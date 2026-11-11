#include "PanoramaGpu.h"
#include "PanoramaAlignment.h"
#include "PanoramaExposure.h"
#include "PanoramaBlend.h"
#include "PanoramaCompatibility.h"
#include "../NativeDiagnostics.h"
#include "panorama_family0_compute_spv.h"
#include "panorama_family1_compute_spv.h"
#include "panorama_family2_compute_spv.h"
#include "panorama_family3_compute_spv.h"
#include "panorama_family4_compute_spv.h"
#include "panorama_family5_compute_spv.h"
#include "panorama_family6_compute_spv.h"
#include "panorama_compat_blend_sampling_compute_spv.h"
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <limits>
#include <stdexcept>
#include <string>
#if !defined(NDEBUG)
#include <sys/system_properties.h>
#endif

namespace panorama {
namespace {
struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkDeviceSize capacity = 0;
};
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
    std::array<VkPipeline, 8> pipelines{};
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory imageMemory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    int width = 0, height = 0;
    VkPhysicalDeviceMemoryProperties memory{};
    VkPhysicalDeviceProperties properties{};
    Buffer staging, params, queries, tile, results;
    VkResult failure = VK_SUCCESS;
    std::array<int32_t, 8> lastJob{};
    bool blendSamplingRejected = false;
#if !defined(NDEBUG)
    std::string lastFault;
    std::string lastCorruption;
#endif

