#ifndef UNSPEKTRA_RAW_SOURCE_GLSL
#define UNSPEKTRA_RAW_SOURCE_GLSL

const int RAW_FORMAT_SENSOR = 0;
const int RAW_FORMAT_10 = 1;
const int RAW_FORMAT_12 = 2;

const int CFA_RGGB = 0;
const int CFA_GRBG = 1;
const int CFA_GBRG = 2;
const int CFA_BGGR = 3;

const int CFA_RED = 0;
const int CFA_GREEN = 1;
const int CFA_BLUE = 2;

#ifdef UNSPEKTRA_RAW_IMAGE_INPUT
#ifdef UNSPEKTRA_RAW_CELL_INPUT
layout(set = 0, binding = 5) uniform sampler2D rawCellImage;
#else
layout(set = 0, binding = 5) uniform usampler2D rawInputImage;
#endif
#else
layout(set = 0, binding = 0, std430) readonly buffer RawInputBuffer {
    uint words[];
} rawInput;
#endif

layout(set = 0, binding = 1, std140) uniform RawParameters {
    ivec4 dimensions; // RAW width, RAW height, output width, output height.
    ivec4 layoutInfo; // Row stride in bytes, format, CFA, frame seed.
    vec4 blackLevel;  // R, G1, G2, B.
    vec4 whiteLevel;  // R, G1, G2, B.
    ivec4 remosaic;   // Block size, source CFA, source phase X and Y.
} rawParams;

#ifdef UNSPEKTRA_PREVIEW_SCALAR_SHADING
#ifdef UNSPEKTRA_PREVIEW_RAW_FORMAT
const int previewRawFormat = UNSPEKTRA_PREVIEW_RAW_FORMAT;
#else
layout(constant_id = 1) const int previewRawFormat = -1;
#endif
layout(constant_id = 2) const int previewCfaPattern = -1;
#define UNSPEKTRA_INPUT_FORMAT (previewRawFormat >= 0 ? previewRawFormat : rawParams.layoutInfo.y)
#define UNSPEKTRA_INPUT_CFA (previewCfaPattern >= 0 ? previewCfaPattern : rawParams.layoutInfo.z)
#else
#define UNSPEKTRA_INPUT_FORMAT rawParams.layoutInfo.y
#define UNSPEKTRA_INPUT_CFA rawParams.layoutInfo.z
#endif

#if defined(UNSPEKTRA_PREVIEW_SCALAR_SHADING) && !defined(UNSPEKTRA_RAW_IMAGE_INPUT) && !defined(UNSPEKTRA_RAW_UNPACK_PASS)
#define UNSPEKTRA_PREVIEW_REMOSAIC
#ifdef UNSPEKTRA_PREVIEW_REMOSAIC_INPUT
const bool previewRemosaic = true;
#else
layout(constant_id = 6) const bool previewRemosaic = false;
#endif
ivec2 previewRemosaicSize;
#endif

uint rawByte(uint packed, uint byteOffset)
{
#if (defined(UNSPEKTRA_PREVIEW_SHADING_REUSE) && UNSPEKTRA_PREVIEW_SHADING_REUSE) || defined(UNSPEKTRA_SENSOR_METER)
    return (packed >> ((byteOffset & 3u) * 8u)) & 255u;
#else
    switch (byteOffset & 3u) {
        case 0u: return packed & 255u;
        case 1u: return (packed >> 8u) & 255u;
        case 2u: return (packed >> 16u) & 255u;
        default: return packed >> 24u;
    }
#endif
}

uint rawSensorIntegerAt(ivec2 position)
{
#ifdef UNSPEKTRA_PREVIEW_REMOSAIC
    if (previewRemosaic) {
        uint sampleIndex = uint(position.y * previewRemosaicSize.x + position.x);
        uint word = rawInput.words[sampleIndex >> 1u];
        return (word >> ((sampleIndex & 1u) * 16u)) & 65535u;
    }
#endif
#ifdef UNSPEKTRA_RAW_IMAGE_INPUT
#ifdef UNSPEKTRA_RAW_CELL_INPUT
#ifdef UNSPEKTRA_RAW_CELL_FULL
    const int cellStep = 2;
#else
    ivec2 outputSize = (frameGeometry.transform.x & 1) != 0 ? rawParams.dimensions.wz : rawParams.dimensions.zw;
    vec2 footprint = frameGeometry.sourceCrop.zw * vec2(rawParams.dimensions.xy) / vec2(outputSize);
    int cellStep = max(2, int(ceil(max(footprint.x, footprint.y) * 0.5)) * 2);
#endif
    ivec2 cell = position / cellStep;
    vec4 values = textureLod(rawCellImage, (vec2(cell) + 0.5) / vec2(textureSize(rawCellImage, 0)), 0.0);
    int site = (position.y & 1) * 2 + (position.x & 1);
    int channel = site == UNSPEKTRA_INPUT_CFA ? 0 : site == (UNSPEKTRA_INPUT_CFA ^ 3) ? 3 : (position.y & 1) + 1;
    return uint(round(values[channel] * max(rawParams.whiteLevel[channel] - rawParams.blackLevel[channel], 1.0) +
        rawParams.blackLevel[channel]));
#else
    // Nearest sampling at texel centers avoids an Adreno compiler failure in integer texelFetch.
    vec2 uv = (vec2(position) + 0.5) / vec2(rawParams.dimensions.xy);
    return textureLod(rawInputImage, uv, 0.0).r;
#endif
#else
    uint row = uint(position.y * rawParams.layoutInfo.x);
    uint x = uint(position.x);
    uint firstOffset = row + x * 2u;
    uint byteDistance = 1u;
    uint lowShift = 0u;
    int format = UNSPEKTRA_INPUT_FORMAT;
    if (format == RAW_FORMAT_10) {
        uint group = row + (x >> 2u) * 5u;
        firstOffset = group + (x & 3u);
        byteDistance = 4u - (x & 3u);
        lowShift = (x & 3u) * 2u;
    } else if (format == RAW_FORMAT_12) {
        uint group = row + (x >> 1u) * 3u;
        firstOffset = group + (x & 1u);
        byteDistance = 2u - (x & 1u);
        lowShift = (x & 1u) * 4u;
    }
    // Keep the loads outside the format branches for Adreno driver correctness.
    uint firstWordIndex = firstOffset / 4u;
    // Compute the carry explicitly so cross-word byte loads stay distinct.
    uint secondByteIndex = (firstOffset & 3u) + byteDistance;
    uint firstWord = rawInput.words[firstWordIndex];
    uint secondWord = firstWord;
    if (secondByteIndex >= 4u)
        secondWord = rawInput.words[firstWordIndex + 1u];
    uint first = rawByte(firstWord, firstOffset);
    uint second = rawByte(secondWord, secondByteIndex);
    if (format == RAW_FORMAT_10) return first * 4u + ((second >> lowShift) & 3u);
    if (format == RAW_FORMAT_12) return first * 16u + ((second >> lowShift) & 15u);
    return first + second * 256u;
#endif
}

#endif
