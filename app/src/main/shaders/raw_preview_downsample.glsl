#ifndef UNSPEKTRA_RAW_PREVIEW_DOWNSAMPLE_GLSL
#define UNSPEKTRA_RAW_PREVIEW_DOWNSAMPLE_GLSL

layout(constant_id = 8) const bool previewAreaPrefiltered = false;
layout(constant_id = 9) const bool previewMhcPrefiltered = false;

vec3 previewMhcArea(vec2 center, vec2 footprint)
{
    vec2 lower = center - footprint * 0.5;
    vec2 upper = center + footprint * 0.5;
    ivec2 first = ivec2(floor(lower));
    ivec2 end = ivec2(ceil(upper));
    vec3 sum = vec3(0.0);
    if (rawColor.reserved1.z > 0.5) {
        for (int y = first.y; y < end.y; ++y) for (int x = first.x; x < end.x; ++x) {
            ivec2 position = clampRawCoordinateToParity(ivec2(x, y));
            int level = cfaLevelIndex(position);
            uint limit = uint(ceil(mix(rawParams.blackLevel[level], rawParams.whiteLevel[level], 0.98)));
            if (rawSensorIntegerAt(position) >= limit) previewSensorWarning = 1.0;
        }
    }
    if (all(lessThanEqual(footprint, vec2(3.0)))) {
        vec2 w0 = clamp(vec2(first) + 1.0 - lower, vec2(0.0), vec2(1.0));
        vec2 w1 = clamp(upper - vec2(first) - 1.0, vec2(0.0), vec2(1.0));
        vec2 w2 = clamp(upper - vec2(first) - 2.0, vec2(0.0), vec2(1.0));
        vec2 w3 = clamp(upper - vec2(first) - 3.0, vec2(0.0), vec2(1.0));
        vec2 nearWeight = w0 + w1;
        vec2 farWeight = w2 + w3;
        vec2 scale = 1.0 / vec2(rawParams.dimensions.xy);
        vec2 nearUv = (vec2(first) + 0.5 + w1 / nearWeight) * scale;
        vec2 farUv = (vec2(first) + 2.5 + w3 / max(farWeight, vec2(0.000001))) * scale;
        sum = textureLod(previewRawTexture, nearUv, 0.0).rgb * (nearWeight.x * nearWeight.y);
        if (farWeight.x > 0.0)
            sum += textureLod(previewRawTexture, vec2(farUv.x, nearUv.y), 0.0).rgb * (farWeight.x * nearWeight.y);
        if (farWeight.y > 0.0) {
            sum += textureLod(previewRawTexture, vec2(nearUv.x, farUv.y), 0.0).rgb * (nearWeight.x * farWeight.y);
            if (farWeight.x > 0.0)
                sum += textureLod(previewRawTexture, farUv, 0.0).rgb * (farWeight.x * farWeight.y);
        }
        return sum / (footprint.x * footprint.y);
    }
    // Pair adjacent area weights into one bilinear texture sample.
    for (int y = first.y; y < end.y; y += 2) {
        vec2 py = float(y) + vec2(0.0, 1.0);
        vec2 wy = max(min(py + 1.0, upper.y) - max(py, lower.y), vec2(0.0));
        float sy = wy.x + wy.y;
        for (int x = first.x; x < end.x; x += 2) {
            vec2 px = float(x) + vec2(0.0, 1.0);
            vec2 wx = max(min(px + 1.0, upper.x) - max(px, lower.x), vec2(0.0));
            float sx = wx.x + wx.y;
            vec2 coordinate = (vec2(x, y) + 0.5 + vec2(wx.y / sx, wy.y / sy)) / vec2(rawParams.dimensions.xy);
            sum += textureLod(previewRawTexture, coordinate, 0.0).rgb * (sx * sy);
        }
    }
    return sum / (footprint.x * footprint.y);
}


vec3 previewAreaWeights(float position, float lower, float upper)
{
    vec3 pixels = position + vec3(-1.0, 0.0, 1.0);
    return max(min(pixels + 1.0, upper) - max(pixels, lower), vec3(0.0));
}

float previewAreaFarWeights(float position, float lower, float upper)
{
    vec2 pixels = position + vec2(-2.0, 2.0);
    return dot(max(min(pixels + 1.0, upper) - max(pixels, lower), vec2(0.0)), vec2(1.0));
}

