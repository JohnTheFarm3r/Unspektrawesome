#pragma once

#include <algorithm>
#include <cstdint>
#include <queue>
#include <vector>

namespace panorama {
class SeamCut {
    struct Edge { int to, next, capacity; };
    std::vector<Edge> edges;
    std::vector<int> heads, height, cursor;
    int source, sink;

    bool labels() {
        std::fill(height.begin(), height.end(), -1);
        std::queue<int> pending;
        height[source] = 0; pending.push(source);
        while (!pending.empty()) {
            const int node = pending.front(); pending.pop();
            for (int e = heads[node]; e >= 0; e = edges[e].next) {
                const int next = edges[e].to;
                if (edges[e].capacity <= 0 || height[next] >= 0) continue;
                height[next] = height[node] + 1; pending.push(next);
            }
        }
        cursor = heads;
        return height[sink] >= 0;
    }
    int send(int node, int flow) {
        if (node == sink) return flow;
        for (int& e = cursor[node]; e >= 0; e = edges[e].next) {
            auto& edge = edges[e];
            if (edge.capacity <= 0 || height[edge.to] != height[node] + 1) continue;
            const int amount = send(edge.to, std::min(flow, edge.capacity));
            if (!amount) continue;
            edge.capacity -= amount; edges[e ^ 1].capacity += amount;
            return amount;
        }
        return 0;
    }
public:
    explicit SeamCut(int nodes) : heads(nodes + 2, -1), height(nodes + 2), cursor(nodes + 2),
        source(nodes), sink(nodes + 1) { edges.reserve(size_t(nodes) * 10); }
    void edge(int a, int b, float capacity, float reverse = 0) {
        edges.push_back({b, heads[a], int(capacity * 16)}); heads[a] = int(edges.size()) - 1;
        edges.push_back({a, heads[b], int(reverse * 16)}); heads[b] = int(edges.size()) - 1;
    }
    void keep(int node, bool old) { edge(old ? source : node, old ? node : sink, 1000000.f); }
    std::vector<uint8_t> solve() {
        while (labels()) while (send(source, 1000000000)) {}
        std::vector<uint8_t> old(heads.size());
        std::queue<int> pending;
        old[source] = 1; pending.push(source);
        while (!pending.empty()) {
            const int node = pending.front(); pending.pop();
            for (int e = heads[node]; e >= 0; e = edges[e].next) {
                const auto& edge = edges[e];
                if (edge.capacity <= 0 || old[edge.to]) continue;
                old[edge.to] = 1; pending.push(edge.to);
            }
        }
        old.resize(source);
        return old;
    }
};

inline std::vector<uint8_t> panelSeam(const std::vector<uint8_t>& coverage, const std::vector<float>& costs, int cols, int rows) {
    const int count = cols * rows;
    SeamCut cut(count);
    for (int y = 0; y < rows; ++y) for (int x = 0; x < cols; ++x) {
        const int i = y * cols + x;
        if (coverage[i] == 1) { cut.keep(i, true); continue; }
        if (coverage[i] != 3) { cut.keep(i, false); continue; }
        if (x == 0 || x == cols - 1 || y == 0 || y == rows - 1) cut.keep(i, true);
    }
    for (int y = 0; y < rows; ++y) for (int x = 0; x < cols; ++x) {
        const int i = y * cols + x;
        for (int j : {x + 1 < cols ? i + 1 : -1, y + 1 < rows ? i + cols : -1}) {
            if (j < 0 || !coverage[i] || !coverage[j] || (coverage[i] != 3 && coverage[j] != 3)) continue;
            const float cost = 1 + (std::min(costs[i], 255.f) + std::min(costs[j], 255.f)) * .5f;
            cut.edge(i, j, cost, cost);
        }
    }
    return cut.solve();
}
}
