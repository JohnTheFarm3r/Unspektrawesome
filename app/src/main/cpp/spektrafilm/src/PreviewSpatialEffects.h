#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace spektrafilm::preview_effects {

inline uint32_t diffusionScale(float sigma) {
  uint32_t scale = 1u;
  while (scale < 64u && sigma >= 2.0f * static_cast<float>(scale)) scale *= 2u;
  return scale;
}

inline uint32_t halationScale(uint32_t width, uint32_t height) {
  uint32_t scale = 1u;
  while ((std::max(width, height) + scale - 1u) / scale > 320u) scale *= 2u;
  return scale;
}

template<class Component>
void simplifyDiffusion(std::vector<Component> &components) {
  std::sort(components.begin(), components.end(), [](const Component &a, const Component &b) {
    return a.sigmaPx < b.sigmaPx;
  });
  auto weight = [](const Component &c) { return c.weightR + c.weightG + c.weightB; };
  while (components.size() > 8u) {
    size_t selected = 0u;
    float minimumCost = std::numeric_limits<float>::max();
    for (size_t i = 0; i + 1u < components.size(); ++i) {
      const auto &a = components[i];
      const auto &b = components[i + 1u];
      const float wa = weight(a);
      const float wb = weight(b);
      const float distance = std::log(std::max(b.sigmaPx, 0.25f) / std::max(a.sigmaPx, 0.25f));
      const float cost = wa * wb / std::max(wa + wb, 1.0e-10f) * distance * distance;
      if (cost < minimumCost) {
        minimumCost = cost;
        selected = i;
      }
    }
    auto &a = components[selected];
    const auto b = components[selected + 1u];
    const float wa = weight(a);
    const float wb = weight(b);
    a.sigmaPx = std::sqrt((wa * a.sigmaPx * a.sigmaPx + wb * b.sigmaPx * b.sigmaPx) /
                         std::max(wa + wb, 1.0e-10f));
    a.weightR += b.weightR;
    a.weightG += b.weightG;
    a.weightB += b.weightB;
    components.erase(components.begin() + selected + 1u);
  }
}

} // namespace spektrafilm::preview_effects
