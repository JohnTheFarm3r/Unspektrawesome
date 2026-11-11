#ifndef UNSPEKTRA_RAW_MHC_GLSL
#define UNSPEKTRA_RAW_MHC_GLSL

// Malvar, He and Cutler, ICASSP 2004: fixed 5x5 Bayer interpolation filters.
vec3 mhcInterpolate(float value, vec4 axial, vec4 distant, vec4 corners, int color, bool redHorizontal)
{
    float horizontal = axial.x + axial.y;
    float vertical = axial.z + axial.w;
    float horizontalFar = distant.x + distant.y;
    float verticalFar = distant.z + distant.w;
    float diagonal = dot(corners, vec4(1.0));
    if (color == CFA_GREEN) {
        float alongRow = (5.0 * value + 4.0 * horizontal - horizontalFar - diagonal +
                          0.5 * verticalFar) * 0.125;
        float alongColumn = (5.0 * value + 4.0 * vertical - verticalFar - diagonal +
                             0.5 * horizontalFar) * 0.125;
        return redHorizontal
            ? vec3(alongRow, value, alongColumn) : vec3(alongColumn, value, alongRow);
    }
    float green = (4.0 * value + 2.0 * (horizontal + vertical) - horizontalFar - verticalFar) * 0.125;
    float opposite = (6.0 * value + 2.0 * diagonal - 1.5 * (horizontalFar + verticalFar)) * 0.125;
    return color == CFA_RED ? vec3(value, green, opposite) : vec3(opposite, green, value);
}

vec3 mhcDemosaic(ivec2 center)
{
    float value = UNSPEKTRA_DEMOSAIC_SAMPLE(center);
    vec4 axial = vec4(UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, 0)),
                      UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(1, 0)),
                      UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, -1)),
                      UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, 1)));
    vec4 distant = vec4(UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-2, 0)),
                        UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(2, 0)),
                        UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, -2)),
                        UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(0, 2)));
    vec4 corners = vec4(UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, -1)),
                        UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(1, -1)),
                        UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(-1, 1)),
                        UNSPEKTRA_DEMOSAIC_SAMPLE(center + ivec2(1, 1)));
    return mhcInterpolate(value, axial, distant, corners, cfaColor(center),
        cfaColor(center + ivec2(1, 0)) == CFA_RED);
}

#endif
