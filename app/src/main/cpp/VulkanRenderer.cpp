#include "NativeBindings.h"
#define VK_USE_PLATFORM_ANDROID_KHR 1

#include <jni.h>
#include "VulkanDiagnostics.h"
#include "RawUploadPool.h"

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <sys/system_properties.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <time.h>

#include "histogram_compute_spv.h"
#include "raw_image_probe_compute_spv.h"
#include "isp_preview_image_compute_spv.h"
#include "isp_preview_buffer_compute_spv.h"
#include "isp_preview_meter_image_compute_spv.h"
#include "isp_preview_meter_buffer_compute_spv.h"
#include "present_fragment_spv.h"
#include "present_vertex_spv.h"
#include "video_fragment_spv.h"
#include "VideoOutput.h"
#include "preview_bilinear_bilinear_compute_spv.h"
#include "preview_bilinear_raw_image_compute_spv.h"
#include "preview_bilinear_mhc_compute_spv.h"
#include "preview_bilinear_mhc_raw_image_compute_spv.h"
#include "preview_bilinear_meter_fixed_compute_spv.h"
#include "preview_bilinear_meter_image_fixed_compute_spv.h"
#include "preview_bilinear_meter_remosaic_compute_spv.h"
#include "preview_bilinear_shading_compute_spv.h"
#include "preview_bilinear_shading_raw_image_compute_spv.h"
#include "capture_color_compute_spv.h"
#include "shadhi_luma_compute_spv.h"
#include "shadhi_bilateral_compute_spv.h"
#include "shadow_color_compute_spv.h"
#include "preview_hr_seed_compute_spv.h"
#include "preview_hr_reduce_compute_spv.h"
#include "preview_hr_apply_compute_spv.h"
#include "post_chroma_denoise_compute_spv.h"
#include "video_chroma_compute_spv.h"
#include "raw_hot_pixels_compute_spv.h"
#include "raw_hot_pixels_image_compute_spv.h"
#include "focus_peaking_compute_spv.h"
#include "preview_average_compute_spv.h"
#include "focus_peaking_overlay_compute_spv.h"
#include "focus_loupe_prepare_compute_spv.h"
#include "focus_loupe_demosaic_compute_spv.h"
#include "focus_loupe_light_compute_spv.h"
#include "focus_loupe_remosaic_compute_spv.h"
#include "focus_loupe_score_compute_spv.h"
#include "focus_loupe_finish_compute_spv.h"
#include "focus_loupe_fragment_spv.h"
#include "FocusLoupe.h"
#include "amaze_normalize_buffer_compute_spv.h"
#include "amaze_normalize_image_compute_spv.h"
#include "amaze_stage0_buffer_compute_spv.h"
#include "amaze_stage0_image_compute_spv.h"
#include "amaze_stage1_buffer_compute_spv.h"
#include "amaze_stage1_image_compute_spv.h"
#include "amaze_stage2_buffer_compute_spv.h"
#include "amaze_stage2_image_compute_spv.h"
#include "amaze_stage3_buffer_compute_spv.h"
#include "amaze_stage3_image_compute_spv.h"
#include "amaze_stage4_buffer_compute_spv.h"
#include "amaze_stage4_image_compute_spv.h"
#include "amaze_stage5_buffer_compute_spv.h"
#include "amaze_stage5_image_compute_spv.h"
#include "amaze_stage6_buffer_compute_spv.h"
#include "amaze_stage6_image_compute_spv.h"
#include "amaze_stage7_buffer_compute_spv.h"
#include "amaze_stage7_image_compute_spv.h"
#include "amaze_hr_opposed_seed_compute_spv.h"
#include "amaze_hr_opposed_seed_raw_image_compute_spv.h"
#include "amaze_hr_opposed_reduce_compute_spv.h"
#include "amaze_hr_opposed_reduce_raw_image_compute_spv.h"
#include "amaze_recovery_compute_spv.h"
#include "amaze_recovery_raw_image_compute_spv.h"
#include "SpektraVulkanRenderer.h"
#include "PipelineCache.h"
#include "CooperativeCompute.h"
#include "NativeDiagnostics.h"
#include "RawPreviewMetrics.h"
#include "GpuPerformanceSession.h"
#include "RawPreviewQuality.h"
#include "RawPreviewCompatibility.h"
#include "CameraBayerBridge.h"
#include "preview_bayer_cell_input_0_compute_spv.h"
#include "preview_bayer_cell_input_1_compute_spv.h"
#include "preview_bayer_cell_input_2_compute_spv.h"
#include "preview_bayer_cell_input_3_compute_spv.h"
#include "preview_bayer_cell_compat_0_fixed_compute_spv.h"
#include "preview_bayer_cell_compat_1_fixed_compute_spv.h"
#include "preview_bayer_cell_compat_2_fixed_compute_spv.h"
#include "preview_bayer_cell_compat_3_fixed_compute_spv.h"
#include "preview_bilinear_cells_meter_compute_spv.h"
#include "preview_bayer_cell_raster_fragment_spv.h"
#include "recording_downsample_input_0_compute_spv.h"
#include "recording_downsample_input_1_compute_spv.h"
#include "recording_downsample_input_2_compute_spv.h"
#include "recording_downsample_tile_0_compute_spv.h"
#include "recording_downsample_tile_1_compute_spv.h"
#include "recording_downsample_tile_2_compute_spv.h"
#include "recording_downsample_compat_0_fixed_compute_spv.h"
#include "recording_downsample_compat_1_fixed_compute_spv.h"
#include "recording_downsample_compat_2_fixed_compute_spv.h"
#include "recording_downsample_raster_fragment_spv.h"
#include "recording_native_input_0_0_compute_spv.h"
#include "recording_native_input_0_1_compute_spv.h"
#include "recording_native_input_0_2_compute_spv.h"
#include "recording_native_input_1_0_compute_spv.h"
#include "recording_native_input_1_1_compute_spv.h"
#include "recording_native_input_1_2_compute_spv.h"
#include "recording_native_compat_0_0_fixed_compute_spv.h"
#include "recording_native_compat_0_1_fixed_compute_spv.h"
#include "recording_native_compat_0_2_fixed_compute_spv.h"
#include "recording_native_compat_1_0_fixed_compute_spv.h"
#include "recording_native_compat_1_1_fixed_compute_spv.h"
#include "recording_native_compat_1_2_fixed_compute_spv.h"
#include "preview_bilinear_recording_meter_compute_spv.h"
#include "preview_bilinear_light_0_0_compute_spv.h"
#include "preview_bilinear_light_0_1_compute_spv.h"
#include "preview_bilinear_light_1_0_compute_spv.h"
#include "preview_bilinear_light_1_1_compute_spv.h"
#include "preview_bilinear_light_remosaic_fixed_compute_spv.h"
#include "preview_bilinear_compat_0_0_fixed_compute_spv.h"
#include "preview_bilinear_compat_0_1_fixed_compute_spv.h"
#include "preview_bilinear_compat_0_2_fixed_compute_spv.h"
#include "preview_bilinear_compat_1_0_fixed_compute_spv.h"
#include "preview_bilinear_compat_1_1_fixed_compute_spv.h"
#include "preview_bilinear_compat_1_2_fixed_compute_spv.h"
#include "preview_bilinear_compat_2_0_fixed_compute_spv.h"
#include "preview_bilinear_compat_2_1_fixed_compute_spv.h"
#include "preview_bilinear_compat_2_2_fixed_compute_spv.h"
#include "preview_bilinear_light_fast_0_compute_spv.h"
#include "preview_bilinear_light_raster_0_fragment_spv.h"
#include "preview_bilinear_light_fast_1_compute_spv.h"
#include "preview_bilinear_light_raster_1_fragment_spv.h"
#include "preview_bilinear_light_fast_2_compute_spv.h"
#include "preview_bilinear_light_raster_2_fragment_spv.h"
#include "preview_bilinear_fast_0_compute_spv.h"
#include "preview_bilinear_fast_1_compute_spv.h"
#include "preview_bilinear_fast_2_compute_spv.h"
#include "preview_bilinear_raster_0_fragment_spv.h"
#include "preview_bilinear_raster_1_fragment_spv.h"
#include "preview_bilinear_raster_2_fragment_spv.h"
#include "bayer_remosaic_compute_spv.h"
#include "bayer_remosaic_raw_image_compute_spv.h"
#include "preview_native_prefilter_0_compute_spv.h"
#include "preview_native_prefilter_1_compute_spv.h"
#include "preview_native_prefilter_2_compute_spv.h"
#include "preview_native_format_0_compute_spv.h"
#include "preview_native_format_1_compute_spv.h"
#include "preview_native_format_2_compute_spv.h"
#include "raw_area_weights_compute_spv.h"
#include "raw_area_format_0_compute_spv.h"
#include "raw_area_format_1_compute_spv.h"
#include "raw_area_format_2_compute_spv.h"
#include "raw_unpack_format_0_compute_spv.h"
#include "raw_unpack_format_1_compute_spv.h"
#include "raw_unpack_format_2_compute_spv.h"

extern "C" bool SpektraFilmRenderVulkanImagesWithRendererNative(
    spektrafilm::VulkanRenderer* renderer,
    VkImage sourceImage,
    VkImage destinationImage,
    uint32_t width,
    uint32_t height,
    const void* paramsData,
    size_t paramsSize,
    double timeSeconds,
    bool optimizePreview,
    const char** errorMessage,
    VkBuffer exposureRange = VK_NULL_HANDLE,
    spektrafilm::ExternalImageViews views = {},
    bool videoEffects = false);

namespace {

constexpr uint32_t kFramesInFlight = 2;
constexpr jint kWarmupStepCount = 55;
constexpr jint kExportWarmupStepCount = 41;
constexpr VkDeviceSize kRawParametersSize = 80;
constexpr VkDeviceSize kRawColorParametersSize = 144;
constexpr VkDeviceSize kPinholeGeometryParametersSize = 96 + 32 + 33 * 48;
constexpr VkDeviceSize kGeometryParametersSize = kPinholeGeometryParametersSize + 48;
constexpr VkDeviceSize kSpektraFilmParametersSize = 648;
constexpr VkDeviceSize kMeterPixelsSize = 256u * 256u * 4u * sizeof(float);
constexpr VkDeviceSize kIspParametersSize = 80 + 256 * 16;
constexpr uint32_t kRecordingTileSharedBytes = 24u * 9u * (4u * sizeof(float) + sizeof(uint32_t));
constexpr float kLensSwitchRevealSeconds = 0.22f;

struct ExposureMeterExchange {
    int64_t requestId = 0;
    int32_t method = 0;
    float x = -1.0f;
    float y = -1.0f;
    int32_t reserved = 0;
    int64_t resultId = 0;
    double correctionEv = 0.0;
};
static_assert(sizeof(ExposureMeterExchange) == 40);

bool serialRawPreview()
{
    char value[PROP_VALUE_MAX]{};
    __system_property_get("debug.unspektra.serial_raw", value);
    return value[0] == '1';
}

enum class RawImportKind {
    None,
    Buffer,
    Image,
};

struct RawParameters {
    int32_t dimensions[4];
    int32_t layoutInfo[4];
    float blackLevel[4];
    float whiteLevel[4];
    int32_t remosaic[4]{1, 0, 0, 0};
};

static_assert(sizeof(RawParameters) == kRawParametersSize);

struct GeometryParameters {
    float sourceCrop[4];
    float activeArray[4];
    int32_t transform[4];
    float target[4];
    float calibrationArray[4];
    int32_t rawBounds[4];
    float stabilization[4]{};
    float stabilizationBounds[4]{};
    float stabilizationRows[33][3][4]{};
    float stabilizationIntrinsics[4]{};
    float stabilizationRadial[4]{};
    float stabilizationTangential[4]{};
};

static_assert(sizeof(GeometryParameters) == kGeometryParametersSize);

GeometryParameters readGeometry(const void* address, jlong capacity, const RawParameters& raw)
{
    if (address == nullptr || (capacity != 64 && capacity != 96 && capacity != kPinholeGeometryParametersSize &&
        capacity < static_cast<jlong>(kGeometryParametersSize)))
        throw std::runtime_error("Invalid geometry parameter buffer");
    GeometryParameters geometry{};
    if (capacity == 64) {
        std::memcpy(&geometry, address, 64);
        std::memcpy(geometry.calibrationArray, geometry.activeArray, sizeof(geometry.activeArray));
        geometry.rawBounds[2] = raw.dimensions[0];
        geometry.rawBounds[3] = raw.dimensions[1];
    } else if (capacity == 96) {
        std::memcpy(&geometry, address, 96);
    } else if (capacity == kPinholeGeometryParametersSize) {
        std::memcpy(&geometry, address, kPinholeGeometryParametersSize);
        geometry.stabilization[3] = 0.0f;
    } else {
        std::memcpy(&geometry, address, sizeof(geometry));
    }
    if (geometry.stabilization[0] != 0.0f) {
        if (geometry.stabilization[0] != 1.0f || !std::isfinite(geometry.stabilization[1]) ||
            !std::isfinite(geometry.stabilization[2]) || geometry.stabilization[2] <= 0.0f)
            throw std::runtime_error("Invalid stabilization readout");
        for (const auto& matrix : geometry.stabilizationRows)
            for (const auto& row : matrix)
                for (float value : row) if (!std::isfinite(value))
                    throw std::runtime_error("Invalid stabilization transform");
        for (float value : geometry.stabilizationBounds) if (!std::isfinite(value))
            throw std::runtime_error("Invalid stabilization bounds");
        for (size_t axis = 0; axis < 2; ++axis)
            if (geometry.stabilizationBounds[axis] < 0.0f || geometry.stabilizationBounds[axis + 2] <= 0.0f ||
                geometry.stabilizationBounds[axis] + geometry.stabilizationBounds[axis + 2] > 1.0001f)
                throw std::runtime_error("Invalid stabilization bounds");
        if (geometry.stabilization[3] != 0.0f) {
            if (geometry.stabilization[3] != 1.0f || geometry.stabilizationIntrinsics[0] <= 0.0f ||
                geometry.stabilizationIntrinsics[1] <= 0.0f)
                throw std::runtime_error("Invalid stabilization transform");
            for (float value : geometry.stabilizationIntrinsics) if (!std::isfinite(value))
                throw std::runtime_error("Invalid stabilization transform");
            for (float value : geometry.stabilizationRadial) if (!std::isfinite(value))
                throw std::runtime_error("Invalid stabilization transform");
            for (float value : geometry.stabilizationTangential) if (!std::isfinite(value))
                throw std::runtime_error("Invalid stabilization transform");
        }
    }
    return geometry;
}

void validateRawBounds(const GeometryParameters& geometry, const RawParameters& raw)
{
    const auto& bounds = geometry.rawBounds;
    if (bounds[0] < 0 || bounds[1] < 0 || bounds[2] < 2 || bounds[3] < 2 ||
        static_cast<int64_t>(bounds[0]) + bounds[2] > raw.dimensions[0] ||
        static_cast<int64_t>(bounds[1]) + bounds[3] > raw.dimensions[1])
        throw std::runtime_error("Invalid RAW processing bounds");
}

struct PreviewPresentation {
    float aspect = 1.0f;
    std::array<float, 4> crop{0.0f, 0.0f, 1.0f, 1.0f};
};

PreviewPresentation previewPresentation(const RawParameters& raw, const GeometryParameters& geometry)
{
    const auto& active = geometry.activeArray;
    const auto& source = geometry.sourceCrop;
    float x = (source[0] - active[0]) / active[2];
    float y = (source[1] - active[1]) / active[3];
    float width = source[2] / active[2];
    float height = source[3] / active[3];
    float aspect = active[2] * raw.dimensions[0] / (active[3] * raw.dimensions[1]);
    switch (geometry.transform[0]) {
        case 1: {
            const float previousX = x;
            x = 1.0f - y - height;
            y = previousX;
            std::swap(width, height);
            aspect = 1.0f / aspect;
            break;
        }
        case 2:
            x = 1.0f - x - width;
            y = 1.0f - y - height;
            break;
        case 3: {
            const float previousX = x;
            x = y;
            y = 1.0f - previousX - width;
            std::swap(width, height);
            aspect = 1.0f / aspect;
            break;
        }
        default: break;
    }
    if (geometry.transform[1] != 0) x = 1.0f - x - width;
    return {aspect, {x, y, width, height}};
}

void require(VkResult result, const char* operation)
{
    if (result != VK_SUCCESS)
        throw std::runtime_error(std::string(operation) + " failed (" +
                                 std::to_string(result) + ")");
}

bool containsExtension(
    const std::vector<VkExtensionProperties>& extensions,
    const char* name)
{
    return std::any_of(
        extensions.begin(), extensions.end(),
        [name](const VkExtensionProperties& extension) {
            return std::strcmp(extension.extensionName, name) == 0;
        });
}

VkShaderModule makeShader(
    VkDevice device,
    const uint32_t* code,
    size_t wordCount)
{
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize = wordCount * sizeof(uint32_t);
    info.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    require(vkCreateShaderModule(device, &info, nullptr, &module),
            "vkCreateShaderModule");
#if defined(UNSPEKTRA_VULKAN_VALIDATION)
    const std::pair<const uint32_t*, const char*> names[]{
        {kPreviewBilinearSpirv.data(), "RAW preview buffer"},
        {kPreviewBilinearImageSpirv.data(), "RAW preview image"},
        {kCaptureColorSpirv.data(), "Capture color"},
        {kAmazeNormalizeBufferSpirv.data(), "AMaZE normalize buffer"},
        {kAmazeNormalizeImageSpirv.data(), "AMaZE normalize image"},
        {kAmazeCandidatesBufferSpirv.data(), "AMaZE candidates buffer"},
        {kAmazeCandidatesImageSpirv.data(), "AMaZE candidates image"},
        {kAmazeDifferencesBufferSpirv.data(), "AMaZE differences buffer"},
        {kAmazeDifferencesImageSpirv.data(), "AMaZE differences image"},
        {kAmazeWeightsBufferSpirv.data(), "AMaZE weights buffer"},
        {kAmazeWeightsImageSpirv.data(), "AMaZE weights image"},
        {kAmazeGreenBufferSpirv.data(), "AMaZE green buffer"},
        {kAmazeGreenImageSpirv.data(), "AMaZE green image"},
        {kAmazeRefineBufferSpirv.data(), "AMaZE refine buffer"},
        {kAmazeRefineImageSpirv.data(), "AMaZE refine image"},
        {kAmazeDiagonalBufferSpirv.data(), "AMaZE diagonal buffer"},
        {kAmazeDiagonalImageSpirv.data(), "AMaZE diagonal image"},
        {kAmazeChromaBufferSpirv.data(), "AMaZE chroma buffer"},
        {kAmazeChromaImageSpirv.data(), "AMaZE chroma image"},
        {kAmazeOutputBufferSpirv.data(), "AMaZE output buffer"},
        {kAmazeOutputImageSpirv.data(), "AMaZE output image"},
        {kAmazeHrSeedBufferSpirv.data(), "AMaZE hrseed buffer"},
        {kAmazeHrSeedImageSpirv.data(), "AMaZE hrseed image"},
        {kAmazeHrReduceBufferSpirv.data(), "AMaZE hrreduce buffer"},
        {kAmazeHrReduceImageSpirv.data(), "AMaZE hrreduce image"},
        {kAmazeRecoveryBufferSpirv.data(), "AMaZE recovery buffer"},
        {kAmazeRecoveryImageSpirv.data(), "AMaZE recovery image"},
    };
    for (const auto& [words, name] : names) {
        if (code == words) nameDiagnosticShader(device, module, name);
    }
#endif
    return module;
}

class VulkanRenderer {
public:
    explicit VulkanRenderer(const std::string& cachePath) : cachePath_(cachePath)
    {
        createInstance();
    }

    ~VulkanRenderer()
    {
        clearSurface();
        destroyDevice();
        validation_.detach(instance_);
        if (instance_ != VK_NULL_HANDLE)
            vkDestroyInstance(instance_, nullptr);
    }

    std::shared_ptr<RawUploadPool> rawUploads() const { return rawUploads_; }

    std::string preparationIdentity() const
    {
        return pipelineCache_.preparationIdentity() + (lightweightRawPreview() ? "/lightweight" : "/filtered") +
            (compatibleRawPreview() ? "/compatible" : "/ordinary") +
            (compatibleNativeRecordingCompute() ? "/native-recording-compatible" : "/native-recording-ordinary");
    }

    bool loadedPipelineCache() const { return pipelineCache_.loadedCompatibleData; }

    std::array<jlong, 5> pipelineCreationStats() const
    {
        return {pipelineCreationStats_.feedbackEnabled ? 1 : 0,
            static_cast<jlong>(pipelineCreationStats_.created.load(std::memory_order_relaxed)),
            static_cast<jlong>(pipelineCreationStats_.cacheHits.load(std::memory_order_relaxed)),
            static_cast<jlong>(pipelineCreationStats_.cacheMisses.load(std::memory_order_relaxed)),
            static_cast<jlong>(pipelineCreationStats_.unreported.load(std::memory_order_relaxed))};
    }

    bool warmUp(bool preview, uint32_t step, int32_t part = -1)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        camera_gpu::ExportScope exportScope(!preview);
        try {
            if (device_ == VK_NULL_HANDLE) {
                try {
                    selectDeviceAndQueue();
                    createDeviceResources();
                } catch (...) {
                    destroyDevice();
                    throw;
                }
            }
            if (!preview && captureColorPipeline_ == VK_NULL_HANDLE) {
                captureColorPipeline_ = createComputeProgram(
                    kCaptureColorSpirv.data(), kCaptureColorSpirv.size(), nullptr);
            }
            pipelineCache_.refresh();
            if (step > 0 && step <= 24) {
                if (!spektraRenderer_->prepareStartupPipelines(preview, step - 1, part))
                    throw std::runtime_error(spektraRenderer_->lastError());
            } else if (step == 25) {
                ensureShadowPipelines(preview);
            } else if (step == 26 && preview) {
                ensureFocusPreviewPipelines();
                ensureFocusLoupePipelines();
                ensureFocusLoupeDrawPipeline();
                ensureVideoChromaPipeline();
                prepareHotPixelPipeline(false);
                prepareHotPixelPipeline(true);
            } else if (step == 27 && preview) {
                ensurePreviewRecoveryPipelines();
            } else if (step == 28 && !preview) {
                ensureChromaDenoisePipeline();
                prepareHotPixelPipeline(false);
                prepareHotPixelPipeline(true);
            } else if (step >= 29 && step <= 36 && !preview) {
                ensureAmazePipelines((step - 29) / 2, (step - 29) % 2, true);
            } else if (step == 37 && preview) {
                for (size_t input = 0; input < meterPipelines_.size(); ++input) prepareMeterPipeline(input);
                prepareRawImageProbe();
            } else if (step >= 38 && step <= 40) {
                for (size_t input = 0; input < 3; ++input) for (bool identity : {false, true})
                    prepareRemosaicPipeline(step - 36, identity, input, preview);
            } else if (step >= 41 && step <= 52 && preview) {
                prepareRawPreviewPipelines((step - 41) / 4, (step - 41) % 4);
            } else if (step == 53 && preview) {
                prepareHistogramProgram();
            } else if (step == 54 && preview && !compatibleRawPreview()) {
                // GPUs on the compatibility path compile these programs only when the ISP viewfinder is used.
                prepareIspPipelines();
            }
            pipelineCache_.save();
            setDiagnostic("Vulkan startup pipelines prepared");
            return true;
        } catch (const std::exception& error) {
            pipelineCache_.save();
            setDiagnostic(error.what());
            return false;
        }
    }

    bool setSurface(JNIEnv* environment, jobject surfaceObject)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        const auto started = std::chrono::steady_clock::now();
        bool reused = device_ != VK_NULL_HANDLE;
        clearSurface();
        rejectedCameraImports_.clear();
        rejectedRawImports_.clear();
        resetRawImageValidation();
        reportedCameraFormat_ = -1;
        if (surfaceObject == nullptr) {
            setDiagnostic("Surface detached");
            return true;
        }

        window_ = ANativeWindow_fromSurface(environment, surfaceObject);
        if (window_ == nullptr) {
            setDiagnostic("Surface does not provide an ANativeWindow");
            return false;
        }

        try {
            VkAndroidSurfaceCreateInfoKHR surfaceInfo{
                VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
            surfaceInfo.window = window_;
            require(vkCreateAndroidSurfaceKHR(
                        instance_, &surfaceInfo, nullptr, &surface_),
                    "vkCreateAndroidSurfaceKHR");
            if (device_ != VK_NULL_HANDLE) {
                VkBool32 supported = VK_FALSE;
                if (vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice_, queueFamily_, surface_, &supported) != VK_SUCCESS || !supported)
                    destroyDevice();
            }
            if (device_ == VK_NULL_HANDLE) {
                reused = false;
                selectDeviceAndQueue();
                createDeviceResources();
            }
            createSwapchain();
            recordNativeDiagnostic(ANDROID_LOG_INFO, "CameraStartup", "Surface ready: device_reused=%d setup_ms=%.1f",
                reused ? 1 : 0, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count());
            setDiagnostic("Vulkan RAW preview surface ready: " +
                std::to_string(swapchainExtent_.width) + "x" +
                std::to_string(swapchainExtent_.height));
            return true;
        } catch (const std::exception& error) {
            setDiagnostic(error.what());
            clearSurface();
            destroyDevice();
            return false;
        }
    }

    void setGpuPerformanceActive(bool active)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        if (active && device_ != VK_NULL_HANDLE && swapchain_ != VK_NULL_HANDLE)
            gpuPerformance_.start(physicalDevice_, queueFamily_);
        else
            gpuPerformance_.stop();
    }

    jintArray histogramPixels(JNIEnv* environment, float cropAspect)
    {
        return histogramSamples(environment, cropAspect, 0, false);
    }

    jintArray histogramBins(JNIEnv* environment, float cropAspect, int64_t generation)
    {
        return histogramSamples(environment, cropAspect, generation, true);
    }

    int64_t previewSnapshot(JNIEnv* environment, jobject bitmap)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        AndroidBitmapInfo info{};
        if (!hasPresentedFrame_ || !lastCleanPreviewImage_ ||
            AndroidBitmap_getInfo(environment, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height ||
            info.width > 1536 || info.height > 1536) return 0;
        PreviewTexture target;
        VkCommandBuffer command = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        void* pixels = nullptr;
        bool submitted = false;
        auto cleanup = [&] {
            if (pixels) AndroidBitmap_unlockPixels(environment, bitmap);
            if (fence) vkDestroyFence(device_, fence, nullptr);
            if (command) vkFreeCommandBuffers(device_, commandPool_, 1, &command);
            destroyPreviewTexture(target);
        };
        try {
            VkFormatProperties sourceFormat{}, targetFormat{};
            vkGetPhysicalDeviceFormatProperties(physicalDevice_, VK_FORMAT_R16G16B16A16_SFLOAT, &sourceFormat);
            vkGetPhysicalDeviceFormatProperties(physicalDevice_, VK_FORMAT_R8G8B8A8_UNORM, &targetFormat);
            if (!(sourceFormat.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) ||
                !(sourceFormat.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) ||
                !(targetFormat.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT))
                throw std::runtime_error("Unable to save panorama");
            ensurePreviewTexture(target, info.width, info.height, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            makeBuffer(VkDeviceSize(info.width) * info.height * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                target.staging, target.stagingMemory, &target.mapped);
            VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            allocate.commandPool = commandPool_; allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; allocate.commandBufferCount = 1;
            require(vkAllocateCommandBuffers(device_, &allocate, &command), "Allocate preview snapshot commands");
            VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            require(vkCreateFence(device_, &fenceInfo, nullptr, &fence), "Create preview snapshot fence");
            VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            require(vkBeginCommandBuffer(command, &begin), "Begin preview snapshot");
            std::array<VkImageMemoryBarrier, 2> barriers{};
            for (auto& barrier : barriers) {
                barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            }
            auto& source = barriers[0];
            source.image = lastCleanPreviewImage_; source.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            source.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            source.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            source.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            auto& destination = barriers[1];
            destination.image = target.image; destination.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            destination.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; destination.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 2, barriers.data());
            const float aspect = float(info.width) / info.height;
            const float visibleWidth = std::min(1.f, aspect / lastPresentation_.aspect);
            const float visibleHeight = std::min(1.f, lastPresentation_.aspect / aspect);
            const auto& crop = lastPresentation_.crop;
            auto coordinate = [](float p, float origin, float extent, uint32_t size) {
                return std::clamp(int32_t(std::round((p - origin) / extent * size)), 0, int32_t(size));
            };
            VkImageBlit blit{};
            blit.srcSubresource = blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            blit.srcOffsets[0] = {std::min(coordinate((1 - visibleWidth) * .5f, crop[0], crop[2], processingWidth_), int32_t(processingWidth_) - 1),
                std::min(coordinate((1 - visibleHeight) * .5f, crop[1], crop[3], processingHeight_), int32_t(processingHeight_) - 1), 0};
            blit.srcOffsets[1] = {std::max(blit.srcOffsets[0].x + 1, coordinate((1 + visibleWidth) * .5f, crop[0], crop[2], processingWidth_)),
                std::max(blit.srcOffsets[0].y + 1, coordinate((1 + visibleHeight) * .5f, crop[1], crop[3], processingHeight_)), 1};
            blit.dstOffsets[1] = {int32_t(info.width), int32_t(info.height), 1};
            vkCmdBlitImage(command, source.image, source.newLayout, target.image, destination.newLayout, 1, &blit, VK_FILTER_LINEAR);
            source.oldLayout = source.newLayout; source.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            source.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            source.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            destination.oldLayout = destination.newLayout; destination.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            destination.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; destination.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, 0, nullptr, 0, nullptr, 2, barriers.data());
            VkBufferImageCopy copy{};
            copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; copy.imageExtent = {info.width, info.height, 1};
            vkCmdCopyImageToBuffer(command, target.image, destination.newLayout, target.staging, 1, &copy);
            VkMemoryBarrier readable{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            readable.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; readable.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &readable, 0, nullptr, 0, nullptr);
            require(vkEndCommandBuffer(command), "End preview snapshot");
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
            { std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                require(vkQueueSubmit(queue_, 1, &submit, fence), "Submit preview snapshot"); }
            submitted = true;
            require(vkWaitForFences(device_, 1, &fence, VK_TRUE, UINT64_MAX), "Read preview snapshot");
            if (AndroidBitmap_lockPixels(environment, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) throw std::runtime_error("Unable to save panorama");
            for (uint32_t y = 0; y < info.height; ++y)
                std::memcpy(static_cast<char*>(pixels) + size_t(y) * info.stride, static_cast<char*>(target.mapped) + size_t(y) * info.width * 4, size_t(info.width) * 4);
            cleanup();
            return lastPreviewTimestamp_;
        } catch (const std::exception& error) {
            if (submitted) vkWaitForFences(device_, 1, &fence, VK_TRUE, UINT64_MAX);
            cleanup(); setDiagnostic(error.what()); return -1;
        }
    }

    jintArray histogramSamples(JNIEnv* environment, float cropAspect, int64_t generation, bool bins)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        if (!hasPresentedFrame_ || !std::isfinite(cropAspect) || cropAspect <= 0.0f)
            return nullptr;
        const HistogramKey key{presentationEpoch_, generation, cropAspect, lastPresentation_};
        jintArray completed = nullptr;
        try {
            if (histogramPending_) {
                if (bins) {
                    const VkResult status = vkGetFenceStatus(device_, histogramFence_);
                    if (status == VK_NOT_READY) return nullptr;
                    require(status, "Read histogram fence");
                } else {
                    require(vkWaitForFences(device_, 1, &histogramFence_, VK_TRUE, UINT64_MAX), "Read histogram fence");
                }
                histogramPending_ = false;
                if (bins && histogramBinsRequest_ && histogramKey_ == key) completed = readHistogram(environment, true);
            }
            if (bins && histogramBinsRequest_ && histogramSequence_ == presentedSequence_ && histogramKey_ == key)
                return completed;
            constexpr int32_t edge = 128;
            if (!histogramTexture_.image) {
                VkFormatProperties source{}, target{};
                vkGetPhysicalDeviceFormatProperties(physicalDevice_, VK_FORMAT_R16G16B16A16_SFLOAT, &source);
                vkGetPhysicalDeviceFormatProperties(physicalDevice_, VK_FORMAT_R8G8B8A8_UNORM, &target);
                if (!(source.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) ||
                    !(source.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) ||
                    !(target.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT)) return nullptr;
                histogramGpuAvailable_ = (target.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
                ensurePreviewTexture(histogramTexture_, edge, edge, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                    (histogramGpuAvailable_ ? VK_IMAGE_USAGE_STORAGE_BIT : 0));
            }
            if (!histogramTexture_.staging) {
                makeBuffer(edge * edge * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    histogramTexture_.staging, histogramTexture_.stagingMemory, &histogramTexture_.mapped);
            }
            if (!histogramFence_) {
                VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
                require(vkCreateFence(device_, &info, nullptr, &histogramFence_), "Reset histogram fence");
            }
            bool gpuBins = bins && histogramGpuAvailable_;
#if !defined(NDEBUG)
            char histogramProperty[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.histogram_cpu", histogramProperty);
            if (histogramProperty[0] == '1') gpuBins = false;
#endif
            if (gpuBins && !histogramDescriptor_) {
                try { prepareHistogramPipeline(); }
                catch (const std::exception& error) {
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "ViewfinderHistogram", "%s", error.what());
                    histogramGpuAvailable_ = false;
                    gpuBins = false;
                }
            }
            require(vkResetCommandBuffer(histogramCommand_, 0), "Reset histogram commands");
            VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            require(vkBeginCommandBuffer(histogramCommand_, &begin), "Begin histogram commands");
            std::array<VkImageMemoryBarrier, 2> barriers{};
            for (auto& barrier : barriers) {
                barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            }
            auto& source = barriers[0];
            source.image = lastPresentedImage_;
            source.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            source.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            source.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            source.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            auto& target = barriers[1];
            target.image = histogramTexture_.image;
            target.srcAccessMask = histogramTexture_.initialized ? VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT : 0;
            target.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            target.oldLayout = histogramTexture_.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            target.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            vkCmdPipelineBarrier(histogramCommand_, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 2, barriers.data());
            // Map the visible crop into the clean processing image, excluding letterboxing and zebra.
            const float visibleWidth = std::min(1.0f, cropAspect / lastPresentation_.aspect);
            const float visibleHeight = std::min(1.0f, lastPresentation_.aspect / cropAspect);
            const auto& crop = lastPresentation_.crop;
            auto coordinate = [](float position, float origin, float extent, uint32_t size) {
                return std::clamp(static_cast<int32_t>(std::round((position - origin) / extent * size)), 0, static_cast<int32_t>(size));
            };
            VkImageBlit blit{};
            blit.srcSubresource = blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            blit.srcOffsets[0] = {
                std::min(coordinate((1.0f - visibleWidth) * 0.5f, crop[0], crop[2], processingWidth_), static_cast<int32_t>(processingWidth_) - 1),
                std::min(coordinate((1.0f - visibleHeight) * 0.5f, crop[1], crop[3], processingHeight_), static_cast<int32_t>(processingHeight_) - 1), 0};
            blit.srcOffsets[1] = {
                std::max(blit.srcOffsets[0].x + 1, coordinate((1.0f + visibleWidth) * 0.5f, crop[0], crop[2], processingWidth_)),
                std::max(blit.srcOffsets[0].y + 1, coordinate((1.0f + visibleHeight) * 0.5f, crop[1], crop[3], processingHeight_)), 1};
            blit.dstOffsets[1] = {edge, edge, 1};
            vkCmdBlitImage(histogramCommand_, lastPresentedImage_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                histogramTexture_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
            source.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            source.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            source.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            source.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            target.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            target.dstAccessMask = gpuBins ? VK_ACCESS_SHADER_READ_BIT : VK_ACCESS_TRANSFER_READ_BIT;
            target.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            target.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            vkCmdPipelineBarrier(histogramCommand_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, 0, nullptr, 0, nullptr, 2, barriers.data());
            if (gpuBins) {
                vkCmdFillBuffer(histogramCommand_, histogramTexture_.staging, 0, 768 * sizeof(uint32_t), 0);
                VkMemoryBarrier cleared{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
                cleared.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                cleared.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                vkCmdPipelineBarrier(histogramCommand_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, 1, &cleared, 0, nullptr, 0, nullptr);
                vkCmdBindPipeline(histogramCommand_, VK_PIPELINE_BIND_POINT_COMPUTE, histogramPipeline_);
                vkCmdBindDescriptorSets(histogramCommand_, VK_PIPELINE_BIND_POINT_COMPUTE,
                    histogramPipelineLayout_, 0, 1, &histogramDescriptor_, 0, nullptr);
                vkCmdDispatch(histogramCommand_, edge * edge / 128, 1, 1);
            } else {
                VkBufferImageCopy copy{};
                copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                copy.imageExtent = {edge, edge, 1};
                vkCmdCopyImageToBuffer(histogramCommand_, histogramTexture_.image, VK_IMAGE_LAYOUT_GENERAL,
                    histogramTexture_.staging, 1, &copy);
            }
            VkMemoryBarrier readable{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            readable.srcAccessMask = gpuBins ? VK_ACCESS_SHADER_WRITE_BIT : VK_ACCESS_TRANSFER_WRITE_BIT;
            readable.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(histogramCommand_, gpuBins ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                0, 1, &readable, 0, nullptr, 0, nullptr);
            require(vkEndCommandBuffer(histogramCommand_), "End histogram commands");
            require(vkResetFences(device_, 1, &histogramFence_), "Reset histogram fence");
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &histogramCommand_;
            VkResult submitted;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                submitted = vkQueueSubmit(queue_, 1, &submit, histogramFence_);
            }
            require(submitted, "Submit histogram commands");
            histogramTexture_.initialized = true;
            histogramPending_ = true;
            histogramBinsRequest_ = bins;
            histogramGpuRequest_ = gpuBins;
            histogramKey_ = key;
            histogramSequence_ = presentedSequence_;
            if (bins) return completed;
            require(vkWaitForFences(device_, 1, &histogramFence_, VK_TRUE, UINT64_MAX), "Read histogram fence");
            histogramPending_ = false;
            return readHistogram(environment, false);
        } catch (const std::exception& error) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "ViewfinderHistogram", "%s", error.what());
            return nullptr;
        }
    }

    bool render(
        JNIEnv* environment,
        jobject hardwareBufferObject,
        jobject packedRawBytesObject,
        jobject rawParametersObject,
        jobject rawColorParametersObject,
        jobject geometryParametersObject,
        jobject spektraFilmParametersObject,
        jobject lensShadingObject,
        int32_t processingWidth,
        int32_t processingHeight,
        jobject performanceOutputObject,
        jobject exposureMeterObject,
        int64_t videoTimestampNs,
        RawUploadPool::Lease* rawUpload, int64_t averageGroup, int averageIndex, int averageCount, int64_t averageRevision,
        int64_t previewTimestampNs,
        jobject ispHardwareBufferObject = nullptr, jobject ispPlanesObject = nullptr, jobject ispParametersObject = nullptr)
    {
        camera_gpu::PreviewScope previewScope;
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        pipelineCache_.refresh();
        RawPreviewCpuTiming timing(performanceOutputObject != nullptr);
        Frame* activeFrame = nullptr;
        bool submitted = false;
        bool rawSubmitted = false;
        bool imageAcquired = false;
        try {
            ExposureMeterExchange meter;
            void* meterAddress = nullptr;
            if (exposureMeterObject != nullptr) {
                meterAddress = environment->GetDirectBufferAddress(exposureMeterObject);
                if (!meterAddress || environment->GetDirectBufferCapacity(exposureMeterObject) < static_cast<jlong>(sizeof(meter)))
                    throw std::runtime_error("Exposure meter requires a direct buffer of at least 40 bytes");
                std::memcpy(&meter, meterAddress, sizeof(meter));
                if (meter.method < 0 || meter.method > 1)
                    throw std::runtime_error("Invalid exposure metering method");
                meter.resultId = 0;
                meter.correctionEv = std::numeric_limits<double>::quiet_NaN();
                std::memcpy(meterAddress, &meter, sizeof(meter));
            }
            void* performanceOutput = nullptr;
            void* encoderSubmissionOutput = nullptr;
            void* encoderInputOutput = nullptr;
            void* workPeriodStartOutput = nullptr;
            if (performanceOutputObject != nullptr) {
                performanceOutput = environment->GetDirectBufferAddress(performanceOutputObject);
                if (performanceOutput == nullptr || environment->GetDirectBufferCapacity(performanceOutputObject) < 8)
                    throw std::runtime_error("Preview timing output must be a direct ByteBuffer of at least 8 bytes");
                const double unavailable = -1.0;
                std::memcpy(performanceOutput, &unavailable, sizeof(unavailable));
                if (environment->GetDirectBufferCapacity(performanceOutputObject) >= 16) {
                    encoderSubmissionOutput = static_cast<char*>(performanceOutput) + 8;
                    const int64_t notSubmitted = 0;
                    std::memcpy(encoderSubmissionOutput, &notSubmitted, sizeof(notSubmitted));
                }
                if (environment->GetDirectBufferCapacity(performanceOutputObject) >= 32) {
                    encoderInputOutput = static_cast<char*>(performanceOutput) + 16;
                    const int64_t inputTiming[] = {-1, 0};
                    std::memcpy(encoderInputOutput, inputTiming, sizeof(inputTiming));
                }
                if (environment->GetDirectBufferCapacity(performanceOutputObject) >= 40) {
                    workPeriodStartOutput = static_cast<char*>(performanceOutput) + 32;
                    const int64_t unavailableStart = 0;
                    std::memcpy(workPeriodStartOutput, &unavailableStart, sizeof(unavailableStart));
                }
            }
            if (swapchainRecoveryPending_) recreateSwapchain();
            if (device_ == VK_NULL_HANDLE || swapchain_ == VK_NULL_HANDLE)
                throw std::runtime_error("No Vulkan surface is attached");
            const bool ispInput = ispParametersObject != nullptr;
            if (!ispInput && hardwareBufferObject == nullptr && packedRawBytesObject == nullptr && rawUpload == nullptr)
                throw std::runtime_error("RAW preview has neither a HardwareBuffer nor copied plane bytes");
            if (ispInput && ispHardwareBufferObject == nullptr && ispPlanesObject == nullptr)
                throw std::runtime_error("ISP viewfinder frame has no usable image data");
            if (processingWidth <= 0 || processingHeight <= 0)
                throw std::runtime_error("Processing dimensions must be positive");
            gpuPerformance_.start(physicalDevice_, queueFamily_);

            void* rawAddress = environment->GetDirectBufferAddress(rawParametersObject);
            jlong rawCapacity = environment->GetDirectBufferCapacity(rawParametersObject);
            void* spektraAddress =
                environment->GetDirectBufferAddress(rawColorParametersObject);
            jlong spektraCapacity =
                environment->GetDirectBufferCapacity(rawColorParametersObject);
            void* geometryAddress =
                environment->GetDirectBufferAddress(geometryParametersObject);
            jlong geometryCapacity =
                environment->GetDirectBufferCapacity(geometryParametersObject);
            void* spektraFilmAddress =
                environment->GetDirectBufferAddress(spektraFilmParametersObject);
            jlong spektraFilmCapacity =
                environment->GetDirectBufferCapacity(spektraFilmParametersObject);
            if (rawAddress == nullptr || (rawCapacity != 64 && rawCapacity <
                    static_cast<jlong>(kRawParametersSize))) {
                throw std::runtime_error(
                    "RAW parameters must be a direct ByteBuffer of at least 64 bytes");
            }
            if (spektraAddress == nullptr || spektraCapacity <
                    static_cast<jlong>(kRawColorParametersSize)) {
                throw std::runtime_error(
                    "RAW color parameters must be a direct ByteBuffer of at least 144 bytes");
            }
            if (spektraFilmAddress == nullptr || spektraFilmCapacity <
                    static_cast<jlong>(kSpektraFilmParametersSize)) {
                throw std::runtime_error(
                    "SpektraFilm parameters must be a direct ByteBuffer of at least 648 bytes");
            }

            std::array<std::byte, kRawColorParametersSize> spektraParameters{};
            std::memcpy(spektraParameters.data(), spektraAddress,
                        spektraParameters.size());
            std::array<std::byte, kSpektraFilmParametersSize> spektraFilmParameters{};
            std::memcpy(spektraFilmParameters.data(), spektraFilmAddress,
                        spektraFilmParameters.size());
            int32_t spektraFilmEnabled = 0;
            std::memcpy(&spektraFilmEnabled, spektraFilmParameters.data(),
                        sizeof(spektraFilmEnabled));
            float shadingWidthValue = 0.0f;
            float shadingHeightValue = 0.0f;
            std::memcpy(&shadingWidthValue, spektraParameters.data() + 136,
                        sizeof(float));
            std::memcpy(&shadingHeightValue, spektraParameters.data() + 140,
                        sizeof(float));
            uint32_t shadingWidth = checkedMapDimension(
                shadingWidthValue, "Lens shading width");
            uint32_t shadingHeight = checkedMapDimension(
                shadingHeightValue, "Lens shading height");
            if ((shadingWidth == 0) != (shadingHeight == 0))
                throw std::runtime_error(
                    "Lens shading width and height must both be zero or positive");
            uint64_t shadingTexels = static_cast<uint64_t>(shadingWidth) *
                                     static_cast<uint64_t>(shadingHeight);
            if (shadingTexels > 1024u * 1024u)
                throw std::runtime_error("Lens shading map is unreasonably large");
            VkDeviceSize shadingBytes = shadingTexels == 0
                ? sizeof(float) * 4
                : shadingTexels * sizeof(float) * 4;
            void* shadingAddress = lensShadingObject == nullptr
                ? nullptr
                : environment->GetDirectBufferAddress(lensShadingObject);
            jlong shadingCapacity = lensShadingObject == nullptr
                ? -1
                : environment->GetDirectBufferCapacity(lensShadingObject);
            if (shadingTexels > 0 &&
                (shadingAddress == nullptr || shadingCapacity <
                    static_cast<jlong>(shadingBytes))) {
                throw std::runtime_error(
                    "Lens shading map must be a direct ByteBuffer containing width times height interleaved R, G-even, G-odd, B float gains");
            }

            RawParameters rawParameters{};
            std::memcpy(&rawParameters, rawAddress, std::min(static_cast<size_t>(rawCapacity), sizeof(rawParameters)));
            if (rawCapacity == 64) rawParameters.remosaic[1] = rawParameters.layoutInfo[2];
            rawParameters.dimensions[2] = processingWidth;
            rawParameters.dimensions[3] = processingHeight;
            validateRawParameters(rawParameters);
            // Hot pixel removal and remosaic rewrite the layout for their GPU outputs. The loupe copies
            // from the camera plane itself, so it keeps the camera's stride, packing and CFA blocks.
            const RawParameters cameraRawParameters = rawParameters;

            Frame& frame = frames_[frameIndex_];
            activeFrame = &frame;
            const auto fenceStarted = timing.now();
            require(vkWaitForFences(device_, 1, &frame.fence, VK_TRUE,
                                    std::numeric_limits<uint64_t>::max()),
                    "vkWaitForFences");
            timing.fenceMs = timing.since(fenceStarted);
            if (meterAddress && collectExposureMeter(meter))
                std::memcpy(meterAddress, &meter, sizeof(meter));
            reportPresentSamples(frame);
            collectFocusLoupe(frame);
            if (hasPresentedFrame_ &&
                lastPresentedFrameIndex_ == frameIndex_) {
                hasPresentedFrame_ = false;
                ++presentationEpoch_;
                lastPresentedImage_ = VK_NULL_HANDLE;
            }
            if (!frame.bayerCells || hardwareBufferObject == nullptr || !bayerCellRawPreview())
                releaseImportedBuffer(frame);
            releaseIspImage(frame);
            frame.ispActive = ispInput;

            if (processingWidth_ != static_cast<uint32_t>(processingWidth) ||
                processingHeight_ != static_cast<uint32_t>(processingHeight)) {
                require(vkDeviceWaitIdle(device_), "vkDeviceWaitIdle");
                createProcessingImages(
                    static_cast<uint32_t>(processingWidth),
                    static_cast<uint32_t>(processingHeight));
            }
            std::memcpy(&frame.focusStrength, spektraParameters.data() + 68, sizeof(float));
            float videoMode = 0.0f;
            std::memcpy(&videoMode, spektraParameters.data() + 80, sizeof(float));
            const bool photoPeaking = frame.focusStrength > 0.5f && videoMode < 0.5f;
            std::memcpy(&frame.exposureZebra, spektraParameters.data() + 104, sizeof(float));
            const bool hadShadows = frame.shadowStrength > 0.001f;
            std::memcpy(&frame.shadowStrength, spektraParameters.data() + 108, sizeof(float));
            frame.computeDescriptorsDirty |= hadShadows != (frame.shadowStrength > 0.001f);
            if (frame.shadowStrength > 0.001f)
                ensureShadowResources(frame, processingWidth_, processingHeight_, true);
            float previewRecovery = 0.0f;
            std::memcpy(&previewRecovery, spektraParameters.data() + 128, sizeof(float));
            const bool recoveryEnabled = previewRecovery > 0.5f && frame.focusStrength <= 0.0f && !ispInput;
            frame.computeDescriptorsDirty |= frame.previewHighlightRecovery != recoveryEnabled;
            frame.previewHighlightRecovery = recoveryEnabled;
            if (recoveryEnabled) ensurePreviewRecoveryResources(frame);
            else if (frame.recovery.pool) destroyPreviewRecoveryResources(frame);
            frame.useSpektraOutput = spektraFilmEnabled != 0 && !photoPeaking;
            if (meter.requestId > 0 && frame.meterPixels == VK_NULL_HANDLE) {
                makeBuffer(kMeterPixelsSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    frame.meterPixels, frame.meterPixelsMemory, nullptr);
                makeBuffer(sizeof(float), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    frame.meterResult, frame.meterMemory, &frame.meterMapped);
                VkDescriptorBufferInfo info{frame.meterPixels, 0, kMeterPixelsSize};
                VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                write.dstSet = frame.computeDescriptor;
                write.dstBinding = 11;
                write.descriptorCount = 1;
                write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                write.pBufferInfo = &info;
                vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
            }
            if (frame.focusStrength > 0.5f) ensureFocusPreviewResources(frame);
            if (frame.useSpektraOutput) ensureSpektraOutput(frame);
            const auto previewGeometry = readGeometry(geometryAddress, geometryCapacity, rawParameters);
            validateRawBounds(previewGeometry, rawParameters);
            const bool stabilized = previewGeometry.stabilization[0] > 0.5f;
            const bool swapsAxes = (previewGeometry.transform[0] & 1) != 0;
            const int32_t sourceWidth = swapsAxes ? processingHeight : processingWidth;
            const int32_t sourceHeight = swapsAxes ? processingWidth : processingHeight;
            const bool downsamplingRaw =
                previewGeometry.sourceCrop[2] * rawParameters.dimensions[0] > sourceWidth * 1.0001f ||
                previewGeometry.sourceCrop[3] * rawParameters.dimensions[1] > sourceHeight * 1.0001f;
            const bool recordingDownsample = videoOutput_ && downsamplingRaw;
            const bool recordingNative = videoOutput_ && !downsamplingRaw && !stabilized;
            bool recordingTile = false;
            if (recordingDownsample) {
                const float footprint = std::max(
                    previewGeometry.sourceCrop[2] * rawParameters.dimensions[0] / sourceWidth,
                    previewGeometry.sourceCrop[3] * rawParameters.dimensions[1] / sourceHeight);
                // The shared-row tile holds every reduction up to 4x and avoids rereading sensor rows per pixel.
                recordingTile = !stabilized && footprint <= 4.0f && recordingTileSupported();
#if !defined(NDEBUG)
                char recordingOverride[PROP_VALUE_MAX]{};
                __system_property_get("debug.unspektra.recording_filter", recordingOverride);
                if (std::strcmp(recordingOverride, "reference") == 0) recordingTile = false;
#endif
            }
            frame.computeDescriptorsDirty |= frame.recordingDownsample != recordingDownsample ||
                frame.recordingNative != recordingNative;
            frame.recordingDownsample = recordingDownsample;
            frame.recordingNative = recordingNative;
            frame.recordingTile = recordingTile;
            frame.useSharedRawTile =
                !stabilized &&
                std::abs(previewGeometry.sourceCrop[2] * rawParameters.dimensions[0] - sourceWidth) < 0.01f &&
                std::abs(previewGeometry.sourceCrop[3] * rawParameters.dimensions[1] - sourceHeight) < 0.01f;
            frame.reusePreviewShading = optimizedRawPreview() && !ispInput;
            const bool stabilizationPrefilter = stabilized && videoOutput_ && !downsamplingRaw &&
                !lightweightRawPreview() && frame.reusePreviewShading;

            shadingBytes = calibrationTableBytes(spektraParameters, shadingTexels, shadingAddress, shadingCapacity);
            updateLensShadingBuffer(frame,
                shadingTexels > 0 || shadingBytes > sizeof(float) * 4 ? shadingAddress : nullptr, shadingBytes);

            const bool previousTextureShading = frame.textureShading;
            const bool previousUnpackRaw = frame.unpackRaw;
            const bool previousAreaPrefilter = frame.areaPrefilter;
            const bool previousMhcPrefilter = frame.mhcPrefilter;
            frame.textureShading = frame.reusePreviewShading && lensTextureSupported_;
#if !defined(NDEBUG)
            char textureOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_texture", textureOverride);
            if (textureOverride[0] == '0') frame.textureShading = false;
#endif
            if (frame.textureShading) {
                try {
                    prepareLensTexture(frame, std::max(shadingWidth, 1u), std::max(shadingHeight, 1u));
                } catch (const std::exception& error) {
                    destroyPreviewTexture(frame.lensTexture);
                    frame.computeDescriptorsDirty = true;
                    lensTextureSupported_ = false;
                    frame.textureShading = false;
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf",
                        "Using buffered lens shading: %s", error.what());
                }
            }

            const auto uploadStarted = timing.now();
            bool imported = false;
            const bool lightweightPreview = lightweightRawPreview();
            if (ispInput) {
                frame.unpackRaw = false;
                frame.areaPrefilter = false;
                frame.mhcPrefilter = false;
                frame.nativeRawQuad = false;
                frame.rasterRaw = false;
                frame.useSharedRawTile = false;
                frame.remosaic.enabled = false;
                frame.remosaic.previewSized = false;
                frame.hotPixels.enabled = false;
                prepareIspInput(environment, ispHardwareBufferObject, ispPlanesObject, ispParametersObject, frame);
                frame.previewPipeline = frame.ispImageInput ? isp_.imagePipeline : isp_.bufferPipeline;
                frame.computeDescriptorsDirty = true;
                timing.uploadMs = timing.since(uploadStarted);
            } else {
            int inputMode = -1;
#if !defined(NDEBUG)
            char inputOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_input", inputOverride);
            if (inputOverride[0] >= '0' && inputOverride[0] <= '2') inputMode = inputOverride[0] - '0';
#endif
            AHardwareBuffer_Desc cameraDescription{};
            AHardwareBuffer* cameraBuffer = hardwareBufferObject ?
                AHardwareBuffer_fromHardwareBuffer(environment, hardwareBufferObject) : nullptr;
            if (cameraBuffer) AHardwareBuffer_describe(cameraBuffer, &cameraDescription);
            float hotPixelRemoval = 0.0f, noiseS = 0.0f, noiseO = 0.0f;
            std::memcpy(&hotPixelRemoval, spektraParameters.data() + 12, sizeof(float));
            std::memcpy(&noiseS, spektraParameters.data() + 116, sizeof(float));
            std::memcpy(&noiseO, spektraParameters.data() + 120, sizeof(float));
            const std::array<uint64_t, 12> cameraImportKey{
                static_cast<uint64_t>(rawParameters.dimensions[0]), static_cast<uint64_t>(rawParameters.dimensions[1]),
                static_cast<uint64_t>(rawParameters.layoutInfo[0]), static_cast<uint64_t>(rawParameters.layoutInfo[1]),
                cameraDescription.format, cameraDescription.usage, cameraDescription.width, cameraDescription.height,
                cameraDescription.stride, cameraDescription.layers, static_cast<uint64_t>(rawParameters.layoutInfo[2]),
                (lightweightRawPreview() ? 0u : 1u) + (recordingDownsample ? 2u : 0u) + (recordingNative ? 4u : 0u) +
                (hotPixelRemoval > 0.5f ? 8u : 0u)};
            if (!imported && inputMode != 0 && inputMode != 1 && cameraBuffer && bayerCellRawPreview() &&
                (!stabilized || recordingDownsample) &&
                rawParameters.remosaic[0] <= 1 && !compatibleRawPreview() &&
                (hotPixelRemoval <= 0.5f || (rawParameters.remosaic[2] == 0 && rawParameters.remosaic[3] == 0)) &&
                std::find(rejectedCameraImports_.begin(), rejectedCameraImports_.end(), cameraImportKey) == rejectedCameraImports_.end()) {
                try {
                    if (!frame.cameraBridge) frame.cameraBridge = std::make_unique<CameraBayerBridge>();
                    const float footprint = std::max(previewGeometry.sourceCrop[2] * rawParameters.dimensions[0] / sourceWidth,
                        previewGeometry.sourceCrop[3] * rawParameters.dimensions[1] / sourceHeight);
                    const int step = recordingDownsample ? 2 :
                        std::max(2, static_cast<int>(std::ceil(footprint * 0.5f)) * 2);
                    AHardwareBuffer* cells = frame.cameraBridge->sample(
                        cameraBuffer, rawParameters.dimensions[0], rawParameters.dimensions[1], step,
                        rawParameters.layoutInfo[1], rawParameters.layoutInfo[0], packedRawBytesObject ?
                            static_cast<const uint8_t*>(environment->GetDirectBufferAddress(packedRawBytesObject)) : nullptr,
                        packedRawBytesObject ?
                            static_cast<size_t>(std::max<jlong>(0, environment->GetDirectBufferCapacity(packedRawBytesObject))) : 0,
                        rawParameters.layoutInfo[2], rawParameters.blackLevel, rawParameters.whiteLevel,
                        previewGeometry.rawBounds, hotPixelRemoval > 0.5f, noiseS, noiseO);
                    importRawBuffer(environment, nullptr, nullptr, rawParameters, frame, nullptr, cells);
                    frame.bayerCells = true;
                    timing.cameraGpuMs = frame.cameraBridge->gpuMs();
                    imported = true;
                    if (reportedCameraFormat_ != rawParameters.layoutInfo[1]) {
                        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf",
                            "Live RAW input: EGL camera cells shared with Vulkan, format=%d step=%d packed=%d",
                            rawParameters.layoutInfo[1], step, rawParameters.layoutInfo[1] != 0);
                        reportedCameraFormat_ = rawParameters.layoutInfo[1];
                    }
                } catch (const std::exception& error) {
                    releaseImportedBuffer(frame);
                    frame.cameraBridge.reset();
                    rejectedCameraImports_.push_back(cameraImportKey);
                    if (dynamic_cast<const CameraRawLayoutError*>(&error)) rejectedRawImports_.push_back(cameraImportKey);
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf",
                        "Camera input bridge rejected: format=%d size=%dx%d stride=%d ahb=%ux%u stride=%u format=%u usage=%llu: %s",
                        rawParameters.layoutInfo[1], rawParameters.dimensions[0], rawParameters.dimensions[1],
                        rawParameters.layoutInfo[0], cameraDescription.width, cameraDescription.height,
                        cameraDescription.stride, cameraDescription.format,
                        static_cast<unsigned long long>(cameraDescription.usage), error.what());
                }
            }
            if (!imported && !stabilizationPrefilter && inputMode != 0 && inputMode != 2 && hardwareBufferObject && packedRawBytesObject && rawParameters.layoutInfo[1] == 0 &&
                std::find(rejectedRawImports_.begin(), rejectedRawImports_.end(), cameraImportKey) == rejectedRawImports_.end()) {
                try {
                    importRawBuffer(environment, hardwareBufferObject, nullptr, rawParameters, frame);
                    validateRawImage(environment, packedRawBytesObject, rawParameters, frame, cameraImportKey);
                    imported = true;
                    if (!reportedRawImageImport_) {
                        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf", "Live RAW input: direct R16_UINT hardware image");
                        reportedRawImageImport_ = true;
                    }
                } catch (const std::exception& error) {
                    releaseImportedBuffer(frame);
                    rejectedRawImports_.push_back(cameraImportKey);
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Live RAW input: upload fallback: %s", error.what());
                }
            }
            if (!imported) importRawBuffer(environment, hardwareBufferObject, packedRawBytesObject,
                rawParameters, frame, rawUpload);
#if !defined(NDEBUG)
            if (videoTimestampNs > 0 && videoOutput_ && videoOutput_->traceFrames) __android_log_print(ANDROID_LOG_INFO, "VideoFrame",
                "[VideoFrame] native_input pts_ns=%lld path=%s hardware=%d camera_rejections=%zu raw_rejections=%zu upload_us=%.0f",
                static_cast<long long>(videoTimestampNs), frame.bayerCells ? "bayer_cells" :
                    frame.rawImportKind == RawImportKind::Image ? "raw_image" : rawUpload ? "mapped_upload" : "cpu_upload",
                hardwareBufferObject != nullptr, rejectedCameraImports_.size(), rejectedRawImports_.size(),
                timing.since(uploadStarted) * 1000.0);
#endif
            prepareHotPixels(frame, rawParameters, hotPixelRemoval > 0.5f && !frame.bayerCells);
            prepareRemosaic(frame, rawParameters, videoOutput_ ? nullptr : &previewGeometry);
            if (recordingDownsample && frame.bayerCells && !frame.cellLinearSupported) frame.textureShading = false;
            frame.useSharedRawTile &= !frame.remosaic.previewSized;
            timing.uploadMs = timing.since(uploadStarted);
            float dcpTableOffset = 0.0f;
            std::memcpy(&dcpTableOffset, spektraParameters.data() + 96, sizeof(float));
            frame.rasterRaw = (!recordingNative || lightweightPreview) && frame.processingFramebuffer != VK_NULL_HANDLE &&
                frame.reusePreviewShading && processedRawKind(frame) == RawImportKind::Buffer &&
                dcpTableOffset == 0.0f;
#if !defined(NDEBUG)
            char rasterOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_raster", rasterOverride);
            if (rasterOverride[0] == '0') frame.rasterRaw = false;
#endif
            bool mhcDemosaic = !lightweightPreview;
#if !defined(NDEBUG)
            char demosaicOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.demosaic", demosaicOverride);
            if (!lightweightPreview && (demosaicOverride[0] == '0' || demosaicOverride[0] == '1'))
                mhcDemosaic = demosaicOverride[0] == '1';
#endif
            // Area filtering revisits a RAW neighborhood even at small output sizes.
            frame.unpackRaw = (stabilizationPrefilter || (!videoOutput_ && !bayerCellRawPreview())) && frame.reusePreviewShading && unpackSupported_ && !frame.remosaic.previewSized &&
                processedRawKind(frame) == RawImportKind::Buffer &&
                ((downsamplingRaw && !lightweightPreview) || static_cast<uint64_t>(processingWidth) * processingHeight * 4 >=
                    static_cast<uint64_t>(rawParameters.dimensions[0]) * rawParameters.dimensions[1]);
#if !defined(NDEBUG)
            char unpackOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_unpack", unpackOverride);
            if (unpackOverride[0] == '0') frame.unpackRaw = false;
#endif
            const bool mhcPrefilterEligible = frame.unpackRaw && (downsamplingRaw || stabilizationPrefilter) && !lightweightPreview &&
                mhcDemosaic && dcpTableOffset == 0.0f && mhcPrefilterSupported_;
            frame.mhcPrefilter = mhcPrefilterEligible &&
                (stabilizationPrefilter || static_cast<uint64_t>(processingWidth) * processingHeight * 5 >=
                    static_cast<uint64_t>(rawParameters.dimensions[0]) * rawParameters.dimensions[1]);
#if !defined(NDEBUG)
            char prefilterOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_prefilter", prefilterOverride);
            if (prefilterOverride[0] == '0' || prefilterOverride[0] == '1')
                frame.mhcPrefilter = mhcPrefilterEligible && prefilterOverride[0] == '1';
#endif
            // Separable weights are tied to fixed output columns and cannot follow a gyro warp.
            frame.areaPrefilter = frame.unpackRaw && downsamplingRaw && !lightweightPreview && !frame.mhcPrefilter && !stabilized;
#if !defined(NDEBUG)
            char areaOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_area", areaOverride);
            if (areaOverride[0] == '0') frame.areaPrefilter = false;
#endif
            for (int attempt = 0; attempt < 2 && frame.unpackRaw; ++attempt) {
                try {
                    if (ensurePreviewTexture(frame.unpackedTexture,
                            frame.areaPrefilter ? sourceWidth * 2 : rawParameters.dimensions[0], rawParameters.dimensions[1],
                            (frame.areaPrefilter || frame.mhcPrefilter) ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R16_UNORM,
                            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT))
                        frame.computeDescriptorsDirty = true;
                    frame.unpackPipeline = frame.mhcPrefilter ? nativeRawPipeline(rawParameters.layoutInfo[1], rawParameters.layoutInfo[2], frame.textureShading, true) : frame.areaPrefilter ? rawAreaPipeline(rawParameters.layoutInfo[1], rawParameters.layoutInfo[2]) :
                        rawUnpackPipeline(rawParameters.layoutInfo[1]);
                    require(frame.unpackPipeline != VK_NULL_HANDLE ? VK_SUCCESS : VK_ERROR_FEATURE_NOT_PRESENT,
                        "vkCreateComputePipelines");
                    if (frame.areaPrefilter) {
                        const std::array<float, 4> key{previewGeometry.sourceCrop[0], previewGeometry.sourceCrop[2],
                            static_cast<float>(rawParameters.dimensions[0]), static_cast<float>(sourceWidth)};
                        if (frame.areaWeightsKey != key) {
                            frame.areaWeightsReady = false;
                            frame.areaWeightsKey = key;
                        }
                        const auto blocks = (static_cast<uint32_t>(std::ceil(previewGeometry.sourceCrop[2] *
                            rawParameters.dimensions[0] / sourceWidth)) + 11) / 4;
                        const VkDeviceSize bytes = static_cast<VkDeviceSize>(sourceWidth) * blocks * 3 * 4 * sizeof(float);
                        if (bytes > frame.areaWeightsCapacity) {
                            if (frame.areaWeights) vkDestroyBuffer(device_, frame.areaWeights, nullptr);
                            if (frame.areaWeightsMemory) vkFreeMemory(device_, frame.areaWeightsMemory, nullptr);
                            frame.areaWeights = VK_NULL_HANDLE;
                            frame.areaWeightsMemory = VK_NULL_HANDLE;
                            frame.areaWeightsCapacity = 0;
                            makeBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                                frame.areaWeights, frame.areaWeightsMemory, nullptr);
                            frame.areaWeightsCapacity = bytes;
                            frame.areaWeightsReady = false;
                            frame.computeDescriptorsDirty = true;
                        }
                    }
                    break;
                } catch (const std::exception& error) {
                    destroyPreviewTexture(frame.unpackedTexture);
                    frame.computeDescriptorsDirty = true;
                    if (frame.mhcPrefilter) {
                        mhcPrefilterSupported_ = false;
                        frame.mhcPrefilter = false;
                        frame.areaPrefilter = downsamplingRaw && !stabilized;
#if !defined(NDEBUG)
                        if (areaOverride[0] == '0') frame.areaPrefilter = false;
#endif
                        recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf",
                            "Using separable RAW filtering: %s", error.what());
                        continue;
                    }
                    unpackSupported_ = false;
                    frame.unpackRaw = false;
                    frame.areaPrefilter = false;
                    frame.mhcPrefilter = false;
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Using direct RAW decoding: %s", error.what());
                }
            }
            frame.nativeRawQuad = frame.useSharedRawTile && frame.reusePreviewShading && frame.unpackRaw &&
                !downsamplingRaw && !frame.remosaic.previewSized && dcpTableOffset == 0.0f && mhcDemosaic;
            for (size_t axis = 0; axis < 2; ++axis) {
                const float origin = previewGeometry.sourceCrop[axis] * rawParameters.dimensions[axis];
                frame.nativeRawQuad &= std::abs(origin - std::round(origin)) < 0.01f;
            }
#if !defined(NDEBUG)
            char quadOverride[PROP_VALUE_MAX]{};
            __system_property_get("debug.unspektra.preview_quad", quadOverride);
            if (quadOverride[0] == '0' || ((rasterOverride[0] == '0' || rasterOverride[0] == '1') && quadOverride[0] != '1'))
                frame.nativeRawQuad = false;
#endif
            if (frame.nativeRawQuad) {
                try {
                    frame.previewPipeline = nativeRawPipeline(rawParameters.layoutInfo[1], rawParameters.layoutInfo[2], frame.textureShading);
                    frame.nativeRawQuad = frame.previewPipeline != VK_NULL_HANDLE;
                    if (frame.nativeRawQuad) frame.rasterRaw = false;
                } catch (const std::exception& error) {
                    frame.nativeRawQuad = false;
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Using standard native RAW rendering: %s", error.what());
                }
            }
            if (recordingNative) {
                frame.previewPipeline = nativeRecordingPipeline(processedRawKind(frame), rawParameters.layoutInfo[1],
                    rawParameters.layoutInfo[2], frame.rasterRaw, frame.textureShading, !lightweightPreview, frame.bayerCells);
            } else if (recordingDownsample) {
                frame.previewPipeline = recordingPipeline(processedRawKind(frame), rawParameters.layoutInfo[1],
                    rawParameters.layoutInfo[2], frame.rasterRaw, frame.textureShading, frame.recordingTile,
                    !lightweightPreview, frame.bayerCells);
            } else if (!frame.nativeRawQuad) {
                frame.previewPipeline = previewPipeline(
                    processedRawKind(frame), frame.useSharedRawTile, frame.reusePreviewShading,
                    rawParameters.layoutInfo[1], rawParameters.layoutInfo[2], dcpTableOffset != 0.0f,
                    frame.rasterRaw, frame.textureShading, frame.unpackRaw, frame.remosaic.previewSized, mhcDemosaic, frame.areaPrefilter, frame.mhcPrefilter, !lightweightPreview, frame.bayerCells, stabilized);
            }
            frame.computeDescriptorsDirty |= frame.textureShading != previousTextureShading ||
                frame.unpackRaw != previousUnpackRaw || frame.areaPrefilter != previousAreaPrefilter ||
                frame.mhcPrefilter != previousMhcPrefilter;
            }
            std::memcpy(&frame.videoChromaStrength, spektraParameters.data() + 132, sizeof(float));
            if (photoPeaking) frame.videoChromaStrength = 0.0f;
            frame.averageCount = averageCount;
            if (averageCount > 1) ensureAverageResources(frame);
            const bool averageComplete = prepareAverage(frame, averageGroup, averageIndex, averageCount,
                averageRevision, previewGeometry, lightweightPreview);
            if (frame.videoChromaStrength > 0.001f) ensureVideoChromaResources(frame);
            int64_t workPeriodStartNs = 0;
            const double pipelineMs = frame.metrics.prepare(device_, physicalDevice_, queueFamily_,
                timing.enabled || performanceOutput != nullptr, timing.enabled,
                frame.reusePreviewShading, frame.useSharedRawTile || frame.recordingTile, frame.useSpektraOutput, frame.rasterRaw,
                frame.textureShading, frame.unpackRaw, frame.mhcPrefilter ? 2 : frame.areaPrefilter ? 1 : 0,
                rawParameters.dimensions, rawParameters.layoutInfo[1], rawParameters.layoutInfo[2],
                shadingWidth, shadingHeight,
                frame.remosaic.enabled ? frame.remosaic.width : 0,
                frame.remosaic.enabled ? frame.remosaic.height : 0,
                frame.videoChromaStrength > 0.001f,
                &workPeriodStartNs);
            if (performanceOutput != nullptr)
                std::memcpy(performanceOutput, &pipelineMs, sizeof(pipelineMs));
            if (workPeriodStartOutput != nullptr)
                std::memcpy(workPeriodStartOutput, &workPeriodStartNs, sizeof(workPeriodStartNs));
            std::memcpy(frame.rawMapped, &rawParameters, sizeof(rawParameters));
            std::memcpy(frame.spektraMapped, spektraParameters.data(),
                        spektraParameters.size());
            if (ispInput) neutralizeRawColor(frame.spektraMapped);
            float directSdr = 0.0f;
            std::memcpy(&directSdr, spektraParameters.data() + 64, sizeof(float));
            frame.averageEncodeSdr = directSdr > 0.5f ? 1 : 0;
            frame.videoChromaEncodeSdr = frame.averageEncodeSdr;
            if (frame.videoChromaStrength > 0.001f) frame.averageEncodeSdr = 0;
            if (averageCount > 1 || frame.videoChromaStrength > 0.001f) {
                const float linear = 0.0f;
                std::memcpy(static_cast<uint8_t*>(frame.spektraMapped) + 64, &linear, sizeof(float));
            }
            previewRecovery = frame.previewHighlightRecovery ? 1.0f : 0.0f;
            std::memcpy(static_cast<uint8_t*>(frame.spektraMapped) + 128, &previewRecovery, sizeof(float));
            std::memcpy(frame.geometryMapped, &previewGeometry,
                        static_cast<size_t>(kGeometryParametersSize));
            updateFrameDescriptors(frame);
            prepareFocusLoupe(frame, environment, packedRawBytesObject, rawUpload, cameraRawParameters, previewGeometry, ispInput);
            if (frame.shadowStrength > 0.001f) updateShadowDescriptors(frame, true);
            if (frame.previewHighlightRecovery) updatePreviewRecoveryDescriptors(frame);

            uint32_t imageIndex = 0;
            VkResult acquire = VK_SUCCESS;
            if (averageComplete) {
                const auto acquireStarted = timing.now();
                acquire = vkAcquireNextImageKHR(
                    device_, swapchain_, std::numeric_limits<uint64_t>::max(),
                    frame.imageAvailable, VK_NULL_HANDLE, &imageIndex);
                timing.acquireMs = timing.since(acquireStarted);
                if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                    releaseImportedBuffer(frame);
                    recreateSwapchain();
                    setDiagnostic("Swapchain recreated after acquire");
                    return false;
                }
                if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR)
                    require(acquire, "vkAcquireNextImageKHR");
                imageAcquired = true;
                frame.acquirePending = true;
                injectAcquisitionFailure();
            }

            require(vkResetCommandBuffer(frame.rawCommandBuffer, 0),
                    "vkResetCommandBuffer");
            recordRawCommands(frame, meter, averageComplete);

            VkSubmitInfo rawSubmit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            rawSubmit.commandBufferCount = 1;
            rawSubmit.pCommandBuffers = &frame.rawCommandBuffer;
            const bool serialRaw = serialRawPreview() || !averageComplete;
            if (serialRaw) {
                require(vkResetFences(device_, 1, &frame.fence),
                        "vkResetFences for RAW processing");
            }
            VkResult rawSubmitResult;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                rawSubmitResult = vkQueueSubmit(
                    queue_, 1, &rawSubmit, serialRaw ? frame.fence : VK_NULL_HANDLE);
            }
            if (rawSubmitResult != VK_SUCCESS) {
                if (serialRaw) restoreSignaledFence(frame);
                require(rawSubmitResult, "vkQueueSubmit for RAW processing");
            }
            rawSubmitted = true;
            frame.meterId = meter.requestId;
            previewFallbackTexture_.initialized = true;
            previewFallbackTexture_.uploadPending = false;
            if (frame.textureShading) {
                frame.lensTexture.initialized = true;
                frame.lensTexture.uploadPending = false;
            }
            if (frame.unpackRaw) frame.unpackedTexture.initialized = true;
            if (frame.areaPrefilter) frame.areaWeightsReady = true;
            if (serialRaw) {
                require(vkWaitForFences(device_, 1, &frame.fence, VK_TRUE,
                                        std::numeric_limits<uint64_t>::max()),
                        "vkWaitForFences for RAW processing");
            }

            if (!averageComplete) {
                timing.wallMs = timing.since(timing.started);
                frame.metrics.submitted(device_, timing);
                setDiagnostic("RAW frame accumulated");
                return true;
            }
            const VkImage colorImage = frame.videoChromaStrength > 0.001f ? videoChromaResult(frame).image : frame.processingImage;
            const VkImageView colorView = frame.videoChromaStrength > 0.001f ? videoChromaResult(frame).view : frame.processingView;
            VkImage outputImage = frame.focusStrength > 0.5f ? frame.focusOutput.image : colorImage;
            const auto spektraStarted = timing.now();
            if (frame.useSpektraOutput) {
                const auto now = std::chrono::steady_clock::now().time_since_epoch();
                const double timeSeconds =
                    std::chrono::duration<double>(now).count();
                const char* spektraError = nullptr;
                if (!SpektraFilmRenderVulkanImagesWithRendererNative(
                        spektraRenderer_.get(), colorImage,
                        frame.spektraImage, processingWidth_, processingHeight_,
                        spektraFilmParameters.data(),
                        spektraFilmParameters.size(), timeSeconds, true,
                        &spektraError, frame.exposureZebra > 0.5f ? frame.exposureRange : VK_NULL_HANDLE,
                        {colorView, frame.spektraView, processingImageGeneration_}, videoMode > 0.5f)) {
                    throw std::runtime_error(
                        std::string("SpektraFilm preview failed: ") +
                        (spektraError ? spektraError : "unknown native error"));
                }
                frame.spektraInitialized = true;
                outputImage = frame.spektraImage;
            }
            if (frame.focusStrength > 0.5f) outputImage = frame.focusOutput.image;
            timing.spektraMs = frame.useSpektraOutput ? timing.since(spektraStarted) : 0;

            bool videoAcquired = false;
            if (videoOutput_ && videoTimestampNs > 0) {
                try { videoAcquired = videoOutput_->acquire(frameIndex_); }
                catch (const std::exception& error) { failVideo(error.what()); }
                if (encoderInputOutput && videoOutput_) {
                    const int64_t inputTiming[] = {videoOutput_->inputWaitNs, videoOutput_->inputCapacity()};
                    std::memcpy(encoderInputOutput, inputTiming, sizeof(inputTiming));
                }
            }

            require(vkResetCommandBuffer(frame.commandBuffer, 0),
                    "vkResetCommandBuffer for presentation");
            const float blurStrength = lensSwitchBlurStrength();
            recordPresentCommands(
                frame, imageIndex, outputImage,
                previewPresentation(rawParameters, previewGeometry), blurStrength, true, videoAcquired);

            std::array<VkPipelineStageFlags, 2> waitStages{
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
            std::array<VkSemaphore, 2> waits{frame.imageAvailable,
                videoAcquired ? videoOutput_->available() : VK_NULL_HANDLE};
            std::array<VkSemaphore, 2> signals{presentSemaphores_[imageIndex],
                videoAcquired ? videoOutput_->finished() : VK_NULL_HANDLE};
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.waitSemaphoreCount = videoAcquired ? 2 : 1;
            submit.pWaitSemaphores = waits.data();
            submit.pWaitDstStageMask = waitStages.data();
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &frame.commandBuffer;
            submit.signalSemaphoreCount = videoAcquired ? 2 : 1;
            submit.pSignalSemaphores = signals.data();
            require(vkResetFences(device_, 1, &frame.fence), "vkResetFences");
            VkResult submitResult;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                submitResult = vkQueueSubmit(queue_, 1, &submit, frame.fence);
            }
            if (submitResult != VK_SUCCESS) {
                vkDestroyFence(device_, frame.fence, nullptr);
                VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
                fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
                require(vkCreateFence(device_, &fenceInfo, nullptr, &frame.fence),
                        "vkCreateFence after failed submit");
                require(submitResult, "vkQueueSubmit");
            }
            submitted = true;
            if (videoAcquired) videoOutput_->submitted();
            frame.acquirePending = false;
            frame.samples.ready = frame.samples.recorded;

            VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            present.waitSemaphoreCount = 1;
            present.pWaitSemaphores = &presentSemaphores_[imageIndex];
            present.swapchainCount = 1;
            present.pSwapchains = &swapchain_;
            present.pImageIndices = &imageIndex;
            VkResult presented;
            const auto presentStarted = timing.now();
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                presented = presentWithTiming(present);
                if (videoAcquired) {
                    try {
                        timespec submitted{};
                        if (encoderSubmissionOutput) clock_gettime(CLOCK_MONOTONIC, &submitted);
                        videoOutput_->present(videoTimestampNs);
                        if (encoderInputOutput) {
                            const int64_t inputTiming[] = {videoOutput_->inputWaitNs, videoOutput_->inputCapacity()};
                            std::memcpy(encoderInputOutput, inputTiming, sizeof(inputTiming));
                        }
                        if (encoderSubmissionOutput) {
                            const int64_t submittedNs = static_cast<int64_t>(submitted.tv_sec) * 1'000'000'000 + submitted.tv_nsec;
                            std::memcpy(encoderSubmissionOutput, &submittedNs, sizeof(submittedNs));
                        }
                    }
                    catch (const std::exception& error) { failVideo(error.what()); }
                }
            }
            timing.presentMs = timing.since(presentStarted);
            const bool stablePresentation =
                presented == VK_SUCCESS && acquire == VK_SUCCESS;
            if (presented == VK_ERROR_OUT_OF_DATE_KHR ||
                presented == VK_SUBOPTIMAL_KHR || acquire == VK_SUBOPTIMAL_KHR) {
                recreateSwapchain();
            } else if (presented != VK_SUCCESS) {
                require(presented, "vkQueuePresentKHR");
            }
            if (stablePresentation)
                finishLensSwitchPresentation();

            // The camera's Image lease ends when this call returns.
            if ((imported && !frame.bayerCells) || frame.ispImageInput) require(vkWaitForFences(device_, 1, &frame.fence, VK_TRUE,
                    std::numeric_limits<uint64_t>::max()), "vkWaitForFences for camera image release");

            lastPresentedFrameIndex_ = frameIndex_;
            ++presentedSequence_;
            lastPresentedImage_ = outputImage;
            lastCleanPreviewImage_ = stablePresentation ? (frame.useSpektraOutput ? frame.spektraImage : colorImage) : VK_NULL_HANDLE;
            lastPreviewTimestamp_ = stablePresentation ? previewTimestampNs : 0;
            lastPresentation_ = previewPresentation(rawParameters, previewGeometry);
            hasPresentedFrame_ = true;
            pipelineCache_.save();
            frameIndex_ = (frameIndex_ + 1) % kFramesInFlight;
            timing.wallMs = timing.since(timing.started);
            frame.metrics.submitted(device_, timing);
            const char* inputDiagnostic = ispInput ? (frame.ispImageInput ? "Frame rendered from the ISP viewfinder hardware image" :
                    "Frame rendered from copied ISP viewfinder planes") :
                frame.bayerCells ? "Frame rendered from EGL camera cells shared with Vulkan" :
                frame.rawImportKind == RawImportKind::Image ? "Frame rendered from a direct RAW hardware image" :
                "Frame rendered from a row-stride-aware RAW Vulkan upload buffer";
            if (frame.recordingNative)
                setDiagnostic(std::string(inputDiagnostic) + (lightweightPreview ?
                    " (native recording bilinear)" : " (native recording MHC)"));
            else if (frame.recordingDownsample)
                setDiagnostic(std::string(inputDiagnostic) + (frame.recordingTile ?
                    " (tiled recording filter)" : " (reference recording filter)"));
            else setDiagnostic(inputDiagnostic);
            return true;
        } catch (const std::exception& error) {
            gpuPerformance_.stop();
            averageLastIndex_ = -1;
            if (!rawSubmitted) averageHistory_.initialized = false;
            if (activeFrame != nullptr) activeFrame->metrics.cancel();
            if (videoOutput_) failVideo(error.what());
            if (rawSubmitted && (!submitted || hardwareBufferObject != nullptr)) {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                vkQueueWaitIdle(queue_);
            }
            if (activeFrame != nullptr && !submitted) {
                releaseImportedBuffer(*activeFrame);
                releaseIspImage(*activeFrame);
                activeFrame->processingInitialized = false;
            }
            if (imageAcquired && !submitted) {
                try {
                    recreateSwapchain();
                } catch (const std::exception& recoveryError) {
                    recordNativeDiagnostic(ANDROID_LOG_ERROR, "CameraRecovery", "%s", recoveryError.what());
                }
            }
            setDiagnostic(error.what());
            return false;
        }
    }

    bool pollExposureMeter(JNIEnv* environment, jobject buffer)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        if (device_ == VK_NULL_HANDLE || buffer == nullptr ||
            environment->GetDirectBufferCapacity(buffer) < static_cast<jlong>(sizeof(ExposureMeterExchange))) return false;
        void* address = environment->GetDirectBufferAddress(buffer);
        if (address == nullptr) return false;
        ExposureMeterExchange meter{};
        std::memcpy(&meter, address, sizeof(meter));
        meter.resultId = 0;
        if (!collectExposureMeter(meter)) return false;
        std::memcpy(address, &meter, sizeof(meter));
        return true;
    }

    bool beginLensSwitchBlur()
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        rejectedCameraImports_.clear();
        resetRawImageValidation();
        rejectedRawImports_.clear();
        reportedCameraFormat_ = -1;
        for (auto& frame : frames_) if (frame.cameraBridge) frame.cameraBridge->resetValidation();
        bool imageAcquired = false;
        bool frameSubmitted = false;
        try {
            if (swapchainRecoveryPending_) recreateSwapchain();
            if (device_ == VK_NULL_HANDLE || swapchain_ == VK_NULL_HANDLE ||
                !hasPresentedFrame_ || lastPresentedImage_ == VK_NULL_HANDLE)
                return false;

            require(vkDeviceWaitIdle(device_), "vkDeviceWaitIdle for lens switch");
            Frame& frame = frames_[lastPresentedFrameIndex_];
            require(vkWaitForFences(
                        device_, 1, &frame.fence, VK_TRUE,
                        std::numeric_limits<uint64_t>::max()),
                    "vkWaitForFences for lens switch");
            reportPresentSamples(frame);

            uint32_t imageIndex = 0;
            VkResult acquire = vkAcquireNextImageKHR(
                device_, swapchain_, std::numeric_limits<uint64_t>::max(),
                frame.imageAvailable, VK_NULL_HANDLE, &imageIndex);
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain();
                return false;
            }
            if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR)
                require(acquire, "vkAcquireNextImageKHR for lens switch");
            imageAcquired = true;
            frame.acquirePending = true;
            injectAcquisitionFailure();

            require(vkResetCommandBuffer(frame.commandBuffer, 0),
                    "vkResetCommandBuffer for lens switch");
            recordPresentCommands(
                frame, imageIndex, lastPresentedImage_,
                lastPresentation_, 1.0f, false);

            VkPipelineStageFlags waitStage =
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.waitSemaphoreCount = 1;
            submit.pWaitSemaphores = &frame.imageAvailable;
            submit.pWaitDstStageMask = &waitStage;
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &frame.commandBuffer;
            submit.signalSemaphoreCount = 1;
            submit.pSignalSemaphores = &presentSemaphores_[imageIndex];
            require(vkResetFences(device_, 1, &frame.fence),
                    "vkResetFences for lens switch");
            VkResult submitted;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                submitted = vkQueueSubmit(queue_, 1, &submit, frame.fence);
            }
            if (submitted != VK_SUCCESS) {
                restoreSignaledFence(frame);
                require(submitted, "vkQueueSubmit for lens switch");
            }
            frameSubmitted = true;
            frame.acquirePending = false;
            frame.samples.ready = frame.samples.recorded;

            VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            present.waitSemaphoreCount = 1;
            present.pWaitSemaphores = &presentSemaphores_[imageIndex];
            present.swapchainCount = 1;
            present.pSwapchains = &swapchain_;
            present.pImageIndices = &imageIndex;
            VkResult presented;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                presented = presentWithTiming(present);
            }
            if (presented == VK_ERROR_OUT_OF_DATE_KHR ||
                presented == VK_SUBOPTIMAL_KHR || acquire == VK_SUBOPTIMAL_KHR) {
                recreateSwapchain();
                return false;
            } else if (presented != VK_SUCCESS) {
                require(presented, "vkQueuePresentKHR for lens switch");
            }
            VkResult queueIdle;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                queueIdle = vkQueueWaitIdle(queue_);
            }
            require(queueIdle, "vkQueueWaitIdle for lens switch");

            lensSwitchRevealPending_ = true;
            lensSwitchRevealActive_ = false;
            setDiagnostic("Vulkan lens-switch blur bridge active");
            return true;
        } catch (const std::exception& error) {
            if (imageAcquired && !frameSubmitted) {
                try {
                    recreateSwapchain();
                } catch (const std::exception& recoveryError) {
                    recordNativeDiagnostic(ANDROID_LOG_ERROR, "CameraRecovery", "%s", recoveryError.what());
                }
            }
            lensSwitchRevealPending_ = false;
            lensSwitchRevealActive_ = false;
            setDiagnostic(error.what());
            return false;
        }
    }

    bool captureAmaze(
        JNIEnv* environment,
        jobject hardwareBufferObject,
        jobject packedRawBytesObject,
        jobject rawParametersObject,
        jobject rawColorParametersObject,
        jobject geometryParametersObject,
        jobject spektraFilmParametersObject,
        jobject lensShadingObject,
        jobject outputObject,
        double spektraTimeSeconds,
        bool halfFloatOutput,
        bool cooperativeScheduling,
        bool bitmapOutput,
        uint32_t gpuPauseMillis)
    {
        const auto captureStarted = std::chrono::steady_clock::now();
        recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline", "renderer=%p stage=waiting bitmap=%d pause_ms=%u",
                            static_cast<void*>(this), bitmapOutput, gpuPauseMillis);
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        camera_gpu::ExportScope exportScope(cooperativeScheduling, gpuPauseMillis);
        bool rawSubmitted = false;
        struct BitmapLock {
            JNIEnv* environment;
            jobject bitmap = nullptr;
            ~BitmapLock() { if (bitmap != nullptr) AndroidBitmap_unlockPixels(environment, bitmap); }
        } bitmapLock{environment};
        try {
            if (device_ == VK_NULL_HANDLE) {
                selectDeviceAndQueue();
                createDeviceResources();
            }
            pipelineCache_.refresh();
            if (hardwareBufferObject == nullptr && packedRawBytesObject == nullptr)
                throw std::runtime_error("RAW capture has neither a HardwareBuffer nor copied plane bytes");

            void* rawAddress = environment->GetDirectBufferAddress(rawParametersObject);
            jlong rawCapacity = environment->GetDirectBufferCapacity(rawParametersObject);
            void* spektraAddress =
                environment->GetDirectBufferAddress(rawColorParametersObject);
            jlong spektraCapacity =
                environment->GetDirectBufferCapacity(rawColorParametersObject);
            void* geometryAddress =
                environment->GetDirectBufferAddress(geometryParametersObject);
            jlong geometryCapacity =
                environment->GetDirectBufferCapacity(geometryParametersObject);
            void* spektraFilmAddress =
                environment->GetDirectBufferAddress(spektraFilmParametersObject);
            jlong spektraFilmCapacity =
                environment->GetDirectBufferCapacity(spektraFilmParametersObject);
            AndroidBitmapInfo bitmapInfo{};
            void* outputAddress = nullptr;
            jlong outputCapacity = 0;
            if (bitmapOutput) {
                if (halfFloatOutput || AndroidBitmap_getInfo(environment, outputObject, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
                    bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || bitmapInfo.stride != bitmapInfo.width * 4u ||
                    AndroidBitmap_lockPixels(environment, outputObject, &outputAddress) != ANDROID_BITMAP_RESULT_SUCCESS) {
                    throw std::runtime_error("Capture requires a mutable tightly packed RGBA8 bitmap");
                }
                bitmapLock.bitmap = outputObject;
                outputCapacity = static_cast<jlong>(bitmapInfo.stride) * bitmapInfo.height;
            } else {
                outputAddress = environment->GetDirectBufferAddress(outputObject);
                outputCapacity = environment->GetDirectBufferCapacity(outputObject);
            }
            if (rawAddress == nullptr || (rawCapacity != 64 && rawCapacity <
                    static_cast<jlong>(kRawParametersSize))) {
                throw std::runtime_error(
                    "RAW parameters must be a direct ByteBuffer of at least 64 bytes");
            }
            if (spektraAddress == nullptr || spektraCapacity <
                    static_cast<jlong>(kRawColorParametersSize)) {
                throw std::runtime_error(
                    "RAW color parameters must be a direct ByteBuffer of at least 144 bytes");
            }
            if (spektraFilmAddress == nullptr || spektraFilmCapacity <
                    static_cast<jlong>(kSpektraFilmParametersSize)) {
                throw std::runtime_error(
                    "SpektraFilm parameters must be a direct ByteBuffer of at least 648 bytes");
            }
            if (outputAddress == nullptr)
                throw std::runtime_error("Capture output must be a direct ByteBuffer");

            RawParameters rawParameters{};
            std::memcpy(&rawParameters, rawAddress, std::min(static_cast<size_t>(rawCapacity), sizeof(rawParameters)));
            if (rawCapacity == 64) rawParameters.remosaic[1] = rawParameters.layoutInfo[2];
            validateRawParameters(rawParameters);
            auto geometryParameters = readGeometry(geometryAddress, geometryCapacity, rawParameters);
            if (geometryParameters.transform[0] < 0 ||
                geometryParameters.transform[0] > 3 ||
                geometryParameters.transform[2] <= 0 ||
                geometryParameters.transform[3] <= 0) {
                throw std::runtime_error(
                    "Capture geometry has an invalid transform or output size");
            }
            for (float value : geometryParameters.sourceCrop) {
                if (!std::isfinite(value))
                    throw std::runtime_error(
                        "Capture source crop must be finite");
            }
            // Saved crops re-mapped to a cropped RAW can land a rounding error below zero.
            if (geometryParameters.sourceCrop[0] < -0.0001f ||
                geometryParameters.sourceCrop[1] < -0.0001f ||
                geometryParameters.sourceCrop[2] <= 0.0f ||
                geometryParameters.sourceCrop[3] <= 0.0f ||
                geometryParameters.sourceCrop[0] +
                        geometryParameters.sourceCrop[2] > 1.0001f ||
                geometryParameters.sourceCrop[1] +
                        geometryParameters.sourceCrop[3] > 1.0001f) {
                throw std::runtime_error(
                    "Capture source crop must stay within normalized RAW bounds");
            }
            geometryParameters.sourceCrop[0] = std::max(geometryParameters.sourceCrop[0], 0.0f);
            geometryParameters.sourceCrop[1] = std::max(geometryParameters.sourceCrop[1], 0.0f);
            validateRawBounds(geometryParameters, rawParameters);
            uint32_t rawWidth = static_cast<uint32_t>(geometryParameters.rawBounds[2]);
            uint32_t rawHeight = static_cast<uint32_t>(geometryParameters.rawBounds[3]);
            uint32_t outputWidth =
                static_cast<uint32_t>(geometryParameters.transform[2]);
            uint32_t outputHeight =
                static_cast<uint32_t>(geometryParameters.transform[3]);
            rawParameters.dimensions[2] = static_cast<int32_t>(outputWidth);
            rawParameters.dimensions[3] = static_cast<int32_t>(outputHeight);
            if (bitmapOutput && (bitmapInfo.width != outputWidth || bitmapInfo.height != outputHeight))
                throw std::runtime_error("Capture bitmap dimensions do not match output geometry");
            uint64_t outputBytes64 = static_cast<uint64_t>(outputWidth) *
                                     static_cast<uint64_t>(outputHeight) * (halfFloatOutput ? 8u : 4u);
            if (outputBytes64 > static_cast<uint64_t>(
                    std::numeric_limits<jlong>::max()) ||
                outputCapacity < static_cast<jlong>(outputBytes64)) {
                throw std::runtime_error(
                    "Capture output ByteBuffer is smaller than oriented width times height times four bytes");
            }

            std::array<std::byte, kRawColorParametersSize> spektraParameters{};
            std::memcpy(spektraParameters.data(), spektraAddress,
                        spektraParameters.size());
            std::array<std::byte, kSpektraFilmParametersSize> spektraFilmParameters{};
            std::memcpy(spektraFilmParameters.data(), spektraFilmAddress,
                        spektraFilmParameters.size());
            int32_t spektraFilmEnabled = 0;
            std::memcpy(&spektraFilmEnabled, spektraFilmParameters.data(),
                        sizeof(spektraFilmEnabled));
            float shadingWidthValue = 0.0f;
            float shadingHeightValue = 0.0f;
            std::memcpy(&shadingWidthValue, spektraParameters.data() + 136,
                        sizeof(float));
            std::memcpy(&shadingHeightValue, spektraParameters.data() + 140,
                        sizeof(float));
            uint32_t shadingWidth = checkedMapDimension(
                shadingWidthValue, "Lens shading width");
            uint32_t shadingHeight = checkedMapDimension(
                shadingHeightValue, "Lens shading height");
            if ((shadingWidth == 0) != (shadingHeight == 0))
                throw std::runtime_error(
                    "Lens shading width and height must both be zero or positive");
            uint64_t shadingTexels = static_cast<uint64_t>(shadingWidth) *
                                     static_cast<uint64_t>(shadingHeight);
            if (shadingTexels > 1024u * 1024u)
                throw std::runtime_error("Lens shading map is unreasonably large");
            VkDeviceSize shadingBytes = shadingTexels == 0
                ? sizeof(float) * 4
                : shadingTexels * sizeof(float) * 4;
            void* shadingAddress = lensShadingObject == nullptr
                ? nullptr
                : environment->GetDirectBufferAddress(lensShadingObject);
            jlong shadingCapacity = lensShadingObject == nullptr
                ? -1
                : environment->GetDirectBufferCapacity(lensShadingObject);
            if (shadingTexels > 0 &&
                (shadingAddress == nullptr || shadingCapacity <
                    static_cast<jlong>(shadingBytes))) {
                throw std::runtime_error(
                    "Lens shading map must contain width times height interleaved R, G-even, G-odd, B float gains");
            }

            Frame& frame = captureFrame_;
            frame.useSpektraOutput = spektraFilmEnabled != 0;
            recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline",
                "renderer=%p stage=prepare raw=%ux%u output=%ux%u spektra=%d",
                static_cast<void*>(this), rawWidth, rawHeight, outputWidth, outputHeight, frame.useSpektraOutput);
            require(vkWaitForFences(device_, 1, &frame.fence, VK_TRUE,
                                    std::numeric_limits<uint64_t>::max()),
                    "vkWaitForFences for capture");
            releaseImportedBuffer(frame);
            ensureCaptureResources(outputWidth, outputHeight, halfFloatOutput);
            std::memcpy(&captureChromaStrength_, spektraParameters.data() + 112, sizeof(float));
            if (!std::isfinite(captureChromaStrength_) || captureChromaStrength_ < 0.0f || captureChromaStrength_ > 1.0f)
                throw std::runtime_error("Capture chroma denoise strength must be between 0 and 1");
            if (captureChromaStrength_ > 0.001f) ensureChromaDenoiseResources();
            float recovery = 0.0f;
            std::memcpy(&recovery, spektraParameters.data() + 76, sizeof(float));
            captureHighlightRecovery_ = recovery > 0.5f;
            ensureAmazeResources(rawWidth, rawHeight);
            ensureHighlightRecoveryResources();
            std::memcpy(&frame.shadowStrength, spektraParameters.data() + 108, sizeof(float));
            if (frame.shadowStrength > 0.001f) ensureShadowResources(frame, rawWidth, rawHeight, false);
            shadingBytes = calibrationTableBytes(spektraParameters, shadingTexels, shadingAddress, shadingCapacity);
            updateLensShadingBuffer(frame,
                shadingTexels > 0 || shadingBytes > sizeof(float) * 4 ? shadingAddress : nullptr, shadingBytes);
            importRawBuffer(
                environment, hardwareBufferObject, packedRawBytesObject,
                rawParameters, frame);
            float hotPixelRemoval = 0.0f;
            std::memcpy(&hotPixelRemoval, spektraParameters.data() + 12, sizeof(float));
            prepareHotPixels(frame, rawParameters, hotPixelRemoval > 0.5f);
            prepareRemosaic(frame, rawParameters);
            const int32_t croppedCfa = rawParameters.layoutInfo[2] ^ (geometryParameters.rawBounds[0] & 1) ^
                ((geometryParameters.rawBounds[1] & 1) << 1);
            ensureAmazePipelines(croppedCfa,
                processedRawKind(frame) == RawImportKind::Image ? 1u : 0u);
            if (captureColorPipeline_ == VK_NULL_HANDLE) {
                captureColorPipeline_ = createComputeProgram(
                    kCaptureColorSpirv.data(), kCaptureColorSpirv.size(), nullptr);
            }
            std::memcpy(frame.rawMapped, &rawParameters, sizeof(rawParameters));
            std::memcpy(frame.spektraMapped, spektraParameters.data(),
                        spektraParameters.size());
            std::memcpy(frame.geometryMapped, &geometryParameters,
                        sizeof(geometryParameters));
            updateCaptureDescriptor(frame);
            updateShadowDescriptors(frame, false);
            updateAmazeDescriptors(frame);

            require(vkResetCommandBuffer(frame.commandBuffer, 0),
                    "vkResetCommandBuffer for capture");
            VkImage outputImage = recordCaptureCommands(frame);
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &frame.commandBuffer;
            require(vkResetFences(device_, 1, &frame.fence),
                    "vkResetFences for capture");
            VkResult submitResult;
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                submitResult = vkQueueSubmit(queue_, 1, &submit, frame.fence);
            }
            if (submitResult != VK_SUCCESS) {
                restoreSignaledFence(frame);
                require(submitResult, "vkQueueSubmit for capture");
            }
            rawSubmitted = true;
            require(vkWaitForFences(device_, 1, &frame.fence, VK_TRUE,
                                    std::numeric_limits<uint64_t>::max()),
                    "vkWaitForFences for capture RAW processing");
            recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline", "renderer=%p stage=raw_complete",
                                static_cast<void*>(this));
            releaseImportedBuffer(frame);
            // RAW intermediates are no longer needed while the film buffers are allocated.
            destroyAmazeResources();
            destroyShadowResources(frame);
            destroyPackedRawBuffer(frame);
            destroyRawBufferResources(frame.remosaic);
            destroyRawBufferResources(frame.hotPixels);

            if (frame.useSpektraOutput) {
                const char* spektraError = nullptr;
                if (!SpektraFilmRenderVulkanImagesWithRendererNative(
                        spektraRenderer_.get(), outputImage,
                        captureSpektraImage_, outputWidth, outputHeight,
                        spektraFilmParameters.data(),
                        spektraFilmParameters.size(), spektraTimeSeconds, false,
                        &spektraError)) {
                    throw std::runtime_error(
                        std::string("SpektraFilm capture failed: ") +
                        (spektraError ? spektraError : "unknown native error"));
                }
                outputImage = captureSpektraImage_;
                const auto& diagnostics = spektraRenderer_->lastDiagnostics();
                recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline",
                    "renderer=%p stage=film_complete tiled=%d tiles=%u transient_bytes=%llu",
                    static_cast<void*>(this), diagnostics.tiledRendering, diagnostics.tileCount,
                    static_cast<unsigned long long>(diagnostics.transientCachedBytes));
            }

            recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline", "renderer=%p stage=readback",
                                static_cast<void*>(this));

            require(vkResetCommandBuffer(frame.commandBuffer, 0),
                    "vkResetCommandBuffer for capture readback");
            recordCaptureReadbackCommands(frame, outputImage);
            require(vkResetFences(device_, 1, &frame.fence),
                    "vkResetFences for capture readback");
            {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                submitResult = vkQueueSubmit(queue_, 1, &submit, frame.fence);
            }
            if (submitResult != VK_SUCCESS) {
                restoreSignaledFence(frame);
                require(submitResult, "vkQueueSubmit for capture readback");
            }
            require(vkWaitForFences(device_, 1, &frame.fence, VK_TRUE,
                                    std::numeric_limits<uint64_t>::max()),
                    "vkWaitForFences for capture readback");

            std::memcpy(outputAddress, captureStagingMapped_,
                        static_cast<size_t>(outputBytes64));
            recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline", "renderer=%p stage=complete bytes=%llu elapsed_ms=%.1f",
                static_cast<void*>(this), static_cast<unsigned long long>(outputBytes64),
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - captureStarted).count());
            pipelineCache_.save();
            if (frame.useSpektraOutput) {
                const auto& diagnostics = spektraRenderer_->lastDiagnostics();
                setDiagnostic("Full-resolution AMAZE capture processed by SpektraFilm (" +
                    std::string(diagnostics.tiledRendering ? "tiled, " : "full-frame, ") +
                    std::to_string(diagnostics.tileCount) + " tiles)");
            } else setDiagnostic("Full-resolution linear AMAZE capture completed");
            releaseCaptureWorkingResources();
            return true;
        } catch (const std::exception& error) {
            releaseImportedBuffer(captureFrame_);
            if (!rawSubmitted) {
                captureInitialized_ = false;
                captureSpektraLayoutInitialized_ = false;
                amazeLayoutsInitialized_ = false;
                hrLayoutsInitialized_ = false;
                chromaInitialized_ = false;
            }
            if (device_ != VK_NULL_HANDLE && captureFrame_.fence != VK_NULL_HANDLE &&
                vkGetFenceStatus(device_, captureFrame_.fence) == VK_SUCCESS) {
                releaseCaptureWorkingResources();
            }
            setDiagnostic(error.what());
            return false;
        }
    }

    std::string diagnostic() const
    {
        std::lock_guard<std::mutex> lock(diagnosticMutex_);
        return diagnostic_;
    }

private:
    struct PresentSamples {
        static constexpr uint32_t kCount = 16;
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        bool recorded = false;
        bool ready = false;
        bool spektra = false;
        uint64_t sequence = 0;
        uint32_t imageIndex = 0;
        VkExtent2D extent{};
        float aspect = 0;
        float blur = 0;
    };

    struct PreviewTexture {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkBuffer staging = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        VkFormat format = VK_FORMAT_UNDEFINED;
        VkImageUsageFlags usage = 0;
        uint32_t stagingPixelBytes = 0;
        uint32_t width = 0;
        uint32_t height = 0;
        bool initialized = false;
        bool uploadPending = false;
    };

    struct HistogramKey {
        uint64_t epoch = 0;
        int64_t generation = 0;
        float cropAspect = 0;
        PreviewPresentation presentation;

        bool operator==(const HistogramKey& other) const {
            return epoch == other.epoch && generation == other.generation && cropAspect == other.cropAspect &&
                presentation.aspect == other.presentation.aspect && presentation.crop == other.presentation.crop;
        }
    };

    jintArray readHistogram(JNIEnv* environment, bool bins)
    {
        constexpr size_t pixels = 128 * 128;
        const auto* bytes = static_cast<const uint8_t*>(histogramTexture_.mapped);
        if (bins) {
            std::array<jint, 768> counts{};
            if (histogramGpuRequest_) std::memcpy(counts.data(), bytes, sizeof(counts));
            else for (size_t index = 0; index < pixels; ++index) {
                ++counts[bytes[index * 4]];
                ++counts[256 + bytes[index * 4 + 1]];
                ++counts[512 + bytes[index * 4 + 2]];
            }
            jintArray result = environment->NewIntArray(counts.size());
            if (result) environment->SetIntArrayRegion(result, 0, counts.size(), counts.data());
            return result;
        }
        std::array<jint, pixels> colors{};
        for (size_t index = 0; index < pixels; ++index)
            colors[index] = static_cast<jint>(0xff000000u | (bytes[index * 4] << 16) |
                (bytes[index * 4 + 1] << 8) | bytes[index * 4 + 2]);
        jintArray result = environment->NewIntArray(colors.size());
        if (result) environment->SetIntArrayRegion(result, 0, colors.size(), colors.data());
        return result;
    }

    void prepareHistogramProgram()
    {
        if (!histogramDescriptorLayout_) {
            const std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
                {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = bindings.size();
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &histogramDescriptorLayout_), "vkCreateDescriptorSetLayout");
        }
        if (!histogramPipelineLayout_) {
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &histogramDescriptorLayout_;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &histogramPipelineLayout_), "vkCreatePipelineLayout");
        }
        if (!histogramPipeline_) histogramPipeline_ = createComputeProgram(kHistogramSpirv.data(), kHistogramSpirv.size(), nullptr, histogramPipelineLayout_);
    }

    void prepareHistogramPipeline()
    {
        prepareHistogramProgram();
        if (!histogramDescriptorPool_) {
            const std::array<VkDescriptorPoolSize, 2> sizes{{
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1}}};
            VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            info.maxSets = 1;
            info.poolSizeCount = sizes.size();
            info.pPoolSizes = sizes.data();
            require(vkCreateDescriptorPool(device_, &info, nullptr, &histogramDescriptorPool_), "vkCreateDescriptorPool");
        }
        if (!histogramDescriptor_) {
            VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            info.descriptorPool = histogramDescriptorPool_;
            info.descriptorSetCount = 1;
            info.pSetLayouts = &histogramDescriptorLayout_;
            require(vkAllocateDescriptorSets(device_, &info, &histogramDescriptor_), "vkAllocateDescriptorSets");
            const VkDescriptorImageInfo image{VK_NULL_HANDLE, histogramTexture_.view, VK_IMAGE_LAYOUT_GENERAL};
            const VkDescriptorBufferInfo buffer{histogramTexture_.staging, 0, 768 * sizeof(uint32_t)};
            std::array<VkWriteDescriptorSet, 2> writes{};
            for (uint32_t index = 0; index < writes.size(); ++index) {
                writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[index].dstSet = histogramDescriptor_;
                writes[index].dstBinding = index;
                writes[index].descriptorCount = 1;
            }
            writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[0].pImageInfo = &image;
            writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[1].pBufferInfo = &buffer;
            vkUpdateDescriptorSets(device_, writes.size(), writes.data(), 0, nullptr);
        }
        recordNativeDiagnostic(ANDROID_LOG_INFO, "ViewfinderHistogram", "bins=768 gpu=1");
    }

    void drainHistogram()
    {
        if (histogramPending_) vkWaitForFences(device_, 1, &histogramFence_, VK_TRUE, UINT64_MAX);
        histogramPending_ = false;
    }

    struct ShadowResources {
        PreviewTexture source;
        std::array<PreviewTexture, 3> mask;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 4> descriptors{};
        uint32_t fullWidth = 0;
        uint32_t fullHeight = 0;
    };

    struct PreviewRecoveryResources {
        PreviewTexture source;
        PreviewTexture temporary;
        PreviewTexture mask;
        VkBuffer reduction = VK_NULL_HANDLE;
        VkDeviceMemory reductionMemory = VK_NULL_HANDLE;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 2> descriptors{};
    };

    struct RawBufferResources {
        VkBuffer output = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize capacity = 0;
        VkBuffer uniform = VK_NULL_HANDLE;
        VkDeviceMemory uniformMemory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        VkDescriptorSet descriptor = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        uint32_t width = 0;
        uint32_t height = 0;
        bool enabled = false;
        bool previewSized = false;
    };

    struct FocusLoupeFrame {
        VkBuffer input = VK_NULL_HANDLE;
        VkDeviceMemory inputMemory = VK_NULL_HANDLE;
        void* inputMapped = nullptr;
        VkDeviceSize inputCapacity = 0;
        VkBuffer score = VK_NULL_HANDLE;
        VkDeviceMemory scoreMemory = VK_NULL_HANDLE;
        void* scoreMapped = nullptr;
        PreviewTexture mosaic;
        PreviewTexture output;
        PreviewTexture detail;
        PreviewTexture display;
        PreviewTexture source;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        VkDescriptorSet compute = VK_NULL_HANDLE;
        VkDescriptorSet draw = VK_NULL_HANDLE;
        focus_loupe::Region region;
        focus_loupe::Request request;
        int block = 1;
        int orientation = 0;
        int mirror = 0;
        bool lightweight = false;
        bool active = false;
        bool scorePending = false;
    };

    struct Frame {
        RawBufferResources remosaic;
        RawBufferResources hotPixels;
        PreviewRecoveryResources recovery;
        bool previewHighlightRecovery = false;
        ShadowResources shadows;
        float shadowStrength = 0.0f;
        RawPreviewMetrics metrics;
        PresentSamples samples;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkCommandBuffer rawCommandBuffer = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore imageAvailable = VK_NULL_HANDLE;
        bool acquirePending = false;
        VkBuffer rawUniform = VK_NULL_HANDLE;
        VkDeviceMemory rawUniformMemory = VK_NULL_HANDLE;
        void* rawMapped = nullptr;
        VkBuffer spektraUniform = VK_NULL_HANDLE;
        VkDeviceMemory spektraUniformMemory = VK_NULL_HANDLE;
        void* spektraMapped = nullptr;
        VkBuffer geometryUniform = VK_NULL_HANDLE;
        VkDeviceMemory geometryUniformMemory = VK_NULL_HANDLE;
        void* geometryMapped = nullptr;
        VkBuffer lensShadingBuffer = VK_NULL_HANDLE;
        VkDeviceMemory lensShadingMemory = VK_NULL_HANDLE;
        void* lensShadingMapped = nullptr;
        VkDeviceSize lensShadingCapacity = 0;
        bool lensShadingInitialized = false;
        bool lensTextureSourceDirty = true;
        PreviewTexture lensTexture;
        bool textureShading = false;
        PreviewTexture unpackedTexture;
        bool unpackRaw = false;
        bool bayerCells = false;
        bool recordingDownsample = false;
        bool recordingNative = false;
        bool recordingTile = false;
        bool cellLinearSupported = false;
        std::unique_ptr<CameraBayerBridge> cameraBridge;
        bool areaPrefilter = false;
        bool mhcPrefilter = false;
        bool nativeRawQuad = false;
        VkBuffer areaWeights = VK_NULL_HANDLE;
        VkDeviceMemory areaWeightsMemory = VK_NULL_HANDLE;
        VkDeviceSize areaWeightsCapacity = 0;
        std::array<float, 4> areaWeightsKey{};
        bool areaWeightsReady = false;
        VkPipeline unpackPipeline = VK_NULL_HANDLE;
        VkDescriptorSet computeDescriptor = VK_NULL_HANDLE;
        bool computeDescriptorsDirty = true;
        VkDescriptorSet presentDescriptor = VK_NULL_HANDLE;
        VkDescriptorSet videoDescriptor = VK_NULL_HANDLE;
        float focusStrength = 0.0f;
        float exposureZebra = 0.0f;
        VkBuffer exposureRange = VK_NULL_HANDLE;
        VkDeviceMemory exposureRangeMemory = VK_NULL_HANDLE;
        VkBuffer meterResult = VK_NULL_HANDLE;
        VkBuffer meterPixels = VK_NULL_HANDLE;
        VkDeviceMemory meterPixelsMemory = VK_NULL_HANDLE;
        VkDeviceMemory meterMemory = VK_NULL_HANDLE;
        void* meterMapped = nullptr;
        int64_t meterId = 0;
        VkDescriptorPool averagePool = VK_NULL_HANDLE;
        VkDescriptorSet averageDescriptor = VK_NULL_HANDLE;
        int averageCount = 1;
        int averageIndex = 0;
        int averageEncodeSdr = 0;
        PreviewTexture videoChromaOutput;
        PreviewTexture videoChromaTemporary;
        VkDescriptorPool videoChromaPool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 3> videoChromaDescriptors{};
        float videoChromaStrength = 0.0f;
        int videoChromaEncodeSdr = 0;
        PreviewTexture focusIntermediate;
        PreviewTexture focusOutput;
        VkDescriptorPool focusPool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 2> focusDescriptors{};
        FocusLoupeFrame loupe;
        VkImage processingImage = VK_NULL_HANDLE;
        VkDeviceMemory processingMemory = VK_NULL_HANDLE;
        VkImageView processingView = VK_NULL_HANDLE;
        VkFramebuffer processingFramebuffer = VK_NULL_HANDLE;
        bool processingInitialized = false;
        VkImage spektraImage = VK_NULL_HANDLE;
        VkDeviceMemory spektraMemory = VK_NULL_HANDLE;
        VkImageView spektraView = VK_NULL_HANDLE;
        bool spektraLayoutInitialized = false;
        bool spektraInitialized = false;
        bool useSpektraOutput = false;
        bool useSharedRawTile = false;
        bool reusePreviewShading = false;
        bool rasterRaw = false;
        VkPipeline previewPipeline = VK_NULL_HANDLE;
        RawImportKind rawImportKind = RawImportKind::None;
        VkBuffer packedRawBuffer = VK_NULL_HANDLE;
        std::unique_ptr<RawUploadPool::GpuUse> rawUpload;
        uint64_t rawUploadGeneration = 0;
        VkDeviceMemory packedRawMemory = VK_NULL_HANDLE;
        void* packedRawMapped = nullptr;
        bool packedRawCoherent = false;
        VkDeviceSize packedRawCapacity = 0;
        VkImage importedImage = VK_NULL_HANDLE;
        VkImageView importedImageView = VK_NULL_HANDLE;
        VkDeviceMemory importedMemory = VK_NULL_HANDLE;
        AHardwareBuffer* retainedHardwareBuffer = nullptr;
        bool ispActive = false;
        bool ispImageInput = false;
        VkBuffer ispUniform = VK_NULL_HANDLE;
        VkDeviceMemory ispUniformMemory = VK_NULL_HANDLE;
        void* ispMapped = nullptr;
        VkBuffer ispPlanes = VK_NULL_HANDLE;
        VkDeviceMemory ispPlanesMemory = VK_NULL_HANDLE;
        void* ispPlanesMapped = nullptr;
        VkDeviceSize ispPlanesCapacity = 0;
        bool ispPlanesCoherent = false;
        VkImage ispImage = VK_NULL_HANDLE;
        VkImageView ispImageView = VK_NULL_HANDLE;
        VkDeviceMemory ispImageMemory = VK_NULL_HANDLE;
        AHardwareBuffer* ispRetainedBuffer = nullptr;
        VkDescriptorSet ispBufferDescriptor = VK_NULL_HANDLE;
        VkDescriptorSet ispImageDescriptor = VK_NULL_HANDLE;
    };

    enum AmazeStage : size_t {
        AmazeNormalize = 0,
        AmazeCandidates = 1,
        AmazeDifferences = 2,
        AmazeWeights = 3,
        AmazeGreen = 4,
        AmazeRefine = 5,
        AmazeDiagonal = 6,
        AmazeChroma = 7,
        AmazeOutput = 8,
        AmazeHrSeed = 9,
        AmazeHrReduce = 10,
        AmazeRecovery = 11,
        AmazeStageCount = 12,
    };

    enum AmazeInput : size_t {
        AmazeBufferInput = 0,
        AmazeImageInput = 1,
        AmazeInputCount = 2,
    };

    void setDiagnostic(const std::string& value)
    {
        std::lock_guard<std::mutex> lock(diagnosticMutex_);
        if (diagnostic_ != value) {
            recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanRenderer", "renderer=%p %s",
                                static_cast<void*>(this), value.c_str());
        }
        diagnostic_ = value;
    }

    float lensSwitchBlurStrength() const
    {
        const auto now = std::chrono::steady_clock::now();
        if (lensSwitchRevealPending_)
            return 1.0f;
        if (!lensSwitchRevealActive_)
            return 0.0f;

        const float elapsed = std::chrono::duration<float>(
            now - lensSwitchRevealStarted_).count();
        const float progress = std::clamp(
            elapsed / kLensSwitchRevealSeconds, 0.0f, 1.0f);
        if (progress >= 1.0f)
            return 0.0f;
        const float eased = progress * progress * (3.0f - 2.0f * progress);
        return 1.0f - eased;
    }

    void finishLensSwitchPresentation()
    {
        const auto now = std::chrono::steady_clock::now();
        if (lensSwitchRevealPending_) {
            lensSwitchRevealPending_ = false;
            lensSwitchRevealActive_ = true;
            lensSwitchRevealStarted_ = now;
            return;
        }
        if (lensSwitchRevealActive_ &&
            std::chrono::duration<float>(
                now - lensSwitchRevealStarted_).count() >=
                kLensSwitchRevealSeconds) {
            lensSwitchRevealActive_ = false;
        }
    }

    void restoreSignaledFence(Frame& frame)
    {
        if (frame.fence != VK_NULL_HANDLE)
            vkDestroyFence(device_, frame.fence, nullptr);
        frame.fence = VK_NULL_HANDLE;
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        require(vkCreateFence(device_, &fenceInfo, nullptr, &frame.fence),
                "vkCreateFence after failed submit");
    }

    static uint32_t checkedMapDimension(float value, const char* name)
    {
        if (!std::isfinite(value) || value < 0.0f || value > 65535.0f ||
            std::abs(value - std::round(value)) > 0.001f) {
            throw std::runtime_error(std::string(name) +
                                     " must be a nonnegative integer");
        }
        return static_cast<uint32_t>(std::round(value));
    }

    VkDeviceSize calibrationTableBytes(
        const std::array<std::byte, kRawColorParametersSize>& color,
        uint64_t shadingTexels, const void* address, jlong capacity) const
    {
        auto integer = [](float value, uint32_t maximum) -> uint32_t {
            if (!std::isfinite(value) || value < 0.0f || value > maximum || value != std::floor(value))
                throw std::runtime_error("Invalid DCP calibration table index");
            return static_cast<uint32_t>(value);
        };
        float offsetValue, weight;
        std::memcpy(&offsetValue, color.data() + 96, sizeof(float));
        std::memcpy(&weight, color.data() + 100, sizeof(float));
        const uint64_t offset = integer(offsetValue, 1024u * 1024u);
        const uint64_t prefix = std::max<uint64_t>(1, shadingTexels);
        if (offset == 0) return prefix * 16;
        if (offset != prefix || address == nullptr || capacity < static_cast<jlong>((offset + 2) * 16) ||
            !std::isfinite(weight) || weight < 0.0f || weight > 1.0f)
            throw std::runtime_error("DCP calibration tables are missing or invalid");
        const float* data = static_cast<const float*>(address) + offset * 4;
        const uint64_t hue = integer(data[0], 131072);
        const uint64_t saturation = integer(data[1], 131072);
        const uint64_t value = integer(data[2], 131072);
        integer(data[3], 1);
        const uint64_t entries = hue * saturation * value;
        if (hue < 1 || saturation < 2 || value < 1 || entries > 131072)
            throw std::runtime_error("Invalid DCP calibration dimensions");
        const uint64_t first = integer(data[4], 262146);
        const uint64_t second = integer(data[5], 262146);
        const uint64_t count = integer(data[6], 262146);
        const uint64_t bytes = (offset + count) * 16;
        if (first != 2 || (second != 2 && second != 2 + entries) || count != second + entries ||
            bytes > physicalProperties_.limits.maxStorageBufferRange || capacity < static_cast<jlong>(bytes))
            throw std::runtime_error("DCP calibration tables are truncated or too large");
        for (uint64_t i = 2; i < count; ++i) {
            if (!std::isfinite(data[i * 4]) || std::abs(data[i * 4]) > 360.0f ||
                !std::isfinite(data[i * 4 + 1]) || data[i * 4 + 1] < 0.0f ||
                !std::isfinite(data[i * 4 + 2]) || data[i * 4 + 2] < 0.0f)
                throw std::runtime_error("DCP calibration contains invalid adjustments");
        }
        return bytes;
    }

    bool collectExposureMeter(ExposureMeterExchange& meter)
    {
        bool found = false;
        for (Frame& completed : frames_) {
            if (completed.meterId == 0 || completed.fence == VK_NULL_HANDLE ||
                vkGetFenceStatus(device_, completed.fence) != VK_SUCCESS) continue;
            if (completed.meterId > meter.resultId) {
                meter.resultId = completed.meterId;
                float ev = 0.0f;
                std::memcpy(&ev, completed.meterMapped, sizeof(ev));
                meter.correctionEv = ev;
                found = true;
            }
            completed.meterId = 0;
        }
        return found;
    }

    void createInstance()
    {
        uint32_t version = VK_API_VERSION_1_0;
        auto enumerateVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
        if (enumerateVersion == nullptr ||
            enumerateVersion(&version) != VK_SUCCESS ||
            version < VK_API_VERSION_1_1) {
            throw std::runtime_error("Vulkan 1.1 loader is required");
        }

        uint32_t extensionCount = 0;
        require(vkEnumerateInstanceExtensionProperties(
                    nullptr, &extensionCount, nullptr),
                "vkEnumerateInstanceExtensionProperties");
        std::vector<VkExtensionProperties> extensions(extensionCount);
        require(vkEnumerateInstanceExtensionProperties(
                    nullptr, &extensionCount, extensions.data()),
                "vkEnumerateInstanceExtensionProperties");
        if (!containsExtension(extensions, VK_KHR_SURFACE_EXTENSION_NAME) ||
            !containsExtension(extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
            throw std::runtime_error("Android Vulkan surface extensions are unavailable");
        }

        VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        application.pApplicationName = "Unspektrawesome";
        application.applicationVersion = 1;
        application.pEngineName = "Unspektrawesome";
        application.engineVersion = 1;
        application.apiVersion = VK_API_VERSION_1_1;
        std::vector<const char*> names{
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        info.pApplicationInfo = &application;
        validation_.configure(info, names);
        info.enabledExtensionCount = static_cast<uint32_t>(names.size());
        info.ppEnabledExtensionNames = names.data();
        require(vkCreateInstance(&info, nullptr, &instance_), "vkCreateInstance");
        validation_.attach(instance_);
    }

    void selectDeviceAndQueue()
    {
        uint32_t deviceCount = 0;
        require(vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr),
                "vkEnumeratePhysicalDevices");
        std::vector<VkPhysicalDevice> devices(deviceCount);
        require(vkEnumeratePhysicalDevices(
                    instance_, &deviceCount, devices.data()),
                "vkEnumeratePhysicalDevices");

        for (VkPhysicalDevice candidate : devices) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(candidate, &properties);
            if (properties.apiVersion < VK_API_VERSION_1_1)
                continue;

            uint32_t extensionCount = 0;
            if (vkEnumerateDeviceExtensionProperties(
                    candidate, nullptr, &extensionCount, nullptr) != VK_SUCCESS)
                continue;
            std::vector<VkExtensionProperties> extensions(extensionCount);
            if (vkEnumerateDeviceExtensionProperties(
                    candidate, nullptr, &extensionCount,
                    extensions.data()) != VK_SUCCESS)
                continue;
            if (!containsExtension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ||
                !containsExtension(
                    extensions,
                    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
                !containsExtension(
                    extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) ||
                !containsExtension(
                    extensions, VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME))
                continue;

            VkPhysicalDeviceShaderFloat16Int8Features float16Features{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
            VkPhysicalDeviceFeatures2 features{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            features.pNext = &float16Features;
            vkGetPhysicalDeviceFeatures2(candidate, &features);
            if (float16Features.shaderFloat16 != VK_TRUE)
                continue;

            uint32_t familyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(
                candidate, &familyCount, nullptr);
            std::vector<VkQueueFamilyProperties> families(familyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(
                candidate, &familyCount, families.data());
            for (uint32_t index = 0; index < familyCount; ++index) {
                VkBool32 presentSupported = surface_ == VK_NULL_HANDLE ? VK_TRUE : VK_FALSE;
                if (surface_ != VK_NULL_HANDLE && vkGetPhysicalDeviceSurfaceSupportKHR(
                        candidate, index, surface_, &presentSupported) != VK_SUCCESS)
                    continue;
                VkQueueFlags required =
                    VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                if ((families[index].queueFlags & required) == required &&
                    presentSupported == VK_TRUE) {
                    physicalDevice_ = candidate;
                    physicalProperties_ = properties;
                    char soc[PROP_VALUE_MAX]{}, platform[PROP_VALUE_MAX]{}, hardware[PROP_VALUE_MAX]{};
                    __system_property_get("ro.soc.model", soc);
                    __system_property_get("ro.board.platform", platform);
                    __system_property_get("ro.hardware", hardware);
                    lightweightPreview_ = raw_preview::useLightweightPreview(
                        properties.vendorID, properties.deviceName, soc, platform, hardware);
                    if (!raw_preview::supportsOptimizedPreview(properties.vendorID,
                            properties.deviceID, properties.deviceName))
                        recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanRenderer",
                            "Using scalar RAW preview for driver compatibility; quality=%s",
                            lightweightPreview_ ? "lightweight" : "filtered");
                    recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanRenderer",
                        "GPU=%s vendor=%u device=%u driver=%u api=%u queue=%u maxImage2D=%u vertexStores=%u fragmentStores=%u",
                        properties.deviceName, properties.vendorID, properties.deviceID,
                        properties.driverVersion, properties.apiVersion, index, properties.limits.maxImageDimension2D,
                        features.features.vertexPipelineStoresAndAtomics, features.features.fragmentStoresAndAtomics);
                    vkGetPhysicalDeviceMemoryProperties(candidate, &memoryProperties_);
                    queueFamily_ = index;
                    displayTimingSupported_ = containsExtension(
                        extensions, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
#if !defined(NDEBUG)
                    pipelineCreationStats_.feedbackEnabled = containsExtension(
                        extensions, VK_EXT_PIPELINE_CREATION_FEEDBACK_EXTENSION_NAME);
#endif
                    return;
                }
            }
        }
        throw std::runtime_error(
            "No Vulkan 1.1 device supports compute, shaderFloat16 AMAZE, presentation, swapchain, Android HardwareBuffer import, and foreign queue ownership");
    }

    void createDeviceResources()
    {
        VkFormatProperties lensFormat{};
        vkGetPhysicalDeviceFormatProperties(physicalDevice_, VK_FORMAT_R16G16B16A16_SFLOAT, &lensFormat);
        const VkFormatFeatureFlags lensFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        lensTextureSupported_ = (lensFormat.optimalTilingFeatures & lensFeatures) == lensFeatures;
        VkPhysicalDeviceFeatures availableFeatures{};
        vkGetPhysicalDeviceFeatures(physicalDevice_, &availableFeatures);
        VkFormatProperties unpackFormat{};
        vkGetPhysicalDeviceFormatProperties(physicalDevice_, VK_FORMAT_R16_UNORM, &unpackFormat);
        const VkFormatFeatureFlags unpackFeatures = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        unpackSupported_ = availableFeatures.shaderStorageImageExtendedFormats &&
            (unpackFormat.optimalTilingFeatures & unpackFeatures) == unpackFeatures;
        VkPhysicalDeviceFeatures enabledFeatures{};
        // Avoid print shader faults on affected Arm drivers.
        enabledFeatures.robustBufferAccess = physicalProperties_.vendorID == 0x13b5u &&
            availableFeatures.robustBufferAccess;
        enabledFeatures.shaderStorageImageExtendedFormats = availableFeatures.shaderStorageImageExtendedFormats;
        float priority = camera_gpu::exporting ? 0.0f : 1.0f;
        VkDeviceQueueCreateInfo queueInfo{
            VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = queueFamily_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        std::vector<const char*> extensions{
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME};
        if (displayTimingSupported_)
            extensions.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
        if (pipelineCreationStats_.feedbackEnabled)
            extensions.push_back(VK_EXT_PIPELINE_CREATION_FEEDBACK_EXTENSION_NAME);
        VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrAvailable{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES};
        VkPhysicalDeviceFeatures2 features2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        features2.pNext = &ycbcrAvailable;
        vkGetPhysicalDeviceFeatures2(physicalDevice_, &features2);
        ispImageSupported_ = ycbcrAvailable.samplerYcbcrConversion == VK_TRUE;
        VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES};
        ycbcrFeatures.samplerYcbcrConversion = VK_TRUE;
        VkPhysicalDeviceShaderFloat16Int8Features float16Features{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
        float16Features.shaderFloat16 = VK_TRUE;
        if (ispImageSupported_) float16Features.pNext = &ycbcrFeatures;
        VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        info.pNext = &float16Features;
        info.pEnabledFeatures = &enabledFeatures;
        info.queueCreateInfoCount = 1;
        info.pQueueCreateInfos = &queueInfo;
        info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
        info.ppEnabledExtensionNames = extensions.data();
        require(vkCreateDevice(physicalDevice_, &info, nullptr, &device_),
                "vkCreateDevice");
        rawUploads_->attach(physicalDevice_, device_);
        recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanRenderer",
            "Device created: vendor=%u robustBufferAccess=%u", physicalProperties_.vendorID,
            enabledFeatures.robustBufferAccess);
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        getRefreshCycleDuration_ = displayTimingSupported_
            ? reinterpret_cast<PFN_vkGetRefreshCycleDurationGOOGLE>(
                vkGetDeviceProcAddr(device_, "vkGetRefreshCycleDurationGOOGLE")) : nullptr;
        getPastPresentationTiming_ = displayTimingSupported_
            ? reinterpret_cast<PFN_vkGetPastPresentationTimingGOOGLE>(
                vkGetDeviceProcAddr(device_, "vkGetPastPresentationTimingGOOGLE")) : nullptr;
        pipelineCache_.initialize(device_, physicalDevice_, cachePath_);
        getAhbProperties_ =
            reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                vkGetDeviceProcAddr(
                    device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (getAhbProperties_ == nullptr)
            throw std::runtime_error(
                "Android HardwareBuffer import function is unavailable");

        VkCommandPoolCreateInfo poolInfo{
            VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamily_;
        require(vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_),
                "vkCreateCommandPool");
        createDescriptorResources();
        createComputePipeline();
        createFrames();
        ensurePreviewTexture(previewFallbackTexture_, 1, 1, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 4);
        std::memset(previewFallbackTexture_.mapped, 255, 4);
        previewFallbackTexture_.uploadPending = true;
        spektrafilm::ExternalVulkanContext spektraContext{};
        spektraContext.instance = instance_;
        spektraContext.physicalDevice = physicalDevice_;
        spektraContext.device = device_;
        spektraContext.computeQueue = queue_;
        spektraContext.computeQueueFamily = queueFamily_;
        spektraContext.queueMutex = &queueMutex_;
        spektraContext.pipelineCache = pipelineCache_.handle;
        spektraContext.pipelineCacheRevision = &pipelineCache_.revision;
        spektraContext.pipelineCreationStats = &pipelineCreationStats_;
        spektraRenderer_ =
            std::make_unique<spektrafilm::VulkanRenderer>(spektraContext);
        if (!spektraRenderer_->isAvailable()) {
            throw std::runtime_error(
                "Spektra shared Vulkan renderer is unavailable: " +
                spektraRenderer_->lastError());
        }
    }

    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) const
    {
        const auto& properties = memoryProperties_;
        for (uint32_t index = 0; index < properties.memoryTypeCount; ++index) {
            if ((bits & (1u << index)) != 0 &&
                (properties.memoryTypes[index].propertyFlags & flags) == flags)
                return index;
        }
        throw std::runtime_error("No compatible Vulkan memory type");
    }

    VkMemoryPropertyFlags makeBuffer(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags memoryFlags,
        VkBuffer& buffer,
        VkDeviceMemory& memory,
        void** mapped)
    {
        VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        require(vkCreateBuffer(device_, &bufferInfo, nullptr, &buffer),
                "vkCreateBuffer");
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device_, buffer, &requirements);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType(
            requirements.memoryTypeBits, memoryFlags);
        require(vkAllocateMemory(device_, &allocation, nullptr, &memory),
                "vkAllocateMemory");
        require(vkBindBufferMemory(device_, buffer, memory, 0),
                "vkBindBufferMemory");
        if (mapped != nullptr) {
            require(vkMapMemory(device_, memory, 0, size, 0, mapped),
                    "vkMapMemory");
        }
        return memoryProperties_.memoryTypes[allocation.memoryTypeIndex].propertyFlags;
    }

    void destroyPreviewTexture(PreviewTexture& texture)
    {
        if (texture.mapped) vkUnmapMemory(device_, texture.stagingMemory);
        if (texture.staging) vkDestroyBuffer(device_, texture.staging, nullptr);
        if (texture.stagingMemory) vkFreeMemory(device_, texture.stagingMemory, nullptr);
        if (texture.view) vkDestroyImageView(device_, texture.view, nullptr);
        if (texture.image) vkDestroyImage(device_, texture.image, nullptr);
        if (texture.memory) vkFreeMemory(device_, texture.memory, nullptr);
        texture = {};
    }

    bool ensurePreviewTexture(PreviewTexture& texture, uint32_t width, uint32_t height,
                              VkFormat format, VkImageUsageFlags usage, uint32_t stagingPixelBytes = 0)
    {
        if (texture.image && texture.width == width && texture.height == height &&
            texture.format == format && texture.usage == usage && texture.stagingPixelBytes == stagingPixelBytes) return false;
        destroyPreviewTexture(texture);
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = usage;
        require(vkCreateImage(device_, &imageInfo, nullptr, &texture.image), "vkCreateImage for preview texture");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device_, texture.image, &requirements);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(device_, &allocation, nullptr, &texture.memory), "vkAllocateMemory for preview texture");
        require(vkBindImageMemory(device_, texture.image, texture.memory, 0), "vkBindImageMemory for preview texture");
        texture.view = createImageView(texture.image, format);
        if (stagingPixelBytes) {
            makeBuffer(static_cast<VkDeviceSize>(width) * height * stagingPixelBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                texture.staging, texture.stagingMemory, &texture.mapped);
        }
        texture.width = width;
        texture.height = height;
        texture.format = format;
        texture.usage = usage;
        texture.stagingPixelBytes = stagingPixelBytes;
        return true;
    }

    void prepareLensTexture(Frame& frame, uint32_t width, uint32_t height)
    {
        auto& texture = frame.lensTexture;
        if (ensurePreviewTexture(texture, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 8)) {
            frame.computeDescriptorsDirty = true;
            frame.lensTextureSourceDirty = true;
        }
        if (!frame.lensTextureSourceDirty) return;
        const auto* source = static_cast<const float*>(frame.lensShadingMapped);
        auto* target = static_cast<__fp16*>(texture.mapped);
        for (size_t index = 0; index < static_cast<size_t>(width) * height * 4; ++index) {
            const float gain = source[index];
            if (!std::isfinite(gain) || gain < 0.0f || gain > 65504.0f)
                throw std::runtime_error("Lens shading gains exceed the half-float texture range");
            const __fp16 packed = static_cast<__fp16>(gain);
            if (!texture.initialized || target[index] != packed) {
                target[index] = packed;
                texture.uploadPending = true;
            }
        }
        frame.lensTextureSourceDirty = false;
    }

    void updateLensShadingBuffer(Frame& frame, const void* data, VkDeviceSize size)
    {
        ensureLensShadingBuffer(frame, size);
        const std::array<float, 4> identity{1.0f, 1.0f, 1.0f, 1.0f};
        const void* source = data ? data : identity.data();
        bool changed = !frame.lensShadingInitialized ||
            std::memcmp(frame.lensShadingMapped, source, static_cast<size_t>(size)) != 0;
#if !defined(NDEBUG)
        char setupProperty[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.reuse_setup", setupProperty);
        changed |= setupProperty[0] == '0';
#endif
        if (changed) {
            std::memcpy(frame.lensShadingMapped, source, static_cast<size_t>(size));
            frame.lensShadingInitialized = true;
            frame.lensTextureSourceDirty = true;
        }
    }

    void recordPreviewTextureUpload(VkCommandBuffer command, PreviewTexture& texture)
    {
        if (!texture.uploadPending) return;
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = texture.initialized ? VK_ACCESS_SHADER_READ_BIT : 0;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.oldLayout = texture.initialized ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = texture.image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, texture.initialized
            ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
        VkBufferImageCopy copy{};
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {texture.width, texture.height, 1};
        vkCmdCopyBufferToImage(command, texture.staging, texture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    void ensureLensShadingBuffer(Frame& frame, VkDeviceSize size)
    {
        if (frame.lensShadingBuffer != VK_NULL_HANDLE &&
            frame.lensShadingCapacity == size)
            return;
        frame.computeDescriptorsDirty = true;
        if (frame.lensShadingMapped != nullptr)
            vkUnmapMemory(device_, frame.lensShadingMemory);
        if (frame.lensShadingBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, frame.lensShadingBuffer, nullptr);
        if (frame.lensShadingMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, frame.lensShadingMemory, nullptr);
        frame.lensShadingBuffer = VK_NULL_HANDLE;
        frame.lensShadingMemory = VK_NULL_HANDLE;
        frame.lensShadingMapped = nullptr;
        frame.lensShadingCapacity = 0;
        frame.lensShadingInitialized = false;
        frame.lensTextureSourceDirty = true;
        makeBuffer(size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                   frame.lensShadingBuffer, frame.lensShadingMemory,
                   &frame.lensShadingMapped);
        frame.lensShadingCapacity = size;
    }

    void ensurePackedRawBuffer(Frame& frame, VkDeviceSize size)
    {
        if (frame.packedRawBuffer != VK_NULL_HANDLE &&
            frame.packedRawCapacity >= size)
            return;
        frame.computeDescriptorsDirty = true;
        if (frame.packedRawMapped != nullptr)
            vkUnmapMemory(device_, frame.packedRawMemory);
        if (frame.packedRawBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, frame.packedRawBuffer, nullptr);
        if (frame.packedRawMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, frame.packedRawMemory, nullptr);
        frame.packedRawBuffer = VK_NULL_HANDLE;
        frame.packedRawMemory = VK_NULL_HANDLE;
        frame.packedRawMapped = nullptr;
        frame.packedRawCapacity = 0;
        frame.packedRawCoherent = (makeBuffer(
            size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            frame.packedRawBuffer, frame.packedRawMemory,
            &frame.packedRawMapped) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        frame.packedRawCapacity = size;
    }

    void destroyPackedRawBuffer(Frame& frame)
    {
        if (frame.packedRawMapped != nullptr)
            vkUnmapMemory(device_, frame.packedRawMemory);
        if (frame.packedRawBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, frame.packedRawBuffer, nullptr);
        if (frame.packedRawMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, frame.packedRawMemory, nullptr);
        frame.packedRawBuffer = VK_NULL_HANDLE;
        frame.packedRawMemory = VK_NULL_HANDLE;
        frame.packedRawMapped = nullptr;
        frame.packedRawCapacity = 0;
    }

    void createDescriptorResources()
    {
        std::array<VkDescriptorSetLayoutBinding, 14> computeBindings{};
        computeBindings[0] = {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[1] = {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[2] = {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[3] = {3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[4] = {4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[5] = {5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[6] = {6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[7] = {7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[8] = {8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                              VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
        computeBindings[9] = {9, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[10] = {10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                               VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
        computeBindings[11] = {11, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[12] = {12, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        computeBindings[13] = {13, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        for (uint32_t binding : {0u, 1u, 3u, 4u, 6u})
            computeBindings[binding].stageFlags |= VK_SHADER_STAGE_FRAGMENT_BIT;
        VkDescriptorSetLayoutCreateInfo computeInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        computeInfo.bindingCount = static_cast<uint32_t>(computeBindings.size());
        computeInfo.pBindings = computeBindings.data();
        require(vkCreateDescriptorSetLayout(
                    device_, &computeInfo, nullptr, &computeDescriptorLayout_),
                "vkCreateDescriptorSetLayout");

        std::array<VkDescriptorSetLayoutBinding, 3> presentBindings{{
            {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
            {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
            {2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}}};
        VkDescriptorSetLayoutCreateInfo presentInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        presentInfo.bindingCount = static_cast<uint32_t>(presentBindings.size());
        presentInfo.pBindings = presentBindings.data();
        require(vkCreateDescriptorSetLayout(
                    device_, &presentInfo, nullptr, &presentDescriptorLayout_),
                "vkCreateDescriptorSetLayout");

        std::array<VkDescriptorPoolSize, 4> sizes{{
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, (kFramesInFlight + 1) * 4 + kFramesInFlight * 2},
            {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, (kFramesInFlight + 1) * 3},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, (kFramesInFlight + 1) * 2},
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
             (kFramesInFlight + 1) * 5 + kFramesInFlight * 4}}};
        VkDescriptorPoolCreateInfo poolInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        poolInfo.maxSets = kFramesInFlight * 3 + 1;
        poolInfo.poolSizeCount = static_cast<uint32_t>(sizes.size());
        poolInfo.pPoolSizes = sizes.data();
        require(vkCreateDescriptorPool(
                    device_, &poolInfo, nullptr, &descriptorPool_),
                "vkCreateDescriptorPool");

        VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxLod = 0.0f;
        require(vkCreateSampler(device_, &samplerInfo, nullptr, &sampler_),
                "vkCreateSampler");

        samplerInfo.magFilter = VK_FILTER_NEAREST;
        samplerInfo.minFilter = VK_FILTER_NEAREST;
        require(vkCreateSampler(
                    device_, &samplerInfo, nullptr, &rawIntegerSampler_),
                "vkCreateSampler for integer RAW image");

        std::array<VkDescriptorSetLayoutBinding, 14> amazeBindings{{
            {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {9, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {10, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {11, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {12, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {13, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        }};
        VkDescriptorSetLayoutCreateInfo amazeLayoutInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        amazeLayoutInfo.bindingCount =
            static_cast<uint32_t>(amazeBindings.size());
        amazeLayoutInfo.pBindings = amazeBindings.data();
        require(vkCreateDescriptorSetLayout(
                    device_, &amazeLayoutInfo, nullptr,
                    &amazeDescriptorLayout_),
                "vkCreateDescriptorSetLayout for staged AMAZE");

        std::array<VkDescriptorPoolSize, 4> amazePoolSizes{{
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, AmazeStageCount * 3},
            {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, AmazeStageCount * 3},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, AmazeStageCount * 2},
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
             AmazeStageCount * 6},
        }};
        VkDescriptorPoolCreateInfo amazePoolInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        amazePoolInfo.maxSets = AmazeStageCount;
        amazePoolInfo.poolSizeCount =
            static_cast<uint32_t>(amazePoolSizes.size());
        amazePoolInfo.pPoolSizes = amazePoolSizes.data();
        require(vkCreateDescriptorPool(
                    device_, &amazePoolInfo, nullptr, &amazeDescriptorPool_),
                "vkCreateDescriptorPool for staged AMAZE");

        std::array<VkDescriptorSetLayout, AmazeStageCount> amazeLayouts{};
        amazeLayouts.fill(amazeDescriptorLayout_);
        VkDescriptorSetAllocateInfo amazeAllocateInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        amazeAllocateInfo.descriptorPool = amazeDescriptorPool_;
        amazeAllocateInfo.descriptorSetCount = AmazeStageCount;
        amazeAllocateInfo.pSetLayouts = amazeLayouts.data();
        require(vkAllocateDescriptorSets(
                    device_, &amazeAllocateInfo, amazeDescriptorSets_.data()),
                "vkAllocateDescriptorSets for staged AMAZE");

        VkPipelineLayoutCreateInfo amazePipelineLayoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        amazePipelineLayoutInfo.setLayoutCount = 1;
        amazePipelineLayoutInfo.pSetLayouts = &amazeDescriptorLayout_;
        VkPushConstantRange amazePush{VK_SHADER_STAGE_COMPUTE_BIT, 0, 4 * sizeof(int32_t)};
        amazePipelineLayoutInfo.pushConstantRangeCount = 1;
        amazePipelineLayoutInfo.pPushConstantRanges = &amazePush;
        require(vkCreatePipelineLayout(
                    device_, &amazePipelineLayoutInfo, nullptr,
                    &amazePipelineLayout_),
                "vkCreatePipelineLayout for staged AMAZE");
    }

    void createComputePipeline()
    {
        VkPipelineLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &computeDescriptorLayout_;
        VkPushConstantRange captureTransform{
            VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(int32_t) * 6};
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &captureTransform;
        require(vkCreatePipelineLayout(
                    device_, &layoutInfo, nullptr, &computePipelineLayout_),
                "vkCreatePipelineLayout");
        createRawRenderPass();
    }

    void createRawRenderPass()
    {
        VkImageFormatProperties properties{};
        if (vkGetPhysicalDeviceImageFormatProperties(physicalDevice_, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                0, &properties) != VK_SUCCESS) return;
        VkAttachmentDescription attachment{};
        attachment.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
        attachment.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &reference;
        std::array<VkSubpassDependency, 2> dependencies{};
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0;
        dependencies[0].srcStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        info.attachmentCount = 1;
        info.pAttachments = &attachment;
        info.subpassCount = 1;
        info.pSubpasses = &subpass;
        info.dependencyCount = static_cast<uint32_t>(dependencies.size());
        info.pDependencies = dependencies.data();
        if (vkCreateRenderPass(device_, &info, nullptr, &rawRenderPass_) != VK_SUCCESS)
            rawRenderPass_ = VK_NULL_HANDLE;
    }

    VkPipeline createComputeProgram(
        const uint32_t* code, size_t wordCount,
        const VkSpecializationInfo* specialization,
        VkPipelineLayout layout = VK_NULL_HANDLE)
    {
        VkShaderModule shader = makeShader(device_, code, wordCount);
        VkPipelineShaderStageCreateInfo stage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shader;
        stage.pName = "main";
        stage.pSpecializationInfo = specialization;
        VkComputePipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipelineInfo.flags = VK_PIPELINE_CREATE_DISPATCH_BASE_BIT;
        pipelineInfo.stage = stage;
        pipelineInfo.layout = layout != VK_NULL_HANDLE ? layout : computePipelineLayout_;
        PipelineCreationFeedback feedback(&pipelineCreationStats_);
        pipelineInfo.pNext = feedback.info();
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = vkCreateComputePipelines(
            device_, pipelineCache_.handle, 1, &pipelineInfo, nullptr, &pipeline);
        vkDestroyShaderModule(device_, shader, nullptr);
        require(result, "vkCreateComputePipelines");
        feedback.record();
        pipelineCache_.markDirty();
        return pipeline;
    }

    VkPipeline prepareMeterPipeline(size_t input)
    {
        auto& pipeline = meterPipelines_.at(input);
        if (pipeline) return pipeline;
        pipeline = input == 4
            ? createComputeProgram(kRecordingMeterSpirv.data(), kRecordingMeterSpirv.size(), nullptr)
            : input == 3
            ? createComputeProgram(kCellMeterSpirv.data(), kCellMeterSpirv.size(), nullptr)
            : input == 2
            ? createComputeProgram(kPreviewMeterRemosaicSpirv.data(), kPreviewMeterRemosaicSpirv.size(), nullptr)
            : input == 1
                ? createComputeProgram(kPreviewMeterImageSpirv.data(), kPreviewMeterImageSpirv.size(), nullptr)
                : createComputeProgram(kPreviewMeterSpirv.data(), kPreviewMeterSpirv.size(), nullptr);
        return pipeline;
    }

    static RawImportKind processedRawKind(const Frame& frame)
    {
        return frame.remosaic.enabled || frame.hotPixels.enabled ? RawImportKind::Buffer : frame.rawImportKind;
    }

    static VkBuffer processedRawBuffer(const Frame& frame)
    {
        return frame.remosaic.enabled ? frame.remosaic.output : frame.hotPixels.enabled ? frame.hotPixels.output : sourceRawBuffer(frame);
    }

    static VkBuffer sourceRawBuffer(const Frame& frame)
    {
        return frame.rawUpload ? frame.rawUpload->buffer() : frame.packedRawBuffer;
    }

    void destroyRawBufferResources(RawBufferResources& resources)
    {
        if (resources.pool) vkDestroyDescriptorPool(device_, resources.pool, nullptr);
        if (resources.mapped) vkUnmapMemory(device_, resources.uniformMemory);
        if (resources.uniform) vkDestroyBuffer(device_, resources.uniform, nullptr);
        if (resources.uniformMemory) vkFreeMemory(device_, resources.uniformMemory, nullptr);
        if (resources.output) vkDestroyBuffer(device_, resources.output, nullptr);
        if (resources.memory) vkFreeMemory(device_, resources.memory, nullptr);
        resources = {};
    }

    VkPipeline prepareRemosaicPipeline(int32_t factor, bool identityLevels, size_t input, bool previewSized)
    {
        if (!remosaicDescriptorLayout_) {
            const std::array<VkDescriptorSetLayoutBinding, 5> bindings{{
                {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            }};
            VkDescriptorSetLayoutCreateInfo layout{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            layout.bindingCount = static_cast<uint32_t>(bindings.size());
            layout.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &layout, nullptr, &remosaicDescriptorLayout_), "Remosaic descriptor layout");
            VkPipelineLayoutCreateInfo pipelineLayout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            pipelineLayout.setLayoutCount = 1;
            pipelineLayout.pSetLayouts = &remosaicDescriptorLayout_;
            require(vkCreatePipelineLayout(device_, &pipelineLayout, nullptr, &remosaicPipelineLayout_), "Remosaic pipeline layout");
        }
        const bool image = input == 2;
        const bool alignedRaw16 = input == 1;
        const int inputVariant = image ? 2 : alignedRaw16 ? 1 : 0;
        const int variant = ((factor - 2) * 2 + (identityLevels ? 1 : 0)) * 3 + inputVariant;
        auto& pipeline = remosaicPipelines_.at(variant * 2 + (previewSized ? 1 : 0));
        if (!pipeline) {
            const int32_t constants[] = {factor, identityLevels ? 1 : 0, alignedRaw16 ? 1 : 0, previewSized ? 1 : 0};
            const VkSpecializationMapEntry entries[] = {{0, 0, 4}, {1, 4, 4}, {3, 8, 4}, {4, 12, 4}};
            const VkSpecializationInfo specialization{4, entries, sizeof(constants), constants};
            pipeline = image
                ? createComputeProgram(kRemosaicImageSpirv.data(), kRemosaicImageSpirv.size(), &specialization, remosaicPipelineLayout_)
                : createComputeProgram(kRemosaicBufferSpirv.data(), kRemosaicBufferSpirv.size(), &specialization, remosaicPipelineLayout_);
        }
        return pipeline;
    }

    void prepareRemosaic(Frame& frame, RawParameters& raw, const GeometryParameters* preview = nullptr)
    {
        auto& resources = frame.remosaic;
        const bool enabled = raw.remosaic[0] > 1;
        frame.computeDescriptorsDirty |= resources.enabled != enabled;
        resources.enabled = enabled;
        resources.previewSized = enabled && preview != nullptr;
        if (!enabled) return;
        const bool previewSized = resources.previewSized;
        const bool image = !frame.hotPixels.enabled && frame.rawImportKind == RawImportKind::Image;
        const bool alignedRaw16 = !image && raw.layoutInfo[1] == 0 && (raw.layoutInfo[0] & 1) == 0;
        bool identityLevels = raw.blackLevel[1] == raw.blackLevel[2] && raw.whiteLevel[1] == raw.whiteLevel[2];
        for (size_t i = 0; i < 4; ++i) {
            identityLevels = identityLevels && raw.blackLevel[i] >= 0 && raw.whiteLevel[i] <= 65535 &&
                raw.whiteLevel[i] > raw.blackLevel[i] && raw.blackLevel[i] == std::floor(raw.blackLevel[i]) &&
                raw.whiteLevel[i] == std::floor(raw.whiteLevel[i]);
        }
        const auto pipeline = prepareRemosaicPipeline(raw.remosaic[0], identityLevels,
            image ? 2 : alignedRaw16 ? 1 : 0, previewSized);
        const bool swapsAxes = previewSized && (preview->transform[0] & 1) != 0;
        const uint32_t width = previewSized ? raw.dimensions[swapsAxes ? 3 : 2] : raw.dimensions[0];
        const uint32_t height = previewSized ? raw.dimensions[swapsAxes ? 2 : 3] : raw.dimensions[1];
        const VkDeviceSize bytes = ((static_cast<VkDeviceSize>(width) * height + 1) / 2) * 4;
        if (resources.capacity < bytes) {
            destroyRawBufferResources(resources);
            resources.enabled = true;
            resources.previewSized = previewSized;
            makeBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                resources.output, resources.memory, nullptr);
            resources.capacity = bytes;
            frame.computeDescriptorsDirty = true;
        }
        if (!resources.uniform) {
            makeBuffer(kRawParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                resources.uniform, resources.uniformMemory, &resources.mapped);
            const std::array<VkDescriptorPoolSize, 3> sizes{{
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2}, {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
            }};
            VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pool.maxSets = 1;
            pool.poolSizeCount = static_cast<uint32_t>(sizes.size());
            pool.pPoolSizes = sizes.data();
            require(vkCreateDescriptorPool(device_, &pool, nullptr, &resources.pool), "Remosaic descriptor pool");
            VkDescriptorSetAllocateInfo allocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocation.descriptorPool = resources.pool;
            allocation.descriptorSetCount = 1;
            allocation.pSetLayouts = &remosaicDescriptorLayout_;
            require(vkAllocateDescriptorSets(device_, &allocation, &resources.descriptor), "Remosaic descriptor set");
        }
        resources.pipeline = pipeline;
        resources.width = width;
        resources.height = height;
        auto source = raw;
        source.dimensions[2] = static_cast<int32_t>(width);
        source.dimensions[3] = static_cast<int32_t>(height);
        std::memcpy(resources.mapped, &source, sizeof(source));
        const std::array<VkDescriptorBufferInfo, 4> buffers{{
            {frame.hotPixels.enabled ? frame.hotPixels.output : sourceRawBuffer(frame), 0, VK_WHOLE_SIZE},
            {resources.uniform, 0, kRawParametersSize},
            {resources.output, 0, bytes},
            {frame.geometryUniform, 0, kGeometryParametersSize},
        }};
        VkDescriptorImageInfo sourceImage{rawIntegerSampler_, frame.importedImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        std::array<VkWriteDescriptorSet, 4> writes{};
        for (uint32_t i = 0; i < writes.size(); ++i) {
            auto& write = writes[i];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = resources.descriptor;
            write.dstBinding = i == 3 ? 6 : i;
            write.descriptorCount = 1;
            write.descriptorType = i == 1 || i == 3 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            write.pBufferInfo = &buffers[i];
        }
        if (image) {
            writes[0].dstBinding = 5;
            writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[0].pBufferInfo = nullptr;
            writes[0].pImageInfo = &sourceImage;
        }
        vkUpdateDescriptorSets(device_, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
        raw.layoutInfo[0] = static_cast<int32_t>(width) * 2;
        raw.layoutInfo[1] = 0;
        raw.remosaic[0] = 1;
    }

    void recordRemosaic(VkCommandBuffer commandBuffer, Frame& frame, camera_gpu::ComputeRecorder& recorder)
    {
        const auto& resources = frame.remosaic;
        if (!resources.enabled) return;
        VkBufferMemoryBarrier barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = resources.output;
        barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &barrier, 0, nullptr);
        recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, resources.pipeline);
        recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, remosaicPipelineLayout_,
            0, 1, &resources.descriptor, 0, nullptr);
        recorder.dispatch(commandBuffer, ((resources.width + 1) / 2 + 15) / 16, (resources.height + 15) / 16, 1);
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 1, &barrier, 0, nullptr);
    }

    VkPipeline nativeRawPipeline(int32_t format, int32_t cfa, bool texture, bool prefilter = false)
    {
        const size_t index = (prefilter ? 24u : 0u) + (texture ? 12u : 0u) + static_cast<size_t>(format * 4 + cfa);
        if (nativeRawRejected_.at(index)) return VK_NULL_HANDLE;
        auto& pipeline = nativeRawPipelines_.at(index);
        if (pipeline == VK_NULL_HANDLE) {
            const int32_t values[] = {cfa, texture ? 1 : 0, prefilter ? 0 : 1};
            const VkSpecializationMapEntry entries[] = {{2, 0, 4}, {4, 4, 4}, {5, 8, 4}};
            const VkSpecializationInfo specialization{3, entries, sizeof(values), values};
            try {
                if (prefilter) {
                    if (format == 0) pipeline = createComputeProgram(kPreviewPrefilter0Spirv.data(), kPreviewPrefilter0Spirv.size(), &specialization);
                    else if (format == 1) pipeline = createComputeProgram(kPreviewPrefilter1Spirv.data(), kPreviewPrefilter1Spirv.size(), &specialization);
                    else pipeline = createComputeProgram(kPreviewPrefilter2Spirv.data(), kPreviewPrefilter2Spirv.size(), &specialization);
                } else if (format == 0) pipeline = createComputeProgram(kPreviewNative0Spirv.data(), kPreviewNative0Spirv.size(), &specialization);
                else if (format == 1) pipeline = createComputeProgram(kPreviewNative1Spirv.data(), kPreviewNative1Spirv.size(), &specialization);
                else pipeline = createComputeProgram(kPreviewNative2Spirv.data(), kPreviewNative2Spirv.size(), &specialization);
            } catch (...) {
                nativeRawRejected_.at(index) = true;
                throw;
            }
        }
        return pipeline;
    }

    VkPipeline rawUnpackPipeline(int32_t format)
    {
        auto& pipeline = rawUnpackPipelines_.at(static_cast<size_t>(format));
        if (pipeline == VK_NULL_HANDLE) {
            if (format == 0) pipeline = createComputeProgram(kRawUnpack0Spirv.data(), kRawUnpack0Spirv.size(), nullptr);
            else if (format == 1) pipeline = createComputeProgram(kRawUnpack1Spirv.data(), kRawUnpack1Spirv.size(), nullptr);
            else pipeline = createComputeProgram(kRawUnpack2Spirv.data(), kRawUnpack2Spirv.size(), nullptr);
        }
        return pipeline;
    }

    VkPipeline rawAreaPipeline(int32_t format, int32_t cfa)
    {
        if (!rawAreaWeightsPipeline_) rawAreaWeightsPipeline_ = createComputeProgram(
            kRawAreaWeightsSpirv.data(), kRawAreaWeightsSpirv.size(), nullptr);
        auto& pipeline = rawAreaPipelines_.at(static_cast<size_t>(format * 4 + cfa));
        if (pipeline == VK_NULL_HANDLE) {
            const VkSpecializationMapEntry entry{2, 0, sizeof(cfa)};
            const VkSpecializationInfo specialization{1, &entry, sizeof(cfa), &cfa};
            if (format == 0) pipeline = createComputeProgram(kRawArea0Spirv.data(), kRawArea0Spirv.size(), &specialization);
            else if (format == 1) pipeline = createComputeProgram(kRawArea1Spirv.data(), kRawArea1Spirv.size(), &specialization);
            else pipeline = createComputeProgram(kRawArea2Spirv.data(), kRawArea2Spirv.size(), &specialization);
        }
        return pipeline;
    }

    VkPipeline previewPipeline(RawImportKind input, bool tiled, bool& reuse, int32_t format, int32_t cfa,
                               bool dcp, bool& raster, bool& texture, bool& unpack, bool previewRemosaic, bool mhc, bool& area, bool& prefilter, bool filterArea, bool cells = false, bool stabilized = false)
    {
        if (!stabilized && bayerCellRawPreview()) {
            unpack = area = prefilter = false;
            return bayerCellPipeline(input, format, cfa, previewRemosaic, raster, texture, filterArea, cells);
        }
        const bool image = input == RawImportKind::Image;
        reuse &= optimizedRawPreview();
        const size_t base = (dcp ? 48u : 0u) + (image ? 24u : 0u) + (tiled ? 12u : 0u) +
                            static_cast<size_t>(format) * 4u + static_cast<size_t>(cfa);
        const size_t shadingIndex = (filterArea ? 0u : 384u) + base + (previewRemosaic ? 96u : 0u) + (mhc ? 192u : 0u);
        if (previewShadingRejected_.at(shadingIndex)) reuse = false;
        if (!reuse) {
            raster = false;
            texture = false;
            unpack = false;
            area = false;
            prefilter = false;
        }
        if (compatibleRawPreview()) {
            const size_t quality = !filterArea ? 0u : (mhc ? 2u : 1u);
            const size_t source = previewRemosaic ? 2u : (image ? 1u : 0u);
            const size_t compatibleIndex = quality * 3u + source;
            auto& compatiblePipeline = previewCompatiblePipelines_.at(compatibleIndex);
            if (compatiblePipeline == VK_NULL_HANDLE) {
                const std::array<const uint32_t*, 9> programs{{
                    kPreviewCompat00Spirv.data(), kPreviewCompat01Spirv.data(), kPreviewCompat02Spirv.data(),
                    kPreviewCompat10Spirv.data(), kPreviewCompat11Spirv.data(), kPreviewCompat12Spirv.data(),
                    kPreviewCompat20Spirv.data(), kPreviewCompat21Spirv.data(), kPreviewCompat22Spirv.data()}};
                const std::array<size_t, 9> sizes{{
                    kPreviewCompat00Spirv.size(), kPreviewCompat01Spirv.size(), kPreviewCompat02Spirv.size(),
                    kPreviewCompat10Spirv.size(), kPreviewCompat11Spirv.size(), kPreviewCompat12Spirv.size(),
                    kPreviewCompat20Spirv.size(), kPreviewCompat21Spirv.size(), kPreviewCompat22Spirv.size()}};
                recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanRenderer",
                    "Preparing fixed RAW preview: quality=%zu input=%zu", quality, source);
                compatiblePipeline = createComputeProgram(programs[compatibleIndex], sizes[compatibleIndex], nullptr);
            }
            return compatiblePipeline;
        }
        const size_t index = (filterArea ? 0u : 12288u) + (prefilter ? 6144u : 0u) + (area ? 3072u : 0u) + (mhc ? 1536u : 0u) + (previewRemosaic ? 768u : 0u) + (unpack ? 384u : 0u) + (texture ? 192u : 0u) + (reuse ? 96u : 0u) + base;
        VkPipeline& pipeline = previewPipelines_.at(index);
        const std::array<int32_t, 10> values{tiled ? 1 : 0, format, cfa, dcp ? 1 : 0, texture ? 1 : 0, unpack ? 1 : 0, previewRemosaic ? 1 : 0, mhc ? 1 : 0, area ? 1 : 0, prefilter ? 1 : 0};
        const std::array<VkSpecializationMapEntry, 10> entries{{
            {0, 0, sizeof(int32_t)},
            {1, sizeof(int32_t), sizeof(int32_t)},
            {2, sizeof(int32_t) * 2, sizeof(int32_t)},
            {3, sizeof(int32_t) * 3, sizeof(int32_t)},
            {4, sizeof(int32_t) * 4, sizeof(int32_t)},
            {5, sizeof(int32_t) * 5, sizeof(int32_t)},
            {6, sizeof(int32_t) * 6, sizeof(int32_t)},
            {7, sizeof(int32_t) * 7, sizeof(int32_t)},
            {8, sizeof(int32_t) * 8, sizeof(int32_t)},
            {9, sizeof(int32_t) * 9, sizeof(int32_t)},
        }};
        const VkSpecializationInfo specialization{
            static_cast<uint32_t>(entries.size()), entries.data(),
            sizeof(values), values.data()};
        if (raster) {
            const size_t rasterIndex = (filterArea ? 0u : 768u) + (prefilter ? 384u : 0u) + (area ? 192u : 0u) + (mhc ? 96u : 0u) + (previewRemosaic ? 48u : 0u) + (unpack ? 24u : 0u) + (texture ? 12u : 0u) +
                static_cast<size_t>(format) * 4u + static_cast<size_t>(cfa);
            if (!previewRasterRejected_.at(rasterIndex)) {
                VkPipeline& rasterPipeline = previewRasterPipelines_.at(rasterIndex);
                try {
                    if (rasterPipeline == VK_NULL_HANDLE) {
                        if (!filterArea) {
                            if (format == 0)
                                rasterPipeline = createGraphicsProgram(kPreviewLightRaster0Spirv.data(), kPreviewLightRaster0Spirv.size(),
                                    rawRenderPass_, computePipelineLayout_, &specialization);
                            else if (format == 1)
                                rasterPipeline = createGraphicsProgram(kPreviewLightRaster1Spirv.data(), kPreviewLightRaster1Spirv.size(),
                                    rawRenderPass_, computePipelineLayout_, &specialization);
                            else
                                rasterPipeline = createGraphicsProgram(kPreviewLightRaster2Spirv.data(), kPreviewLightRaster2Spirv.size(),
                                    rawRenderPass_, computePipelineLayout_, &specialization);
                        } else if (format == 0)
                            rasterPipeline = createGraphicsProgram(kPreviewRaster0Spirv.data(), kPreviewRaster0Spirv.size(),
                                rawRenderPass_, computePipelineLayout_, &specialization);
                        else if (format == 1)
                            rasterPipeline = createGraphicsProgram(kPreviewRaster1Spirv.data(), kPreviewRaster1Spirv.size(),
                                rawRenderPass_, computePipelineLayout_, &specialization);
                        else
                            rasterPipeline = createGraphicsProgram(kPreviewRaster2Spirv.data(), kPreviewRaster2Spirv.size(),
                                rawRenderPass_, computePipelineLayout_, &specialization);
                    }
                    return rasterPipeline;
                } catch (const std::exception& error) {
                    previewRasterRejected_[rasterIndex] = true;
                    recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf",
                        "Using compute after RAW raster pipeline rejection: %s", error.what());
                }
            }
            raster = false;
        }
        if (pipeline != VK_NULL_HANDLE) return pipeline;
        if (!reuse)
            recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanRenderer",
                "Preparing scalar RAW preview: image=%d format=%d cfa=%d tiled=%d dcp=%d remosaic=%d filtered=%d",
                image, format, cfa, tiled, dcp, previewRemosaic, filterArea);
        try {
            if (!filterArea) {
                if (reuse && !image && !dcp) {
                    if (format == 0)
                        pipeline = createComputeProgram(kPreviewLightFast0Spirv.data(), kPreviewLightFast0Spirv.size(), &specialization);
                    else if (format == 1)
                        pipeline = createComputeProgram(kPreviewLightFast1Spirv.data(), kPreviewLightFast1Spirv.size(), &specialization);
                    else
                        pipeline = createComputeProgram(kPreviewLightFast2Spirv.data(), kPreviewLightFast2Spirv.size(), &specialization);
                } else if (reuse) {
                    pipeline = image
                        ? createComputeProgram(kPreviewLight11Spirv.data(), kPreviewLight11Spirv.size(), &specialization)
                        : createComputeProgram(kPreviewLight01Spirv.data(), kPreviewLight01Spirv.size(), &specialization);
                } else if (previewRemosaic && !image) {
                    pipeline = createComputeProgram(kPreviewLightRemosaicSpirv.data(), kPreviewLightRemosaicSpirv.size(), nullptr);
                } else {
                    pipeline = image
                        ? createComputeProgram(kPreviewLight10Spirv.data(), kPreviewLight10Spirv.size(), &specialization)
                        : createComputeProgram(kPreviewLight00Spirv.data(), kPreviewLight00Spirv.size(), &specialization);
                }
            } else if (reuse) {
                if (!image && !dcp) {
                    if (format == 0)
                        pipeline = createComputeProgram(kPreviewFast0Spirv.data(), kPreviewFast0Spirv.size(), &specialization);
                    else if (format == 1)
                        pipeline = createComputeProgram(kPreviewFast1Spirv.data(), kPreviewFast1Spirv.size(), &specialization);
                    else
                        pipeline = createComputeProgram(kPreviewFast2Spirv.data(), kPreviewFast2Spirv.size(), &specialization);
                } else {
                    pipeline = image
                        ? createComputeProgram(kPreviewShadingImageSpirv.data(), kPreviewShadingImageSpirv.size(), &specialization)
                        : createComputeProgram(kPreviewShadingSpirv.data(), kPreviewShadingSpirv.size(), &specialization);
                }
            } else {
                if (mhc) {
                    pipeline = image
                        ? createComputeProgram(kPreviewMhcImageSpirv.data(), kPreviewMhcImageSpirv.size(), &specialization)
                        : createComputeProgram(kPreviewMhcSpirv.data(), kPreviewMhcSpirv.size(), &specialization);
                } else {
                    pipeline = image
                        ? createComputeProgram(kPreviewBilinearImageSpirv.data(), kPreviewBilinearImageSpirv.size(), &specialization)
                        : createComputeProgram(kPreviewBilinearSpirv.data(), kPreviewBilinearSpirv.size(), &specialization);
                }
            }
        } catch (const std::exception& error) {
            if (texture || unpack) {
                lensTextureSupported_ = false;
                unpackSupported_ = false;
                texture = false;
                unpack = false;
                area = false;
                prefilter = false;
                recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf",
                    "Using direct RAW preview after texture pipeline rejection: %s", error.what());
                return previewPipeline(input, tiled, reuse, format, cfa, dcp, raster, texture, unpack, previewRemosaic, mhc, area, prefilter, filterArea, cells, stabilized);
            }
            if (!reuse) throw;
            previewShadingRejected_[shadingIndex] = true;
            reuse = false;
            recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf",
                "Using per-sample shading after optimized pipeline rejection: %s", error.what());
            raster = false;
            return previewPipeline(input, tiled, reuse, format, cfa, dcp, raster, texture, unpack, previewRemosaic, mhc, area, prefilter, filterArea, cells, stabilized);
        }
        return pipeline;
    }

    bool bayerCellRawPreview() const
    {
#if !defined(NDEBUG)
        char samplingOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.preview_sampling", samplingOverride);
        if (samplingOverride[0] == '0') return false;
#endif
        return true;
    }

    VkPipeline bayerCellPipeline(RawImportKind input, int32_t format, int32_t cfa,
                                 bool remosaic, bool& raster, bool& texture, bool filtered, bool cells = false)
    {
        const bool compatible = compatibleRawPreview();
        const size_t source = cells ? 3u : remosaic ? 2u : input == RawImportKind::Image ? 1u : 0u;
        if (compatible) raster = texture = false;
        const size_t index = (filtered ? 384u : 0u) + (compatible ? 192u : 0u) +
            (raster ? 96u : 0u) + (texture ? 48u : 0u) + source * 12u + format * 4u + cfa;
        if (raster && bayerCellRasterRejected_[index]) {
            raster = false;
            return bayerCellPipeline(input, format, cfa, remosaic, raster, texture, filtered, cells);
        }
        auto& pipeline = bayerCellPipelines_.at(index);
        if (pipeline) return pipeline;
        const int32_t values[] = {format, cfa, texture ? 1 : 0, remosaic ? 1 : 0};
        const VkSpecializationMapEntry entries[] = {{1, 0, 4}, {2, 4, 4}, {4, 8, 4}, {6, 12, 4}};
        const VkSpecializationInfo specialization{4, entries, sizeof(values), values};
        const std::array<const uint32_t*, 8> programs{{
            kBayerCell0Spirv.data(), kBayerCell1Spirv.data(), kBayerCell2Spirv.data(), kBayerCell3Spirv.data(),
            kBayerCellCompat0Spirv.data(), kBayerCellCompat1Spirv.data(), kBayerCellCompat2Spirv.data(), kBayerCellCompat3Spirv.data()}};
        const std::array<size_t, 8> sizes{{
            kBayerCell0Spirv.size(), kBayerCell1Spirv.size(), kBayerCell2Spirv.size(), kBayerCell3Spirv.size(),
            kBayerCellCompat0Spirv.size(), kBayerCellCompat1Spirv.size(), kBayerCellCompat2Spirv.size(), kBayerCellCompat3Spirv.size()}};
        try {
            if (raster) {
                pipeline = createGraphicsProgram(kBayerCellRasterSpirv.data(), kBayerCellRasterSpirv.size(),
                    rawRenderPass_, computePipelineLayout_, &specialization);
            } else {
                const size_t program = source + (compatible ? 4u : 0u);
                pipeline = createComputeProgram(programs[program], sizes[program], compatible ? nullptr : &specialization);
            }
        } catch (const std::exception& error) {
            if (!raster) throw;
            bayerCellRasterRejected_[index] = true;
            raster = false;
            recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Using Bayer-cell compute: %s", error.what());
            return bayerCellPipeline(input, format, cfa, remosaic, raster, texture, filtered, cells);
        }
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf",
            "BayerCell RAW: input=%zu format=%d cfa=%d raster=%d texture=%d compatible=%d filtered_device=%d",
            source, format, cfa, raster, texture, compatible, filtered);
        return pipeline;
    }

    VkPipeline recordingPipeline(RawImportKind input, int32_t format, int32_t cfa,
                                 bool& raster, bool& texture, bool& tiled, bool filtered, bool cells = false)
    {
        const bool compatible = compatibleRawPreview();
        const size_t source = cells ? 2u : input == RawImportKind::Image ? 1u : 0u;
        if (compatible) raster = texture = tiled = false;
        if (tiled) raster = false;
        const size_t index = (tiled ? 576u : 0u) + (filtered ? 288u : 0u) + (compatible ? 144u : 0u) +
            (raster ? 72u : 0u) + (texture ? 36u : 0u) + source * 12u + format * 4u + cfa;
        if ((raster || tiled) && recordingPipelineRejected_[index]) {
            raster = tiled = false;
            return recordingPipeline(input, format, cfa, raster, texture, tiled, filtered, cells);
        }
        auto& pipeline = recordingPipelines_.at(index);
        if (pipeline) return pipeline;
        const int32_t values[] = {format, cfa, texture ? 1 : 0};
        const VkSpecializationMapEntry entries[] = {{1, 0, 4}, {2, 4, 4}, {4, 8, 4}};
        const VkSpecializationInfo specialization{3, entries, sizeof(values), values};
        const std::array<const uint32_t*, 9> programs{{kRecording0Spirv.data(), kRecording1Spirv.data(),
            kRecording2Spirv.data(), kRecordingCompat0Spirv.data(), kRecordingCompat1Spirv.data(), kRecordingCompat2Spirv.data(),
            kRecordingTile0Spirv.data(), kRecordingTile1Spirv.data(), kRecordingTile2Spirv.data()}};
        const std::array<size_t, 9> sizes{{kRecording0Spirv.size(), kRecording1Spirv.size(), kRecording2Spirv.size(),
            kRecordingCompat0Spirv.size(), kRecordingCompat1Spirv.size(), kRecordingCompat2Spirv.size(),
            kRecordingTile0Spirv.size(), kRecordingTile1Spirv.size(), kRecordingTile2Spirv.size()}};
        try {
            if (raster) {
                pipeline = createGraphicsProgram(kRecordingRasterSpirv.data(), kRecordingRasterSpirv.size(),
                    rawRenderPass_, computePipelineLayout_, &specialization);
            } else {
                const size_t program = source + (tiled ? 6u : compatible ? 3u : 0u);
                pipeline = createComputeProgram(programs[program], sizes[program], compatible ? nullptr : &specialization);
            }
        } catch (const std::exception& error) {
            if (!raster && !tiled) throw;
            recordingPipelineRejected_[index] = true;
            raster = tiled = false;
            recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Using recording compute: %s", error.what());
            return recordingPipeline(input, format, cfa, raster, texture, tiled, filtered, cells);
        }
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf",
            "Recording tent: input=%zu format=%d cfa=%d raster=%d texture=%d tiled=%d compatible=%d filtered_device=%d",
            source, format, cfa, raster, texture, tiled, compatible, filtered);
        return pipeline;
    }

    VkPipeline prepareHotPixelPipeline(bool image)
    {
        if (!hotPixelDescriptorLayout_) {
            const VkDescriptorSetLayoutBinding bindings[] = {
                {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}};
            VkDescriptorSetLayoutCreateInfo layout{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            layout.bindingCount = 6;
            layout.pBindings = bindings;
            require(vkCreateDescriptorSetLayout(device_, &layout, nullptr, &hotPixelDescriptorLayout_), "Hot pixel descriptor layout");
            VkPipelineLayoutCreateInfo pipelineLayout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            pipelineLayout.setLayoutCount = 1;
            pipelineLayout.pSetLayouts = &hotPixelDescriptorLayout_;
            require(vkCreatePipelineLayout(device_, &pipelineLayout, nullptr, &hotPixelPipelineLayout_), "Hot pixel pipeline layout");
        }
        auto& pipeline = hotPixelPipelines_[image ? 1 : 0];
        if (!pipeline) pipeline = image
            ? createComputeProgram(kRawHotPixelsImageSpirv.data(), kRawHotPixelsImageSpirv.size(), nullptr, hotPixelPipelineLayout_)
            : createComputeProgram(kRawHotPixelsBufferSpirv.data(), kRawHotPixelsBufferSpirv.size(), nullptr, hotPixelPipelineLayout_);
        return pipeline;
    }

    void prepareHotPixels(Frame& frame, RawParameters& raw, bool enabled)
    {
        auto& resources = frame.hotPixels;
        frame.computeDescriptorsDirty |= resources.enabled != enabled;
        resources.enabled = enabled;
        if (!enabled) return;
        const bool image = frame.rawImportKind == RawImportKind::Image;
        const VkDeviceSize stride = ((static_cast<VkDeviceSize>(raw.dimensions[0]) + 1) / 2) * 4;
        const VkDeviceSize bytes = stride * raw.dimensions[1];
        const auto pipeline = prepareHotPixelPipeline(image);
        if (resources.capacity < bytes) {
            destroyRawBufferResources(resources);
            resources.enabled = true;
            makeBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                resources.output, resources.memory, nullptr);
            resources.capacity = bytes;
            frame.computeDescriptorsDirty = true;
        }
        if (!resources.uniform) {
            makeBuffer(kRawParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                resources.uniform, resources.uniformMemory, &resources.mapped);
            const VkDescriptorPoolSize sizes[] = {
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2}, {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 3},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}};
            VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pool.maxSets = 1;
            pool.poolSizeCount = 3;
            pool.pPoolSizes = sizes;
            require(vkCreateDescriptorPool(device_, &pool, nullptr, &resources.pool), "Hot pixel descriptor pool");
            VkDescriptorSetAllocateInfo allocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocation.descriptorPool = resources.pool;
            allocation.descriptorSetCount = 1;
            allocation.pSetLayouts = &hotPixelDescriptorLayout_;
            require(vkAllocateDescriptorSets(device_, &allocation, &resources.descriptor), "Hot pixel descriptor set");
        }
        resources.pipeline = pipeline;
        resources.width = raw.dimensions[0];
        resources.height = raw.dimensions[1];
        std::memcpy(resources.mapped, &raw, sizeof(raw));
        const VkDescriptorBufferInfo buffers[] = {
            {sourceRawBuffer(frame), 0, VK_WHOLE_SIZE}, {resources.uniform, 0, kRawParametersSize},
            {resources.output, 0, bytes}, {frame.spektraUniform, 0, kRawColorParametersSize},
            {frame.geometryUniform, 0, kGeometryParametersSize}};
        const VkDescriptorImageInfo input{rawIntegerSampler_, frame.importedImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        std::array<VkWriteDescriptorSet, 5> writes{};
        for (uint32_t i = 0; i < writes.size(); ++i) {
            auto& write = writes[i];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = resources.descriptor;
            write.dstBinding = i == 4 ? 6 : i;
            write.descriptorCount = 1;
            write.descriptorType = i == 0 || i == 2 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            write.pBufferInfo = &buffers[i];
        }
        if (image) {
            writes[0].dstBinding = 5;
            writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[0].pBufferInfo = nullptr;
            writes[0].pImageInfo = &input;
        }
        vkUpdateDescriptorSets(device_, writes.size(), writes.data(), 0, nullptr);
        raw.layoutInfo[0] = static_cast<int32_t>(stride);
        raw.layoutInfo[1] = 0;
    }

    void recordHotPixels(VkCommandBuffer command, Frame& frame, camera_gpu::ComputeRecorder& recorder)
    {
        const auto& resources = frame.hotPixels;
        if (!resources.enabled) return;
        VkBufferMemoryBarrier barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = resources.output;
        barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &barrier, 0, nullptr);
        recorder.bindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, resources.pipeline);
        recorder.bindDescriptors(command, VK_PIPELINE_BIND_POINT_COMPUTE, hotPixelPipelineLayout_,
            0, 1, &resources.descriptor, 0, nullptr);
        recorder.dispatch(command, ((resources.width + 1) / 2 + 15) / 16, (resources.height + 15) / 16, 1);
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 1, &barrier, 0, nullptr);
    }

    VkPipeline nativeRecordingPipeline(RawImportKind input, int32_t format, int32_t cfa,
                                       bool& raster, bool texture, bool filtered, bool cells = false)
    {
        const size_t source = cells ? 2u : input == RawImportKind::Image ? 1u : 0u;
        if (compatibleRawPreview() || filtered || source != 0u) raster = false;
        const size_t rasterIndex = (texture ? 12u : 0u) + format * 4u + cfa;
        if (nativeRecordingRasterRejected_.at(rasterIndex)) raster = false;
        const bool compatible = !raster && compatibleNativeRecordingCompute();
        if (compatible) texture = false;
        const size_t index = (raster ? 288u : 0u) + (compatible ? 144u : 0u) + (filtered ? 72u : 0u) +
            (texture ? 36u : 0u) + source * 12u + format * 4u + cfa;
        auto& pipeline = nativeRecordingPipelines_.at(index);
        if (pipeline) return pipeline;
        const int32_t values[] = {format, cfa, texture ? 1 : 0};
        const VkSpecializationMapEntry entries[] = {{1, 0, 4}, {2, 4, 4}, {4, 8, 4}};
        const VkSpecializationInfo specialization{3, entries, sizeof(values), values};
        const std::array<const uint32_t*, 12> programs{{
            kRecordingNative00Spirv.data(), kRecordingNative01Spirv.data(), kRecordingNative02Spirv.data(),
            kRecordingNative10Spirv.data(), kRecordingNative11Spirv.data(), kRecordingNative12Spirv.data(),
            kRecordingNativeCompat00Spirv.data(), kRecordingNativeCompat01Spirv.data(), kRecordingNativeCompat02Spirv.data(),
            kRecordingNativeCompat10Spirv.data(), kRecordingNativeCompat11Spirv.data(), kRecordingNativeCompat12Spirv.data()}};
        const std::array<size_t, 12> sizes{{
            kRecordingNative00Spirv.size(), kRecordingNative01Spirv.size(), kRecordingNative02Spirv.size(),
            kRecordingNative10Spirv.size(), kRecordingNative11Spirv.size(), kRecordingNative12Spirv.size(),
            kRecordingNativeCompat00Spirv.size(), kRecordingNativeCompat01Spirv.size(), kRecordingNativeCompat02Spirv.size(),
            kRecordingNativeCompat10Spirv.size(), kRecordingNativeCompat11Spirv.size(), kRecordingNativeCompat12Spirv.size()}};
        const size_t program = source + (filtered ? 3u : 0u) + (compatible ? 6u : 0u);
        bool scalarFallback = compatible;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf",
            "Preparing native recording: input=%zu format=%d cfa=%d raster=%d texture=%d compatible=%d mhc=%d",
            source, format, cfa, raster, texture, compatible, filtered);
        try {
            if (raster) {
                const std::array<const uint32_t*, 3> fragments{{kPreviewLightRaster0Spirv.data(),
                    kPreviewLightRaster1Spirv.data(), kPreviewLightRaster2Spirv.data()}};
                const std::array<size_t, 3> fragmentSizes{{kPreviewLightRaster0Spirv.size(),
                    kPreviewLightRaster1Spirv.size(), kPreviewLightRaster2Spirv.size()}};
                pipeline = createGraphicsProgram(fragments.at(format), fragmentSizes.at(format),
                    rawRenderPass_, computePipelineLayout_, &specialization);
            } else {
                pipeline = createComputeProgram(programs[program], sizes[program], compatible ? nullptr : &specialization);
            }
        } catch (const std::exception& error) {
            if (raster) {
                nativeRecordingRasterRejected_.at(rasterIndex) = true;
                raster = false;
                recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Using native recording compute: %s", error.what());
                return nativeRecordingPipeline(input, format, cfa, raster, texture, filtered, cells);
            }
            if (compatible) throw;
            recordNativeDiagnostic(ANDROID_LOG_WARN, "RawPreviewPerf", "Using scalar native recording: %s", error.what());
            pipeline = createComputeProgram(programs[program + 6u], sizes[program + 6u], nullptr);
            scalarFallback = true;
        }
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf",
            "Native recording: input=%zu format=%d cfa=%d raster=%d texture=%d compatible=%d mhc=%d",
            source, format, cfa, raster, texture && !scalarFallback, scalarFallback, filtered);
        return pipeline;
    }

    bool optimizedRawPreview() const
    {
        return !compatibleRawPreview() && previewShadingReuse();
    }

    bool recordingTileSupported() const
    {
        // Shared-row filtering is tuned for Adreno.
        return physicalProperties_.vendorID == 0x5143u && optimizedRawPreview() &&
            physicalProperties_.limits.maxComputeSharedMemorySize >= kRecordingTileSharedBytes;
    }

    bool compatibleRawPreview() const
    {
#if !defined(NDEBUG)
        char compatibilityOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.preview_compat", compatibilityOverride);
        if (compatibilityOverride[0] == '1') return true;
#endif
        return !raw_preview::supportsOptimizedPreview(physicalProperties_.vendorID,
            physicalProperties_.deviceID, physicalProperties_.deviceName);
    }

    bool compatibleNativeRecordingCompute() const
    {
#if !defined(NDEBUG)
        char compatibilityOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.native_recording_compat", compatibilityOverride);
        if (compatibilityOverride[0] == '1') return true;
#endif
        return compatibleRawPreview() ||
            !raw_preview::supportsOptimizedNativeRecordingCompute(physicalProperties_.vendorID,
                physicalProperties_.deviceID, physicalProperties_.driverVersion, physicalProperties_.deviceName);
    }

    bool lightweightRawPreview() const
    {
#if !defined(NDEBUG)
        char qualityOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.preview_quality", qualityOverride);
        if (qualityOverride[0] == '0') return true;
        if (qualityOverride[0] == '1') return false;
#endif
        return lightweightPreview_;
    }

    void prepareRawPreviewPipelines(int32_t format, int32_t cfa)
    {
        const bool lightweight = lightweightRawPreview();
        const bool optimized = optimizedRawPreview();
        for (const auto input : {RawImportKind::Buffer, RawImportKind::Image}) {
            for (bool dcp : {false, true}) for (bool useRaster : {false, true}) for (bool usePrefilter : {false, true}) {
                if (usePrefilter && (lightweight || !optimized || dcp || input == RawImportKind::Image)) continue;
                bool reuse = optimized;
                bool texture = lensTextureSupported_ && optimized;
                bool raster = useRaster && input == RawImportKind::Buffer && optimized && !dcp && rawRenderPass_ != VK_NULL_HANDLE;
                bool unpack = usePrefilter, area = false, prefilter = usePrefilter;
                previewPipeline(input, false, reuse, format, cfa, dcp, raster, texture, unpack,
                    false, !lightweight, area, prefilter, !lightweight, false, true);
            }
            bool nativeRaster = lightweight && input == RawImportKind::Buffer && optimized && rawRenderPass_ != VK_NULL_HANDLE;
            nativeRecordingPipeline(input, format, cfa, nativeRaster, lensTextureSupported_ && optimized, !lightweight);
            nativeRaster = false;
            nativeRecordingPipeline(input, format, cfa, nativeRaster, lensTextureSupported_ && optimized, !lightweight);
            bool raster = input == RawImportKind::Buffer && optimized && rawRenderPass_ != VK_NULL_HANDLE;
            bool texture = lensTextureSupported_ && optimized;
            bool tiled = false;
            recordingPipeline(input, format, cfa, raster, texture, tiled, !lightweight);
            raster = false;
            recordingPipeline(input, format, cfa, raster, texture, tiled, !lightweight);
            if (recordingTileSupported()) {
                tiled = true;
                recordingPipeline(input, format, cfa, raster, texture, tiled, !lightweight);
            }
        }
        if (optimized) {
            bool raster = false;
            bool texture = lensTextureSupported_;
            bool tiled = false;
            nativeRecordingPipeline(RawImportKind::Image, format, cfa, raster, texture, !lightweight, true);
            recordingPipeline(RawImportKind::Image, format, cfa, raster, texture, tiled, !lightweight, true);
            if (recordingTileSupported()) {
                tiled = true;
                recordingPipeline(RawImportKind::Image, format, cfa, raster, texture, tiled, !lightweight, true);
            }
        }
        if (bayerCellRawPreview()) {
            if (optimized) {
                bool raster = false;
                bool texture = lensTextureSupported_;
                bayerCellPipeline(RawImportKind::Image, format, cfa, false, raster, texture, !lightweight, true);
            }
            for (const auto input : {RawImportKind::Buffer, RawImportKind::Image}) {
                for (bool remosaic : {false, true}) {
                    if (remosaic && input == RawImportKind::Image) continue;
                    bool raster = input == RawImportKind::Buffer && optimized && rawRenderPass_ != VK_NULL_HANDLE;
                    bool texture = lensTextureSupported_ && optimized;
                    bayerCellPipeline(input, format, cfa, remosaic, raster, texture, !lightweight);
                    raster = false;
                    bayerCellPipeline(input, format, cfa, remosaic, raster, texture, !lightweight);
                }
            }
            return;
        }
        if (optimized) {
            rawUnpackPipeline(format);
            if (!lightweight) {
                rawAreaPipeline(format, cfa);
                nativeRawPipeline(format, cfa, lensTextureSupported_, true);
            }
        }
        for (bool image : {false, true}) for (bool dcp : {false, true}) {
            for (int mode = 0; mode < (image ? 2 : 5); ++mode) {
                if (lightweight && mode == 3) continue;
                if (!optimized && (mode == 2 || mode == 3)) continue;
                bool reuse = optimized;
                bool texture = reuse && lensTextureSupported_;
                bool raster = reuse && !image && !dcp && rawRenderPass_ != VK_NULL_HANDLE;
                bool unpack = reuse && !image && mode < 4 && unpackSupported_ && (!lightweight || mode != 0);
                bool area = !lightweight && unpack && mode == 2;
                bool prefilter = unpack && mode == 3 && !dcp && mhcPrefilterSupported_;
                previewPipeline(image ? RawImportKind::Image : RawImportKind::Buffer,
                    mode == 1, reuse, format, cfa, dcp, raster, texture, unpack,
                    !image && mode == 4, !lightweight, area, prefilter, !lightweight);
            }
        }
    }

    struct AmazeWorkgroup {
        uint32_t x;
        uint32_t y;
    };

    AmazeWorkgroup chooseAmazeWorkgroup() const
    {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice_, &properties);
        const bool supports16x16 =
            properties.limits.maxComputeWorkGroupInvocations >= 256 &&
            properties.limits.maxComputeWorkGroupSize[0] >= 16 &&
            properties.limits.maxComputeWorkGroupSize[1] >= 16 &&
            properties.limits.maxComputeSharedMemorySize >= 8 * 1024;
        if (!supports16x16)
            return {8, 8};

        const char* adreno = std::strstr(properties.deviceName, "Adreno");
        if (adreno != nullptr) {
            while (*adreno != '\0' && (*adreno < '0' || *adreno > '9'))
                ++adreno;
            const int gpu = *adreno == '\0' ? 0 : std::atoi(adreno);
            if (gpu > 0 && gpu < 700)
                return {16, 8};
        }
        return {16, 16};
    }

    VkPipeline createAmazePipeline(
        const uint32_t* code,
        size_t wordCount,
        int32_t cfaPattern,
        AmazeWorkgroup workgroup)
    {
        std::array<std::array<int32_t, 2>, 4> redOffsets{{
            {{0, 0}},
            {{1, 0}},
            {{0, 1}},
            {{1, 1}},
        }};
        if (cfaPattern < 0 || cfaPattern >=
                static_cast<int32_t>(redOffsets.size())) {
            throw std::runtime_error("AMAZE requires a standard Bayer CFA");
        }

        struct SpecializationData {
            int32_t redOffsetX;
            int32_t redOffsetY;
            uint32_t localSizeX;
            uint32_t localSizeY;
        } data{
            redOffsets[static_cast<size_t>(cfaPattern)][0],
            redOffsets[static_cast<size_t>(cfaPattern)][1],
            workgroup.x,
            workgroup.y,
        };
        std::array<VkSpecializationMapEntry, 4> entries{{
            {7, offsetof(SpecializationData, redOffsetX), sizeof(int32_t)},
            {14, offsetof(SpecializationData, redOffsetY), sizeof(int32_t)},
            {12, offsetof(SpecializationData, localSizeX), sizeof(uint32_t)},
            {13, offsetof(SpecializationData, localSizeY), sizeof(uint32_t)},
        }};
        VkSpecializationInfo specialization{};
        specialization.mapEntryCount =
            static_cast<uint32_t>(entries.size());
        specialization.pMapEntries = entries.data();
        specialization.dataSize = sizeof(data);
        specialization.pData = &data;

        VkShaderModule shader = makeShader(device_, code, wordCount);
        VkPipelineShaderStageCreateInfo stage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shader;
        stage.pName = "main";
        stage.pSpecializationInfo = &specialization;
        VkComputePipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipelineInfo.flags |= VK_PIPELINE_CREATE_DISPATCH_BASE_BIT;
        pipelineInfo.stage = stage;
        pipelineInfo.layout = amazePipelineLayout_;
        PipelineCreationFeedback feedback(&pipelineCreationStats_);
        pipelineInfo.pNext = feedback.info();
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = vkCreateComputePipelines(
            device_, pipelineCache_.handle, 1, &pipelineInfo, nullptr, &pipeline);
        vkDestroyShaderModule(device_, shader, nullptr);
        require(result, "vkCreateComputePipelines for staged AMAZE");
        feedback.record();
        pipelineCache_.markDirty();
        return pipeline;
    }

    void destroyAmazePipelines()
    {
        for (auto& patternPipelines : amazePipelines_) for (auto& inputPipelines : patternPipelines) {
            for (VkPipeline& pipeline : inputPipelines) {
                if (pipeline != VK_NULL_HANDLE)
                    vkDestroyPipeline(device_, pipeline, nullptr);
                pipeline = VK_NULL_HANDLE;
            }
        }
        amazePipelinePattern_ = -1;
    }

    void ensureAmazePipelines(
        int32_t cfaPattern,
        size_t input,
        bool includeHighlightRecovery = false)
    {
        auto& pipelines = amazePipelines_.at(static_cast<size_t>(cfaPattern)).at(input);

        struct SpirvView {
            const uint32_t* data;
            size_t size;
        };
        const std::array<std::array<SpirvView, AmazeStageCount>, AmazeInputCount> shaders{{
            {{
                {kAmazeNormalizeBufferSpirv.data(), kAmazeNormalizeBufferSpirv.size()},
                {kAmazeCandidatesBufferSpirv.data(), kAmazeCandidatesBufferSpirv.size()},
                {kAmazeDifferencesBufferSpirv.data(), kAmazeDifferencesBufferSpirv.size()},
                {kAmazeWeightsBufferSpirv.data(), kAmazeWeightsBufferSpirv.size()},
                {kAmazeGreenBufferSpirv.data(), kAmazeGreenBufferSpirv.size()},
                {kAmazeRefineBufferSpirv.data(), kAmazeRefineBufferSpirv.size()},
                {kAmazeDiagonalBufferSpirv.data(), kAmazeDiagonalBufferSpirv.size()},
                {kAmazeChromaBufferSpirv.data(), kAmazeChromaBufferSpirv.size()},
                {kAmazeOutputBufferSpirv.data(), kAmazeOutputBufferSpirv.size()},
                {kAmazeHrSeedBufferSpirv.data(), kAmazeHrSeedBufferSpirv.size()},
                {kAmazeHrReduceBufferSpirv.data(), kAmazeHrReduceBufferSpirv.size()},
                {kAmazeRecoveryBufferSpirv.data(), kAmazeRecoveryBufferSpirv.size()},
            }},
            {{
                {kAmazeNormalizeImageSpirv.data(), kAmazeNormalizeImageSpirv.size()},
                {kAmazeCandidatesImageSpirv.data(), kAmazeCandidatesImageSpirv.size()},
                {kAmazeDifferencesImageSpirv.data(), kAmazeDifferencesImageSpirv.size()},
                {kAmazeWeightsImageSpirv.data(), kAmazeWeightsImageSpirv.size()},
                {kAmazeGreenImageSpirv.data(), kAmazeGreenImageSpirv.size()},
                {kAmazeRefineImageSpirv.data(), kAmazeRefineImageSpirv.size()},
                {kAmazeDiagonalImageSpirv.data(), kAmazeDiagonalImageSpirv.size()},
                {kAmazeChromaImageSpirv.data(), kAmazeChromaImageSpirv.size()},
                {kAmazeOutputImageSpirv.data(), kAmazeOutputImageSpirv.size()},
                {kAmazeHrSeedImageSpirv.data(), kAmazeHrSeedImageSpirv.size()},
                {kAmazeHrReduceImageSpirv.data(), kAmazeHrReduceImageSpirv.size()},
                {kAmazeRecoveryImageSpirv.data(), kAmazeRecoveryImageSpirv.size()},
            }}
        }};
        const AmazeWorkgroup workgroup = chooseAmazeWorkgroup();
        const std::array<const char*, AmazeStageCount> stageNames{
            "normalize", "candidates", "differences", "weights", "green", "refine", "diagonal", "chroma", "output", "hrseed", "hrreduce", "recovery"};
        try {
            for (size_t stage = 0; stage < AmazeStageCount; ++stage) {
                if (pipelines[stage] == VK_NULL_HANDLE &&
                    (stage < AmazeHrSeed || captureHighlightRecovery_ || includeHighlightRecovery)) {
                    try {
                        pipelines[stage] = createAmazePipeline(
                            shaders[input][stage].data,
                            shaders[input][stage].size,
                            cfaPattern, workgroup);
                    } catch (const std::exception& error) {
                        throw std::runtime_error(std::string("AMAZE ") + stageNames[stage] +
                            (input == 0 ? " (buffer): " : " (image): ") + error.what());
                    }
                }
            }
        } catch (...) {
            destroyAmazePipelines();
            throw;
        }
        amazeWorkgroup_ = workgroup;
        amazePipelinePattern_ = cfaPattern;
    }

    void createFrames()
    {
        std::array<VkCommandBuffer, kFramesInFlight * 2 + 2> commandBuffers{};
        VkCommandBufferAllocateInfo commandInfo{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        commandInfo.commandPool = commandPool_;
        commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandInfo.commandBufferCount = static_cast<uint32_t>(commandBuffers.size());
        require(vkAllocateCommandBuffers(
                    device_, &commandInfo, commandBuffers.data()),
                "vkAllocateCommandBuffers");

        histogramCommand_ = commandBuffers.back();

        std::array<VkDescriptorSetLayout, kFramesInFlight + 1> computeLayouts{};
        computeLayouts.fill(computeDescriptorLayout_);
        std::array<VkDescriptorSet, kFramesInFlight + 1> computeSets{};
        VkDescriptorSetAllocateInfo computeInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        computeInfo.descriptorPool = descriptorPool_;
        computeInfo.descriptorSetCount = kFramesInFlight + 1;
        computeInfo.pSetLayouts = computeLayouts.data();
        require(vkAllocateDescriptorSets(
                    device_, &computeInfo, computeSets.data()),
                "vkAllocateDescriptorSets");

        std::array<VkDescriptorSetLayout, kFramesInFlight * 2> presentLayouts{};
        presentLayouts.fill(presentDescriptorLayout_);
        std::array<VkDescriptorSet, kFramesInFlight * 2> presentSets{};
        VkDescriptorSetAllocateInfo presentInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        presentInfo.descriptorPool = descriptorPool_;
        presentInfo.descriptorSetCount = static_cast<uint32_t>(presentSets.size());
        presentInfo.pSetLayouts = presentLayouts.data();
        require(vkAllocateDescriptorSets(
                    device_, &presentInfo, presentSets.data()),
                "vkAllocateDescriptorSets");

        for (uint32_t index = 0; index < kFramesInFlight; ++index) {
            Frame& frame = frames_[index];
            frame.commandBuffer = commandBuffers[index];
            frame.rawCommandBuffer = commandBuffers[kFramesInFlight + 1 + index];
            frame.computeDescriptor = computeSets[index];
            frame.presentDescriptor = presentSets[index];
            frame.videoDescriptor = presentSets[kFramesInFlight + index];
            makeBuffer(8 * sizeof(float), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, frame.exposureRange, frame.exposureRangeMemory, nullptr);
            VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            require(vkCreateFence(device_, &fenceInfo, nullptr, &frame.fence),
                    "vkCreateFence");
            VkSemaphoreCreateInfo semaphoreInfo{
                VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            require(vkCreateSemaphore(
                        device_, &semaphoreInfo, nullptr, &frame.imageAvailable),
                    "vkCreateSemaphore");
            makeBuffer(kRawParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                           VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       frame.rawUniform, frame.rawUniformMemory,
                       &frame.rawMapped);
            makeBuffer(kRawColorParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                           VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       frame.spektraUniform, frame.spektraUniformMemory,
                       &frame.spektraMapped);
            makeBuffer(kGeometryParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                           VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       frame.geometryUniform, frame.geometryUniformMemory,
                       &frame.geometryMapped);
        }

        captureFrame_.commandBuffer = commandBuffers[kFramesInFlight];
        captureFrame_.computeDescriptor = computeSets[kFramesInFlight];
        VkFenceCreateInfo captureFenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        captureFenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        require(vkCreateFence(
                    device_, &captureFenceInfo, nullptr, &captureFrame_.fence),
                "vkCreateFence for capture");
        makeBuffer(kRawParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                   captureFrame_.rawUniform, captureFrame_.rawUniformMemory,
                   &captureFrame_.rawMapped);
        makeBuffer(kRawColorParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                   captureFrame_.spektraUniform,
                   captureFrame_.spektraUniformMemory,
                   &captureFrame_.spektraMapped);
        makeBuffer(kGeometryParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                   captureFrame_.geometryUniform,
                   captureFrame_.geometryUniformMemory,
                   &captureFrame_.geometryMapped);
    }

    void validateRawParameters(const RawParameters& parameters) const
    {
        if (parameters.dimensions[0] <= 0 || parameters.dimensions[1] <= 0)
            throw std::runtime_error("RAW dimensions must be positive");
        if (parameters.layoutInfo[0] <= 0)
            throw std::runtime_error("RAW row stride must be positive");
        if (parameters.layoutInfo[1] < 0 || parameters.layoutInfo[1] > 2)
            throw std::runtime_error("RAW format must be RAW_SENSOR, RAW10, or RAW12");
        if (parameters.layoutInfo[2] < 0 || parameters.layoutInfo[2] > 3)
            throw std::runtime_error("CFA must be RGGB, GRBG, GBRG, or BGGR");
        if (parameters.remosaic[0] < 0 || parameters.remosaic[0] > 4 ||
            parameters.remosaic[1] < 0 || parameters.remosaic[1] > 3 ||
            parameters.remosaic[2] < 0 || parameters.remosaic[3] < 0 ||
            parameters.remosaic[2] >= 2 * std::max(1, parameters.remosaic[0]) ||
            parameters.remosaic[3] >= 2 * std::max(1, parameters.remosaic[0]))
            throw std::runtime_error("Invalid RAW remosaic layout");
        int64_t minimumStride = 0;
        if (parameters.layoutInfo[1] == 1)
            minimumStride = ((static_cast<int64_t>(parameters.dimensions[0]) + 3) / 4) * 5;
        else if (parameters.layoutInfo[1] == 2)
            minimumStride = ((static_cast<int64_t>(parameters.dimensions[0]) + 1) / 2) * 3;
        else
            minimumStride = static_cast<int64_t>(parameters.dimensions[0]) * 2;
        if (parameters.layoutInfo[0] < minimumStride)
            throw std::runtime_error("RAW row stride is too small for its width and format");
    }

    static constexpr uint32_t kRawImageProbeCount = 9 * 5 * 16;

    void resetRawImageValidation()
    {
        for (auto* buffer : validatedRawImages_) AHardwareBuffer_release(buffer);
        validatedRawImages_.clear();
    }

    void prepareRawImageProbe()
    {
        try {
            createRawImageProbeResources();
        } catch (...) {
            destroyRawImageProbe();
            throw;
        }
    }

    void createRawImageProbeResources()
    {
        auto& probe = rawImageProbe_;
        if (!probe.descriptorLayout) {
            const std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
                {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = bindings.size();
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &probe.descriptorLayout), "RAW image probe descriptors");
        }
        if (!probe.pipelineLayout) {
            VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 2 * sizeof(int32_t)};
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &probe.descriptorLayout;
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &range;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &probe.pipelineLayout), "RAW image probe layout");
        }
        if (!probe.pipeline) probe.pipeline = createComputeProgram(
            kRawImageProbeSpirv.data(), kRawImageProbeSpirv.size(), nullptr, probe.pipelineLayout);
        if (!probe.buffer) makeBuffer(kRawImageProbeCount * sizeof(uint32_t), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            probe.buffer, probe.memory, &probe.mapped);
        if (!probe.pool) {
            const std::array<VkDescriptorPoolSize, 2> sizes{{
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1}}};
            VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            info.maxSets = 1;
            info.poolSizeCount = sizes.size();
            info.pPoolSizes = sizes.data();
            require(vkCreateDescriptorPool(device_, &info, nullptr, &probe.pool), "RAW image probe pool");
        }
        if (!probe.descriptor) {
            VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            info.descriptorPool = probe.pool;
            info.descriptorSetCount = 1;
            info.pSetLayouts = &probe.descriptorLayout;
            require(vkAllocateDescriptorSets(device_, &info, &probe.descriptor), "RAW image probe set");
        }
        if (!probe.command) {
            VkCommandBufferAllocateInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            info.commandPool = commandPool_;
            info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            info.commandBufferCount = 1;
            require(vkAllocateCommandBuffers(device_, &info, &probe.command), "RAW image probe commands");
        }
        if (!probe.fence) {
            VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            require(vkCreateFence(device_, &info, nullptr, &probe.fence), "RAW image probe fence");
        }
    }

    void validateRawImage(JNIEnv* environment, jobject planeObject, const RawParameters& raw,
                          Frame& frame, const std::array<uint64_t, 12>& layout)
    {
        if (validatedRawImageLayout_ != layout) {
            resetRawImageValidation();
            validatedRawImageLayout_ = layout;
        }
        if (std::find(validatedRawImages_.begin(), validatedRawImages_.end(), frame.retainedHardwareBuffer) !=
            validatedRawImages_.end()) return;
        const auto* plane = static_cast<const uint8_t*>(environment->GetDirectBufferAddress(planeObject));
        const jlong capacity = environment->GetDirectBufferCapacity(planeObject);
        const int width = raw.dimensions[0], height = raw.dimensions[1], stride = raw.layoutInfo[0];
        if (!plane || capacity < static_cast<int64_t>(stride) * (height - 1) + int64_t(width) * 2)
            throw CameraRawLayoutError("RAW image probe has no complete CPU plane");
        prepareRawImageProbe();
        auto& probe = rawImageProbe_;
        VkDescriptorImageInfo image{rawIntegerSampler_, frame.importedImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorBufferInfo buffer{probe.buffer, 0, kRawImageProbeCount * sizeof(uint32_t)};
        std::array<VkWriteDescriptorSet, 2> writes{};
        for (uint32_t index = 0; index < writes.size(); ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = probe.descriptor;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
        }
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[0].pImageInfo = &image;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[1].pBufferInfo = &buffer;
        vkUpdateDescriptorSets(device_, writes.size(), writes.data(), 0, nullptr);
        require(vkResetCommandBuffer(probe.command, 0), "Reset RAW image probe");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require(vkBeginCommandBuffer(probe.command, &begin), "Begin RAW image probe");
        acquireRawForCompute(probe.command, frame);
        vkCmdBindPipeline(probe.command, VK_PIPELINE_BIND_POINT_COMPUTE, probe.pipeline);
        vkCmdBindDescriptorSets(probe.command, VK_PIPELINE_BIND_POINT_COMPUTE, probe.pipelineLayout,
            0, 1, &probe.descriptor, 0, nullptr);
        vkCmdPushConstants(probe.command, probe.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, 2 * sizeof(int32_t), raw.dimensions);
        vkCmdDispatch(probe.command, (kRawImageProbeCount + 31) / 32, 1, 1);
        releaseRawFromCompute(probe.command, frame);
        VkMemoryBarrier readable{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        readable.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        readable.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &readable, 0, nullptr, 0, nullptr);
        require(vkEndCommandBuffer(probe.command), "End RAW image probe");
        require(vkResetFences(device_, 1, &probe.fence), "Reset RAW image probe fence");
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &probe.command;
        {
            std::lock_guard<std::recursive_mutex> lock(queueMutex_);
            require(vkQueueSubmit(queue_, 1, &submit, probe.fence), "Submit RAW image probe");
        }
        require(vkWaitForFences(device_, 1, &probe.fence, VK_TRUE, UINT64_MAX), "Read RAW image probe");
        const auto* samples = static_cast<const uint32_t*>(probe.mapped);
        std::array<uint32_t, 16> low{}, high{};
        low.fill(UINT32_MAX);
        for (uint32_t index = 0; index < kRawImageProbeCount; ++index) {
            const int cell = index / 16, offset = index % 16;
            const int x = std::min(width - 1, ((std::max(width - 4, 0) * (cell % 9) / 8) & ~1) + offset % 4);
            const int y = std::min(height - 1, ((std::max(height - 4, 0) * (cell / 9) / 4) & ~1) + offset / 4);
            const auto* address = plane + size_t(y) * stride + size_t(x) * 2;
            const uint32_t expected = uint32_t(address[0]) | (uint32_t(address[1]) << 8);
            if (samples[index] != expected)
                throw CameraRawLayoutError("Vulkan RAW image differs from CPU plane at " + std::to_string(x) + "," +
                    std::to_string(y) + " expected=" + std::to_string(expected) + " actual=" + std::to_string(samples[index]));
            low[offset] = std::min(low[offset], expected);
            high[offset] = std::max(high[offset], expected);
        }
        // Uniform frames cannot establish that the driver uses the correct row layout.
        bool spatialDetail = false;
        for (size_t phase = 0; phase < low.size(); ++phase) spatialDetail |= high[phase] - low[phase] > 8;
        if (spatialDetail) {
            if (validatedRawImages_.size() >= 16) resetRawImageValidation();
            AHardwareBuffer_acquire(frame.retainedHardwareBuffer);
            validatedRawImages_.push_back(frame.retainedHardwareBuffer);
        }
    }


    // ----- ISP viewfinder: the camera's processed stream as the preview source -----

    struct IspParameters {
        int32_t source[4];
        float exposure[4];
        int32_t planes[3][4];
        float curve[256 * 4];
    };
    static_assert(sizeof(IspParameters) == kIspParametersSize, "ISP parameters must match the shader layout");

    struct IspFormatKey {
        VkFormat format = VK_FORMAT_UNDEFINED;
        uint64_t externalFormat = 0;
        VkSamplerYcbcrModelConversion model = VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY;
        VkSamplerYcbcrRange range = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
        VkComponentMapping components{};
        VkChromaLocation xChroma = VK_CHROMA_LOCATION_COSITED_EVEN;
        VkChromaLocation yChroma = VK_CHROMA_LOCATION_COSITED_EVEN;
        VkFormatFeatureFlags features = 0;

        bool operator==(const IspFormatKey& other) const
        {
            return format == other.format && externalFormat == other.externalFormat && model == other.model &&
                range == other.range && components.r == other.components.r && components.g == other.components.g &&
                components.b == other.components.b && components.a == other.components.a &&
                xChroma == other.xChroma && yChroma == other.yChroma && features == other.features;
        }
    };

    static IspFormatKey ispFormatKey(const VkAndroidHardwareBufferFormatPropertiesANDROID& properties)
    {
        IspFormatKey key;
        key.format = properties.format;
        key.externalFormat = properties.externalFormat;
        key.model = properties.suggestedYcbcrModel;
        key.range = properties.suggestedYcbcrRange;
        key.components = properties.samplerYcbcrConversionComponents;
        key.xChroma = properties.suggestedXChromaOffset;
        key.yChroma = properties.suggestedYChromaOffset;
        key.features = properties.formatFeatures;
        return key;
    }

    // Flag bits in IspParameters::source[2]: 1 full range, 2 driver converted, 4 range from the buffer.
    static constexpr int32_t kIspFullRange = 1;
    static constexpr int32_t kIspDriverRgb = 2;
    static constexpr int32_t kIspRangeFromBuffer = 4;

    // The shadow pass finishes ISP frames with the shared RAW color program, which must not
    // apply the sensor matrix, white balance, lens shading or DCP a second time.
    static void neutralizeRawColor(void* mapped)
    {
        auto* bytes = static_cast<uint8_t*>(mapped);
        const float identity[16] = {1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f};
        std::memcpy(bytes, identity, sizeof(identity));
        const float zero = 0.0f;
        std::memcpy(bytes + 92, &zero, sizeof(zero));
        std::memcpy(bytes + 96, &zero, sizeof(zero));
    }

    void ensureIspPool()
    {
        if (isp_.pool) return;
        const uint32_t sets = 2 * kFramesInFlight + 2;
        const std::array<VkDescriptorPoolSize, 4> sizes{{
            // A multi-planar sampler conversion can consume several descriptors per binding.
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3 * sets},
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2 * sets},
            {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 3 * sets},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, sets}}};
        VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        info.maxSets = sets;
        info.poolSizeCount = static_cast<uint32_t>(sizes.size());
        info.pPoolSizes = sizes.data();
        require(vkCreateDescriptorPool(device_, &info, nullptr, &isp_.pool), "ISP viewfinder descriptor pool");
    }

    VkDescriptorSetLayout createIspLayout(const VkSampler* immutableSampler)
    {
        const std::array<VkDescriptorSetLayoutBinding, 6> bindings{{
            {0, immutableSampler ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
             VK_SHADER_STAGE_COMPUTE_BIT, immutableSampler},
            {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {11, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
        VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        info.bindingCount = static_cast<uint32_t>(bindings.size());
        info.pBindings = bindings.data();
        VkDescriptorSetLayout layout = VK_NULL_HANDLE;
        require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &layout), "ISP viewfinder descriptor layout");
        return layout;
    }

    VkPipelineLayout createIspPipelineLayout(VkDescriptorSetLayout descriptorLayout)
    {
        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 6 * sizeof(uint32_t)};
        VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        info.setLayoutCount = 1;
        info.pSetLayouts = &descriptorLayout;
        info.pushConstantRangeCount = 1;
        info.pPushConstantRanges = &range;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        require(vkCreatePipelineLayout(device_, &info, nullptr, &layout), "ISP viewfinder pipeline layout");
        return layout;
    }

    void prepareIspBufferPipelines()
    {
        ensureIspPool();
        if (!isp_.bufferLayout) isp_.bufferLayout = createIspLayout(nullptr);
        if (!isp_.bufferPipelineLayout) isp_.bufferPipelineLayout = createIspPipelineLayout(isp_.bufferLayout);
        if (!isp_.bufferPipeline) isp_.bufferPipeline = createComputeProgram(
            kIspPreviewBufferSpirv.data(), kIspPreviewBufferSpirv.size(), nullptr, isp_.bufferPipelineLayout);
        if (!isp_.bufferMeterPipeline) isp_.bufferMeterPipeline = createComputeProgram(
            kIspMeterBufferSpirv.data(), kIspMeterBufferSpirv.size(), nullptr, isp_.bufferPipelineLayout);
    }

    void prepareIspPipelines()
    {
        prepareIspBufferPipelines();
        if (!ispImageSupported_) return;
        // Compile the sampler conversion programs against the camera's usual YUV layout before the first frame.
        AHardwareBuffer_Desc description{};
        description.width = 64;
        description.height = 64;
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420;
        description.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
        AHardwareBuffer* probe = nullptr;
        if (AHardwareBuffer_allocate(&description, &probe) != 0 || probe == nullptr) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "IspViewfinder", "YUV probe allocation failed; conversion programs compile on first use");
            return;
        }
        try {
            VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{
                VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
            VkAndroidHardwareBufferPropertiesANDROID properties{
                VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
            properties.pNext = &formatProperties;
            require(getAhbProperties_(device_, probe, &properties), "vkGetAndroidHardwareBufferPropertiesANDROID for the YUV probe");
            ensureIspConversion(formatProperties);
        } catch (const std::exception& error) {
            recordNativeDiagnostic(ANDROID_LOG_WARN, "IspViewfinder", "YUV probe conversion unavailable: %s", error.what());
        }
        AHardwareBuffer_release(probe);
    }

    void destroyIspImageResources()
    {
        // Image views created with the old conversion go first; the caller has already waited for the device.
        for (auto& frame : frames_) releaseIspImage(frame);
        if (isp_.imagePipeline) vkDestroyPipeline(device_, isp_.imagePipeline, nullptr);
        if (isp_.imageMeterPipeline) vkDestroyPipeline(device_, isp_.imageMeterPipeline, nullptr);
        if (isp_.imagePipelineLayout) vkDestroyPipelineLayout(device_, isp_.imagePipelineLayout, nullptr);
        if (isp_.imageLayout) vkDestroyDescriptorSetLayout(device_, isp_.imageLayout, nullptr);
        if (isp_.sampler) vkDestroySampler(device_, isp_.sampler, nullptr);
        if (isp_.conversion) vkDestroySamplerYcbcrConversion(device_, isp_.conversion, nullptr);
        isp_.imagePipeline = VK_NULL_HANDLE;
        isp_.imageMeterPipeline = VK_NULL_HANDLE;
        isp_.imagePipelineLayout = VK_NULL_HANDLE;
        isp_.imageLayout = VK_NULL_HANDLE;
        isp_.sampler = VK_NULL_HANDLE;
        isp_.conversion = VK_NULL_HANDLE;
        isp_.hasConversion = false;
        // Sets of the old image layout are incompatible; the buffer sets are reallocated with them.
        if (isp_.pool) vkResetDescriptorPool(device_, isp_.pool, 0);
        for (auto& frame : frames_) {
            frame.ispImageDescriptor = VK_NULL_HANDLE;
            frame.ispBufferDescriptor = VK_NULL_HANDLE;
        }
    }

    void destroyIspResources()
    {
        destroyIspImageResources();
        if (isp_.bufferPipeline) vkDestroyPipeline(device_, isp_.bufferPipeline, nullptr);
        if (isp_.bufferMeterPipeline) vkDestroyPipeline(device_, isp_.bufferMeterPipeline, nullptr);
        if (isp_.bufferPipelineLayout) vkDestroyPipelineLayout(device_, isp_.bufferPipelineLayout, nullptr);
        if (isp_.bufferLayout) vkDestroyDescriptorSetLayout(device_, isp_.bufferLayout, nullptr);
        if (isp_.pool) vkDestroyDescriptorPool(device_, isp_.pool, nullptr);
        isp_ = {};
    }

    void ensureIspConversion(const VkAndroidHardwareBufferFormatPropertiesANDROID& properties)
    {
        const IspFormatKey key = ispFormatKey(properties);
        if (isp_.hasConversion && key == isp_.key) return;
        if ((properties.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0)
            ispImportFailure("buffer format cannot be sampled");
        require(vkDeviceWaitIdle(device_), "vkDeviceWaitIdle before replacing the ISP sampler conversion");
        destroyIspImageResources();
        ensureIspPool();
        VkExternalFormatANDROID external{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
        external.externalFormat = properties.externalFormat;
        VkSamplerYcbcrConversionCreateInfo info{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO};
        if (properties.format == VK_FORMAT_UNDEFINED) info.pNext = &external;
        info.format = properties.format;
        info.ycbcrRange = properties.suggestedYcbcrRange;
        info.components = properties.samplerYcbcrConversionComponents;
        info.xChromaOffset = properties.suggestedXChromaOffset;
        info.yChromaOffset = properties.suggestedYChromaOffset;
        info.chromaFilter = (properties.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT)
            ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        info.forceExplicitReconstruction = VK_FALSE;
        bool driverConverted = false;
#if !defined(NDEBUG)
        char modelOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.isp_driver_rgb", modelOverride);
        driverConverted = modelOverride[0] == '1';
#endif
        // The identity model returns the stored Y, Cb and Cr so the shader applies the camera's full-range matrix.
        info.ycbcrModel = driverConverted ? properties.suggestedYcbcrModel : VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY;
        VkResult created = vkCreateSamplerYcbcrConversion(device_, &info, nullptr, &isp_.conversion);
        if (created != VK_SUCCESS && !driverConverted) {
            driverConverted = true;
            info.ycbcrModel = properties.suggestedYcbcrModel;
            created = vkCreateSamplerYcbcrConversion(device_, &info, nullptr, &isp_.conversion);
        }
        require(created, "vkCreateSamplerYcbcrConversion");
        isp_.driverConverted = driverConverted;
        VkSamplerYcbcrConversionInfo conversionInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
        conversionInfo.conversion = isp_.conversion;
        const bool linear = (properties.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        const bool separate = (properties.formatFeatures &
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_SEPARATE_RECONSTRUCTION_FILTER_BIT) != 0;
        const VkFilter filter = separate ? (linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST) : info.chromaFilter;
        VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        samplerInfo.pNext = &conversionInfo;
        samplerInfo.magFilter = filter;
        samplerInfo.minFilter = filter;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxLod = 0.0f;
        require(vkCreateSampler(device_, &samplerInfo, nullptr, &isp_.sampler), "vkCreateSampler for the ISP viewfinder");
        isp_.imageLayout = createIspLayout(&isp_.sampler);
        isp_.imagePipelineLayout = createIspPipelineLayout(isp_.imageLayout);
        isp_.imagePipeline = createComputeProgram(
            kIspPreviewImageSpirv.data(), kIspPreviewImageSpirv.size(), nullptr, isp_.imagePipelineLayout);
        isp_.imageMeterPipeline = createComputeProgram(
            kIspMeterImageSpirv.data(), kIspMeterImageSpirv.size(), nullptr, isp_.imagePipelineLayout);
        isp_.key = key;
        isp_.hasConversion = true;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "IspViewfinder",
            "Sampler conversion ready: format=%d external=%llu model=%d range=%d features=0x%x chroma=%d/%d filter=%d shaderMatrix=%d",
            static_cast<int>(properties.format), static_cast<unsigned long long>(properties.externalFormat),
            static_cast<int>(info.ycbcrModel), static_cast<int>(info.ycbcrRange),
            static_cast<unsigned>(properties.formatFeatures), static_cast<int>(info.xChromaOffset),
            static_cast<int>(info.yChromaOffset), static_cast<int>(filter), driverConverted ? 0 : 1);
    }

    [[noreturn]] void ispImportFailure(const char* detail)
    {
        recordNativeDiagnostic(ANDROID_LOG_WARN, "IspViewfinder", "Hardware image import: %s", detail);
        throw std::runtime_error("ISP viewfinder image cannot be imported");
    }

    void releaseIspImage(Frame& frame)
    {
        if (frame.ispImageView != VK_NULL_HANDLE) vkDestroyImageView(device_, frame.ispImageView, nullptr);
        if (frame.ispImage != VK_NULL_HANDLE) vkDestroyImage(device_, frame.ispImage, nullptr);
        if (frame.ispImageMemory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.ispImageMemory, nullptr);
        if (frame.ispRetainedBuffer != nullptr) AHardwareBuffer_release(frame.ispRetainedBuffer);
        frame.ispImageView = VK_NULL_HANDLE;
        frame.ispImage = VK_NULL_HANDLE;
        frame.ispImageMemory = VK_NULL_HANDLE;
        frame.ispRetainedBuffer = nullptr;
        frame.ispImageInput = false;
    }

    void destroyIspPlanes(Frame& frame)
    {
        if (frame.ispPlanesMapped != nullptr) vkUnmapMemory(device_, frame.ispPlanesMemory);
        if (frame.ispPlanes != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.ispPlanes, nullptr);
        if (frame.ispPlanesMemory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.ispPlanesMemory, nullptr);
        frame.ispPlanes = VK_NULL_HANDLE;
        frame.ispPlanesMemory = VK_NULL_HANDLE;
        frame.ispPlanesMapped = nullptr;
        frame.ispPlanesCapacity = 0;
    }

    void ensureIspDescriptorSets(Frame& frame)
    {
        ensureIspPool();
        const auto allocate = [&](VkDescriptorSetLayout layout, VkDescriptorSet& set) {
            if (set != VK_NULL_HANDLE || layout == VK_NULL_HANDLE) return;
            VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            info.descriptorPool = isp_.pool;
            info.descriptorSetCount = 1;
            info.pSetLayouts = &layout;
            require(vkAllocateDescriptorSets(device_, &info, &set), "ISP viewfinder descriptor set");
        };
        allocate(isp_.bufferLayout, frame.ispBufferDescriptor);
        allocate(isp_.imageLayout, frame.ispImageDescriptor);
    }

    VkDescriptorSet& ispDescriptor(Frame& frame)
    {
        return frame.ispImageInput ? frame.ispImageDescriptor : frame.ispBufferDescriptor;
    }

    VkPipelineLayout ispPipelineLayout(const Frame& frame) const
    {
        return frame.ispImageInput ? isp_.imagePipelineLayout : isp_.bufferPipelineLayout;
    }

    void importIspImage(JNIEnv* environment, jobject hardwareBufferObject, Frame& frame)
    {
        AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(environment, hardwareBufferObject);
        if (hardwareBuffer == nullptr) ispImportFailure("HardwareBuffer is inaccessible");
        AHardwareBuffer_Desc description{};
        AHardwareBuffer_describe(hardwareBuffer, &description);
        if (description.layers != 1 || description.width == 0 || description.height == 0)
            ispImportFailure("HardwareBuffer is not a single two-dimensional image");
        VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        properties.pNext = &formatProperties;
        require(getAhbProperties_(device_, hardwareBuffer, &properties),
                "vkGetAndroidHardwareBufferPropertiesANDROID for the ISP viewfinder");
        if (properties.memoryTypeBits == 0)
            ispImportFailure("HardwareBuffer has no compatible Vulkan memory type");
        ensureIspConversion(formatProperties);
        ensureIspDescriptorSets(frame);

        VkExternalFormatANDROID externalFormat{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
        externalFormat.externalFormat = formatProperties.externalFormat;
        VkExternalMemoryImageCreateInfo externalCreate{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        externalCreate.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        if (formatProperties.format == VK_FORMAT_UNDEFINED) externalCreate.pNext = &externalFormat;
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.pNext = &externalCreate;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = formatProperties.format;
        imageInfo.extent = {description.width, description.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        require(vkCreateImage(device_, &imageInfo, nullptr, &frame.ispImage), "vkCreateImage for the ISP viewfinder");

        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.image = frame.ispImage;
        VkImportAndroidHardwareBufferInfoANDROID importInfo{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        importInfo.pNext = &dedicated;
        importInfo.buffer = hardwareBuffer;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.pNext = &importInfo;
        allocation.allocationSize = properties.allocationSize;
        allocation.memoryTypeIndex = memoryType(properties.memoryTypeBits, 0);
        require(vkAllocateMemory(device_, &allocation, nullptr, &frame.ispImageMemory), "vkAllocateMemory for the ISP viewfinder");
        require(vkBindImageMemory(device_, frame.ispImage, frame.ispImageMemory, 0), "vkBindImageMemory for the ISP viewfinder");

        VkSamplerYcbcrConversionInfo conversionInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
        conversionInfo.conversion = isp_.conversion;
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.pNext = &conversionInfo;
        viewInfo.image = frame.ispImage;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = formatProperties.format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        require(vkCreateImageView(device_, &viewInfo, nullptr, &frame.ispImageView), "vkCreateImageView for the ISP viewfinder");
        AHardwareBuffer_acquire(hardwareBuffer);
        frame.ispRetainedBuffer = hardwareBuffer;

        VkDescriptorImageInfo image{VK_NULL_HANDLE, frame.ispImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet = frame.ispImageDescriptor;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &image;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        frame.ispImageInput = true;
    }

    void uploadIspPlanes(JNIEnv* environment, jobject planesObject, const IspParameters& parameters, Frame& frame)
    {
        const auto* address = static_cast<const std::byte*>(environment->GetDirectBufferAddress(planesObject));
        const jlong capacity = environment->GetDirectBufferCapacity(planesObject);
        if (address == nullptr || capacity <= 0)
            throw std::runtime_error("ISP viewfinder frame is invalid");
        const int64_t width = parameters.source[0];
        const int64_t height = parameters.source[1];
        for (int index = 0; index < 3; ++index) {
            const int32_t* plane = parameters.planes[index];
            const int64_t planeWidth = index == 0 ? width : std::max<int64_t>(width / 2, 1);
            const int64_t planeHeight = index == 0 ? height : std::max<int64_t>(height / 2, 1);
            const int64_t end = int64_t(plane[0]) + (planeHeight - 1) * int64_t(plane[1]) + (planeWidth - 1) * int64_t(plane[2]) + 1;
            if (plane[0] < 0 || plane[1] <= 0 || plane[2] <= 0 || end > capacity)
                throw std::runtime_error("ISP viewfinder frame is invalid");
        }
        const VkDeviceSize bytes = (static_cast<VkDeviceSize>(capacity) + 3u) & ~VkDeviceSize{3u};
        if (bytes > physicalProperties_.limits.maxStorageBufferRange)
            throw std::runtime_error("ISP viewfinder frame is invalid");
        if (frame.ispPlanes == VK_NULL_HANDLE || frame.ispPlanesCapacity < bytes) {
            destroyIspPlanes(frame);
            frame.ispPlanesCoherent = (makeBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, frame.ispPlanes, frame.ispPlanesMemory,
                &frame.ispPlanesMapped) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            frame.ispPlanesCapacity = bytes;
        }
        std::memcpy(frame.ispPlanesMapped, address, static_cast<size_t>(capacity));
        if (bytes != static_cast<VkDeviceSize>(capacity)) {
            std::memset(static_cast<std::byte*>(frame.ispPlanesMapped) + capacity, 0, static_cast<size_t>(bytes - capacity));
        }
        if (!frame.ispPlanesCoherent) {
            VkMappedMemoryRange flush{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
            flush.memory = frame.ispPlanesMemory;
            flush.offset = 0;
            flush.size = VK_WHOLE_SIZE;
            require(vkFlushMappedMemoryRanges(device_, 1, &flush), "vkFlushMappedMemoryRanges for ISP viewfinder planes");
        }
    }

    void prepareIspInput(JNIEnv* environment, jobject hardwareBufferObject, jobject planesObject,
                         jobject parametersObject, Frame& frame)
    {
        void* address = environment->GetDirectBufferAddress(parametersObject);
        const jlong capacity = environment->GetDirectBufferCapacity(parametersObject);
        if (address == nullptr || capacity < static_cast<jlong>(kIspParametersSize))
            throw std::runtime_error("ISP viewfinder frame is invalid");
        IspParameters parameters{};
        std::memcpy(&parameters, address, sizeof(parameters));
        if (parameters.source[0] <= 0 || parameters.source[1] <= 0 || parameters.source[0] > 16384 || parameters.source[1] > 16384)
            throw std::runtime_error("ISP viewfinder frame is invalid");
        if (!std::isfinite(parameters.exposure[0]) || parameters.exposure[0] <= 0.0f)
            throw std::runtime_error("ISP viewfinder frame is invalid");
        for (float value : parameters.curve) {
            if (!std::isfinite(value) || value < 0.0f) throw std::runtime_error("ISP viewfinder frame is invalid");
        }
        if (frame.ispUniform == VK_NULL_HANDLE) {
            makeBuffer(kIspParametersSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                frame.ispUniform, frame.ispUniformMemory, &frame.ispMapped);
        }
        bool tryImage = hardwareBufferObject != nullptr && ispImageSupported_ && !isp_.imageRejected;
#if !defined(NDEBUG)
        char inputOverride[PROP_VALUE_MAX]{};
        __system_property_get("debug.unspektra.isp_input", inputOverride);
        if (inputOverride[0] == '0') tryImage = false;
#endif
        frame.ispImageInput = false;
        if (tryImage) {
            try {
                importIspImage(environment, hardwareBufferObject, frame);
            } catch (const std::exception& error) {
                releaseIspImage(frame);
                if (planesObject == nullptr) throw;
                isp_.imageRejected = true;
                recordNativeDiagnostic(ANDROID_LOG_WARN, "IspViewfinder",
                    "Hardware image import rejected, copying planes instead: %s", error.what());
            }
        }
        if (!frame.ispImageInput) {
            if (planesObject == nullptr)
                throw std::runtime_error("ISP viewfinder frame has no usable image data");
            prepareIspBufferPipelines();
            ensureIspDescriptorSets(frame);
            uploadIspPlanes(environment, planesObject, parameters, frame);
        } else {
            if (isp_.driverConverted) parameters.source[2] |= kIspDriverRgb;
            if ((parameters.source[2] & kIspRangeFromBuffer) != 0) {
                if (isp_.key.range == VK_SAMPLER_YCBCR_RANGE_ITU_FULL) parameters.source[2] |= kIspFullRange;
                else parameters.source[2] &= ~kIspFullRange;
            }
        }
        parameters.source[3] = frame.ispImageInput ? 1 : 0;
        std::memcpy(frame.ispMapped, &parameters, sizeof(parameters));
        if (!isp_.reportedInput) {
            recordNativeDiagnostic(ANDROID_LOG_INFO, "IspViewfinder", "Live ISP input: %s %dx%d flags=%d",
                frame.ispImageInput ? "hardware image" : "copied planes", parameters.source[0], parameters.source[1],
                parameters.source[2]);
            isp_.reportedInput = true;
        }
    }

    void updateIspDescriptors(Frame& frame)
    {
        ensureIspDescriptorSets(frame);
        VkDescriptorSet set = ispDescriptor(frame);
        if (set == VK_NULL_HANDLE) ispImportFailure("descriptor sets are unavailable");
        const VkImageView output = frame.shadowStrength > 0.001f ? frame.shadows.source.view : frame.processingView;
        VkDescriptorBufferInfo planes{frame.ispPlanes, 0, VK_WHOLE_SIZE};
        VkDescriptorBufferInfo parameters{frame.ispUniform, 0, kIspParametersSize};
        VkDescriptorImageInfo storage{VK_NULL_HANDLE, output, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorBufferInfo color{frame.spektraUniform, 0, kRawColorParametersSize};
        VkDescriptorBufferInfo geometry{frame.geometryUniform, 0, kGeometryParametersSize};
        VkDescriptorBufferInfo meter{frame.meterPixels, 0, kMeterPixelsSize};
        std::array<VkWriteDescriptorSet, 6> writes{};
        uint32_t count = 0;
        const auto add = [&](uint32_t binding, VkDescriptorType type, const VkDescriptorBufferInfo* buffer,
                             const VkDescriptorImageInfo* image) {
            auto& write = writes[count++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = set;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = type;
            write.pBufferInfo = buffer;
            write.pImageInfo = image;
        };
        if (!frame.ispImageInput) add(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &planes, nullptr);
        add(1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &parameters, nullptr);
        add(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, nullptr, &storage);
        add(3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &color, nullptr);
        add(6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &geometry, nullptr);
        if (frame.meterPixels != VK_NULL_HANDLE) add(11, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &meter, nullptr);
        vkUpdateDescriptorSets(device_, count, writes.data(), 0, nullptr);
    }

    // The shadow pass copies shared bindings from the compute set, so every binding must reference a live resource.
    void updateIspComputeDescriptors(Frame& frame)
    {
        const VkImageView output = frame.shadowStrength > 0.001f ? frame.shadows.source.view : frame.processingView;
        VkDescriptorBufferInfo placeholder{frame.lensShadingBuffer, 0, frame.lensShadingCapacity};
        VkDescriptorBufferInfo rawUniform{frame.rawUniform, 0, kRawParametersSize};
        VkDescriptorImageInfo storage{VK_NULL_HANDLE, output, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorBufferInfo color{frame.spektraUniform, 0, kRawColorParametersSize};
        VkDescriptorBufferInfo geometry{frame.geometryUniform, 0, kGeometryParametersSize};
        VkDescriptorImageInfo integerTexture{rawIntegerSampler_, previewFallbackTexture_.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorImageInfo linearTexture{sampler_, previewFallbackTexture_.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        std::array<VkWriteDescriptorSet, 9> writes{};
        uint32_t count = 0;
        const auto add = [&](uint32_t binding, VkDescriptorType type, const VkDescriptorBufferInfo* buffer,
                             const VkDescriptorImageInfo* image) {
            auto& write = writes[count++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = frame.computeDescriptor;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = type;
            write.pBufferInfo = buffer;
            write.pImageInfo = image;
        };
        add(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &placeholder, nullptr);
        add(1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &rawUniform, nullptr);
        add(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, nullptr, &storage);
        add(3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &color, nullptr);
        add(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &placeholder, nullptr);
        add(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nullptr, &integerTexture);
        add(6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &geometry, nullptr);
        add(8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nullptr, &linearTexture);
        add(10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nullptr, &integerTexture);
        vkUpdateDescriptorSets(device_, count, writes.data(), 0, nullptr);
    }

    void acquireIspForCompute(VkCommandBuffer commandBuffer, const Frame& frame)
    {
        if (frame.ispImageInput) {
            VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            barrier.dstQueueFamilyIndex = queueFamily_;
            barrier.image = frame.ispImage;
            barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barrier.subresourceRange.levelCount = 1;
            barrier.subresourceRange.layerCount = 1;
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &barrier);
            return;
        }
        VkBufferMemoryBarrier barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = frame.ispPlanes;
        barrier.offset = 0;
        barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 1, &barrier, 0, nullptr);
    }

    void releaseIspFromCompute(VkCommandBuffer commandBuffer, const Frame& frame)
    {
        if (!frame.ispImageInput) return;
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.dstAccessMask = 0;
        barrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = queueFamily_;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        barrier.image = frame.ispImage;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    void destroyRawImageProbe()
    {
        resetRawImageValidation();
        auto& probe = rawImageProbe_;
        if (probe.command) vkFreeCommandBuffers(device_, commandPool_, 1, &probe.command);
        if (probe.pipeline) vkDestroyPipeline(device_, probe.pipeline, nullptr);
        if (probe.pool) vkDestroyDescriptorPool(device_, probe.pool, nullptr);
        if (probe.pipelineLayout) vkDestroyPipelineLayout(device_, probe.pipelineLayout, nullptr);
        if (probe.descriptorLayout) vkDestroyDescriptorSetLayout(device_, probe.descriptorLayout, nullptr);
        if (probe.fence) vkDestroyFence(device_, probe.fence, nullptr);
        if (probe.mapped) vkUnmapMemory(device_, probe.memory);
        if (probe.buffer) vkDestroyBuffer(device_, probe.buffer, nullptr);
        if (probe.memory) vkFreeMemory(device_, probe.memory, nullptr);
        probe = {};
    }

    void importRawBuffer(
        JNIEnv* environment,
        jobject object,
        jobject packedRawBytesObject,
        const RawParameters& parameters,
        Frame& frame,
        RawUploadPool::Lease* rawUpload = nullptr,
        AHardwareBuffer* preparedCells = nullptr)
    {
        if (preparedCells && frame.bayerCells && frame.retainedHardwareBuffer == preparedCells)
            return;
        releaseImportedBuffer(frame);
        if (packedRawBytesObject != nullptr || rawUpload != nullptr) {
            void* packedAddress = packedRawBytesObject == nullptr
                ? nullptr
                : environment->GetDirectBufferAddress(packedRawBytesObject);
            jlong packedCapacity = packedRawBytesObject == nullptr
                ? -1
                : environment->GetDirectBufferCapacity(packedRawBytesObject);
            if (rawUpload) packedCapacity = static_cast<jlong>(rawUpload->size());
            uint64_t fullRowsBytes =
                static_cast<uint64_t>(parameters.layoutInfo[0]) *
                static_cast<uint64_t>(parameters.dimensions[1]);
            uint64_t finalRowBytes;
            if (parameters.layoutInfo[1] == 1)
                finalRowBytes =
                    ((static_cast<uint64_t>(parameters.dimensions[0]) + 3u) / 4u) * 5u;
            else if (parameters.layoutInfo[1] == 2)
                finalRowBytes =
                    ((static_cast<uint64_t>(parameters.dimensions[0]) + 1u) / 2u) * 3u;
            else
                finalRowBytes = static_cast<uint64_t>(parameters.dimensions[0]) * 2u;
            uint64_t minimumBytes =
                static_cast<uint64_t>(parameters.layoutInfo[0]) *
                    static_cast<uint64_t>(parameters.dimensions[1] - 1) +
                finalRowBytes;
            if (fullRowsBytes == 0 || fullRowsBytes >
                    static_cast<uint64_t>(std::numeric_limits<jlong>::max()) ||
                (packedAddress == nullptr && rawUpload == nullptr) ||
                packedCapacity < static_cast<jlong>(minimumBytes)) {
                throw std::runtime_error(
                    "RAW plane must be a direct ByteBuffer containing its row-stride layout");
            }
            uint64_t alignedBytes = (fullRowsBytes + 3u) & ~uint64_t{3u};
            if (alignedBytes > physicalProperties_.limits.maxStorageBufferRange)
                throw std::runtime_error(
                    "Packed RAW plane exceeds the Vulkan storage buffer range");

            if (rawUpload) {
                frame.rawUpload = rawUploads_->acquire(*rawUpload, device_, minimumBytes, alignedBytes);
                if (!frame.rawUpload) throw std::runtime_error(
                    "RAW plane must be a direct ByteBuffer containing its row-stride layout");
                if (frame.rawUploadGeneration != frame.rawUpload->generation()) {
                    frame.computeDescriptorsDirty = true;
                    frame.rawUploadGeneration = frame.rawUpload->generation();
                }
                if (frame.packedRawBuffer != VK_NULL_HANDLE) destroyPackedRawBuffer(frame);
                frame.rawImportKind = RawImportKind::Buffer;
                return;
            }

            ensurePackedRawBuffer(
                frame, static_cast<VkDeviceSize>(alignedBytes));
            frame.rawUploadGeneration = 0;
            uint64_t copiedBytes = std::min(
                fullRowsBytes, static_cast<uint64_t>(packedCapacity));
            std::memcpy(frame.packedRawMapped, packedAddress,
                        static_cast<size_t>(copiedBytes));
            if (alignedBytes != copiedBytes) {
                std::memset(
                    static_cast<std::byte*>(frame.packedRawMapped) + copiedBytes,
                    0, static_cast<size_t>(alignedBytes - copiedBytes));
            }
            if (!frame.packedRawCoherent) {
                VkMappedMemoryRange flush{
                    VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
                flush.memory = frame.packedRawMemory;
                flush.offset = 0;
                flush.size = VK_WHOLE_SIZE;
                require(vkFlushMappedMemoryRanges(device_, 1, &flush),
                        "vkFlushMappedMemoryRanges for packed RAW upload");
            }
            frame.rawImportKind = RawImportKind::Buffer;
            return;
        }

        AHardwareBuffer* hardwareBuffer = preparedCells ? preparedCells :
            AHardwareBuffer_fromHardwareBuffer(environment, object);
        if (hardwareBuffer == nullptr)
            throw std::runtime_error("Unable to access RAW HardwareBuffer");
        AHardwareBuffer_Desc description{};
        AHardwareBuffer_describe(hardwareBuffer, &description);
        VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        properties.pNext = &formatProperties;
        require(getAhbProperties_(device_, hardwareBuffer, &properties),
                "vkGetAndroidHardwareBufferPropertiesANDROID");

        if (description.format != AHARDWAREBUFFER_FORMAT_BLOB) {
            if (description.height <= 1 || description.layers != 1 || (!preparedCells && (
                description.width !=
                    static_cast<uint32_t>(parameters.dimensions[0]) ||
                description.height !=
                    static_cast<uint32_t>(parameters.dimensions[1])))) {
                throw std::runtime_error(
                    "RAW HardwareBuffer image dimensions do not match Camera2 metadata: format=" +
                    std::to_string(description.format) + " width=" +
                    std::to_string(description.width) + " height=" +
                    std::to_string(description.height) + " layers=" +
                    std::to_string(description.layers));
            }
            const VkFormat expectedFormat = preparedCells ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R16_UINT;
            if (formatProperties.format != expectedFormat) {
                throw std::runtime_error(
                    "RAW_SENSOR HardwareBuffer does not expose VK_FORMAT_R16_UINT: ahbFormat=" +
                    std::to_string(description.format) + " vkFormat=" +
                    std::to_string(formatProperties.format) + " externalFormat=" +
                    std::to_string(formatProperties.externalFormat));
            }
            VkFormatFeatureFlags requiredFeatures =
                VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            frame.cellLinearSupported = preparedCells &&
                (formatProperties.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
            if ((formatProperties.formatFeatures & requiredFeatures) !=
                requiredFeatures) {
                throw std::runtime_error(
                    "RAW_SENSOR R16_UINT HardwareBuffer lacks sampled Vulkan image support: formatFeatures=" +
                    std::to_string(formatProperties.formatFeatures));
            }

            VkExternalMemoryImageCreateInfo externalCreate{
                VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
            externalCreate.handleTypes =
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
            VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.pNext = &externalCreate;
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = expectedFormat;
            imageInfo.extent = {description.width, description.height, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            require(vkCreateImage(
                        device_, &imageInfo, nullptr, &frame.importedImage),
                    "vkCreateImage for RAW_SENSOR HardwareBuffer");

            // AHardwareBuffer images obtain allocation requirements from the buffer.
            uint32_t typeBits = properties.memoryTypeBits;
            if (typeBits == 0) {
                throw std::runtime_error(
                    "RAW_SENSOR HardwareBuffer image has no compatible Vulkan memory type");
            }

            VkMemoryDedicatedAllocateInfo dedicated{
                VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
            dedicated.image = frame.importedImage;
            VkImportAndroidHardwareBufferInfoANDROID importInfo{
                VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
            importInfo.pNext = &dedicated;
            importInfo.buffer = hardwareBuffer;
            VkMemoryAllocateInfo allocation{
                VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocation.pNext = &importInfo;
            allocation.allocationSize = properties.allocationSize;
            allocation.memoryTypeIndex = memoryType(typeBits, 0);
            require(vkAllocateMemory(
                        device_, &allocation, nullptr, &frame.importedMemory),
                    "vkAllocateMemory for RAW_SENSOR HardwareBuffer image");
            require(vkBindImageMemory(
                        device_, frame.importedImage, frame.importedMemory, 0),
                    "vkBindImageMemory for RAW_SENSOR HardwareBuffer");
            frame.importedImageView = createImageView(
                frame.importedImage, expectedFormat);
            frame.computeDescriptorsDirty = true;
            AHardwareBuffer_acquire(hardwareBuffer);
            frame.retainedHardwareBuffer = hardwareBuffer;
            frame.rawImportKind = RawImportKind::Image;
            return;
        }

        throw std::runtime_error(
            "RAW_SENSOR HardwareBuffer is not a two-dimensional R16_UINT image");
    }

    void releaseImportedBuffer(Frame& frame)
    {
        frame.bayerCells = false;
        frame.cellLinearSupported = false;
        frame.rawUpload.reset();
        if (frame.importedImageView != VK_NULL_HANDLE) {
            frame.computeDescriptorsDirty = true;
            vkDestroyImageView(device_, frame.importedImageView, nullptr);
            frame.importedImageView = VK_NULL_HANDLE;
        }
        if (frame.importedImage != VK_NULL_HANDLE) {
            vkDestroyImage(device_, frame.importedImage, nullptr);
            frame.importedImage = VK_NULL_HANDLE;
        }
        if (frame.importedMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, frame.importedMemory, nullptr);
            frame.importedMemory = VK_NULL_HANDLE;
        }
        if (frame.retainedHardwareBuffer != nullptr) {
            AHardwareBuffer_release(frame.retainedHardwareBuffer);
            frame.retainedHardwareBuffer = nullptr;
        }
        frame.rawImportKind = RawImportKind::None;
    }

    void ensureSpektraOutput(Frame& frame)
    {
        if (frame.spektraImage != VK_NULL_HANDLE) return;
        PreviewTexture texture;
        try {
            ensurePreviewTexture(texture, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        } catch (...) {
            destroyPreviewTexture(texture);
            throw;
        }
        frame.spektraImage = texture.image;
        frame.spektraMemory = texture.memory;
        frame.spektraView = texture.view;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf", "Spektra output allocated: %ux%u",
            processingWidth_, processingHeight_);
    }

    void createProcessingImages(uint32_t width, uint32_t height)
    {
        destroyProcessingImages();
        processingWidth_ = width;
        processingHeight_ = height;
        for (Frame& frame : frames_) {
            VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
            imageInfo.extent = {width, height, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage =
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            if (rawRenderPass_ != VK_NULL_HANDLE)
                imageInfo.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            require(vkCreateImage(
                        device_, &imageInfo, nullptr, &frame.processingImage),
                    "vkCreateImage");
            VkMemoryRequirements requirements{};
            vkGetImageMemoryRequirements(
                device_, frame.processingImage, &requirements);
            VkMemoryAllocateInfo allocation{
                VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocation.allocationSize = requirements.size;
            allocation.memoryTypeIndex = memoryType(
                requirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            require(vkAllocateMemory(
                        device_, &allocation, nullptr, &frame.processingMemory),
                    "vkAllocateMemory");
            require(vkBindImageMemory(
                        device_, frame.processingImage,
                        frame.processingMemory, 0),
                    "vkBindImageMemory");
            frame.processingView = createImageView(
                frame.processingImage, VK_FORMAT_R16G16B16A16_SFLOAT);
            if (rawRenderPass_ != VK_NULL_HANDLE) {
                VkPhysicalDeviceProperties properties{};
                vkGetPhysicalDeviceProperties(physicalDevice_, &properties);
                if (width <= properties.limits.maxFramebufferWidth && height <= properties.limits.maxFramebufferHeight) {
                    VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
                    info.renderPass = rawRenderPass_;
                    info.attachmentCount = 1;
                    info.pAttachments = &frame.processingView;
                    info.width = width;
                    info.height = height;
                    info.layers = 1;
                    if (vkCreateFramebuffer(device_, &info, nullptr, &frame.processingFramebuffer) != VK_SUCCESS)
                        frame.processingFramebuffer = VK_NULL_HANDLE;
                }
            }
            frame.processingInitialized = false;
            frame.spektraLayoutInitialized = false;
            frame.spektraInitialized = false;
        }
    }

    void ensureCaptureResources(uint32_t width, uint32_t height, bool halfFloatOutput)
    {
        if (captureImage_ != VK_NULL_HANDLE && captureWidth_ == width &&
            captureHeight_ == height && captureHalfFloat_ == halfFloatOutput)
            return;
        destroyCaptureResources();
        const VkFormat readbackFormat = halfFloatOutput ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM;

        VkPhysicalDeviceProperties deviceProperties{};
        vkGetPhysicalDeviceProperties(physicalDevice_, &deviceProperties);
        if (width > deviceProperties.limits.maxImageDimension2D ||
            height > deviceProperties.limits.maxImageDimension2D) {
            throw std::runtime_error(
                "Full-resolution capture exceeds the Vulkan image dimension limit");
        }
        VkFormatProperties formatProperties{};
        vkGetPhysicalDeviceFormatProperties(
            physicalDevice_, VK_FORMAT_R16G16B16A16_SFLOAT, &formatProperties);
        VkFormatFeatureFlags required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                        VK_FORMAT_FEATURE_TRANSFER_SRC_BIT |
                                        VK_FORMAT_FEATURE_BLIT_SRC_BIT;
        if ((formatProperties.optimalTilingFeatures & required) != required) {
            throw std::runtime_error(
                "Device cannot store and read back an RGBA16F capture image");
        }
        VkFormatProperties readbackFormatProperties{};
        vkGetPhysicalDeviceFormatProperties(
            physicalDevice_, readbackFormat,
            &readbackFormatProperties);
        const VkFormatFeatureFlags readbackRequired =
            VK_FORMAT_FEATURE_BLIT_DST_BIT |
            VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
        if ((readbackFormatProperties.optimalTilingFeatures &
             readbackRequired) != readbackRequired) {
            throw std::runtime_error(
                "Device cannot convert RGBA16F capture output to RGBA8");
        }

        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage =
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        require(vkCreateImage(device_, &imageInfo, nullptr, &captureImage_),
                "vkCreateImage for AMAZE capture");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device_, captureImage_, &requirements);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType(
            requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(
                    device_, &allocation, nullptr, &captureMemory_),
                "vkAllocateMemory for AMAZE capture");
        require(vkBindImageMemory(device_, captureImage_, captureMemory_, 0),
                "vkBindImageMemory for AMAZE capture");
        captureView_ = createImageView(
            captureImage_, VK_FORMAT_R16G16B16A16_SFLOAT);
        require(vkCreateImage(
                    device_, &imageInfo, nullptr, &captureSpektraImage_),
                "vkCreateImage for SpektraFilm capture output");
        vkGetImageMemoryRequirements(
            device_, captureSpektraImage_, &requirements);
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType(
            requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(
                    device_, &allocation, nullptr, &captureSpektraMemory_),
                "vkAllocateMemory for SpektraFilm capture output");
        require(vkBindImageMemory(
                    device_, captureSpektraImage_, captureSpektraMemory_, 0),
                "vkBindImageMemory for SpektraFilm capture output");
        captureSpektraView_ = createImageView(
            captureSpektraImage_, VK_FORMAT_R16G16B16A16_SFLOAT);

        VkImageCreateInfo readbackImageInfo = imageInfo;
        readbackImageInfo.format = readbackFormat;
        readbackImageInfo.usage =
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        require(vkCreateImage(
                    device_, &readbackImageInfo, nullptr,
                    &captureReadbackImage_),
                "vkCreateImage for capture RGBA8 conversion");
        vkGetImageMemoryRequirements(
            device_, captureReadbackImage_, &requirements);
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType(
            requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(
                    device_, &allocation, nullptr, &captureReadbackMemory_),
                "vkAllocateMemory for capture RGBA8 conversion");
        require(vkBindImageMemory(
                    device_, captureReadbackImage_, captureReadbackMemory_, 0),
                "vkBindImageMemory for capture RGBA8 conversion");

        VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) *
                                   static_cast<VkDeviceSize>(height) * (halfFloatOutput ? 8u : 4u);
        makeBuffer(stagingSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                   captureStaging_, captureStagingMemory_,
                   &captureStagingMapped_);
        captureWidth_ = width;
        captureHeight_ = height;
        captureHalfFloat_ = halfFloatOutput;
        captureInitialized_ = false;
        captureSpektraLayoutInitialized_ = false;
        captureReadbackInitialized_ = false;
    }

    void destroyCaptureResources()
    {
        destroyShadowResources(captureFrame_);
        destroyChromaDenoiseResources();
        if (captureStagingMapped_ != nullptr)
            vkUnmapMemory(device_, captureStagingMemory_);
        if (captureStaging_ != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, captureStaging_, nullptr);
        if (captureStagingMemory_ != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureStagingMemory_, nullptr);
        if (captureView_ != VK_NULL_HANDLE)
            vkDestroyImageView(device_, captureView_, nullptr);
        if (captureSpektraView_ != VK_NULL_HANDLE)
            vkDestroyImageView(device_, captureSpektraView_, nullptr);
        if (captureImage_ != VK_NULL_HANDLE)
            vkDestroyImage(device_, captureImage_, nullptr);
        if (captureSpektraImage_ != VK_NULL_HANDLE)
            vkDestroyImage(device_, captureSpektraImage_, nullptr);
        if (captureReadbackImage_ != VK_NULL_HANDLE)
            vkDestroyImage(device_, captureReadbackImage_, nullptr);
        if (captureMemory_ != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureMemory_, nullptr);
        if (captureSpektraMemory_ != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureSpektraMemory_, nullptr);
        if (captureReadbackMemory_ != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureReadbackMemory_, nullptr);
        captureStagingMapped_ = nullptr;
        captureStaging_ = VK_NULL_HANDLE;
        captureStagingMemory_ = VK_NULL_HANDLE;
        captureView_ = VK_NULL_HANDLE;
        captureImage_ = VK_NULL_HANDLE;
        captureMemory_ = VK_NULL_HANDLE;
        captureSpektraView_ = VK_NULL_HANDLE;
        captureSpektraImage_ = VK_NULL_HANDLE;
        captureSpektraMemory_ = VK_NULL_HANDLE;
        captureReadbackImage_ = VK_NULL_HANDLE;
        captureReadbackMemory_ = VK_NULL_HANDLE;
        captureWidth_ = 0;
        captureHeight_ = 0;
        captureInitialized_ = false;
        captureSpektraLayoutInitialized_ = false;
        captureReadbackInitialized_ = false;
    }

    void releaseCaptureWorkingResources()
    {
        if (spektraRenderer_) spektraRenderer_->releaseTransientResources();
        destroyAmazeResources();
        destroyPackedRawBuffer(captureFrame_);
        destroyRawBufferResources(captureFrame_.remosaic);
        destroyRawBufferResources(captureFrame_.hotPixels);
        destroyCaptureResources();
        recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline",
            "renderer=%p stage=buffers_released", static_cast<void*>(this));
    }

    VkImageView createImageView(VkImage image, VkFormat format) const
    {
        VkImageViewCreateInfo viewInfo{
            VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        VkImageView view = VK_NULL_HANDLE;
        require(vkCreateImageView(device_, &viewInfo, nullptr, &view),
                "vkCreateImageView");
        return view;
    }

    void createAmazeImage(
        uint32_t width,
        uint32_t height,
        VkFormat format,
        VkImage& image,
        VkDeviceMemory& memory,
        VkImageView& view,
        VkImageUsageFlags extraUsage = 0)
    {
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage =
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | extraUsage;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        require(vkCreateImage(device_, &imageInfo, nullptr, &image),
                "vkCreateImage for staged AMAZE");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device_, image, &requirements);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType(
            requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(device_, &allocation, nullptr, &memory),
                "vkAllocateMemory for staged AMAZE");
        require(vkBindImageMemory(device_, image, memory, 0),
                "vkBindImageMemory for staged AMAZE");
        view = createImageView(image, format);
    }

    void ensureAmazeResources(uint32_t width, uint32_t height)
    {
        if (amazeWidth_ == width && amazeHeight_ == height &&
            amazeDemosaicImage_ != VK_NULL_HANDLE)
            return;
        destroyAmazeResources();

        try {
            const uint32_t tileWidth = std::min(width, kAmazeTileCore) + kAmazeTileHalo * 2;
            const uint32_t tileHeight = std::min(height, kAmazeTileCore) + kAmazeTileHalo * 2;
            for (auto& image : amazeScratch_)
                createAmazeImage(tileWidth, tileHeight, VK_FORMAT_R16G16B16A16_SFLOAT,
                                 image.image, image.memory, image.view);
            createAmazeImage(width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
                             amazeDemosaicImage_, amazeDemosaicMemory_, amazeDemosaicView_);
        } catch (...) {
            destroyAmazeResources();
            throw;
        }
        amazeWidth_ = width;
        amazeHeight_ = height;
        amazeLayoutsInitialized_ = false;
    }

    void ensureHighlightRecoveryResources()
    {
        if (hrReduceBuffer_ == VK_NULL_HANDLE) {
            makeBuffer(24, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, hrReduceBuffer_, hrReduceMemory_, nullptr);
        }
        if (!captureHighlightRecovery_ || hrTmpImage_ != VK_NULL_HANDLE) return;
        try {
            createAmazeImage(amazeWidth_, amazeHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
                           hrTmpImage_, hrTmpMemory_, hrTmpView_);
            createAmazeImage(amazeWidth_, amazeHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
                           hrMaskImage_, hrMaskMemory_, hrMaskView_);
        } catch (...) {
            destroyAmazeResources();
            throw;
        }
        hrLayoutsInitialized_ = false;
    }

    void destroyAmazeResources()
    {
        if (hrReduceBuffer_ != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, hrReduceBuffer_, nullptr);
        if (hrReduceMemory_ != VK_NULL_HANDLE)
            vkFreeMemory(device_, hrReduceMemory_, nullptr);
        hrReduceBuffer_ = VK_NULL_HANDLE;
        hrReduceMemory_ = VK_NULL_HANDLE;
        for (VkImageView view : {
                 amazeDemosaicView_, hrTmpView_, hrMaskView_}) {
            if (view != VK_NULL_HANDLE)
                vkDestroyImageView(device_, view, nullptr);
        }
        for (VkImage image : {
                 amazeDemosaicImage_, hrTmpImage_, hrMaskImage_}) {
            if (image != VK_NULL_HANDLE)
                vkDestroyImage(device_, image, nullptr);
        }
        for (VkDeviceMemory memory : {
                 amazeDemosaicMemory_, hrTmpMemory_, hrMaskMemory_}) {
            if (memory != VK_NULL_HANDLE)
                vkFreeMemory(device_, memory, nullptr);
        }
        for (auto& image : amazeScratch_) {
            if (image.view) vkDestroyImageView(device_, image.view, nullptr);
            if (image.image) vkDestroyImage(device_, image.image, nullptr);
            if (image.memory) vkFreeMemory(device_, image.memory, nullptr);
            image = {};
        }
        amazeDemosaicImage_ = VK_NULL_HANDLE;
        amazeDemosaicMemory_ = VK_NULL_HANDLE;
        amazeDemosaicView_ = VK_NULL_HANDLE;
        hrTmpView_ = hrMaskView_ = VK_NULL_HANDLE;
        hrTmpImage_ = hrMaskImage_ = VK_NULL_HANDLE;
        hrTmpMemory_ = hrMaskMemory_ = VK_NULL_HANDLE;
        hrLayoutsInitialized_ = false;
        amazeWidth_ = 0;
        amazeHeight_ = 0;
        amazeLayoutsInitialized_ = false;
    }

    void updateAmazeDescriptorSet(
        Frame& frame,
        AmazeStage stage,
        VkImageView output,
        VkImageView source0,
        VkImageView source1,
        VkImageView source2)
    {
        VkDescriptorBufferInfo rawBuffer{
            processedRawBuffer(frame), 0, VK_WHOLE_SIZE};
        VkDescriptorImageInfo rawImage{
            rawIntegerSampler_, frame.importedImageView,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorBufferInfo rawUniform{
            frame.rawUniform, 0, kRawParametersSize};
        VkDescriptorImageInfo outputImage{
            VK_NULL_HANDLE, output, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo source0Image{
            rawIntegerSampler_, source0, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo source1Image{
            rawIntegerSampler_, source1, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo source2Image{
            rawIntegerSampler_, source2, VK_IMAGE_LAYOUT_GENERAL};

        VkDescriptorBufferInfo colorUniform{frame.spektraUniform, 0, kRawColorParametersSize};
        VkDescriptorBufferInfo shadingBuffer{frame.lensShadingBuffer, 0, frame.lensShadingCapacity};
        VkDescriptorBufferInfo geometryUniform{frame.geometryUniform, 0, kGeometryParametersSize};
        VkDescriptorImageInfo hrMaskStorage{VK_NULL_HANDLE, hrMaskView_, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo hrMaskSampled{rawIntegerSampler_, hrMaskView_, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorBufferInfo hrReduce{hrReduceBuffer_, 0, 24};
        std::array<VkWriteDescriptorSet, 12> writes{};
        uint32_t writeCount = 0;
        auto& rawWrite = writes[writeCount++];
        rawWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        rawWrite.dstSet = amazeDescriptorSets_[stage];
        rawWrite.dstBinding =
            processedRawKind(frame) == RawImportKind::Image ? 5 : 0;
        rawWrite.descriptorCount = 1;
        rawWrite.descriptorType =
            processedRawKind(frame) == RawImportKind::Image
                ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        if (processedRawKind(frame) == RawImportKind::Image)
            rawWrite.pImageInfo = &rawImage;
        else
            rawWrite.pBufferInfo = &rawBuffer;

        auto& uniformWrite = writes[writeCount++];
        uniformWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        uniformWrite.dstSet = amazeDescriptorSets_[stage];
        uniformWrite.dstBinding = 1;
        uniformWrite.descriptorCount = 1;
        uniformWrite.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        uniformWrite.pBufferInfo = &rawUniform;

        auto& outputWrite = writes[writeCount++];
        outputWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        outputWrite.dstSet = amazeDescriptorSets_[stage];
        outputWrite.dstBinding = 2;
        outputWrite.descriptorCount = 1;
        outputWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        outputWrite.pImageInfo = &outputImage;

        auto addSampled = [&](uint32_t binding, VkDescriptorImageInfo* info) {
            auto& write = writes[writeCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = amazeDescriptorSets_[stage];
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            write.pImageInfo = info;
        };
        VkDescriptorImageInfo normalizedImage{rawIntegerSampler_, amazeScratch_[7].view, VK_IMAGE_LAYOUT_GENERAL};
        if (stage > AmazeNormalize && stage <= AmazeOutput) addSampled(13, &normalizedImage);
        if (source0 != VK_NULL_HANDLE)
            addSampled(6, &source0Image);
        if (source1 != VK_NULL_HANDLE)
            addSampled(7, &source1Image);
        if (source2 != VK_NULL_HANDLE)
            addSampled(8, &source2Image);
        auto addBuffer = [&](uint32_t binding, VkDescriptorType type, VkDescriptorBufferInfo* info) {
            auto& write = writes[writeCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = amazeDescriptorSets_[stage];
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = type;
            write.pBufferInfo = info;
        };
        addBuffer(3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &colorUniform);
        addBuffer(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &shadingBuffer);
        addBuffer(9, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &geometryUniform);
        if (stage == AmazeRecovery || stage == AmazeHrReduce)
            addBuffer(11, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &hrReduce);
        if (stage == AmazeHrReduce)
            addSampled(12, &hrMaskSampled);
        if (stage == AmazeHrSeed) {
            auto& write = writes[writeCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = amazeDescriptorSets_[stage];
            write.dstBinding = 10;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            write.pImageInfo = &hrMaskStorage;
        }
        vkUpdateDescriptorSets(device_, writeCount, writes.data(), 0, nullptr);
    }

    VkImageView captureDemosaicView() const {
        return captureHighlightRecovery_ ? hrMaskView_ : amazeDemosaicView_;
    }

    void updateAmazeDescriptors(Frame& frame)
    {
        updateAmazeDescriptorSet(frame, AmazeNormalize, amazeScratch_[7].view, VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeCandidates, amazeScratch_[0].view,
            VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeDifferences, amazeScratch_[1].view,
            amazeScratch_[0].view, VK_NULL_HANDLE, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeWeights, amazeScratch_[2].view,
            amazeScratch_[1].view, VK_NULL_HANDLE, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeGreen, amazeScratch_[3].view,
            amazeScratch_[1].view, amazeScratch_[2].view, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeRefine, amazeScratch_[4].view,
            amazeScratch_[1].view, amazeScratch_[3].view, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeDiagonal, amazeScratch_[5].view,
            VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE);
        updateAmazeDescriptorSet(frame, AmazeChroma, amazeScratch_[6].view,
            amazeScratch_[4].view, amazeScratch_[3].view, amazeScratch_[5].view);
        updateAmazeDescriptorSet(frame, AmazeOutput, amazeDemosaicView_,
            VK_NULL_HANDLE, amazeScratch_[6].view, VK_NULL_HANDLE);
        if (captureHighlightRecovery_) {
            updateAmazeDescriptorSet(frame, AmazeHrSeed, hrTmpView_, VK_NULL_HANDLE, amazeDemosaicView_, VK_NULL_HANDLE);
            updateAmazeDescriptorSet(frame, AmazeHrReduce, hrTmpView_, hrTmpView_, amazeDemosaicView_, VK_NULL_HANDLE);
            updateAmazeDescriptorSet(frame, AmazeRecovery, hrMaskView_, hrTmpView_, amazeDemosaicView_, VK_NULL_HANDLE);
        }
    }

    void updateFrameDescriptors(Frame& frame)
    {
        if (frame.ispActive) {
            // The ISP sets are rewritten per frame; RAW descriptors refresh on the next RAW frame.
            updateIspComputeDescriptors(frame);
            updateIspDescriptors(frame);
            frame.computeDescriptorsDirty = true;
        } else if (frame.computeDescriptorsDirty) {
            updatePreviewComputeDescriptors(frame);
            frame.computeDescriptorsDirty = false;
        }

        VkDescriptorImageInfo sampledImage{
            sampler_, frame.focusStrength > 0.5f ? frame.focusOutput.view : frame.useSpektraOutput
                ? frame.spektraView : frame.videoChromaStrength > 0.001f ? videoChromaResult(frame).view : frame.processingView,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet presentWrite{
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        presentWrite.dstSet = frame.presentDescriptor;
        presentWrite.dstBinding = 0;
        presentWrite.descriptorCount = 1;
        presentWrite.descriptorType =
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        presentWrite.pImageInfo = &sampledImage;
        vkUpdateDescriptorSets(device_, 1, &presentWrite, 0, nullptr);
        VkDescriptorImageInfo exposureImage = frame.focusStrength > 0.5f || frame.useSpektraOutput
            ? VkDescriptorImageInfo{sampler_, frame.processingView, VK_IMAGE_LAYOUT_GENERAL} : sampledImage;
        presentWrite.dstBinding = 1;
        presentWrite.pImageInfo = &exposureImage;
        vkUpdateDescriptorSets(device_, 1, &presentWrite, 0, nullptr);
        VkDescriptorBufferInfo rangeBuffer{frame.exposureRange, 0, 8 * sizeof(float)};
        presentWrite.dstBinding = 2;
        presentWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        presentWrite.pImageInfo = nullptr;
        presentWrite.pBufferInfo = &rangeBuffer;
        vkUpdateDescriptorSets(device_, 1, &presentWrite, 0, nullptr);

        VkDescriptorImageInfo videoImage{sampler_, frame.useSpektraOutput ? frame.spektraView :
            frame.videoChromaStrength > 0.001f ? videoChromaResult(frame).view : frame.processingView,
            VK_IMAGE_LAYOUT_GENERAL};
        presentWrite.dstSet = frame.videoDescriptor;
        presentWrite.dstBinding = 0;
        presentWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        presentWrite.pImageInfo = &videoImage;
        presentWrite.pBufferInfo = nullptr;
        vkUpdateDescriptorSets(device_, 1, &presentWrite, 0, nullptr);
    }

    void updatePreviewComputeDescriptors(Frame& frame)
    {
        VkDescriptorBufferInfo rawBuffer{
            processedRawBuffer(frame), 0, VK_WHOLE_SIZE};
        VkDescriptorImageInfo rawImage{
            frame.recordingDownsample && frame.bayerCells && frame.textureShading ? sampler_ : rawIntegerSampler_,
            frame.importedImageView,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorBufferInfo rawUniform{
            frame.rawUniform, 0, kRawParametersSize};
        VkDescriptorImageInfo storageImage{
            VK_NULL_HANDLE, frame.previewHighlightRecovery ? frame.recovery.source.view :
                frame.shadowStrength > 0.001f ? frame.shadows.source.view : frame.processingView,
            VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorBufferInfo spektraUniform{
            frame.spektraUniform, 0, kRawColorParametersSize};
        VkDescriptorBufferInfo lensShading{
            frame.lensShadingBuffer, 0, frame.lensShadingCapacity};
        VkDescriptorBufferInfo geometryUniform{
            frame.geometryUniform, 0, kGeometryParametersSize};
        std::array<VkWriteDescriptorSet, 6> computeWrites{};
        for (auto& write : computeWrites)
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        computeWrites[0].dstSet = frame.computeDescriptor;
        computeWrites[0].dstBinding =
            processedRawKind(frame) == RawImportKind::Image ? 5 : 0;
        computeWrites[0].descriptorCount = 1;
        computeWrites[0].descriptorType =
            processedRawKind(frame) == RawImportKind::Image
                ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        if (processedRawKind(frame) == RawImportKind::Image)
            computeWrites[0].pImageInfo = &rawImage;
        else
            computeWrites[0].pBufferInfo = &rawBuffer;
        computeWrites[1].dstSet = frame.computeDescriptor;
        computeWrites[1].dstBinding = 1;
        computeWrites[1].descriptorCount = 1;
        computeWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        computeWrites[1].pBufferInfo = &rawUniform;
        computeWrites[2].dstSet = frame.computeDescriptor;
        computeWrites[2].dstBinding = 2;
        computeWrites[2].descriptorCount = 1;
        computeWrites[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        computeWrites[2].pImageInfo = &storageImage;
        computeWrites[3].dstSet = frame.computeDescriptor;
        computeWrites[3].dstBinding = 3;
        computeWrites[3].descriptorCount = 1;
        computeWrites[3].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        computeWrites[3].pBufferInfo = &spektraUniform;
        computeWrites[4].dstSet = frame.computeDescriptor;
        computeWrites[4].dstBinding = 4;
        computeWrites[4].descriptorCount = 1;
        computeWrites[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        computeWrites[4].pBufferInfo = &lensShading;
        computeWrites[5].dstSet = frame.computeDescriptor;
        computeWrites[5].dstBinding = 6;
        computeWrites[5].descriptorCount = 1;
        computeWrites[5].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        computeWrites[5].pBufferInfo = &geometryUniform;
        vkUpdateDescriptorSets(
            device_, static_cast<uint32_t>(computeWrites.size()),
            computeWrites.data(), 0, nullptr);
        VkDescriptorImageInfo lensImage{sampler_, frame.textureShading ? frame.lensTexture.view : previewFallbackTexture_.view,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorImageInfo sampledRaw{frame.mhcPrefilter ? sampler_ : rawIntegerSampler_, frame.unpackRaw ? frame.unpackedTexture.view : previewFallbackTexture_.view,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorImageInfo unpackOutput{VK_NULL_HANDLE, frame.unpackedTexture.view, VK_IMAGE_LAYOUT_GENERAL};
        std::array<VkWriteDescriptorSet, 3> imageWrites{};
        const uint32_t imageWriteCount = frame.unpackRaw ? 3 : 2;
        for (auto& write : imageWrites) {
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = frame.computeDescriptor;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        imageWrites[0].dstBinding = 8;
        imageWrites[0].pImageInfo = &lensImage;
        imageWrites[1].dstBinding = 10;
        imageWrites[1].pImageInfo = &sampledRaw;
        imageWrites[2].dstBinding = 9;
        imageWrites[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        imageWrites[2].pImageInfo = &unpackOutput;
        vkUpdateDescriptorSets(device_, imageWriteCount, imageWrites.data(), 0, nullptr);
        if (frame.areaPrefilter) {
            VkDescriptorBufferInfo info{frame.areaWeights, 0, frame.areaWeightsCapacity};
            VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            write.dstSet = frame.computeDescriptor;
            write.dstBinding = 13;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            write.pBufferInfo = &info;
            vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        }
    }

    void updateCaptureDescriptor(Frame& frame)
    {
        VkDescriptorBufferInfo rawBuffer{
            processedRawBuffer(frame), 0, VK_WHOLE_SIZE};
        VkDescriptorImageInfo rawImage{
            rawIntegerSampler_, frame.importedImageView,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorBufferInfo rawUniform{
            frame.rawUniform, 0, kRawParametersSize};
        VkDescriptorImageInfo outputImage{
            VK_NULL_HANDLE, captureView_, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorBufferInfo spektraUniform{
            frame.spektraUniform, 0, kRawColorParametersSize};
        VkDescriptorBufferInfo lensShading{
            frame.lensShadingBuffer, 0, frame.lensShadingCapacity};
        VkDescriptorBufferInfo geometryUniform{
            frame.geometryUniform, 0, kGeometryParametersSize};
        VkDescriptorImageInfo amazeInput{
            rawIntegerSampler_, captureDemosaicView(), VK_IMAGE_LAYOUT_GENERAL};
        std::array<VkWriteDescriptorSet, 7> writes{};
        for (auto& write : writes)
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = frame.computeDescriptor;
        writes[0].dstBinding =
            processedRawKind(frame) == RawImportKind::Image ? 5 : 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType =
            processedRawKind(frame) == RawImportKind::Image
                ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        if (processedRawKind(frame) == RawImportKind::Image)
            writes[0].pImageInfo = &rawImage;
        else
            writes[0].pBufferInfo = &rawBuffer;
        writes[1].dstSet = frame.computeDescriptor;
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[1].pBufferInfo = &rawUniform;
        writes[2].dstSet = frame.computeDescriptor;
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[2].pImageInfo = &outputImage;
        writes[3].dstSet = frame.computeDescriptor;
        writes[3].dstBinding = 3;
        writes[3].descriptorCount = 1;
        writes[3].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[3].pBufferInfo = &spektraUniform;
        writes[4].dstSet = frame.computeDescriptor;
        writes[4].dstBinding = 4;
        writes[4].descriptorCount = 1;
        writes[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[4].pBufferInfo = &lensShading;
        writes[5].dstSet = frame.computeDescriptor;
        writes[5].dstBinding = 6;
        writes[5].descriptorCount = 1;
        writes[5].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[5].pBufferInfo = &geometryUniform;
        writes[6].dstSet = frame.computeDescriptor;
        writes[6].dstBinding = 7;
        writes[6].descriptorCount = 1;
        writes[6].descriptorType =
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[6].pImageInfo = &amazeInput;
        vkUpdateDescriptorSets(
            device_, static_cast<uint32_t>(writes.size()),
            writes.data(), 0, nullptr);
    }

    void createSwapchain()
    {
        VkSurfaceCapabilitiesKHR capabilities{};
        require(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                    physicalDevice_, surface_, &capabilities),
                "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        uint32_t formatCount = 0;
        require(vkGetPhysicalDeviceSurfaceFormatsKHR(
                    physicalDevice_, surface_, &formatCount, nullptr),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        if (formatCount == 0)
            throw std::runtime_error("Surface exposes no Vulkan formats");
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        require(vkGetPhysicalDeviceSurfaceFormatsKHR(
                    physicalDevice_, surface_, &formatCount, formats.data()),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        VkSurfaceFormatKHR selected = formats.front();
        for (const VkSurfaceFormatKHR& format : formats) {
            if ((format.format == VK_FORMAT_B8G8R8A8_UNORM ||
                 format.format == VK_FORMAT_R8G8B8A8_UNORM) &&
                format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                selected = format;
                break;
            }
        }
        swapchainFormat_ = selected.format;
        swapchainExtent_ = capabilities.currentExtent;
        if (swapchainExtent_.width == std::numeric_limits<uint32_t>::max()) {
            uint32_t width = static_cast<uint32_t>(
                std::max(ANativeWindow_getWidth(window_), 1));
            uint32_t height = static_cast<uint32_t>(
                std::max(ANativeWindow_getHeight(window_), 1));
            swapchainExtent_.width = std::clamp(
                width, capabilities.minImageExtent.width,
                capabilities.maxImageExtent.width);
            swapchainExtent_.height = std::clamp(
                height, capabilities.minImageExtent.height,
                capabilities.maxImageExtent.height);
        }

        uint32_t modeCount = 0;
        require(vkGetPhysicalDeviceSurfacePresentModesKHR(
                    physicalDevice_, surface_, &modeCount, nullptr),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        std::vector<VkPresentModeKHR> modes(modeCount);
        require(vkGetPhysicalDeviceSurfacePresentModesKHR(
                    physicalDevice_, surface_, &modeCount, modes.data()),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        for (VkPresentModeKHR candidate : {VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR}) {
            if (std::find(modes.begin(), modes.end(), candidate) != modes.end()) {
                presentMode = candidate;
                break;
            }
        }
        recordNativeDiagnostic(ANDROID_LOG_INFO, "RawPreviewPerf", "Presentation mode=%d immediate=%d mailbox=%d frames=%u",
            presentMode, std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_IMMEDIATE_KHR) != modes.end(),
            std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end(), kFramesInFlight);
        uint32_t imageCount = std::max(capabilities.minImageCount,
            presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? kFramesInFlight + 1 : kFramesInFlight);
        if (capabilities.maxImageCount > 0)
            imageCount = std::min(imageCount, capabilities.maxImageCount);
        VkSwapchainCreateInfoKHR info{
            VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        info.surface = surface_;
        info.minImageCount = imageCount;
        info.imageFormat = selected.format;
        info.imageColorSpace = selected.colorSpace;
        info.imageExtent = swapchainExtent_;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
#if !defined(NDEBUG)
        presentSamplesSupported_ = (capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0 &&
            (selected.format == VK_FORMAT_R8G8B8A8_UNORM || selected.format == VK_FORMAT_B8G8R8A8_UNORM ||
             selected.format == VK_FORMAT_R8G8B8A8_SRGB || selected.format == VK_FORMAT_B8G8R8A8_SRGB);
        if (presentSamplesSupported_) info.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
#endif
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.preTransform = capabilities.currentTransform;
        info.compositeAlpha = chooseCompositeAlpha(capabilities);
        info.presentMode = presentMode;
        info.clipped = VK_TRUE;
        require(vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain_),
                "vkCreateSwapchainKHR");

        uint32_t actualCount = 0;
        require(vkGetSwapchainImagesKHR(
                    device_, swapchain_, &actualCount, nullptr),
                "vkGetSwapchainImagesKHR");
        swapchainImages_.resize(actualCount);
        require(vkGetSwapchainImagesKHR(
                    device_, swapchain_, &actualCount,
                    swapchainImages_.data()),
                "vkGetSwapchainImagesKHR");
        // Reacquiring an image guarantees its previous presentation wait has finished.
        presentSemaphores_.resize(actualCount, VK_NULL_HANDLE);
        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        for (VkSemaphore& semaphore : presentSemaphores_)
            require(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &semaphore),
                    "vkCreateSemaphore for swapchain image");
        VkRefreshCycleDurationGOOGLE refresh{};
        if (getRefreshCycleDuration_ != nullptr)
            getRefreshCycleDuration_(device_, swapchain_, &refresh);
        nextPresentId_ = 1;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "CameraPresentation",
            "mode=%s images=%u display_timing=%d refresh_ns=%llu samples=%d format=%d",
            presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "FIFO",
            actualCount, displayTimingSupported_,
            static_cast<unsigned long long>(refresh.refreshDuration), presentSamplesSupported_, selected.format);
        for (VkImage image : swapchainImages_)
            swapchainViews_.push_back(createImageView(image, swapchainFormat_));
        createPresentationPipeline();
        for (VkImageView view : swapchainViews_) {
            VkFramebufferCreateInfo framebufferInfo{
                VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            framebufferInfo.renderPass = renderPass_;
            framebufferInfo.attachmentCount = 1;
            framebufferInfo.pAttachments = &view;
            framebufferInfo.width = swapchainExtent_.width;
            framebufferInfo.height = swapchainExtent_.height;
            framebufferInfo.layers = 1;
            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            require(vkCreateFramebuffer(
                        device_, &framebufferInfo, nullptr, &framebuffer),
                    "vkCreateFramebuffer");
            framebuffers_.push_back(framebuffer);
        }
    }

    VkResult presentWithTiming(VkPresentInfoKHR& present)
    {
        VkPresentTimeGOOGLE time{nextPresentId_++, 0};
        VkPresentTimesInfoGOOGLE timing{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE};
        if (displayTimingSupported_) {
            timing.swapchainCount = 1;
            timing.pTimes = &time;
            present.pNext = &timing;
        }
        VkResult result = vkQueuePresentKHR(queue_, &present);
        present.pNext = nullptr;
        if ((result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) &&
            getPastPresentationTiming_ != nullptr && time.presentID % 30 == 0 &&
            __android_log_is_loggable(ANDROID_LOG_DEBUG, "CameraPresentation", ANDROID_LOG_INFO)) {
            std::array<VkPastPresentationTimingGOOGLE, 64> history{};
            uint32_t count = static_cast<uint32_t>(history.size());
            VkResult queried = getPastPresentationTiming_(device_, swapchain_, &count, history.data());
            if (queried == VK_SUCCESS || queried == VK_INCOMPLETE) {
                for (uint32_t index = 0; index < count; ++index) {
                    const auto& entry = history[index];
                    recordNativeDiagnostic(ANDROID_LOG_DEBUG, "CameraPresentation",
                        "present_id=%u actual_ns=%llu earliest_ns=%llu margin_ns=%llu",
                        entry.presentID,
                        static_cast<unsigned long long>(entry.actualPresentTime),
                        static_cast<unsigned long long>(entry.earliestPresentTime),
                        static_cast<unsigned long long>(entry.presentMargin));
                }
            }
        }
        return result;
    }

    static VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(
        const VkSurfaceCapabilitiesKHR& capabilities)
    {
        constexpr std::array<VkCompositeAlphaFlagBitsKHR, 4> choices{{
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR}};
        for (VkCompositeAlphaFlagBitsKHR choice : choices) {
            if ((capabilities.supportedCompositeAlpha & choice) != 0)
                return choice;
        }
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    void createPresentationPipeline()
    {
        VkAttachmentDescription attachment{};
        attachment.format = swapchainFormat_;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference reference{0,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &reference;
        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo renderInfo{
            VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        renderInfo.attachmentCount = 1;
        renderInfo.pAttachments = &attachment;
        renderInfo.subpassCount = 1;
        renderInfo.pSubpasses = &subpass;
        renderInfo.dependencyCount = 1;
        renderInfo.pDependencies = &dependency;
        require(vkCreateRenderPass(
                    device_, &renderInfo, nullptr, &renderPass_),
                "vkCreateRenderPass");

        VkPushConstantRange pushRange{
            VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float) * 12};
        VkPipelineLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &presentDescriptorLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        require(vkCreatePipelineLayout(
                    device_, &layoutInfo, nullptr, &presentPipelineLayout_),
                "vkCreatePipelineLayout");

        presentPipeline_ = createGraphicsProgram(kPresentFragmentSpirv.data(), kPresentFragmentSpirv.size(),
            renderPass_, presentPipelineLayout_, nullptr);
    }

    VkPipeline createGraphicsProgram(const uint32_t* code, size_t wordCount,
                                     VkRenderPass renderPass, VkPipelineLayout layout,
                                     const VkSpecializationInfo* specialization, bool blending = false)
    {
        VkShaderModule vertex = makeShader(
            device_, kPresentVertexSpirv.data(), kPresentVertexSpirv.size());
        VkShaderModule fragment = VK_NULL_HANDLE;
        try {
            fragment = makeShader(device_, code, wordCount);
        } catch (...) {
            vkDestroyShaderModule(device_, vertex, nullptr);
            throw;
        }
        std::array<VkPipelineShaderStageCreateInfo, 2> stages{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vertex;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fragment;
        stages[1].pName = "main";
        stages[1].pSpecializationInfo = specialization;
        VkPipelineVertexInputStateCreateInfo vertexInput{
            VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo assembly{
            VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewport{
            VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        viewport.viewportCount = 1;
        viewport.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo raster{
            VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = VK_CULL_MODE_NONE;
        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        raster.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo multisample{
            VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        if (blending) {
            blendAttachment.blendEnable = VK_TRUE;
            blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
            blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
            blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
            blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        }
        VkPipelineColorBlendStateCreateInfo blend{
            VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        blend.attachmentCount = 1;
        blend.pAttachments = &blendAttachment;
        std::array<VkDynamicState, 2> dynamicStates{
            VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic{
            VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dynamic.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamic.pDynamicStates = dynamicStates.data();
        VkGraphicsPipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pipelineInfo.stageCount = static_cast<uint32_t>(stages.size());
        pipelineInfo.pStages = stages.data();
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &assembly;
        pipelineInfo.pViewportState = &viewport;
        pipelineInfo.pRasterizationState = &raster;
        pipelineInfo.pMultisampleState = &multisample;
        pipelineInfo.pColorBlendState = &blend;
        pipelineInfo.pDynamicState = &dynamic;
        pipelineInfo.layout = layout;
        pipelineInfo.renderPass = renderPass;
        PipelineCreationFeedback feedback(&pipelineCreationStats_);
        pipelineInfo.pNext = feedback.info();
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = vkCreateGraphicsPipelines(
            device_, pipelineCache_.handle, 1, &pipelineInfo, nullptr,
            &pipeline);
        vkDestroyShaderModule(device_, fragment, nullptr);
        vkDestroyShaderModule(device_, vertex, nullptr);
        require(result, "vkCreateGraphicsPipelines");
        feedback.record();
        pipelineCache_.markDirty();
        return pipeline;
    }

    void acquireRawForCompute(VkCommandBuffer commandBuffer, const Frame& frame)
    {
        const VkPipelineStageFlags shaderStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
            (frame.rasterRaw ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : 0);
        if (frame.rawImportKind == RawImportKind::Image) {
            VkImageMemoryBarrier barrier{
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            barrier.dstQueueFamilyIndex = queueFamily_;
            barrier.image = frame.importedImage;
            barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barrier.subresourceRange.levelCount = 1;
            barrier.subresourceRange.layerCount = 1;
            vkCmdPipelineBarrier(
                commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                shaderStage, 0, 0, nullptr,
                0, nullptr, 1, &barrier);
            return;
        }

        VkBufferMemoryBarrier barrier{
            VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = sourceRawBuffer(frame);
        barrier.offset = 0;
        barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(
            commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
            shaderStage, 0, 0, nullptr,
            1, &barrier, 0, nullptr);
    }

    void releaseRawFromCompute(VkCommandBuffer commandBuffer, const Frame& frame)
    {
        if (frame.rawImportKind == RawImportKind::Image) {
            VkImageMemoryBarrier barrier{
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.dstAccessMask = 0;
            barrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.srcQueueFamilyIndex = queueFamily_;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
            barrier.image = frame.importedImage;
            barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barrier.subresourceRange.levelCount = 1;
            barrier.subresourceRange.layerCount = 1;
            vkCmdPipelineBarrier(
                commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                    (frame.rasterRaw ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : 0),
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr,
                0, nullptr, 1, &barrier);
            return;
        }

        return;
    }

    void recordHighlightRecovery(VkCommandBuffer commandBuffer, size_t input,
                                 camera_gpu::ComputeRecorder& recorder)
    {
        std::array<VkImageMemoryBarrier, 2> images{};
        for (size_t i = 0; i < images.size(); ++i) {
            auto& barrier = images[i];
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barrier.srcAccessMask = hrLayoutsInitialized_ ? VK_ACCESS_SHADER_READ_BIT : 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.oldLayout = hrLayoutsInitialized_ ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = i == 0 ? hrTmpImage_ : hrMaskImage_;
            barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        VkBufferMemoryBarrier reduction{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        reduction.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        reduction.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        reduction.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        reduction.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        reduction.buffer = hrReduceBuffer_;
        reduction.size = 24;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 1, &reduction, 2, images.data());
        hrLayoutsInitialized_ = true;
        vkCmdFillBuffer(commandBuffer, hrReduceBuffer_, 0, 24, 0);
        reduction.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        reduction.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &reduction, 0, nullptr);
        for (AmazeStage stage : {AmazeHrSeed, AmazeHrReduce}) {
            recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, amazePipelines_[amazePipelinePattern_][input][stage]);
            recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                                     amazePipelineLayout_, 0, 1, &amazeDescriptorSets_[stage], 0, nullptr);
            recorder.dispatch(commandBuffer,
                              (amazeWidth_ + amazeWorkgroup_.x - 1) / amazeWorkgroup_.x,
                              (amazeHeight_ + amazeWorkgroup_.y - 1) / amazeWorkgroup_.y, 1);
            if (stage == AmazeHrSeed) {
                for (auto& barrier : images) {
                    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                    barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                }
                vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                                     0, nullptr, 0, nullptr, 2, images.data());
            } else {
                reduction.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                reduction.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                                     0, nullptr, 1, &reduction, 0, nullptr);
            }
        }
    }

    void recordAmazeStages(VkCommandBuffer commandBuffer, Frame& frame, camera_gpu::ComputeRecorder& recorder)
    {
        std::array<VkImageMemoryBarrier, 9> initialize{};
        for (size_t i = 0; i < initialize.size(); ++i) {
            auto& barrier = initialize[i];
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barrier.srcAccessMask = amazeLayoutsInitialized_ ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.oldLayout = amazeLayoutsInitialized_ ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = i < 8 ? amazeScratch_[i].image : amazeDemosaicImage_;
            barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        vkCmdPipelineBarrier(commandBuffer,
            amazeLayoutsInitialized_ ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            static_cast<uint32_t>(initialize.size()), initialize.data());
        amazeLayoutsInitialized_ = true;
        const size_t input = processedRawKind(frame) == RawImportKind::Image ? AmazeImageInput : AmazeBufferInput;
        VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        ready.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        for (uint32_t y = 0; y < amazeHeight_; y += kAmazeTileCore) {
            for (uint32_t x = 0; x < amazeWidth_; x += kAmazeTileCore) {
                std::array<int32_t, 4> tile{
                    static_cast<int32_t>(x) - static_cast<int32_t>(kAmazeTileHalo),
                    static_cast<int32_t>(y) - static_cast<int32_t>(kAmazeTileHalo),
                    static_cast<int32_t>(std::min(kAmazeTileCore, amazeWidth_ - x) + 2 * kAmazeTileHalo),
                    static_cast<int32_t>(std::min(kAmazeTileCore, amazeHeight_ - y) + 2 * kAmazeTileHalo)};
                recorder.push(commandBuffer, amazePipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(tile), tile.data());
                for (size_t stage = 0; stage <= AmazeOutput; ++stage) {
                    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        amazePipelines_[amazePipelinePattern_][input][stage]);
                    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        amazePipelineLayout_, 0, 1, &amazeDescriptorSets_[stage], 0, nullptr);
                    recorder.dispatch(commandBuffer,
                        ((tile[2] + 1) / 2 + amazeWorkgroup_.x - 1) / amazeWorkgroup_.x,
                        ((tile[3] + 1) / 2 + amazeWorkgroup_.y - 1) / amazeWorkgroup_.y, 1);
                    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &ready, 0, nullptr, 0, nullptr);
                }
            }
        }
        if (captureHighlightRecovery_) {
            recordHighlightRecovery(commandBuffer, input, recorder);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &ready, 0, nullptr, 0, nullptr);
            recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                amazePipelines_[amazePipelinePattern_][input][AmazeRecovery]);
            recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                amazePipelineLayout_, 0, 1, &amazeDescriptorSets_[AmazeRecovery], 0, nullptr);
            recorder.dispatch(commandBuffer, (amazeWidth_ + amazeWorkgroup_.x - 1) / amazeWorkgroup_.x,
                (amazeHeight_ + amazeWorkgroup_.y - 1) / amazeWorkgroup_.y, 1);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &ready, 0, nullptr, 0, nullptr);
        }
    }

    struct ChromaPushConstants {
        int32_t width;
        int32_t height;
        float sharpenAmount;
        float sharpenRadius;
        float sharpenThreshold;
        float chromaDenoiseStrength;
        float nlmStrength;
        float localContrastStrength;
        float pixelSizeX;
        float pixelSizeY;
        int32_t colorSpace;
        float noiseProfileS;
        float noiseProfileO;
        float wbR;
        float wbG;
        float wbB;
    };
    static_assert(sizeof(ChromaPushConstants) == 64);

    struct FocusPushConstants {
        int32_t width, height;
        float strength, noiseS, noiseO, lumaVarianceGain, quantization;
    };
    static_assert(sizeof(FocusPushConstants) == 28);

    void destroyPreviewRecoveryResources(Frame& frame)
    {
        auto& recovery = frame.recovery;
        if (recovery.pool) vkDestroyDescriptorPool(device_, recovery.pool, nullptr);
        if (recovery.framebuffer) vkDestroyFramebuffer(device_, recovery.framebuffer, nullptr);
        destroyPreviewTexture(recovery.source);
        destroyPreviewTexture(recovery.temporary);
        destroyPreviewTexture(recovery.mask);
        if (recovery.reduction) vkDestroyBuffer(device_, recovery.reduction, nullptr);
        if (recovery.reductionMemory) vkFreeMemory(device_, recovery.reductionMemory, nullptr);
        recovery = {};
        frame.computeDescriptorsDirty = true;
    }

    void ensurePreviewRecoveryPipelines()
    {
        if (!previewRecoveryDescriptorLayout_) {
            std::array<VkDescriptorSetLayoutBinding, 10> bindings{};
            for (uint32_t binding = 0; binding < bindings.size(); ++binding) {
                const auto type = binding == 1 || binding == 3 || binding == 6 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER :
                    binding == 4 || binding == 9 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER :
                    binding == 2 || binding == 8 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings[binding] = {binding, type, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
            }
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = bindings.size();
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &previewRecoveryDescriptorLayout_),
                "Preview recovery descriptor layout");
        }
        if (!previewRecoveryPipelineLayout_) {
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &previewRecoveryDescriptorLayout_;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &previewRecoveryPipelineLayout_),
                "Preview recovery pipeline layout");
        }
        if (!previewRecoveryPipelines_[0]) previewRecoveryPipelines_[0] = createComputeProgram(
            kPreviewHrSeedSpirv.data(), kPreviewHrSeedSpirv.size(), nullptr, previewRecoveryPipelineLayout_);
        if (!previewRecoveryPipelines_[1]) previewRecoveryPipelines_[1] = createComputeProgram(
            kPreviewHrReduceSpirv.data(), kPreviewHrReduceSpirv.size(), nullptr, previewRecoveryPipelineLayout_);
        if (!previewRecoveryPipelines_[2]) previewRecoveryPipelines_[2] = createComputeProgram(
            kPreviewHrApplySpirv.data(), kPreviewHrApplySpirv.size(), nullptr, previewRecoveryPipelineLayout_);
    }

    void ensurePreviewRecoveryResources(Frame& frame)
    {
        ensurePreviewRecoveryPipelines();
        auto& recovery = frame.recovery;
        if (recovery.pool && recovery.source.width == processingWidth_ && recovery.source.height == processingHeight_) return;
        destroyPreviewRecoveryResources(frame);
        for (auto* texture : {&recovery.source, &recovery.temporary, &recovery.mask})
            ensurePreviewTexture(*texture, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                    (texture == &recovery.source && frame.processingFramebuffer ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : 0));
        if (frame.processingFramebuffer) {
            VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            info.renderPass = rawRenderPass_;
            info.attachmentCount = 1;
            info.pAttachments = &recovery.source.view;
            info.width = processingWidth_;
            info.height = processingHeight_;
            info.layers = 1;
            require(vkCreateFramebuffer(device_, &info, nullptr, &recovery.framebuffer), "Preview recovery framebuffer");
        }
        makeBuffer(24, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, recovery.reduction, recovery.reductionMemory, nullptr);
        const VkDescriptorPoolSize sizes[] = {
            {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 6}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 6}};
        VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool.maxSets = 2;
        pool.poolSizeCount = 4;
        pool.pPoolSizes = sizes;
        require(vkCreateDescriptorPool(device_, &pool, nullptr, &recovery.pool), "Preview recovery descriptor pool");
        const VkDescriptorSetLayout layouts[] = {previewRecoveryDescriptorLayout_, previewRecoveryDescriptorLayout_};
        VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        allocate.descriptorPool = recovery.pool;
        allocate.descriptorSetCount = 2;
        allocate.pSetLayouts = layouts;
        require(vkAllocateDescriptorSets(device_, &allocate, recovery.descriptors.data()), "Preview recovery descriptors");
    }

    void updatePreviewRecoveryDescriptors(Frame& frame)
    {
        auto& recovery = frame.recovery;
        const VkDescriptorBufferInfo buffers[] = {
            {frame.rawUniform, 0, kRawParametersSize}, {frame.spektraUniform, 0, kRawColorParametersSize},
            {frame.lensShadingBuffer, 0, frame.lensShadingCapacity}, {frame.geometryUniform, 0, kGeometryParametersSize},
            {recovery.reduction, 0, 24}};
        for (size_t stage = 0; stage < recovery.descriptors.size(); ++stage) {
            const VkDescriptorImageInfo images[] = {
                {sampler_, recovery.source.view, VK_IMAGE_LAYOUT_GENERAL},
                {VK_NULL_HANDLE, stage == 0 ? recovery.temporary.view :
                    frame.shadowStrength > 0.001f ? frame.shadows.source.view : frame.processingView, VK_IMAGE_LAYOUT_GENERAL},
                {sampler_, recovery.temporary.view, VK_IMAGE_LAYOUT_GENERAL},
                {sampler_, recovery.mask.view, VK_IMAGE_LAYOUT_GENERAL},
                {VK_NULL_HANDLE, recovery.mask.view, VK_IMAGE_LAYOUT_GENERAL}};
            std::array<VkWriteDescriptorSet, 10> writes{};
            size_t buffer = 0, image = 0;
            for (uint32_t binding = 0; binding < writes.size(); ++binding) {
                auto& write = writes[binding];
                write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                write.dstSet = recovery.descriptors[stage];
                write.dstBinding = binding;
                write.descriptorCount = 1;
                if (binding == 1 || binding == 3 || binding == 4 || binding == 6 || binding == 9) {
                    write.descriptorType = binding == 4 || binding == 9 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                    write.pBufferInfo = &buffers[buffer++];
                } else {
                    write.descriptorType = binding == 2 || binding == 8 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                    write.pImageInfo = &images[image++];
                }
            }
            vkUpdateDescriptorSets(device_, writes.size(), writes.data(), 0, nullptr);
        }
    }

    void recordPreviewRecovery(VkCommandBuffer command, Frame& frame)
    {
        auto& recovery = frame.recovery;
        recovery.source.initialized = true;
        const bool shadows = frame.shadowStrength > 0.001f;
        const auto prepareImage = [&](VkImage image, bool& initialized) {
            VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.srcAccessMask = initialized ? VK_ACCESS_SHADER_READ_BIT : 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.oldLayout = initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = image;
            barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(command, initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT :
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &barrier);
            initialized = true;
        };
        prepareImage(recovery.temporary.image, recovery.temporary.initialized);
        prepareImage(recovery.mask.image, recovery.mask.initialized);
        if (shadows) prepareImage(frame.shadows.source.image, frame.shadows.source.initialized);
        else prepareImage(frame.processingImage, frame.processingInitialized);
        vkCmdFillBuffer(command, recovery.reduction, 0, 24, 0);
        VkMemoryBarrier clear{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        clear.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        clear.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 1, &clear, 0, nullptr, 0, nullptr);
        for (size_t stage = 0; stage < previewRecoveryPipelines_.size(); ++stage) {
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, previewRecoveryPipelines_[stage]);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, previewRecoveryPipelineLayout_,
                0, 1, &recovery.descriptors[stage == 2 ? 1 : 0], 0, nullptr);
            vkCmdDispatch(command, (processingWidth_ + 7) / 8, (processingHeight_ + 7) / 8, 1);
            VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, 1, &ready, 0, nullptr, 0, nullptr);
        }
    }

    void destroyShadowResources(Frame& frame)
    {
        auto& shadows = frame.shadows;
        if (shadows.pool) vkDestroyDescriptorPool(device_, shadows.pool, nullptr);
        if (shadows.framebuffer) vkDestroyFramebuffer(device_, shadows.framebuffer, nullptr);
        destroyPreviewTexture(shadows.source);
        for (auto& image : shadows.mask) destroyPreviewTexture(image);
        shadows = {};
        frame.computeDescriptorsDirty = true;
    }

    void ensureShadowPipelines(bool preview)
    {
        for (size_t i = 0; i < 2; ++i) {
            if (!shadowDescriptorLayouts_[i]) {
                std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
                for (uint32_t j = 0; j < bindings.size(); ++j)
                    bindings[j] = {j, i == 0 && j == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
                VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
                info.bindingCount = i == 0 ? 2 : 3;
                info.pBindings = bindings.data();
                require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &shadowDescriptorLayouts_[i]),
                    "Shadow mask descriptor layout");
            }
            if (!shadowPipelineLayouts_[i]) {
                VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, i == 0 ? 8u : 24u};
                VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
                info.setLayoutCount = 1;
                info.pSetLayouts = &shadowDescriptorLayouts_[i];
                info.pushConstantRangeCount = 1;
                info.pPushConstantRanges = &range;
                require(vkCreatePipelineLayout(device_, &info, nullptr, &shadowPipelineLayouts_[i]),
                    "Shadow mask pipeline layout");
            }
            if (!shadowPipelines_[i]) shadowPipelines_[i] = i == 0
                ? createComputeProgram(kShadowLumaSpirv.data(), kShadowLumaSpirv.size(), nullptr, shadowPipelineLayouts_[i])
                : createComputeProgram(kShadowBilateralSpirv.data(), kShadowBilateralSpirv.size(), nullptr, shadowPipelineLayouts_[i]);
        }
        if (preview && !shadowColorPipeline_)
            shadowColorPipeline_ = createComputeProgram(kShadowColorSpirv.data(), kShadowColorSpirv.size(), nullptr);
    }

    void ensureShadowResources(Frame& frame, uint32_t width, uint32_t height, bool preview)
    {
        ensureShadowPipelines(preview);
        auto& shadows = frame.shadows;
        if (shadows.pool && shadows.fullWidth == width && shadows.fullHeight == height) return;
        destroyShadowResources(frame);
        shadows.fullWidth = width;
        shadows.fullHeight = height;
        const uint32_t fullWidth = std::max(width, 16u), fullHeight = std::max(height, 16u);
        const float scale = std::min(1.0f, (preview ? 960.0f : 2048.0f) / std::max(fullWidth, fullHeight));
        const uint32_t guideWidth = std::max(16u, static_cast<uint32_t>(fullWidth * scale) & ~15u);
        const uint32_t guideHeight = std::max(16u, static_cast<uint32_t>(fullHeight * scale) & ~15u);
        for (auto& image : shadows.mask)
            ensurePreviewTexture(image, guideWidth, guideHeight, VK_FORMAT_R16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        if (preview) {
            ensurePreviewTexture(shadows.source, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                    (frame.processingFramebuffer ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : 0));
            if (frame.processingFramebuffer) {
                VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
                info.renderPass = rawRenderPass_;
                info.attachmentCount = 1;
                info.pAttachments = &shadows.source.view;
                info.width = width;
                info.height = height;
                info.layers = 1;
                require(vkCreateFramebuffer(device_, &info, nullptr, &shadows.framebuffer), "Shadow source framebuffer");
            }
        }
        const VkDescriptorPoolSize sizes[] = {
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4}, {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 3},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 9}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 6}};
        VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool.maxSets = 4;
        pool.poolSizeCount = 4;
        pool.pPoolSizes = sizes;
        require(vkCreateDescriptorPool(device_, &pool, nullptr, &shadows.pool), "Shadow mask descriptor pool");
        const VkDescriptorSetLayout layouts[] = {shadowDescriptorLayouts_[0], shadowDescriptorLayouts_[1],
            shadowDescriptorLayouts_[1], computeDescriptorLayout_};
        VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        allocate.descriptorPool = shadows.pool;
        allocate.descriptorSetCount = 4;
        allocate.pSetLayouts = layouts;
        require(vkAllocateDescriptorSets(device_, &allocate, shadows.descriptors.data()), "Shadow mask descriptors");
    }

    void updateShadowDescriptors(Frame& frame, bool preview)
    {
        auto& shadows = frame.shadows;
        const auto writeImage = [&](VkDescriptorSet set, uint32_t binding, VkImageView image, bool sampled) {
            VkDescriptorImageInfo info{sampled ? sampler_ : VK_NULL_HANDLE, image, VK_IMAGE_LAYOUT_GENERAL};
            VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            write.dstSet = set;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            write.pImageInfo = &info;
            vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        };
        if (!preview) writeImage(frame.computeDescriptor, 12,
            frame.shadowStrength > 0.001f ? shadows.mask[2].view : captureDemosaicView(), true);
        if (frame.shadowStrength <= 0.001f) return;
        writeImage(shadows.descriptors[0], 0, preview ? shadows.source.view : captureDemosaicView(), true);
        writeImage(shadows.descriptors[0], 1, shadows.mask[0].view, false);
        for (size_t i = 0; i < 2; ++i) {
            writeImage(shadows.descriptors[i + 1], 0, shadows.mask[i].view, false);
            writeImage(shadows.descriptors[i + 1], 1, shadows.mask[0].view, false);
            writeImage(shadows.descriptors[i + 1], 2, shadows.mask[i + 1].view, false);
        }
        if (preview) {
            for (uint32_t binding : {1u, 3u, 4u, 6u}) {
                VkCopyDescriptorSet copy{VK_STRUCTURE_TYPE_COPY_DESCRIPTOR_SET};
                copy.srcSet = frame.computeDescriptor;
                copy.dstSet = shadows.descriptors[3];
                copy.srcBinding = copy.dstBinding = binding;
                copy.descriptorCount = 1;
                vkUpdateDescriptorSets(device_, 0, nullptr, 1, &copy);
            }
            writeImage(shadows.descriptors[3], 2, frame.processingView, false);
            writeImage(shadows.descriptors[3], 7, shadows.source.view, true);
            writeImage(shadows.descriptors[3], 12, shadows.mask[2].view, true);
        }
    }

    void recordShadowGuide(VkCommandBuffer command, ShadowResources& shadows)
    {
        for (auto& image : shadows.mask) {
            VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.srcAccessMask = image.initialized ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.oldLayout = image.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = image.image;
            barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(command, image.initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
            image.initialized = true;
        }
        const uint32_t width = shadows.mask[0].width, height = shadows.mask[0].height;
        struct PushConstants {
            int32_t width, height, horizontal, radius;
            float sigmaSpatial, sigmaRange;
        } pc{};
        pc.width = static_cast<int32_t>(width);
        pc.height = static_cast<int32_t>(height);
        const float guideScale = std::min(static_cast<float>(width) / std::max(shadows.fullWidth, 1u),
            static_cast<float>(height) / std::max(shadows.fullHeight, 1u));
        pc.radius = std::clamp(static_cast<int>(std::round(20.0f * guideScale)), 4, 20);
        pc.sigmaSpatial = std::max(2.0f, 9.0f * guideScale);
        pc.sigmaRange = 0.16f;
        for (size_t i = 0; i < 3; ++i) {
            const size_t pipeline = i == 0 ? 0 : 1;
            pc.horizontal = i == 1 ? 1 : 0;
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, shadowPipelines_[pipeline]);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, shadowPipelineLayouts_[pipeline],
                0, 1, &shadows.descriptors[i], 0, nullptr);
            vkCmdPushConstants(command, shadowPipelineLayouts_[pipeline], VK_SHADER_STAGE_COMPUTE_BIT,
                0, i == 0 ? 8 : sizeof(pc), &pc);
            vkCmdDispatch(command, (width + 15) / 16, (height + 15) / 16, 1);
            VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 1, &ready, 0, nullptr, 0, nullptr);
        }
    }

    void ensureAverageResources(Frame& frame)
    {
        if (!averageDescriptorLayout_) {
            std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
                {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = static_cast<uint32_t>(bindings.size());
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &averageDescriptorLayout_), "Average descriptor layout");
        }
        if (!averagePipelineLayout_) {
            VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 2 * sizeof(int32_t)};
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &averageDescriptorLayout_;
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &range;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &averagePipelineLayout_), "Average pipeline layout");
        }
        if (!averagePipeline_)
            averagePipeline_ = createComputeProgram(kPreviewAverageSpirv.data(), kPreviewAverageSpirv.size(), nullptr, averagePipelineLayout_);
        ensurePreviewTexture(averageHistory_, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT);
        if (!frame.averagePool) {
            VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2};
            VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pool.maxSets = 1;
            pool.poolSizeCount = 1;
            pool.pPoolSizes = &size;
            require(vkCreateDescriptorPool(device_, &pool, nullptr, &frame.averagePool), "Average descriptor pool");
            VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocate.descriptorPool = frame.averagePool;
            allocate.descriptorSetCount = 1;
            allocate.pSetLayouts = &averageDescriptorLayout_;
            require(vkAllocateDescriptorSets(device_, &allocate, &frame.averageDescriptor), "Average descriptor");
        }
        std::array<VkDescriptorImageInfo, 2> images{{
            {VK_NULL_HANDLE, frame.processingView, VK_IMAGE_LAYOUT_GENERAL},
            {VK_NULL_HANDLE, averageHistory_.view, VK_IMAGE_LAYOUT_GENERAL}}};
        std::array<VkWriteDescriptorSet, 2> writes{};
        for (uint32_t binding = 0; binding < 2; ++binding) {
            writes[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[binding].dstSet = frame.averageDescriptor;
            writes[binding].dstBinding = binding;
            writes[binding].descriptorCount = 1;
            writes[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[binding].pImageInfo = &images[binding];
        }
        vkUpdateDescriptorSets(device_, 2, writes.data(), 0, nullptr);
    }

    bool prepareAverage(Frame& frame, int64_t group, int index, int count, int64_t revision,
                        const GeometryParameters& geometry, bool lightweight)
    {
        if (count <= 1) { averageLastIndex_ = -1; return true; }
        // Each capture is already warped into its stabilized output coordinates.
        // Motion changes between captures must not discard the average in progress.
        auto averageGeometry = geometry;
        std::memset(averageGeometry.stabilizationRows, 0, sizeof(averageGeometry.stabilizationRows));
        const bool valid = index == 0 || (averageHistory_.initialized && group == averageGroup_ &&
            index == averageLastIndex_ + 1 && revision == averageRevision_ && count == averageCount_ &&
            lightweight == averageLightweight_ &&
            frame.recordingDownsample == averageRecordingDownsample_ &&
            frame.recordingNative == averageRecordingNative_ &&
            frame.recordingTile == averageRecordingTile_ &&
            std::memcmp(&averageGeometry, &averageGeometry_, sizeof(averageGeometry)) == 0);
        frame.averageIndex = valid ? index : 0;
        averageLastIndex_ = valid ? index : -1;
        averageGroup_ = group;
        averageRevision_ = revision;
        averageCount_ = count;
        averageGeometry_ = averageGeometry;
        averageLightweight_ = lightweight;
        averageRecordingDownsample_ = frame.recordingDownsample;
        averageRecordingNative_ = frame.recordingNative;
        averageRecordingTile_ = frame.recordingTile;
        return valid && index == count - 1;
    }

    void recordAverage(Frame& frame)
    {
        if (frame.averageCount <= 1) return;
        VkImageMemoryBarrier history{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        history.srcAccessMask = averageHistory_.initialized ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
        history.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        history.oldLayout = averageHistory_.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
        history.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        history.srcQueueFamilyIndex = history.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        history.image = averageHistory_.image;
        history.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
        ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(frame.rawCommandBuffer,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 1, &ready, 0, nullptr, 1, &history);
        vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, averagePipeline_);
        vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, averagePipelineLayout_,
            0, 1, &frame.averageDescriptor, 0, nullptr);
        const int32_t parameters[] = {frame.averageIndex, frame.averageEncodeSdr};
        vkCmdPushConstants(frame.rawCommandBuffer, averagePipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(parameters), parameters);
        vkCmdDispatch(frame.rawCommandBuffer, (processingWidth_ + 15) / 16, (processingHeight_ + 15) / 16, 1);
        ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 1, &ready, 0, nullptr, 0, nullptr);
        averageHistory_.initialized = true;
    }

    struct VideoChromaParameters {
        float strength;
        float noiseS;
        float noiseO;
        int32_t encodeSdr;
        float varianceWeights[4];
        int32_t spacing;
    };

    static int videoChromaPassCount(float strength)
    {
        return std::clamp(static_cast<int>(std::ceil(strength * 3.0f)), 1, 3);
    }

    static PreviewTexture& videoChromaResult(Frame& frame)
    {
        return videoChromaPassCount(frame.videoChromaStrength) == 2 ? frame.videoChromaTemporary : frame.videoChromaOutput;
    }

    void ensureVideoChromaPipeline()
    {
        if (!videoChromaDescriptorLayout_) {
            const VkDescriptorSetLayoutBinding bindings[] = {
                {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = 2;
            info.pBindings = bindings;
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &videoChromaDescriptorLayout_),
                "Video chroma descriptor layout");
        }
        if (!videoChromaPipelineLayout_) {
            VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VideoChromaParameters)};
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &videoChromaDescriptorLayout_;
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &range;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &videoChromaPipelineLayout_),
                "Video chroma pipeline layout");
        }
        if (!videoChromaPipeline_)
            videoChromaPipeline_ = createComputeProgram(kVideoChromaSpirv.data(), kVideoChromaSpirv.size(),
                nullptr, videoChromaPipelineLayout_);
    }

    void ensureVideoChromaResources(Frame& frame)
    {
        ensureVideoChromaPipeline();
        ensurePreviewTexture(frame.videoChromaOutput, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        if (videoChromaPassCount(frame.videoChromaStrength) > 1)
            ensurePreviewTexture(frame.videoChromaTemporary, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        const VkDescriptorPoolSize sizes[] = {
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3}};
        if (!frame.videoChromaPool) {
            VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pool.maxSets = 3;
            pool.poolSizeCount = 2;
            pool.pPoolSizes = sizes;
            require(vkCreateDescriptorPool(device_, &pool, nullptr, &frame.videoChromaPool), "Video chroma descriptor pool");
            const VkDescriptorSetLayout layouts[] = {videoChromaDescriptorLayout_, videoChromaDescriptorLayout_, videoChromaDescriptorLayout_};
            VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocate.descriptorPool = frame.videoChromaPool;
            allocate.descriptorSetCount = 3;
            allocate.pSetLayouts = layouts;
            require(vkAllocateDescriptorSets(device_, &allocate, frame.videoChromaDescriptors.data()), "Video chroma descriptor");
        }
        const VkImageView inputs[] = {frame.processingView, frame.videoChromaOutput.view, frame.videoChromaTemporary.view};
        const VkImageView outputs[] = {frame.videoChromaOutput.view, frame.videoChromaTemporary.view, frame.videoChromaOutput.view};
        const int count = frame.videoChromaTemporary.view ? 3 : 1;
        for (int pass = 0; pass < count; ++pass) {
            const VkDescriptorImageInfo images[] = {
                {rawIntegerSampler_, inputs[pass], VK_IMAGE_LAYOUT_GENERAL},
                {VK_NULL_HANDLE, outputs[pass], VK_IMAGE_LAYOUT_GENERAL}};
            std::array<VkWriteDescriptorSet, 2> writes{};
            for (uint32_t binding = 0; binding < writes.size(); ++binding) {
                auto& write = writes[binding];
                write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                write.dstSet = frame.videoChromaDescriptors[pass];
                write.dstBinding = binding;
                write.descriptorCount = 1;
                write.descriptorType = sizes[binding].type;
                write.pImageInfo = &images[binding];
            }
            vkUpdateDescriptorSets(device_, writes.size(), writes.data(), 0, nullptr);
        }
    }

    void recordVideoChroma(Frame& frame)
    {
        if (frame.videoChromaStrength <= 0.001f || frame.averageIndex + 1 < frame.averageCount) return;
        const int passes = videoChromaPassCount(frame.videoChromaStrength);
        const auto* color = static_cast<const float*>(frame.spektraMapped);
        VideoChromaParameters parameters{0.0f, color[29], color[30], 0, {}, 1};
        const float coefficients[3][3] = {
            {0.2126f, 0.7152f, 0.0722f},
            {-0.2126f / 1.8556f, -0.7152f / 1.8556f, 0.5f},
            {0.5f, -0.7152f / 1.5748f, -0.0722f / 1.5748f}};
        for (size_t component = 0; component < 3; ++component)
            for (size_t channel = 0; channel < 3; ++channel) {
                const float gain = coefficients[component][channel] * color[channel];
                parameters.varianceWeights[component] += gain * gain;
            }
        vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, videoChromaPipeline_);
        for (int pass = 0; pass < passes; ++pass) {
            auto& output = pass == 1 ? frame.videoChromaTemporary : frame.videoChromaOutput;
            VkImageMemoryBarrier initialize{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            initialize.srcAccessMask = output.initialized ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
            initialize.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            initialize.oldLayout = output.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            initialize.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            initialize.srcQueueFamilyIndex = initialize.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialize.image = output.image;
            initialize.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 1, &ready, 0, nullptr, 1, &initialize);
            parameters.strength = std::clamp(frame.videoChromaStrength * 3.0f - pass, 0.0f, 1.0f);
            parameters.spacing = 1 << pass;
            parameters.encodeSdr = pass + 1 == passes ? frame.videoChromaEncodeSdr : 0;
            vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                videoChromaPipelineLayout_, 0, 1, &frame.videoChromaDescriptors[pass], 0, nullptr);
            vkCmdPushConstants(frame.rawCommandBuffer, videoChromaPipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                0, sizeof(parameters), &parameters);
            vkCmdDispatch(frame.rawCommandBuffer, (processingWidth_ + 15) / 16, (processingHeight_ + 15) / 16, 1);
            ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, 1, &ready, 0, nullptr, 0, nullptr);
            output.initialized = true;
        }
    }

    void ensureFocusPreviewPipelines()
    {
        if (!focusDescriptorLayout_) {
            std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
                {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = static_cast<uint32_t>(bindings.size());
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &focusDescriptorLayout_),
                    "Focus preview descriptor layout");
        }
        if (!focusPipelineLayout_) {
            VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FocusPushConstants)};
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &focusDescriptorLayout_;
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &range;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &focusPipelineLayout_), "Focus preview pipeline layout");
        }
        if (!focusPipeline_)
            focusPipeline_ = createComputeProgram(kFocusPeakingSpirv.data(), kFocusPeakingSpirv.size(), nullptr, focusPipelineLayout_);
        if (!focusOverlayPipeline_)
            focusOverlayPipeline_ = createComputeProgram(kFocusOverlaySpirv.data(), kFocusOverlaySpirv.size(), nullptr, focusPipelineLayout_);
    }

    void ensureFocusPreviewResources(Frame& frame)
    {
        ensureFocusPreviewPipelines();
        ensurePreviewTexture(frame.focusIntermediate, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        ensurePreviewTexture(frame.focusOutput, processingWidth_, processingHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        if (frame.focusPool) return;
        std::array<VkDescriptorPoolSize, 2> sizes{{
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2}}};
        VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool.maxSets = 2;
        pool.poolSizeCount = static_cast<uint32_t>(sizes.size());
        pool.pPoolSizes = sizes.data();
        require(vkCreateDescriptorPool(device_, &pool, nullptr, &frame.focusPool), "Focus preview descriptor pool");
        std::array<VkDescriptorSetLayout, 2> layouts{};
        layouts.fill(focusDescriptorLayout_);
        VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        allocate.descriptorPool = frame.focusPool;
        allocate.descriptorSetCount = 2;
        allocate.pSetLayouts = layouts.data();
        require(vkAllocateDescriptorSets(device_, &allocate, frame.focusDescriptors.data()), "Focus preview descriptors");
        for (size_t i = 0; i < 2; ++i) {
            VkDescriptorImageInfo input{rawIntegerSampler_, i == 0 ? frame.processingView : frame.focusIntermediate.view,
                VK_IMAGE_LAYOUT_GENERAL};
            VkDescriptorImageInfo output{VK_NULL_HANDLE,
                i == 0 ? frame.focusIntermediate.view : frame.focusOutput.view, VK_IMAGE_LAYOUT_GENERAL};
            std::array<VkWriteDescriptorSet, 2> writes{};
            for (uint32_t binding = 0; binding < 2; ++binding) {
                writes[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[binding].dstSet = frame.focusDescriptors[i];
                writes[binding].dstBinding = binding;
                writes[binding].descriptorCount = 1;
                writes[binding].descriptorType = binding == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                writes[binding].pImageInfo = binding == 0 ? &input : &output;
            }
            vkUpdateDescriptorSets(device_, 2, writes.data(), 0, nullptr);
        }
    }

    void recordFocusPreview(Frame& frame)
    {
        VkMemoryBarrier sourceReady{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        sourceReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
        sourceReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &sourceReady, 0, nullptr, 0, nullptr);
        for (PreviewTexture* texture : {&frame.focusIntermediate, &frame.focusOutput}) {
            VkImageMemoryBarrier initialize{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            initialize.srcAccessMask = texture->initialized ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
            initialize.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            initialize.oldLayout = texture->initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            initialize.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            initialize.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialize.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialize.image = texture->image;
            initialize.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(frame.commandBuffer, texture->initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &initialize);
            texture->initialized = true;
        }
        const auto* color = static_cast<const float*>(frame.spektraMapped);
        auto dispatch = [&](VkPipeline pipeline, size_t descriptor, const void* parameters, uint32_t size) {
            vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, focusPipelineLayout_,
                0, 1, &frame.focusDescriptors[descriptor], 0, nullptr);
            vkCmdPushConstants(frame.commandBuffer, focusPipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, size, parameters);
            vkCmdDispatch(frame.commandBuffer, (processingWidth_ + 15) / 16, (processingHeight_ + 15) / 16, 1);
            VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            ready.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 1, &ready, 0, nullptr, 0, nullptr);
        };
        FocusPushConstants parameters{static_cast<int32_t>(processingWidth_), static_cast<int32_t>(processingHeight_),
            frame.focusStrength, color[29], color[30],
            0.04519876f * color[0] * color[0] + 0.25575552f * color[1] * color[1] + 0.00521284f * color[2] * color[2], 0.0001f};
        dispatch(focusPipeline_, 0, &parameters, sizeof(parameters));
        dispatch(focusOverlayPipeline_, 1, &parameters, sizeof(parameters));
    }


    // Focus loupe: a small area of the full-resolution RAW frame rendered at native resolution and
    // drawn over the viewfinder. Only the copied area is processed, never the whole frame.
    struct FocusLoupePush {
        int32_t region[4];
        int32_t shape[4];
        float controls[4];
        float finish[4];
    };
    static_assert(sizeof(FocusLoupePush) == 64, "Focus loupe push constants");

    struct FocusLoupeDraw {
        float rect[4];
        float shape[4];
        int32_t transform[4];
    };
    static_assert(sizeof(FocusLoupeDraw) == 48, "Focus loupe draw constants");

    void ensureFocusLoupePipelines()
    {
        if (!loupeDescriptorLayout_) {
            const VkShaderStageFlags stage = VK_SHADER_STAGE_COMPUTE_BIT;
            std::array<VkDescriptorSetLayoutBinding, 11> bindings{{
                {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, stage, nullptr},
                {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, stage, nullptr},
                {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, stage, nullptr},
                {3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, stage, nullptr},
                {4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, stage, nullptr},
                {5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, stage, nullptr},
                {6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, stage, nullptr},
                {7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, stage, nullptr},
                {8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, stage, nullptr},
                {9, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, stage, nullptr},
                {10, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, stage, nullptr}}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = static_cast<uint32_t>(bindings.size());
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &loupeDescriptorLayout_),
                    "Focus loupe descriptor layout");
        }
        if (!loupePipelineLayout_) {
            VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FocusLoupePush)};
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &loupeDescriptorLayout_;
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &range;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &loupePipelineLayout_), "Focus loupe pipeline layout");
        }
        if (!loupePreparePipeline_)
            loupePreparePipeline_ = createComputeProgram(kFocusLoupePrepareSpirv.data(), kFocusLoupePrepareSpirv.size(),
                nullptr, loupePipelineLayout_);
        // The demosaic follows the device's preview quality path: bilinear on lightweight devices, MHC otherwise.
        const size_t quality = lightweightRawPreview() ? 1 : 0;
        if (!loupeDemosaicPipelines_[quality]) {
            loupeDemosaicPipelines_[quality] = quality == 1
                ? createComputeProgram(kFocusLoupeLightSpirv.data(), kFocusLoupeLightSpirv.size(), nullptr, loupePipelineLayout_)
                : createComputeProgram(kFocusLoupeDemosaicSpirv.data(), kFocusLoupeDemosaicSpirv.size(), nullptr, loupePipelineLayout_);
        }
        if (!loupeRemosaicPipeline_)
            loupeRemosaicPipeline_ = createComputeProgram(kFocusLoupeRemosaicSpirv.data(), kFocusLoupeRemosaicSpirv.size(),
                nullptr, loupePipelineLayout_);
        if (!loupeScorePipeline_)
            loupeScorePipeline_ = createComputeProgram(kFocusLoupeScoreSpirv.data(), kFocusLoupeScoreSpirv.size(),
                nullptr, loupePipelineLayout_);
        if (!loupeFinishPipeline_)
            loupeFinishPipeline_ = createComputeProgram(kFocusLoupeFinishSpirv.data(), kFocusLoupeFinishSpirv.size(),
                nullptr, loupePipelineLayout_);
    }

    void ensureFocusLoupeDrawPipeline()
    {
        if (!loupeDrawDescriptorLayout_) {
            std::array<VkDescriptorSetLayoutBinding, 1> bindings{{
                {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}}};
            VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = static_cast<uint32_t>(bindings.size());
            info.pBindings = bindings.data();
            require(vkCreateDescriptorSetLayout(device_, &info, nullptr, &loupeDrawDescriptorLayout_),
                    "Focus loupe draw descriptor layout");
        }
        if (!loupeDrawPipelineLayout_) {
            VkPushConstantRange range{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(FocusLoupeDraw)};
            VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            info.setLayoutCount = 1;
            info.pSetLayouts = &loupeDrawDescriptorLayout_;
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &range;
            require(vkCreatePipelineLayout(device_, &info, nullptr, &loupeDrawPipelineLayout_),
                    "Focus loupe draw pipeline layout");
        }
        if (!loupeDrawPipeline_ && renderPass_)
            loupeDrawPipeline_ = createGraphicsProgram(kFocusLoupeFragmentSpirv.data(), kFocusLoupeFragmentSpirv.size(),
                renderPass_, loupeDrawPipelineLayout_, nullptr, true);
    }

    void destroyFocusLoupeInput(FocusLoupeFrame& loupe)
    {
        if (loupe.inputMapped) vkUnmapMemory(device_, loupe.inputMemory);
        if (loupe.input) vkDestroyBuffer(device_, loupe.input, nullptr);
        if (loupe.inputMemory) vkFreeMemory(device_, loupe.inputMemory, nullptr);
        loupe.input = VK_NULL_HANDLE;
        loupe.inputMemory = VK_NULL_HANDLE;
        loupe.inputMapped = nullptr;
        loupe.inputCapacity = 0;
    }

    void destroyFocusLoupe(Frame& frame)
    {
        auto& loupe = frame.loupe;
        destroyFocusLoupeInput(loupe);
        if (loupe.scoreMapped) vkUnmapMemory(device_, loupe.scoreMemory);
        if (loupe.score) vkDestroyBuffer(device_, loupe.score, nullptr);
        if (loupe.scoreMemory) vkFreeMemory(device_, loupe.scoreMemory, nullptr);
        destroyPreviewTexture(loupe.mosaic);
        destroyPreviewTexture(loupe.output);
        destroyPreviewTexture(loupe.detail);
        destroyPreviewTexture(loupe.display);
        destroyPreviewTexture(loupe.source);
        if (loupe.pool) vkDestroyDescriptorPool(device_, loupe.pool, nullptr);
        loupe = {};
    }

    void ensureFocusLoupeResources(Frame& frame, const focus_loupe::Region& region, int block)
    {
        ensureFocusLoupePipelines();
        ensureFocusLoupeDrawPipeline();
        auto& loupe = frame.loupe;
        const VkDeviceSize inputBytes = static_cast<VkDeviceSize>(region.stride) *
            static_cast<VkDeviceSize>(region.height) * sizeof(uint16_t);
        if (inputBytes > loupe.inputCapacity) {
            destroyFocusLoupeInput(loupe);
            const VkDeviceSize capacity = std::max<VkDeviceSize>(inputBytes, 512u * 1024u);
            makeBuffer(capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                loupe.input, loupe.inputMemory, &loupe.inputMapped);
            loupe.inputCapacity = capacity;
        }
        if (!loupe.score) {
            makeBuffer(kLoupeScoreBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                loupe.score, loupe.scoreMemory, &loupe.scoreMapped);
            std::memset(loupe.scoreMapped, 0, kLoupeScoreBytes);
        }
        ensurePreviewTexture(loupe.mosaic, static_cast<uint32_t>(region.width), static_cast<uint32_t>(region.height),
            VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
        ensurePreviewTexture(loupe.output, static_cast<uint32_t>(region.visibleWidth),
            static_cast<uint32_t>(region.visibleHeight), VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
        ensurePreviewTexture(loupe.display, static_cast<uint32_t>(region.visibleWidth),
            static_cast<uint32_t>(region.visibleHeight), VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        ensurePreviewTexture(loupe.detail, static_cast<uint32_t>(region.visibleWidth),
            static_cast<uint32_t>(region.visibleHeight), VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
        // Quad Bayer samples before the remosaic pass. Bayer input only needs a placeholder.
        const bool remosaic = block > 1;
        ensurePreviewTexture(loupe.source, remosaic ? static_cast<uint32_t>(region.width) : 1u,
            remosaic ? static_cast<uint32_t>(region.height) : 1u, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
        if (!loupe.pool) {
            std::array<VkDescriptorPoolSize, 4> sizes{{
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3}, {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 3},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 5}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}}};
            VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pool.maxSets = 2;
            pool.poolSizeCount = static_cast<uint32_t>(sizes.size());
            pool.pPoolSizes = sizes.data();
            require(vkCreateDescriptorPool(device_, &pool, nullptr, &loupe.pool), "Focus loupe descriptor pool");
            std::array<VkDescriptorSetLayout, 2> layouts{loupeDescriptorLayout_, loupeDrawDescriptorLayout_};
            std::array<VkDescriptorSet, 2> sets{};
            VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocate.descriptorPool = loupe.pool;
            allocate.descriptorSetCount = 2;
            allocate.pSetLayouts = layouts.data();
            require(vkAllocateDescriptorSets(device_, &allocate, sets.data()), "Focus loupe descriptors");
            loupe.compute = sets[0];
            loupe.draw = sets[1];
        }
        // The frame's fence has completed, so its loupe sets are not in use.
        VkDescriptorBufferInfo input{loupe.input, 0, VK_WHOLE_SIZE};
        VkDescriptorBufferInfo raw{frame.rawUniform, 0, kRawParametersSize};
        VkDescriptorBufferInfo color{frame.spektraUniform, 0, kRawColorParametersSize};
        VkDescriptorBufferInfo shading{frame.lensShadingBuffer, 0, frame.lensShadingCapacity};
        VkDescriptorBufferInfo geometry{frame.geometryUniform, 0, kGeometryParametersSize};
        VkDescriptorBufferInfo score{loupe.score, 0, kLoupeScoreBytes};
        VkDescriptorImageInfo mosaic{VK_NULL_HANDLE, loupe.mosaic.view, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo output{VK_NULL_HANDLE, loupe.output.view, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo detail{VK_NULL_HANDLE, loupe.detail.view, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo display{VK_NULL_HANDLE, loupe.display.view, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo sampled{sampler_, loupe.display.view, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo source{VK_NULL_HANDLE, loupe.source.view, VK_IMAGE_LAYOUT_GENERAL};
        std::array<VkWriteDescriptorSet, 12> writes{};
        auto buffer = [&](size_t index, VkDescriptorSet set, uint32_t binding, VkDescriptorType type,
                          const VkDescriptorBufferInfo* info) {
            writes[index] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[index].dstSet = set;
            writes[index].dstBinding = binding;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = type;
            writes[index].pBufferInfo = info;
        };
        auto image = [&](size_t index, VkDescriptorSet set, uint32_t binding, VkDescriptorType type,
                         const VkDescriptorImageInfo* info) {
            writes[index] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[index].dstSet = set;
            writes[index].dstBinding = binding;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = type;
            writes[index].pImageInfo = info;
        };
        buffer(0, loupe.compute, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &input);
        buffer(1, loupe.compute, 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &raw);
        image(2, loupe.compute, 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &mosaic);
        buffer(3, loupe.compute, 3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &color);
        buffer(4, loupe.compute, 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &shading);
        image(5, loupe.compute, 5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &output);
        buffer(6, loupe.compute, 6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &geometry);
        buffer(7, loupe.compute, 7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &score);
        image(8, loupe.draw, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &sampled);
        image(9, loupe.compute, 8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &detail);
        image(10, loupe.compute, 9, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &display);
        image(11, loupe.compute, 10, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &source);
        vkUpdateDescriptorSets(device_, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // Header plus one edge energy and sample record per workgroup of the largest loupe.
    static constexpr VkDeviceSize kLoupeScoreBytes = 16 + 16 *
        static_cast<VkDeviceSize>(focus_loupe::kMaxVisible / 16) * (focus_loupe::kMaxVisible / 16);

    // Reads the sharpness measured when this frame slot was last rendered.
    void collectFocusLoupe(Frame& frame)
    {
        auto& loupe = frame.loupe;
        if (!loupe.scorePending || !loupe.scoreMapped) return;
        loupe.scorePending = false;
        uint32_t header[4]{};
        std::memcpy(header, loupe.scoreMapped, sizeof(header));
        if (header[2] == 0) return;
        const int groupsX = (loupe.region.visibleWidth + 15) / 16;
        const int groupsY = (loupe.region.visibleHeight + 15) / 16;
        const auto* groups = reinterpret_cast<const float*>(static_cast<const uint8_t*>(loupe.scoreMapped) + 16);
        focus_loupe::Reading reading;
        if (!focus_loupe::summarize(groups, groupsX, groupsY, loupeGroupFine_, reading)) return;
        std::lock_guard<std::mutex> lock(loupeMutex_);
        if (loupe.request.generation < static_cast<int64_t>(loupeResult_[0])) return;
        loupeResult_[0] = static_cast<float>(loupe.request.generation);
        loupeResult_[2] = reading.fine;
        loupeResult_[3] = static_cast<float>(header[1]) / (static_cast<float>(header[2]) * 1048576.0f);
        loupeResult_[9] = reading.flatFine;
        loupeResult_[11] = static_cast<float>(++loupeReadings_ & 0xFFFFFF);
        loupeResult_[12] = loupe.lightweight ? 1.0f : 0.0f;
        loupeResult_[13] = static_cast<float>(loupe.block);
    }

    // Copies the requested area of the RAW plane while the camera buffer is still held.
    void prepareFocusLoupe(Frame& frame, JNIEnv* environment, jobject packedRawBytesObject,
                           RawUploadPool::Lease* rawUpload, const RawParameters& raw,
                           const GeometryParameters& geometry, bool ispInput)
    {
        auto& loupe = frame.loupe;
        loupe.active = false;
        focus_loupe::Request request;
        {
            std::lock_guard<std::mutex> lock(loupeMutex_);
            request = loupeRequest_;
            if (!request.enabled) loupeResult_[1] = 0.0f;
        }
        if (!request.enabled) return;
        bool active = false;
        try {
            const uint8_t* plane = nullptr;
            size_t capacity = 0;
            bool reading = false;
            if (packedRawBytesObject != nullptr) {
                plane = static_cast<const uint8_t*>(environment->GetDirectBufferAddress(packedRawBytesObject));
                capacity = static_cast<size_t>(std::max<jlong>(0, environment->GetDirectBufferCapacity(packedRawBytesObject)));
            } else if (rawUpload != nullptr) {
                plane = static_cast<const uint8_t*>(rawUpload->beginRead());
                reading = plane != nullptr;
                capacity = rawUpload->size();
            }
            struct ReadScope {
                RawUploadPool::Lease* lease;
                bool active;
                ~ReadScope() { if (active) lease->endRead(); }
            } readScope{rawUpload, reading};
            const int format = raw.layoutInfo[1];
            if (!ispInput && plane != nullptr && format >= 0 && format <= 2) {
                focus_loupe::Source source;
                source.plane = plane;
                source.capacity = capacity;
                source.rowStride = raw.layoutInfo[0];
                source.format = format;
                source.width = raw.dimensions[0];
                source.height = raw.dimensions[1];
                for (int i = 0; i < 4; ++i) source.bounds[i] = geometry.rawBounds[i];
                source.block = std::max(1, raw.remosaic[0]);
                source.phaseX = source.block > 1 ? raw.remosaic[2] : 0;
                source.phaseY = source.block > 1 ? raw.remosaic[3] : 0;
                focus_loupe::Region region;
                if (focus_loupe::plan(source, request, region)) {
                    ensureFocusLoupeResources(frame, region, source.block);
                    if (focus_loupe::extract(source, region, static_cast<uint16_t*>(loupe.inputMapped),
                            static_cast<size_t>(loupe.inputCapacity / sizeof(uint16_t)), loupeColumns_)) {
                        std::memset(loupe.scoreMapped, 0, 16);
                        loupe.region = region;
                        loupe.lightweight = lightweightRawPreview();
                        loupe.request = request;
                        loupe.block = source.block;
                        loupe.orientation = geometry.transform[0] & 3;
                        loupe.mirror = geometry.transform[1] != 0 ? 1 : 0;
                        loupe.scorePending = true;
                        active = true;
                    }
                }
            }
        } catch (const std::exception& error) {
            if (!loupeErrorReported_) {
                loupeErrorReported_ = true;
                recordNativeDiagnostic(ANDROID_LOG_WARN, "FocusLoupe", "Unavailable: %s", error.what());
            }
        }
        loupe.active = active;
        std::lock_guard<std::mutex> lock(loupeMutex_);
        loupeResult_[1] = active ? 1.0f : 0.0f;
        if (active) {
            loupeResult_[4] = static_cast<float>(loupe.region.visibleWidth);
            loupeResult_[5] = static_cast<float>(loupe.region.visibleHeight);
            loupeResult_[6] = static_cast<float>(loupe.region.visibleX) + 0.5f * loupe.region.visibleWidth;
            loupeResult_[7] = static_cast<float>(loupe.region.visibleY) + 0.5f * loupe.region.visibleHeight;
        }
    }

    void recordFocusLoupe(Frame& frame)
    {
        auto& loupe = frame.loupe;
        const auto& region = loupe.region;
        for (PreviewTexture* texture : {&loupe.mosaic, &loupe.output, &loupe.detail, &loupe.display, &loupe.source}) {
            VkImageMemoryBarrier initialize{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            initialize.srcAccessMask = texture->initialized ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
            initialize.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            initialize.oldLayout = texture->initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            initialize.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            initialize.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialize.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialize.image = texture->image;
            initialize.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(frame.commandBuffer,
                texture->initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                                     : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &initialize);
            texture->initialized = true;
        }
        FocusLoupePush parameters{
            {region.originX, region.originY, region.width, region.height},
            {region.stride, region.halo, region.visibleWidth, region.visibleHeight},
            {loupe.request.noiseScale, loupe.request.noiseOffset, 1.0f, static_cast<float>(loupe.block)},
            {loupe.request.peaking, focus_loupe::fineNoise(loupe.lightweight), 0.0f, 0.0f}};
        auto dispatch = [&](VkPipeline pipeline, int width, int height, VkPipelineStageFlags next) {
            vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, loupePipelineLayout_,
                0, 1, &loupe.compute, 0, nullptr);
            vkCmdPushConstants(frame.commandBuffer, loupePipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                sizeof(parameters), &parameters);
            vkCmdDispatch(frame.commandBuffer, static_cast<uint32_t>((width + 15) / 16),
                static_cast<uint32_t>((height + 15) / 16), 1);
            VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                ((next & VK_PIPELINE_STAGE_HOST_BIT) ? VK_ACCESS_HOST_READ_BIT : 0);
            vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, next,
                0, 1, &ready, 0, nullptr, 0, nullptr);
        };
        const size_t quality = lightweightRawPreview() ? 1 : 0;
        dispatch(loupePreparePipeline_, region.width, region.height, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        if (loupe.block > 1)
            dispatch(loupeRemosaicPipeline_, region.width, region.height, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        dispatch(loupeDemosaicPipelines_[quality], region.visibleWidth, region.visibleHeight,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        dispatch(loupeScorePipeline_, region.visibleWidth, region.visibleHeight,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT);
        dispatch(loupeFinishPipeline_, region.visibleWidth, region.visibleHeight, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    void recordFocusLoupeDraw(Frame& frame)
    {
        auto& loupe = frame.loupe;
        if (!loupeDrawPipeline_) return;
        const float surfaceWidth = static_cast<float>(swapchainExtent_.width);
        const float surfaceHeight = static_cast<float>(swapchainExtent_.height);
        const auto& rect = loupe.request.rect;
        const float x = rect[0] * surfaceWidth;
        const float y = rect[1] * surfaceHeight;
        const float width = rect[2] * surfaceWidth;
        const float height = rect[3] * surfaceHeight;
        const int left = std::max(0, static_cast<int>(std::floor(x)));
        const int top = std::max(0, static_cast<int>(std::floor(y)));
        const int right = std::min(static_cast<int>(swapchainExtent_.width), static_cast<int>(std::ceil(x + width)));
        const int bottom = std::min(static_cast<int>(swapchainExtent_.height), static_cast<int>(std::ceil(y + height)));
        if (width < 8.0f || height < 8.0f || right - left < 1 || bottom - top < 1) return;
        VkViewport viewport{x, y, width, height, 0.0f, 1.0f};
        VkRect2D scissor{{left, top}, {static_cast<uint32_t>(right - left), static_cast<uint32_t>(bottom - top)}};
        vkCmdSetViewport(frame.commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(frame.commandBuffer, 0, 1, &scissor);
        vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, loupeDrawPipeline_);
        vkCmdBindDescriptorSets(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, loupeDrawPipelineLayout_,
            0, 1, &loupe.draw, 0, nullptr);
        const bool srgb = swapchainFormat_ == VK_FORMAT_B8G8R8A8_SRGB || swapchainFormat_ == VK_FORMAT_R8G8B8A8_SRGB;
        FocusLoupeDraw parameters{
            {x, y, width, height},
            {loupe.request.cornerRadius * width, std::clamp(loupe.request.opacity, 0.0f, 1.0f), srgb ? 1.0f : 0.0f,
             loupe.request.peaking},
            {loupe.orientation, loupe.mirror, loupe.region.visibleWidth, loupe.region.visibleHeight}};
        vkCmdPushConstants(frame.commandBuffer, loupeDrawPipelineLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
            sizeof(parameters), &parameters);
        vkCmdDraw(frame.commandBuffer, 3, 1, 0, 0);
    }

    void ensureChromaDenoisePipeline()
    {
        if (chromaPipeline_ != VK_NULL_HANDLE) return;
        std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
        bindings[0] = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        bindings[1] = {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        descriptorInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        descriptorInfo.pBindings = bindings.data();
        if (!chromaDescriptorLayout_) {
            require(vkCreateDescriptorSetLayout(device_, &descriptorInfo, nullptr, &chromaDescriptorLayout_),
                    "vkCreateDescriptorSetLayout for export chroma denoise");
        }
        VkPushConstantRange pushRange{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ChromaPushConstants)};
        VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &chromaDescriptorLayout_;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        if (!chromaPipelineLayout_) {
            require(vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &chromaPipelineLayout_),
                    "vkCreatePipelineLayout for export chroma denoise");
        }
        chromaPipeline_ = createComputeProgram(kPostChromaDenoiseSpirv.data(), kPostChromaDenoiseSpirv.size(),
                                              nullptr, chromaPipelineLayout_);
    }

    void ensureChromaDenoiseResources()
    {
        ensureChromaDenoisePipeline();
        if (chromaDescriptors_[1]) return;
        destroyChromaDenoiseResources();
        createAmazeImage(captureWidth_, captureHeight_, VK_FORMAT_R16G16B16A16_SFLOAT,
                      chromaImage_, chromaMemory_, chromaView_, VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        std::array<VkDescriptorPoolSize, 2> sizes{{
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2}}};
        VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        poolInfo.maxSets = 2;
        poolInfo.poolSizeCount = static_cast<uint32_t>(sizes.size());
        poolInfo.pPoolSizes = sizes.data();
        require(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &chromaDescriptorPool_),
                "vkCreateDescriptorPool for export chroma denoise");
        std::array<VkDescriptorSetLayout, 2> layouts{chromaDescriptorLayout_, chromaDescriptorLayout_};
        VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        allocate.descriptorPool = chromaDescriptorPool_;
        allocate.descriptorSetCount = 2;
        allocate.pSetLayouts = layouts.data();
        require(vkAllocateDescriptorSets(device_, &allocate, chromaDescriptors_.data()),
                "vkAllocateDescriptorSets for export chroma denoise");
        for (size_t i = 0; i < chromaDescriptors_.size(); ++i) {
            VkDescriptorImageInfo input{rawIntegerSampler_, i == 0 ? captureView_ : chromaView_, VK_IMAGE_LAYOUT_GENERAL};
            VkDescriptorImageInfo output{VK_NULL_HANDLE, i == 0 ? chromaView_ : captureView_, VK_IMAGE_LAYOUT_GENERAL};
            std::array<VkWriteDescriptorSet, 2> writes{};
            for (uint32_t binding = 0; binding < writes.size(); ++binding) {
                writes[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[binding].dstSet = chromaDescriptors_[i];
                writes[binding].dstBinding = binding;
                writes[binding].descriptorCount = 1;
                writes[binding].descriptorType = binding == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                writes[binding].pImageInfo = binding == 0 ? &input : &output;
            }
            vkUpdateDescriptorSets(device_, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
        }
    }

    void destroyChromaDenoiseResources()
    {
        if (chromaDescriptorPool_) vkDestroyDescriptorPool(device_, chromaDescriptorPool_, nullptr);
        if (chromaView_) vkDestroyImageView(device_, chromaView_, nullptr);
        if (chromaImage_) vkDestroyImage(device_, chromaImage_, nullptr);
        if (chromaMemory_) vkFreeMemory(device_, chromaMemory_, nullptr);
        chromaDescriptorPool_ = VK_NULL_HANDLE;
        chromaDescriptors_.fill(VK_NULL_HANDLE);
        chromaView_ = VK_NULL_HANDLE;
        chromaImage_ = VK_NULL_HANDLE;
        chromaMemory_ = VK_NULL_HANDLE;
        chromaInitialized_ = false;
    }

    VkImage recordChromaDenoise(Frame& frame, camera_gpu::ComputeRecorder& recorder)
    {
        if (captureChromaStrength_ <= 0.001f) return captureImage_;
        recorder.beginIndependentPass();
        VkImageMemoryBarrier initialize{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        initialize.srcAccessMask = chromaInitialized_ ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
        initialize.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        initialize.oldLayout = chromaInitialized_ ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
        initialize.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        initialize.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        initialize.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        initialize.image = chromaImage_;
        initialize.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(frame.commandBuffer,
            chromaInitialized_ ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &initialize);
        const auto* color = static_cast<const float*>(frame.spektraMapped);
        ChromaPushConstants pc{};
        pc.width = static_cast<int32_t>(captureWidth_);
        pc.height = static_cast<int32_t>(captureHeight_);
        pc.localContrastStrength = color[16] > 0.5f ? 1.0f : 0.0f;
        pc.pixelSizeX = 1.0f / static_cast<float>(captureWidth_);
        pc.pixelSizeY = 1.0f / static_cast<float>(captureHeight_);
        pc.noiseProfileS = color[29];
        pc.noiseProfileO = color[30];
        pc.wbR = color[0];
        pc.wbG = color[1];
        pc.wbB = color[2];
        const int activeLevels = std::clamp(static_cast<int>(std::ceil(captureChromaStrength_ * 6.0f)), 1, 6);
        int passCount = 0;
        recorder.bindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, chromaPipeline_);
        for (int level = 0; level < 6; ++level) {
            pc.chromaDenoiseStrength = std::clamp(captureChromaStrength_ * 6.0f - static_cast<float>(level), 0.0f, 1.0f);
            if (pc.chromaDenoiseStrength <= 0.001f) continue;
            pc.sharpenRadius = static_cast<float>(1 << level);
            pc.sharpenThreshold = level == activeLevels - 1 ? captureChromaStrength_ : 0.0f;
            recorder.bindDescriptors(frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                chromaPipelineLayout_, 0, 1, &chromaDescriptors_[passCount % 2], 0, nullptr);
            recorder.push(frame.commandBuffer, chromaPipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
            recorder.dispatch(frame.commandBuffer, (captureWidth_ + 15) / 16, (captureHeight_ + 15) / 16, 1);
            VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            ready.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &ready, 0, nullptr, 0, nullptr);
            ++passCount;
        }
        chromaInitialized_ = true;
        recordNativeDiagnostic(ANDROID_LOG_INFO, "CapturePipeline", "stage=chroma_denoise strength=%.3f passes=%d",
                               captureChromaStrength_, passCount);
        return passCount % 2 == 0 ? captureImage_ : chromaImage_;
    }

    VkImage recordCaptureCommands(Frame& frame)
    {
        camera_gpu::ComputeRecorder recorder(device_, frame.commandBuffer, [&](const VkSubmitInfo& submit, VkFence fence) {
            std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
            return vkQueueSubmit(queue_, 1, &submit, fence);
        });
        VkCommandBufferBeginInfo begin{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require(vkBeginCommandBuffer(frame.commandBuffer, &begin),
                "vkBeginCommandBuffer for capture");

        acquireRawForCompute(frame.commandBuffer, frame);
        recordHotPixels(frame.commandBuffer, frame, recorder);
        recordRemosaic(frame.commandBuffer, frame, recorder);

        VkImageMemoryBarrier toGeneral{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        toGeneral.srcAccessMask = captureInitialized_
            ? VK_ACCESS_SHADER_READ_BIT : 0;
        toGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toGeneral.oldLayout = captureInitialized_
            ? VK_IMAGE_LAYOUT_GENERAL
            : VK_IMAGE_LAYOUT_UNDEFINED;
        toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.image = captureImage_;
        toGeneral.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        toGeneral.subresourceRange.levelCount = 1;
        toGeneral.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            frame.commandBuffer,
            captureInitialized_ ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                                : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &toGeneral);

        recordAmazeStages(frame.commandBuffer, frame, recorder);
        if (frame.shadowStrength > 0.001f) recordShadowGuide(frame.commandBuffer, frame.shadows);

        recorder.bindPipeline(
            frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
            captureColorPipeline_);
        recorder.bindDescriptors(
            frame.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
            computePipelineLayout_, 0, 1, &frame.computeDescriptor,
            0, nullptr);
        recorder.dispatch(
            frame.commandBuffer,
            (captureWidth_ + 7) / 8,
            (captureHeight_ + 7) / 8,
            1);

        VkImageMemoryBarrier sourceReady{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        sourceReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        sourceReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        sourceReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        sourceReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        sourceReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceReady.image = captureImage_;
        sourceReady.subresourceRange = toGeneral.subresourceRange;
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &sourceReady);

        const VkImage outputImage = recordChromaDenoise(frame, recorder);

        if (frame.useSpektraOutput && !captureSpektraLayoutInitialized_) {
            VkImageMemoryBarrier initializeSpektra{
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            initializeSpektra.srcAccessMask = 0;
            initializeSpektra.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            initializeSpektra.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            initializeSpektra.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            initializeSpektra.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initializeSpektra.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initializeSpektra.image = captureSpektraImage_;
            initializeSpektra.subresourceRange = toGeneral.subresourceRange;
            vkCmdPipelineBarrier(
                frame.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                0, nullptr, 1, &initializeSpektra);
            captureSpektraLayoutInitialized_ = true;
        }

        releaseRawFromCompute(frame.commandBuffer, frame);

        require(vkEndCommandBuffer(frame.commandBuffer),
                "vkEndCommandBuffer for capture RAW processing");
        captureInitialized_ = true;
        return outputImage;
    }

    void recordCaptureReadbackCommands(Frame& frame, VkImage outputImage)
    {
        VkCommandBufferBeginInfo begin{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require(vkBeginCommandBuffer(frame.commandBuffer, &begin),
                "vkBeginCommandBuffer for capture readback");

        VkImageMemoryBarrier toTransfer{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT |
                                   VK_ACCESS_SHADER_READ_BIT;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        toTransfer.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.image = outputImage;
        toTransfer.subresourceRange = {
            VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &toTransfer);

        VkImageMemoryBarrier readbackToDestination{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        readbackToDestination.srcAccessMask = captureReadbackInitialized_
            ? VK_ACCESS_TRANSFER_READ_BIT : 0;
        readbackToDestination.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        readbackToDestination.oldLayout = captureReadbackInitialized_
            ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
            : VK_IMAGE_LAYOUT_UNDEFINED;
        readbackToDestination.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        readbackToDestination.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readbackToDestination.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readbackToDestination.image = captureReadbackImage_;
        readbackToDestination.subresourceRange = toTransfer.subresourceRange;
        vkCmdPipelineBarrier(
            frame.commandBuffer,
            captureReadbackInitialized_ ? VK_PIPELINE_STAGE_TRANSFER_BIT
                                        : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &readbackToDestination);

        VkImageBlit conversion{};
        conversion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        conversion.srcSubresource.layerCount = 1;
        conversion.srcOffsets[1] = {
            static_cast<int32_t>(captureWidth_),
            static_cast<int32_t>(captureHeight_), 1};
        conversion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        conversion.dstSubresource.layerCount = 1;
        conversion.dstOffsets[1] = conversion.srcOffsets[1];
        vkCmdBlitImage(
            frame.commandBuffer,
            outputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            captureReadbackImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &conversion, VK_FILTER_NEAREST);

        VkImageMemoryBarrier readbackToSource{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        readbackToSource.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        readbackToSource.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        readbackToSource.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        readbackToSource.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        readbackToSource.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readbackToSource.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readbackToSource.image = captureReadbackImage_;
        readbackToSource.subresourceRange = toTransfer.subresourceRange;
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &readbackToSource);

        VkBufferMemoryBarrier stagingForTransfer{
            VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        stagingForTransfer.srcAccessMask = VK_ACCESS_HOST_READ_BIT;
        stagingForTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        stagingForTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        stagingForTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        stagingForTransfer.buffer = captureStaging_;
        stagingForTransfer.offset = 0;
        stagingForTransfer.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
            1, &stagingForTransfer, 0, nullptr);

        VkBufferImageCopy copy{};
        copy.bufferOffset = 0;
        copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.imageSubresource.mipLevel = 0;
        copy.imageSubresource.baseArrayLayer = 0;
        copy.imageSubresource.layerCount = 1;
        copy.imageExtent = {captureWidth_, captureHeight_, 1};
        vkCmdCopyImageToBuffer(
            frame.commandBuffer, captureReadbackImage_,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, captureStaging_, 1, &copy);
        captureReadbackInitialized_ = true;

        VkImageMemoryBarrier backToGeneral{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        backToGeneral.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        backToGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                                      VK_ACCESS_SHADER_WRITE_BIT;
        backToGeneral.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        backToGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        backToGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        backToGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        backToGeneral.image = outputImage;
        backToGeneral.subresourceRange = toTransfer.subresourceRange;
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &backToGeneral);

        VkBufferMemoryBarrier stagingForHost{
            VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        stagingForHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        stagingForHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        stagingForHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        stagingForHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        stagingForHost.buffer = captureStaging_;
        stagingForHost.offset = 0;
        stagingForHost.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr,
            1, &stagingForHost, 0, nullptr);
        require(vkEndCommandBuffer(frame.commandBuffer),
                "vkEndCommandBuffer for capture readback");
    }

    void recordRawCommands(Frame& frame, const ExposureMeterExchange& meter, bool averageComplete)
    {
        const bool bounded = !frame.rasterRaw &&
            camera_gpu::boundedPreview(static_cast<uint64_t>(processingWidth_) * processingHeight_, frame.nativeRawQuad);
        camera_gpu::ComputeRecorder recorder(device_, frame.rawCommandBuffer,
            [this](const VkSubmitInfo& submit, VkFence fence) {
                std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
                return vkQueueSubmit(queue_, 1, &submit, fence);
            }, bounded);
        VkCommandBufferBeginInfo begin{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require(vkBeginCommandBuffer(frame.rawCommandBuffer, &begin),
                "vkBeginCommandBuffer");
        if (frame.ispActive) acquireIspForCompute(frame.rawCommandBuffer, frame);
        else acquireRawForCompute(frame.rawCommandBuffer, frame);
        frame.metrics.beginGpu(frame.rawCommandBuffer);
        recordHotPixels(frame.rawCommandBuffer, frame, recorder);
        recordRemosaic(frame.rawCommandBuffer, frame, recorder);
        frame.metrics.endRemosaicGpu(frame.rawCommandBuffer);
        recordPreviewTextureUpload(frame.rawCommandBuffer, previewFallbackTexture_);
        if (frame.textureShading) recordPreviewTextureUpload(frame.rawCommandBuffer, frame.lensTexture);
        const bool shadows = frame.shadowStrength > 0.001f;
        const bool recovery = frame.previewHighlightRecovery;
        const bool rawInitialized = recovery ? frame.recovery.source.initialized :
            shadows ? frame.shadows.source.initialized : frame.processingInitialized;
        const VkImage rawOutput = recovery ? frame.recovery.source.image :
            shadows ? frame.shadows.source.image : frame.processingImage;
        VkImageMemoryBarrier toGeneral{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        toGeneral.srcAccessMask = rawInitialized
            ? VK_ACCESS_SHADER_READ_BIT : 0;
        toGeneral.dstAccessMask = frame.rasterRaw ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
        toGeneral.oldLayout = rawInitialized
            ? VK_IMAGE_LAYOUT_GENERAL
            : VK_IMAGE_LAYOUT_UNDEFINED;
        toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.image = rawOutput;
        toGeneral.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        toGeneral.subresourceRange.levelCount = 1;
        toGeneral.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            frame.rawCommandBuffer,
            rawInitialized
                ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            frame.rasterRaw ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr,
            1, &toGeneral);
        if (frame.areaPrefilter && !frame.areaWeightsReady) {
            VkBufferMemoryBarrier weights{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
            weights.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            weights.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            weights.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            weights.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            weights.buffer = frame.areaWeights;
            weights.size = VK_WHOLE_SIZE;
            vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &weights, 0, nullptr);
            vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, rawAreaWeightsPipeline_);
            vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                computePipelineLayout_, 0, 1, &frame.computeDescriptor, 0, nullptr);
            vkCmdDispatch(frame.rawCommandBuffer, (frame.unpackedTexture.width / 2 + 63) / 64, 1, 1);
            weights.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            weights.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &weights, 0, nullptr);
        }
        if (frame.unpackRaw) {
            VkImageMemoryBarrier unpackBarrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            unpackBarrier.srcAccessMask = frame.unpackedTexture.initialized ? VK_ACCESS_SHADER_READ_BIT : 0;
            unpackBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            unpackBarrier.oldLayout = frame.unpackedTexture.initialized
                ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
            unpackBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            unpackBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            unpackBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            unpackBarrier.image = frame.unpackedTexture.image;
            unpackBarrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(frame.rawCommandBuffer, frame.unpackedTexture.initialized
                ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &unpackBarrier);
            vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, frame.unpackPipeline);
            vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                computePipelineLayout_, 0, 1, &frame.computeDescriptor, 0, nullptr);
            uint32_t width = frame.areaPrefilter ? frame.unpackedTexture.width / 2 : frame.unpackedTexture.width;
            uint32_t height = frame.unpackedTexture.height;
            if (frame.areaPrefilter || frame.mhcPrefilter) {
                const auto& raw = *static_cast<const RawParameters*>(frame.rawMapped);
                const auto& geometry = *static_cast<const GeometryParameters*>(frame.geometryMapped);
                const bool stabilization = frame.mhcPrefilter && geometry.stabilization[0] > 0.5f;
                const float* crop = stabilization ? geometry.stabilizationBounds : geometry.sourceCrop;
                const auto extent = [&](size_t axis, int halo, int alignment) {
                    const int first = std::max(static_cast<int>(std::floor(crop[axis] * raw.dimensions[axis])) - halo, 0) & ~(alignment - 1);
                    const int end = std::min(static_cast<int>(std::ceil((crop[axis] + crop[axis + 2]) * raw.dimensions[axis])) + halo, raw.dimensions[axis]);
                    return static_cast<uint32_t>(std::max(end - first, 1));
                };
                height = extent(1, frame.areaPrefilter || stabilization ? 2 : 0, frame.areaPrefilter ? 8 : 16);
                if (frame.mhcPrefilter) width = extent(0, stabilization ? 2 : 0, 16);
            }
            vkCmdDispatch(frame.rawCommandBuffer, (width + 15) / 16,
                frame.mhcPrefilter ? (height + 15) / 16 : (height + 7) / 8, 1);
            unpackBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            unpackBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            unpackBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            unpackBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &unpackBarrier);
        }
        frame.metrics.endPrepareGpu(frame.rawCommandBuffer);
        if (frame.rasterRaw) {
            VkRenderPassBeginInfo render{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            render.renderPass = rawRenderPass_;
            render.framebuffer = recovery ? frame.recovery.framebuffer :
                shadows ? frame.shadows.framebuffer : frame.processingFramebuffer;
            render.renderArea.extent = {processingWidth_, processingHeight_};
            vkCmdBeginRenderPass(frame.rawCommandBuffer, &render, VK_SUBPASS_CONTENTS_INLINE);
            VkViewport viewport{0.0f, 0.0f, static_cast<float>(processingWidth_),
                static_cast<float>(processingHeight_), 0.0f, 1.0f};
            VkRect2D scissor{{0, 0}, {processingWidth_, processingHeight_}};
            vkCmdSetViewport(frame.rawCommandBuffer, 0, 1, &viewport);
            vkCmdSetScissor(frame.rawCommandBuffer, 0, 1, &scissor);
            vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, frame.previewPipeline);
            vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                computePipelineLayout_, 0, 1, &frame.computeDescriptor, 0, nullptr);
            vkCmdDraw(frame.rawCommandBuffer, 3, 1, 0, 0);
            vkCmdEndRenderPass(frame.rawCommandBuffer);
        } else {
            recorder.bindPipeline(
                frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                frame.previewPipeline);
            recorder.bindDescriptors(
                frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                frame.ispActive ? ispPipelineLayout(frame) : computePipelineLayout_, 0, 1,
                frame.ispActive ? &ispDescriptor(frame) : &frame.computeDescriptor, 0, nullptr);
            if (frame.nativeRawQuad || frame.recordingNative) {
                const auto geometry = static_cast<const GeometryParameters*>(frame.geometryMapped);
                const bool swapsAxes = (geometry->transform[0] & 1) != 0;
                auto width = swapsAxes ? processingHeight_ : processingWidth_;
                auto height = swapsAxes ? processingWidth_ : processingHeight_;
                if (frame.recordingNative) {
                    const auto raw = static_cast<const RawParameters*>(frame.rawMapped);
                    width += static_cast<uint32_t>(std::lround(geometry->sourceCrop[0] * raw->dimensions[0])) & 1u;
                    height += static_cast<uint32_t>(std::lround(geometry->sourceCrop[1] * raw->dimensions[1])) & 1u;
                }
                recorder.dispatch(frame.rawCommandBuffer, (width + 15) / 16, (height + 15) / 16, 1);
            } else {
                recorder.dispatch(frame.rawCommandBuffer,
                                  (processingWidth_ + 7) / 8,
                                  (processingHeight_ + 7) / 8, 1);
            }
        }
        VkImageMemoryBarrier sourceReady{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        sourceReady.srcAccessMask = frame.rasterRaw ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
        sourceReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        sourceReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        sourceReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        sourceReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceReady.image = rawOutput;
        sourceReady.subresourceRange = toGeneral.subresourceRange;
        vkCmdPipelineBarrier(
            frame.rawCommandBuffer,
            frame.rasterRaw ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr,
            1, &sourceReady);

        if (recovery) recordPreviewRecovery(frame.rawCommandBuffer, frame);

        if (shadows) {
            frame.shadows.source.initialized = true;
            recordShadowGuide(frame.rawCommandBuffer, frame.shadows);
            VkImageMemoryBarrier target = toGeneral;
            target.srcAccessMask = frame.processingInitialized ? VK_ACCESS_SHADER_READ_BIT : 0;
            target.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            target.oldLayout = frame.processingInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            target.image = frame.processingImage;
            vkCmdPipelineBarrier(frame.rawCommandBuffer,
                frame.processingInitialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &target);
            vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, shadowColorPipeline_);
            vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                computePipelineLayout_, 0, 1, &frame.shadows.descriptors[3], 0, nullptr);
            vkCmdDispatch(frame.rawCommandBuffer, (processingWidth_ + 7) / 8, (processingHeight_ + 7) / 8, 1);
            sourceReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            sourceReady.image = frame.processingImage;
            vkCmdPipelineBarrier(frame.rawCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &sourceReady);
        }
        recordAverage(frame);
        frame.metrics.endRawGpu(frame.rawCommandBuffer);
        recordVideoChroma(frame);
        frame.metrics.endChromaGpu(frame.rawCommandBuffer);

        if (frame.useSpektraOutput && !frame.spektraLayoutInitialized) {
            VkImageMemoryBarrier initializeSpektra{
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            initializeSpektra.srcAccessMask = 0;
            initializeSpektra.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            initializeSpektra.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            initializeSpektra.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            initializeSpektra.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initializeSpektra.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initializeSpektra.image = frame.spektraImage;
            initializeSpektra.subresourceRange = toGeneral.subresourceRange;
            vkCmdPipelineBarrier(
                frame.rawCommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                0, nullptr, 1, &initializeSpektra);
            frame.spektraLayoutInitialized = true;
        }

        if (meter.requestId > 0) {
            VkRect2D region{{0, 0}, {processingWidth_, processingHeight_}};
            const bool image = processedRawKind(frame) == RawImportKind::Image;
            const auto pipeline = frame.ispActive ? (frame.ispImageInput ? isp_.imageMeterPipeline : isp_.bufferMeterPipeline) :
                prepareMeterPipeline(frame.bayerCells ? (videoOutput_ ? 4 : 3) :
                frame.remosaic.previewSized ? 2 : image ? 1 : 0);
            const VkPipelineLayout meterLayout = frame.ispActive ? ispPipelineLayout(frame) : computePipelineLayout_;
            const VkDescriptorSet* meterSet = frame.ispActive ? &ispDescriptor(frame) : &frame.computeDescriptor;
            const double scale = std::min(1.0, 256.0 / std::max(region.extent.width, region.extent.height));
            const uint32_t sampleWidth = std::max(1u, static_cast<uint32_t>(std::lround(region.extent.width * scale)));
            const uint32_t sampleHeight = std::max(1u, static_cast<uint32_t>(std::lround(region.extent.height * scale)));
            const uint32_t parameters[] = {static_cast<uint32_t>(region.offset.x), static_cast<uint32_t>(region.offset.y),
                region.extent.width, region.extent.height, sampleWidth, sampleHeight};
            vkCmdBindPipeline(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(frame.rawCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                meterLayout, 0, 1, meterSet, 0, nullptr);
            vkCmdPushConstants(frame.rawCommandBuffer, meterLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                0, sizeof(parameters), parameters);
            vkCmdDispatch(frame.rawCommandBuffer, (sampleWidth + 7) / 8, (sampleHeight + 7) / 8, 1);
            if (!spektraRenderer_->recordAutoExposure(frame.rawCommandBuffer, frameIndex_, frame.meterPixels,
                    region.extent.width, region.extent.height, static_cast<spektrafilm::AutoExposureMethod>(meter.method),
                    frame.meterResult, meter.x, meter.y)) {
                throw std::runtime_error("Exposure metering failed: " + spektraRenderer_->lastError());
            }
        }
        if (frame.ispActive) releaseIspFromCompute(frame.rawCommandBuffer, frame);
        else releaseRawFromCompute(frame.rawCommandBuffer, frame);

        if (!averageComplete) frame.metrics.endFrameGpu(frame.rawCommandBuffer);
        require(vkEndCommandBuffer(frame.rawCommandBuffer),
                "vkEndCommandBuffer for RAW processing");
        frame.processingInitialized = true;
    }

    void reportPresentSamples(Frame& frame)
    {
        auto& samples = frame.samples;
        if (!samples.ready) return;
        samples.ready = false;
        const auto* bytes = static_cast<const uint8_t*>(samples.mapped);
        uint32_t minimum = 255, maximum = 0, sum = 0, black = 0, zeroAlpha = 0;
        for (uint32_t index = 0; index < PresentSamples::kCount; ++index) {
            uint32_t peak = 0;
            for (uint32_t channel = 0; channel < 3; ++channel) {
                const uint32_t value = bytes[index * 4 + channel];
                minimum = std::min(minimum, value);
                maximum = std::max(maximum, value);
                sum += value;
                peak = std::max(peak, value);
            }
            if (peak <= 1) ++black;
            if (bytes[index * 4 + 3] == 0) ++zeroAlpha;
        }
        recordNativeDiagnostic(ANDROID_LOG_INFO, "PresentSamples",
            "renderer=%p sequence=%llu slot=%zu image=%u size=%ux%u vf_s=%d aspect=%.4f blur=%.3f "
            "min=%u max=%u mean=%.3f black=%u/%u zero_alpha=%u",
            static_cast<void*>(this), static_cast<unsigned long long>(samples.sequence),
            static_cast<size_t>(&frame - frames_.data()), samples.imageIndex,
            samples.extent.width, samples.extent.height, samples.spektra, samples.aspect, samples.blur,
            minimum, maximum, sum / (PresentSamples::kCount * 3.0), black, PresentSamples::kCount, zeroAlpha);
    }

    void recordPresentSamples(Frame& frame, uint32_t imageIndex,
                              const PreviewPresentation& presentation, float blurStrength)
    {
        auto& samples = frame.samples;
        samples.recorded = false;
#if !defined(NDEBUG)
        ++presentSampleSequence_;
        if (!presentSamplesSupported_ ||
            (presentSampleSequence_ > 4 && presentSampleSequence_ % 120 >= 2 &&
             samples.buffer != VK_NULL_HANDLE && samples.spektra == frame.useSpektraOutput &&
             samples.extent.width == swapchainExtent_.width && samples.extent.height == swapchainExtent_.height)) return;
        if (samples.buffer == VK_NULL_HANDLE) {
            makeBuffer(PresentSamples::kCount * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       samples.buffer, samples.memory, &samples.mapped);
        }
        samples.sequence = presentSampleSequence_;
        samples.imageIndex = imageIndex;
        samples.extent = swapchainExtent_;
        samples.spektra = frame.useSpektraOutput;
        samples.aspect = presentation.aspect;
        samples.blur = blurStrength;
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = swapchainImages_.at(imageIndex);
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
        std::array<VkBufferImageCopy, PresentSamples::kCount> regions{};
        const float contentWidth = std::min(static_cast<float>(samples.extent.width), samples.extent.height * presentation.aspect);
        const float contentHeight = std::min(static_cast<float>(samples.extent.height), samples.extent.width / presentation.aspect);
        for (uint32_t index = 0; index < regions.size(); ++index) {
            auto& region = regions[index];
            region.bufferOffset = index * 4;
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageOffset = {
                static_cast<int32_t>(samples.extent.width * 0.5f + ((index % 4) / 3.0f - 0.5f) * contentWidth * 0.6f),
                static_cast<int32_t>(samples.extent.height - contentHeight * 0.5f + ((index / 4) / 3.0f - 0.5f) * contentHeight * 0.6f), 0};
            region.imageExtent = {1, 1, 1};
        }
        vkCmdCopyImageToBuffer(frame.commandBuffer, barrier.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               samples.buffer, static_cast<uint32_t>(regions.size()), regions.data());
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        barrier.dstAccessMask = 0;
        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
        VkMemoryBarrier host{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                             0, 1, &host, 0, nullptr, 0, nullptr);
        samples.recorded = true;
#else
        (void)imageIndex;
        (void)presentation;
        (void)blurStrength;
#endif
    }

    void recordPresentCommands(
        Frame& frame,
        uint32_t imageIndex,
        VkImage outputImage,
        const PreviewPresentation& presentation,
        float blurStrength,
        bool processFocus = true,
        bool videoAcquired = false)
    {
        VkCommandBufferBeginInfo begin{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require(vkBeginCommandBuffer(frame.commandBuffer, &begin),
                "vkBeginCommandBuffer for presentation");

        if (frame.focusStrength > 0.5f && processFocus)
            recordFocusPreview(frame);
        // Lens switch frames reuse an older image, so they never show the loupe.
        const bool loupe = processFocus && frame.loupe.active && loupeDrawPipeline_ != VK_NULL_HANDLE;
        if (loupe) recordFocusLoupe(frame);

        if ((frame.focusStrength > 0.5f || frame.useSpektraOutput) && frame.exposureZebra > 0.5f) {
            VkMemoryBarrier exposureReady{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            exposureReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            exposureReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &exposureReady, 0, nullptr, 0, nullptr);
        }

        VkImageMemoryBarrier toSample{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        toSample.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT |
                                 VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        toSample.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toSample.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        toSample.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        toSample.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toSample.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toSample.image = outputImage;
        toSample.subresourceRange = {
            VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &toSample);

        VkClearValue clear{};
        clear.color.float32[3] = 1.0f;
        VkRenderPassBeginInfo renderBegin{
            VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        renderBegin.renderPass = renderPass_;
        renderBegin.framebuffer = framebuffers_.at(imageIndex);
        renderBegin.renderArea.extent = swapchainExtent_;
        renderBegin.clearValueCount = 1;
        renderBegin.pClearValues = &clear;
        vkCmdBeginRenderPass(
            frame.commandBuffer, &renderBegin, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport viewport{0.0f, 0.0f,
            static_cast<float>(swapchainExtent_.width),
            static_cast<float>(swapchainExtent_.height), 0.0f, 1.0f};
        VkRect2D scissor{{0, 0}, swapchainExtent_};
        vkCmdSetViewport(frame.commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(frame.commandBuffer, 0, 1, &scissor);
        vkCmdBindPipeline(frame.commandBuffer,
                          VK_PIPELINE_BIND_POINT_GRAPHICS, presentPipeline_);
        vkCmdBindDescriptorSets(
            frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
            presentPipelineLayout_, 0, 1, &frame.presentDescriptor, 0, nullptr);
        std::array<float, 12> presentationParameters{
            presentation.aspect,
            static_cast<float>(swapchainExtent_.width) /
                static_cast<float>(swapchainExtent_.height),
            swapchainFormat_ == VK_FORMAT_B8G8R8A8_SRGB ||
                    swapchainFormat_ == VK_FORMAT_R8G8B8A8_SRGB
                ? 1.0f : 0.0f,
            std::clamp(blurStrength, 0.0f, 1.0f),
            presentation.crop[0], presentation.crop[1],
            presentation.crop[2], presentation.crop[3],
            frame.exposureZebra, frame.focusStrength > 0.5f ? 1.0f : 0.0f,
            std::max(6.0f, std::min(swapchainExtent_.width, swapchainExtent_.height) / 80.0f),
            frame.useSpektraOutput ? 1.0f : 0.0f};
        vkCmdPushConstants(
            frame.commandBuffer, presentPipelineLayout_,
            VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(presentationParameters),
            presentationParameters.data());
        vkCmdDraw(frame.commandBuffer, 3, 1, 0, 0);
        if (loupe) recordFocusLoupeDraw(frame);
        vkCmdEndRenderPass(frame.commandBuffer);
        if (videoAcquired && videoOutput_) {
            if (frame.focusStrength > 0.5f) {
                VkMemoryBarrier videoReady{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
                videoReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                videoReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                vkCmdPipelineBarrier(frame.commandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &videoReady, 0, nullptr, 0, nullptr);
            }
            videoOutput_->record(frame.commandBuffer,
                frame.focusStrength > 0.5f ? frame.videoDescriptor : frame.presentDescriptor,
                processingWidth_ < processingHeight_);
        }
        frame.metrics.endFrameGpu(frame.commandBuffer);
        recordPresentSamples(frame, imageIndex, presentation, blurStrength);

        VkImageMemoryBarrier backToGeneral{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        backToGeneral.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        backToGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                                      VK_ACCESS_SHADER_WRITE_BIT;
        backToGeneral.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        backToGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        backToGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        backToGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        backToGeneral.image = outputImage;
        backToGeneral.subresourceRange = toSample.subresourceRange;
        vkCmdPipelineBarrier(
            frame.commandBuffer, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            1, &backToGeneral);
        require(vkEndCommandBuffer(frame.commandBuffer),
                "vkEndCommandBuffer for presentation");
    }

    static void injectAcquisitionFailure()
    {
#if !defined(NDEBUG)
        const char* value = std::getenv("UNSPEKTRA_FAIL_AFTER_ACQUIRE");
        if (value != nullptr && std::strcmp(value, "1") == 0)
            throw std::runtime_error("Injected failure after image acquisition");
#endif
    }

    void drainAcquiredImages()
    {
        for (Frame& frame : frames_) {
            if (!frame.acquirePending) continue;
            // An aborted frame still owns the acquire signal, even after swapchain recreation.
            VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            VkSubmitInfo drain{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            drain.waitSemaphoreCount = 1;
            drain.pWaitSemaphores = &frame.imageAvailable;
            drain.pWaitDstStageMask = &waitStage;
            std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
            require(vkQueueSubmit(queue_, 1, &drain, VK_NULL_HANDLE), "vkQueueSubmit to drain abandoned acquisition");
            frame.acquirePending = false;
            require(vkQueueWaitIdle(queue_), "vkQueueWaitIdle to drain abandoned acquisition");
            recordNativeDiagnostic(ANDROID_LOG_INFO, "CameraRecovery", "Abandoned image acquisition drained");
        }
    }

    void recreateSwapchain()
    {
        swapchainRecoveryPending_ = true;
        drainAcquiredImages();
        require(vkDeviceWaitIdle(device_), "vkDeviceWaitIdle");
        destroySwapchain();
        createSwapchain();
        swapchainRecoveryPending_ = false;
    }

    void destroySwapchain()
    {
        averageLastIndex_ = -1;
        if (spektraRenderer_) spektraRenderer_->resetPreviewScheduling();
        for (Frame& frame : frames_) {
            reportPresentSamples(frame);
            frame.metrics.cancel();
        }
        for (VkSemaphore semaphore : presentSemaphores_)
            if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(device_, semaphore, nullptr);
        presentSemaphores_.clear();
        for (VkFramebuffer framebuffer : framebuffers_)
            vkDestroyFramebuffer(device_, framebuffer, nullptr);
        framebuffers_.clear();
        if (presentPipeline_ != VK_NULL_HANDLE)
            vkDestroyPipeline(device_, presentPipeline_, nullptr);
        presentPipeline_ = VK_NULL_HANDLE;
        // The loupe draw pipeline belongs to the presentation render pass.
        if (loupeDrawPipeline_ != VK_NULL_HANDLE)
            vkDestroyPipeline(device_, loupeDrawPipeline_, nullptr);
        loupeDrawPipeline_ = VK_NULL_HANDLE;
        if (presentPipelineLayout_ != VK_NULL_HANDLE)
            vkDestroyPipelineLayout(device_, presentPipelineLayout_, nullptr);
        presentPipelineLayout_ = VK_NULL_HANDLE;
        if (renderPass_ != VK_NULL_HANDLE)
            vkDestroyRenderPass(device_, renderPass_, nullptr);
        renderPass_ = VK_NULL_HANDLE;
        for (VkImageView view : swapchainViews_)
            vkDestroyImageView(device_, view, nullptr);
        swapchainViews_.clear();
        swapchainImages_.clear();
        if (swapchain_ != VK_NULL_HANDLE)
            vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }

    void destroyProcessingImages()
    {
        drainHistogram();
        ++processingImageGeneration_;
        averageLastIndex_ = -1;
        destroyPreviewTexture(averageHistory_);
        for (Frame& frame : frames_) {
            if (frame.averagePool) vkDestroyDescriptorPool(device_, frame.averagePool, nullptr);
            frame.averagePool = VK_NULL_HANDLE;
            frame.averageDescriptor = VK_NULL_HANDLE;
            frame.averageCount = 1;
            if (frame.videoChromaPool) vkDestroyDescriptorPool(device_, frame.videoChromaPool, nullptr);
            frame.videoChromaPool = VK_NULL_HANDLE;
            frame.videoChromaDescriptors = {};
            frame.videoChromaStrength = 0.0f;
            destroyPreviewTexture(frame.videoChromaOutput);
            destroyPreviewTexture(frame.videoChromaTemporary);
            destroyPreviewRecoveryResources(frame);
            frame.previewHighlightRecovery = false;
            destroyShadowResources(frame);
            destroyPreviewTexture(frame.focusIntermediate);
            destroyPreviewTexture(frame.focusOutput);
            if (frame.focusPool) vkDestroyDescriptorPool(device_, frame.focusPool, nullptr);
            frame.focusPool = VK_NULL_HANDLE;
            frame.focusDescriptors.fill(VK_NULL_HANDLE);
            frame.focusStrength = 0.0f;
            frame.computeDescriptorsDirty = true;
            if (frame.processingFramebuffer != VK_NULL_HANDLE)
                vkDestroyFramebuffer(device_, frame.processingFramebuffer, nullptr);
            frame.processingFramebuffer = VK_NULL_HANDLE;
            if (frame.spektraView != VK_NULL_HANDLE)
                vkDestroyImageView(device_, frame.spektraView, nullptr);
            if (frame.spektraImage != VK_NULL_HANDLE)
                vkDestroyImage(device_, frame.spektraImage, nullptr);
            if (frame.spektraMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.spektraMemory, nullptr);
            if (frame.processingView != VK_NULL_HANDLE)
                vkDestroyImageView(device_, frame.processingView, nullptr);
            if (frame.processingImage != VK_NULL_HANDLE)
                vkDestroyImage(device_, frame.processingImage, nullptr);
            if (frame.processingMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.processingMemory, nullptr);
            frame.processingView = VK_NULL_HANDLE;
            frame.processingImage = VK_NULL_HANDLE;
            frame.processingMemory = VK_NULL_HANDLE;
            frame.processingInitialized = false;
            frame.spektraView = VK_NULL_HANDLE;
            frame.spektraImage = VK_NULL_HANDLE;
            frame.spektraMemory = VK_NULL_HANDLE;
            frame.spektraLayoutInitialized = false;
            frame.spektraInitialized = false;
            frame.useSpektraOutput = false;
        }
        processingWidth_ = 0;
        processingHeight_ = 0;
        hasPresentedFrame_ = false;
        ++presentationEpoch_;
        lastPresentedImage_ = VK_NULL_HANDLE;
    }

    void destroyDevice()
    {
        gpuPerformance_.stop();
        if (device_ == VK_NULL_HANDLE)
            return;
        vkDeviceWaitIdle(device_);
        spektraRenderer_.reset();
        destroyRawImageProbe();
        destroyIspResources();
        if (videoChromaPipeline_) vkDestroyPipeline(device_, videoChromaPipeline_, nullptr);
        if (videoChromaPipelineLayout_) vkDestroyPipelineLayout(device_, videoChromaPipelineLayout_, nullptr);
        if (videoChromaDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, videoChromaDescriptorLayout_, nullptr);
        videoChromaPipeline_ = VK_NULL_HANDLE;
        videoChromaPipelineLayout_ = VK_NULL_HANDLE;
        videoChromaDescriptorLayout_ = VK_NULL_HANDLE;
        if (focusPipeline_) vkDestroyPipeline(device_, focusPipeline_, nullptr);
        if (focusOverlayPipeline_) vkDestroyPipeline(device_, focusOverlayPipeline_, nullptr);
        if (focusPipelineLayout_) vkDestroyPipelineLayout(device_, focusPipelineLayout_, nullptr);
        if (focusDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, focusDescriptorLayout_, nullptr);
        focusPipeline_ = VK_NULL_HANDLE;
        focusOverlayPipeline_ = VK_NULL_HANDLE;
        focusPipelineLayout_ = VK_NULL_HANDLE;
        focusDescriptorLayout_ = VK_NULL_HANDLE;
        if (loupePreparePipeline_) vkDestroyPipeline(device_, loupePreparePipeline_, nullptr);
        for (VkPipeline pipeline : loupeDemosaicPipelines_) if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
        if (loupeRemosaicPipeline_) vkDestroyPipeline(device_, loupeRemosaicPipeline_, nullptr);
        if (loupeScorePipeline_) vkDestroyPipeline(device_, loupeScorePipeline_, nullptr);
        if (loupeFinishPipeline_) vkDestroyPipeline(device_, loupeFinishPipeline_, nullptr);
        if (loupeDrawPipeline_) vkDestroyPipeline(device_, loupeDrawPipeline_, nullptr);
        if (loupePipelineLayout_) vkDestroyPipelineLayout(device_, loupePipelineLayout_, nullptr);
        if (loupeDrawPipelineLayout_) vkDestroyPipelineLayout(device_, loupeDrawPipelineLayout_, nullptr);
        if (loupeDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, loupeDescriptorLayout_, nullptr);
        if (loupeDrawDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, loupeDrawDescriptorLayout_, nullptr);
        loupePreparePipeline_ = VK_NULL_HANDLE;
        loupeDemosaicPipelines_ = {};
        loupeRemosaicPipeline_ = VK_NULL_HANDLE;
        loupeScorePipeline_ = VK_NULL_HANDLE;
        loupeFinishPipeline_ = VK_NULL_HANDLE;
        loupeDrawPipeline_ = VK_NULL_HANDLE;
        loupePipelineLayout_ = VK_NULL_HANDLE;
        loupeDrawPipelineLayout_ = VK_NULL_HANDLE;
        loupeDescriptorLayout_ = VK_NULL_HANDLE;
        loupeDrawDescriptorLayout_ = VK_NULL_HANDLE;
        for (Frame& frame : frames_)
            releaseImportedBuffer(frame);
        releaseImportedBuffer(captureFrame_);
        rawUploads_->detach();
        destroyProcessingImages();
        destroyAmazeResources();
        destroyCaptureResources();
        for (auto& pipeline : previewRecoveryPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (previewRecoveryPipelineLayout_) vkDestroyPipelineLayout(device_, previewRecoveryPipelineLayout_, nullptr);
        previewRecoveryPipelineLayout_ = VK_NULL_HANDLE;
        if (previewRecoveryDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, previewRecoveryDescriptorLayout_, nullptr);
        previewRecoveryDescriptorLayout_ = VK_NULL_HANDLE;
        for (auto& pipeline : shadowPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (shadowColorPipeline_) vkDestroyPipeline(device_, shadowColorPipeline_, nullptr);
        shadowColorPipeline_ = VK_NULL_HANDLE;
        for (auto& layout : shadowPipelineLayouts_) {
            if (layout) vkDestroyPipelineLayout(device_, layout, nullptr);
            layout = VK_NULL_HANDLE;
        }
        for (auto& layout : shadowDescriptorLayouts_) {
            if (layout) vkDestroyDescriptorSetLayout(device_, layout, nullptr);
            layout = VK_NULL_HANDLE;
        }
        destroySwapchain();
        if (averagePipeline_) vkDestroyPipeline(device_, averagePipeline_, nullptr);
        if (averagePipelineLayout_) vkDestroyPipelineLayout(device_, averagePipelineLayout_, nullptr);
        if (averageDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, averageDescriptorLayout_, nullptr);
        averagePipeline_ = VK_NULL_HANDLE;
        averagePipelineLayout_ = VK_NULL_HANDLE;
        averageDescriptorLayout_ = VK_NULL_HANDLE;
        destroyPreviewTexture(previewFallbackTexture_);
        if (histogramPipeline_) vkDestroyPipeline(device_, histogramPipeline_, nullptr);
        if (histogramPipelineLayout_) vkDestroyPipelineLayout(device_, histogramPipelineLayout_, nullptr);
        if (histogramDescriptorPool_) vkDestroyDescriptorPool(device_, histogramDescriptorPool_, nullptr);
        if (histogramDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, histogramDescriptorLayout_, nullptr);
        if (histogramFence_) vkDestroyFence(device_, histogramFence_, nullptr);
        histogramPipeline_ = VK_NULL_HANDLE;
        histogramPipelineLayout_ = VK_NULL_HANDLE;
        histogramDescriptorPool_ = VK_NULL_HANDLE;
        histogramDescriptorLayout_ = VK_NULL_HANDLE;
        histogramDescriptor_ = VK_NULL_HANDLE;
        histogramFence_ = VK_NULL_HANDLE;
        histogramBinsRequest_ = false;
        histogramGpuAvailable_ = false;
        destroyPreviewTexture(histogramTexture_);
        histogramCommand_ = VK_NULL_HANDLE;
        for (Frame& frame : frames_) {
            frame.metrics.destroy(device_);
            destroyPreviewTexture(frame.lensTexture);
            destroyPreviewTexture(frame.unpackedTexture);
            if (frame.areaWeights) vkDestroyBuffer(device_, frame.areaWeights, nullptr);
            if (frame.areaWeightsMemory) vkFreeMemory(device_, frame.areaWeightsMemory, nullptr);
            frame.areaWeights = VK_NULL_HANDLE;
            frame.areaWeightsMemory = VK_NULL_HANDLE;
            frame.areaWeightsCapacity = 0;
            destroyPackedRawBuffer(frame);
            destroyRawBufferResources(frame.remosaic);
            destroyRawBufferResources(frame.hotPixels);
            releaseIspImage(frame);
            destroyIspPlanes(frame);
            if (frame.ispMapped != nullptr) vkUnmapMemory(device_, frame.ispUniformMemory);
            if (frame.ispUniform != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.ispUniform, nullptr);
            if (frame.ispUniformMemory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.ispUniformMemory, nullptr);
            if (frame.samples.mapped != nullptr) vkUnmapMemory(device_, frame.samples.memory);
            if (frame.samples.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.samples.buffer, nullptr);
            if (frame.samples.memory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.samples.memory, nullptr);
            if (frame.rawMapped != nullptr)
                vkUnmapMemory(device_, frame.rawUniformMemory);
            if (frame.spektraMapped != nullptr)
                vkUnmapMemory(device_, frame.spektraUniformMemory);
            if (frame.geometryMapped != nullptr)
                vkUnmapMemory(device_, frame.geometryUniformMemory);
            if (frame.rawUniform != VK_NULL_HANDLE)
                vkDestroyBuffer(device_, frame.rawUniform, nullptr);
            if (frame.rawUniformMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.rawUniformMemory, nullptr);
            if (frame.spektraUniform != VK_NULL_HANDLE)
                vkDestroyBuffer(device_, frame.spektraUniform, nullptr);
            if (frame.spektraUniformMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.spektraUniformMemory, nullptr);
            if (frame.geometryUniform != VK_NULL_HANDLE)
                vkDestroyBuffer(device_, frame.geometryUniform, nullptr);
            if (frame.geometryUniformMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.geometryUniformMemory, nullptr);
            if (frame.exposureRange != VK_NULL_HANDLE)
                vkDestroyBuffer(device_, frame.exposureRange, nullptr);
            if (frame.exposureRangeMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.exposureRangeMemory, nullptr);
            if (frame.meterMapped != nullptr) vkUnmapMemory(device_, frame.meterMemory);
            if (frame.meterPixels != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.meterPixels, nullptr);
            if (frame.meterPixelsMemory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.meterPixelsMemory, nullptr);
            if (frame.meterResult != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.meterResult, nullptr);
            if (frame.meterMemory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.meterMemory, nullptr);
            if (frame.lensShadingMapped != nullptr)
                vkUnmapMemory(device_, frame.lensShadingMemory);
            if (frame.lensShadingBuffer != VK_NULL_HANDLE)
                vkDestroyBuffer(device_, frame.lensShadingBuffer, nullptr);
            if (frame.lensShadingMemory != VK_NULL_HANDLE)
                vkFreeMemory(device_, frame.lensShadingMemory, nullptr);
            if (frame.imageAvailable != VK_NULL_HANDLE)
                vkDestroySemaphore(device_, frame.imageAvailable, nullptr);
            if (frame.fence != VK_NULL_HANDLE)
                vkDestroyFence(device_, frame.fence, nullptr);
            destroyFocusLoupe(frame);
            frame = {};
        }
        destroyPackedRawBuffer(captureFrame_);
        destroyRawBufferResources(captureFrame_.remosaic);
        destroyRawBufferResources(captureFrame_.hotPixels);
        if (captureFrame_.rawMapped != nullptr)
            vkUnmapMemory(device_, captureFrame_.rawUniformMemory);
        if (captureFrame_.spektraMapped != nullptr)
            vkUnmapMemory(device_, captureFrame_.spektraUniformMemory);
        if (captureFrame_.geometryMapped != nullptr)
            vkUnmapMemory(device_, captureFrame_.geometryUniformMemory);
        if (captureFrame_.lensShadingMapped != nullptr)
            vkUnmapMemory(device_, captureFrame_.lensShadingMemory);
        if (captureFrame_.rawUniform != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, captureFrame_.rawUniform, nullptr);
        if (captureFrame_.rawUniformMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureFrame_.rawUniformMemory, nullptr);
        if (captureFrame_.spektraUniform != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, captureFrame_.spektraUniform, nullptr);
        if (captureFrame_.spektraUniformMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureFrame_.spektraUniformMemory, nullptr);
        if (captureFrame_.geometryUniform != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, captureFrame_.geometryUniform, nullptr);
        if (captureFrame_.geometryUniformMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureFrame_.geometryUniformMemory, nullptr);
        if (captureFrame_.lensShadingBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device_, captureFrame_.lensShadingBuffer, nullptr);
        if (captureFrame_.lensShadingMemory != VK_NULL_HANDLE)
            vkFreeMemory(device_, captureFrame_.lensShadingMemory, nullptr);
        if (captureFrame_.fence != VK_NULL_HANDLE)
            vkDestroyFence(device_, captureFrame_.fence, nullptr);
        captureFrame_ = {};
        if (rawIntegerSampler_ != VK_NULL_HANDLE)
            vkDestroySampler(device_, rawIntegerSampler_, nullptr);
        if (sampler_ != VK_NULL_HANDLE)
            vkDestroySampler(device_, sampler_, nullptr);
        if (chromaPipeline_) vkDestroyPipeline(device_, chromaPipeline_, nullptr);
        chromaPipeline_ = VK_NULL_HANDLE;
        if (chromaPipelineLayout_) vkDestroyPipelineLayout(device_, chromaPipelineLayout_, nullptr);
        chromaPipelineLayout_ = VK_NULL_HANDLE;
        if (chromaDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, chromaDescriptorLayout_, nullptr);
        chromaDescriptorLayout_ = VK_NULL_HANDLE;
        destroyAmazePipelines();
        if (amazePipelineLayout_ != VK_NULL_HANDLE)
            vkDestroyPipelineLayout(device_, amazePipelineLayout_, nullptr);
        if (amazeDescriptorPool_ != VK_NULL_HANDLE)
            vkDestroyDescriptorPool(device_, amazeDescriptorPool_, nullptr);
        if (amazeDescriptorLayout_ != VK_NULL_HANDLE)
            vkDestroyDescriptorSetLayout(
                device_, amazeDescriptorLayout_, nullptr);
        for (VkPipeline& pipeline : meterPipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        for (VkPipeline& pipeline : previewCompatiblePipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        for (VkPipeline& pipeline : bayerCellPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        for (VkPipeline& pipeline : recordingPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        for (VkPipeline& pipeline : nativeRecordingPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        nativeRecordingRasterRejected_.fill(false);
        recordingPipelineRejected_.fill(false);
        for (VkPipeline& pipeline : previewPipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        previewShadingRejected_.fill(false);
        for (VkPipeline& pipeline : previewRasterPipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        previewRasterRejected_.fill(false);
        for (VkPipeline& pipeline : hotPixelPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (hotPixelPipelineLayout_) vkDestroyPipelineLayout(device_, hotPixelPipelineLayout_, nullptr);
        if (hotPixelDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, hotPixelDescriptorLayout_, nullptr);
        hotPixelPipelineLayout_ = VK_NULL_HANDLE;
        hotPixelDescriptorLayout_ = VK_NULL_HANDLE;
        for (VkPipeline& pipeline : remosaicPipelines_) {
            if (pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (remosaicPipelineLayout_) vkDestroyPipelineLayout(device_, remosaicPipelineLayout_, nullptr);
        if (remosaicDescriptorLayout_) vkDestroyDescriptorSetLayout(device_, remosaicDescriptorLayout_, nullptr);
        remosaicPipelineLayout_ = VK_NULL_HANDLE;
        remosaicDescriptorLayout_ = VK_NULL_HANDLE;
        if (rawAreaWeightsPipeline_) vkDestroyPipeline(device_, rawAreaWeightsPipeline_, nullptr);
        rawAreaWeightsPipeline_ = VK_NULL_HANDLE;
        for (VkPipeline& pipeline : rawAreaPipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        for (VkPipeline& pipeline : nativeRawPipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        nativeRawRejected_.fill(false);
        for (VkPipeline& pipeline : rawUnpackPipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (rawRenderPass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, rawRenderPass_, nullptr);
        rawRenderPass_ = VK_NULL_HANDLE;
        if (captureColorPipeline_ != VK_NULL_HANDLE)
            vkDestroyPipeline(device_, captureColorPipeline_, nullptr);
        if (computePipelineLayout_ != VK_NULL_HANDLE)
            vkDestroyPipelineLayout(device_, computePipelineLayout_, nullptr);
        if (descriptorPool_ != VK_NULL_HANDLE)
            vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
        if (presentDescriptorLayout_ != VK_NULL_HANDLE)
            vkDestroyDescriptorSetLayout(
                device_, presentDescriptorLayout_, nullptr);
        if (computeDescriptorLayout_ != VK_NULL_HANDLE)
            vkDestroyDescriptorSetLayout(
                device_, computeDescriptorLayout_, nullptr);
        if (commandPool_ != VK_NULL_HANDLE)
            vkDestroyCommandPool(device_, commandPool_, nullptr);
        pipelineCache_.destroy();
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        queue_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        frameIndex_ = 0;
        hasPresentedFrame_ = false;
        ++presentationEpoch_;
        lastPresentedImage_ = VK_NULL_HANDLE;
        lensSwitchRevealPending_ = false;
        lensSwitchRevealActive_ = false;
        rawIntegerSampler_ = VK_NULL_HANDLE;
        sampler_ = VK_NULL_HANDLE;
        captureColorPipeline_ = VK_NULL_HANDLE;
        amazePipelineLayout_ = VK_NULL_HANDLE;
        amazeDescriptorPool_ = VK_NULL_HANDLE;
        amazeDescriptorLayout_ = VK_NULL_HANDLE;
        amazeDescriptorSets_.fill(VK_NULL_HANDLE);
        computePipelineLayout_ = VK_NULL_HANDLE;
        descriptorPool_ = VK_NULL_HANDLE;
        presentDescriptorLayout_ = VK_NULL_HANDLE;
        computeDescriptorLayout_ = VK_NULL_HANDLE;
        commandPool_ = VK_NULL_HANDLE;
        getAhbProperties_ = nullptr;
    }

public:
    bool setVideoSurface(JNIEnv* environment, jobject surface, uint32_t width, uint32_t height, bool fullRange)
    {
        std::lock_guard<std::mutex> operationLock(operationMutex_);
        std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
        videoOutput_.reset();
        videoFailed_ = false;
        if (!surface) return true;
        try {
            if (!device_ || !swapchain_ || !displayTimingSupported_)
                throw std::runtime_error("Timed Vulkan video presentation is unavailable");
            videoOutput_ = std::make_unique<VideoOutput>(instance_, physicalDevice_, device_, queue_, queueFamily_, kFramesInFlight);
            videoOutput_->create(environment, surface, width, height, fullRange, presentDescriptorLayout_,
                [this](VkRenderPass renderPass, VkPipelineLayout layout) {
                    return createGraphicsProgram(kVideoFragmentSpirv.data(), kVideoFragmentSpirv.size(), renderPass, layout, nullptr);
                });
            return true;
        } catch (const std::exception& error) {
            failVideo(error.what());
            return false;
        }
    }

    int64_t presentedFrames() const { return presentedSequence_; }

    void setFocusLoupe(const focus_loupe::Request& request)
    {
        std::lock_guard<std::mutex> lock(loupeMutex_);
        loupeRequest_ = request;
    }

    std::array<float, focus_loupe::kResultSize> focusLoupeResult()
    {
        std::lock_guard<std::mutex> lock(loupeMutex_);
        return loupeResult_;
    }

    int64_t videoFrames() const { return videoFailed_ ? -1 : videoOutput_ ? videoOutput_->frames : 0; }

private:
    void failVideo(const char* error)
    {
        std::lock_guard<std::recursive_mutex> queueLock(queueMutex_);
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "VideoRecording", "%s", error);
        videoOutput_.reset();
        videoFailed_ = true;
    }

    void clearSurface()
    {
        gpuPerformance_.stop();
        if (videoOutput_) failVideo("Viewfinder surface was replaced");
        if (device_ != VK_NULL_HANDLE) {
            try {
                drainAcquiredImages();
            } catch (const std::exception& error) {
                recordNativeDiagnostic(ANDROID_LOG_ERROR, "CameraRecovery", "%s", error.what());
            }
            vkDeviceWaitIdle(device_);
            pipelineCache_.save();
            resetRawImageValidation();
            for (Frame& frame : frames_) {
                releaseImportedBuffer(frame);
                if (frame.cameraBridge) frame.cameraBridge->resetValidation();
            }
            destroySwapchain();
            hasPresentedFrame_ = false;
            ++presentationEpoch_;
            lastPresentedImage_ = VK_NULL_HANDLE;
            swapchainRecoveryPending_ = false;
        }
        if (surface_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    VkInstance instance_ = VK_NULL_HANDLE;
    GpuPerformanceSession gpuPerformance_;
    std::shared_ptr<RawUploadPool> rawUploads_ = std::make_shared<RawUploadPool>();
    std::unique_ptr<VideoOutput> videoOutput_;
    bool videoFailed_ = false;
    VulkanDiagnostics validation_;
    std::string cachePath_;
    PipelineCache pipelineCache_;
    bool swapchainRecoveryPending_ = false;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    ANativeWindow* window_ = nullptr;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    bool displayTimingSupported_ = false;
    PipelineCreationStats pipelineCreationStats_;
    PFN_vkGetRefreshCycleDurationGOOGLE getRefreshCycleDuration_ = nullptr;
    PFN_vkGetPastPresentationTimingGOOGLE getPastPresentationTiming_ = nullptr;
    uint32_t nextPresentId_ = 1;
    std::vector<VkSemaphore> presentSemaphores_;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties_ = nullptr;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout computeDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout presentDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout computePipelineLayout_ = VK_NULL_HANDLE;
    std::array<VkPipeline, 768> bayerCellPipelines_{};
    std::array<VkPipeline, 1152> recordingPipelines_{};
    std::array<VkPipeline, 576> nativeRecordingPipelines_{};
    std::array<bool, 24> nativeRecordingRasterRejected_{};
    std::array<bool, 1152> recordingPipelineRejected_{};
    std::array<bool, 768> bayerCellRasterRejected_{};
    std::vector<std::array<uint64_t, 12>> rejectedRawImports_;
    std::array<uint64_t, 12> validatedRawImageLayout_{};
    std::vector<AHardwareBuffer*> validatedRawImages_;
    struct RawImageProbe {
        VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        VkDescriptorSet descriptor = VK_NULL_HANDLE;
        VkCommandBuffer command = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
    } rawImageProbe_;
    bool reportedRawImageImport_ = false;
    struct IspResources {
        VkDescriptorPool pool = VK_NULL_HANDLE;
        VkDescriptorSetLayout bufferLayout = VK_NULL_HANDLE;
        VkPipelineLayout bufferPipelineLayout = VK_NULL_HANDLE;
        VkPipeline bufferPipeline = VK_NULL_HANDLE;
        VkPipeline bufferMeterPipeline = VK_NULL_HANDLE;
        VkSamplerYcbcrConversion conversion = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
        VkDescriptorSetLayout imageLayout = VK_NULL_HANDLE;
        VkPipelineLayout imagePipelineLayout = VK_NULL_HANDLE;
        VkPipeline imagePipeline = VK_NULL_HANDLE;
        VkPipeline imageMeterPipeline = VK_NULL_HANDLE;
        IspFormatKey key;
        bool hasConversion = false;
        bool driverConverted = false;
        bool imageRejected = false;
        bool reportedInput = false;
    } isp_;
    bool ispImageSupported_ = false;
    std::vector<std::array<uint64_t, 12>> rejectedCameraImports_;
    int reportedCameraFormat_ = -1;
    std::array<VkPipeline, 24576> previewPipelines_{};
    std::array<VkPipeline, 9> previewCompatiblePipelines_{};
    std::array<VkPipeline, 5> meterPipelines_{};
    std::array<VkDescriptorSetLayout, 2> shadowDescriptorLayouts_{};
    std::array<VkPipelineLayout, 2> shadowPipelineLayouts_{};
    std::array<VkPipeline, 2> shadowPipelines_{};
    VkPipeline shadowColorPipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout previewRecoveryDescriptorLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout previewRecoveryPipelineLayout_ = VK_NULL_HANDLE;
    std::array<VkPipeline, 3> previewRecoveryPipelines_{};
    std::array<VkPipeline, 1536> previewRasterPipelines_{};
    std::array<bool, 1536> previewRasterRejected_{};
    std::array<VkPipeline, 36> remosaicPipelines_{};
    VkPipelineLayout remosaicPipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout remosaicDescriptorLayout_ = VK_NULL_HANDLE;
    std::array<VkPipeline, 2> hotPixelPipelines_{};
    VkPipelineLayout hotPixelPipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout hotPixelDescriptorLayout_ = VK_NULL_HANDLE;
    std::array<VkPipeline, 48> nativeRawPipelines_{};
    std::array<bool, 48> nativeRawRejected_{};
    std::array<VkPipeline, 3> rawUnpackPipelines_{};
    std::array<VkPipeline, 12> rawAreaPipelines_{};
    VkPipeline rawAreaWeightsPipeline_ = VK_NULL_HANDLE;
    bool lensTextureSupported_ = false;
    bool unpackSupported_ = false;
    bool mhcPrefilterSupported_ = true;
    VkDescriptorSetLayout averageDescriptorLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout averagePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline averagePipeline_ = VK_NULL_HANDLE;
    VkPipeline videoChromaPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout videoChromaPipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout videoChromaDescriptorLayout_ = VK_NULL_HANDLE;
    PreviewTexture averageHistory_;
    GeometryParameters averageGeometry_{};
    int64_t averageGroup_ = 0, averageRevision_ = 0;
    int averageLastIndex_ = -1, averageCount_ = 1;
    bool averageLightweight_ = false;
    bool averageRecordingDownsample_ = false;
    bool averageRecordingNative_ = false;
    bool averageRecordingTile_ = false;
    PreviewTexture previewFallbackTexture_;
    VkRenderPass rawRenderPass_ = VK_NULL_HANDLE;
    std::array<bool, 768> previewShadingRejected_{};
    VkPhysicalDeviceProperties physicalProperties_{};
    bool lightweightPreview_ = false;
    VkPhysicalDeviceMemoryProperties memoryProperties_{};
    VkPipeline captureColorPipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout amazeDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool amazeDescriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout amazePipelineLayout_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, AmazeStageCount> amazeDescriptorSets_{};
    std::array<std::array<std::array<VkPipeline, AmazeStageCount>, AmazeInputCount>, 4> amazePipelines_{};
    AmazeWorkgroup amazeWorkgroup_{8, 8};
    int32_t amazePipelinePattern_ = -1;
    VkSampler sampler_ = VK_NULL_HANDLE;
    VkSampler rawIntegerSampler_ = VK_NULL_HANDLE;
    std::array<Frame, kFramesInFlight> frames_{};
    Frame captureFrame_{};
    uint32_t frameIndex_ = 0;
    uint32_t processingWidth_ = 0;
    uint32_t processingHeight_ = 0;
    uint32_t lastPresentedFrameIndex_ = 0;
    VkImage lastPresentedImage_ = VK_NULL_HANDLE;
    VkImage lastCleanPreviewImage_ = VK_NULL_HANDLE;
    int64_t lastPreviewTimestamp_ = 0;
    PreviewPresentation lastPresentation_;
    PreviewTexture histogramTexture_;
    VkCommandBuffer histogramCommand_ = VK_NULL_HANDLE;
    VkFence histogramFence_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout histogramDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool histogramDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet histogramDescriptor_ = VK_NULL_HANDLE;
    VkPipelineLayout histogramPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline histogramPipeline_ = VK_NULL_HANDLE;
    HistogramKey histogramKey_;
    bool histogramPending_ = false;
    bool histogramBinsRequest_ = false;
    bool histogramGpuAvailable_ = false;
    bool histogramGpuRequest_ = false;
    uint64_t histogramSequence_ = 0;
    uint64_t presentedSequence_ = 0;
    uint64_t presentationEpoch_ = 0;
    bool hasPresentedFrame_ = false;
    bool lensSwitchRevealPending_ = false;
    bool lensSwitchRevealActive_ = false;
    std::chrono::steady_clock::time_point lensSwitchRevealStarted_{};
    VkImage captureImage_ = VK_NULL_HANDLE;
    VkDeviceMemory captureMemory_ = VK_NULL_HANDLE;
    VkImageView captureView_ = VK_NULL_HANDLE;
    uint64_t processingImageGeneration_ = 0;
    VkImage captureSpektraImage_ = VK_NULL_HANDLE;
    VkImage chromaImage_ = VK_NULL_HANDLE;
    VkDeviceMemory chromaMemory_ = VK_NULL_HANDLE;
    VkImageView chromaView_ = VK_NULL_HANDLE;
    VkPipeline chromaPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout chromaPipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout chromaDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool chromaDescriptorPool_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, 2> chromaDescriptors_{};
    float captureChromaStrength_ = 0.0f;
    bool chromaInitialized_ = false;
    VkDeviceMemory captureSpektraMemory_ = VK_NULL_HANDLE;
    VkImageView captureSpektraView_ = VK_NULL_HANDLE;
    static constexpr uint32_t kAmazeTileCore = 512;
    static constexpr uint32_t kAmazeTileHalo = 32;
    struct AmazeScratch { VkImage image{}; VkDeviceMemory memory{}; VkImageView view{}; };
    std::array<AmazeScratch, 8> amazeScratch_{};
    VkImage amazeDemosaicImage_ = VK_NULL_HANDLE;
    VkDeviceMemory amazeDemosaicMemory_ = VK_NULL_HANDLE;
    VkImageView amazeDemosaicView_ = VK_NULL_HANDLE;
    uint32_t amazeWidth_ = 0;
    uint32_t amazeHeight_ = 0;
    bool amazeLayoutsInitialized_ = false;
    bool captureHighlightRecovery_ = false;
    bool hrLayoutsInitialized_ = false;
    VkImage hrTmpImage_ = VK_NULL_HANDLE;
    VkDeviceMemory hrTmpMemory_ = VK_NULL_HANDLE;
    VkImageView hrTmpView_ = VK_NULL_HANDLE;
    VkImage hrMaskImage_ = VK_NULL_HANDLE;
    VkDeviceMemory hrMaskMemory_ = VK_NULL_HANDLE;
    VkImageView hrMaskView_ = VK_NULL_HANDLE;
    VkBuffer hrReduceBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory hrReduceMemory_ = VK_NULL_HANDLE;
    VkImage captureReadbackImage_ = VK_NULL_HANDLE;
    VkDeviceMemory captureReadbackMemory_ = VK_NULL_HANDLE;
    VkBuffer captureStaging_ = VK_NULL_HANDLE;
    VkDeviceMemory captureStagingMemory_ = VK_NULL_HANDLE;
    void* captureStagingMapped_ = nullptr;
    uint32_t captureWidth_ = 0;
    bool captureHalfFloat_ = false;
    uint32_t captureHeight_ = 0;
    bool captureInitialized_ = false;
    bool captureSpektraLayoutInitialized_ = false;
    bool captureReadbackInitialized_ = false;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    bool presentSamplesSupported_ = false;
#if !defined(NDEBUG)
    uint64_t presentSampleSequence_ = 0;
#endif
    VkExtent2D swapchainExtent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainViews_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout presentPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline presentPipeline_ = VK_NULL_HANDLE;
    VkPipeline focusPipeline_ = VK_NULL_HANDLE;
    VkPipeline focusOverlayPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout focusPipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout focusDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout loupeDescriptorLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout loupeDrawDescriptorLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout loupePipelineLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout loupeDrawPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline loupePreparePipeline_ = VK_NULL_HANDLE;
    std::array<VkPipeline, 2> loupeDemosaicPipelines_{};
    VkPipeline loupeRemosaicPipeline_ = VK_NULL_HANDLE;
    VkPipeline loupeScorePipeline_ = VK_NULL_HANDLE;
    VkPipeline loupeFinishPipeline_ = VK_NULL_HANDLE;
    VkPipeline loupeDrawPipeline_ = VK_NULL_HANDLE;
    std::mutex loupeMutex_;
    focus_loupe::Request loupeRequest_;
    // Laid out as described by focus_loupe::kResultSize.
    std::array<float, focus_loupe::kResultSize> loupeResult_{};
    uint32_t loupeReadings_ = 0;
    std::vector<float> loupeGroupFine_;
    std::vector<int> loupeColumns_;
    bool loupeErrorReported_ = false;
    std::vector<VkFramebuffer> framebuffers_;
    mutable std::mutex diagnosticMutex_;
    std::mutex operationMutex_;
    std::recursive_mutex queueMutex_;
    std::unique_ptr<spektrafilm::VulkanRenderer> spektraRenderer_;
    std::string diagnostic_ = "Renderer created without a surface";
};

VulkanRenderer* renderer(jlong handle)
{
    return reinterpret_cast<VulkanRenderer*>(static_cast<intptr_t>(handle));
}

void throwRuntimeException(JNIEnv* environment, const char* message)
{
    jclass exceptionClass = environment->FindClass("java/lang/RuntimeException");
    if (exceptionClass != nullptr)
        environment->ThrowNew(exceptionClass, message);
}

} // namespace

extern "C" jlong JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeCreate(
    JNIEnv* environment,
    jobject,
    jstring cachePath)
{
    try {
        const char* chars = cachePath == nullptr ? nullptr : environment->GetStringUTFChars(cachePath, nullptr);
        const std::string path = chars == nullptr ? "" : chars;
        if (chars != nullptr) environment->ReleaseStringUTFChars(cachePath, chars);
        return static_cast<jlong>(
            reinterpret_cast<intptr_t>(new VulkanRenderer(path)));
    } catch (const std::exception& error) {
        throwRuntimeException(environment, error.what());
        return 0;
    }
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeWarmUp(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean preview,
    jint step,
    jint part)
{
    VulkanRenderer* instance = renderer(handle);
    const auto stepCount = preview == JNI_TRUE ? kWarmupStepCount : kExportWarmupStepCount;
    return instance != nullptr && step >= 0 && step < stepCount &&
        instance->warmUp(preview == JNI_TRUE, static_cast<uint32_t>(step), part) ? JNI_TRUE : JNI_FALSE;
}

extern "C" jint JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeWarmupPartCount(JNIEnv*, jclass, jboolean preview, jint step)
{
    return step > 0 && step <= 24
        ? spektrafilm::VulkanRenderer::startupPipelinePartCount(preview == JNI_TRUE, static_cast<uint32_t>(step - 1))
        : 1;
}

extern "C" jint JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeWarmupStepCount(JNIEnv*, jclass, jboolean preview)
{
    return preview == JNI_TRUE ? kWarmupStepCount : kExportWarmupStepCount;
}

extern "C" jlongArray JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePipelineCreationStats(JNIEnv* environment, jobject, jlong handle)
{
    VulkanRenderer* instance = renderer(handle);
    if (instance == nullptr) return nullptr;
    const auto stats = instance->pipelineCreationStats();
    jlongArray result = environment->NewLongArray(stats.size());
    if (result != nullptr) environment->SetLongArrayRegion(result, 0, stats.size(), stats.data());
    return result;
}

extern "C" jstring JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePreparationIdentity(JNIEnv* environment, jobject, jlong handle)
{
    return environment->NewStringUTF(renderer(handle)->preparationIdentity().c_str());
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeLoadedPipelineCache(JNIEnv*, jobject, jlong handle)
{
    return renderer(handle)->loadedPipelineCache() ? JNI_TRUE : JNI_FALSE;
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetSurface(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jobject surface)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr && instance->setSurface(environment, surface)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" void JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetGpuPerformanceActive(
    JNIEnv*, jobject, jlong handle, jboolean active)
{
    if (VulkanRenderer* instance = renderer(handle))
        instance->setGpuPerformanceActive(active == JNI_TRUE);
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeRender(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jobject hardwareBuffer,
    jobject packedRawBytes,
    jobject rawParameters,
    jobject rawColorParameters,
    jobject geometryParameters,
    jobject spektraFilmParameters,
    jobject lensShadingMap,
    jint processingWidth,
    jint processingHeight,
    jobject performanceOutput,
    jobject exposureMeter,
    jlong videoTimestampNs,
    jlong rawUpload, jlong averageGroup, jint averageIndex, jint averageCount, jlong averageRevision,
    jlong previewTimestampNs,
    jobject ispHardwareBuffer, jobject ispPlanes, jobject ispParameters)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr && instance->render(
        environment, hardwareBuffer, packedRawBytes, rawParameters,
        rawColorParameters,
        geometryParameters,
        spektraFilmParameters,
        lensShadingMap,
        processingWidth, processingHeight, performanceOutput, exposureMeter, videoTimestampNs,
        reinterpret_cast<RawUploadPool::Lease*>(static_cast<intptr_t>(rawUpload)),
        averageGroup, averageIndex, averageCount, averageRevision,
        previewTimestampNs,
        ispHardwareBuffer, ispPlanes, ispParameters) ? JNI_TRUE : JNI_FALSE;
}

extern "C" jlong JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeRawUploads(JNIEnv*, jobject, jlong handle)
{
    try {
        auto* instance = renderer(handle);
        return instance ? reinterpret_cast<jlong>(new std::shared_ptr<RawUploadPool>(instance->rawUploads())) : 0;
    } catch (...) { return 0; }
}

extern "C" jlong JNICALL
Java_com_unspektrawesome_vulkan_RawUploadPool_nativeCopy(
    JNIEnv* environment, jobject, jlong handle, jobject source, jlong rowLayoutBytes)
{
    if (!handle || !source || rowLayoutBytes <= 0) return 0;
    const auto capacity = environment->GetDirectBufferCapacity(source);
    const auto* data = environment->GetDirectBufferAddress(source);
    if (!data || capacity <= 0) return 0;
    try {
        auto& pool = *reinterpret_cast<std::shared_ptr<RawUploadPool>*>(static_cast<intptr_t>(handle));
        return reinterpret_cast<jlong>(pool->copy(data, static_cast<size_t>(capacity),
            static_cast<size_t>(rowLayoutBytes)).release());
    } catch (...) { return 0; }
}

extern "C" void JNICALL
Java_com_unspektrawesome_vulkan_RawUploadPool_nativeRelease(JNIEnv*, jobject, jlong handle)
{
    delete reinterpret_cast<std::shared_ptr<RawUploadPool>*>(static_cast<intptr_t>(handle));
}

extern "C" jobject JNICALL
Java_com_unspektrawesome_vulkan_RawUpload_nativeBeginRead(JNIEnv* environment, jobject, jlong handle)
{
    auto* lease = reinterpret_cast<RawUploadPool::Lease*>(static_cast<intptr_t>(handle));
    if (!lease) return nullptr;
    void* data = lease->beginRead();
    if (!data) return nullptr;
    jobject buffer = environment->NewDirectByteBuffer(data, static_cast<jlong>(lease->size()));
    if (!buffer) lease->endRead();
    return buffer;
}

extern "C" void JNICALL
Java_com_unspektrawesome_vulkan_RawUpload_nativeEndRead(JNIEnv*, jobject, jlong handle)
{
    reinterpret_cast<RawUploadPool::Lease*>(static_cast<intptr_t>(handle))->endRead();
}

extern "C" void JNICALL
Java_com_unspektrawesome_vulkan_RawUpload_nativeRelease(JNIEnv*, jobject, jlong handle)
{
    delete reinterpret_cast<RawUploadPool::Lease*>(static_cast<intptr_t>(handle));
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetVideoColorSpace(
    JNIEnv* environment, jclass, jobject surface, jboolean fullRange)
{
    ANativeWindow* window = ANativeWindow_fromSurface(environment, surface);
    if (!window) return JNI_FALSE;
    const bool supported = VideoOutput::setColorSpace(window, fullRange == JNI_TRUE);
    ANativeWindow_release(window);
    return supported ? JNI_TRUE : JNI_FALSE;
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetVideoSurface(
    JNIEnv* environment, jobject, jlong handle, jobject surface, jint width, jint height, jboolean fullRange)
{
    auto* instance = renderer(handle);
    return instance && instance->setVideoSurface(environment, surface, width, height, fullRange == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" void JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetFocusLoupe(JNIEnv* environment, jobject, jlong handle,
    jfloatArray values)
{
    auto* instance = renderer(handle);
    if (!instance || !values || environment->GetArrayLength(values) < 16) return;
    float data[16]{};
    environment->GetFloatArrayRegion(values, 0, 16, data);
    focus_loupe::Request request;
    request.enabled = data[0] > 0.5f;
    request.centerX = data[1];
    request.centerY = data[2];
    request.width = static_cast<int>(data[3]);
    request.height = static_cast<int>(data[4]);
    for (int i = 0; i < 4; ++i) request.rect[i] = data[5 + i];
    request.cornerRadius = data[9];
    request.peaking = data[10];
    request.opacity = data[11];
    request.noiseScale = data[12];
    request.noiseOffset = data[13];
    request.generation = static_cast<int64_t>(data[14]);
    bool valid = true;
    for (float value : data) valid = valid && std::isfinite(value);
    if (!valid || request.width <= 0 || request.height <= 0 || request.rect[2] <= 0.0f || request.rect[3] <= 0.0f)
        request.enabled = false;
    instance->setFocusLoupe(request);
}

extern "C" jfloatArray JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeFocusLoupeResult(JNIEnv* environment, jobject, jlong handle)
{
    auto* instance = renderer(handle);
    if (!instance) return nullptr;
    const auto values = instance->focusLoupeResult();
    jfloatArray result = environment->NewFloatArray(static_cast<jsize>(values.size()));
    if (result) environment->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" jlong JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePresentedFrames(JNIEnv*, jobject, jlong handle)
{
    auto* instance = renderer(handle);
    return instance ? instance->presentedFrames() : -1;
}

extern "C" jlong JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeVideoFrames(JNIEnv*, jobject, jlong handle)
{
    auto* instance = renderer(handle);
    return instance ? instance->videoFrames() : -1;
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePollExposureMeter(
    JNIEnv* environment, jobject, jlong handle, jobject buffer)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr && instance->pollExposureMeter(environment, buffer) ? JNI_TRUE : JNI_FALSE;
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeBeginLensSwitchBlur(
    JNIEnv*,
    jobject,
    jlong handle)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr && instance->beginLensSwitchBlur()
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" jintArray JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeHistogramBins(
    JNIEnv* environment, jobject, jlong handle, jfloat cropAspect, jlong generation)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr ? instance->histogramBins(environment, cropAspect, generation) : nullptr;
}

extern "C" jlong JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePreviewSnapshot(
    JNIEnv* environment, jobject, jlong handle, jobject bitmap)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr ? instance->previewSnapshot(environment, bitmap) : 0;
}

extern "C" jintArray JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeHistogramPixels(
    JNIEnv* environment, jobject, jlong handle, jfloat cropAspect)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr ? instance->histogramPixels(environment, cropAspect) : nullptr;
}

extern "C" jboolean JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeCaptureAmaze(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jobject hardwareBuffer,
    jobject packedRawBytes,
    jobject rawParameters,
    jobject rawColorParameters,
    jobject geometryParameters,
    jobject spektraFilmParameters,
    jobject lensShadingMap,
    jobject rgbaOutput,
    jdouble spektraTimeSeconds,
    jboolean halfFloatOutput,
    jboolean cooperativeScheduling,
    jboolean bitmapOutput,
    jint gpuPauseMillis)
{
    VulkanRenderer* instance = renderer(handle);
    return instance != nullptr && instance->captureAmaze(
        environment, hardwareBuffer, packedRawBytes, rawParameters,
        rawColorParameters,
        geometryParameters,
        spektraFilmParameters,
        lensShadingMap, rgbaOutput,
        spektraTimeSeconds, halfFloatOutput == JNI_TRUE, cooperativeScheduling == JNI_TRUE,
        bitmapOutput == JNI_TRUE, static_cast<uint32_t>(std::clamp(gpuPauseMillis, 0, 20))) ? JNI_TRUE : JNI_FALSE;
}

extern "C" jstring JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeGetDiagnosticString(
    JNIEnv* environment,
    jobject,
    jlong handle)
{
    VulkanRenderer* instance = renderer(handle);
    std::string value = instance != nullptr
        ? instance->diagnostic() : "Renderer is released";
    return environment->NewStringUTF(value.c_str());
}

extern "C" void JNICALL
Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle)
{
    delete renderer(handle);
}

bool registerVulkanRenderer(JNIEnv* environment) {
    const JNINativeMethod methods0[] = {
        {"nativeCreate", "(Ljava/lang/String;)J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeCreate)},
        {"nativeRawUploads", "(J)J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeRawUploads)},
        {"nativeWarmUp", "(JZII)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeWarmUp)},
        {"nativePipelineCreationStats", "(J)[J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePipelineCreationStats)},
        {"nativePreparationIdentity", "(J)Ljava/lang/String;", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePreparationIdentity)},
        {"nativeLoadedPipelineCache", "(J)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeLoadedPipelineCache)},
        {"nativeSetSurface", "(JLandroid/view/Surface;)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetSurface)},
        {"nativeSetGpuPerformanceActive", "(JZ)V", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetGpuPerformanceActive)},
        {"nativeSetVideoSurface", "(JLandroid/view/Surface;IIZ)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetVideoSurface)},
        {"nativeSetVideoColorSpace", "(Landroid/view/Surface;Z)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetVideoColorSpace)},
        {"nativeVideoFrames", "(J)J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeVideoFrames)},
        {"nativePresentedFrames", "(J)J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePresentedFrames)},
        {"nativeSetFocusLoupe", "(J[F)V", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeSetFocusLoupe)},
        {"nativeFocusLoupeResult", "(J)[F", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeFocusLoupeResult)},
        {"nativeBeginLensSwitchBlur", "(J)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeBeginLensSwitchBlur)},
        {"nativeHistogramPixels", "(JF)[I", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeHistogramPixels)},
        {"nativeHistogramBins", "(JFJ)[I", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeHistogramBins)},
        {"nativePreviewSnapshot", "(JLandroid/graphics/Bitmap;)J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePreviewSnapshot)},
        {"nativePollExposureMeter", "(JLjava/nio/ByteBuffer;)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativePollExposureMeter)},
        {"nativeRender", "(JLandroid/hardware/HardwareBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;JJJIIJJLandroid/hardware/HardwareBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeRender)},
        {"nativeGetDiagnosticString", "(J)Ljava/lang/String;", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeGetDiagnosticString)},
        {"nativeCaptureAmaze", "(JLandroid/hardware/HardwareBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/lang/Object;DZZZI)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeCaptureAmaze)},
        {"nativeRelease", "(J)V", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeRelease)},
        {"nativeWarmupPartCount", "(ZI)I", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeWarmupPartCount)},
        {"nativeWarmupStepCount", "(Z)I", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanRenderer_nativeWarmupStepCount)},
    };
    if (!registerNativeMethods(environment, "com/unspektrawesome/vulkan/VulkanRenderer", methods0)) return false;
    const JNINativeMethod methods1[] = {
        {"nativeCopy", "(JLjava/nio/ByteBuffer;J)J", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_RawUploadPool_nativeCopy)},
        {"nativeRelease", "(J)V", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_RawUploadPool_nativeRelease)},
    };
    if (!registerNativeMethods(environment, "com/unspektrawesome/vulkan/RawUploadPool", methods1)) return false;
    const JNINativeMethod methods2[] = {
        {"nativeBeginRead", "(J)Ljava/nio/ByteBuffer;", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_RawUpload_nativeBeginRead)},
        {"nativeEndRead", "(J)V", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_RawUpload_nativeEndRead)},
        {"nativeRelease", "(J)V", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_RawUpload_nativeRelease)},
    };
    if (!registerNativeMethods(environment, "com/unspektrawesome/vulkan/RawUpload", methods2)) return false;
    return true;
}
