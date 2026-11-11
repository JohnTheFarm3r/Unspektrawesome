#ifndef UNSPEKTRA_RAW_COLOR_GLSL
#define UNSPEKTRA_RAW_COLOR_GLSL

layout(set = 0, binding = 3, std140) uniform RawColorParameters {
    vec4 whiteBalanceExposure;
    vec4 colorMatrix0;
    vec4 colorMatrix1;
    vec4 colorMatrix2;
    vec4 reserved0;
    vec4 lensControls;
    vec4 reserved1;
    vec4 reserved2;
    vec4 geometryControls;
} rawColor;

layout(set = 0, binding = 4, std430) readonly buffer LensShadingMap {
    vec4 gain[];
} lensShading;

#ifdef UNSPEKTRA_PREVIEW_SCALAR_SHADING
layout(constant_id = 4) const bool previewTextureShading = false;
layout(set = 0, binding = 8) uniform sampler2D previewLensShading;
#endif

#include "raw_dcp.glsl"
#include "highlight_recovery.glsl"

vec4 rawLensGainAt(ivec2 position, ivec2 dimensions)
{
    ivec2 clamped = clamp(position, ivec2(0), dimensions - 1);
    return lensShading.gain[clamped.y * dimensions.x + clamped.x];
}

vec4 rawLensGains(vec2 normalizedPosition)
{
    ivec2 dimensions = ivec2(round(rawColor.geometryControls.zw));
    if (rawColor.lensControls.w <= 0.0 || any(lessThan(dimensions, ivec2(1))))
        return vec4(1.0);
#ifdef UNSPEKTRA_PREVIEW_SCALAR_SHADING
    if (previewTextureShading) {
        vec2 uv = (clamp(normalizedPosition, vec2(0.0), vec2(1.0)) *
                   vec2(dimensions - 1) + 0.5) / vec2(dimensions);
        return textureLod(previewLensShading, uv, 0.0);
    }
#endif
    vec2 mapPosition = clamp(normalizedPosition, vec2(0.0), vec2(1.0)) *
                       vec2(max(dimensions - 1, ivec2(0)));
    ivec2 lower = ivec2(floor(mapPosition));
    ivec2 upper = min(lower + 1, dimensions - 1);
    vec2 fraction = fract(mapPosition);
    vec4 top = mix(rawLensGainAt(lower, dimensions),
                   rawLensGainAt(ivec2(upper.x, lower.y), dimensions),
                   fraction.x);
    vec4 bottom = mix(rawLensGainAt(ivec2(lower.x, upper.y), dimensions),
                      rawLensGainAt(upper, dimensions), fraction.x);
    return mix(top, bottom, fraction.y);
}

float rawLensGain(vec2 normalizedPosition, int channel)
{
    ivec2 dimensions = ivec2(round(rawColor.geometryControls.zw));
    if (rawColor.lensControls.w <= 0.0 || any(lessThan(dimensions, ivec2(1))))
        return 1.0;
    vec2 mapPosition = clamp(normalizedPosition, vec2(0.0), vec2(1.0)) *
                       vec2(max(dimensions - 1, ivec2(0)));
    ivec2 lower = ivec2(floor(mapPosition));
    ivec2 upper = min(lower + 1, dimensions - 1);
    vec2 fraction = fract(mapPosition);
    float top = mix(lensShading.gain[lower.y * dimensions.x + lower.x][channel],
                    lensShading.gain[lower.y * dimensions.x + upper.x][channel], fraction.x);
    float bottom = mix(lensShading.gain[upper.y * dimensions.x + lower.x][channel],
                       lensShading.gain[upper.y * dimensions.x + upper.x][channel], fraction.x);
    return mix(top, bottom, fraction.y);
}

