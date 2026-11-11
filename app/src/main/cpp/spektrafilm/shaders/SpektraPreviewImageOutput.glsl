#include "SpektraPreviewImageConfig.h"
#include "SpektraHalfDither.glsl"

layout(binding = SPEKTRA_PREVIEW_DESTINATION_IMAGE_BINDING, rgba16f) uniform writeonly image2D previewDestination;

void storePreviewImage(uint index, vec4 color) {
  const uint seed = index * 747796405u + 0x9e3779b9u;
  const float tpdf = 0.5 * (hashUnitFloat(seed) + hashUnitFloat(seed ^ 0x85ebca6bu) - 1.0);
  const uvec3 magnitude = floatBitsToUint(color.rgb) & uvec3(0x7fffffffu);
  const uvec3 exponent = magnitude & uvec3(0x7f800000u);
  // The float exponent gives the half-float spacing without logarithms.
  vec3 ulp = uintBitsToFloat(max(exponent, uvec3(0x38800000u)) - uvec3(0x05000000u));
  ulp = mix(ulp, vec3(0.0), greaterThanEqual(magnitude, uvec3(0x477fe000u)));
  const vec3 rgb = color.rgb + tpdf * ulp;
  const vec2 rg = unpackHalf2x16(packHalf2x16(rgb.rg));
  const vec2 ba = unpackHalf2x16(packHalf2x16(vec2(rgb.b, color.a)));
  imageStore(previewDestination, ivec2(gl_GlobalInvocationID.xy) +
    ivec2(params.activeOriginX, params.activeOriginY), vec4(rg, ba));
}
