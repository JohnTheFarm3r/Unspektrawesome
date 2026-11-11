#pragma once

#include <atomic>
#include <cstdint>
#include <vulkan/vulkan.h>

struct PipelineCreationStats {
  bool feedbackEnabled = false;
  std::atomic<uint64_t> created{0};
  std::atomic<uint64_t> cacheHits{0};
  std::atomic<uint64_t> cacheMisses{0};
  std::atomic<uint64_t> unreported{0};
};

class PipelineCreationFeedback {
public:
  explicit PipelineCreationFeedback(PipelineCreationStats *stats) : stats_(stats) {
    info_.pPipelineCreationFeedback = &feedback_;
  }

  const void *info() const {
    return stats_ && stats_->feedbackEnabled ? &info_ : nullptr;
  }

  void record() const {
    if (!stats_) return;
    stats_->created.fetch_add(1, std::memory_order_relaxed);
    if (!(feedback_.flags & VK_PIPELINE_CREATION_FEEDBACK_VALID_BIT_EXT))
      stats_->unreported.fetch_add(1, std::memory_order_relaxed);
    else if (feedback_.flags & VK_PIPELINE_CREATION_FEEDBACK_APPLICATION_PIPELINE_CACHE_HIT_BIT_EXT)
      stats_->cacheHits.fetch_add(1, std::memory_order_relaxed);
    else
      stats_->cacheMisses.fetch_add(1, std::memory_order_relaxed);
  }

private:
  PipelineCreationStats *stats_;
  VkPipelineCreationFeedbackEXT feedback_{};
  VkPipelineCreationFeedbackCreateInfoEXT info_{
    VK_STRUCTURE_TYPE_PIPELINE_CREATION_FEEDBACK_CREATE_INFO_EXT};
};
