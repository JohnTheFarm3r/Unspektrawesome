const int recordingTileSize = 24;
// Padding keeps rotated reads from repeatedly hitting the same memory banks.
const int recordingRowStride = 9;
shared vec4 recordingRows[recordingTileSize * recordingRowStride];
shared uint recordingRowClips[recordingTileSize * recordingRowStride];

ivec2 recordingOutputPosition(ivec2 source)
{
    ivec2 size = frameGeometry.transform.zw;
    ivec2 position = source;
    if (frameGeometry.transform.x == 1) position = ivec2(size.x - 1 - source.y, source.x);
    else if (frameGeometry.transform.x == 2) position = size - 1 - source;
    else if (frameGeometry.transform.x == 3) position = ivec2(source.y, size.y - 1 - source.x);
    if (frameGeometry.transform.y != 0) position.x = size.x - 1 - position.x;
    return position;
}

uint recordingClippedChannels(vec4 value, vec4 weight)
{
    return uint(value.r >= 0.98 && weight.r > 0.0) |
        (uint(value.g >= 0.98 && weight.g > 0.0) << 1) |
        (uint(value.b >= 0.98 && weight.b > 0.0) << 2) |
        (uint(value.a >= 0.98 && weight.a > 0.0) << 3);
}

vec4 recordingTile(ivec2 outputPosition, vec2 radius, vec4 offsetX, vec4 offsetY,
                   ivec2 red, ivec2 green, out float clipped)
{
    ivec2 outputFirst = ivec2(gl_WorkGroupID.xy) * 8;
    ivec2 outputLast = outputFirst + 7;
    ivec2 sourceFirst = min(geometryUnrotatePosition(outputFirst), geometryUnrotatePosition(outputLast));
    vec2 corner0 = geometrySourceUv(outputFirst) * vec2(rawParams.dimensions.xy);
    vec2 corner1 = geometrySourceUv(outputLast) * vec2(rawParams.dimensions.xy);
    int firstY = int(floor((min(corner0.y, corner1.y) - radius.y - 1.5) * 0.5)) + 1;
    int endY = int(ceil((max(corner0.y, corner1.y) + radius.y - 0.5) * 0.5));
    int rows = endY - firstY;
    int lane = int(gl_LocalInvocationIndex);
    int column = lane % 8;
    ivec2 pointX = recordingOutputPosition(sourceFirst + ivec2(column, 0));
    float centerX = geometrySourceUv(pointX).x * float(rawParams.dimensions.x);
    int firstX = int(floor((centerX - radius.x - 1.5) * 0.5)) + 1;
    int endX = int(ceil((centerX + radius.x - 0.5) * 0.5));
    // Reuse each horizontal result for the eight output rows.
    for (int row = lane / 8; row < rows; row += 8) {
        vec4 sumX = vec4(0.0);
        vec4 totalX = vec4(0.0);
        uint mask = 0u;
        for (int x = firstX; x < endX; ++x) {
            vec4 weight = max(vec4(0.0), 1.0 - abs(float(x * 2) + offsetX - centerX) / radius.x);
            vec4 value = recordingCell(ivec2(x, firstY + row) * 2, red, green);
            sumX += value * weight;
            totalX += weight;
            if (rawColor.reserved1.z > 0.5) mask |= recordingClippedChannels(value, weight);
        }
        recordingRows[row * recordingRowStride + column] = sumX / max(totalX, vec4(0.000001));
        recordingRowClips[row * recordingRowStride + column] = mask;
    }
    barrier();
    ivec2 local = geometryUnrotatePosition(outputPosition) - sourceFirst;
    float centerY = geometrySourceUv(outputPosition).y * float(rawParams.dimensions.y);
    int startY = int(floor((centerY - radius.y - 1.5) * 0.5)) + 1;
    int stopY = int(ceil((centerY + radius.y - 0.5) * 0.5));
    vec4 sum = vec4(0.0);
    vec4 totalY = vec4(0.0);
    clipped = 0.0;
    for (int y = startY; y < stopY; ++y) {
        int index = (y - firstY) * recordingRowStride + local.x;
        vec4 weight = max(vec4(0.0), 1.0 - abs(float(y * 2) + offsetY - centerY) / radius.y);
        sum += recordingRows[index] * weight;
        totalY += weight;
        if (rawColor.reserved1.z > 0.5) {
            uint mask = recordingRowClips[index];
            vec4 channels = vec4(mask & 1u, (mask >> 1) & 1u, (mask >> 2) & 1u, (mask >> 3) & 1u);
            if (dot(channels, weight) > 0.0) clipped = 1.0;
        }
    }
    return sum / max(totalY, vec4(0.000001));
}
