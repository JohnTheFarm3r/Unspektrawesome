#pragma once

#include <algorithm>
#include <array>
#include <cstdint>

namespace multiframe {

constexpr int kMergeBuffers = 10;
// Buffers holding the tile spectra of a band.
constexpr int kSpectrumBuffers[] = {5, 6};

/** Band layout of one merge and the bytes each GPU buffer needs for it. */
struct BandPlan {
    int interior = 0;  // rows kept from each band
    int overlap = 0;   // extra rows above and below the interior used for alignment only
    int bandRows = 0;  // rows processed per band
    std::array<uint64_t, kMergeBuffers> bytes{};
    uint64_t total = 0;
    bool valid() const { return interior > 0; }
};

/** What the GPU allows for the merge buffers. */
struct MergeMemoryLimits {
    uint64_t buffer = 0;    // largest single buffer
    uint64_t spectrum = 0;  // largest tile spectrum buffer
    uint64_t total = 0;     // all buffers together
};

/**
 * Picks the largest band that fits [limits]. [sizes] returns the buffer bytes for a band of
 * bandRows rows keeping interiorRows rows, or all zeros when the band is too small to align.
 * Whole frames are preferred; otherwise bands shrink in steps of [unit] rows.
 */
template <typename Sizes>
BandPlan planBands(int height, int unit, int overlapRows, const MergeMemoryLimits& limits, Sizes sizes) {
    if (height <= 0 || unit <= 0) return {};
    const int overlap = ((overlapRows + unit - 1) / unit) * unit;
    auto fits = [&](BandPlan& plan) {
        plan.bytes = sizes(plan.bandRows, std::min(plan.interior, height));
        plan.total = 0;
        for (int i = 0; i < kMergeBuffers; ++i) {
            if (plan.bytes[size_t(i)] == 0 || plan.bytes[size_t(i)] > limits.buffer) return false;
            plan.total += plan.bytes[size_t(i)];
        }
        for (int i : kSpectrumBuffers) if (plan.bytes[size_t(i)] > limits.spectrum) return false;
        return plan.total <= limits.total;
    };
    BandPlan whole;
    whole.interior = height;
    whole.bandRows = height;
    if (fits(whole)) return whole;
    for (int interior = ((height - 1) / unit) * unit; interior >= unit; interior -= unit) {
        BandPlan plan;
        plan.interior = interior;
        plan.overlap = overlap;
        plan.bandRows = std::min(height, interior + 2 * overlap);
        if (fits(plan)) return plan;
    }
    return {};
}

}  // namespace multiframe
