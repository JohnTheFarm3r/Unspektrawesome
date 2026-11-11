#pragma once

#include <vulkan/vulkan.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace camera_gpu {
using Clock = std::chrono::steady_clock;
inline thread_local bool exporting = false;
inline thread_local uint32_t groupBudget = 4096;
inline thread_local uint32_t exportPauseMillis = 0;
inline thread_local Clock::time_point lastYield = Clock::now();
inline std::mutex previewMutex;
inline std::condition_variable previewChanged;
inline uint64_t previewGeneration = 0;
inline uint32_t activePreviews = 0;

inline bool boundedPreview(uint64_t pixelCount, bool measuredFast = false) {
#if defined(__ANDROID__) && !defined(NDEBUG)
    char value[PROP_VALUE_MAX]{};
    __system_property_get("debug.unspektra.preview_slices", value);
    if (value[0] == '0') return false;
    if (value[0] == '1') return true;
#endif
    return !measuredFast && pixelCount > 1024u * 1024u;
}

struct ExportScope {
    bool previous = exporting;
    uint32_t previousPause = exportPauseMillis;
    explicit ExportScope(bool enabled = true, uint32_t pauseMillis = 0) {
        exporting = enabled;
        exportPauseMillis = std::min(pauseMillis, 20u);
        lastYield = Clock::now();
    }
    ~ExportScope() { exporting = previous; exportPauseMillis = previousPause; }
};

struct PreviewScope {
    PreviewScope() {
        std::lock_guard<std::mutex> lock(previewMutex);
        ++activePreviews;
    }
    ~PreviewScope() {
        std::lock_guard<std::mutex> lock(previewMutex);
        --activePreviews;
        ++previewGeneration;
        previewChanged.notify_all();
    }
};

inline void yieldToPreview() {
    const auto now = Clock::now();
    if (now - lastYield < std::chrono::milliseconds(6)) return;
    std::unique_lock<std::mutex> lock(previewMutex);
    if (activePreviews > 0) {
        const auto generation = previewGeneration;
        previewChanged.wait_for(lock, std::chrono::milliseconds(4), [&] {
            return previewGeneration != generation;
        });
    }
    lastYield = Clock::now();
}

// Every continuation restores its compute state after a bounded submission.
class ComputeRecorder {
public:
    ComputeRecorder(VkDevice device, VkCommandBuffer commands,
                    std::function<VkResult(const VkSubmitInfo&, VkFence)> submit,
                    bool bounded = exporting)
        : device_(device), commands_(commands), submit_(std::move(submit)), enabled_(bounded) {}
    ~ComputeRecorder() {
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
    }

    // Debug profiling: one timestamp after every dispatch, labelled by pipeline and operation word.
    struct DispatchSample {
        VkPipeline pipeline = VK_NULL_HANDLE;
        uint32_t operation = 0;
        uint32_t groups = 0;
    };
    void profileDispatches(VkQueryPool pool, uint32_t capacity, uint32_t operationOffset) {
        profilePool_ = pool;
        profileCapacity_ = capacity;
        profileOperationOffset_ = operationOffset;
        profileSamples_.clear();
        if (pool == VK_NULL_HANDLE || capacity < 2u) return;
        vkCmdResetQueryPool(commands_, pool, 0, capacity);
        vkCmdWriteTimestamp(commands_, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, 0);
    }
    const std::vector<DispatchSample>& profiledDispatches() const { return profileSamples_; }

