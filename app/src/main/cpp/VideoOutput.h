#pragma once

#include <functional>
#include <chrono>
#include <android/log.h>
#include <android/data_space.h>

class VideoOutput {
public:
    static bool setColorSpace(ANativeWindow* window, bool fullRange)
    {
        return ANativeWindow_setBuffersDataSpace(window, ADATASPACE_STANDARD_BT709 |
            ADATASPACE_TRANSFER_SMPTE_170M | (fullRange ? ADATASPACE_RANGE_FULL : ADATASPACE_RANGE_LIMITED)) == 0;
    }

#if !defined(NDEBUG)
    const bool traceFrames = __android_log_is_loggable(ANDROID_LOG_VERBOSE, "VideoFrame", ANDROID_LOG_INFO) != 0;
#endif

    VideoOutput(VkInstance instance, VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                uint32_t queueFamily, uint32_t frames)
        : instance_(instance), physical_(physical), device_(device), queue_(queue),
          queueFamily_(queueFamily), available_(frames, VK_NULL_HANDLE) {}

    ~VideoOutput()
    {
        if (pending_ != VK_NULL_HANDLE) {
            VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.waitSemaphoreCount = 1;
            submit.pWaitSemaphores = &pending_;
            submit.pWaitDstStageMask = &stage;
            vkQueueSubmit(queue_, 1, &submit, VK_NULL_HANDLE);
        }
        vkDeviceWaitIdle(device_);
        for (auto framebuffer : framebuffers_) vkDestroyFramebuffer(device_, framebuffer, nullptr);
        if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
        if (layout_) vkDestroyPipelineLayout(device_, layout_, nullptr);
        if (renderPass_) vkDestroyRenderPass(device_, renderPass_, nullptr);
        for (auto view : views_) vkDestroyImageView(device_, view, nullptr);
        for (auto semaphore : available_) if (semaphore) vkDestroySemaphore(device_, semaphore, nullptr);
        for (auto semaphore : finished_) if (semaphore) vkDestroySemaphore(device_, semaphore, nullptr);
        if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        if (window_) ANativeWindow_release(window_);
    }

    void create(JNIEnv* environment, jobject surface, uint32_t width, uint32_t height, bool fullRange,
                VkDescriptorSetLayout descriptorLayout,
                const std::function<VkPipeline(VkRenderPass, VkPipelineLayout)>& createPipeline)
    {
        window_ = ANativeWindow_fromSurface(environment, surface);
        if (!window_) throw std::runtime_error("Video surface is unavailable");
        VkAndroidSurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        surfaceInfo.window = window_;
        check(vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_), "video surface");
        VkBool32 supported = VK_FALSE;
        check(vkGetPhysicalDeviceSurfaceSupportKHR(physical_, queueFamily_, surface_, &supported), "video presentation support");
        if (!supported) throw std::runtime_error("Video surface cannot use the preview queue");
        VkSurfaceCapabilitiesKHR caps{};
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps), "video surface capabilities");
        if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
            throw std::runtime_error("Video surface cannot receive GPU frames");
        extent_ = {width, height};
        if (width < caps.minImageExtent.width || height < caps.minImageExtent.height ||
            width > caps.maxImageExtent.width || height > caps.maxImageExtent.height ||
            (caps.currentExtent.width != UINT32_MAX &&
             (caps.currentExtent.width != width || caps.currentExtent.height != height)))
            throw std::runtime_error("Video surface size does not match the encoder");
        uint32_t count = 0;
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &count, nullptr), "video formats");
        std::vector<VkSurfaceFormatKHR> formats(count);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &count, formats.data()), "video formats");
        auto selected = std::find_if(formats.begin(), formats.end(), [](const auto& format) {
            return format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM;
        });
        if (selected == formats.end()) throw std::runtime_error("Video surface has no SDR RGBA format");
        VkSwapchainCreateInfoKHR swapchainInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        swapchainInfo.surface = surface_;
        const uint32_t minimumCount = std::max(caps.minImageCount, 3u);
        const uint64_t imageBytes = static_cast<uint64_t>(width) * height * 4;
        // Add up to five input buffers, with at most 64 MiB of extra RGBA storage.
        const uint32_t extraCount = static_cast<uint32_t>(std::min<uint64_t>(5, (64u * 1024u * 1024u) / imageBytes));
        swapchainInfo.minImageCount = minimumCount + extraCount;
        if (caps.maxImageCount) swapchainInfo.minImageCount = std::min(swapchainInfo.minImageCount, caps.maxImageCount);
        swapchainInfo.imageFormat = selected->format;
        swapchainInfo.imageColorSpace = selected->colorSpace;
        swapchainInfo.imageExtent = extent_;
        swapchainInfo.imageArrayLayers = 1;
        swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchainInfo.preTransform = caps.currentTransform;
        swapchainInfo.compositeAlpha = caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
            ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        swapchainInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchainInfo.clipped = VK_TRUE;
        VkResult created = vkCreateSwapchainKHR(device_, &swapchainInfo, nullptr, &swapchain_);
        while (created != VK_SUCCESS && swapchainInfo.minImageCount > caps.minImageCount) {
            --swapchainInfo.minImageCount;
            created = vkCreateSwapchainKHR(device_, &swapchainInfo, nullptr, &swapchain_);
        }
        check(created, "video swapchain");
        // Keep the buffer color description aligned with the configured encoder.
        if (!setColorSpace(window_, fullRange)) {
            // Older Android versions reject encoder dataspaces through the NDK API.
            if (fullRange || ANativeWindow_setBuffersDataSpace(window_, ADATASPACE_UNKNOWN) != 0)
                throw std::runtime_error("Video surface color space is unavailable");
        }
        check(vkGetSwapchainImagesKHR(device_, swapchain_, &count, nullptr), "video buffers");
        std::vector<VkImage> images(count);
        check(vkGetSwapchainImagesKHR(device_, swapchain_, &count, images.data()), "video buffers");
