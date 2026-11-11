#include "MultiFrameMerge.h"
#include "MultiFrameBandPlan.h"
#include "../NativeBindings.h"
#include "../NativeDiagnostics.h"
#include "multiframe_merge_stage0_compute_spv.h"
#include "multiframe_merge_stage1_compute_spv.h"
#include "multiframe_merge_stage2_compute_spv.h"
#include "multiframe_merge_stage3_compute_spv.h"
#include "multiframe_merge_stage4_compute_spv.h"
#include "multiframe_merge_stage5_compute_spv.h"
#include "multiframe_merge_stage6_compute_spv.h"
#include "multiframe_merge_stage7_compute_spv.h"
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <stdexcept>

namespace multiframe {
namespace {

constexpr int kTile = 16;
constexpr int kBindings = 10;
constexpr int kStages = 8;
// Largest whole-frame translation search radius at the top pyramid level.
constexpr int kGlobalRadius = 32;
constexpr int kGlobalEntries = (kGlobalRadius * 2 + 1) * (kGlobalRadius * 2 + 1);
// Each spectrum buffer of a band is kept to this size so the frequency accumulator and the reference fit.
constexpr VkDeviceSize kSpectrumBudget = 160u << 20;
// Merge buffers use at most this share of the free memory the driver reports.
constexpr uint64_t kBudgetSharePercent = 60;
// Without a reported budget, the merge buffers use at most this fraction of the memory heap.
constexpr uint64_t kHeapShare = 8;
constexpr VkMemoryPropertyFlags kHostMemory = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
// Target duration of one GPU submission. Short submissions leave gaps in which the viewfinder renders.
constexpr double kChunkTargetMs = 4.0;

enum Stage { kGray = 0, kDownsample = 1, kAlign = 2, kGlobal = 3, kSpectrum = 4, kMerge = 5, kInverse = 6, kFinalize = 7 };

struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkDeviceSize capacity = 0;
};

struct Push {
    int32_t frame[4];
    int32_t mosaic[4];
    int32_t level[4];
    int32_t tiles[4];
    int32_t coarse[4];
    float black[4];
    float noise[4];
    float scale[4];
};
static_assert(sizeof(Push) == 128, "Push constants must fit the guaranteed Vulkan minimum");

struct Job {
    int stage;
    Push push;
    uint32_t groupsX;
    uint32_t groupsY;
    uint32_t groupsZ = 1;
};

VkDeviceSize align16(VkDeviceSize bytes) { return (bytes + 15u) & ~VkDeviceSize{15u}; }

// The driver could not provide memory for a buffer. The merge retries with smaller bands.
struct OutOfMemory : std::runtime_error { using std::runtime_error::runtime_error; };

bool outOfMemory(VkResult result) {
    return result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY || result == VK_ERROR_MEMORY_MAP_FAILED;
}

class Gpu {
public:
    std::mutex mutex;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptors = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    std::array<VkPipeline, kStages> pipelines{};
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memory{};
    VkPhysicalDeviceProperties properties{};
    std::array<Buffer, kBindings> buffers{};
    PFN_vkCmdDispatchBase dispatchBase = nullptr;
    bool lowPriority = false;
    VkResult failure = VK_SUCCESS;
    int lastStage = -1;
    // Workgroups per submission for each stage, adapted to the measured duration.
    std::array<uint64_t, kStages> chunkGroups{};
    std::array<double, kStages> stageMs{};
    int submissions = 0;

    Gpu() { initialize(); }
    ~Gpu() { release(); }

    void check(VkResult result, const char* operation) {
        if (result == VK_SUCCESS) result = failure;
        if (result == VK_SUCCESS) return;
        failure = result;
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "MultiFrameMerge", "%s failed result=%d stage=%d gpu=%s driver=%u",
            operation, result, lastStage, properties.deviceName, properties.driverVersion);
        throw std::runtime_error(std::string(operation) + " failed");
    }

    void prepare() {
        if (device && failure == VK_SUCCESS) return;
        recordNativeDiagnostic(ANDROID_LOG_WARN, "MultiFrameMerge", "Recreating merge device after GPU failure");
        release();
        failure = VK_SUCCESS;
        initialize();
    }

