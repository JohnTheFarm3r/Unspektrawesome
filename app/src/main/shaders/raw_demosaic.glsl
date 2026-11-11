#ifndef UNSPEKTRA_RAW_DEMOSAIC_GLSL
#define UNSPEKTRA_RAW_DEMOSAIC_GLSL

#include "raw_input.glsl"

#ifndef UNSPEKTRA_DEMOSAIC_SAMPLE
#define UNSPEKTRA_DEMOSAIC_SAMPLE rawNormalized
#endif

float averageAxial(ivec2 center)
{
    return 0.25 * (UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, 0)) +
                   UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2( 1, 0)) +
                   UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2( 0,-1)) +
                   UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2( 0, 1)));
}

float averageDiagonal(ivec2 center)
{
    return 0.25 * (UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1,-1)) +
                   UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2( 1,-1)) +
                   UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, 1)) +
                   UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2( 1, 1)));
}

vec3 bilinearDemosaic(ivec2 center)
{
    int centerColor = cfaColor(center);
    float centerValue = UNSPEKTRA_DEMOSAIC_SAMPLE(center);

#ifdef UNSPEKTRA_PREVIEW_REMOSAIC_INPUT
    // Selecting completed channels avoids an Adreno compiler crash on remosaic early returns.
    float horizontal = 0.5 * (UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, 0)) +
                              UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(1, 0)));
    float vertical = 0.5 * (UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, -1)) +
                            UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, 1)));
    float diagonal = averageDiagonal(center);
    bool redIsHorizontal = cfaColor(center + ivec2(1, 0)) == CFA_RED;
    vec3 green = redIsHorizontal ? vec3(horizontal, centerValue, vertical)
                                : vec3(vertical, centerValue, horizontal);
    vec3 red = vec3(centerValue, 0.5 * (horizontal + vertical), diagonal);
    vec3 blue = vec3(diagonal, red.g, centerValue);
    return centerColor == CFA_RED ? red : centerColor == CFA_BLUE ? blue : green;
#else
    if (centerColor == CFA_RED)
        return vec3(centerValue, averageAxial(center), averageDiagonal(center));
    if (centerColor == CFA_BLUE)
        return vec3(averageDiagonal(center), averageAxial(center), centerValue);

    bool redIsHorizontal = cfaColor(center + ivec2(1, 0)) == CFA_RED;
    float horizontal = 0.5 * (UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, 0)) +
                              UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2( 1, 0)));
    float vertical = 0.5 * (UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, -1)) +
                            UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0,  1)));
    return redIsHorizontal
        ? vec3(horizontal, centerValue, vertical)
        : vec3(vertical, centerValue, horizontal);
#endif
}

#endif
