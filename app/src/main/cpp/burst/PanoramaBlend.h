#pragma once

#include "PanoramaGpu.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <stdexcept>

namespace panorama {
struct BlendLevel {
    int cols, rows;
    size_t offset;
};

class BlendSampleError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

inline std::vector<Float4> referenceBlend(const Float4* samples, const std::vector<BlendLevel>& levels) {
    if (levels.empty()) throw std::runtime_error("Invalid panorama blend");
    const auto& last = levels.back();
    const size_t entries = last.offset + size_t(last.cols) * last.rows * 2;
    const size_t baseEntries = size_t(levels[0].cols) * levels[0].rows * 2;
    for (size_t i = 0; i < baseEntries; i += 2) {
        const auto& difference = samples[i];
        const float mask = samples[i + 1][0];
        bool valid = std::isfinite(difference[3]) && difference[3] >= 0 && difference[3] <= 1 &&
            std::isfinite(mask) && mask >= 0 && mask <= 1;
        for (int c = 0; c < 3; ++c)
            if (!std::isfinite(difference[c]) || std::abs(difference[c]) > 255 * difference[3] + .01f)
                valid = false;
        if (!valid) {
            char message[256];
            std::snprintf(message, sizeof(message), "Invalid panorama blend samples cell=%zu difference=[%.9g,%.9g,%.9g] overlap=%.9g mask=%.9g",
                i / 2, difference[0], difference[1], difference[2], difference[3], mask);
            throw BlendSampleError(message);
        }
    }
    std::vector<Float4> pyramid(entries), reconstruction(entries);
    for (size_t i = 0; i < baseEntries; i += 2) {
        pyramid[i] = samples[i];
        pyramid[i + 1][0] = samples[i + 1][0];
    }
    auto at = [](const std::vector<Float4>& data, const BlendLevel& level, int x, int y, int image) -> const Float4& {
        x = std::clamp(x, 0, level.cols - 1); y = std::clamp(y, 0, level.rows - 1);
        return data[level.offset + (size_t(y) * level.cols + x) * 2 + image];
    };
    constexpr float kernel[] = {1, 4, 6, 4, 1};
    for (size_t level = 1; level < levels.size(); ++level) {
        const auto& shape = levels[level];
        for (int y = 0; y < shape.rows; ++y) for (int x = 0; x < shape.cols; ++x) {
            const size_t offset = shape.offset + (size_t(y) * shape.cols + x) * 2;
            for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
                const float weight = kernel[dx + 2] * kernel[dy + 2] / 256;
                for (int image = 0; image < 2; ++image) {
                    const auto& value = at(pyramid, levels[level - 1], x * 2 + dx, y * 2 + dy, image);
                    for (int c = 0; c < 4; ++c) pyramid[offset + image][c] += value[c] * weight;
                }
            }
        }
    }
    auto expand = [&](const BlendLevel& shape, int x, int y, int c, bool reconstructed) {
        const float px = std::min(x * .5f, float(shape.cols - 1)), py = std::min(y * .5f, float(shape.rows - 1));
        const int ix = int(px), iy = int(py);
        auto value = [&](int sx, int sy) {
            const auto& sample = at(reconstructed ? reconstruction : pyramid, shape, sx, sy, 0);
            return reconstructed ? sample[c] : sample[c] / std::max(1e-6f, sample[3]);
        };
        const float fx = px - ix, fy = py - iy;
        return (value(ix, iy) * (1 - fx) + value(ix + 1, iy) * fx) * (1 - fy) +
            (value(ix, iy + 1) * (1 - fx) + value(ix + 1, iy + 1) * fx) * fy;
    };
    for (int level = int(levels.size()) - 1; level >= 0; --level) {
        const auto& shape = levels[level];
        for (int y = 0; y < shape.rows; ++y) for (int x = 0; x < shape.cols; ++x) {
            const size_t offset = shape.offset + (size_t(y) * shape.cols + x) * 2;
            const float mask = pyramid[offset + 1][0];
            for (int c = 0; c < 3; ++c) {
                const float difference = pyramid[offset][c] / std::max(1e-6f, pyramid[offset][3]);
                if (level == int(levels.size()) - 1) {
                    reconstruction[offset][c] = level == 0 ? 0 : mask * difference;
                } else {
                    const float low = expand(levels[level + 1], x, y, c, false);
                    const float merged = expand(levels[level + 1], x, y, c, true);
                    reconstruction[offset][c] = level == 0 ? merged : merged + mask * (difference - low);
                    if (level == 0) reconstruction[offset + 1][c] = merged - low;
                }
            }
        }
    }
    reconstruction.resize(baseEntries);
    return reconstruction;
}

inline bool blendMatchesReference(const Float4* actual, const std::vector<Float4>& expected) {
    for (size_t i = 0; i < expected.size(); ++i) for (int c = 0; c < 3; ++c)
        if (!std::isfinite(actual[i][c]) || std::abs(actual[i][c] - expected[i][c]) > .05f) return false;
    return true;
}
}