    bool hasExtension(const char* name) {
        uint32_t count = 0;
        if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr) != VK_SUCCESS) return false;
        std::vector<VkExtensionProperties> extensions(count);
        if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
        for (const auto& extension : extensions) if (std::strcmp(extension.extensionName, name) == 0) return true;
        return false;
    }

    void initialize() {
        try {
            VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
            app.pApplicationName = "MultiFrameMerge";
            app.apiVersion = VK_API_VERSION_1_1;
            VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
            create.pApplicationInfo = &app;
            check(vkCreateInstance(&create, nullptr, &instance), "vkCreateInstance");
            uint32_t count = 0;
            check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "vkEnumeratePhysicalDevices");
            std::vector<VkPhysicalDevice> devices(count);
            check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "vkEnumeratePhysicalDevices");
            uint32_t family = UINT32_MAX;
            for (auto candidate : devices) {
                uint32_t size = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, &size, nullptr);
                std::vector<VkQueueFamilyProperties> families(size);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, &size, families.data());
                for (uint32_t i = 0; i < size; ++i) {
                    if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { physical = candidate; family = i; break; }
                }
                if (physical) break;
            }
            if (!physical) throw std::runtime_error("No compute device");
            vkGetPhysicalDeviceProperties(physical, &properties);
            vkGetPhysicalDeviceMemoryProperties(physical, &memory);
            if (properties.limits.maxPushConstantsSize < sizeof(Push)) throw std::runtime_error("Push constants too small");
            const float priority = .5f;
            VkDeviceQueueCreateInfo queues{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            queues.queueFamilyIndex = family;
            queues.queueCount = 1;
            queues.pQueuePriorities = &priority;
            // The merge yields to the viewfinder: its queue asks for low global priority where the driver offers it.
            VkDeviceQueueGlobalPriorityCreateInfoEXT globalPriority{VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT};
            globalPriority.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT;
            const char* extensions[] = {VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME};
            VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
            deviceInfo.queueCreateInfoCount = 1;
            deviceInfo.pQueueCreateInfos = &queues;
            lowPriority = false;
            if (hasExtension(VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME)) {
                queues.pNext = &globalPriority;
                deviceInfo.enabledExtensionCount = 1;
                deviceInfo.ppEnabledExtensionNames = extensions;
                lowPriority = vkCreateDevice(physical, &deviceInfo, nullptr, &device) == VK_SUCCESS;
                if (!lowPriority) {
                    device = VK_NULL_HANDLE;
                    queues.pNext = nullptr;
                    deviceInfo.enabledExtensionCount = 0;
                    deviceInfo.ppEnabledExtensionNames = nullptr;
                }
            }
            if (!device) check(vkCreateDevice(physical, &deviceInfo, nullptr, &device), "vkCreateDevice");
            vkGetDeviceQueue(device, family, 0, &queue);
            dispatchBase = reinterpret_cast<PFN_vkCmdDispatchBase>(vkGetDeviceProcAddr(device, "vkCmdDispatchBase"));
            if (!dispatchBase) dispatchBase = reinterpret_cast<PFN_vkCmdDispatchBase>(vkGetDeviceProcAddr(device, "vkCmdDispatchBaseKHR"));
            VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
            poolInfo.queueFamilyIndex = family;
            poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
            check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool), "vkCreateCommandPool");
            VkCommandBufferAllocateInfo commands{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            commands.commandPool = pool;
            commands.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            commands.commandBufferCount = 1;
            check(vkAllocateCommandBuffers(device, &commands, &command), "vkAllocateCommandBuffers");
            VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            check(vkCreateFence(device, &fenceInfo, nullptr, &fence), "vkCreateFence");
            std::array<VkDescriptorSetLayoutBinding, kBindings> bindings{};
            for (uint32_t i = 0; i < bindings.size(); ++i) bindings[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
            VkDescriptorSetLayoutCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            descriptorInfo.bindingCount = bindings.size();
            descriptorInfo.pBindings = bindings.data();
            check(vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptors), "vkCreateDescriptorSetLayout");
            VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
            VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            layoutInfo.setLayoutCount = 1;
            layoutInfo.pSetLayouts = &descriptors;
            layoutInfo.pushConstantRangeCount = 1;
            layoutInfo.pPushConstantRanges = &push;
            check(vkCreatePipelineLayout(device, &layoutInfo, nullptr, &layout), "vkCreatePipelineLayout");
            VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kBindings}};
            VkDescriptorPoolCreateInfo descriptorPoolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            descriptorPoolInfo.maxSets = 1;
            descriptorPoolInfo.poolSizeCount = 1;
            descriptorPoolInfo.pPoolSizes = sizes;
            check(vkCreateDescriptorPool(device, &descriptorPoolInfo, nullptr, &descriptorPool), "vkCreateDescriptorPool");
            VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocate.descriptorPool = descriptorPool;
            allocate.descriptorSetCount = 1;
            allocate.pSetLayouts = &descriptors;
            check(vkAllocateDescriptorSets(device, &allocate, &set), "vkAllocateDescriptorSets");
            chunkGroups = {4096, 4096, 512, 256, 256, 256, 256, 4096};
        } catch (...) {
            release();
            throw;
        }
    }

    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
        for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & flags) == flags) return i;
        }
        throw std::runtime_error("No host visible memory");
    }

    void destroy(Buffer& buffer) {
        if (buffer.mapped) vkUnmapMemory(device, buffer.memory);
        if (buffer.buffer) vkDestroyBuffer(device, buffer.buffer, nullptr);
        if (buffer.memory) vkFreeMemory(device, buffer.memory, nullptr);
        buffer = {};
    }

    void ensure(Buffer& buffer, VkDeviceSize bytes) {
        bytes = align16(std::max<VkDeviceSize>(bytes, 16));
        if (bytes <= buffer.capacity) return;
        if (bytes > properties.limits.maxStorageBufferRange) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "MultiFrameMerge", "Buffer exceeds device limit bytes=%llu limit=%u",
                static_cast<unsigned long long>(bytes), properties.limits.maxStorageBufferRange);
            throw std::runtime_error("Frame too large for this GPU");
        }
        destroy(buffer);
        // Running out of memory is not a device failure: the caller releases everything and plans smaller bands.
        auto allocated = [&](VkResult result, const char* operation) {
            if (outOfMemory(result)) {
                destroy(buffer);
                throw OutOfMemory(operation);
            }
            check(result, operation);
        };
        VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        info.size = bytes;
        info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        allocated(vkCreateBuffer(device, &info, nullptr, &buffer.buffer), "vkCreateBuffer");
        VkMemoryRequirements required{};
        vkGetBufferMemoryRequirements(device, buffer.buffer, &required);
        VkMemoryAllocateInfo allocate{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocate.allocationSize = required.size;
        allocate.memoryTypeIndex = memoryType(required.memoryTypeBits, kHostMemory);
        allocated(vkAllocateMemory(device, &allocate, nullptr, &buffer.memory), "vkAllocateMemory");
        check(vkBindBufferMemory(device, buffer.buffer, buffer.memory, 0), "vkBindBufferMemory");
        allocated(vkMapMemory(device, buffer.memory, 0, bytes, 0, &buffer.mapped), "vkMapMemory");
        buffer.capacity = bytes;
    }

    /**
     * Limits for the merge buffers on this GPU: the per-buffer and per-allocation maximums, and a share of
     * the memory the driver reports as free, or of the memory heap when it does not report a budget.
     */
    MergeMemoryLimits memoryLimits(const char*& source) {
        MergeMemoryLimits limits;
        limits.buffer = properties.limits.maxStorageBufferRange;
        const bool vulkan11 = properties.apiVersion >= VK_API_VERSION_1_1;
        auto properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));
        if (vulkan11 && properties2) {
            VkPhysicalDeviceMaintenance3Properties maintenance{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_3_PROPERTIES};
            VkPhysicalDeviceProperties2 all{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
            all.pNext = &maintenance;
            properties2(physical, &all);
            if (maintenance.maxMemoryAllocationSize > 0) limits.buffer = std::min<uint64_t>(limits.buffer, maintenance.maxMemoryAllocationSize);
        }
        limits.spectrum = std::min<uint64_t>(kSpectrumBudget, limits.buffer);
        uint32_t heap = UINT32_MAX;
        for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
            if ((memory.memoryTypes[i].propertyFlags & kHostMemory) == kHostMemory) { heap = memory.memoryTypes[i].heapIndex; break; }
        }
        if (heap == UINT32_MAX) throw std::runtime_error("No host visible memory");
        limits.total = memory.memoryHeaps[heap].size / kHeapShare;
        source = "heap";
        auto memory2 = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties2"));
        if (vulkan11 && memory2 && hasExtension(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME)) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT budget{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
            VkPhysicalDeviceMemoryProperties2 all{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2};
            all.pNext = &budget;
            memory2(physical, &all);
            if (budget.heapBudget[heap] > 0) {
                const uint64_t available = budget.heapBudget[heap] > budget.heapUsage[heap] ? budget.heapBudget[heap] - budget.heapUsage[heap] : 0;
                limits.total = available / 100 * kBudgetSharePercent;
                source = "budget";
            }
        }
        return limits;
    }

    void releaseBuffers() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        for (auto& buffer : buffers) destroy(buffer);
    }

    void preparePipeline(int stage) {
        if (pipelines[stage]) return;
        const uint32_t* code[] = {kMultiFrameMerge0Spirv.data(), kMultiFrameMerge1Spirv.data(), kMultiFrameMerge2Spirv.data(),
            kMultiFrameMerge3Spirv.data(), kMultiFrameMerge4Spirv.data(), kMultiFrameMerge5Spirv.data(),
            kMultiFrameMerge6Spirv.data(), kMultiFrameMerge7Spirv.data()};
        const size_t sizes[] = {sizeof(kMultiFrameMerge0Spirv), sizeof(kMultiFrameMerge1Spirv), sizeof(kMultiFrameMerge2Spirv),
            sizeof(kMultiFrameMerge3Spirv), sizeof(kMultiFrameMerge4Spirv), sizeof(kMultiFrameMerge5Spirv),
            sizeof(kMultiFrameMerge6Spirv), sizeof(kMultiFrameMerge7Spirv)};
        VkShaderModule module = VK_NULL_HANDLE;
        VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        moduleInfo.codeSize = sizes[stage];
        moduleInfo.pCode = code[stage];
        check(vkCreateShaderModule(device, &moduleInfo, nullptr, &module), "vkCreateShaderModule");
        VkComputePipelineCreateInfo info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        // Dispatches are submitted in pieces with a base workgroup offset.
        info.flags = dispatchBase ? VK_PIPELINE_CREATE_DISPATCH_BASE_BIT : 0;
        info.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, module, "main", nullptr};
        info.layout = layout;
        const auto result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &pipelines[stage]);
        vkDestroyShaderModule(device, module, nullptr);
        check(result, "vkCreateComputePipelines");
    }

    void bindBuffers() {
        std::array<VkDescriptorBufferInfo, kBindings> infos{};
        std::array<VkWriteDescriptorSet, kBindings> writes{};
        for (uint32_t i = 0; i < kBindings; ++i) {
            infos[i] = {buffers[i].buffer, 0, buffers[i].capacity};
            auto& write = writes[i];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = set;
            write.dstBinding = i;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            write.pBufferInfo = &infos[i];
        }
        vkUpdateDescriptorSets(device, writes.size(), writes.data(), 0, nullptr);
    }

    // Submits part of one dispatch and waits for it.
    void submit(const Job& job, uint32_t baseX, uint32_t baseY, uint32_t countX, uint32_t countY) {
        lastStage = job.stage;
        check(vkResetCommandBuffer(command, 0), "vkResetCommandBuffer");
        check(vkResetFences(device, 1, &fence), "vkResetFences");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(command, &begin), "vkBeginCommandBuffer");
        VkMemoryBarrier input{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        input.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        input.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &input, 0, nullptr, 0, nullptr);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[job.stage]);
        vkCmdPushConstants(command, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push), &job.push);
        if (baseX == 0 && baseY == 0) vkCmdDispatch(command, countX, countY, job.groupsZ);
        else dispatchBase(command, baseX, baseY, 0, countX, countY, job.groupsZ);
        VkMemoryBarrier output{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        output.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        output.dstAccessMask = VK_ACCESS_HOST_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 1, &output, 0, nullptr, 0, nullptr);
        check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
        VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &command;
        check(vkQueueSubmit(queue, 1, &submitInfo, fence), "vkQueueSubmit");
        check(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX), "vkWaitForFences");
        ++submissions;
    }

    // Runs the jobs in order, each split into submissions of a few milliseconds so the viewfinder
    // keeps rendering between them. The split size per stage follows the measured duration.
    void run(const std::vector<Job>& jobs) {
        for (const auto& job : jobs) preparePipeline(job.stage);
        for (const auto& job : jobs) {
            const bool splitY = job.groupsY > 1;
            const uint32_t lines = splitY ? job.groupsY : job.groupsX;
            const uint64_t perLine = uint64_t(splitY ? job.groupsX : 1) * job.groupsZ;
            uint64_t& budget = chunkGroups[job.stage];
            uint32_t start = 0;
            while (start < lines) {
                uint32_t count = dispatchBase ? uint32_t(std::max<uint64_t>(1, budget / std::max<uint64_t>(perLine, 1))) : lines;
                count = std::min(count, lines - start);
                const auto began = std::chrono::steady_clock::now();
                if (splitY) submit(job, 0, start, job.groupsX, count);
                else submit(job, start, 0, count, job.groupsY);
                const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - began).count();
                stageMs[job.stage] += ms;
                if (ms > 0.1) {
                    const double scale = std::min(std::max(kChunkTargetMs / ms, 0.5), 2.0);
                    budget = std::min<uint64_t>(std::max<uint64_t>(uint64_t(double(count) * double(perLine) * scale), 64), uint64_t(1) << 24);
                }
                start += count;
            }
        }
    }

    void release() {
        if (device) {
            vkDeviceWaitIdle(device);
            for (auto& buffer : buffers) destroy(buffer);
            if (descriptorPool) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            for (auto pipeline : pipelines) if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
            if (layout) vkDestroyPipelineLayout(device, layout, nullptr);
            if (descriptors) vkDestroyDescriptorSetLayout(device, descriptors, nullptr);
            if (fence) vkDestroyFence(device, fence, nullptr);
            if (pool) vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyDevice(device, nullptr);
            device = VK_NULL_HANDLE;
        }
        if (instance) vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
        physical = VK_NULL_HANDLE;
        queue = VK_NULL_HANDLE;
        pool = VK_NULL_HANDLE;
        command = VK_NULL_HANDLE;
        fence = VK_NULL_HANDLE;
        descriptors = VK_NULL_HANDLE;
        layout = VK_NULL_HANDLE;
        pipelines = {};
        descriptorPool = VK_NULL_HANDLE;
        set = VK_NULL_HANDLE;
        dispatchBase = nullptr;
    }
};

