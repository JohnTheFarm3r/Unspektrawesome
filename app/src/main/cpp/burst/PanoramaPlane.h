#pragma once

#include "PanoramaAlignment.h"

namespace panorama {
using PlaneMatrix = std::array<double, 9>;

inline PlaneMatrix planeMultiply(const PlaneMatrix& a, const PlaneMatrix& b) {
    PlaneMatrix result{};
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col)
        for (int k = 0; k < 3; ++k) result[row * 3 + col] += a[row * 3 + k] * b[k * 3 + col];
    return result;
}

inline bool refinePlaneMotion(const std::vector<FlowMatch>& matches, const std::vector<int>& indices,
    int width, int height, double focal, PlaneMatrix& matrix) {
    const double cx = width * .5, cy = height * .5;
    const PlaneMatrix k{focal, 0, cx, 0, focal, cy, 0, 0, 1}, inverse{1 / focal, 0, -cx / focal, 0, 1 / focal, -cy / focal, 0, 0, 1};
    const auto initial = planeMultiply(planeMultiply(inverse, matrix), k);
    const auto original = matrix;
    double scale = 0;
    for (double value : initial) scale += value * value / 3;
    if (!std::isfinite(scale) || scale < 1e-8) return false;
    double deformation = 0;
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col) {
        double gram = 0;
        for (int axis = 0; axis < 3; ++axis) gram += initial[axis * 3 + row] * initial[axis * 3 + col] / scale;
        deformation += std::pow(gram - (row == col ? 1 : 0), 2) / 3;
    }
    if (deformation > .18 * .18) return false;
    std::array<double, 3> right{initial[0], initial[3], initial[6]}, down{initial[1], initial[4], initial[7]};
    auto normalize = [](auto& v) {
        const double length = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!std::isfinite(length) || length < 1e-8) return false;
        for (auto& value : v) value /= length;
        return true;
    };
    if (!normalize(right)) return false;
    const double dot = right[0] * down[0] + right[1] * down[1] + right[2] * down[2];
    for (int i = 0; i < 3; ++i) down[i] -= dot * right[i];
    if (!normalize(down)) return false;
    const std::array<double, 3> forward{right[1] * down[2] - right[2] * down[1],
        right[2] * down[0] - right[0] * down[2], right[0] * down[1] - right[1] * down[0]};
    if (forward[0] * initial[2] + forward[1] * initial[5] + forward[2] * initial[8] <= 0) return false;
    PlaneMatrix rotation{right[0], down[0], forward[0], right[1], down[1], forward[1], right[2], down[2], forward[2]};
    for (int iteration = 0; iteration < 15; ++iteration) {
        std::array<std::array<double, 3>, 3> a{};
        Affine b{}, solution{};
        std::array<double, 3> delta{};
        int count = 0;
        for (int index : indices) {
            const auto& match = matches[index];
            const double sx = (match.point.x - cx) / focal, sy = (match.point.y - cy) / focal;
            const double z = rotation[6] * sx + rotation[7] * sy + rotation[8];
            if (z <= .1) continue;
            const double x = (rotation[0] * sx + rotation[1] * sy + rotation[2]) / z;
            const double y = (rotation[3] * sx + rotation[4] * sy + rotation[5]) / z;
            const double residual[] = {match.point.x + match.shift.x - cx - focal * x, match.point.y + match.shift.y - cy - focal * y};
            const double weight = std::min(1., 3 / std::max(1e-8, std::hypot(residual[0], residual[1])));
            const std::array<double, 3> rows[] = {{-focal * x * y, focal * (1 + x * x), -focal * y},
                {-focal * (1 + y * y), focal * x * y, focal * x}};
            for (int axis = 0; axis < 2; ++axis) for (int i = 0; i < 3; ++i) {
                b[i].x += weight * rows[axis][i] * residual[axis];
                for (int j = 0; j < 3; ++j) a[i][j] += weight * rows[axis][i] * rows[axis][j];
            }
            ++count;
        }
        if (count < 12 || !solve(a, b, solution)) return false;
        for (int i = 0; i < 3; ++i) delta[i] = solution[i].x;
        double angle = std::sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
        if (!std::isfinite(angle)) return false;
        if (angle < 1e-9) break;
        if (angle > .1) { for (auto& value : delta) value *= .1 / angle; angle = .1; }
        const PlaneMatrix skew{0, -delta[2], delta[1], delta[2], 0, -delta[0], -delta[1], delta[0], 0};
        const auto square = planeMultiply(skew, skew);
        PlaneMatrix step{1, 0, 0, 0, 1, 0, 0, 0, 1};
        for (int i = 0; i < 9; ++i) step[i] += std::sin(angle) / angle * skew[i] + (1 - std::cos(angle)) / (angle * angle) * square[i];
        rotation = planeMultiply(step, rotation);
    }
    matrix = planeMultiply(planeMultiply(k, rotation), inverse);
    int supported = 0;
    for (int index : indices) {
        const auto& match = matches[index];
        const double z = matrix[6] * match.point.x + matrix[7] * match.point.y + matrix[8];
        if (z <= 0) continue;
        const double x = (matrix[0] * match.point.x + matrix[1] * match.point.y + matrix[2]) / z;
        const double y = (matrix[3] * match.point.x + matrix[4] * match.point.y + matrix[5]) / z;
        if (std::hypot(x - match.point.x - match.shift.x, y - match.point.y - match.shift.y) < 3.5) ++supported;
    }
    if (supported >= 12 && supported * 5 >= int(indices.size()) * 3) return true;
    // Nearby subjects can require a small projective correction for parallax.
    matrix = original;
    return true;
}