#if defined(UNSPEKTRA_PREVIEW_SHADING_REUSE) && UNSPEKTRA_PREVIEW_SHADING_REUSE
vec3 rawColorProcessWithShading(vec3 rawRgb, vec4 shading, bool neutralizeClipping, vec3 adjustedRgb, bool recoveredHighlights)
#else
vec3 rawColorProcess(vec3 rawRgb, vec2 normalizedPosition, float frameSeed, bool neutralizeClipping, vec3 adjustedRgb, bool recoveredHighlights)
#endif
{
    mat3 cameraToWorking = mat3(
        rawColor.colorMatrix0.xyz,
        rawColor.colorMatrix1.xyz,
        rawColor.colorMatrix2.xyz);
    bool directSdr = rawColor.reserved0.x > 0.5;
#if !defined(UNSPEKTRA_PREVIEW_SHADING_REUSE) || !UNSPEKTRA_PREVIEW_SHADING_REUSE
    vec4 shading = rawLensGains(normalizedPosition);
#endif
    vec3 channelWhite = rawColor.whiteBalanceExposure.xyz *
        vec3(shading.r, 0.5 * (shading.g + shading.b), shading.a);
    // Detect sensor clipping before compression can lower the clipped values.
    vec3 sensorRgb = rawRgb / max(channelWhite, vec3(0.000001));
    // Fade each channel over the last 20% of sensor range. A single clipped
    // channel must not erase the color still recorded by the other two.
    vec3 channelClipping = smoothstep(vec3(0.8), vec3(1.0), sensorRgb);
    float clipping = dot(channelClipping, vec3(1.0 / 3.0));
    vec3 working = rawDcpCalibration(cameraToWorking * adjustedRgb);
    float lum = dot(working, vec3(0.2126, 0.7152, 0.0722));
    if (neutralizeClipping) {
        if (clipping > 0.0 && clipping < 1.0) {
            float peak = max(rawRgb.r, max(rawRgb.g, rawRgb.b));
            bool reconstructed = recoveredHighlights && hrAnyClipped(sensorRgb, vec3(HR_CLIP_VALUE));
            // Reconstructed headroom does not measure the original neutral level.
            vec3 boundedRaw = reconstructed ? min(rawRgb, channelWhite) : rawRgb;
            float measuredPeak = max(boundedRaw.r, max(boundedRaw.g, boundedRaw.b));
            // Predict the cast of a neutral highlight at the sensor's channel limits.
            vec3 neutralRaw = mix(vec3(measuredPeak), min(vec3(measuredPeak), channelWhite), channelClipping);
            if (reconstructed || any(lessThan(neutralRaw, vec3(measuredPeak)))) {
                if (reconstructed) {
                    // Recovery changes the clipping cast before calibration. Predict its
                    // remaining color using the same reconstruction without lens shading.
                    vec3 gains = max(vec3(shading.r, 0.5 * (shading.g + shading.b), shading.a), vec3(0.000001));
                    vec3 wb = hrWhiteBalance(rawColor.whiteBalanceExposure.xyz);
                    vec3 balancedNeutral = neutralRaw / gains;
                    vec3 recoveredNeutral = applyOpposedHighlightRecovery(balancedNeutral / wb,
                        hrRefavg(balancedNeutral * 9.0), vec3(0.0), wb) * wb * gains;
                    neutralRaw = mix(neutralRaw, recoveredNeutral,
                        greaterThanEqual(sensorRgb, vec3(HR_CLIP_VALUE)));
                }
                float adjustedPeak = max(adjustedRgb.r, max(adjustedRgb.g, adjustedRgb.b));
                vec3 neutralWorking = rawDcpCalibration(
                    cameraToWorking * (neutralRaw * (adjustedPeak / max(peak, 0.000001))));
                float neutralLum = dot(neutralWorking, vec3(0.2126, 0.7152, 0.0722));
                vec3 clippingCast = neutralWorking - vec3(neutralLum);
                // Remove the component along the clipping cast, retaining other color differences.
                float correction = clamp(dot(working - vec3(lum), clippingCast) /
                    max(dot(clippingCast, clippingCast), 0.00000001), 0.0, 1.0);
                // Keep the correction from pushing a surviving channel below zero.
                vec3 limits = mix(vec3(1.0), max(working, vec3(0.0)) / max(clippingCast, vec3(0.000001)),
                    greaterThan(clippingCast, vec3(0.000001)));
                correction = min(correction, min(limits.r, min(limits.g, limits.b)));
                working -= clippingCast * correction;
            }
        }
        // Fade the remaining color without discarding brightness detail or linear headroom.
        working = mix(working, vec3(lum), clipping);
    }
#ifdef UNSPEKTRA_SENSOR_METER
    return working;
#else
    float highlights = rawColor.reserved0.z;
    float highlightScale = 1.0;
    // A zero marker preserves the curve stored in older queued captures.
    if (rawColor.reserved2.w < 1.0 && abs(highlights) > 0.001) {
        float perceptualLum = pow(clamp(lum, 0.0, 1.0), 1.0 / 2.2);
        float highlightMask = smoothstep(0.3, 1.0, perceptualLum);
        float highlightAdjust = highlights * highlightMask * 1.2;
        highlightScale = exp2(highlightAdjust);
    } else if (abs(highlights) > 0.001) {
        float start = clamp(rawColor.reserved2.w - 1.0, 0.0, 1.0);
        float threshold = pow(start, 2.2);
        if (lum > threshold) {
            if (highlights < 0.0) {
                // The shoulder joins with slope one and stays increasing above the threshold.
                float strength = exp2(-3.5 * highlights) - 1.0;
                float excess = lum - threshold;
                float x = strength * excess;
                float shoulder = x < 0.001 ? 1.0 - 0.5 * x + x * x / 3.0 : log(1.0 + x) / x;
                highlightScale = (threshold + excess * shoulder) / lum;
            } else {
                float perceptualLum = pow(lum, 1.0 / 2.2);
                float highlightMask = smoothstep(start, max(1.0, start + 0.01), perceptualLum);
                highlightScale = exp2(highlights * highlightMask * 1.2);
            }
        }
    }
    if (directSdr) {
        vec3 positive = max(working * highlightScale, vec3(0.0));
        return mix(12.92 * positive, 1.055 * pow(positive, vec3(1.0 / 2.4)) - 0.055,
                   step(vec3(0.0031308), positive));
    }
    return working * highlightScale;
#endif
}

