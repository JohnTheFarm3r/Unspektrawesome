#pragma once

#include <algorithm>
#include <array>
#include <cstdint>

namespace spektrafilm {

struct DiffusionRegion {
  uint32_t x = 0;
  uint32_t y = 0;
  uint32_t width = 0;
  uint32_t height = 0;

  constexpr uint64_t pixels() const { return uint64_t{width} * height; }
};

struct DiffusionRegions {
  DiffusionRegion output;
  DiffusionRegion temporary;
  std::array<DiffusionRegion, 3> reduced;
};

static_assert(sizeof(DiffusionRegions) == 80u);

constexpr DiffusionRegion expandDiffusionRegion(
  DiffusionRegion region, uint32_t radius, uint32_t width, uint32_t height
) {
  const uint32_t x = region.x > radius ? region.x - radius : 0u;
  const uint32_t y = region.y > radius ? region.y - radius : 0u;
  const uint32_t endX = static_cast<uint32_t>(std::min(uint64_t{width}, uint64_t{region.x} + region.width + radius));
  const uint32_t endY = static_cast<uint32_t>(std::min(uint64_t{height}, uint64_t{region.y} + region.height + radius));
  return {x, y, endX - x, endY - y};
}

constexpr DiffusionRegions makeDiffusionRegions(
  uint32_t width, uint32_t height, uint32_t fullWidth, uint32_t fullHeight,
  uint32_t tileX, uint32_t tileY, DiffusionRegion output, const std::array<uint32_t, 4> &radii
) {
  DiffusionRegions regions{};
  regions.output = output;
  regions.temporary = expandDiffusionRegion(output, radii[0], width, height);
  for (uint32_t level = 0; level < regions.reduced.size(); ++level) {
    const uint32_t scale = 2u << level;
    const uint32_t x = (tileX + output.x) / scale;
    const uint32_t y = (tileY + output.y) / scale;
    const uint32_t endX = (tileX + output.x + output.width + scale - 1u) / scale;
    const uint32_t endY = (tileY + output.y + output.height + scale - 1u) / scale;
    // Reconstruction reads both neighbors around each fractional sample position.
    regions.reduced[level] = expandDiffusionRegion({x, y, endX - x, endY - y}, radii[level + 1u] + 1u,
      (fullWidth + scale - 1u) / scale, (fullHeight + scale - 1u) / scale);
  }
  return regions;
}

}