vec3 previewAreaDemosaicScalar(vec2 center, vec2 footprint)
{
    vec2 lower = center - footprint * 0.5;
    vec2 upper = center + footprint * 0.5;
    int radius = useMhcDemosaic ? 2 : 1;
    ivec2 first = ivec2(floor(lower)) - radius;
    ivec2 end = ivec2(ceil(upper)) + radius;
    vec3 sum = vec3(0.0);

    // Fold the linear demosaic kernels into the pixel's area filter. Each RAW
    // sample is read once instead of demosaicing every covered pixel separately.
    for (int y = first.y; y < end.y; ++y) {
        vec3 wy = previewAreaWeights(float(y), lower.y, upper.y);
        float yNear = wy.x + wy.z;
        float yFar = useMhcDemosaic ? previewAreaFarWeights(float(y), lower.y, upper.y) : 0.0;
        for (int x = first.x; x < end.x; ++x) {
            vec3 wx = previewAreaWeights(float(x), lower.x, upper.x);
            float xNear = wx.x + wx.z;
            float centerWeight = wx.y * wy.y;
            float axial = xNear * wy.y + wx.y * yNear;
            float diagonal = xNear * yNear;
            float horizontalFar = useMhcDemosaic ? previewAreaFarWeights(float(x), lower.x, upper.x) * wy.y : 0.0;
            float verticalFar = wx.y * yFar;
            ivec2 position = ivec2(x, y);
            int color = cfaColor(position);
            vec3 weight;
            if (color == CFA_GREEN) {
                float alongRow = useMhcDemosaic ?
                    0.625 * centerWeight - 0.125 * (horizontalFar + diagonal) + 0.0625 * verticalFar : 0.0;
                float alongColumn = useMhcDemosaic ?
                    0.625 * centerWeight - 0.125 * (verticalFar + diagonal) + 0.0625 * horizontalFar : 0.0;
                float green = centerWeight + 0.25 * axial;
                weight = cfaColor(position + ivec2(1, 0)) == CFA_RED ?
                    vec3(alongRow, green, alongColumn) : vec3(alongColumn, green, alongRow);
            } else {
                float own = centerWeight + 0.5 * axial + 0.25 * diagonal;
                float far = horizontalFar + verticalFar;
                float green = useMhcDemosaic ? 0.5 * centerWeight - 0.125 * far : 0.0;
                float opposite = useMhcDemosaic ? 0.75 * centerWeight - 0.1875 * far : 0.0;
                weight = color == CFA_RED ? vec3(own, green, opposite) : vec3(opposite, green, own);
            }
            sum += previewRawSample(position) * weight;
            if (rawColor.reserved1.z > 0.5 && centerWeight > 0.0) {
                int level = cfaLevelIndex(position);
                float limit = ceil(mix(rawParams.blackLevel[level], rawParams.whiteLevel[level], 0.98));
                float sensor = float(rawSensorIntegerAt(clampRawCoordinateToParity(position)));
                previewSensorWarning = max(previewSensorWarning, sensor >= limit ? 1.0 : 0.0);
            }
        }
    }
    return sum / (footprint.x * footprint.y);
}

vec3 previewAreaRow(vec3 samples, vec3 weights, int color, bool redHorizontal)
{
    float center = samples.x * weights.x;
    float axial = samples.y * weights.x + samples.x * weights.y;
    float diagonal = samples.y * weights.y;
    float horizontalFar = samples.z * weights.x;
    float verticalFar = samples.x * weights.z;
    if (color == CFA_GREEN) {
        float green = center + 0.25 * axial;
        float alongRow = useMhcDemosaic ?
            0.625 * center - 0.125 * (horizontalFar + diagonal) + 0.0625 * verticalFar : 0.0;
        float alongColumn = useMhcDemosaic ?
            0.625 * center - 0.125 * (verticalFar + diagonal) + 0.0625 * horizontalFar : 0.0;
        return redHorizontal ? vec3(alongRow, green, alongColumn) : vec3(alongColumn, green, alongRow);
    }
    float own = center + 0.5 * axial + 0.25 * diagonal;
    float green = useMhcDemosaic ? 0.5 * center - 0.125 * (horizontalFar + verticalFar) : 0.0;
    return color == CFA_RED ? vec3(own, green, 1.5 * green) : vec3(1.5 * green, green, own);
}

vec4 previewAreaWeights4(vec4 positions, float lower, float upper)
{
    return max(min(positions + 1.0, upper) - max(positions, lower), vec4(0.0));
}

