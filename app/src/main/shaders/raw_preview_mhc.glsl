#ifndef UNSPEKTRA_RAW_PREVIEW_MHC_GLSL
#define UNSPEKTRA_RAW_PREVIEW_MHC_GLSL

float previewSensorHighlights(ivec2 position)
{
    if (rawColor.reserved1.z < 0.5)
        return 0.0;
    // Check a Bayer cell before lens shading or white balance.
    ivec2 origin = position & ~ivec2(1);
    ivec2 right = origin + ivec2(1, 0);
    ivec2 bottom = origin + ivec2(0, 1);
    ivec2 diagonal = origin + ivec2(1, 1);
    // Preserve integer precision instead of sampling the normalized unpack texture.
    uvec4 values = uvec4(rawSensorIntegerAt(clampRawCoordinateToParity(origin)),
                       rawSensorIntegerAt(clampRawCoordinateToParity(right)),
                       rawSensorIntegerAt(clampRawCoordinateToParity(bottom)),
                       rawSensorIntegerAt(clampRawCoordinateToParity(diagonal)));
    ivec4 channels = ivec4(cfaLevelIndex(origin), cfaLevelIndex(right),
                           cfaLevelIndex(bottom), cfaLevelIndex(diagonal));
    vec4 white = vec4(rawParams.whiteLevel[channels.x], rawParams.whiteLevel[channels.y],
                      rawParams.whiteLevel[channels.z], rawParams.whiteLevel[channels.w]);
    vec4 black = vec4(rawParams.blackLevel[channels.x], rawParams.blackLevel[channels.y],
                      rawParams.blackLevel[channels.z], rawParams.blackLevel[channels.w]);
    // Warn at 98% of the sensor range, before the last small amount of headroom is lost.
    uvec4 limit = uvec4(ceil(mix(black, white, 0.98)));
    return any(greaterThanEqual(values, limit)) ? 1.0 : 0.0;
}

#if UNSPEKTRA_PREVIEW_SHADING_REUSE
vec3 previewMhcInterpolate(float value, vec4 axial, vec4 distant, vec4 corners, ivec2 center, vec4 shading)
{
    vec4 black = rawParams.blackLevel;
    vec4 scale = shading * rawColor.whiteBalanceExposure.rggb /
                 max(rawParams.whiteLevel - black, vec4(1.0));
    int color = cfaColor(center);
    bool topRow = (center.y & 1) == 0;
    bool redHorizontal = cfaColor(center + ivec2(1, 0)) == CFA_RED;
    if (color == CFA_GREEN) {
        float centerBlack = topRow ? black.g : black.b;
        float centerScale = topRow ? scale.g : scale.b;
        value = max(value - centerBlack, 0.0) * centerScale;
        distant = max(distant - centerBlack, vec4(0.0)) * centerScale;
        corners = max(corners - (topRow ? black.b : black.g), vec4(0.0)) * (topRow ? scale.b : scale.g);
        axial = max(axial - (redHorizontal ? black.rraa : black.aarr), vec4(0.0)) *
                (redHorizontal ? scale.rraa : scale.aarr);
    } else {
        float centerBlack = color == CFA_RED ? black.r : black.a;
        float centerScale = color == CFA_RED ? scale.r : scale.a;
        value = max(value - centerBlack, 0.0) * centerScale;
        distant = max(distant - centerBlack, vec4(0.0)) * centerScale;
        corners = max(corners - (color == CFA_RED ? black.a : black.r), vec4(0.0)) *
                  (color == CFA_RED ? scale.a : scale.r);
        axial = max(axial - (topRow ? black.ggbb : black.bbgg), vec4(0.0)) *
                (topRow ? scale.ggbb : scale.bbgg);
    }
    return mhcInterpolate(value, axial, distant, corners, color, redHorizontal);
}

#endif

#endif
