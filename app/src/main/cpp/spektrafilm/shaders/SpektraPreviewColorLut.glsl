#include "SpektraPreviewColorLutConfig.h"
const uint kPreviewColorLutSize = SPEKTRA_PREVIEW_COLOR_LUT_SIZE;
const uint kPreviewColorLutCount = kPreviewColorLutSize * kPreviewColorLutSize * kPreviewColorLutSize;

#if defined(SPEKTRA_PREVIEW_LUT_BUILD)
layout(std430, binding = 4) buffer PreviewColorLut { vec4 previewColorLut[]; };
#else
layout(std430, binding = 4) readonly buffer PreviewColorLut { vec4 previewColorLut[]; };
#endif

float previewColorLutMaximum(uint table) {
  const bool paper = table == 1u && u(0u) != 1u;
  const uint offset = paper ? 19u : 16u;
  return max(max(f(offset), f(offset + 1u)), f(offset + 2u)) + 0.25;
}

#if defined(SPEKTRA_PREVIEW_COLOR_LUT)
bool samplePreviewColorLut(vec3 density, uint table, out vec3 value) {
  const float maximum = previewColorLutMaximum(table);
  if (!all(greaterThanEqual(density, vec3(0.0))) ||
      !all(lessThanEqual(density, vec3(maximum)))) return false;
  const vec3 position = density * (float(kPreviewColorLutSize - 1u) / maximum);
  const uvec3 lo = min(uvec3(floor(position)), uvec3(kPreviewColorLutSize - 2u));
  const vec3 t = clamp(position - vec3(lo), vec3(0.0), vec3(1.0));
  const uint row = kPreviewColorLutSize;
  const uint plane = row * row;
  const uint base = table * kPreviewColorLutCount + lo.x + lo.y * row + lo.z * plane;
  const vec3 a = mix(previewColorLut[base].xyz, previewColorLut[base + 1u].xyz, t.x);
  const vec3 b = mix(previewColorLut[base + row].xyz, previewColorLut[base + row + 1u].xyz, t.x);
  const vec3 c = mix(previewColorLut[base + plane].xyz, previewColorLut[base + plane + 1u].xyz, t.x);
  const vec3 d = mix(previewColorLut[base + plane + row].xyz, previewColorLut[base + plane + row + 1u].xyz, t.x);
  // Interpolate positive spectral responses in log space to preserve dark tones.
  value = exp(mix(mix(a, b, t.y), mix(c, d, t.y), t.z));
  return true;
}
#endif
