#pragma once

#include <stdint.h>

namespace export_memory {
constexpr uint64_t gib = 1024ull * 1024ull * 1024ull;
constexpr uint64_t smallImagePixels = 4ull * 1024ull * 1024ull;

constexpr bool needsTiles(uint64_t pixels, uint64_t total, uint64_t available, uint64_t additional) {
    if (total == 0 || available == 0) return pixels > smallImagePixels;
    if (total <= 8u * gib && pixels > smallImagePixels) return true;
    const uint64_t reserve = total / 8u > gib ? total / 8u : gib;
    const uint64_t usable = available > reserve ? available - reserve : 0;
    return additional > usable;
}

constexpr bool needsSeparateEffects(uint32_t vendor, uint64_t pixels, uint64_t total,
                                    uint64_t available, uint64_t workingPixels) {
    // Large Mali tiles can exhaust shared RAM even when each buffer fits the GPU limits.
    return vendor == 0x13b5u && pixels > smallImagePixels &&
        needsTiles(pixels, total, available, workingPixels * 248u);
}
}
