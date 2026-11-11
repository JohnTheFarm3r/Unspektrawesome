#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdint>
#include <vector>
#include <sys/system_properties.h>
#include <vulkan/vulkan.h>
#include "NativeDiagnostics.h"

inline bool previewShadingReuse()
{
#if !defined(NDEBUG)
    char value[PROP_VALUE_MAX]{};
    __system_property_get("debug.unspektra.preview_shading", value);
    if (value[0] == '0') return false;
    if (value[0] == '1') return true;
#endif
    return true;
}

struct RawPreviewCpuTiming {
    using Clock = std::chrono::steady_clock;
    bool enabled = false;
    Clock::time_point started{};
    double fenceMs = 0;
    double uploadMs = 0;
    double acquireMs = 0;
    double spektraMs = 0;
    double presentMs = 0;
    double wallMs = 0;
    double cameraGpuMs = 0;

    explicit RawPreviewCpuTiming(bool measurePipeline = false)
    {
#if !defined(NDEBUG)
        char value[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.raw_perf", value);
        enabled = value[0] == '1';
#endif
        if (enabled || measurePipeline) started = Clock::now();
    }

    Clock::time_point now() const { return enabled ? Clock::now() : Clock::time_point{}; }
    double since(Clock::time_point start) const
    {
        return enabled ? std::chrono::duration<double, std::milli>(Clock::now() - start).count() : 0;
    }
};

class RawPreviewMetrics {
public:
    double prepare(VkDevice device, VkPhysicalDevice physical, uint32_t queueFamily,
                 bool enabled, bool logging, bool reuse, bool tiled, bool spektra, bool raster, bool texture, bool unpack, int32_t prefilter,
                 const int32_t* dimensions, int32_t format, int32_t cfa,
                 uint32_t mapWidth, uint32_t mapHeight, uint32_t remosaicWidth, uint32_t remosaicHeight,
                 bool chroma = false,
                 int64_t* workPeriodStartNs = nullptr)
    {
        const bool compatible = spektra_ == spektra && prefilter_ == prefilter && chroma_ == chroma &&
            remosaicWidth_ == remosaicWidth && remosaicHeight_ == remosaicHeight &&
            std::equal(dimensions_.begin(), dimensions_.end(), dimensions);
        const int64_t previousStartNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            cpu_.started.time_since_epoch()).count();
        const double previousSpanMs = collect(device);
        if (workPeriodStartNs) *workPeriodStartNs = compatible && previousSpanMs > 0 ? previousStartNs : 0;
        active_ = enabled;
        logging_ = logging;
        if (!enabled) return -1.0;
        reuse_ = reuse;
        tiled_ = tiled;
        spektra_ = spektra;
        raster_ = raster;
        texture_ = texture;
        unpack_ = unpack;
        prefilter_ = prefilter;
        chroma_ = chroma;
        for (size_t i = 0; i < dimensions_.size(); ++i) dimensions_[i] = dimensions[i];
        format_ = format;
        cfa_ = cfa;
        mapWidth_ = mapWidth;
        mapHeight_ = mapHeight;
        remosaicWidth_ = remosaicWidth;
        remosaicHeight_ = remosaicHeight;
        if (!attempted_) {
            attempted_ = true;
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physical, &properties);
            uint32_t count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
            std::vector<VkQueueFamilyProperties> families(count);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
            const uint32_t bits = families.at(queueFamily).timestampValidBits;
            period_ = properties.limits.timestampPeriod;
            if (bits > 0 && period_ > 0) {
                mask_ = bits >= 64 ? UINT64_MAX : (uint64_t{1} << bits) - 1;
                VkQueryPoolCreateInfo info{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
                info.queryType = VK_QUERY_TYPE_TIMESTAMP;
                info.queryCount = 6;
                if (vkCreateQueryPool(device, &info, nullptr, &pool_) != VK_SUCCESS)
                    pool_ = VK_NULL_HANDLE;
            }
            if (logging_) log(
                "support gpu=%s timestamps=%d bits=%u period_ns=%.6f",
                properties.deviceName, pool_ != VK_NULL_HANDLE, bits, period_);
        }
        return compatible ? previousSpanMs : -1.0;
    }

    void beginGpu(VkCommandBuffer command)
    {
        if (active_ && pool_ != VK_NULL_HANDLE) {
            vkCmdResetQueryPool(command, pool_, 0, 6);
            vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool_, 0);
        }
    }

    void endRemosaicGpu(VkCommandBuffer command) { timestamp(command, 1); }
    void endPrepareGpu(VkCommandBuffer command) { timestamp(command, 4); }
    void endRawGpu(VkCommandBuffer command) { timestamp(command, 2); }
    void endChromaGpu(VkCommandBuffer command) { timestamp(command, 5); }
    void endFrameGpu(VkCommandBuffer command) { timestamp(command, 3); }

    void submitted(VkDevice device, const RawPreviewCpuTiming& cpu)
    {
        if (!active_) return;
        cpu_ = cpu;
        pending_ = true;
        active_ = false;
        if (pool_ == VK_NULL_HANDLE) collect(device);
    }

    void cancel()
    {
        active_ = false;
        pending_ = false;
    }

    void destroy(VkDevice device)
    {
        collect(device);
        if (pool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device, pool_, nullptr);
        *this = {};
    }

