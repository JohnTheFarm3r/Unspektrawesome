#extension GL_GOOGLE_include_directive : require

#define RAW_GEOMETRY_BINDING 9
#define UNSPEKTRA_AMAZE_INPUT
#include "raw_input.glsl"

layout(constant_id = 7) const int AMAZE_BAYER_OFFSET_X = 0;
layout(constant_id = 14) const int AMAZE_BAYER_OFFSET_Y = 0;
#define AMAZE_WIDTH frameGeometry.rawBounds.z
#define AMAZE_HEIGHT frameGeometry.rawBounds.w

const highp float AMAZE_EPS = 1e-5;
const highp float AMAZE_EPSSQ = 1e-10;

highp float amazeSqr(highp float value)
{
    return value * value;
}

#ifdef UNSPEKTRA_AMAZE_TILED_RAW
layout(set = 0, binding = 13) uniform sampler2D normalizedRaw;
#endif
highp float getRaw(ivec2 position)
{
#ifdef UNSPEKTRA_AMAZE_TILED_RAW
    ivec2 q = position - tile.bounds.xy;
    ivec2 parity = q & 1;
    ivec2 maximum = tile.bounds.zw - 1;
    maximum -= (maximum & 1) ^ parity;
    return texelFetch(normalizedRaw, clamp(q, parity, maximum), 0).r;
#else
    return rawNormalized(position + frameGeometry.rawBounds.xy);
#endif
}

ivec2 amazeRedOffset()
{
    return ivec2(AMAZE_BAYER_OFFSET_X, AMAZE_BAYER_OFFSET_Y);
}

ivec2 amazeBlueOffset()
{
    return ivec2(1) - amazeRedOffset();
}

int amazeFc(ivec2 position)
{
    int x = position.x & 1;
    int y = position.y & 1;
    if (x == AMAZE_BAYER_OFFSET_X && y == AMAZE_BAYER_OFFSET_Y)
        return CFA_RED;
    if (x == 1 - AMAZE_BAYER_OFFSET_X && y == 1 - AMAZE_BAYER_OFFSET_Y)
        return CFA_BLUE;
    return CFA_GREEN;
}

bool amazeIsGreen(ivec2 position)
{
    return amazeFc(position) == CFA_GREEN;
}

float amazeGradient(ivec2 p, ivec2 d) {
    float c = getRaw(p);
    return AMAZE_EPS + abs(getRaw(p + 2*d) - c) + abs(c - getRaw(p - 2*d)) +
        abs(getRaw(p + d) - getRaw(p - d));
}
float amazeMedian(float x, float a, float b) { return clamp(x, min(a,b), max(a,b)); }
float amazeBound(float value, float center, float a, float b, float strength) {
    float bounded = amazeMedian(value, a, b);
    float blend = clamp(strength * (center - value) / (AMAZE_EPS + center + value), 0.0, 1.0);
    return value > 1.0 ? bounded : mix(value, bounded, blend);
}