    void check(VkResult result, const char* operation) {
        if (result == VK_SUCCESS) result = failure;
        if (result == VK_SUCCESS) return;
        failure = result;
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu",
            "%s failed result=%d job=%d count=%d source=%dx%d gpu=%s driver=%u",
            operation, result, lastJob[0], lastJob[1], width, height, properties.deviceName, properties.driverVersion);
        throw std::runtime_error("Unable to save panorama");
    }

    Gpu() { initialize(); }
    void initialize() {
        try {
            VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "Panorama"; app.apiVersion = VK_API_VERSION_1_1;
            VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; create.pApplicationInfo = &app;
            check(vkCreateInstance(&create, nullptr, &instance), "vkCreateInstance");
            uint32_t count = 0; check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "vkEnumeratePhysicalDevices");
            std::vector<VkPhysicalDevice> devices(count); check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "vkEnumeratePhysicalDevices");
            uint32_t family = UINT32_MAX;
            for (auto candidate : devices) {
                uint32_t size = 0; vkGetPhysicalDeviceQueueFamilyProperties(candidate, &size, nullptr);
                std::vector<VkQueueFamilyProperties> families(size); vkGetPhysicalDeviceQueueFamilyProperties(candidate, &size, families.data());
                for (uint32_t i = 0; i < size; ++i) if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    physical = candidate; family = i; break;
                }
                if (physical) break;
            }
            if (!physical) throw std::runtime_error("Unable to save panorama");
            vkGetPhysicalDeviceProperties(physical, &properties); vkGetPhysicalDeviceMemoryProperties(physical, &memory);
            const float priority = .5f;
            VkDeviceQueueCreateInfo queues{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; queues.queueFamilyIndex = family; queues.queueCount = 1; queues.pQueuePriorities = &priority;
            VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; deviceInfo.queueCreateInfoCount = 1; deviceInfo.pQueueCreateInfos = &queues;
            check(vkCreateDevice(physical, &deviceInfo, nullptr, &device), "vkCreateDevice"); vkGetDeviceQueue(device, family, 0, &queue);
            VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; poolInfo.queueFamilyIndex = family; poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
            check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool), "vkCreateCommandPool");
            VkCommandBufferAllocateInfo commands{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; commands.commandPool = pool; commands.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; commands.commandBufferCount = 1;
            check(vkAllocateCommandBuffers(device, &commands, &command), "vkAllocateCommandBuffers");
            VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; check(vkCreateFence(device, &fenceInfo, nullptr, &fence), "vkCreateFence");
            std::array<VkDescriptorSetLayoutBinding, 5> bindings{};
            for (uint32_t i = 0; i < bindings.size(); ++i) bindings[i] = {i, i == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
            VkDescriptorSetLayoutCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; descriptorInfo.bindingCount = bindings.size(); descriptorInfo.pBindings = bindings.data();
            check(vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptors), "vkCreateDescriptorSetLayout");
            VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 32};
            VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; layoutInfo.setLayoutCount = 1; layoutInfo.pSetLayouts = &descriptors; layoutInfo.pushConstantRangeCount = 1; layoutInfo.pPushConstantRanges = &push;
            check(vkCreatePipelineLayout(device, &layoutInfo, nullptr, &layout), "vkCreatePipelineLayout");
            VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4}};
            VkDescriptorPoolCreateInfo descriptorPoolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; descriptorPoolInfo.maxSets = 1; descriptorPoolInfo.poolSizeCount = 2; descriptorPoolInfo.pPoolSizes = sizes;
            check(vkCreateDescriptorPool(device, &descriptorPoolInfo, nullptr, &descriptorPool), "vkCreateDescriptorPool");
            VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; allocate.descriptorPool = descriptorPool; allocate.descriptorSetCount = 1; allocate.pSetLayouts = &descriptors;
            check(vkAllocateDescriptorSets(device, &allocate, &set), "vkAllocateDescriptorSets");
            VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            samplerInfo.magFilter = samplerInfo.minFilter = VK_FILTER_LINEAR; samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            samplerInfo.addressModeU = samplerInfo.addressModeV = samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            check(vkCreateSampler(device, &samplerInfo, nullptr, &sampler), "vkCreateSampler");
            ensure(params, 512); ensure(queries, 16); ensure(tile, 16); ensure(results, 16);
        } catch (...) { release(); throw; }
    }
    ~Gpu() { release(); }
    void prepare() {
        if (device && failure == VK_SUCCESS) return;
        recordNativeDiagnostic(ANDROID_LOG_WARN, "PanoramaGpu", "Recreating stitching device after GPU failure");
        release();
        failure = VK_SUCCESS;
        lastJob = {};
        initialize();
    }
    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
        for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) if ((bits & (1U << i)) && (memory.memoryTypes[i].propertyFlags & flags) == flags) return i;
        throw std::runtime_error("Unable to save panorama");
    }
    void destroy(Buffer& buffer) {
        if (buffer.mapped) vkUnmapMemory(device, buffer.memory);
        if (buffer.buffer) vkDestroyBuffer(device, buffer.buffer, nullptr);
        if (buffer.memory) vkFreeMemory(device, buffer.memory, nullptr);
        buffer = {};
    }
    void ensure(Buffer& buffer, VkDeviceSize bytes) {
        if (bytes <= buffer.capacity) return;
        if (bytes > properties.limits.maxStorageBufferRange) {
            recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu", "Buffer exceeds device limit bytes=%llu limit=%u job=%d",
                static_cast<unsigned long long>(bytes), properties.limits.maxStorageBufferRange, lastJob[0]);
            throw std::runtime_error("Unable to save panorama");
        }
        destroy(buffer);
        VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; info.size = bytes; info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        check(vkCreateBuffer(device, &info, nullptr, &buffer.buffer), "vkCreateBuffer");
        VkMemoryRequirements required{}; vkGetBufferMemoryRequirements(device, buffer.buffer, &required);
        VkMemoryAllocateInfo allocate{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocate.allocationSize = required.size;
        allocate.memoryTypeIndex = memoryType(required.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        check(vkAllocateMemory(device, &allocate, nullptr, &buffer.memory), "vkAllocateMemory"); check(vkBindBufferMemory(device, buffer.buffer, buffer.memory, 0), "vkBindBufferMemory");
        check(vkMapMemory(device, buffer.memory, 0, bytes, 0, &buffer.mapped), "vkMapMemory"); buffer.capacity = bytes;
    }
    void simulateFailure(size_t count) {
#if !defined(NDEBUG)
        if (lastJob[0] == 21) {
            char limit[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.panorama_flow_limit", limit);
            const auto maximum = std::strtoul(limit, nullptr, 10);
            if (maximum > 0 && count > maximum) check(VK_ERROR_DEVICE_LOST, "simulated overlap budget");
        }
        char value[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.panorama_fault", value);
        if (lastJob[0] > 0 && std::string(value).starts_with(std::to_string(lastJob[0]) + ":") && lastFault != value) {
            lastFault = value;
            const auto code = std::strtol(std::strchr(value, ':') + 1, nullptr, 10);
            if (code == VK_ERROR_DEVICE_LOST || code == VK_ERROR_OUT_OF_DEVICE_MEMORY || code == VK_ERROR_OUT_OF_HOST_MEMORY)
                check(static_cast<VkResult>(code), "simulated GPU failure");
        }
#else
        (void)count;
#endif
    }
    void begin() {
        check(vkResetCommandBuffer(command, 0), "vkResetCommandBuffer"); check(vkResetFences(device, 1, &fence), "vkResetFences");
        VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(command, &info), "vkBeginCommandBuffer");
    }
    void submit() {
        check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
        VkSubmitInfo info{VK_STRUCTURE_TYPE_SUBMIT_INFO}; info.commandBufferCount = 1; info.pCommandBuffers = &command;
        check(vkQueueSubmit(queue, 1, &info, fence), "vkQueueSubmit"); check(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX), "vkWaitForFences");
    }
    void imageBarrier(VkImageLayout before, VkImageLayout after, VkAccessFlags source, VkAccessFlags target) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; barrier.image = image; barrier.oldLayout = before; barrier.newLayout = after;
        barrier.srcAccessMask = source; barrier.dstAccessMask = target; barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }
    void upload(const uint8_t* pixels, int w, int h, int stride) {
        if (w > int(properties.limits.maxImageDimension2D) || h > int(properties.limits.maxImageDimension2D)) throw std::runtime_error("Unable to save panorama");
        if (width != w || height != h) {
            releaseImage();
            VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; info.imageType = VK_IMAGE_TYPE_2D; info.format = VK_FORMAT_R8G8B8A8_UNORM;
            info.extent = {uint32_t(w), uint32_t(h), 1}; info.mipLevels = info.arrayLayers = 1; info.samples = VK_SAMPLE_COUNT_1_BIT; info.tiling = VK_IMAGE_TILING_OPTIMAL;
            info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            check(vkCreateImage(device, &info, nullptr, &image), "vkCreateImage");
            VkMemoryRequirements required{}; vkGetImageMemoryRequirements(device, image, &required);
            VkMemoryAllocateInfo allocate{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocate.allocationSize = required.size; allocate.memoryTypeIndex = memoryType(required.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            check(vkAllocateMemory(device, &allocate, nullptr, &imageMemory), "vkAllocateMemory"); check(vkBindImageMemory(device, image, imageMemory, 0), "vkBindImageMemory");
            VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; viewInfo.image = image; viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D; viewInfo.format = info.format; viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            check(vkCreateImageView(device, &viewInfo, nullptr, &view), "vkCreateImageView");
            begin(); imageBarrier(VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT); submit();
            width = w; height = h;
        }
        const int rows = std::max(1, std::min(h, int(16 * 1024 * 1024 / (w * 4))));
        ensure(staging, VkDeviceSize(w) * rows * 4);
        for (int top = 0; top < h; top += rows) {
            const int count = std::min(rows, h - top);
            for (int y = 0; y < count; ++y) std::memcpy(static_cast<uint8_t*>(staging.mapped) + size_t(y) * w * 4, pixels + size_t(top + y) * stride, size_t(w) * 4);
            begin(); imageBarrier(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; copy.imageOffset = {0, top, 0}; copy.imageExtent = {uint32_t(w), uint32_t(count), 1};
            vkCmdCopyBufferToImage(command, staging.buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
            imageBarrier(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT); submit();
        }
    }
    using Job = std::pair<std::array<int32_t, 8>, size_t>;
    bool compatibleBlendSampling() const {
#if !defined(NDEBUG)
        char overrideValue[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.panorama_sampling", overrideValue);
        if (overrideValue[0] == '1') return true;
#endif
        return blendSamplingRejected || useCompatibleBlendSampling(properties.vendorID);
    }
    int family(int job) const {
        if (job == 70 && compatibleBlendSampling()) return 7;
        return job >= 70 ? 6 : job >= 60 ? 5 : job >= 48 ? 4 : job >= 40 ? 3 : job >= 30 ? 2 : job >= 20 ? 1 : 0;
    }
    void preparePipeline(int index) {
        if (pipelines[index]) return;
        const uint32_t* code[] = {kPanorama0Spirv.data(), kPanorama1Spirv.data(), kPanorama2Spirv.data(), kPanorama3Spirv.data(), kPanorama4Spirv.data(), kPanorama5Spirv.data(), kPanorama6Spirv.data(), kPanoramaBlendSamplingSpirv.data()};
        const size_t sizes[] = {sizeof(kPanorama0Spirv), sizeof(kPanorama1Spirv), sizeof(kPanorama2Spirv), sizeof(kPanorama3Spirv), sizeof(kPanorama4Spirv), sizeof(kPanorama5Spirv), sizeof(kPanorama6Spirv), sizeof(kPanoramaBlendSamplingSpirv)};
        VkShaderModule module = VK_NULL_HANDLE;
        VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; moduleInfo.codeSize = sizes[index]; moduleInfo.pCode = code[index];
        check(vkCreateShaderModule(device, &moduleInfo, nullptr, &module), "vkCreateShaderModule");
        VkComputePipelineCreateInfo info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        info.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, module, "main", nullptr}; info.layout = layout;
        const auto result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &pipelines[index]);
        vkDestroyShaderModule(device, module, nullptr); check(result, "vkCreateComputePipelines");
    }
    void dispatch(std::array<int32_t, 8> push, size_t count) { dispatchJobs({{push, count}}); }
    void dispatchJobs(const std::vector<Job>& jobs) {
        for (auto* buffer : {&params, &queries, &tile, &results}) ensure(*buffer, 16);
        for (const auto& [push, count] : jobs) { (void) count; preparePipeline(family(push[0])); }
        VkDescriptorImageInfo imageInfo{sampler, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        std::array<VkDescriptorBufferInfo, 4> buffers{};
        const Buffer* inputs[] = {&params, &queries, &tile, &results};
        std::array<VkWriteDescriptorSet, 5> writes{};
        for (uint32_t i = 0; i < writes.size(); ++i) {
            auto& write = writes[i]; write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; write.dstSet = set; write.dstBinding = i; write.descriptorCount = 1;
            if (i == 0) { write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; write.pImageInfo = &imageInfo; }
            else { buffers[i - 1] = {inputs[i - 1]->buffer, 0, inputs[i - 1]->capacity}; write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; write.pBufferInfo = &buffers[i - 1]; }
        }
        vkUpdateDescriptorSets(device, writes.size(), writes.data(), 0, nullptr);
        for (size_t first = 0; first < jobs.size(); first += 8) {
            begin();
            VkMemoryBarrier input{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; input.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT; input.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &input, 0, nullptr, 0, nullptr);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
            for (size_t i = first; i < std::min(first + 8, jobs.size()); ++i) {
                const auto& [push, count] = jobs[i];
                lastJob = push;
                simulateFailure(count);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[family(push[0])]);
                vkCmdPushConstants(command, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), push.data());
                const uint32_t groups = uint32_t((count + 63) / 64), x = std::min(groups, properties.limits.maxComputeWorkGroupCount[0]);
                vkCmdDispatch(command, x, (groups + x - 1) / x, 1);
                VkMemoryBarrier written{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; written.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; written.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &written, 0, nullptr, 0, nullptr);
            }
            VkMemoryBarrier output{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; output.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; output.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &output, 0, nullptr, 0, nullptr); submit();
        }
    }
    void gray(const float* data, int w, int h) {
        if (!image) { const uint32_t pixel = 0xff000000; upload(reinterpret_cast<const uint8_t*>(&pixel), 1, 1, 4); }
        const size_t bytes = (size_t(w) * h * sizeof(float) + 15) & ~size_t(15);
        ensure(queries, bytes); std::memcpy(queries.mapped, data, size_t(w) * h * sizeof(float));
    }
    void releaseImage() {
        if (view) vkDestroyImageView(device, view, nullptr);
        if (image) vkDestroyImage(device, image, nullptr);
        if (imageMemory) vkFreeMemory(device, imageMemory, nullptr);
        view = VK_NULL_HANDLE; image = VK_NULL_HANDLE; imageMemory = VK_NULL_HANDLE; width = height = 0;
    }
    void release() {
        if (device) {
            vkDeviceWaitIdle(device);
            releaseImage(); destroy(staging); destroy(params); destroy(queries); destroy(tile); destroy(results);
            if (sampler) vkDestroySampler(device, sampler, nullptr);
            if (descriptorPool) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            for (auto pipeline : pipelines) if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
            if (layout) vkDestroyPipelineLayout(device, layout, nullptr);
            if (descriptors) vkDestroyDescriptorSetLayout(device, descriptors, nullptr);
            if (fence) vkDestroyFence(device, fence, nullptr);
            if (pool) vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyDevice(device, nullptr); device = VK_NULL_HANDLE;
        }
        if (instance) vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
        physical = VK_NULL_HANDLE; queue = VK_NULL_HANDLE; pool = VK_NULL_HANDLE; command = VK_NULL_HANDLE;
        fence = VK_NULL_HANDLE; descriptors = VK_NULL_HANDLE; layout = VK_NULL_HANDLE; pipelines = {};
        blendSamplingRejected = false;
        descriptorPool = VK_NULL_HANDLE; set = VK_NULL_HANDLE; sampler = VK_NULL_HANDLE;
    }
};
Gpu& gpu() { static Gpu instance; return instance; }
}