private:
    void timestamp(VkCommandBuffer command, uint32_t query)
    {
        if (active_ && pool_ != VK_NULL_HANDLE)
            vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool_, query);
    }

    static void log(const char* format, ...)
    {
        char message[1536];
        va_list arguments;
        va_start(arguments, format);
        std::vsnprintf(message, sizeof(message), format, arguments);
        va_end(arguments);
        __android_log_write(ANDROID_LOG_INFO, "RawPreviewPerf", message);
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf", "%s", message);
    }

    double collect(VkDevice device)
    {
        if (!pending_) return -1.0;
        pending_ = false;
        std::array<uint64_t, 12> queries{};
        const bool available = pool_ != VK_NULL_HANDLE &&
            vkGetQueryPoolResults(device, pool_, 0, 6, sizeof(queries), queries.data(),
                2 * sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT) == VK_SUCCESS &&
            queries[1] && queries[3] && queries[5] && queries[7] && queries[9] && queries[11];
        const double hostElapsedMs = std::chrono::duration<double, std::milli>(
            RawPreviewCpuTiming::Clock::now() - cpu_.started).count();
        const auto elapsed = [&](size_t end) {
            if (!available) return -1.0;
            const double milliseconds = static_cast<double>((queries[end] - queries[0]) & mask_) * period_ / 1e6;
            return milliseconds <= hostElapsedMs + 100.0 ? milliseconds : -1.0;
        };
        const double remosaicMs = elapsed(2);
        const double prepareMs = elapsed(8);
        const double rawMs = elapsed(4) >= 0 && cpu_.cameraGpuMs >= 0 ? elapsed(4) + cpu_.cameraGpuMs : -1.0;
        const double spanMs = elapsed(6) >= 0 && cpu_.cameraGpuMs >= 0 ? elapsed(6) + cpu_.cameraGpuMs : -1.0;
        const double chromaMs = elapsed(10) >= 0 && elapsed(4) >= 0 ? elapsed(10) - elapsed(4) : -1.0;
        if (logging_) log(
            "frame reuse=%d tiled=%d spektra=%d raster=%d texture=%d unpack=%d prefilter=%d raw_w=%d raw_h=%d width=%d height=%d format=%d cfa=%d map_w=%u map_h=%u remosaic_w=%u remosaic_h=%u "
            "gpu_available=%d clock_disjoint=%d remosaic_gpu_ms=%.6f raw_gpu_ms=%.6f gpu_span_ms=%.6f prepare_gpu_ms=%.6f cpu_wall_ms=%.3f fence_ms=%.3f upload_ms=%.3f acquire_ms=%.3f spektra_cpu_ms=%.3f present_cpu_ms=%.3f camera_gpu_ms=%.6f chroma=%d chroma_gpu_ms=%.6f",
            reuse_, tiled_, spektra_, raster_, texture_, unpack_, prefilter_, dimensions_[0], dimensions_[1], dimensions_[2], dimensions_[3],
            format_, cfa_, mapWidth_, mapHeight_, remosaicWidth_, remosaicHeight_,
            available, available && (rawMs < 0 || spanMs < 0), remosaicMs, rawMs, spanMs,
            prepareMs >= 0 && remosaicMs >= 0 ? prepareMs - remosaicMs : -1.0,
            cpu_.wallMs, cpu_.fenceMs, cpu_.uploadMs, cpu_.acquireMs, cpu_.spektraMs, cpu_.presentMs, cpu_.cameraGpuMs,
            chroma_, chromaMs);
        return rawMs >= 0 ? spanMs : -1.0;
    }

    VkQueryPool pool_ = VK_NULL_HANDLE;
    bool attempted_ = false;
    bool active_ = false;
    bool pending_ = false;
    bool logging_ = false;
    bool reuse_ = false;
    bool tiled_ = false;
    bool spektra_ = false;
    bool raster_ = false;
    bool texture_ = false;
    bool unpack_ = false;
    bool chroma_ = false;
    int32_t prefilter_ = 0;
    std::array<int32_t, 4> dimensions_{};
    int32_t format_ = 0;
    int32_t cfa_ = 0;
    uint32_t mapWidth_ = 0;
    uint32_t mapHeight_ = 0;
    uint32_t remosaicWidth_ = 0;
    uint32_t remosaicHeight_ = 0;
    uint64_t mask_ = 0;
    double period_ = 0;
    RawPreviewCpuTiming cpu_{};
};
