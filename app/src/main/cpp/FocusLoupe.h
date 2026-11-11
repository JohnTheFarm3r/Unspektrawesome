#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

// Focus loupe: copies a small area of the full-resolution RAW plane so the viewfinder can show it
// magnified without processing the whole frame at native resolution.
namespace focus_loupe {

// Rows and columns of context copied around the visible area for hot pixel checks and demosaicing.
constexpr int kHalo = 8;
// Largest visible side in RAW pixels.
constexpr int kMaxVisible = 1024;
constexpr int kMinVisible = 16;

// Fine edge energy that sensor noise alone produces after each preview path's demosaic. Matches
// FocusLoupeResult.fineNoise, which the focus meter uses.
inline float fineNoise(bool lightweight) { return lightweight ? 1.65f : 3.4f; }

struct Request {
    bool enabled = false;
    float centerX = 0.0f;      // RAW buffer pixel, unrotated sensor orientation
    float centerY = 0.0f;
    int width = 0;             // visible RAW pixels, unrotated sensor orientation
    int height = 0;
    float rect[4]{};           // x, y, width, height normalized to the viewfinder surface
    float cornerRadius = 0.0f; // fraction of the rectangle width
    float peaking = 0.0f;      // 0 off, otherwise 1 to 3
    float opacity = 1.0f;
    float noiseScale = 0.0f;   // sensor noise profile, normalized RAW units
    float noiseOffset = 0.0f;
    int64_t generation = 0;
};

struct Region {
    int originX = 0;   // RAW pixel of the first copied sample
    int originY = 0;
    int width = 0;     // copied samples per row, including the halo
    int height = 0;
    int stride = 0;    // samples per row in the output, even so rows start on a 32-bit word
    int visibleWidth = 0;
    int visibleHeight = 0;
    int visibleX = 0;  // RAW pixel of the first visible sample
    int visibleY = 0;
    int halo = 0;      // copied context on each side, a whole number of colour filter periods
};

struct Source {
    const uint8_t* plane = nullptr;
    size_t capacity = 0;
    int rowStride = 0;
    int format = 0;      // 0 16-bit, 1 RAW10, 2 RAW12
    int width = 0;
    int height = 0;
    int bounds[4]{};     // usable x, y, width, height
    int block = 1;       // 1 for Bayer, 2 or more for Quad Bayer blocks
    int phaseX = 0;
    int phaseY = 0;
};

inline int floorMod(int value, int period) {
    const int remainder = value % period;
    return remainder < 0 ? remainder + period : remainder;
}

// Keeps the colour filter position of [value] while clamping it to [low, high].
inline int clampToPeriod(int value, int low, int high, int period) {
    if (value >= low && value <= high) return value;
    const int phase = floorMod(value, period);
    if (value < low) {
        int candidate = low + floorMod(phase - floorMod(low, period), period);
        return candidate <= high ? candidate : low;
    }
    int candidate = high - floorMod(floorMod(high, period) - phase, period);
    return candidate >= low ? candidate : high;
}

// Bytes a row needs to hold the sample at column [x].
inline size_t sampleEnd(int format, int x) {
    if (format == 1) return static_cast<size_t>(x / 4) * 5u + 5u;
    if (format == 2) return static_cast<size_t>(x / 2) * 3u + 3u;
    return static_cast<size_t>(x) * 2u + 2u;
}

inline uint16_t sampleAt(const uint8_t* row, int format, int x) {
    if (format == 1) {
        const uint8_t* group = row + static_cast<size_t>(x >> 2) * 5u;
        const int lane = x & 3;
        return static_cast<uint16_t>((group[lane] << 2) | ((group[4] >> (lane * 2)) & 3));
    }
    if (format == 2) {
        const uint8_t* group = row + static_cast<size_t>(x >> 1) * 3u;
        const int lane = x & 1;
        return static_cast<uint16_t>((group[lane] << 4) | ((group[2] >> (lane * 4)) & 15));
    }
    return static_cast<uint16_t>(row[static_cast<size_t>(x) * 2u] | (row[static_cast<size_t>(x) * 2u + 1u] << 8));
}

/**
 * Places the visible area around the request centre inside the usable sensor bounds, aligned so the
 * colour filter pattern of the copy matches the full frame. Returns false when the source is unusable.
 */
inline bool plan(const Source& source, const Request& request, Region& region) {
    if (source.width <= 0 || source.height <= 0 || source.block < 1 || source.block > 4) return false;
    const int boundsX = std::clamp(source.bounds[0], 0, source.width - 1);
    const int boundsY = std::clamp(source.bounds[1], 0, source.height - 1);
    const int boundsWidth = std::min(source.bounds[2], source.width - boundsX);
    const int boundsHeight = std::min(source.bounds[3], source.height - boundsY);
    const int period = source.block * 2;
    if (boundsWidth < period * 4 || boundsHeight < period * 4) return false;
    auto size = [&](int requested, int available) {
        int value = std::clamp(requested, kMinVisible, std::min(kMaxVisible, available));
        return std::max(period, value / period * period);
    };
    region.visibleWidth = size(request.width, boundsWidth);
    region.visibleHeight = size(request.height, boundsHeight);
    auto start = [&](float center, int visible, int low, int extent, int phase) {
        int value = static_cast<int>(center - visible * 0.5f + 0.5f);
        value = std::clamp(value, low, low + extent - visible);
        // Start on a whole colour filter period. Blocks begin where x + phase is a multiple of it.
        value -= floorMod(value + phase, period);
        if (value < low) value += period;
        return value;
    };
    region.visibleX = start(request.centerX, region.visibleWidth, boundsX, boundsWidth, source.phaseX);
    region.visibleY = start(request.centerY, region.visibleHeight, boundsY, boundsHeight, source.phaseY);
    region.halo = (kHalo + period - 1) / period * period;
    region.originX = region.visibleX - region.halo;
    region.originY = region.visibleY - region.halo;
    region.width = region.visibleWidth + region.halo * 2;
    region.height = region.visibleHeight + region.halo * 2;
    region.stride = (region.width + 1) & ~1;
    return true;
}

/**
 * Copies [region] as 16-bit samples. Samples outside the usable bounds repeat the nearest sample
 * of the same colour filter position. Returns false when the plane is too small for the rows used.
 */
inline bool extract(const Source& source, const Region& region, uint16_t* output, size_t outputSamples,
                    std::vector<int>& columns) {
    if (!source.plane || source.rowStride <= 0 || region.width <= 0 || region.height <= 0) return false;
    if (static_cast<size_t>(region.stride) * static_cast<size_t>(region.height) > outputSamples) return false;
    const int period = source.block * 2;
    const int lowX = std::max(0, source.bounds[0]);
    const int lowY = std::max(0, source.bounds[1]);
    const int highX = std::min(source.width, source.bounds[0] + source.bounds[2]) - 1;
    const int highY = std::min(source.height, source.bounds[1] + source.bounds[3]) - 1;
    if (highX < lowX || highY < lowY) return false;
    columns.resize(static_cast<size_t>(region.width));
    int lastColumn = 0;
    for (int x = 0; x < region.width; ++x) {
        const int column = clampToPeriod(region.originX + x, lowX, highX, period);
        columns[static_cast<size_t>(x)] = column;
        lastColumn = std::max(lastColumn, column);
    }
    const size_t rowBytes = sampleEnd(source.format, lastColumn);
    for (int y = 0; y < region.height; ++y) {
        const int row = clampToPeriod(region.originY + y, lowY, highY, period);
        const size_t offset = static_cast<size_t>(row) * static_cast<size_t>(source.rowStride);
        if (offset + rowBytes > source.capacity) return false;
        const uint8_t* line = source.plane + offset;
        uint16_t* target = output + static_cast<size_t>(y) * static_cast<size_t>(region.stride);
        for (int x = 0; x < region.width; ++x) target[x] = sampleAt(line, source.format, columns[static_cast<size_t>(x)]);
        if (region.stride > region.width) target[region.width] = target[region.width - 1];
    }
    return true;
}

/**
 * Result values passed to Kotlin: score generation, active, edge energy, mean peaking edge, visible
 * width, height, centre x, centre y, unused, edge energy of the flattest areas, unused, reading
 * sequence, lightweight preview, block size and two unused values.
 */
constexpr int kResultSize = 16;

struct Reading {
    float fine = 0.0f;      // gradient energy in units of the kernel's white-noise response
    float flatFine = 0.0f;  // gradient energy of the flattest tenth of the loupe
};

/**
 * Combines the per-workgroup records written by the score pass: edge energy, two unused values and
 * meter samples. Returns false when no group was measured.
 */
inline bool summarize(const float* groups, int groupsX, int groupsY, std::vector<float>& flatness, Reading& reading) {
    if (!groups || groupsX <= 0 || groupsY <= 0) return false;
    double fine = 0.0, samples = 0.0;
    flatness.clear();
    for (int y = 0; y < groupsY; ++y) {
        for (int x = 0; x < groupsX; ++x) {
            const float* group = groups + 4 * (static_cast<size_t>(y) * groupsX + x);
            if (!std::isfinite(group[0]) || !std::isfinite(group[3]) || group[3] <= 0.0f) continue;
            fine += static_cast<double>(group[0]) * group[3];
            samples += group[3];
            // Only well-covered groups describe the flattest areas.
            if (group[3] >= 16.0f) flatness.push_back(group[0]);
        }
    }
    if (samples <= 0.0) return false;
    reading.fine = static_cast<float>(fine / samples);
    if (flatness.empty()) {
        reading.flatFine = reading.fine;
    } else {
        auto tenth = flatness.begin() + static_cast<std::ptrdiff_t>(flatness.size() / 10);
        std::nth_element(flatness.begin(), tenth, flatness.end());
        reading.flatFine = *tenth;
    }
    return true;
}

}  // namespace focus_loupe