struct GpuFrame::State {
    Gpu& gpu;
    std::unique_lock<std::mutex> lock;
    std::vector<Float4> parameters;
    explicit State(Gpu& value) : gpu(value), lock(value.mutex) { gpu.prepare(); }
    std::vector<Float4> pyramid(const Gray& source, const Gray& reference, std::vector<Gpu::Job>& jobs, int maximum = 4) {
        std::vector<Float4> info(20);
        int width = source.w, height = source.h, levels = 0;
        size_t floats = 0;
        do {
            info[1 + levels] = {float(width), float(height), float(floats), 0};
            floats += (size_t(width) * height + 3) & ~size_t(3);
            ++levels;
            if (levels == maximum || width < 48 || height < 48) break;
            width /= 2; height /= 2;
        } while (true);
        info[0] = {float(source.w), float(source.h), float(levels), 0};
        for (int i = 0; i < levels; ++i) info[1 + i][3] = info[1 + i][2] + float(floats);
        gpu.ensure(gpu.queries, floats * 2 * sizeof(float));
        std::memcpy(gpu.queries.mapped, source.data.data(), source.data.size() * sizeof(float));
        std::memcpy(static_cast<float*>(gpu.queries.mapped) + floats, reference.data.data(), reference.data.size() * sizeof(float));
        for (int i = 1; i < levels; ++i) {
            const int count = int(info[1 + i][0] * info[1 + i][1]) * 2;
            jobs.push_back({{20, count, i, 0, 0, 0, 0, 0}, size_t(count)});
        }
        return info;
    }
};
GpuFrame::GpuFrame() : state_(std::make_unique<State>(gpu())) {
    if (!state_->gpu.image) {
        const uint32_t pixel = 0xff000000;
        state_->gpu.upload(reinterpret_cast<const uint8_t*>(&pixel), 1, 1, 4);
    }
}
GpuFrame::GpuFrame(const uint8_t* pixels, int width, int height, int stride) : state_(std::make_unique<State>(gpu())) {
    state_->gpu.upload(pixels, width, height, stride);
}
GpuFrame::~GpuFrame() {
    auto& engine = state_->gpu;
    if (engine.width > 1536 || engine.height > 1536) engine.releaseImage();
    for (auto* buffer : {&engine.staging, &engine.params, &engine.queries, &engine.tile, &engine.results})
        if (buffer->capacity > 16 * 1024 * 1024) engine.destroy(*buffer);
}
void GpuFrame::parameters(const std::vector<Float4>& values) {
    state_->parameters = values;
    auto& engine = state_->gpu; engine.ensure(engine.params, values.size() * sizeof(Float4));
    std::memcpy(engine.params.mapped, values.data(), values.size() * sizeof(Float4));
}
std::vector<Float4> GpuFrame::sample(const std::vector<Float4>& points, int mode) {
    auto& engine = state_->gpu; std::vector<Float4> output(points.size());
    constexpr size_t chunk = 65536;
    for (size_t start = 0; start < points.size(); start += chunk) {
        const auto count = std::min(chunk, points.size() - start);
        engine.ensure(engine.queries, count * sizeof(Float4)); engine.ensure(engine.results, count * sizeof(Float4));
        std::memcpy(engine.queries.mapped, points.data() + start, count * sizeof(Float4));
        engine.dispatch({mode, int32_t(count), 0, 0, 0, 0, 0, 0}, count);
        std::memcpy(output.data() + start, engine.results.mapped, count * sizeof(Float4));
    }
    return output;
}
std::vector<Float4> gpuFeatureScores(const float* data, int width, int height) {
    auto& engine = gpu(); std::lock_guard<std::mutex> lock(engine.mutex);
    engine.gray(data, width, height);
    const int cols = (width - 23) / 2, rows = (height - 23) / 2;
    if (cols <= 0 || rows <= 0) return {};
    std::vector<Float4> output(size_t(cols) * rows); engine.ensure(engine.results, output.size() * sizeof(Float4));
    engine.dispatch({10, int32_t(output.size()), width, height, cols, rows, 0, 0}, output.size());
    std::memcpy(output.data(), engine.results.mapped, output.size() * sizeof(Float4)); return output;
}
std::vector<float> gpuDescribe(const float* data, int width, int height, const std::vector<Float4>& points) {
    if (points.empty()) return {};
    auto& engine = gpu(); std::lock_guard<std::mutex> lock(engine.mutex);
    engine.gray(data, width, height);
    engine.ensure(engine.params, (points.size() + 1) * sizeof(Float4));
    std::memcpy(static_cast<Float4*>(engine.params.mapped) + 1, points.data(), points.size() * sizeof(Float4));
    std::vector<float> output(points.size() * 128); engine.ensure(engine.results, output.size() * sizeof(float));
    engine.dispatch({11, int32_t(points.size()), width, height, 0, 0, 0, 0}, points.size());
    std::memcpy(output.data(), engine.results.mapped, output.size() * sizeof(float)); return output;
}
std::vector<Float4> gpuMatch(const std::vector<float>& before, const std::vector<float>& after) {
    const int n = before.size() / 128, m = after.size() / 128;
    if (n == 0 || m == 0) return {};
    auto& engine = gpu(); std::lock_guard<std::mutex> lock(engine.mutex);
    if (!engine.image) { const uint32_t pixel = 0xff000000; engine.upload(reinterpret_cast<const uint8_t*>(&pixel), 1, 1, 4); }
    engine.ensure(engine.queries, (before.size() + after.size()) * sizeof(float));
    std::memcpy(engine.queries.mapped, before.data(), before.size() * sizeof(float));
    std::memcpy(static_cast<float*>(engine.queries.mapped) + before.size(), after.data(), after.size() * sizeof(float));
    engine.ensure(engine.results, size_t(n) * m * sizeof(Float4)); engine.ensure(engine.tile, size_t(n + m) * sizeof(Float4));
    engine.dispatch({12, n * m, n, m, 0, 0, 0, 0}, size_t(n) * m);
    engine.dispatch({13, n + m, n, m, 0, 0, 0, 0}, n + m);
    std::vector<Float4> output(n + m); std::memcpy(output.data(), engine.tile.mapped, output.size() * sizeof(Float4)); return output;
}
std::vector<float> GpuFrame::trackingGray(double focal, bool rectilinear) {
    auto& engine = state_->gpu; const size_t count = size_t(engine.width) * engine.height;
    parameters({{float(engine.width), float(engine.height), float(focal), rectilinear ? 1.f : 0.f}});
    engine.ensure(engine.results, count * sizeof(Float4));
    engine.dispatch({1, int32_t(count), engine.width, 0, 0, 0, 0, 0}, count);
    const auto* rgba = static_cast<const Float4*>(engine.results.mapped);
    std::vector<float> output(count);
    for (size_t i = 0; i < count; ++i) output[i] = rgba[i][0];
    return output;
}
std::vector<float> GpuFrame::referenceGray(const uint8_t* canvas, int width, int height, int left, int top, int cols, int rows, double step, int taps) {
    auto& engine = state_->gpu;
    std::vector<float> output(size_t(cols) * rows, -1);
    const int chunkRows = std::max(1, std::min(65536 / cols, int((16 * 1024 * 1024 / (width * 4) - 4) / step)));
    for (int row = 0; row < rows; row += chunkRows) {
        const int count = std::min(chunkRows, rows - row);
        const int first = std::clamp(int(std::floor(top + row * step)) - 1, 0, height - 1);
        const int end = std::clamp(int(std::ceil(top + (row + count) * step)) + 2, first + 1, height);
        engine.ensure(engine.tile, size_t(width) * (end - first) * 4);
        std::memcpy(engine.tile.mapped, canvas + size_t(first) * width * 4, size_t(width) * (end - first) * 4);
        std::vector<Float4> points(size_t(cols) * count);
        for (int y = 0; y < count; ++y) for (int x = 0; x < cols; ++x)
            points[size_t(y) * cols + x] = {float(left + (x + .5) * step), float(top + (row + y + .5) * step), float(step), float(taps)};
        engine.ensure(engine.queries, points.size() * sizeof(Float4)); engine.ensure(engine.results, points.size() * sizeof(Float4));
        std::memcpy(engine.queries.mapped, points.data(), points.size() * sizeof(Float4));
        engine.dispatch({5, int32_t(points.size()), width, first, height, end - first, 0, 0}, points.size());
        const auto* rgba = static_cast<const Float4*>(engine.results.mapped);
        for (size_t i = 0; i < points.size(); ++i) output[size_t(row) * cols + i] = rgba[i][0];
    }
    return output;
}
std::vector<FlowMatch> GpuFrame::flow(const Gray& source, const Gray& reference) {
    auto& engine = state_->gpu; std::vector<Gpu::Job> jobs;
    auto info = state_->pyramid(source, reference, jobs);
    const int cell = std::max(16, std::min(source.w, source.h) / 28);
    const int cols = (source.w - 25) / cell + 1, rows = (source.h - 25) / cell + 1;
    if (cols <= 0 || rows <= 0) return {};
    const size_t count = size_t(cols) * rows;
    info[6] = {float(cell), float(cols), float(rows), float(std::max(8., std::max(source.w, source.h) * .035))};
    parameters(info); engine.ensure(engine.results, count * 2 * sizeof(Float4));
    engine.dispatchJobs(jobs);
    // Each group performs a full feature search, so submit groups independently.
    for (size_t first = 0; first < count; first += 64)
        engine.dispatch({21, int32_t(count), 0, int32_t(first), 0, 0, 0, 0}, std::min(size_t(64), count - first));
    const auto* output = static_cast<const Float4*>(engine.results.mapped);
    std::vector<FlowMatch> matches;
    for (size_t i = 0; i < count; ++i) if (output[count + i][0] >= .94f)
        matches.push_back({{output[i][0], output[i][1]}, {output[i][2], output[i][3]}, output[count + i][0]});
    return matches;
}
Warp GpuFrame::refineWarp(const Gray& source, const Gray& reference, const Warp& initial, const std::vector<FlowMatch>& matches) {
    const double spacing = std::clamp(std::min(source.w, source.h) / 16., 16., 32.);
    Warp warp;
    warp.cols = int(std::ceil((source.w - 1) / spacing)) + 1; warp.rows = int(std::ceil((source.h - 1) / spacing)) + 1;
    warp.stepX = double(source.w - 1) / (warp.cols - 1); warp.stepY = double(source.h - 1) / (warp.rows - 1);
    const size_t count = size_t(warp.cols) * warp.rows;
    auto& engine = state_->gpu; std::vector<Gpu::Job> jobs;
    auto info = state_->pyramid(source, reference, jobs);
    info[0][3] = float(spacing);
    info[6] = {float(warp.cols), float(warp.rows), float(warp.stepX), float(warp.stepY)};
    info[7] = {float(source.w), float(source.h), initial.shifts.empty() ? .06f : .025f, 0};
    const size_t base = info.size(), current = base + count, next = current + count;
    info[8] = {float(base), float(current), float(next), 0};
    info.resize(next + count);
    for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
        const size_t index = size_t(y) * warp.cols + x;
        const Point point{x * warp.stepX, y * warp.stepY}, shift = initial.at(point.x, point.y);
        Point anchor; double weight = 0;
        for (const auto& match : matches) {
            const auto residual = match.shift - initial.at(match.point.x, match.point.y);
            if (match.confidence < .97 || std::hypot(residual.x, residual.y) > 1.2) continue;
            const double distance = std::hypot(match.point.x - point.x, match.point.y - point.y) / spacing;
            if (distance > 2.5) continue;
            const double value = std::exp(-distance * distance * .5);
            anchor = anchor + residual * value; weight += value;
        }
        if (weight > 0) anchor = anchor * (1 / weight);
        info[base + index] = {float(shift.x), float(shift.y), float(anchor.x), float(anchor.y)};
        info[current + index] = {float(shift.x), float(shift.y), 0, float(weight)};
    }
    parameters(info);
    int sourceOffset = current, destinationOffset = next;
    auto pass = [&](int mode, int level) {
        jobs.push_back({{mode, int32_t(count), level, sourceOffset, destinationOffset, 0, 0, 0}, count});
        std::swap(sourceOffset, destinationOffset);
    };
    for (int level = int(info[0][2]) - 1; level >= 0; --level) for (int iteration = 0; iteration < 12; ++iteration) pass(22, level);
    for (int iteration = 0; iteration < 24; ++iteration) pass(23, 0);
    for (int iteration = 0; iteration < 48; ++iteration) pass(24, 0);
    engine.dispatchJobs(jobs);
    const auto* values = static_cast<const Float4*>(engine.params.mapped) + sourceOffset;
    warp.shifts.resize(count);
    for (size_t i = 0; i < count; ++i) warp.shifts[i] = {values[i][0], values[i][1]};
    return warp;
}
std::vector<uint8_t> GpuFrame::panelSeam(const std::vector<uint8_t>& coverage, const std::vector<float>& costs, int cols, int rows) {
    auto& engine = state_->gpu; const int count = cols * rows;
    parameters({{float(cols), float(rows), float(count), 0}});
    engine.ensure(engine.queries, size_t(count) * sizeof(Float4));
    auto* input = static_cast<Float4*>(engine.queries.mapped);
    for (int i = 0; i < count; ++i) input[i] = {costs[i], float(coverage[i]), 0, 0};
    engine.ensure(engine.tile, (size_t(count) * 8 + 2) * sizeof(uint32_t));
    engine.ensure(engine.results, size_t(count) * sizeof(Float4));
    auto* counters = static_cast<const uint32_t*>(engine.tile.mapped) + size_t(count) * 8;
    auto run = [&](int stage) { engine.dispatch({stage, count, cols, rows, 0, 0, 0, 0}, count); };
    auto flood = [&](int stage) {
        for (int pass = 0; pass < count + 32; pass += 32) {
            std::vector<Gpu::Job> jobs;
            for (int i = 0; i < 32; ++i) {
                jobs.push_back({{stage, count, cols, rows, 0, 0, 0, 0}, size_t(count)});
                jobs.push_back({{stage + 1, count, cols, rows, 0, 0, 0, 0}, size_t(count)});
            }
            engine.dispatchJobs(jobs);
            if (counters[1] == 0) return;
        }
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu", "Seam flood did not converge stage=%d size=%dx%d changes=%u", stage, cols, rows, counters[1]);
        throw std::runtime_error("Unable to save panorama");
    };
    run(30); run(31); flood(32);
    bool complete = false;
    for (int pass = 0; pass < 32768; pass += 64) {
        std::vector<Gpu::Job> jobs;
        for (int i = 0; i < 64; ++i) {
            jobs.push_back({{34, count, cols, rows, 0, 0, 0, 0}, size_t(count)});
            jobs.push_back({{35, count, cols, rows, 0, 0, 0, 0}, size_t(count)});
        }
        engine.dispatchJobs(jobs);
        if (counters[0] == 0) { complete = true; break; }
        if (pass % 256 == 192) { run(31); flood(32); }
    }
    if (!complete) {
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu", "Seam flow did not converge size=%dx%d active=%u", cols, rows, counters[0]);
        throw std::runtime_error("Unable to save panorama");
    }
    run(36); flood(37);
    std::vector<uint8_t> labels(count); const auto* result = static_cast<const Float4*>(engine.results.mapped);
    for (int i = 0; i < count; ++i) labels[i] = result[i][0] > .5f;
    return labels;
}
std::vector<Float4> GpuFrame::seamCosts(const uint8_t* canvas, int width, int height, const std::vector<Float4>& points, bool fine) {
    auto& engine = state_->gpu;
    std::vector<Float4> output(points.size());
    const int rows = std::max(1, 8 * 1024 * 1024 / (width * 4));
    std::vector<std::vector<Float4>> strips((height + rows - 1) / rows);
    int halo = 2;
    for (size_t i = 0; i < points.size(); ++i) {
        auto point = points[i]; point[3] = float(i);
        strips[std::clamp(int(point[1]) / rows, 0, int(strips.size()) - 1)].push_back(point);
        halo = std::max(halo, int(std::ceil(point[2])) + 2);
    }
    for (size_t strip = 0; strip < strips.size(); ++strip) {
        const auto& samples = strips[strip]; if (samples.empty()) continue;
        const int first = std::max(0, int(strip) * rows - halo), end = std::min(height, (int(strip) + 1) * rows + halo);
        engine.ensure(engine.tile, size_t(width) * (end - first) * 4);
        std::memcpy(engine.tile.mapped, canvas + size_t(first) * width * 4, size_t(width) * (end - first) * 4);
        for (size_t start = 0; start < samples.size(); start += 65536) {
            const size_t count = std::min(size_t(65536), samples.size() - start);
            engine.ensure(engine.queries, count * sizeof(Float4)); engine.ensure(engine.results, count * sizeof(Float4));
            std::memcpy(engine.queries.mapped, samples.data() + start, count * sizeof(Float4));
            engine.dispatch({6, int32_t(count), width, first, height, end - first, fine, 0}, count);
            const auto* values = static_cast<const Float4*>(engine.results.mapped);
            for (size_t i = 0; i < count; ++i) output[size_t(values[i][2])] = values[i];
        }
    }
    return output;
}
std::vector<Float4> GpuFrame::refinePoints(const Gray& source, const Gray& reference, const std::vector<Float4>& points) {
    if (points.empty()) return {};
    auto& engine = state_->gpu; std::vector<Gpu::Job> jobs;
    auto info = state_->pyramid(source, reference, jobs, 1);
    info.insert(info.end(), points.begin(), points.end()); parameters(info);
    engine.ensure(engine.results, points.size() * 2 * sizeof(Float4));
    engine.dispatch({26, int32_t(points.size()), 0, 0, 0, 0, 0, 0}, points.size());
    const auto* result = static_cast<const Float4*>(engine.results.mapped);
    std::vector<Float4> refined;
    for (size_t i = 0; i < points.size(); ++i) if (result[points.size() + i][0] >= .94f) refined.push_back(result[i]);
    return refined;
}
std::vector<Float4> GpuFrame::correlations(const Gray& source, const Gray& reference, const std::vector<Float4>& offsets) {
    if (offsets.empty()) return {};
    auto& engine = state_->gpu; std::vector<Gpu::Job> jobs;
    auto info = state_->pyramid(source, reference, jobs, 1);
    info.insert(info.end(), offsets.begin(), offsets.end()); parameters(info);
    engine.ensure(engine.results, offsets.size() * sizeof(Float4));
    engine.dispatch({27, int32_t(offsets.size()), 0, 0, 0, 0, 0, 0}, offsets.size());
    std::vector<Float4> output(offsets.size()); std::memcpy(output.data(), engine.results.mapped, output.size() * sizeof(Float4));
    return output;
}
std::vector<ColorMatch> GpuFrame::colorMatches(const Gray& source, const Gray& reference, const uint8_t* canvas, int width, int height, std::vector<Float4> values) {
    auto& engine = state_->gpu;
    const size_t grayFloats = (source.data.size() + 3) & ~size_t(3), queryOffset = grayFloats / 2;
    engine.ensure(engine.queries, grayFloats * 2 * sizeof(float) + 65536 * sizeof(Float4));
    std::memcpy(engine.queries.mapped, source.data.data(), source.data.size() * sizeof(float));
    std::memcpy(static_cast<float*>(engine.queries.mapped) + grayFloats, reference.data.data(), reference.data.size() * sizeof(float));
    values[19] = {0, float(grayFloats), float(source.w), float(source.h)};
    parameters(values);
    const int interval = std::max(2, std::max(source.w, source.h) / 220), rows = std::max(1, 8 * 1024 * 1024 / (width * 4));
    std::vector<std::vector<Float4>> strips((height + rows - 1) / rows);
    for (int y = 1; y < source.h - 1; y += interval) for (int x = 1; x < source.w - 1; x += interval) {
        const int strip = std::clamp(int(values[6][1] + (y + .5) * values[6][2]) / rows, 0, int(strips.size()) - 1);
        strips[strip].push_back({float(x), float(y), 0, 0});
    }
    std::vector<ColorMatch> matches;
    for (size_t strip = 0; strip < strips.size(); ++strip) {
        const auto& points = strips[strip]; if (points.empty()) continue;
        const int first = std::max(0, int(strip) * rows - 2), end = std::min(height, (int(strip) + 1) * rows + 2);
        engine.ensure(engine.tile, size_t(width) * (end - first) * 4);
        std::memcpy(engine.tile.mapped, canvas + size_t(first) * width * 4, size_t(width) * (end - first) * 4);
        for (size_t start = 0; start < points.size(); start += 65536) {
            const size_t count = std::min(size_t(65536), points.size() - start);
            std::memcpy(static_cast<Float4*>(engine.queries.mapped) + queryOffset, points.data() + start, count * sizeof(Float4));
            engine.ensure(engine.results, count * 3 * sizeof(Float4));
            engine.dispatch({7, int32_t(count), width, first, height, end - first, int32_t(queryOffset), 0}, count);
            const auto* output = static_cast<const Float4*>(engine.results.mapped);
            for (size_t i = 0; i < count; ++i) {
                const auto& a = output[i * 3]; const auto& b = output[i * 3 + 1]; const auto& point = output[i * 3 + 2];
                if (point[2] < -1.5f) continue;
                matches.push_back({point[0], point[1], a[3], b[3], {a[0], a[1], a[2]}, {b[0], b[1], b[2]}, point[3] > .5f});
            }
        }
    }
    return matches;
}
std::array<int, 4> GpuFrame::crop(const uint8_t* canvas, int width, int height) {
    auto& engine = state_->gpu;
    parameters(std::vector<Float4>(width));
    int power = 1; while (power < width) power *= 2;
    const int rows = std::max(1, std::min(height, 262144 / width));
    engine.ensure(engine.tile, (size_t(width) * rows + 2) * sizeof(uint32_t));
    engine.ensure(engine.results, size_t(power) * 2 * rows * sizeof(Float4));
    engine.ensure(engine.queries, size_t(width) * rows * sizeof(Float4));
    uint32_t area = 0; std::array<int, 4> crop{};
    for (int top = 0; top < height; top += rows) {
        const int count = std::min(rows, height - top), pixels = width * count;
        std::memcpy(engine.tile.mapped, canvas + size_t(top) * width * 4, size_t(pixels) * 4);
        std::vector<Gpu::Job> jobs{{{40, width, width, count, power, pixels, 0, 0}, size_t(width)}};
        for (int nodes = power / 2; nodes > 0; nodes /= 2)
            jobs.push_back({{41, nodes * count, width, nodes, power, pixels, 0, 0}, size_t(nodes) * count});
        jobs.push_back({{42, pixels, width, count, power, pixels, 0, 0}, size_t(pixels)});
        jobs.push_back({{43, pixels, width, count, power, pixels, 0, 0}, size_t(pixels)});
        engine.dispatchJobs(jobs);
        const auto* statistics = static_cast<const uint32_t*>(engine.tile.mapped) + pixels;
        if (statistics[0] <= area) continue;
        area = statistics[0]; const int index = int(statistics[1]);
        const auto& rectangle = static_cast<const Float4*>(engine.queries.mapped)[index];
        const int left = int(rectangle[1]) + 1, extent = int(rectangle[2]) - left, h = int(rectangle[0]);
        crop = {left, top + index / width - h + 1, extent, h};
    }
    if (!area) throw std::runtime_error("Unable to save panorama");
    return crop;
}
void GpuFrame::resize(uint8_t* output, int width, int height, int stride, int rotation) {
    auto& engine = state_->gpu;
    const size_t count = size_t(width) * height;
    engine.ensure(engine.tile, count * 4);
    engine.dispatch({9, int32_t(count), width, height, rotation, 0, 0, 0}, count);
    for (int y = 0; y < height; ++y) std::memcpy(output + size_t(y) * stride, static_cast<uint8_t*>(engine.tile.mapped) + size_t(y) * width * 4, size_t(width) * 4);
}
ExposureField GpuFrame::exposure(const std::vector<ColorMatch>& samples, int width, int height) {
    ExposureField field;
    if (samples.size() < 64) return field;
    auto& engine = state_->gpu;
    const double spacing = std::max(24., std::min(width, height) / 8.);
    field.cols = std::max(2, int(std::ceil((width - 1) / spacing)) + 1); field.rows = std::max(2, int(std::ceil((height - 1) / spacing)) + 1);
    field.stepX = double(width - 1) / (field.cols - 1); field.stepY = double(height - 1) / (field.rows - 1);
    const int nodes = field.cols * field.rows, count = samples.size();
    const int aligned = std::count_if(samples.begin(), samples.end(), [](const auto& sample) { return sample.aligned; });
    std::vector<Float4> values(12 + nodes * 4);
    values[0] = {float(width), float(height), float(count), float(aligned)};
    values[1] = {1, 1, 1, 0}; values[6] = {float(field.cols), float(field.rows), float(field.stepX), float(field.stepY)};
    values[7] = {float(spacing), 12, float(nodes), 0}; parameters(values);
    engine.ensure(engine.queries, size_t(count) * 3 * sizeof(Float4));
    auto* input = static_cast<Float4*>(engine.queries.mapped);
    for (int i = 0; i < count; ++i) {
        const auto& sample = samples[i];
        input[i * 3] = {float(sample.source[0]), float(sample.source[1]), float(sample.source[2]), float(sample.u)};
        input[i * 3 + 1] = {float(sample.reference[0]), float(sample.reference[1]), float(sample.reference[2]), float(sample.v)};
        input[i * 3 + 2] = {float(sample.x), float(sample.y), sample.aligned ? 1.f : 0.f, 0};
    }
    engine.ensure(engine.tile, (size_t(nodes) * 3 + size_t(count) * 16) * sizeof(uint32_t));
    std::vector<Gpu::Job> jobs;
    auto run = [&](int stage, int size, int option = 0, int inputSlot = 0, int outputSlot = 0) {
        jobs.push_back({{stage, size, option, inputSlot, outputSlot, 0, 0, 0}, size_t(size)});
    };
    run(52, 3 * 64, 0);
    if (aligned >= 64) {
        for (int iteration = 0; iteration < 6; ++iteration) { run(52, 3 * 64, 1); run(52, 3 * 64, 2); run(53, 3 * 64, 0); }
        run(54, 3);
        for (int iteration = 0; iteration < 5; ++iteration) { run(52, 3 * 64, 3); run(52, 3 * 64, 4); run(53, 3 * 64, 1); }
    }
    run(48, nodes); run(49, count); run(50, 1); run(51, count);
    run(52, nodes * 3 * 64, 5); run(52, nodes * 3 * 64, 6); run(55, nodes);
    for (int iteration = 0; iteration < 180; ++iteration) run(56, nodes, 0, 2 + iteration % 2, 3 - iteration % 2);
    engine.dispatchJobs(jobs);
    const auto* output = static_cast<const Float4*>(engine.params.mapped);
    for (int c = 0; c < 3; ++c) { field.gain[c] = output[1][c]; field.bias[c] = output[2][c]; field.shading[c] = output[3][c]; }
    field.offsets.resize(nodes);
    for (int i = 0; i < nodes; ++i) for (int c = 0; c < 3; ++c) field.offsets[i][c] = output[12 + i * 4 + 2][c];
#if !defined(NDEBUG)
    char corruption[PROP_VALUE_MAX]{};
    __system_property_get("debug.unspektra.panorama_corrupt", corruption);
    if (std::string(corruption).starts_with("exposure:") && engine.lastCorruption != corruption) {
        engine.lastCorruption = corruption;
        field.gain[0] = std::numeric_limits<double>::quiet_NaN();
    }
#endif
    if (!field.valid()) {
        recordNativeDiagnostic(ANDROID_LOG_WARN, "PanoramaGpu", "Rebuilt invalid exposure samples=%d gpu=%s driver=%u",
            count, engine.properties.deviceName, engine.properties.driverVersion);
        field = matchExposure(samples, width, height);
        if (!field.valid()) throw std::runtime_error("Unable to save panorama");
    }
    return field;
}
std::vector<int> GpuFrame::sweepSeam(const std::vector<float>& costs, int cols, const std::vector<int>& origins, int low, int high, int step, bool fine) {
    const int rows = origins.size();
    if (cols <= 0 || cols > 1024 || rows <= 0 || costs.size() != size_t(cols) * rows) throw std::runtime_error("Unable to save panorama");
    auto& engine = state_->gpu;
    std::vector<Float4> values(rows + 2);
    values[0] = {float(cols), float(rows), float(low), float(high)}; values[1] = {float(step), fine ? 1.f : 0.f, 0, 0};
    for (int i = 0; i < rows; ++i) values[2 + i][0] = float(origins[i]);
    parameters(values);
    engine.ensure(engine.queries, (costs.size() * sizeof(float) + 15) & ~size_t(15));
    std::memcpy(engine.queries.mapped, costs.data(), costs.size() * sizeof(float));
    engine.ensure(engine.tile, (costs.size() + rows) * sizeof(uint32_t));
    engine.dispatch({60, 64, 0, 0, 0, 0, 0, 0}, 64);
    std::vector<int> cuts(rows); const auto* output = static_cast<const uint32_t*>(engine.tile.mapped) + costs.size();
    for (int i = 0; i < rows; ++i) cuts[i] = int32_t(output[i]);
    return cuts;
}
std::vector<Float4> GpuFrame::patchAlignment(const uint8_t* canvas, int width, int height, const std::vector<Float4>& points,
    const std::vector<Float4>& raster, bool seam, double maxShift) {
    auto& engine = state_->gpu;
    std::vector<Float4> output(points.size());
    const int edge = seam ? 41 : 33, stride = (edge * edge + 3) & ~3, halo = edge / 2 + 2;
    const int rows = std::max(1, 8 * 1024 * 1024 / (width * 4));
    std::vector<std::vector<size_t>> strips((height + rows - 1) / rows);
    for (size_t i = 0; i < points.size(); ++i)
        strips[std::clamp(int(points[i][3]) / rows, 0, int(strips.size()) - 1)].push_back(i);
    for (size_t strip = 0; strip < strips.size(); ++strip) {
        const auto& indices = strips[strip]; if (indices.empty()) continue;
        const int first = std::max(0, int(strip) * rows - halo), end = std::min(height, (int(strip) + 1) * rows + halo);
        engine.ensure(engine.tile, size_t(width) * (end - first) * 4);
        std::memcpy(engine.tile.mapped, canvas + size_t(first) * width * 4, size_t(width) * (end - first) * 4);
        for (size_t start = 0; start < indices.size(); start += 32) {
            const int count = int(std::min(size_t(32), indices.size() - start)), pixels = count * stride;
            auto values = raster;
            values[19] = {float(edge), float(stride), float(values.size()), seam ? 1.f : 0.f};
            for (int i = 0; i < count; ++i) values.push_back(points[indices[start + i]]);
            parameters(values);
            engine.ensure(engine.queries, size_t(pixels) * 2 * sizeof(float));
            engine.ensure(engine.results, size_t(pixels) * sizeof(Float4));
            engine.dispatch({28, pixels, width, first, height, end - first, 0, 0}, pixels);
            std::vector<Float4> info(3);
            info[0] = {float(edge), float(edge), 1, 0}; info[1] = {float(edge), float(edge), 0, float(pixels)};
            info[2] = {float(maxShift), seam ? 1.f : 0.f, float(stride), 0}; parameters(info);
            std::vector<Gpu::Job> jobs;
            if (seam) {
                jobs.push_back({{25, pixels, 0, 0, 0, 0, 0, 0}, size_t(pixels)});
                jobs.push_back({{25, pixels, 1, 0, 0, 0, 0, 0}, size_t(pixels)});
            }
            jobs.push_back({{29, count, 0, 0, 0, 0, 0, 0}, size_t(count)});
            engine.dispatchJobs(jobs);
            const auto* results = static_cast<const Float4*>(engine.results.mapped);
            for (int i = 0; i < count; ++i) output[indices[start + i]] = results[i];
        }
    }
    return output;
}
void GpuFrame::compose(uint8_t* canvas, int width, int height, int left, int top, int right, int bottom, bool blend) {
    auto& engine = state_->gpu;
    if (blend && left < right && top < bottom) {
        auto values = state_->parameters;
        const int descriptor = int(values.size());
        const double radius = std::clamp(std::min(engine.width, engine.height) / 12., 32., 512.);
        const int padding = int(std::ceil(radius * 4));
        left = std::max(0, left - padding); top = std::max(0, top - padding);
        right = std::min(width, right + padding); bottom = std::min(height, bottom + padding);
        const double step = std::max(1., std::max(right - left, bottom - top) / 768.);
        int cols = int(std::ceil((right - left) / step)), rows = int(std::ceil((bottom - top) / step));
        values[18][2] = float(descriptor); values[18][3] = 1;
        values.push_back({float(left), float(top), float(step), 0});
        size_t entries = 0;
        int levels = 0;
        std::vector<BlendLevel> shapes;
        do {
            values.push_back({float(cols), float(rows), float(entries), 0});
            shapes.push_back({cols, rows, entries});
            entries += size_t(cols) * rows * 2;
            ++levels;
            if (levels >= 8 || step * (1 << levels) > radius || std::min(cols, rows) < 16) break;
            cols = (cols + 1) / 2; rows = (rows + 1) / 2;
        } while (true);
        values[descriptor][3] = float(levels);
        parameters(values);
        engine.ensure(engine.queries, entries * sizeof(Float4));
        engine.ensure(engine.results, entries * sizeof(Float4));
        cols = int(values[descriptor + 1][0]); rows = int(values[descriptor + 1][1]);
        const int stripRows = std::max(1, int(8 * 1024 * 1024 / (width * 4)));
        const int taps = std::min(4, int(std::ceil(step)));
        auto sampleBlend = [&] {
            recordNativeDiagnostic(ANDROID_LOG_INFO, "PanoramaGpu", "Sampling blend compatible=%d grid=%dx%d taps=%d gpu=%s driver=%u",
                engine.compatibleBlendSampling(), cols, rows, taps, engine.properties.deviceName, engine.properties.driverVersion);
            for (int row = 0; row < rows;) {
                const int first = std::max(0, int(top + row * step) - 2);
                int endRow = row + 1;
                while (endRow < rows && (endRow - row + 1) * step < stripRows) ++endRow;
                const int end = std::min(height, int(std::ceil(top + endRow * step)) + 2);
                engine.ensure(engine.tile, size_t(width) * (end - first) * 4);
                std::memcpy(engine.tile.mapped, canvas + size_t(first) * width * 4, size_t(width) * (end - first) * 4);
                engine.dispatch({70, cols * (endRow - row), width, first, height, end - first, row, taps}, size_t(cols) * (endRow - row));
                row = endRow;
            }
        };
        sampleBlend();
#if !defined(NDEBUG)
        char sampleCorruption[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.panorama_corrupt", sampleCorruption);
        if (std::string(sampleCorruption).starts_with("blend-samples:") && engine.lastCorruption != sampleCorruption) {
            engine.lastCorruption = sampleCorruption;
            static_cast<Float4*>(engine.queries.mapped)[0][0] = std::numeric_limits<float>::quiet_NaN();
        }
#endif
        std::vector<Float4> reference;
        try { reference = referenceBlend(static_cast<const Float4*>(engine.queries.mapped), shapes); }
        catch (const BlendSampleError& error) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "PanoramaGpu", "Invalid blend samples source=%dx%d canvas=%dx%d gpu=%s driver=%u compatible=%d reason=%s",
                engine.width, engine.height, width, height, engine.properties.deviceName, engine.properties.driverVersion,
                engine.compatibleBlendSampling(), error.what());
            engine.blendSamplingRejected = true;
            sampleBlend();
            try { reference = referenceBlend(static_cast<const Float4*>(engine.queries.mapped), shapes); }
            catch (const BlendSampleError& retryError) {
                recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu", "Compatible blend sampling failed: %s", retryError.what());
                throw;
            }
            recordNativeDiagnostic(ANDROID_LOG_INFO, "PanoramaGpu", "Rebuilt blend samples with scalar sampling gpu=%s driver=%u",
                engine.properties.deviceName, engine.properties.driverVersion);
        }
        std::vector<Gpu::Job> jobs;
        for (int level = 1; level < levels; ++level) {
            const auto& shape = values[descriptor + 1 + level];
            const int count = int(shape[0]) * int(shape[1]);
            jobs.push_back({{71, count, level, 0, 0, 0, 0, 0}, size_t(count)});
        }
        for (int level = levels - 1; level >= 0; --level) {
            const auto& shape = values[descriptor + 1 + level];
            const int count = int(shape[0]) * int(shape[1]);
            jobs.push_back({{72, count, level, 0, 0, 0, 0, 0}, size_t(count)});
        }
        engine.dispatchJobs(jobs);
#if !defined(NDEBUG)
        char corruption[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.panorama_corrupt", corruption);
        if (std::string(corruption).starts_with("blend:") && engine.lastCorruption != corruption) {
            engine.lastCorruption = corruption;
            static_cast<Float4*>(engine.results.mapped)[0][0] = std::numeric_limits<float>::quiet_NaN();
        }
#endif
        if (!blendMatchesReference(static_cast<const Float4*>(engine.results.mapped), reference)) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "PanoramaGpu", "Rebuilt inconsistent blend source=%dx%d canvas=%dx%d gpu=%s driver=%u",
                engine.width, engine.height, width, height, engine.properties.deviceName, engine.properties.driverVersion);
            std::memcpy(engine.results.mapped, reference.data(), reference.size() * sizeof(Float4));
        }
    }
    const int tileWidth = right - left;
    if (tileWidth <= 0 || top >= bottom) return;
    const int rows = std::max(1, std::min(bottom - top, int(1024 * 1024 / (tileWidth * 4))));
    engine.ensure(engine.tile, size_t(tileWidth) * rows * 4);
    for (int y = top; y < bottom; y += rows) {
        const int count = std::min(rows, bottom - y);
        for (int row = 0; row < count; ++row) std::memcpy(static_cast<uint8_t*>(engine.tile.mapped) + size_t(row) * tileWidth * 4,
            canvas + (size_t(y + row) * width + left) * 4, size_t(tileWidth) * 4);
        engine.dispatch({2, tileWidth * count, tileWidth, y, left, count, width, height}, size_t(tileWidth) * count);
        for (int row = 0; row < count; ++row) std::memcpy(canvas + (size_t(y + row) * width + left) * 4,
            static_cast<uint8_t*>(engine.tile.mapped) + size_t(row) * tileWidth * 4, size_t(tileWidth) * 4);
    }
}
}
