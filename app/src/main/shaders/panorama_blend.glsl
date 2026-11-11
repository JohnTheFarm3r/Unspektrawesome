vec4 blendShape(int level) { return p[int(p[18].z) + 1 + level]; }
#if !defined(PANORAMA_BLEND_SAMPLING_ONLY)
vec4 blendGaussian(ivec2 point, int level, int image) {
    vec4 shape = blendShape(level);
    point = clamp(point, ivec2(0), ivec2(shape.xy) - 1);
    return queries[int(shape.z) + (point.y * int(shape.x) + point.x) * 2 + image];
}
vec3 blendExpand(vec2 point, int level, int image, bool reconstructed) {
    vec4 shape = blendShape(level);
    point = clamp(point * .5, vec2(0), shape.xy - 1);
    ivec2 cell = ivec2(point), next = min(cell + 1, ivec2(shape.xy) - 1);
    vec2 fraction = fract(point);
    int indices[4] = int[](cell.y * int(shape.x) + cell.x, cell.y * int(shape.x) + next.x,
                          next.y * int(shape.x) + cell.x, next.y * int(shape.x) + next.x);
    vec3 values[4];
    for (int i = 0; i < 4; ++i) {
        int offset = int(shape.z) + indices[i] * 2;
        vec4 value = queries[offset + image];
        values[i] = reconstructed ? result[offset].rgb : value.rgb / max(1e-6, value.a);
    }
    return mix(mix(values[0], values[1], fraction.x), mix(values[2], values[3], fraction.x), fraction.y);
}
#endif
void blendJob(uint index) {
    int descriptor = int(p[18].z);
#if defined(PANORAMA_BLEND_SAMPLING_ONLY)
    int cols = int(blendShape(0).x);
    ivec2 cell = ivec2(int(index) % cols, int(index) / cols + tile.z);
    vec2 point = p[descriptor].xy + (vec2(cell) + .5) * p[descriptor].z;
    float red = 0, green = 0, blue = 0;
    int beforeCount = 0, afterCount = 0, overlap = 0;
    for (int y = 0; y < tile.w; ++y) for (int x = 0; x < tile.w; ++x) {
        vec2 q = point + ((vec2(x, y) + .5) / tile.w - .5) * p[descriptor].z;
        vec4 reference = referenceAt(q);
        vec2 source = correctedPoint(q);
        bool before = reference.a > 0;
        bool after = valid(source);
        if (before) beforeCount += 1;
        if (after) afterCount += 1;
        if (before && after) {
            vec3 value = color(rgb(source), q, source);
            red += value.r - reference.r;
            green += value.g - reference.g;
            blue += value.b - reference.b;
            overlap += 1;
        }
    }
    float divisor = float(tile.w * tile.w);
    float mask = beforeCount == 0 ? 1 : afterCount == 0 ? 0 : sourceSelection(point);
    int offset = (cell.y * cols + cell.x) * 2;
    queries[offset] = vec4(red / divisor, green / divisor, blue / divisor, float(overlap) / divisor);
    queries[offset + 1] = vec4(mask, 0, 0, 0);
#else
    if (job.x == 70) {
        int cols = int(blendShape(0).x);
        ivec2 cell = ivec2(int(index) % cols, int(index) / cols + tile.z);
        vec2 point = p[descriptor].xy + (vec2(cell) + .5) * p[descriptor].z;
        vec3 difference = vec3(0);
        float beforeCount = 0, afterCount = 0, overlap = 0;
        for (int y = 0; y < tile.w; ++y) for (int x = 0; x < tile.w; ++x) {
            vec2 q = point + ((vec2(x, y) + .5) / tile.w - .5) * p[descriptor].z;
            vec4 reference = referenceAt(q);
            vec2 source = correctedPoint(q);
            if (reference.a > 0) beforeCount += 1;
            if (valid(source)) {
                afterCount += 1;
                if (reference.a > 0) {
                    difference += color(rgb(source), q, source) - reference.rgb;
                    overlap += 1;
                }
            }
        }
        float mask = beforeCount == 0 ? 1 : afterCount == 0 ? 0 : sourceSelection(point);
        int offset = (cell.y * cols + cell.x) * 2;
        queries[offset] = vec4(difference, overlap) / (tile.w * tile.w);
        queries[offset + 1] = vec4(mask, 0, 0, 0);
        return;
    }
    int level = job.z;
    vec4 shape = blendShape(level);
    ivec2 point = ivec2(int(index) % int(shape.x), int(index) / int(shape.x));
    int offset = int(shape.z) + int(index) * 2;
    if (job.x == 71) {
        const float kernel[5] = float[](1, 4, 6, 4, 1);
        vec4 before = vec4(0), after = vec4(0);
        for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
            float weight = kernel[x + 2] * kernel[y + 2] / 256;
            before += blendGaussian(point * 2 + ivec2(x, y), level - 1, 0) * weight;
            after += blendGaussian(point * 2 + ivec2(x, y), level - 1, 1) * weight;
        }
        queries[offset] = before; queries[offset + 1] = after;
        return;
    }
    vec4 difference = queries[offset];
    float mask = queries[offset + 1].x;
    if (level == int(p[descriptor].w) - 1) {
        result[offset] = level == 0 ? vec4(0) : vec4(mask * difference.rgb / max(1e-6, difference.a), 0);
        result[offset + 1] = vec4(0);
        return;
    }
    vec3 low = blendExpand(vec2(point), level + 1, 0, false);
    vec3 merged = blendExpand(vec2(point), level + 1, 0, true);
    if (level == 0) {
        // Keep fine detail from one frame and blend the broader color differences.
        result[offset] = vec4(merged, 0);
        result[offset + 1] = vec4(merged - low, 0);
    } else result[offset] = vec4(merged + mask * (difference.rgb / max(1e-6, difference.a) - low), 0);
#endif
}
