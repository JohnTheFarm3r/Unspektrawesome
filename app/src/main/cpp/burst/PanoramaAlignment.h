#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <random>
#include <utility>
#include <vector>

namespace panorama {
struct Point {
    double x = 0, y = 0;
    Point operator+(Point p) const { return {x + p.x, y + p.y}; }
    Point operator-(Point p) const { return {x - p.x, y - p.y}; }
    Point operator*(double scale) const { return {x * scale, y * scale}; }
};
struct Gray {
    int w, h;
    std::vector<float> data;
    float get(int x, int y) const { return data[size_t(y) * w + x]; }
    double sample(double x, double y) const {
        if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return -1;
        const int ix = int(x), iy = int(y);
        const double a = get(ix, iy), b = get(ix + 1, iy), c = get(ix, iy + 1), d = get(ix + 1, iy + 1);
        if (std::min({a, b, c, d}) < 0) return -1;
        return (a + (b - a) * (x - ix)) * (1 - y + iy) + (c + (d - c) * (x - ix)) * (y - iy);
    }
};
inline std::vector<Gray> pyramid(const Gray& image, int levels) {
    std::vector<Gray> result{image};
    while (int(result.size()) < levels && result.back().w >= 48 && result.back().h >= 48) {
        const auto& previous = result.back();
        Gray next{previous.w / 2, previous.h / 2, {}};
        next.data.resize(size_t(next.w) * next.h, -1);
        for (int y = 0; y < next.h; ++y) for (int x = 0; x < next.w; ++x) {
            double sum = 0;
            bool valid = true;
            for (int j = 0; j < 2; ++j) for (int i = 0; i < 2; ++i) {
                const double value = previous.get(x * 2 + i, y * 2 + j);
                valid &= value >= 0;
                sum += value;
            }
            if (valid) next.data[size_t(y) * next.w + x] = float(sum * .25);
        }
        result.push_back(std::move(next));
    }
    return result;
}
inline double corner(const Gray& image, int x, int y) {
    double xx = 0, yy = 0, xy = 0;
    for (int j = -2; j <= 2; ++j) for (int i = -2; i <= 2; ++i) {
        const double left = image.get(x + i - 1, y + j), right = image.get(x + i + 1, y + j);
        const double top = image.get(x + i, y + j - 1), bottom = image.get(x + i, y + j + 1);
        if (std::min({left, right, top, bottom}) < 0) return 0;
        const double gx = (right - left) * .5, gy = (bottom - top) * .5;
        xx += gx * gx; yy += gy * gy; xy += gx * gy;
    }
    return .5 * (xx + yy - std::hypot(xx - yy, 2 * xy));
}
inline double patchCorrelation(const Gray& source, const Gray& target, Point p, Point q, int radius = 5) {
    double a = 0, b = 0, aa = 0, bb = 0, ab = 0;
    const int count = (2 * radius + 1) * (2 * radius + 1);
    for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
        const double s = source.sample(p.x + x, p.y + y), t = target.sample(q.x + x, q.y + y);
        if (s < 0 || t < 0) return -1;
        a += s; b += t; aa += s * s; bb += t * t; ab += s * t;
    }
    const double va = aa - a * a / count, vb = bb - b * b / count;
    if (va < count * 9 || vb < count * 9) return -1;
    return (ab - a * b / count) / std::sqrt(va * vb);
}
inline bool refinePoint(const Gray& source, const Gray& target, Point p, Point& q) {
    constexpr int radius = 5, count = 121;
    std::array<double, count> pixels{};
    double mean = 0, variance = 0;
    int index = 0;
    for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
        const double value = source.sample(p.x + x, p.y + y);
        if (value < 0) return false;
        pixels[index++] = value; mean += value;
    }
    mean /= count;
    for (auto& value : pixels) { value -= mean; variance += value * value; }
    if (variance < count * 9) return false;
    for (int iteration = 0; iteration < 18; ++iteration) {
        std::array<double, count> values{}, gx{}, gy{};
        double average = 0, averageX = 0, averageY = 0, covariance = 0;
        index = 0;
        for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
            const double xx = q.x + x, yy = q.y + y;
            const double value = target.sample(xx, yy), left = target.sample(xx - 1, yy), right = target.sample(xx + 1, yy);
            const double top = target.sample(xx, yy - 1), bottom = target.sample(xx, yy + 1);
            if (std::min({value, left, right, top, bottom}) < 0) return false;
            values[index] = value; gx[index] = (right - left) * .5; gy[index] = (bottom - top) * .5;
            average += value; averageX += gx[index]; averageY += gy[index]; covariance += pixels[index] * value;
            ++index;
        }
        average /= count; averageX /= count; averageY /= count;
        const double gain = covariance / variance;
        if (gain < .5 || gain > 2) return false;
        double xx = 0, yy = 0, xy = 0, bx = 0, by = 0;
        for (int i = 0; i < count; ++i) {
            const double dx = gx[i] - averageX, dy = gy[i] - averageY;
            const double error = pixels[i] * gain - values[i] + average;
            xx += dx * dx; yy += dy * dy; xy += dx * dy; bx += dx * error; by += dy * error;
        }
        const double determinant = xx * yy - xy * xy;
        if (determinant < 1e-5 || .5 * (xx + yy - std::hypot(xx - yy, 2 * xy)) < count * .1) return false;
        Point delta{(yy * bx - xy * by) / determinant, (xx * by - xy * bx) / determinant};
        const double length = std::hypot(delta.x, delta.y);
        if (length > 2) delta = delta * (2 / length);
        q = q + delta;
        if (length < .015) break;
    }
    return true;
}
inline bool trackPoint(const std::vector<Gray>& source, const std::vector<Gray>& target, Point p, Point& q, double search = 0) {
    const int top = int(std::min(source.size(), target.size())) - 1;
    const Point initial = q;
    for (int level = top; level >= 0; --level) {
        const double scale = double(1 << level);
        const Point from{(p.x + .5) / scale - .5, (p.y + .5) / scale - .5};
        Point to{(q.x + .5) / scale - .5, (q.y + .5) / scale - .5};
        if (level == top && search > 0) {
            double best = -1;
            const Point center = to;
            const int distance = int(std::ceil(search / scale));
            for (int y = -distance; y <= distance; ++y) for (int x = -distance; x <= distance; ++x) {
                const Point candidate{center.x + x, center.y + y};
                const double score = patchCorrelation(source[level], target[level], from, candidate);
                if (score > best) { best = score; to = candidate; }
            }
            if (best < .75) return false;
        }
        if (!refinePoint(source[level], target[level], from, to)) return false;
        q = {(to.x + .5) * scale - .5, (to.y + .5) * scale - .5};
        if (search > 0 && std::hypot(q.x - initial.x, q.y - initial.y) > search * 1.25) return false;
    }
    return patchCorrelation(source.front(), target.front(), p, q) >= .94;
}
struct FlowMatch { Point point, shift; double confidence; };
inline Gray smoothAlignment(const Gray& image) {
    Gray current = image, next = image;
    for (int pass = 0; pass < 2; ++pass) {
        std::fill(next.data.begin(), next.data.end(), -1);
        for (int y = 1; y < image.h - 1; ++y) for (int x = 1; x < image.w - 1; ++x) {
            double sum = 0;
            bool valid = true;
            for (int j = -1; j <= 1; ++j) for (int i = -1; i <= 1; ++i) {
                const double value = current.get(x + i, y + j);
                valid &= value >= 0;
                sum += value * (i == 0 ? 2 : 1) * (j == 0 ? 2 : 1);
            }
            if (valid) next.data[size_t(y) * image.w + x] = float(sum / 16);
        }
        current.data.swap(next.data);
    }
    return current;
}
inline bool refineJoin(const Gray& reference, const Gray& current, Point& shift) {
    constexpr int radius = 9, count = 361;
    const Point center{20, 20};
    Point q = center;
    std::array<double, count> pixels{};
    double mean = 0, variance = 0;
    int index = 0;
    for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
        const double value = reference.sample(center.x + x, center.y + y);
        if (value < 0) return false;
        pixels[index++] = value; mean += value;
    }
    mean /= count;
    for (auto& pixel : pixels) { pixel -= mean; variance += pixel * pixel; }
    if (variance < count * .5) return false;
    auto correlation = [&](Point point) {
        double sum = 0, square = 0, product = 0;
        int i = 0;
        for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
            const double value = current.sample(point.x + x, point.y + y);
            if (value < 0) return -1.;
            sum += value; square += value * value; product += pixels[i++] * value;
        }
        const double v = square - sum * sum / count;
        return v > 1 ? product / std::sqrt(variance * v) : -1.;
    };
    const double before = correlation(q);
    for (int iteration = 0; iteration < 12; ++iteration) {
        std::array<double, count> values{}, gx{}, gy{};
        double sum = 0, sumX = 0, sumY = 0, covariance = 0;
        index = 0;
        for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
            const double xx = q.x + x, yy = q.y + y;
            const double value = current.sample(xx, yy), left = current.sample(xx - 1, yy), right = current.sample(xx + 1, yy);
            const double top = current.sample(xx, yy - 1), bottom = current.sample(xx, yy + 1);
            if (std::min({value, left, right, top, bottom}) < 0) return false;
            values[index] = value; gx[index] = (right - left) * .5; gy[index] = (bottom - top) * .5;
            sum += value; sumX += gx[index]; sumY += gy[index]; covariance += pixels[index] * value; ++index;
        }
        const double gain = covariance / variance;
        if (gain < .8 || gain > 1.25) return false;
        sum /= count; sumX /= count; sumY /= count;
        double xx = 0, yy = 0, xy = 0, bx = 0, by = 0;
        for (int i = 0; i < count; ++i) {
            const double dx = gx[i] - sumX, dy = gy[i] - sumY;
            const double error = pixels[i] * gain - values[i] + sum;
            xx += dx * dx; yy += dy * dy; xy += dx * dy; bx += dx * error; by += dy * error;
        }
        const double largest = .5 * (xx + yy + std::hypot(xx - yy, 2 * xy));
        if (largest < count * .06) return false;
        const double regularization = std::max(1., largest * .01);
        xx += regularization; yy += regularization;
        const double determinant = xx * yy - xy * xy;
        Point delta{(yy * bx - xy * by) / determinant, (xx * by - xy * bx) / determinant};
        const double length = std::hypot(delta.x, delta.y);
        if (length > 1) delta = delta * (1 / length);
        q = q + delta;
        if (std::hypot(q.x - center.x, q.y - center.y) > 3) return false;
        if (length < .02) break;
    }
    const double after = correlation(q);
    if (after < .92 || after < before + .002) return false;
    shift = q - center;
    return true;
}
using Affine = std::array<Point, 3>;
inline bool solve(std::array<std::array<double, 3>, 3> a, Affine b, Affine& out) {
    for (int i = 0; i < 3; ++i) {
        int pivot = i;
        for (int j = i + 1; j < 3; ++j) if (std::abs(a[j][i]) > std::abs(a[pivot][i])) pivot = j;
        if (std::abs(a[pivot][i]) < 1e-9) return false;
        std::swap(a[i], a[pivot]); std::swap(b[i], b[pivot]);
        const double divisor = a[i][i];
        for (int j = i; j < 3; ++j) a[i][j] /= divisor;
        b[i] = b[i] * (1 / divisor);
        for (int row = 0; row < 3; ++row) if (row != i) {
            const double factor = a[row][i];
            for (int col = i; col < 3; ++col) a[row][col] -= factor * a[i][col];
            b[row] = b[row] - b[i] * factor;
        }
    }
    out = b;
    return true;
}
inline Point evaluate(const Affine& model, Point p) { return model[0] + model[1] * p.x + model[2] * p.y; }
inline bool fitFlow(const std::vector<FlowMatch>& matches, const std::vector<int>& indices, Affine& model) {
    std::array<std::array<double, 3>, 3> a{};
    Affine b{};
    for (int index : indices) {
        const auto& m = matches[index];
        const double v[] = {1, m.point.x, m.point.y};
        for (int i = 0; i < 3; ++i) {
            b[i] = b[i] + m.shift * v[i];
            for (int j = 0; j < 3; ++j) a[i][j] += v[i] * v[j];
        }
    }
    return solve(a, b, model);
}
using RotationBasis = std::function<std::array<Point, 4>(Point)>;
inline bool fitRotation(const std::vector<FlowMatch>& matches, const std::vector<int>& indices, const RotationBasis& basis, std::array<double, 4>& result) {
    std::array<std::array<double, 5>, 4> a{};
    for (int index : indices) {
        const auto& match = matches[index];
        const auto values = basis(match.point);
        for (int row = 0; row < 4; ++row) {
            for (int col = 0; col < 4; ++col) a[row][col] += values[row].x * values[col].x + values[row].y * values[col].y;
            a[row][4] += values[row].x * match.shift.x + values[row].y * match.shift.y;
        }
    }
    a[2][2] += .001; a[3][3] += .001;
    for (int col = 0; col < 4; ++col) {
        int pivot = col;
        for (int row = col + 1; row < 4; ++row) if (std::abs(a[row][col]) > std::abs(a[pivot][col])) pivot = row;
        if (std::abs(a[pivot][col]) < 1e-9) return false;
        std::swap(a[col], a[pivot]);
        const double divisor = a[col][col];
        for (int i = col; i < 5; ++i) a[col][i] /= divisor;
        for (int row = 0; row < 4; ++row) if (row != col) {
            const double factor = a[row][col];
            for (int i = col; i < 5; ++i) a[row][i] -= factor * a[col][i];
        }
    }
    for (int i = 0; i < 4; ++i) result[i] = a[i][4];
    return std::abs(result[2]) <= 5 && std::abs(result[3]) <= 5;
}
struct Warp {
    int cols = 0, rows = 0;
    double stepX = 1, stepY = 1;
    std::vector<Point> shifts;
    Point at(double x, double y) const {
        if (shifts.empty()) return {};
        const double u = std::clamp(x / stepX, 0., double(cols - 1)), v = std::clamp(y / stepY, 0., double(rows - 1));
        const int ix = std::min(int(u), cols - 2), iy = std::min(int(v), rows - 2);
        return (shifts[size_t(iy) * cols + ix] * (1 - u + ix) + shifts[size_t(iy) * cols + ix + 1] * (u - ix)) * (1 - v + iy) +
            (shifts[size_t(iy + 1) * cols + ix] * (1 - u + ix) + shifts[size_t(iy + 1) * cols + ix + 1] * (u - ix)) * (v - iy);
    }
};
inline std::vector<FlowMatch> overlapMatches(const Gray& source, const Gray& reference) {
    const auto current = pyramid(source, 4), previous = pyramid(reference, 4);
    std::vector<FlowMatch> result;
    const int cell = std::max(16, std::min(source.w, source.h) / 28);
    const double maximum = std::max(8., std::max(source.w, source.h) * .035);
    for (int cy = 12; cy < source.h - 12; cy += cell) for (int cx = 12; cx < source.w - 12; cx += cell) {
        Point p;
        double strongest = 1;
        for (int y = cy; y < std::min(cy + cell, source.h - 12); y += 3) for (int x = cx; x < std::min(cx + cell, source.w - 12); x += 3) {
            if (reference.get(x, y) < 0) continue;
            const double strength = corner(source, x, y);
            if (strength > strongest) { strongest = strength; p = {double(x), double(y)}; }
        }
        if (strongest == 1) continue;
        Point q = p;
        if (!trackPoint(current, previous, p, q, maximum)) continue;
        Point reverse = p;
        if (!trackPoint(previous, current, q, reverse)) continue;
        if (std::hypot(reverse.x - p.x, reverse.y - p.y) > .35) continue;
        result.push_back({q, p - q, patchCorrelation(source, reference, p, q)});
    }
    return result;
}
inline Warp makeWarp(const std::vector<FlowMatch>& matches, int width, int height, const RotationBasis& basis) {
    if (matches.size() < 16) return {};
    std::mt19937 random(31);
    std::vector<int> best, bestRotation;
    auto rotationAt = [&](const std::array<double, 4>& model, Point p) {
        Point value;
        const auto terms = basis(p);
        for (int i = 0; i < 4; ++i) value = value + terms[i] * model[i];
        return value;
    };
    for (int iteration = 0; iteration < 300; ++iteration) {
        std::vector<int> sample{int(random() % matches.size()), int(random() % matches.size()), int(random() % matches.size())};
        Affine model{};
        if (fitFlow(matches, sample, model) && std::hypot(model[1].x, model[1].y) <= .12 && std::hypot(model[2].x, model[2].y) <= .12) {
            std::vector<int> inliers;
            for (size_t i = 0; i < matches.size(); ++i) {
                const auto error = evaluate(model, matches[i].point) - matches[i].shift;
                if (std::hypot(error.x, error.y) < .65) inliers.push_back(int(i));
            }
            if (inliers.size() > best.size()) best = std::move(inliers);
        }
        std::array<double, 4> rotation{};
        if (fitRotation(matches, sample, basis, rotation)) {
            std::vector<int> inliers;
            for (size_t i = 0; i < matches.size(); ++i) {
                const auto error = rotationAt(rotation, matches[i].point) - matches[i].shift;
                if (std::hypot(error.x, error.y) < .65) inliers.push_back(int(i));
            }
            if (inliers.size() > bestRotation.size()) bestRotation = std::move(inliers);
        }
    }
    std::function<Point(Point)> base;
    std::array<double, 4> rotation{};
    if (bestRotation.size() >= best.size() * .85 && bestRotation.size() >= 12 && fitRotation(matches, bestRotation, basis, rotation)) {
        best = bestRotation;
        base = [rotation, basis](Point p) {
            Point value;
            const auto terms = basis(p);
            for (int i = 0; i < 4; ++i) value = value + terms[i] * rotation[i];
            return value;
        };
    } else {
        Affine global{};
        if (best.size() < 12 || !fitFlow(matches, best, global)) return {};
        base = [global](Point p) { return evaluate(global, p); };
    }
    if (best.size() < 12 || best.size() * 3 < matches.size()) return {};
    double left = width, right = 0, top = height, bottom = 0;
    for (int index : best) {
        const auto p = matches[index].point;
        left = std::min(left, p.x); right = std::max(right, p.x); top = std::min(top, p.y); bottom = std::max(bottom, p.y);
    }
    if (right - left < width * .18 || bottom - top < height * .18) return {};
    // Keep the camera motion as the base, then correct supported local differences.
    const double spacing = std::max(16., std::min(40., std::min(width, height) / 10.));
    const double sigma = spacing * 1.5;
    std::vector<FlowMatch> supported;
    for (const auto& m : matches) {
        const auto residual = m.shift - base(m.point);
        if (std::hypot(residual.x, residual.y) > 8) continue;
        int support = 0;
        for (const auto& n : matches) {
            if (std::hypot(n.point.x - m.point.x, n.point.y - m.point.y) > sigma * 2) continue;
            const auto difference = n.shift - base(n.point) - residual;
            if (std::hypot(difference.x, difference.y) < 1.5) ++support;
        }
        if (support >= 3) supported.push_back(m);
    }
    Warp warp;
    warp.cols = int(std::ceil((width - 1) / spacing)) + 1; warp.rows = int(std::ceil((height - 1) / spacing)) + 1;
    warp.stepX = double(width - 1) / (warp.cols - 1); warp.stepY = double(height - 1) / (warp.rows - 1);
    warp.shifts.resize(size_t(warp.cols) * warp.rows);
    std::vector<Point> residuals(warp.shifts.size());
    for (int row = 0; row < warp.rows; ++row) for (int col = 0; col < warp.cols; ++col) {
        const Point p{col * warp.stepX, row * warp.stepY};
        std::array<std::array<double, 3>, 3> a{{{{.1, 0, 0}}, {{0, .5, 0}}, {{0, 0, .5}}}};
        Affine b{}, local{};
        for (const auto& m : supported) {
            const Point relative = (m.point - p) * (1 / sigma);
            const double distance = relative.x * relative.x + relative.y * relative.y;
            if (distance > 9) continue;
            const double weight = m.confidence / std::pow(.15 + distance, 2);
            const double v[] = {1, relative.x, relative.y};
            const auto residual = m.shift - base(m.point);
            for (int i = 0; i < 3; ++i) {
                b[i] = b[i] + residual * (weight * v[i]);
                for (int j = 0; j < 3; ++j) a[i][j] += weight * v[i] * v[j];
            }
        }
        if (solve(a, b, local)) residuals[size_t(row) * warp.cols + col] = local[0];
    }
    double largestGradient = 0;
    for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
        const size_t i = size_t(y) * warp.cols + x;
        if (x + 1 < warp.cols) {
            const auto d = residuals[i + 1] - residuals[i];
            largestGradient = std::max(largestGradient, std::hypot(d.x, d.y) / warp.stepX);
        }
        if (y + 1 < warp.rows) {
            const auto d = residuals[i + warp.cols] - residuals[i];
            largestGradient = std::max(largestGradient, std::hypot(d.x, d.y) / warp.stepY);
        }
    }
    const double damping = largestGradient > .1 ? .1 / largestGradient : 1;
    const double maximum = std::max(width, height) * .05;
    for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
        const size_t i = size_t(y) * warp.cols + x;
        auto shift = base({x * warp.stepX, y * warp.stepY}) + residuals[i] * damping;
        const double length = std::hypot(shift.x, shift.y);
        warp.shifts[i] = length > maximum ? shift * (maximum / length) : shift;
    }
    return warp;
}
inline Warp projectiveWarp(const std::vector<FlowMatch>& matches, int width, int height) {
    if (matches.size() < 10) return {};
    const double scale = std::max(width, height);
    auto normalized = [&](Point p) { return Point{(p.x - width * .5) / scale, (p.y - height * .5) / scale}; };
    using Model = std::array<double, 8>;
    auto fit = [&](const std::vector<int>& indices, Model& model) {
        std::array<std::array<double, 9>, 8> a{};
        for (int index : indices) {
            const Point p = normalized(matches[index].point), q = p + matches[index].shift * (1 / scale);
            const std::array<double, 8> rows[] = {{p.x, p.y, 1, 0, 0, 0, -q.x * p.x, -q.x * p.y},
                {0, 0, 0, p.x, p.y, 1, -q.y * p.x, -q.y * p.y}};
            const double values[] = {q.x - p.x, q.y - p.y};
            for (int axis = 0; axis < 2; ++axis) for (int i = 0; i < 8; ++i) {
                a[i][8] += rows[axis][i] * values[axis];
                for (int j = 0; j < 8; ++j) a[i][j] += rows[axis][i] * rows[axis][j];
            }
        }
        for (int i = 0; i < 8; ++i) a[i][i] += i >= 6 ? 1e-7 : 1e-9;
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
        for (int i = 0; i < 8; ++i) model[i] = a[i][8];
        return std::abs(model[0]) < .15 && std::abs(model[1]) < .15 && std::abs(model[3]) < .15 &&
            std::abs(model[4]) < .15 && std::abs(model[6]) < .2 && std::abs(model[7]) < .2;
    };
    auto displacement = [&](const Model& m, Point point) {
        const auto p = normalized(point);
        const double z = 1 + m[6] * p.x + m[7] * p.y;
        return Point{((1 + m[0]) * p.x + m[1] * p.y + m[2]) / z - p.x,
            (m[3] * p.x + (1 + m[4]) * p.y + m[5]) / z - p.y} * scale;
    };
    std::mt19937 random(47);
    std::vector<int> best;
    for (int iteration = 0; iteration < 300; ++iteration) {
        std::vector<int> indices;
        while (indices.size() < 4) {
            const int i = int(random() % matches.size());
            if (std::find(indices.begin(), indices.end(), i) == indices.end()) indices.push_back(i);
        }
        Model model{};
        if (!fit(indices, model)) continue;
        std::vector<int> inliers;
        for (size_t i = 0; i < matches.size(); ++i) {
            const auto error = displacement(model, matches[i].point) - matches[i].shift;
            if (std::hypot(error.x, error.y) < .65) inliers.push_back(int(i));
        }
        if (inliers.size() > best.size()) best = std::move(inliers);
    }
    Model model{};
    if (best.size() < 10 || best.size() * 3 < matches.size() || !fit(best, model)) return {};
    double left = width, right = 0, top = height, bottom = 0;
    for (int i : best) {
        left = std::min(left, matches[i].point.x); right = std::max(right, matches[i].point.x);
        top = std::min(top, matches[i].point.y); bottom = std::max(bottom, matches[i].point.y);
    }
    if (right - left < width * .12 || bottom - top < height * .12) return {};
    Warp warp;
    warp.cols = int(std::ceil(width / 24.)) + 1; warp.rows = int(std::ceil(height / 24.)) + 1;
    warp.stepX = double(width - 1) / (warp.cols - 1); warp.stepY = double(height - 1) / (warp.rows - 1);
    for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
        const auto shift = displacement(model, {x * warp.stepX, y * warp.stepY});
        if (std::hypot(shift.x, shift.y) > scale * .08) return {};
        warp.shifts.push_back(shift);
    }
    return warp;
}
inline Warp refineOverlap(const Gray& source, const Gray& reference, const Warp& initial, const std::vector<FlowMatch>& matches) {
    const auto current = pyramid(source, 4), previous = pyramid(reference, 4);
    const double spacing = std::clamp(std::min(source.w, source.h) / 16., 16., 32.);
    Warp warp;
    warp.cols = int(std::ceil((source.w - 1) / spacing)) + 1;
    warp.rows = int(std::ceil((source.h - 1) / spacing)) + 1;
    warp.stepX = double(source.w - 1) / (warp.cols - 1);
    warp.stepY = double(source.h - 1) / (warp.rows - 1);
    warp.shifts.resize(size_t(warp.cols) * warp.rows);
    for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x)
        warp.shifts[size_t(y) * warp.cols + x] = initial.at(x * warp.stepX, y * warp.stepY);
    const auto base = warp.shifts;
    std::vector<Point> next = base;
    std::vector<double> support(base.size());
    std::vector<Point> anchors(base.size());
    std::vector<double> anchorWeights(base.size());
    for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
        const size_t index = size_t(y) * warp.cols + x;
        const Point p{x * warp.stepX, y * warp.stepY};
        for (const auto& match : matches) {
            const Point residual = match.shift - initial.at(match.point.x, match.point.y);
            if (match.confidence < .97 || std::hypot(residual.x, residual.y) > 1.2) continue;
            const double distance = std::hypot(match.point.x - p.x, match.point.y - p.y) / spacing;
            if (distance > 2.5) continue;
            const double weight = std::exp(-distance * distance * .5);
            anchors[index] = anchors[index] + residual * weight;
            anchorWeights[index] += weight;
        }
        if (anchorWeights[index] > 0) anchors[index] = anchors[index] * (1 / anchorWeights[index]);
    }
    for (int level = int(current.size()) - 1; level >= 0; --level) {
        const double scale = double(1 << level);
        const int radius = std::clamp(int(spacing / scale), 4, 12);
        const int stride = std::max(1, radius / 4);
        for (int iteration = 0; iteration < 12; ++iteration) {
            for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
                const size_t index = size_t(y) * warp.cols + x;
                const Point p{(x * warp.stepX + .5) / scale - .5, (y * warp.stepY + .5) / scale - .5};
                const Point shift = warp.shifts[index] * (1 / scale);
                struct Pixel { double value, old, gx, gy; };
                std::vector<Pixel> pixels;
                pixels.reserve(81);
                double a = 0, b = 0, aa = 0, ab = 0, gx = 0, gy = 0;
                for (int j = -radius; j <= radius; j += stride) for (int i = -radius; i <= radius; i += stride) {
                    const double xx = p.x + i + shift.x, yy = p.y + j + shift.y;
                    const double value = current[level].sample(xx, yy), old = previous[level].sample(p.x + i, p.y + j);
                    const double left = current[level].sample(xx - 1, yy), right = current[level].sample(xx + 1, yy);
                    const double top = current[level].sample(xx, yy - 1), bottom = current[level].sample(xx, yy + 1);
                    if (std::min({value, old, left, right, top, bottom}) < 0) continue;
                    const Pixel pixel{value, old, (right - left) * .5, (bottom - top) * .5};
                    pixels.push_back(pixel);
                    a += value; b += old; aa += old * old; ab += old * value; gx += pixel.gx; gy += pixel.gy;
                }
                const double count = double(pixels.size());
                double xx = 0, yy = 0, xy = 0, bx = 0, by = 0;
                if (count >= 30) {
                    const double variance = aa - b * b / count;
                    const double gain = variance > count ? std::clamp((ab - a * b / count) / variance, .8, 1.25) : 1;
                    a /= count; b /= count; gx /= count; gy /= count;
                    for (const auto& pixel : pixels) {
                        const double error = (pixel.old - b) * gain - (pixel.value - a);
                        const double weight = std::min(1., 4. / std::max(.01, std::abs(error)));
                        const double dx = pixel.gx - gx, dy = pixel.gy - gy;
                        xx += weight * dx * dx; yy += weight * dy * dy; xy += weight * dx * dy;
                        bx += weight * dx * error; by += weight * dy * error;
                    }
                }
                support[index] = xx + yy;
                Point neighbor;
                int neighbors = 0;
                for (const auto& offset : {std::pair{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    const int nx = x + offset.first, ny = y + offset.second;
                    if (nx < 0 || ny < 0 || nx >= warp.cols || ny >= warp.rows) continue;
                    const size_t n = size_t(ny) * warp.cols + nx;
                    neighbor = neighbor + warp.shifts[n] - base[n]; ++neighbors;
                }
                const Point prior = base[index] + neighbor * (.97 / std::max(1, neighbors));
                const double regularization = std::max(3., (xx + yy) * .035);
                const Point deltaPrior = (prior - warp.shifts[index]) * (1 / scale);
                xx += regularization; yy += regularization;
                bx += regularization * deltaPrior.x; by += regularization * deltaPrior.y;
                const double anchoring = regularization * std::min(16., anchorWeights[index] * 8);
                const Point anchor = (base[index] + anchors[index] - warp.shifts[index]) * (1 / scale);
                xx += anchoring; yy += anchoring; bx += anchoring * anchor.x; by += anchoring * anchor.y;
                const double determinant = xx * yy - xy * xy;
                Point delta{(yy * bx - xy * by) / determinant, (xx * by - xy * bx) / determinant};
                const double length = std::hypot(delta.x, delta.y);
                if (length > 1.5) delta = delta * (1.5 / length);
                Point residual = warp.shifts[index] + delta * (scale * .7) - base[index];
                const double maximum = std::max(source.w, source.h) * .035;
                const double distance = std::hypot(residual.x, residual.y);
                if (distance > maximum) residual = residual * (maximum / distance);
                next[index] = base[index] + residual;
            }
            warp.shifts.swap(next);
        }
    }
    // Unsupported areas follow the established camera motion, rather than noise.
    for (int iteration = 0; iteration < 24; ++iteration) {
        for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
            const size_t index = size_t(y) * warp.cols + x;
            if (support[index] >= 12) { next[index] = warp.shifts[index]; continue; }
            Point residual;
            int count = 0;
            for (const auto& offset : {std::pair{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                const int nx = x + offset.first, ny = y + offset.second;
                if (nx < 0 || ny < 0 || nx >= warp.cols || ny >= warp.rows) continue;
                const size_t n = size_t(ny) * warp.cols + nx;
                residual = residual + warp.shifts[n] - base[n]; ++count;
            }
            next[index] = base[index] + residual * (.98 / std::max(1, count));
        }
        warp.shifts.swap(next);
    }
    const double limit = initial.shifts.empty() ? .06 : .025;
    for (int iteration = 0; iteration < 48; ++iteration) {
        for (int y = 0; y < warp.rows; ++y) for (int x = 0; x < warp.cols; ++x) {
            const size_t i = size_t(y) * warp.cols + x;
            const Point residual = warp.shifts[i] - base[i];
            Point correction;
            for (const auto& offset : {std::pair{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                const int nx = x + offset.first, ny = y + offset.second;
                if (nx < 0 || ny < 0 || nx >= warp.cols || ny >= warp.rows) continue;
                const size_t j = size_t(ny) * warp.cols + nx;
                const Point difference = warp.shifts[j] - base[j] - residual;
                const double length = std::hypot(difference.x, difference.y);
                const double maximum = limit * (offset.first ? warp.stepX : warp.stepY);
                if (length > maximum) correction = correction + difference * ((1 - maximum / length) * .24);
            }
            next[i] = warp.shifts[i] + correction * (1 / (1 + anchorWeights[i]));
        }
        warp.shifts.swap(next);
    }
    return warp;
}
}
