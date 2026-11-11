#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <utility>
#include <vector>

namespace panorama {
struct ColorMatch {
    double x, y, u, v;
    std::array<double, 3> source, reference;
    bool aligned;
};
inline double median(std::vector<double> values) {
    if (values.empty()) return 0;
    auto middle = values.begin() + values.size() / 2;
    std::nth_element(values.begin(), middle, values.end());
    return *middle;
}
struct ExposureField {
    int cols = 0, rows = 0;
    double stepX = 1, stepY = 1;
    std::array<double, 3> gain{1, 1, 1}, bias{};
    std::array<double, 3> shading{};
    std::vector<std::array<double, 3>> offsets;

    bool valid() const {
        for (int c = 0; c < 3; ++c)
            if (!std::isfinite(gain[c]) || gain[c] < .649 || gain[c] > 1.551 ||
                !std::isfinite(bias[c]) || std::abs(bias[c]) > 40.01 ||
                !std::isfinite(shading[c]) || std::abs(shading[c]) > .401) return false;
        for (const auto& offset : offsets) for (double value : offset)
            if (!std::isfinite(value) || std::abs(value) > 48.01) return false;
        return true;
    }

    double scale(int c, double u, double v) const {
        return gain[c] + shading[c] * (u * u + v * v);
    }
    std::array<double, 3> apply(std::array<double, 3> rgb, double x, double y, double u, double v) const {
        std::array<double, 3> local{};
        if (!offsets.empty()) {
            const double u = std::clamp(x / stepX, 0., double(cols - 1)), v = std::clamp(y / stepY, 0., double(rows - 1));
            const int ix = std::min(int(u), cols - 2), iy = std::min(int(v), rows - 2);
            for (int c = 0; c < 3; ++c) {
                local[c] = (offsets[size_t(iy) * cols + ix][c] * (1 - u + ix) + offsets[size_t(iy) * cols + ix + 1][c] * (u - ix)) * (1 - v + iy) +
                    (offsets[size_t(iy + 1) * cols + ix][c] * (1 - u + ix) + offsets[size_t(iy + 1) * cols + ix + 1][c] * (u - ix)) * (v - iy);
            }
        }
        for (int c = 0; c < 3; ++c) {
            const double protection = std::clamp(rgb[c] / 8., 0., 1.) * std::clamp((255 - rgb[c]) / 8., 0., 1.);
            rgb[c] = std::clamp(rgb[c] + (rgb[c] * (scale(c, u, v) - 1) + bias[c] + local[c]) * protection, 0., 255.);
        }
        return rgb;
    }
};
inline ExposureField matchExposure(const std::vector<ColorMatch>& samples, int width, int height) {
    ExposureField field;
    if (samples.size() < 64) return field;
    std::vector<ColorMatch> calibration;
    for (const auto& sample : samples) if (sample.aligned) calibration.push_back(sample);
    const auto& fitting = calibration.size() >= 64 ? calibration : samples;
    for (int c = 0; c < 3; ++c) {
        std::vector<double> differences;
        for (const auto& sample : fitting) differences.push_back(sample.reference[c] - sample.source[c]);
        field.bias[c] = std::clamp(median(differences), -40., 40.);
        if (calibration.size() < 64) continue;
        for (int iteration = 0; iteration < 6; ++iteration) {
            std::vector<double> residuals;
            for (const auto& sample : fitting) residuals.push_back(sample.reference[c] - sample.source[c] * field.gain[c] - field.bias[c]);
            const double center = median(residuals);
            for (auto& value : residuals) value = std::abs(value - center);
            const double threshold = std::max(2., median(residuals) * 2.5);
            double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (const auto& sample : fitting) {
                const double residual = sample.reference[c] - sample.source[c] * field.gain[c] - field.bias[c] - center;
                const double weight = std::min(1., threshold / std::max(.001, std::abs(residual)));
                const double a = sample.source[c], b = sample.reference[c];
                sw += weight; sx += weight * a; sy += weight * b; sxx += weight * a * a; sxy += weight * a * b;
            }
            const double variance = sxx - sx * sx / sw;
            if (variance / sw < 36) break;
            field.gain[c] = std::clamp((sxy - sx * sy / sw) / variance, .65, 1.55);
            field.bias[c] = std::clamp((sy - field.gain[c] * sx) / sw, -40., 40.);
        }
        std::array<double, 3> model{field.gain[c], 0, field.bias[c] / 128};
        auto predict = [](const std::array<double, 3>& coefficients, const ColorMatch& sample, int channel) {
            const double u = sample.u, v = sample.v;
            return sample.source[channel] * (coefficients[0] + coefficients[1] * (u * u + v * v)) + coefficients[2] * 128;
        };
        for (int iteration = 0; iteration < 5; ++iteration) {
            std::vector<double> residuals;
            for (const auto& sample : fitting) residuals.push_back(sample.reference[c] - predict(model, sample, c));
            const double center = median(residuals);
            for (auto& value : residuals) value = std::abs(value - center);
            const double threshold = std::max(1.5, median(residuals) * 2.5);
            std::array<std::array<double, 4>, 3> equations{};
            const double penalties[] = {1e-4, 1e-3, 1e-4};
            for (int i = 0; i < 3; ++i) {
                equations[i][i] = fitting.size() * penalties[i];
                equations[i][3] = equations[i][i] * (i == 0 ? field.gain[c] : i == 2 ? field.bias[c] / 128 : 0);
            }
            for (const auto& sample : fitting) {
                const double u = sample.u, v = sample.v, value = sample.source[c] / 128;
                const double terms[] = {value, value * (u * u + v * v), 1};
                const double residual = sample.reference[c] - predict(model, sample, c) - center;
                const double weight = std::min(1., threshold / std::max(.001, std::abs(residual)));
                for (int i = 0; i < 3; ++i) {
                    equations[i][3] += weight * terms[i] * sample.reference[c] / 128;
                    for (int j = 0; j < 3; ++j) equations[i][j] += weight * terms[i] * terms[j];
                }
            }
            bool valid = true;
            for (int col = 0; col < 3; ++col) {
                int pivot = col;
                for (int row = col + 1; row < 3; ++row) if (std::abs(equations[row][col]) > std::abs(equations[pivot][col])) pivot = row;
                if (std::abs(equations[pivot][col]) < 1e-9) { valid = false; break; }
                std::swap(equations[col], equations[pivot]);
                const double divisor = equations[col][col];
                for (int j = col; j < 4; ++j) equations[col][j] /= divisor;
                for (int row = 0; row < 3; ++row) if (row != col) {
                    const double factor = equations[row][col];
                    for (int j = col; j < 4; ++j) equations[row][j] -= factor * equations[col][j];
                }
            }
            if (!valid) break;
            std::array<double, 3> next{};
            for (int i = 0; i < 3; ++i) next[i] = equations[i][3];
            if (next[0] < .65 || next[0] > 1.55 || std::abs(next[2] * 128) > 24 || std::abs(next[1]) > .4) break;
            for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
                const double u = x * .25, v = y * .25;
                const double scale = next[0] + next[1] * (u * u + v * v);
                if (scale < .55 || scale > 1.8) valid = false;
            }
            if (!valid) break;
            model = next;
        }
        field.gain[c] = model[0]; field.bias[c] = model[2] * 128; field.shading[c] = model[1];
    }
    const double spacing = std::max(24., std::min(width, height) / 8.);
    field.cols = std::max(2, int(std::ceil((width - 1) / spacing)) + 1);
    field.rows = std::max(2, int(std::ceil((height - 1) / spacing)) + 1);
    field.stepX = double(width - 1) / (field.cols - 1); field.stepY = double(height - 1) / (field.rows - 1);
    field.offsets.resize(size_t(field.cols) * field.rows);
    auto targets = field.offsets, next = field.offsets;
    std::vector<double> weights(field.offsets.size());
    for (int row = 0; row < field.rows; ++row) for (int col = 0; col < field.cols; ++col) {
        std::array<std::vector<double>, 3> values;
        for (const auto& sample : samples) {
            if (std::hypot((sample.x - col * field.stepX) / spacing, (sample.y - row * field.stepY) / spacing) > 1.1) continue;
            for (int c = 0; c < 3; ++c) values[c].push_back(sample.reference[c] - sample.source[c] * field.scale(c, sample.u, sample.v) - field.bias[c]);
        }
        if (values[0].size() < 16) continue;
        const size_t index = size_t(row) * field.cols + col;
        bool valid = true;
        for (int c = 0; c < 3; ++c) {
            const double center = median(values[c]);
            for (auto& value : values[c]) value = std::abs(value - center);
            if (median(values[c]) > 10 || std::abs(center) > 48) valid = false;
            targets[index][c] = center;
        }
        if (valid) {
            weights[index] = std::min(40., values[0].size() * .25);
            field.offsets[index] = targets[index];
        }
    }
    for (int iteration = 0; iteration < 180; ++iteration) {
        for (int y = 0; y < field.rows; ++y) for (int x = 0; x < field.cols; ++x) {
            const size_t i = size_t(y) * field.cols + x;
            auto sum = targets[i];
            for (auto& value : sum) value *= weights[i];
            double weight = weights[i];
            for (const auto& delta : {std::pair{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                const int nx = x + delta.first, ny = y + delta.second;
                if (nx < 0 || ny < 0 || nx >= field.cols || ny >= field.rows) continue;
                for (int c = 0; c < 3; ++c) sum[c] += field.offsets[size_t(ny) * field.cols + nx][c] * 2;
                weight += 2;
            }
            for (int c = 0; c < 3; ++c) next[i][c] = sum[c] / std::max(1., weight);
        }
        field.offsets.swap(next);
    }
    return field;
}
}
