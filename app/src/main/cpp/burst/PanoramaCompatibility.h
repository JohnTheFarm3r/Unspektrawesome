#pragma once

#include <cstdint>

namespace panorama {
constexpr bool useCompatibleBlendSampling(uint32_t vendor) {
    // Keep Mali and Immortalis sampling separate from blur and reconstruction.
    return vendor == 0x13b5u;
}
}