Gpu& gpu() {
    static Gpu instance;
    return instance;
}

struct Level {
    int width, height;
    uint32_t grayOffset;   // floats
    uint32_t alignOffset;  // vec2 entries
    int tilesX, tilesY;
    int radius;
};

uint32_t groups(int size, int local) { return uint32_t((size + local - 1) / local); }

Push basePush(const Layout& layout) {
    Push push{};
    push.frame[0] = layout.width;
    push.frame[1] = layout.height;
    push.frame[2] = layout.rowBytes;
    push.frame[3] = layout.format;
    push.mosaic[0] = layout.blockSize * 2;
    push.mosaic[1] = layout.sourcePattern;
    push.mosaic[2] = layout.phaseX;
    push.mosaic[3] = layout.phaseY;
    for (int i = 0; i < 4; ++i) push.black[i] = layout.black[i];
    push.noise[0] = layout.white;
    push.noise[1] = layout.noiseS;
    push.noise[2] = layout.noiseO;
    push.noise[3] = layout.motion;
    push.scale[0] = 1.f;
    return push;
}

std::vector<Level> buildLevels(const Layout& layout) {
    std::vector<Level> levels;
    const int period = layout.blockSize * 2;
    int width = layout.width / period, height = layout.height / period;
    uint32_t grayOffset = 0, alignOffset = 0;
    while (levels.size() < 5u && width >= 32 && height >= 32) {
        Level level{};
        level.width = width;
        level.height = height;
        level.grayOffset = grayOffset;
        level.alignOffset = alignOffset;
        level.tilesX = (width + kTile - 1) / kTile;
        level.tilesY = (height + kTile - 1) / kTile;
        levels.push_back(level);
        grayOffset += uint32_t(align16(VkDeviceSize(width) * height * sizeof(float)) / sizeof(float));
        alignOffset += uint32_t(level.tilesX * level.tilesY);
        width /= 2;
        height /= 2;
    }
    for (size_t i = 0; i < levels.size(); ++i) {
        const bool top = i + 1 == levels.size();
        levels[i].radius = top ? 8 : i >= 2 ? 4 : 3;
    }
    return levels;
}

