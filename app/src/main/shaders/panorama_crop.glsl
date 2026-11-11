int cropSmaller(int row, int x, float height, bool left) {
    int node = tile.x + x, offset = row * tile.x * 2;
    while (node > 1) {
        bool sibling = left ? (node & 1) != 0 : (node & 1) == 0;
        int other = node + (left ? -1 : 1);
        if (sibling && result[offset + other].x < height) {
            node = other;
            while (node < tile.x) {
                int first = node * 2 + (left ? 1 : 0);
                node = result[offset + first].x < height ? first : first + (left ? -1 : 1);
            }
            return node - tile.x;
        }
        node /= 2;
    }
    return left ? -1 : job.z;
}
bool cropJob(uint index) {
    if (job.x < 40 || job.x > 43) return false;
    if (job.x == 40) {
        uint height = uint(p[index].x);
        if (index == 0) { pixels[tile.y] = 0; pixels[tile.y + 1] = 0xffffffff; }
        for (int row = 0; row < job.w; ++row) {
            height = (pixels[row * job.z + index] >> 24) != 0 ? height + 1 : 0;
            int leaf = row * tile.x * 2 + tile.x + int(index);
            result[leaf].x = float(height);
            if (int(index) + job.z < tile.x) result[leaf + job.z].x = 1e20;
        }
        p[index].x = float(height); return true;
    }
    if (job.x == 41) {
        int row = int(index) / job.w, node = job.w + int(index) % job.w, offset = row * tile.x * 2;
        result[offset + node].x = min(result[offset + node * 2].x, result[offset + node * 2 + 1].x); return true;
    }
    int row = int(index) / job.z, x = int(index) % job.z;
    if (job.x == 42) {
        float height = result[row * tile.x * 2 + tile.x + x].x;
        int left = cropSmaller(row, x, height, true), right = cropSmaller(row, x, height, false);
        queries[index] = vec4(height, left, right, 0);
        uint area = uint(right - left - 1) * uint(height);
        atomicMax(pixels[tile.y], area); return true;
    }
    vec4 rectangle = queries[index];
    uint area = uint(rectangle.z - rectangle.y - 1) * uint(rectangle.x);
    if (area == pixels[tile.y]) atomicMin(pixels[tile.y + 1], index);
    return true;
}
