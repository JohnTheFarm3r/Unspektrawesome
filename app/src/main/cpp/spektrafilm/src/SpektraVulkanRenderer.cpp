#include "../../CooperativeCompute.h"
#include "../../ExportMemoryPolicy.h"
#include "SpektraVulkanRenderer.h"
#include "SpektraColorLutKey.h"
#include "SpektraDiffusionRegions.h"
#include "SpektraMonochrome.h"
#include "PreviewSpatialEffects.h"
#include "SpektraVideoEffects.h"
#include "SpektraProfileCurves.h"
#include "../shaders/SpektraPreviewColorLutConfig.h"
#include "../shaders/SpektraFourStageLutConfig.h"
#include "../shaders/SpektraPreviewImageConfig.h"
#include "../shaders/SpektraAutoExposureConfig.h"
#include "../shaders/SpektraLinearDispatch.h"

#include <vulkan/vulkan.h>

#if defined(__ANDROID__)
#  include <android/log.h>
#  include "../../NativeDiagnostics.h"
#  include "../../VulkanDiagnostics.h"
#  include <sys/system_properties.h>
#  include "SpektraCopy_spv.h"
#  include "SpektraFormatConvert_spv.h"
#  include "SpektraImageFormatConvert_spv.h"
#  include "SpektraAutoExposure_spv.h"
#  include "SpektraFilmExposure_spv.h"
#  include "SpektraCurveDevelop_spv.h"
#  include "SpektraHalationPreview_spv.h"
#  include "SpektraGrainPreview_spv.h"
#  include "SpektraDiffusionPreview_spv.h"
#  include "SpektraPrintScanPreview_spv.h"
#  include "SpektraPrintLut_spv.h"
#  include "SpektraPrintLutCompact_spv.h"
#  include "SpektraPrintLutBuild_spv.h"
#  include "SpektraPrintLutBuildCompact_spv.h"
#  include "SpektraFilmExposureFourStage_spv.h"
#  include "SpektraFilmExposureFourStageCompact_spv.h"
#  include "SpektraCurveDevelopFourStage_spv.h"
#  include "SpektraCurveDevelopFourStageCompact_spv.h"
#  include "SpektraPrintFourStage_spv.h"
#  include "SpektraPrintFourStageCompact_spv.h"
#  include "SpektraPrintFourStageBuild_spv.h"
#  include "SpektraPrintFourStageBuildCompact_spv.h"
#  include "SpektraFilmExposureFourStageTexture_spv.h"
#  include "SpektraFilmExposureFourStageTextureCompact_spv.h"
#  include "SpektraFilmExposureFourStageTextureImage_spv.h"
#  include "SpektraFilmExposureFourStageTextureImageCompact_spv.h"
#  include "SpektraCurveDevelopFourStageTexture_spv.h"
#  include "SpektraCurveDevelopFourStageTextureCompact_spv.h"
#  include "SpektraPrintFourStageTexture_spv.h"
#  include "SpektraPrintFourStageTextureCompact_spv.h"
#  include "SpektraPrintFourStageTextureImage_spv.h"
#  include "SpektraPrintFourStageTextureImageCompact_spv.h"
#  include "SpektraPreviewColor_spv.h"
#  include "SpektraComposedPreviewColor_spv.h"
#  include "SpektraVideoEffects_spv.h"
#  include "SpektraPrintComposedImage_spv.h"
#  include "SpektraPrintComposedImageCompact_spv.h"
#  include "SpektraComposeLut_spv.h"
#  include "SpektraFilmExposurePreview_spv.h"
#  include "SpektraCurveDevelopPreview_spv.h"
#  include "SpektraPreviewOutput_spv.h"
#  include "SpektraPrintScan_spv.h"
#  include "SpektraPrintProbe_spv.h"
#  include "SpektraPrintProbeCompact_spv.h"
#  include "SpektraPrintScanCompact_spv.h"
#  include "SpektraPrintConstantsCompact_spv.h"
#  include "SpektraPrintPixelsCompact_spv.h"
#  include "SpektraPrintAccessProbe_spv.h"
#  include "SpektraHalation_spv.h"
#  include "SpektraDiffusion_spv.h"
#  include "SpektraDiffusionRegions_spv.h"
#  include "SpektraDir_spv.h"
#  include "SpektraScannerPost_spv.h"
#  include "SpektraFilmExposureFourStageImage_spv.h"
#  include "SpektraFilmExposureFourStageImageCompact_spv.h"
#  include "SpektraPrintFourStageImage_spv.h"
#  include "SpektraPrintFourStageImageCompact_spv.h"
#  include "SpektraScannerPostImage_spv.h"
#  include "SpektraGrain_spv.h"
#  include "SpektraHanatos2025Spectra_f32.h"
#  include "SpektraOutputGamutCompression_f32.h"
#endif

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  include <windows.h>
#elif defined(__linux__)
#  include <dlfcn.h>
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <initializer_list>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace spektrafilm {

namespace {

int moduleImageAnchor = 0;
constexpr std::array<uint32_t, 4> kOperationPipelineCounts{12u, 14u, 5u, 14u};

using PerfClock = std::chrono::steady_clock;

double elapsedMilliseconds(PerfClock::time_point start, PerfClock::time_point end) {
  return std::chrono::duration<double, std::milli>(end - start).count();
}

bool envFlagEnabled(const char *name) {
  const char *value = std::getenv(name);
  if (!value) {
    return false;
  }
  return std::strcmp(value, "1") == 0 ||
         std::strcmp(value, "true") == 0 ||
         std::strcmp(value, "TRUE") == 0 ||
         std::strcmp(value, "yes") == 0 ||
         std::strcmp(value, "YES") == 0 ||
         std::strcmp(value, "on") == 0 ||
         std::strcmp(value, "ON") == 0;
}

bool envFlagEnabledOrDefault(const char *name, bool defaultValue) {
  const char *value = std::getenv(name);
  if (!value || value[0] == '\0') {
    return defaultValue;
  }
  return std::strcmp(value, "1") == 0 ||
         std::strcmp(value, "true") == 0 ||
         std::strcmp(value, "TRUE") == 0 ||
         std::strcmp(value, "yes") == 0 ||
         std::strcmp(value, "YES") == 0 ||
         std::strcmp(value, "on") == 0 ||
         std::strcmp(value, "ON") == 0;
}

bool envFlagEnabledOrDefaultAny(
  const std::initializer_list<const char *> &names,
  bool defaultValue
) {
  for (const char *name : names) {
    const char *value = std::getenv(name);
    if (value && value[0] != '\0') {
      return envFlagEnabledOrDefault(name, defaultValue);
    }
  }
  return defaultValue;
}

std::string envString(const char *name, const char *defaultValue) {
  const char *value = std::getenv(name);
  return value && value[0] ? std::string(value) : std::string(defaultValue);
}

std::string envStringAny(
  const std::initializer_list<const char *> &names,
  const char *defaultValue
) {
  for (const char *name : names) {
    const char *value = std::getenv(name);
    if (value && value[0] != '\0') {
      return std::string(value);
    }
  }
  return std::string(defaultValue);
}

std::filesystem::path envPath(const char *name) {
  const char *value = std::getenv(name);
  return value && value[0] ? std::filesystem::path(value) : std::filesystem::path();
}

std::filesystem::path modulePath() {
#if defined(_WIN32)
  HMODULE module = nullptr;
  if (!GetModuleHandleExW(
        GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
        reinterpret_cast<LPCWSTR>(&moduleImageAnchor),
        &module
      )) {
    return {};
  }

  std::vector<wchar_t> path(MAX_PATH);
  for (;;) {
    const DWORD length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    if (length == 0) {
      return {};
    }
    if (length < path.size() - 1) {
      return std::filesystem::path(std::wstring(path.data(), length));
    }
    path.resize(path.size() * 2u);
  }
#elif defined(__linux__)
  Dl_info imageInfo{};
  if (dladdr(&moduleImageAnchor, &imageInfo) == 0 || !imageInfo.dli_fname) {
    return {};
  }
  return std::filesystem::path(imageInfo.dli_fname);
#else
  return {};
#endif
}

std::filesystem::path findResourcePath(const std::filesystem::path &relativePath) {
  std::vector<std::filesystem::path> directories;
  if (std::filesystem::path resourceDir = envPath("SPEKTRAFILM_RESOURCE_DIR"); !resourceDir.empty()) {
    directories.push_back(resourceDir);
  }

  const std::filesystem::path imagePath = modulePath();
  if (!imagePath.empty()) {
    const std::filesystem::path contentsPath = imagePath.parent_path().parent_path();
    if (!contentsPath.empty()) {
      directories.push_back(contentsPath / "Resources");
    }
    if (!imagePath.parent_path().empty()) {
      directories.push_back(imagePath.parent_path());
    }
  }

  directories.push_back(std::filesystem::current_path());

  for (const std::filesystem::path &directory : directories) {
    const std::filesystem::path candidate = directory / relativePath;
    std::error_code ec;
    if (std::filesystem::is_regular_file(candidate, ec)) {
      return candidate;
    }
  }
  return {};
}

std::vector<uint32_t> readSpirvFile(const std::filesystem::path &path, std::string &error) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) {
    error = "Unable to open Vulkan shader module " + path.string() + ".";
    return {};
  }
  const std::streamoff byteSize = static_cast<std::streamoff>(input.tellg());
  if (byteSize <= 0 || (static_cast<uint64_t>(byteSize) % sizeof(uint32_t)) != 0u) {
    error = "Vulkan shader module has an invalid SPIR-V byte size: " + path.string() + ".";
    return {};
  }
  input.seekg(0, std::ios::beg);
  std::vector<uint32_t> data(static_cast<size_t>(byteSize) / sizeof(uint32_t));
  if (!input.read(reinterpret_cast<char *>(data.data()), static_cast<std::streamsize>(byteSize))) {
    error = "Unable to read Vulkan shader module " + path.string() + ".";
    return {};
  }
  return data;
}

#if defined(__ANDROID__)
struct EmbeddedSpirv {
  const uint32_t *data = nullptr;
  size_t byteSize = 0;
};

EmbeddedSpirv embeddedSpirvForPath(const char *relativePath) {
  const std::string path(relativePath ? relativePath : "");
  if (path == "shaders/SpektraCopy.comp.spv") {
    return {SpektraCopy_spv, SpektraCopy_spv_size};
  }
  if (path == "shaders/SpektraFormatConvert.comp.spv") {
    return {SpektraFormatConvert_spv, SpektraFormatConvert_spv_size};
  }
  if (path == "shaders/SpektraImageFormatConvert.comp.spv") {
    return {SpektraImageFormatConvert_spv, SpektraImageFormatConvert_spv_size};
  }
  if (path == "shaders/SpektraAutoExposure.comp.spv") {
    return {SpektraAutoExposure_spv, SpektraAutoExposure_spv_size};
  }
  if (path == "shaders/SpektraFilmExposure.comp.spv") {
    return {SpektraFilmExposure_spv, SpektraFilmExposure_spv_size};
  }
  if (path == "shaders/SpektraCurveDevelop.comp.spv") {
    return {SpektraCurveDevelop_spv, SpektraCurveDevelop_spv_size};
  }
  if (path == "shaders/SpektraHalationPreview.comp.spv") {
    return {SpektraHalationPreview_spv, SpektraHalationPreview_spv_size};
  }
  if (path == "shaders/SpektraGrainPreview.comp.spv") return {SpektraGrainPreview_spv, SpektraGrainPreview_spv_size};
  if (path == "shaders/SpektraDiffusionPreview.comp.spv") {
    return {SpektraDiffusionPreview_spv, SpektraDiffusionPreview_spv_size};
  }
  if (path == "shaders/SpektraPrintScanPreview.comp.spv") {
    return {SpektraPrintScanPreview_spv, SpektraPrintScanPreview_spv_size};
  }
  if (path == "shaders/SpektraPrintLut.comp.spv") return {SpektraPrintLut_spv, SpektraPrintLut_spv_size};
  if (path == "shaders/SpektraPrintLutCompact.comp.spv") return {SpektraPrintLutCompact_spv, SpektraPrintLutCompact_spv_size};
  if (path == "shaders/SpektraPrintLutBuild.comp.spv") return {SpektraPrintLutBuild_spv, SpektraPrintLutBuild_spv_size};
  if (path == "shaders/SpektraPrintLutBuildCompact.comp.spv") return {SpektraPrintLutBuildCompact_spv, SpektraPrintLutBuildCompact_spv_size};
  if (path == "shaders/SpektraFilmExposureFourStage.comp.spv") return {SpektraFilmExposureFourStage_spv, SpektraFilmExposureFourStage_spv_size};
  if (path == "shaders/SpektraFilmExposureFourStageCompact.comp.spv") return {SpektraFilmExposureFourStageCompact_spv, SpektraFilmExposureFourStageCompact_spv_size};
  if (path == "shaders/SpektraCurveDevelopFourStage.comp.spv") return {SpektraCurveDevelopFourStage_spv, SpektraCurveDevelopFourStage_spv_size};
  if (path == "shaders/SpektraCurveDevelopFourStageCompact.comp.spv") return {SpektraCurveDevelopFourStageCompact_spv, SpektraCurveDevelopFourStageCompact_spv_size};
  if (path == "shaders/SpektraPrintFourStage.comp.spv") return {SpektraPrintFourStage_spv, SpektraPrintFourStage_spv_size};
  if (path == "shaders/SpektraPrintFourStageCompact.comp.spv") return {SpektraPrintFourStageCompact_spv, SpektraPrintFourStageCompact_spv_size};
  if (path == "shaders/SpektraPrintFourStageBuild.comp.spv") return {SpektraPrintFourStageBuild_spv, SpektraPrintFourStageBuild_spv_size};
  if (path == "shaders/SpektraPrintFourStageBuildCompact.comp.spv") return {SpektraPrintFourStageBuildCompact_spv, SpektraPrintFourStageBuildCompact_spv_size};
  if (path == "shaders/SpektraFilmExposureFourStageTexture.comp.spv") return {SpektraFilmExposureFourStageTexture_spv, SpektraFilmExposureFourStageTexture_spv_size};
  if (path == "shaders/SpektraFilmExposureFourStageTextureCompact.comp.spv") return {SpektraFilmExposureFourStageTextureCompact_spv, SpektraFilmExposureFourStageTextureCompact_spv_size};
  if (path == "shaders/SpektraFilmExposureFourStageTextureImage.comp.spv") return {SpektraFilmExposureFourStageTextureImage_spv, SpektraFilmExposureFourStageTextureImage_spv_size};
  if (path == "shaders/SpektraFilmExposureFourStageTextureImageCompact.comp.spv") return {SpektraFilmExposureFourStageTextureImageCompact_spv, SpektraFilmExposureFourStageTextureImageCompact_spv_size};
  if (path == "shaders/SpektraCurveDevelopFourStageTexture.comp.spv") return {SpektraCurveDevelopFourStageTexture_spv, SpektraCurveDevelopFourStageTexture_spv_size};
  if (path == "shaders/SpektraCurveDevelopFourStageTextureCompact.comp.spv") return {SpektraCurveDevelopFourStageTextureCompact_spv, SpektraCurveDevelopFourStageTextureCompact_spv_size};
  if (path == "shaders/SpektraPrintFourStageTexture.comp.spv") return {SpektraPrintFourStageTexture_spv, SpektraPrintFourStageTexture_spv_size};
  if (path == "shaders/SpektraPrintFourStageTextureCompact.comp.spv") return {SpektraPrintFourStageTextureCompact_spv, SpektraPrintFourStageTextureCompact_spv_size};
  if (path == "shaders/SpektraPrintFourStageTextureImage.comp.spv") return {SpektraPrintFourStageTextureImage_spv, SpektraPrintFourStageTextureImage_spv_size};
  if (path == "shaders/SpektraPrintFourStageTextureImageCompact.comp.spv") return {SpektraPrintFourStageTextureImageCompact_spv, SpektraPrintFourStageTextureImageCompact_spv_size};
  if (path == "shaders/SpektraPreviewColor.comp.spv") return {SpektraPreviewColor_spv, SpektraPreviewColor_spv_size};
  if (path == "shaders/SpektraPrintComposedImage.comp.spv") return {SpektraPrintComposedImage_spv, SpektraPrintComposedImage_spv_size};
  if (path == "shaders/SpektraPrintComposedImageCompact.comp.spv") return {SpektraPrintComposedImageCompact_spv, SpektraPrintComposedImageCompact_spv_size};
  if (path == "shaders/SpektraComposedPreviewColor.comp.spv") return {SpektraComposedPreviewColor_spv, SpektraComposedPreviewColor_spv_size};
  if (path == "shaders/SpektraComposeLut.comp.spv") return {SpektraComposeLut_spv, SpektraComposeLut_spv_size};
  if (path == "shaders/SpektraVideoEffects.comp.spv") return {SpektraVideoEffects_spv, SpektraVideoEffects_spv_size};
  if (path == "shaders/SpektraFilmExposurePreview.comp.spv") return {SpektraFilmExposurePreview_spv, SpektraFilmExposurePreview_spv_size};
  if (path == "shaders/SpektraCurveDevelopPreview.comp.spv") return {SpektraCurveDevelopPreview_spv, SpektraCurveDevelopPreview_spv_size};
  if (path == "shaders/SpektraPreviewOutput.comp.spv") {
    return {SpektraPreviewOutput_spv, SpektraPreviewOutput_spv_size};
  }
  if (path == "shaders/SpektraPrintScan.comp.spv") {
    return {SpektraPrintScan_spv, SpektraPrintScan_spv_size};
  }
  if (path == "shaders/SpektraPrintProbe.comp.spv") {
    return {SpektraPrintProbe_spv, SpektraPrintProbe_spv_size};
  }
  if (path == "shaders/SpektraPrintProbeCompact.comp.spv") {
    return {SpektraPrintProbeCompact_spv, SpektraPrintProbeCompact_spv_size};
  }
  if (path == "shaders/SpektraPrintScanCompact.comp.spv") {
    return {SpektraPrintScanCompact_spv, SpektraPrintScanCompact_spv_size};
  }
  if (path == "shaders/SpektraPrintConstantsCompact.comp.spv") {
    return {SpektraPrintConstantsCompact_spv, SpektraPrintConstantsCompact_spv_size};
  }
  if (path == "shaders/SpektraPrintPixelsCompact.comp.spv") {
    return {SpektraPrintPixelsCompact_spv, SpektraPrintPixelsCompact_spv_size};
  }
  if (path == "shaders/SpektraPrintAccessProbe.comp.spv") {
    return {SpektraPrintAccessProbe_spv, SpektraPrintAccessProbe_spv_size};
  }
  if (path == "shaders/SpektraHalation.comp.spv") {
    return {SpektraHalation_spv, SpektraHalation_spv_size};
  }
  if (path == "shaders/SpektraDiffusion.comp.spv") {
    return {SpektraDiffusion_spv, SpektraDiffusion_spv_size};
  }
  if (path == "shaders/SpektraDiffusionRegions.comp.spv") {
    return {SpektraDiffusionRegions_spv, SpektraDiffusionRegions_spv_size};
  }
  if (path == "shaders/SpektraDir.comp.spv") {
    return {SpektraDir_spv, SpektraDir_spv_size};
  }
  if (path == "shaders/SpektraScannerPost.comp.spv") {
    return {SpektraScannerPost_spv, SpektraScannerPost_spv_size};
  }
  if (path == "shaders/SpektraFilmExposureFourStageImage.comp.spv") {
    return {SpektraFilmExposureFourStageImage_spv, SpektraFilmExposureFourStageImage_spv_size};
  }
  if (path == "shaders/SpektraFilmExposureFourStageImageCompact.comp.spv") {
    return {SpektraFilmExposureFourStageImageCompact_spv, SpektraFilmExposureFourStageImageCompact_spv_size};
  }
  if (path == "shaders/SpektraPrintFourStageImage.comp.spv") {
    return {SpektraPrintFourStageImage_spv, SpektraPrintFourStageImage_spv_size};
  }
  if (path == "shaders/SpektraPrintFourStageImageCompact.comp.spv") {
    return {SpektraPrintFourStageImageCompact_spv, SpektraPrintFourStageImageCompact_spv_size};
  }
  if (path == "shaders/SpektraScannerPostImage.comp.spv") {
    return {SpektraScannerPostImage_spv, SpektraScannerPostImage_spv_size};
  }
  if (path == "shaders/SpektraGrain.comp.spv") {
    return {SpektraGrain_spv, SpektraGrain_spv_size};
  }
  return {};
}

std::vector<float> embeddedFloatVector(
  const uint8_t *bytes,
  size_t byteSize,
  uint32_t expectedElementCount,
  std::string &error
) {
  const uint64_t expectedBytes = static_cast<uint64_t>(expectedElementCount) * sizeof(float);
  if (!bytes || byteSize != expectedBytes) {
    error = "Embedded Spektra float resource has an unexpected byte size.";
    return {};
  }
  std::vector<float> data(expectedElementCount);
  std::memcpy(data.data(), bytes, static_cast<size_t>(expectedBytes));
  return data;
}
#endif

std::vector<float> readFloatResourceFile(
  const std::filesystem::path &path,
  uint32_t expectedElementCount,
  std::string &error
) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) {
    error = "Unable to open Vulkan float resource " + path.string() + ".";
    return {};
  }
  const std::streamoff byteSize = static_cast<std::streamoff>(input.tellg());
  const uint64_t expectedBytes = static_cast<uint64_t>(expectedElementCount) * sizeof(float);
  if (byteSize <= 0 || static_cast<uint64_t>(byteSize) != expectedBytes) {
    error = "Vulkan float resource has an unexpected byte size: " + path.string() + ".";
    return {};
  }
  input.seekg(0, std::ios::beg);
  std::vector<float> data(expectedElementCount);
  if (!input.read(reinterpret_cast<char *>(data.data()), static_cast<std::streamsize>(byteSize))) {
    error = "Unable to read Vulkan float resource " + path.string() + ".";
    return {};
  }
  return data;
}

bool isSupportedRgba(const ImageView &source, const MutableImageView &destination) {
  return source.components == 4 &&
         destination.components == 4 &&
         (source.bytesPerComponent == 2 || source.bytesPerComponent == 4) &&
         (destination.bytesPerComponent == 2 || destination.bytesPerComponent == 4);
}

bool windowFitsView(
  const ImageView &view,
  const RenderWindow &window,
  int32_t width,
  int32_t height
) {
  const int32_t sourceX = window.x1 - view.x1;
  const int32_t sourceY = window.y1 - view.y1;
  return sourceX >= 0 &&
         sourceY >= 0 &&
         sourceX + width <= view.width &&
         sourceY + height <= view.height;
}

bool windowFitsView(
  const MutableImageView &view,
  const RenderWindow &window,
  int32_t width,
  int32_t height
) {
  const int32_t destinationX = window.x1 - view.x1;
  const int32_t destinationY = window.y1 - view.y1;
  return destinationX >= 0 &&
         destinationY >= 0 &&
         destinationX + width <= view.width &&
         destinationY + height <= view.height;
}

bool copyWindowToMappedBytes(
  const ImageView &source,
  const RenderWindow &window,
  int32_t width,
  int32_t height,
  void *destinationBytes,
  size_t destinationByteCount
) {
  if (!source.data || !destinationBytes || !windowFitsView(source, window, width, height)) {
    return false;
  }

  const int32_t pixelBytes = source.components * source.bytesPerComponent;
  const int32_t rowBytes = width * pixelBytes;
  const size_t byteCount = static_cast<size_t>(height) * static_cast<size_t>(rowBytes);
  if (destinationByteCount < byteCount) {
    return false;
  }

  const auto *sourceBase = static_cast<const uint8_t *>(source.data);
  auto *destinationBase = static_cast<uint8_t *>(destinationBytes);
  const int32_t sourceX = window.x1 - source.x1;
  const int32_t sourceY0 = window.y1 - source.y1;
  if (sourceX == 0 && source.rowBytes == rowBytes) {
    std::memcpy(
      destinationBase,
      sourceBase + static_cast<size_t>(sourceY0) * static_cast<size_t>(source.rowBytes),
      byteCount
    );
    return true;
  }

  for (int32_t y = 0; y < height; ++y) {
    const int32_t sourceY = sourceY0 + y;
    const auto *src = sourceBase +
      static_cast<size_t>(sourceY) * static_cast<size_t>(source.rowBytes) +
      static_cast<size_t>(sourceX) * static_cast<size_t>(pixelBytes);
    uint8_t *dst = destinationBase + static_cast<size_t>(y) * static_cast<size_t>(rowBytes);
    std::memcpy(dst, src, static_cast<size_t>(rowBytes));
  }
  return true;
}

bool copyMappedBytesToWindow(
  const void *sourceBytes,
  size_t sourceByteCount,
  const MutableImageView &destination,
  const RenderWindow &window,
  int32_t width,
  int32_t height
) {
  if (!sourceBytes || !destination.data || !windowFitsView(destination, window, width, height)) {
    return false;
  }

  const int32_t pixelBytes = destination.components * destination.bytesPerComponent;
  const int32_t rowBytes = width * pixelBytes;
  const size_t byteCount = static_cast<size_t>(height) * static_cast<size_t>(rowBytes);
  if (sourceByteCount < byteCount) {
    return false;
  }

  auto *destinationBase = static_cast<uint8_t *>(destination.data);
  const auto *sourceBase = static_cast<const uint8_t *>(sourceBytes);
  const int32_t destinationX = window.x1 - destination.x1;
  const int32_t destinationY0 = window.y1 - destination.y1;
  if (destinationX == 0 && destination.rowBytes == rowBytes) {
    std::memcpy(
      destinationBase + static_cast<size_t>(destinationY0) * static_cast<size_t>(destination.rowBytes),
      sourceBase,
      byteCount
    );
    return true;
  }

  for (int32_t y = 0; y < height; ++y) {
    const int32_t destinationY = destinationY0 + y;
    auto *dst = destinationBase +
      static_cast<size_t>(destinationY) * static_cast<size_t>(destination.rowBytes) +
      static_cast<size_t>(destinationX) * static_cast<size_t>(pixelBytes);
    const uint8_t *src = sourceBase + static_cast<size_t>(y) * static_cast<size_t>(rowBytes);
    std::memcpy(dst, src, static_cast<size_t>(rowBytes));
  }
  return true;
}

bool copyMappedBytesRegionToWindow(
  const void *sourceBytes,
  size_t sourceByteCount,
  int32_t sourceWidth,
  int32_t sourceHeight,
  int32_t sourceX,
  int32_t sourceY,
  const MutableImageView &destination,
  const RenderWindow &window,
  int32_t width,
  int32_t height
) {
  if (!sourceBytes || !destination.data || sourceWidth <= 0 || sourceHeight <= 0 ||
      sourceX < 0 || sourceY < 0 || sourceX + width > sourceWidth || sourceY + height > sourceHeight ||
      !windowFitsView(destination, window, width, height)) {
    return false;
  }

  const int32_t pixelBytes = destination.components * destination.bytesPerComponent;
  const int32_t sourceRowBytes = sourceWidth * pixelBytes;
  const int32_t copyRowBytes = width * pixelBytes;
  const size_t requiredBytes =
    static_cast<size_t>(sourceHeight) * static_cast<size_t>(sourceRowBytes);
  if (sourceByteCount < requiredBytes) {
    return false;
  }

  const auto *sourceBase = static_cast<const uint8_t *>(sourceBytes);
  auto *destinationBase = static_cast<uint8_t *>(destination.data);
  const int32_t destinationX = window.x1 - destination.x1;
  const int32_t destinationY0 = window.y1 - destination.y1;
  for (int32_t y = 0; y < height; ++y) {
    const uint8_t *src = sourceBase +
      static_cast<size_t>(sourceY + y) * static_cast<size_t>(sourceRowBytes) +
      static_cast<size_t>(sourceX) * static_cast<size_t>(pixelBytes);
    uint8_t *dst = destinationBase +
      static_cast<size_t>(destinationY0 + y) * static_cast<size_t>(destination.rowBytes) +
      static_cast<size_t>(destinationX) * static_cast<size_t>(pixelBytes);
    std::memcpy(dst, src, static_cast<size_t>(copyRowBytes));
  }
  return true;
}

uint32_t halfToFloatBits(uint16_t h) {
  const uint32_t sign = (static_cast<uint32_t>(h & 0x8000u)) << 16;
  uint32_t exponent = (h >> 10) & 0x1fu;
  uint32_t mantissa = h & 0x03ffu;

  if (exponent == 0) {
    if (mantissa == 0) {
      return sign;
    }
    exponent = 1;
    while ((mantissa & 0x0400u) == 0) {
      mantissa <<= 1;
      --exponent;
    }
    mantissa &= 0x03ffu;
  } else if (exponent == 31) {
    return sign | 0x7f800000u | (mantissa << 13);
  }

  exponent = exponent + (127 - 15);
  return sign | (exponent << 23) | (mantissa << 13);
}

float halfToFloat(uint16_t h) {
  const uint32_t bits = halfToFloatBits(h);
  float out = 0.0f;
  std::memcpy(&out, &bits, sizeof(out));
  return out;
}

bool copySourceToFloatStaging(
  const ImageView &source,
  const RenderWindow &window,
  int32_t width,
  int32_t height,
  float *staging
) {
  if (!source.data || !staging || !windowFitsView(source, window, width, height)) {
    return false;
  }

  const auto *sourceBase = static_cast<const uint8_t *>(source.data);
  if (source.bytesPerComponent == 4) {
    const size_t rowByteCount = static_cast<size_t>(width) * 4u * sizeof(float);
    for (int32_t y = 0; y < height; ++y) {
      const int32_t sourceY = window.y1 + y - source.y1;
      const int32_t sourceX = window.x1 - source.x1;
      const auto *row = sourceBase +
        static_cast<size_t>(sourceY) * static_cast<size_t>(source.rowBytes) +
        static_cast<size_t>(sourceX) * 4u * sizeof(float);
      std::memcpy(
        staging + static_cast<size_t>(y) * static_cast<size_t>(width) * 4u,
        row,
        rowByteCount
      );
    }
    return true;
  }

  for (int32_t y = 0; y < height; ++y) {
    const int32_t sourceY = window.y1 + y - source.y1;
    const int32_t sourceX = window.x1 - source.x1;
    const auto *row = sourceBase +
      static_cast<size_t>(sourceY) * static_cast<size_t>(source.rowBytes) +
      static_cast<size_t>(sourceX) * static_cast<size_t>(source.components) * static_cast<size_t>(source.bytesPerComponent);
    for (int32_t x = 0; x < width; ++x) {
      float *dst = staging + (static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 4u;
      if (source.bytesPerComponent == 4) {
        const auto *src = reinterpret_cast<const float *>(row + static_cast<size_t>(x) * 4u * sizeof(float));
        dst[0] = src[0];
        dst[1] = src[1];
        dst[2] = src[2];
        dst[3] = src[3];
      } else {
        const auto *src = reinterpret_cast<const uint16_t *>(row + static_cast<size_t>(x) * 4u * sizeof(uint16_t));
        dst[0] = halfToFloat(src[0]);
        dst[1] = halfToFloat(src[1]);
        dst[2] = halfToFloat(src[2]);
        dst[3] = halfToFloat(src[3]);
      }
    }
  }
  return true;
}

std::vector<float> makeLinearSensitivity(const float *logSensitivity, uint32_t wavelengthCount) {
  std::vector<float> linear(static_cast<size_t>(wavelengthCount) * 3u, 0.0f);
  if (!logSensitivity) {
    return linear;
  }
  for (uint32_t i = 0; i < wavelengthCount * 3u; ++i) {
    const float value = std::pow(10.0f, logSensitivity[i]);
    linear[i] = std::isfinite(value) ? value : 0.0f;
  }
  return linear;
}

float smoothErfEdge(float wavelength, float center, float width) {
  return std::erf((wavelength - center) / width) * 0.5f + 0.5f;
}

std::vector<float> applyCameraBandPass(
  const ProfileCurveSet &filmCurves,
  const std::vector<float> &linearSensitivity,
  const RenderParams &params
) {
  std::vector<float> filtered = linearSensitivity;
  if (!filmCurves.wavelengths ||
      filtered.size() < static_cast<size_t>(filmCurves.wavelengthCount) * 3u ||
      (!params.cameraUvFilterEnabled && !params.cameraIrFilterEnabled)) {
    return filtered;
  }

  constexpr float kPythonUvTransitionNm = 8.0f;
  constexpr float kPythonIrTransitionNm = 15.0f;
  std::array<float, 3> numerator = {0.0f, 0.0f, 0.0f};
  std::array<float, 3> denominator = {0.0f, 0.0f, 0.0f};
  std::vector<float> transmissionByWavelength(filmCurves.wavelengthCount, 1.0f);
  for (uint32_t wavelength = 0; wavelength < filmCurves.wavelengthCount; ++wavelength) {
    const float wl = filmCurves.wavelengths[wavelength];
    const float uvTransmission = params.cameraUvFilterEnabled
      ? smoothErfEdge(wl, params.cameraUvCutNm, kPythonUvTransitionNm)
      : 1.0f;
    const float irTransmission = params.cameraIrFilterEnabled
      ? smoothErfEdge(wl, params.cameraIrCutNm, -kPythonIrTransitionNm)
      : 1.0f;
    const float transmission = uvTransmission * irTransmission;
    transmissionByWavelength[wavelength] = transmission;
    const uint32_t offset = wavelength * 3u;
    const float illuminant = filmCurves.referenceIlluminantSpectrum ? filmCurves.referenceIlluminantSpectrum[wavelength] : 1.0f;
    for (uint32_t channel = 0; channel < 3u; ++channel) {
      const float response = linearSensitivity[offset + channel] * illuminant;
      denominator[channel] += response;
      numerator[channel] += response * transmission;
    }
  }

  std::array<float, 3> normalization = {1.0f, 1.0f, 1.0f};
  for (uint32_t channel = 0; channel < 3u; ++channel) {
    normalization[channel] = numerator[channel] / std::max(denominator[channel], 1.0e-10f);
    normalization[channel] = std::max(normalization[channel], 1.0e-10f);
  }
  for (uint32_t wavelength = 0; wavelength < filmCurves.wavelengthCount; ++wavelength) {
    const uint32_t offset = wavelength * 3u;
    for (uint32_t channel = 0; channel < 3u; ++channel) {
      filtered[offset + channel] *= transmissionByWavelength[wavelength] / normalization[channel];
    }
  }
  return filtered;
}

std::array<float, 9> makeMallettRawMatrix(
  const ProfileCurveSet &filmCurves,
  const std::vector<float> &linearSensitivity
) {
  std::array<float, 9> matrix{};
  const uint32_t wavelengthCount = filmCurves.wavelengthCount;
  if (!filmCurves.mallettBasisIlluminant ||
      linearSensitivity.size() < static_cast<size_t>(wavelengthCount) * 3u) {
    return matrix;
  }
  for (uint32_t wavelength = 0; wavelength < wavelengthCount; ++wavelength) {
    const uint32_t offset = wavelength * 3u;
    for (uint32_t outChannel = 0; outChannel < 3u; ++outChannel) {
      for (uint32_t inChannel = 0; inChannel < 3u; ++inChannel) {
        matrix[outChannel * 3u + inChannel] +=
          linearSensitivity[offset + outChannel] * filmCurves.mallettBasisIlluminant[offset + inChannel];
      }
    }
  }

  const float normalization = std::max(filmCurves.mallettRawMidgrayGreen, 1.0e-10f);
  for (float &value : matrix) {
    value /= normalization;
  }
  return matrix;
}

std::array<float, 3> densityCurveMaximums(const ProfileCurveSet &curves) {
  std::array<float, 3> maximums = {0.0f, 0.0f, 0.0f};
  if (!curves.densityCurves || curves.exposureCount == 0u) {
    return maximums;
  }
  for (uint32_t index = 0; index < curves.exposureCount; ++index) {
    const float *density = curves.densityCurves + static_cast<size_t>(index) * 3u;
    maximums[0] = std::max(maximums[0], density[0]);
    maximums[1] = std::max(maximums[1], density[1]);
    maximums[2] = std::max(maximums[2], density[2]);
  }
  return maximums;
}

std::vector<float> makePackedCurveExposure(const float *logExposure, uint32_t exposureCount) {
  std::vector<float> packed(static_cast<size_t>(exposureCount) * 2u, 0.0f);
  for (uint32_t index = 0u; index < exposureCount; ++index) {
    const size_t offset = static_cast<size_t>(index) * 2u;
    packed[offset] = logExposure[index];
    if (index + 1u < exposureCount) {
      packed[offset + 1u] = 1.0f / std::max(logExposure[index + 1u] - logExposure[index], 1.0e-9f);
    }
  }
  return packed;
}

std::vector<float> makePackedSpectralDensity(
  const float *channelDensity,
  const float *baseDensity,
  uint32_t wavelengthCount
) {
  // Match the shader's 32-byte SpectralDensity stride, including its validity flag.
  std::vector<float> packed(static_cast<size_t>(wavelengthCount) * 8u, 0.0f);
  for (uint32_t wavelength = 0u; wavelength < wavelengthCount; ++wavelength) {
    const size_t sourceOffset = static_cast<size_t>(wavelength) * 3u;
    const size_t offset = static_cast<size_t>(wavelength) * 8u;
    if (!std::isfinite(channelDensity[sourceOffset]) ||
        !std::isfinite(channelDensity[sourceOffset + 1u]) ||
        !std::isfinite(channelDensity[sourceOffset + 2u]) || !std::isfinite(baseDensity[wavelength])) continue;
    packed[offset] = channelDensity[sourceOffset];
    packed[offset + 1u] = channelDensity[sourceOffset + 1u];
    packed[offset + 2u] = channelDensity[sourceOffset + 2u];
    packed[offset + 3u] = baseDensity[wavelength];
    packed[offset + 4u] = 1.0f;
  }
  return packed;
}

std::vector<float> makeBaseTransmittance(const float *baseDensity, uint32_t wavelengthCount) {
  std::vector<float> transmittance(wavelengthCount, 0.0f);
  for (uint32_t wavelength = 0u; wavelength < wavelengthCount; ++wavelength) {
    if (!std::isfinite(baseDensity[wavelength])) continue;
    const float value = std::pow(10.0f, -baseDensity[wavelength]);
    if (std::isfinite(value)) transmittance[wavelength] = value;
  }
  return transmittance;
}

std::vector<float> makeScanProducts(
  const float *filmIlluminant,
  const float *paperIlluminant,
  const float *filmBaseDensity,
  const float *paperBaseDensity,
  const float *cmfs,
  uint32_t wavelengthCount
) {
  const size_t packedFloatCount = static_cast<size_t>(wavelengthCount) * 8u;
  const size_t legacyFloatCount = static_cast<size_t>(wavelengthCount) * 8u;
  std::vector<float> products(packedFloatCount + legacyFloatCount + 4u, 0.0f);
  const float *illuminants[] = {filmIlluminant, paperIlluminant};
  const float *baseDensities[] = {filmBaseDensity, paperBaseDensity};
  for (uint32_t stage = 0u; stage < 2u; ++stage) {
    float normalization = 0.0f;
    const size_t packedStageOffset = static_cast<size_t>(stage) * wavelengthCount * 4u;
    const size_t legacyStageOffset = packedFloatCount + static_cast<size_t>(stage) * wavelengthCount * 4u;
    for (uint32_t wavelength = 0u; wavelength < wavelengthCount; ++wavelength) {
      const size_t channelOffset = static_cast<size_t>(wavelength) * 3u;
      const size_t packedOffset = packedStageOffset + static_cast<size_t>(wavelength) * 4u;
      const size_t legacyOffset = legacyStageOffset + static_cast<size_t>(wavelength) * 4u;
      const float illuminant = illuminants[stage][wavelength];
      const float baseTransmittanceRaw = std::pow(10.0f, -baseDensities[stage][wavelength]);
      const float baseTransmittance = std::isfinite(baseTransmittanceRaw) ? baseTransmittanceRaw : 0.0f;
      for (uint32_t channel = 0u; channel < 3u; ++channel) {
        const float product = illuminant * cmfs[channelOffset + channel];
        products[packedOffset + channel] = baseTransmittance * product;
        products[legacyOffset + channel] = product;
      }
      normalization += illuminant * cmfs[channelOffset + 1u];
    }
    products[packedFloatCount + legacyFloatCount + stage] =
      1.0f / std::max(normalization, 1.0e-10f);
  }
  return products;
}

std::vector<float> makeHanatosRawResponse(
  const ProfileCurveSet &filmCurves,
  const std::vector<float> &linearSensitivity,
  const std::vector<float> &hanatosSpectra,
  const HanatosSpectraLutInfo &hanatos,
  RgbToRawMethod method
) {
  const size_t responseCount = static_cast<size_t>(hanatos.width) * static_cast<size_t>(hanatos.height) * 3u;
  std::vector<float> response(responseCount, 0.0f);
  const size_t expectedSpectra =
    static_cast<size_t>(hanatos.width) *
    static_cast<size_t>(hanatos.height) *
    static_cast<size_t>(hanatos.wavelengthCount);
  if (hanatos.width == 0u ||
      hanatos.height == 0u ||
      hanatos.wavelengthCount == 0u ||
      hanatosSpectra.size() < expectedSpectra ||
      linearSensitivity.size() < static_cast<size_t>(hanatos.wavelengthCount) * 3u) {
    return response;
  }

  std::vector<float> hanatos2026Window(hanatos.wavelengthCount, 1.0f);
  std::array<float, 3> hanatos2026Normalization = {1.0f, 1.0f, 1.0f};
  const bool useHanatos2026 =
    method == RgbToRawMethod::Hanatos2026 &&
    filmCurves.hanatos2026WindowParams &&
    filmCurves.referenceIlluminantSpectrum;
  if (useHanatos2026) {
    constexpr float kSqrt2 = 1.4142135623730951f;
    const float cUv = filmCurves.hanatos2026WindowParams[0];
    const float sigmaUv = filmCurves.hanatos2026WindowParams[1];
    const float cIr = filmCurves.hanatos2026WindowParams[2];
    const float sigmaIr = filmCurves.hanatos2026WindowParams[3];
    if (sigmaUv > 0.0f && sigmaIr > 0.0f) {
      std::array<float, 3> numerator = {0.0f, 0.0f, 0.0f};
      std::array<float, 3> denominator = {0.0f, 0.0f, 0.0f};
      for (uint32_t wavelength = 0; wavelength < hanatos.wavelengthCount; ++wavelength) {
        const float wl = filmCurves.wavelengths ? filmCurves.wavelengths[wavelength] : 0.0f;
        const float edgeUv = smoothErfEdge(wl, cUv, sigmaUv * kSqrt2);
        const float edgeIr = smoothErfEdge(wl, cIr, -sigmaIr * kSqrt2);
        const float window = edgeUv * edgeIr;
        hanatos2026Window[wavelength] = window;
        const float illuminant = filmCurves.referenceIlluminantSpectrum[wavelength];
        const uint32_t sensitivityOffset = wavelength * 3u;
        for (uint32_t channel = 0; channel < 3u; ++channel) {
          const float referenceResponse = linearSensitivity[sensitivityOffset + channel] * illuminant;
          denominator[channel] += referenceResponse;
          numerator[channel] += referenceResponse * window;
        }
      }
      for (uint32_t channel = 0; channel < 3u; ++channel) {
        hanatos2026Normalization[channel] =
          numerator[channel] / std::max(denominator[channel], 1.0e-10f);
        hanatos2026Normalization[channel] = std::max(hanatos2026Normalization[channel], 1.0e-10f);
      }
    }
  }
  if (!useHanatos2026 && !filmCurves.bandpassHanatos2025) {
    return response;
  }

  for (uint32_t x = 0; x < hanatos.width; ++x) {
    for (uint32_t y = 0; y < hanatos.height; ++y) {
      std::array<float, 3> raw = {0.0f, 0.0f, 0.0f};
      const size_t spectraOffset =
        (static_cast<size_t>(x) * static_cast<size_t>(hanatos.height) + static_cast<size_t>(y)) *
        static_cast<size_t>(hanatos.wavelengthCount);
      for (uint32_t wavelength = 0; wavelength < hanatos.wavelengthCount; ++wavelength) {
        const float spectrum = hanatosSpectra[spectraOffset + wavelength];
        const uint32_t sensitivityOffset = wavelength * 3u;
        if (useHanatos2026) {
          const float window = hanatos2026Window[wavelength];
          raw[0] += spectrum * linearSensitivity[sensitivityOffset] * window / hanatos2026Normalization[0];
          raw[1] += spectrum * linearSensitivity[sensitivityOffset + 1u] * window / hanatos2026Normalization[1];
          raw[2] += spectrum * linearSensitivity[sensitivityOffset + 2u] * window / hanatos2026Normalization[2];
        } else {
          raw[0] += spectrum * linearSensitivity[sensitivityOffset] * filmCurves.bandpassHanatos2025[sensitivityOffset];
          raw[1] += spectrum * linearSensitivity[sensitivityOffset + 1u] * filmCurves.bandpassHanatos2025[sensitivityOffset + 1u];
          raw[2] += spectrum * linearSensitivity[sensitivityOffset + 2u] * filmCurves.bandpassHanatos2025[sensitivityOffset + 2u];
        }
      }
      const size_t responseOffset =
        (static_cast<size_t>(x) * static_cast<size_t>(hanatos.height) + static_cast<size_t>(y)) * 3u;
      response[responseOffset] = raw[0];
      response[responseOffset + 1u] = raw[1];
      response[responseOffset + 2u] = raw[2];
    }
  }
  return response;
}

// Native port of Andrea Volpato's spektrafilm/forum gamut-compression approach:
// remap the Hanatos response toward the visible locus and let shaders select it.
float reinhardKnee(float value, float threshold, float limit, float power) {
  if (!std::isfinite(value) || value <= threshold) {
    return value;
  }
  const float scale = std::max(limit - threshold, 1.0e-12f);
  const float x = (value - threshold) / scale;
  const float y = x / std::pow(1.0f + std::pow(x, power), 1.0f / power);
  return threshold + scale * y;
}

std::array<float, 2> filmReferenceIlluminantXy(const ProfileCurveSet &filmCurves) {
  const float *cmfs = standardObserverCmfs();
  if (!filmCurves.referenceIlluminantSpectrum || !cmfs || filmCurves.wavelengthCount == 0u) {
    return {1.0f / 3.0f, 1.0f / 3.0f};
  }
  std::array<float, 3> xyz = {0.0f, 0.0f, 0.0f};
  for (uint32_t wavelength = 0; wavelength < filmCurves.wavelengthCount; ++wavelength) {
    const float illuminant = filmCurves.referenceIlluminantSpectrum[wavelength];
    const uint32_t offset = wavelength * 3u;
    xyz[0] += illuminant * cmfs[offset];
    xyz[1] += illuminant * cmfs[offset + 1u];
    xyz[2] += illuminant * cmfs[offset + 2u];
  }
  const float sum = xyz[0] + xyz[1] + xyz[2];
  if (!(sum > 1.0e-12f) || !std::isfinite(sum)) {
    return {1.0f / 3.0f, 1.0f / 3.0f};
  }
  return {xyz[0] / sum, xyz[1] / sum};
}

std::vector<std::array<float, 2>> spectralLocusXy(const ProfileCurveSet &filmCurves) {
  std::vector<std::array<float, 2>> locus;
  const float *cmfs = standardObserverCmfs();
  if (!filmCurves.wavelengths || !cmfs) {
    return locus;
  }
  for (uint32_t wavelength = 0; wavelength < filmCurves.wavelengthCount; ++wavelength) {
    if (filmCurves.wavelengths[wavelength] > 700.0f + 1.0e-4f) {
      continue;
    }
    const uint32_t offset = wavelength * 3u;
    const float x = cmfs[offset];
    const float y = cmfs[offset + 1u];
    const float z = cmfs[offset + 2u];
    const float sum = x + y + z;
    if (sum > 1.0e-12f && std::isfinite(sum)) {
      locus.push_back({x / sum, y / sum});
    }
  }
  if (!locus.empty()) {
    locus.push_back(locus.front());
  }
  return locus;
}

float rayPolygonDistance(
  std::array<float, 2> origin,
  std::array<float, 2> direction,
  const std::vector<std::array<float, 2>> &polygon
) {
  float tMin = INFINITY;
  for (size_t edgeIndex = 0; edgeIndex + 1u < polygon.size(); ++edgeIndex) {
    const float ax = polygon[edgeIndex][0];
    const float ay = polygon[edgeIndex][1];
    const float ex = polygon[edgeIndex + 1u][0] - ax;
    const float ey = polygon[edgeIndex + 1u][1] - ay;
    const float denom = direction[0] * ey - direction[1] * ex;
    if (std::abs(denom) <= 1.0e-12f) {
      continue;
    }
    const float ox = origin[0] - ax;
    const float oy = origin[1] - ay;
    const float t = (-ox * ey + oy * ex) / denom;
    const float s = (-ox * direction[1] + oy * direction[0]) / denom;
    if (t > 1.0e-9f && s >= 0.0f && s <= 1.0f && t < tMin) {
      tMin = t;
    }
  }
  return tMin;
}

std::array<float, 2> compressXyRadial(
  std::array<float, 2> xy,
  std::array<float, 2> whiteXy,
  const std::vector<std::array<float, 2>> &locus
) {
  const float dx = xy[0] - whiteXy[0];
  const float dy = xy[1] - whiteXy[1];
  const float distance = std::sqrt(dx * dx + dy * dy);
  if (!(distance > 1.0e-9f) || locus.size() < 4u) {
    return xy;
  }
  const std::array<float, 2> direction = {dx / distance, dy / distance};
  const float boundary = rayPolygonDistance(whiteXy, direction, locus);
  if (!(boundary > 1.0e-12f) || !std::isfinite(boundary)) {
    return xy;
  }
  const float normalized = distance / boundary;
  const float compressed = reinhardKnee(normalized, 0.0f, 1.0f, 6.0f);
  const float newDistance = compressed * boundary;
  return {whiteXy[0] + direction[0] * newDistance, whiteXy[1] + direction[1] * newDistance};
}

std::array<float, 3> sampleHanatosResponseBilinear(
  const std::vector<float> &response,
  const HanatosSpectraLutInfo &hanatos,
  float x,
  float y
) {
  x = std::clamp(x, 0.0f, static_cast<float>(hanatos.width - 1u));
  y = std::clamp(y, 0.0f, static_cast<float>(hanatos.height - 1u));
  const uint32_t x0 = static_cast<uint32_t>(std::floor(x));
  const uint32_t y0 = static_cast<uint32_t>(std::floor(y));
  const uint32_t x1 = std::min(x0 + 1u, hanatos.width - 1u);
  const uint32_t y1 = std::min(y0 + 1u, hanatos.height - 1u);
  const float tx = x - static_cast<float>(x0);
  const float ty = y - static_cast<float>(y0);
  const auto valueAt = [&](uint32_t xi, uint32_t yi) -> std::array<float, 3> {
    const size_t offset = (static_cast<size_t>(xi) * hanatos.height + yi) * 3u;
    return {response[offset], response[offset + 1u], response[offset + 2u]};
  };
  const std::array<float, 3> v00 = valueAt(x0, y0);
  const std::array<float, 3> v10 = valueAt(x1, y0);
  const std::array<float, 3> v01 = valueAt(x0, y1);
  const std::array<float, 3> v11 = valueAt(x1, y1);
  std::array<float, 3> out{};
  for (uint32_t channel = 0; channel < 3u; ++channel) {
    const float a = v00[channel] + (v10[channel] - v00[channel]) * tx;
    const float b = v01[channel] + (v11[channel] - v01[channel]) * tx;
    out[channel] = a + (b - a) * ty;
  }
  return out;
}

std::vector<float> remapHanatosResponseForInputGamutCompression(
  const ProfileCurveSet &filmCurves,
  const std::vector<float> &baseResponse,
  const HanatosSpectraLutInfo &hanatos
) {
  std::vector<float> remapped(baseResponse.size(), 0.0f);
  if (hanatos.width < 2u || hanatos.height < 2u || baseResponse.size() < static_cast<size_t>(hanatos.width) * hanatos.height * 3u) {
    return remapped;
  }
  const std::array<float, 2> whiteXy = filmReferenceIlluminantXy(filmCurves);
  const std::vector<std::array<float, 2>> locus = spectralLocusXy(filmCurves);
  if (locus.size() < 4u) {
    return baseResponse;
  }
  for (uint32_t x = 0; x < hanatos.width; ++x) {
    const float tx = static_cast<float>(x) / static_cast<float>(hanatos.width - 1u);
    const float rootTx = std::sqrt(std::max(tx, 0.0f));
    for (uint32_t y = 0; y < hanatos.height; ++y) {
      const float ty = static_cast<float>(y) / static_cast<float>(hanatos.height - 1u);
      const std::array<float, 2> xy = {1.0f - rootTx, ty * rootTx};
      const std::array<float, 2> compressedXy = compressXyRadial(xy, whiteXy, locus);
      const float oneMinusX = std::max(1.0f - compressedXy[0], 1.0e-10f);
      const float sampleTx = std::clamp(oneMinusX * oneMinusX, 0.0f, 1.0f);
      const float sampleTy = std::clamp(compressedXy[1] / oneMinusX, 0.0f, 1.0f);
      const std::array<float, 3> sampled = sampleHanatosResponseBilinear(
        baseResponse,
        hanatos,
        sampleTx * static_cast<float>(hanatos.width - 1u),
        sampleTy * static_cast<float>(hanatos.height - 1u)
      );
      const size_t offset = (static_cast<size_t>(x) * hanatos.height + y) * 3u;
      remapped[offset] = sampled[0];
      remapped[offset + 1u] = sampled[1];
      remapped[offset + 2u] = sampled[2];
    }
  }
  return remapped;
}

std::vector<float> makeHanatosRawResponsePair(
  const ProfileCurveSet &filmCurves,
  const std::vector<float> &linearSensitivity,
  const std::vector<float> &hanatosSpectra,
  const HanatosSpectraLutInfo &hanatos,
  RgbToRawMethod method
) {
  std::vector<float> response = makeHanatosRawResponse(filmCurves, linearSensitivity, hanatosSpectra, hanatos, method);
  std::vector<float> compressed = remapHanatosResponseForInputGamutCompression(filmCurves, response, hanatos);
  std::vector<float> packed;
  packed.reserve((response.size() + compressed.size()) / 3u * 4u);
  const auto appendPacked = [&](const std::vector<float> &source) {
    for (size_t offset = 0u; offset + 2u < source.size(); offset += 3u) {
      packed.push_back(source[offset]);
      packed.push_back(source[offset + 1u]);
      packed.push_back(source[offset + 2u]);
      packed.push_back(0.0f);
    }
  };
  appendPacked(response);
  appendPacked(compressed);
  return packed;
}

std::vector<float> makeHanatosResponsePairFromWeights(
  const ProfileCurveSet &referenceCurves,
  const std::vector<float> &spectralWeights,
  const std::vector<float> &hanatosSpectra,
  const HanatosSpectraLutInfo &hanatos
) {
  const size_t responseCount = static_cast<size_t>(hanatos.width) * static_cast<size_t>(hanatos.height) * 3u;
  std::vector<float> response(responseCount, 0.0f);
  const size_t expectedSpectra = static_cast<size_t>(hanatos.width) * static_cast<size_t>(hanatos.height) * static_cast<size_t>(hanatos.wavelengthCount);
  if (hanatos.width == 0u ||
      hanatos.height == 0u ||
      hanatos.wavelengthCount == 0u ||
      hanatosSpectra.size() < expectedSpectra ||
      spectralWeights.size() < static_cast<size_t>(hanatos.wavelengthCount) * 3u) {
    return response;
  }
  for (uint32_t x = 0; x < hanatos.width; ++x) {
    for (uint32_t y = 0; y < hanatos.height; ++y) {
      float raw[3] = {0.0f, 0.0f, 0.0f};
      const size_t spectraOffset = (static_cast<size_t>(x) * hanatos.height + y) * hanatos.wavelengthCount;
      for (uint32_t wavelength = 0u; wavelength < hanatos.wavelengthCount; ++wavelength) {
        const float spectrum = hanatosSpectra[spectraOffset + wavelength];
        const uint32_t weightOffset = wavelength * 3u;
        raw[0] += spectrum * spectralWeights[weightOffset];
        raw[1] += spectrum * spectralWeights[weightOffset + 1u];
        raw[2] += spectrum * spectralWeights[weightOffset + 2u];
      }
      const size_t responseOffset = (static_cast<size_t>(x) * hanatos.height + y) * 3u;
      response[responseOffset] = raw[0];
      response[responseOffset + 1u] = raw[1];
      response[responseOffset + 2u] = raw[2];
    }
  }
  std::vector<float> compressed = remapHanatosResponseForInputGamutCompression(referenceCurves, response, hanatos);
  std::vector<float> packed;
  packed.reserve((response.size() + compressed.size()) / 3u * 4u);
  const auto appendPacked = [&](const std::vector<float> &source) {
    for (size_t offset = 0u; offset + 2u < source.size(); offset += 3u) {
      packed.push_back(source[offset]);
      packed.push_back(source[offset + 1u]);
      packed.push_back(source[offset + 2u]);
      packed.push_back(0.0f);
    }
  };
  appendPacked(response);
  appendPacked(compressed);
  return packed;
}

float filteredEnlargerIlluminantCpu(
  uint32_t wavelength,
  const RenderParams &params,
  float cFilter,
  float mFilterShift,
  float yFilterShift
) {
  const uint32_t film = std::min<uint32_t>(
    static_cast<uint32_t>(std::max(params.film, 0)),
    std::max<uint32_t>(kSpektraFilmCount, 1u) - 1u
  );
  const uint32_t paper = std::min<uint32_t>(
    static_cast<uint32_t>(std::max(params.paper, 0)),
    std::max<uint32_t>(kSpektraPaperCount, 1u) - 1u
  );
  const uint32_t neutralOffset = (paper * kSpektraFilmCount + film) * 3u;
  const float *neutral = neutralPrintFilters();
  const float cc0 = std::max(neutral[neutralOffset] + cFilter, 0.0f);
  const float cc1 = std::max(neutral[neutralOffset + 1u] + mFilterShift, 0.0f);
  const float cc2 = std::max(neutral[neutralOffset + 2u] + yFilterShift, 0.0f);
  const float wheel0 = std::pow(10.0f, -cc0 / 100.0f);
  const float wheel1 = std::pow(10.0f, -cc1 / 100.0f);
  const float wheel2 = std::pow(10.0f, -cc2 / 100.0f);
  const uint32_t offset = wavelength * 3u;
  const float *filters = customEnlargerFilters();
  const float f0 = std::clamp(filters[offset], 0.0f, 1.0f);
  const float f1 = std::clamp(filters[offset + 1u], 0.0f, 1.0f);
  const float f2 = std::clamp(filters[offset + 2u], 0.0f, 1.0f);
  const float dim0 = 1.0f - (1.0f - f0) * (1.0f - wheel0);
  const float dim1 = 1.0f - (1.0f - f1) * (1.0f - wheel1);
  const float dim2 = 1.0f - (1.0f - f2) * (1.0f - wheel2);
  return thKg3Illuminant()[wavelength] * dim0 * dim1 * dim2;
}

std::vector<float> makeProcessNegativePaperWeights(
  const ProfileCurveSet &paperCurves,
  const std::vector<float> &paperSensitivityLinear,
  const RenderParams &params,
  bool preflash
) {
  std::vector<float> weights(static_cast<size_t>(paperCurves.wavelengthCount) * 3u, 0.0f);
  if (paperSensitivityLinear.size() < weights.size()) {
    return weights;
  }
  if (params.printTiming == PrintTimingMode::ApdPrinterDensity) {
    std::array<float, 3> normalization = {0.0f, 0.0f, 0.0f};
    for (uint32_t wavelength = 0u; wavelength < paperCurves.wavelengthCount; ++wavelength) {
      const uint32_t offset = wavelength * 3u;
      normalization[0] += std::max(academyPrinterDensityData()[offset], 0.0f);
      normalization[1] += std::max(academyPrinterDensityData()[offset + 1u], 0.0f);
      normalization[2] += std::max(academyPrinterDensityData()[offset + 2u], 0.0f);
    }
    const float preflashScale = preflash ? std::max(params.preflashExposure, 0.0f) : 1.0f;
    for (uint32_t wavelength = 0u; wavelength < paperCurves.wavelengthCount; ++wavelength) {
      const uint32_t offset = wavelength * 3u;
      weights[offset] = preflashScale * std::max(academyPrinterDensityData()[offset], 0.0f) / std::max(normalization[0], 1.0e-10f);
      weights[offset + 1u] = preflashScale * std::max(academyPrinterDensityData()[offset + 1u], 0.0f) / std::max(normalization[1], 1.0e-10f);
      weights[offset + 2u] = preflashScale * std::max(academyPrinterDensityData()[offset + 2u], 0.0f) / std::max(normalization[2], 1.0e-10f);
    }
    return weights;
  }
  const float cFilter = preflash ? 0.0f : params.filterC;
  const float mFilter = preflash ? params.preflashMFilterShift : params.filterMShift;
  const float yFilter = preflash ? params.preflashYFilterShift : params.filterYShift;
  const float preflashScale = preflash ? std::max(params.preflashExposure, 0.0f) : 1.0f;
  for (uint32_t wavelength = 0u; wavelength < paperCurves.wavelengthCount; ++wavelength) {
    const uint32_t offset = wavelength * 3u;
    const float illuminant = filteredEnlargerIlluminantCpu(wavelength, params, cFilter, mFilter, yFilter);
    weights[offset] = preflashScale * illuminant * paperSensitivityLinear[offset];
    weights[offset + 1u] = preflashScale * illuminant * paperSensitivityLinear[offset + 1u];
    weights[offset + 2u] = preflashScale * illuminant * paperSensitivityLinear[offset + 2u];
  }
  return weights;
}

uint32_t findMemoryTypeIndex(
  VkPhysicalDevice physicalDevice,
  uint32_t typeBits,
  VkMemoryPropertyFlags requiredFlags,
  VkMemoryPropertyFlags preferredFlags = 0
) {
  VkPhysicalDeviceMemoryProperties memoryProperties{};
  vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
  uint32_t fallback = std::numeric_limits<uint32_t>::max();
  for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
    if (((typeBits & (1u << i)) != 0u) &&
        ((memoryProperties.memoryTypes[i].propertyFlags & requiredFlags) == requiredFlags)) {
      const VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[i].propertyFlags;
      if ((flags & preferredFlags) == preferredFlags) {
        return i;
      }
      if (fallback == std::numeric_limits<uint32_t>::max()) {
        fallback = i;
      }
    }
  }
  return fallback;
}

int physicalDeviceTypeScore(VkPhysicalDeviceType type) {
  switch (type) {
    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
      return 4000;
    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
      return 3000;
    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
      return 2000;
    case VK_PHYSICAL_DEVICE_TYPE_CPU:
      return 1000;
    default:
      return 0;
  }
}

bool chooseComputeQueueFamily(
  VkPhysicalDevice candidate,
  uint32_t &queueFamily,
  bool &dedicatedCompute
) {
  uint32_t queueFamilyCount = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, nullptr);
  std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
  vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, queueFamilies.data());

  uint32_t fallback = std::numeric_limits<uint32_t>::max();
  for (uint32_t i = 0; i < queueFamilyCount; ++i) {
    const VkQueueFlags flags = queueFamilies[i].queueFlags;
    if (queueFamilies[i].queueCount == 0 || (flags & VK_QUEUE_COMPUTE_BIT) == 0u) {
      continue;
    }
    if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0u) {
      queueFamily = i;
      dedicatedCompute = true;
      return true;
    }
    if (fallback == std::numeric_limits<uint32_t>::max()) {
      fallback = i;
    }
  }

  if (fallback != std::numeric_limits<uint32_t>::max()) {
    queueFamily = fallback;
    dedicatedCompute = false;
    return true;
  }
  return false;
}

const char *vkResultName(VkResult result) {
  switch (result) {
    case VK_SUCCESS: return "VK_SUCCESS";
    case VK_NOT_READY: return "VK_NOT_READY";
    case VK_TIMEOUT: return "VK_TIMEOUT";
    case VK_EVENT_SET: return "VK_EVENT_SET";
    case VK_EVENT_RESET: return "VK_EVENT_RESET";
    case VK_INCOMPLETE: return "VK_INCOMPLETE";
    case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
    case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
    case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
    case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
    case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
    case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
    case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
    case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
    case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
    case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
    case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
    case VK_ERROR_FRAGMENTED_POOL: return "VK_ERROR_FRAGMENTED_POOL";
    default: return "VK_ERROR_UNKNOWN";
  }
}

std::string vkError(const char *context, VkResult result) {
  return std::string(context) + " failed with " + vkResultName(result) + ".";
}

std::string describePhysicalDevice(const VkPhysicalDeviceProperties &properties) {
  std::ostringstream out;
  out << properties.deviceName
      << " vendor=0x" << std::hex << properties.vendorID
      << " device=0x" << properties.deviceID
      << " driver=0x" << properties.driverVersion
      << std::dec
      << " api="
      << VK_VERSION_MAJOR(properties.apiVersion) << "."
      << VK_VERSION_MINOR(properties.apiVersion) << "."
      << VK_VERSION_PATCH(properties.apiVersion)
      << " maxPushConstants=" << properties.limits.maxPushConstantsSize
      << " maxStorageBuffersPerStage="
      << properties.limits.maxPerStageDescriptorStorageBuffers;
  return out.str();
}

std::string vkErrorWithDevice(
  const char *context,
  VkResult result,
  const VkPhysicalDeviceProperties &properties
) {
  return vkError(context, result) + " Device: " + describePhysicalDevice(properties) + ".";
}

bool isDeviceLostError(const std::string &error) {
  return error.find("VK_ERROR_DEVICE_LOST") != std::string::npos;
}

struct SystemMemory {
  uint64_t total = 0;
  uint64_t available = 0;
};

SystemMemory readSystemMemory() {
  SystemMemory result;
#if defined(__linux__)
  std::ifstream input("/proc/meminfo");
  std::string line;
  while (std::getline(input, line)) {
    std::istringstream values(line);
    std::string name;
    uint64_t kilobytes = 0;
    if (!(values >> name >> kilobytes)) continue;
    if (name == "MemTotal:") result.total = kilobytes * 1024u;
    if (name == "MemAvailable:") result.available = kilobytes * 1024u;
    if (result.total && result.available) break;
  }
#endif
  return result;
}

GpuRenderTilingMode resolveVulkanTileMode(const RenderParams &params) {
  const std::string mode = envString("SPEKTRAFILM_VULKAN_TILE_MODE", "");
  if (mode == "legacy" || mode == "full-frame" || mode == "fullframe") {
    return GpuRenderTilingMode::LegacyFullFrame;
  }
  if (mode == "tiled" || mode == "tile") {
    return GpuRenderTilingMode::Tiled;
  }
  return params.gpuRenderTiling;
}

uint32_t envTileDimension(const char *name, uint32_t fallback) {
  const std::string text = envString(name, "");
  if (text.empty()) {
    return fallback;
  }
  char *end = nullptr;
  const long value = std::strtol(text.c_str(), &end, 10);
  if (!end || *end != '\0' || value < 64 || value > 8192) {
    return fallback;
  }
  return static_cast<uint32_t>(value);
}

constexpr uint32_t kVulkanGrainSpatialRadiusPx = 64u;
constexpr uint32_t kVulkanSpatialEffectRadiusPx = 256u;
constexpr uint32_t kDefaultVulkanTileWidth = 512u;
constexpr uint32_t kDefaultVulkanTileHeight = 256u;
constexpr uint32_t kDefaultExternalVulkanTileWidth = 1024u;
constexpr uint32_t kDefaultExternalVulkanTileHeight = 512u;

uint32_t estimateVulkanTileOverlap(const RenderParams &params,
                                  uint32_t cameraDiffusionRadius, uint32_t printDiffusionRadius,
                                  uint32_t halationRadius) {
  uint32_t overlap = 0u;
  const bool finalOutput = params.renderOutput == RenderOutputMode::FinalPreview;
  const bool rcmOutput = finalOutput && params.outputRole == OutputRole::Rcm;
  const bool finalPrintSimulation =
    finalOutput && params.process == ProcessMode::PrintSimulation;
  const bool finalProcessNegative =
    finalOutput && params.process == ProcessMode::ProcessNegative;
  const bool finalPostProcessPath =
    finalOutput &&
    (params.process == ProcessMode::ScanNegative ||
     params.process == ProcessMode::ProcessNegative ||
     finalPrintSimulation);

  if (params.cameraDiffusionEnabled &&
      params.process != ProcessMode::ProcessNegative &&
      params.cameraDiffusionStrength > 0.0f &&
      params.cameraDiffusionSpatialScale > 0.0f) {
    overlap += cameraDiffusionRadius;
  }
  if (params.halationEnabled &&
      params.process != ProcessMode::ProcessNegative &&
      ((params.scatterAmount > 0.0f && params.scatterScale > 0.0f) ||
       (params.halationAmount > 0.0f &&
        params.halationScale > 0.0f &&
        (params.halationStrengthR > 0.0f ||
         params.halationStrengthG > 0.0f ||
         params.halationStrengthB > 0.0f)))) {
    overlap += halationRadius;
  }
  if (params.dirCouplersAmount > 0.0f &&
      params.process != ProcessMode::ProcessNegative &&
      (params.dirCouplersDiffusionUm > 0.0f ||
       (params.dirCouplersDiffusionTailUm > 0.0f && params.dirCouplersDiffusionTailWeight > 0.0f))) {
    overlap += kVulkanSpatialEffectRadiusPx;
  }
  if (params.grainEnabled &&
      params.process != ProcessMode::ProcessNegative &&
      (params.grainModel == GrainModel::Production ||
       params.grainModel == GrainModel::GrainSynthesis)) {
    overlap += kVulkanGrainSpatialRadiusPx;
  }
  if ((finalPrintSimulation || finalProcessNegative) &&
      params.printDiffusionEnabled &&
      params.printDiffusionStrength > 0.0f &&
      params.printDiffusionSpatialScale > 0.0f) {
    overlap += printDiffusionRadius;
  }
  if (finalPostProcessPath &&
      !rcmOutput &&
      params.scannerEnabled &&
      ((params.glarePercent > 0.0f && params.glareBlur > 0.0f) ||
       params.scannerMtf50LpMm > 0.0f ||
       (params.scannerUnsharpRadiusUm > 0.0f && params.scannerUnsharpAmount > 0.0f))) {
    overlap += kVulkanSpatialEffectRadiusPx;
  }
  return overlap;
}

uint32_t alignedReducedDimension(
  uint32_t localSize,
  uint32_t tileOrigin,
  uint32_t fullSize,
  uint32_t scale
) {
  if (localSize == 0u || fullSize == 0u) {
    return 0u;
  }
  scale = std::max(scale, 1u);
  const uint64_t start = static_cast<uint64_t>(tileOrigin) / scale;
  const uint64_t localEnd =
    std::min<uint64_t>(static_cast<uint64_t>(tileOrigin) + localSize, fullSize);
  const uint64_t end = (localEnd + scale - 1u) / scale;
  return static_cast<uint32_t>(std::max<uint64_t>(end > start ? end - start : 0u, 1u));
}

struct VulkanCorePushConstants {
  uint32_t width = 0;
  uint32_t height = 0;
  float filmExposureEv = 0.0f;
  float filmGamma = 1.0f;
  uint32_t exposureCount = 0;
  int32_t inputColorSpace = 0;
  int32_t rgbToRawMethod = 0;
  uint32_t colorSpaceCount = 0;
  uint32_t transferLutSize = 0;
  float colorDecodeMin = 0.0f;
  float colorDecodeMax = 1.0f;
  uint32_t hanatosWidth = 0;
  uint32_t hanatosHeight = 0;
  uint32_t _pad0 = 0;
  uint32_t _pad1 = 0;
  uint32_t _pad2 = 0;
  int32_t filmPushPullMode = 0;
  float filmPushPullStops = 0.0f;
  uint32_t fullWidth = 0;
  uint32_t fullHeight = 0;
  uint32_t tileOriginX = 0;
  uint32_t tileOriginY = 0;
  uint32_t activeOriginX = 0;
  uint32_t activeOriginY = 0;
  uint32_t activeWidth = 0;
  uint32_t activeHeight = 0;
};

static_assert(sizeof(VulkanCorePushConstants) == 104u);

struct VideoEffectPushConstants {
  uint32_t width = 0;
  uint32_t height = 0;
  float exposureLog = 0.0f;
  uint32_t flags = 0;
  uint32_t level = 0;
  uint32_t composedSize = 0;
  uint32_t activeOriginX = 0;
  uint32_t activeOriginY = 0;
};

enum VideoEffectOperation : uint32_t {
  kVideoEffectBoostCurve = 0u,
  kVideoEffectBase = 1u,
  kVideoEffectDown = 2u,
  kVideoEffectChain = 3u,
  kVideoEffectPrintDown = 4u,
  kVideoEffectPrintChain = 5u,
  kVideoEffectComposeComposed = 6u,
  kVideoEffectComposeStages = 7u,
  kVideoEffectOperationCount = 8u,
};

struct alignas(16) VideoEffectInfo {
  uint32_t config[4]{};
  float sharp[4]{};
  float sharpPrint[4]{};
  float boost[4]{};
  float glare[4]{};
  float weights[video_effects::kMaxLevels][4]{};
  float printWeights[video_effects::kMaxLevels][4]{};
};

static_assert(sizeof(VideoEffectInfo) == 400u);

struct VulkanDiffusionInfo {
  uint32_t componentCount = 0;
  float scatterFraction = 0.0f;
  uint32_t _pad0 = 0;
  uint32_t _pad1 = 0;
};

struct VulkanDiffusionComponent {
  float sigmaPx = 0.0f;
  float weightR = 0.0f;
  float weightG = 0.0f;
  float weightB = 0.0f;
};

static_assert(sizeof(VulkanDiffusionInfo) == 16u);
static_assert(sizeof(VulkanDiffusionComponent) == 16u);

constexpr uint32_t kExposureRangeOffset = 160u;
constexpr uint32_t kCoreFrameFloatCount = SPEKTRA_FOUR_STAGE_DIR_MATRIX_OFFSET + 9u;
constexpr uint32_t kCoreFrameIntCount = SPEKTRA_FOUR_STAGE_DIR_ENABLED_INDEX + 1u;
constexpr uint32_t kCoreStorageBufferBindingCount = 31u;
constexpr uint32_t kCoreHalationSetCount = 15u;
constexpr uint32_t kCoreDiffusionSetCount = 3u;
constexpr uint32_t kCorePrintDiffusionSetCount = 2u;
constexpr uint32_t kCoreDirSetCount = 11u;
constexpr uint32_t kCoreScannerPostSetCount = 9u;
constexpr uint32_t kCoreGrainSetCount = 5u;
constexpr uint32_t kCoreDescriptorSetCount =
  4u + kCoreHalationSetCount + kCoreDiffusionSetCount + kCorePrintDiffusionSetCount + kCoreDirSetCount +
  kCoreScannerPostSetCount + kCoreGrainSetCount;
constexpr uint32_t kFilmExposureAutoExposureEnabled = 1u << 31u;
constexpr uint32_t kMaxVulkanDiffusionComponents = 32u;
constexpr uint32_t kDirFloatCount = 18u;
enum : uint32_t {
  kHalationOpClear = 0u,
  kHalationOpBlurX = 1u,
  kHalationOpBlurYStore = 2u,
  kHalationOpBlurYAccumulate = 3u,
  kHalationOpScatterResolve = 4u,
  kHalationOpBounceResolveLog = 5u,
  kHalationOpRawToLog = 6u,
  kHalationOpBoostMax = 7u,
  kHalationOpBoostReduceMax = 8u,
  kHalationOpBoostApply = 9u,
  kHalationOpPreviewDownsample = 10u,
  kHalationOpPreviewResolve = 11u,
};

constexpr uint32_t kHalationBoostMaxChunkPixels = 256u;

enum : uint32_t {
  kHalationSigmaScatterCore = 0u,
  kHalationSigmaScatterTail = 1u,
  kHalationSigmaBounce = 2u,
};

enum : uint32_t {
  kDiffusionOpClear = 0u,
  kDiffusionOpBlurX = 1u,
  kDiffusionOpBlurYAccumulate = 2u,
  kDiffusionOpResolve = 3u,
  kDiffusionOpRawToLog = 4u,
  kDiffusionOpGroupBlurX = 5u,
  kDiffusionOpGroupBlurYAccumulate = 6u,
  kDiffusionOpDownsample = 7u,
  kDiffusionOpDownsampleBlurX = 8u,
  kDiffusionOpDownsampleBlurY = 9u,
  kDiffusionOpDownsampleUpsampleAccumulate = 10u,
  kDiffusionOpDownsampleGroupBlurX = 11u,
  kDiffusionOpDownsampleGroupBlurY = 12u,
  kDiffusionOpDownsampleGroupUpsampleAccumulate = 13u,
};

enum : uint32_t {
  kPrintScanOpFull = 0u,
  kPrintScanOpPrintRaw = 1u,
  kPrintScanOpFinalFromPrintRaw = 2u,
  kPrintScanOpFrameConstants = 3u,
  kPrintScanOpPrintRawFromNegativeLight = 4u,
};

enum : uint32_t {
  kDirOpCorrectionFromDensity = 0u,
  kDirOpBlurX = 1u,
  kDirOpBlurYStore = 2u,
  kDirOpTailClear = 3u,
  kDirOpTailBlurX = 4u,
  kDirOpTailBlurYAccumulate = 5u,
  kDirOpRedevelop = 6u,
  kDirOpTailDownsample = 7u,
  kDirOpTailReducedBlurX = 8u,
  kDirOpTailReducedBlurYAccumulate = 9u,
  kDirOpTailUpsampleAccumulate = 10u,
};

enum : uint32_t {
  kScannerPostOpPrintGlareGenerate = 0u,
  kScannerPostOpPrintGlareBlurX = 1u,
  kScannerPostOpPrintGlareBlurY = 2u,
  kScannerPostOpPrintGlareApply = 3u,
  kScannerPostOpScannerBlurX = 4u,
  kScannerPostOpScannerBlurY = 5u,
  kScannerPostOpUnsharpBlurX = 6u,
  kScannerPostOpUnsharpBlurY = 7u,
  kScannerPostOpFinalize = 8u,
};

enum : uint32_t {
  kGrainOpPreview = 0u,
  kGrainOpProductionLayers = 1u,
  kGrainOpLayerBlurX = 2u,
  kGrainOpLayerBlurY = 3u,
  kGrainOpMicroSource = 4u,
  kGrainOpMicroBlurX = 5u,
  kGrainOpMicroBlurY = 6u,
  kGrainOpResolveDensity = 7u,
  kGrainOpDensityBlurX = 8u,
  kGrainOpDensityBlurY = 9u,
  kGrainOpApplyControls = 10u,
  kGrainOpSynthesisLayers = 11u,
  kGrainOpSynthesisResolveDensity = 12u,
  kGrainOpCopyDensity = 13u,
};

struct DiffusionGroup {
  float lambdaUm = 0.0f;
  float spread = 1.0f;
  uint32_t count = 1u;
  float alpha = 3.0f;
};

struct DiffusionFamilyShape {
  DiffusionGroup core;
  DiffusionGroup halo;
  DiffusionGroup bloom;
  float weightCore = 0.0f;
  float weightHalo = 0.0f;
  float weightBloom = 0.0f;
  float warmthBase = 0.0f;
  float totalGain = 1.0f;
};

struct DiffusionSettings {
  DiffusionFilterFamily family = DiffusionFilterFamily::BlackProMist;
  float strength = 0.5f;
  float spatialScale = 1.0f;
  float haloWarmth = 0.0f;
  float coreIntensity = 1.0f;
  float coreSize = 1.0f;
  float haloIntensity = 1.0f;
  float haloSize = 1.0f;
  float bloomIntensity = 1.0f;
  float bloomSize = 1.0f;

  bool operator==(const DiffusionSettings &) const = default;
};

DiffusionSettings diffusionSettings(const RenderParams &params, bool print) {
  if (print) {
    return {params.printDiffusionFamily, params.printDiffusionStrength, params.printDiffusionSpatialScale,
      params.printDiffusionHaloWarmth, params.printDiffusionCoreIntensity, params.printDiffusionCoreSize,
      params.printDiffusionHaloIntensity, params.printDiffusionHaloSize, params.printDiffusionBloomIntensity,
      params.printDiffusionBloomSize};
  }
  return {params.cameraDiffusionFamily, params.cameraDiffusionStrength, params.cameraDiffusionSpatialScale,
    params.cameraDiffusionHaloWarmth, params.cameraDiffusionCoreIntensity, params.cameraDiffusionCoreSize,
    params.cameraDiffusionHaloIntensity, params.cameraDiffusionHaloSize, params.cameraDiffusionBloomIntensity,
    params.cameraDiffusionBloomSize};
}

std::array<uint32_t, 2> diffusionPreviewWorkgroup(uint32_t operation) {
  switch (operation) {
    case kDiffusionOpBlurX:
    case kDiffusionOpGroupBlurX:
    case kDiffusionOpDownsampleBlurX:
    case kDiffusionOpDownsampleGroupBlurX:
      return {128u, 2u};
    case kDiffusionOpBlurYAccumulate:
    case kDiffusionOpGroupBlurYAccumulate:
    case kDiffusionOpDownsampleBlurY:
    case kDiffusionOpDownsampleGroupBlurY:
      return {4u, 64u};
    default:
      return {32u, 8u};
  }
}

enum CorePipelineFlag : uint32_t {
  kCoreAutoExposure = 1u << 0u,
  kCoreFilmExposure = 1u << 1u,
  kCoreCurveDevelop = 1u << 2u,
  kCorePrintScan = 1u << 3u,
  kCoreHalation = 1u << 4u,
  kCoreDiffusion = 1u << 5u,
  kCoreDir = 1u << 6u,
  kCoreScannerPost = 1u << 7u,
  kCoreGrain = 1u << 8u,
};

struct VulkanSharedBackend {
  struct QueueSlot {
    VkQueue queue = VK_NULL_HANDLE;
    std::mutex submitMutex;
  };

  uint32_t generation = 0;
  std::string lastError;
  bool available = false;
  std::atomic<bool> poisoned{false};
  bool externalDevice = false;
  std::recursive_mutex *externalQueueMutex = nullptr;
  VkPipelineCache pipelineCache = VK_NULL_HANDLE;
  std::atomic<uint64_t> *pipelineCacheRevision = nullptr;
  PipelineCreationStats *pipelineCreationStats = nullptr;

  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
  VkPhysicalDeviceProperties physicalDeviceProperties{};
  VkDevice device = VK_NULL_HANDLE;
  uint32_t computeQueueFamily = 0;
  std::vector<std::unique_ptr<QueueSlot>> queueSlots;
  std::atomic<uint32_t> nextQueueSlot{0};

  VkShaderModule copyShaderModule = VK_NULL_HANDLE;
  VkShaderModule formatConvertShaderModule = VK_NULL_HANDLE;
  VkShaderModule imageFormatConvertShaderModule = VK_NULL_HANDLE;
  VkDescriptorSetLayout copyDescriptorSetLayout = VK_NULL_HANDLE;
  VkDescriptorSetLayout imageFormatConvertDescriptorSetLayout = VK_NULL_HANDLE;
  VkPipelineLayout copyPipelineLayout = VK_NULL_HANDLE;
  VkPipelineLayout imageFormatConvertPipelineLayout = VK_NULL_HANDLE;
  VkPipeline copyPipeline = VK_NULL_HANDLE;
  VkPipeline formatConvertPipeline = VK_NULL_HANDLE;
  VkPipeline imageFormatConvertPipeline = VK_NULL_HANDLE;
  VkShaderModule filmExposureShaderModule = VK_NULL_HANDLE;
  VkShaderModule autoExposureShaderModule = VK_NULL_HANDLE;
  VkShaderModule curveDevelopShaderModule = VK_NULL_HANDLE;
  VkShaderModule printScanShaderModule = VK_NULL_HANDLE;
  VkShaderModule halationShaderModule = VK_NULL_HANDLE;
  VkShaderModule diffusionShaderModule = VK_NULL_HANDLE;
  VkShaderModule dirShaderModule = VK_NULL_HANDLE;
  VkShaderModule scannerPostShaderModule = VK_NULL_HANDLE;
  VkShaderModule grainShaderModule = VK_NULL_HANDLE;
  VkDescriptorSetLayout coreDescriptorSetLayout = VK_NULL_HANDLE;
  VkPipelineLayout corePipelineLayout = VK_NULL_HANDLE;
  VkPipeline filmExposurePipeline = VK_NULL_HANDLE;
  VkPipeline autoExposurePipeline = VK_NULL_HANDLE;
  VkPipeline curveDevelopPipeline = VK_NULL_HANDLE;
  VkPipeline printScanPipeline = VK_NULL_HANDLE;
  VkPipeline halationPipeline = VK_NULL_HANDLE;
  VkPipeline diffusionPipeline = VK_NULL_HANDLE;
  VkPipeline dirPipeline = VK_NULL_HANDLE;
  VkPipeline scannerPostPipeline = VK_NULL_HANDLE;
  VkPipeline grainPipeline = VK_NULL_HANDLE;
  std::array<VkShaderModule, 4> previewOperationModules{};
  VkShaderModule diffusionRegionsModule = VK_NULL_HANDLE;
  VkPipeline diffusionRegionsPipeline = VK_NULL_HANDLE;
  VkShaderModule previewOutputModule = VK_NULL_HANDLE;
  VkPipeline previewOutputPipeline = VK_NULL_HANDLE;
  VkShaderModule printProbeModule = VK_NULL_HANDLE;
  VkPipeline printProbePipeline = VK_NULL_HANDLE;
  VkPipeline directPrintPipeline = VK_NULL_HANDLE;
  VkShaderModule compactPrintModule = VK_NULL_HANDLE;
  VkPipeline compactPrintPipeline = VK_NULL_HANDLE;
  std::array<VkShaderModule, 2> splitPrintModules{};
  std::array<VkPipeline, 2> splitPrintPipelines{};
  VkShaderModule compactPrintProbeModule = VK_NULL_HANDLE;
  VkPipeline compactPrintProbePipeline = VK_NULL_HANDLE;
  VkDescriptorSetLayout compactPrintSetLayout = VK_NULL_HANDLE;
  VkPipelineLayout compactPrintLayout = VK_NULL_HANDLE;
  VkShaderModule printAccessModule = VK_NULL_HANDLE;
  VkPipeline printAccessPipeline = VK_NULL_HANDLE;
  VkPipelineLayout printAccessLayout = VK_NULL_HANDLE;
  VkDescriptorSetLayout printAccessSetLayout = VK_NULL_HANDLE;
  std::mutex operationPipelineMutex;
  std::array<std::array<VkPipeline, 14>, 4> operationPipelines{};
  std::array<VkShaderModule, 2> previewLutModules{};
  std::array<VkShaderModule, 2> previewLutBuildModules{};
  std::array<VkPipeline, 2> previewLutBuildPipelines{};
  std::array<std::array<VkPipeline, 5>, 2> previewLutPipelines{};
  std::array<VkShaderModule, 2> previewDevelopmentModules{};
  std::array<VkPipeline, 2> previewDevelopmentPipelines{};
  std::array<std::array<VkShaderModule, 2>, 4> fourStageDevelopmentModules{};
  std::array<std::array<VkPipeline, 2>, 4> fourStageDevelopmentPipelines{};
  std::array<VkShaderModule, 4> fourStagePrintModules{};
  std::array<VkShaderModule, 2> fourStageBuildModules{};
  std::array<VkPipeline, 2> fourStageBuildPipelines{};
  std::array<std::array<VkPipeline, 3>, 4> fourStagePrintPipelines{};
  std::array<VkShaderModule, 4> imageExposureModules{};
  std::array<VkPipeline, 4> imageExposurePipelines{};
  std::array<VkShaderModule, 4> imagePrintModules{};
  std::array<std::array<VkPipeline, 3>, 4> imagePrintPipelines{};
  VkShaderModule imageScannerModule = VK_NULL_HANDLE;
  VkPipeline imageScannerPipeline = VK_NULL_HANDLE;
  std::array<VkShaderModule, 2> previewColorModules{};
  std::array<VkPipeline, 2> previewColorPipelines{};
  VkDescriptorSetLayout composeSetLayout = VK_NULL_HANDLE;
  VkPipelineLayout composeLayout = VK_NULL_HANDLE;
  VkShaderModule composeModule = VK_NULL_HANDLE;
  VkPipeline composePipeline = VK_NULL_HANDLE;

  bool preparePreviewColor(bool composed = false) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    auto &pipeline = previewColorPipelines[composed ? 1u : 0u];
    auto &module = previewColorModules[composed ? 1u : 0u];
    if (pipeline != VK_NULL_HANDLE) return true;
    const char *name = composed ? "SpektraComposedPreviewColor" : "SpektraPreviewColor";
    return (module != VK_NULL_HANDLE || loadShaderModule((std::string("shaders/") + name + ".comp.spv").c_str(), module)) &&
      createComputePipeline(name, module, corePipelineLayout, pipeline);
  }

  std::array<VkShaderModule, 2> composedPrintModules{};
  std::array<VkPipeline, 2> composedPrintPipelines{};
  VkDescriptorSetLayout videoEffectsSetLayout = VK_NULL_HANDLE;
  VkDescriptorSetLayout videoEffectsLevelLayout = VK_NULL_HANDLE;
  VkPipelineLayout videoEffectsLayout = VK_NULL_HANDLE;
  VkShaderModule videoEffectsModule = VK_NULL_HANDLE;
  std::array<VkPipeline, kVideoEffectOperationCount> videoEffectsPipelines{};

  bool prepareVideoEffects(uint32_t operation) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (operation >= kVideoEffectOperationCount) return false;
    VkPipeline &pipeline = videoEffectsPipelines[operation];
    if (pipeline != VK_NULL_HANDLE) return true;
    VkResult result = VK_SUCCESS;
    if (videoEffectsSetLayout == VK_NULL_HANDLE) {
      const VkDescriptorSetLayoutBinding bindings[]{
        {10, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {30, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {SPEKTRA_PREVIEW_SOURCE_IMAGE_BINDING, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {SPEKTRA_PREVIEW_DESTINATION_IMAGE_BINDING, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {SPEKTRA_FOUR_STAGE_LUT_TEXTURE_BINDING, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {40, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {41, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {42, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {43, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {45, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}};
      VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      info.bindingCount = static_cast<uint32_t>(std::size(bindings));
      info.pBindings = bindings;
      result = vkCreateDescriptorSetLayout(device, &info, nullptr, &videoEffectsSetLayout);
    }
    if (result == VK_SUCCESS && videoEffectsLevelLayout == VK_NULL_HANDLE) {
      const VkDescriptorSetLayoutBinding bindings[]{
        {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}};
      VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      info.bindingCount = static_cast<uint32_t>(std::size(bindings));
      info.pBindings = bindings;
      result = vkCreateDescriptorSetLayout(device, &info, nullptr, &videoEffectsLevelLayout);
    }
    if (result == VK_SUCCESS && videoEffectsLayout == VK_NULL_HANDLE) {
      const VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VideoEffectPushConstants)};
      const VkDescriptorSetLayout layouts[]{videoEffectsSetLayout, videoEffectsLevelLayout};
      VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
      info.setLayoutCount = 2;
      info.pSetLayouts = layouts;
      info.pushConstantRangeCount = 1;
      info.pPushConstantRanges = &range;
      result = vkCreatePipelineLayout(device, &info, nullptr, &videoEffectsLayout);
    }
    if (result != VK_SUCCESS) {
      lastError = vkError("Video effects layout", result);
      return false;
    }
    if (videoEffectsModule == VK_NULL_HANDLE &&
        !loadShaderModule("shaders/SpektraVideoEffects.comp.spv", videoEffectsModule)) return false;
    const VkSpecializationMapEntry entry{20u, 0u, sizeof(uint32_t)};
    const VkSpecializationInfo specialization{1u, &entry, sizeof(operation), &operation};
    const std::string name = "SpektraVideoEffects" + std::to_string(operation);
    return createComputePipeline(name.c_str(), videoEffectsModule, videoEffectsLayout, pipeline, &specialization);
  }

  bool prepareComposedPrint(bool compact) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    auto &pipeline = composedPrintPipelines[compact ? 1u : 0u];
    auto &module = composedPrintModules[compact ? 1u : 0u];
    if (pipeline != VK_NULL_HANDLE) return true;
    const std::string name = std::string("SpektraPrintComposedImage") + (compact ? "Compact" : "");
    return (module != VK_NULL_HANDLE || loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), module)) &&
      createComputePipeline(name.c_str(), module, compact ? compactPrintLayout : corePipelineLayout,
        pipeline, nullptr, compact ? 0u : VK_PIPELINE_CREATE_DISPATCH_BASE_BIT);
  }

  bool prepareComposedLut() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (composePipeline != VK_NULL_HANDLE) return true;
    VkResult result = VK_SUCCESS;
    if (composeSetLayout == VK_NULL_HANDLE) {
      const VkDescriptorSetLayoutBinding bindings[]{
        {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}};
      VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      info.bindingCount = 2;
      info.pBindings = bindings;
      result = vkCreateDescriptorSetLayout(device, &info, nullptr, &composeSetLayout);
    }
    if (result == VK_SUCCESS && composeLayout == VK_NULL_HANDLE) {
      const VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t)};
      VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
      info.setLayoutCount = 1;
      info.pSetLayouts = &composeSetLayout;
      info.pushConstantRangeCount = 1;
      info.pPushConstantRanges = &range;
      result = vkCreatePipelineLayout(device, &info, nullptr, &composeLayout);
    }
    if (result != VK_SUCCESS) { lastError = vkError("Composed colour layout", result); return false; }
    return (composeModule != VK_NULL_HANDLE || loadShaderModule("shaders/SpektraComposeLut.comp.spv", composeModule)) &&
      createComputePipeline("SpektraComposeLut", composeModule, composeLayout, composePipeline);
  }

  bool preparePreviewImages(bool compact, bool scanner, bool texture) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    const uint32_t index = (compact ? 1u : 0u) + (texture ? 2u : 0u);
    const std::string suffix = std::string(texture ? "TextureImage" : "Image") + (compact ? "Compact" : "");
    if (imageExposurePipelines[index] == VK_NULL_HANDLE) {
      auto &pipeline = imageExposurePipelines[index];
      auto &module = imageExposureModules[index];
      const std::string name = "SpektraFilmExposureFourStage" + suffix;
      if ((module == VK_NULL_HANDLE && !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), module)) ||
          !createComputePipeline(name.c_str(), module, corePipelineLayout, pipeline)) return false;
    }
    if (scanner) {
      if (imageScannerPipeline == VK_NULL_HANDLE &&
          ((imageScannerModule == VK_NULL_HANDLE &&
            !loadShaderModule("shaders/SpektraScannerPostImage.comp.spv", imageScannerModule)) ||
           !createComputePipeline("SpektraScannerPostImage", imageScannerModule, corePipelineLayout, imageScannerPipeline))) return false;
    } else {
      const std::string name = "SpektraPrintFourStage" + suffix;
      auto &module = imagePrintModules[index];
      if (module == VK_NULL_HANDLE && !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), module)) return false;
      for (uint32_t operation : {0u, 2u}) {
        auto &pipeline = imagePrintPipelines[index][operation];
        if (pipeline != VK_NULL_HANDLE) continue;
        const VkSpecializationMapEntry entry{0u, 0u, sizeof(operation)};
        const VkSpecializationInfo specialization{1u, &entry, sizeof(operation), &operation};
        if (!createComputePipeline(name.c_str(), module, compact ? compactPrintLayout : corePipelineLayout,
            pipeline, &specialization, compact ? 0u : VK_PIPELINE_CREATE_DISPATCH_BASE_BIT)) return false;
      }
    }
    return true;
  }

  bool prepareFourStageLut(bool compact, bool texture = false) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    const uint32_t buildIndex = compact ? 1u : 0u;
    const uint32_t index = buildIndex + (texture ? 2u : 0u);
    const std::string suffix = std::string(texture ? "Texture" : "") + (compact ? "Compact" : "");
    const std::array<const char *, 2> names{"SpektraFilmExposureFourStage", "SpektraCurveDevelopFourStage"};
    for (size_t stage = 0; stage < names.size(); ++stage) {
      auto &pipeline = fourStageDevelopmentPipelines[index][stage];
      auto &module = fourStageDevelopmentModules[index][stage];
      if (pipeline != VK_NULL_HANDLE) continue;
      const std::string name = std::string(names[stage]) + suffix;
      if ((module == VK_NULL_HANDLE && !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), module)) ||
          !createComputePipeline(name.c_str(), module, corePipelineLayout, pipeline)) return false;
    }
    const VkPipelineLayout layout = compact ? compactPrintLayout : corePipelineLayout;
    const VkPipelineCreateFlags flags = compact ? 0u : VK_PIPELINE_CREATE_DISPATCH_BASE_BIT;
    if (fourStageBuildPipelines[buildIndex] == VK_NULL_HANDLE) {
      const std::string name = std::string("SpektraPrintFourStageBuild") + (compact ? "Compact" : "");
      if ((fourStageBuildModules[buildIndex] == VK_NULL_HANDLE &&
           !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), fourStageBuildModules[buildIndex])) ||
          !createComputePipeline(name.c_str(), fourStageBuildModules[buildIndex], layout,
            fourStageBuildPipelines[buildIndex], nullptr, flags)) return false;
    }
    const std::string name = "SpektraPrintFourStage" + suffix;
    if (fourStagePrintModules[index] == VK_NULL_HANDLE &&
        !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), fourStagePrintModules[index])) return false;
    for (uint32_t operation = 0; operation < 3u; ++operation) {
      auto &pipeline = fourStagePrintPipelines[index][operation];
      if (pipeline != VK_NULL_HANDLE) continue;
      const VkSpecializationMapEntry entry{0u, 0u, sizeof(operation)};
      const VkSpecializationInfo specialization{1u, &entry, sizeof(operation), &operation};
      if (!createComputePipeline(name.c_str(), fourStagePrintModules[index], layout, pipeline,
          &specialization, flags)) return false;
    }
    return true;
  }

  bool prepareCorePipeline(const char *name, VkShaderModule module,
      VkPipeline &sharedPipeline, VkPipeline &localPipeline) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (sharedPipeline == VK_NULL_HANDLE &&
        !createComputePipeline(name, module, corePipelineLayout, sharedPipeline)) return false;
    localPipeline = sharedPipeline;
    return true;
  }

  bool prepareDiffusionRegions() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (diffusionRegionsPipeline != VK_NULL_HANDLE) return true;
    return (diffusionRegionsModule != VK_NULL_HANDLE ||
      loadShaderModule("shaders/SpektraDiffusionRegions.comp.spv", diffusionRegionsModule)) &&
      createComputePipeline("SpektraDiffusionRegions", diffusionRegionsModule, corePipelineLayout, diffusionRegionsPipeline);
  }

  bool preparePreviewDevelopment() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    const std::array<const char *, 2> names{"SpektraFilmExposurePreview", "SpektraCurveDevelopPreview"};
    for (size_t index = 0; index < names.size(); ++index) {
      if (previewDevelopmentPipelines[index] != VK_NULL_HANDLE) continue;
      if ((previewDevelopmentModules[index] == VK_NULL_HANDLE &&
          !loadShaderModule((std::string("shaders/") + names[index] + ".comp.spv").c_str(), previewDevelopmentModules[index])) ||
          !createComputePipeline(names[index], previewDevelopmentModules[index], corePipelineLayout,
            previewDevelopmentPipelines[index])) return false;
    }
    return true;
  }

  bool preparePreviewColorLut(bool compact) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    const uint32_t index = compact ? 1u : 0u;
    const std::string suffix = compact ? "Compact" : "";
    const VkPipelineLayout layout = compact ? compactPrintLayout : corePipelineLayout;
    const VkPipelineCreateFlags flags = compact ? 0u : VK_PIPELINE_CREATE_DISPATCH_BASE_BIT;
    if (previewLutBuildPipelines[index] == VK_NULL_HANDLE) {
      const std::string name = "SpektraPrintLutBuild" + suffix;
      if ((previewLutBuildModules[index] == VK_NULL_HANDLE &&
           !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), previewLutBuildModules[index])) ||
          !createComputePipeline(name.c_str(), previewLutBuildModules[index], layout,
            previewLutBuildPipelines[index], nullptr, flags)) return false;
    }
    const std::string name = "SpektraPrintLut" + suffix;
    if (previewLutModules[index] == VK_NULL_HANDLE &&
        !loadShaderModule(("shaders/" + name + ".comp.spv").c_str(), previewLutModules[index])) return false;
    for (uint32_t operation : {0u, 1u, 2u, 4u}) {
      auto &pipeline = previewLutPipelines[index][operation];
      if (pipeline != VK_NULL_HANDLE) continue;
      const VkSpecializationMapEntry entry{0u, 0u, sizeof(operation)};
      const VkSpecializationInfo specialization{1u, &entry, sizeof(operation), &operation};
      if (!createComputePipeline(name.c_str(), previewLutModules[index], layout, pipeline,
          &specialization, flags)) return false;
    }
    return true;
  }

  uint32_t storageChunkCount() const {
    const auto &limits = physicalDeviceProperties.limits;
    const uint32_t descriptors = std::min({limits.maxPerStageDescriptorStorageBuffers,
      limits.maxDescriptorSetStorageBuffers, limits.maxPerStageResources - SPEKTRA_PREVIEW_IMAGE_BINDING_COUNT - 2u});
    const uint32_t fixedBindings = kCoreStorageBufferBindingCount - 8u;
    return descriptors >= kCoreStorageBufferBindingCount
      ? std::min(8u, (descriptors - fixedBindings) / 8u) : 1u;
  }

  uint32_t storageChunkBytes() const {
    const auto &limits = physicalDeviceProperties.limits;
    const uint64_t alignment = std::max(uint64_t{16u}, limits.minStorageBufferOffsetAlignment);
    // Some drivers alias large buffer accesses despite advertising a larger range.
    constexpr uint64_t safeRange = 128u * 1024u * 1024u;
    const uint64_t range = std::min(uint64_t{limits.maxStorageBufferRange}, safeRange);
    return static_cast<uint32_t>(range / alignment * alignment);
  }

  bool requiresSpecializedPrint() const {
    // Adreno 830 fails to compile the combined print and frame-constants shader.
    return physicalDeviceProperties.vendorID == 0x5143u &&
      physicalDeviceProperties.deviceID == 0x44050001u;
  }

  bool prepareOperationPipelines(uint32_t family, int32_t part = -1) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    const std::array<const char *, 4> names{"SpektraHalationPreview", "SpektraDiffusionPreview", "SpektraPrintScanPreview", "SpektraGrainPreview"};
    if (previewOperationModules[family] == VK_NULL_HANDLE &&
        !loadShaderModule((std::string("shaders/") + names[family] + ".comp.spv").c_str(), previewOperationModules[family])) {
      return false;
    }
    const uint32_t first = part < 0 ? 0u : static_cast<uint32_t>(part);
    const uint32_t end = part < 0 ? kOperationPipelineCounts[family] : first + 1u;
    if (first >= kOperationPipelineCounts[family]) return false;
    for (uint32_t operation = first; operation < end; ++operation) {
      auto &pipeline = operationPipelines[family][operation];
      if (pipeline != VK_NULL_HANDLE) continue;
      const auto group = diffusionPreviewWorkgroup(operation);
      const std::array<uint32_t, 3> values{operation, group[0], group[1]};
      const std::array<VkSpecializationMapEntry, 3> entries{{
        {0u, 0u, sizeof(uint32_t)}, {1u, 4u, sizeof(uint32_t)}, {2u, 8u, sizeof(uint32_t)}}};
      const VkSpecializationInfo specialization{
        family == 1u ? 3u : 1u, entries.data(), sizeof(values), values.data()};
      if (!createComputePipeline(names[family], previewOperationModules[family], corePipelineLayout, pipeline, &specialization)) {
        return false;
      }
    }
    return true;
  }

  bool preparePreviewOutput() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (previewOutputPipeline != VK_NULL_HANDLE) return true;
    if (previewOutputModule == VK_NULL_HANDLE &&
        !loadShaderModule("shaders/SpektraPreviewOutput.comp.spv", previewOutputModule)) return false;
    return createComputePipeline("SpektraPreviewOutput", previewOutputModule, imageFormatConvertPipelineLayout, previewOutputPipeline);
  }

  bool preparePrintProbe() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (printProbePipeline != VK_NULL_HANDLE) return true;
    if (printProbeModule == VK_NULL_HANDLE &&
        !loadShaderModule("shaders/SpektraPrintProbe.comp.spv", printProbeModule)) return false;
    return createComputePipeline("SpektraPrintProbe", printProbeModule, corePipelineLayout, printProbePipeline);
  }

  bool prepareDirectPrint() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (directPrintPipeline != VK_NULL_HANDLE) return true;
    return createComputePipeline("SpektraPrintDirect", printScanShaderModule, corePipelineLayout,
      directPrintPipeline, nullptr, 0u);
  }

  bool prepareCompactPrint(bool probe, bool split) {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (compactPrintSetLayout == VK_NULL_HANDLE) {
      std::array<VkDescriptorSetLayoutBinding, SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING + 1u> bindings{};
      for (uint32_t index = 0; index < bindings.size(); ++index) {
        bindings[index] = {index, index < kCoreStorageBufferBindingCount ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER :
          VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
      }
      bindings[SPEKTRA_FOUR_STAGE_LUT_TEXTURE_BINDING].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      bindings[SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      info.bindingCount = static_cast<uint32_t>(bindings.size());
      info.pBindings = bindings.data();
      const VkResult result = vkCreateDescriptorSetLayout(device, &info, nullptr, &compactPrintSetLayout);
      if (result != VK_SUCCESS) { lastError = vkError("Compact print descriptor layout", result); return false; }
    }
    if (compactPrintLayout == VK_NULL_HANDLE) {
      const VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(VulkanCorePushConstants)};
      VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
      info.setLayoutCount = 1;
      info.pSetLayouts = &compactPrintSetLayout;
      info.pushConstantRangeCount = 1;
      info.pPushConstantRanges = &range;
      const VkResult result = vkCreatePipelineLayout(device, &info, nullptr, &compactPrintLayout);
      if (result != VK_SUCCESS) { lastError = vkError("Compact print pipeline layout", result); return false; }
    }
    if (!split && compactPrintPipeline == VK_NULL_HANDLE &&
        ((compactPrintModule == VK_NULL_HANDLE &&
          !loadShaderModule("shaders/SpektraPrintScanCompact.comp.spv", compactPrintModule)) ||
         !createComputePipeline("SpektraPrintScanCompact", compactPrintModule, compactPrintLayout,
           compactPrintPipeline, nullptr, 0u))) return false;
    if (split) {
      const std::array<const char *, 2> names{"SpektraPrintConstantsCompact", "SpektraPrintPixelsCompact"};
      for (size_t index = 0; index < names.size(); ++index) {
        if (splitPrintPipelines[index] != VK_NULL_HANDLE) continue;
        if (splitPrintModules[index] == VK_NULL_HANDLE &&
            !loadShaderModule((std::string("shaders/") + names[index] + ".comp.spv").c_str(), splitPrintModules[index])) return false;
        if (!createComputePipeline(names[index], splitPrintModules[index], compactPrintLayout,
            splitPrintPipelines[index], nullptr, 0u)) return false;
      }
    }
    if (probe && compactPrintProbePipeline == VK_NULL_HANDLE &&
        ((compactPrintProbeModule == VK_NULL_HANDLE &&
          !loadShaderModule("shaders/SpektraPrintProbeCompact.comp.spv", compactPrintProbeModule)) ||
         !createComputePipeline("SpektraPrintProbeCompact", compactPrintProbeModule, compactPrintLayout,
           compactPrintProbePipeline, nullptr, VK_PIPELINE_CREATE_DISPATCH_BASE_BIT))) return false;
    return true;
  }

  uint64_t transientBudgetBytes = 0;

  bool preparePrintAccessProbe() {
    std::lock_guard<std::mutex> lock(operationPipelineMutex);
    if (printAccessPipeline != VK_NULL_HANDLE) return true;
    if (printAccessSetLayout == VK_NULL_HANDLE) {
      std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
      for (uint32_t index = 0; index < bindings.size(); ++index) {
        bindings[index] = {index, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
      }
      VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      info.bindingCount = static_cast<uint32_t>(bindings.size());
      info.pBindings = bindings.data();
      const VkResult result = vkCreateDescriptorSetLayout(device, &info, nullptr, &printAccessSetLayout);
      if (result != VK_SUCCESS) { lastError = vkError("Print access descriptor layout", result); return false; }
    }
    if (printAccessLayout == VK_NULL_HANDLE) {
      const VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t)};
      VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
      info.setLayoutCount = 1;
      info.pSetLayouts = &printAccessSetLayout;
      info.pushConstantRangeCount = 1;
      info.pPushConstantRanges = &range;
      const VkResult result = vkCreatePipelineLayout(device, &info, nullptr, &printAccessLayout);
      if (result != VK_SUCCESS) { lastError = vkError("Print access pipeline layout", result); return false; }
    }
    if (printAccessModule == VK_NULL_HANDLE &&
        !loadShaderModule("shaders/SpektraPrintAccessProbe.comp.spv", printAccessModule)) return false;
    return createComputePipeline("SpektraPrintAccessProbe", printAccessModule, printAccessLayout, printAccessPipeline);
  }

  explicit VulkanSharedBackend(uint32_t backendGeneration) : generation(backendGeneration) {
    initialize(nullptr);
  }

  VulkanSharedBackend(
    uint32_t backendGeneration,
    const ExternalVulkanContext &externalContext
  ) : generation(backendGeneration) {
    initialize(&externalContext);
  }

  ~VulkanSharedBackend() {
    cleanup();
  }

  bool isAvailable() const {
    return available && !poisoned.load();
  }

  bool matchesExternalContext(const ExternalVulkanContext *externalContext) const {
    if (!externalContext) {
      return !externalDevice;
    }
    return externalDevice &&
      instance == externalContext->instance &&
      physicalDevice == externalContext->physicalDevice &&
      device == externalContext->device &&
      computeQueueFamily == externalContext->computeQueueFamily &&
      externalQueueMutex == externalContext->queueMutex &&
      pipelineCache == externalContext->pipelineCache &&
      pipelineCacheRevision == externalContext->pipelineCacheRevision &&
      pipelineCreationStats == externalContext->pipelineCreationStats &&
      queueCount() == 1u &&
      queueForIndex(0u) == externalContext->computeQueue;
  }

  uint32_t queueCount() const {
    return static_cast<uint32_t>(queueSlots.size());
  }

  uint32_t assignQueueIndex() {
    const uint32_t count = queueCount();
    if (count == 0u) {
      return 0u;
    }
    return nextQueueSlot.fetch_add(1u) % count;
  }

  VkQueue queueForIndex(uint32_t queueIndex) const {
    if (queueSlots.empty()) {
      return VK_NULL_HANDLE;
    }
    return queueSlots[static_cast<size_t>(queueIndex % queueCount())]->queue;
  }

  VkResult submit(uint32_t queueIndex, const VkSubmitInfo &submitInfo, VkFence fence) {
    if (queueSlots.empty() || poisoned.load()) {
      return poisoned.load() ? VK_ERROR_DEVICE_LOST : VK_ERROR_INITIALIZATION_FAILED;
    }
    QueueSlot &slot = *queueSlots[static_cast<size_t>(queueIndex % queueCount())];
    if (externalQueueMutex) {
      std::lock_guard<std::recursive_mutex> lock(*externalQueueMutex);
      return vkQueueSubmit(slot.queue, 1, &submitInfo, fence);
    }
    std::lock_guard<std::mutex> lock(slot.submitMutex);
    return vkQueueSubmit(slot.queue, 1, &submitInfo, fence);
  }

  void markLost() {
    poisoned.store(true);
  }

private:
  static uint32_t envPositiveUint(const char *name, uint32_t fallback) {
    const char *text = std::getenv(name);
    if (!text || text[0] == '\0') {
      return fallback;
    }
    char *end = nullptr;
    const unsigned long value = std::strtoul(text, &end, 10);
    if (!end || *end != '\0' || value == 0ul || value > std::numeric_limits<uint32_t>::max()) {
      return fallback;
    }
    return static_cast<uint32_t>(value);
  }

  static uint64_t envPositiveMb(const char *name, uint64_t fallbackBytes) {
    const char *text = std::getenv(name);
    if (!text || text[0] == '\0') {
      return fallbackBytes;
    }
    char *end = nullptr;
    const unsigned long long value = std::strtoull(text, &end, 10);
    if (!end || *end != '\0' || value == 0ull ||
        value > (std::numeric_limits<uint64_t>::max() / (1024ull * 1024ull))) {
      return fallbackBytes;
    }
    return static_cast<uint64_t>(value) * 1024ull * 1024ull;
  }

  void initialize(const ExternalVulkanContext *externalContext) {
    lastError.clear();

    if (externalContext) {
      if (!externalContext->isValid()) {
        lastError = "The external Vulkan context is incomplete.";
        return;
      }

      uint32_t queueFamilyCount = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(
        externalContext->physicalDevice,
        &queueFamilyCount,
        nullptr
      );
      std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
      vkGetPhysicalDeviceQueueFamilyProperties(
        externalContext->physicalDevice,
        &queueFamilyCount,
        queueFamilies.data()
      );
      if (externalContext->computeQueueFamily >= queueFamilyCount ||
          queueFamilies[externalContext->computeQueueFamily].queueCount == 0u ||
          (queueFamilies[externalContext->computeQueueFamily].queueFlags & VK_QUEUE_COMPUTE_BIT) == 0u) {
        lastError = "The external Vulkan queue family is not compute-capable.";
        return;
      }

      externalDevice = true;
      externalQueueMutex = externalContext->queueMutex;
      pipelineCache = externalContext->pipelineCache;
      pipelineCacheRevision = externalContext->pipelineCacheRevision;
      pipelineCreationStats = externalContext->pipelineCreationStats;
      instance = externalContext->instance;
      physicalDevice = externalContext->physicalDevice;
      device = externalContext->device;
      computeQueueFamily = externalContext->computeQueueFamily;
      vkGetPhysicalDeviceProperties(physicalDevice, &physicalDeviceProperties);
      auto queueSlot = std::make_unique<QueueSlot>();
      queueSlot->queue = externalContext->computeQueue;
      queueSlots.emplace_back(std::move(queueSlot));

      if (!createCopyPipeline() || !createCorePipelines()) {
        cleanup();
        return;
      }

      transientBudgetBytes = computeTransientBudgetBytes();
      available = true;
      return;
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "SpektraFilm OFX";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.pEngineName = "SpektraFilm";
    appInfo.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateInstance", result);
      return;
    }

    if (!selectPhysicalDevice() || !createLogicalDevice() || !createCopyPipeline() || !createCorePipelines()) {
      cleanup();
      return;
    }

    transientBudgetBytes = computeTransientBudgetBytes();
    available = true;
  }

  bool selectPhysicalDevice() {
    uint32_t deviceCount = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (result != VK_SUCCESS || deviceCount == 0) {
      lastError = result == VK_SUCCESS ? "No Vulkan physical device is available." : vkError("vkEnumeratePhysicalDevices", result);
      return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    result = vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    if (result != VK_SUCCESS) {
      lastError = vkError("vkEnumeratePhysicalDevices", result);
      return false;
    }

    if (const char *requestedIndexText = std::getenv("SPEKTRAFILM_VULKAN_DEVICE_INDEX");
        requestedIndexText && requestedIndexText[0]) {
      char *end = nullptr;
      const unsigned long requestedIndex = std::strtoul(requestedIndexText, &end, 10);
      if (!end || *end != '\0' || requestedIndex >= deviceCount) {
        lastError = "SPEKTRAFILM_VULKAN_DEVICE_INDEX does not identify an available Vulkan physical device.";
        return false;
      }

      uint32_t queueFamily = 0;
      bool dedicatedCompute = false;
      VkPhysicalDevice candidate = devices[static_cast<size_t>(requestedIndex)];
      if (!chooseComputeQueueFamily(candidate, queueFamily, dedicatedCompute)) {
        lastError = "The requested Vulkan physical device does not expose a compute-capable queue family.";
        return false;
      }
      physicalDevice = candidate;
      computeQueueFamily = queueFamily;
      vkGetPhysicalDeviceProperties(physicalDevice, &physicalDeviceProperties);
      return true;
    }

    int bestScore = std::numeric_limits<int>::min();
    for (VkPhysicalDevice candidate : devices) {
      uint32_t queueFamily = 0;
      bool dedicatedCompute = false;
      if (!chooseComputeQueueFamily(candidate, queueFamily, dedicatedCompute)) {
        continue;
      }

      VkPhysicalDeviceProperties properties{};
      vkGetPhysicalDeviceProperties(candidate, &properties);
      const int score = physicalDeviceTypeScore(properties.deviceType) + (dedicatedCompute ? 100 : 0);
      if (score > bestScore) {
        bestScore = score;
        physicalDevice = candidate;
        physicalDeviceProperties = properties;
        computeQueueFamily = queueFamily;
      }
    }

    if (physicalDevice == VK_NULL_HANDLE) {
      lastError = "No Vulkan compute-capable queue family is available.";
      return false;
    }
    return true;
  }

  bool createLogicalDevice() {
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());
    if (computeQueueFamily >= queueFamilyCount || queueFamilies[computeQueueFamily].queueCount == 0u) {
      lastError = "The selected Vulkan queue family is not available.";
      return false;
    }

    const uint32_t requestedQueueCount = envPositiveUint("SPEKTRAFILM_VULKAN_QUEUE_COUNT", 1u);
    const uint32_t queueCount = std::max(1u, std::min(requestedQueueCount, queueFamilies[computeQueueFamily].queueCount));
    std::vector<float> priorities(queueCount, 1.0f);

    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = computeQueueFamily;
    queueInfo.queueCount = queueCount;
    queueInfo.pQueuePriorities = priorities.data();

    VkPhysicalDeviceFeatures availableFeatures{};
    vkGetPhysicalDeviceFeatures(physicalDevice, &availableFeatures);
    VkPhysicalDeviceFeatures enabledFeatures{};
    // Avoid print shader faults on affected Arm drivers.
    enabledFeatures.robustBufferAccess = physicalDeviceProperties.vendorID == 0x13b5u &&
      availableFeatures.robustBufferAccess;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.pEnabledFeatures = &enabledFeatures;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    VkResult result = vkCreateDevice(physicalDevice, &deviceInfo, nullptr, &device);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDevice", result);
      return false;
    }
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraVulkanRenderer",
      "Device created: vendor=%u robustBufferAccess=%u", physicalDeviceProperties.vendorID,
      enabledFeatures.robustBufferAccess);
#endif

    queueSlots.reserve(queueCount);
    for (uint32_t queueIndex = 0; queueIndex < queueCount; ++queueIndex) {
      auto slot = std::make_unique<QueueSlot>();
      vkGetDeviceQueue(device, computeQueueFamily, queueIndex, &slot->queue);
      queueSlots.push_back(std::move(slot));
    }
    return true;
  }

  bool loadShaderModule(const char *relativePath, VkShaderModule &shaderModule) {
#if defined(__ANDROID__)
    const EmbeddedSpirv embedded = embeddedSpirvForPath(relativePath);
    if (embedded.data && embedded.byteSize > 0) {
      VkShaderModuleCreateInfo shaderInfo{};
      shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
      shaderInfo.codeSize = embedded.byteSize;
      shaderInfo.pCode = embedded.data;
      const VkResult result = vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule);
      if (result == VK_SUCCESS) nameDiagnosticShader(device, shaderModule, relativePath);
      if (result != VK_SUCCESS) {
        lastError = std::string("Unable to create embedded Vulkan shader module ") +
          relativePath + " (VkResult " + std::to_string(static_cast<int>(result)) + ").";
        return false;
      }
      return true;
    }
#endif
    const std::filesystem::path shaderPath = findResourcePath(relativePath);
    if (shaderPath.empty()) {
      lastError = std::string("Unable to locate Vulkan shader resource ") + relativePath + ".";
      return false;
    }

    std::string spirvError;
    const std::vector<uint32_t> spirv = readSpirvFile(shaderPath, spirvError);
    if (spirv.empty()) {
      lastError = spirvError;
      return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(uint32_t);
    shaderInfo.pCode = spirv.data();
    const VkResult result = vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateShaderModule", result);
      return false;
    }
    return true;
  }

  bool createComputePipeline(
    const char *name,
    VkShaderModule shaderModule,
    VkPipelineLayout layout,
    VkPipeline &pipeline,
    const VkSpecializationInfo *specialization = nullptr,
    VkPipelineCreateFlags flags = VK_PIPELINE_CREATE_DISPATCH_BASE_BIT
  ) {
    std::vector<uint8_t> values(specialization ? specialization->dataSize : 0u);
    std::vector<VkSpecializationMapEntry> entries;
    if (specialization) {
      std::memcpy(values.data(), specialization->pData, values.size());
      entries.assign(specialization->pMapEntries, specialization->pMapEntries + specialization->mapEntryCount);
    }
    for (const auto &[id, value] : std::array<std::pair<uint32_t, uint32_t>, 2>{{
           {30u, storageChunkCount()}, {31u, storageChunkBytes()}}}) {
      entries.push_back({id, static_cast<uint32_t>(values.size()), sizeof(value)});
      const auto *bytes = reinterpret_cast<const uint8_t *>(&value);
      values.insert(values.end(), bytes, bytes + sizeof(value));
    }
    const VkSpecializationInfo storageSpecialization{
      static_cast<uint32_t>(entries.size()), entries.data(), values.size(), values.data()};
    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shaderModule;
    stageInfo.pName = "main";
    stageInfo.pSpecializationInfo = &storageSpecialization;

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.flags = flags;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = layout;
    PipelineCreationFeedback feedback(pipelineCreationStats);
    pipelineInfo.pNext = feedback.info();
#if defined(__ANDROID__)
    const auto compileStart = PerfClock::now();
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraPipeline",
      "backend=%p stage=compile_start name=%s flags=%u", static_cast<void *>(this), name, flags);
#endif
    const VkResult result = vkCreateComputePipelines(device, pipelineCache, 1, &pipelineInfo, nullptr, &pipeline);
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraPipeline",
      "backend=%p stage=compile_end name=%s result=%d elapsed_ms=%.1f", static_cast<void *>(this), name,
      result, elapsedMilliseconds(compileStart, PerfClock::now()));
#endif
    if (result != VK_SUCCESS) {
      const std::string context = std::string("vkCreateComputePipelines(") + name + ")";
      lastError = vkErrorWithDevice(context.c_str(), result, physicalDeviceProperties);
      return false;
    }
    if (pipelineCacheRevision) pipelineCacheRevision->fetch_add(1, std::memory_order_relaxed);
    feedback.record();
    return true;
  }

  bool createCopyPipeline() {
    if (!loadShaderModule("shaders/SpektraCopy.comp.spv", copyShaderModule) ||
        !loadShaderModule("shaders/SpektraFormatConvert.comp.spv", formatConvertShaderModule) ||
        !loadShaderModule("shaders/SpektraImageFormatConvert.comp.spv", imageFormatConvertShaderModule)) {
      return false;
    }

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = storageChunkCount();
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = storageChunkCount();
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = static_cast<uint32_t>(sizeof(bindings) / sizeof(bindings[0]));
    descriptorInfo.pBindings = bindings;
    VkResult result = vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &copyDescriptorSetLayout);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorSetLayout", result);
      return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(uint32_t) * 2u;

    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &copyDescriptorSetLayout;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &pushRange;
    result = vkCreatePipelineLayout(device, &layoutInfo, nullptr, &copyPipelineLayout);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreatePipelineLayout", result);
      return false;
    }

    if (!createComputePipeline("SpektraCopy", copyShaderModule, copyPipelineLayout, copyPipeline) ||
        !createComputePipeline("SpektraFormatConvert", formatConvertShaderModule, copyPipelineLayout, formatConvertPipeline)) {
      return false;
    }

    VkDescriptorSetLayoutBinding imageBindings[2]{};
    imageBindings[0].binding = 0;
    imageBindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageBindings[0].descriptorCount = 1;
    imageBindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    imageBindings[1].binding = 1;
    imageBindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    imageBindings[1].descriptorCount = storageChunkCount();
    imageBindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    descriptorInfo.pBindings = imageBindings;
    result = vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &imageFormatConvertDescriptorSetLayout);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorSetLayout(image format convert)", result);
      return false;
    }
    VkPushConstantRange imagePushRange{};
    imagePushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    imagePushRange.size = sizeof(uint32_t) * 8u;
    VkPipelineLayoutCreateInfo imageLayoutInfo{};
    imageLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    imageLayoutInfo.setLayoutCount = 1;
    imageLayoutInfo.pSetLayouts = &imageFormatConvertDescriptorSetLayout;
    imageLayoutInfo.pushConstantRangeCount = 1;
    imageLayoutInfo.pPushConstantRanges = &imagePushRange;
    result = vkCreatePipelineLayout(device, &imageLayoutInfo, nullptr, &imageFormatConvertPipelineLayout);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreatePipelineLayout(image format convert)", result);
      return false;
    }
    return createComputePipeline("SpektraImageFormatConvert", imageFormatConvertShaderModule,
                                 imageFormatConvertPipelineLayout, imageFormatConvertPipeline);
  }

  bool createCorePipelines() {
    if (physicalDeviceProperties.limits.maxPerStageDescriptorStorageBuffers <
        kCoreStorageBufferBindingCount) {
      lastError =
        "Spektra Vulkan core pipelines require " +
        std::to_string(kCoreStorageBufferBindingCount) +
        " storage buffers in one compute stage, but this device exposes " +
        std::to_string(physicalDeviceProperties.limits.maxPerStageDescriptorStorageBuffers) +
        ". Device: " + describePhysicalDevice(physicalDeviceProperties) + ".";
      return false;
    }

    if (!loadShaderModule("shaders/SpektraAutoExposure.comp.spv", autoExposureShaderModule) ||
        !loadShaderModule("shaders/SpektraFilmExposure.comp.spv", filmExposureShaderModule) ||
        !loadShaderModule("shaders/SpektraCurveDevelop.comp.spv", curveDevelopShaderModule) ||
        !loadShaderModule("shaders/SpektraPrintScan.comp.spv", printScanShaderModule) ||
        !loadShaderModule("shaders/SpektraHalation.comp.spv", halationShaderModule) ||
        !loadShaderModule("shaders/SpektraDiffusion.comp.spv", diffusionShaderModule) ||
        !loadShaderModule("shaders/SpektraDir.comp.spv", dirShaderModule) ||
        !loadShaderModule("shaders/SpektraScannerPost.comp.spv", scannerPostShaderModule) ||
        !loadShaderModule("shaders/SpektraGrain.comp.spv", grainShaderModule)) {
      return false;
    }

    VkDescriptorSetLayoutBinding bindings[SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING + 1u]{};
    for (uint32_t bindingIndex = 0; bindingIndex <= SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING; ++bindingIndex) {
      bindings[bindingIndex].binding = bindingIndex;
      bindings[bindingIndex].descriptorType = bindingIndex < kCoreStorageBufferBindingCount ?
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
      bindings[bindingIndex].descriptorCount = bindingIndex < 8u ? storageChunkCount() : 1u;
      bindings[bindingIndex].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[SPEKTRA_FOUR_STAGE_LUT_TEXTURE_BINDING].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = static_cast<uint32_t>(sizeof(bindings) / sizeof(bindings[0]));
    descriptorInfo.pBindings = bindings;
    VkResult result = vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &coreDescriptorSetLayout);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorSetLayout", result);
      return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(VulkanCorePushConstants);

    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &coreDescriptorSetLayout;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &pushRange;
    result = vkCreatePipelineLayout(device, &layoutInfo, nullptr, &corePipelineLayout);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreatePipelineLayout", result);
      return false;
    }

    return true;
  }

  uint64_t computeTransientBudgetBytes() const {
    constexpr uint64_t kMb = 1024ull * 1024ull;
    constexpr uint64_t kMinBudget = 512ull * kMb;
    constexpr uint64_t kMaxBudget = 8192ull * kMb;

    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    uint64_t largestDeviceLocalHeap = 0;
    uint64_t largestHeap = 0;
    for (uint32_t heapIndex = 0; heapIndex < memoryProperties.memoryHeapCount; ++heapIndex) {
      const uint64_t heapSize = static_cast<uint64_t>(memoryProperties.memoryHeaps[heapIndex].size);
      largestHeap = std::max(largestHeap, heapSize);
      if ((memoryProperties.memoryHeaps[heapIndex].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0u) {
        largestDeviceLocalHeap = std::max(largestDeviceLocalHeap, heapSize);
      }
    }

    const uint64_t baseHeap = largestDeviceLocalHeap != 0 ? largestDeviceLocalHeap : largestHeap;
    const uint64_t autoBudget = std::clamp(baseHeap / 2u, kMinBudget, kMaxBudget);
    return envPositiveMb("SPEKTRAFILM_VULKAN_TRANSIENT_BUDGET_MB", autoBudget);
  }

  void cleanup() {
    available = false;
    if (device != VK_NULL_HANDLE) {
      if (!externalDevice && !poisoned.load()) {
        vkDeviceWaitIdle(device);
      }
      if (previewOutputPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, previewOutputPipeline, nullptr);
      if (previewOutputModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, previewOutputModule, nullptr);
      previewOutputPipeline = VK_NULL_HANDLE;
      previewOutputModule = VK_NULL_HANDLE;
      for (size_t index = 0; index < previewLutModules.size(); ++index) {
        for (VkPipeline pipeline : previewLutPipelines[index]) {
          if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
        }
        if (previewLutBuildPipelines[index] != VK_NULL_HANDLE) vkDestroyPipeline(device, previewLutBuildPipelines[index], nullptr);
        if (previewLutModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, previewLutModules[index], nullptr);
        if (previewLutBuildModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, previewLutBuildModules[index], nullptr);
      }
      previewLutPipelines = {};
      previewLutBuildPipelines = {};
      previewLutModules = {};
      previewLutBuildModules = {};
      for (size_t index = 0; index < previewDevelopmentModules.size(); ++index) {
        if (previewDevelopmentPipelines[index] != VK_NULL_HANDLE) vkDestroyPipeline(device, previewDevelopmentPipelines[index], nullptr);
        if (previewDevelopmentModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, previewDevelopmentModules[index], nullptr);
      }
      previewDevelopmentPipelines = {};
      previewDevelopmentModules = {};
      for (size_t index = 0; index < fourStageBuildPipelines.size(); ++index) {
        if (fourStageBuildPipelines[index] != VK_NULL_HANDLE) vkDestroyPipeline(device, fourStageBuildPipelines[index], nullptr);
        if (fourStageBuildModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, fourStageBuildModules[index], nullptr);
      }
      for (size_t index = 0; index < fourStagePrintModules.size(); ++index) {
        for (VkPipeline pipeline : fourStagePrintPipelines[index]) {
          if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
        }
        for (VkPipeline pipeline : fourStageDevelopmentPipelines[index]) {
          if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
        }
        if (fourStagePrintModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, fourStagePrintModules[index], nullptr);
        for (VkShaderModule module : fourStageDevelopmentModules[index]) {
          if (module != VK_NULL_HANDLE) vkDestroyShaderModule(device, module, nullptr);
        }
      }
      fourStageBuildPipelines = {};
      fourStagePrintPipelines = {};
      fourStageDevelopmentPipelines = {};
      fourStageBuildModules = {};
      fourStagePrintModules = {};
      fourStageDevelopmentModules = {};
      for (size_t index = 0; index < imageExposurePipelines.size(); ++index) {
        if (imageExposurePipelines[index] != VK_NULL_HANDLE) vkDestroyPipeline(device, imageExposurePipelines[index], nullptr);
        for (VkPipeline pipeline : imagePrintPipelines[index]) {
          if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
        }
        if (imageExposureModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, imageExposureModules[index], nullptr);
        if (imagePrintModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, imagePrintModules[index], nullptr);
      }
      if (imageScannerPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, imageScannerPipeline, nullptr);
      if (imageScannerModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, imageScannerModule, nullptr);
      imageExposurePipelines = {};
      imageExposureModules = {};
      imagePrintPipelines = {};
      imagePrintModules = {};
      imageScannerPipeline = VK_NULL_HANDLE;
      imageScannerModule = VK_NULL_HANDLE;
      for (auto pipeline : previewColorPipelines) if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
      for (auto pipeline : composedPrintPipelines) if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
      for (auto module : composedPrintModules) if (module != VK_NULL_HANDLE) vkDestroyShaderModule(device, module, nullptr);
      composedPrintPipelines = {};
      composedPrintModules = {};
      for (auto pipeline : videoEffectsPipelines) if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
      videoEffectsPipelines = {};
      if (videoEffectsModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, videoEffectsModule, nullptr);
      if (videoEffectsLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, videoEffectsLayout, nullptr);
      if (videoEffectsSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, videoEffectsSetLayout, nullptr);
      if (videoEffectsLevelLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, videoEffectsLevelLayout, nullptr);
      videoEffectsLevelLayout = VK_NULL_HANDLE;
      videoEffectsModule = VK_NULL_HANDLE;
      videoEffectsLayout = VK_NULL_HANDLE;
      videoEffectsSetLayout = VK_NULL_HANDLE;
      for (auto module : previewColorModules) if (module != VK_NULL_HANDLE) vkDestroyShaderModule(device, module, nullptr);
      previewColorPipelines = {};
      previewColorModules = {};
      if (composePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, composePipeline, nullptr);
      if (composeModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, composeModule, nullptr);
      if (composeLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, composeLayout, nullptr);
      if (composeSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, composeSetLayout, nullptr);
      composePipeline = VK_NULL_HANDLE;
      composeModule = VK_NULL_HANDLE;
      composeLayout = VK_NULL_HANDLE;
      composeSetLayout = VK_NULL_HANDLE;
      if (printProbePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, printProbePipeline, nullptr);
      if (printProbeModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, printProbeModule, nullptr);
      printProbePipeline = VK_NULL_HANDLE;
      printProbeModule = VK_NULL_HANDLE;
      if (directPrintPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, directPrintPipeline, nullptr);
      directPrintPipeline = VK_NULL_HANDLE;
      if (compactPrintPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, compactPrintPipeline, nullptr);
      if (compactPrintModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, compactPrintModule, nullptr);
      for (size_t index = 0; index < splitPrintPipelines.size(); ++index) {
        if (splitPrintPipelines[index] != VK_NULL_HANDLE) vkDestroyPipeline(device, splitPrintPipelines[index], nullptr);
        if (splitPrintModules[index] != VK_NULL_HANDLE) vkDestroyShaderModule(device, splitPrintModules[index], nullptr);
      }
      splitPrintPipelines.fill(VK_NULL_HANDLE);
      splitPrintModules.fill(VK_NULL_HANDLE);
      if (compactPrintProbePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, compactPrintProbePipeline, nullptr);
      if (compactPrintProbeModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, compactPrintProbeModule, nullptr);
      if (compactPrintLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, compactPrintLayout, nullptr);
      if (compactPrintSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, compactPrintSetLayout, nullptr);
      compactPrintPipeline = VK_NULL_HANDLE;
      compactPrintModule = VK_NULL_HANDLE;
      compactPrintProbePipeline = VK_NULL_HANDLE;
      compactPrintProbeModule = VK_NULL_HANDLE;
      compactPrintLayout = VK_NULL_HANDLE;
      compactPrintSetLayout = VK_NULL_HANDLE;
      if (printAccessPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, printAccessPipeline, nullptr);
      if (printAccessModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, printAccessModule, nullptr);
      if (printAccessLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, printAccessLayout, nullptr);
      if (printAccessSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, printAccessSetLayout, nullptr);
      printAccessPipeline = VK_NULL_HANDLE;
      printAccessModule = VK_NULL_HANDLE;
      printAccessLayout = VK_NULL_HANDLE;
      printAccessSetLayout = VK_NULL_HANDLE;
      for (auto &module : previewOperationModules) {
        if (module != VK_NULL_HANDLE) vkDestroyShaderModule(device, module, nullptr);
        module = VK_NULL_HANDLE;
      }
      for (auto &family : operationPipelines) {
        for (auto &pipeline : family) {
          if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
          pipeline = VK_NULL_HANDLE;
        }
      }
      if (grainPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, grainPipeline, nullptr);
        grainPipeline = VK_NULL_HANDLE;
      }
      if (scannerPostPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, scannerPostPipeline, nullptr);
        scannerPostPipeline = VK_NULL_HANDLE;
      }
      if (dirPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, dirPipeline, nullptr);
        dirPipeline = VK_NULL_HANDLE;
      }
      if (diffusionPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, diffusionPipeline, nullptr);
        diffusionPipeline = VK_NULL_HANDLE;
      }
      if (halationPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, halationPipeline, nullptr);
        halationPipeline = VK_NULL_HANDLE;
      }
      if (printScanPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, printScanPipeline, nullptr);
        printScanPipeline = VK_NULL_HANDLE;
      }
      if (curveDevelopPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, curveDevelopPipeline, nullptr);
        curveDevelopPipeline = VK_NULL_HANDLE;
      }
      if (filmExposurePipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, filmExposurePipeline, nullptr);
        filmExposurePipeline = VK_NULL_HANDLE;
      }
      if (autoExposurePipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, autoExposurePipeline, nullptr);
        autoExposurePipeline = VK_NULL_HANDLE;
      }
      if (grainShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, grainShaderModule, nullptr);
        grainShaderModule = VK_NULL_HANDLE;
      }
      if (corePipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device, corePipelineLayout, nullptr);
        corePipelineLayout = VK_NULL_HANDLE;
      }
      if (coreDescriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device, coreDescriptorSetLayout, nullptr);
        coreDescriptorSetLayout = VK_NULL_HANDLE;
      }
      if (curveDevelopShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, curveDevelopShaderModule, nullptr);
        curveDevelopShaderModule = VK_NULL_HANDLE;
      }
      if (filmExposureShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, filmExposureShaderModule, nullptr);
        filmExposureShaderModule = VK_NULL_HANDLE;
      }
      if (autoExposureShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, autoExposureShaderModule, nullptr);
        autoExposureShaderModule = VK_NULL_HANDLE;
      }
      if (printScanShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, printScanShaderModule, nullptr);
        printScanShaderModule = VK_NULL_HANDLE;
      }
      if (halationShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, halationShaderModule, nullptr);
        halationShaderModule = VK_NULL_HANDLE;
      }
      if (diffusionShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, diffusionShaderModule, nullptr);
        diffusionShaderModule = VK_NULL_HANDLE;
      }
      if (diffusionRegionsPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, diffusionRegionsPipeline, nullptr);
        diffusionRegionsPipeline = VK_NULL_HANDLE;
      }
      if (diffusionRegionsModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, diffusionRegionsModule, nullptr);
        diffusionRegionsModule = VK_NULL_HANDLE;
      }
      if (dirShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, dirShaderModule, nullptr);
        dirShaderModule = VK_NULL_HANDLE;
      }
      if (scannerPostShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, scannerPostShaderModule, nullptr);
        scannerPostShaderModule = VK_NULL_HANDLE;
      }
      if (copyPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, copyPipeline, nullptr);
        copyPipeline = VK_NULL_HANDLE;
      }
      if (formatConvertPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, formatConvertPipeline, nullptr);
        formatConvertPipeline = VK_NULL_HANDLE;
      }
      if (imageFormatConvertPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, imageFormatConvertPipeline, nullptr);
        imageFormatConvertPipeline = VK_NULL_HANDLE;
      }
      if (copyPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device, copyPipelineLayout, nullptr);
        copyPipelineLayout = VK_NULL_HANDLE;
      }
      if (imageFormatConvertPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device, imageFormatConvertPipelineLayout, nullptr);
        imageFormatConvertPipelineLayout = VK_NULL_HANDLE;
      }
      if (copyDescriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device, copyDescriptorSetLayout, nullptr);
        copyDescriptorSetLayout = VK_NULL_HANDLE;
      }
      if (imageFormatConvertDescriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device, imageFormatConvertDescriptorSetLayout, nullptr);
        imageFormatConvertDescriptorSetLayout = VK_NULL_HANDLE;
      }
      if (copyShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, copyShaderModule, nullptr);
        copyShaderModule = VK_NULL_HANDLE;
      }
      if (formatConvertShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, formatConvertShaderModule, nullptr);
        formatConvertShaderModule = VK_NULL_HANDLE;
      }
      if (imageFormatConvertShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, imageFormatConvertShaderModule, nullptr);
        imageFormatConvertShaderModule = VK_NULL_HANDLE;
      }
      queueSlots.clear();
      if (!externalDevice) {
        vkDestroyDevice(device, nullptr);
      }
      device = VK_NULL_HANDLE;
    }
    if (instance != VK_NULL_HANDLE) {
      if (!externalDevice) {
        vkDestroyInstance(instance, nullptr);
      }
      instance = VK_NULL_HANDLE;
    }
    physicalDevice = VK_NULL_HANDLE;
    externalQueueMutex = nullptr;
    externalDevice = false;
  }
};

std::mutex gVulkanSharedBackendMutex;
std::weak_ptr<VulkanSharedBackend> gVulkanSharedBackend;
std::atomic<uint32_t> gVulkanSharedBackendGeneration{0};

std::shared_ptr<VulkanSharedBackend> acquireVulkanSharedBackend(
  const ExternalVulkanContext *externalContext,
  std::string &error
) {
  std::lock_guard<std::mutex> lock(gVulkanSharedBackendMutex);
  if (std::shared_ptr<VulkanSharedBackend> existing = gVulkanSharedBackend.lock()) {
    if (existing->isAvailable() && existing->matchesExternalContext(externalContext)) {
      return existing;
    }
    gVulkanSharedBackend.reset();
  }

  const uint32_t generation = gVulkanSharedBackendGeneration.fetch_add(1u) + 1u;
  auto backend = externalContext
    ? std::make_shared<VulkanSharedBackend>(generation, *externalContext)
    : std::make_shared<VulkanSharedBackend>(generation);
  if (!backend->isAvailable()) {
    error = backend->lastError.empty() ? "Unable to initialize shared Vulkan backend." : backend->lastError;
    return nullptr;
  }
  gVulkanSharedBackend = backend;
  return backend;
}

void clearVulkanSharedBackendIfSame(const std::shared_ptr<VulkanSharedBackend> &backend) {
  if (!backend) {
    return;
  }
  std::lock_guard<std::mutex> lock(gVulkanSharedBackendMutex);
  if (gVulkanSharedBackend.lock() == backend) {
    gVulkanSharedBackend.reset();
  }
}

float filmFormatLongEdgeMm(FilmFormat format) {
  switch (format) {
    case FilmFormat::Standard8:
      return 4.8f;
    case FilmFormat::Super8:
      return 5.79f;
    case FilmFormat::Standard16:
      return 10.26f;
    case FilmFormat::Super16:
      return 12.52f;
    case FilmFormat::Super35:
      return 24.89f;
    case FilmFormat::Standard65:
      return 52.48f;
    case FilmFormat::Imax70:
      return 70.41f;
    case FilmFormat::Standard35:
    default:
      return 35.0f;
  }
}

float resolvedEnlargerScale(const RenderParams &params) {
  return std::clamp(params.enlargerScale, 1.0f, 32.0f);
}

float filmPushPullGamma(float stops) {
  const float clampedStops = std::clamp(stops, -2.0f, 2.0f);
  constexpr float kNormalEcn2Seconds = 180.0f;
  constexpr float kPull1Seconds = 150.0f;
  constexpr float kPush1Seconds = 220.0f;
  constexpr float kPush2Seconds = 280.0f;
  if (clampedStops < 0.0f) {
    return std::pow(kPull1Seconds / kNormalEcn2Seconds, -clampedStops);
  }
  if (clampedStops <= 1.0f) {
    return std::exp(std::log(kPush1Seconds / kNormalEcn2Seconds) * clampedStops);
  }
  const float segment = clampedStops - 1.0f;
  return std::exp(
    std::log(kPush1Seconds / kNormalEcn2Seconds) +
    std::log(kPush2Seconds / kPush1Seconds) * segment
  );
}

float printPushPullGamma(float stops) {
  const float clampedStops = std::clamp(stops, -2.0f, 2.0f);
  return std::exp2(clampedStops * 0.25f);
}

float scannerSigmaUmFromMtf50(float mtf50LpMm) {
  if (!std::isfinite(mtf50LpMm) || mtf50LpMm <= 0.0f) {
    return 0.0f;
  }
  constexpr float kPi = 3.14159265358979323846f;
  return 1000.0f * std::sqrt(std::log(2.0f) / (2.0f * kPi * kPi)) / mtf50LpMm;
}

void autoExposurePreviewShape(int32_t width, int32_t height, int32_t &previewWidth, int32_t &previewHeight) {
  constexpr int32_t kPreviewMaxSize = 256;
  const int32_t longEdge = std::max(width, height);
  if (longEdge <= kPreviewMaxSize) {
    previewWidth = width;
    previewHeight = height;
    return;
  }
  const double scale = static_cast<double>(kPreviewMaxSize) / static_cast<double>(longEdge);
  previewWidth = std::max(1, static_cast<int32_t>(std::lround(static_cast<double>(width) * scale)));
  previewHeight = std::max(1, static_cast<int32_t>(std::lround(static_cast<double>(height) * scale)));
}

bool isDefaultHalationStrength(const RenderParams &params) {
  return std::abs(params.halationStrengthR - 0.05f) <= 1.0e-6f &&
         std::abs(params.halationStrengthG - 0.015f) <= 1.0e-6f &&
         std::abs(params.halationStrengthB) <= 1.0e-6f;
}

DiffusionFamilyShape diffusionShape(DiffusionFilterFamily family) {
  switch (family) {
    case DiffusionFilterFamily::Glimmerglass:
      return {{10.0f, 1.5f, 2u, 3.0f}, {50.0f, 2.0f, 3u, 3.0f}, {260.0f, 2.5f, 4u, 3.2f}, 0.60f, 0.30f, 0.10f, 0.0f, 0.65f};
    case DiffusionFilterFamily::ProMist:
      return {{14.0f, 1.5f, 2u, 3.0f}, {150.0f, 2.0f, 3u, 3.0f}, {650.0f, 2.5f, 4u, 2.9f}, 0.28f, 0.42f, 0.30f, 0.40f, 1.05f};
    case DiffusionFilterFamily::CineBloom:
      return {{20.0f, 1.5f, 2u, 3.0f}, {200.0f, 2.0f, 3u, 3.0f}, {1000.0f, 2.5f, 4u, 2.5f}, 0.22f, 0.30f, 0.48f, 0.85f, 1.00f};
    case DiffusionFilterFamily::BlackProMist:
    default:
      return {{16.0f, 1.5f, 2u, 3.0f}, {95.0f, 2.0f, 3u, 3.0f}, {380.0f, 2.5f, 4u, 3.5f}, 0.40f, 0.47f, 0.13f, 0.65f, 0.75f};
  }
}

float diffusionScatterFraction(float strength, float familyGain) {
  if (strength <= 0.0f) {
    return 0.0f;
  }
  constexpr std::array<float, 5> kBreaks = {0.125f, 0.25f, 0.5f, 1.0f, 2.0f};
  constexpr std::array<float, 5> kFractions = {0.10f, 0.20f, 0.35f, 0.55f, 0.75f};
  if (strength <= kBreaks.front()) {
    return std::clamp(kFractions.front() * (strength / kBreaks.front()) * familyGain, 0.0f, 0.99f);
  }
  const float logStrength = std::log2(strength);
  if (logStrength >= std::log2(kBreaks.back())) {
    return std::clamp(kFractions.back() * familyGain, 0.0f, 0.99f);
  }
  for (size_t i = 0; i + 1u < kBreaks.size(); ++i) {
    const float x0 = std::log2(kBreaks[i]);
    const float x1 = std::log2(kBreaks[i + 1u]);
    if (logStrength >= x0 && logStrength <= x1) {
      const float t = std::clamp((logStrength - x0) / std::max(x1 - x0, 1.0e-6f), 0.0f, 1.0f);
      return std::clamp((kFractions[i] + (kFractions[i + 1u] - kFractions[i]) * t) * familyGain, 0.0f, 0.99f);
    }
  }
  return 0.0f;
}

std::vector<float> diffusionLambdas(const DiffusionGroup &group) {
  std::vector<float> lambdas(group.count, group.lambdaUm);
  if (group.count <= 1u || group.spread <= 1.0f) {
    return lambdas;
  }
  const float logLo = std::log(group.lambdaUm / group.spread);
  const float logHi = std::log(group.lambdaUm * group.spread);
  for (uint32_t i = 0; i < group.count; ++i) {
    const float t = group.count == 1u ? 0.0f : static_cast<float>(i) / static_cast<float>(group.count - 1u);
    lambdas[i] = std::exp(logLo + (logHi - logLo) * t);
  }
  return lambdas;
}

std::vector<float> diffusionWeights(const DiffusionGroup &group, bool bloom) {
  const std::vector<float> lambdas = diffusionLambdas(group);
  std::vector<float> weights(lambdas.size(), 1.0f);
  if (bloom) {
    for (size_t i = 0; i < weights.size(); ++i) {
      weights[i] = std::pow(std::max(lambdas[i], 1.0e-6f), 2.0f - group.alpha);
    }
  }
  float sum = 0.0f;
  for (float weight : weights) {
    sum += weight;
  }
  for (float &weight : weights) {
    weight /= std::max(sum, 1.0e-6f);
  }
  return weights;
}

std::array<std::vector<float>, 3> haloChannelWeights(const std::vector<float> &weights, float warmth) {
  constexpr std::array<float, 3> kWarmthAxis = {1.30f, 0.15f, -1.45f};
  std::array<std::vector<float>, 3> out;
  const size_t count = weights.size();
  for (auto &channel : out) {
    channel = weights;
  }
  if (count < 2u) {
    return out;
  }
  warmth = std::clamp(warmth, -1.5f, 1.5f);
  std::vector<float> gradient(count, 0.0f);
  float totalWeight = 0.0f;
  float weightedGradient = 0.0f;
  for (size_t i = 0; i < count; ++i) {
    gradient[i] = -1.0f + 2.0f * static_cast<float>(i) / static_cast<float>(count - 1u);
    totalWeight += weights[i];
    weightedGradient += weights[i] * gradient[i];
  }
  const float gradientMean = weightedGradient / std::max(totalWeight, 1.0e-6f);
  for (float &value : gradient) {
    value -= gradientMean;
  }
  for (size_t channel = 0; channel < 3u; ++channel) {
    float sum = 0.0f;
    for (size_t i = 0; i < count; ++i) {
      out[channel][i] = std::max(weights[i] * (1.0f + warmth * kWarmthAxis[channel] * gradient[i]), 0.0f);
      sum += out[channel][i];
    }
    for (size_t i = 0; i < count; ++i) {
      out[channel][i] *= totalWeight / std::max(sum, 1.0e-6f);
    }
  }
  return out;
}

void appendDiffusionGroupComponents(
  std::vector<VulkanDiffusionComponent> &components,
  const DiffusionGroup &group,
  const std::vector<float> &weights,
  const std::array<float, 3> &channelScale,
  float groupWeight,
  float spatialScale,
  float pixelSizeUm
) {
  constexpr std::array<std::array<float, 2>, 3> kExpGaussianFit = {{
    {0.1633f, 0.5360f},
    {0.6496f, 1.5236f},
    {0.1870f, 2.7684f},
  }};
  const std::vector<float> lambdas = diffusionLambdas(group);
  for (size_t i = 0; i < lambdas.size(); ++i) {
    for (const auto &fit : kExpGaussianFit) {
      const float sigmaPx = std::max(lambdas[i] * fit[1] * spatialScale / std::max(pixelSizeUm, 1.0e-6f), 1.0e-6f);
      const float weight = groupWeight * weights[i] * fit[0];
      const float weightR = weight * channelScale[0];
      const float weightG = weight * channelScale[1];
      const float weightB = weight * channelScale[2];
      if (weightR == 0.0f && weightG == 0.0f && weightB == 0.0f) {
        continue;
      }
      components.push_back({sigmaPx, weightR, weightG, weightB});
    }
  }
}

void clusterDiffusionComponents(std::vector<VulkanDiffusionComponent> &components, float sigmaRatio) {
  if (components.size() < 2u || sigmaRatio <= 0.0f) {
    return;
  }
  std::sort(
    components.begin(),
    components.end(),
    [](const VulkanDiffusionComponent &a, const VulkanDiffusionComponent &b) {
      return a.sigmaPx < b.sigmaPx;
    }
  );
  std::vector<VulkanDiffusionComponent> clustered;
  clustered.reserve(components.size());
  for (const VulkanDiffusionComponent &component : components) {
    if (clustered.empty()) {
      clustered.push_back(component);
      continue;
    }
    VulkanDiffusionComponent &last = clustered.back();
    const float denom = std::max(std::max(last.sigmaPx, component.sigmaPx), 1.0e-6f);
    if (std::abs(component.sigmaPx - last.sigmaPx) / denom <= sigmaRatio) {
      const float lastWeight = std::abs(last.weightR) + std::abs(last.weightG) + std::abs(last.weightB);
      const float componentWeight = std::abs(component.weightR) + std::abs(component.weightG) + std::abs(component.weightB);
      const float totalWeight = lastWeight + componentWeight;
      if (totalWeight > 1.0e-8f) {
        last.sigmaPx = (last.sigmaPx * lastWeight + component.sigmaPx * componentWeight) / totalWeight;
      }
      last.weightR += component.weightR;
      last.weightG += component.weightG;
      last.weightB += component.weightB;
    } else {
      clustered.push_back(component);
    }
  }
  components.swap(clustered);
}

uint32_t diffusionDownsampleScaleForSigma(const std::string &mode, float sigmaPx) {
  if (mode == "off") {
    return 1u;
  }
  if (mode == "auto") {
    if (sigmaPx >= 48.0f) {
      return 8u;
    }
    if (sigmaPx >= 24.0f) {
      return 4u;
    }
    if (sigmaPx >= 12.0f) {
      return 2u;
    }
    return 1u;
  }
  if (mode == "8") {
    return sigmaPx >= 48.0f ? 8u : 1u;
  }
  if (mode == "4") {
    return sigmaPx >= 24.0f ? 4u : 1u;
  }
  if (mode == "2") {
    return sigmaPx >= 12.0f ? 2u : 1u;
  }
  return 1u;
}

bool anyDiffusionComponentDownsamples(
  const std::vector<VulkanDiffusionComponent> &components,
  const std::string &mode
) {
  for (const VulkanDiffusionComponent &component : components) {
    if (diffusionDownsampleScaleForSigma(mode, component.sigmaPx) > 1u) {
      return true;
    }
  }
  return false;
}

std::vector<VulkanDiffusionComponent> makeDiffusionComponents(
  const DiffusionSettings &settings,
  float pixelSizeUm,
  VulkanDiffusionInfo &info,
  float clusterSigmaRatio = 0.0f
) {
  DiffusionFamilyShape shape = diffusionShape(settings.family);
  const float coreIntensity = std::max(settings.coreIntensity, 0.0f);
  const float haloIntensity = std::max(settings.haloIntensity, 0.0f);
  const float bloomIntensity = std::max(settings.bloomIntensity, 0.0f);
  float wc = shape.weightCore * coreIntensity;
  float wh = shape.weightHalo * haloIntensity;
  float wb = shape.weightBloom * bloomIntensity;
  const float total = wc + wh + wb;
  if (total > 0.0f) {
    wc /= total;
    wh /= total;
    wb /= total;
  } else {
    info = {};
    return {};
  }
  shape.core.lambdaUm *= std::max(settings.coreSize, 1.0e-6f);
  shape.halo.lambdaUm *= std::max(settings.haloSize, 1.0e-6f);
  shape.bloom.lambdaUm *= std::max(settings.bloomSize, 1.0e-6f);

  info.scatterFraction = diffusionScatterFraction(settings.strength, shape.totalGain);
  std::vector<VulkanDiffusionComponent> components;
  if (info.scatterFraction <= 0.0f || settings.spatialScale <= 0.0f) {
    info.componentCount = 0u;
    return components;
  }

  appendDiffusionGroupComponents(components, shape.core, diffusionWeights(shape.core, false),
    {1.0f, 1.0f, 1.0f}, wc, settings.spatialScale, pixelSizeUm);

  const std::vector<float> haloWeights = diffusionWeights(shape.halo, false);
  const auto haloPerChannel = haloChannelWeights(haloWeights, shape.warmthBase + settings.haloWarmth);
  const std::vector<float> haloLambdas = diffusionLambdas(shape.halo);
  constexpr std::array<std::array<float, 2>, 3> kExpGaussianFit = {{
    {0.1633f, 0.5360f},
    {0.6496f, 1.5236f},
    {0.1870f, 2.7684f},
  }};
  for (size_t i = 0; i < haloLambdas.size(); ++i) {
    for (const auto &fit : kExpGaussianFit) {
      const float sigmaPx = std::max(haloLambdas[i] * fit[1] * settings.spatialScale / std::max(pixelSizeUm, 1.0e-6f), 1.0e-6f);
      const float weightR = wh * haloPerChannel[0][i] * fit[0];
      const float weightG = wh * haloPerChannel[1][i] * fit[0];
      const float weightB = wh * haloPerChannel[2][i] * fit[0];
      if (weightR == 0.0f && weightG == 0.0f && weightB == 0.0f) {
        continue;
      }
      components.push_back({sigmaPx, weightR, weightG, weightB});
    }
  }

  appendDiffusionGroupComponents(components, shape.bloom, diffusionWeights(shape.bloom, true),
    {1.0f, 1.0f, 1.0f}, wb, settings.spatialScale, pixelSizeUm);

  clusterDiffusionComponents(components, clusterSigmaRatio);
  if (components.size() > kMaxVulkanDiffusionComponents) {
    components.resize(kMaxVulkanDiffusionComponents);
  }
  info.componentCount = static_cast<uint32_t>(components.size());
  return components;
}

video_effects::Kernel videoDiffusionKernel(const DiffusionSettings &settings, float pixelSizeUm) {
  VulkanDiffusionInfo info{};
  const auto components = makeDiffusionComponents(settings, pixelSizeUm, info, 0.0f);
  auto kernel = video_effects::identityKernel();
  if (components.empty() || info.scatterFraction <= 0.0f) return kernel;
  const double fraction = std::clamp(info.scatterFraction, 0.0f, 0.99f);
  for (auto &channel : kernel) channel.front().weight = 1.0 - fraction;
  for (const auto &component : components) {
    const double variance = static_cast<double>(component.sigmaPx) * component.sigmaPx;
    const std::array<float, 3> weights{component.weightR, component.weightG, component.weightB};
    for (size_t channel = 0; channel < 3u; ++channel) {
      if (weights[channel] != 0.0f) kernel[channel].push_back({variance, fraction * weights[channel]});
    }
  }
  return kernel;
}

// Emulsion scatter followed by the halation bounce, matching the photo halation pass.
video_effects::Kernel videoHalationKernel(const RenderParams &params, float pixelSizeUm,
                                          const std::array<float, 3> &strength,
                                          const std::array<float, 3> &firstSigmaUm) {
  auto kernel = video_effects::identityKernel();
  const double pixelSize = std::max(pixelSizeUm, 1.0e-6f);
  auto square = [](double value) { return value * value; };
  if (params.scatterAmount > 0.0f && params.scatterScale > 0.0f) {
    constexpr std::array<double, 3> core{2.2, 2.0, 1.6};
    constexpr std::array<double, 3> tail{9.3, 9.7, 9.1};
    constexpr std::array<double, 3> tailMix{0.78, 0.65, 0.67};
    constexpr std::array<double, 3> ratios{0.5360, 1.5236, 2.7684};
    constexpr std::array<double, 3> tailWeights{0.1633, 0.6496, 0.1870};
    const double amount = std::clamp(params.scatterAmount, 0.0f, 1.0f);
    const double scale = params.scatterScale / pixelSize;
    video_effects::Kernel scatter;
    for (size_t channel = 0; channel < 3u; ++channel) {
      scatter[channel].push_back({0.0, 1.0 - amount});
      scatter[channel].push_back({square(core[channel] * scale), amount * (1.0 - tailMix[channel])});
      for (size_t component = 0; component < 3u; ++component) {
        scatter[channel].push_back({square(tail[channel] * ratios[component] * scale),
          amount * tailMix[channel] * tailWeights[component]});
      }
    }
    kernel = video_effects::convolve(scatter, kernel);
  }
  if (params.halationAmount > 0.0f && params.halationScale > 0.0f &&
      (params.halationStrengthR > 0.0f || params.halationStrengthG > 0.0f || params.halationStrengthB > 0.0f)) {
    video_effects::Kernel bounce;
    for (size_t channel = 0; channel < 3u; ++channel) {
      const double amount = std::max(strength[channel], 0.0f) * std::max(params.halationAmount, 0.0f);
      bounce[channel].push_back({0.0, 1.0 / (1.0 + amount)});
      for (uint32_t component = 0; component < 3u; ++component) {
        const double sigma = std::max(firstSigmaUm[channel], 1.0e-6f) * params.halationScale *
          std::sqrt(static_cast<double>(component + 1u)) / pixelSize;
        bounce[channel].push_back({square(sigma),
          amount / (1.0 + amount) * std::pow(0.5, static_cast<double>(component)) / 1.75});
      }
    }
    kernel = video_effects::convolve(bounce, kernel);
  }
  return kernel;
}

uint32_t halationSpatialRadius(const RenderParams &params, float pixelSizeUm) {
  uint32_t radius = 0u;
  auto gaussianRadius = [&](float sigmaUm) {
    return static_cast<uint32_t>(std::min(std::ceil(3.0f * sigmaUm / pixelSizeUm), 256.0f));
  };
  if (params.scatterAmount > 0.0f && params.scatterScale > 0.0f) {
    radius += gaussianRadius(9.7f * 2.7684f * params.scatterScale);
  }
  if (params.halationAmount > 0.0f && params.halationScale > 0.0f &&
      (params.halationStrengthR > 0.0f || params.halationStrengthG > 0.0f || params.halationStrengthB > 0.0f)) {
    const float sigma = std::max({params.halationFirstSigmaUmR, params.halationFirstSigmaUmG,
                                  params.halationFirstSigmaUmB, 1.0e-6f});
    // Bounce reads the scatter result, so their sampling footprints add.
    radius += gaussianRadius(sigma * params.halationScale * std::sqrt(3.0f));
  }
  return radius;
}

uint32_t diffusionSpatialRadius(const std::vector<VulkanDiffusionComponent> &components,
                                const std::string &mode, bool preview) {
  uint32_t radius = 0u;
  for (const auto &component : components) {
    const uint32_t scale = preview ? preview_effects::diffusionScale(component.sigmaPx)
      : diffusionDownsampleScaleForSigma(mode, component.sigmaPx);
    const uint32_t reducedRadius = static_cast<uint32_t>(
      std::min(std::ceil(3.0f * component.sigmaPx / static_cast<float>(scale)), 256.0f));
    // Reduced blurs also need the box-filter footprint and bilinear reconstruction neighbors.
    const uint32_t support = reducedRadius * scale + (scale > 1u ? 2u * scale : 0u);
    radius = std::max(radius, support);
  }
  return radius;
}

float interpLinearDensityCurve(
  const std::vector<float> &x,
  const float *y,
  uint32_t channel,
  float target
) {
  if (x.empty() || !y) {
    return 0.0f;
  }
  if (x.size() == 1u) {
    return y[channel];
  }
  const bool ascending = x.back() >= x.front();
  if ((ascending && target <= x.front()) || (!ascending && target >= x.front())) {
    return y[channel];
  }
  if ((ascending && target >= x.back()) || (!ascending && target <= x.back())) {
    return y[(x.size() - 1u) * 3u + channel];
  }

  uint32_t lo = 0u;
  uint32_t hi = static_cast<uint32_t>(x.size() - 1u);
  while (hi - lo > 1u) {
    const uint32_t mid = (lo + hi) >> 1u;
    if ((ascending && x[mid] <= target) || (!ascending && x[mid] >= target)) {
      lo = mid;
    } else {
      hi = mid;
    }
  }

  const float denom = std::max(std::abs(x[hi] - x[lo]), 1.0e-9f);
  const float t = std::clamp((target - x[lo]) / denom, 0.0f, 1.0f);
  const float y0 = y[lo * 3u + channel];
  const float y1 = y[hi * 3u + channel];
  return y0 + (y1 - y0) * t;
}

std::array<float, kDirFloatCount> makeDirFloatParams(
  const ProfileCurveSet &filmCurves,
  const RenderParams &params,
  float filmPixelSizeUm
) {
  const float amount = std::max(params.dirCouplersAmount, 0.0f);
  const float sameLayer = std::max(params.dirCouplersInhibitionSameLayer, 0.0f);
  const float interlayer = std::max(params.dirCouplersInhibitionInterlayer, 0.0f);
  const std::array<float, 3> densityMaximums = densityCurveMaximums(filmCurves);
  std::array<float, kDirFloatCount> out{};
  out[0] = params.dirCouplersGammaSameLayerR * sameLayer * amount;
  out[4] = params.dirCouplersGammaSameLayerG * sameLayer * amount;
  out[8] = params.dirCouplersGammaSameLayerB * sameLayer * amount;
  out[1] = params.dirCouplersGammaRToG * interlayer * amount;
  out[2] = params.dirCouplersGammaRToB * interlayer * amount;
  out[3] = params.dirCouplersGammaGToR * interlayer * amount;
  out[5] = params.dirCouplersGammaGToB * interlayer * amount;
  out[6] = params.dirCouplersGammaBToR * interlayer * amount;
  out[7] = params.dirCouplersGammaBToG * interlayer * amount;
  out[9] = densityMaximums[0];
  out[10] = densityMaximums[1];
  out[11] = densityMaximums[2];
  out[12] = std::max(params.dirCouplersDiffusionUm, 0.0f) / std::max(filmPixelSizeUm, 1.0e-6f);
  out[13] = std::max(params.dirCouplersDiffusionTailUm, 0.0f) * 0.5360f / std::max(filmPixelSizeUm, 1.0e-6f);
  out[14] = std::max(params.dirCouplersDiffusionTailUm, 0.0f) * 1.5236f / std::max(filmPixelSizeUm, 1.0e-6f);
  out[15] = std::max(params.dirCouplersDiffusionTailUm, 0.0f) * 2.7684f / std::max(filmPixelSizeUm, 1.0e-6f);
  out[16] = std::clamp(params.dirCouplersDiffusionTailWeight, 0.0f, 1.0f);
  out[17] = filmCurves.type && std::strcmp(filmCurves.type, "positive") == 0 ? 1.0f : 0.0f;
  return out;
}

std::vector<float> makeDirCorrectedDensityCurves(
  const ProfileCurveSet &filmCurves,
  const std::array<float, kDirFloatCount> &dirFloats
) {
  const bool positive = dirFloats[17] > 0.5f;
  std::vector<float> corrected(static_cast<size_t>(filmCurves.exposureCount) * 3u, 0.0f);
  for (uint32_t receiver = 0; receiver < 3u; ++receiver) {
    std::vector<float> logExposure0(filmCurves.exposureCount, 0.0f);
    for (uint32_t i = 0; i < filmCurves.exposureCount; ++i) {
      const float d0 = filmCurves.densityCurves[i * 3u];
      const float d1 = filmCurves.densityCurves[i * 3u + 1u];
      const float d2 = filmCurves.densityCurves[i * 3u + 2u];
      const float silver0 = positive ? dirFloats[9] - d0 : d0;
      const float silver1 = positive ? dirFloats[10] - d1 : d1;
      const float silver2 = positive ? dirFloats[11] - d2 : d2;
      float amount = 0.0f;
      if (receiver == 0u) {
        amount = silver0 * dirFloats[0] + silver1 * dirFloats[3] + silver2 * dirFloats[6];
      } else if (receiver == 1u) {
        amount = silver0 * dirFloats[1] + silver1 * dirFloats[4] + silver2 * dirFloats[7];
      } else {
        amount = silver0 * dirFloats[2] + silver1 * dirFloats[5] + silver2 * dirFloats[8];
      }
      logExposure0[i] = filmCurves.logExposure[i] - amount;
    }
    for (uint32_t i = 0; i < filmCurves.exposureCount; ++i) {
      corrected[i * 3u + receiver] = interpLinearDensityCurve(
        logExposure0,
        filmCurves.densityCurves,
        receiver,
        filmCurves.logExposure[i]
      );
    }
  }
  return corrected;
}

std::array<float, 3> scanIlluminantToOutputRgb(
  const ProfileCurveSet *paperCurves,
  const RenderParams &params
) {
  if (!paperCurves || paperCurves->wavelengthCount == 0u || !paperCurves->scanIlluminant ||
      !paperCurves->scanToOutputRgb || !standardObserverCmfs()) {
    return {1.0f, 1.0f, 1.0f};
  }

  std::array<float, 3> xyz = {0.0f, 0.0f, 0.0f};
  float normalization = 0.0f;
  const float *cmfs = standardObserverCmfs();
  for (uint32_t wavelength = 0; wavelength < paperCurves->wavelengthCount; ++wavelength) {
    const uint32_t offset = wavelength * 3u;
    const float illuminant = paperCurves->scanIlluminant[wavelength];
    xyz[0] += illuminant * cmfs[offset];
    xyz[1] += illuminant * cmfs[offset + 1u];
    xyz[2] += illuminant * cmfs[offset + 2u];
    normalization += illuminant * cmfs[offset + 1u];
  }

  const float invNormalization = 1.0f / std::max(normalization, 1.0e-10f);
  xyz[0] *= invNormalization;
  xyz[1] *= invNormalization;
  xyz[2] *= invNormalization;

  const uint32_t colorSpace = std::min<uint32_t>(
    static_cast<uint32_t>(std::max(static_cast<int32_t>(params.outputColorSpace), 0)),
    kSpektraColorSpaceCount - 1u
  );
  const float *matrix = paperCurves->scanToOutputRgb + static_cast<size_t>(colorSpace) * 9u;
  return {
    matrix[0] * xyz[0] + matrix[1] * xyz[1] + matrix[2] * xyz[2],
    matrix[3] * xyz[0] + matrix[4] * xyz[1] + matrix[5] * xyz[2],
    matrix[6] * xyz[0] + matrix[7] * xyz[1] + matrix[8] * xyz[2],
  };
}

} // namespace

struct VulkanRenderer::Impl {
  struct ScratchBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize capacity = 0;
    VkDeviceSize initializedBytes = 0;
    VkDeviceSize allocationSize = 0;
    VkMemoryPropertyFlags memoryFlags = 0;
    void *mapped = nullptr;
    std::vector<uint8_t> cachedUpload;
  };

  struct StaticBufferUpload {
    ScratchBuffer *scratch = nullptr;
    const void *data = nullptr;
    VkDeviceSize size = 0;
    const char *name = nullptr;
  };

  struct CopyFrameResources {
    ScratchBuffer source;
    ScratchBuffer destination;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool submitted = false;
  };

  struct FrameSamples {
    static constexpr uint32_t kGrid = 4;
    static constexpr uint32_t kCount = kGrid * kGrid;
    static constexpr VkDeviceSize kStageBytes = kCount * 4u * sizeof(float);
    static constexpr VkDeviceSize kMetadataOffset = 6u * kStageBytes;
    static constexpr VkDeviceSize kProbeOffset = kMetadataOffset + 512u;
    static constexpr VkDeviceSize kProbeBytes = 3u * 16u * sizeof(float);
    static constexpr VkDeviceSize kTransferOffset = kProbeOffset + kProbeBytes;
    static constexpr VkDeviceSize kBytes = kTransferOffset + 4u * sizeof(float);
    ScratchBuffer readback;
    VkDescriptorPool probePool = VK_NULL_HANDLE;
    VkDescriptorSet probeSet = VK_NULL_HANDLE;
    bool ready = false;
    bool preview = false;
    bool printConstants = false;
    bool printExecutionVerified = false;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stages = 0;
    uint64_t sequence = 0;
    RenderParams params;
  };

  struct VideoEffectResources {
    struct Pyramid {
      VkImage image = VK_NULL_HANDLE;
      VkDeviceMemory memory = VK_NULL_HANDLE;
      VkImageView sampled = VK_NULL_HANDLE;
      std::array<VkImageView, video_effects::kMaxLevels> levels{};
    };
    Pyramid film;
    Pyramid print;
    VkSampler sampler = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet shared = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, video_effects::kMaxLevels> levelSets{};
    ScratchBuffer info;
    ScratchBuffer state;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t baseWidth = 0;
    uint32_t baseHeight = 0;
    uint32_t levelCount = 0;
    bool pyramid = false;
    bool initialized = false;
    std::array<uint32_t, 4> loggedConfig{};
    VkQueryPool timing = VK_NULL_HANDLE;
    bool timingPending = false;
    bool loggedSkip = false;
  };

  struct CoreFrameResources {
    static constexpr size_t kPhaseScratchCount = 8u;
    static constexpr uint32_t kTimingQueryCount = 36u;
    static constexpr uint32_t kColorLutSize = SPEKTRA_PREVIEW_COLOR_LUT_SIZE;
    static constexpr VkDeviceSize kColorLutBytes =
      VkDeviceSize{kColorLutSize} * kColorLutSize * kColorLutSize * 2u * 4u * sizeof(float);
    struct ColorLutKey {
      int32_t film = -1;
      int32_t paper = -1;
      ProcessMode process = ProcessMode::PrintSimulation;
      PrintTimingMode timing = PrintTimingMode::FilteredEnlarger;
      std::array<float, 3> filters{};
      bool operator==(const ColorLutKey &) const = default;
    };
    ColorLutKey colorLutKey;
    bool colorLutReady = false;
    uint64_t colorLutGeneration = 0;
    ScratchBuffer colorLut;
    static constexpr uint32_t kFourStageLutSize = SPEKTRA_FOUR_STAGE_LUT_SIZE;
    static constexpr uint32_t kFourStageProbeSize = SPEKTRA_FOUR_STAGE_LUT_PROBE_SIZE;
    static constexpr VkDeviceSize kFourStageLutBytes = sizeof(float) * 4u *
      (SPEKTRA_FOUR_STAGE_LUT_METADATA_COUNT + 3u * kFourStageProbeSize * kFourStageProbeSize * kFourStageProbeSize +
       VkDeviceSize{kFourStageLutSize} * kFourStageLutSize * kFourStageLutSize * 4u);
    ScratchBuffer fourStageLut;
    VkImage fourStageTexture = VK_NULL_HANDLE;
    VkDeviceMemory fourStageTextureMemory = VK_NULL_HANDLE;
    VkImageView fourStageTextureView = VK_NULL_HANDLE;
    VkSampler fourStageTextureSampler = VK_NULL_HANDLE;
    VkDeviceSize fourStageTextureBytes = 0;
    bool fourStageTextureReady = false;
    uint64_t fourStageTextureGeneration = 0;
    struct ComposedLut {
      VkImage image = VK_NULL_HANDLE;
      VkDeviceMemory memory = VK_NULL_HANDLE;
      VkImageView view = VK_NULL_HANDLE;
      VkDescriptorPool pool = VK_NULL_HANDLE;
      VkDescriptorSet set = VK_NULL_HANDLE;
      VkDeviceSize bytes = 0;
      uint32_t size = 0;
      uint64_t generation = 0;
      bool ready = false;
    } composedLut;
    FourStageColorLutKey fourStageLutKey;
    bool fourStageLutReady = false;
    uint64_t fourStageLutGeneration = 0;
    ScratchBuffer sourceStaging;
    ScratchBuffer source;
    ScratchBuffer autoExposureLuminance;
    ScratchBuffer autoExposureSource;
    ScratchBuffer autoExposureResult;
    ScratchBuffer filmRaw;
    ScratchBuffer filmDensity;
    ScratchBuffer destination;
    ScratchBuffer destinationHalf;
    ScratchBuffer destinationStaging;
    ScratchBuffer destinationHalfStaging;
    ScratchBuffer halationPreviewSource;
    ScratchBuffer halationBoostChunks;
    ScratchBuffer halationBoostInfo;
    ScratchBuffer halationBoostInfoReadback;
    ScratchBuffer tiledHalationBoostInfo;
    ScratchBuffer cameraDiffusionInfo;
    ScratchBuffer cameraDiffusionComponents;
    ScratchBuffer diffusionRegions;
    ScratchBuffer printDiffusionInfo;
    ScratchBuffer printDiffusionComponents;
    ScratchBuffer dirFloats;
    ScratchBuffer dirCorrectedDensityCurves;
    ScratchBuffer frameFloats;
    ScratchBuffer frameInts;
    ScratchBuffer filteredEnlargerResponse;
    std::array<ScratchBuffer, kPhaseScratchCount> phaseScratch;
    bool sourceAliasedForGrain = false;
    bool filmRawAliasedForGrain = false;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet autoExposureDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet exposureDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet developDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet finalDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorPool compactPrintPool = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, 3> compactPrintSets{};
    std::array<VkDescriptorSet, kCoreHalationSetCount> halationDescriptorSets{};
    std::array<VkDescriptorSet, kCoreDiffusionSetCount> diffusionDescriptorSets{};
    std::array<VkDescriptorSet, kCorePrintDiffusionSetCount> printDiffusionDescriptorSets{};
    std::array<VkDescriptorSet, kCoreDirSetCount> dirDescriptorSets{};
    std::array<VkDescriptorSet, kCoreScannerPostSetCount> scannerPostDescriptorSets{};
    std::array<VkDescriptorSet, kCoreGrainSetCount> grainDescriptorSets{};
    VkDescriptorPool formatDescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet sourceFormatDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet destinationFormatDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorPool imageFormatDescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet sourceImageFormatDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet autoExposureImageDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet destinationImageFormatDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet sampleImageDescriptorSet = VK_NULL_HANDLE;
    FrameSamples samples;
    VkImageView sourceImageView = VK_NULL_HANDLE;
    VkImageView destinationImageView = VK_NULL_HANDLE;
    bool ownsSourceImageView = false;
    bool ownsDestinationImageView = false;
    VkQueryPool timingPool = VK_NULL_HANDLE;
    VkQueryPool dispatchProfilePool = VK_NULL_HANDLE;
    uint64_t timingMask = 0;
    bool timingPending = false;
    uint32_t timingQueryCount = 0;
    bool timingProfile = false;
    uint32_t grainTimingCount = 0;
    std::array<uint32_t, 14> grainTimingOperations{};
    bool timingAdaptive = false;
    bool timingBounded = false;
    RenderParams previewScheduleParams;
    uint32_t previewScheduleWidth = 0;
    uint32_t previewScheduleHeight = 0;
    uint32_t previewFastFrames = 0;
    bool previewDirectSubmission = false;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool submitted = false;
  };

  struct StaticFilmResources {
    int32_t film = -1;
    int32_t paper = -1;
    RgbToRawMethod rgbToRawMethod = RgbToRawMethod::Hanatos2026;
    bool cameraUvFilterEnabled = false;
    float cameraUvCutNm = 0.0f;
    bool cameraIrFilterEnabled = false;
    float cameraIrCutNm = 0.0f;
    PrintTimingMode processNegativePrintTiming = PrintTimingMode::FilteredEnlarger;
    float processNegativeFilterC = 0.0f;
    float processNegativeFilterMShift = 0.0f;
    float processNegativeFilterYShift = 0.0f;
    float processNegativePreflashMFilterShift = 0.0f;
    float processNegativePreflashYFilterShift = 0.0f;
    bool printScanResources = false;
    bool processNegativeResponsesReady = false;
    bool grainResources = false;
    const ProfileCurveSet *curves = nullptr;
    const ProfileCurveSet *paperCurves = nullptr;
    uint32_t exposureCount = 0;
    uint32_t paperExposureCount = 0;
    uint32_t wavelengthCount = 0;
    uint32_t filmPositive = 0;
    uint32_t hanatosWidth = 0;
    uint32_t hanatosHeight = 0;
    std::array<float, 3> filmDensityMaximum = {0.0f, 0.0f, 0.0f};
    std::array<float, 3> paperDensityMaximum = {0.0f, 0.0f, 0.0f};
    ScratchBuffer logExposure;
    ScratchBuffer densityCurves;
    ScratchBuffer densityCurveLayers;
    ScratchBuffer densityCurveLayerMaxima;
    ScratchBuffer inputMeterXyz;
    ScratchBuffer inputToReferenceXyz;
    ScratchBuffer inputToSrgb;
    ScratchBuffer colorDecodeLuts;
    ScratchBuffer colorTransferKinds;
    ScratchBuffer mallettRawMatrix;
    ScratchBuffer hanatosRawResponse;
    ScratchBuffer paperHanatosResponse;
    ScratchBuffer preflashPaperHanatosResponse;
    ScratchBuffer paperLogExposure;
    ScratchBuffer paperDensityCurves;
    ScratchBuffer filmChannelDensity;
    ScratchBuffer filmBaseTransmittance;
    ScratchBuffer paperLogSensitivity;
    ScratchBuffer thKg3Illuminant;
    ScratchBuffer customEnlargerFilters;
    ScratchBuffer neutralPrintFilters;
    ScratchBuffer paperChannelDensity;
    ScratchBuffer paperBaseTransmittance;
    ScratchBuffer filmScanIlluminant;
    ScratchBuffer paperScanIlluminant;
    ScratchBuffer standardObserverCmfs;
    ScratchBuffer filmScanToOutputRgb;
    ScratchBuffer paperScanToOutputRgb;
    ScratchBuffer colorEncodeLuts;
    ScratchBuffer academyPrinterDensityData;
  };

  RendererDiagnostics diagnostics;
  std::string lastError;
  std::vector<float> outputGamutCompressionData;
  bool available = false;
  bool preferPrivateScratch = true;
  bool bufferAllocationFailed = false;
  bool grainBlurRecurrence = true;
  uint32_t diffusionGroupSize = 2u;
  float diffusionClusterSigmaRatio = 0.10f;
  std::string threadgroupMode = "auto";
  std::string blurBackend = "custom";
  std::string blurDownsample = "auto";
  std::string intermediatePrecision = "float";
  std::string diffusionClusterSigma = "0.10";
  std::string dirTailBackend = "mps";
  bool halationGroupedTail = false;
  bool scannerMps = false;
  std::recursive_mutex renderMutex;
  std::shared_ptr<VulkanSharedBackend> backend;
  ExternalVulkanContext externalContext{};
  bool hasExternalContext = false;
  uint32_t queueIndex = 0;
  uint64_t transientCachedBytes = 0;
  uint64_t lastUseSerial = 0;

  struct ActiveTileContext {
    bool enabled = false;
    uint32_t fullWidth = 0;
    uint32_t fullHeight = 0;
    uint32_t tileOriginX = 0;
    uint32_t tileOriginY = 0;
    uint32_t centerOriginX = 0;
    uint32_t centerOriginY = 0;
    uint32_t centerWidth = 0;
    uint32_t centerHeight = 0;
    bool halationBoostMilestoneEnabled = false;
    bool fullFrameSource = false;
    bool autoExposureReady = false;
  };
  ActiveTileContext activeTileContext;

  struct ActiveExternalImageContext {
    bool enabled = false;
    VkImage sourceImage = VK_NULL_HANDLE;
    VkImage destinationImage = VK_NULL_HANDLE;
    ExternalImageViews views;
  };
  ActiveExternalImageContext activeExternalImageContext;
  ExternalImageViews suppliedImageViews;
  uint64_t externalImageGeneration = 0;
  struct DescriptorBinding {
    bool valid = false;
    VkDescriptorType type = VK_DESCRIPTOR_TYPE_MAX_ENUM;
    uint32_t arrayElement = 0;
    uint32_t count = 0;
    VkDescriptorBufferInfo buffer{};
    VkDescriptorImageInfo image{};
  };
  std::unordered_map<VkDescriptorSet, std::vector<DescriptorBinding>> descriptorContents;
  std::vector<VkDescriptorBufferInfo> descriptorBuffers;
  std::vector<VkWriteDescriptorSet> changedDescriptorWrites;
  bool reuseSetup = true;
  uint32_t requestedDescriptorWrites = 0;
  uint32_t appliedDescriptorWrites = 0;
  VkBuffer diffusionMilestone = VK_NULL_HANDLE;
  bool writingDiffusionMilestone = false;
  VkBuffer previewExposureRange = VK_NULL_HANDLE;

  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
  VkPhysicalDeviceProperties physicalDeviceProperties{};
  VkDevice device = VK_NULL_HANDLE;
  VkQueue computeQueue = VK_NULL_HANDLE;
  uint32_t computeQueueFamily = 0;
  VkCommandPool commandPool = VK_NULL_HANDLE;
  VkShaderModule copyShaderModule = VK_NULL_HANDLE;
  VkShaderModule formatConvertShaderModule = VK_NULL_HANDLE;
  VkShaderModule imageFormatConvertShaderModule = VK_NULL_HANDLE;
  VkDescriptorSetLayout copyDescriptorSetLayout = VK_NULL_HANDLE;
  VkDescriptorSetLayout imageFormatConvertDescriptorSetLayout = VK_NULL_HANDLE;
  VkPipelineLayout copyPipelineLayout = VK_NULL_HANDLE;
  VkPipelineLayout imageFormatConvertPipelineLayout = VK_NULL_HANDLE;
  VkPipeline copyPipeline = VK_NULL_HANDLE;
  VkPipeline formatConvertPipeline = VK_NULL_HANDLE;
  VkPipeline imageFormatConvertPipeline = VK_NULL_HANDLE;
  CopyFrameResources copyFrame;
  struct MeterSlot {
    ScratchBuffer luminance;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet meterSet = VK_NULL_HANDLE;
  };
  std::array<MeterSlot, 2> meterSlots;
  ScratchBuffer meterDecodeLuts;
  ScratchBuffer meterTransferKinds;
  ScratchBuffer meterXyz;
  VkShaderModule filmExposureShaderModule = VK_NULL_HANDLE;
  VkShaderModule autoExposureShaderModule = VK_NULL_HANDLE;
  VkShaderModule curveDevelopShaderModule = VK_NULL_HANDLE;
  VkShaderModule printScanShaderModule = VK_NULL_HANDLE;
  VkShaderModule halationShaderModule = VK_NULL_HANDLE;
  VkShaderModule diffusionShaderModule = VK_NULL_HANDLE;
  VkShaderModule dirShaderModule = VK_NULL_HANDLE;
  VkShaderModule scannerPostShaderModule = VK_NULL_HANDLE;
  VkShaderModule grainShaderModule = VK_NULL_HANDLE;
  VkDescriptorSetLayout coreDescriptorSetLayout = VK_NULL_HANDLE;
  VkPipelineLayout corePipelineLayout = VK_NULL_HANDLE;
  VkPipeline filmExposurePipeline = VK_NULL_HANDLE;
  VkPipeline autoExposurePipeline = VK_NULL_HANDLE;
  VkPipeline curveDevelopPipeline = VK_NULL_HANDLE;
  VkPipeline printScanPipeline = VK_NULL_HANDLE;
  VkPipeline halationPipeline = VK_NULL_HANDLE;
  VkPipeline diffusionPipeline = VK_NULL_HANDLE;
  VkPipeline dirPipeline = VK_NULL_HANDLE;
  VkPipeline scannerPostPipeline = VK_NULL_HANDLE;
  VkPipeline grainPipeline = VK_NULL_HANDLE;
  struct DiffusionCache {
    bool ready = false;
    DiffusionSettings settings;
    float pixelSizeUm = 0.0f;
    float clusterSigmaRatio = 0.0f;
    VulkanDiffusionInfo info{};
    std::vector<VulkanDiffusionComponent> components;

    void prepare(const DiffusionSettings &next, float pixelSize, float clusterRatio) {
      if (ready && settings == next && pixelSizeUm == pixelSize && clusterSigmaRatio == clusterRatio) {
        return;
      }
      info = {};
      components = makeDiffusionComponents(next, pixelSize, info, clusterRatio);
      preview_effects::simplifyDiffusion(components);
      info.componentCount = static_cast<uint32_t>(components.size());
      settings = next;
      pixelSizeUm = pixelSize;
      clusterSigmaRatio = clusterRatio;
      ready = true;
    }
  };
  DiffusionCache cameraDiffusionCache;
  DiffusionCache printDiffusionCache;
  const ProfileCurveSet *dirCachedProfile = nullptr;
  std::array<float, kDirFloatCount> dirCachedFloats{};
  std::vector<float> dirCachedCurves;
  bool dirCachedBaked = false;
  bool previewOptimizations = false;
  bool previewColorLutAvailable = true;
  bool previewDevelopmentAvailable = true;
  bool previewImagesAvailable = true;
  bool previewLutTextureAvailable = true;
  bool previewComposedLutAvailable = true;
  bool previewColorFusionAvailable = true;
  bool previewGrainAvailable = true;
  CoreFrameResources coreFrame;
  StaticFilmResources staticFilm;
  VideoEffectResources video;
  bool videoEffectsRequested = false;
  RenderParams videoEffectParams;
  std::vector<float> hanatosSpectraData;
  static std::mutex transientRegistryMutex;
  static std::vector<Impl *> transientRegistry;
  static uint64_t transientUseClock;

  explicit Impl(const ExternalVulkanContext *external = nullptr) {
    if (external) {
      externalContext = *external;
      hasExternalContext = true;
    }
    preferPrivateScratch = envStringAny(
      {"SPEKTRAFILM_VULKAN_SCRATCH_STORAGE", "SPEKTRAFILM_SCRATCH_STORAGE"},
      "private"
    ) != "shared";
    grainBlurRecurrence = envFlagEnabledOrDefaultAny(
      {"SPEKTRAFILM_VULKAN_GRAIN_BLUR_RECURRENCE", "SPEKTRAFILM_GRAIN_BLUR_RECURRENCE"},
      true
    );
    const std::string diffusionGroupSizeText = envStringAny(
      {"SPEKTRAFILM_VULKAN_DIFFUSION_GROUP_SIZE", "SPEKTRAFILM_DIFFUSION_GROUP_SIZE"},
      "2"
    );
    if (diffusionGroupSizeText == "1") {
      diffusionGroupSize = 1u;
    } else if (diffusionGroupSizeText == "2") {
      diffusionGroupSize = 2u;
    } else {
      diffusionGroupSize = 4u;
    }
    blurBackend = envStringAny(
      {"SPEKTRAFILM_VULKAN_BLUR_BACKEND", "SPEKTRAFILM_BLUR_BACKEND"},
      "custom"
    );
    if (blurBackend != "custom" && blurBackend != "mps" && blurBackend != "auto") {
      blurBackend = "custom";
    }
    blurDownsample = envStringAny(
      {"SPEKTRAFILM_VULKAN_BLUR_DOWNSAMPLE", "SPEKTRAFILM_BLUR_DOWNSAMPLE"},
      "auto"
    );
    if (blurDownsample != "off" && blurDownsample != "2" && blurDownsample != "4" &&
        blurDownsample != "8" && blurDownsample != "auto") {
      blurDownsample = "off";
    }
    intermediatePrecision = envStringAny(
      {"SPEKTRAFILM_VULKAN_INTERMEDIATE_PRECISION", "SPEKTRAFILM_INTERMEDIATE_PRECISION"},
      "float"
    );
    if (intermediatePrecision != "float") {
      intermediatePrecision = "float";
    }
    diffusionClusterSigma = envStringAny(
      {"SPEKTRAFILM_VULKAN_DIFFUSION_CLUSTER_SIGMA", "SPEKTRAFILM_DIFFUSION_CLUSTER_SIGMA"},
      "0.10"
    );
    if (diffusionClusterSigma == "0.05") {
      diffusionClusterSigmaRatio = 0.05f;
    } else if (diffusionClusterSigma == "0.10" || diffusionClusterSigma == "0.1") {
      diffusionClusterSigma = "0.10";
      diffusionClusterSigmaRatio = 0.10f;
    } else {
      diffusionClusterSigma = "off";
      diffusionClusterSigmaRatio = 0.0f;
    }
    dirTailBackend = envStringAny(
      {"SPEKTRAFILM_VULKAN_DIR_TAIL_BACKEND", "SPEKTRAFILM_DIR_TAIL_BACKEND"},
      "mps"
    ) == "mps" ? "mps" : "fused";
    threadgroupMode = envStringAny(
      {"SPEKTRAFILM_VULKAN_THREADGROUP", "SPEKTRAFILM_THREADGROUP"},
      "auto"
    );
    if (threadgroupMode != "auto" && threadgroupMode != "32x8") {
      threadgroupMode = "auto";
    }
    halationGroupedTail = envFlagEnabledOrDefaultAny(
      {"SPEKTRAFILM_VULKAN_HALATION_GROUPED_TAIL", "SPEKTRAFILM_HALATION_GROUPED_TAIL"},
      false
    );
    scannerMps = envFlagEnabledOrDefaultAny(
      {"SPEKTRAFILM_VULKAN_SCANNER_MPS", "SPEKTRAFILM_SCANNER_MPS"},
      false
    );
    registerTransientBudgetEntry();
    initialize();
  }

  ~Impl() {
    unregisterTransientBudgetEntry();
    std::lock_guard<std::recursive_mutex> lock(renderMutex);
    cleanup();
  }

  bool renderCopyValidation(
    const ImageView &source,
    const MutableImageView &destination,
    const RenderWindow &window,
    double time
  );

  bool renderCoreBootstrap(
    const ImageView &source,
    const MutableImageView &destination,
    const RenderWindow &window,
    const RenderParams &params,
    double time
  );
#if defined(__ANDROID__) && !defined(NDEBUG)
  void logDispatchProfile(const std::vector<camera_gpu::ComputeRecorder::DispatchSample> &samples,
                          int32_t width, int32_t height) {
    if (samples.empty() || coreFrame.dispatchProfilePool == VK_NULL_HANDLE) return;
    std::vector<uint64_t> stamps(samples.size() + 1u);
    if (vkGetQueryPoolResults(device, coreFrame.dispatchProfilePool, 0, static_cast<uint32_t>(stamps.size()),
          stamps.size() * sizeof(uint64_t), stamps.data(), sizeof(uint64_t),
          VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) != VK_SUCCESS) return;
    const auto name = [&](VkPipeline pipeline) -> std::string {
      const std::pair<VkPipeline, const char *> known[] = {
        {formatConvertPipeline, "format_convert"}, {imageFormatConvertPipeline, "image_format_convert"},
        {filmExposurePipeline, "film_exposure"}, {autoExposurePipeline, "auto_exposure"},
        {curveDevelopPipeline, "curve_develop"}, {printScanPipeline, "print_scan"},
        {halationPipeline, "halation"}, {diffusionPipeline, "diffusion"}, {dirPipeline, "dir"},
        {scannerPostPipeline, "scanner_post"}, {grainPipeline, "grain"},
        {backend->diffusionRegionsPipeline, "diffusion_regions"}, {backend->directPrintPipeline, "direct_print"},
        {backend->compactPrintPipeline, "compact_print"}, {backend->splitPrintPipelines[0], "split_print_constants"},
        {backend->splitPrintPipelines[1], "split_print"},
      };
      for (const auto &[candidate, label] : known) if (candidate == pipeline && pipeline != VK_NULL_HANDLE) return label;
      static const char *families[] = {"halation_op", "diffusion_op", "print_op", "grain_op"};
      for (size_t family = 0; family < backend->operationPipelines.size(); ++family) {
        for (const VkPipeline candidate : backend->operationPipelines[family]) {
          if (candidate == pipeline && pipeline != VK_NULL_HANDLE) return families[family];
        }
      }
      return "other";
    };
    const double period = physicalDeviceProperties.limits.timestampPeriod / 1.0e6;
    struct Total { double ms = 0.0; uint32_t count = 0; };
    std::map<std::string, Total> totals;
    double all = 0.0;
    for (size_t i = 0; i < samples.size(); ++i) {
      const double ms = static_cast<double>(stamps[i + 1u] - stamps[i]) * period;
      const std::string key = name(samples[i].pipeline) + ":" + std::to_string(samples[i].operation);
      totals[key].ms += ms;
      totals[key].count++;
      all += ms;
    }
    std::vector<std::pair<std::string, Total>> ordered(totals.begin(), totals.end());
    std::sort(ordered.begin(), ordered.end(), [](const auto &a, const auto &b) { return a.second.ms > b.second.ms; });
    __android_log_print(ANDROID_LOG_INFO, "SpektraPhotoProfile", "size=%dx%d dispatches=%zu total_ms=%.2f",
      width, height, samples.size(), all);
    for (const auto &[key, total] : ordered) {
      __android_log_print(ANDROID_LOG_INFO, "SpektraPhotoProfile", "pass=%s count=%u ms=%.2f share=%.1f%%",
        key.c_str(), total.count, total.ms, all > 0.0 ? 100.0 * total.ms / all : 0.0);
    }
  }
#endif
  bool renderExternalImages(
    VkImage sourceImage,
    VkImage destinationImage,
    uint32_t width,
    uint32_t height,
    const RenderParams &params,
    double time
  );
  bool renderTiledExternalImages(
    VkImage sourceImage,
    VkImage destinationImage,
    uint32_t width,
    uint32_t height,
    const RenderParams &params,
    double time
  );
  bool renderSeparateEffects(VkImage sourceImage, VkImage destinationImage,
                            uint32_t width, uint32_t height, const RenderParams &params, double time);
  bool computeHalationBoostMilestone(
    const ImageView &source,
    const RenderWindow &window,
    const RenderParams &params,
    uint32_t centerTileWidth,
    uint32_t centerTileHeight
  );

  bool renderTiledBootstrap(
    const ImageView &source,
    const MutableImageView &destination,
    const RenderWindow &window,
    const RenderParams &params,
    double time
  );

  bool createBuffer(
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags memoryFlags,
    VkMemoryPropertyFlags preferredMemoryFlags,
    VkBuffer &buffer,
    VkDeviceMemory &memory,
    VkDeviceSize *allocatedBytes = nullptr,
    VkMemoryPropertyFlags *actualMemoryFlags = nullptr
  );

  bool ensureScratchBuffer(
    ScratchBuffer &scratch,
    VkDeviceSize size,
    const char *name,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags memoryFlags,
    VkMemoryPropertyFlags preferredMemoryFlags,
    bool persistentMap,
    bool countPrivate
  );
  bool flushMappedScratchBuffer(ScratchBuffer &scratch, VkDeviceSize size, const char *name);
  bool invalidateMappedScratchBuffer(ScratchBuffer &scratch, VkDeviceSize size, const char *name);
  bool ensureSharedScratchBuffer(
    ScratchBuffer &scratch,
    VkDeviceSize size,
    const char *name,
    VkBufferUsageFlags usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
  );
  bool ensureUploadScratchBuffer(
    ScratchBuffer &scratch,
    VkDeviceSize size,
    const char *name,
    VkBufferUsageFlags usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
  );
  bool ensureReadbackScratchBuffer(
    ScratchBuffer &scratch,
    VkDeviceSize size,
    const char *name,
    VkBufferUsageFlags usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
  );
  bool ensurePrivateScratchBuffer(
    ScratchBuffer &scratch,
    VkDeviceSize size,
    const char *name,
    VkBufferUsageFlags usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
  );
  bool ensureScratchBuffer(ScratchBuffer &scratch, VkDeviceSize size, const char *name);
  uint64_t storageBufferCapacity() const;
  bool updateStorageDescriptors(uint32_t count, VkWriteDescriptorSet *writes, bool cache = true);
  bool uploadScratchBuffer(ScratchBuffer &scratch, const void *data, VkDeviceSize size, const char *name, bool cacheUpload = false);
  bool uploadStaticBuffer(ScratchBuffer &scratch, const void *data, VkDeviceSize size, const char *name);
  bool uploadStaticBuffers(const std::vector<StaticBufferUpload> &uploads);
  bool copyBufferImmediate(VkBuffer source, VkBuffer destination, VkDeviceSize size, const char *name);
  bool loadHanatosSpectraData();
  bool loadOutputGamutCompressionData();
  bool prepareStaticFilmResources(const RenderParams &params, bool includePrintScanResources, bool includeGrainResources);
  uint32_t tileOverlap(const RenderParams &params, uint32_t width, uint32_t height) const;
  uint64_t requiredStorageBufferRange(const RenderParams &params, uint32_t width, uint32_t height) const;
  bool fitTileToStorageBufferLimit(const RenderParams &params, uint32_t width, uint32_t height,
                                   uint32_t overlap, uint32_t &tileWidth, uint32_t &tileHeight);
  bool ensureCopyFrameResources();
  bool ensureCoreFrameResources();
  bool ensureCompactPrintDescriptors();
  bool ensureFourStageTexture();
  bool ensureComposedLut(uint32_t size);
  void destroyComposedLut();
  bool ensureVideoEffectResources(uint32_t width, uint32_t height, bool pyramid);
  void destroyVideoEffectResources();
  struct VideoEffectInputs {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    camera_gpu::ComputeRecorder *recorder = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    float filmPixelSizeUm = 1.0f;
    float exposureEv = 0.0f;
    bool composed = false;
    uint32_t composedSize = 0;
    VkDescriptorBufferInfo lut{};
    VkDescriptorBufferInfo exposure{};
    VkDescriptorImageInfo lutTexture{};
    VkDescriptorImageInfo composedTexture{};
  };
  bool recordVideoEffects(const VideoEffectInputs &inputs);
  void destroyFourStageTexture();
  void destroyScratchBuffer(ScratchBuffer &scratch);
  void destroyStaticFilmResources();
  void destroyCopyFrameResources();
  void destroyCoreFrameResources();
  void releaseCoreImageViews();
  void releaseTransientResources();
  void releaseTransientResourcesNoLock();
  VkResult finishSubmittedWork(bool wait);
  void reportFrameSamples();
  void recordFrameSamples(VkCommandBuffer commandBuffer, VkBuffer source, uint32_t stage,
                          uint32_t width, uint32_t height);
  uint64_t sampleSequence = 0;
  void registerTransientBudgetEntry();
  void unregisterTransientBudgetEntry();
  uint64_t transientAllocationBytes() const;
  void refreshTransientBudgetEntry();
  void enforceTransientBudget();
  void updateSharedDiagnostics();
  bool attachBackend();
  bool createCommandPool();
  void clearBackendAliases();
  void markBackendLost();
  void initialize();
  bool selectPhysicalDevice();
  bool createLogicalDevice();
  bool createCopyPipeline();
  bool prepareCorePipelines(uint32_t required);
  void cleanup();
};

std::mutex VulkanRenderer::Impl::transientRegistryMutex;

uint64_t VulkanRenderer::Impl::storageBufferCapacity() const {
  return uint64_t{backend->storageChunkCount()} * backend->storageChunkBytes();
}

bool VulkanRenderer::Impl::updateStorageDescriptors(uint32_t count, VkWriteDescriptorSet *writes, bool cache) {
  const uint32_t chunkCount = backend->storageChunkCount();
  const VkDeviceSize chunkBytes = backend->storageChunkBytes();
  descriptorBuffers.resize(static_cast<size_t>(count) * chunkCount);
  changedDescriptorWrites.clear();
  requestedDescriptorWrites += count;
  const auto fail = [&]() {
    descriptorContents.clear();
    return false;
  };
  for (uint32_t i = 0u; i < count; ++i) {
    auto write = writes[i];
    if (write.dstSet == VK_NULL_HANDLE || write.pNext != nullptr) {
      lastError = "Invalid shared-device Spektra Vulkan render arguments.";
      return fail();
    }
    auto &bindings = descriptorContents[write.dstSet];
    if (bindings.size() <= write.dstBinding) bindings.resize(write.dstBinding + 1u);
    auto &previous = bindings[write.dstBinding];
    DescriptorBinding next{};
    next.valid = true;
    next.type = write.descriptorType;
    next.arrayElement = write.dstArrayElement;
    bool same = previous.valid && previous.type == next.type && previous.arrayElement == next.arrayElement;
    if (write.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) {
      const VkDescriptorBufferInfo original = *write.pBufferInfo;
      if (original.buffer == VK_NULL_HANDLE) {
        lastError = "Missing Vulkan storage resource for write " + std::to_string(i) +
          ", binding " + std::to_string(write.dstBinding) + ".";
        return fail();
      }
      const bool compact = std::find(coreFrame.compactPrintSets.begin(), coreFrame.compactPrintSets.end(),
                                    write.dstSet) != coreFrame.compactPrintSets.end();
      const uint32_t sections = write.dstBinding < 8u && !compact ? chunkCount : 1u;
      if (original.range == 0u || original.range > chunkBytes * sections) {
        lastError = "Vulkan storage buffer binding " + std::to_string(write.dstBinding) +
          " requires " + std::to_string(original.range) + " bytes, exceeding the device's descriptor capacity.";
        return fail();
      }
      next.buffer = original;
      next.count = sections;
      same = same && previous.count == sections && previous.buffer.buffer == original.buffer &&
        previous.buffer.offset == original.offset && previous.buffer.range == original.range;
      if (cache && reuseSetup && same) continue;
      for (uint32_t section = 0u; section < sections; ++section) {
        auto &buffer = descriptorBuffers[static_cast<size_t>(i) * chunkCount + section];
        buffer = original;
        const VkDeviceSize offset = section * chunkBytes;
        if (offset < original.range) {
          buffer.offset += offset;
          buffer.range = std::min(chunkBytes, original.range - offset);
        } else {
          buffer.range = std::min(chunkBytes, original.range);
        }
      }
      write.descriptorCount = sections;
      write.pBufferInfo = &descriptorBuffers[static_cast<size_t>(i) * chunkCount];
    } else if ((write.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE ||
                write.descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) && write.descriptorCount == 1u) {
      next.image = *write.pImageInfo;
      next.count = 1u;
      same = same && previous.count == 1u && previous.image.imageView == next.image.imageView &&
        previous.image.imageLayout == next.image.imageLayout && previous.image.sampler == next.image.sampler;
      if (cache && reuseSetup && same) continue;
    } else {
      lastError = "Invalid shared-device Spektra Vulkan render arguments.";
      return fail();
    }
    previous = next;
    changedDescriptorWrites.push_back(write);
  }
  if (!changedDescriptorWrites.empty()) {
    vkUpdateDescriptorSets(device, static_cast<uint32_t>(changedDescriptorWrites.size()), changedDescriptorWrites.data(), 0, nullptr);
    appliedDescriptorWrites += static_cast<uint32_t>(changedDescriptorWrites.size());
  }
  return true;
}

uint32_t VulkanRenderer::Impl::tileOverlap(const RenderParams &params, uint32_t width, uint32_t height) const {
  const float pixelSizeUm = filmFormatLongEdgeMm(params.filmFormat) * 1000.0f /
    static_cast<float>(std::max(width, height)) / resolvedEnlargerScale(params);
  auto radius = [&](bool print) {
    if (!(print ? params.printDiffusionEnabled : params.cameraDiffusionEnabled)) return 0u;
    VulkanDiffusionInfo info{};
    const auto components = makeDiffusionComponents(diffusionSettings(params, print), pixelSizeUm,
                                                    info, diffusionClusterSigmaRatio);
    return diffusionSpatialRadius(components, blurDownsample, previewOptimizations);
  };
  return estimateVulkanTileOverlap(params, radius(false), radius(true), halationSpatialRadius(params, pixelSizeUm));
}

uint64_t VulkanRenderer::Impl::requiredStorageBufferRange(
  const RenderParams &params, uint32_t width, uint32_t height
) const {
  uint64_t valuesPerPixel = 4u;
  if (params.grainEnabled && params.grainModel != GrainModel::Preview) {
    valuesPerPixel = std::max(valuesPerPixel, uint64_t{9u});
  }
  return uint64_t{width} * height * valuesPerPixel * sizeof(float);
}

bool VulkanRenderer::Impl::fitTileToStorageBufferLimit(
  const RenderParams &params, uint32_t width, uint32_t height,
  uint32_t overlap, uint32_t &tileWidth, uint32_t &tileHeight
) {
  auto workingSize = [overlap](uint32_t center, uint32_t full) {
    return static_cast<uint32_t>(std::min(uint64_t{full}, uint64_t{center} + 2u * uint64_t{overlap}));
  };
  while (requiredStorageBufferRange(params, workingSize(tileWidth, width), workingSize(tileHeight, height)) >
         storageBufferCapacity()) {
    if (tileWidth == 1u && tileHeight == 1u) {
      lastError = "The effect overlap exceeds the device's Vulkan compute or storage buffer limit.";
      return false;
    }
    if (tileWidth >= tileHeight && tileWidth > 1u) tileWidth = (tileWidth + 1u) / 2u;
    else tileHeight = (tileHeight + 1u) / 2u;
  }
  return true;
}
std::vector<VulkanRenderer::Impl *> VulkanRenderer::Impl::transientRegistry;
uint64_t VulkanRenderer::Impl::transientUseClock = 0;

void VulkanRenderer::Impl::registerTransientBudgetEntry() {
  std::lock_guard<std::mutex> lock(transientRegistryMutex);
  if (std::find(transientRegistry.begin(), transientRegistry.end(), this) == transientRegistry.end()) {
    transientRegistry.push_back(this);
  }
}

void VulkanRenderer::Impl::unregisterTransientBudgetEntry() {
  std::lock_guard<std::mutex> lock(transientRegistryMutex);
  transientRegistry.erase(std::remove(transientRegistry.begin(), transientRegistry.end(), this), transientRegistry.end());
}

uint64_t VulkanRenderer::Impl::transientAllocationBytes() const {
  uint64_t bytes = 0;
  auto add = [&](const ScratchBuffer &scratch) {
    bytes += static_cast<uint64_t>(scratch.allocationSize);
  };

  add(copyFrame.source);
  add(copyFrame.destination);

  add(coreFrame.sourceStaging);
  add(coreFrame.source);
  add(coreFrame.samples.readback);
  add(coreFrame.autoExposureSource);
  add(coreFrame.filmRaw);
  add(coreFrame.filmDensity);
  add(coreFrame.destination);
  add(coreFrame.destinationHalf);
  add(coreFrame.destinationStaging);
  add(coreFrame.destinationHalfStaging);
  for (const ScratchBuffer &scratch : coreFrame.phaseScratch) {
    add(scratch);
  }
  add(coreFrame.halationPreviewSource);
  add(coreFrame.halationBoostChunks);
  add(coreFrame.halationBoostInfo);
  add(coreFrame.halationBoostInfoReadback);
  add(coreFrame.tiledHalationBoostInfo);
  add(coreFrame.cameraDiffusionInfo);
  add(coreFrame.cameraDiffusionComponents);
  add(coreFrame.diffusionRegions);
  add(coreFrame.printDiffusionInfo);
  add(coreFrame.printDiffusionComponents);
  add(coreFrame.dirFloats);
  add(coreFrame.dirCorrectedDensityCurves);
  add(coreFrame.frameFloats);
  add(coreFrame.frameInts);
  add(coreFrame.colorLut);
  add(coreFrame.fourStageLut);
  bytes += coreFrame.fourStageTextureBytes;
  bytes += coreFrame.composedLut.bytes;
  return bytes;
}

void VulkanRenderer::Impl::refreshTransientBudgetEntry() {
  const uint64_t bytes = transientAllocationBytes();
  std::lock_guard<std::mutex> lock(transientRegistryMutex);
  transientCachedBytes = bytes;
  lastUseSerial = ++transientUseClock;
}

void VulkanRenderer::Impl::updateSharedDiagnostics() {
  diagnostics.sharedBackend = backend && backend->isAvailable();
  diagnostics.externalBackend = backend && backend->externalDevice;
  diagnostics.sharedBackendGeneration = backend ? backend->generation : 0u;
  diagnostics.sharedQueueCount = backend ? backend->queueCount() : 0u;
  diagnostics.transientCachedBytes = transientCachedBytes;
  diagnostics.transientBudgetBytes = backend ? backend->transientBudgetBytes : 0u;
}

void VulkanRenderer::Impl::enforceTransientBudget() {
  if (!backend || backend->transientBudgetBytes == 0u) {
    return;
  }

  struct Candidate {
    Impl *renderer = nullptr;
    uint64_t bytes = 0;
    uint64_t lastUse = 0;
  };

  const uint64_t budget = backend->transientBudgetBytes;
  uint64_t total = 0;
  std::vector<Candidate> candidates;
  {
    std::lock_guard<std::mutex> lock(transientRegistryMutex);
    for (Impl *renderer : transientRegistry) {
      if (!renderer) {
        continue;
      }
      total += renderer->transientCachedBytes;
      if (renderer != this && renderer->transientCachedBytes != 0u) {
        candidates.push_back({renderer, renderer->transientCachedBytes, renderer->lastUseSerial});
      }
    }
  }

  if (total <= budget || candidates.empty()) {
    return;
  }

  std::sort(candidates.begin(), candidates.end(), [](const Candidate &a, const Candidate &b) {
    return a.lastUse < b.lastUse;
  });

  for (const Candidate &candidate : candidates) {
    if (total <= budget) {
      break;
    }
    std::unique_lock<std::recursive_mutex> lock;
    {
      std::lock_guard<std::mutex> registryLock(transientRegistryMutex);
      if (std::find(transientRegistry.begin(), transientRegistry.end(), candidate.renderer) == transientRegistry.end()) {
        continue;
      }
      lock = std::unique_lock<std::recursive_mutex>(candidate.renderer->renderMutex, std::try_to_lock);
    }
    if (!lock.owns_lock()) {
      continue;
    }
    // A returned preview call can still have GPU work using its scratch buffers.
    if (candidate.renderer->finishSubmittedWork(false) != VK_SUCCESS) {
      continue;
    }
    const uint64_t releasedBytes = candidate.renderer->transientCachedBytes;
    candidate.renderer->releaseTransientResourcesNoLock();
    total = releasedBytes > total ? 0u : total - releasedBytes;
  }
}

bool VulkanRenderer::Impl::createCommandPool() {
  VkCommandPoolCreateInfo poolInfo{};
  poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
  poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  poolInfo.queueFamilyIndex = computeQueueFamily;
  const VkResult result = vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkCreateCommandPool", result);
    return false;
  }
  return true;
}

void VulkanRenderer::Impl::clearBackendAliases() {
  instance = VK_NULL_HANDLE;
  physicalDevice = VK_NULL_HANDLE;
  physicalDeviceProperties = {};
  device = VK_NULL_HANDLE;
  computeQueue = VK_NULL_HANDLE;
  computeQueueFamily = 0;
  copyShaderModule = VK_NULL_HANDLE;
  formatConvertShaderModule = VK_NULL_HANDLE;
  imageFormatConvertShaderModule = VK_NULL_HANDLE;
  copyDescriptorSetLayout = VK_NULL_HANDLE;
  imageFormatConvertDescriptorSetLayout = VK_NULL_HANDLE;
  copyPipelineLayout = VK_NULL_HANDLE;
  imageFormatConvertPipelineLayout = VK_NULL_HANDLE;
  copyPipeline = VK_NULL_HANDLE;
  formatConvertPipeline = VK_NULL_HANDLE;
  imageFormatConvertPipeline = VK_NULL_HANDLE;
  autoExposureShaderModule = VK_NULL_HANDLE;
  filmExposureShaderModule = VK_NULL_HANDLE;
  curveDevelopShaderModule = VK_NULL_HANDLE;
  printScanShaderModule = VK_NULL_HANDLE;
  halationShaderModule = VK_NULL_HANDLE;
  diffusionShaderModule = VK_NULL_HANDLE;
  dirShaderModule = VK_NULL_HANDLE;
  scannerPostShaderModule = VK_NULL_HANDLE;
  grainShaderModule = VK_NULL_HANDLE;
  coreDescriptorSetLayout = VK_NULL_HANDLE;
  corePipelineLayout = VK_NULL_HANDLE;
  autoExposurePipeline = VK_NULL_HANDLE;
  filmExposurePipeline = VK_NULL_HANDLE;
  curveDevelopPipeline = VK_NULL_HANDLE;
  printScanPipeline = VK_NULL_HANDLE;
  halationPipeline = VK_NULL_HANDLE;
  diffusionPipeline = VK_NULL_HANDLE;
  dirPipeline = VK_NULL_HANDLE;
  scannerPostPipeline = VK_NULL_HANDLE;
  grainPipeline = VK_NULL_HANDLE;
  queueIndex = 0u;
}

bool VulkanRenderer::Impl::attachBackend() {
  backend = acquireVulkanSharedBackend(
    hasExternalContext ? &externalContext : nullptr,
    lastError
  );
  if (!backend) {
    return false;
  }

  queueIndex = backend->assignQueueIndex();
  instance = backend->instance;
  physicalDevice = backend->physicalDevice;
  physicalDeviceProperties = backend->physicalDeviceProperties;
  device = backend->device;
  computeQueue = backend->queueForIndex(queueIndex);
  computeQueueFamily = backend->computeQueueFamily;
  copyShaderModule = backend->copyShaderModule;
  formatConvertShaderModule = backend->formatConvertShaderModule;
  imageFormatConvertShaderModule = backend->imageFormatConvertShaderModule;
  copyDescriptorSetLayout = backend->copyDescriptorSetLayout;
  imageFormatConvertDescriptorSetLayout = backend->imageFormatConvertDescriptorSetLayout;
  copyPipelineLayout = backend->copyPipelineLayout;
  imageFormatConvertPipelineLayout = backend->imageFormatConvertPipelineLayout;
  copyPipeline = backend->copyPipeline;
  formatConvertPipeline = backend->formatConvertPipeline;
  imageFormatConvertPipeline = backend->imageFormatConvertPipeline;
  autoExposureShaderModule = backend->autoExposureShaderModule;
  filmExposureShaderModule = backend->filmExposureShaderModule;
  curveDevelopShaderModule = backend->curveDevelopShaderModule;
  printScanShaderModule = backend->printScanShaderModule;
  halationShaderModule = backend->halationShaderModule;
  diffusionShaderModule = backend->diffusionShaderModule;
  dirShaderModule = backend->dirShaderModule;
  scannerPostShaderModule = backend->scannerPostShaderModule;
  grainShaderModule = backend->grainShaderModule;
  coreDescriptorSetLayout = backend->coreDescriptorSetLayout;
  corePipelineLayout = backend->corePipelineLayout;
  return createCommandPool();
}

void VulkanRenderer::Impl::markBackendLost() {
  if (!backend) {
    return;
  }
  available = false;
  backend->markLost();
  clearVulkanSharedBackendIfSame(backend);
}

void VulkanRenderer::Impl::initialize() {
  diagnostics = {};
  lastError.clear();
  available = false;

  if (!attachBackend()) {
    cleanup();
    return;
  }

  refreshTransientBudgetEntry();
  updateSharedDiagnostics();
  available = true;
}

bool VulkanRenderer::Impl::selectPhysicalDevice() {
  return backend && physicalDevice != VK_NULL_HANDLE;
}

bool VulkanRenderer::Impl::createLogicalDevice() {
  if (!backend || device == VK_NULL_HANDLE) {
    lastError = "Shared Vulkan backend is not attached.";
    return false;
  }
  return commandPool != VK_NULL_HANDLE || createCommandPool();
}

bool VulkanRenderer::Impl::createCopyPipeline() {
  if (!backend || copyDescriptorSetLayout == VK_NULL_HANDLE ||
      copyPipelineLayout == VK_NULL_HANDLE ||
      copyPipeline == VK_NULL_HANDLE ||
      formatConvertPipeline == VK_NULL_HANDLE) {
    lastError = "Shared Vulkan copy pipeline is not available.";
    return false;
  }
  return true;
}

bool VulkanRenderer::Impl::prepareCorePipelines(uint32_t required) {
  if (!backend || coreDescriptorSetLayout == VK_NULL_HANDLE ||
      corePipelineLayout == VK_NULL_HANDLE) {
    lastError = "Shared Vulkan core layout is not available.";
    return false;
  }
  const auto prepare = [&](uint32_t flag, const char *name, VkShaderModule module,
      VkPipeline &sharedPipeline, VkPipeline &localPipeline) {
    return (required & flag) == 0u || localPipeline != VK_NULL_HANDLE ||
      backend->prepareCorePipeline(name, module, sharedPipeline, localPipeline);
  };
  if (!prepare(kCoreAutoExposure, "SpektraAutoExposure", autoExposureShaderModule, backend->autoExposurePipeline, autoExposurePipeline) ||
      !prepare(kCoreFilmExposure, "SpektraFilmExposure", filmExposureShaderModule, backend->filmExposurePipeline, filmExposurePipeline) ||
      !prepare(kCoreCurveDevelop, "SpektraCurveDevelop", curveDevelopShaderModule, backend->curveDevelopPipeline, curveDevelopPipeline) ||
      !prepare(kCorePrintScan, "SpektraPrintScan", printScanShaderModule, backend->printScanPipeline, printScanPipeline) ||
      !prepare(kCoreHalation, "SpektraHalation", halationShaderModule, backend->halationPipeline, halationPipeline) ||
      !prepare(kCoreDiffusion, "SpektraDiffusion", diffusionShaderModule, backend->diffusionPipeline, diffusionPipeline) ||
      !prepare(kCoreDir, "SpektraDir", dirShaderModule, backend->dirPipeline, dirPipeline) ||
      !prepare(kCoreScannerPost, "SpektraScannerPost", scannerPostShaderModule, backend->scannerPostPipeline, scannerPostPipeline) ||
      !prepare(kCoreGrain, "SpektraGrain", grainShaderModule, backend->grainPipeline, grainPipeline)) {
    lastError = backend->lastError;
    return false;
  }
  return true;
}

void VulkanRenderer::Impl::cleanup() {
  available = false;
  if (device != VK_NULL_HANDLE) {
    const VkResult result = finishSubmittedWork(true);
    if (result != VK_SUCCESS && result != VK_ERROR_DEVICE_LOST) return;
    destroyCoreFrameResources();
    destroyCopyFrameResources();
    destroyStaticFilmResources();
    for (auto &slot : meterSlots) {
      if (slot.pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, slot.pool, nullptr);
      destroyScratchBuffer(slot.luminance);
      slot = {};
    }
    destroyScratchBuffer(meterDecodeLuts);
    destroyScratchBuffer(meterTransferKinds);
    destroyScratchBuffer(meterXyz);
    if (commandPool != VK_NULL_HANDLE) {
      vkDestroyCommandPool(device, commandPool, nullptr);
      commandPool = VK_NULL_HANDLE;
    }
  }
  refreshTransientBudgetEntry();
  updateSharedDiagnostics();
  clearBackendAliases();
  backend.reset();
}

bool VulkanRenderer::Impl::createBuffer(
  VkDeviceSize size,
  VkBufferUsageFlags usage,
  VkMemoryPropertyFlags memoryFlags,
  VkMemoryPropertyFlags preferredMemoryFlags,
  VkBuffer &buffer,
  VkDeviceMemory &memory,
  VkDeviceSize *allocatedBytes,
  VkMemoryPropertyFlags *actualMemoryFlags
) {
  bufferAllocationFailed = false;
  VkBufferCreateInfo bufferInfo{};
  bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
  bufferInfo.size = size;
  bufferInfo.usage = usage;
  bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

  VkResult result = vkCreateBuffer(device, &bufferInfo, nullptr, &buffer);
  if (result != VK_SUCCESS) {
    bufferAllocationFailed = result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY;
    lastError = vkError("vkCreateBuffer", result);
    return false;
  }

  VkMemoryRequirements requirements{};
  vkGetBufferMemoryRequirements(device, buffer, &requirements);
  const uint32_t memoryTypeIndex = findMemoryTypeIndex(
    physicalDevice,
    requirements.memoryTypeBits,
    memoryFlags,
    preferredMemoryFlags
  );
  if (memoryTypeIndex == std::numeric_limits<uint32_t>::max()) {
    lastError = "Unable to find a compatible Vulkan memory type.";
    return false;
  }

  VkMemoryAllocateInfo allocationInfo{};
  allocationInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  allocationInfo.allocationSize = requirements.size;
  allocationInfo.memoryTypeIndex = memoryTypeIndex;
  result = vkAllocateMemory(device, &allocationInfo, nullptr, &memory);
  if (result != VK_SUCCESS) {
    bufferAllocationFailed = result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY;
    lastError = vkError("vkAllocateMemory", result);
    return false;
  }

  result = vkBindBufferMemory(device, buffer, memory, 0);
  if (result != VK_SUCCESS) {
    bufferAllocationFailed = result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY;
    lastError = vkError("vkBindBufferMemory", result);
    return false;
  }
  if (allocatedBytes) {
    *allocatedBytes = requirements.size;
  }
  if (actualMemoryFlags) {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    *actualMemoryFlags = memoryProperties.memoryTypes[memoryTypeIndex].propertyFlags;
  }
  return true;
}

void VulkanRenderer::Impl::destroyScratchBuffer(ScratchBuffer &scratch) {
  if (scratch.mapped) {
    vkUnmapMemory(device, scratch.memory);
    scratch.mapped = nullptr;
  }
  if (scratch.buffer != VK_NULL_HANDLE) {
    descriptorContents.clear();
    vkDestroyBuffer(device, scratch.buffer, nullptr);
    scratch.buffer = VK_NULL_HANDLE;
  }
  if (scratch.memory != VK_NULL_HANDLE) {
    vkFreeMemory(device, scratch.memory, nullptr);
    scratch.memory = VK_NULL_HANDLE;
  }
  scratch.capacity = 0;
  scratch.initializedBytes = 0;
  scratch.allocationSize = 0;
  scratch.memoryFlags = 0;
  scratch.cachedUpload.clear();
}

void VulkanRenderer::Impl::destroyStaticFilmResources() {
  coreFrame.colorLutReady = false;
  coreFrame.fourStageLutReady = false;
  destroyScratchBuffer(staticFilm.academyPrinterDensityData);
  destroyScratchBuffer(staticFilm.colorEncodeLuts);
  destroyScratchBuffer(staticFilm.paperScanToOutputRgb);
  destroyScratchBuffer(staticFilm.filmScanToOutputRgb);
  destroyScratchBuffer(staticFilm.standardObserverCmfs);
  destroyScratchBuffer(staticFilm.paperScanIlluminant);
  destroyScratchBuffer(staticFilm.filmScanIlluminant);
  destroyScratchBuffer(staticFilm.paperBaseTransmittance);
  destroyScratchBuffer(staticFilm.paperChannelDensity);
  destroyScratchBuffer(staticFilm.neutralPrintFilters);
  destroyScratchBuffer(staticFilm.customEnlargerFilters);
  destroyScratchBuffer(staticFilm.thKg3Illuminant);
  destroyScratchBuffer(staticFilm.paperLogSensitivity);
  destroyScratchBuffer(staticFilm.filmBaseTransmittance);
  destroyScratchBuffer(staticFilm.filmChannelDensity);
  destroyScratchBuffer(staticFilm.paperDensityCurves);
  destroyScratchBuffer(staticFilm.paperLogExposure);
  destroyScratchBuffer(staticFilm.hanatosRawResponse);
  destroyScratchBuffer(staticFilm.paperHanatosResponse);
  destroyScratchBuffer(staticFilm.preflashPaperHanatosResponse);
  destroyScratchBuffer(staticFilm.mallettRawMatrix);
  destroyScratchBuffer(staticFilm.colorTransferKinds);
  destroyScratchBuffer(staticFilm.colorDecodeLuts);
  destroyScratchBuffer(staticFilm.inputToSrgb);
  destroyScratchBuffer(staticFilm.inputToReferenceXyz);
  destroyScratchBuffer(staticFilm.inputMeterXyz);
  destroyScratchBuffer(staticFilm.densityCurveLayerMaxima);
  destroyScratchBuffer(staticFilm.densityCurveLayers);
  destroyScratchBuffer(staticFilm.densityCurves);
  destroyScratchBuffer(staticFilm.logExposure);
  staticFilm.film = -1;
  staticFilm.paper = -1;
  staticFilm.rgbToRawMethod = RgbToRawMethod::Hanatos2026;
  staticFilm.cameraUvFilterEnabled = false;
  staticFilm.cameraUvCutNm = 0.0f;
  staticFilm.cameraIrFilterEnabled = false;
  staticFilm.cameraIrCutNm = 0.0f;
  staticFilm.processNegativePrintTiming = PrintTimingMode::FilteredEnlarger;
  staticFilm.processNegativeFilterC = 0.0f;
  staticFilm.processNegativeFilterMShift = 0.0f;
  staticFilm.processNegativeFilterYShift = 0.0f;
  staticFilm.processNegativePreflashMFilterShift = 0.0f;
  staticFilm.processNegativePreflashYFilterShift = 0.0f;
  staticFilm.printScanResources = false;
  staticFilm.grainResources = false;
  staticFilm.curves = nullptr;
  staticFilm.paperCurves = nullptr;
  staticFilm.exposureCount = 0;
  staticFilm.paperExposureCount = 0;
  staticFilm.wavelengthCount = 0;
  staticFilm.filmPositive = 0;
  staticFilm.hanatosWidth = 0;
  staticFilm.hanatosHeight = 0;
  staticFilm.filmDensityMaximum = {0.0f, 0.0f, 0.0f};
  staticFilm.paperDensityMaximum = {0.0f, 0.0f, 0.0f};
}

bool VulkanRenderer::Impl::ensureScratchBuffer(
  ScratchBuffer &scratch,
  VkDeviceSize size,
  const char *name,
  VkBufferUsageFlags usage,
  VkMemoryPropertyFlags memoryFlags,
  VkMemoryPropertyFlags preferredMemoryFlags,
  bool persistentMap,
  bool countPrivate
) {
  if (scratch.buffer != VK_NULL_HANDLE &&
      scratch.memory != VK_NULL_HANDLE &&
      scratch.capacity >= size &&
      ((scratch.memoryFlags & memoryFlags) == memoryFlags) &&
      (!persistentMap || scratch.mapped)) {
    return true;
  }

#if defined(__ANDROID__) && !defined(NDEBUG)
  if (scratch.capacity > 0 && physicalDeviceProperties.vendorID == 0x13b5u) {
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraBufferResize",
      "renderer=%p backend=%p name=%s old_bytes=%llu new_bytes=%llu pending_gpu=%d",
      static_cast<void *>(this), static_cast<void *>(backend.get()), name,
      static_cast<unsigned long long>(scratch.capacity), static_cast<unsigned long long>(size), coreFrame.submitted);
  }
#endif
  destroyScratchBuffer(scratch);
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkDeviceSize allocatedBytes = 0;
  VkMemoryPropertyFlags actualMemoryFlags = 0;
  if (!createBuffer(size, usage, memoryFlags, preferredMemoryFlags, buffer, memory, &allocatedBytes, &actualMemoryFlags)) {
    if (buffer != VK_NULL_HANDLE) {
      vkDestroyBuffer(device, buffer, nullptr);
    }
    if (memory != VK_NULL_HANDLE) {
      vkFreeMemory(device, memory, nullptr);
    }
    if (lastError.empty()) {
      lastError = std::string("Unable to allocate Vulkan scratch buffer ") + name + ".";
    }
    return false;
  }

  void *mapped = nullptr;
  if (persistentMap) {
    const VkResult mapResult = vkMapMemory(device, memory, 0, allocatedBytes, 0, &mapped);
    if (mapResult != VK_SUCCESS) {
      vkDestroyBuffer(device, buffer, nullptr);
      vkFreeMemory(device, memory, nullptr);
      lastError = vkError("vkMapMemory(scratch)", mapResult);
      return false;
    }
  }

  scratch.buffer = buffer;
  scratch.memory = memory;
  scratch.capacity = size;
  scratch.allocationSize = allocatedBytes;
  scratch.memoryFlags = actualMemoryFlags;
  scratch.mapped = mapped;
  diagnostics.scratchAllocationBytes += static_cast<uint64_t>(allocatedBytes);
  diagnostics.scratchAllocationCount += 1u;
  if (countPrivate) {
    diagnostics.privateScratchAllocationBytes += static_cast<uint64_t>(allocatedBytes);
    diagnostics.privateScratchAllocationCount += 1u;
  } else {
    diagnostics.sharedScratchAllocationBytes += static_cast<uint64_t>(allocatedBytes);
    diagnostics.sharedScratchAllocationCount += 1u;
  }
  return true;
}

bool VulkanRenderer::Impl::flushMappedScratchBuffer(ScratchBuffer &scratch, VkDeviceSize size, const char *name) {
  if ((scratch.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0u || size == 0) {
    return true;
  }
  if (scratch.memory == VK_NULL_HANDLE) {
    lastError = std::string("Unable to flush unmapped Vulkan buffer ") + name + ".";
    return false;
  }

  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = scratch.memory;
  range.offset = 0;
  range.size = VK_WHOLE_SIZE;
  const VkResult result = vkFlushMappedMemoryRanges(device, 1, &range);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkFlushMappedMemoryRanges", result);
    return false;
  }
  return true;
}

bool VulkanRenderer::Impl::invalidateMappedScratchBuffer(ScratchBuffer &scratch, VkDeviceSize size, const char *name) {
  if ((scratch.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0u || size == 0) {
    return true;
  }
  if (scratch.memory == VK_NULL_HANDLE) {
    lastError = std::string("Unable to invalidate unmapped Vulkan buffer ") + name + ".";
    return false;
  }

  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = scratch.memory;
  range.offset = 0;
  range.size = VK_WHOLE_SIZE;
  const VkResult result = vkInvalidateMappedMemoryRanges(device, 1, &range);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkInvalidateMappedMemoryRanges", result);
    return false;
  }
  return true;
}

bool VulkanRenderer::Impl::ensureSharedScratchBuffer(
  ScratchBuffer &scratch,
  VkDeviceSize size,
  const char *name,
  VkBufferUsageFlags usage
) {
  return ensureUploadScratchBuffer(scratch, size, name, usage);
}

bool VulkanRenderer::Impl::ensureUploadScratchBuffer(
  ScratchBuffer &scratch,
  VkDeviceSize size,
  const char *name,
  VkBufferUsageFlags usage
) {
  constexpr VkMemoryPropertyFlags hostMemory = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
  constexpr VkMemoryPropertyFlags preferredHostMemory = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
  return ensureScratchBuffer(scratch, size, name, usage, hostMemory, preferredHostMemory, true, false);
}

bool VulkanRenderer::Impl::ensureReadbackScratchBuffer(
  ScratchBuffer &scratch,
  VkDeviceSize size,
  const char *name,
  VkBufferUsageFlags usage
) {
  constexpr VkMemoryPropertyFlags hostMemory = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
  constexpr VkMemoryPropertyFlags preferredHostMemory = VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
  return ensureScratchBuffer(scratch, size, name, usage, hostMemory, preferredHostMemory, true, false);
}

bool VulkanRenderer::Impl::ensurePrivateScratchBuffer(
  ScratchBuffer &scratch,
  VkDeviceSize size,
  const char *name,
  VkBufferUsageFlags usage
) {
  if (scratch.buffer != VK_NULL_HANDLE &&
      scratch.memory != VK_NULL_HANDLE &&
      scratch.capacity >= size &&
      ((scratch.memoryFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0u)) {
    return true;
  }
  if (preferPrivateScratch &&
      ensureScratchBuffer(scratch, size, name, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false, true)) {
    return true;
  }
  preferPrivateScratch = false;
  lastError.clear();
  return ensureSharedScratchBuffer(scratch, size, name, usage);
}

bool VulkanRenderer::Impl::ensureScratchBuffer(ScratchBuffer &scratch, VkDeviceSize size, const char *name) {
  return ensureSharedScratchBuffer(scratch, size, name);
}

bool VulkanRenderer::Impl::uploadScratchBuffer(ScratchBuffer &scratch, const void *data, VkDeviceSize size, const char *name, bool cacheUpload) {
  if (!data || size == 0) {
    lastError = std::string("Unable to upload empty Vulkan buffer ") + name + ".";
    return false;
  }
  if (!ensureSharedScratchBuffer(scratch, size, name)) {
    return false;
  }

  if (!scratch.mapped) {
    lastError = std::string("Vulkan shared scratch buffer is not mapped for ") + name + ".";
    return false;
  }
  if (cacheUpload && scratch.cachedUpload.size() == size &&
      std::memcmp(scratch.cachedUpload.data(), data, static_cast<size_t>(size)) == 0) {
    return true;
  }
  scratch.cachedUpload.clear();
  std::memcpy(scratch.mapped, data, static_cast<size_t>(size));
  if (!flushMappedScratchBuffer(scratch, size, name)) {
    return false;
  }
  if (cacheUpload) {
    const auto *bytes = static_cast<const uint8_t *>(data);
    scratch.cachedUpload.assign(bytes, bytes + static_cast<size_t>(size));
  }
  diagnostics.uploadBytes += static_cast<uint64_t>(size);
  return true;
}

bool VulkanRenderer::Impl::copyBufferImmediate(VkBuffer source, VkBuffer destination, VkDeviceSize size, const char *name) {
  VkCommandBufferAllocateInfo allocateInfo{};
  allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  allocateInfo.commandPool = commandPool;
  allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  allocateInfo.commandBufferCount = 1;

  VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
  VkResult result = vkAllocateCommandBuffers(device, &allocateInfo, &commandBuffer);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkAllocateCommandBuffers(upload)", result);
    return false;
  }

  VkCommandBufferBeginInfo beginInfo{};
  beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    lastError = vkError("vkBeginCommandBuffer(upload)", result);
    return false;
  }

  VkMemoryBarrier hostInputBarrier{};
  hostInputBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  hostInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
  hostInputBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  vkCmdPipelineBarrier(
    commandBuffer,
    VK_PIPELINE_STAGE_HOST_BIT,
    VK_PIPELINE_STAGE_TRANSFER_BIT,
    0,
    1,
    &hostInputBarrier,
    0,
    nullptr,
    0,
    nullptr
  );

  VkBufferCopy copyRegion{};
  copyRegion.size = size;
  vkCmdCopyBuffer(commandBuffer, source, destination, 1, &copyRegion);

  VkMemoryBarrier uploadBarrier{};
  uploadBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  uploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  uploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  vkCmdPipelineBarrier(
    commandBuffer,
    VK_PIPELINE_STAGE_TRANSFER_BIT,
    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
    0,
    1,
    &uploadBarrier,
    0,
    nullptr,
    0,
    nullptr
  );

  result = vkEndCommandBuffer(commandBuffer);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    lastError = vkError("vkEndCommandBuffer(upload)", result);
    return false;
  }

  VkSubmitInfo submitInfo{};
  submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  submitInfo.commandBufferCount = 1;
  submitInfo.pCommandBuffers = &commandBuffer;

  VkFenceCreateInfo fenceInfo{};
  fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
  VkFence fence = VK_NULL_HANDLE;
  result = vkCreateFence(device, &fenceInfo, nullptr, &fence);
  if (result != VK_SUCCESS) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
    lastError = vkError("vkCreateFence(upload)", result);
    return false;
  }

  result = backend ? backend->submit(queueIndex, submitInfo, fence) : VK_ERROR_INITIALIZATION_FAILED;
  if (result == VK_SUCCESS) {
    result = vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
  }
  vkDestroyFence(device, fence, nullptr);
  vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
  if (result != VK_SUCCESS) {
    lastError = vkError(name, result);
    return false;
  }
  return true;
}

bool VulkanRenderer::Impl::uploadStaticBuffer(ScratchBuffer &scratch, const void *data, VkDeviceSize size, const char *name) {
  if (!data || size == 0) {
    lastError = std::string("Unable to upload empty Vulkan buffer ") + name + ".";
    return false;
  }

  constexpr VkBufferUsageFlags storageUsage =
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  if (!ensurePrivateScratchBuffer(scratch, size, name, storageUsage)) {
    return false;
  }

  VkBuffer stagingBuffer = VK_NULL_HANDLE;
  VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
  VkDeviceSize stagingAllocatedBytes = 0;
  VkMemoryPropertyFlags stagingMemoryFlags = 0;
  constexpr VkMemoryPropertyFlags hostMemory = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
  constexpr VkMemoryPropertyFlags preferredHostMemory = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
  if (!createBuffer(
        size,
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        hostMemory,
        preferredHostMemory,
        stagingBuffer,
        stagingMemory,
        &stagingAllocatedBytes,
        &stagingMemoryFlags
      )) {
    if (stagingBuffer != VK_NULL_HANDLE) {
      vkDestroyBuffer(device, stagingBuffer, nullptr);
    }
    if (stagingMemory != VK_NULL_HANDLE) {
      vkFreeMemory(device, stagingMemory, nullptr);
    }
    return false;
  }

  void *mapped = nullptr;
  VkResult result = vkMapMemory(device, stagingMemory, 0, stagingAllocatedBytes, 0, &mapped);
  if (result != VK_SUCCESS) {
    vkDestroyBuffer(device, stagingBuffer, nullptr);
    vkFreeMemory(device, stagingMemory, nullptr);
    lastError = vkError("vkMapMemory(static staging)", result);
    return false;
  }
  std::memcpy(mapped, data, static_cast<size_t>(size));
  if ((stagingMemoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0u) {
    VkMappedMemoryRange range{};
    range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
    range.memory = stagingMemory;
    range.offset = 0;
    range.size = VK_WHOLE_SIZE;
    result = vkFlushMappedMemoryRanges(device, 1, &range);
    if (result != VK_SUCCESS) {
      vkUnmapMemory(device, stagingMemory);
      vkDestroyBuffer(device, stagingBuffer, nullptr);
      vkFreeMemory(device, stagingMemory, nullptr);
      lastError = vkError("vkFlushMappedMemoryRanges(static staging)", result);
      return false;
    }
  }
  vkUnmapMemory(device, stagingMemory);

  const bool ok = copyBufferImmediate(stagingBuffer, scratch.buffer, size, "vkQueueSubmit(static upload)");
  vkDestroyBuffer(device, stagingBuffer, nullptr);
  vkFreeMemory(device, stagingMemory, nullptr);
  if (!ok) {
    return false;
  }

  diagnostics.uploadBytes += static_cast<uint64_t>(size);
  return true;
}

bool VulkanRenderer::Impl::uploadStaticBuffers(
  const std::vector<StaticBufferUpload> &uploads
) {
  if (uploads.empty()) {
    return true;
  }

#if defined(__ANDROID__)
  for (const StaticBufferUpload &upload : uploads) {
    if (!upload.scratch || !upload.data || upload.size == 0 || !upload.name) {
      lastError = "Unable to batch an empty Vulkan static buffer upload.";
      return false;
    }
    if (!ensureUploadScratchBuffer(
          *upload.scratch,
          upload.size,
          upload.name,
          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        )) {
      return false;
    }
    if (upload.scratch->initializedBytes == upload.size &&
        std::memcmp(upload.scratch->mapped, upload.data, static_cast<size_t>(upload.size)) == 0) {
      continue;
    }
    std::memcpy(upload.scratch->mapped, upload.data, static_cast<size_t>(upload.size));
    if (!flushMappedScratchBuffer(*upload.scratch, upload.size, upload.name)) {
      return false;
    }
    upload.scratch->initializedBytes = upload.size;
    diagnostics.uploadBytes += static_cast<uint64_t>(upload.size);
  }
  return true;
#else

  constexpr VkBufferUsageFlags storageUsage =
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  VkDeviceSize totalSize = 0;
  std::vector<VkDeviceSize> offsets;
  offsets.reserve(uploads.size());
  for (const StaticBufferUpload &upload : uploads) {
    if (!upload.scratch || !upload.data || upload.size == 0 || !upload.name) {
      lastError = "Unable to batch an empty Vulkan static buffer upload.";
      return false;
    }
    totalSize = (totalSize + 3u) & ~VkDeviceSize{3u};
    offsets.push_back(totalSize);
    totalSize += upload.size;
    if (!ensurePrivateScratchBuffer(*upload.scratch, upload.size, upload.name, storageUsage)) {
      return false;
    }
  }

  VkBuffer stagingBuffer = VK_NULL_HANDLE;
  VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
  VkDeviceSize stagingAllocatedBytes = 0;
  VkMemoryPropertyFlags stagingMemoryFlags = 0;
  constexpr VkMemoryPropertyFlags hostMemory = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
  constexpr VkMemoryPropertyFlags preferredHostMemory = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
  if (!createBuffer(
        totalSize,
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        hostMemory,
        preferredHostMemory,
        stagingBuffer,
        stagingMemory,
        &stagingAllocatedBytes,
        &stagingMemoryFlags
      )) {
    if (stagingBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, stagingBuffer, nullptr);
    if (stagingMemory != VK_NULL_HANDLE) vkFreeMemory(device, stagingMemory, nullptr);
    return false;
  }

  void *mapped = nullptr;
  VkResult result = vkMapMemory(device, stagingMemory, 0, stagingAllocatedBytes, 0, &mapped);
  if (result != VK_SUCCESS) {
    vkDestroyBuffer(device, stagingBuffer, nullptr);
    vkFreeMemory(device, stagingMemory, nullptr);
    lastError = vkError("vkMapMemory(static batch staging)", result);
    return false;
  }
  for (size_t index = 0; index < uploads.size(); ++index) {
    std::memcpy(
      static_cast<std::byte *>(mapped) + offsets[index],
      uploads[index].data,
      static_cast<size_t>(uploads[index].size)
    );
  }
  if ((stagingMemoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0u) {
    VkMappedMemoryRange range{};
    range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
    range.memory = stagingMemory;
    range.offset = 0;
    range.size = VK_WHOLE_SIZE;
    result = vkFlushMappedMemoryRanges(device, 1, &range);
  }
  vkUnmapMemory(device, stagingMemory);
  if (result != VK_SUCCESS) {
    vkDestroyBuffer(device, stagingBuffer, nullptr);
    vkFreeMemory(device, stagingMemory, nullptr);
    lastError = vkError("vkFlushMappedMemoryRanges(static batch staging)", result);
    return false;
  }

  VkCommandBufferAllocateInfo allocateInfo{};
  allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  allocateInfo.commandPool = commandPool;
  allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  allocateInfo.commandBufferCount = 1;
  VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
  result = vkAllocateCommandBuffers(device, &allocateInfo, &commandBuffer);
  if (result == VK_SUCCESS) {
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
  }
  if (result == VK_SUCCESS) {
    VkMemoryBarrier hostBarrier{};
    hostBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    hostBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    hostBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      0, 1, &hostBarrier, 0, nullptr, 0, nullptr
    );
    for (size_t index = 0; index < uploads.size(); ++index) {
      VkBufferCopy copy{};
      copy.srcOffset = offsets[index];
      copy.size = uploads[index].size;
      vkCmdCopyBuffer(
        commandBuffer,
        stagingBuffer,
        uploads[index].scratch->buffer,
        1,
        &copy
      );
    }
    VkMemoryBarrier shaderBarrier{};
    shaderBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    shaderBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    shaderBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 1, &shaderBarrier, 0, nullptr, 0, nullptr
    );
    result = vkEndCommandBuffer(commandBuffer);
  }

  VkFence fence = VK_NULL_HANDLE;
  if (result == VK_SUCCESS) {
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    result = vkCreateFence(device, &fenceInfo, nullptr, &fence);
  }
  if (result == VK_SUCCESS) {
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    result = backend ? backend->submit(queueIndex, submitInfo, fence) : VK_ERROR_INITIALIZATION_FAILED;
  }
  if (result == VK_SUCCESS) {
    result = vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
  }

  if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
  if (commandBuffer != VK_NULL_HANDLE) {
    vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
  }
  vkDestroyBuffer(device, stagingBuffer, nullptr);
  vkFreeMemory(device, stagingMemory, nullptr);
  if (result != VK_SUCCESS) {
    lastError = vkError("Vulkan static upload batch", result);
    return false;
  }
  for (const StaticBufferUpload &upload : uploads) {
    diagnostics.uploadBytes += static_cast<uint64_t>(upload.size);
  }
  return true;
#endif
}

bool VulkanRenderer::Impl::loadHanatosSpectraData() {
  const HanatosSpectraLutInfo &hanatos = hanatosSpectraLutInfo();
  if (hanatos.elementCount == 0u || hanatos.width == 0u || hanatos.height == 0u || hanatos.wavelengthCount == 0u) {
    lastError = "Generated Hanatos spectra LUT metadata is unavailable.";
    return false;
  }
  if (hanatosSpectraData.size() == hanatos.elementCount) {
    return true;
  }

#if defined(__ANDROID__)
  std::string resourceError;
  std::vector<float> data = embeddedFloatVector(
    SpektraHanatos2025Spectra_f32,
    SpektraHanatos2025Spectra_f32_size,
    hanatos.elementCount,
    resourceError);
  if (data.empty()) {
    lastError = resourceError;
    return false;
  }
  hanatosSpectraData = std::move(data);
  return true;
#else
  const std::filesystem::path lutPath = findResourcePath("SpektraHanatos2025Spectra.f32");
  if (lutPath.empty()) {
    lastError = "Unable to locate SpektraHanatos2025Spectra.f32 for Vulkan Hanatos RGB-to-raw.";
    return false;
  }

  std::string resourceError;
  std::vector<float> data = readFloatResourceFile(lutPath, hanatos.elementCount, resourceError);
  if (data.empty()) {
    lastError = resourceError;
    return false;
  }
  hanatosSpectraData = std::move(data);
  return true;
#endif
}

bool VulkanRenderer::Impl::loadOutputGamutCompressionData() {
  if (outputGamutCompressionData.size() == kSpektraOutputGamutCompressionElementCount) {
    return true;
  }

#if defined(__ANDROID__)
  std::string resourceError;
  std::vector<float> data = embeddedFloatVector(
    SpektraOutputGamutCompression_f32,
    SpektraOutputGamutCompression_f32_size,
    kSpektraOutputGamutCompressionElementCount,
    resourceError);
  if (data.empty()) {
    lastError = resourceError;
    return false;
  }
  outputGamutCompressionData = std::move(data);
  return true;
#else
  const std::filesystem::path dataPath = findResourcePath("SpektraOutputGamutCompression.f32");
  if (dataPath.empty()) {
    lastError = "Unable to locate SpektraOutputGamutCompression.f32 for Vulkan output gamut compression.";
    return false;
  }

  std::string resourceError;
  std::vector<float> data = readFloatResourceFile(
    dataPath,
    kSpektraOutputGamutCompressionElementCount,
    resourceError
  );
  if (data.empty()) {
    lastError = resourceError;
    return false;
  }
  outputGamutCompressionData = std::move(data);
  return true;
#endif
}

bool VulkanRenderer::Impl::prepareStaticFilmResources(
  const RenderParams &params,
  bool includePrintScanResources,
  bool includeGrainResources
) {
  const bool processNegativeResponseCached =
    params.process != ProcessMode::ProcessNegative ||
    (staticFilm.processNegativeResponsesReady &&
     staticFilm.processNegativePrintTiming == params.printTiming &&
     staticFilm.processNegativeFilterC == params.filterC &&
     staticFilm.processNegativeFilterMShift == params.filterMShift &&
     staticFilm.processNegativeFilterYShift == params.filterYShift &&
     staticFilm.processNegativePreflashMFilterShift == params.preflashMFilterShift &&
     staticFilm.processNegativePreflashYFilterShift == params.preflashYFilterShift);
  const bool coreResourcesCached =
    staticFilm.film == params.film &&
    staticFilm.rgbToRawMethod == params.rgbToRawMethod &&
    staticFilm.cameraUvFilterEnabled == params.cameraUvFilterEnabled &&
    staticFilm.cameraUvCutNm == params.cameraUvCutNm &&
    staticFilm.cameraIrFilterEnabled == params.cameraIrFilterEnabled &&
    staticFilm.cameraIrCutNm == params.cameraIrCutNm &&
    processNegativeResponseCached &&
    staticFilm.curves &&
    staticFilm.exposureCount > 0u &&
    staticFilm.wavelengthCount > 0u &&
    staticFilm.logExposure.buffer != VK_NULL_HANDLE &&
    staticFilm.densityCurves.buffer != VK_NULL_HANDLE &&
    staticFilm.inputMeterXyz.buffer != VK_NULL_HANDLE &&
    staticFilm.inputToReferenceXyz.buffer != VK_NULL_HANDLE &&
    staticFilm.inputToSrgb.buffer != VK_NULL_HANDLE &&
    staticFilm.colorDecodeLuts.buffer != VK_NULL_HANDLE &&
    staticFilm.colorTransferKinds.buffer != VK_NULL_HANDLE &&
    staticFilm.mallettRawMatrix.buffer != VK_NULL_HANDLE &&
    staticFilm.hanatosRawResponse.buffer != VK_NULL_HANDLE &&
    staticFilm.paperHanatosResponse.buffer != VK_NULL_HANDLE &&
    staticFilm.preflashPaperHanatosResponse.buffer != VK_NULL_HANDLE;
  const bool printScanResourcesCached =
    !includePrintScanResources ||
    (staticFilm.printScanResources &&
     staticFilm.paper == params.paper &&
     staticFilm.paperCurves &&
     staticFilm.paperExposureCount > 0u &&
     staticFilm.paperLogExposure.buffer != VK_NULL_HANDLE &&
     staticFilm.paperDensityCurves.buffer != VK_NULL_HANDLE &&
     staticFilm.filmChannelDensity.buffer != VK_NULL_HANDLE &&
     staticFilm.filmBaseTransmittance.buffer != VK_NULL_HANDLE &&
     staticFilm.paperLogSensitivity.buffer != VK_NULL_HANDLE &&
     staticFilm.thKg3Illuminant.buffer != VK_NULL_HANDLE &&
     staticFilm.customEnlargerFilters.buffer != VK_NULL_HANDLE &&
     staticFilm.neutralPrintFilters.buffer != VK_NULL_HANDLE &&
     staticFilm.paperChannelDensity.buffer != VK_NULL_HANDLE &&
     staticFilm.paperBaseTransmittance.buffer != VK_NULL_HANDLE &&
     staticFilm.filmScanIlluminant.buffer != VK_NULL_HANDLE &&
     staticFilm.paperScanIlluminant.buffer != VK_NULL_HANDLE &&
     staticFilm.standardObserverCmfs.buffer != VK_NULL_HANDLE &&
     staticFilm.filmScanToOutputRgb.buffer != VK_NULL_HANDLE &&
     staticFilm.paperScanToOutputRgb.buffer != VK_NULL_HANDLE &&
     staticFilm.colorEncodeLuts.buffer != VK_NULL_HANDLE &&
     staticFilm.academyPrinterDensityData.buffer != VK_NULL_HANDLE);
  const bool grainResourcesCached =
    !includeGrainResources ||
    (staticFilm.grainResources &&
     staticFilm.densityCurveLayers.buffer != VK_NULL_HANDLE &&
     staticFilm.densityCurveLayerMaxima.buffer != VK_NULL_HANDLE);
  if (coreResourcesCached && printScanResourcesCached && grainResourcesCached) {
    return true;
  }
  coreFrame.colorLutReady = false;
  coreFrame.fourStageLutReady = false;

  const ProfileCurveSet *curves = filmProfileCurves(params.film);
  if (!curves) {
    curves = filmProfileCurves(static_cast<int32_t>(kSpektraDefaultFilmIndex));
  }
  const ProfileCurveSet *paperCurves = nullptr;
  if (includePrintScanResources) {
    paperCurves = paperProfileCurves(params.paper);
    if (!paperCurves) {
      paperCurves = paperProfileCurves(static_cast<int32_t>(kSpektraDefaultPaperIndex));
    }
  }
  if (!curves || curves->exposureCount == 0u || !curves->logExposure || !curves->densityCurves) {
    lastError = "Unable to locate generated film density curves.";
    return false;
  }
  if (includeGrainResources && (!curves->densityCurveLayers || !curves->densityCurveLayerMaxima)) {
    lastError = "Unable to locate generated film grain layer data for the Vulkan grain pass.";
    return false;
  }
  if (includePrintScanResources &&
      (!paperCurves || paperCurves->exposureCount == 0u || !paperCurves->logExposure || !paperCurves->densityCurves)) {
    lastError = "Unable to locate generated paper density curves.";
    return false;
  }
  if (!inputMeterXyzMatrices() || !curves->inputToReferenceXyz || !curves->inputToSrgb ||
      !colorDecodeLuts() || !colorTransferKinds()) {
    lastError = "Unable to locate generated Vulkan input color transform data.";
    return false;
  }
  if (includePrintScanResources && (!colorEncodeLuts() || !colorTransferParams())) {
    lastError = "Unable to locate generated Vulkan output color transform data.";
    return false;
  }
  if (curves->wavelengthCount == 0u || !curves->logSensitivity ||
      !curves->mallettBasisIlluminant || curves->mallettRawMidgrayGreen <= 0.0f) {
    lastError = "Unable to locate generated Vulkan Mallett raw matrix data.";
    return false;
  }
  if (includePrintScanResources &&
      (paperCurves->wavelengthCount != curves->wavelengthCount || !paperCurves->logSensitivity)) {
    lastError = "Unable to locate generated Vulkan paper spectral sensitivity data.";
    return false;
  }
  if (includePrintScanResources &&
      (!curves->channelDensity || !curves->baseDensity || !curves->densityCurveMinimum ||
       !paperCurves->channelDensity || !paperCurves->baseDensity)) {
    lastError = "Unable to locate generated Vulkan spectral density data.";
    return false;
  }
  if (includePrintScanResources &&
      (!curves->scanIlluminant || !paperCurves->scanIlluminant ||
       !curves->scanToOutputRgb || !paperCurves->scanToOutputRgb || !standardObserverCmfs())) {
    lastError = "Unable to locate generated Vulkan scan conversion data.";
    return false;
  }
  if (includePrintScanResources &&
      (!thKg3Illuminant() || !customEnlargerFilters() || !neutralPrintFilters() || !academyPrinterDensityData())) {
    lastError = "Unable to locate generated Vulkan print exposure data.";
    return false;
  }
  if (params.rgbToRawMethod == RgbToRawMethod::Hanatos2025 && !curves->bandpassHanatos2025) {
    lastError = "Unable to locate archived Hanatos 2025 film bandpass data.";
    return false;
  }
  if (params.rgbToRawMethod == RgbToRawMethod::Hanatos2026 &&
      (!curves->hanatos2026WindowParams || !curves->referenceIlluminantSpectrum || !curves->wavelengths)) {
    lastError = "Unable to locate generated Hanatos 2026 film adaptation data.";
    return false;
  }
  const bool needsHanatos = params.rgbToRawMethod != RgbToRawMethod::Mallett2019;
  if (needsHanatos && !loadHanatosSpectraData()) {
    return false;
  }
  if (includePrintScanResources && !loadOutputGamutCompressionData()) {
    return false;
  }

  const std::vector<float> packedFilmCurveExposure =
    makePackedCurveExposure(curves->logExposure, curves->exposureCount);
  const VkDeviceSize logExposureBytes = static_cast<VkDeviceSize>(packedFilmCurveExposure.size()) * sizeof(float);
  const VkDeviceSize densityCurveBytes = static_cast<VkDeviceSize>(curves->exposureCount) * 3u * sizeof(float);
  const VkDeviceSize densityCurveLayerBytes = static_cast<VkDeviceSize>(curves->exposureCount) * 9u * sizeof(float);
  const VkDeviceSize densityCurveLayerMaximaBytes = 9u * sizeof(float);
  const VkDeviceSize inputMatrixBytes = static_cast<VkDeviceSize>(kSpektraColorSpaceCount) * 9u * sizeof(float);
  const VkDeviceSize transferLutBytes =
    static_cast<VkDeviceSize>(kSpektraColorSpaceCount) *
    static_cast<VkDeviceSize>(kSpektraColorTransferLutSize) *
    sizeof(float);
  std::vector<float> colorEncodeAndGamutData;
  if (includePrintScanResources) {
    colorEncodeAndGamutData.reserve(
      static_cast<size_t>(kSpektraColorSpaceCount) * static_cast<size_t>(kSpektraColorTransferLutSize) +
      outputGamutCompressionData.size() +
      static_cast<size_t>(kSpektraColorSpaceCount)
    );
    colorEncodeAndGamutData.insert(
      colorEncodeAndGamutData.end(),
      colorEncodeLuts(),
      colorEncodeLuts() + static_cast<size_t>(kSpektraColorSpaceCount) * static_cast<size_t>(kSpektraColorTransferLutSize)
    );
    colorEncodeAndGamutData.insert(
      colorEncodeAndGamutData.end(),
      outputGamutCompressionData.begin(),
      outputGamutCompressionData.end()
    );
    colorEncodeAndGamutData.insert(
      colorEncodeAndGamutData.end(),
      colorTransferParams(),
      colorTransferParams() + static_cast<size_t>(kSpektraColorSpaceCount)
    );
  }
  const VkDeviceSize transferKindBytes = static_cast<VkDeviceSize>(kSpektraColorSpaceCount) * sizeof(uint32_t);
  const VkDeviceSize mallettRawMatrixBytes = 9u * sizeof(float);
  // Print shaders address small tables directly through the first descriptor.
  for (const auto &[binding, bytes] : std::array<std::pair<uint32_t, VkDeviceSize>, 5>{{
         {2u, logExposureBytes}, {3u, densityCurveBytes}, {5u, transferLutBytes},
         {6u, transferKindBytes}, {7u, mallettRawMatrixBytes}}}) {
    if (bytes > backend->storageChunkBytes()) {
      lastError = "Vulkan storage buffer binding " + std::to_string(binding) +
        " requires " + std::to_string(bytes) + " bytes, exceeding the device's descriptor capacity.";
      return false;
    }
  }
  const std::vector<float> baseFilmSensitivityLinear = makeLinearSensitivity(curves->logSensitivity, curves->wavelengthCount);
  const std::vector<float> filmSensitivityLinear = applyCameraBandPass(*curves, baseFilmSensitivityLinear, params);
  const std::array<float, 9> mallettRawMatrix = makeMallettRawMatrix(*curves, filmSensitivityLinear);
  const HanatosSpectraLutInfo &hanatos = hanatosSpectraLutInfo();
  std::vector<float> hanatosRawResponse = needsHanatos && !coreResourcesCached
    ? makeHanatosRawResponsePair(*curves, filmSensitivityLinear, hanatosSpectraData, hanatos, params.rgbToRawMethod)
    : std::vector<float>(8u, 0.0f);
  if (hanatosRawResponse.empty()) {
    hanatosRawResponse.assign(8u, 0.0f);
  }
  std::vector<float> paperHanatosResponse(8u, 0.0f);
  std::vector<float> preflashPaperHanatosResponse(8u, 0.0f);
  const bool needsProcessNegativeResponses = includePrintScanResources &&
    params.process == ProcessMode::ProcessNegative;
  if (needsProcessNegativeResponses) {
    const std::vector<float> paperSensitivityLinear = makeLinearSensitivity(paperCurves->logSensitivity, paperCurves->wavelengthCount);
    const std::vector<float> processNegativePaperWeights = makeProcessNegativePaperWeights(
      *paperCurves,
      paperSensitivityLinear,
      params,
      false
    );
    const std::vector<float> processNegativePreflashWeights = makeProcessNegativePaperWeights(
      *paperCurves,
      paperSensitivityLinear,
      params,
      true
    );
    paperHanatosResponse = makeHanatosResponsePairFromWeights(*curves, processNegativePaperWeights, hanatosSpectraData, hanatos);
    preflashPaperHanatosResponse = makeHanatosResponsePairFromWeights(*curves, processNegativePreflashWeights, hanatosSpectraData, hanatos);
  }
  const VkDeviceSize hanatosRawResponseBytes =
    static_cast<VkDeviceSize>(hanatosRawResponse.size()) * sizeof(float);
  const VkDeviceSize paperHanatosResponseBytes =
    static_cast<VkDeviceSize>(paperHanatosResponse.size()) * sizeof(float);
  const VkDeviceSize preflashPaperHanatosResponseBytes =
    static_cast<VkDeviceSize>(preflashPaperHanatosResponse.size()) * sizeof(float);
  const uint64_t scratchBytesBefore = diagnostics.scratchAllocationBytes;
  const uint64_t scratchCountBefore = diagnostics.scratchAllocationCount;
  const uint64_t sharedBytesBefore = diagnostics.sharedScratchAllocationBytes;
  const uint64_t sharedCountBefore = diagnostics.sharedScratchAllocationCount;
  const uint64_t privateBytesBefore = diagnostics.privateScratchAllocationBytes;
  const uint64_t privateCountBefore = diagnostics.privateScratchAllocationCount;

  if (!uploadStaticBuffers({
        {&staticFilm.logExposure, packedFilmCurveExposure.data(), logExposureBytes, "packed film curve exposure"},
        {&staticFilm.densityCurves, curves->densityCurves, densityCurveBytes, "film density curves"},
        {&staticFilm.inputMeterXyz, inputMeterXyzMatrices(), inputMatrixBytes, "input meter XYZ matrices"},
        {&staticFilm.inputToReferenceXyz, curves->inputToReferenceXyz, inputMatrixBytes, "input to reference XYZ matrices"},
        {&staticFilm.inputToSrgb, curves->inputToSrgb, inputMatrixBytes, "input to sRGB matrices"},
        {&staticFilm.colorDecodeLuts, colorDecodeLuts(), transferLutBytes, "color decode LUTs"},
        {&staticFilm.colorTransferKinds, colorTransferKinds(), transferKindBytes, "color transfer kinds"},
        {&staticFilm.mallettRawMatrix, mallettRawMatrix.data(), mallettRawMatrixBytes, "Mallett raw matrix"},
        {&staticFilm.paperHanatosResponse, paperHanatosResponse.data(), paperHanatosResponseBytes, "paper Hanatos response"},
        {&staticFilm.preflashPaperHanatosResponse, preflashPaperHanatosResponse.data(), preflashPaperHanatosResponseBytes, "preflash paper Hanatos response"},
      })) {
    return false;
  }
  if (!coreResourcesCached && !uploadStaticBuffers({
        {&staticFilm.hanatosRawResponse, hanatosRawResponse.data(), hanatosRawResponseBytes, "Hanatos raw response"},
      })) {
    return false;
  }
  if (includeGrainResources &&
      !uploadStaticBuffers({
        {&staticFilm.densityCurveLayers, curves->densityCurveLayers, densityCurveLayerBytes, "film density curve layers"},
        {&staticFilm.densityCurveLayerMaxima, curves->densityCurveLayerMaxima, densityCurveLayerMaximaBytes, "film density curve layer maxima"},
      })) {
    return false;
  }
  if (includePrintScanResources) {
    const std::vector<float> packedPaperCurveExposure =
      makePackedCurveExposure(paperCurves->logExposure, paperCurves->exposureCount);
    const VkDeviceSize paperLogExposureBytes =
      static_cast<VkDeviceSize>(packedPaperCurveExposure.size()) * sizeof(float);
    const VkDeviceSize paperDensityCurveBytes = static_cast<VkDeviceSize>(paperCurves->exposureCount) * 3u * sizeof(float);
    const VkDeviceSize wavelengthBytes = static_cast<VkDeviceSize>(curves->wavelengthCount) * sizeof(float);
    const VkDeviceSize spectralTripletBytes = static_cast<VkDeviceSize>(curves->wavelengthCount) * 3u * sizeof(float);
    const VkDeviceSize neutralPrintFilterBytes =
      static_cast<VkDeviceSize>(kSpektraPaperCount) * static_cast<VkDeviceSize>(kSpektraFilmCount) * 3u * sizeof(float);
    const VkDeviceSize academyPrinterDensityBytes =
      (static_cast<VkDeviceSize>(curves->wavelengthCount) * 3u +
       static_cast<VkDeviceSize>(kSpektraPaperCount) * static_cast<VkDeviceSize>(kSpektraFilmCount) * 3u) *
      sizeof(float);
    const std::vector<float> paperSensitivityLinear =
      makeLinearSensitivity(paperCurves->logSensitivity, paperCurves->wavelengthCount);
    const std::vector<float> packedFilmSpectralDensity =
      makePackedSpectralDensity(curves->channelDensity, curves->baseDensity, curves->wavelengthCount);
    const std::vector<float> packedPaperSpectralDensity =
      makePackedSpectralDensity(paperCurves->channelDensity, paperCurves->baseDensity, paperCurves->wavelengthCount);
    const std::vector<float> filmBaseTransmittance = makeBaseTransmittance(curves->baseDensity, curves->wavelengthCount);
    const std::vector<float> paperBaseTransmittance = makeBaseTransmittance(paperCurves->baseDensity, paperCurves->wavelengthCount);
    const std::vector<float> scanProducts = makeScanProducts(
      curves->scanIlluminant,
      paperCurves->scanIlluminant,
      curves->baseDensity,
      paperCurves->baseDensity,
      standardObserverCmfs(),
      curves->wavelengthCount
    );
    if (!uploadStaticBuffers({
          {&staticFilm.paperLogExposure, packedPaperCurveExposure.data(), paperLogExposureBytes, "packed paper curve exposure"},
          {&staticFilm.paperDensityCurves, paperCurves->densityCurves, paperDensityCurveBytes, "paper density curves"},
          {&staticFilm.filmChannelDensity, packedFilmSpectralDensity.data(), packedFilmSpectralDensity.size() * sizeof(float), "packed film spectral density"},
          {&staticFilm.filmBaseTransmittance, filmBaseTransmittance.data(), wavelengthBytes, "film base transmittance"},
          {&staticFilm.paperLogSensitivity, paperSensitivityLinear.data(), spectralTripletBytes, "paper linear sensitivity"},
          {&staticFilm.thKg3Illuminant, thKg3Illuminant(), wavelengthBytes, "TH-KG3 illuminant"},
          {&staticFilm.customEnlargerFilters, customEnlargerFilters(), spectralTripletBytes, "custom enlarger filters"},
          {&staticFilm.neutralPrintFilters, neutralPrintFilters(), neutralPrintFilterBytes, "neutral print filters"},
          {&staticFilm.paperChannelDensity, packedPaperSpectralDensity.data(), packedPaperSpectralDensity.size() * sizeof(float), "packed paper spectral density"},
          {&staticFilm.paperBaseTransmittance, paperBaseTransmittance.data(), wavelengthBytes, "paper base transmittance"},
          {&staticFilm.filmScanIlluminant, scanProducts.data(), scanProducts.size() * sizeof(float), "packed scan products"},
          {&staticFilm.paperScanIlluminant, paperCurves->scanIlluminant, wavelengthBytes, "paper scan illuminant"},
          {&staticFilm.standardObserverCmfs, standardObserverCmfs(), spectralTripletBytes, "standard observer CMFs"},
          {&staticFilm.filmScanToOutputRgb, curves->scanToOutputRgb, inputMatrixBytes, "film scan to output RGB"},
          {&staticFilm.paperScanToOutputRgb, paperCurves->scanToOutputRgb, inputMatrixBytes, "paper scan to output RGB"},
          {&staticFilm.colorEncodeLuts, colorEncodeAndGamutData.data(), static_cast<VkDeviceSize>(colorEncodeAndGamutData.size()) * sizeof(float), "color encode LUTs and output gamut compression data"},
          {&staticFilm.academyPrinterDensityData, academyPrinterDensityData(), academyPrinterDensityBytes, "Academy printer density data"},
        })) {
      return false;
    }
#if defined(__ANDROID__)
    const auto missingWavelengths = [](const std::vector<float> &packed) {
      uint32_t missing = 0;
      for (size_t index = 4; index < packed.size(); index += 8) missing += packed[index] == 0.0f;
      return missing;
    };
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraSpectral",
      "film=%d paper=%d masked_film=%u masked_paper=%u format=2", params.film, params.paper,
      missingWavelengths(packedFilmSpectralDensity), missingWavelengths(packedPaperSpectralDensity));
#endif
  }

  diagnostics.staticAllocationBytes += diagnostics.scratchAllocationBytes - scratchBytesBefore;
  diagnostics.staticAllocationCount += diagnostics.scratchAllocationCount - scratchCountBefore;
  diagnostics.scratchAllocationBytes = scratchBytesBefore;
  diagnostics.scratchAllocationCount = scratchCountBefore;
  diagnostics.sharedScratchAllocationBytes = sharedBytesBefore;
  diagnostics.sharedScratchAllocationCount = sharedCountBefore;
  diagnostics.privateScratchAllocationBytes = privateBytesBefore;
  diagnostics.privateScratchAllocationCount = privateCountBefore;

  staticFilm.film = params.film;
  staticFilm.paper = params.paper;
  staticFilm.rgbToRawMethod = params.rgbToRawMethod;
  staticFilm.cameraUvFilterEnabled = params.cameraUvFilterEnabled;
  staticFilm.cameraUvCutNm = params.cameraUvCutNm;
  staticFilm.cameraIrFilterEnabled = params.cameraIrFilterEnabled;
  staticFilm.cameraIrCutNm = params.cameraIrCutNm;
  staticFilm.processNegativePrintTiming = params.printTiming;
  staticFilm.processNegativeResponsesReady = needsProcessNegativeResponses;
  staticFilm.processNegativeFilterC = params.filterC;
  staticFilm.processNegativeFilterMShift = params.filterMShift;
  staticFilm.processNegativeFilterYShift = params.filterYShift;
  staticFilm.processNegativePreflashMFilterShift = params.preflashMFilterShift;
  staticFilm.processNegativePreflashYFilterShift = params.preflashYFilterShift;
  staticFilm.printScanResources = includePrintScanResources;
  staticFilm.grainResources = includeGrainResources;
  staticFilm.curves = curves;
  staticFilm.exposureCount = curves->exposureCount;
  staticFilm.wavelengthCount = curves->wavelengthCount;
  staticFilm.filmPositive = curves->type && std::strcmp(curves->type, "positive") == 0 ? 1u : 0u;
  staticFilm.hanatosWidth = needsHanatos ? hanatos.width : 0u;
  staticFilm.hanatosHeight = needsHanatos ? hanatos.height : 0u;
  staticFilm.filmDensityMaximum = densityCurveMaximums(*curves);
  if (includePrintScanResources) {
    staticFilm.paperCurves = paperCurves;
    staticFilm.paperExposureCount = paperCurves->exposureCount;
    staticFilm.paperDensityMaximum = densityCurveMaximums(*paperCurves);
  } else {
    staticFilm.paperCurves = nullptr;
    staticFilm.paperExposureCount = 0u;
    staticFilm.paperDensityMaximum = {0.0f, 0.0f, 0.0f};
  }
  return true;
}

bool VulkanRenderer::Impl::ensureCopyFrameResources() {
  if (copyFrame.descriptorPool == VK_NULL_HANDLE) {
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2u * backend->storageChunkCount();

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    VkResult result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &copyFrame.descriptorPool);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorPool", result);
      return false;
    }

    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = copyFrame.descriptorPool;
    setInfo.descriptorSetCount = 1;
    setInfo.pSetLayouts = &copyDescriptorSetLayout;
    result = vkAllocateDescriptorSets(device, &setInfo, &copyFrame.descriptorSet);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkAllocateDescriptorSets", result);
      destroyCopyFrameResources();
      return false;
    }
  }

  if (copyFrame.commandBuffer == VK_NULL_HANDLE) {
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1;
    VkResult result = vkAllocateCommandBuffers(device, &commandInfo, &copyFrame.commandBuffer);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkAllocateCommandBuffers", result);
      return false;
    }
  }

  if (copyFrame.fence == VK_NULL_HANDLE) {
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    VkResult result = vkCreateFence(device, &fenceInfo, nullptr, &copyFrame.fence);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateFence", result);
      return false;
    }
  }

  return true;
}

void VulkanRenderer::Impl::destroyFourStageTexture() {
  descriptorContents.clear();
  if (coreFrame.fourStageTextureSampler != VK_NULL_HANDLE) vkDestroySampler(device, coreFrame.fourStageTextureSampler, nullptr);
  if (coreFrame.fourStageTextureView != VK_NULL_HANDLE) vkDestroyImageView(device, coreFrame.fourStageTextureView, nullptr);
  if (coreFrame.fourStageTexture != VK_NULL_HANDLE) vkDestroyImage(device, coreFrame.fourStageTexture, nullptr);
  if (coreFrame.fourStageTextureMemory != VK_NULL_HANDLE) vkFreeMemory(device, coreFrame.fourStageTextureMemory, nullptr);
  coreFrame.fourStageTextureSampler = VK_NULL_HANDLE;
  coreFrame.fourStageTextureView = VK_NULL_HANDLE;
  coreFrame.fourStageTexture = VK_NULL_HANDLE;
  coreFrame.fourStageTextureMemory = VK_NULL_HANDLE;
  coreFrame.fourStageTextureBytes = 0;
  coreFrame.fourStageTextureReady = false;
  coreFrame.fourStageTextureGeneration = 0;
}

bool VulkanRenderer::Impl::ensureFourStageTexture() {
  if (coreFrame.fourStageTextureView != VK_NULL_HANDLE) return true;
  constexpr VkFormat format = VK_FORMAT_R32G32B32A32_SFLOAT;
  constexpr uint32_t size = CoreFrameResources::kFourStageLutSize;
  constexpr VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
  VkFormatProperties properties{};
  vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &properties);
  constexpr VkFormatFeatureFlags required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
    VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
  VkImageFormatProperties imageProperties{};
  if (physicalDeviceProperties.limits.subTexelPrecisionBits < 8u ||
      (properties.optimalTilingFeatures & required) != required ||
      vkGetPhysicalDeviceImageFormatProperties(physicalDevice, format, VK_IMAGE_TYPE_3D,
        VK_IMAGE_TILING_OPTIMAL, usage, 0, &imageProperties) != VK_SUCCESS ||
      imageProperties.maxExtent.width < size || imageProperties.maxExtent.height < size ||
      imageProperties.maxExtent.depth < size * 4u) {
    lastError = vkError("Filtered colour tables", VK_ERROR_FORMAT_NOT_SUPPORTED);
    return false;
  }
  auto fail = [&](const char *operation, VkResult result) {
    lastError = vkError(operation, result);
    destroyFourStageTexture();
    return false;
  };
  VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  info.imageType = VK_IMAGE_TYPE_3D;
  info.format = format;
  info.extent = {size, size, size * 4u};
  info.mipLevels = info.arrayLayers = 1;
  info.samples = VK_SAMPLE_COUNT_1_BIT;
  info.tiling = VK_IMAGE_TILING_OPTIMAL;
  info.usage = usage;
  VkResult result = vkCreateImage(device, &info, nullptr, &coreFrame.fourStageTexture);
  if (result != VK_SUCCESS) return fail("Create colour table image", result);
  VkMemoryRequirements requirements{};
  vkGetImageMemoryRequirements(device, coreFrame.fourStageTexture, &requirements);
  const uint32_t memoryType = findMemoryTypeIndex(physicalDevice, requirements.memoryTypeBits,
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (memoryType == std::numeric_limits<uint32_t>::max()) return fail("Colour table memory type", VK_ERROR_FEATURE_NOT_PRESENT);
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.allocationSize = requirements.size;
  allocation.memoryTypeIndex = memoryType;
  result = vkAllocateMemory(device, &allocation, nullptr, &coreFrame.fourStageTextureMemory);
  if (result != VK_SUCCESS) return fail("Allocate colour table image", result);
  result = vkBindImageMemory(device, coreFrame.fourStageTexture, coreFrame.fourStageTextureMemory, 0);
  if (result != VK_SUCCESS) return fail("Bind colour table image", result);
  VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  view.image = coreFrame.fourStageTexture;
  view.viewType = VK_IMAGE_VIEW_TYPE_3D;
  view.format = format;
  view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  result = vkCreateImageView(device, &view, nullptr, &coreFrame.fourStageTextureView);
  if (result != VK_SUCCESS) return fail("Create colour table view", result);
  VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  sampler.magFilter = sampler.minFilter = VK_FILTER_LINEAR;
  sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
  sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  result = vkCreateSampler(device, &sampler, nullptr, &coreFrame.fourStageTextureSampler);
  if (result != VK_SUCCESS) return fail("Create colour table sampler", result);
  coreFrame.fourStageTextureBytes = requirements.size;
  return true;
}

void VulkanRenderer::Impl::destroyComposedLut() {
  auto &lut = coreFrame.composedLut;
  descriptorContents.clear();
  if (lut.pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, lut.pool, nullptr);
  if (lut.view != VK_NULL_HANDLE) vkDestroyImageView(device, lut.view, nullptr);
  if (lut.image != VK_NULL_HANDLE) vkDestroyImage(device, lut.image, nullptr);
  if (lut.memory != VK_NULL_HANDLE) vkFreeMemory(device, lut.memory, nullptr);
  lut = {};
}

void VulkanRenderer::Impl::destroyVideoEffectResources() {
  auto &resources = video;
  if (device != VK_NULL_HANDLE) {
    if (resources.pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, resources.pool, nullptr);
    for (auto *pyramid : {&resources.film, &resources.print}) {
      for (VkImageView view : pyramid->levels) if (view != VK_NULL_HANDLE) vkDestroyImageView(device, view, nullptr);
      if (pyramid->sampled != VK_NULL_HANDLE) vkDestroyImageView(device, pyramid->sampled, nullptr);
      if (pyramid->image != VK_NULL_HANDLE) vkDestroyImage(device, pyramid->image, nullptr);
      if (pyramid->memory != VK_NULL_HANDLE) vkFreeMemory(device, pyramid->memory, nullptr);
    }
    if (resources.sampler != VK_NULL_HANDLE) vkDestroySampler(device, resources.sampler, nullptr);
    if (resources.timing != VK_NULL_HANDLE) vkDestroyQueryPool(device, resources.timing, nullptr);
    destroyScratchBuffer(resources.info);
    destroyScratchBuffer(resources.state);
  }
  resources = VideoEffectResources{};
}

bool VulkanRenderer::Impl::ensureVideoEffectResources(uint32_t width, uint32_t height, bool pyramid) {
  auto &resources = video;
  if (resources.pool != VK_NULL_HANDLE && resources.width == width && resources.height == height &&
      resources.pyramid == pyramid) return true;
  destroyVideoEffectResources();
  constexpr VkFormat format = VK_FORMAT_R16G16B16A16_SFLOAT;
  constexpr VkFormatFeatureFlags required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
    VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
  VkFormatProperties properties{};
  vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &properties);
  if ((properties.optimalTilingFeatures & required) != required) {
    lastError = vkError("Video effect pyramid format", VK_ERROR_FORMAT_NOT_SUPPORTED);
    return false;
  }
  auto fail = [&](const char *operation, VkResult result) {
    lastError = vkError(operation, result);
    destroyVideoEffectResources();
    return false;
  };
  // Without spatial effects the colour pass only needs placeholder pyramids.
  const uint32_t baseWidth = pyramid ? (width + 1u) / 2u : 1u;
  const uint32_t baseHeight = pyramid ? (height + 1u) / 2u : 1u;
  const uint32_t levels = pyramid ? video_effects::availableLevels(baseWidth, baseHeight) : 1u;
  for (auto *image : {&resources.film, &resources.print}) {
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {baseWidth, baseHeight, 1u};
    info.mipLevels = levels;
    info.arrayLayers = 1u;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult result = vkCreateImage(device, &info, nullptr, &image->image);
    if (result != VK_SUCCESS) return fail("Create video effect pyramid", result);
    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(device, image->image, &requirements);
    const uint32_t memoryType = findMemoryTypeIndex(physicalDevice, requirements.memoryTypeBits,
      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memoryType == std::numeric_limits<uint32_t>::max()) {
      return fail("Video effect pyramid memory type", VK_ERROR_FEATURE_NOT_PRESENT);
    }
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = memoryType;
    result = vkAllocateMemory(device, &allocation, nullptr, &image->memory);
    if (result != VK_SUCCESS) return fail("Allocate video effect pyramid", result);
    result = vkBindImageMemory(device, image->image, image->memory, 0);
    if (result != VK_SUCCESS) return fail("Bind video effect pyramid", result);
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = image->image;
    view.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view.format = format;
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1};
    result = vkCreateImageView(device, &view, nullptr, &image->sampled);
    if (result != VK_SUCCESS) return fail("Create video effect pyramid view", result);
    for (uint32_t level = 0; level < levels; ++level) {
      view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, level, 1, 0, 1};
      result = vkCreateImageView(device, &view, nullptr, &image->levels[level]);
      if (result != VK_SUCCESS) return fail("Create video effect level view", result);
    }
  }
  VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  sampler.magFilter = VK_FILTER_LINEAR;
  sampler.minFilter = VK_FILTER_LINEAR;
  sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
  sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler.maxLod = static_cast<float>(levels);
  VkResult result = vkCreateSampler(device, &sampler, nullptr, &resources.sampler);
  if (result != VK_SUCCESS) return fail("Create video effect sampler", result);
  if (!ensureScratchBuffer(resources.info, sizeof(VideoEffectInfo), "video effect info",
        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        true, true) ||
      !ensureScratchBuffer(resources.state, 64u, "video effect state",
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        0u, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false, true)) {
    destroyVideoEffectResources();
    return false;
  }
  const VkDescriptorPoolSize sizes[]{
    {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3u},
    {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u},
    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2u + 2u * levels},
    {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 5u}};
  VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  pool.maxSets = 1u + levels;
  pool.poolSizeCount = static_cast<uint32_t>(std::size(sizes));
  pool.pPoolSizes = sizes;
  result = vkCreateDescriptorPool(device, &pool, nullptr, &resources.pool);
  if (result != VK_SUCCESS) return fail("Create video effect descriptors", result);
  std::array<VkDescriptorSetLayout, 1u + video_effects::kMaxLevels> layouts{};
  layouts[0] = backend->videoEffectsSetLayout;
  for (uint32_t level = 0; level < levels; ++level) layouts[1u + level] = backend->videoEffectsLevelLayout;
  std::array<VkDescriptorSet, 1u + video_effects::kMaxLevels> sets{};
  VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  allocate.descriptorPool = resources.pool;
  allocate.descriptorSetCount = 1u + levels;
  allocate.pSetLayouts = layouts.data();
  result = vkAllocateDescriptorSets(device, &allocate, sets.data());
  if (result != VK_SUCCESS) return fail("Allocate video effect descriptors", result);
  resources.shared = sets[0];
  std::array<VkDescriptorImageInfo, 2u * video_effects::kMaxLevels> images{};
  std::array<VkWriteDescriptorSet, 2u * video_effects::kMaxLevels> writes{};
  for (uint32_t level = 0; level < levels; ++level) {
    resources.levelSets[level] = sets[1u + level];
    for (uint32_t binding = 0; binding < 2u; ++binding) {
      const uint32_t index = level * 2u + binding;
      images[index] = {VK_NULL_HANDLE, (binding == 0u ? resources.film : resources.print).levels[level],
        VK_IMAGE_LAYOUT_GENERAL};
      writes[index] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
      writes[index].dstSet = resources.levelSets[level];
      writes[index].dstBinding = binding;
      writes[index].descriptorCount = 1u;
      writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
      writes[index].pImageInfo = &images[index];
    }
  }
  vkUpdateDescriptorSets(device, 2u * levels, writes.data(), 0, nullptr);
  resources.width = width;
  resources.height = height;
  resources.baseWidth = baseWidth;
  resources.baseHeight = baseHeight;
  resources.levelCount = levels;
  resources.pyramid = pyramid;
  resources.initialized = false;
  return true;
}

bool VulkanRenderer::Impl::recordVideoEffects(const VideoEffectInputs &inputs) {
  const RenderParams &params = videoEffectParams;
  const bool finalOutput = params.renderOutput == RenderOutputMode::FinalPreview;
  const bool printSimulation = finalOutput && params.process == ProcessMode::PrintSimulation;
  const bool processNegative = finalOutput && params.process == ProcessMode::ProcessNegative;
  const uint32_t baseWidth = (inputs.width + 1u) / 2u;
  const uint32_t baseHeight = (inputs.height + 1u) / 2u;
  const uint32_t available = video_effects::availableLevels(baseWidth, baseHeight);
  const double baseTexel = static_cast<double>(inputs.width) / static_cast<double>(baseWidth);

  const bool halation = params.halationEnabled && !processNegative;
  std::array<float, 3> strength{params.halationStrengthR, params.halationStrengthG, params.halationStrengthB};
  if (staticFilm.curves && staticFilm.curves->halationStrength && isDefaultHalationStrength(params)) {
    strength = {staticFilm.curves->halationStrength[0], staticFilm.curves->halationStrength[1],
      staticFilm.curves->halationStrength[2]};
  }
  std::array<float, 3> firstSigma{params.halationFirstSigmaUmR, params.halationFirstSigmaUmG,
    params.halationFirstSigmaUmB};
  if (staticFilm.curves && staticFilm.curves->halationFirstSigmaUm) {
    for (size_t channel = 0; channel < 3u; ++channel) {
      firstSigma[channel] *= staticFilm.curves->halationFirstSigmaUm[channel] / 65.0f;
    }
  }
  auto filmKernel = video_effects::identityKernel();
  if (params.cameraDiffusionEnabled && !processNegative && params.cameraDiffusionStrength > 0.0f &&
      params.cameraDiffusionSpatialScale > 0.0f) {
    filmKernel = videoDiffusionKernel(diffusionSettings(params, false), inputs.filmPixelSizeUm);
  }
  if (halation) {
    filmKernel = video_effects::convolve(
      videoHalationKernel(params, inputs.filmPixelSizeUm, strength, firstSigma), filmKernel);
  }
  const auto filmLevels = video_effects::fitLevels(filmKernel, baseTexel, available);
  video_effects::LevelWeights printLevels;
  if (printSimulation && params.printDiffusionEnabled && params.printDiffusionStrength > 0.0f &&
      params.printDiffusionSpatialScale > 0.0f) {
    printLevels = video_effects::fitLevels(
      videoDiffusionKernel(diffusionSettings(params, true), inputs.filmPixelSizeUm), baseTexel, available);
  }
  const bool film = filmLevels.spreads();
  const bool boost = halation && params.halationBoostEv > 0.0f;
  const bool print = printLevels.spreads();
  const bool glare = printSimulation && params.scannerEnabled && params.glarePercent > 0.0f &&
    params.outputRole == OutputRole::DisplaySdr;
  const bool pyramid = film || boost || print;
  const uint32_t composeOperation = inputs.composed ? kVideoEffectComposeComposed : kVideoEffectComposeStages;

  auto unavailable = [&](const char *stage) {
#if defined(__ANDROID__)
    if (video.loggedConfig[3] != 0xffffffffu) {
      recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraVideoEffects", "Video effects unavailable at %s: %s",
        stage, lastError.empty() ? backend->lastError.c_str() : lastError.c_str());
    }
#endif
    video.loggedConfig = {0u, 0u, 0u, 0xffffffffu};
    lastError.clear();
    return false;
  };
  const uint32_t flags = (film ? 1u : 0u) | (boost ? 2u : 0u) | (print ? 4u : 0u) | (glare ? 8u : 0u);
  const std::array<uint32_t, 6> operations{kVideoEffectBoostCurve, kVideoEffectBase, kVideoEffectDown,
    kVideoEffectChain, kVideoEffectPrintDown, kVideoEffectPrintChain};
  for (uint32_t operation : operations) {
    if (pyramid && !backend->prepareVideoEffects(operation)) return unavailable("pipeline preparation");
  }
  if (!backend->prepareVideoEffects(composeOperation)) return unavailable("pipeline preparation");
  if (!ensureVideoEffectResources(inputs.width, inputs.height, pyramid)) return unavailable("resource allocation");

  VideoEffectInfo info{};
  const uint32_t filmCount = film ? filmLevels.count : 1u;
  const uint32_t printCount = print ? printLevels.count : 1u;
  info.config[0] = filmCount;
  info.config[1] = flags;
  info.config[2] = printCount;
  for (size_t channel = 0; channel < 3u; ++channel) {
    info.sharp[channel] = film ? filmLevels.sharp[channel] : 1.0f;
    info.sharpPrint[channel] = print ? printLevels.sharp[channel] : 1.0f;
  }
  // Boosted highlights stay within half-float range.
  info.sharp[3] = boost ? std::exp2(-std::max(params.halationBoostEv, 0.0f)) : 1.0f;
  info.boost[0] = params.halationBoostEv;
  info.boost[1] = params.halationBoostRange;
  info.boost[2] = params.halationProtectEv;
  info.boost[3] = 0.25f;
  if (glare) {
    const auto rgb = scanIlluminantToOutputRgb(staticFilm.paperCurves, params);
    for (size_t channel = 0; channel < 3u; ++channel) info.glare[channel] = params.glarePercent * 0.01f * rgb[channel];
  }
  for (uint32_t level = 0; level < video_effects::kMaxLevels; ++level) {
    for (size_t channel = 0; channel < 3u; ++channel) {
      info.weights[level][channel] = film && level < filmCount ? filmLevels.levels[level][channel] : 0.0f;
      info.printWeights[level][channel] = print && level < printCount ? printLevels.levels[level][channel] : 0.0f;
    }
  }
  std::memcpy(video.info.mapped, &info, sizeof(info));
  if (!flushMappedScratchBuffer(video.info, sizeof(info), "video effect info")) return unavailable("upload");

  const VkDescriptorImageInfo source{VK_NULL_HANDLE, coreFrame.sourceImageView, VK_IMAGE_LAYOUT_GENERAL};
  const VkDescriptorImageInfo sourceTexture{video.sampler, coreFrame.sourceImageView, VK_IMAGE_LAYOUT_GENERAL};
  const VkDescriptorImageInfo destination{VK_NULL_HANDLE, coreFrame.destinationImageView, VK_IMAGE_LAYOUT_GENERAL};
  const VkDescriptorImageInfo filmPyramid{video.sampler, video.film.sampled, VK_IMAGE_LAYOUT_GENERAL};
  const VkDescriptorImageInfo printPyramid{video.sampler, video.print.sampled, VK_IMAGE_LAYOUT_GENERAL};
  const VkDescriptorImageInfo composedTexture = inputs.composedTexture.imageView != VK_NULL_HANDLE
    ? inputs.composedTexture : inputs.lutTexture;
  const VkDescriptorBufferInfo infoBuffer{video.info.buffer, 0, sizeof(VideoEffectInfo)};
  const VkDescriptorBufferInfo stateBuffer{video.state.buffer, 0, VK_WHOLE_SIZE};
  std::array<VkWriteDescriptorSet, 11> writes{};
  auto write = [&](size_t index, uint32_t binding, VkDescriptorType type,
                   const VkDescriptorImageInfo *image, const VkDescriptorBufferInfo *buffer) {
    writes[index] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    writes[index].dstSet = video.shared;
    writes[index].dstBinding = binding;
    writes[index].descriptorCount = 1u;
    writes[index].descriptorType = type;
    writes[index].pImageInfo = image;
    writes[index].pBufferInfo = buffer;
  };
  write(0, 10u, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &inputs.lut);
  write(1, 30u, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &inputs.exposure);
  write(2, SPEKTRA_PREVIEW_SOURCE_IMAGE_BINDING, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &source, nullptr);
  write(3, SPEKTRA_PREVIEW_DESTINATION_IMAGE_BINDING, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &destination, nullptr);
  write(4, SPEKTRA_FOUR_STAGE_LUT_TEXTURE_BINDING, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &inputs.lutTexture, nullptr);
  write(5, SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &composedTexture, nullptr);
  write(6, 40u, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &infoBuffer);
  write(7, 41u, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &stateBuffer);
  write(8, 43u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &filmPyramid, nullptr);
  write(9, 45u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &printPyramid, nullptr);
  write(10, 42u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &sourceTexture, nullptr);
  vkUpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

#if defined(__ANDROID__)
  const std::array<uint32_t, 4> logged{flags, filmCount, printCount, inputs.width * 65536u + inputs.height};
  if (logged != video.loggedConfig) {
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraVideoEffects",
      "size=%ux%u film=%d boost=%d print=%d glare=%d film_levels=%u print_levels=%u sharp=%.3f/%.3f/%.3f print_sharp=%.3f",
      inputs.width, inputs.height, film, boost, print, glare, filmCount, printCount,
      info.sharp[0], info.sharp[1], info.sharp[2], info.sharpPrint[1]);
    video.loggedConfig = logged;
  }
#endif

  VkCommandBuffer commands = inputs.commandBuffer;
  bool timed = false;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char profileProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.profile", profileProperty);
  if (video.timingPending) {
    std::array<uint64_t, 5> stamps{};
    if (vkGetQueryPoolResults(device, video.timing, 0, 5, sizeof(stamps), stamps.data(), sizeof(uint64_t),
          VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
      const double period = physicalDeviceProperties.limits.timestampPeriod / 1.0e6;
      auto interval = [&](size_t from, size_t to) { return static_cast<double>(stamps[to] - stamps[from]) * period; };
      __android_log_print(ANDROID_LOG_INFO, "SpektraVideoEffectsTiming",
        "size=%ux%u base=%.3f down=%.3f chain=%.3f compose=%.3f pyramid=%.3f",
        video.width, video.height, interval(0, 1), interval(1, 2), interval(2, 3), interval(3, 4), interval(0, 3));
    }
    video.timingPending = false;
  }
  if (profileProperty[0] == '1' && video.timing == VK_NULL_HANDLE) {
    VkQueryPoolCreateInfo pool{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    pool.queryType = VK_QUERY_TYPE_TIMESTAMP;
    pool.queryCount = 5u;
    if (vkCreateQueryPool(device, &pool, nullptr, &video.timing) != VK_SUCCESS) video.timing = VK_NULL_HANDLE;
  }
  timed = profileProperty[0] == '1' && video.timing != VK_NULL_HANDLE;
  if (timed) vkCmdResetQueryPool(commands, video.timing, 0, 5u);
#endif
  auto stamp = [&](uint32_t query) {
    if (timed) vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, video.timing, query);
  };
  auto computeBarrier = [&]() {
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 1, &barrier, 0, nullptr, 0, nullptr);
  };
  if (!video.initialized) {
    std::array<VkImageMemoryBarrier, 2> images{};
    for (size_t index = 0; index < images.size(); ++index) {
      images[index] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
      images[index].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      images[index].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      images[index].newLayout = VK_IMAGE_LAYOUT_GENERAL;
      images[index].srcQueueFamilyIndex = images[index].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      images[index].image = index == 0u ? video.film.image : video.print.image;
      images[index].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, video.levelCount, 0, 1};
    }
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 0, nullptr, 0, nullptr, static_cast<uint32_t>(images.size()), images.data());
    vkCmdFillBuffer(commands, video.state.buffer, 0, VK_WHOLE_SIZE, 0u);
    VkMemoryBarrier cleared{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    cleared.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    cleared.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 1, &cleared, 0, nullptr, 0, nullptr);
    video.initialized = true;
  }
  computeBarrier();

  const VkPipelineLayout layout = backend->videoEffectsLayout;
  VideoEffectPushConstants push{};
  push.width = inputs.width;
  push.height = inputs.height;
  push.exposureLog = inputs.exposureEv * 0.3010299956639812f;
  push.flags = params.autoExposure ? 1u : 0u;
  push.composedSize = inputs.composedSize;
  vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &video.shared, 0, nullptr);
  auto dispatch = [&](uint32_t operation, uint32_t level, uint32_t width, uint32_t height) {
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, backend->videoEffectsPipelines[operation]);
    vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 1, 1, &video.levelSets[level], 0, nullptr);
    push.level = level;
    vkCmdPushConstants(commands, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(commands, (width + 31u) / 32u, (height + 7u) / 8u, 1u);
    computeBarrier();
  };
  auto levelWidth = [&](uint32_t level) { return std::max(video.baseWidth >> level, 1u); };
  auto levelHeight = [&](uint32_t level) { return std::max(video.baseHeight >> level, 1u); };
  stamp(0u);
  if (boost) dispatch(kVideoEffectBoostCurve, 0u, 1u, 1u);
  if (pyramid) dispatch(kVideoEffectBase, 0u, video.baseWidth, video.baseHeight);
  stamp(1u);
  if (film) {
    for (uint32_t level = 1u; level < filmCount; ++level) {
      dispatch(kVideoEffectDown, level, levelWidth(level), levelHeight(level));
    }
  }
  stamp(2u);
  if (film || print) {
    for (uint32_t level = filmCount; level-- > 0u;) {
      dispatch(kVideoEffectChain, level, levelWidth(level), levelHeight(level));
    }
  }
  if (print) {
    for (uint32_t level = 1u; level < printCount; ++level) {
      dispatch(kVideoEffectPrintDown, level, levelWidth(level), levelHeight(level));
    }
    for (uint32_t level = printCount; level-- > 0u;) {
      dispatch(kVideoEffectPrintChain, level, levelWidth(level), levelHeight(level));
    }
  }
  stamp(3u);
  const VkDescriptorSet sets[]{video.shared, video.levelSets[0]};
  push.level = 0u;
  inputs.recorder->bindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, backend->videoEffectsPipelines[composeOperation]);
  inputs.recorder->bindDescriptors(commands, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 2, sets, 0, nullptr);
  inputs.recorder->push(commands, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
  inputs.recorder->dispatch(commands, (inputs.width + 31u) / 32u, (inputs.height + 7u) / 8u, 1u);
  if (timed) {
    computeBarrier();
    stamp(4u);
    video.timingPending = true;
  }
  return true;
}

bool VulkanRenderer::Impl::ensureComposedLut(uint32_t size) {
  auto &lut = coreFrame.composedLut;
  if (lut.set != VK_NULL_HANDLE && lut.size == size) return true;
  destroyComposedLut();
  if (!backend->prepareComposedLut() || !backend->preparePreviewColor(true)) return false;
  constexpr VkFormat format = VK_FORMAT_R16G16B16A16_SFLOAT;
  constexpr VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
  constexpr VkFormatFeatureFlags required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
    VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
  VkFormatProperties properties{};
  VkImageFormatProperties imageProperties{};
  vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &properties);
  if ((properties.optimalTilingFeatures & required) != required ||
      vkGetPhysicalDeviceImageFormatProperties(physicalDevice, format, VK_IMAGE_TYPE_3D,
        VK_IMAGE_TILING_OPTIMAL, usage, 0, &imageProperties) != VK_SUCCESS ||
      std::min({imageProperties.maxExtent.width, imageProperties.maxExtent.height, imageProperties.maxExtent.depth}) < size)
    return false;
  auto fail = [&](const char *operation, VkResult result) {
    lastError = vkError(operation, result);
    destroyComposedLut();
    return false;
  };
  VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  info.imageType = VK_IMAGE_TYPE_3D;
  info.format = format;
  info.extent = {size, size, size};
  info.mipLevels = info.arrayLayers = 1;
  info.samples = VK_SAMPLE_COUNT_1_BIT;
  info.tiling = VK_IMAGE_TILING_OPTIMAL;
  info.usage = usage;
  VkResult result = vkCreateImage(device, &info, nullptr, &lut.image);
  if (result != VK_SUCCESS) return fail("Create composed colour image", result);
  VkMemoryRequirements requirements{};
  vkGetImageMemoryRequirements(device, lut.image, &requirements);
  const uint32_t memoryType = findMemoryTypeIndex(physicalDevice, requirements.memoryTypeBits,
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (memoryType == std::numeric_limits<uint32_t>::max()) return fail("Composed colour memory type", VK_ERROR_FEATURE_NOT_PRESENT);
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.allocationSize = requirements.size;
  allocation.memoryTypeIndex = memoryType;
  result = vkAllocateMemory(device, &allocation, nullptr, &lut.memory);
  if (result != VK_SUCCESS) return fail("Allocate composed colour image", result);
  result = vkBindImageMemory(device, lut.image, lut.memory, 0);
  if (result != VK_SUCCESS) return fail("Bind composed colour image", result);
  VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  view.image = lut.image;
  view.viewType = VK_IMAGE_VIEW_TYPE_3D;
  view.format = format;
  view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  result = vkCreateImageView(device, &view, nullptr, &lut.view);
  if (result != VK_SUCCESS) return fail("Create composed colour view", result);
  const VkDescriptorPoolSize sizes[]{{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1}};
  VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  pool.maxSets = 1;
  pool.poolSizeCount = 2;
  pool.pPoolSizes = sizes;
  result = vkCreateDescriptorPool(device, &pool, nullptr, &lut.pool);
  if (result != VK_SUCCESS) return fail("Create composed colour descriptors", result);
  VkDescriptorSetAllocateInfo set{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  set.descriptorPool = lut.pool;
  set.descriptorSetCount = 1;
  set.pSetLayouts = &backend->composeSetLayout;
  result = vkAllocateDescriptorSets(device, &set, &lut.set);
  if (result != VK_SUCCESS) return fail("Allocate composed colour descriptors", result);
  lut.size = size;
  lut.bytes = requirements.size;
  return true;
}

bool VulkanRenderer::Impl::ensureCompactPrintDescriptors() {
  if (coreFrame.compactPrintSets[0] != VK_NULL_HANDLE) return true;
  if (coreFrame.compactPrintPool == VK_NULL_HANDLE) {
    const VkDescriptorPoolSize sizes[]{
      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3u * kCoreStorageBufferBindingCount},
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3u * SPEKTRA_PREVIEW_IMAGE_BINDING_COUNT},
      {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 6u}};
    VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    info.maxSets = 3;
    info.poolSizeCount = 3;
    info.pPoolSizes = sizes;
    const VkResult result = vkCreateDescriptorPool(device, &info, nullptr, &coreFrame.compactPrintPool);
    if (result != VK_SUCCESS) { lastError = vkError("Compact print descriptor pool", result); return false; }
  }
  const std::array<VkDescriptorSetLayout, 3> layouts{
    backend->compactPrintSetLayout, backend->compactPrintSetLayout, backend->compactPrintSetLayout};
  VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  info.descriptorPool = coreFrame.compactPrintPool;
  info.descriptorSetCount = static_cast<uint32_t>(layouts.size());
  info.pSetLayouts = layouts.data();
  const VkResult result = vkAllocateDescriptorSets(device, &info, coreFrame.compactPrintSets.data());
  if (result != VK_SUCCESS) {
    coreFrame.compactPrintSets.fill(VK_NULL_HANDLE);
    lastError = vkError("Compact print descriptor sets", result);
    return false;
  }
  return true;
}

bool VulkanRenderer::Impl::ensureCoreFrameResources() {
  if (coreFrame.descriptorPool == VK_NULL_HANDLE) {
    const VkDescriptorPoolSize poolSizes[]{
      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, (kCoreStorageBufferBindingCount + 8u * (backend->storageChunkCount() - 1u)) * kCoreDescriptorSetCount},
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SPEKTRA_PREVIEW_IMAGE_BINDING_COUNT * kCoreDescriptorSetCount},
      {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2u * kCoreDescriptorSetCount}};

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kCoreDescriptorSetCount;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    VkResult result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &coreFrame.descriptorPool);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorPool", result);
      return false;
    }

    std::array<VkDescriptorSetLayout, kCoreDescriptorSetCount> setLayouts{};
    setLayouts.fill(coreDescriptorSetLayout);
    std::array<VkDescriptorSet, kCoreDescriptorSetCount> descriptorSets{};
    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = coreFrame.descriptorPool;
    setInfo.descriptorSetCount = kCoreDescriptorSetCount;
    setInfo.pSetLayouts = setLayouts.data();
    result = vkAllocateDescriptorSets(device, &setInfo, descriptorSets.data());
    if (result != VK_SUCCESS) {
      lastError = vkError("vkAllocateDescriptorSets", result);
      destroyCoreFrameResources();
      return false;
    }
    coreFrame.autoExposureDescriptorSet = descriptorSets[0];
    coreFrame.exposureDescriptorSet = descriptorSets[1];
    coreFrame.developDescriptorSet = descriptorSets[2];
    coreFrame.finalDescriptorSet = descriptorSets[3];
    for (uint32_t setIndex = 0; setIndex < kCoreHalationSetCount; ++setIndex) {
      coreFrame.halationDescriptorSets[setIndex] = descriptorSets[4u + setIndex];
    }
    uint32_t descriptorSetOffset = 4u + kCoreHalationSetCount;
    for (uint32_t setIndex = 0; setIndex < kCoreDiffusionSetCount; ++setIndex) {
      coreFrame.diffusionDescriptorSets[setIndex] = descriptorSets[descriptorSetOffset + setIndex];
    }
    descriptorSetOffset += kCoreDiffusionSetCount;
    for (uint32_t setIndex = 0; setIndex < kCorePrintDiffusionSetCount; ++setIndex) {
      coreFrame.printDiffusionDescriptorSets[setIndex] = descriptorSets[descriptorSetOffset + setIndex];
    }
    descriptorSetOffset += kCorePrintDiffusionSetCount;
    for (uint32_t setIndex = 0; setIndex < kCoreDirSetCount; ++setIndex) {
      coreFrame.dirDescriptorSets[setIndex] = descriptorSets[descriptorSetOffset + setIndex];
    }
    descriptorSetOffset += kCoreDirSetCount;
    for (uint32_t setIndex = 0; setIndex < kCoreScannerPostSetCount; ++setIndex) {
      coreFrame.scannerPostDescriptorSets[setIndex] = descriptorSets[descriptorSetOffset + setIndex];
    }
    descriptorSetOffset += kCoreScannerPostSetCount;
    for (uint32_t setIndex = 0; setIndex < kCoreGrainSetCount; ++setIndex) {
      coreFrame.grainDescriptorSets[setIndex] = descriptorSets[descriptorSetOffset + setIndex];
    }
  }

  if (coreFrame.formatDescriptorPool == VK_NULL_HANDLE) {
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 4u * backend->storageChunkCount();

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 2;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    VkResult result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &coreFrame.formatDescriptorPool);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorPool(format)", result);
      return false;
    }

    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = coreFrame.formatDescriptorPool;
    const std::array<VkDescriptorSetLayout, 2> setLayouts = {
      copyDescriptorSetLayout,
      copyDescriptorSetLayout
    };
    std::array<VkDescriptorSet, 2> descriptorSets{};
    setInfo.descriptorSetCount = static_cast<uint32_t>(setLayouts.size());
    setInfo.pSetLayouts = setLayouts.data();
    result = vkAllocateDescriptorSets(device, &setInfo, descriptorSets.data());
    if (result != VK_SUCCESS) {
      lastError = vkError("vkAllocateDescriptorSets(format)", result);
      destroyCoreFrameResources();
      return false;
    }
    coreFrame.sourceFormatDescriptorSet = descriptorSets[0];
    coreFrame.destinationFormatDescriptorSet = descriptorSets[1];
  }

  if (coreFrame.imageFormatDescriptorPool == VK_NULL_HANDLE) {
    const std::array<VkDescriptorPoolSize, 2> poolSizes = {{
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4},
      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4u * backend->storageChunkCount()}
    }};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 4;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    VkResult result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &coreFrame.imageFormatDescriptorPool);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateDescriptorPool(image format)", result);
      return false;
    }
    const std::array<VkDescriptorSetLayout, 4> layouts = {
      imageFormatConvertDescriptorSetLayout, imageFormatConvertDescriptorSetLayout,
      imageFormatConvertDescriptorSetLayout, imageFormatConvertDescriptorSetLayout
    };
    std::array<VkDescriptorSet, 4> sets{};
    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = coreFrame.imageFormatDescriptorPool;
    setInfo.descriptorSetCount = static_cast<uint32_t>(layouts.size());
    setInfo.pSetLayouts = layouts.data();
    result = vkAllocateDescriptorSets(device, &setInfo, sets.data());
    if (result != VK_SUCCESS) {
      lastError = vkError("vkAllocateDescriptorSets(image format)", result);
      destroyCoreFrameResources();
      return false;
    }
    coreFrame.sourceImageFormatDescriptorSet = sets[0];
    coreFrame.destinationImageFormatDescriptorSet = sets[1];
    coreFrame.autoExposureImageDescriptorSet = sets[2];
    coreFrame.sampleImageDescriptorSet = sets[3];
  }

  if (coreFrame.commandBuffer == VK_NULL_HANDLE) {
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1;
    VkResult result = vkAllocateCommandBuffers(device, &commandInfo, &coreFrame.commandBuffer);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkAllocateCommandBuffers", result);
      return false;
    }
  }

  if (coreFrame.fence == VK_NULL_HANDLE) {
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    VkResult result = vkCreateFence(device, &fenceInfo, nullptr, &coreFrame.fence);
    if (result != VK_SUCCESS) {
      lastError = vkError("vkCreateFence", result);
      return false;
    }
  }

  return true;
}

void VulkanRenderer::Impl::destroyCopyFrameResources() {
  descriptorContents.clear();
  copyFrame.submitted = false;
  if (copyFrame.fence != VK_NULL_HANDLE) {
    vkDestroyFence(device, copyFrame.fence, nullptr);
    copyFrame.fence = VK_NULL_HANDLE;
  }
  if (copyFrame.commandBuffer != VK_NULL_HANDLE) {
    vkFreeCommandBuffers(device, commandPool, 1, &copyFrame.commandBuffer);
    copyFrame.commandBuffer = VK_NULL_HANDLE;
  }
  if (copyFrame.descriptorPool != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(device, copyFrame.descriptorPool, nullptr);
    copyFrame.descriptorPool = VK_NULL_HANDLE;
    copyFrame.descriptorSet = VK_NULL_HANDLE;
  }
  destroyScratchBuffer(copyFrame.destination);
  destroyScratchBuffer(copyFrame.source);
}

void VulkanRenderer::Impl::releaseCoreImageViews() {
  if (coreFrame.ownsSourceImageView && coreFrame.sourceImageView != VK_NULL_HANDLE) {
    descriptorContents.clear();
    vkDestroyImageView(device, coreFrame.sourceImageView, nullptr);
  }
  if (coreFrame.ownsDestinationImageView && coreFrame.destinationImageView != VK_NULL_HANDLE) {
    descriptorContents.clear();
    vkDestroyImageView(device, coreFrame.destinationImageView, nullptr);
  }
  coreFrame.sourceImageView = coreFrame.destinationImageView = VK_NULL_HANDLE;
  coreFrame.ownsSourceImageView = coreFrame.ownsDestinationImageView = false;
}

void VulkanRenderer::Impl::destroyCoreFrameResources() {
  destroyVideoEffectResources();
  descriptorContents.clear();
  coreFrame.submitted = false;
  coreFrame.previewScheduleWidth = coreFrame.previewScheduleHeight = 0;
  coreFrame.previewFastFrames = 0;
  coreFrame.previewDirectSubmission = false;
  coreFrame.colorLutReady = false;
  coreFrame.fourStageLutReady = false;
  destroyScratchBuffer(coreFrame.colorLut);
  destroyScratchBuffer(coreFrame.fourStageLut);
  destroyFourStageTexture();
  destroyComposedLut();
  if (coreFrame.compactPrintPool != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(device, coreFrame.compactPrintPool, nullptr);
    coreFrame.compactPrintPool = VK_NULL_HANDLE;
    coreFrame.compactPrintSets.fill(VK_NULL_HANDLE);
  }
  coreFrame.samples.ready = false;
  if (coreFrame.samples.probePool != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(device, coreFrame.samples.probePool, nullptr);
    coreFrame.samples.probePool = VK_NULL_HANDLE;
    coreFrame.samples.probeSet = VK_NULL_HANDLE;
  }
  destroyScratchBuffer(coreFrame.samples.readback);
  if (coreFrame.dispatchProfilePool != VK_NULL_HANDLE) {
    vkDestroyQueryPool(device, coreFrame.dispatchProfilePool, nullptr);
    coreFrame.dispatchProfilePool = VK_NULL_HANDLE;
  }
  if (coreFrame.timingPool != VK_NULL_HANDLE) {
    vkDestroyQueryPool(device, coreFrame.timingPool, nullptr);
    coreFrame.timingPool = VK_NULL_HANDLE;
    coreFrame.timingPending = false;
  }
  releaseCoreImageViews();
  if (coreFrame.fence != VK_NULL_HANDLE) {
    vkDestroyFence(device, coreFrame.fence, nullptr);
    coreFrame.fence = VK_NULL_HANDLE;
  }
  if (coreFrame.commandBuffer != VK_NULL_HANDLE) {
    vkFreeCommandBuffers(device, commandPool, 1, &coreFrame.commandBuffer);
    coreFrame.commandBuffer = VK_NULL_HANDLE;
  }
  if (coreFrame.descriptorPool != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(device, coreFrame.descriptorPool, nullptr);
    coreFrame.descriptorPool = VK_NULL_HANDLE;
    coreFrame.autoExposureDescriptorSet = VK_NULL_HANDLE;
    coreFrame.exposureDescriptorSet = VK_NULL_HANDLE;
    coreFrame.developDescriptorSet = VK_NULL_HANDLE;
    coreFrame.finalDescriptorSet = VK_NULL_HANDLE;
    coreFrame.halationDescriptorSets.fill(VK_NULL_HANDLE);
    coreFrame.diffusionDescriptorSets.fill(VK_NULL_HANDLE);
    coreFrame.printDiffusionDescriptorSets.fill(VK_NULL_HANDLE);
    coreFrame.dirDescriptorSets.fill(VK_NULL_HANDLE);
    coreFrame.scannerPostDescriptorSets.fill(VK_NULL_HANDLE);
    coreFrame.grainDescriptorSets.fill(VK_NULL_HANDLE);
  }
  if (coreFrame.formatDescriptorPool != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(device, coreFrame.formatDescriptorPool, nullptr);
    coreFrame.formatDescriptorPool = VK_NULL_HANDLE;
    coreFrame.sourceFormatDescriptorSet = VK_NULL_HANDLE;
    coreFrame.destinationFormatDescriptorSet = VK_NULL_HANDLE;
  }
  if (coreFrame.imageFormatDescriptorPool != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(device, coreFrame.imageFormatDescriptorPool, nullptr);
    coreFrame.imageFormatDescriptorPool = VK_NULL_HANDLE;
    coreFrame.sourceImageFormatDescriptorSet = VK_NULL_HANDLE;
    coreFrame.destinationImageFormatDescriptorSet = VK_NULL_HANDLE;
    coreFrame.autoExposureImageDescriptorSet = VK_NULL_HANDLE;
    coreFrame.sampleImageDescriptorSet = VK_NULL_HANDLE;
  }
  destroyScratchBuffer(coreFrame.dirCorrectedDensityCurves);
  destroyScratchBuffer(coreFrame.dirFloats);
  destroyScratchBuffer(coreFrame.printDiffusionComponents);
  destroyScratchBuffer(coreFrame.printDiffusionInfo);
  destroyScratchBuffer(coreFrame.cameraDiffusionComponents);
  destroyScratchBuffer(coreFrame.diffusionRegions);
  destroyScratchBuffer(coreFrame.cameraDiffusionInfo);
  destroyScratchBuffer(coreFrame.frameInts);
  destroyScratchBuffer(coreFrame.frameFloats);
  destroyScratchBuffer(coreFrame.filteredEnlargerResponse);
  destroyScratchBuffer(coreFrame.tiledHalationBoostInfo);
  destroyScratchBuffer(coreFrame.halationPreviewSource);
  destroyScratchBuffer(coreFrame.halationBoostInfoReadback);
  destroyScratchBuffer(coreFrame.halationBoostInfo);
  destroyScratchBuffer(coreFrame.halationBoostChunks);
  destroyScratchBuffer(coreFrame.destinationHalfStaging);
  for (ScratchBuffer &scratch : coreFrame.phaseScratch) {
    destroyScratchBuffer(scratch);
  }
  coreFrame.sourceAliasedForGrain = false;
  coreFrame.filmRawAliasedForGrain = false;
  destroyScratchBuffer(coreFrame.destinationStaging);
  destroyScratchBuffer(coreFrame.destinationHalf);
  destroyScratchBuffer(coreFrame.destination);
  destroyScratchBuffer(coreFrame.filmDensity);
  destroyScratchBuffer(coreFrame.filmRaw);
  destroyScratchBuffer(coreFrame.autoExposureResult);
  destroyScratchBuffer(coreFrame.autoExposureLuminance);
  destroyScratchBuffer(coreFrame.autoExposureSource);
  destroyScratchBuffer(coreFrame.source);
  destroyScratchBuffer(coreFrame.sourceStaging);
}

void VulkanRenderer::Impl::releaseTransientResources() {
  std::lock_guard<std::recursive_mutex> lock(renderMutex);
  releaseTransientResourcesNoLock();
}

void VulkanRenderer::Impl::releaseTransientResourcesNoLock() {
  if (device == VK_NULL_HANDLE) {
    return;
  }
  const VkResult result = finishSubmittedWork(true);
  if (result != VK_SUCCESS && result != VK_ERROR_DEVICE_LOST) return;
  destroyCoreFrameResources();
  destroyCopyFrameResources();
  refreshTransientBudgetEntry();
  updateSharedDiagnostics();
}

VkResult VulkanRenderer::Impl::finishSubmittedWork(bool wait) {
  for (const auto &[fence, submitted] : {
         std::pair{coreFrame.fence, &coreFrame.submitted},
         std::pair{copyFrame.fence, &copyFrame.submitted}}) {
    if (!*submitted) continue;
    const VkResult result = wait
      ? vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX)
      : vkGetFenceStatus(device, fence);
    if (result == VK_SUCCESS || result == VK_ERROR_DEVICE_LOST) *submitted = false;
    if (result != VK_SUCCESS) {
      if (wait) lastError = vkError("Waiting for spektrafilm GPU work", result);
      return result;
    }
  }
  reportFrameSamples();
  return VK_SUCCESS;
}

void VulkanRenderer::Impl::reportFrameSamples() {
  auto &samples = coreFrame.samples;
  if (!samples.ready) return;
  samples.ready = false;
#if defined(__ANDROID__)
  if (!invalidateMappedScratchBuffer(samples.readback, FrameSamples::kBytes, "frame samples")) {
    recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraSamples", "Readback failed: %s", lastError.c_str());
    return;
  }
  const auto *values = static_cast<const float *>(samples.readback.mapped);
  std::ostringstream message;
  message << "renderer=" << this << " sequence=" << samples.sequence
          << " preview=" << samples.preview << " size=" << samples.width << 'x' << samples.height
          << " film=" << samples.params.film << " paper=" << samples.params.paper
          << " auto_exposure=" << samples.params.autoExposure
          << " film_ev=" << samples.params.filmExposureEv << " print_ev=" << samples.params.printExposureEv;
  constexpr std::array<const char *, 6> names{"input", "film_raw", "develop_input", "film_density", "output", "image"};
  bool invalid = false;
  for (uint32_t stage = 0; stage < names.size(); ++stage) {
    if ((samples.stages & (1u << stage)) == 0u) continue;
    const float *pixels = values + stage * FrameSamples::kCount * 4u;
    float minimum = std::numeric_limits<float>::infinity();
    float maximum = -std::numeric_limits<float>::infinity();
    double sum = 0;
    uint32_t finite = 0, nonfinite = 0, zeroAlpha = 0;
    for (uint32_t index = 0; index < FrameSamples::kCount; ++index) {
      if (pixels[index * 4u + 3u] == 0.0f) ++zeroAlpha;
      for (uint32_t channel = 0; channel < 3u; ++channel) {
        const float value = pixels[index * 4u + channel];
        if (!std::isfinite(value)) { ++nonfinite; continue; }
        minimum = std::min(minimum, value);
        maximum = std::max(maximum, value);
        sum += value;
        ++finite;
      }
    }
    invalid |= nonfinite != 0u;
    message << ' ' << names[stage] << "={min=" << minimum << ",max=" << maximum
            << ",mean=" << (finite ? sum / finite : 0.0) << ",nonfinite=" << nonfinite
            << ",zero_alpha=" << zeroAlpha << '}';
  }
  const float *metadata = values + FrameSamples::kMetadataOffset / sizeof(float);
  if (samples.params.autoExposure) message << " metered_ev=" << metadata[0];
  if (samples.printConstants) {
    message << " print_scale=" << metadata[4] << ',' << metadata[5] << ',' << metadata[6];
#if !defined(NDEBUG)
    const auto trace = [&](const char *name, uint32_t first, uint32_t count) {
      message << ' ' << name << '=';
      for (uint32_t index = 0; index < count; ++index) {
        if (index) message << ',';
        message << metadata[4u + first + index - 91u];
      }
    };
    trace("constants_progress", 105u, 4u);
    trace("constants_inputs", 109u, 6u);
    trace("print_rect", 115u, 4u);
    trace("print_progress", 119u, 7u);
    trace("print_pixel", 126u, 4u);
    trace("print_operation", 130u, 1u);
    trace("binding_probe", 132u, 9u);
    trace("print_single_group", 144u, 14u);
#endif
  }
  recordNativeDiagnostic(invalid ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "SpektraSamples", "%s", message.str().c_str());
#if !defined(NDEBUG)
  if (samples.printConstants) {
    const float* probes = values + FrameSamples::kProbeOffset / sizeof(float);
    std::ostringstream access;
    access << "renderer=" << this << " sequence=" << samples.sequence << " preview=" << samples.preview;
    constexpr std::array<const char*, 3> probeNames{"before", "constants", "after"};
    uint32_t completedStages = 0;
    for (uint32_t stage = 0; stage < probeNames.size(); ++stage) {
      if (probes[stage * 16u] == static_cast<float>(stage + 1u)) completedStages |= 1u << stage;
      access << ' ' << probeNames[stage] << '=';
      for (uint32_t index = 0; index < 16u; ++index) {
        if (index) access << ',';
        access << probes[stage * 16u + index];
      }
    }
    const auto* transfer = reinterpret_cast<const std::byte*>(values) + FrameSamples::kTransferOffset;
    float gamma = 0;
    std::array<uint32_t, 3> integers{};
    std::memcpy(&gamma, transfer, sizeof(gamma));
    std::memcpy(integers.data(), transfer + sizeof(gamma), sizeof(integers));
    access << " transfer_params=" << gamma << ',' << integers[0] << ',' << integers[1] << ',' << integers[2];
    access << " completed_stages=" << completedStages;
    const float* after = probes + 2u * 16u;
    samples.printExecutionVerified = completedStages == 7u && after[12] == 1.0f &&
      after[13] == 1.0f && after[14] == 1.0f;
    access << " print_execution_verified=" << samples.printExecutionVerified;
    recordNativeDiagnostic(samples.printExecutionVerified ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
      "SpektraAccessProbe", "%s", access.str().c_str());
  }
#endif
#endif
}

void VulkanRenderer::Impl::recordFrameSamples(VkCommandBuffer commands, VkBuffer source,
                                              uint32_t stage, uint32_t width, uint32_t height) {
  auto &samples = coreFrame.samples;
  VkMemoryBarrier before{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  before.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  before.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       0, 1, &before, 0, nullptr, 0, nullptr);
  std::array<VkBufferCopy, FrameSamples::kCount> copies{};
  for (uint32_t index = 0; index < copies.size(); ++index) {
    const uint32_t x = (index % FrameSamples::kGrid) * width / FrameSamples::kGrid;
    const uint32_t y = (index / FrameSamples::kGrid) * height / FrameSamples::kGrid;
    copies[index] = {(VkDeviceSize{y} * width + x) * 4u * sizeof(float),
                     stage * FrameSamples::kStageBytes + index * 4u * sizeof(float), 4u * sizeof(float)};
  }
  vkCmdCopyBuffer(commands, source, samples.readback.buffer, static_cast<uint32_t>(copies.size()), copies.data());
  VkMemoryBarrier after{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  after.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  after.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       0, 1, &after, 0, nullptr, 0, nullptr);
  samples.stages |= 1u << stage;
}

bool VulkanRenderer::Impl::renderCopyValidation(
  const ImageView &source,
  const MutableImageView &destination,
  const RenderWindow &window,
  double
) {
  std::lock_guard<std::recursive_mutex> lock(renderMutex);
  if (finishSubmittedWork(true) != VK_SUCCESS) return false;
  releaseCoreImageViews();
  diagnostics = {};
  lastError.clear();
  updateSharedDiagnostics();

  const PerfClock::time_point setupStart = PerfClock::now();
  const int32_t width = window.x2 - window.x1;
  const int32_t height = window.y2 - window.y1;
  if (width <= 0 || height <= 0) {
    return true;
  }
  if (!isSupportedRgba(source, destination)) {
    lastError = "Only RGBA 16-bit half and 32-bit float images are supported by the Windows Vulkan path.";
    return false;
  }
  if (source.bytesPerComponent != destination.bytesPerComponent) {
    lastError = "The Windows Vulkan copy-validation path requires matching source and destination pixel depths.";
    return false;
  }

  const int32_t pixelBytes = source.components * source.bytesPerComponent;
  const VkDeviceSize byteCount = static_cast<VkDeviceSize>(
    static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * static_cast<uint64_t>(pixelBytes)
  );
  const VkDeviceSize paddedByteCount = (byteCount + 3u) & ~VkDeviceSize(3u);
  if (paddedByteCount == 0) {
    return true;
  }

  if (byteCount > static_cast<VkDeviceSize>(std::numeric_limits<uint32_t>::max())) {
    lastError = "The Vulkan copy-validation render window is too large.";
    return false;
  }
  const uint32_t wordCount = static_cast<uint32_t>(paddedByteCount / sizeof(uint32_t));
  if (static_cast<VkDeviceSize>(wordCount) * sizeof(uint32_t) != paddedByteCount) {
    lastError = "The Vulkan copy-validation render window is too large.";
    return false;
  }

  diagnostics.uploadBytes = static_cast<uint64_t>(byteCount);
  diagnostics.threadgroupMode = "vulkan-256x1";
  diagnostics.passTimingMode = "cpu-fence";
  diagnostics.passCount = 1;
  diagnostics.privateScratchEnabled = false;

  if (!ensureCopyFrameResources() ||
      !ensureUploadScratchBuffer(copyFrame.source, paddedByteCount, "copy source") ||
      !ensureReadbackScratchBuffer(copyFrame.destination, paddedByteCount, "copy destination")) {
    return false;
  }

  if (!copyFrame.source.mapped) {
    lastError = "Vulkan copy source buffer is not mapped.";
    return false;
  }
  const PerfClock::time_point sourceCopyStart = PerfClock::now();
  if (!copyWindowToMappedBytes(source, window, width, height, copyFrame.source.mapped, static_cast<size_t>(byteCount))) {
    lastError = "The requested render window does not fit inside the Vulkan source image view.";
    return false;
  }
  if (paddedByteCount > byteCount) {
    std::memset(static_cast<uint8_t *>(copyFrame.source.mapped) + byteCount, 0, static_cast<size_t>(paddedByteCount - byteCount));
  }
  if (!flushMappedScratchBuffer(copyFrame.source, paddedByteCount, "copy source")) {
    return false;
  }
  const PerfClock::time_point sourceCopyEnd = PerfClock::now();
  diagnostics.sourceCopyMs = elapsedMilliseconds(sourceCopyStart, sourceCopyEnd);
  VkResult result = VK_SUCCESS;

  VkDescriptorBufferInfo sourceBufferInfo{};
  sourceBufferInfo.buffer = copyFrame.source.buffer;
  sourceBufferInfo.offset = 0;
  sourceBufferInfo.range = paddedByteCount;
  VkDescriptorBufferInfo destinationBufferInfo{};
  destinationBufferInfo.buffer = copyFrame.destination.buffer;
  destinationBufferInfo.offset = 0;
  destinationBufferInfo.range = paddedByteCount;

  VkWriteDescriptorSet writes[2]{};
  writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
  writes[0].dstSet = copyFrame.descriptorSet;
  writes[0].dstBinding = 0;
  writes[0].descriptorCount = 1;
  writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
  writes[0].pBufferInfo = &sourceBufferInfo;
  writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
  writes[1].dstSet = copyFrame.descriptorSet;
  writes[1].dstBinding = 1;
  writes[1].descriptorCount = 1;
  writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
  writes[1].pBufferInfo = &destinationBufferInfo;
  if (!updateStorageDescriptors(2u, writes)) return false;

  VkCommandBuffer commandBuffer = copyFrame.commandBuffer;
  result = vkResetCommandBuffer(commandBuffer, 0);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkResetCommandBuffer", result);
    return false;
  }

  VkCommandBufferBeginInfo beginInfo{};
  beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkBeginCommandBuffer", result);
    return false;
  }

  VkMemoryBarrier hostInputBarrier{};
  hostInputBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  hostInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
  hostInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  vkCmdPipelineBarrier(
    commandBuffer,
    VK_PIPELINE_STAGE_HOST_BIT,
    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
    0,
    1,
    &hostInputBarrier,
    0,
    nullptr,
    0,
    nullptr
  );

  const uint32_t pushConstants[2] = {wordCount, static_cast<uint32_t>(byteCount)};
  vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, copyPipeline);
  vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, copyPipelineLayout, 0, 1, &copyFrame.descriptorSet, 0, nullptr);
  vkCmdPushConstants(commandBuffer, copyPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), pushConstants);
  const auto copyGroups = linearDispatchGroups(wordCount, physicalDeviceProperties.limits.maxComputeWorkGroupCount[0]);
  vkCmdDispatch(commandBuffer, copyGroups[0], copyGroups[1], 1);

  VkMemoryBarrier memoryBarrier{};
  memoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  memoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  memoryBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  vkCmdPipelineBarrier(
    commandBuffer,
    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
    VK_PIPELINE_STAGE_HOST_BIT,
    0,
    1,
    &memoryBarrier,
    0,
    nullptr,
    0,
    nullptr
  );

  result = vkEndCommandBuffer(commandBuffer);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkEndCommandBuffer", result);
    return false;
  }

  VkFence fence = copyFrame.fence;
  result = vkResetFences(device, 1, &fence);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkResetFences", result);
    return false;
  }

  VkSubmitInfo submitInfo{};
  submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  submitInfo.commandBufferCount = 1;
  submitInfo.pCommandBuffers = &commandBuffer;
  const PerfClock::time_point commandStart = PerfClock::now();
  diagnostics.cpuSetupMs = std::max(0.0, elapsedMilliseconds(setupStart, commandStart) - diagnostics.sourceCopyMs);
  result = backend ? backend->submit(queueIndex, submitInfo, fence) : VK_ERROR_INITIALIZATION_FAILED;
  if (result != VK_SUCCESS) {
    lastError = vkError("vkQueueSubmit", result);
    return false;
  }
  copyFrame.submitted = true;
  if (finishSubmittedWork(true) != VK_SUCCESS) return false;
  const PerfClock::time_point commandEnd = PerfClock::now();
  diagnostics.commandBufferMs = elapsedMilliseconds(commandStart, commandEnd);

  const PerfClock::time_point outputCopyStart = PerfClock::now();
  if (!copyFrame.destination.mapped) {
    lastError = "Vulkan copy destination buffer is not mapped.";
    return false;
  }
  if (!invalidateMappedScratchBuffer(copyFrame.destination, paddedByteCount, "copy destination")) {
    return false;
  }
  if (!copyMappedBytesToWindow(copyFrame.destination.mapped, static_cast<size_t>(byteCount), destination, window, width, height)) {
    lastError = "The requested render window does not fit inside the Vulkan destination image view.";
    return false;
  }
  const PerfClock::time_point outputCopyEnd = PerfClock::now();
  diagnostics.outputCopyMs = elapsedMilliseconds(outputCopyStart, outputCopyEnd);

  RendererPassDiagnostics pass{};
  pass.name = "copyValidation";
  pass.width = static_cast<uint32_t>(width);
  pass.height = static_cast<uint32_t>(height);
  pass.threadgroupWidth = 256;
  pass.threadgroupHeight = 1;
  pass.estimatedBytes = static_cast<uint64_t>(byteCount) * 2u;
  diagnostics.passes.push_back(pass);

  refreshTransientBudgetEntry();
  enforceTransientBudget();
  updateSharedDiagnostics();
  return true;
}

bool VulkanRenderer::Impl::renderCoreBootstrap(
  const ImageView &source,
  const MutableImageView &destination,
  const RenderWindow &window,
  const RenderParams &params,
  double time
) {
  std::lock_guard<std::recursive_mutex> lock(renderMutex);
  double previousGpuMs = 0.0;
  bool previousGpuAvailable = false;
  if (finishSubmittedWork(true) != VK_SUCCESS) return false;
  requestedDescriptorWrites = appliedDescriptorWrites = 0;
  reuseSetup = true;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char setupProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.reuse_setup", setupProperty);
  reuseSetup = setupProperty[0] != '0';
#endif
  if (activeExternalImageContext.views.generation != externalImageGeneration) {
    descriptorContents.clear();
    externalImageGeneration = activeExternalImageContext.views.generation;
  }
  if (diffusionMilestone != VK_NULL_HANDLE && coreFrame.commandBuffer != VK_NULL_HANDLE) {
    const auto reset = vkResetCommandBuffer(coreFrame.commandBuffer, 0);
    if (reset != VK_SUCCESS) {
      lastError = vkError("vkResetCommandBuffer", reset);
      return false;
    }
  }
  if (coreFrame.timingPending) {
    uint64_t timestamps[CoreFrameResources::kTimingQueryCount]{};
    if (vkGetQueryPoolResults(device, coreFrame.timingPool, 0, coreFrame.timingQueryCount, sizeof(timestamps),
          timestamps, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
      previousGpuMs = static_cast<double>((timestamps[1] - timestamps[0]) & coreFrame.timingMask) *
        physicalDeviceProperties.limits.timestampPeriod / 1e6;
      previousGpuAvailable = true;
#if defined(__ANDROID__)
      if (coreFrame.timingProfile) {
        const auto interval = [&](uint32_t begin, uint32_t end) {
          return static_cast<double>((timestamps[end] - timestamps[begin]) & coreFrame.timingMask) *
            physicalDeviceProperties.limits.timestampPeriod / 1e6;
        };
        __android_log_print(ANDROID_LOG_INFO, "SpektraPreviewTiming",
          "source=%.3f film_exposure=%.3f film_curve=%.3f effects=%.3f print_setup=%.3f print_pixels=%.3f output=%.3f total=%.3f lut_generation=%llu",
          interval(0, 2), interval(2, 7), interval(7, 3), interval(3, 4), interval(4, 5), interval(5, 6), interval(6, 1),
          previousGpuMs, static_cast<unsigned long long>(coreFrame.colorLutGeneration));
        for (uint32_t i = 0; i < coreFrame.grainTimingCount; ++i) {
          __android_log_print(ANDROID_LOG_INFO, "SpektraGrainTiming", "operation=%u gpu_ms=%.3f",
            coreFrame.grainTimingOperations[i], interval(8u + i * 2u, 9u + i * 2u));
        }
      }
#endif
    }
    if (coreFrame.timingAdaptive) {
      const double timedPixels = static_cast<double>(coreFrame.previewScheduleWidth) * coreFrame.previewScheduleHeight;
      const double millisecondsPerMegapixel = previousGpuMs * 1.0e6 / std::max(1.0, timedPixels);
#if defined(__ANDROID__) && !defined(NDEBUG)
      const bool wasDirect = coreFrame.previewDirectSubmission;
#endif
      if (!previousGpuAvailable || !std::isfinite(previousGpuMs) || previousGpuMs <= 0.0 || millisecondsPerMegapixel > 120.0) {
        coreFrame.previewDirectSubmission = false;
        coreFrame.previewFastFrames = 0;
      } else if (coreFrame.timingBounded) {
        // Compare throughput across resolutions, with hysteresis for clock and scene changes.
        coreFrame.previewFastFrames = millisecondsPerMegapixel < 80.0 ? std::min(coreFrame.previewFastFrames + 1u, 3u) : 0u;
        if (coreFrame.previewFastFrames >= 3u) coreFrame.previewDirectSubmission = true;
      }
#if defined(__ANDROID__) && !defined(NDEBUG)
      if (wasDirect != coreFrame.previewDirectSubmission) {
        recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraPreviewSchedule",
          "renderer=%p size=%ux%u mode=%s gpu_frame_ms=%.3f gpu_ms_per_mpix=%.3f",
          static_cast<void *>(this), coreFrame.previewScheduleWidth, coreFrame.previewScheduleHeight,
          coreFrame.previewDirectSubmission ? "direct" : "bounded", previousGpuMs, millisecondsPerMegapixel);
      }
#endif
    }
    coreFrame.timingPending = false;
  }
  releaseCoreImageViews();
  diagnostics = {};
  diagnostics.renderSerialized = true;
  diagnostics.gpuCommandBufferMs = previousGpuMs;
  diagnostics.passGpuTimingAvailable = previousGpuAvailable;
  lastError.clear();
  updateSharedDiagnostics();

  const PerfClock::time_point setupStart = PerfClock::now();
  const int32_t width = window.x2 - window.x1;
  const int32_t height = window.y2 - window.y1;
  const bool externalImages = activeExternalImageContext.enabled;
  bool sampleFrame = false;
#if defined(__ANDROID__)
  bool sampleDevice = physicalDeviceProperties.vendorID == 0x1010u;
#if !defined(NDEBUG)
  sampleDevice = true;
#endif
  // Sample consecutive frames to cover both preview buffers without serializing every frame.
  // Video effects need the fused colour pass, which sampled frames cannot use.
  ++sampleSequence;
  sampleFrame = sampleDevice && externalImages && !activeTileContext.enabled && !videoEffectsRequested &&
    (!previewOptimizations || sampleSequence <= 4u || sampleSequence % 120u < 2u ||
     coreFrame.samples.readback.buffer == VK_NULL_HANDLE ||
     coreFrame.samples.width != static_cast<uint32_t>(width) || coreFrame.samples.height != static_cast<uint32_t>(height));
#endif
  if (width <= 0 || height <= 0) {
    return true;
  }
  if (!isSupportedRgba(source, destination)) {
    lastError = "Only RGBA 16-bit half and 32-bit float images are supported by the Windows Vulkan path.";
    return false;
  }
  if (externalImages &&
      (activeExternalImageContext.sourceImage == VK_NULL_HANDLE ||
       activeExternalImageContext.destinationImage == VK_NULL_HANDLE)) {
    lastError = "The external Vulkan source or destination image is invalid.";
    return false;
  }
  const uint64_t pixelCount = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  if (pixelCount > static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())) {
    lastError = "The Vulkan core render window is too large.";
    return false;
  }
  const uint64_t byteCount64 = pixelCount * 4u * sizeof(float);
  const uint64_t halfByteCount64 = pixelCount * 4u * sizeof(uint16_t);
  if (pixelCount == 0 || byteCount64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
    lastError = "The Vulkan core render window is too large.";
    return false;
  }
  const uint64_t grainLayerByteCount64 = pixelCount * 9u * sizeof(float);
  if (grainLayerByteCount64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
    lastError = "The Vulkan grain render window is too large.";
    return false;
  }
  const VkDeviceSize byteCount = static_cast<VkDeviceSize>(byteCount64);
  const VkDeviceSize halfByteCount = static_cast<VkDeviceSize>(halfByteCount64);
  const VkDeviceSize grainLayerByteCount = static_cast<VkDeviceSize>(grainLayerByteCount64);
  const uint32_t diffusionGroupSize = writingDiffusionMilestone ? 1u : static_cast<uint32_t>(std::max<VkDeviceSize>(1u,
    std::min<VkDeviceSize>(this->diffusionGroupSize,
      storageBufferCapacity() / byteCount)));
  const bool destinationIsHalf = destination.bytesPerComponent == 2;
  const uint32_t coordinateWidth =
    activeTileContext.enabled ? std::max(activeTileContext.fullWidth, 1u) : static_cast<uint32_t>(width);
  const uint32_t coordinateHeight =
    activeTileContext.enabled ? std::max(activeTileContext.fullHeight, 1u) : static_cast<uint32_t>(height);
  const bool fullFrameSourcePath = activeTileContext.enabled && activeTileContext.fullFrameSource;
  const uint64_t sourcePixelCount = fullFrameSourcePath
    ? static_cast<uint64_t>(coordinateWidth) * static_cast<uint64_t>(coordinateHeight)
    : pixelCount;
  const uint64_t sourceByteCount64 = sourcePixelCount * 4u * sizeof(float);
  if (sourcePixelCount == 0u || sourceByteCount64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
    lastError = "The Vulkan source render window is too large.";
    return false;
  }
  const VkDeviceSize sourceByteCount = static_cast<VkDeviceSize>(sourceByteCount64);
  int32_t autoExposurePreviewWidth = static_cast<int32_t>(coordinateWidth);
  int32_t autoExposurePreviewHeight = static_cast<int32_t>(coordinateHeight);
  autoExposurePreviewShape(
    static_cast<int32_t>(coordinateWidth),
    static_cast<int32_t>(coordinateHeight),
    autoExposurePreviewWidth,
    autoExposurePreviewHeight
  );
  const VkDeviceSize autoExposureLuminanceByteCount =
    static_cast<VkDeviceSize>(autoExposurePreviewWidth) *
    static_cast<VkDeviceSize>(autoExposurePreviewHeight) *
    sizeof(float);
  const uint32_t halationBoostMaxChunkCount = static_cast<uint32_t>(
    (pixelCount + kHalationBoostMaxChunkPixels - 1u) / kHalationBoostMaxChunkPixels
  );
  const VkDeviceSize halationBoostChunkByteCount = static_cast<VkDeviceSize>(
    std::max<uint64_t>(halationBoostMaxChunkCount, 1u) * 4u * sizeof(float)
  );
  const float filmPixelSizeUm = filmFormatLongEdgeMm(params.filmFormat) * 1000.0f /
    static_cast<float>(std::max(coordinateWidth, coordinateHeight)) /
    resolvedEnlargerScale(params);
  const bool finalOutput = params.renderOutput == RenderOutputMode::FinalPreview;
  const bool rcmOutput = finalOutput && params.outputRole == OutputRole::Rcm;
  const bool finalPrintSimulation =
    finalOutput && params.process == ProcessMode::PrintSimulation;
  const bool finalScanNegative =
    finalOutput && params.process == ProcessMode::ScanNegative;
  const bool finalProcessNegative =
    finalOutput && params.process == ProcessMode::ProcessNegative;
  const bool printLikeFinalPath = finalPrintSimulation || finalProcessNegative;
  const bool finalPostProcessPath = finalPrintSimulation || finalScanNegative || finalProcessNegative;
  const bool printScanPassEnabled = envFlagEnabledOrDefault(
    "SPEKTRAFILM_VULKAN_PRINT_SCAN_PASS",
    finalPostProcessPath
  );
  const bool scannerPostFeatureEnabled =
    envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_SCANNER_POST_PASS", true);
  const bool printGlarePath =
    scannerPostFeatureEnabled &&
    printScanPassEnabled &&
    printLikeFinalPath &&
    !rcmOutput &&
    params.scannerEnabled &&
    params.glarePercent > 0.0f;
  const bool printGlareBlurPath = printGlarePath && params.glareBlur > 0.0f;
  const bool scannerBlurPath =
    scannerPostFeatureEnabled &&
    printScanPassEnabled &&
    finalPostProcessPath &&
    !rcmOutput &&
    params.scannerEnabled &&
    params.scannerMtf50LpMm > 0.0f;
  const bool scannerUnsharpPath =
    scannerPostFeatureEnabled &&
    printScanPassEnabled &&
    finalPostProcessPath &&
    !rcmOutput &&
    params.scannerEnabled &&
    params.scannerUnsharpRadiusUm > 0.0f &&
    params.scannerUnsharpAmount > 0.0f;
  const bool scannerPostPath = printGlarePath || scannerBlurPath || scannerUnsharpPath;
  const bool grainFeatureEnabled =
    envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_GRAIN_PASS", true) && params.grainEnabled && !finalProcessNegative;
  const bool grainSynthesisRequested =
    grainFeatureEnabled &&
    params.grainModel == GrainModel::GrainSynthesis;
  const bool previewGrainPath =
    grainFeatureEnabled &&
    params.grainModel == GrainModel::Preview && params.grainAmount > 0.0f;
  const bool productionGrainPath =
    grainFeatureEnabled &&
    params.grainModel == GrainModel::Production;
  const bool grainSynthesisPath = grainSynthesisRequested;
  const bool grainPath = previewGrainPath || productionGrainPath || grainSynthesisPath;
  const bool diffusionFeatureEnabled =
    envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_DIFFUSION_PASS", true);

  VulkanDiffusionInfo cameraDiffusionInfo{};
  std::vector<VulkanDiffusionComponent> cameraUncachedComponents;
  if (diffusionFeatureEnabled &&
      !finalProcessNegative &&
      params.cameraDiffusionEnabled &&
      params.cameraDiffusionStrength > 0.0f &&
      params.cameraDiffusionSpatialScale > 0.0f) {
    const auto settings = diffusionSettings(params, false);
    if (previewOptimizations) {
      cameraDiffusionCache.prepare(settings, filmPixelSizeUm, diffusionClusterSigmaRatio);
      cameraDiffusionInfo = cameraDiffusionCache.info;
    } else {
      cameraUncachedComponents = makeDiffusionComponents(settings, filmPixelSizeUm, cameraDiffusionInfo, diffusionClusterSigmaRatio);
    }
  }
  const auto &cameraDiffusionComponents = previewOptimizations && cameraDiffusionInfo.componentCount > 0u
    ? cameraDiffusionCache.components : cameraUncachedComponents;
  const bool cameraDiffusionPath =
    cameraDiffusionInfo.componentCount > 0u &&
    !cameraDiffusionComponents.empty();

  VulkanDiffusionInfo printDiffusionInfo{};
  std::vector<VulkanDiffusionComponent> printUncachedComponents;
  if (diffusionFeatureEnabled &&
      printScanPassEnabled &&
      printLikeFinalPath &&
      params.printDiffusionEnabled &&
      params.printDiffusionStrength > 0.0f &&
      params.printDiffusionSpatialScale > 0.0f) {
    const auto settings = diffusionSettings(params, true);
    if (previewOptimizations) {
      printDiffusionCache.prepare(settings, filmPixelSizeUm, diffusionClusterSigmaRatio);
      printDiffusionInfo = printDiffusionCache.info;
    } else {
      printUncachedComponents = makeDiffusionComponents(settings, filmPixelSizeUm, printDiffusionInfo, diffusionClusterSigmaRatio);
    }
  }
  const auto &printDiffusionComponents = previewOptimizations && printDiffusionInfo.componentCount > 0u
    ? printDiffusionCache.components : printUncachedComponents;
  const bool printDiffusionPath =
    printDiffusionInfo.componentCount > 0u &&
    !printDiffusionComponents.empty();
  auto diffusionScale = [&](float sigma) {
    return previewOptimizations ? preview_effects::diffusionScale(sigma)
      : diffusionDownsampleScaleForSigma(blurDownsample, sigma);
  };
  auto needsDiffusionDownsample = [&](const std::vector<VulkanDiffusionComponent> &components) {
    return std::any_of(components.begin(), components.end(), [&](const auto &component) {
      return diffusionScale(component.sigmaPx) > 1u;
    });
  };
  const bool diffusionDownsamplePath = needsDiffusionDownsample(cameraDiffusionComponents) ||
    needsDiffusionDownsample(printDiffusionComponents);
  const bool dirFeatureEnabled = envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_DIR_PASS", true);
  const bool dirEnabled = dirFeatureEnabled && !finalProcessNegative && params.dirCouplersAmount > 0.0f;
  const bool dirBlurPath = dirEnabled && params.dirCouplersDiffusionUm > 0.0f;
  const bool dirTailPath =
    dirBlurPath &&
    params.dirCouplersDiffusionTailUm > 0.0f &&
    params.dirCouplersDiffusionTailWeight > 0.0f;
  // The coupler tails are wide Gaussians. Blur them on a reduced grid, using the same scale
  // rule as diffusion for the narrowest tail, and upsample their sum once.
  bool dirTailDownsampleEnabled = true;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char dirTailDownsampleProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.dir_tail_downsample", dirTailDownsampleProperty);
  dirTailDownsampleEnabled = dirTailDownsampleProperty[0] != '0';
#endif
  uint32_t dirTailScale = dirTailPath && dirTailDownsampleEnabled
    ? diffusionDownsampleScaleForSigma("auto",
        std::max(params.dirCouplersDiffusionTailUm, 0.0f) * 0.5360f / std::max(filmPixelSizeUm, 1.0e-6f))
    : 1u;
  const uint32_t dirTailLocalWidth = static_cast<uint32_t>(width);
  const uint32_t dirTailLocalHeight = static_cast<uint32_t>(height);
  const uint32_t dirTailTileOriginX = activeTileContext.enabled ? activeTileContext.tileOriginX : 0u;
  const uint32_t dirTailTileOriginY = activeTileContext.enabled ? activeTileContext.tileOriginY : 0u;
  const uint32_t dirTailReducedWidth = alignedReducedDimension(dirTailLocalWidth, dirTailTileOriginX,
    std::max(coordinateWidth, dirTailLocalWidth), dirTailScale);
  const uint32_t dirTailReducedHeight = alignedReducedDimension(dirTailLocalHeight, dirTailTileOriginY,
    std::max(coordinateHeight, dirTailLocalHeight), dirTailScale);
  const VkDeviceSize pixelStorageByteCount = byteCount;
  DiffusionRegions diffusionRegions{};
  if (writingDiffusionMilestone) {
    std::array<uint32_t, 4> radii{};
    for (const auto &component : cameraDiffusionComponents) {
      const uint32_t scale = diffusionScale(component.sigmaPx);
      const uint32_t level = scale == 1u ? 0u : scale == 2u ? 1u : scale == 4u ? 2u : 3u;
      radii[level] = std::max(radii[level], static_cast<uint32_t>(
        std::min(std::ceil(3.0f * component.sigmaPx / scale), 256.0f)));
    }
    diffusionRegions = makeDiffusionRegions(width, height, coordinateWidth, coordinateHeight,
      activeTileContext.tileOriginX, activeTileContext.tileOriginY,
      {activeTileContext.centerOriginX, activeTileContext.centerOriginY,
       activeTileContext.centerWidth, activeTileContext.centerHeight}, radii);
  }
  const VkDeviceSize diffusionOutputByteCount = writingDiffusionMilestone
    ? diffusionRegions.output.pixels() * 16u : pixelStorageByteCount;
  const VkDeviceSize diffusionTempByteCount =
    (writingDiffusionMilestone ? diffusionRegions.temporary.pixels() * 16u : pixelStorageByteCount) *
      static_cast<VkDeviceSize>(std::max(diffusionGroupSize, 1u));
  const uint32_t maxDiffusionDownsampleWidth = alignedReducedDimension(
    static_cast<uint32_t>(width),
    activeTileContext.enabled ? activeTileContext.tileOriginX : 0u,
    coordinateWidth,
    2u
  );
  const uint32_t maxDiffusionDownsampleHeight = alignedReducedDimension(
    static_cast<uint32_t>(height),
    activeTileContext.enabled ? activeTileContext.tileOriginY : 0u,
    coordinateHeight,
    2u
  );
  uint64_t diffusionDownsamplePixelCount =
    static_cast<uint64_t>(maxDiffusionDownsampleWidth) * static_cast<uint64_t>(maxDiffusionDownsampleHeight);
  if (writingDiffusionMilestone) {
    diffusionDownsamplePixelCount = 1u;
    for (const auto &component : cameraDiffusionComponents) {
      const uint32_t scale = diffusionScale(component.sigmaPx);
      if (scale > 1u) diffusionDownsamplePixelCount = std::max(diffusionDownsamplePixelCount,
        diffusionRegions.reduced[scale == 2u ? 0u : scale == 4u ? 1u : 2u].pixels());
    }
  }
  const VkDeviceSize diffusionDownsampleByteCount =
    static_cast<VkDeviceSize>(diffusionDownsamplePixelCount * 4u * sizeof(float));
  const VkDeviceSize diffusionDownsampleGroupByteCount =
    diffusionDownsampleByteCount * static_cast<VkDeviceSize>(std::max(diffusionGroupSize, 1u));
  const bool fusedPreviewOutput = externalImages && previewOptimizations;
  const bool destinationFormatConvert = destinationIsHalf && !fusedPreviewOutput && !writingDiffusionMilestone;
  const bool halationFeatureEnabled =
    envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_HALATION_PASS", true) && params.halationEnabled && !finalProcessNegative;
  const bool halationScatterEnabled =
    halationFeatureEnabled &&
    params.scatterAmount > 0.0f &&
    params.scatterScale > 0.0f;
  const bool halationBounceEnabled =
    halationFeatureEnabled &&
    params.halationAmount > 0.0f &&
    params.halationScale > 0.0f &&
    (params.halationStrengthR > 0.0f || params.halationStrengthG > 0.0f || params.halationStrengthB > 0.0f);
  const bool halationBoostEnabled =
    halationFeatureEnabled &&
    params.halationBoostEv > 0.0f;
  const bool halationBoostMilestoneEnabled =
    halationBoostEnabled &&
    activeTileContext.enabled &&
    activeTileContext.halationBoostMilestoneEnabled;
  const bool halationBoostLocalReductionEnabled =
    halationBoostEnabled && !halationBoostMilestoneEnabled;
  const bool halationPassEnabled = halationBoostEnabled || halationScatterEnabled || halationBounceEnabled;
  const uint32_t halationPreviewScale = previewOptimizations && (halationScatterEnabled || halationBounceEnabled)
    ? preview_effects::halationScale(static_cast<uint32_t>(width), static_cast<uint32_t>(height)) : 1u;
  const uint32_t halationPreviewWidth = (static_cast<uint32_t>(width) + halationPreviewScale - 1u) / halationPreviewScale;
  const uint32_t halationPreviewHeight = (static_cast<uint32_t>(height) + halationPreviewScale - 1u) / halationPreviewScale;
  const VkDeviceSize halationPreviewBytes = static_cast<VkDeviceSize>(halationPreviewWidth) * halationPreviewHeight * 4u * sizeof(float);
  bool previewColorLut = externalImages && previewOptimizations && previewColorLutAvailable && printScanPassEnabled &&
    CoreFrameResources::kColorLutBytes <= physicalDeviceProperties.limits.maxStorageBufferRange;
  bool fourStageLut = previewColorLut && (finalPrintSimulation || finalScanNegative) &&
    params.inputColorSpace == ColorSpace::LinearRec709 && params.outputColorSpace == ColorSpace::Srgb &&
    params.outputRole == OutputRole::DisplaySdr;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char lutProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.preview_lut", lutProperty);
  previewColorLut = previewColorLut && lutProperty[0] != '0';
  fourStageLut = fourStageLut && previewColorLut && lutProperty[0] != '1';
#endif
  bool bakedDir = fourStageLut && dirEnabled && !dirBlurPath &&
    staticFilm.exposureCount >= 2u && staticFilm.exposureCount <= SPEKTRA_FOUR_STAGE_DIR_CURVE_CAPACITY;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char couplerLutProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.coupler_lut", couplerLutProperty);
  bakedDir = bakedDir && couplerLutProperty[0] != '0';
#endif
  const bool dirPath = dirEnabled && !bakedDir;
  bool sharedPrintPipeline = physicalDeviceProperties.vendorID == 0x13b5u;
  bool samplePrint = false;
#if !defined(NDEBUG)
  samplePrint = sampleFrame && printScanPassEnabled;
  sharedPrintPipeline = envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_SHARED_PRINT", sharedPrintPipeline);
#endif
  bool compactPrint = sharedPrintPipeline || previewColorLut;
#if !defined(NDEBUG)
  compactPrint = envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_COMPACT_PRINT", compactPrint);
#endif
  // Descriptor arrays are only needed when a complete buffer exceeds one descriptor's range.
  compactPrint = compactPrint && std::max(sourceByteCount, pixelStorageByteCount) <= backend->storageChunkBytes();
  bool splitPrint = compactPrint;
#if !defined(NDEBUG)
  splitPrint = compactPrint && envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_SPLIT_PRINT", true);
#endif
  const bool specializePrintOperation = (previewOptimizations || backend->requiresSpecializedPrint()) &&
    !sharedPrintPipeline && !compactPrint;
  const bool adaptivePreview = externalImages && previewColorLut && !camera_gpu::exporting && pixelCount > 1024u * 1024u;
  if (!adaptivePreview || coreFrame.previewScheduleWidth != static_cast<uint32_t>(width) ||
      coreFrame.previewScheduleHeight != static_cast<uint32_t>(height) || coreFrame.previewScheduleParams != params) {
    coreFrame.previewDirectSubmission = false;
    coreFrame.previewFastFrames = 0;
    coreFrame.previewScheduleWidth = adaptivePreview ? static_cast<uint32_t>(width) : 0u;
    coreFrame.previewScheduleHeight = adaptivePreview ? static_cast<uint32_t>(height) : 0u;
    coreFrame.previewScheduleParams = params;
#if defined(__ANDROID__) && !defined(NDEBUG)
    if (adaptivePreview) recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraPreviewSchedule",
      "renderer=%p size=%ux%u mode=bounded reason=workload_change",
      static_cast<void *>(this), static_cast<uint32_t>(width), static_cast<uint32_t>(height));
#endif
  }
  const bool boundedCompute = camera_gpu::exporting ||
    (previewOptimizations && camera_gpu::boundedPreview(pixelCount, coreFrame.previewDirectSubmission));
  const bool slicePrintWithOrigin = (sharedPrintPipeline || compactPrint) && boundedCompute;
  bool independentPrintPasses = false;
#if !defined(NDEBUG)
  independentPrintPasses = envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_INDEPENDENT_PRINT", sharedPrintPipeline);
#endif
  if (printScanPassEnabled && compactPrint &&
      (!backend->prepareCompactPrint(samplePrint, splitPrint) || !ensureCompactPrintDescriptors())) {
    if (lastError.empty()) lastError = backend->lastError;
    return false;
  }
  if (printScanPassEnabled && !compactPrint && slicePrintWithOrigin && !backend->prepareDirectPrint()) {
    lastError = backend->lastError;
    return false;
  }
  const VkPipelineLayout printLayout = compactPrint ? backend->compactPrintLayout : corePipelineLayout;
  bool textureLut = fourStageLut && previewLutTextureAvailable;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char textureLutProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.lut_texture", textureLutProperty);
  textureLut = textureLut && textureLutProperty[0] != '0';
#endif
  if (textureLut && (!ensureFourStageTexture() || !backend->prepareFourStageLut(compactPrint, true))) {
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraColorLut", "Using buffer colour tables: %s",
      lastError.empty() ? backend->lastError.c_str() : lastError.c_str());
#endif
    previewLutTextureAvailable = false;
    textureLut = false;
    lastError.clear();
  }
  const uint32_t fourStagePipelineIndex = (compactPrint ? 1u : 0u) + (textureLut ? 2u : 0u);
  if (fourStageLut && !textureLut && !backend->prepareFourStageLut(compactPrint)) {
    lastError = backend->lastError;
    return false;
  }
  if (previewColorLut && !fourStageLut && !backend->preparePreviewColorLut(compactPrint)) {
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraColorLut", "Using full preview processing: %s", backend->lastError.c_str());
#endif
    previewColorLutAvailable = false;
    previewColorLut = false;
  }
  const uint32_t printGroupWidth = (splitPrint || previewColorLut) ? 8u : 32u;
  bool previewDevelopment = fourStageLut || (previewColorLut && previewDevelopmentAvailable &&
    std::max(sourceByteCount, pixelStorageByteCount) <= backend->storageChunkBytes());
  if (previewDevelopment && !fourStageLut && !backend->preparePreviewDevelopment()) {
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraColorLut", "Using full film development: %s", backend->lastError.c_str());
#endif
    previewDevelopmentAvailable = false;
    previewDevelopment = false;
  }
  if ((printScanPassEnabled && specializePrintOperation && !backend->prepareOperationPipelines(2u)) ||
      (previewOptimizations && ((halationPassEnabled && !backend->prepareOperationPipelines(0u)) ||
      ((cameraDiffusionPath || printDiffusionPath) && !backend->prepareOperationPipelines(1u)) ||
      (fusedPreviewOutput && !backend->preparePreviewOutput())))) {
    lastError = backend->lastError;
    return false;
  }
  bool specializedGrain = previewOptimizations && grainPath && previewGrainAvailable;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char grainProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.grain_passes", grainProperty);
  specializedGrain = specializedGrain && grainProperty[0] != '0';
#endif
  if (specializedGrain && !backend->prepareOperationPipelines(3u)) {
    previewGrainAvailable = false;
    specializedGrain = false;
  }
#if defined(__ANDROID__) && !defined(NDEBUG)
  if (grainPath && (grainProperty[0] == '0' || grainProperty[0] == '1'))
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraGrain", "specialized=%d", specializedGrain);
#endif
  const float grainMicroBlurSigma = std::max(params.grainMicroStructureScale, 0.0f) / std::max(filmPixelSizeUm, 1.0e-6f);
  const float grainMicroSourceSigma = params.grainMicroStructureSigmaNm * 0.001f / std::max(filmPixelSizeUm, 1.0e-6f);
  const bool grainMicroPath = !specializedGrain || (params.grainSublayersEnabled &&
    (grainMicroBlurSigma > 0.4f || (grainMicroSourceSigma > 0.05f && params.grainMicroStructureScale > 0.0f)));
  const bool grainLayerBlurPath = !specializedGrain || (params.grainSublayersEnabled && params.grainBlurDyeCloudsUm > 0.0f);
  const bool milestoneToLog = diffusionMilestone != VK_NULL_HANDLE && !writingDiffusionMilestone && !halationPassEnabled;
  const uint32_t requiredCorePipelines =
    (params.autoExposure ? kCoreAutoExposure : 0u) |
    (!previewDevelopment ? kCoreFilmExposure | kCoreCurveDevelop : 0u) |
    (printScanPassEnabled && !compactPrint && !slicePrintWithOrigin && !specializePrintOperation ? kCorePrintScan : 0u) |
    (halationPassEnabled && !previewOptimizations ? kCoreHalation : 0u) |
    ((cameraDiffusionPath || printDiffusionPath || milestoneToLog) && !previewOptimizations ? kCoreDiffusion : 0u) |
    (dirPath ? kCoreDir : 0u) |
    (scannerPostPath ? kCoreScannerPost : 0u) |
    (grainPath && !specializedGrain ? kCoreGrain : 0u);
  if (!prepareCorePipelines(requiredCorePipelines)) return false;
  if (writingDiffusionMilestone && !backend->prepareDiffusionRegions()) {
    lastError = backend->lastError;
    return false;
  }
  const VkPipeline frameDiffusionPipeline = writingDiffusionMilestone ? backend->diffusionRegionsPipeline : diffusionPipeline;
  const VkPipeline runtimePrintPipeline = splitPrint ? backend->splitPrintPipelines[1] : compactPrint ? backend->compactPrintPipeline :
    (slicePrintWithOrigin ? backend->directPrintPipeline : printScanPipeline);
  const VkPipeline constantsPrintPipeline = splitPrint ? backend->splitPrintPipelines[0] : runtimePrintPipeline;
  const bool preExposureRawPath = cameraDiffusionPath || halationPassEnabled;
  bool previewFusion = true;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char fusionProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.preview_fusion", fusionProperty);
  previewFusion = fusionProperty[0] != '0';
#endif
  bool directPreviewImages = previewFusion && previewImagesAvailable && fourStageLut &&
    !sampleFrame && !activeTileContext.enabled && !writingDiffusionMilestone;
  if (directPreviewImages && !backend->preparePreviewImages(compactPrint, scannerPostPath, textureLut)) {
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraColorLut", "Using full preview processing: %s", backend->lastError.c_str());
#endif
    previewImagesAvailable = false;
    directPreviewImages = false;
  }
  bool fusedPreviewColor = directPreviewImages && textureLut && previewColorFusionAvailable &&
    !preExposureRawPath && !dirPath && !grainPath && !printDiffusionPath && !scannerPostPath;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char colorFusionProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.color_fusion", colorFusionProperty);
  fusedPreviewColor = fusedPreviewColor && colorFusionProperty[0] != '0';
#endif
  if (fusedPreviewColor && !backend->preparePreviewColor()) {
    previewColorFusionAvailable = false;
    fusedPreviewColor = false;
  }
  uint32_t composedLutSize = 161;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char composedProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.composed_lut", composedProperty);
  if (composedProperty[0] == '0') composedLutSize = 0;
  const int requestedSize = std::atoi(composedProperty);
  if (requestedSize >= 33 && requestedSize <= 161 && (requestedSize - 1) % 8 == 0) composedLutSize = requestedSize;
#endif
  bool composedPrintColor = composedLutSize != 0 && directPreviewImages && textureLut &&
    !printDiffusionPath && !scannerPostPath && previewComposedLutAvailable;
  if (composedPrintColor && (!ensureComposedLut(composedLutSize) ||
      (!fusedPreviewColor && !backend->prepareComposedPrint(compactPrint)))) {
    previewComposedLutAvailable = false;
    composedPrintColor = false;
    lastError.clear();
  }
  const bool composedPreviewColor = composedPrintColor && fusedPreviewColor;
#if defined(__ANDROID__) && !defined(NDEBUG)
  if (composedProperty[0] != '\0' && composedProperty[0] != 'a') recordNativeDiagnostic(ANDROID_LOG_INFO,
    "SpektraCompose", "active=%d size=%u", composedPrintColor, composedLutSize);
#endif
#if defined(__ANDROID__) && !defined(NDEBUG)
  if (colorFusionProperty[0] == '0' || colorFusionProperty[0] == '1') recordNativeDiagnostic(ANDROID_LOG_INFO,
    "SpektraFusion", "colour=%d texture=%d sample=%d couplers=%d dir_pass=%d scanner_pass=%d",
    fusedPreviewColor, textureLut, sampleFrame, bakedDir, dirPath, scannerPostPath);
#endif
#if defined(__ANDROID__) && !defined(NDEBUG)
  if (fusionProperty[0] == '0' || fusionProperty[0] == '1') recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraFusion",
    "images=%d sample=%d scanner=%d", directPreviewImages, sampleFrame, scannerPostPath);
#endif
  const auto pixelPrintPipeline = [&](uint32_t operation) {
    if (composedPrintColor && operation == kPrintScanOpFull) return backend->composedPrintPipelines[compactPrint ? 1u : 0u];
    if (directPreviewImages && !scannerPostPath && (operation == 0u || operation == 2u)) {
      return backend->imagePrintPipelines[fourStagePipelineIndex][operation];
    }
    return fourStageLut ? backend->fourStagePrintPipelines[fourStagePipelineIndex][operation] :
      previewColorLut ? backend->previewLutPipelines[compactPrint ? 1u : 0u][operation] :
      (specializePrintOperation ? backend->operationPipelines[2][operation] : runtimePrintPipeline);
  };
  const uint32_t halationBoostPassCount = halationBoostEnabled
    ? (halationBoostMilestoneEnabled ? 1u : 3u)
    : 0u;
  const uint32_t halationExtraPassCount = halationPassEnabled
    ? halationBoostPassCount + (halationPreviewScale > 1u ? 2u : 0u) + (halationScatterEnabled ? 10u : 0u) +
        (halationBounceEnabled ? 8u : 1u)
    : 0u;
  auto diffusionBlurPassCount = [&](const std::vector<VulkanDiffusionComponent> &components) {
    uint32_t passCount = 0u;
    uint32_t preparedDownsampleScale = 0u;
    for (uint32_t component = 0u; component < static_cast<uint32_t>(components.size());) {
      const uint32_t downsampleScale = diffusionDownsamplePath
        ? diffusionScale(components[component].sigmaPx)
        : 1u;
      uint32_t groupCount = 1u;
      while (component + groupCount < static_cast<uint32_t>(components.size()) &&
             groupCount < diffusionGroupSize &&
             (!diffusionDownsamplePath ||
              diffusionScale(components[component + groupCount].sigmaPx) == downsampleScale)) {
        ++groupCount;
      }
      if (downsampleScale > 1u) {
        passCount += preparedDownsampleScale == downsampleScale ? 3u : 4u;
        preparedDownsampleScale = downsampleScale;
      } else {
        passCount += 2u;
      }
      component += groupCount;
    }
    return passCount;
  };
  const uint32_t cameraDiffusionExtraPassCount = cameraDiffusionPath
    ? diffusionBlurPassCount(cameraDiffusionComponents) + 2u + (halationPassEnabled ? 0u : 1u)
    : 0u;
  const uint32_t printDiffusionExtraPassCount = printDiffusionPath
    ? diffusionBlurPassCount(printDiffusionComponents) + 4u
    : 0u;
  const uint32_t dirExtraPassCount = dirPath
    ? 2u + (dirBlurPath ? 2u : 0u) + (dirTailPath ? 7u : 0u)
    : 0u;
  const uint32_t scannerPostExtraPassCount = scannerPostPath
    ? (printGlarePath ? 2u + (printGlareBlurPath ? 2u : 0u) : 0u) +
      (scannerBlurPath ? 2u : 0u) +
      (scannerUnsharpPath ? 2u : 0u) +
      1u
    : 0u;
  const uint32_t grainExtraPassCount =
    previewGrainPath ? 1u : ((productionGrainPath || grainSynthesisPath)
      ? 5u + (grainLayerBlurPath ? 2u : 0u) + (grainMicroPath ? 3u : 0u) : 0u);
  const uint32_t cameraDiffusionRadius = diffusionSpatialRadius(cameraDiffusionComponents, blurDownsample, previewOptimizations);
  const uint32_t halationRadius =
    (halationScatterEnabled || halationBounceEnabled) ? halationSpatialRadius(params, filmPixelSizeUm) : 0u;
  const uint32_t dirRadius = dirBlurPath ? kVulkanSpatialEffectRadiusPx : 0u;
  const uint32_t grainRadius =
    (productionGrainPath || grainSynthesisPath) ? kVulkanGrainSpatialRadiusPx : 0u;
  const uint32_t printDiffusionRadius = diffusionSpatialRadius(printDiffusionComponents, blurDownsample, previewOptimizations);
  const uint32_t scannerPostRadius =
    (printGlareBlurPath || scannerBlurPath || scannerUnsharpPath) ? kVulkanSpatialEffectRadiusPx : 0u;
  const bool activeRectShrinkEnabled = activeTileContext.enabled;
  const bool frameParamsEnabled = printScanPassEnabled || halationPassEnabled || scannerPostPath || grainPath;
  const bool filmDensityIntermediateEnabled = printScanPassEnabled || dirPath || grainPath;

  const float effectiveFilmGamma = params.filmGamma *
    (params.filmPushPullMode == PushPullMode::Standard ? filmPushPullGamma(params.filmPushPullStops) : 1.0f);
  const float effectivePrintGamma = params.printGamma * printPushPullGamma(params.printPushPullStops);

  diagnostics.uploadBytes =
    (fullFrameSourcePath || externalImages) ? 0u : static_cast<uint64_t>(byteCount);
  diagnostics.diffusionGroupSize = diffusionGroupSize;
  diagnostics.threadgroupMode = threadgroupMode;
  diagnostics.passTimingMode = "cpu-fence";
  diagnostics.blurBackend = blurBackend;
  diagnostics.blurDownsample = previewOptimizations ? "preview" : blurDownsample;
  diagnostics.intermediatePrecision = intermediatePrecision;
  diagnostics.diffusionClusterSigma = diffusionClusterSigma;
  diagnostics.dirTailBackend = dirTailBackend;
  diagnostics.halationGroupedTail = halationGroupedTail;
  diagnostics.scannerMps = scannerMps;
  diagnostics.grainBlurRecurrence = grainBlurRecurrence;
  diagnostics.passCount =
    2u + (milestoneToLog ? 1u : 0u) +
    (params.autoExposure ? (params.autoExposureMethod == AutoExposureMethod::Median ?
      SPEKTRA_AE_MEDIAN_PASSES : SPEKTRA_AE_WEIGHTED_PASSES) : 0u) +
    cameraDiffusionExtraPassCount +
    halationExtraPassCount +
    dirExtraPassCount +
    grainExtraPassCount +
    (printScanPassEnabled ? 1u + (printDiffusionPath ? printDiffusionExtraPassCount : 1u) : 0u) +
    scannerPostExtraPassCount;
  if (fusedPreviewColor) diagnostics.passCount -= 2u;
  if (fusedPreviewColor && previewExposureRange == VK_NULL_HANDLE) --diagnostics.passCount;
  diagnostics.privateScratchEnabled = false;
  diagnostics.halationPath = halationPassEnabled;
  diagnostics.cameraDiffusionPath = cameraDiffusionPath;
  diagnostics.printDiffusionPath = printDiffusionPath;
  diagnostics.dirPath = dirPath;
  diagnostics.productionGrainPath = productionGrainPath;
  diagnostics.grainSynthesisPath = grainSynthesisPath;
  diagnostics.finalPostProcessPath = printScanPassEnabled;

  const bool layeredGrainPath = productionGrainPath || grainSynthesisPath;
  const VkDeviceSize sourceAllocationByteCount =
    layeredGrainPath ? std::max(sourceByteCount, grainLayerByteCount) : sourceByteCount;
  const VkDeviceSize filmRawAllocationByteCount =
    layeredGrainPath ? std::max(pixelStorageByteCount, grainLayerByteCount) : pixelStorageByteCount;
  if (!layeredGrainPath && coreFrame.sourceAliasedForGrain) {
    destroyScratchBuffer(coreFrame.source);
    coreFrame.sourceAliasedForGrain = false;
  }
  if (!layeredGrainPath && coreFrame.filmRawAliasedForGrain) {
    destroyScratchBuffer(coreFrame.filmRaw);
    coreFrame.filmRawAliasedForGrain = false;
  }
  std::array<VkDeviceSize, CoreFrameResources::kPhaseScratchCount> phaseScratchSizes{};
  auto requirePhaseScratch = [&](size_t slot, VkDeviceSize size) {
    phaseScratchSizes[slot] = std::max(phaseScratchSizes[slot], size);
  };
  // Diffusion reads the boosted image while its downsample blur writes slot 5.
  // Slot 0 stays unused until diffusion finishes and halation starts.
  const size_t halationBoostedRawSlot = cameraDiffusionPath ? 0u : 5u;
  if (halationPassEnabled) {
    requirePhaseScratch(0u, pixelStorageByteCount);
    requirePhaseScratch(1u, pixelStorageByteCount);
    requirePhaseScratch(2u, pixelStorageByteCount);
    requirePhaseScratch(3u, pixelStorageByteCount);
    requirePhaseScratch(4u, pixelStorageByteCount);
  }
  if (halationBoostEnabled) {
    requirePhaseScratch(halationBoostedRawSlot, pixelStorageByteCount);
  }
  if (cameraDiffusionPath || printDiffusionPath) {
    requirePhaseScratch(2u, diffusionTempByteCount);
    requirePhaseScratch(3u, diffusionOutputByteCount);
  }
  if (diffusionDownsamplePath) {
    requirePhaseScratch(4u, diffusionDownsampleByteCount);
    requirePhaseScratch(5u, diffusionDownsampleGroupByteCount);
    requirePhaseScratch(6u, diffusionDownsampleGroupByteCount);
  }
  if (cameraDiffusionPath || printDiffusionPath) {
    requirePhaseScratch(7u, diffusionOutputByteCount);
  }
  if (cameraDiffusionPath && !halationPassEnabled && !writingDiffusionMilestone) {
    requirePhaseScratch(4u, pixelStorageByteCount);
  }
  if (printDiffusionPath) {
    requirePhaseScratch(1u, pixelStorageByteCount);
  }
  if (dirPath) {
    requirePhaseScratch(0u, pixelStorageByteCount);
    requirePhaseScratch(1u, pixelStorageByteCount);
    requirePhaseScratch(2u, pixelStorageByteCount);
    if (printScanPassEnabled || grainPath) {
      requirePhaseScratch(3u, pixelStorageByteCount);
    }
  }
  if (grainPath) {
    requirePhaseScratch(0u, pixelStorageByteCount);
    requirePhaseScratch(1u, pixelStorageByteCount);
  }
  if (layeredGrainPath) {
    requirePhaseScratch(2u, pixelStorageByteCount);
    requirePhaseScratch(4u, pixelStorageByteCount);
  }
  if (scannerPostPath) {
    requirePhaseScratch(0u, pixelStorageByteCount);
    requirePhaseScratch(1u, pixelStorageByteCount);
    requirePhaseScratch(6u, pixelStorageByteCount);
  }
  if (printGlarePath) {
    requirePhaseScratch(2u, pixelStorageByteCount);
    requirePhaseScratch(3u, pixelStorageByteCount);
  }

  const std::string tileOverride = envString("SPEKTRAFILM_VULKAN_TILE_MODE", "");
  const bool forceFullFrame = tileOverride == "legacy" || tileOverride == "full-frame" || tileOverride == "fullframe";
  if (externalImages && !previewOptimizations && !activeTileContext.enabled && !forceFullFrame &&
      !grainSynthesisPath && !halationBoostEnabled) {
    uint64_t additional = 0;
    auto account = [&](const ScratchBuffer &buffer, VkDeviceSize required) {
      if (required > buffer.capacity) additional += required - buffer.capacity;
    };
    account(coreFrame.source, sourceAllocationByteCount);
    account(coreFrame.filmRaw, filmRawAllocationByteCount);
    account(coreFrame.destination, pixelStorageByteCount);
    account(coreFrame.sourceStaging, halfByteCount);
    if (destinationFormatConvert) account(coreFrame.destinationHalf, halfByteCount);
    if (filmDensityIntermediateEnabled) account(coreFrame.filmDensity, pixelStorageByteCount);
    if (params.autoExposure) {
      account(coreFrame.autoExposureLuminance, SPEKTRA_AE_SCRATCH_BYTES);
      account(coreFrame.autoExposureSource, autoExposureLuminanceByteCount * 4u);
    }
    for (size_t slot = 0; slot < phaseScratchSizes.size(); ++slot)
      account(coreFrame.phaseScratch[slot], phaseScratchSizes[slot]);
    const auto memory = readSystemMemory();
    const bool tiled = export_memory::needsTiles(pixelCount, memory.total, memory.available, additional);
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "ExportMemory",
      "pixels=%llu total_bytes=%llu available_bytes=%llu additional_bytes=%llu tiled=%d",
      static_cast<unsigned long long>(pixelCount), static_cast<unsigned long long>(memory.total),
      static_cast<unsigned long long>(memory.available), static_cast<unsigned long long>(additional), tiled);
#endif
    if (tiled) return renderTiledExternalImages(activeExternalImageContext.sourceImage,
      activeExternalImageContext.destinationImage, static_cast<uint32_t>(width), static_cast<uint32_t>(height), params, time);
  }

  if (!prepareStaticFilmResources(params, printScanPassEnabled, productionGrainPath || grainSynthesisPath) ||
      !ensureCoreFrameResources() ||
      !ensurePrivateScratchBuffer(
        coreFrame.source,
        sourceAllocationByteCount,
        fullFrameSourcePath ? "core full-frame source and grain layer A" : "core source and grain layer A"
      ) ||
      !ensurePrivateScratchBuffer(coreFrame.autoExposureResult, 4u * sizeof(float), "core auto exposure result") ||
      (!writingDiffusionMilestone &&
       !ensurePrivateScratchBuffer(coreFrame.filmRaw, filmRawAllocationByteCount, "core film raw and grain layer B")) ||
      !ensurePrivateScratchBuffer(coreFrame.destination, writingDiffusionMilestone ? 16u : pixelStorageByteCount, "core destination")) {
    return false;
  }
  const VkDescriptorSet finalPrintDescriptorSet = compactPrint ? coreFrame.compactPrintSets[0] : coreFrame.finalDescriptorSet;
  const std::array<VkDescriptorSet, 2> printDiffusionSets = compactPrint
    ? std::array<VkDescriptorSet, 2>{coreFrame.compactPrintSets[1], coreFrame.compactPrintSets[2]}
    : coreFrame.printDiffusionDescriptorSets;
  coreFrame.sourceAliasedForGrain = layeredGrainPath && coreFrame.source.capacity > sourceByteCount;
  coreFrame.filmRawAliasedForGrain = layeredGrainPath && coreFrame.filmRaw.capacity > pixelStorageByteCount;
  if (params.autoExposure &&
      !ensurePrivateScratchBuffer(
        coreFrame.autoExposureLuminance,
        SPEKTRA_AE_SCRATCH_BYTES,
        "core auto exposure luminance"
      )) {
    return false;
  }
  if (externalImages && params.autoExposure &&
      !ensurePrivateScratchBuffer(coreFrame.autoExposureSource,
        autoExposureLuminanceByteCount * 4u, "core whole-image exposure samples")) {
    return false;
  }
  const auto formatGroups = linearDispatchGroups(static_cast<uint32_t>(pixelCount),
    physicalDeviceProperties.limits.maxComputeWorkGroupCount[0]);
  if (!fullFrameSourcePath && !writingDiffusionMilestone) {
    const bool sourceReady = externalImages
      ? ensurePrivateScratchBuffer(
          coreFrame.sourceStaging,
          halfByteCount,
          "core Vulkan image source",
          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        )
      : ensureUploadScratchBuffer(
          coreFrame.sourceStaging,
          byteCount,
          "core source staging",
          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
        );
    if (!sourceReady) {
      return false;
    }
  }
  if (destinationIsHalf && !fusedPreviewOutput && !writingDiffusionMilestone) {
    if (!ensurePrivateScratchBuffer(coreFrame.destinationHalf, halfByteCount, "core destination half")) {
      return false;
    }
    if (!externalImages &&
        !ensureReadbackScratchBuffer(
          coreFrame.destinationHalfStaging,
          halfByteCount,
          "core destination half staging",
          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        )) {
      return false;
    }
  } else if (!externalImages && !ensureReadbackScratchBuffer(
               coreFrame.destinationStaging,
               byteCount,
               "core destination staging",
               VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
             )) {
    return false;
  }
  if (halationPreviewScale > 1u) {
    if (!ensurePrivateScratchBuffer(coreFrame.halationPreviewSource, halationPreviewBytes, "halation preview source")) return false;
  } else {
    destroyScratchBuffer(coreFrame.halationPreviewSource);
  }
  if (filmDensityIntermediateEnabled &&
      !ensurePrivateScratchBuffer(coreFrame.filmDensity, pixelStorageByteCount, "core film density")) {
    return false;
  }
  if (halationBoostEnabled) {
    if (halationBoostLocalReductionEnabled &&
        (!ensurePrivateScratchBuffer(coreFrame.halationBoostChunks, halationBoostChunkByteCount, "core halation boost chunks") ||
         !ensurePrivateScratchBuffer(coreFrame.halationBoostInfo, 4u * sizeof(float), "core halation boost info"))) {
      return false;
    }
    if (halationBoostMilestoneEnabled && coreFrame.tiledHalationBoostInfo.buffer == VK_NULL_HANDLE) {
      lastError = "Vulkan tiled halation boost milestone info is unavailable.";
      return false;
    }
  }
  static constexpr std::array<const char *, CoreFrameResources::kPhaseScratchCount> kPhaseScratchNames = {
    "core phase scratch 0",
    "core phase scratch 1",
    "core phase scratch 2",
    "core phase scratch 3",
    "core phase scratch 4",
    "core phase scratch 5",
    "core phase scratch 6",
    "core phase scratch 7"
  };
  for (size_t slot = 0u; slot < phaseScratchSizes.size(); ++slot) {
    if (phaseScratchSizes[slot] == 0u) {
      destroyScratchBuffer(coreFrame.phaseScratch[slot]);
    } else if (!ensurePrivateScratchBuffer(
                 coreFrame.phaseScratch[slot],
                 phaseScratchSizes[slot],
                 kPhaseScratchNames[slot]
               )) {
      return false;
    }
  }
  if (!params.autoExposure) {
    destroyScratchBuffer(coreFrame.autoExposureLuminance);
  }
  if (!filmDensityIntermediateEnabled) {
    destroyScratchBuffer(coreFrame.filmDensity);
  }
  if (!destinationIsHalf || fusedPreviewOutput) {
    destroyScratchBuffer(coreFrame.destinationHalf);
    destroyScratchBuffer(coreFrame.destinationHalfStaging);
  } else {
    destroyScratchBuffer(coreFrame.destinationStaging);
  }
  if (!halationBoostLocalReductionEnabled) {
    destroyScratchBuffer(coreFrame.halationBoostChunks);
    destroyScratchBuffer(coreFrame.halationBoostInfo);
  }
  if (!halationBoostMilestoneEnabled) {
    destroyScratchBuffer(coreFrame.tiledHalationBoostInfo);
  }
  if (!cameraDiffusionPath) {
    destroyScratchBuffer(coreFrame.cameraDiffusionInfo);
    destroyScratchBuffer(coreFrame.cameraDiffusionComponents);
  }
  if (!printDiffusionPath) {
    destroyScratchBuffer(coreFrame.printDiffusionInfo);
    destroyScratchBuffer(coreFrame.printDiffusionComponents);
  }
  if (!dirPath) destroyScratchBuffer(coreFrame.dirFloats);
  if (!dirEnabled) destroyScratchBuffer(coreFrame.dirCorrectedDensityCurves);
  if (!frameParamsEnabled) {
    destroyScratchBuffer(coreFrame.frameFloats);
    destroyScratchBuffer(coreFrame.frameInts);
  }
  if (!printScanPassEnabled) {
    destroyScratchBuffer(coreFrame.filteredEnlargerResponse);
  }
  if (frameParamsEnabled &&
      (!ensureSharedScratchBuffer(coreFrame.frameFloats, kCoreFrameFloatCount * sizeof(float), "core frame float params") ||
       !ensureSharedScratchBuffer(coreFrame.frameInts, kCoreFrameIntCount * sizeof(uint32_t), "core frame int params"))) {
    return false;
  }
  if (printScanPassEnabled &&
      !ensurePrivateScratchBuffer(
        coreFrame.filteredEnlargerResponse,
        static_cast<VkDeviceSize>(staticFilm.wavelengthCount) * 8u * sizeof(float),
        "filtered enlarger response"
      )) {
    return false;
  }
  diagnostics.privateScratchEnabled = preferPrivateScratch;
  if (writingDiffusionMilestone && !uploadScratchBuffer(coreFrame.diffusionRegions, &diffusionRegions,
      sizeof(diffusionRegions), "diffusion scratch regions")) return false;

  if (sampleFrame) {
    if (samplePrint && ((!compactPrint && !backend->preparePrintProbe()) || !backend->preparePrintAccessProbe())) {
      lastError = backend->lastError;
      return false;
    }
    if (!ensureReadbackScratchBuffer(coreFrame.samples.readback, FrameSamples::kBytes, "frame samples")) return false;
    if (samplePrint && coreFrame.samples.probeSet == VK_NULL_HANDLE) {
      if (coreFrame.samples.probePool == VK_NULL_HANDLE) {
        const VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4};
        VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        info.maxSets = 1;
        info.poolSizeCount = 1;
        info.pPoolSizes = &size;
        const VkResult result = vkCreateDescriptorPool(device, &info, nullptr, &coreFrame.samples.probePool);
        if (result != VK_SUCCESS) { lastError = vkError("Print access descriptor pool", result); return false; }
      }
      VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
      info.descriptorPool = coreFrame.samples.probePool;
      info.descriptorSetCount = 1;
      info.pSetLayouts = &backend->printAccessSetLayout;
      const VkResult result = vkAllocateDescriptorSets(device, &info, &coreFrame.samples.probeSet);
      if (result != VK_SUCCESS) { lastError = vkError("Print access descriptor set", result); return false; }
    }
    coreFrame.samples.width = static_cast<uint32_t>(width);
    coreFrame.samples.height = static_cast<uint32_t>(height);
    coreFrame.samples.sequence = sampleSequence;
    coreFrame.samples.preview = previewOptimizations;
    coreFrame.samples.printConstants = printScanPassEnabled;
    coreFrame.samples.printExecutionVerified = false;
    coreFrame.samples.params = params;
    coreFrame.samples.stages = 0;
  }

  const PerfClock::time_point sourceCopyStart = PerfClock::now();
  if (!fullFrameSourcePath && !externalImages) {
    if (!coreFrame.sourceStaging.mapped) {
      lastError = "Vulkan source staging buffer is not mapped.";
      return false;
    }
    if (!copySourceToFloatStaging(
          source,
          window,
          width,
          height,
          static_cast<float *>(coreFrame.sourceStaging.mapped)
        )) {
      lastError = "The requested render window does not fit inside the Vulkan source image view.";
      return false;
    }
    if (!flushMappedScratchBuffer(coreFrame.sourceStaging, byteCount, "core source staging")) {
      return false;
    }
  }
  const PerfClock::time_point sourceCopyEnd = PerfClock::now();
  diagnostics.sourceCopyMs = elapsedMilliseconds(sourceCopyStart, sourceCopyEnd);
  VkResult result = VK_SUCCESS;

  if (cameraDiffusionPath &&
      (!uploadScratchBuffer(coreFrame.cameraDiffusionInfo, &cameraDiffusionInfo, sizeof(cameraDiffusionInfo), "camera diffusion info", previewOptimizations) ||
       !uploadScratchBuffer(
         coreFrame.cameraDiffusionComponents,
         cameraDiffusionComponents.data(),
         static_cast<VkDeviceSize>(cameraDiffusionComponents.size() * sizeof(VulkanDiffusionComponent)),
         "camera diffusion components", previewOptimizations
       ))) {
    return false;
  }
  if (printDiffusionPath &&
      (!uploadScratchBuffer(coreFrame.printDiffusionInfo, &printDiffusionInfo, sizeof(printDiffusionInfo), "print diffusion info", previewOptimizations) ||
       !uploadScratchBuffer(
         coreFrame.printDiffusionComponents,
         printDiffusionComponents.data(),
         static_cast<VkDeviceSize>(printDiffusionComponents.size() * sizeof(VulkanDiffusionComponent)),
         "print diffusion components", previewOptimizations
       ))) {
    return false;
  }
  std::array<float, kDirFloatCount> dirFloats{};
  if (dirEnabled) {
    if (!staticFilm.curves || !staticFilm.curves->densityCurves || !staticFilm.curves->logExposure) {
      lastError = "Unable to locate generated film density curves for DIR correction.";
      return false;
    }
    dirFloats = makeDirFloatParams(*staticFilm.curves, params, filmPixelSizeUm);
    if (previewOptimizations && (dirCachedProfile != staticFilm.curves || dirCachedFloats != dirFloats ||
        dirCachedBaked != bakedDir || dirCachedCurves.empty())) {
      dirCachedCurves = makeDirCorrectedDensityCurves(*staticFilm.curves, dirFloats);
      if (bakedDir) {
        // LUT builds need the original development and compensated redevelopment curves.
        dirCachedCurves.insert(dirCachedCurves.begin(), staticFilm.curves->densityCurves,
          staticFilm.curves->densityCurves + staticFilm.curves->exposureCount * 3u);
      }
      dirCachedProfile = staticFilm.curves;
      dirCachedFloats = dirFloats;
      dirCachedBaked = bakedDir;
    }
    const std::vector<float> uncachedCurves = previewOptimizations ? std::vector<float>{}
      : makeDirCorrectedDensityCurves(*staticFilm.curves, dirFloats);
    const auto &correctedDensityCurves = previewOptimizations ? dirCachedCurves : uncachedCurves;
    if ((dirPath && !uploadScratchBuffer(coreFrame.dirFloats, dirFloats.data(), dirFloats.size() * sizeof(float), "DIR params", previewOptimizations)) ||
        !uploadScratchBuffer(
          coreFrame.dirCorrectedDensityCurves,
          correctedDensityCurves.data(),
          static_cast<VkDeviceSize>(correctedDensityCurves.size() * sizeof(float)),
          "DIR corrected density curves", previewOptimizations
        )) {
      return false;
    }
  }

  if (frameParamsEnabled) {
    std::array<float, kCoreFrameFloatCount> frameFloats{};
    frameFloats[0] = params.filterC;
    frameFloats[1] = params.filterMShift;
    frameFloats[2] = params.filterYShift;
    frameFloats[3] = params.printExposureEv;
    frameFloats[4] = effectivePrintGamma;
    frameFloats[5] = params.printShadowShape;
    frameFloats[6] = params.printHighlightShape;
    frameFloats[7] = params.negativeBleachBypassAmount;
    frameFloats[8] = params.negativeLeucoCyanCoupling;
    frameFloats[9] = params.printBleachBypassAmount;
    frameFloats[10] = params.preflashExposure;
    frameFloats[11] = params.preflashMFilterShift;
    frameFloats[12] = params.preflashYFilterShift;
    frameFloats[13] = params.printerLightsR;
    frameFloats[14] = params.printerLightsG;
    frameFloats[15] = params.printerLightsB;
    frameFloats[16] = staticFilm.filmDensityMaximum[0];
    frameFloats[17] = staticFilm.filmDensityMaximum[1];
    frameFloats[18] = staticFilm.filmDensityMaximum[2];
    frameFloats[19] = staticFilm.paperDensityMaximum[0];
    frameFloats[20] = staticFilm.paperDensityMaximum[1];
    frameFloats[21] = staticFilm.paperDensityMaximum[2];
    frameFloats[22] = colorEncodeLutMin();
    frameFloats[23] = colorEncodeLutMax();
    frameFloats[24] = params.scannerWhiteLevel;
    frameFloats[25] = params.scannerBlackLevel;
    frameFloats[26] = staticFilm.curves && staticFilm.curves->densityCurveMinimum ? staticFilm.curves->densityCurveMinimum[0] : 0.0f;
    frameFloats[27] = staticFilm.curves && staticFilm.curves->densityCurveMinimum ? staticFilm.curves->densityCurveMinimum[1] : 0.0f;
    frameFloats[28] = staticFilm.curves && staticFilm.curves->densityCurveMinimum ? staticFilm.curves->densityCurveMinimum[2] : 0.0f;
    float halationStrengthR = params.halationStrengthR;
    float halationStrengthG = params.halationStrengthG;
    float halationStrengthB = params.halationStrengthB;
    if (staticFilm.curves && staticFilm.curves->halationStrength && isDefaultHalationStrength(params)) {
      halationStrengthR = staticFilm.curves->halationStrength[0];
      halationStrengthG = staticFilm.curves->halationStrength[1];
      halationStrengthB = staticFilm.curves->halationStrength[2];
    }
    float halationFirstSigmaR = params.halationFirstSigmaUmR;
    float halationFirstSigmaG = params.halationFirstSigmaUmG;
    float halationFirstSigmaB = params.halationFirstSigmaUmB;
    if (staticFilm.curves && staticFilm.curves->halationFirstSigmaUm) {
      halationFirstSigmaR *= staticFilm.curves->halationFirstSigmaUm[0] / 65.0f;
      halationFirstSigmaG *= staticFilm.curves->halationFirstSigmaUm[1] / 65.0f;
      halationFirstSigmaB *= staticFilm.curves->halationFirstSigmaUm[2] / 65.0f;
    }
    frameFloats[29] = filmPixelSizeUm;
    frameFloats[30] = params.scatterAmount;
    frameFloats[31] = params.scatterScale;
    frameFloats[32] = params.halationAmount;
    frameFloats[33] = params.halationScale;
    frameFloats[34] = halationStrengthR;
    frameFloats[35] = halationStrengthG;
    frameFloats[36] = halationStrengthB;
    frameFloats[37] = halationFirstSigmaR;
    frameFloats[38] = halationFirstSigmaG;
    frameFloats[39] = halationFirstSigmaB;
    frameFloats[40] = scannerSigmaUmFromMtf50(params.scannerMtf50LpMm) / std::max(filmPixelSizeUm, 1.0e-6f);
    frameFloats[41] = std::max(params.scannerUnsharpRadiusUm, 0.0f) / std::max(filmPixelSizeUm, 1.0e-6f);
    frameFloats[42] = params.scannerUnsharpAmount;
    frameFloats[43] = params.glarePercent;
    frameFloats[44] = params.glareRoughness;
    frameFloats[45] = params.glareBlur;
    const std::array<float, 3> glareRgb = scanIlluminantToOutputRgb(staticFilm.paperCurves, params);
    frameFloats[46] = glareRgb[0];
    frameFloats[47] = glareRgb[1];
    frameFloats[48] = glareRgb[2];
    frameFloats[49] = params.grainAmount;
    frameFloats[50] = params.grainSaturation;
    frameFloats[51] = params.grainParticleAreaUm2;
    frameFloats[52] = params.grainParticleScaleR;
    frameFloats[53] = params.grainParticleScaleG;
    frameFloats[54] = params.grainParticleScaleB;
    frameFloats[55] = params.grainParticleScaleLayer0;
    frameFloats[56] = params.grainParticleScaleLayer1;
    frameFloats[57] = params.grainParticleScaleLayer2;
    frameFloats[58] = params.grainDensityMinR;
    frameFloats[59] = params.grainDensityMinG;
    frameFloats[60] = params.grainDensityMinB;
    frameFloats[61] = params.grainUniformityR;
    frameFloats[62] = params.grainUniformityG;
    frameFloats[63] = params.grainUniformityB;
    const float grainFormatScale = std::pow(
      std::max(filmFormatLongEdgeMm(params.filmFormat) / 35.0f, 1.0e-6f),
      0.62f
    );
    frameFloats[64] = std::max(params.grainFinalBlurUm, 0.0f) * grainFormatScale /
      std::max(filmPixelSizeUm, 1.0e-6f);
    frameFloats[65] = params.grainBlurDyeCloudsUm;
    frameFloats[66] = params.grainMicroStructureScale;
    frameFloats[67] = params.grainMicroStructureSigmaNm * 0.001f / std::max(filmPixelSizeUm, 1.0e-6f);
    frameFloats[68] = resolvedEnlargerScale(params);
    frameFloats[69] = params.enlargerOffsetXPercent;
    frameFloats[70] = params.enlargerOffsetYPercent;
    frameFloats[71] = filmPixelSizeUm;
    frameFloats[72] = params.hdrReferenceWhiteNits;
    frameFloats[73] = params.hdrPeakNits;
    frameFloats[74] = params.hdrExposureEv;
    frameFloats[75] = params.halationBoostEv;
    frameFloats[76] = params.halationBoostRange;
    frameFloats[77] = params.halationProtectEv;
    const float grainSynthesisQuality = std::clamp(params.grainSynthesisQuality, 0.25f, 4.0f);
    const float grainSynthesisSize = std::clamp(params.grainSynthesisSize, 0.25f, 4.0f);
    const float grainSynthesisSharpness = std::max(params.grainSynthesisSharpness, 0.25f);
    frameFloats[78] = std::clamp(params.grainSynthesisAmount, 0.0f, 3.0f);
    frameFloats[79] = params.grainSynthesisMeanRadiusUm * grainSynthesisSize;
    frameFloats[80] = params.grainSynthesisRadiusStdDevRatio;
    frameFloats[81] = params.grainSynthesisObservationSigmaUm / grainSynthesisSharpness;
    frameFloats[82] = params.grainSynthesisCellSizeRatio;
    frameFloats[83] = params.grainSynthesisMaxRadiusQuantile;
    frameFloats[84] = params.grainSynthesisCoverageEpsilon;
    frameFloats[85] = params.grainSynthesisRadiusScaleR;
    frameFloats[86] = params.grainSynthesisRadiusScaleG;
    frameFloats[87] = params.grainSynthesisRadiusScaleB;
    frameFloats[88] = params.grainSynthesisLayerScale0;
    frameFloats[89] = params.grainSynthesisLayerScale1;
    frameFloats[90] = params.grainSynthesisLayerScale2;
    if (bakedDir) std::copy_n(dirFloats.begin(), 9u, frameFloats.begin() + SPEKTRA_FOUR_STAGE_DIR_MATRIX_OFFSET);

    std::array<uint32_t, kCoreFrameIntCount> frameInts{};
    frameInts[0] = static_cast<uint32_t>(params.process);
    frameInts[1] = static_cast<uint32_t>(params.outputColorSpace);
    frameInts[2] = static_cast<uint32_t>(params.outputRole);
    frameInts[3] = static_cast<uint32_t>(params.printTiming);
    frameInts[4] = static_cast<uint32_t>(std::max(params.film, 0));
    frameInts[5] = static_cast<uint32_t>(std::max(params.paper, 0));
    frameInts[6] = kSpektraFilmCount;
    frameInts[7] = kSpektraPaperCount;
    frameInts[8] = staticFilm.wavelengthCount;
    frameInts[9] = staticFilm.paperExposureCount;
    frameInts[10] = staticFilm.filmPositive;
    frameInts[11] = params.printerLightsGang ? 1u : 0u;
    frameInts[12] = params.printerLightCalibration ? 1u : 0u;
    frameInts[13] = params.scannerEnabled ? 1u : 0u;
    frameInts[14] = params.scannerWhiteCorrection ? 1u : 0u;
    frameInts[15] = params.scannerBlackCorrection ? 1u : 0u;
    frameInts[16] = params.grainSeed;
    frameInts[17] = params.grainSublayersEnabled ? 1u : 0u;
    frameInts[18] = static_cast<uint32_t>(std::max(params.grainSubLayerCount, 1));
    frameInts[19] = params.grainAnimate
      ? static_cast<uint32_t>(std::max(0.0, std::floor(time * 24.0 + 0.5)))
      : 0u;
    frameInts[20] = static_cast<uint32_t>(params.hdrTransfer);
    frameInts[21] = static_cast<uint32_t>(params.hdrToneMapping);
    frameInts[22] = static_cast<uint32_t>(std::clamp(
      static_cast<int32_t>(std::lround(static_cast<float>(params.grainSynthesisSamples) * grainSynthesisQuality)),
      1,
      1024
    ));
    frameInts[23] = static_cast<uint32_t>(std::clamp(params.grainSynthesisMaxGrainsPerCell, 1, 128));
    frameInts[24] = params.grainSynthesisLayered ? 1u : 0u;
    frameInts[25] = grainSynthesisPath ? 1u : 0u;
    frameInts[26] = grainBlurRecurrence ? 1u : 0u;
    frameInts[27] = colorAdaptationFlags(params);
    frameInts[28] = params.scanNegativeInvert ? 1u : 0u;
    frameInts[SPEKTRA_FOUR_STAGE_DIR_ENABLED_INDEX] = bakedDir ? 1u : 0u;

    if (!coreFrame.frameFloats.mapped || !coreFrame.frameInts.mapped) {
      lastError = "Vulkan frame parameter buffers are not mapped.";
      return false;
    }
    std::memcpy(coreFrame.frameFloats.mapped, frameFloats.data(), frameFloats.size() * sizeof(float));
    std::memcpy(coreFrame.frameInts.mapped, frameInts.data(), frameInts.size() * sizeof(uint32_t));
    if (!flushMappedScratchBuffer(coreFrame.frameFloats, frameFloats.size() * sizeof(float), "core frame float params") ||
        !flushMappedScratchBuffer(coreFrame.frameInts, frameInts.size() * sizeof(uint32_t), "core frame int params")) {
      return false;
    }
  }

  const CoreFrameResources::ColorLutKey colorLutKey{
    params.film, params.paper, params.process, params.printTiming,
    {params.filterC, params.filterMShift, params.filterYShift}};
  bool rebuildColorLut = false;
  if (previewColorLut && !fourStageLut) {
    if (coreFrame.colorLut.buffer == VK_NULL_HANDLE) coreFrame.colorLutReady = false;
    if (!ensurePrivateScratchBuffer(coreFrame.colorLut, CoreFrameResources::kColorLutBytes, "preview spectral color LUT")) return false;
    rebuildColorLut = !coreFrame.colorLutReady || !(coreFrame.colorLutKey == colorLutKey);
    if (rebuildColorLut) coreFrame.colorLutReady = false;
  }
  const VkDescriptorBufferInfo colorLutBufferInfo{coreFrame.colorLut.buffer, 0, CoreFrameResources::kColorLutBytes};
  const auto fourStageLutKey = fourStageColorLutKey(params, bakedDir);
  bool forceLutRebuild = false;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char rebuildProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.lut_rebuild", rebuildProperty);
  forceLutRebuild = rebuildProperty[0] == '1';
#endif
  bool rebuildFourStageLut = false;
  if (fourStageLut) {
    if (coreFrame.fourStageLut.buffer == VK_NULL_HANDLE) coreFrame.fourStageLutReady = false;
    if (!ensurePrivateScratchBuffer(coreFrame.fourStageLut, CoreFrameResources::kFourStageLutBytes, "four-stage color LUT")) return false;
    rebuildFourStageLut = forceLutRebuild || !coreFrame.fourStageLutReady || coreFrame.fourStageLutKey != fourStageLutKey;
    if (rebuildFourStageLut) coreFrame.fourStageLutReady = false;
  }
  const VkDescriptorBufferInfo fourStageLutBufferInfo{coreFrame.fourStageLut.buffer, 0, CoreFrameResources::kFourStageLutBytes};
  const bool uploadFourStageTexture = textureLut && (rebuildFourStageLut || !coreFrame.fourStageTextureReady ||
    coreFrame.fourStageTextureGeneration != coreFrame.fourStageLutGeneration);
  const VkDescriptorImageInfo fourStageTextureInfo{coreFrame.fourStageTextureSampler,
    coreFrame.fourStageTextureView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
  const bool rebuildComposedLut = composedPrintColor && (rebuildFourStageLut || !coreFrame.composedLut.ready ||
    coreFrame.composedLut.generation != coreFrame.fourStageLutGeneration);
  const VkDescriptorImageInfo composedTextureInfo{coreFrame.fourStageTextureSampler,
    coreFrame.composedLut.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
  if (rebuildComposedLut) {
    const VkDescriptorImageInfo imageInfo{VK_NULL_HANDLE, coreFrame.composedLut.view, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writes[2]{};
    for (uint32_t binding = 0; binding < 2; ++binding) {
      writes[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[binding].dstSet = coreFrame.composedLut.set;
      writes[binding].dstBinding = binding;
      writes[binding].descriptorCount = 1;
    }
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &fourStageLutBufferInfo;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);
  }

  VkDescriptorBufferInfo sourceBufferInfo{};
  sourceBufferInfo.buffer = coreFrame.source.buffer;
  sourceBufferInfo.offset = 0;
  sourceBufferInfo.range = sourceByteCount;
  VkDescriptorBufferInfo sourceHalfBufferInfo{};
  sourceHalfBufferInfo.buffer = coreFrame.sourceStaging.buffer;
  sourceHalfBufferInfo.offset = 0;
  sourceHalfBufferInfo.range = halfByteCount;
  VkDescriptorBufferInfo autoExposureLuminanceBufferInfo{};
  autoExposureLuminanceBufferInfo.buffer = coreFrame.autoExposureLuminance.buffer;
  autoExposureLuminanceBufferInfo.offset = 0;
  autoExposureLuminanceBufferInfo.range = coreFrame.autoExposureLuminance.capacity;
  VkDescriptorBufferInfo autoExposureResultBufferInfo{};
  autoExposureResultBufferInfo.buffer = coreFrame.autoExposureResult.buffer;
  autoExposureResultBufferInfo.offset = 0;
  autoExposureResultBufferInfo.range = coreFrame.autoExposureResult.capacity;
  VkDescriptorBufferInfo filmRawBufferInfo{};
  filmRawBufferInfo.buffer = writingDiffusionMilestone ? coreFrame.source.buffer : coreFrame.filmRaw.buffer;
  filmRawBufferInfo.offset = 0;
  filmRawBufferInfo.range = pixelStorageByteCount;
  VkDescriptorBufferInfo destinationBufferInfo{};
  destinationBufferInfo.buffer = coreFrame.destination.buffer;
  destinationBufferInfo.offset = 0;
  destinationBufferInfo.range = writingDiffusionMilestone ? 16u : pixelStorageByteCount;
  VkDescriptorBufferInfo destinationHalfBufferInfo{};
  destinationHalfBufferInfo.buffer = coreFrame.destinationHalf.buffer;
  destinationHalfBufferInfo.offset = 0;
  destinationHalfBufferInfo.range = halfByteCount;
  VkDescriptorBufferInfo filmDensityBufferInfo{};
  filmDensityBufferInfo.buffer = coreFrame.filmDensity.buffer;
  filmDensityBufferInfo.offset = 0;
  filmDensityBufferInfo.range = pixelStorageByteCount;
  const VkDescriptorBufferInfo &developOutputBufferInfo = filmDensityIntermediateEnabled ? filmDensityBufferInfo : destinationBufferInfo;
  VkDescriptorBufferInfo logExposureBufferInfo{};
  logExposureBufferInfo.buffer = staticFilm.logExposure.buffer;
  logExposureBufferInfo.offset = 0;
  logExposureBufferInfo.range = staticFilm.logExposure.capacity;
  VkDescriptorBufferInfo densityCurvesBufferInfo{};
  densityCurvesBufferInfo.buffer = staticFilm.densityCurves.buffer;
  densityCurvesBufferInfo.offset = 0;
  densityCurvesBufferInfo.range = staticFilm.densityCurves.capacity;
  VkDescriptorBufferInfo densityCurveLayersBufferInfo{};
  densityCurveLayersBufferInfo.buffer = staticFilm.densityCurveLayers.buffer != VK_NULL_HANDLE
    ? staticFilm.densityCurveLayers.buffer
    : staticFilm.densityCurves.buffer;
  densityCurveLayersBufferInfo.offset = 0;
  densityCurveLayersBufferInfo.range = staticFilm.densityCurveLayers.buffer != VK_NULL_HANDLE
    ? staticFilm.densityCurveLayers.capacity
    : staticFilm.densityCurves.capacity;
  VkDescriptorBufferInfo densityCurveLayerMaximaBufferInfo{};
  densityCurveLayerMaximaBufferInfo.buffer = staticFilm.densityCurveLayerMaxima.buffer != VK_NULL_HANDLE
    ? staticFilm.densityCurveLayerMaxima.buffer
    : staticFilm.densityCurves.buffer;
  densityCurveLayerMaximaBufferInfo.offset = 0;
  densityCurveLayerMaximaBufferInfo.range = staticFilm.densityCurveLayerMaxima.buffer != VK_NULL_HANDLE
    ? staticFilm.densityCurveLayerMaxima.capacity
    : staticFilm.densityCurves.capacity;
  VkDescriptorBufferInfo inputMeterXyzBufferInfo{};
  inputMeterXyzBufferInfo.buffer = staticFilm.inputMeterXyz.buffer;
  inputMeterXyzBufferInfo.offset = 0;
  inputMeterXyzBufferInfo.range = staticFilm.inputMeterXyz.capacity;
  VkDescriptorBufferInfo inputToReferenceXyzBufferInfo{};
  inputToReferenceXyzBufferInfo.buffer = staticFilm.inputToReferenceXyz.buffer;
  inputToReferenceXyzBufferInfo.offset = 0;
  inputToReferenceXyzBufferInfo.range = staticFilm.inputToReferenceXyz.capacity;
  VkDescriptorBufferInfo inputToSrgbBufferInfo{};
  inputToSrgbBufferInfo.buffer = staticFilm.inputToSrgb.buffer;
  inputToSrgbBufferInfo.offset = 0;
  inputToSrgbBufferInfo.range = staticFilm.inputToSrgb.capacity;
  VkDescriptorBufferInfo colorDecodeLutsBufferInfo{};
  colorDecodeLutsBufferInfo.buffer = staticFilm.colorDecodeLuts.buffer;
  colorDecodeLutsBufferInfo.offset = 0;
  colorDecodeLutsBufferInfo.range = staticFilm.colorDecodeLuts.capacity;
  VkDescriptorBufferInfo colorTransferKindsBufferInfo{};
  colorTransferKindsBufferInfo.buffer = staticFilm.colorTransferKinds.buffer;
  colorTransferKindsBufferInfo.offset = 0;
  colorTransferKindsBufferInfo.range = staticFilm.colorTransferKinds.capacity;
  VkDescriptorBufferInfo mallettRawMatrixBufferInfo{};
  mallettRawMatrixBufferInfo.buffer = staticFilm.mallettRawMatrix.buffer;
  mallettRawMatrixBufferInfo.offset = 0;
  mallettRawMatrixBufferInfo.range = staticFilm.mallettRawMatrix.capacity;
  VkDescriptorBufferInfo hanatosRawResponseBufferInfo{};
  hanatosRawResponseBufferInfo.buffer = staticFilm.hanatosRawResponse.buffer;
  hanatosRawResponseBufferInfo.offset = 0;
  hanatosRawResponseBufferInfo.range = staticFilm.hanatosRawResponse.capacity;
  VkDescriptorBufferInfo paperHanatosResponseBufferInfo{};
  paperHanatosResponseBufferInfo.buffer = staticFilm.paperHanatosResponse.buffer;
  paperHanatosResponseBufferInfo.offset = 0;
  paperHanatosResponseBufferInfo.range = staticFilm.paperHanatosResponse.capacity;
  VkDescriptorBufferInfo preflashPaperHanatosResponseBufferInfo{};
  preflashPaperHanatosResponseBufferInfo.buffer = staticFilm.preflashPaperHanatosResponse.buffer;
  preflashPaperHanatosResponseBufferInfo.offset = 0;
  preflashPaperHanatosResponseBufferInfo.range = staticFilm.preflashPaperHanatosResponse.capacity;
  VkDescriptorBufferInfo paperLogExposureBufferInfo{};
  paperLogExposureBufferInfo.buffer = staticFilm.paperLogExposure.buffer;
  paperLogExposureBufferInfo.offset = 0;
  paperLogExposureBufferInfo.range = staticFilm.paperLogExposure.capacity;
  VkDescriptorBufferInfo paperDensityCurvesBufferInfo{};
  paperDensityCurvesBufferInfo.buffer = staticFilm.paperDensityCurves.buffer;
  paperDensityCurvesBufferInfo.offset = 0;
  paperDensityCurvesBufferInfo.range = staticFilm.paperDensityCurves.capacity;
  VkDescriptorBufferInfo filmChannelDensityBufferInfo{};
  filmChannelDensityBufferInfo.buffer = staticFilm.filmChannelDensity.buffer;
  filmChannelDensityBufferInfo.offset = 0;
  filmChannelDensityBufferInfo.range = staticFilm.filmChannelDensity.capacity;
  VkDescriptorBufferInfo filmBaseTransmittanceBufferInfo{};
  filmBaseTransmittanceBufferInfo.buffer = staticFilm.filmBaseTransmittance.buffer;
  filmBaseTransmittanceBufferInfo.offset = 0;
  filmBaseTransmittanceBufferInfo.range = staticFilm.filmBaseTransmittance.capacity;
  VkDescriptorBufferInfo paperLogSensitivityBufferInfo{};
  paperLogSensitivityBufferInfo.buffer = staticFilm.paperLogSensitivity.buffer;
  paperLogSensitivityBufferInfo.offset = 0;
  paperLogSensitivityBufferInfo.range = staticFilm.paperLogSensitivity.capacity;
  VkDescriptorBufferInfo filteredEnlargerResponseBufferInfo{};
  filteredEnlargerResponseBufferInfo.buffer = coreFrame.filteredEnlargerResponse.buffer;
  filteredEnlargerResponseBufferInfo.offset = 0;
  filteredEnlargerResponseBufferInfo.range = coreFrame.filteredEnlargerResponse.capacity;
  VkDescriptorBufferInfo thKg3IlluminantBufferInfo{};
  thKg3IlluminantBufferInfo.buffer = staticFilm.thKg3Illuminant.buffer;
  thKg3IlluminantBufferInfo.offset = 0;
  thKg3IlluminantBufferInfo.range = staticFilm.thKg3Illuminant.capacity;
  VkDescriptorBufferInfo customEnlargerFiltersBufferInfo{};
  customEnlargerFiltersBufferInfo.buffer = staticFilm.customEnlargerFilters.buffer;
  customEnlargerFiltersBufferInfo.offset = 0;
  customEnlargerFiltersBufferInfo.range = staticFilm.customEnlargerFilters.capacity;
  VkDescriptorBufferInfo neutralPrintFiltersBufferInfo{};
  neutralPrintFiltersBufferInfo.buffer = staticFilm.neutralPrintFilters.buffer;
  neutralPrintFiltersBufferInfo.offset = 0;
  neutralPrintFiltersBufferInfo.range = staticFilm.neutralPrintFilters.capacity;
  VkDescriptorBufferInfo paperChannelDensityBufferInfo{};
  paperChannelDensityBufferInfo.buffer = staticFilm.paperChannelDensity.buffer;
  paperChannelDensityBufferInfo.offset = 0;
  paperChannelDensityBufferInfo.range = staticFilm.paperChannelDensity.capacity;
  VkDescriptorBufferInfo paperBaseTransmittanceBufferInfo{};
  paperBaseTransmittanceBufferInfo.buffer = staticFilm.paperBaseTransmittance.buffer;
  paperBaseTransmittanceBufferInfo.offset = 0;
  paperBaseTransmittanceBufferInfo.range = staticFilm.paperBaseTransmittance.capacity;
  VkDescriptorBufferInfo filmScanIlluminantBufferInfo{};
  filmScanIlluminantBufferInfo.buffer = staticFilm.filmScanIlluminant.buffer;
  filmScanIlluminantBufferInfo.offset = 0;
  filmScanIlluminantBufferInfo.range = staticFilm.filmScanIlluminant.capacity;
  VkDescriptorBufferInfo paperScanIlluminantBufferInfo{};
  paperScanIlluminantBufferInfo.buffer = staticFilm.paperScanIlluminant.buffer;
  paperScanIlluminantBufferInfo.offset = 0;
  paperScanIlluminantBufferInfo.range = staticFilm.paperScanIlluminant.capacity;
  VkDescriptorBufferInfo standardObserverCmfsBufferInfo{};
  standardObserverCmfsBufferInfo.buffer = staticFilm.standardObserverCmfs.buffer;
  standardObserverCmfsBufferInfo.offset = 0;
  standardObserverCmfsBufferInfo.range = staticFilm.standardObserverCmfs.capacity;
  VkDescriptorBufferInfo filmScanToOutputRgbBufferInfo{};
  filmScanToOutputRgbBufferInfo.buffer = staticFilm.filmScanToOutputRgb.buffer;
  filmScanToOutputRgbBufferInfo.offset = 0;
  filmScanToOutputRgbBufferInfo.range = staticFilm.filmScanToOutputRgb.capacity;
  VkDescriptorBufferInfo paperScanToOutputRgbBufferInfo{};
  paperScanToOutputRgbBufferInfo.buffer = staticFilm.paperScanToOutputRgb.buffer;
  paperScanToOutputRgbBufferInfo.offset = 0;
  paperScanToOutputRgbBufferInfo.range = staticFilm.paperScanToOutputRgb.capacity;
  VkDescriptorBufferInfo colorEncodeLutsBufferInfo{};
  colorEncodeLutsBufferInfo.buffer = staticFilm.colorEncodeLuts.buffer;
  colorEncodeLutsBufferInfo.offset = 0;
  colorEncodeLutsBufferInfo.range = staticFilm.colorEncodeLuts.capacity;
  VkDescriptorBufferInfo academyPrinterDensityDataBufferInfo{};
  academyPrinterDensityDataBufferInfo.buffer = staticFilm.academyPrinterDensityData.buffer;
  academyPrinterDensityDataBufferInfo.offset = 0;
  academyPrinterDensityDataBufferInfo.range = staticFilm.academyPrinterDensityData.capacity;
  auto phaseBufferInfo = [&](size_t slot, VkDeviceSize range) {
    VkDescriptorBufferInfo info{};
    info.buffer = coreFrame.phaseScratch[slot].buffer;
    info.offset = 0;
    info.range = range;
    return info;
  };
  auto phaseBufferInfoOr = [&](size_t slot, size_t fallbackSlot, VkDeviceSize range) {
    const size_t resolvedSlot =
      coreFrame.phaseScratch[slot].buffer != VK_NULL_HANDLE ? slot : fallbackSlot;
    return phaseBufferInfo(resolvedSlot, std::min(range, coreFrame.phaseScratch[resolvedSlot].capacity));
  };
  const VkDescriptorBufferInfo halationRawABufferInfo = phaseBufferInfo(0u, pixelStorageByteCount);
  const VkDescriptorBufferInfo halationRawBBufferInfo = phaseBufferInfo(1u, pixelStorageByteCount);
  const VkDescriptorBufferInfo halationRawCBufferInfo = phaseBufferInfo(2u, pixelStorageByteCount);
  const VkDescriptorBufferInfo halationRawDBufferInfo = phaseBufferInfo(3u, pixelStorageByteCount);
  const VkDescriptorBufferInfo halationBoostedRawBufferInfo =
    phaseBufferInfo(halationBoostedRawSlot, pixelStorageByteCount);
  VkDescriptorBufferInfo halationBoostChunksBufferInfo{};
  halationBoostChunksBufferInfo.buffer = coreFrame.halationBoostChunks.buffer;
  halationBoostChunksBufferInfo.offset = 0;
  halationBoostChunksBufferInfo.range = coreFrame.halationBoostChunks.capacity;
  VkDescriptorBufferInfo halationBoostInfoBufferInfo{};
  halationBoostInfoBufferInfo.buffer = coreFrame.halationBoostInfo.buffer;
  halationBoostInfoBufferInfo.offset = 0;
  halationBoostInfoBufferInfo.range = coreFrame.halationBoostInfo.capacity;
  VkDescriptorBufferInfo tiledHalationBoostInfoBufferInfo{};
  tiledHalationBoostInfoBufferInfo.buffer = coreFrame.tiledHalationBoostInfo.buffer;
  tiledHalationBoostInfoBufferInfo.offset = 0;
  tiledHalationBoostInfoBufferInfo.range = coreFrame.tiledHalationBoostInfo.capacity;
  const VkDescriptorBufferInfo halationLogRawBufferInfo = phaseBufferInfo(4u, pixelStorageByteCount);
  const VkDescriptorBufferInfo diffusionTempBufferInfo = phaseBufferInfo(2u, diffusionTempByteCount);
  const VkDescriptorBufferInfo diffusionAccumBufferInfo = phaseBufferInfo(3u, diffusionOutputByteCount);
  const VkDescriptorBufferInfo diffusionDownsampleSourceBufferInfo =
    phaseBufferInfoOr(4u, 2u, diffusionDownsamplePath ? diffusionDownsampleByteCount : diffusionTempByteCount);
  const VkDescriptorBufferInfo diffusionDownsampleTempBufferInfo =
    phaseBufferInfoOr(5u, 2u, diffusionDownsamplePath ? diffusionDownsampleGroupByteCount : diffusionTempByteCount);
  const VkDescriptorBufferInfo diffusionDownsampleBlurBufferInfo =
    phaseBufferInfoOr(6u, 2u, diffusionDownsamplePath ? diffusionDownsampleGroupByteCount : diffusionTempByteCount);
  const VkDescriptorBufferInfo cameraDiffusionRawBufferInfo = phaseBufferInfo(7u, diffusionOutputByteCount);
  const VkDescriptorBufferInfo printRawBufferInfo = phaseBufferInfo(1u, pixelStorageByteCount);
  const VkDescriptorBufferInfo printDiffusionRawBufferInfo = phaseBufferInfo(7u, pixelStorageByteCount);
  VkDescriptorBufferInfo cameraDiffusionInfoBufferInfo{};
  cameraDiffusionInfoBufferInfo.buffer = coreFrame.cameraDiffusionInfo.buffer;
  cameraDiffusionInfoBufferInfo.offset = 0;
  cameraDiffusionInfoBufferInfo.range = coreFrame.cameraDiffusionInfo.capacity;
  VkDescriptorBufferInfo cameraDiffusionComponentsBufferInfo{};
  cameraDiffusionComponentsBufferInfo.buffer = coreFrame.cameraDiffusionComponents.buffer;
  cameraDiffusionComponentsBufferInfo.offset = 0;
  cameraDiffusionComponentsBufferInfo.range = coreFrame.cameraDiffusionComponents.capacity;
  VkDescriptorBufferInfo printDiffusionInfoBufferInfo{};
  printDiffusionInfoBufferInfo.buffer = coreFrame.printDiffusionInfo.buffer;
  printDiffusionInfoBufferInfo.offset = 0;
  printDiffusionInfoBufferInfo.range = coreFrame.printDiffusionInfo.capacity;
  VkDescriptorBufferInfo printDiffusionComponentsBufferInfo{};
  printDiffusionComponentsBufferInfo.buffer = coreFrame.printDiffusionComponents.buffer;
  printDiffusionComponentsBufferInfo.offset = 0;
  printDiffusionComponentsBufferInfo.range = coreFrame.printDiffusionComponents.capacity;
  const VkDescriptorBufferInfo dirCorrectionABufferInfo = phaseBufferInfo(0u, pixelStorageByteCount);
  const VkDescriptorBufferInfo dirCorrectionBBufferInfo = phaseBufferInfo(1u, pixelStorageByteCount);
  const VkDescriptorBufferInfo dirCorrectionCBufferInfo = phaseBufferInfo(2u, pixelStorageByteCount);
  const VkDescriptorBufferInfo dirDensityBufferInfo = phaseBufferInfo(3u, pixelStorageByteCount);
  VkDescriptorBufferInfo dirFloatsBufferInfo{};
  dirFloatsBufferInfo.buffer = coreFrame.dirFloats.buffer;
  dirFloatsBufferInfo.offset = 0;
  dirFloatsBufferInfo.range = coreFrame.dirFloats.capacity;
  VkDescriptorBufferInfo dirCorrectedDensityCurvesBufferInfo{};
  dirCorrectedDensityCurvesBufferInfo.buffer = coreFrame.dirCorrectedDensityCurves.buffer;
  dirCorrectedDensityCurvesBufferInfo.offset = 0;
  dirCorrectedDensityCurvesBufferInfo.range = coreFrame.dirCorrectedDensityCurves.capacity;
  const VkDescriptorBufferInfo scannerPostABufferInfo = phaseBufferInfo(6u, pixelStorageByteCount);
  const VkDescriptorBufferInfo scannerPostBBufferInfo = phaseBufferInfo(0u, pixelStorageByteCount);
  const VkDescriptorBufferInfo scannerPostCBufferInfo = phaseBufferInfo(1u, pixelStorageByteCount);
  const VkDescriptorBufferInfo printGlareABufferInfo = phaseBufferInfo(2u, pixelStorageByteCount);
  const VkDescriptorBufferInfo printGlareBBufferInfo = phaseBufferInfo(3u, pixelStorageByteCount);
  const size_t grainDensityASlot = layeredGrainPath ? 1u : 0u;
  const size_t grainDensityBSlot = layeredGrainPath ? 0u : 1u;
  const VkDescriptorBufferInfo grainDensityABufferInfo =
    phaseBufferInfo(grainDensityASlot, pixelStorageByteCount);
  const VkDescriptorBufferInfo grainDensityBBufferInfo =
    phaseBufferInfo(grainDensityBSlot, pixelStorageByteCount);
  // The simple grain pass does not access microstructure or layered storage.
  const VkDescriptorBufferInfo grainMicroABufferInfo = layeredGrainPath
    ? phaseBufferInfo(2u, pixelStorageByteCount) : sourceBufferInfo;
  const VkDescriptorBufferInfo grainMicroBBufferInfo = layeredGrainPath
    ? phaseBufferInfo(4u, pixelStorageByteCount) : sourceBufferInfo;
  VkDescriptorBufferInfo grainLayerABufferInfo = sourceBufferInfo;
  VkDescriptorBufferInfo grainLayerBBufferInfo = filmRawBufferInfo;
  if (layeredGrainPath) {
    grainLayerABufferInfo.range = grainLayerByteCount;
    grainLayerBBufferInfo.range = grainLayerByteCount;
  }
  const VkDescriptorBufferInfo &dirCorrectionFinalBufferInfo = dirPath
    ? (dirTailPath ? dirCorrectionBBufferInfo : (dirBlurPath ? dirCorrectionCBufferInfo : dirCorrectionABufferInfo))
    : dirCorrectionABufferInfo;
  const VkDescriptorBufferInfo &dirDensityOutputBufferInfo =
    (printScanPassEnabled || grainPath) ? dirDensityBufferInfo : destinationBufferInfo;
  const VkDescriptorBufferInfo &preGrainFilmDensityBufferInfo = dirPath ? dirDensityBufferInfo : filmDensityBufferInfo;
  const VkDescriptorBufferInfo &grainFinalDensityBufferInfo = productionGrainPath || grainSynthesisPath
    ? (printScanPassEnabled ? grainDensityBBufferInfo : destinationBufferInfo)
    : (previewGrainPath ? (printScanPassEnabled ? grainDensityABufferInfo : destinationBufferInfo) : preGrainFilmDensityBufferInfo);
  const VkDescriptorBufferInfo &finalFilmDensityBufferInfo = grainPath
    ? grainFinalDensityBufferInfo
    : (dirPath ? (printScanPassEnabled ? dirDensityBufferInfo : destinationBufferInfo) : filmDensityBufferInfo);
  const VkDescriptorBufferInfo &developInputBufferInfo = preExposureRawPath && !writingDiffusionMilestone
    ? halationLogRawBufferInfo : filmRawBufferInfo;
  VkDescriptorBufferInfo frameFloatsBufferInfo{};
  frameFloatsBufferInfo.buffer = coreFrame.frameFloats.buffer;
  frameFloatsBufferInfo.offset = 0;
  frameFloatsBufferInfo.range = coreFrame.frameFloats.capacity;
  VkDescriptorBufferInfo frameIntsBufferInfo{};
  frameIntsBufferInfo.buffer = coreFrame.frameInts.buffer;
  frameIntsBufferInfo.offset = 0;
  frameIntsBufferInfo.range = coreFrame.frameInts.capacity;

  std::array<VkWriteDescriptorSet, 512> writes{};
  if (samplePrint) {
    const std::array<VkDescriptorBufferInfo, 4> infos{{
      {coreFrame.samples.readback.buffer, FrameSamples::kProbeOffset, FrameSamples::kProbeBytes},
      frameFloatsBufferInfo, frameIntsBufferInfo,
      {finalFilmDensityBufferInfo.buffer, finalFilmDensityBufferInfo.offset, sizeof(float) * 4u}}};
    std::array<VkWriteDescriptorSet, 4> probeWrites{};
    for (uint32_t index = 0; index < probeWrites.size(); ++index) {
      auto& write = probeWrites[index];
      write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      write.dstSet = coreFrame.samples.probeSet;
      write.dstBinding = index;
      write.descriptorCount = 1;
      write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      write.pBufferInfo = &infos[index];
    }
    vkUpdateDescriptorSets(device, static_cast<uint32_t>(probeWrites.size()), probeWrites.data(), 0, nullptr);
  }
  std::array<VkDescriptorBufferInfo, 512> writeBufferInfos{};
  uint32_t writeCount = 0;
  auto writeLutTexture = [&](VkDescriptorSet set) {
    if (!textureLut) return;
    auto &write = writes[writeCount++];
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = set;
    write.dstBinding = SPEKTRA_FOUR_STAGE_LUT_TEXTURE_BINDING;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &fourStageTextureInfo;
    if (composedPrintColor && (set == coreFrame.exposureDescriptorSet || set == finalPrintDescriptorSet)) {
      auto &composedWrite = writes[writeCount++];
      composedWrite = write;
      composedWrite.dstBinding = SPEKTRA_COMPOSED_LUT_TEXTURE_BINDING;
      composedWrite.pImageInfo = &composedTextureInfo;
    }
  };
  bool writesOverflowed = false;
  auto writeStorageBuffer = [&](VkDescriptorSet set, uint32_t binding, const VkDescriptorBufferInfo &bufferInfo) {
    if (writeCount >= writes.size()) {
      writesOverflowed = true;
      return;
    }
    VkWriteDescriptorSet &write = writes[writeCount];
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = set;
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    // Own each description until the batched descriptor update has consumed it.
    writeBufferInfos[writeCount] = bufferInfo;
    write.pBufferInfo = &writeBufferInfos[writeCount];
    ++writeCount;
  };
  if (params.autoExposure) {
    const VkDescriptorBufferInfo exposureSourceInfo = externalImages
      ? VkDescriptorBufferInfo{coreFrame.autoExposureSource.buffer, 0, coreFrame.autoExposureSource.capacity}
      : sourceBufferInfo;
    writeStorageBuffer(coreFrame.autoExposureDescriptorSet, 0, exposureSourceInfo);
    writeStorageBuffer(coreFrame.autoExposureDescriptorSet, 5, colorDecodeLutsBufferInfo);
    writeStorageBuffer(coreFrame.autoExposureDescriptorSet, 6, colorTransferKindsBufferInfo);
    writeStorageBuffer(coreFrame.autoExposureDescriptorSet, 8, inputMeterXyzBufferInfo);
    writeStorageBuffer(coreFrame.autoExposureDescriptorSet, 29, autoExposureLuminanceBufferInfo);
    writeStorageBuffer(coreFrame.autoExposureDescriptorSet, 30, autoExposureResultBufferInfo);
  }
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 0, sourceBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 1, filmRawBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 2, logExposureBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 3, densityCurvesBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 4, inputToSrgbBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 5, colorDecodeLutsBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 6, colorTransferKindsBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 7, mallettRawMatrixBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 8, inputToReferenceXyzBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 9, hanatosRawResponseBufferInfo);
  writeStorageBuffer(coreFrame.exposureDescriptorSet, 30, autoExposureResultBufferInfo);
  writeStorageBuffer(coreFrame.developDescriptorSet, 0, developInputBufferInfo);
  writeStorageBuffer(coreFrame.developDescriptorSet, 1, developOutputBufferInfo);
  writeStorageBuffer(coreFrame.developDescriptorSet, 2, logExposureBufferInfo);
  writeStorageBuffer(coreFrame.developDescriptorSet, 3, densityCurvesBufferInfo);
  if (fourStageLut) {
    writeStorageBuffer(coreFrame.exposureDescriptorSet, 10, fourStageLutBufferInfo);
    writeStorageBuffer(coreFrame.developDescriptorSet, 10, fourStageLutBufferInfo);
    writeLutTexture(coreFrame.exposureDescriptorSet);
    writeLutTexture(coreFrame.developDescriptorSet);
  }
  auto writePrintScanSet = [&](
    VkDescriptorSet set,
    const VkDescriptorBufferInfo &inputBufferInfo,
    const VkDescriptorBufferInfo &outputBufferInfo
  ) {
    writeLutTexture(set);
    writeStorageBuffer(set, 0, inputBufferInfo);
    writeStorageBuffer(set, 1, outputBufferInfo);
    writeStorageBuffer(set, 2, logExposureBufferInfo);
    writeStorageBuffer(set, 3, bakedDir ? dirCorrectedDensityCurvesBufferInfo : densityCurvesBufferInfo);
    // Preview LUTs use the print shader's otherwise unused matrix slot.
    writeStorageBuffer(set, 4, fourStageLut ? fourStageLutBufferInfo :
      previewColorLut ? colorLutBufferInfo : inputToSrgbBufferInfo);
    writeStorageBuffer(set, 5, colorDecodeLutsBufferInfo);
    writeStorageBuffer(set, 6, colorTransferKindsBufferInfo);
    writeStorageBuffer(set, 7, mallettRawMatrixBufferInfo);
    writeStorageBuffer(set, 8, inputToReferenceXyzBufferInfo);
    writeStorageBuffer(set, 9, hanatosRawResponseBufferInfo);
    writeStorageBuffer(set, 10, paperLogExposureBufferInfo);
    writeStorageBuffer(set, 11, paperDensityCurvesBufferInfo);
    writeStorageBuffer(set, 12, filmChannelDensityBufferInfo);
    writeStorageBuffer(set, 13, filmBaseTransmittanceBufferInfo);
    writeStorageBuffer(set, 14, filteredEnlargerResponseBufferInfo);
    writeStorageBuffer(set, 15, thKg3IlluminantBufferInfo);
    writeStorageBuffer(set, 16, customEnlargerFiltersBufferInfo);
    writeStorageBuffer(set, 17, neutralPrintFiltersBufferInfo);
    writeStorageBuffer(set, 18, paperChannelDensityBufferInfo);
    writeStorageBuffer(set, 19, paperBaseTransmittanceBufferInfo);
    writeStorageBuffer(set, 20, filmScanIlluminantBufferInfo);
    writeStorageBuffer(set, 21, paperLogSensitivityBufferInfo);
    writeStorageBuffer(set, 22, standardObserverCmfsBufferInfo);
    writeStorageBuffer(set, 23, filmScanToOutputRgbBufferInfo);
    writeStorageBuffer(set, 24, paperScanToOutputRgbBufferInfo);
    writeStorageBuffer(set, 25, colorEncodeLutsBufferInfo);
    writeStorageBuffer(set, 26, academyPrinterDensityDataBufferInfo);
    writeStorageBuffer(set, 27, frameFloatsBufferInfo);
    writeStorageBuffer(set, 28, frameIntsBufferInfo);
    writeStorageBuffer(set, 29, paperHanatosResponseBufferInfo);
    writeStorageBuffer(set, 30, preflashPaperHanatosResponseBufferInfo);
  };
  if (printScanPassEnabled) {
    const VkDescriptorBufferInfo &printScanInputBufferInfo = finalProcessNegative
      ? sourceBufferInfo
      : finalFilmDensityBufferInfo;
    if (printDiffusionPath) {
      writePrintScanSet(printDiffusionSets[0], printScanInputBufferInfo, printRawBufferInfo);
      writePrintScanSet(
        printDiffusionSets[1],
        printDiffusionRawBufferInfo,
        scannerPostPath ? scannerPostABufferInfo : destinationBufferInfo
      );
    } else {
      writePrintScanSet(
        finalPrintDescriptorSet,
        printScanInputBufferInfo,
        scannerPostPath ? scannerPostABufferInfo : destinationBufferInfo
      );
    }
  }
  auto writeHalationSet = [&](
    VkDescriptorSet set,
    const VkDescriptorBufferInfo &binding0,
    const VkDescriptorBufferInfo &binding1,
    const VkDescriptorBufferInfo &binding2,
    const VkDescriptorBufferInfo &binding3
  ) {
    writeStorageBuffer(set, 0, binding0);
    writeStorageBuffer(set, 1, binding1);
    writeStorageBuffer(set, 2, binding2);
    writeStorageBuffer(set, 3, binding3);
    writeStorageBuffer(set, 27, frameFloatsBufferInfo);
  };
  auto writeDiffusionSet = [&](
    VkDescriptorSet set,
    const VkDescriptorBufferInfo &source,
    const VkDescriptorBufferInfo &temp,
    const VkDescriptorBufferInfo &accum,
    const VkDescriptorBufferInfo &destination,
    const VkDescriptorBufferInfo &info,
    const VkDescriptorBufferInfo &components
  ) {
    writeStorageBuffer(set, 0, source);
    writeStorageBuffer(set, 1, temp);
    writeStorageBuffer(set, 2, accum);
    writeStorageBuffer(set, 3, destination);
    writeStorageBuffer(set, 4, diffusionDownsampleSourceBufferInfo);
    writeStorageBuffer(set, 5, diffusionDownsampleTempBufferInfo);
    writeStorageBuffer(set, 6, diffusionDownsampleBlurBufferInfo);
    if (writingDiffusionMilestone) {
      const VkDescriptorBufferInfo regions{coreFrame.diffusionRegions.buffer, 0, sizeof(DiffusionRegions)};
      writeStorageBuffer(set, 26, regions);
    }
    writeStorageBuffer(set, 27, info);
    writeStorageBuffer(set, 28, components);
  };
  if (cameraDiffusionPath) {
    const VkDescriptorBufferInfo &cameraDiffusionSourceBufferInfo =
      halationBoostEnabled ? halationBoostedRawBufferInfo : filmRawBufferInfo;
    writeDiffusionSet(
      coreFrame.diffusionDescriptorSets[0],
      cameraDiffusionSourceBufferInfo,
      diffusionTempBufferInfo,
      diffusionAccumBufferInfo,
      cameraDiffusionRawBufferInfo,
      cameraDiffusionInfoBufferInfo,
      cameraDiffusionComponentsBufferInfo
    );
    if (!halationPassEnabled && !writingDiffusionMilestone) {
      writeDiffusionSet(
        coreFrame.diffusionDescriptorSets[1],
        cameraDiffusionRawBufferInfo,
        diffusionTempBufferInfo,
        diffusionAccumBufferInfo,
        halationLogRawBufferInfo,
        cameraDiffusionInfoBufferInfo,
        cameraDiffusionComponentsBufferInfo
      );
    }
  }
  if (milestoneToLog) {
    const VkDescriptorSet set = coreFrame.diffusionDescriptorSets[1];
    for (uint32_t binding = 0u; binding < 7u; ++binding) writeStorageBuffer(set, binding, filmRawBufferInfo);
    writeStorageBuffer(set, 27, autoExposureResultBufferInfo);
    writeStorageBuffer(set, 28, autoExposureResultBufferInfo);
  }
  if (printDiffusionPath) {
    writeDiffusionSet(
      coreFrame.diffusionDescriptorSets[2],
      printRawBufferInfo,
      diffusionTempBufferInfo,
      diffusionAccumBufferInfo,
      printDiffusionRawBufferInfo,
      printDiffusionInfoBufferInfo,
      printDiffusionComponentsBufferInfo
    );
  }
  auto writeDirSet = [&](
    VkDescriptorSet set,
    const VkDescriptorBufferInfo &binding0,
    const VkDescriptorBufferInfo &binding1,
    const VkDescriptorBufferInfo &binding2,
    const VkDescriptorBufferInfo &binding3,
    const VkDescriptorBufferInfo &curves
  ) {
    writeStorageBuffer(set, 0, binding0);
    writeStorageBuffer(set, 1, binding1);
    writeStorageBuffer(set, 2, binding2);
    writeStorageBuffer(set, 3, binding3);
    writeStorageBuffer(set, 4, logExposureBufferInfo);
    writeStorageBuffer(set, 5, curves);
    writeStorageBuffer(set, 27, dirFloatsBufferInfo);
  };
  if (dirPath) {
    writeDirSet(coreFrame.dirDescriptorSets[0], filmDensityBufferInfo, dirCorrectionABufferInfo, dirCorrectionABufferInfo, dirCorrectionABufferInfo, dirCorrectedDensityCurvesBufferInfo);
    writeDirSet(coreFrame.dirDescriptorSets[1], dirCorrectionABufferInfo, dirCorrectionBBufferInfo, dirCorrectionABufferInfo, dirCorrectionABufferInfo, dirCorrectedDensityCurvesBufferInfo);
    writeDirSet(coreFrame.dirDescriptorSets[2], dirCorrectionBBufferInfo, dirCorrectionCBufferInfo, dirCorrectionABufferInfo, dirCorrectionCBufferInfo, dirCorrectedDensityCurvesBufferInfo);
    const VkDescriptorBufferInfo &tailBaseBufferInfo = dirBlurPath ? dirCorrectionCBufferInfo : dirCorrectionABufferInfo;
    writeDirSet(coreFrame.dirDescriptorSets[3], tailBaseBufferInfo, dirCorrectionBBufferInfo, dirCorrectionABufferInfo, dirCorrectionBBufferInfo, dirCorrectedDensityCurvesBufferInfo);
    writeDirSet(coreFrame.dirDescriptorSets[4], dirCorrectionABufferInfo, dirCorrectionCBufferInfo, dirCorrectionABufferInfo, dirCorrectionCBufferInfo, dirCorrectedDensityCurvesBufferInfo);
    writeDirSet(coreFrame.dirDescriptorSets[5], dirCorrectionCBufferInfo, dirCorrectionBBufferInfo, dirCorrectionABufferInfo, dirCorrectionBBufferInfo, dirCorrectedDensityCurvesBufferInfo);
    writeDirSet(coreFrame.dirDescriptorSets[6], developInputBufferInfo, dirCorrectionABufferInfo, dirCorrectionFinalBufferInfo, dirDensityOutputBufferInfo, dirCorrectedDensityCurvesBufferInfo);
    if (dirTailPath && dirTailScale > 1u) {
      const VkDeviceSize alignment = std::max<VkDeviceSize>(16u,
        physicalDeviceProperties.limits.minStorageBufferOffsetAlignment);
      const VkDeviceSize planeBytes = static_cast<VkDeviceSize>(dirTailReducedWidth) * dirTailReducedHeight * 16u;
      const VkDeviceSize planeStride = (planeBytes + alignment - 1u) / alignment * alignment;
      if (planeStride * 3u <= dirCorrectionCBufferInfo.range) {
        VkDescriptorBufferInfo planes[3];
        for (uint32_t plane = 0u; plane < 3u; ++plane) {
          planes[plane] = dirCorrectionCBufferInfo;
          planes[plane].offset += plane * planeStride;
          planes[plane].range = planeBytes;
        }
        writeDirSet(coreFrame.dirDescriptorSets[7], dirCorrectionABufferInfo, planes[0], dirCorrectionABufferInfo, dirCorrectionABufferInfo, dirCorrectedDensityCurvesBufferInfo);
        writeDirSet(coreFrame.dirDescriptorSets[8], planes[0], planes[1], dirCorrectionABufferInfo, dirCorrectionABufferInfo, dirCorrectedDensityCurvesBufferInfo);
        writeDirSet(coreFrame.dirDescriptorSets[9], planes[1], planes[2], dirCorrectionABufferInfo, dirCorrectionABufferInfo, dirCorrectedDensityCurvesBufferInfo);
        writeDirSet(coreFrame.dirDescriptorSets[10], planes[2], dirCorrectionBBufferInfo, dirCorrectionABufferInfo, dirCorrectionBBufferInfo, dirCorrectedDensityCurvesBufferInfo);
      } else {
        dirTailScale = 1u;
      }
    }
  }
  auto writeScannerPostSet = [&](
    VkDescriptorSet set,
    const VkDescriptorBufferInfo &source,
    const VkDescriptorBufferInfo &temp,
    const VkDescriptorBufferInfo &unsharp,
    const VkDescriptorBufferInfo &destination
  ) {
    writeStorageBuffer(set, 0, source);
    writeStorageBuffer(set, 1, temp);
    writeStorageBuffer(set, 2, unsharp);
    writeStorageBuffer(set, 3, destination);
    writeStorageBuffer(set, 6, colorTransferKindsBufferInfo);
    writeStorageBuffer(set, 25, colorEncodeLutsBufferInfo);
    writeStorageBuffer(set, 27, frameFloatsBufferInfo);
    writeStorageBuffer(set, 28, frameIntsBufferInfo);
  };
  if (scannerPostPath) {
    const VkDescriptorBufferInfo &postGlareBufferInfo = printGlarePath ? scannerPostBBufferInfo : scannerPostABufferInfo;
    const VkDescriptorBufferInfo &postBlurBufferInfo = scannerBlurPath ? scannerPostABufferInfo : postGlareBufferInfo;
    const bool postBlurBufferIsA = scannerBlurPath || !printGlarePath;
    const VkDescriptorBufferInfo &unsharpBlurBufferInfo = postBlurBufferIsA ? scannerPostBBufferInfo : scannerPostABufferInfo;
    if (printGlarePath) {
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[0], scannerPostABufferInfo, printGlareABufferInfo, scannerPostABufferInfo, printGlareABufferInfo);
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[1], printGlareABufferInfo, printGlareBBufferInfo, printGlareABufferInfo, printGlareBBufferInfo);
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[2], printGlareBBufferInfo, printGlareABufferInfo, printGlareBBufferInfo, printGlareABufferInfo);
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[3], scannerPostABufferInfo, printGlareABufferInfo, scannerPostABufferInfo, scannerPostBBufferInfo);
    }
    if (scannerBlurPath) {
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[4], postGlareBufferInfo, scannerPostCBufferInfo, postGlareBufferInfo, scannerPostCBufferInfo);
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[5], scannerPostCBufferInfo, scannerPostABufferInfo, scannerPostCBufferInfo, scannerPostABufferInfo);
    }
    if (scannerUnsharpPath) {
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[6], postBlurBufferInfo, scannerPostCBufferInfo, postBlurBufferInfo, scannerPostCBufferInfo);
      writeScannerPostSet(coreFrame.scannerPostDescriptorSets[7], scannerPostCBufferInfo, unsharpBlurBufferInfo, scannerPostCBufferInfo, unsharpBlurBufferInfo);
    }
    writeScannerPostSet(
      coreFrame.scannerPostDescriptorSets[8],
      postBlurBufferInfo,
      scannerPostCBufferInfo,
      scannerUnsharpPath ? unsharpBlurBufferInfo : postBlurBufferInfo,
      destinationBufferInfo
    );
  }
  auto writeGrainSet = [&](
    VkDescriptorSet set,
    const VkDescriptorBufferInfo &source,
    const VkDescriptorBufferInfo &destination,
    const VkDescriptorBufferInfo &auxA,
    const VkDescriptorBufferInfo &auxB
  ) {
    writeStorageBuffer(set, 0, source);
    writeStorageBuffer(set, 1, destination);
    writeStorageBuffer(set, 2, auxA);
    writeStorageBuffer(set, 3, auxB);
    writeStorageBuffer(set, 4, grainMicroABufferInfo);
    writeStorageBuffer(set, 5, grainMicroBBufferInfo);
    writeStorageBuffer(set, 6, grainLayerABufferInfo);
    writeStorageBuffer(set, 7, grainLayerBBufferInfo);
    writeStorageBuffer(set, 8, densityCurvesBufferInfo);
    writeStorageBuffer(set, 9, densityCurveLayersBufferInfo);
    writeStorageBuffer(set, 10, densityCurveLayerMaximaBufferInfo);
    writeStorageBuffer(set, 27, frameFloatsBufferInfo);
    writeStorageBuffer(set, 28, frameIntsBufferInfo);
  };
  if (previewGrainPath) {
    writeGrainSet(
      coreFrame.grainDescriptorSets[0],
      preGrainFilmDensityBufferInfo,
      grainFinalDensityBufferInfo,
      grainDensityBBufferInfo,
      grainDensityBBufferInfo
    );
  }
  if (productionGrainPath || grainSynthesisPath) {
    writeGrainSet(
      coreFrame.grainDescriptorSets[1],
      preGrainFilmDensityBufferInfo,
      grainDensityABufferInfo,
      grainDensityBBufferInfo,
      grainDensityBBufferInfo
    );
    writeGrainSet(
      coreFrame.grainDescriptorSets[2],
      grainDensityABufferInfo,
      grainDensityBBufferInfo,
      grainDensityBBufferInfo,
      grainDensityABufferInfo
    );
    writeGrainSet(
      coreFrame.grainDescriptorSets[3],
      grainDensityBBufferInfo,
      grainDensityABufferInfo,
      grainDensityBBufferInfo,
      grainDensityABufferInfo
    );
    writeGrainSet(
      coreFrame.grainDescriptorSets[4],
      preGrainFilmDensityBufferInfo,
      grainDensityABufferInfo,
      grainFinalDensityBufferInfo,
      grainDensityBBufferInfo
    );
  }
  if (halationPassEnabled) {
    if (halationBoostEnabled) {
      if (halationBoostLocalReductionEnabled) {
        writeHalationSet(
          coreFrame.halationDescriptorSets[10],
          filmRawBufferInfo,
          halationBoostChunksBufferInfo,
          halationBoostInfoBufferInfo,
          halationBoostedRawBufferInfo
        );
        writeHalationSet(
          coreFrame.halationDescriptorSets[11],
          halationBoostChunksBufferInfo,
          halationBoostInfoBufferInfo,
          halationBoostInfoBufferInfo,
          halationBoostedRawBufferInfo
        );
      }
      const VkDescriptorBufferInfo &boostApplyInfoBufferInfo = halationBoostMilestoneEnabled
        ? tiledHalationBoostInfoBufferInfo
        : halationBoostInfoBufferInfo;
      writeHalationSet(
        coreFrame.halationDescriptorSets[12],
        filmRawBufferInfo,
        halationBoostedRawBufferInfo,
        boostApplyInfoBufferInfo,
        halationBoostedRawBufferInfo
      );
    }
    const VkDescriptorBufferInfo &fullScatterSourceBufferInfo = cameraDiffusionPath
      ? cameraDiffusionRawBufferInfo
      : (halationBoostEnabled ? halationBoostedRawBufferInfo : filmRawBufferInfo);
    const VkDescriptorBufferInfo previewSourceBufferInfo{coreFrame.halationPreviewSource.buffer, 0, halationPreviewBytes};
    const VkDescriptorBufferInfo &scatterSourceBufferInfo = halationPreviewScale > 1u
      ? previewSourceBufferInfo : fullScatterSourceBufferInfo;
    const VkDescriptorBufferInfo &halationResultBufferInfo = halationPreviewScale > 1u
      ? halationRawBBufferInfo : halationLogRawBufferInfo;
    if (halationPreviewScale > 1u) {
      writeHalationSet(coreFrame.halationDescriptorSets[13], fullScatterSourceBufferInfo, previewSourceBufferInfo,
                       fullScatterSourceBufferInfo, halationLogRawBufferInfo);
      writeHalationSet(coreFrame.halationDescriptorSets[14], fullScatterSourceBufferInfo, halationResultBufferInfo,
                       previewSourceBufferInfo, halationLogRawBufferInfo);
    }
    writeHalationSet(coreFrame.halationDescriptorSets[0], scatterSourceBufferInfo, halationRawABufferInfo, halationRawABufferInfo, halationRawABufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[1], halationRawABufferInfo, halationRawBBufferInfo, halationRawABufferInfo, halationRawBBufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[2], scatterSourceBufferInfo, halationRawCBufferInfo, halationRawABufferInfo, halationRawCBufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[3], scatterSourceBufferInfo, halationRawABufferInfo, halationRawABufferInfo, halationRawABufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[4], halationRawABufferInfo, halationRawCBufferInfo, halationRawABufferInfo, halationRawCBufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[5], scatterSourceBufferInfo, halationRawBBufferInfo, halationRawCBufferInfo, halationRawDBufferInfo);
    const VkDescriptorBufferInfo &bounceSourceBufferInfo = halationScatterEnabled ? halationRawDBufferInfo : scatterSourceBufferInfo;
    writeHalationSet(coreFrame.halationDescriptorSets[6], bounceSourceBufferInfo, halationRawCBufferInfo, halationRawABufferInfo, halationRawCBufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[7], bounceSourceBufferInfo, halationRawABufferInfo, halationRawABufferInfo, halationRawABufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[8], halationRawABufferInfo, halationRawCBufferInfo, halationRawABufferInfo, halationRawCBufferInfo);
    writeHalationSet(coreFrame.halationDescriptorSets[9], bounceSourceBufferInfo, halationRawCBufferInfo, halationRawABufferInfo, halationResultBufferInfo);
  }
  if (writesOverflowed) {
    lastError = "Invalid shared-device Spektra Vulkan render arguments.";
    return false;
  }
  if (!updateStorageDescriptors(writeCount, writes.data())) return false;
  auto updateFormatDescriptorSet = [&](
    VkDescriptorSet descriptorSet,
    const VkDescriptorBufferInfo &sourceInfo,
    const VkDescriptorBufferInfo &destinationInfo
  ) {
    VkWriteDescriptorSet formatWrites[2]{};
    formatWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    formatWrites[0].dstSet = descriptorSet;
    formatWrites[0].dstBinding = 0;
    formatWrites[0].descriptorCount = 1;
    formatWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    formatWrites[0].pBufferInfo = &sourceInfo;
    formatWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    formatWrites[1].dstSet = descriptorSet;
    formatWrites[1].dstBinding = 1;
    formatWrites[1].descriptorCount = 1;
    formatWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    formatWrites[1].pBufferInfo = &destinationInfo;
    return updateStorageDescriptors(2u, formatWrites);
  };
  if (destinationFormatConvert) {
    if (!updateFormatDescriptorSet(
      coreFrame.destinationFormatDescriptorSet,
      destinationBufferInfo,
      destinationHalfBufferInfo
    )) return false;
  }
  if (externalImages) {
    auto updateImageFormatDescriptorSet = [&](VkDescriptorSet descriptorSet, VkImage image,
                                               VkImageView borrowedView, bool &owned,
                                               const VkDescriptorBufferInfo &bufferInfo) -> VkImageView {
      VkDescriptorImageInfo imageInfo{};
      imageInfo.imageView = VK_NULL_HANDLE;
      imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
      VkImageViewCreateInfo viewInfo{};
      viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
      viewInfo.image = image;
      viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
      viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
      viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      VkImageView imageView = reuseSetup ? borrowedView : VK_NULL_HANDLE;
      owned = imageView == VK_NULL_HANDLE;
      if (owned && vkCreateImageView(device, &viewInfo, nullptr, &imageView) != VK_SUCCESS) {
        lastError = "Unable to create Vulkan image view for spektrafilm image conversion.";
        return VK_NULL_HANDLE;
      }
      if (directPreviewImages) return imageView;
      imageInfo.imageView = imageView;
      VkWriteDescriptorSet writes[2]{};
      writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[0].dstSet = descriptorSet;
      writes[0].dstBinding = 0;
      writes[0].descriptorCount = 1;
      writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
      writes[0].pImageInfo = &imageInfo;
      writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[1].dstSet = descriptorSet;
      writes[1].dstBinding = 1;
      writes[1].descriptorCount = 1;
      writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      writes[1].pBufferInfo = &bufferInfo;
      if (!updateStorageDescriptors(2u, writes)) {
        if (owned) vkDestroyImageView(device, imageView, nullptr);
        owned = false;
        return VK_NULL_HANDLE;
      }
      return imageView;
    };
    coreFrame.sourceImageView = updateImageFormatDescriptorSet(
      coreFrame.sourceImageFormatDescriptorSet, activeExternalImageContext.sourceImage,
      activeExternalImageContext.views.source, coreFrame.ownsSourceImageView, sourceBufferInfo);
    coreFrame.destinationImageView = updateImageFormatDescriptorSet(
      coreFrame.destinationImageFormatDescriptorSet, activeExternalImageContext.destinationImage,
      activeExternalImageContext.views.destination, coreFrame.ownsDestinationImageView,
      writingDiffusionMilestone ? sourceBufferInfo : (fusedPreviewOutput ? destinationBufferInfo : destinationHalfBufferInfo));
    if (coreFrame.sourceImageView == VK_NULL_HANDLE || coreFrame.destinationImageView == VK_NULL_HANDLE) {
      return false;
    }
    if (directPreviewImages) {
      const VkDescriptorImageInfo images[]{
        {VK_NULL_HANDLE, coreFrame.sourceImageView, VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, coreFrame.destinationImageView, VK_IMAGE_LAYOUT_GENERAL}};
      const VkDescriptorSet outputSet = fusedPreviewColor ? coreFrame.exposureDescriptorSet :
        scannerPostPath ? coreFrame.scannerPostDescriptorSets[8] :
        (printDiffusionPath ? printDiffusionSets[1] : finalPrintDescriptorSet);
      VkWriteDescriptorSet imageWrites[2]{};
      for (uint32_t index = 0; index < 2u; ++index) {
        auto &write = imageWrites[index];
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = index == 0u ? coreFrame.exposureDescriptorSet : outputSet;
        write.dstBinding = index == 0u ? SPEKTRA_PREVIEW_SOURCE_IMAGE_BINDING : SPEKTRA_PREVIEW_DESTINATION_IMAGE_BINDING;
        write.descriptorCount = 1u;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        write.pImageInfo = &images[index];
      }
      if (!updateStorageDescriptors(2u, imageWrites)) return false;
    }
    if (sampleFrame) {
      const VkDescriptorImageInfo imageInfo{VK_NULL_HANDLE, coreFrame.destinationImageView, VK_IMAGE_LAYOUT_GENERAL};
      const VkDescriptorBufferInfo bufferInfo{coreFrame.samples.readback.buffer, 5u * FrameSamples::kStageBytes, FrameSamples::kStageBytes};
      VkWriteDescriptorSet sampleWrites[2]{};
      sampleWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      sampleWrites[0].dstSet = coreFrame.sampleImageDescriptorSet;
      sampleWrites[0].dstBinding = 0;
      sampleWrites[0].descriptorCount = 1;
      sampleWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
      sampleWrites[0].pImageInfo = &imageInfo;
      sampleWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      sampleWrites[1].dstSet = coreFrame.sampleImageDescriptorSet;
      sampleWrites[1].dstBinding = 1;
      sampleWrites[1].descriptorCount = 1;
      sampleWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      sampleWrites[1].pBufferInfo = &bufferInfo;
      if (!updateStorageDescriptors(2u, sampleWrites)) return false;
    }
    if (params.autoExposure) {
      const VkDescriptorImageInfo imageInfo{VK_NULL_HANDLE, coreFrame.sourceImageView, VK_IMAGE_LAYOUT_GENERAL};
      const VkDescriptorBufferInfo bufferInfo{coreFrame.autoExposureSource.buffer, 0, coreFrame.autoExposureSource.capacity};
      VkWriteDescriptorSet writes[2]{};
      writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[0].dstSet = coreFrame.autoExposureImageDescriptorSet;
      writes[0].dstBinding = 0;
      writes[0].descriptorCount = 1;
      writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
      writes[0].pImageInfo = &imageInfo;
      writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[1].dstSet = coreFrame.autoExposureImageDescriptorSet;
      writes[1].dstBinding = 1;
      writes[1].descriptorCount = 1;
      writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      writes[1].pBufferInfo = &bufferInfo;
      if (!updateStorageDescriptors(2u, writes)) return false;
    }
  }

#if defined(__ANDROID__) && !defined(NDEBUG)
  char setupStats[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.setup_stats", setupStats);
  if (setupStats[0] == '1') recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraSetup",
    "reuse=%d descriptors=%u/%u borrowed_views=%d generation=%llu",
    reuseSetup ? 1 : 0, appliedDescriptorWrites, requestedDescriptorWrites,
    externalImages && !coreFrame.ownsSourceImageView && !coreFrame.ownsDestinationImageView ? 2 : 0,
    static_cast<unsigned long long>(externalImageGeneration));
#endif
  VkCommandBuffer commandBuffer = coreFrame.commandBuffer;

  camera_gpu::ComputeRecorder recorder(device, commandBuffer, [&](const VkSubmitInfo& submit, VkFence fence) {
    return backend ? backend->submit(queueIndex, submit, fence) : VK_ERROR_INITIALIZATION_FAILED;
  }, boundedCompute);
  bool measureGpu = false;
  bool profileGpu = false;
#if defined(__ANDROID__)
  char profileValue[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.profile", profileValue);
  profileGpu = externalImages && profileValue[0] == '1';
  if (profileGpu || adaptivePreview) {
    if (coreFrame.timingPool == VK_NULL_HANDLE) {
      uint32_t count = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &count, nullptr);
      std::vector<VkQueueFamilyProperties> families(count);
      vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &count, families.data());
      const uint32_t bits = families[computeQueueFamily].timestampValidBits;
      if (bits > 0) {
        VkQueryPoolCreateInfo info{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
        info.queryType = VK_QUERY_TYPE_TIMESTAMP;
        info.queryCount = CoreFrameResources::kTimingQueryCount;
        if (vkCreateQueryPool(device, &info, nullptr, &coreFrame.timingPool) == VK_SUCCESS) {
          coreFrame.timingMask = bits == 64 ? UINT64_MAX : ((uint64_t{1} << bits) - 1);
        }
      }
    }
    measureGpu = coreFrame.timingPool != VK_NULL_HANDLE;
  }
#endif
  const uint32_t timingQueryCount = profileGpu ? CoreFrameResources::kTimingQueryCount : 2u;
  result = vkResetCommandBuffer(commandBuffer, 0);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkResetCommandBuffer", result);
    return false;
  }

  VkCommandBufferBeginInfo beginInfo{};
  beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkBeginCommandBuffer", result);
    return false;
  }

  VkMemoryBarrier hostInputBarrier{};
  if (measureGpu) vkCmdResetQueryPool(commandBuffer, coreFrame.timingPool, 0, timingQueryCount);
#if defined(__ANDROID__) && !defined(NDEBUG)
  constexpr uint32_t kDispatchProfileCapacity = 1024u;
  bool profileDispatches = false;
  char dispatchProfileValue[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.photo_profile", dispatchProfileValue);
  profileDispatches = !externalImages && dispatchProfileValue[0] == '1';
  if (profileDispatches && coreFrame.dispatchProfilePool == VK_NULL_HANDLE) {
    VkQueryPoolCreateInfo info{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    info.queryType = VK_QUERY_TYPE_TIMESTAMP;
    info.queryCount = kDispatchProfileCapacity;
    if (vkCreateQueryPool(device, &info, nullptr, &coreFrame.dispatchProfilePool) != VK_SUCCESS) {
      coreFrame.dispatchProfilePool = VK_NULL_HANDLE;
    }
  }
  profileDispatches = profileDispatches && coreFrame.dispatchProfilePool != VK_NULL_HANDLE;
  if (profileDispatches) {
    recorder.profileDispatches(coreFrame.dispatchProfilePool, kDispatchProfileCapacity,
      static_cast<uint32_t>(offsetof(VulkanCorePushConstants, _pad0)));
  }
#endif
  hostInputBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  hostInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
  hostInputBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
  vkCmdPipelineBarrier(
    commandBuffer,
    VK_PIPELINE_STAGE_HOST_BIT,
    VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
    0,
    1,
    &hostInputBarrier,
    0,
    nullptr,
    0,
    nullptr
  );

  if (externalImages) {
    VkImageMemoryBarrier sourceToCompute{};
    sourceToCompute.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    sourceToCompute.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    sourceToCompute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    sourceToCompute.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceToCompute.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceToCompute.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceToCompute.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceToCompute.image = activeExternalImageContext.sourceImage;
    sourceToCompute.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0,
      0,
      nullptr,
      0,
      nullptr,
      1,
      &sourceToCompute
    );
    if (params.autoExposure && (!activeTileContext.enabled || !activeTileContext.autoExposureReady)) {
      const uint32_t sampleCount = static_cast<uint32_t>(autoExposurePreviewWidth * autoExposurePreviewHeight);
      const uint32_t exposureSamplePushConstants[8] = {
        sampleCount, 2u, static_cast<uint32_t>(autoExposurePreviewWidth), coordinateWidth, coordinateHeight
      };
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipelineLayout,
        0, 1, &coreFrame.autoExposureImageDescriptorSet, 0, nullptr);
      recorder.push(commandBuffer, imageFormatConvertPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
        0, sizeof(exposureSamplePushConstants), exposureSamplePushConstants);
      recorder.dispatch(commandBuffer, (sampleCount + 255u) / 256u, 1, 1);
    }
    if (directPreviewImages) {
      VkImageMemoryBarrier destinationToCompute = sourceToCompute;
      destinationToCompute.image = activeExternalImageContext.destinationImage;
      destinationToCompute.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &destinationToCompute);
    }
    if (measureGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 0);
    if (!directPreviewImages) {
      const uint32_t sourceFormatPushConstants[8] = {
        static_cast<uint32_t>(pixelCount), 0u, static_cast<uint32_t>(width),
        activeTileContext.enabled ? activeTileContext.tileOriginX : 0u,
        activeTileContext.enabled ? activeTileContext.tileOriginY : 0u
      };
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipelineLayout,
        0, 1, &coreFrame.sourceImageFormatDescriptorSet, 0, nullptr);
      recorder.push(commandBuffer, imageFormatConvertPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
        0, sizeof(sourceFormatPushConstants), sourceFormatPushConstants);
      recorder.dispatch(commandBuffer, formatGroups[0], formatGroups[1], 1);
    }

    VkMemoryBarrier sourceFormatBarrier{};
    sourceFormatBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    sourceFormatBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    sourceFormatBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0,
      1,
      &sourceFormatBarrier,
      0,
      nullptr,
      0,
      nullptr
    );
  } else if (!fullFrameSourcePath) {
    VkBufferCopy sourceUploadRegion{};
    sourceUploadRegion.size = pixelStorageByteCount;
    vkCmdCopyBuffer(commandBuffer, coreFrame.sourceStaging.buffer, coreFrame.source.buffer, 1, &sourceUploadRegion);

    VkMemoryBarrier sourceUploadBarrier{};
    sourceUploadBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    sourceUploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    sourceUploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0,
      1,
      &sourceUploadBarrier,
      0,
      nullptr,
      0,
      nullptr
    );
  }

  VulkanCorePushConstants pushConstants{};
  pushConstants.width = static_cast<uint32_t>(width);
  pushConstants.height = static_cast<uint32_t>(height);
  pushConstants.filmExposureEv = params.filmExposureEv;
  pushConstants.filmGamma = effectiveFilmGamma;
  pushConstants.exposureCount = staticFilm.exposureCount;
  pushConstants.inputColorSpace = static_cast<int32_t>(params.inputColorSpace);
  pushConstants.rgbToRawMethod = static_cast<int32_t>(params.rgbToRawMethod);
  pushConstants.colorSpaceCount = kSpektraColorSpaceCount;
  pushConstants.transferLutSize = kSpektraColorTransferLutSize;
  pushConstants.colorDecodeMin = colorDecodeLutMin();
  pushConstants.colorDecodeMax = colorDecodeLutMax();
  pushConstants.hanatosWidth = staticFilm.hanatosWidth;
  pushConstants.hanatosHeight = staticFilm.hanatosHeight;
  pushConstants.filmPushPullMode = static_cast<int32_t>(params.filmPushPullMode);
  pushConstants.filmPushPullStops = params.filmPushPullStops;
  pushConstants.fullWidth = coordinateWidth;
  pushConstants.fullHeight = coordinateHeight;
  pushConstants.tileOriginX = activeTileContext.enabled ? activeTileContext.tileOriginX : 0u;
  pushConstants.tileOriginY = activeTileContext.enabled ? activeTileContext.tileOriginY : 0u;

  struct ActiveRect {
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
  };
  const ActiveRect centerRect = activeTileContext.enabled
    ? ActiveRect{
        std::min(activeTileContext.centerOriginX, pushConstants.width),
        std::min(activeTileContext.centerOriginY, pushConstants.height),
        std::min(activeTileContext.centerWidth, pushConstants.width - std::min(activeTileContext.centerOriginX, pushConstants.width)),
        std::min(activeTileContext.centerHeight, pushConstants.height - std::min(activeTileContext.centerOriginY, pushConstants.height))
      }
    : ActiveRect{0u, 0u, pushConstants.width, pushConstants.height};
  auto inflatedCenterRect = [&](uint32_t radius) {
    const uint32_t x0 = centerRect.x > radius ? centerRect.x - radius : 0u;
    const uint32_t y0 = centerRect.y > radius ? centerRect.y - radius : 0u;
    const uint32_t x1 = std::min<uint32_t>(
      pushConstants.width,
      centerRect.x + centerRect.width > std::numeric_limits<uint32_t>::max() - radius
        ? pushConstants.width
        : centerRect.x + centerRect.width + radius
    );
    const uint32_t y1 = std::min<uint32_t>(
      pushConstants.height,
      centerRect.y + centerRect.height > std::numeric_limits<uint32_t>::max() - radius
        ? pushConstants.height
        : centerRect.y + centerRect.height + radius
    );
    return ActiveRect{x0, y0, x1 > x0 ? x1 - x0 : 0u, y1 > y0 ? y1 - y0 : 0u};
  };
  auto setActiveRect = [&](const ActiveRect &rect) {
    pushConstants.activeOriginX = std::min(rect.x, pushConstants.width);
    pushConstants.activeOriginY = std::min(rect.y, pushConstants.height);
    pushConstants.activeWidth = std::min(rect.width, pushConstants.width - pushConstants.activeOriginX);
    pushConstants.activeHeight = std::min(rect.height, pushConstants.height - pushConstants.activeOriginY);
    if (pushConstants.activeWidth == 0u || pushConstants.activeHeight == 0u) {
      pushConstants.activeOriginX = 0u;
      pushConstants.activeOriginY = 0u;
      pushConstants.activeWidth = pushConstants.width;
      pushConstants.activeHeight = pushConstants.height;
    }
  };
  uint32_t remainingSpatialRadius =
    cameraDiffusionRadius +
    halationRadius +
    dirRadius +
    grainRadius +
    printDiffusionRadius +
    scannerPostRadius;
  auto setActiveForRemainingRadius = [&]() {
    if (!activeRectShrinkEnabled) {
      setActiveRect(ActiveRect{0u, 0u, pushConstants.width, pushConstants.height});
      return;
    }
    setActiveRect(inflatedCenterRect(remainingSpatialRadius));
  };
  auto consumeSpatialRadius = [&](uint32_t radius) {
    if (!activeRectShrinkEnabled) {
      return;
    }
    remainingSpatialRadius = remainingSpatialRadius > radius ? remainingSpatialRadius - radius : 0u;
    setActiveForRemainingRadius();
  };
  setActiveForRemainingRadius();

  auto activeGroupsX = [&]() {
    return (std::max(pushConstants.activeWidth, 1u) + 31u) / 32u;
  };
  auto activeGroupsY = [&]() {
    return (std::max(pushConstants.activeHeight, 1u) + 7u) / 8u;
  };
  auto insertComputeBarrier = [&]() {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0,
      1,
      &barrier,
      0,
      nullptr,
      0,
      nullptr
    );
  };
  uint32_t halationWorkScale = 1u;
  auto dispatchHalation = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t sigmaMode, uint32_t component) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = sigmaMode;
    pushConstants._pad2 = component | (halationWorkScale > 1u ? halationWorkScale << 16u : 0u);
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, previewOptimizations ? backend->operationPipelines[0][operation] : halationPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    if (operation == kHalationOpPreviewDownsample) {
      recorder.dispatch(commandBuffer, (halationPreviewWidth + 31u) / 32u, (halationPreviewHeight + 7u) / 8u, 1);
    } else {
      recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), 1);
    }
    insertComputeBarrier();
  };
  auto dispatchHalation1D = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t value, uint32_t itemCount) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = value;
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, previewOptimizations ? backend->operationPipelines[0][operation] : halationPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, (std::max(itemCount, 1u) + 31u) / 32u, 1u, 1u);
    insertComputeBarrier();
  };
  auto dispatchDiffusionGroups = [&](uint32_t operation, uint32_t width, uint32_t height) {
    const auto group = previewOptimizations ? diffusionPreviewWorkgroup(operation)
      : std::array<uint32_t, 2>{32u, 8u};
    recorder.dispatch(commandBuffer, (std::max(width, 1u) + group[0] - 1u) / group[0],
                      (std::max(height, 1u) + group[1] - 1u) / group[1], 1u);
  };
  auto dispatchDiffusion = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t component) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = component;
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, previewOptimizations ? backend->operationPipelines[1][operation] : frameDiffusionPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    dispatchDiffusionGroups(operation, pushConstants.activeWidth, pushConstants.activeHeight);
    insertComputeBarrier();
  };
  auto dispatchDiffusionActiveSized = [&](
    VkDescriptorSet descriptorSet,
    uint32_t operation,
    uint32_t value1,
    uint32_t value2
  ) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = value1;
    pushConstants._pad2 = value2;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, previewOptimizations ? backend->operationPipelines[1][operation] : frameDiffusionPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    dispatchDiffusionGroups(operation, pushConstants.activeWidth, pushConstants.activeHeight);
    insertComputeBarrier();
  };
  auto dispatchDiffusionSized = [&](
    VkDescriptorSet descriptorSet,
    uint32_t operation,
    uint32_t value1,
    uint32_t value2,
    uint32_t dispatchWidth,
    uint32_t dispatchHeight
  ) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = value1;
    pushConstants._pad2 = value2;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, previewOptimizations ? backend->operationPipelines[1][operation] : frameDiffusionPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    dispatchDiffusionGroups(operation, dispatchWidth, dispatchHeight);
    insertComputeBarrier();
  };
  auto packDiffusionGroup = [](uint32_t groupCount, uint32_t downsampleScale) {
    return (std::min(downsampleScale, 0xffffu) << 16u) | std::min(groupCount, 0xffffu);
  };
  auto dispatchDiffusionSequence = [&](
    VkDescriptorSet descriptorSet,
    const std::vector<VulkanDiffusionComponent> &components,
    uint32_t spatialRadius
  ) {
    const uint32_t outputRadius = remainingSpatialRadius > spatialRadius ? remainingSpatialRadius - spatialRadius : 0u;
    const ActiveRect outputRect = activeRectShrinkEnabled ? inflatedCenterRect(outputRadius)
      : ActiveRect{0u, 0u, pushConstants.width, pushConstants.height};
    setActiveRect(outputRect);
    dispatchDiffusion(descriptorSet, kDiffusionOpClear, 0u);
    uint32_t preparedDownsampleScale = 0u;
    const uint32_t componentCount = static_cast<uint32_t>(components.size());
    for (uint32_t component = 0u; component < componentCount;) {
      const uint32_t downsampleScale = diffusionDownsamplePath
        ? diffusionScale(components[component].sigmaPx)
        : 1u;
      uint32_t groupCount = 1u;
      while (component + groupCount < componentCount &&
             groupCount < diffusionGroupSize &&
             (!diffusionDownsamplePath ||
              diffusionScale(components[component + groupCount].sigmaPx) == downsampleScale)) {
        ++groupCount;
      }
      if (downsampleScale <= 1u) {
        uint32_t groupRadius = 0u;
        for (uint32_t group = 0u; group < groupCount; ++group) {
          groupRadius = std::max(groupRadius, static_cast<uint32_t>(
            std::min(std::ceil(3.0f * components[component + group].sigmaPx), 256.0f)));
        }
        // The horizontal pass supplies the vertical pass's neighbors, not every tile border.
        if (activeRectShrinkEnabled) setActiveRect(inflatedCenterRect(outputRadius + groupRadius));
        if (groupCount <= 1u) {
          dispatchDiffusion(descriptorSet, kDiffusionOpBlurX, component);
          setActiveRect(outputRect);
          dispatchDiffusion(descriptorSet, kDiffusionOpBlurYAccumulate, component);
        } else {
          dispatchDiffusionActiveSized(descriptorSet, kDiffusionOpGroupBlurX, component, groupCount);
          setActiveRect(outputRect);
          dispatchDiffusionActiveSized(descriptorSet, kDiffusionOpGroupBlurYAccumulate, component, groupCount);
        }
        component += groupCount;
        continue;
      }

      const uint32_t regionLevel = downsampleScale == 2u ? 0u : downsampleScale == 4u ? 1u : 2u;
      const uint32_t reducedWidth = writingDiffusionMilestone ? diffusionRegions.reduced[regionLevel].width : alignedReducedDimension(
        pushConstants.width,
        pushConstants.tileOriginX,
        pushConstants.fullWidth,
        downsampleScale
      );
      const uint32_t reducedHeight = writingDiffusionMilestone ? diffusionRegions.reduced[regionLevel].height : alignedReducedDimension(
        pushConstants.height,
        pushConstants.tileOriginY,
        pushConstants.fullHeight,
        downsampleScale
      );
      if (preparedDownsampleScale != downsampleScale) {
        dispatchDiffusionSized(descriptorSet, kDiffusionOpDownsample, downsampleScale, 0u, reducedWidth, reducedHeight);
        preparedDownsampleScale = downsampleScale;
      }
      if (groupCount <= 1u) {
        const uint32_t packed = packDiffusionGroup(1u, downsampleScale);
        dispatchDiffusionSized(descriptorSet, kDiffusionOpDownsampleBlurX, component, packed, reducedWidth, reducedHeight);
        dispatchDiffusionSized(descriptorSet, kDiffusionOpDownsampleBlurY, component, packed, reducedWidth, reducedHeight);
        dispatchDiffusionActiveSized(descriptorSet, kDiffusionOpDownsampleUpsampleAccumulate, component, packed);
      } else {
        const uint32_t packed = packDiffusionGroup(groupCount, downsampleScale);
        dispatchDiffusionSized(descriptorSet, kDiffusionOpDownsampleGroupBlurX, component, packed, reducedWidth, reducedHeight);
        dispatchDiffusionSized(descriptorSet, kDiffusionOpDownsampleGroupBlurY, component, packed, reducedWidth, reducedHeight);
        dispatchDiffusionActiveSized(descriptorSet, kDiffusionOpDownsampleGroupUpsampleAccumulate, component, packed);
      }
      component += groupCount;
    }
    dispatchDiffusion(descriptorSet, kDiffusionOpResolve, 0u);
  };
  auto dispatchDir = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t component) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = component;
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, dirPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), 1);
    insertComputeBarrier();
  };
  auto dispatchDirScaled = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t component,
                               uint32_t groupsX, uint32_t groupsY) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = component;
    pushConstants._pad2 = dirTailScale;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, dirPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, groupsX, groupsY, 1);
    insertComputeBarrier();
  };
  coreFrame.grainTimingCount = 0;
  auto dispatchGrain = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t depth) {
    const bool timeGrain = measureGpu && profileGpu && coreFrame.grainTimingCount < coreFrame.grainTimingOperations.size();
    const uint32_t grainQuery = 8u + 2u * coreFrame.grainTimingCount;
    if (timeGrain) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, grainQuery);
    pushConstants._pad0 = operation;
    pushConstants._pad1 = 0u;
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
      specializedGrain ? backend->operationPipelines[3][operation] : grainPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), depth);
    insertComputeBarrier();
    if (timeGrain) {
      vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, grainQuery + 1u);
      coreFrame.grainTimingOperations[coreFrame.grainTimingCount++] = operation;
    }
  };

  if (rebuildFourStageLut) {
    const auto savedPush = pushConstants;
    const VkDescriptorSet set = printDiffusionPath ? printDiffusionSets[0] : finalPrintDescriptorSet;
    pushConstants._pad0 = kPrintScanOpFrameConstants;
    pushConstants._pad1 = pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
      specializePrintOperation ? backend->operationPipelines[2][kPrintScanOpFrameConstants] : constantsPrintPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &set, 0, nullptr);
    recorder.push(commandBuffer, printLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, 1u, 1u, 1u);
    insertComputeBarrier();
    const auto bake = [&](uint32_t operation, uint32_t width, uint32_t height) {
      pushConstants.width = width;
      pushConstants.height = height;
      pushConstants.activeOriginX = pushConstants.activeOriginY = 0u;
      pushConstants.activeWidth = width;
      pushConstants.activeHeight = height;
      pushConstants._pad0 = operation;
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
        backend->fourStageBuildPipelines[compactPrint ? 1u : 0u]);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &set, 0, nullptr);
      recorder.push(commandBuffer, printLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
      recorder.dispatch(commandBuffer, (width + 7u) / 8u, (height + 7u) / 8u, 1u);
      insertComputeBarrier();
    };
    constexpr uint32_t probe = CoreFrameResources::kFourStageProbeSize;
    constexpr uint32_t size = CoreFrameResources::kFourStageLutSize;
    bake(4u, probe * probe, probe);
    bake(5u, 1u, 1u);
    for (uint32_t stage = 0; stage < 4u; ++stage) bake(stage, size * size, size);
    diagnostics.passCount += 7u;
    pushConstants = savedPush;
  }

  if (uploadFourStageTexture) {
    VkMemoryBarrier sourceBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    sourceBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    sourceBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    VkImageMemoryBarrier imageBarrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    imageBarrier.srcAccessMask = coreFrame.fourStageTextureReady ? VK_ACCESS_SHADER_READ_BIT : 0u;
    imageBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    imageBarrier.oldLayout = coreFrame.fourStageTextureReady ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
    imageBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    imageBarrier.srcQueueFamilyIndex = imageBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.image = coreFrame.fourStageTexture;
    imageBarrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      0, 1, &sourceBarrier, 0, nullptr, 1, &imageBarrier);
    constexpr uint32_t size = CoreFrameResources::kFourStageLutSize;
    constexpr uint32_t probe = CoreFrameResources::kFourStageProbeSize;
    VkBufferImageCopy copy{};
    copy.bufferOffset = sizeof(float) * 4u * (SPEKTRA_FOUR_STAGE_LUT_METADATA_COUNT + 3u * probe * probe * probe);
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {size, size, size * 4u};
    vkCmdCopyBufferToImage(commandBuffer, coreFrame.fourStageLut.buffer, coreFrame.fourStageTexture,
      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    imageBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    imageBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    imageBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    imageBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 0, nullptr, 0, nullptr, 1, &imageBarrier);
  }

  if (rebuildComposedLut) {
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcAccessMask = coreFrame.composedLut.ready ? VK_ACCESS_SHADER_READ_BIT : 0u;
    barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.oldLayout = coreFrame.composedLut.ready ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = coreFrame.composedLut.image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 0, nullptr, 0, nullptr, 1, &barrier);
    const uint32_t composePush = composedLutSize;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backend->composePipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backend->composeLayout, 0, 1, &coreFrame.composedLut.set, 0, nullptr);
    recorder.push(commandBuffer, backend->composeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(composePush), &composePush);
    recorder.dispatch(commandBuffer, (composedLutSize + 7u) / 8u, (composedLutSize + 3u) / 4u, (composedLutSize + 3u) / 4u);
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 0, nullptr, 0, nullptr, 1, &barrier);
    ++diagnostics.passCount;
  }

  if (measureGpu && profileGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 2);
  if (sampleFrame) recordFrameSamples(commandBuffer, coreFrame.source.buffer, 0u, width, height);
  if (params.autoExposure && (!activeTileContext.enabled || !activeTileContext.autoExposureReady)) {
    pushConstants._pad0 = static_cast<uint32_t>(params.autoExposureMethod);
    pushConstants._pad1 = externalImages ? 1u : 0u;
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, autoExposurePipeline);
    recorder.bindDescriptors(
      commandBuffer,
      VK_PIPELINE_BIND_POINT_COMPUTE,
      corePipelineLayout,
      0,
      1,
      &coreFrame.autoExposureDescriptorSet,
      0,
      nullptr
    );
    const uint32_t groups = (autoExposurePreviewWidth * autoExposurePreviewHeight +
      SPEKTRA_AE_GROUP_SAMPLES - 1u) / SPEKTRA_AE_GROUP_SAMPLES;
    const uint32_t passes = params.autoExposureMethod == AutoExposureMethod::Median ?
      SPEKTRA_AE_MEDIAN_PASSES : SPEKTRA_AE_WEIGHTED_PASSES;
    for (uint32_t pass = 0; pass < passes; ++pass) {
      pushConstants._pad2 = pass;
      recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
        0, sizeof(pushConstants), &pushConstants);
      recorder.dispatch(commandBuffer, (pass & 1u) == 0u ? groups : 1u, 1u, 1u);
      insertComputeBarrier();
    }
  }

  bool videoEffectsRecorded = false;
  if (fusedPreviewColor && videoEffectsRequested) {
    VideoEffectInputs videoInputs{};
    videoInputs.commandBuffer = commandBuffer;
    videoInputs.recorder = &recorder;
    videoInputs.width = static_cast<uint32_t>(width);
    videoInputs.height = static_cast<uint32_t>(height);
    videoInputs.filmPixelSizeUm = filmPixelSizeUm;
    videoInputs.exposureEv = params.filmExposureEv;
    videoInputs.composed = composedPreviewColor;
    videoInputs.composedSize = composedPreviewColor ? composedLutSize : 0u;
    videoInputs.lut = fourStageLutBufferInfo;
    videoInputs.exposure = autoExposureResultBufferInfo;
    videoInputs.lutTexture = fourStageTextureInfo;
    if (composedPreviewColor) videoInputs.composedTexture = composedTextureInfo;
    videoEffectsRecorded = recordVideoEffects(videoInputs);
  }
#if defined(__ANDROID__)
  if (videoEffectsRequested && !fusedPreviewColor && !video.loggedSkip) {
    recordNativeDiagnostic(ANDROID_LOG_WARN, "SpektraVideoEffects",
      "Video frame rendered without effects: images=%d texture=%d sample=%d", directPreviewImages, textureLut, sampleFrame);
  }
  video.loggedSkip = videoEffectsRequested && !fusedPreviewColor;
#endif
  if (fusedPreviewColor && !videoEffectsRecorded) {
    pushConstants._pad0 = composedPreviewColor ? composedLutSize : 0u;
    pushConstants._pad2 = 0u;
    pushConstants._pad1 = colorAdaptationFlags(params) | (params.autoExposure ? kFilmExposureAutoExposureEnabled : 0u);
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backend->previewColorPipelines[composedPreviewColor ? 1u : 0u]);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &coreFrame.exposureDescriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), 1);
  }
  if (measureGpu && profileGpu && (finalProcessNegative || fusedPreviewColor)) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 7);
  if (!finalProcessNegative && !fusedPreviewColor) {
    if (diffusionMilestone != VK_NULL_HANDLE && !writingDiffusionMilestone) {
      std::vector<VkBufferCopy> rows(static_cast<size_t>(height));
      for (uint32_t y = 0; y < static_cast<uint32_t>(height); ++y) {
        rows[y] = {((uint64_t{activeTileContext.tileOriginY} + y) * coordinateWidth +
                     activeTileContext.tileOriginX) * 16u,
                   uint64_t{y} * static_cast<uint32_t>(width) * 16u,
                   uint64_t{static_cast<uint32_t>(width)} * 16u};
      }
      VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      toTransfer.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
      vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &toTransfer, 0, nullptr, 0, nullptr);
      vkCmdCopyBuffer(commandBuffer, diffusionMilestone, coreFrame.filmRaw.buffer,
        static_cast<uint32_t>(rows.size()), rows.data());
      VkMemoryBarrier toCompute{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      toCompute.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      toCompute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &toCompute, 0, nullptr, 0, nullptr);
      if (milestoneToLog) dispatchDiffusion(coreFrame.diffusionDescriptorSets[1], kDiffusionOpRawToLog, 0u);
    } else {
      pushConstants._pad0 = preExposureRawPath ? 1u : 0u;
      pushConstants._pad1 =
        colorAdaptationFlags(params) |
        (params.autoExposure ? kFilmExposureAutoExposureEnabled : 0u);
      pushConstants._pad2 = fullFrameSourcePath ? 1u : 0u;
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
        directPreviewImages ? backend->imageExposurePipelines[fourStagePipelineIndex] :
        fourStageLut ? backend->fourStageDevelopmentPipelines[fourStagePipelineIndex][0] :
        previewDevelopment ? backend->previewDevelopmentPipelines[0] : filmExposurePipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &coreFrame.exposureDescriptorSet, 0, nullptr);
      recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
      recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), 1);
      insertComputeBarrier();
    }

    if (sampleFrame) recordFrameSamples(commandBuffer, coreFrame.filmRaw.buffer, 1u, width, height);
    if (halationBoostEnabled) {
      if (halationBoostLocalReductionEnabled) {
        dispatchHalation1D(
          coreFrame.halationDescriptorSets[10],
          kHalationOpBoostMax,
          kHalationBoostMaxChunkPixels,
          halationBoostMaxChunkCount
        );
        dispatchHalation1D(
          coreFrame.halationDescriptorSets[11],
          kHalationOpBoostReduceMax,
          halationBoostMaxChunkCount,
          1u
        );
      }
      dispatchHalation(coreFrame.halationDescriptorSets[12], kHalationOpBoostApply, 0u, 0u);
    }

    if (cameraDiffusionPath) {
      dispatchDiffusionSequence(coreFrame.diffusionDescriptorSets[0], cameraDiffusionComponents, cameraDiffusionRadius);
      consumeSpatialRadius(cameraDiffusionRadius);
      if (writingDiffusionMilestone) {
        // Store linear film exposure without a half-float round trip between the two effect passes.
        std::vector<VkBufferCopy> rows(activeTileContext.centerHeight);
        for (uint32_t y = 0; y < activeTileContext.centerHeight; ++y) {
          rows[y] = {uint64_t{y} * activeTileContext.centerWidth * 16u,
                     ((uint64_t{activeTileContext.tileOriginY} + activeTileContext.centerOriginY + y) *
                       coordinateWidth + activeTileContext.tileOriginX + activeTileContext.centerOriginX) * 16u,
                     uint64_t{activeTileContext.centerWidth} * 16u};
        }
        VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
          0, 1, &toTransfer, 0, nullptr, 0, nullptr);
        vkCmdCopyBuffer(commandBuffer, coreFrame.phaseScratch[7].buffer, diffusionMilestone,
          static_cast<uint32_t>(rows.size()), rows.data());
        if (measureGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, coreFrame.timingPool, 1);
        result = vkEndCommandBuffer(commandBuffer);
        if (result == VK_SUCCESS) result = vkResetFences(device, 1, &coreFrame.fence);
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commandBuffer;
        if (result == VK_SUCCESS) result = backend->submit(queueIndex, submit, coreFrame.fence);
        if (result != VK_SUCCESS) {
          lastError = vkError("vkQueueSubmit", result);
          return false;
        }
        coreFrame.submitted = true;
        diagnostics.cpuSetupMs = elapsedMilliseconds(setupStart, PerfClock::now());
        refreshTransientBudgetEntry();
        updateSharedDiagnostics();
        return true;
      }
      if (!halationPassEnabled) {
        dispatchDiffusion(coreFrame.diffusionDescriptorSets[1], kDiffusionOpRawToLog, 0u);
      }
    }

    if (halationPassEnabled) {
      const auto fullHalationParameters = pushConstants;
      if (halationPreviewScale > 1u) {
        dispatchHalation(coreFrame.halationDescriptorSets[13], kHalationOpPreviewDownsample, halationPreviewScale, 0u);
        pushConstants.width = halationPreviewWidth;
        pushConstants.height = halationPreviewHeight;
        setActiveRect(ActiveRect{0u, 0u, halationPreviewWidth, halationPreviewHeight});
        halationWorkScale = halationPreviewScale;
      }
      if (halationScatterEnabled) {
        dispatchHalation(coreFrame.halationDescriptorSets[0], kHalationOpBlurX, kHalationSigmaScatterCore, 0u);
        dispatchHalation(coreFrame.halationDescriptorSets[1], kHalationOpBlurYStore, kHalationSigmaScatterCore, 0u);
        dispatchHalation(coreFrame.halationDescriptorSets[2], kHalationOpClear, 0u, 0u);
        for (uint32_t component = 0u; component < 3u; ++component) {
          dispatchHalation(coreFrame.halationDescriptorSets[3], kHalationOpBlurX, kHalationSigmaScatterTail, component);
          dispatchHalation(coreFrame.halationDescriptorSets[4], kHalationOpBlurYAccumulate, kHalationSigmaScatterTail, component);
        }
        dispatchHalation(coreFrame.halationDescriptorSets[5], kHalationOpScatterResolve, 0u, 0u);
      }
      if (halationBounceEnabled) {
        dispatchHalation(coreFrame.halationDescriptorSets[6], kHalationOpClear, 0u, 0u);
        for (uint32_t bounce = 0u; bounce < 3u; ++bounce) {
          dispatchHalation(coreFrame.halationDescriptorSets[7], kHalationOpBlurX, kHalationSigmaBounce, bounce);
          dispatchHalation(coreFrame.halationDescriptorSets[8], kHalationOpBlurYAccumulate, kHalationSigmaBounce, bounce);
        }
        dispatchHalation(coreFrame.halationDescriptorSets[9], kHalationOpBounceResolveLog, 0u, 0u);
      } else {
        dispatchHalation(coreFrame.halationDescriptorSets[9], kHalationOpRawToLog, 0u, 0u);
      }
      if (halationPreviewScale > 1u) {
        pushConstants = fullHalationParameters;
        halationWorkScale = 1u;
        dispatchHalation(coreFrame.halationDescriptorSets[14], kHalationOpPreviewResolve, halationPreviewScale, 0u);
      }
      consumeSpatialRadius(halationRadius);
    }

    if (measureGpu && profileGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 7);
    if (sampleFrame) recordFrameSamples(commandBuffer, developInputBufferInfo.buffer, 2u, width, height);
    pushConstants._pad0 = 0u;
    pushConstants._pad1 = colorAdaptationFlags(params);
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
      fourStageLut ? backend->fourStageDevelopmentPipelines[fourStagePipelineIndex][1] :
      previewDevelopment ? backend->previewDevelopmentPipelines[1] : curveDevelopPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &coreFrame.developDescriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), 1);
    if (sampleFrame) recordFrameSamples(commandBuffer, developOutputBufferInfo.buffer, 3u, width, height);
  }

  if (measureGpu && profileGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 3);
  if (dirPath) {
    insertComputeBarrier();
    dispatchDir(coreFrame.dirDescriptorSets[0], kDirOpCorrectionFromDensity, 0u);
    if (dirBlurPath) {
      dispatchDir(coreFrame.dirDescriptorSets[1], kDirOpBlurX, 0u);
      dispatchDir(coreFrame.dirDescriptorSets[2], kDirOpBlurYStore, 0u);
    }
    if (dirTailPath) {
      dispatchDir(coreFrame.dirDescriptorSets[3], kDirOpTailClear, 0u);
      if (dirTailScale > 1u) {
        const uint32_t reducedGroupsX = (dirTailReducedWidth + 31u) / 32u;
        const uint32_t reducedGroupsY = (dirTailReducedHeight + 7u) / 8u;
        dispatchDirScaled(coreFrame.dirDescriptorSets[7], kDirOpTailDownsample, 0u, reducedGroupsX, reducedGroupsY);
        for (uint32_t component = 0u; component < 3u; ++component) {
          dispatchDirScaled(coreFrame.dirDescriptorSets[8], kDirOpTailReducedBlurX, component, reducedGroupsX, reducedGroupsY);
          dispatchDirScaled(coreFrame.dirDescriptorSets[9], kDirOpTailReducedBlurYAccumulate, component,
            reducedGroupsX, reducedGroupsY);
        }
        dispatchDirScaled(coreFrame.dirDescriptorSets[10], kDirOpTailUpsampleAccumulate, 0u, activeGroupsX(), activeGroupsY());
      } else {
        for (uint32_t component = 0u; component < 3u; ++component) {
          dispatchDir(coreFrame.dirDescriptorSets[4], kDirOpTailBlurX, component);
          dispatchDir(coreFrame.dirDescriptorSets[5], kDirOpTailBlurYAccumulate, component);
        }
      }
    }
    dispatchDir(coreFrame.dirDescriptorSets[6], kDirOpRedevelop, 0u);
    consumeSpatialRadius(dirRadius);
  }

  if (grainPath) {
    const uint32_t layerDepth = specializedGrain &&
      ((productionGrainPath && !params.grainSublayersEnabled) || (grainSynthesisPath && !params.grainSynthesisLayered)) ? 3u : 9u;
    insertComputeBarrier();
    if (previewGrainPath) {
      dispatchGrain(coreFrame.grainDescriptorSets[0], kGrainOpPreview, 1u);
    } else if (productionGrainPath) {
      dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpProductionLayers, layerDepth);
      if (grainLayerBlurPath) {
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpLayerBlurX, layerDepth);
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpLayerBlurY, layerDepth);
      }
      if (grainMicroPath) {
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpMicroSource, 1u);
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpMicroBlurX, 1u);
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpMicroBlurY, 1u);
      }
      dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpResolveDensity, 1u);
      dispatchGrain(coreFrame.grainDescriptorSets[2], kGrainOpDensityBlurX, 1u);
      dispatchGrain(coreFrame.grainDescriptorSets[3], kGrainOpDensityBlurY, 1u);
      dispatchGrain(coreFrame.grainDescriptorSets[4], kGrainOpApplyControls, 1u);
      consumeSpatialRadius(grainRadius);
    } else if (grainSynthesisPath) {
      dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpSynthesisLayers, layerDepth);
      if (grainLayerBlurPath) {
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpLayerBlurX, layerDepth);
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpLayerBlurY, layerDepth);
      }
      if (grainMicroPath) {
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpMicroSource, 1u);
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpMicroBlurX, 1u);
        dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpMicroBlurY, 1u);
      }
      dispatchGrain(coreFrame.grainDescriptorSets[1], kGrainOpSynthesisResolveDensity, 1u);
      dispatchGrain(coreFrame.grainDescriptorSets[2], kGrainOpDensityBlurX, 1u);
      dispatchGrain(coreFrame.grainDescriptorSets[3], kGrainOpDensityBlurY, 1u);
      dispatchGrain(coreFrame.grainDescriptorSets[4], kGrainOpCopyDensity, 1u);
      consumeSpatialRadius(grainRadius);
    }
  }

  const auto probePrintAccess = [&](uint32_t stage) {
    if (!samplePrint) return;
    if (independentPrintPasses) recorder.beginIndependentPass();
    insertComputeBarrier();
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backend->printAccessPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backend->printAccessLayout,
      0, 1, &coreFrame.samples.probeSet, 0, nullptr);
    recorder.push(commandBuffer, backend->printAccessLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(stage), &stage);
    recorder.dispatch(commandBuffer, 1u, 1u, 1u);
    insertComputeBarrier();
  };
  const auto dispatchPrint = [&](bool constantsOnly) {
#if !defined(NDEBUG)
    if (!previewOptimizations && envFlagEnabled("SPEKTRAFILM_VULKAN_SKIP_PRINT")) return;
#endif
    if (!slicePrintWithOrigin) {
      recorder.push(commandBuffer, printLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
      recorder.dispatch(commandBuffer, constantsOnly ? 1u : (pushConstants.activeWidth + printGroupWidth - 1u) / printGroupWidth,
        constantsOnly ? 1u : activeGroupsY(), 1u);
      return;
    }
    if (constantsOnly) {
      recorder.push(commandBuffer, printLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
      recorder.dispatchSlice(commandBuffer, 1u, 1u);
      return;
    }
    const VulkanCorePushConstants full = pushConstants;
    for (uint32_t y = 0; y < full.activeHeight;) {
      const uint32_t budget = std::min(camera_gpu::groupBudget, 4096u);
      const uint32_t columns = std::min((full.activeWidth + printGroupWidth - 1u) / printGroupWidth, budget);
      const uint32_t rows = std::min((full.activeHeight - y + 7u) / 8u,
        std::max(1u, budget / columns));
      const uint32_t sliceHeight = std::min(full.activeHeight - y, rows * 8u);
      for (uint32_t x = 0; x < full.activeWidth;) {
        VulkanCorePushConstants slice = full;
        slice.activeOriginX += x;
        slice.activeOriginY += y;
        slice.activeWidth = std::min(full.activeWidth - x, columns * printGroupWidth);
        slice.activeHeight = sliceHeight;
        recorder.push(commandBuffer, printLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(slice), &slice);
        recorder.dispatchSlice(commandBuffer, (slice.activeWidth + printGroupWidth - 1u) / printGroupWidth, rows);
        x += slice.activeWidth;
      }
      y += sliceHeight;
    }
    recorder.push(commandBuffer, printLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(full), &full);
  };
  if (measureGpu && profileGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 4);
  if (printScanPassEnabled) {
    insertComputeBarrier();
    if (samplePrint) {
      VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT;
      toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
      vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &toTransfer, 0, nullptr, 0, nullptr);
      vkCmdFillBuffer(commandBuffer, coreFrame.samples.readback.buffer, FrameSamples::kProbeOffset,
        FrameSamples::kProbeBytes, 0x7fc00001u);
      const VkBufferCopy gammaCopy{4u * sizeof(float), FrameSamples::kTransferOffset, sizeof(float)};
      vkCmdCopyBuffer(commandBuffer, coreFrame.frameFloats.buffer, coreFrame.samples.readback.buffer, 1, &gammaCopy);
      const VkBufferCopy integerCopies[2]{
        {0, FrameSamples::kTransferOffset + 4u, 4u}, {8u * sizeof(uint32_t), FrameSamples::kTransferOffset + 8u, 8u}};
      vkCmdCopyBuffer(commandBuffer, coreFrame.frameInts.buffer, coreFrame.samples.readback.buffer, 2, integerCopies);
      VkMemoryBarrier toCompute{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      toCompute.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      toCompute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &toCompute, 0, nullptr, 0, nullptr);
      probePrintAccess(0u);
    }

    pushConstants._pad0 = kPrintScanOpFrameConstants;
    pushConstants._pad1 = 0u;
    pushConstants._pad2 = previewExposureRange != VK_NULL_HANDLE ? 1u : 0u;
    const VkDescriptorSet frameConstantsSet = printDiffusionPath
      ? printDiffusionSets[0]
      : finalPrintDescriptorSet;
    if (samplePrint) {
#if defined(__ANDROID__)
      recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraPrintDispatch",
        "renderer=%p sequence=%llu preview=%d size=%ux%u active=%ux%u+%u+%u "
        "chunks=%u chunk_bytes=%u frame_float_bytes=%llu frame_int_bytes=%llu density_bytes=%llu "
        "binding_mode=complete_pipeline_first print_pipeline=%s dispatch=%s dispatch_base_pipeline=%d storage_layout=%s independent_passes=%d backend=%p print_program=%s pixel_group=%ux8 constants_group=%s",
        static_cast<void *>(this), static_cast<unsigned long long>(sampleSequence), previewOptimizations,
        pushConstants.width, pushConstants.height, pushConstants.activeWidth, pushConstants.activeHeight,
        pushConstants.activeOriginX, pushConstants.activeOriginY,
        compactPrint ? 1u : backend->storageChunkCount(), backend->storageChunkBytes(),
        static_cast<unsigned long long>(frameFloatsBufferInfo.range),
        static_cast<unsigned long long>(frameIntsBufferInfo.range),
        static_cast<unsigned long long>(finalFilmDensityBufferInfo.range),
        specializePrintOperation ? "specialized" : "shared",
        slicePrintWithOrigin ? "origin_slices" : (camera_gpu::exporting ? "dispatch_base" : "direct"),
        !compactPrint && !slicePrintWithOrigin, compactPrint ? "single_buffer" : "chunked", independentPrintPasses,
        static_cast<void *>(backend.get()), splitPrint ? "split" : "combined", printGroupWidth, splitPrint ? "1x1" : "32x8");
#endif
      if (independentPrintPasses) recorder.beginIndependentPass();
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, compactPrint ? backend->compactPrintProbePipeline : backend->printProbePipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &frameConstantsSet, 0, nullptr);
      recorder.dispatch(commandBuffer, 1u, 1u, 1u);
      insertComputeBarrier();
    }
    if (!fusedPreviewColor || previewExposureRange != VK_NULL_HANDLE) {
      if (independentPrintPasses) recorder.beginIndependentPass();
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, specializePrintOperation ? backend->operationPipelines[2][pushConstants._pad0] : constantsPrintPipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &frameConstantsSet, 0, nullptr);
      dispatchPrint(true);
      insertComputeBarrier();
    }
    pushConstants._pad2 = 0u;

    if (rebuildColorLut) {
      const auto framePush = pushConstants;
      constexpr uint32_t size = CoreFrameResources::kColorLutSize;
      pushConstants.width = size * size;
      pushConstants.height = size;
      pushConstants.activeOriginX = pushConstants.activeOriginY = 0u;
      pushConstants.activeWidth = pushConstants.width;
      pushConstants.activeHeight = pushConstants.height;
      const uint32_t firstTable = finalPrintSimulation ? 0u : 1u;
      for (uint32_t table = firstTable; table < 2u; ++table) {
        pushConstants._pad0 = table;
        recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
          backend->previewLutBuildPipelines[compactPrint ? 1u : 0u]);
        recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &frameConstantsSet, 0, nullptr);
        dispatchPrint(false);
        insertComputeBarrier();
      }
      pushConstants = framePush;
      diagnostics.passCount += 2u - firstTable;
    }

    if (measureGpu && profileGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 5);
    probePrintAccess(1u);
    if (printDiffusionPath) {
      pushConstants._pad0 = finalProcessNegative ? kPrintScanOpPrintRawFromNegativeLight : kPrintScanOpPrintRaw;
      pushConstants._pad1 = 0u;
      pushConstants._pad2 = 0u;
      if (independentPrintPasses) recorder.beginIndependentPass();
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pixelPrintPipeline(pushConstants._pad0));
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &printDiffusionSets[0], 0, nullptr);
      dispatchPrint(false);
      insertComputeBarrier();

      dispatchDiffusionSequence(coreFrame.diffusionDescriptorSets[2], printDiffusionComponents, printDiffusionRadius);
      consumeSpatialRadius(printDiffusionRadius);

      pushConstants._pad0 = kPrintScanOpFinalFromPrintRaw;
      pushConstants._pad1 = scannerPostPath ? 1u : 0u;
      pushConstants._pad2 = 0u;
    } else {
      pushConstants._pad0 = kPrintScanOpFull;
      pushConstants._pad1 = scannerPostPath ? 1u : 0u;
      pushConstants._pad2 = composedPrintColor ? composedLutSize : 0u;
    }
    if (!fusedPreviewColor) {
      const VkDescriptorSet finalPrintSet = printDiffusionPath
        ? printDiffusionSets[1]
        : finalPrintDescriptorSet;
      if (independentPrintPasses) recorder.beginIndependentPass();
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pixelPrintPipeline(pushConstants._pad0));
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, printLayout, 0, 1, &finalPrintSet, 0, nullptr);
      dispatchPrint(false);
    }
    probePrintAccess(2u);
    if (independentPrintPasses) recorder.beginIndependentPass();
  } else if (measureGpu && profileGpu) {
    vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 5);
  }
  if (measureGpu && profileGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, coreFrame.timingPool, 6);

  auto dispatchScannerPost = [&](VkDescriptorSet descriptorSet, uint32_t operation) {
    pushConstants._pad0 = operation;
    pushConstants._pad1 = 0u;
    pushConstants._pad2 = 0u;
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
      directPreviewImages && operation == kScannerPostOpFinalize ? backend->imageScannerPipeline : scannerPostPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
    recorder.dispatch(commandBuffer, activeGroupsX(), activeGroupsY(), 1);
    insertComputeBarrier();
  };

  if (scannerPostPath) {
    insertComputeBarrier();
    if (printGlarePath) {
      dispatchScannerPost(coreFrame.scannerPostDescriptorSets[0], kScannerPostOpPrintGlareGenerate);
      if (printGlareBlurPath) {
        dispatchScannerPost(coreFrame.scannerPostDescriptorSets[1], kScannerPostOpPrintGlareBlurX);
        dispatchScannerPost(coreFrame.scannerPostDescriptorSets[2], kScannerPostOpPrintGlareBlurY);
      }
      dispatchScannerPost(coreFrame.scannerPostDescriptorSets[3], kScannerPostOpPrintGlareApply);
    }
    if (scannerBlurPath) {
      dispatchScannerPost(coreFrame.scannerPostDescriptorSets[4], kScannerPostOpScannerBlurX);
      dispatchScannerPost(coreFrame.scannerPostDescriptorSets[5], kScannerPostOpScannerBlurY);
    }
    if (scannerUnsharpPath) {
      dispatchScannerPost(coreFrame.scannerPostDescriptorSets[6], kScannerPostOpUnsharpBlurX);
      dispatchScannerPost(coreFrame.scannerPostDescriptorSets[7], kScannerPostOpUnsharpBlurY);
    }
    dispatchScannerPost(coreFrame.scannerPostDescriptorSets[8], kScannerPostOpFinalize);
    consumeSpatialRadius(scannerPostRadius);
  }

  if (fusedPreviewOutput && !directPreviewImages) {
    insertComputeBarrier();
    VkImageMemoryBarrier destinationToCompute{};
    destinationToCompute.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    destinationToCompute.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    destinationToCompute.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    destinationToCompute.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    destinationToCompute.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    destinationToCompute.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destinationToCompute.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destinationToCompute.image = activeExternalImageContext.destinationImage;
    destinationToCompute.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0,
      0,
      nullptr,
      0,
      nullptr,
      1,
      &destinationToCompute
    );
    const uint32_t destinationImagePushConstants[5] = {
      static_cast<uint32_t>(pixelCount), 2u, static_cast<uint32_t>(width),
      activeTileContext.enabled ? activeTileContext.tileOriginX : 0u,
      activeTileContext.enabled ? activeTileContext.tileOriginY : 0u
    };
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, backend->previewOutputPipeline);
    recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            imageFormatConvertPipelineLayout, 0, 1,
                            &coreFrame.destinationImageFormatDescriptorSet, 0, nullptr);
    recorder.push(commandBuffer, imageFormatConvertPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(destinationImagePushConstants), destinationImagePushConstants);
    recorder.dispatch(commandBuffer, formatGroups[0], formatGroups[1], 1);
  }

  VkPipelineStageFlags hostBarrierSourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
  VkAccessFlags hostBarrierSourceAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
  if (destinationFormatConvert) {
    VkMemoryBarrier destinationConvertInputBarrier{};
    destinationConvertInputBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    destinationConvertInputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    destinationConvertInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0,
      1,
      &destinationConvertInputBarrier,
      0,
      nullptr,
      0,
      nullptr
    );

    const uint32_t formatPushConstants[2] = {static_cast<uint32_t>(pixelCount), destinationIsHalf ? 1u : 0u};
    recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, formatConvertPipeline);
    recorder.bindDescriptors(
      commandBuffer,
      VK_PIPELINE_BIND_POINT_COMPUTE,
      copyPipelineLayout,
      0,
      1,
      &coreFrame.destinationFormatDescriptorSet,
      0,
      nullptr
    );
    recorder.push(commandBuffer, copyPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(formatPushConstants), formatPushConstants);
    recorder.dispatch(commandBuffer, formatGroups[0], formatGroups[1], 1);

    VkMemoryBarrier readbackBarrier{};
    readbackBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    readbackBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    readbackBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT,
      0,
      1,
      &readbackBarrier,
      0,
      nullptr,
      0,
      nullptr
    );

    if (externalImages) {
      VkMemoryBarrier destinationHalfToImage{};
      destinationHalfToImage.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
      destinationHalfToImage.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      destinationHalfToImage.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
      vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &destinationHalfToImage,
                           0, nullptr, 0, nullptr);
      VkImageMemoryBarrier destinationToCompute{};
      destinationToCompute.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
      destinationToCompute.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      destinationToCompute.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      destinationToCompute.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
      destinationToCompute.newLayout = VK_IMAGE_LAYOUT_GENERAL;
      destinationToCompute.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      destinationToCompute.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      destinationToCompute.image = activeExternalImageContext.destinationImage;
      destinationToCompute.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &destinationToCompute
      );
      const uint32_t centerPixelCount = centerRect.width * centerRect.height;
      const uint32_t destinationImagePushConstants[8] = {
        centerPixelCount, 1u, static_cast<uint32_t>(width),
        activeTileContext.enabled ? activeTileContext.tileOriginX : 0u,
        activeTileContext.enabled ? activeTileContext.tileOriginY : 0u,
        centerRect.x, centerRect.y, centerRect.width
      };
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                              imageFormatConvertPipelineLayout, 0, 1,
                              &coreFrame.destinationImageFormatDescriptorSet, 0, nullptr);
      recorder.push(commandBuffer, imageFormatConvertPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                         0, sizeof(destinationImagePushConstants), destinationImagePushConstants);
      const auto centerGroups = linearDispatchGroups(centerPixelCount,
        physicalDeviceProperties.limits.maxComputeWorkGroupCount[0]);
      recorder.dispatch(commandBuffer, centerGroups[0], centerGroups[1], 1);
    } else {
      VkBufferCopy destinationDownloadRegion{};
      destinationDownloadRegion.size = halfByteCount;
      vkCmdCopyBuffer(
        commandBuffer,
        coreFrame.destinationHalf.buffer,
        coreFrame.destinationHalfStaging.buffer,
        1,
        &destinationDownloadRegion
      );
    }
  } else if (!fusedPreviewOutput) {
    VkMemoryBarrier readbackBarrier{};
    readbackBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    readbackBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    readbackBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT,
      0,
      1,
      &readbackBarrier,
      0,
      nullptr,
      0,
      nullptr
    );

    VkBufferCopy destinationDownloadRegion{};
    destinationDownloadRegion.size = pixelStorageByteCount;
    vkCmdCopyBuffer(
      commandBuffer,
      coreFrame.destination.buffer,
      destinationIsHalf ? coreFrame.destinationHalfStaging.buffer : coreFrame.destinationStaging.buffer,
      1,
      &destinationDownloadRegion
    );
  }

  if (!externalImages) {
    VkMemoryBarrier hostBarrier{};
    hostBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    hostBarrier.srcAccessMask = hostBarrierSourceAccess;
    hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
      commandBuffer,
      hostBarrierSourceStage,
      VK_PIPELINE_STAGE_HOST_BIT,
      0,
      1,
      &hostBarrier,
      0,
      nullptr,
      0,
      nullptr
    );
  }

  if (sampleFrame) {
    recordFrameSamples(commandBuffer, coreFrame.destination.buffer, 4u, width, height);
    insertComputeBarrier();
    const uint32_t samplePush[8] = {FrameSamples::kCount, 2u, FrameSamples::kGrid,
                                  static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipeline);
    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, imageFormatConvertPipelineLayout,
                            0, 1, &coreFrame.sampleImageDescriptorSet, 0, nullptr);
    vkCmdPushConstants(commandBuffer, imageFormatConvertPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                        0, sizeof(samplePush), samplePush);
    vkCmdDispatch(commandBuffer, 1u, 1u, 1u);
    coreFrame.samples.stages |= 1u << 5u;
    if (params.autoExposure) {
      const VkBufferCopy copy{0, FrameSamples::kMetadataOffset, sizeof(float)};
      vkCmdCopyBuffer(commandBuffer, coreFrame.autoExposureResult.buffer, coreFrame.samples.readback.buffer, 1, &copy);
    }
    if (printScanPassEnabled) {
      const VkBufferCopy copy{91u * sizeof(float), FrameSamples::kMetadataOffset + 4u * sizeof(float),
                             (kCoreFrameFloatCount - 91u) * sizeof(float)};
      vkCmdCopyBuffer(commandBuffer, coreFrame.frameFloats.buffer, coreFrame.samples.readback.buffer, 1, &copy);
    }
    VkMemoryBarrier host{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &host, 0, nullptr, 0, nullptr);
  }
  if (previewExposureRange != VK_NULL_HANDLE) {
    VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &toTransfer, 0, nullptr, 0, nullptr);
    if (printScanPassEnabled) {
      const VkBufferCopy rangeCopy{kExposureRangeOffset * sizeof(float), 0, 8u * sizeof(float)};
      vkCmdCopyBuffer(commandBuffer, coreFrame.frameFloats.buffer, previewExposureRange, 1, &rangeCopy);
    } else {
      const float range[8]{0, 0, 0, 0, 1, 1, 1, 0};
      vkCmdUpdateBuffer(commandBuffer, previewExposureRange, 0, sizeof(range), range);
    }
    VkMemoryBarrier toPresentation{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    toPresentation.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toPresentation.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &toPresentation, 0, nullptr, 0, nullptr);
  }
  if (measureGpu) vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, coreFrame.timingPool, 1);
  result = vkEndCommandBuffer(commandBuffer);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkEndCommandBuffer", result);
    return false;
  }

  VkFence fence = coreFrame.fence;
  result = vkResetFences(device, 1, &fence);
  if (result != VK_SUCCESS) {
    lastError = vkError("vkResetFences", result);
    return false;
  }

  VkSubmitInfo submitInfo{};
  submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  submitInfo.commandBufferCount = 1;
  submitInfo.pCommandBuffers = &commandBuffer;
  const PerfClock::time_point commandStart = PerfClock::now();
  diagnostics.cpuSetupMs = std::max(0.0, elapsedMilliseconds(setupStart, commandStart) - diagnostics.sourceCopyMs);
  result = backend ? backend->submit(queueIndex, submitInfo, fence) : VK_ERROR_INITIALIZATION_FAILED;
  if (result != VK_SUCCESS) {
    lastError = vkError("vkQueueSubmit", result);
    return false;
  }
  coreFrame.submitted = true;
  coreFrame.samples.ready = sampleFrame;
  if (rebuildFourStageLut) {
    coreFrame.fourStageLutKey = fourStageLutKey;
    coreFrame.fourStageLutReady = true;
    ++coreFrame.fourStageLutGeneration;
#if defined(__ANDROID__) && !defined(NDEBUG)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraColorLut",
      "renderer=%p generation=%llu stages=4 size=%u film=%d paper=%d bytes=%llu forced=%d couplers=%d",
      static_cast<void *>(this), static_cast<unsigned long long>(coreFrame.fourStageLutGeneration),
      CoreFrameResources::kFourStageLutSize, params.film, params.paper,
      static_cast<unsigned long long>(CoreFrameResources::kFourStageLutBytes), forceLutRebuild ? 1 : 0, bakedDir ? 1 : 0);
#endif
  }
  if (rebuildColorLut) {
    coreFrame.colorLutKey = colorLutKey;
    coreFrame.colorLutReady = true;
    ++coreFrame.colorLutGeneration;
#if defined(__ANDROID__) && !defined(NDEBUG)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraColorLut",
      "renderer=%p generation=%llu size=%u film=%d paper=%d process=%d bytes=%llu",
      static_cast<void *>(this), static_cast<unsigned long long>(coreFrame.colorLutGeneration),
      CoreFrameResources::kColorLutSize, params.film, params.paper, static_cast<int>(params.process),
      static_cast<unsigned long long>(CoreFrameResources::kColorLutBytes));
#endif
  }
  if (uploadFourStageTexture) {
    coreFrame.fourStageTextureReady = true;
    coreFrame.fourStageTextureGeneration = coreFrame.fourStageLutGeneration;
#if defined(__ANDROID__) && !defined(NDEBUG)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "SpektraColorLut", "renderer=%p texture=1 generation=%llu bytes=%llu",
      static_cast<void *>(this), static_cast<unsigned long long>(coreFrame.fourStageTextureGeneration),
      static_cast<unsigned long long>(coreFrame.fourStageTextureBytes));
#endif
  }
  if (rebuildComposedLut) {
    coreFrame.composedLut.ready = true;
    coreFrame.composedLut.generation = coreFrame.fourStageLutGeneration;
  }
  if (!externalImages && finishSubmittedWork(true) != VK_SUCCESS) return false;
#if defined(__ANDROID__) && !defined(NDEBUG)
  if (profileDispatches) logDispatchProfile(recorder.profiledDispatches(), width, height);
#endif
  if (externalImages && !previewOptimizations && samplePrint) {
    if (finishSubmittedWork(true) != VK_SUCCESS) return false;
    if (!coreFrame.samples.printExecutionVerified) {
      lastError = "spektrafilm print GPU work did not complete";
      return false;
    }
  }
  coreFrame.timingPending = measureGpu;
  coreFrame.timingQueryCount = profileGpu ? 8u + coreFrame.grainTimingCount * 2u : 2u;
  coreFrame.timingProfile = profileGpu;
  coreFrame.timingAdaptive = adaptivePreview && previewColorLut && !rebuildColorLut && !rebuildFourStageLut;
  coreFrame.timingBounded = boundedCompute;
  const PerfClock::time_point commandEnd = PerfClock::now();
  diagnostics.commandBufferMs = elapsedMilliseconds(commandStart, commandEnd);

  const PerfClock::time_point outputCopyStart = PerfClock::now();
  if (externalImages) {
    diagnostics.outputCopyMs = 0.0;
    refreshTransientBudgetEntry();
    enforceTransientBudget();
    updateSharedDiagnostics();
    return true;
  }
  ScratchBuffer &readbackStaging = destinationIsHalf ? coreFrame.destinationHalfStaging : coreFrame.destinationStaging;
  const VkDeviceSize readbackByteCount = destinationIsHalf ? halfByteCount : byteCount;
  if (!readbackStaging.mapped) {
    lastError = "Vulkan destination staging buffer is not mapped.";
    return false;
  }
  if (!invalidateMappedScratchBuffer(readbackStaging, readbackByteCount, "core destination staging")) {
    return false;
  }
  if (!copyMappedBytesToWindow(
        readbackStaging.mapped,
        static_cast<size_t>(readbackByteCount),
        destination,
        window,
        width,
        height
      )) {
    lastError = "The requested render window does not fit inside the Vulkan destination image view.";
    return false;
  }
  const PerfClock::time_point outputCopyEnd = PerfClock::now();
  diagnostics.outputCopyMs = elapsedMilliseconds(outputCopyStart, outputCopyEnd);

  RendererPassDiagnostics exposurePass{};
  exposurePass.name = preExposureRawPath ? "spektrafilm_raw_exposure" : "spektrafilm_film_exposure";
  exposurePass.width = static_cast<uint32_t>(width);
  exposurePass.height = static_cast<uint32_t>(height);
  exposurePass.threadgroupWidth = 32;
  exposurePass.threadgroupHeight = 8;
  exposurePass.estimatedBytes = static_cast<uint64_t>(pixelStorageByteCount) * 2u;
  diagnostics.passes.push_back(exposurePass);

  auto addComputePassDepth = [&](const std::string &name, uint32_t depth) {
    RendererPassDiagnostics pass{};
    pass.name = name;
    pass.width = static_cast<uint32_t>(width);
    pass.height = static_cast<uint32_t>(height);
    pass.depth = depth;
    pass.threadgroupWidth = 32;
    pass.threadgroupHeight = 8;
    pass.estimatedBytes = depth == 9u
      ? static_cast<uint64_t>(grainLayerByteCount) * 2u
      : static_cast<uint64_t>(pixelStorageByteCount) * 2u;
    diagnostics.passes.push_back(pass);
  };
  auto addComputePass = [&](const std::string &name) {
    addComputePassDepth(name, 1u);
  };
  auto addComputePassSize = [&](
    const std::string &name,
    uint32_t passWidth,
    uint32_t passHeight,
    uint32_t depth,
    uint64_t estimatedBytes
  ) {
    RendererPassDiagnostics pass{};
    pass.name = name;
    pass.width = passWidth;
    pass.height = passHeight;
    pass.depth = depth;
    pass.threadgroupWidth = 32;
    pass.threadgroupHeight = 8;
    pass.estimatedBytes = estimatedBytes;
    diagnostics.passes.push_back(pass);
  };
  auto addDiffusionPasses = [&](const char *prefix, const std::vector<VulkanDiffusionComponent> &components) {
    const uint32_t localWidth = static_cast<uint32_t>(width);
    const uint32_t localHeight = static_cast<uint32_t>(height);
    const uint64_t fullBytes = static_cast<uint64_t>(pixelStorageByteCount) * 2u;
    addComputePass(std::string(prefix) + "_diffusion_clear");
    uint32_t preparedDownsampleScale = 0u;
    for (uint32_t component = 0u; component < static_cast<uint32_t>(components.size());) {
      const uint32_t downsampleScale = diffusionDownsamplePath
        ? diffusionScale(components[component].sigmaPx)
        : 1u;
      uint32_t groupCount = 1u;
      while (component + groupCount < static_cast<uint32_t>(components.size()) &&
             groupCount < diffusionGroupSize &&
             (!diffusionDownsamplePath ||
              diffusionScale(components[component + groupCount].sigmaPx) == downsampleScale)) {
        ++groupCount;
      }
      if (downsampleScale <= 1u) {
        if (groupCount <= 1u) {
          addComputePass(std::string(prefix) + "_diffusion_blur_x");
          addComputePass(std::string(prefix) + "_diffusion_blur_y_accumulate");
        } else {
          addComputePass(std::string(prefix) + "_diffusion_group_blur_x");
          addComputePass(std::string(prefix) + "_diffusion_group_blur_y_accumulate");
        }
        component += groupCount;
        continue;
      }
      const uint32_t reducedWidth = alignedReducedDimension(
        localWidth,
        activeTileContext.enabled ? activeTileContext.tileOriginX : 0u,
        coordinateWidth,
        downsampleScale
      );
      const uint32_t reducedHeight = alignedReducedDimension(
        localHeight,
        activeTileContext.enabled ? activeTileContext.tileOriginY : 0u,
        coordinateHeight,
        downsampleScale
      );
      const uint64_t reducedBytes =
        static_cast<uint64_t>(reducedWidth) * static_cast<uint64_t>(reducedHeight) * 4u * sizeof(float) * 2u;
      const std::string base = std::string(prefix) + "_diffusion_downsample";
      if (preparedDownsampleScale != downsampleScale) {
        addComputePassSize(base, reducedWidth, reducedHeight, 1u, reducedBytes);
        preparedDownsampleScale = downsampleScale;
      }
      if (groupCount <= 1u) {
        addComputePassSize(base + "_blur_x", reducedWidth, reducedHeight, 1u, reducedBytes);
        addComputePassSize(base + "_blur_y", reducedWidth, reducedHeight, 1u, reducedBytes);
        addComputePassSize(base + "_upsample_accumulate", localWidth, localHeight, 1u, fullBytes);
      } else {
        const uint64_t groupBytes = reducedBytes * static_cast<uint64_t>(groupCount);
        addComputePassSize(base + "_group_blur_x", reducedWidth, reducedHeight, groupCount, groupBytes);
        addComputePassSize(base + "_group_blur_y", reducedWidth, reducedHeight, groupCount, groupBytes);
        addComputePassSize(base + "_group_upsample_accumulate", localWidth, localHeight, groupCount, fullBytes);
      }
      component += groupCount;
    }
    addComputePass(std::string(prefix) + "_diffusion_resolve");
  };
  if (cameraDiffusionPath) {
    addDiffusionPasses("spektrafilm_camera", cameraDiffusionComponents);
    if (!halationPassEnabled) {
      addComputePass("spektrafilm_raw_to_log_raw");
    }
  }
  if (halationPassEnabled) {
    if (halationBoostEnabled) {
      if (halationBoostLocalReductionEnabled) {
        addComputePass("spektrafilm_halation_boost_max");
        addComputePass("spektrafilm_halation_boost_reduce_max");
      }
      addComputePass("spektrafilm_halation_boost_apply");
    }
    if (halationScatterEnabled) {
      addComputePass("spektrafilm_halation_scatter_core_blur_x");
      addComputePass("spektrafilm_halation_scatter_core_blur_y");
      addComputePass("spektrafilm_halation_scatter_tail_clear");
      for (uint32_t component = 0u; component < 3u; ++component) {
        addComputePass("spektrafilm_halation_scatter_tail_blur_x");
        addComputePass("spektrafilm_halation_scatter_tail_blur_y_accumulate");
      }
      addComputePass("spektrafilm_halation_scatter_resolve");
    }
    if (halationBounceEnabled) {
      addComputePass("spektrafilm_halation_bounce_clear");
      for (uint32_t bounce = 0u; bounce < 3u; ++bounce) {
        addComputePass("spektrafilm_halation_bounce_blur_x");
        addComputePass("spektrafilm_halation_bounce_blur_y_accumulate");
      }
      addComputePass("spektrafilm_halation_resolve_log_raw");
    } else {
      addComputePass("spektrafilm_raw_to_log_raw");
    }
  }

  RendererPassDiagnostics developPass{};
  developPass.name = "spektrafilm_curve_develop";
  developPass.width = static_cast<uint32_t>(width);
  developPass.height = static_cast<uint32_t>(height);
  developPass.threadgroupWidth = 32;
  developPass.threadgroupHeight = 8;
  developPass.estimatedBytes = static_cast<uint64_t>(pixelStorageByteCount) * 2u;
  diagnostics.passes.push_back(developPass);

  if (dirPath) {
    addComputePass("spektrafilm_dir_correction_from_density");
    if (dirBlurPath) {
      addComputePass("spektrafilm_dir_blur_x");
      addComputePass("spektrafilm_dir_blur_y");
    }
    if (dirTailPath) {
      addComputePass("spektrafilm_dir_tail_clear");
      for (uint32_t component = 0u; component < 3u; ++component) {
        addComputePass("spektrafilm_dir_tail_blur_x");
        addComputePass("spektrafilm_dir_tail_blur_y_accumulate");
      }
    }
    addComputePass("spektrafilm_dir_redevelop");
  }

  if (previewGrainPath) {
    addComputePass("spektrafilm_preview_grain_from_density");
  } else if (productionGrainPath) {
    addComputePassDepth("spektrafilm_production_grain_layers", 9u);
    addComputePassDepth("spektrafilm_grain_layer_blur_x", 9u);
    addComputePassDepth("spektrafilm_grain_layer_blur_y", 9u);
    addComputePass("spektrafilm_grain_microstructure_source");
    addComputePass("spektrafilm_grain_micro_blur_x");
    addComputePass("spektrafilm_grain_micro_blur_y");
    addComputePass("spektrafilm_grain_resolve_density");
    addComputePass("spektrafilm_grain_density_blur_x");
    addComputePass("spektrafilm_grain_density_blur_y");
    addComputePass("spektrafilm_grain_apply_controls");
  } else if (grainSynthesisPath) {
    addComputePassDepth("spektrafilm_grain_synthesis_layers_from_density", 9u);
    addComputePassDepth("spektrafilm_grain_layer_blur_x", 9u);
    addComputePassDepth("spektrafilm_grain_layer_blur_y", 9u);
    addComputePass("spektrafilm_grain_microstructure_source");
    addComputePass("spektrafilm_grain_micro_blur_x");
    addComputePass("spektrafilm_grain_micro_blur_y");
    addComputePass("spektrafilm_grain_synthesis_resolve_density");
    addComputePass("spektrafilm_grain_density_blur_x");
    addComputePass("spektrafilm_grain_density_blur_y");
    addComputePass("spektrafilm_grain_synthesis_copy_density");
  }

  if (printScanPassEnabled) {
    const auto addPrintPass = [&](const std::string &name) {
      addComputePass(name);
      diagnostics.passes.back().threadgroupWidth = printGroupWidth;
    };
    if (printDiffusionPath) {
      addPrintPass(finalProcessNegative ? "spektrafilm_print_raw_from_negative_light" : "spektrafilm_print_raw_from_film_density");
      addDiffusionPasses("spektrafilm_print", printDiffusionComponents);
      addPrintPass("spektrafilm_final_from_print_raw");
    } else {
      addPrintPass("spektrafilm_print_scan");
    }
  }

  if (scannerPostPath) {
    if (printGlarePath) {
      addComputePass("spektrafilm_print_glare_generate");
      if (printGlareBlurPath) {
        addComputePass("spektrafilm_print_glare_blur_x");
        addComputePass("spektrafilm_print_glare_blur_y");
      }
      addComputePass("spektrafilm_print_glare_apply");
    }
    if (scannerBlurPath) {
      addComputePass("spektrafilm_scanner_blur_x");
      addComputePass("spektrafilm_scanner_blur_y");
    }
    if (scannerUnsharpPath) {
      addComputePass("spektrafilm_scanner_unsharp_blur_x");
      addComputePass("spektrafilm_scanner_unsharp_blur_y");
    }
    addComputePass("spektrafilm_scanner_finalize");
  }

  refreshTransientBudgetEntry();
  enforceTransientBudget();
  updateSharedDiagnostics();
  return true;
}

bool VulkanRenderer::Impl::computeHalationBoostMilestone(
  const ImageView &source,
  const RenderWindow &window,
  const RenderParams &params,
  uint32_t centerTileWidth,
  uint32_t centerTileHeight
) {
  const int32_t fullWidth = window.x2 - window.x1;
  const int32_t fullHeight = window.y2 - window.y1;
  if (fullWidth <= 0 || fullHeight <= 0) {
    return true;
  }
  if (!windowFitsView(source, window, fullWidth, fullHeight)) {
    lastError = "The requested halation boost milestone window does not fit inside the Vulkan source image view.";
    return false;
  }

  centerTileWidth = std::max(centerTileWidth, 1u);
  centerTileHeight = std::max(centerTileHeight, 1u);
  const uint32_t tileColumns =
    (static_cast<uint32_t>(fullWidth) + centerTileWidth - 1u) / centerTileWidth;
  const uint32_t tileRows =
    (static_cast<uint32_t>(fullHeight) + centerTileHeight - 1u) / centerTileHeight;

  if (!prepareCorePipelines(kCoreFilmExposure | kCoreHalation) ||
      !prepareStaticFilmResources(params, false, false) || !ensureCoreFrameResources()) {
    return false;
  }

  std::array<float, kCoreFrameFloatCount> frameFloats{};
  frameFloats[75] = params.halationBoostEv;
  frameFloats[76] = params.halationBoostRange;
  frameFloats[77] = params.halationProtectEv;
  if (!uploadScratchBuffer(
        coreFrame.frameFloats,
        frameFloats.data(),
        static_cast<VkDeviceSize>(frameFloats.size() * sizeof(float)),
        "core halation boost milestone frame params"
      )) {
    return false;
  }

  VkDescriptorBufferInfo logExposureBufferInfo{};
  logExposureBufferInfo.buffer = staticFilm.logExposure.buffer;
  logExposureBufferInfo.offset = 0;
  logExposureBufferInfo.range = staticFilm.logExposure.capacity;
  VkDescriptorBufferInfo densityCurvesBufferInfo{};
  densityCurvesBufferInfo.buffer = staticFilm.densityCurves.buffer;
  densityCurvesBufferInfo.offset = 0;
  densityCurvesBufferInfo.range = staticFilm.densityCurves.capacity;
  VkDescriptorBufferInfo inputToSrgbBufferInfo{};
  inputToSrgbBufferInfo.buffer = staticFilm.inputToSrgb.buffer;
  inputToSrgbBufferInfo.offset = 0;
  inputToSrgbBufferInfo.range = staticFilm.inputToSrgb.capacity;
  VkDescriptorBufferInfo colorDecodeLutsBufferInfo{};
  colorDecodeLutsBufferInfo.buffer = staticFilm.colorDecodeLuts.buffer;
  colorDecodeLutsBufferInfo.offset = 0;
  colorDecodeLutsBufferInfo.range = staticFilm.colorDecodeLuts.capacity;
  VkDescriptorBufferInfo colorTransferKindsBufferInfo{};
  colorTransferKindsBufferInfo.buffer = staticFilm.colorTransferKinds.buffer;
  colorTransferKindsBufferInfo.offset = 0;
  colorTransferKindsBufferInfo.range = staticFilm.colorTransferKinds.capacity;
  VkDescriptorBufferInfo mallettRawMatrixBufferInfo{};
  mallettRawMatrixBufferInfo.buffer = staticFilm.mallettRawMatrix.buffer;
  mallettRawMatrixBufferInfo.offset = 0;
  mallettRawMatrixBufferInfo.range = staticFilm.mallettRawMatrix.capacity;
  VkDescriptorBufferInfo inputToReferenceXyzBufferInfo{};
  inputToReferenceXyzBufferInfo.buffer = staticFilm.inputToReferenceXyz.buffer;
  inputToReferenceXyzBufferInfo.offset = 0;
  inputToReferenceXyzBufferInfo.range = staticFilm.inputToReferenceXyz.capacity;
  VkDescriptorBufferInfo hanatosRawResponseBufferInfo{};
  hanatosRawResponseBufferInfo.buffer = staticFilm.hanatosRawResponse.buffer;
  hanatosRawResponseBufferInfo.offset = 0;
  hanatosRawResponseBufferInfo.range = staticFilm.hanatosRawResponse.capacity;

  float globalMaxRaw = 0.0f;
  for (uint32_t tileY = 0u; tileY < tileRows; ++tileY) {
    for (uint32_t tileX = 0u; tileX < tileColumns; ++tileX) {
      const int32_t tileX0 = window.x1 + static_cast<int32_t>(tileX * centerTileWidth);
      const int32_t tileY0 = window.y1 + static_cast<int32_t>(tileY * centerTileHeight);
      const int32_t tileX1 = std::min(window.x2, tileX0 + static_cast<int32_t>(centerTileWidth));
      const int32_t tileY1 = std::min(window.y2, tileY0 + static_cast<int32_t>(centerTileHeight));
      const int32_t tileWidth = tileX1 - tileX0;
      const int32_t tileHeight = tileY1 - tileY0;
      if (tileWidth <= 0 || tileHeight <= 0) {
        continue;
      }

      const uint64_t pixelCount =
        static_cast<uint64_t>(tileWidth) * static_cast<uint64_t>(tileHeight);
      const uint64_t byteCount64 = pixelCount * 4u * sizeof(float);
      if (byteCount64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max()) ||
          byteCount64 > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        lastError = "The Vulkan halation boost milestone tile is too large.";
        return false;
      }
      const VkDeviceSize byteCount = static_cast<VkDeviceSize>(byteCount64);
      const uint32_t chunkCount = static_cast<uint32_t>(
        (pixelCount + kHalationBoostMaxChunkPixels - 1u) / kHalationBoostMaxChunkPixels
      );
      const VkDeviceSize chunkByteCount = static_cast<VkDeviceSize>(
        std::max<uint64_t>(chunkCount, 1u) * 4u * sizeof(float)
      );

      if (!ensureUploadScratchBuffer(
            coreFrame.sourceStaging,
            byteCount,
            "core halation boost milestone source staging",
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
          ) ||
          !ensurePrivateScratchBuffer(coreFrame.source, byteCount, "core halation boost milestone source") ||
          !ensurePrivateScratchBuffer(coreFrame.filmRaw, byteCount, "core halation boost milestone film raw") ||
          !ensurePrivateScratchBuffer(
            coreFrame.halationBoostChunks,
            chunkByteCount,
            "core halation boost milestone chunks"
          ) ||
          !ensurePrivateScratchBuffer(
            coreFrame.halationBoostInfo,
            4u * sizeof(float),
            "core halation boost milestone info"
          ) ||
          !ensureReadbackScratchBuffer(
            coreFrame.halationBoostInfoReadback,
            4u * sizeof(float),
            "core halation boost milestone info readback",
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
          )) {
        return false;
      }

      if (!coreFrame.sourceStaging.mapped) {
        lastError = "Vulkan halation boost milestone source staging buffer is not mapped.";
        return false;
      }
      const RenderWindow tileWindow{tileX0, tileY0, tileX1, tileY1};
      if (!copySourceToFloatStaging(
            source,
            tileWindow,
            tileWidth,
            tileHeight,
            static_cast<float *>(coreFrame.sourceStaging.mapped)
          )) {
        lastError = "The requested halation boost milestone tile does not fit inside the Vulkan source image view.";
        return false;
      }
      if (!flushMappedScratchBuffer(coreFrame.sourceStaging, byteCount, "core halation boost milestone source staging")) {
        return false;
      }

      VkDescriptorBufferInfo sourceBufferInfo{};
      sourceBufferInfo.buffer = coreFrame.source.buffer;
      sourceBufferInfo.offset = 0;
      sourceBufferInfo.range = byteCount;
      VkDescriptorBufferInfo filmRawBufferInfo{};
      filmRawBufferInfo.buffer = coreFrame.filmRaw.buffer;
      filmRawBufferInfo.offset = 0;
      filmRawBufferInfo.range = byteCount;
      VkDescriptorBufferInfo halationBoostChunksBufferInfo{};
      halationBoostChunksBufferInfo.buffer = coreFrame.halationBoostChunks.buffer;
      halationBoostChunksBufferInfo.offset = 0;
      halationBoostChunksBufferInfo.range = coreFrame.halationBoostChunks.capacity;
      VkDescriptorBufferInfo halationBoostInfoBufferInfo{};
      halationBoostInfoBufferInfo.buffer = coreFrame.halationBoostInfo.buffer;
      halationBoostInfoBufferInfo.offset = 0;
      halationBoostInfoBufferInfo.range = coreFrame.halationBoostInfo.capacity;
      VkDescriptorBufferInfo frameFloatsBufferInfo{};
      frameFloatsBufferInfo.buffer = coreFrame.frameFloats.buffer;
      frameFloatsBufferInfo.offset = 0;
      frameFloatsBufferInfo.range = coreFrame.frameFloats.capacity;

      std::array<VkWriteDescriptorSet, 20> writes{};
      uint32_t writeCount = 0;
      auto writeStorageBuffer = [&](VkDescriptorSet set, uint32_t binding, const VkDescriptorBufferInfo &bufferInfo) {
        VkWriteDescriptorSet &write = writes[writeCount++];
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = set;
        write.dstBinding = binding;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &bufferInfo;
      };
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 0, sourceBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 1, filmRawBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 2, logExposureBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 3, densityCurvesBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 4, inputToSrgbBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 5, colorDecodeLutsBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 6, colorTransferKindsBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 7, mallettRawMatrixBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 8, inputToReferenceXyzBufferInfo);
      writeStorageBuffer(coreFrame.exposureDescriptorSet, 9, hanatosRawResponseBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[10], 0, filmRawBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[10], 1, halationBoostChunksBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[10], 2, halationBoostInfoBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[10], 3, filmRawBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[10], 27, frameFloatsBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[11], 0, halationBoostChunksBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[11], 1, halationBoostInfoBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[11], 2, halationBoostInfoBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[11], 3, filmRawBufferInfo);
      writeStorageBuffer(coreFrame.halationDescriptorSets[11], 27, frameFloatsBufferInfo);
      if (!updateStorageDescriptors(writeCount, writes.data())) return false;

      VkCommandBuffer commandBuffer = coreFrame.commandBuffer;

      camera_gpu::ComputeRecorder recorder(device, commandBuffer, [&](const VkSubmitInfo& submit, VkFence fence) {
        return backend ? backend->submit(queueIndex, submit, fence) : VK_ERROR_INITIALIZATION_FAILED;
      });
      VkResult result = vkResetCommandBuffer(commandBuffer, 0);
      if (result != VK_SUCCESS) {
        lastError = vkError("vkResetCommandBuffer(halation boost milestone)", result);
        return false;
      }

      VkCommandBufferBeginInfo beginInfo{};
      beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
      beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
      result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
      if (result != VK_SUCCESS) {
        lastError = vkError("vkBeginCommandBuffer(halation boost milestone)", result);
        return false;
      }

      VkMemoryBarrier hostInputBarrier{};
      hostInputBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
      hostInputBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
      hostInputBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
      vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        1,
        &hostInputBarrier,
        0,
        nullptr,
        0,
        nullptr
      );

      VkBufferCopy sourceUploadRegion{};
      sourceUploadRegion.size = byteCount;
      vkCmdCopyBuffer(commandBuffer, coreFrame.sourceStaging.buffer, coreFrame.source.buffer, 1, &sourceUploadRegion);

      VkMemoryBarrier sourceUploadBarrier{};
      sourceUploadBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
      sourceUploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      sourceUploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
      vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        1,
        &sourceUploadBarrier,
        0,
        nullptr,
        0,
        nullptr
      );

      VulkanCorePushConstants pushConstants{};
      pushConstants.width = static_cast<uint32_t>(tileWidth);
      pushConstants.height = static_cast<uint32_t>(tileHeight);
      pushConstants.filmExposureEv = params.filmExposureEv;
      pushConstants.filmGamma = params.filmGamma *
        (params.filmPushPullMode == PushPullMode::Standard ? filmPushPullGamma(params.filmPushPullStops) : 1.0f);
      pushConstants.exposureCount = staticFilm.exposureCount;
      pushConstants.inputColorSpace = static_cast<int32_t>(params.inputColorSpace);
      pushConstants.rgbToRawMethod = static_cast<int32_t>(params.rgbToRawMethod);
      pushConstants.colorSpaceCount = kSpektraColorSpaceCount;
      pushConstants.transferLutSize = kSpektraColorTransferLutSize;
      pushConstants.colorDecodeMin = colorDecodeLutMin();
      pushConstants.colorDecodeMax = colorDecodeLutMax();
      pushConstants.hanatosWidth = staticFilm.hanatosWidth;
      pushConstants.hanatosHeight = staticFilm.hanatosHeight;
      pushConstants.filmPushPullMode = static_cast<int32_t>(params.filmPushPullMode);
      pushConstants.filmPushPullStops = params.filmPushPullStops;
      pushConstants.fullWidth = static_cast<uint32_t>(fullWidth);
      pushConstants.fullHeight = static_cast<uint32_t>(fullHeight);
      pushConstants.tileOriginX = static_cast<uint32_t>(tileX0 - window.x1);
      pushConstants.tileOriginY = static_cast<uint32_t>(tileY0 - window.y1);

      const uint32_t groupsX = (pushConstants.width + 31u) / 32u;
      const uint32_t groupsY = (pushConstants.height + 7u) / 8u;
      auto insertComputeBarrier = [&]() {
        VkMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
          commandBuffer,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          0,
          1,
          &barrier,
          0,
          nullptr,
          0,
          nullptr
        );
      };

      pushConstants._pad0 = 1u;
      pushConstants._pad1 = colorAdaptationFlags(params);
      pushConstants._pad2 = 0u;
      recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, filmExposurePipeline);
      recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &coreFrame.exposureDescriptorSet, 0, nullptr);
      recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
      recorder.dispatch(commandBuffer, groupsX, groupsY, 1);
      insertComputeBarrier();

      auto dispatchHalation1D = [&](VkDescriptorSet descriptorSet, uint32_t operation, uint32_t value, uint32_t itemCount) {
        pushConstants._pad0 = operation;
        pushConstants._pad1 = value;
        pushConstants._pad2 = 0u;
        recorder.bindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, halationPipeline);
        recorder.bindDescriptors(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, corePipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
        recorder.push(commandBuffer, corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
        recorder.dispatch(commandBuffer, (std::max(itemCount, 1u) + 31u) / 32u, 1u, 1u);
        insertComputeBarrier();
      };
      dispatchHalation1D(
        coreFrame.halationDescriptorSets[10],
        kHalationOpBoostMax,
        kHalationBoostMaxChunkPixels,
        chunkCount
      );
      dispatchHalation1D(
        coreFrame.halationDescriptorSets[11],
        kHalationOpBoostReduceMax,
        chunkCount,
        1u
      );

      VkMemoryBarrier readbackInputBarrier{};
      readbackInputBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
      readbackInputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      readbackInputBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
      vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        1,
        &readbackInputBarrier,
        0,
        nullptr,
        0,
        nullptr
      );

      VkBufferCopy boostInfoReadbackRegion{};
      boostInfoReadbackRegion.size = 4u * sizeof(float);
      vkCmdCopyBuffer(
        commandBuffer,
        coreFrame.halationBoostInfo.buffer,
        coreFrame.halationBoostInfoReadback.buffer,
        1,
        &boostInfoReadbackRegion
      );

      VkMemoryBarrier hostReadbackBarrier{};
      hostReadbackBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
      hostReadbackBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      hostReadbackBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
      vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0,
        1,
        &hostReadbackBarrier,
        0,
        nullptr,
        0,
        nullptr
      );

      result = vkEndCommandBuffer(commandBuffer);
      if (result != VK_SUCCESS) {
        lastError = vkError("vkEndCommandBuffer(halation boost milestone)", result);
        return false;
      }

      VkFence fence = coreFrame.fence;
      result = vkResetFences(device, 1, &fence);
      if (result != VK_SUCCESS) {
        lastError = vkError("vkResetFences(halation boost milestone)", result);
        return false;
      }

      VkSubmitInfo submitInfo{};
      submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
      submitInfo.commandBufferCount = 1;
      submitInfo.pCommandBuffers = &commandBuffer;
      result = backend ? backend->submit(queueIndex, submitInfo, fence) : VK_ERROR_INITIALIZATION_FAILED;
      if (result != VK_SUCCESS) {
        lastError = vkError("vkQueueSubmit(halation boost milestone)", result);
        return false;
      }
      coreFrame.submitted = true;
      if (finishSubmittedWork(true) != VK_SUCCESS) return false;
      if (!coreFrame.halationBoostInfoReadback.mapped) {
        lastError = "Vulkan halation boost milestone readback buffer is not mapped.";
        return false;
      }
      if (!invalidateMappedScratchBuffer(
            coreFrame.halationBoostInfoReadback,
            4u * sizeof(float),
            "core halation boost milestone info readback"
          )) {
        return false;
      }
      const auto *boostInfo = static_cast<const float *>(coreFrame.halationBoostInfoReadback.mapped);
      globalMaxRaw = std::max(globalMaxRaw, boostInfo[0]);
    }
  }

  const float rawX0 = std::clamp(
    0.184f * std::exp2(params.halationProtectEv),
    0.0f,
    globalMaxRaw
  );
  const float boostRange = std::clamp(params.halationBoostRange, 0.0f, 1.0f);
  const float a = std::pow(28.0f, 1.0f - boostRange);
  const float x0 = globalMaxRaw > 0.0f ? rawX0 / globalMaxRaw : 1.0f;
  const float denom = std::exp(a * (1.0f - x0)) - a * (1.0f - x0) - 1.0f;
  const float k = (globalMaxRaw > 0.0f && rawX0 < globalMaxRaw && denom > 1.0e-10f)
    ? (std::exp2(std::max(params.halationBoostEv, 0.0f)) - 1.0f) / denom
    : 0.0f;
  const std::array<float, 4> milestoneInfo = {globalMaxRaw, rawX0, a, k};
  return uploadScratchBuffer(
    coreFrame.tiledHalationBoostInfo,
    milestoneInfo.data(),
    static_cast<VkDeviceSize>(milestoneInfo.size() * sizeof(float)),
    "core tiled halation boost info"
  );
}

bool VulkanRenderer::Impl::renderTiledBootstrap(
  const ImageView &source,
  const MutableImageView &destination,
  const RenderWindow &window,
  const RenderParams &params,
  double time
) {
  std::lock_guard<std::recursive_mutex> lock(renderMutex);
  diagnostics = {};
  diagnostics.renderSerialized = true;
  diagnostics.tiledRendering = true;
  lastError.clear();
  updateSharedDiagnostics();

  const int32_t fullWidth = window.x2 - window.x1;
  const int32_t fullHeight = window.y2 - window.y1;
  if (fullWidth <= 0 || fullHeight <= 0) {
    return true;
  }
  if (!isSupportedRgba(source, destination)) {
    lastError = "Only RGBA 16-bit half and 32-bit float images are supported by the Windows Vulkan path.";
    return false;
  }
  if (!windowFitsView(source, window, fullWidth, fullHeight) ||
      !windowFitsView(destination, window, fullWidth, fullHeight)) {
    lastError = "The requested tiled render window does not fit inside the Vulkan source or destination image view.";
    return false;
  }
  if (envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_GRAIN_PASS", true) &&
      params.grainEnabled &&
      params.grainModel == GrainModel::GrainSynthesis) {
    lastError =
      "Vulkan tiled rendering requires a full-frame pre-grain density milestone for Grain Synthesis parity; "
      "use GPU Render Tiling: Full-frame for this setting until that milestone path is implemented.";
    return false;
  }
  RenderParams tileParams = params;

  uint32_t centerTileWidth = std::min<uint32_t>(
    static_cast<uint32_t>(fullWidth),
    envTileDimension("SPEKTRAFILM_VULKAN_TILE_WIDTH", kDefaultVulkanTileWidth)
  );
  uint32_t centerTileHeight = std::min<uint32_t>(
    static_cast<uint32_t>(fullHeight),
    envTileDimension("SPEKTRAFILM_VULKAN_TILE_HEIGHT", kDefaultVulkanTileHeight)
  );
  const uint32_t overlap = tileOverlap(params, static_cast<uint32_t>(fullWidth), static_cast<uint32_t>(fullHeight));
  auto growTileForOverlap = [&](uint32_t current, uint32_t fullDimension) {
    if (overlap <= current) {
      return current;
    }
    const uint32_t overlapSizedTile = overlap > std::numeric_limits<uint32_t>::max() / 2u
      ? std::numeric_limits<uint32_t>::max()
      : overlap * 2u;
    return std::min<uint32_t>(fullDimension, std::max(current, overlapSizedTile));
  };
  centerTileWidth = growTileForOverlap(centerTileWidth, static_cast<uint32_t>(fullWidth));
  centerTileHeight = growTileForOverlap(centerTileHeight, static_cast<uint32_t>(fullHeight));
  const uint32_t tileColumns =
    (static_cast<uint32_t>(fullWidth) + centerTileWidth - 1u) / centerTileWidth;
  const uint32_t tileRows =
    (static_cast<uint32_t>(fullHeight) + centerTileHeight - 1u) / centerTileHeight;
  const uint32_t plannedTileCount = tileColumns * tileRows;
  const bool halationBoostMilestoneEnabled =
    envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_HALATION_PASS", true) &&
    tileParams.halationEnabled &&
    tileParams.halationBoostEv > 0.0f;
  if (halationBoostMilestoneEnabled &&
      !computeHalationBoostMilestone(source, window, tileParams, centerTileWidth, centerTileHeight)) {
    const std::string milestoneError = lastError.empty() ? "unknown Vulkan halation boost milestone failure" : lastError;
    lastError = "Vulkan tiled render failed while computing the halation boost milestone: " + milestoneError;
    updateSharedDiagnostics();
    return false;
  }

  const uint64_t fullSourcePixelCount =
    static_cast<uint64_t>(fullWidth) * static_cast<uint64_t>(fullHeight);
  const uint64_t fullSourceByteCount64 = fullSourcePixelCount * 4u * sizeof(float);
  if (fullSourcePixelCount == 0u ||
      fullSourceByteCount64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max()) ||
      fullSourceByteCount64 > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
    lastError = "The Vulkan tiled full-frame source upload is too large.";
    updateSharedDiagnostics();
    return false;
  }
  const VkDeviceSize fullSourceByteCount = static_cast<VkDeviceSize>(fullSourceByteCount64);
  if (!ensureCoreFrameResources() ||
      !ensureUploadScratchBuffer(
        coreFrame.sourceStaging,
        fullSourceByteCount,
        "core tiled full-frame source staging",
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
      ) ||
      !ensurePrivateScratchBuffer(coreFrame.source, fullSourceByteCount, "core tiled full-frame source")) {
    updateSharedDiagnostics();
    return false;
  }
  if (!coreFrame.sourceStaging.mapped) {
    lastError = "Vulkan tiled full-frame source staging buffer is not mapped.";
    updateSharedDiagnostics();
    return false;
  }
  if (!copySourceToFloatStaging(
        source,
        window,
        fullWidth,
        fullHeight,
        static_cast<float *>(coreFrame.sourceStaging.mapped)
      )) {
    lastError = "The requested tiled full-frame source upload does not fit inside the Vulkan source image view.";
    updateSharedDiagnostics();
    return false;
  }
  if (!flushMappedScratchBuffer(coreFrame.sourceStaging, fullSourceByteCount, "core tiled full-frame source staging") ||
      !copyBufferImmediate(
        coreFrame.sourceStaging.buffer,
        coreFrame.source.buffer,
        fullSourceByteCount,
        "vkQueueSubmit(tiled full-frame source upload)"
      )) {
    updateSharedDiagnostics();
    return false;
  }

  RendererDiagnostics aggregate{};
  aggregate.renderSerialized = true;
  aggregate.tiledRendering = true;
  aggregate.tileCount = plannedTileCount;
  aggregate.tileWidth = centerTileWidth;
  aggregate.tileHeight = centerTileHeight;
  aggregate.tileOverlap = overlap;
  aggregate.uploadBytes = static_cast<uint64_t>(fullSourceByteCount);

  const int32_t pixelBytes = destination.components * destination.bytesPerComponent;
  bool capturedPassList = false;
  bool autoExposureReady = false;
  uint32_t tileIndex = 0u;
  for (uint32_t tileY = 0u; tileY < tileRows; ++tileY) {
    for (uint32_t tileX = 0u; tileX < tileColumns; ++tileX, ++tileIndex) {
      const int32_t centerX0 = window.x1 + static_cast<int32_t>(tileX * centerTileWidth);
      const int32_t centerY0 = window.y1 + static_cast<int32_t>(tileY * centerTileHeight);
      const int32_t centerX1 = std::min(window.x2, centerX0 + static_cast<int32_t>(centerTileWidth));
      const int32_t centerY1 = std::min(window.y2, centerY0 + static_cast<int32_t>(centerTileHeight));
      const int32_t centerWidth = centerX1 - centerX0;
      const int32_t centerHeight = centerY1 - centerY0;
      if (centerWidth <= 0 || centerHeight <= 0) {
        continue;
      }

      const int32_t overlapPx = static_cast<int32_t>(std::min<uint32_t>(
        overlap,
        static_cast<uint32_t>(std::numeric_limits<int32_t>::max())
      ));
      const int32_t workingX0 = std::max(window.x1, centerX0 - overlapPx);
      const int32_t workingY0 = std::max(window.y1, centerY0 - overlapPx);
      const int32_t workingX1 = std::min(window.x2, centerX1 + overlapPx);
      const int32_t workingY1 = std::min(window.y2, centerY1 + overlapPx);
      const int32_t workingWidth = workingX1 - workingX0;
      const int32_t workingHeight = workingY1 - workingY0;
      if (workingWidth <= 0 || workingHeight <= 0) {
        lastError = "The Vulkan tiled planner produced an empty working tile.";
        diagnostics = aggregate;
        updateSharedDiagnostics();
        return false;
      }

      const uint64_t tileByteCount =
        static_cast<uint64_t>(workingWidth) *
        static_cast<uint64_t>(workingHeight) *
        static_cast<uint64_t>(pixelBytes);
      if (tileByteCount > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        lastError = "The Vulkan tiled planner produced a tile larger than host address space.";
        diagnostics = aggregate;
        updateSharedDiagnostics();
        return false;
      }

      std::vector<uint8_t> tileOutput(static_cast<size_t>(tileByteCount));
      MutableImageView tileDestination{};
      tileDestination.data = tileOutput.data();
      tileDestination.x1 = workingX0;
      tileDestination.y1 = workingY0;
      tileDestination.width = workingWidth;
      tileDestination.height = workingHeight;
      tileDestination.rowBytes = workingWidth * pixelBytes;
      tileDestination.components = destination.components;
      tileDestination.bytesPerComponent = destination.bytesPerComponent;

      activeTileContext.enabled = true;
      activeTileContext.fullWidth = static_cast<uint32_t>(fullWidth);
      activeTileContext.fullHeight = static_cast<uint32_t>(fullHeight);
      activeTileContext.tileOriginX = static_cast<uint32_t>(workingX0 - window.x1);
      activeTileContext.tileOriginY = static_cast<uint32_t>(workingY0 - window.y1);
      activeTileContext.centerOriginX = static_cast<uint32_t>(centerX0 - workingX0);
      activeTileContext.centerOriginY = static_cast<uint32_t>(centerY0 - workingY0);
      activeTileContext.centerWidth = static_cast<uint32_t>(centerWidth);
      activeTileContext.centerHeight = static_cast<uint32_t>(centerHeight);
      activeTileContext.halationBoostMilestoneEnabled = halationBoostMilestoneEnabled;
      activeTileContext.fullFrameSource = true;
      activeTileContext.autoExposureReady = autoExposureReady;
      const RenderWindow workingWindow{workingX0, workingY0, workingX1, workingY1};
      const bool tileOk = renderCoreBootstrap(source, tileDestination, workingWindow, tileParams, time);
      if (tileOk && tileParams.autoExposure) {
        autoExposureReady = true;
      }
      activeTileContext = {};

      const RendererDiagnostics tileDiagnostics = diagnostics;
      aggregate.cpuSetupMs += tileDiagnostics.cpuSetupMs;
      aggregate.sourceCopyMs += tileDiagnostics.sourceCopyMs;
      aggregate.commandBufferMs += tileDiagnostics.commandBufferMs;
      aggregate.outputCopyMs += tileDiagnostics.outputCopyMs;
      aggregate.staticAllocationBytes = std::max(aggregate.staticAllocationBytes, tileDiagnostics.staticAllocationBytes);
      aggregate.staticAllocationCount = std::max(aggregate.staticAllocationCount, tileDiagnostics.staticAllocationCount);
      aggregate.scratchAllocationBytes = std::max(aggregate.scratchAllocationBytes, tileDiagnostics.scratchAllocationBytes);
      aggregate.scratchAllocationCount = std::max(aggregate.scratchAllocationCount, tileDiagnostics.scratchAllocationCount);
      aggregate.sharedScratchAllocationBytes =
        std::max(aggregate.sharedScratchAllocationBytes, tileDiagnostics.sharedScratchAllocationBytes);
      aggregate.sharedScratchAllocationCount =
        std::max(aggregate.sharedScratchAllocationCount, tileDiagnostics.sharedScratchAllocationCount);
      aggregate.privateScratchAllocationBytes =
        std::max(aggregate.privateScratchAllocationBytes, tileDiagnostics.privateScratchAllocationBytes);
      aggregate.privateScratchAllocationCount =
        std::max(aggregate.privateScratchAllocationCount, tileDiagnostics.privateScratchAllocationCount);
      aggregate.uploadBytes = std::max(aggregate.uploadBytes, tileDiagnostics.uploadBytes);
      aggregate.sharedBackend = tileDiagnostics.sharedBackend;
      aggregate.sharedBackendGeneration = tileDiagnostics.sharedBackendGeneration;
      aggregate.sharedQueueCount = tileDiagnostics.sharedQueueCount;
      aggregate.transientCachedBytes = std::max(aggregate.transientCachedBytes, tileDiagnostics.transientCachedBytes);
      aggregate.transientBudgetBytes = tileDiagnostics.transientBudgetBytes;
      aggregate.passCount += tileDiagnostics.passCount;
      aggregate.sourceNoCopy = aggregate.sourceNoCopy && tileDiagnostics.sourceNoCopy;
      aggregate.destinationNoCopy = aggregate.destinationNoCopy && tileDiagnostics.destinationNoCopy;
      aggregate.passGpuTimingEnabled = aggregate.passGpuTimingEnabled || tileDiagnostics.passGpuTimingEnabled;
      aggregate.passGpuTimingAvailable = aggregate.passGpuTimingAvailable || tileDiagnostics.passGpuTimingAvailable;
      aggregate.privateScratchEnabled = aggregate.privateScratchEnabled || tileDiagnostics.privateScratchEnabled;
      aggregate.halationPath = aggregate.halationPath || tileDiagnostics.halationPath;
      aggregate.cameraDiffusionPath = aggregate.cameraDiffusionPath || tileDiagnostics.cameraDiffusionPath;
      aggregate.printDiffusionPath = aggregate.printDiffusionPath || tileDiagnostics.printDiffusionPath;
      aggregate.dirPath = aggregate.dirPath || tileDiagnostics.dirPath;
      aggregate.productionGrainPath = aggregate.productionGrainPath || tileDiagnostics.productionGrainPath;
      aggregate.grainSynthesisPath = aggregate.grainSynthesisPath || tileDiagnostics.grainSynthesisPath;
      aggregate.finalPostProcessPath = aggregate.finalPostProcessPath || tileDiagnostics.finalPostProcessPath;
      aggregate.scannerTextureIntermediates =
        aggregate.scannerTextureIntermediates || tileDiagnostics.scannerTextureIntermediates;
      aggregate.halationGroupedTail = aggregate.halationGroupedTail || tileDiagnostics.halationGroupedTail;
      aggregate.scannerMps = aggregate.scannerMps || tileDiagnostics.scannerMps;
      aggregate.grainBlurRecurrence = tileDiagnostics.grainBlurRecurrence;
      aggregate.diffusionGroupSize = tileDiagnostics.diffusionGroupSize;
      aggregate.threadgroupMode = tileDiagnostics.threadgroupMode;
      aggregate.passTimingMode = tileDiagnostics.passTimingMode;
      aggregate.blurBackend = tileDiagnostics.blurBackend;
      aggregate.blurDownsample = tileDiagnostics.blurDownsample;
      aggregate.intermediatePrecision = tileDiagnostics.intermediatePrecision;
      aggregate.diffusionClusterSigma = tileDiagnostics.diffusionClusterSigma;
      aggregate.dirTailBackend = tileDiagnostics.dirTailBackend;
      aggregate.densityCurveLookup = tileDiagnostics.densityCurveLookup;
      aggregate.spectralTransmittance = tileDiagnostics.spectralTransmittance;
      if (!capturedPassList) {
        aggregate.passes = tileDiagnostics.passes;
        capturedPassList = true;
      }

      RendererTileDiagnostics tileRecord{};
      tileRecord.index = tileIndex;
      tileRecord.outputX = centerX0;
      tileRecord.outputY = centerY0;
      tileRecord.outputWidth = centerWidth;
      tileRecord.outputHeight = centerHeight;
      tileRecord.workingX = workingX0;
      tileRecord.workingY = workingY0;
      tileRecord.workingWidth = workingWidth;
      tileRecord.workingHeight = workingHeight;
      tileRecord.overlap = overlap;
      tileRecord.allocatedBytes = transientAllocationBytes();
      tileRecord.submitMs = tileDiagnostics.commandBufferMs;
      tileRecord.passCount = tileDiagnostics.passCount;
      aggregate.tiles.push_back(tileRecord);

      if (!tileOk) {
        const std::string tileError = lastError.empty() ? "unknown Vulkan tile failure" : lastError;
        lastError = "Vulkan tiled render failed on tile " +
          std::to_string(tileIndex + 1u) + "/" + std::to_string(plannedTileCount) +
          " center=[" + std::to_string(centerX0) + "," + std::to_string(centerY0) +
          "-" + std::to_string(centerX1) + "," + std::to_string(centerY1) +
          "] working=[" + std::to_string(workingX0) + "," + std::to_string(workingY0) +
          "-" + std::to_string(workingX1) + "," + std::to_string(workingY1) +
          "]: " + tileError;
        diagnostics = aggregate;
        updateSharedDiagnostics();
        return false;
      }

      const RenderWindow centerWindow{centerX0, centerY0, centerX1, centerY1};
      if (!copyMappedBytesRegionToWindow(
            tileOutput.data(),
            tileOutput.size(),
            workingWidth,
            workingHeight,
            centerX0 - workingX0,
            centerY0 - workingY0,
            destination,
            centerWindow,
            centerWidth,
            centerHeight
          )) {
        lastError = "Vulkan tiled render failed to copy a tile center into the destination image view.";
        diagnostics = aggregate;
        updateSharedDiagnostics();
        return false;
      }
    }
  }

  diagnostics = aggregate;
  diagnostics.tiledRendering = true;
  diagnostics.tileCount = plannedTileCount;
  diagnostics.tileWidth = centerTileWidth;
  diagnostics.tileHeight = centerTileHeight;
  diagnostics.tileOverlap = overlap;
  refreshTransientBudgetEntry();
  enforceTransientBudget();
  updateSharedDiagnostics();
  return true;
}

bool VulkanRenderer::Impl::renderExternalImages(
  VkImage sourceImage,
  VkImage destinationImage,
  uint32_t width,
  uint32_t height,
  const RenderParams &params,
  double time
) {
  bufferAllocationFailed = false;
  const bool preferredPrivateScratch = preferPrivateScratch;
  if (sourceImage == VK_NULL_HANDLE || destinationImage == VK_NULL_HANDLE ||
      width == 0u || height == 0u ||
      width > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()) ||
      height > static_cast<uint32_t>(std::numeric_limits<int32_t>::max())) {
    lastError = "Invalid external Vulkan image render arguments.";
    return false;
  }

  ImageView source{};
  source.data = reinterpret_cast<const void *>(uintptr_t{1});
  source.width = static_cast<int32_t>(width);
  source.height = static_cast<int32_t>(height);
  source.rowBytes = static_cast<int32_t>(width * 4u * sizeof(uint16_t));
  source.components = 4;
  source.bytesPerComponent = sizeof(uint16_t);

  MutableImageView destination{};
  destination.data = reinterpret_cast<void *>(uintptr_t{1});
  destination.width = static_cast<int32_t>(width);
  destination.height = static_cast<int32_t>(height);
  destination.rowBytes = source.rowBytes;
  destination.components = 4;
  destination.bytesPerComponent = sizeof(uint16_t);

  RenderWindow window{};
  window.x2 = static_cast<int32_t>(width);
  window.y2 = static_cast<int32_t>(height);

  const bool tiledRequested = resolveVulkanTileMode(params) == GpuRenderTilingMode::Tiled ||
    requiredStorageBufferRange(params, width, height) > storageBufferCapacity();
  const bool requiresFullFrameMilestone =
    (envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_GRAIN_PASS", true) &&
     params.grainEnabled &&
     params.grainModel == GrainModel::GrainSynthesis) ||
    (envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_HALATION_PASS", true) &&
     params.halationEnabled &&
     params.halationBoostEv > 0.0f);
  const auto cameraDiffusionShape = diffusionShape(params.cameraDiffusionFamily);
  const float cameraDiffusionWeight =
    cameraDiffusionShape.weightCore * std::max(params.cameraDiffusionCoreIntensity, 0.0f) +
    cameraDiffusionShape.weightHalo * std::max(params.cameraDiffusionHaloIntensity, 0.0f) +
    cameraDiffusionShape.weightBloom * std::max(params.cameraDiffusionBloomIntensity, 0.0f);
  const bool canSeparateDiffusion = !previewOptimizations && !requiresFullFrameMilestone &&
      sourceImage != destinationImage && params.renderOutput == RenderOutputMode::FinalPreview &&
      params.process != ProcessMode::ProcessNegative && params.cameraDiffusionEnabled &&
      params.cameraDiffusionStrength > 0.0f && params.cameraDiffusionSpatialScale > 0.0f &&
      cameraDiffusionWeight > 0.0f &&
      envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_DIFFUSION_PASS", true);
  const bool combinedHalation = params.halationEnabled &&
      ((params.scatterAmount > 0.0f && params.scatterScale > 0.0f) ||
       (params.halationAmount > 0.0f && params.halationScale > 0.0f &&
        (params.halationStrengthR > 0.0f || params.halationStrengthG > 0.0f || params.halationStrengthB > 0.0f))) &&
      envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_HALATION_PASS", true);
  const std::string separate = canSeparateDiffusion ? envString("SPEKTRAFILM_VULKAN_SEPARATE_EFFECTS", "auto") : "0";
  const auto finishRender = [&](bool ok) {
    if (ok || !bufferAllocationFailed || !canSeparateDiffusion || separate == "0") return ok;
#if defined(__ANDROID__)
    recordNativeDiagnostic(ANDROID_LOG_INFO, "ExportMemory", "retry_separate_effects=1 reason=%s", lastError.c_str());
#endif
    preferPrivateScratch = preferredPrivateScratch;
    return renderSeparateEffects(sourceImage, destinationImage, width, height, params, time);
  };
  if (canSeparateDiffusion && (combinedHalation || separate == "1")) {
    const auto memory = readSystemMemory();
    const uint64_t overlap = tileOverlap(params, width, height);
    const uint64_t workingPixels = std::min(uint64_t{width}, kDefaultExternalVulkanTileWidth + 2u * overlap) *
      std::min(uint64_t{height}, kDefaultExternalVulkanTileHeight + 2u * overlap);
    if (separate == "1" || (separate != "0" && export_memory::needsSeparateEffects(
        physicalDeviceProperties.vendorID, uint64_t{width} * height, memory.total, memory.available, workingPixels))) {
#if defined(__ANDROID__)
      recordNativeDiagnostic(ANDROID_LOG_INFO, "ExportMemory",
        "separate_effects=1 pixels=%llu available_bytes=%llu combined_tile_bytes=%llu",
        static_cast<unsigned long long>(uint64_t{width} * height),
        static_cast<unsigned long long>(memory.available), static_cast<unsigned long long>(workingPixels * 248u));
#endif
      return renderSeparateEffects(sourceImage, destinationImage, width, height, params, time);
    }
  }
  if (tiledRequested && !requiresFullFrameMilestone) {
    return finishRender(renderTiledExternalImages(
      sourceImage,
      destinationImage,
      width,
      height,
      params,
      time
    ));
  }

  activeExternalImageContext.enabled = true;
  activeExternalImageContext.sourceImage = sourceImage;
  activeExternalImageContext.destinationImage = destinationImage;
  activeExternalImageContext.views = suppliedImageViews;
  const bool ok = renderCoreBootstrap(source, destination, window, params, time);
  activeExternalImageContext = {};
  return finishRender(ok);
}

bool VulkanRenderer::Impl::renderSeparateEffects(
  VkImage sourceImage, VkImage destinationImage, uint32_t width, uint32_t height,
  const RenderParams &params, double time
) {
  std::lock_guard<std::recursive_mutex> lock(renderMutex);
  if (finishSubmittedWork(true) != VK_SUCCESS) return false;
  releaseTransientResourcesNoLock();
  struct Milestone {
    Impl &renderer;
    ScratchBuffer buffer;
    ~Milestone() {
      renderer.finishSubmittedWork(true);
      renderer.diffusionMilestone = VK_NULL_HANDLE;
      renderer.writingDiffusionMilestone = false;
      renderer.activeTileContext = {};
      renderer.activeExternalImageContext = {};
      renderer.destroyScratchBuffer(buffer);
    }
  } milestone{*this, {}};
  if (!ensurePrivateScratchBuffer(milestone.buffer, uint64_t{width} * height * 16u, "diffusion milestone")) return false;
  diffusionMilestone = milestone.buffer.buffer;
  writingDiffusionMilestone = true;
  RenderParams diffusionParams = params;
  diffusionParams.renderOutput = RenderOutputMode::FilmLogRaw;
  diffusionParams.halationEnabled = false;
  diffusionParams.grainEnabled = false;
  diffusionParams.dirCouplersAmount = 0.0f;
  diffusionParams.printDiffusionEnabled = false;
  diffusionParams.scannerEnabled = false;
  bool ok = renderTiledExternalImages(sourceImage, destinationImage, width, height, diffusionParams, time);
  const auto diffusionDiagnostics = diagnostics;
  if (finishSubmittedWork(true) != VK_SUCCESS) ok = false;
#if defined(__ANDROID__)
  recordNativeDiagnostic(ANDROID_LOG_INFO, "ExportMemory", "diffusion_milestone_complete=%d working_bytes=%llu",
    ok, static_cast<unsigned long long>(diffusionDiagnostics.transientCachedBytes + milestone.buffer.allocationSize));
#endif
  releaseTransientResourcesNoLock();
  writingDiffusionMilestone = false;
  if (ok) {
    RenderParams remainingParams = params;
    remainingParams.cameraDiffusionEnabled = false;
    ok = renderTiledExternalImages(sourceImage, destinationImage, width, height, remainingParams, time);
  }
  if (finishSubmittedWork(true) != VK_SUCCESS) ok = false;
  diagnostics.cameraDiffusionPath = diffusionDiagnostics.cameraDiffusionPath;
  diagnostics.tileCount += diffusionDiagnostics.tileCount;
  diagnostics.passCount += diffusionDiagnostics.passCount;
  diagnostics.cpuSetupMs += diffusionDiagnostics.cpuSetupMs;
  diagnostics.commandBufferMs += diffusionDiagnostics.commandBufferMs;
  diagnostics.transientCachedBytes = std::max(diagnostics.transientCachedBytes,
    diffusionDiagnostics.transientCachedBytes) + uint64_t{width} * height * 16u;
#if defined(__ANDROID__)
  recordNativeDiagnostic(ANDROID_LOG_INFO, "ExportMemory", "separate_effects_complete=%d peak_working_bytes=%llu",
    ok, static_cast<unsigned long long>(diagnostics.transientCachedBytes));
#endif
  return ok;
}

bool VulkanRenderer::Impl::renderTiledExternalImages(
  VkImage sourceImage,
  VkImage destinationImage,
  uint32_t width,
  uint32_t height,
  const RenderParams &params,
  double time
) {
  std::lock_guard<std::recursive_mutex> lock(renderMutex);
  diagnostics = {};
  diagnostics.renderSerialized = true;
  diagnostics.tiledRendering = true;
  lastError.clear();
  updateSharedDiagnostics();

  uint32_t centerTileWidth = std::min(
    width,
    envTileDimension("SPEKTRAFILM_VULKAN_TILE_WIDTH", kDefaultExternalVulkanTileWidth)
  );
  uint32_t centerTileHeight = std::min(
    height,
    envTileDimension("SPEKTRAFILM_VULKAN_TILE_HEIGHT", kDefaultExternalVulkanTileHeight)
  );
  const uint32_t overlap = tileOverlap(params, width, height);

  if (!fitTileToStorageBufferLimit(params, width, height, overlap, centerTileWidth, centerTileHeight)) {
    return false;
  }

  const uint32_t tileColumns = (width + centerTileWidth - 1u) / centerTileWidth;
  const uint32_t tileRows = (height + centerTileHeight - 1u) / centerTileHeight;
  const uint32_t plannedTileCount = tileColumns * tileRows;

  RendererDiagnostics aggregate{};
  aggregate.renderSerialized = true;
  aggregate.tiledRendering = true;
  aggregate.tileCount = plannedTileCount;
  aggregate.tileWidth = centerTileWidth;
  aggregate.tileHeight = centerTileHeight;
  aggregate.tileOverlap = overlap;

  activeExternalImageContext.enabled = true;
  activeExternalImageContext.sourceImage = sourceImage;
  activeExternalImageContext.destinationImage = destinationImage;

  bool capturedPassList = false;
  bool autoExposureReady = false;
  uint32_t tileIndex = 0u;
  for (uint32_t tileY = 0u; tileY < tileRows; ++tileY) {
    for (uint32_t tileX = 0u; tileX < tileColumns; ++tileX, ++tileIndex) {
      const uint32_t centerX0 = tileX * centerTileWidth;
      const uint32_t centerY0 = tileY * centerTileHeight;
      const uint32_t centerX1 = std::min(width, centerX0 + centerTileWidth);
      const uint32_t centerY1 = std::min(height, centerY0 + centerTileHeight);
      const uint32_t workingX0 = centerX0 > overlap ? centerX0 - overlap : 0u;
      const uint32_t workingY0 = centerY0 > overlap ? centerY0 - overlap : 0u;
      const uint32_t workingX1 = std::min(width, centerX1 + overlap);
      const uint32_t workingY1 = std::min(height, centerY1 + overlap);
      const uint32_t workingWidth = workingX1 - workingX0;
      const uint32_t workingHeight = workingY1 - workingY0;

      activeTileContext.enabled = true;
      activeTileContext.fullWidth = width;
      activeTileContext.fullHeight = height;
      activeTileContext.tileOriginX = workingX0;
      activeTileContext.tileOriginY = workingY0;
      activeTileContext.centerOriginX = centerX0 - workingX0;
      activeTileContext.centerOriginY = centerY0 - workingY0;
      activeTileContext.centerWidth = centerX1 - centerX0;
      activeTileContext.centerHeight = centerY1 - centerY0;
      activeTileContext.fullFrameSource = false;
      activeTileContext.autoExposureReady = autoExposureReady;
      activeTileContext.halationBoostMilestoneEnabled = false;

      ImageView tileSource{};
      tileSource.data = reinterpret_cast<const void *>(uintptr_t{1});
      tileSource.width = static_cast<int32_t>(width);
      tileSource.height = static_cast<int32_t>(height);
      tileSource.rowBytes = static_cast<int32_t>(width * 4u * sizeof(uint16_t));
      tileSource.components = 4;
      tileSource.bytesPerComponent = sizeof(uint16_t);

      MutableImageView tileDestination{};
      tileDestination.data = reinterpret_cast<void *>(uintptr_t{1});
      tileDestination.width = static_cast<int32_t>(width);
      tileDestination.height = static_cast<int32_t>(height);
      tileDestination.rowBytes = tileSource.rowBytes;
      tileDestination.components = 4;
      tileDestination.bytesPerComponent = sizeof(uint16_t);

      const RenderWindow workingWindow{
        static_cast<int32_t>(workingX0),
        static_cast<int32_t>(workingY0),
        static_cast<int32_t>(workingX1),
        static_cast<int32_t>(workingY1)
      };
      const bool tileOk =
        renderCoreBootstrap(tileSource, tileDestination, workingWindow, params, time);
      if (tileOk && params.autoExposure) autoExposureReady = true;
      activeTileContext = {};

      const RendererDiagnostics tileDiagnostics = diagnostics;
      aggregate.cpuSetupMs += tileDiagnostics.cpuSetupMs;
      aggregate.sourceCopyMs += tileDiagnostics.sourceCopyMs;
      aggregate.commandBufferMs += tileDiagnostics.commandBufferMs;
      aggregate.outputCopyMs += tileDiagnostics.outputCopyMs;
      aggregate.staticAllocationBytes =
        std::max(aggregate.staticAllocationBytes, tileDiagnostics.staticAllocationBytes);
      aggregate.staticAllocationCount =
        std::max(aggregate.staticAllocationCount, tileDiagnostics.staticAllocationCount);
      aggregate.scratchAllocationBytes =
        std::max(aggregate.scratchAllocationBytes, tileDiagnostics.scratchAllocationBytes);
      aggregate.scratchAllocationCount =
        std::max(aggregate.scratchAllocationCount, tileDiagnostics.scratchAllocationCount);
      aggregate.sharedScratchAllocationBytes =
        std::max(aggregate.sharedScratchAllocationBytes, tileDiagnostics.sharedScratchAllocationBytes);
      aggregate.sharedScratchAllocationCount =
        std::max(aggregate.sharedScratchAllocationCount, tileDiagnostics.sharedScratchAllocationCount);
      aggregate.privateScratchAllocationBytes =
        std::max(aggregate.privateScratchAllocationBytes, tileDiagnostics.privateScratchAllocationBytes);
      aggregate.privateScratchAllocationCount =
        std::max(aggregate.privateScratchAllocationCount, tileDiagnostics.privateScratchAllocationCount);
      aggregate.uploadBytes += tileDiagnostics.uploadBytes;
      aggregate.passCount += tileDiagnostics.passCount;
      aggregate.sharedBackend = tileDiagnostics.sharedBackend;
      aggregate.sharedBackendGeneration = tileDiagnostics.sharedBackendGeneration;
      aggregate.sharedQueueCount = tileDiagnostics.sharedQueueCount;
      aggregate.transientCachedBytes =
        std::max(aggregate.transientCachedBytes, tileDiagnostics.transientCachedBytes);
      aggregate.transientBudgetBytes = tileDiagnostics.transientBudgetBytes;
      aggregate.passGpuTimingEnabled =
        aggregate.passGpuTimingEnabled || tileDiagnostics.passGpuTimingEnabled;
      aggregate.passGpuTimingAvailable =
        aggregate.passGpuTimingAvailable || tileDiagnostics.passGpuTimingAvailable;
      aggregate.privateScratchEnabled =
        aggregate.privateScratchEnabled || tileDiagnostics.privateScratchEnabled;
      aggregate.halationPath = aggregate.halationPath || tileDiagnostics.halationPath;
      aggregate.cameraDiffusionPath =
        aggregate.cameraDiffusionPath || tileDiagnostics.cameraDiffusionPath;
      aggregate.printDiffusionPath =
        aggregate.printDiffusionPath || tileDiagnostics.printDiffusionPath;
      aggregate.dirPath = aggregate.dirPath || tileDiagnostics.dirPath;
      aggregate.productionGrainPath =
        aggregate.productionGrainPath || tileDiagnostics.productionGrainPath;
      aggregate.grainSynthesisPath =
        aggregate.grainSynthesisPath || tileDiagnostics.grainSynthesisPath;
      aggregate.finalPostProcessPath =
        aggregate.finalPostProcessPath || tileDiagnostics.finalPostProcessPath;
      if (!capturedPassList) {
        aggregate.passes = tileDiagnostics.passes;
        capturedPassList = true;
      }

      RendererTileDiagnostics tileRecord{};
      tileRecord.index = tileIndex;
      tileRecord.outputX = static_cast<int32_t>(centerX0);
      tileRecord.outputY = static_cast<int32_t>(centerY0);
      tileRecord.outputWidth = static_cast<int32_t>(centerX1 - centerX0);
      tileRecord.outputHeight = static_cast<int32_t>(centerY1 - centerY0);
      tileRecord.workingX = static_cast<int32_t>(workingX0);
      tileRecord.workingY = static_cast<int32_t>(workingY0);
      tileRecord.workingWidth = static_cast<int32_t>(workingWidth);
      tileRecord.workingHeight = static_cast<int32_t>(workingHeight);
      tileRecord.overlap = overlap;
      tileRecord.allocatedBytes = transientAllocationBytes();
      tileRecord.submitMs = tileDiagnostics.commandBufferMs;
      tileRecord.passCount = tileDiagnostics.passCount;
      aggregate.tiles.push_back(tileRecord);

      if (!tileOk) {
        const std::string tileError =
          lastError.empty() ? "unknown Vulkan tile failure" : lastError;
        lastError =
          "GPU-native Vulkan tiled render failed on tile " +
          std::to_string(tileIndex + 1u) + "/" + std::to_string(plannedTileCount) +
          ": " + tileError;
        activeExternalImageContext = {};
        diagnostics = aggregate;
        updateSharedDiagnostics();
        return false;
      }
    }
  }

  activeExternalImageContext = {};
  diagnostics = aggregate;
  refreshTransientBudgetEntry();
  enforceTransientBudget();
  updateSharedDiagnostics();
  return true;
}

VulkanRenderer::VulkanRenderer() : impl_(std::make_unique<Impl>()) {}

VulkanRenderer::VulkanRenderer(const ExternalVulkanContext &externalContext)
  : externalContext_(externalContext),
    hasExternalContext_(true),
    impl_(std::make_unique<Impl>(&externalContext_)) {}

VulkanRenderer::~VulkanRenderer() = default;

uint32_t VulkanRenderer::startupPipelinePartCount(bool preview, uint32_t step) {
  if (!preview) return 1u;
  switch (step) {
    case 5: return kOperationPipelineCounts[0];
    case 6: return kOperationPipelineCounts[1];
    case 17: return kOperationPipelineCounts[3];
    case 23: return kOperationPipelineCounts[2];
    default: return 1u;
  }
}

bool VulkanRenderer::prepareStartupPipelines(bool preview, uint32_t step, int32_t part) {
  if (part < -1 || (part >= 0 && static_cast<uint32_t>(part) >= startupPipelinePartCount(preview, step))) return false;
  if (!impl_ || !impl_->available) return false;
  std::lock_guard<std::recursive_mutex> lock(impl_->renderMutex);
  auto &backend = *impl_->backend;
  bool ok = false;
  switch (step) {
    case 0:
      ok = impl_->prepareCorePipelines(kCoreAutoExposure);
      break;
    case 1:
      ok = preview ? backend.preparePreviewDevelopment()
        : impl_->prepareCorePipelines(kCoreFilmExposure | kCoreCurveDevelop);
      break;
    case 2:
      ok = preview || backend.physicalDeviceProperties.vendorID == 0x13b5u
        ? backend.prepareCompactPrint(false, true)
        : backend.requiresSpecializedPrint() ? backend.prepareOperationPipelines(2u)
        : impl_->prepareCorePipelines(kCorePrintScan);
      break;
    case 3:
      ok = preview ? backend.prepareFourStageLut(true)
        : impl_->prepareCorePipelines(kCoreHalation);
      break;
    case 4:
      ok = preview ? backend.preparePreviewOutput()
        : impl_->prepareCorePipelines(kCoreDiffusion) && backend.prepareDiffusionRegions();
      break;
    case 5:
      ok = preview ? backend.prepareOperationPipelines(0u, part)
        : impl_->prepareCorePipelines(kCoreDir);
      break;
    case 6:
      ok = preview ? backend.prepareOperationPipelines(1u, part)
        : impl_->prepareCorePipelines(kCoreScannerPost);
      break;
    case 7:
      ok = impl_->prepareCorePipelines(kCoreGrain);
      break;
    case 8:
      ok = preview ? impl_->prepareCorePipelines(kCoreDir | kCoreScannerPost)
        : backend.physicalDeviceProperties.vendorID != 0x13b5u || backend.prepareDirectPrint();
      break;
    case 9: ok = !preview || backend.prepareFourStageLut(true, true); break;
    case 10: ok = !preview || backend.preparePreviewImages(true, false, false); break;
    case 11: ok = !preview || backend.preparePreviewImages(true, false, true); break;
    case 12: ok = !preview || backend.preparePreviewImages(true, true, true); break;
    case 13:
      ok = !preview || backend.preparePreviewColor();
      for (uint32_t operation = kVideoEffectBoostCurve; ok && preview && operation <= kVideoEffectPrintChain; ++operation) {
        ok = backend.prepareVideoEffects(operation);
      }
      break;
    case 14:
      ok = !preview || (backend.preparePreviewColor(true) &&
        backend.prepareVideoEffects(kVideoEffectComposeComposed) && backend.prepareVideoEffects(kVideoEffectComposeStages));
      break;
    case 15: ok = !preview || backend.prepareComposedLut(); break;
    case 16: ok = !preview || backend.prepareComposedPrint(true); break;
    case 17: ok = !preview || backend.prepareOperationPipelines(3u, part); break;
    case 18:
      ok = !preview || impl_->prepareCorePipelines(kCoreFilmExposure | kCoreCurveDevelop | kCoreHalation | kCoreDiffusion);
      break;
    case 19: ok = !preview || backend.preparePreviewColorLut(true); break;
    case 20: ok = !preview || backend.prepareFourStageLut(false); break;
    case 21: ok = !preview || backend.prepareFourStageLut(false, true); break;
    case 22:
      ok = !preview || (backend.preparePreviewImages(false, false, false) &&
        backend.preparePreviewImages(false, false, true) && backend.prepareComposedPrint(false));
      break;
    case 23: ok = !preview || backend.prepareOperationPipelines(2u, part); break;
    default:
      lastRenderError_ = "Unknown startup pipeline step";
      return false;
  }
#if !defined(NDEBUG)
  if (ok && step == 2u) {
    ok = backend.preparePrintProbe() && backend.preparePrintAccessProbe() &&
      backend.prepareCompactPrint(true, true);
  }
#endif
  lastRenderError_ = ok ? std::string() : backend.lastError;
  return ok;
}

bool VulkanRenderer::recordAutoExposure(
  VkCommandBuffer commands, uint32_t slotIndex, VkBuffer sampledSource,
  uint32_t width, uint32_t height, AutoExposureMethod method, VkBuffer result, float tapX, float tapY
) {
  if (!hasExternalContext_ || !impl_ || !impl_->available ||
      slotIndex >= impl_->meterSlots.size() || !commands || !sampledSource || !result || !width || !height) return false;
  auto &state = *impl_;
  std::lock_guard<std::recursive_mutex> lock(state.renderMutex);
  if (!state.prepareCorePipelines(kCoreAutoExposure)) return false;
  if (state.meterDecodeLuts.buffer == VK_NULL_HANDLE &&
      !state.uploadScratchBuffer(state.meterDecodeLuts, colorDecodeLuts(),
        VkDeviceSize{kSpektraColorSpaceCount} * kSpektraColorTransferLutSize * sizeof(float), "meter decode LUTs")) return false;
  if (state.meterTransferKinds.buffer == VK_NULL_HANDLE &&
      !state.uploadScratchBuffer(state.meterTransferKinds, colorTransferKinds(),
        VkDeviceSize{kSpektraColorSpaceCount} * sizeof(uint32_t), "meter transfer kinds")) return false;
  if (state.meterXyz.buffer == VK_NULL_HANDLE &&
      !state.uploadScratchBuffer(state.meterXyz, inputMeterXyzMatrices(),
        VkDeviceSize{kSpektraColorSpaceCount} * 9u * sizeof(float), "meter XYZ matrices")) return false;

  auto &slot = state.meterSlots[slotIndex];
  if (!state.ensurePrivateScratchBuffer(slot.luminance, SPEKTRA_AE_SCRATCH_BYTES, "meter luminance")) return false;
  const auto check = [&](VkResult status, const char *name) {
    if (status == VK_SUCCESS) return true;
    state.lastError = vkError(name, status);
    return false;
  };
  if (slot.pool == VK_NULL_HANDLE) {
    const uint32_t chunks = state.backend->storageChunkCount();
    const VkDescriptorPoolSize sizes[] = {
      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kCoreStorageBufferBindingCount + 8u * (chunks - 1u)},
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SPEKTRA_PREVIEW_IMAGE_BINDING_COUNT},
      {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2u}
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = sizes;
    if (!check(vkCreateDescriptorPool(state.device, &poolInfo, nullptr, &slot.pool), "Create meter pool")) return false;
    VkDescriptorSetAllocateInfo allocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocation.descriptorPool = slot.pool;
    allocation.descriptorSetCount = 1;
    allocation.pSetLayouts = &state.coreDescriptorSetLayout;
    if (!check(vkAllocateDescriptorSets(state.device, &allocation, &slot.meterSet), "Allocate meter descriptors")) {
      vkDestroyDescriptorPool(state.device, slot.pool, nullptr);
      slot.pool = VK_NULL_HANDLE;
      return false;
    }
  }
  const VkDescriptorBufferInfo buffers[] = {
    {sampledSource, 0, 256u * 256u * 4u * sizeof(float)},
    {state.meterDecodeLuts.buffer, 0, state.meterDecodeLuts.capacity},
    {state.meterTransferKinds.buffer, 0, state.meterTransferKinds.capacity},
    {state.meterXyz.buffer, 0, state.meterXyz.capacity},
    {slot.luminance.buffer, 0, slot.luminance.capacity},
    {result, 0, sizeof(float)}
  };
  const uint32_t bindings[] = {0, 5, 6, 8, 29, 30};
  VkWriteDescriptorSet writes[6]{};
  for (uint32_t i = 0; i < 6; ++i) {
    auto &write = writes[i];
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.descriptorCount = 1;
    write.dstSet = slot.meterSet;
    write.dstBinding = bindings[i];
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = &buffers[i];
  }
  if (!state.updateStorageDescriptors(6, writes, false)) return false;

  VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
  VulkanCorePushConstants params{};
  params.width = params.fullWidth = width;
  params.height = params.fullHeight = height;
  params.inputColorSpace = static_cast<int32_t>(ColorSpace::LinearRec709);
  params.colorSpaceCount = kSpektraColorSpaceCount;
  params.transferLutSize = kSpektraColorTransferLutSize;
  params.colorDecodeMin = colorDecodeLutMin();
  params.colorDecodeMax = colorDecodeLutMax();
  params._pad0 = static_cast<uint32_t>(method);
  params._pad1 = 1u;
  if (std::isfinite(tapX) && std::isfinite(tapY) && tapX >= 0.0f && tapY >= 0.0f) {
    params._pad1 = 2u;
    params.activeOriginX = static_cast<uint32_t>(std::clamp(tapX, 0.0f, 1.0f) * (width - 1u));
    params.activeOriginY = static_cast<uint32_t>(std::clamp(tapY, 0.0f, 1.0f) * (height - 1u));
  }
  vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, state.autoExposurePipeline);
  vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, state.corePipelineLayout,
    0, 1, &slot.meterSet, 0, nullptr);
  int32_t sampleWidth, sampleHeight;
  autoExposurePreviewShape(static_cast<int32_t>(width), static_cast<int32_t>(height), sampleWidth, sampleHeight);
  const uint32_t groups = (sampleWidth * sampleHeight + SPEKTRA_AE_GROUP_SAMPLES - 1u) / SPEKTRA_AE_GROUP_SAMPLES;
  const uint32_t passes = method == AutoExposureMethod::Median ? SPEKTRA_AE_MEDIAN_PASSES : SPEKTRA_AE_WEIGHTED_PASSES;
  barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  for (uint32_t pass = 0; pass < passes; ++pass) {
    params._pad2 = pass;
    vkCmdPushConstants(commands, state.corePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), &params);
    vkCmdDispatch(commands, (pass & 1u) == 0u ? groups : 1u,
      (pass & 1u) == 0u && params._pad1 == 2u ? 2u : 1u, 1u);
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
      0, 1, &barrier, 0, nullptr, 0, nullptr);
  }
  barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
    0, 1, &barrier, 0, nullptr, 0, nullptr);
  return true;
}

bool VulkanRenderer::renderVulkanImages(
  VkImage sourceImage,
  VkImage destinationImage,
  uint32_t width,
  uint32_t height,
  const RenderParams &requestedParams,
  double time,
  bool optimizePreview,
  VkBuffer exposureRange,
  ExternalImageViews views,
  bool videoEffects
) {
  RenderParams params = resolveMonochromeParams(requestedParams);
  videoEffects = videoEffects && optimizePreview;
#if defined(__ANDROID__) && !defined(NDEBUG)
  char videoProperty[PROP_VALUE_MAX]{};
  __system_property_get("debug.unspektra.video_effects", videoProperty);
  // "legacy" keeps the photo preview effects for comparison, "0" renders video without them.
  if (videoEffects && std::strcmp(videoProperty, "legacy") == 0) videoEffects = false;
  const bool stripVideoEffects = videoEffects && videoProperty[0] == '0';
#else
  constexpr bool stripVideoEffects = false;
#endif
  const RenderParams videoEffectParams = params;
  if (videoEffects) {
    params.halationEnabled = false;
    params.cameraDiffusionEnabled = false;
    params.printDiffusionEnabled = false;
    params.glarePercent = 0.0f;
  }
  if (!hasExternalContext_ || !impl_ || !impl_->available) {
    lastRenderError_ =
      "spektrafilm Vulkan image rendering requires the shared application Vulkan device.";
    return false;
  }

  std::lock_guard<std::recursive_mutex> lock(impl_->renderMutex);
  impl_->previewOptimizations = optimizePreview;
  impl_->previewExposureRange = optimizePreview ? exposureRange : VK_NULL_HANDLE;
  impl_->suppliedImageViews = views;
  impl_->videoEffectsRequested = videoEffects && !stripVideoEffects;
  impl_->videoEffectParams = videoEffectParams;
  const bool ok = impl_->renderExternalImages(
    sourceImage,
    destinationImage,
    width,
    height,
    params,
    time
  );
  impl_->videoEffectsRequested = false;
  impl_->previewOptimizations = false;
  impl_->previewExposureRange = VK_NULL_HANDLE;
  impl_->suppliedImageViews = {};
  lastRenderDiagnostics_ = impl_->diagnostics;
  lastRenderError_ = ok ? std::string() : impl_->lastError;
  if (!ok && isDeviceLostError(lastRenderError_)) {
    impl_->markBackendLost();
    lastRenderError_ +=
      " The main application Vulkan device must be recreated before spektrafilm can resume.";
  }
  return ok;
}

bool VulkanRenderer::isAvailable() const {
  return impl_ && impl_->available;
}

const VulkanRenderDiagnostics &VulkanRenderer::lastDiagnostics() const {
  if (!lastRenderError_.empty()) {
    return lastRenderDiagnostics_;
  }
  return impl_ ? impl_->diagnostics : lastRenderDiagnostics_;
}

const std::string &VulkanRenderer::lastError() const {
  static const std::string empty;
  if (!lastRenderError_.empty()) {
    return lastRenderError_;
  }
  return impl_ ? impl_->lastError : empty;
}

void VulkanRenderer::releaseTransientResources() {
  if (impl_) {
    impl_->releaseTransientResources();
  }
}

void VulkanRenderer::resetPreviewScheduling() {
  if (!impl_) return;
  std::lock_guard<std::recursive_mutex> lock(impl_->renderMutex);
  impl_->coreFrame.previewScheduleWidth = impl_->coreFrame.previewScheduleHeight = 0;
  impl_->coreFrame.previewFastFrames = 0;
  impl_->coreFrame.previewDirectSubmission = false;
  impl_->coreFrame.timingAdaptive = false;
}

bool VulkanRenderer::render(
  const ImageView &source,
  const MutableImageView &destination,
  const RenderWindow &window,
  const RenderParams &requestedParams,
  double time
) {
  const RenderParams params = resolveMonochromeParams(requestedParams);
  if (!impl_ || !impl_->available) {
    if (impl_) {
      std::lock_guard<std::recursive_mutex> lock(impl_->renderMutex);
      if (impl_->lastError.empty()) {
        impl_->lastError = "Vulkan is not available on this system.";
      }
      lastRenderDiagnostics_ = impl_->diagnostics;
      lastRenderError_ = impl_->lastError;
    }
    return false;
  }

  bool ok = false;
  if (envFlagEnabled("SPEKTRAFILM_VULKAN_COPY_PASS")) {
    ok = impl_->renderCopyValidation(source, destination, window, time);
  } else {
    const GpuRenderTilingMode tileMode = resolveVulkanTileMode(params);
    if (tileMode == GpuRenderTilingMode::Tiled) {
      ok = impl_->renderTiledBootstrap(source, destination, window, params, time);
    } else {
      ok = impl_->renderCoreBootstrap(source, destination, window, params, time);
    }
  }

  if (ok) {
    if (impl_) {
      lastRenderDiagnostics_ = impl_->diagnostics;
    }
    lastRenderError_.clear();
    return true;
  }

  if (impl_) {
    lastRenderDiagnostics_ = impl_->diagnostics;
  }
  lastRenderError_ = impl_ ? impl_->lastError : std::string();
  if (isDeviceLostError(lastRenderError_)) {
    if (impl_) {
      impl_->markBackendLost();
    }
    if (hasExternalContext_) {
      lastRenderError_ +=
        " The main application Vulkan device must be recreated before spektrafilm can resume.";
      return false;
    }
    lastRenderError_ += " Recreated the shared Vulkan backend for the next render.";
    impl_.reset();
    impl_ = std::make_unique<Impl>(
      hasExternalContext_ ? &externalContext_ : nullptr
    );
    if (!impl_ || !impl_->available) {
      lastRenderError_ += " Vulkan device recreation failed.";
      if (impl_ && !impl_->lastError.empty()) {
        lastRenderError_ += " ";
        lastRenderError_ += impl_->lastError;
      }
    }
  }
  return false;
}

std::unique_ptr<Renderer> createNativeRenderer() {
  return std::make_unique<VulkanRenderer>();
}

} // namespace spektrafilm