vec4 previewRawCell(ivec2 p)
{
    // Gather one complete Bayer cell, keeping parity-aware clamping at the edges.
    if (previewUnpacked && all(greaterThanEqual(p, frameGeometry.rawBounds.xy)) &&
        all(lessThan(p + 1, frameGeometry.rawBounds.xy + frameGeometry.rawBounds.zw))) {
        vec2 uv = (vec2(p) + 1.0) / vec2(rawParams.dimensions.xy);
        return round(textureGather(previewRawTexture, uv, 0).wzxy * 65535.0);
    }
    return vec4(rawInteger(p), rawInteger(p + ivec2(1, 0)),
                rawInteger(p + ivec2(0, 1)), rawInteger(p + ivec2(1, 1)));
}

vec3 previewAreaSmall(vec2 lower, vec2 upper, ivec2 first, ivec2 end, vec4 black, vec4 scale)
{
    // A footprint up to two pixels fits eight columns including the MHC border.
    // Keep the horizontal weights outside the row loop.
    vec4 evenPositions = float(first.x) + vec4(0.0, 2.0, 4.0, 6.0);
    vec4 oddPositions = evenPositions + 1.0;
    vec4 evenCenter = previewAreaWeights4(evenPositions, lower.x, upper.x);
    vec4 oddCenter = previewAreaWeights4(oddPositions, lower.x, upper.x);
    vec4 evenNear = previewAreaWeights4(evenPositions - 1.0, lower.x, upper.x) +
                    previewAreaWeights4(evenPositions + 1.0, lower.x, upper.x);
    vec4 oddNear = previewAreaWeights4(oddPositions - 1.0, lower.x, upper.x) +
                   previewAreaWeights4(oddPositions + 1.0, lower.x, upper.x);
    vec4 evenFar = useMhcDemosaic ? previewAreaWeights4(evenPositions - 2.0, lower.x, upper.x) +
        previewAreaWeights4(evenPositions + 2.0, lower.x, upper.x) : vec4(0.0);
    vec4 oddFar = useMhcDemosaic ? previewAreaWeights4(oddPositions - 2.0, lower.x, upper.x) +
        previewAreaWeights4(oddPositions + 2.0, lower.x, upper.x) : vec4(0.0);
    ivec4 levels = ivec4(cfaLevelIndex(ivec2(0, 0)), cfaLevelIndex(ivec2(1, 0)),
                         cfaLevelIndex(ivec2(0, 1)), cfaLevelIndex(ivec2(1, 1)));
    vec4 cellBlack = vec4(black[levels.x], black[levels.y], black[levels.z], black[levels.w]);
    vec4 cellScale = vec4(scale[levels.x], scale[levels.y], scale[levels.z], scale[levels.w]);
    vec4 cellWhite = vec4(rawParams.whiteLevel[levels.x], rawParams.whiteLevel[levels.y],
                          rawParams.whiteLevel[levels.z], rawParams.whiteLevel[levels.w]);
    vec4 cellLimit = ceil(mix(cellBlack, cellWhite, 0.98));
    int topEvenColor = cfaColor(ivec2(0, 0));
    int topOddColor = cfaColor(ivec2(1, 0));
    int bottomEvenColor = cfaColor(ivec2(0, 1));
    int bottomOddColor = cfaColor(ivec2(1, 1));
    vec3 sum = vec3(0.0);
    for (int y = first.y & ~1; y < end.y; y += 2) {
        ivec2 p = ivec2(first.x, y);
        vec4 a = previewRawCell(p);
        vec4 b = previewRawCell(p + ivec2(2, 0));
        vec4 c = previewRawCell(p + ivec2(4, 0));
        vec4 d = previewRawCell(p + ivec2(6, 0));
        vec3 wy = previewAreaWeights(float(y), lower.y, upper.y);
        vec3 nextWy = previewAreaWeights(float(y + 1), lower.y, upper.y);
        if (rawColor.reserved1.z > 0.5) {
            vec4 vertical = vec4(wy.yy, nextWy.yy);
            float clipped = dot(vec4(greaterThanEqual(a, cellLimit)), vertical * vec2(evenCenter.x, oddCenter.x).xyxy) +
                dot(vec4(greaterThanEqual(b, cellLimit)), vertical * vec2(evenCenter.y, oddCenter.y).xyxy) +
                dot(vec4(greaterThanEqual(c, cellLimit)), vertical * vec2(evenCenter.z, oddCenter.z).xyxy) +
                dot(vec4(greaterThanEqual(d, cellLimit)), vertical * vec2(evenCenter.w, oddCenter.w).xyxy);
            previewSensorWarning = max(previewSensorWarning, clipped > 0.0 ? 1.0 : 0.0);
        }
        a = max(a - cellBlack, vec4(0.0)) * cellScale;
        b = max(b - cellBlack, vec4(0.0)) * cellScale;
        c = max(c - cellBlack, vec4(0.0)) * cellScale;
        d = max(d - cellBlack, vec4(0.0)) * cellScale;
        vec4 topEven = vec4(a.x, b.x, c.x, d.x);
        vec4 topOdd = vec4(a.y, b.y, c.y, d.y);
        vec4 bottomEven = vec4(a.z, b.z, c.z, d.z);
        vec4 bottomOdd = vec4(a.w, b.w, c.w, d.w);
        vec3 rowWeights = vec3(wy.y, wy.x + wy.z,
            useMhcDemosaic ? previewAreaFarWeights(float(y), lower.y, upper.y) : 0.0);
        vec3 nextRowWeights = vec3(nextWy.y, nextWy.x + nextWy.z,
            useMhcDemosaic ? previewAreaFarWeights(float(y + 1), lower.y, upper.y) : 0.0);
        sum += previewAreaRow(vec3(dot(topEven, evenCenter), dot(topEven, evenNear), dot(topEven, evenFar)),
            rowWeights, topEvenColor, topOddColor == CFA_RED);
        sum += previewAreaRow(vec3(dot(topOdd, oddCenter), dot(topOdd, oddNear), dot(topOdd, oddFar)),
            rowWeights, topOddColor, topEvenColor == CFA_RED);
        sum += previewAreaRow(vec3(dot(bottomEven, evenCenter), dot(bottomEven, evenNear), dot(bottomEven, evenFar)),
            nextRowWeights, bottomEvenColor, bottomOddColor == CFA_RED);
        sum += previewAreaRow(vec3(dot(bottomOdd, oddCenter), dot(bottomOdd, oddNear), dot(bottomOdd, oddFar)),
            nextRowWeights, bottomOddColor, bottomEvenColor == CFA_RED);
    }
    return sum;
}

