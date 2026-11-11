#include "SpektraFourStageLutConfig.h"

#ifndef SPEKTRA_FOUR_STAGE_LUT_BINDING
#define SPEKTRA_FOUR_STAGE_LUT_BINDING 4
#endif

const uint kFourStageLutSize = SPEKTRA_FOUR_STAGE_LUT_SIZE;
const uint kFourStageLutCount = kFourStageLutSize * kFourStageLutSize * kFourStageLutSize;
const uint kFourStageProbeSize = SPEKTRA_FOUR_STAGE_LUT_PROBE_SIZE;
const uint kFourStageProbeCount = kFourStageProbeSize * kFourStageProbeSize * kFourStageProbeSize;
const uint kFourStageProbeOffset = SPEKTRA_FOUR_STAGE_LUT_METADATA_COUNT;
const uint kFourStageTableOffset = kFourStageProbeOffset + 3u * kFourStageProbeCount;

#if defined(SPEKTRA_FOUR_STAGE_LUT_BUILD)
layout(std430, binding = SPEKTRA_FOUR_STAGE_LUT_BINDING) buffer FourStageLut { vec4 fourStageLutData[]; };
#else
layout(std430, binding = SPEKTRA_FOUR_STAGE_LUT_BINDING) readonly buffer FourStageLut { vec4 fourStageLutData[]; };
#endif

// ACEScct transports the camera's linear RGB into the input cube's bounded domain.
vec3 fourStageEncodeInput(vec3 linearRgb) {
  const vec3 logarithmic = (log2(max(linearRgb, vec3(0.0078125))) + 9.72) / 17.52;
  return mix(logarithmic, linearRgb * 10.5402377416545 + 0.0729055341958355,
    lessThanEqual(linearRgb, vec3(0.0078125)));
}

vec3 fourStageDecodeInput(vec3 code) {
  return mix(exp2(code * 17.52 - 9.72), (code - 0.0729055341958355) / 10.5402377416545,
    lessThanEqual(code, vec3(0.155251141552511)));
}

vec2 fourStageLogRange(uint stage) {
  return stage == 0u ? fourStageLutData[0].xy : fourStageLutData[0].zw;
}

vec3 fourStageEncodeLog(vec3 logExposure, uint stage) {
  const vec2 range = fourStageLogRange(stage);
  return (logExposure - range.x) / (range.y - range.x);
}

vec3 fourStageDecodeLog(vec3 code, uint stage) {
  const vec2 range = fourStageLogRange(stage);
  return code * (range.y - range.x) + range.x;
}

vec3 fourStageEncodeDensity(vec3 density) {
  const vec3 minimum = fourStageLutData[1].xyz;
  return clamp((density - minimum) / (fourStageLutData[2].xyz - minimum), vec3(0.0), vec3(1.0));
}

vec3 fourStageDecodeDensity(vec3 code) {
  const vec3 minimum = fourStageLutData[1].xyz;
  return code * (fourStageLutData[2].xyz - minimum) + minimum;
}

#if !defined(SPEKTRA_FOUR_STAGE_LUT_BUILD)
float fourStageDirCurve(float logRaw, uint channel) {
  const vec4 domain = fourStageLutData[3];
  const uint count = uint(domain.x);
  const uint base = SPEKTRA_FOUR_STAGE_DIR_CURVE_OFFSET;
  const float lookupRaw = logRaw * max(domain.w, 1.0e-6);
  if (lookupRaw <= domain.y) return fourStageLutData[base][channel + 1u];
  if (lookupRaw >= domain.z) return fourStageLutData[base + count - 1u][channel + 1u];
  uint lo = min(uint((lookupRaw - domain.y) / (domain.z - domain.y) * float(count - 1u)), count - 2u);
  uint hi = lo + 1u;
  if (fourStageLutData[base + lo].x > lookupRaw || fourStageLutData[base + hi].x < lookupRaw) {
    lo = 0u;
    hi = count - 1u;
    while (hi - lo > 1u) {
      const uint mid = (lo + hi) >> 1u;
      if (fourStageLutData[base + mid].x <= lookupRaw) lo = mid;
      else hi = mid;
    }
  }
  const vec4 a = fourStageLutData[base + lo], b = fourStageLutData[base + hi];
  const float t = clamp((lookupRaw - a.x) / (b.x - a.x), 0.0, 1.0);
  return mix(a[channel + 1u], b[channel + 1u], t);
}