// Merge tiles of one band: overlapping by half, starting half a tile outside the region.
int mergeTiles(int planePixels) { return planePixels >= kTile ? (planePixels + kTile / 2 - 1) / (kTile / 2) + 1 : 0; }

}  // namespace

Result merge(const Layout& layout, const std::vector<Frame>& frames, uint16_t* output) {
    std::vector<double> frameWeights;
    std::vector<float> frameAlignment;
    std::vector<int> frameShiftX, frameShiftY;
    std::vector<float> frameDistinct;
    int skipped = 0;
    int submissions = 0;
    bool lowPriority = false;
    std::array<double, kStages> stageMs{};
    Result result;
    const auto started = std::chrono::steady_clock::now();
    const int period = layout.blockSize * 2;
    if (frames.size() < 2 || !output || layout.width < 64 || layout.height < 64 || (layout.width & 1) ||
        layout.blockSize < 1 || layout.blockSize > 4 || layout.format < 0 || layout.format > 2 ||
        layout.rowBytes <= 0 || layout.height % period != 0) {
        result.error = "Invalid merge input";
        return result;
    }
    auto& engine = gpu();
    std::lock_guard<std::mutex> lock(engine.mutex);
    int bands = 0;
    try {
        engine.prepare();
        engine.submissions = 0;
        engine.stageMs = {};
        lowPriority = engine.lowPriority;
        // Frames are merged in overlapping bands sized to this GPU's buffer limits and free memory.
        // Only the interior rows of a band are kept.
        const int unit = 64 * period;
        const int planeWidth = layout.width / period;
        auto sizes = [&](int bandRows, int interiorRows) {
            std::array<uint64_t, kMergeBuffers> bytes{};
            Layout band = layout;
            band.height = bandRows;
            const auto levels = buildLevels(band);
            if (levels.empty() || mergeTiles(interiorRows / period) <= 0) return bytes;
            VkDeviceSize grayFloats = 0, alignEntries = 0;
            for (const auto& level : levels) {
                grayFloats = std::max<VkDeviceSize>(grayFloats, level.grayOffset + align16(VkDeviceSize(level.width) * level.height * sizeof(float)) / sizeof(float));
                alignEntries = std::max<VkDeviceSize>(alignEntries, level.alignOffset + VkDeviceSize(level.tilesX) * level.tilesY);
            }
            // Finest-level tile residuals and the global search candidates live behind the offset pyramid.
            alignEntries += VkDeviceSize(levels.front().tilesX) * levels.front().tilesY + kGlobalEntries;
            const VkDeviceSize pixels = VkDeviceSize(layout.width) * bandRows;
            const VkDeviceSize spectrumEntries = VkDeviceSize(period) * period * mergeTiles(planeWidth) *
                mergeTiles(interiorRows / period) * kTile * kTile;
            const VkDeviceSize values[kMergeBuffers] = {
                VkDeviceSize(layout.rowBytes) * bandRows, VkDeviceSize(layout.rowBytes) * bandRows,
                grayFloats * sizeof(float), grayFloats * sizeof(float), alignEntries * 2 * sizeof(float),
                spectrumEntries * 2 * sizeof(float), spectrumEntries * 2 * sizeof(float),
                pixels * sizeof(uint16_t), 64, pixels * sizeof(float)};
            for (int i = 0; i < kMergeBuffers; ++i) bytes[size_t(i)] = align16(std::max<VkDeviceSize>(values[i], 16));
            return bytes;
        };
        const char* memorySource = "";
        MergeMemoryLimits limits = engine.memoryLimits(memorySource);
        const uint64_t reported = limits.total;
        BandPlan plan;
        for (int attempt = 0;; ++attempt) {
            plan = planBands(layout.height, unit, 192, limits, sizes);
            if (!plan.valid()) throw std::runtime_error("Frame too large for this GPU");
            try {
                for (int i = 0; i < kMergeBuffers; ++i) engine.ensure(engine.buffers[size_t(i)], plan.bytes[size_t(i)]);
                break;
            } catch (const OutOfMemory& error) {
                engine.releaseBuffers();
                recordNativeDiagnostic(ANDROID_LOG_WARN, "MultiFrameMerge", "%s ran out of memory at total_mb=%.0f attempt=%d, using smaller bands",
                    error.what(), plan.total / 1048576.0, attempt);
                limits.total = plan.total / 2;
            }
        }
        const int interior = plan.interior;
        const int overlap = plan.overlap;
        const VkDeviceSize bandPixels = VkDeviceSize(layout.width) * plan.bandRows;
        Layout band = layout;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "MultiFrameMerge",
            "plan size=%dx%d band_rows=%d interior=%d overlap=%d total_mb=%.0f limit_mb=%.0f source=%s buffer_limit_mb=%.0f",
            layout.width, layout.height, plan.bandRows, interior, overlap, plan.total / 1048576.0, reported / 1048576.0,
            memorySource, limits.buffer / 1048576.0);
        engine.bindBuffers();
        std::memset(engine.buffers[8].mapped, 0, 64);
        frameWeights.assign(frames.size(), 0.0);
        frameAlignment.assign(frames.size(), 0.f);
        frameShiftX.assign(frames.size(), 0);
        frameShiftY.assign(frames.size(), 0);
        frameDistinct.assign(frames.size(), 0.f);
        const float motion = std::min(std::max(layout.motion, 0.f), 1.f);
        // A tile whose residual exceeds this multiple of the residual expected from noise and
        // sub-pixel error did not align. Higher motion sensitivity tolerates less.
        const float tileLimit = 5.f - 2.5f * motion;
        // Coefficients differing by less than this multiple of the noise variance are averaged.
        const float noiseFactor = 16.f - 12.f * motion;
        std::vector<float> tileRatios;

        for (int interiorStart = 0; interiorStart < layout.height; interiorStart += std::max(interior, 1)) {
            const int interiorEnd = overlap == 0 ? layout.height : std::min(layout.height, interiorStart + interior);
            const int y0 = std::max(0, interiorStart - overlap);
            const int y1 = std::min(layout.height, interiorEnd + overlap);
            band.height = y1 - y0;
            const auto levels = buildLevels(band);
            if (levels.empty()) throw std::runtime_error("Frame too small to align");
            const Level& base = levels.front();
            const Level& top = levels.back();
            const int baseTiles = base.tilesX * base.tilesY;
            const uint32_t residualOffset = top.alignOffset + uint32_t(top.tilesX * top.tilesY);
            const uint32_t globalOffset = residualOffset + uint32_t(baseTiles);
            const int globalRadius = std::clamp(std::min(top.width, top.height) / 4, 4, kGlobalRadius);
            const int globalSpan = globalRadius * 2 + 1;
            const int topScale = period << (levels.size() - 1);
            const VkDeviceSize packedBytes = VkDeviceSize(layout.rowBytes) * band.height;
            const size_t packedOffset = size_t(layout.rowBytes) * size_t(y0);
            // Merge region: the interior rows of this band, in plane pixels.
            const int regionY = (interiorStart - y0) / period;
            const int regionRows = (interiorEnd - interiorStart) / period;
            const int regionTilesX = mergeTiles(base.width);
            const int regionTilesY = mergeTiles(regionRows);
            if (regionTilesX <= 0 || regionTilesY <= 0) throw std::runtime_error("Frame too small to align");
            ++bands;
            // Frames are ordered by distance from the reference, so the previous two shifts predict the next one.
            int previousX = 0, previousY = 0, earlierX = 0, earlierY = 0;
            auto roundDivide = [](int value, int divisor) {
                return value >= 0 ? (value + divisor / 2) / divisor : -((-value + divisor / 2) / divisor);
            };

            auto pyramid = [&](bool alternate, float exposureScale, std::vector<Job>& jobs) {
                Push push = basePush(band);
                push.level[0] = 0;
                push.level[1] = int32_t(base.grayOffset);
                push.level[2] = base.width;
                push.level[3] = base.height;
                push.coarse[2] = alternate ? 1 : 0;
                push.scale[0] = exposureScale;
                jobs.push_back({kGray, push, groups(base.width, 16), groups(base.height, 16)});
                for (size_t i = 1; i < levels.size(); ++i) {
                    Push down = basePush(band);
                    down.level[0] = int32_t(levels[i - 1].grayOffset);
                    down.level[1] = int32_t(levels[i].grayOffset);
                    down.level[2] = levels[i - 1].width;
                    down.level[3] = levels[i - 1].height;
                    down.coarse[0] = levels[i].width;
                    down.coarse[1] = levels[i].height;
                    down.coarse[2] = alternate ? 1 : 0;
                    jobs.push_back({kDownsample, down, groups(levels[i].width, 16), groups(levels[i].height, 16)});
                }
            };
            auto mergePush = [&](float exposureScale) {
                Push push = basePush(band);
                push.level[0] = 0;
                push.level[1] = regionY;
                push.level[2] = base.width;
                push.level[3] = base.height;
                push.tiles[0] = base.tilesX;
                push.tiles[1] = base.tilesY;
                push.tiles[2] = int32_t(frames.size());
                push.coarse[2] = regionTilesX;
                push.coarse[3] = regionTilesY;
                push.scale[0] = exposureScale;
                push.scale[1] = float(residualOffset);
                push.scale[2] = tileLimit;
                push.scale[3] = noiseFactor;
                return push;
            };

            // Frames that contribute nothing to this band count as copies of the reference.
            int standIns = 0;
            // Reference band: gray pyramid and the tile spectra that seed the accumulator.
            {
                std::memcpy(engine.buffers[0].mapped, frames[0].pixels + packedOffset, size_t(packedBytes));
                std::vector<Job> jobs;
                pyramid(false, 1.f, jobs);
                Job spectrum{kSpectrum, mergePush(1.f), uint32_t((regionTilesX + 3) / 4), uint32_t(regionTilesY)};
                spectrum.groupsZ = uint32_t(period * period);
                jobs.push_back(spectrum);
                std::memset(engine.buffers[8].mapped, 0, 64);
                engine.run(jobs);
            }

            for (size_t f = 1; f < frames.size(); ++f) {
                const float exposureScale = frames[f].exposureScale > 0.f ? frames[f].exposureScale : 1.f;
                std::memcpy(engine.buffers[1].mapped, frames[f].pixels + packedOffset, size_t(packedBytes));
                std::vector<Job> jobs;
                pyramid(true, exposureScale, jobs);
                const int predictedX = 2 * previousX - earlierX;
                const int predictedY = 2 * previousY - earlierY;
                const int32_t centerX = roundDivide(predictedX, topScale);
                const int32_t centerY = roundDivide(predictedY, topScale);
                {
                    Push global = basePush(band);
                    global.level[0] = int32_t(top.grayOffset);
                    global.level[1] = int32_t(globalOffset);
                    global.level[2] = top.width;
                    global.level[3] = top.height;
                    global.tiles[2] = globalRadius;
                    global.coarse[0] = centerX;
                    global.coarse[1] = centerY;
                    jobs.push_back({kGlobal, global, uint32_t(globalSpan * globalSpan), 1});
                }
                engine.run(jobs);
                jobs.clear();
                int32_t globalX = 0, globalY = 0;
                bool located = false;
                {
                    const float* candidates = static_cast<const float*>(engine.buffers[4].mapped) + size_t(globalOffset) * 2;
                    float best = -1.f;
                    double sum = 0.0;
                    int usable = 0;
                    for (int i = 0; i < globalSpan * globalSpan; ++i) {
                        const float distance = candidates[size_t(i) * 2];
                        if (!(distance < 1e29f)) continue;
                        sum += distance;
                        ++usable;
                        if (best < 0.f || distance < best) {
                            best = distance;
                            globalX = centerX + i % globalSpan - globalRadius;
                            globalY = centerY + i / globalSpan - globalRadius;
                        }
                    }
                    located = usable > 0;
                    if (located) frameDistinct[f] = float(sum / usable / std::max(best, 1e-9f));
                    earlierX = previousX;
                    earlierY = previousY;
                    previousX = located ? globalX * topScale : predictedX;
                    previousY = located ? globalY * topScale : predictedY;
                    frameShiftX[f] = previousX;
                    frameShiftY[f] = previousY;
                }
                const bool mergeThisFrame = [&]() {
                    if (!located) {
                        // Less than half of the frame overlaps the reference: nothing worth merging.
                        frameAlignment[f] = std::max(frameAlignment[f], 99.f);
                        return false;
                    }
                    for (size_t i = levels.size(); i-- > 0;) {
                        const Level& level = levels[i];
                        Push push = basePush(band);
                        push.scale[1] = float(residualOffset);
                        push.level[0] = int32_t(level.grayOffset);
                        push.level[1] = int32_t(level.alignOffset);
                        push.level[2] = level.width;
                        push.level[3] = level.height;
                        push.tiles[0] = level.tilesX;
                        push.tiles[1] = level.tilesY;
                        push.tiles[2] = level.radius;
                        const bool top = i + 1 == levels.size();
                        push.tiles[3] = top ? -1 : int32_t(levels[i + 1].alignOffset);
                        push.coarse[0] = top ? globalX : levels[i + 1].tilesX;
                        push.coarse[1] = top ? globalY : levels[i + 1].tilesY;
                        push.coarse[2] = 1;
                        push.coarse[3] = i == 0 ? 3 : 0;  // L1 distance and subpixel refinement at the finest level
                        jobs.push_back({kAlign, push, uint32_t(level.tilesX), uint32_t(level.tilesY)});
                    }
                    engine.run(jobs);
                    jobs.clear();
                    // A frame in which the typical tile failed to align contributes nothing.
                    const float* residuals = static_cast<const float*>(engine.buffers[4].mapped) + size_t(residualOffset) * 2;
                    tileRatios.resize(size_t(baseTiles));
                    for (int t = 0; t < baseTiles; ++t) {
                        tileRatios[size_t(t)] = residuals[size_t(t) * 2] / std::max(residuals[size_t(t) * 2 + 1], 1e-7f);
                    }
                    std::nth_element(tileRatios.begin(), tileRatios.begin() + baseTiles / 2, tileRatios.end());
                    const float typical = tileRatios[size_t(baseTiles / 2)];
                    frameAlignment[f] = std::max(frameAlignment[f], typical);
                    return typical <= tileLimit;
                }();
                if (!mergeThisFrame) {
                    if (interiorStart == 0) ++skipped;
                    // The frame still counts in the normalization: its tiles take the reference.
                    ++standIns;
                    continue;
                }
                Job job{kMerge, mergePush(exposureScale), uint32_t((regionTilesX + 3) / 4), uint32_t(regionTilesY)};
                job.groupsZ = uint32_t(period * period);
                jobs.push_back(job);
                engine.run(jobs);
                uint32_t frameStats = 0;
                std::memcpy(&frameStats, static_cast<const uint8_t*>(engine.buffers[8].mapped) + sizeof(uint32_t), sizeof(frameStats));
                std::memset(static_cast<uint8_t*>(engine.buffers[8].mapped) + sizeof(uint32_t), 0, sizeof(uint32_t));
                frameWeights[f] += double(frameStats) * 0.5;
            }

            {
                // Back to the spatial domain, then to 16-bit RAW. Tiles of one parity pass never overlap.
                std::memset(engine.buffers[9].mapped, 0, size_t(bandPixels) * sizeof(float));
                std::vector<Job> jobs;
                for (int parity = 0; parity < 4; ++parity) {
                    Push push = mergePush(float(standIns));
                    push.coarse[0] = parity & 1;
                    push.coarse[1] = parity >> 1;
                    const int countX = (regionTilesX - (parity & 1) + 1) / 2;
                    const int countY = (regionTilesY - (parity >> 1) + 1) / 2;
                    if (countX <= 0 || countY <= 0) continue;
                    Job job{kInverse, push, uint32_t((countX + 3) / 4), uint32_t(countY)};
                    job.groupsZ = uint32_t(period * period);
                    jobs.push_back(job);
                }
                Push final = basePush(band);
                jobs.push_back({kFinalize, final, groups(layout.width / 2, 16), groups(band.height, 16)});
                engine.run(jobs);
            }
            const int keepStart = interiorStart - y0;
            const int keepRows = interiorEnd - interiorStart;
            std::memcpy(output + size_t(interiorStart) * layout.width,
                static_cast<const uint16_t*>(engine.buffers[7].mapped) + size_t(keepStart) * layout.width,
                size_t(keepRows) * layout.width * sizeof(uint16_t));
            if (overlap == 0) break;
        }
        double merged = 0.0;
        for (size_t f = 1; f < frameWeights.size(); ++f) merged += frameWeights[f];
        result.meanWeight = 1.f + float(merged / (double(layout.width) * layout.height));
        if (!(result.meanWeight > 0.f)) result.meanWeight = 1.f;
        submissions = engine.submissions;
        stageMs = engine.stageMs;
    } catch (const std::exception& error) {
        result.meanWeight = 0.f;
        result.error = error.what();
        recordNativeDiagnostic(ANDROID_LOG_WARN, "MultiFrameMerge", "Merge failed: %s", error.what());
    }
    // Frame buffers are large; release them between shots.
    try { engine.releaseBuffers(); } catch (...) {}
    result.elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
    std::string detail;
    {
        char item[48];
        const double pixels = double(layout.width) * layout.height;
        for (size_t f = 1; f < frames.size() && f < frameWeights.size(); ++f) {
            std::snprintf(item, sizeof(item), "%s%.2f", f > 1 ? "," : " weights=", frameWeights[f] / pixels);
            detail += item;
        }
        for (size_t f = 1; f < frames.size() && f < frameAlignment.size(); ++f) {
            std::snprintf(item, sizeof(item), "%s%.2f", f > 1 ? "," : " align=", frameAlignment[f]);
            detail += item;
        }
        for (size_t f = 1; f < frames.size() && f < frameShiftX.size(); ++f) {
            std::snprintf(item, sizeof(item), "%s%d:%d", f > 1 ? "," : " shift=", frameShiftX[f], frameShiftY[f]);
            detail += item;
        }
        for (size_t f = 1; f < frames.size() && f < frameDistinct.size(); ++f) {
            std::snprintf(item, sizeof(item), "%s%.2f", f > 1 ? "," : " distinct=", frameDistinct[f]);
            detail += item;
        }
        std::snprintf(item, sizeof(item), " skipped=%d submissions=%d low_priority=%d", skipped, submissions, lowPriority ? 1 : 0);
        detail += item;
        for (int stage = 0; stage < kStages; ++stage) {
            std::snprintf(item, sizeof(item), "%s%.0f", stage > 0 ? "," : " stage_ms=", stageMs[size_t(stage)]);
            detail += item;
        }
        for (size_t f = 1; f < frames.size(); ++f) {
            std::snprintf(item, sizeof(item), "%s%.3f", f > 1 ? "," : " scales=", frames[f].exposureScale);
            detail += item;
        }
        std::snprintf(item, sizeof(item), " noise=%.3g/%.3g motion=%.2f", layout.noiseS, layout.noiseO, layout.motion);
        detail += item;
    }
    recordNativeDiagnostic(ANDROID_LOG_INFO, "MultiFrameMerge", "frames=%zu size=%dx%d format=%d block=%d bands=%d mean_weight=%.2f elapsed_ms=%lld%s%s%s",
        frames.size(), layout.width, layout.height, layout.format, layout.blockSize, bands, result.meanWeight,
        static_cast<long long>(result.elapsedMs), detail.c_str(), result.error.empty() ? "" : " error=", result.error.c_str());
    return result;
}

}  // namespace multiframe