vec3 previewAreaDemosaic(vec2 center, vec2 footprint)
{
#if UNSPEKTRA_PREVIEW_SHADING_REUSE
    vec2 lower = center - footprint * 0.5;
    vec2 upper = center + footprint * 0.5;
    int radius = useMhcDemosaic ? 2 : 1;
    ivec2 first = ivec2(floor(lower)) - radius;
    ivec2 end = ivec2(ceil(upper)) + radius;
    first.x &= ~1;
    vec4 black = rawParams.blackLevel;
    vec4 scale = previewShading * rawColor.whiteBalanceExposure.rggb /
                 max(rawParams.whiteLevel - black, vec4(1.0));
    if (previewAreaPrefiltered) {
        int column = int(round((center.x - frameGeometry.sourceCrop.x * float(rawParams.dimensions.x)) / footprint.x - 0.5));
        vec4 gains = previewShading * rawColor.whiteBalanceExposure.rggb;
        vec3 sum = vec3(0.0);
        for (int y = first.y; y < end.y; ++y) {
            int row = clampRawCoordinateToParity(ivec2(0, y)).y;
            vec4 even = texelFetch(previewRawTexture, ivec2(column * 2, row), 0);
            vec4 odd = texelFetch(previewRawTexture, ivec2(column * 2 + 1, row), 0);
            vec3 wy = previewAreaWeights(float(y), lower.y, upper.y);
            vec3 weights = vec3(wy.y, wy.x + wy.z,
                useMhcDemosaic ? previewAreaFarWeights(float(y), lower.y, upper.y) : 0.0);
            int evenColor = cfaColor(ivec2(0, y));
            int oddColor = cfaColor(ivec2(1, y));
            sum += previewAreaRow(even.xyz * gains[cfaLevelIndex(ivec2(0, y))], weights, evenColor, oddColor == CFA_RED);
            sum += previewAreaRow(odd.xyz * gains[cfaLevelIndex(ivec2(1, y))], weights, oddColor, evenColor == CFA_RED);
            if (rawColor.reserved1.z > 0.5 && wy.y > 0.0)
                previewSensorWarning = max(previewSensorWarning, max(even.w, odd.w));
        }
        return sum / footprint.y;
    }
    if (all(lessThanEqual(footprint, vec2(2.0))))
        return previewAreaSmall(lower, upper, first, end, black, scale) / (footprint.x * footprint.y);
    vec3 sum = vec3(0.0);
    // Reuse each horizontal weight across the whole footprint and gather Bayer cells.
    for (int x = first.x; x < end.x; x += 8)
        sum += previewAreaSmall(lower, upper, ivec2(x, first.y), end, black, scale);
    return sum / (footprint.x * footprint.y);
#else
    return previewAreaDemosaicScalar(center, footprint);
#endif
}

#endif