inline Point planePoint(const PlaneMatrix& m, Point p) {
    const double z = m[6] * p.x + m[7] * p.y + m[8];
    if (z <= 1e-8) return {1e20, 1e20};
    return {(m[0] * p.x + m[1] * p.y + m[2]) / z, (m[3] * p.x + m[4] * p.y + m[5]) / z};
}

inline bool fitPlane(const std::vector<FlowMatch>& matches, const std::vector<int>& indices, int width, int height, PlaneMatrix& matrix) {
    if (indices.size() < 4) return false;
    const double scale = std::max(width, height), cx = width * .5, cy = height * .5;
    std::array<std::array<double, 9>, 8> a{};
    for (int index : indices) {
        const auto& match = matches[index];
        const Point p{(match.point.x - cx) / scale, (match.point.y - cy) / scale};
        const Point q = p + match.shift * (1 / scale);
        const std::array<double, 8> rows[] = {{p.x, p.y, 1, 0, 0, 0, -q.x * p.x, -q.x * p.y},
            {0, 0, 0, p.x, p.y, 1, -q.y * p.x, -q.y * p.y}};
        const double values[] = {q.x, q.y};
        for (int axis = 0; axis < 2; ++axis) for (int i = 0; i < 8; ++i) {
            a[i][8] += rows[axis][i] * values[axis];
            for (int j = 0; j < 8; ++j) a[i][j] += rows[axis][i] * rows[axis][j];
        }
    }
    for (int col = 0; col < 8; ++col) {
        int pivot = col;
        for (int row = col + 1; row < 8; ++row) if (std::abs(a[row][col]) > std::abs(a[pivot][col])) pivot = row;
        if (std::abs(a[pivot][col]) < 1e-10) return false;
        std::swap(a[col], a[pivot]);
        const double divisor = a[col][col];
        for (int j = col; j < 9; ++j) a[col][j] /= divisor;
        for (int row = 0; row < 8; ++row) if (row != col) {
            const double factor = a[row][col];
            for (int j = col; j < 9; ++j) a[row][j] -= factor * a[col][j];
        }
    }
    const double h[] = {a[0][8], a[1][8], a[2][8], a[3][8], a[4][8], a[5][8], a[6][8], a[7][8]};
    matrix = {h[0] + cx * h[6] / scale, h[1] + cx * h[7] / scale,
        scale * h[2] + cx - (h[0] + cx * h[6] / scale) * cx - (h[1] + cx * h[7] / scale) * cy,
        h[3] + cy * h[6] / scale, h[4] + cy * h[7] / scale,
        scale * h[5] + cy - (h[3] + cy * h[6] / scale) * cx - (h[4] + cy * h[7] / scale) * cy,
        h[6] / scale, h[7] / scale, 1 - (h[6] * cx + h[7] * cy) / scale};
    return std::all_of(matrix.begin(), matrix.end(), [](double value) { return std::isfinite(value); });
}

inline std::vector<int> planeInliers(const std::vector<FlowMatch>& matches, const PlaneMatrix& matrix) {
    std::vector<int> indices;
    for (size_t i = 0; i < matches.size(); ++i) {
        const auto error = planePoint(matrix, matches[i].point) - matches[i].point - matches[i].shift;
        if (std::hypot(error.x, error.y) < 2.2) indices.push_back(int(i));
    }
    return indices;
}

inline bool alignPlane(const std::vector<FlowMatch>& matches, int width, int height, double focal, PlaneMatrix& matrix, std::vector<int>& best) {
    if (matches.size() < 12) return false;
    std::mt19937 random(61);
    for (int iteration = 0; iteration < 1200; ++iteration) {
        std::vector<int> sample;
        while (sample.size() < 4) {
            const int index = int(random() % matches.size());
            if (std::find(sample.begin(), sample.end(), index) == sample.end()) sample.push_back(index);
        }
        PlaneMatrix candidate;
        if (!fitPlane(matches, sample, width, height, candidate)) continue;
        auto inliers = planeInliers(matches, candidate);
        if (inliers.size() > best.size()) best = std::move(inliers);
    }
    if (best.size() < 16 || best.size() * 4 < matches.size() || !fitPlane(matches, best, width, height, matrix)) return false;
    best = planeInliers(matches, matrix);
    if (best.size() < 16 || !fitPlane(matches, best, width, height, matrix)) return false;
    double left = width, right = 0, top = height, bottom = 0;
    for (int i : best) {
        left = std::min(left, matches[i].point.x); right = std::max(right, matches[i].point.x);
        top = std::min(top, matches[i].point.y); bottom = std::max(bottom, matches[i].point.y);
    }
    if (right - left < width * .18 || bottom - top < height * .18) return false;
    if (!refinePlaneMotion(matches, best, width, height, focal, matrix)) return false;
    const auto center = planePoint(matrix, {width * .5, height * .5});
    const auto horizontal = planePoint(matrix, {width * .5 + 1, height * .5}) - center;
    const auto vertical = planePoint(matrix, {width * .5, height * .5 + 1}) - center;
    return std::hypot(horizontal.x, horizontal.y) > .7 && std::hypot(horizontal.x, horizontal.y) < 1.4 &&
        std::hypot(vertical.x, vertical.y) > .7 && std::hypot(vertical.x, vertical.y) < 1.4 &&
        horizontal.x * vertical.y - horizontal.y * vertical.x > .4;
}
}