    void bindPipeline(VkCommandBuffer commands, VkPipelineBindPoint point, VkPipeline pipeline) {
        if (enabled_) pipeline_ = pipeline;
        profilePipeline_ = pipeline;
        vkCmdBindPipeline(commands, point, pipeline);
    }
    void bindDescriptors(VkCommandBuffer commands, VkPipelineBindPoint point, VkPipelineLayout layout,
                         uint32_t first, uint32_t count, const VkDescriptorSet* sets,
                         uint32_t dynamicCount, const uint32_t* dynamicOffsets) {
        if (enabled_) {
            descriptorLayout_ = layout;
            firstSet_ = first;
            descriptors_.assign(sets, sets + count);
            offsets_.clear();
            if (dynamicCount) offsets_.assign(dynamicOffsets, dynamicOffsets + dynamicCount);
        }
        vkCmdBindDescriptorSets(commands, point, layout, first, count, sets, dynamicCount, dynamicOffsets);
    }
    void push(VkCommandBuffer commands, VkPipelineLayout layout, VkShaderStageFlags stages,
              uint32_t offset, uint32_t size, const void* data) {
        if (enabled_) {
            pushLayout_ = layout;
            pushStages_ = stages;
            pushOffset_ = offset;
            pushData_.resize(size);
            std::memcpy(pushData_.data(), data, size);
        }
        if (profilePool_ != VK_NULL_HANDLE && offset <= profileOperationOffset_ &&
            profileOperationOffset_ + 4u <= offset + size) {
            std::memcpy(&profileOperation_, static_cast<const std::byte*>(data) + (profileOperationOffset_ - offset), 4u);
        }
        vkCmdPushConstants(commands, layout, stages, offset, size, data);
    }
    void dispatch(VkCommandBuffer commands, uint32_t width, uint32_t height, uint32_t depth) {
        dispatchUnprofiled(commands, width, height, depth);
        recordProfile(commands, width * height * depth);
    }

private:
    void recordProfile(VkCommandBuffer commands, uint32_t groups) {
        if (profilePool_ == VK_NULL_HANDLE || profileSamples_.size() + 2u > profileCapacity_) return;
        profileSamples_.push_back({profilePipeline_, profileOperation_, groups});
        vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, profilePool_,
            static_cast<uint32_t>(profileSamples_.size()));
    }
    void dispatchUnprofiled(VkCommandBuffer commands, uint32_t width, uint32_t height, uint32_t depth) {
        if (!enabled_ || width == 0 || height == 0 || depth == 0) {
            vkCmdDispatch(commands, width, height, depth);
            return;
        }
        for (uint32_t z = 0; z < depth; ++z) {
            for (uint32_t y = 0; y < height;) {
                const uint32_t columns = std::min(width, groupBudget);
                const uint32_t rows = std::min(height - y, std::max(1u, groupBudget / columns));
                for (uint32_t x = 0; x < width;) {
                    const uint32_t count = std::min(width - x, std::max(1u, groupBudget / rows));
                    vkCmdDispatchBase(commands, x, y, z, count, rows, 1);
                    flush(count * rows);
                    x += count;
                }
                y += rows;
            }
        }
    }

public:
    // The caller supplies an explicit image origin for each bounded slice.
    void dispatchSlice(VkCommandBuffer commands, uint32_t width, uint32_t height) {
        vkCmdDispatch(commands, width, height, 1u);
        recordProfile(commands, width * height);
        if (enabled_) flush(width * height);
    }

    void beginIndependentPass() {
        pipeline_ = VK_NULL_HANDLE;
        descriptors_.clear();
        offsets_.clear();
        pushData_.clear();
        flush(0u);
    }

private:
    static void check(VkResult result) {
        if (result != VK_SUCCESS) throw std::runtime_error("Cooperative GPU submission failed: " + std::to_string(result));
    }
    void flush(uint32_t groups) {
        if (fence_ == VK_NULL_HANDLE) {
            VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            check(vkCreateFence(device_, &info, nullptr, &fence_));
        }
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        vkCmdPipelineBarrier(commands_, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
        check(vkEndCommandBuffer(commands_));
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commands_;
        const auto started = Clock::now();
        check(submit_(submit, fence_));
        check(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX));
        const double milliseconds = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
        // Amortize submission overhead while keeping exports split into short GPU slices.
        if (groups > 0u && (groups >= groupBudget / 2 || milliseconds > 8.0)) {
            const auto estimated = static_cast<uint32_t>(groups * std::clamp(8.0 / std::max(0.1, milliseconds), 0.5, 2.0));
            groupBudget = std::clamp(estimated, 4096u, 16384u);
        }
        if (exporting) yieldToPreview();
        if (exportPauseMillis > 0) std::this_thread::sleep_for(std::chrono::milliseconds(exportPauseMillis));
        check(vkResetFences(device_, 1, &fence_));
        check(vkResetCommandBuffer(commands_, 0));
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(commands_, &begin));
        if (pipeline_ != VK_NULL_HANDLE) vkCmdBindPipeline(commands_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        if (!descriptors_.empty()) vkCmdBindDescriptorSets(commands_, VK_PIPELINE_BIND_POINT_COMPUTE,
            descriptorLayout_, firstSet_, static_cast<uint32_t>(descriptors_.size()), descriptors_.data(),
            static_cast<uint32_t>(offsets_.size()), offsets_.data());
        if (!pushData_.empty()) vkCmdPushConstants(commands_, pushLayout_, pushStages_, pushOffset_,
            static_cast<uint32_t>(pushData_.size()), pushData_.data());
    }

    VkDevice device_;
    VkCommandBuffer commands_;
    std::function<VkResult(const VkSubmitInfo&, VkFence)> submit_;
    bool enabled_;
    VkFence fence_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout descriptorLayout_ = VK_NULL_HANDLE;
    uint32_t firstSet_ = 0;
    std::vector<VkDescriptorSet> descriptors_;
    std::vector<uint32_t> offsets_;
    VkPipelineLayout pushLayout_ = VK_NULL_HANDLE;
    VkShaderStageFlags pushStages_ = 0;
    uint32_t pushOffset_ = 0;
    std::vector<std::byte> pushData_;
    VkQueryPool profilePool_ = VK_NULL_HANDLE;
    uint32_t profileCapacity_ = 0;
    uint32_t profileOperationOffset_ = 0;
    VkPipeline profilePipeline_ = VK_NULL_HANDLE;
    uint32_t profileOperation_ = 0;
    std::vector<DispatchSample> profileSamples_;
};
}
