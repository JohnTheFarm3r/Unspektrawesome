#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vulkan/vulkan.h>

#include "SpektraRenderer.h"
#include "PipelineCreationStats.h"

namespace spektrafilm {

using VulkanPassDiagnostics = RendererPassDiagnostics;
using VulkanRenderDiagnostics = RendererDiagnostics;

struct ExternalVulkanContext {
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue computeQueue = VK_NULL_HANDLE;
  uint32_t computeQueueFamily = 0;
  std::recursive_mutex *queueMutex = nullptr;
  VkPipelineCache pipelineCache = VK_NULL_HANDLE;
  std::atomic<uint64_t> *pipelineCacheRevision = nullptr;
  PipelineCreationStats *pipelineCreationStats = nullptr;

  bool isValid() const {
    return instance != VK_NULL_HANDLE &&
      physicalDevice != VK_NULL_HANDLE &&
      device != VK_NULL_HANDLE &&
      computeQueue != VK_NULL_HANDLE &&
      queueMutex != nullptr;
  }
};

struct ExternalImageViews {
  // The caller retains these views until submitted work completes and changes
  // generation whenever either owning image is replaced, even if handles repeat.
  VkImageView source = VK_NULL_HANDLE;
  VkImageView destination = VK_NULL_HANDLE;
  uint64_t generation = 0;
};

class VulkanRenderer final : public Renderer {
public:
  VulkanRenderer();
  explicit VulkanRenderer(const ExternalVulkanContext &externalContext);
  ~VulkanRenderer() override;

  VulkanRenderer(const VulkanRenderer &) = delete;
  VulkanRenderer &operator=(const VulkanRenderer &) = delete;

  bool isAvailable() const override;
  const VulkanRenderDiagnostics &lastDiagnostics() const override;
  const std::string &lastError() const override;
  void releaseTransientResources() override;
  void resetPreviewScheduling();
  static uint32_t startupPipelinePartCount(bool preview, uint32_t step);
  bool prepareStartupPipelines(bool preview, uint32_t step, int32_t part = -1);

  // The caller waits for a slot's GPU work before reusing it or destroying the renderer.
  bool recordAutoExposure(
    VkCommandBuffer commands, uint32_t slot, VkBuffer sampledSource,
    uint32_t width, uint32_t height, AutoExposureMethod method, VkBuffer result,
    float tapX = -1.0f, float tapY = -1.0f
  );

  bool renderVulkanImages(
    VkImage sourceImage,
    VkImage destinationImage,
    uint32_t width,
    uint32_t height,
    const RenderParams &params,
    double time,
    bool optimizePreview = false,
    VkBuffer exposureRange = VK_NULL_HANDLE,
    ExternalImageViews views = {},
    bool videoEffects = false
  );

  bool render(
    const ImageView &source,
    const MutableImageView &destination,
    const RenderWindow &window,
    const RenderParams &params,
    double time
  ) override;

private:
  struct Impl;
  ExternalVulkanContext externalContext_{};
  bool hasExternalContext_ = false;
  std::unique_ptr<Impl> impl_;
  VulkanRenderDiagnostics lastRenderDiagnostics_;
  std::string lastRenderError_;
};

} // namespace spektrafilm
