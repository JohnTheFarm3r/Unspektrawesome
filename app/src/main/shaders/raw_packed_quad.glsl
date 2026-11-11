#ifndef UNSPEKTRA_RAW_PACKED_QUAD_GLSL
#define UNSPEKTRA_RAW_PACKED_QUAD_GLSL

vec4 rawPackedFour(ivec2 p)
{
    if (p.x < frameGeometry.rawBounds.x || p.x + 3 >= frameGeometry.rawBounds.x + frameGeometry.rawBounds.z)
        return vec4(rawInteger(p), rawInteger(p + ivec2(1, 0)),
            rawInteger(p + ivec2(2, 0)), rawInteger(p + ivec2(3, 0)));
    uint row = uint(p.y * rawParams.layoutInfo.x);
    int format = UNSPEKTRA_INPUT_FORMAT;
    uint offset = row + uint(p.x) * (format == RAW_FORMAT_10 ? 5u : format == RAW_FORMAT_12 ? 6u : 8u) / 4u;
    uint index = offset >> 2u;
    uint shift = (offset & 3u) * 8u;
    uint low = rawInput.words[index];
    uint high = rawInput.words[index + 1u];
    uint first = low >> shift;
    if (shift != 0u) first |= high << (32u - shift);
    uint second = high >> shift;
    if ((format == RAW_FORMAT_SENSOR && shift != 0u) || (format == RAW_FORMAT_12 && shift == 24u))
        second |= rawInput.words[index + 2u] << (32u - shift);
    if (format == RAW_FORMAT_10) {
        uvec4 most = (uvec4(first) >> uvec4(0u, 8u, 16u, 24u)) & 255u;
        uvec4 least = (uvec4(second) >> uvec4(0u, 2u, 4u, 6u)) & 3u;
        return vec4(most * 4u + least);
    }
    if (format == RAW_FORMAT_12) {
        uvec4 most = uvec4(first, first >> 8u, first >> 24u, second) & 255u;
        uvec4 least = uvec4(first >> 16u, first >> 20u, second >> 8u, second >> 12u) & 15u;
        return vec4(most * 16u + least);
    }
    return vec4(uvec4(first, first >> 16u, second, second >> 16u) & 65535u);
}

#endif
