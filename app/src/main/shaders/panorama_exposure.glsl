shared uint exposureBins[16];
shared uint exposurePrefix;
shared uint exposureRank;
shared float exposureSums[768];

float exposureResidual(int sampleIndex, int channel, bool radial) {
    vec4 source = queries[sampleIndex * 3], reference = queries[sampleIndex * 3 + 1];
    float radius = source.w * source.w + reference.w * reference.w;
    return reference[channel] - source[channel] * (p[1][channel] + (radial ? p[3][channel] * radius : 0)) - p[2][channel];
}
float exposureValue(int sampleIndex, int channel, int kind, int node) {
    if (kind == 0) return queries[sampleIndex * 3 + 1][channel] - queries[sampleIndex * 3][channel];
    float value = exposureResidual(sampleIndex, channel, kind >= 3);
    if (kind == 2 || kind == 4) return abs(value - p[4][channel]);
    if (kind == 6) return abs(value - p[12 + node * 4][channel]);
    return value;
}
void exposureMedian(uint groupIndex) {
    int kind = job.z, channel = int(groupIndex % 3), node = int(groupIndex / 3);
    bool localField = kind >= 5, calibrated = p[0].w >= 64;
    uint count = localField ? pixels[node] : uint(calibrated ? p[0].w : p[0].z);
    if (localField && count < 16) return;
    uint lane = gl_LocalInvocationID.x;
    if (lane == 0) { exposurePrefix = 0; exposureRank = count / 2; }
    barrier();
    for (int shift = 28; shift >= 0; shift -= 4) {
        if (lane < 16) exposureBins[lane] = 0;
        barrier();
        uint mask = shift == 28 ? 0 : 0xffffffff << (shift + 4);
        uint size = localField ? count : uint(p[0].z);
        for (uint i = lane; i < size; i += 64) {
            uint sampleIndex = localField ? pixels[uint(p[7].z) * 3 + pixels[uint(p[7].z) + node] + i] : i;
            if (!localField && calibrated && queries[sampleIndex * 3 + 2].z < .5) continue;
            float value = exposureValue(int(sampleIndex), channel, kind, node);
            uint bits = floatBitsToUint(value), key = (bits & 0x80000000) != 0 ? ~bits : bits ^ 0x80000000;
            if ((key & mask) == exposurePrefix) atomicAdd(exposureBins[(key >> shift) & 15], 1);
        }
        barrier();
        if (lane == 0) {
            uint rank = exposureRank;
            for (uint bin = 0; bin < 16; ++bin) {
                if (rank < exposureBins[bin]) { exposurePrefix |= bin << shift; exposureRank = rank; break; }
                rank -= exposureBins[bin];
            }
        }
        barrier();
    }
    if (lane == 0) {
        uint bits = (exposurePrefix & 0x80000000) != 0 ? exposurePrefix ^ 0x80000000 : ~exposurePrefix;
        float value = uintBitsToFloat(bits);
        if (kind == 0) p[2][channel] = clamp(value, -40, 40);
        else if (kind == 1 || kind == 3) p[4][channel] = value;
        else if (kind == 2 || kind == 4) p[5][channel] = value;
        else if (kind == 5) p[12 + node * 4][channel] = value;
        else p[13 + node * 4][channel] = value;
    }
}
void exposureRegression(uint groupIndex) {
    int channel = int(groupIndex); uint lane = gl_LocalInvocationID.x;
    bool radial = job.z != 0;
    float moments[12]; for (int i = 0; i < 12; ++i) moments[i] = 0;
    float threshold = max(radial ? 1.5 : 2, p[5][channel] * 2.5);
    for (uint i = lane; i < uint(p[0].z); i += 64) {
        if (queries[i * 3 + 2].z < .5) continue;
        vec4 source = queries[i * 3], reference = queries[i * 3 + 1];
        float weight = min(1, threshold / max(.001, abs(exposureResidual(int(i), channel, radial) - p[4][channel])));
        float a = source[channel] / 128, b = reference[channel] / 128;
        if (!radial) {
            moments[0] += weight; moments[1] += weight * a; moments[2] += weight * b;
            moments[3] += weight * a * a; moments[4] += weight * a * b;
        } else {
            vec3 terms = vec3(a, a * (source.w * source.w + reference.w * reference.w), 1);
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 3; ++col) moments[row * 4 + col] += weight * terms[row] * terms[col];
                moments[row * 4 + 3] += weight * terms[row] * b;
            }
        }
    }
    for (int i = 0; i < 12; ++i) exposureSums[i * 64 + lane] = moments[i];
    barrier();
    for (uint distance = 32; distance > 0; distance /= 2) {
        if (lane < distance) for (int i = 0; i < 12; ++i) exposureSums[i * 64 + lane] += exposureSums[i * 64 + lane + distance];
        barrier();
    }
    if (lane != 0) return;
    if (!radial) {
        float weight = exposureSums[0], a = exposureSums[64], b = exposureSums[128];
        float variance = exposureSums[192] - a * a / weight;
        if (variance / weight < 36.0 / 16384) return;
        float gain = clamp((exposureSums[256] - a * b / weight) / variance, .65, 1.55);
        p[1][channel] = gain; p[2][channel] = clamp((b - gain * a) / weight * 128, -40, 40); return;
    }
    vec4 equations[3];
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 4; ++col) equations[row][col] = exposureSums[(row * 4 + col) * 64];
        float penalty = p[0].w * (row == 1 ? .001 : .0001);
        equations[row][row] += penalty;
        equations[row][3] += penalty * (row == 0 ? p[9][channel] : row == 2 ? p[10][channel] / 128 : 0);
    }
    for (int col = 0; col < 3; ++col) {
        int pivot = col;
        for (int row = col + 1; row < 3; ++row) if (abs(equations[row][col]) > abs(equations[pivot][col])) pivot = row;
        if (abs(equations[pivot][col]) < 1e-9) return;
        vec4 swapRow = equations[col]; equations[col] = equations[pivot]; equations[pivot] = swapRow;
        equations[col] /= equations[col][col];
        for (int row = 0; row < 3; ++row) if (row != col) equations[row] -= equations[col] * equations[row][col];
    }
    vec3 model = vec3(equations[0][3], equations[1][3], equations[2][3]);
    if (model.x < .65 || model.x > 1.55 || abs(model.z * 128) > 24 || abs(model.y) > .4) return;
    float edge = model.x + model.y * .5;
    if (min(model.x, edge) < .55 || max(model.x, edge) > 1.8) return;
    p[1][channel] = model.x; p[3][channel] = model.y; p[2][channel] = model.z * 128;
}
bool exposureJob(uint index) {
    if (job.x < 48 || job.x > 56) return false;
    uint groupIndex = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
    int nodes = int(p[7].z);
    if (job.x == 48) { pixels[index] = 0; pixels[nodes + index] = 0; pixels[nodes * 2 + index] = 0; return true; }
    if (job.x == 49 || job.x == 51) {
        vec2 point = queries[index * 3 + 2].xy; ivec2 center = ivec2(floor(point / p[6].zw));
        for (int y = -1; y <= 2; ++y) for (int x = -1; x <= 2; ++x) {
            ivec2 cell = center + ivec2(x, y);
            if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(p[6].xy)))) continue;
            vec2 distance = (point - vec2(cell) * p[6].zw) / p[7].x;
            if (dot(distance, distance) > 1.21) continue;
            int node = cell.y * int(p[6].x) + cell.x;
            if (job.x == 49) atomicAdd(pixels[node], 1);
            else { uint slot = atomicAdd(pixels[nodes * 2 + node], 1); pixels[uint(nodes) * 3 + pixels[nodes + node] + slot] = index; }
        }
        return true;
    }
    if (job.x == 50) { uint offset = 0; for (int i = 0; i < nodes; ++i) { pixels[nodes + i] = offset; offset += pixels[i]; } return true; }
    if (job.x == 52) { exposureMedian(groupIndex); return true; }
    if (job.x == 53) { exposureRegression(groupIndex); return true; }
    if (job.x == 54) { p[9][index] = p[1][index]; p[10][index] = p[2][index]; return true; }
    int base = 12 + int(index) * 4;
    if (job.x == 55) {
        bool valid = pixels[index] >= 16 && all(lessThanEqual(p[base + 1].rgb, vec3(10))) && all(lessThanEqual(abs(p[base].rgb), vec3(48)));
        p[base].w = valid ? min(40, pixels[index] * .25) : 0;
        p[base + 2] = valid ? vec4(p[base].rgb, 0) : vec4(0); return true;
    }
    vec3 sum = p[base].rgb * p[base].w; float weight = p[base].w;
    ivec2 cell = ivec2(int(index) % int(p[6].x), int(index) / int(p[6].x));
    ivec2 offsets[4] = ivec2[](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    for (int i = 0; i < 4; ++i) {
        ivec2 neighbor = cell + offsets[i];
        if (any(lessThan(neighbor, ivec2(0))) || any(greaterThanEqual(neighbor, ivec2(p[6].xy)))) continue;
        int other = 12 + (neighbor.y * int(p[6].x) + neighbor.x) * 4;
        sum += p[other + job.w].rgb * 2; weight += 2;
    }
    p[base + tile.x] = vec4(sum / max(1, weight), 0); return true;
}
