uint hashUint(uint value) {
  value ^= value >> 16u;
  value *= 0x7feb352du;
  value ^= value >> 15u;
  value *= 0x846ca68bu;
  value ^= value >> 16u;
  return value;
}

float hashUnitFloat(uint value) {
  return float(hashUint(value) & 0x00ffffffu) * (1.0 / 16777216.0);
}

float halfUlp(float value) {
  const float magnitude = abs(value);
  if (isnan(magnitude) || isinf(magnitude) || magnitude >= 65504.0) {
    return 0.0;
  }
  if (magnitude < 6.103515625e-5) {
    return 5.960464477539063e-8;
  }
  return exp2(floor(log2(magnitude)) - 10.0);
}

float ditherTpdfForHalf(uint pixelIndex) {
  const uint seed = pixelIndex * 747796405u + 0x9e3779b9u;
  return 0.5 * (hashUnitFloat(seed) + hashUnitFloat(seed ^ 0x85ebca6bu) - 1.0);
}

float ditherForHalf(float value, float tpdf) {
  return value + tpdf * halfUlp(value);
}

vec3 ditherRgbForHalf(vec3 rgb, uint pixelIndex) {
  const float tpdf = ditherTpdfForHalf(pixelIndex);
  return vec3(
    ditherForHalf(rgb.r, tpdf),
    ditherForHalf(rgb.g, tpdf),
    ditherForHalf(rgb.b, tpdf)
  );
}