vec3 fourStageDirRegions(float logRaw) {
  const float toe = 1.0 - 1.0 / (1.0 + exp(-(logRaw + 2.0) / 0.5));
  const float shoulder = 1.0 / (1.0 + exp(-logRaw / 0.5));
  return vec3(toe, max(1.0 - toe - shoulder, 0.0), shoulder);
}

vec3 applyFourStageCouplers(vec3 logRaw, vec3 density) {
  if (fourStageLutData[3].x == 0.0) return density;
  const vec3 silver = fourStageLutData[4].w != 0.0
    ? max(fourStageLutData[7].xyz - density, vec3(0.0)) : max(density, vec3(0.0));
  vec3 corrected = logRaw - vec3(dot(silver, fourStageLutData[4].xyz),
    dot(silver, fourStageLutData[5].xyz), dot(silver, fourStageLutData[6].xyz));
  const bool experimental = fourStageLutData[7].w == 1.0;
  const float activity = fourStageLutData[5].w;
  const float squared = activity * abs(activity);
  if (experimental) {
    corrected -= vec3(fourStageLutData[6].w);
    const float meanRaw = (corrected.r + corrected.g + corrected.b) / 3.0;
    const mat3 coupling = mat3(vec3(0.0, -0.015, 0.015), vec3(0.015, 0.0, -0.015), vec3(-0.015, 0.015, 0.0));
    corrected += activity * (coupling * (corrected - vec3(meanRaw)));
    for (uint channel = 0u; channel < 3u; ++channel) {
      const vec3 regions = fourStageDirRegions(corrected[channel]);
      corrected[channel] += (vec3(0.25, 0.28, 0.31)[channel] * activity +
        vec3(0.04, 0.06, 0.08)[channel] * squared) * regions.y +
        (vec3(0.32, 0.38, 0.45)[channel] * activity + vec3(0.08, 0.12, 0.16)[channel] * squared) * regions.z;
    }
  }
  vec3 result = vec3(fourStageDirCurve(corrected.r, 0u), fourStageDirCurve(corrected.g, 1u),
    fourStageDirCurve(corrected.b, 2u));
  if (experimental) {
    for (uint channel = 0u; channel < 3u; ++channel) {
      const vec3 regions = fourStageDirRegions(corrected[channel]);
      const float build = vec3(1.05, 0.95, 1.0)[channel] * activity + vec3(-0.90, -0.82, -0.86)[channel] * squared;
      result[channel] *= clamp(1.0 + build * (0.12 * regions.x + regions.y + regions.z), 0.35, 2.0);
    }
  }
  return max(result, vec3(0.0));
}

#if defined(SPEKTRA_FOUR_STAGE_LUT_TEXTURE)
layout(binding = SPEKTRA_FOUR_STAGE_LUT_TEXTURE_BINDING) uniform sampler3D fourStageLutTexture;
#endif

vec3 sampleFourStageLut(vec3 code, uint stage) {
  const vec3 position = clamp(code, vec3(0.0), vec3(1.0)) * float(kFourStageLutSize - 1u);
#if defined(SPEKTRA_FOUR_STAGE_LUT_TEXTURE)
  // Each table occupies its own depth range, including both boundary texels.
  const vec3 coordinate = (position + vec3(0.5, 0.5, float(stage * kFourStageLutSize) + 0.5)) /
    vec3(kFourStageLutSize, kFourStageLutSize, 4u * kFourStageLutSize);
  return textureLod(fourStageLutTexture, coordinate, 0.0).xyz;
#else
  const uvec3 lo = min(uvec3(floor(position)), uvec3(kFourStageLutSize - 2u));
  const vec3 t = position - vec3(lo);
  const uint row = kFourStageLutSize;
  const uint plane = row * row;
  const uint base = kFourStageTableOffset + stage * kFourStageLutCount + lo.x + lo.y * row + lo.z * plane;
  const vec3 a = mix(fourStageLutData[base].xyz, fourStageLutData[base + 1u].xyz, t.x);
  const vec3 b = mix(fourStageLutData[base + row].xyz, fourStageLutData[base + row + 1u].xyz, t.x);
  const vec3 c = mix(fourStageLutData[base + plane].xyz, fourStageLutData[base + plane + 1u].xyz, t.x);
  const vec3 d = mix(fourStageLutData[base + plane + row].xyz, fourStageLutData[base + plane + row + 1u].xyz, t.x);
  return mix(mix(a, b, t.y), mix(c, d, t.y), t.z);
#endif
}
#endif
