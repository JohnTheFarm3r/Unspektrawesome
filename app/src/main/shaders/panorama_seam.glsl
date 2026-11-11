const uint SEAM_TERMINAL = 16000000;
int seamNeighbor(int node, int direction) {
    int x = node % job.z, y = node / job.z;
    if (direction == 0) return x > 0 ? node - 1 : -1;
    if (direction == 1) return x + 1 < job.z ? node + 1 : -1;
    if (direction == 2) return y > 0 ? node - job.z : -1;
    return y + 1 < job.w ? node + job.z : -1;
}
bool seamOld(int node) {
    int coverage = int(queries[node].y);
    int x = node % job.z, y = node / job.z;
    return coverage == 1 || coverage == 3 && (x == 0 || y == 0 || x == job.z - 1 || y == job.w - 1);
}
uint seamHeight(int node) { return atomicAdd(pixels[node * 8 + 7], 0); }
bool seamJob(uint index) {
    if (job.x < 30 || job.x > 38) return false;
    uint base = index * 8, activeCounter = uint(job.y) * 8, changed = activeCounter + 1;
    uint unreachable = uint(job.y) + 1;
    if (job.x == 30) {
        int coverage = int(queries[index].y);
        for (int d = 0; d < 4; ++d) {
            int other = seamNeighbor(int(index), d);
            uint capacity = 0;
            if (other >= 0 && coverage != 0 && queries[other].y != 0 && (coverage == 3 || queries[other].y == 3))
                capacity = uint((1 + (min(queries[index].x, 255) + min(queries[other].x, 255)) * .5) * 16);
            pixels[base + d] = capacity;
        }
        bool old = seamOld(int(index));
        pixels[base + 4] = !old && coverage != 3 ? SEAM_TERMINAL : 0;
        pixels[base + 5] = old ? SEAM_TERMINAL : 0;
        pixels[base + 6] = old ? SEAM_TERMINAL : 0;
        pixels[base + 7] = unreachable;
        return true;
    }
    if (job.x == 31) {
        pixels[base + 7] = pixels[base + 4] > 0 ? 1 : pixels[base + 5] > 0 ? unreachable : uint(job.y) * 2 + 1;
        return true;
    }
    if (job.x == 32) {
        if (index == 0) pixels[changed] = 0;
        uint height = pixels[base + 7];
        for (int d = 0; d < 4; ++d) {
            int other = seamNeighbor(int(index), d);
            if (other >= 0 && pixels[base + d] > 0) height = min(height, pixels[other * 8 + 7] + 1);
        }
        result[index].x = float(height); return true;
    }
    if (job.x == 33) {
        uint height = uint(result[index].x);
        if (height != pixels[base + 7]) atomicAdd(pixels[changed], 1);
        pixels[base + 7] = height; return true;
    }
    if (job.x == 34) {
        if (index == 0) pixels[activeCounter] = 0;
        uint excess = atomicAdd(pixels[base + 6], 0), available = excess, height = seamHeight(int(index));
        if (height == 1) {
            uint amount = min(available, pixels[base + 4]);
            pixels[base + 4] -= amount; available -= amount;
        }
        for (int d = 0; d < 4 && available > 0; ++d) {
            int other = seamNeighbor(int(index), d);
            if (other < 0 || height != seamHeight(other) + 1) continue;
            uint amount = min(available, atomicAdd(pixels[base + d], 0));
            atomicAdd(pixels[base + d], 0u - amount); atomicAdd(pixels[other * 8 + (d ^ 1)], amount);
            atomicAdd(pixels[other * 8 + 6], amount); available -= amount;
        }
        if (height == unreachable) {
            uint amount = min(available, pixels[base + 5]); pixels[base + 5] -= amount; available -= amount;
        }
        atomicAdd(pixels[base + 6], 0u - (excess - available)); return true;
    }
    if (job.x == 35) {
        if (atomicAdd(pixels[base + 6], 0) == 0) return true;
        atomicAdd(pixels[activeCounter], 1);
        uint height = pixels[base + 4] > 0 ? 1 : uint(job.y) * 2 + 1;
        if (pixels[base + 5] > 0) height = min(height, unreachable);
        for (int d = 0; d < 4; ++d) {
            int other = seamNeighbor(int(index), d);
            if (other >= 0 && atomicAdd(pixels[base + d], 0) > 0) height = min(height, seamHeight(other) + 1);
        }
        atomicExchange(pixels[base + 7], height); return true;
    }
    if (job.x == 36) {
        result[index].x = seamOld(int(index)) && pixels[base + 5] < SEAM_TERMINAL ? 1 : 0; return true;
    }
    if (job.x == 37) {
        if (index == 0) pixels[changed] = 0;
        float label = result[index].x;
        for (int d = 0; d < 4; ++d) {
            int other = seamNeighbor(int(index), d);
            if (other >= 0 && pixels[other * 8 + (d ^ 1)] > 0) label = max(label, result[other].x);
        }
        result[index].y = label; return true;
    }
    if (result[index].x != result[index].y) atomicAdd(pixels[changed], 1);
    result[index].x = result[index].y; return true;
}