#if defined(UNSPEKTRA_PREVIEW_SHADING_REUSE) && UNSPEKTRA_PREVIEW_SHADING_REUSE
vec3 rawColorProcessWithShading(vec3 rawRgb, vec4 shading, bool neutralizeClipping)
{
    return rawColorProcessWithShading(rawRgb, shading, neutralizeClipping, rawRgb, false);
}

vec3 rawColorProcess(vec3 rawRgb, vec2 normalizedPosition, float frameSeed, bool neutralizeClipping, vec3 adjustedRgb, bool recoveredHighlights)
{
    return rawColorProcessWithShading(rawRgb, rawLensGains(normalizedPosition), neutralizeClipping, adjustedRgb, recoveredHighlights);
}
#endif

vec3 rawColorProcess(vec3 rawRgb, vec2 normalizedPosition, float frameSeed, bool neutralizeClipping, vec3 adjustedRgb)
{
    return rawColorProcess(rawRgb, normalizedPosition, frameSeed, neutralizeClipping, adjustedRgb, false);
}

vec3 rawColorProcess(vec3 rawRgb, vec2 normalizedPosition, float frameSeed, bool neutralizeClipping)
{
    return rawColorProcess(rawRgb, normalizedPosition, frameSeed, neutralizeClipping, rawRgb);
}

vec3 rawColorProcess(vec3 rawRgb, vec2 normalizedPosition, float frameSeed)
{
    return rawColorProcess(rawRgb, normalizedPosition, frameSeed, true);
}

#endif
