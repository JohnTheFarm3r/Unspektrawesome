shared float sweepPrevious[1024];
shared float sweepNext[1024];
float sweepCost(int index) { return queries[index / 4][index % 4]; }
void sweepJob() {
    uint lane = gl_LocalInvocationID.x;
    int cols = int(p[0].x), rows = int(p[0].y), low = int(p[0].z), high = int(p[0].w);
    bool fine = p[1].y > .5;
    for (int col = int(lane); col < cols; col += 64) sweepPrevious[col] = col >= low && col <= high ? sweepCost(col) : 1e20;
    barrier();
    for (int row = 1; row < rows; ++row) {
        for (int col = int(lane); col < cols; col += 64) {
            sweepNext[col] = 1e20;
            if (col < low || col > high) continue;
            int winner = col;
            if (!fine) {
                if (col - 1 >= low && sweepPrevious[col - 1] + 2 < sweepPrevious[winner]) winner = col - 1;
                if (col + 1 <= high && sweepPrevious[col + 1] + 2 < sweepPrevious[winner]) winner = col + 1;
                sweepNext[col] = sweepPrevious[winner] + sweepCost(row * cols + col);
            } else {
                int center = int(p[2 + row].x) + col - int(p[1 + row].x);
                float best = 1e20;
                for (int delta = -1; delta <= 1; ++delta) {
                    int candidate = center + delta;
                    if (candidate < low || candidate > high) continue;
                    float score = sweepPrevious[candidate] + abs(delta) * .5;
                    if (score < best) { best = score; winner = candidate; }
                }
                sweepNext[col] = best + sweepCost(row * cols + col);
            }
            pixels[row * cols + col] = uint(winner);
        }
        barrier();
        for (int col = int(lane); col < cols; col += 64) sweepPrevious[col] = sweepNext[col];
        barrier();
    }
    if (lane == 0) {
        int winner = low;
        for (int col = low; col <= high; ++col) if (sweepPrevious[col] < sweepPrevious[winner]) winner = col;
        for (int row = rows - 1; row >= 0; --row) {
            pixels[rows * cols + row] = uint(int(p[2 + row].x) + winner * int(p[1].x));
            if (row > 0) winner = int(pixels[row * cols + winner]);
        }
    }
}
