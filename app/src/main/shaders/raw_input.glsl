#ifndef UNSPEKTRA_RAW_INPUT_GLSL
#define UNSPEKTRA_RAW_INPUT_GLSL

#include "raw_color.glsl"
#include "frame_geometry.glsl"

#include "raw_source.glsl"

#if defined(UNSPEKTRA_PREVIEW_SCALAR_SHADING) && !defined(UNSPEKTRA_RAW_UNPACK_PASS)
layout(constant_id = 5) const bool previewUnpacked = false;
layout(set = 0, binding = 10) uniform sampler2D previewRawTexture;
#endif

ivec2 clampRawCoordinate(ivec2 position)
{
#ifdef UNSPEKTRA_PREVIEW_REMOSAIC
    if (previewRemosaic)
        return clamp(position, ivec2(0), previewRemosaicSize - 1);
#endif
    return clamp(position, frameGeometry.rawBounds.xy,
        frameGeometry.rawBounds.xy + frameGeometry.rawBounds.zw - ivec2(1));
}

ivec2 clampRawCoordinateToParity(ivec2 position)
{
    ivec2 parity = position & ivec2(1);
#ifdef UNSPEKTRA_PREVIEW_REMOSAIC
    if (previewRemosaic) {
        ivec2 maximum = previewRemosaicSize - 1;
        maximum -= (maximum & 1) ^ parity;
        return clamp(position, parity, maximum);
    }
#endif
    ivec2 minimum = frameGeometry.rawBounds.xy;
    minimum += (minimum & ivec2(1)) ^ parity;
    ivec2 maximum = frameGeometry.rawBounds.xy + frameGeometry.rawBounds.zw - ivec2(1);
    maximum -= (maximum & ivec2(1)) ^ parity;
    return clamp(position, minimum, maximum);
}

uint rawIntegerAt(ivec2 position)
{
#if defined(UNSPEKTRA_PREVIEW_SCALAR_SHADING) && !defined(UNSPEKTRA_RAW_UNPACK_PASS)
    if (previewUnpacked)
        return uint(round(texelFetch(previewRawTexture, position, 0).r * 65535.0));
#endif
    return rawSensorIntegerAt(position);
}

uint rawInteger(ivec2 requestedPosition)
{
    return rawIntegerAt(clampRawCoordinateToParity(requestedPosition));
}

uint rawIntegerClamped(ivec2 requestedPosition)
{
    return rawIntegerAt(clampRawCoordinate(requestedPosition));
}

int cfaColor(ivec2 position)
{
    ivec2 parity = position & ivec2(1);
    int site = parity.y * 2 + parity.x;
    if (site == UNSPEKTRA_INPUT_CFA) return CFA_RED;
    if (site == (UNSPEKTRA_INPUT_CFA ^ 3)) return CFA_BLUE;
    return CFA_GREEN;
}

int cfaLevelIndex(ivec2 position)
{
    ivec2 parity = position & ivec2(1);
    int site = parity.y * 2 + parity.x;
    if (site == UNSPEKTRA_INPUT_CFA) return 0;
    if (site == (UNSPEKTRA_INPUT_CFA ^ 3)) return 3;
    return parity.y + 1;
}

vec2 rawSourceUv(ivec2 position)
{
#ifdef UNSPEKTRA_PREVIEW_REMOSAIC
    if (previewRemosaic)
        return frameGeometry.sourceCrop.xy +
            (vec2(position) + 0.5) / vec2(previewRemosaicSize) * frameGeometry.sourceCrop.zw;
#endif
    return (vec2(position) + 0.5) / vec2(rawParams.dimensions.xy);
}

float rawNormalized(ivec2 position)
{
    position = clampRawCoordinateToParity(position);
    int levelIndex = cfaLevelIndex(position);
    float black = rawParams.blackLevel[levelIndex];
    float white = rawParams.whiteLevel[levelIndex];
#ifdef UNSPEKTRA_PREVIEW_SCALAR_SHADING
    float value = float(rawIntegerAt(position));
#else
    float value = float(rawInteger(position));
#endif
#ifdef UNSPEKTRA_AMAZE_INPUT
    if (rawColor.reserved0.w > 0.5)
        return max((value - black) / max(white - black, 1.0), 0.0);
#endif
    vec2 sourceUv = rawSourceUv(position);
    float shading = rawLensGain(geometryLensShadingUv(sourceUv, rawParams.dimensions.xy), levelIndex);
    int color = cfaColor(position);
    return max((value - black) / max(white - black, 1.0), 0.0) *
           shading * rawColor.whiteBalanceExposure[color];
}

float rawNormalizedClamped(ivec2 position)
{
    position = clampRawCoordinate(position);
    int levelIndex = cfaLevelIndex(position);
    float black = rawParams.blackLevel[levelIndex];
    float white = rawParams.whiteLevel[levelIndex];
    float value = float(rawIntegerClamped(position));
#ifdef UNSPEKTRA_AMAZE_INPUT
    if (rawColor.reserved0.w > 0.5)
        return max((value - black) / max(white - black, 1.0), 0.0);
#endif
    vec2 sourceUv = rawSourceUv(position);
    float shading = rawLensGains(geometryLensShadingUv(sourceUv, rawParams.dimensions.xy))[levelIndex];
    int color = cfaColor(position);
    return max((value - black) / max(white - black, 1.0), 0.0) *
           shading * rawColor.whiteBalanceExposure[color];
}

#endif