namespace {

jfloat Java_com_unspektrawesome_burst_MultiFrameMerge_merge(JNIEnv* env, jobject, jobjectArray frames, jfloatArray scales,
    jint width, jint height, jint rowBytes, jint format, jint blockSize, jint sourcePattern, jint phaseX, jint phaseY,
    jfloatArray black, jfloat white, jfloat noiseS, jfloat noiseO, jfloat motion, jobject output) {
    const jsize count = frames ? env->GetArrayLength(frames) : 0;
    if (count < 2 || count > 32 || !scales || env->GetArrayLength(scales) != count || !black || env->GetArrayLength(black) != 4 ||
        width < 64 || height < 64 || (width & 1) || rowBytes <= 0 || format < 0 || format > 2 || blockSize < 1 || blockSize > 4 ||
        sourcePattern < 0 || sourcePattern > 3 || !output) {
        return 0.f;
    }
    const int64_t packedBytes = int64_t(rowBytes) * height;
    const int64_t outputBytes = int64_t(width) * height * 2;
    auto* target = static_cast<uint16_t*>(env->GetDirectBufferAddress(output));
    if (!target || env->GetDirectBufferCapacity(output) < outputBytes) return 0.f;
    multiframe::Layout layout;
    layout.width = width;
    layout.height = height;
    layout.rowBytes = rowBytes;
    layout.format = format;
    layout.blockSize = blockSize;
    layout.sourcePattern = sourcePattern;
    layout.phaseX = phaseX;
    layout.phaseY = phaseY;
    env->GetFloatArrayRegion(black, 0, 4, layout.black);
    layout.white = white;
    layout.noiseS = noiseS;
    layout.noiseO = noiseO;
    layout.motion = motion;
    std::vector<float> scaleValues(static_cast<size_t>(count));
    env->GetFloatArrayRegion(scales, 0, count, scaleValues.data());
    std::vector<multiframe::Frame> inputs;
    std::vector<jobject> locals;
    for (jsize i = 0; i < count; ++i) {
        jobject buffer = env->GetObjectArrayElement(frames, i);
        locals.push_back(buffer);
        auto* pixels = buffer ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer)) : nullptr;
        if (!pixels || env->GetDirectBufferCapacity(buffer) < packedBytes) {
            for (auto local : locals) if (local) env->DeleteLocalRef(local);
            return 0.f;
        }
        inputs.push_back({pixels, scaleValues[size_t(i)]});
    }
    const auto result = multiframe::merge(layout, inputs, target);
    for (auto local : locals) if (local) env->DeleteLocalRef(local);
    return result.meanWeight;
}

}  // namespace

bool registerMultiFrameMerge(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"merge", "([Ljava/nio/ByteBuffer;[FIIIIIIII[FFFFFLjava/nio/ByteBuffer;)F", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_MultiFrameMerge_merge)},
    };
    return registerNativeMethods(env, "com/unspektrawesome/burst/MultiFrameMerge", methods);
}