#if !defined(NDEBUG)
        __android_log_print(ANDROID_LOG_INFO, "UnspekTrace",
            "[VideoRecording] encoder input buffers=%u requested=%u range=%u..%u size=%ux%u",
            count, swapchainInfo.minImageCount, caps.minImageCount, caps.maxImageCount, width, height);
#endif
        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        finished_.resize(count, VK_NULL_HANDLE);
        for (auto& semaphore : available_) check(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &semaphore), "video acquire semaphore");
        for (auto& semaphore : finished_) check(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &semaphore), "video present semaphore");

        VkAttachmentDescription attachment{};
        attachment.format = selected->format;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &reference;
        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo renderInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        renderInfo.attachmentCount = 1;
        renderInfo.pAttachments = &attachment;
        renderInfo.subpassCount = 1;
        renderInfo.pSubpasses = &subpass;
        renderInfo.dependencyCount = 1;
        renderInfo.pDependencies = &dependency;
        check(vkCreateRenderPass(device_, &renderInfo, nullptr, &renderPass_), "video render pass");
        VkPushConstantRange constants{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float)};
        VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &descriptorLayout;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &constants;
        check(vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &layout_), "video pipeline layout");
        pipeline_ = createPipeline(renderPass_, layout_);
        for (auto image : images) {
            VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = selected->format;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            VkImageView view = VK_NULL_HANDLE;
            check(vkCreateImageView(device_, &viewInfo, nullptr, &view), "video image view");
            views_.push_back(view);
            VkFramebufferCreateInfo framebufferInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            framebufferInfo.renderPass = renderPass_;
            framebufferInfo.attachmentCount = 1;
            framebufferInfo.pAttachments = &view;
            framebufferInfo.width = width;
            framebufferInfo.height = height;
            framebufferInfo.layers = 1;
            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            check(vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffer), "video framebuffer");
            framebuffers_.push_back(framebuffer);
        }
    }

    bool acquire(uint32_t frame)
    {
        // A positive timeout disables Android's queued-buffer replacement and absorbs brief encoder stalls.
        // Keep the wait bounded so a stalled encoder cannot stop the rendering worker indefinitely.
        constexpr uint64_t timeoutNs = 50'000'000;
        const auto started = std::chrono::steady_clock::now();
        auto result = vkAcquireNextImageKHR(device_, swapchain_, timeoutNs, available_.at(frame), VK_NULL_HANDLE, &image_);
        inputWaitNs = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started).count();
        if (result == VK_NOT_READY || result == VK_TIMEOUT) {
#if !defined(NDEBUG)
            __android_log_print(ANDROID_LOG_INFO, "UnspekTrace",
                "[VideoRecording] encoder buffer unavailable after %lld submitted frames", static_cast<long long>(frames));
#endif
            return false;
        }
        check(result, "acquire video frame");
        pending_ = available_.at(frame);
        return true;
    }

    VkSemaphore available() const { return pending_; }
    VkSemaphore finished() const { return finished_.at(image_); }
    void submitted() { pending_ = VK_NULL_HANDLE; }

    void record(VkCommandBuffer command, VkDescriptorSet descriptor, bool portrait)
    {
        VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        begin.renderPass = renderPass_;
        begin.framebuffer = framebuffers_.at(image_);
        begin.renderArea.extent = extent_;
        vkCmdBeginRenderPass(command, &begin, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport viewport{0, 0, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0, 1};
        VkRect2D scissor{{0, 0}, extent_};
        vkCmdSetViewport(command, 0, 1, &viewport);
        vkCmdSetScissor(command, 0, 1, &scissor);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout_, 0, 1, &descriptor, 0, nullptr);
        const float rotate = portrait ? 1.0f : 0.0f;
        vkCmdPushConstants(command, layout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(rotate), &rotate);
        vkCmdDraw(command, 3, 1, 0, 0);
        vkCmdEndRenderPass(command);
    }

    void present(int64_t timestamp)
    {
        VkPresentTimeGOOGLE time{++presentId_, static_cast<uint64_t>(timestamp)};
        VkPresentTimesInfoGOOGLE timing{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE};
        timing.swapchainCount = 1;
        timing.pTimes = &time;
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.pNext = &timing;
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &finished_.at(image_);
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &image_;
        const auto started = std::chrono::steady_clock::now();
        const auto result = vkQueuePresentKHR(queue_, &present);
        inputWaitNs += std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started).count();
        check(result, "present video frame");
        ++frames;
    }

    int64_t frames = 0;
    int64_t inputWaitNs = -1;
    int64_t inputCapacity() const { return static_cast<int64_t>(finished_.size()); }

private:
    static void check(VkResult result, const char* operation)
    {
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR)
            throw std::runtime_error(std::string(operation) + ": " + std::to_string(result));
    }
    VkInstance instance_;
    VkPhysicalDevice physical_;
    VkDevice device_;
    VkQueue queue_;
    uint32_t queueFamily_;
    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkExtent2D extent_{};
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    std::vector<VkImageView> views_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkSemaphore> available_;
    std::vector<VkSemaphore> finished_;
    VkSemaphore pending_ = VK_NULL_HANDLE;
    uint32_t image_ = 0;
    uint32_t presentId_ = 0;
};
