#pragma once

#include <cstdint>
#include <string_view>

namespace raw_preview {

constexpr bool supportsOptimizedPreview(uint32_t vendor, uint32_t device, std::string_view name = {})
{
    // Adreno 740 compiler crashes affect both recording and preview shaders.
    // Keep every driver version on fixed scalar programs with full demosaic and downsampling.
    const bool adreno740 = device == 1124403713u ||
        name == "Adreno (TM) 740" || name == "Adreno 740";
    return vendor != 0x5143u || !adreno740;
}

constexpr bool supportsOptimizedNativeRecordingCompute(uint32_t vendor, uint32_t device, uint32_t,
                                                       std::string_view name = {})
{
    // Adreno 720 native recording compute crashes across driver versions. Raster preview still works.
    const bool adreno720 = device == 1124204544u ||
        name == "Adreno (TM) 720" || name == "Adreno 720";
    return supportsOptimizedPreview(vendor, device, name) &&
        !(vendor == 0x5143u && adreno720);
}

}
