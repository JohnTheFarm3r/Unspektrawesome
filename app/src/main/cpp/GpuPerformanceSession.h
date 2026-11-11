#pragma once

#include <vulkan/vulkan.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <pthread.h>
#include <sys/system_properties.h>
#include "NativeDiagnostics.h"
#include "gpu_performance_compute_spv.h"

class GpuPerformanceSession {
public:
    GpuPerformanceSession() = default;
    GpuPerformanceSession(const GpuPerformanceSession&) = delete;
    GpuPerformanceSession& operator=(const GpuPerformanceSession&) = delete;
    ~GpuPerformanceSession() { stop(); }

    void start(VkPhysicalDevice physical, uint32_t queueFamily)
    {
        if (attempted_) return;
        attempted_ = true;
#if !defined(NDEBUG)
        char boostOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.gpu_boost", boostOverride);
        if (boostOverride[0] == '0') return;
#endif
        try {
            prepare(physical, queueFamily);
            stopping_ = false;
            worker_ = std::thread([this] { run(); });
            recordNativeDiagnostic(ANDROID_LOG_INFO, "GpuPerformance", "Compute performance session started");
        } catch (const std::exception& error) {
            stop();
            attempted_ = true;
            recordNativeDiagnostic(ANDROID_LOG_WARN, "GpuPerformance", "Performance session unavailable: %s", error.what());
        }
    }

    void stop()
    {
        attempted_ = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
        }
        changed_.notify_all();
        if (worker_.joinable()) worker_.join();
        if (device_ == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(device_);
        if (fence_) vkDestroyFence(device_, fence_, nullptr);
        if (commands_) vkDestroyCommandPool(device_, commands_, nullptr);
        if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
        if (layout_) vkDestroyPipelineLayout(device_, layout_, nullptr);
        if (shader_) vkDestroyShaderModule(device_, shader_, nullptr);
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        queue_ = VK_NULL_HANDLE;
        fence_ = VK_NULL_HANDLE;
        commands_ = VK_NULL_HANDLE;
        command_ = VK_NULL_HANDLE;
        pipeline_ = VK_NULL_HANDLE;
        layout_ = VK_NULL_HANDLE;
        shader_ = VK_NULL_HANDLE;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "GpuPerformance", "Compute performance session stopped");
    }

private:
    static void check(VkResult result)
    {
        if (result != VK_SUCCESS)
            throw std::runtime_error("Vulkan result " + std::to_string(result));
    }

    void prepare(VkPhysicalDevice physical, uint32_t queueFamily)
    {
        // Graphics submissions disable the compute boost on some drivers for the device's lifetime.
        // Keep this small compute-only context separate from the image renderer.
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = queueFamily;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        check(vkCreateDevice(physical, &deviceInfo, nullptr, &device_));
        vkGetDeviceQueue(device_, queueFamily, 0, &queue_);

        VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        shaderInfo.codeSize = kGpuPerformanceSpirv.size() * sizeof(uint32_t);
        shaderInfo.pCode = kGpuPerformanceSpirv.data();
        check(vkCreateShaderModule(device_, &shaderInfo, nullptr, &shader_));
        VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        check(vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &layout_));
        VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipelineInfo.layout = layout_;
        pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipelineInfo.stage.module = shader_;
        pipelineInfo.stage.pName = "main";
        check(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline_));

        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.queueFamilyIndex = queueFamily;
        check(vkCreateCommandPool(device_, &poolInfo, nullptr, &commands_));
        VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        allocation.commandPool = commands_;
        allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocation.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device_, &allocation, &command_));
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        check(vkBeginCommandBuffer(command_, &begin));
        vkCmdBindPipeline(command_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdDispatch(command_, 1, 1, 1);
        check(vkEndCommandBuffer(command_));
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        check(vkCreateFence(device_, &fenceInfo, nullptr, &fence_));
    }

    void run()
    {
        pthread_setname_np(pthread_self(), "GpuPerformance");
        try {
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &command_;
            std::unique_lock<std::mutex> lock(mutex_);
            while (!stopping_) {
                lock.unlock();
                const VkResult ready = vkGetFenceStatus(device_, fence_);
                if (ready == VK_SUCCESS) {
                    check(vkResetFences(device_, 1, &fence_));
                    check(vkQueueSubmit(queue_, 1, &submit, fence_));
                } else if (ready != VK_NOT_READY) {
                    check(ready);
                }
                lock.lock();
                changed_.wait_for(lock, std::chrono::milliseconds(33), [this] { return stopping_; });
            }
        } catch (const std::exception& error) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "GpuPerformance", "Performance submission stopped: %s", error.what());
        }
    }

    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkShaderModule shader_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commands_ = VK_NULL_HANDLE;
    VkCommandBuffer command_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    std::thread worker_;
    std::mutex mutex_;
    std::condition_variable changed_;
    bool stopping_ = true;
    bool attempted_ = false;
};
