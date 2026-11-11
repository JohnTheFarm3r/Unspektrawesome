#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

// Video effects approximate each blur kernel with weighted levels of one shared pyramid.
// A Gaussian mixture is mapped onto the levels by matching the variance of every term.
namespace spektrafilm::video_effects {

constexpr uint32_t kMaxLevels = 10u;

struct Term {
  double variance = 0.0;
  double weight = 0.0;
};

using Kernel = std::array<std::vector<Term>, 3>;

inline Kernel identityKernel() {
  Kernel kernel;
  for (auto &channel : kernel) channel.push_back({0.0, 1.0});
  return kernel;
}

// Convolving two Gaussians adds their variances.
inline Kernel convolve(const Kernel &a, const Kernel &b) {
  Kernel result;
  for (size_t channel = 0; channel < 3u; ++channel) {
    result[channel].reserve(a[channel].size() * b[channel].size());
    for (const Term &x : a[channel]) {
      for (const Term &y : b[channel]) {
        const double weight = x.weight * y.weight;
        if (std::abs(weight) > 1.0e-9) result[channel].push_back({x.variance + y.variance, weight});
      }
    }
  }
  return result;
}

// Response variance of a pyramid level in full-resolution pixels. Level 0 has half resolution;
// each level adds the [1 3 3 1] downsample and the four-tap upsample used to return to full size.
inline double levelVariance(uint32_t level, double baseTexel) {
  const double scale = baseTexel * 0.5;
  return scale * scale * (2.0 + 10.0 * (std::pow(4.0, static_cast<double>(level)) - 1.0) / 3.0);
}

inline uint32_t availableLevels(uint32_t baseWidth, uint32_t baseHeight) {
  uint32_t count = 1u;
  while (count < kMaxLevels && std::min(baseWidth >> count, baseHeight >> count) >= 2u) ++count;
  return count;
}

struct LevelWeights {
  std::array<float, 3> sharp{1.0f, 1.0f, 1.0f};
  std::array<std::array<float, 3>, kMaxLevels> levels{};
  uint32_t count = 1u;

  bool spreads() const {
    for (uint32_t level = 0; level < count; ++level) {
      for (float weight : levels[level]) if (weight > 1.0e-6f) return true;
    }
    return false;
  }
};

inline LevelWeights fitLevels(const Kernel &kernel, double baseTexel, uint32_t available) {
  double widest = 0.0;
  for (const auto &channel : kernel) {
    for (const Term &term : channel) {
      if (term.variance > 0.0 && std::abs(term.weight) > 1.0e-5) widest = std::max(widest, term.variance);
    }
  }
  LevelWeights result;
  available = std::clamp(available, 1u, kMaxLevels);
  while (result.count < available && levelVariance(result.count - 1u, baseTexel) < widest) ++result.count;
  std::array<double, kMaxLevels> variances{};
  for (uint32_t level = 0; level < result.count; ++level) variances[level] = levelVariance(level, baseTexel);
  for (size_t channel = 0; channel < 3u; ++channel) {
    double sharp = 0.0;
    std::array<double, kMaxLevels> weights{};
    for (const Term &term : kernel[channel]) {
      if (term.variance <= 0.0) {
        sharp += term.weight;
        continue;
      }
      if (term.variance >= variances[result.count - 1u]) {
        weights[result.count - 1u] += term.weight;
        continue;
      }
      double lower = 0.0;
      for (uint32_t level = 0; level < result.count; ++level) {
        if (term.variance < variances[level]) {
          const double t = (term.variance - lower) / (variances[level] - lower);
          if (level == 0u) sharp += term.weight * (1.0 - t);
          else weights[level - 1u] += term.weight * (1.0 - t);
          weights[level] += term.weight * t;
          break;
        }
        lower = variances[level];
      }
    }
    result.sharp[channel] = static_cast<float>(sharp);
    for (uint32_t level = 0; level < result.count; ++level) {
      result.levels[level][channel] = static_cast<float>(weights[level]);
    }
  }
  return result;
}

}  // namespace spektrafilm::video_effects
