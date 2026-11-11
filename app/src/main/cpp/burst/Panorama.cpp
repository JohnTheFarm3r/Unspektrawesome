#include "../NativeBindings.h"
#include "../NativeDiagnostics.h"
#include "PanoramaAlignment.h"
#include "PanoramaPlane.h"
#include "PanoramaExposure.h"
#include "PanoramaGpu.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fcntl.h>
#include <random>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {
using panorama::Gray;
using panorama::Point;
void require(bool valid) { if (!valid) throw std::runtime_error("Invalid panorama data"); }
struct Image {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    uint8_t* pixels = nullptr;
    Image(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        require(AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS);
        require(info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 && info.width > 0 && info.height > 0);
        require(AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void**>(&pixels)) == ANDROID_BITMAP_RESULT_SUCCESS);
    }
    ~Image() { if (pixels) AndroidBitmap_unlockPixels(env, bitmap); }
};
struct Mapping {
    int fd = -1;
    uint8_t* pixels = nullptr;
    size_t bytes;
    Mapping(JNIEnv* env, jstring file, int w, int h) {
        require(w > 0 && h > 0 && uint64_t(w) * h <= 2000000000ULL);
        bytes = size_t(w) * h * 4;
        const char* path = env->GetStringUTFChars(file, nullptr);
        require(path != nullptr);
        fd = open(path, O_RDWR | O_CLOEXEC);
        env->ReleaseStringUTFChars(file, path);
        require(fd >= 0);
        struct stat status{};
        if (fstat(fd, &status) != 0 || uint64_t(status.st_size) != bytes) { close(fd); fd = -1; require(false); }
        auto* mapped = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (mapped == MAP_FAILED) { close(fd); fd = -1; throw std::runtime_error("Not enough memory for panorama export"); }
        pixels = static_cast<uint8_t*>(mapped);
        madvise(pixels, bytes, MADV_RANDOM);
    }
    ~Mapping() { if (pixels) munmap(pixels, bytes); if (fd >= 0) close(fd); }
};
struct Transform {
    double a = 1, b = 0, x = 0, y = 0;
    bool grid = false;
    bool plane = false;
    std::array<double, 9> matrix{}, reversed{};
    void setProjective(const double* m, bool planar = false) {
        grid = true;
        plane = planar;
        std::copy(m, m + 9, matrix.begin());
        reversed = {m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3]};
        const double determinant = m[0] * reversed[0] + m[1] * reversed[3] + m[2] * reversed[6];
        require(std::abs(determinant) > 1e-9);
        for (auto& value : reversed) value /= determinant;
        if (determinant < 0) {
            for (auto& value : matrix) value = -value;
            for (auto& value : reversed) value = -value;
        }
    }
    static std::pair<double, double> project(const std::array<double, 9>& m, double px, double py, double pz = 1) {
        const double z = m[6] * px + m[7] * py + m[8] * pz;
        if (z <= 1e-8) return {-1e12, -1e12};
        return {(m[0] * px + m[1] * py + m[2] * pz) / z, (m[3] * px + m[4] * py + m[5] * pz) / z};
    }
    std::pair<double, double> point(double px, double py) const {
        return grid ? project(matrix, px, py) : std::pair{a * px - b * py + x, b * px + a * py + y};
    }
    std::pair<double, double> inverse(double px, double py, double pz = 1) const {
        if (grid) return project(reversed, px, py, pz);
        const double d = a * a + b * b;
        return {(a * (px - x) + b * (py - y)) / d, (-b * (px - x) + a * (py - y)) / d};
    }
    std::pair<double, double> panelPoint(double px, double py, double width, double height, double focal) const {
        if (plane) return project(matrix, px, py);
        const double z = matrix[6] * px + matrix[7] * py + matrix[8];
        const double x = matrix[0] * px + matrix[1] * py + matrix[2] - width * .5 * z;
        const double y = matrix[3] * px + matrix[4] * py + matrix[5] - height * .5 * z;
        const double scale = 2 * focal / (std::sqrt(x * x + y * y + focal * focal * z * z) + focal * z);
        return {width * .5 + x * scale, height * .5 + y * scale};
    }
};
Gray gray(const Image& image, double focal, bool rectilinear = false) {
    panorama::GpuFrame gpu(image.pixels, image.info.width, image.info.height, image.info.stride);
    return Gray{int(image.info.width), int(image.info.height), gpu.trackingGray(focal, rectilinear)};
}
struct Feature { double x, y, strength; std::array<float, 128> descriptor{}; };
std::vector<Feature> features(const Gray& image) {
    auto scores = panorama::gpuFeatureScores(image.data.data(), image.w, image.h);
    std::vector<Feature> candidates;
    for (const auto& value : scores) if (value[0] > 60) candidates.push_back({value[1], value[2], value[0], {}});
    std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) { return a.strength > b.strength; });
    std::vector<Feature> result;
    std::vector<panorama::Float4> points;
    const int cells = (image.w + 7) / 8;
    std::vector<uint8_t> occupied(size_t(cells) * ((image.h + 7) / 8), 0);
    for (const auto& feature : candidates) {
        const int cx = int(feature.x) / 8, cy = int(feature.y) / 8;
        if (occupied[size_t(cy) * cells + cx]) continue;
        occupied[size_t(cy) * cells + cx] = 1;
        result.push_back(feature); points.push_back({float(feature.x), float(feature.y), 0, 0});
        if (result.size() >= 700) break;
    }
    const auto descriptors = panorama::gpuDescribe(image.data.data(), image.w, image.h, points);
    for (size_t i = 0; i < result.size(); ++i) std::copy_n(descriptors.data() + i * 128, 128, result[i].descriptor.begin());
    return result;
}
struct Match { Point from, to; };
bool translation(const Gray& previous, const Gray& current, Transform& transform) {
    struct Peak { double score, x, y; };
    panorama::GpuFrame gpu;
    auto evaluate = [&](std::vector<Peak>& peaks, int spacing) {
        std::vector<panorama::Float4> offsets;
        for (const auto& peak : peaks) offsets.push_back({float(peak.x), float(peak.y), float(spacing), 0});
        const auto scores = gpu.correlations(current, previous, offsets);
        for (size_t i = 0; i < peaks.size(); ++i) peaks[i].score = scores[i][0];
    };
    std::vector<Peak> peaks;
    for (int y = -current.h / 2; y <= current.h / 2; y += 8) for (int x = -current.w / 2; x <= current.w / 2; x += 8)
        peaks.push_back({0, double(x), double(y)});
    evaluate(peaks, 12);
    std::sort(peaks.begin(), peaks.end(), [](auto a, auto b) { return a.score > b.score; });
    if (peaks.empty() || peaks.front().score < .65) return false;
    std::vector<Peak> selected;
    for (const auto& peak : peaks) {
        if (std::any_of(selected.begin(), selected.end(), [&](const auto& other) { return std::hypot(peak.x - other.x, peak.y - other.y) < 24; })) continue;
        selected.push_back(peak);
        if (selected.size() == 12) break;
    }
    const int radii[] = {4, 2, 2, 2}, spacing[] = {8, 6, 6, 6};
    const double steps[] = {2, 1, .25, .05};
    for (int pass = 0; pass < 4 && !selected.empty(); ++pass) {
        std::vector<Peak> candidates;
        const int radius = radii[pass], count = (radius * 2 + 1) * (radius * 2 + 1);
        for (const auto& center : selected) for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x)
            candidates.push_back({0, center.x + x * steps[pass], center.y + y * steps[pass]});
        evaluate(candidates, spacing[pass]);
        for (size_t i = 0; i < selected.size(); ++i) {
            auto first = candidates.begin() + i * count;
            selected[i] = *std::max_element(first, first + count, [](auto a, auto b) { return a.score < b.score; });
        }
        if (pass == 1) selected.erase(std::remove_if(selected.begin(), selected.end(), [](const auto& peak) { return peak.score < .94; }), selected.end());
    }
    if (selected.empty()) return false;
    evaluate(selected, 5);
    std::sort(selected.begin(), selected.end(), [](auto a, auto b) { return a.score > b.score; });
    if (selected.front().score < .96) return false;
    const auto best = selected.front();
    for (const auto& other : selected) if (std::hypot(other.x - best.x, other.y - best.y) > 12 && other.score > best.score - .01) return false;
    transform = {1, 0, best.x, best.y}; return true;
}
std::vector<Match> matches(const std::vector<Feature>& previous, const std::vector<Feature>& current) {
    if (previous.empty() || current.empty()) return {};
    std::vector<float> before, after;
    before.reserve(previous.size() * 128); after.reserve(current.size() * 128);
    for (const auto& feature : previous) before.insert(before.end(), feature.descriptor.begin(), feature.descriptor.end());
    for (const auto& feature : current) after.insert(after.end(), feature.descriptor.begin(), feature.descriptor.end());
    const auto nearest = panorama::gpuMatch(before, after);
    std::vector<Match> result;
    for (size_t i = 0; i < current.size(); ++i) {
        const auto& match = nearest[i];
        const int j = int(match[0]);
        if (j < 0 || match[1] >= match[2] * .64 || match[1] >= .65) continue;
        const auto& reverse = nearest[current.size() + j];
        if (int(reverse[0]) == int(i) && reverse[1] < reverse[2] * .64)
            result.push_back({{current[i].x, current[i].y}, {previous[j].x, previous[j].y}});
    }
    return result;
}
Transform fit(const std::vector<Match>& points) {
    double fx = 0, fy = 0, tx = 0, ty = 0;
    for (auto& p : points) { fx += p.from.x; fy += p.from.y; tx += p.to.x; ty += p.to.y; }
    fx /= points.size(); fy /= points.size(); tx /= points.size(); ty /= points.size();
    double dot = 0, cross = 0, length = 0;
    for (auto& p : points) {
        const double x = p.from.x - fx, y = p.from.y - fy;
        dot += x * (p.to.x - tx) + y * (p.to.y - ty);
        cross += x * (p.to.y - ty) - y * (p.to.x - tx);
        length += x * x + y * y;
    }
    const double a = dot / std::max(length, 1e-9), b = cross / std::max(length, 1e-9);
    return {a, b, tx - a * fx + b * fy, ty - b * fx - a * fy};
}
std::vector<Match> inliers(const std::vector<Match>& points, const Transform& transform) {
    std::vector<Match> result;
    for (auto& p : points) {
        auto xy = transform.point(p.from.x, p.from.y);
        if (std::hypot(xy.first - p.to.x, xy.second - p.to.y) < 2.2) result.push_back(p);
    }
    return result;
}
jdoubleArray Java_com_unspektrawesome_panorama_PanoramaNative_align(JNIEnv* env, jobject, jobject before, jobject after, jdouble focal, jboolean plane) {
    try {
        Image previous(env, before), current(env, after);
        require(previous.info.width == current.info.width && previous.info.height == current.info.height && focal > 1 && std::isfinite(focal));
        require(previous.info.width <= 768 && previous.info.height <= 768);
        const auto previousGray = gray(previous, focal), currentGray = gray(current, focal);
        if (plane) for (bool rectilinear : {false, true}) {
            const auto rawPrevious = rectilinear ? gray(previous, focal, true) : Gray{};
            const auto rawCurrent = rectilinear ? gray(current, focal, true) : Gray{};
            const auto& reference = rectilinear ? rawPrevious : previousGray;
            const auto& source = rectilinear ? rawCurrent : currentGray;
            const auto points = matches(features(reference), features(source));
            auto toRaw = [&](Point p) {
                if (rectilinear) return p;
                const double angle = (p.x - current.info.width * .5) / focal;
                return Point{focal * std::tan(angle) + current.info.width * .5,
                    (p.y - current.info.height * .5) / std::cos(angle) + current.info.height * .5};
            };
            std::vector<panorama::FlowMatch> flow;
            for (const auto& match : points) {
                const auto from = toRaw(match.from), to = toRaw(match.to);
                flow.push_back({from, to - from});
            }
            panorama::PlaneMatrix matrix;
            std::vector<int> best;
            if (!panorama::alignPlane(flow, current.info.width, current.info.height, focal, matrix, best)) continue;
            std::vector<panorama::Float4> coordinates;
            for (int i : best) coordinates.push_back({float(points[i].from.x), float(points[i].from.y), float(points[i].to.x), float(points[i].to.y)});
            std::vector<panorama::Float4> tracked;
            { panorama::GpuFrame gpu; tracked = gpu.refinePoints(source, reference, coordinates); }
            std::vector<panorama::FlowMatch> refined;
            for (const auto& match : tracked) {
                const auto from = toRaw({match[0], match[1]}), to = toRaw({match[2], match[3]});
                refined.push_back({from, to - from});
            }
            std::vector<int> indices;
            for (size_t i = 0; i < refined.size(); ++i) indices.push_back(int(i));
            panorama::PlaneMatrix fitted;
            if (refined.size() >= 12 && panorama::fitPlane(refined, indices, current.info.width, current.info.height, fitted) &&
                panorama::refinePlaneMotion(refined, indices, current.info.width, current.info.height, focal, fitted)) matrix = fitted;
            auto result = env->NewDoubleArray(9);
            if (result) env->SetDoubleArrayRegion(result, 0, 9, matrix.data());
            return result;
        }
        if (plane) return nullptr;
        auto resultFor = [&](const Transform& transform, double count, double residual) {
            const double values[] = {transform.a, transform.b, transform.x, transform.y, count, residual};
            auto result = env->NewDoubleArray(6);
            if (result) env->SetDoubleArrayRegion(result, 0, 6, values);
            return result;
        };
        auto fallback = [&]() -> jdoubleArray {
            Transform transform;
            return translation(previousGray, currentGray, transform) ? resultFor(transform, 0, 0) : nullptr;
        };
        const auto previousFeatures = features(previousGray), currentFeatures = features(currentGray);
        const auto points = matches(previousFeatures, currentFeatures);
        if (points.size() < 12) return fallback();
        std::mt19937 random(17);
        std::vector<Match> best;
        std::vector<std::pair<Transform, size_t>> hypotheses;
        for (int iteration = 0; iteration < 1200; ++iteration) {
            const auto& p = points[random() % points.size()]; const auto& q = points[random() % points.size()];
            if (std::hypot(p.from.x - q.from.x, p.from.y - q.from.y) < 40) continue;
            const auto transform = fit({p, q});
            if (std::hypot(transform.a, transform.b) < .96 || std::hypot(transform.a, transform.b) > 1.04 || std::abs(transform.b) > .09) continue;
            auto accepted = inliers(points, transform);
            if (accepted.size() >= 12 && accepted.size() * 3 >= points.size()) hypotheses.emplace_back(transform, accepted.size());
            if (accepted.size() > best.size()) best = std::move(accepted);
        }
        if (best.size() < 12 || best.size() * 3 < points.size()) return fallback();
        auto transform = fit(best);
        const auto center = transform.point(current.info.width * .5, current.info.height * .5);
        for (const auto& hypothesis : hypotheses) {
            if (hypothesis.second < best.size() * .7) continue;
            const auto other = hypothesis.first.point(current.info.width * .5, current.info.height * .5);
            if (std::hypot(other.first - center.first, other.second - center.second) > 12) return fallback();
        }
        best = inliers(points, transform);
        if (best.size() < 12) return fallback();
        std::vector<panorama::Float4> coordinates;
        for (const auto& match : best) coordinates.push_back({float(match.from.x), float(match.from.y), float(match.to.x), float(match.to.y)});
        std::vector<panorama::Float4> tracked;
        { panorama::GpuFrame gpu; tracked = gpu.refinePoints(currentGray, previousGray, coordinates); }
        std::vector<Match> refined;
        for (const auto& match : tracked) refined.push_back({{match[0], match[1]}, {match[2], match[3]}});
        if (refined.size() >= 12) { best = std::move(refined); transform = fit(best); }
        double minX = 1e20, maxX = -1e20, minY = 1e20, maxY = -1e20, residual = 0;
        for (auto& p : best) {
            minX = std::min(minX, p.from.x); maxX = std::max(maxX, p.from.x);
            minY = std::min(minY, p.from.y); maxY = std::max(maxY, p.from.y);
            auto xy = transform.point(p.from.x, p.from.y);
            residual += std::pow(xy.first - p.to.x, 2) + std::pow(xy.second - p.to.y, 2);
        }
        if (maxX - minX < current.info.width * .18 || maxY - minY < current.info.height * .18) return fallback();
        return resultFor(transform, double(best.size()), std::sqrt(residual / best.size()));
    } catch (...) { env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to save panorama"); return nullptr; }
}
struct OverlapWarp {
    panorama::Warp mesh;
    panorama::ExposureField exposure;
    double left = 0, top = 0, step = 1;
};
struct JoinWarp {
    bool horizontal = true;
    double start = 0, step = 1, radius = 64;
    std::vector<Point> shifts;
    std::vector<double> cuts;
};
std::vector<panorama::Float4> rasterParameters(const Image& source, int width, int height, double focal,
    const Transform& transform, int originX, int originY, const OverlapWarp& warp = {}, const JoinWarp& join = {}) {
    auto f = [](double a, double b = 0, double c = 0, double d = 0) -> panorama::Float4 { return {float(a), float(b), float(c), float(d)}; };
    std::vector<panorama::Float4> p(20);
    p[0] = f(source.info.width, source.info.height, focal, transform.plane ? 2 : transform.grid ? 1 : 0);
    p[1] = f(width, height, originX, originY); p[2] = f(transform.a, transform.b, transform.x, transform.y);
    for (int row = 0; row < 3; ++row) p[3 + row] = f(transform.reversed[row * 3], transform.reversed[row * 3 + 1], transform.reversed[row * 3 + 2]);
    p[6] = f(warp.left, warp.top, warp.step);
    p[7] = f(warp.mesh.cols, warp.mesh.rows, warp.mesh.stepX, warp.mesh.stepY);
    p[8] = f(warp.exposure.cols, warp.exposure.rows, warp.exposure.stepX, warp.exposure.stepY);
    p[9] = f(warp.exposure.gain[0], warp.exposure.gain[1], warp.exposure.gain[2]);
    p[10] = f(warp.exposure.bias[0], warp.exposure.bias[1], warp.exposure.bias[2]);
    p[11] = f(warp.exposure.shading[0], warp.exposure.shading[1], warp.exposure.shading[2]);
    p[12] = f(join.start, join.step, join.radius, join.horizontal); p[13][0] = float(join.shifts.size());
    p[17][0] = float(p.size());
    for (const auto& point : warp.mesh.shifts) p.push_back(f(point.x, point.y));
    p[17][1] = float(p.size());
    for (const auto& color : warp.exposure.offsets) p.push_back(f(color[0], color[1], color[2]));
    p[17][2] = float(p.size());
    for (size_t i = 0; i < join.shifts.size(); ++i) p.push_back(f(join.shifts[i].x, join.shifts[i].y, join.cuts[i]));
    p[18][0] = -1;
    return p;
}
OverlapWarp alignOverlap(const Image& source, const Mapping& output, int width, int height, double focal,
    const Transform& transform, int originX, int originY, int left, int top, int right, int bottom, panorama::GpuFrame& gpu) {
    OverlapWarp warp;
    warp.left = left; warp.top = top;
    warp.step = std::max(1., std::max(source.info.width, source.info.height) / 1536.);
    const int w = int(std::ceil((right - left) / warp.step)), h = int(std::ceil((bottom - top) / warp.step));
    if (w < 48 || h < 48 || uint64_t(w) * h > 4000000) return warp;
    const int taps = std::min(4, int(std::ceil(warp.step)));
    std::vector<panorama::Float4> points(size_t(w) * h);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
        points[size_t(y) * w + x] = {float(left + (x + .5) * warp.step + originX), float(top + (y + .5) * warp.step + originY), float(warp.step), float(taps)};
    const auto sampled = gpu.sample(points, 4);
    Gray current{w, h, std::vector<float>(sampled.size())};
    Gray previous{w, h, gpu.referenceGray(output.pixels, width, height, left, top, w, h, warp.step, taps)};
    for (size_t i = 0; i < sampled.size(); ++i) {
        current.data[i] = std::min(sampled[i][0], previous.data[i]) >= 0 ? sampled[i][0] : -1;
        if (current.data[i] < 0) previous.data[i] = -1;
    }
    auto matches = gpu.flow(current, previous);
    if (warp.step > 1.5 && !matches.empty()) {
        std::vector<panorama::Float4> patches;
        for (const auto& match : matches) patches.push_back({
            float(left + (match.point.x + match.shift.x + .5) * warp.step), float(top + (match.point.y + match.shift.y + .5) * warp.step),
            float(left + (match.point.x + .5) * warp.step), float(top + (match.point.y + .5) * warp.step)});
        const auto refined = gpu.patchAlignment(output.pixels, width, height, patches,
            rasterParameters(source, width, height, focal, transform, originX, originY), false, warp.step * 1.2);
        for (size_t i = 0; i < matches.size(); ++i) if (refined[i][3] > .5f) {
            const Point delta{refined[i][0] / warp.step, refined[i][1] / warp.step};
            matches[i].point = matches[i].point + delta;
            matches[i].shift = matches[i].shift - delta;
        }
    }
    auto rotationBasis = [&](Point p) -> std::array<Point, 4> {
        if (transform.grid) {
            const double x = (p.x - w * .5) * .01, y = (p.y - h * .5) * .01;
            return {Point{1, 0}, Point{0, 1}, Point{-y, x}, Point{x, y}};
        }
        const auto local = transform.inverse(left + (p.x + .5) * warp.step + originX, top + (p.y + .5) * warp.step + originY);
        const double angle = (local.first - source.info.width * .5) / focal;
        const double vertical = local.second - source.info.height * .5;
        const double sine = std::sin(angle), cosine = std::cos(angle);
        const double heightFactor = focal * (1 + std::pow(vertical / focal, 2));
        auto vector = [&](double x, double y) { return Point{(transform.a * x - transform.b * y) * .01 / warp.step, (transform.b * x + transform.a * y) * .01 / warp.step}; };
        return {Point{1, 0}, Point{0, 1}, vector(-vertical * sine, -heightFactor * cosine), vector(-vertical * cosine, heightFactor * sine)};
    };
    auto base = transform.grid ? panorama::projectiveWarp(matches, w, h) : panorama::makeWarp(matches, w, h, rotationBasis);
    if (transform.grid && base.shifts.empty()) base = panorama::makeWarp(matches, w, h, rotationBasis);
    warp.mesh = gpu.refineWarp(current, previous, base, matches);
    const auto colors = gpu.colorMatches(current, previous, output.pixels, width, height,
        rasterParameters(source, width, height, focal, transform, originX, originY, warp));
    warp.exposure = gpu.exposure(colors, w, h);
    return warp;
}
void Java_com_unspektrawesome_panorama_PanoramaNative_compose(JNIEnv* env, jobject, jobject bitmap, jstring file, jint width, jint height, jdouble focal,
    jdoubleArray matrix, jint originX, jint originY, jint direction, jboolean blend) {
    const char* stage = "input";
    try {
        Image source(env, bitmap);
        Mapping output(env, file, width, height);
        recordNativeDiagnostic(ANDROID_LOG_INFO, "PanoramaGpu", "compose_start source=%ux%u canvas=%dx%d direction=%d blend=%d",
            source.info.width, source.info.height, width, height, direction, blend);
        stage = "geometry";
        const int count = env->GetArrayLength(matrix);
        require((count == 4 || count == 9 || count == 10) && focal > 1 && std::isfinite(focal) && direction >= -2 && direction <= 2);
        double values[10]{}; env->GetDoubleArrayRegion(matrix, 0, count, values);
        for (int i = 0; i < count; ++i) require(std::isfinite(values[i]));
        Transform transform;
        if (count >= 9) {
            require(count == 9 || values[9] == 1);
            transform.setProjective(values, count == 10);
        }
        else transform = Transform{values[0], values[1], values[2], values[3]};
        require(std::hypot(transform.a, transform.b) > .5 && std::hypot(transform.a, transform.b) < 2);
        double left = 1e20, right = -1e20, top = 1e20, bottom = -1e20;
        auto include = [&](double x, double y) {
            auto xy = transform.grid ? transform.panelPoint(x, y, source.info.width, source.info.height, focal) : transform.point(x, y);
            require(std::isfinite(xy.first) && std::isfinite(xy.second) && std::abs(xy.first) <= 1000000 && std::abs(xy.second) <= 1000000);
            left = std::min(left, xy.first); right = std::max(right, xy.first);
            top = std::min(top, xy.second); bottom = std::max(bottom, xy.second);
        };
        if (transform.grid) for (int i = 0; i <= 64; ++i) {
            const double x = source.info.width * i / 64., y = source.info.height * i / 64.;
            include(x, 0); include(x, source.info.height); include(0, y); include(source.info.width, y);
        }
        else for (int y : {0, int(source.info.height)}) for (int x : {0, int(source.info.width)}) {
            include(x, y);
        }
        const int l = std::clamp(int(std::floor(left)) - originX, 0, int(width));
        const int r = std::clamp(int(std::ceil(right)) - originX, 0, int(width));
        const int t = std::clamp(int(std::floor(top)) - originY, 0, int(height));
        const int b = std::clamp(int(std::ceil(bottom)) - originY, 0, int(height));
        stage = "upload";
        panorama::GpuFrame gpu(source.pixels, source.info.width, source.info.height, source.info.stride);
        gpu.parameters(rasterParameters(source, width, height, focal, transform, originX, originY));
        stage = "overlap";
        const auto correction = direction == 0 ? OverlapWarp{} : alignOverlap(source, output, width, height, focal, transform, originX, originY, l, t, r, b, gpu);
        JoinWarp join;
        const bool horizontal = std::abs(direction) != 2;
        const int resolution = transform.grid ? (source.info.width <= 768 ? 256 : 512) : 1024;
        const int step = std::max(1, (std::max(r - l, b - t) + resolution - 1) / resolution);
        const int acrossStart = horizontal ? l : t, acrossEnd = horizontal ? r : b;
        const int alongStart = horizontal ? t : l, alongEnd = horizontal ? b : r;
        const int cols = (acrossEnd - acrossStart + step - 1) / step, rows = (alongEnd - alongStart + step - 1) / step;
        if (cols <= 0 || rows <= 0) return;
        require(size_t(cols) * rows <= 2000000);
        std::vector<int> seam(rows, -1);
        std::vector<float> costs(size_t(cols) * rows, 100000.f);
        std::vector<uint8_t> coverage(transform.grid ? costs.size() : 0);
        int overlapLeft = cols, overlapRight = -1;
        std::vector<panorama::Float4> costPoints(size_t(cols) * rows);
        for (int row = 0; row < rows; ++row) for (int col = 0; col < cols; ++col) {
            const int x = horizontal ? acrossStart + col * step : alongStart + row * step;
            const int y = horizontal ? alongStart + row * step : acrossStart + col * step;
            costPoints[size_t(row) * cols + col] = {float(x + .5), float(y + .5), step * .5f, 0};
        }
        gpu.parameters(rasterParameters(source, width, height, focal, transform, originX, originY, correction));
        stage = "seam-costs";
        const auto samples = gpu.seamCosts(output.pixels, width, height, costPoints, false);
        for (size_t i = 0; i < samples.size(); ++i) {
            costs[i] = samples[i][0];
            if (transform.grid) coverage[i] = uint8_t(samples[i][1]);
            if (int(samples[i][1]) == 3) {
                const int col = int(i % cols);
                overlapLeft = std::min(overlapLeft, col); overlapRight = std::max(overlapRight, col);
            }
        }
        stage = "panel-seam";
        const auto panel = transform.grid && direction != 0 ? gpu.panelSeam(coverage, costs, cols, rows) : std::vector<uint8_t>{};
        if (!transform.grid && direction != 0 && overlapRight - overlapLeft > 4) {
            stage = "sweep-seam";
            const int margin = std::max(1, (overlapRight - overlapLeft) / 8);
            const int low = overlapLeft + margin, high = overlapRight - margin;
            seam = gpu.sweepSeam(costs, cols, std::vector<int>(rows, acrossStart), low, high, step, false);
        }
        std::vector<int> preciseSeam;
        if (!transform.grid && direction != 0 && step > 1 && seam[0] >= 0) {
            stage = "fine-seam";
            const int radius = std::min(48, step * 3), band = radius * 2 + 1;
            const int length = alongEnd - alongStart;
            if (uint64_t(length) * band <= 4000000 && acrossEnd - acrossStart >= band) {
                std::vector<int> origins(length);
                for (int row = 0; row < length; ++row) {
                    const double coarse = double(row) / step;
                    const int i = std::min(int(coarse), rows - 1), j = std::min(i + 1, rows - 1);
                    const double cut = seam[i] + (seam[j] - seam[i]) * (coarse - i);
                    origins[row] = std::clamp(int(std::floor(cut)) - radius, acrossStart, acrossEnd - band);
                }
                std::vector<panorama::Float4> finePoints(size_t(length) * band);
                for (int row = 0; row < length; ++row) for (int col = 0; col < band; ++col) {
                    const int across = origins[row] + col, along = alongStart + row;
                    finePoints[size_t(row) * band + col] = {float((horizontal ? across : along) + .5), float((horizontal ? along : across) + .5), 1, 0};
                }
                gpu.parameters(rasterParameters(source, width, height, focal, transform, originX, originY, correction));
                const auto fineCosts = gpu.seamCosts(output.pixels, width, height, finePoints, true);
                std::vector<float> values(fineCosts.size());
                for (size_t i = 0; i < values.size(); ++i) values[i] = fineCosts[i][0];
                preciseSeam = gpu.sweepSeam(values, band, origins, 0, band - 1, 1, true);
            }
        }
        auto cutAt = [&](double along) {
            if (!preciseSeam.empty()) {
                const double row = std::clamp(along - alongStart, 0., double(preciseSeam.size() - 1));
                const int i = int(row), j = std::min(i + 1, int(preciseSeam.size()) - 1);
                return preciseSeam[i] + (preciseSeam[j] - preciseSeam[i]) * (row - i);
            }
            const double row = std::clamp((along - alongStart) / step, 0., double(rows - 1));
            const int i = int(row), j = std::min(i + 1, rows - 1);
            return seam[i] >= 0 ? seam[i] + (seam[j] - seam[i]) * (row - i) : acrossStart + (acrossEnd - acrossStart) * .5;
        };
        if (!transform.grid && direction != 0 && overlapRight - overlapLeft > 4 && alongEnd - alongStart > 32) {
            stage = "seam-alignment";
            JoinWarp fine;
            fine.horizontal = horizontal; fine.start = alongStart + .5;
            const int count = (alongEnd - alongStart + 31) / 32 + 1;
            fine.step = double(alongEnd - alongStart - 1) / (count - 1);
            fine.shifts.resize(count); fine.cuts.resize(count);
            std::vector<uint8_t> supported(count);
            std::vector<panorama::Float4> patches;
            for (int i = 0; i < count; ++i) {
                const double along = alongStart + i * fine.step, across = cutAt(along);
                fine.cuts[i] = across + .5;
                const double cx = horizontal ? across + .5 : along + .5, cy = horizontal ? along + .5 : across + .5;
                patches.push_back({float(cx), float(cy), float(cx), float(cy)});
            }
            const auto refined = gpu.patchAlignment(output.pixels, width, height, patches,
                rasterParameters(source, width, height, focal, transform, originX, originY, correction), true, 3);
            for (int i = 0; i < count; ++i) {
                supported[i] = refined[i][3] > .5f;
                if (supported[i]) fine.shifts[i] = {refined[i][0], refined[i][1]};
            }
            const auto observed = fine.shifts;
            for (int i = 0; i < count; ++i) if (!supported[i]) {
                int nearest = -1;
                for (int j = std::max(0, i - 2); j <= std::min(count - 1, i + 2); ++j)
                    if (supported[j] && (nearest < 0 || std::abs(j - i) < std::abs(nearest - i))) nearest = j;
                if (nearest >= 0) fine.shifts[i] = observed[nearest] * std::exp(-std::pow(i - nearest, 2) / 8.);
            }
            join = std::move(fine);
        }
        auto parameters = rasterParameters(source, width, height, focal, transform, originX, originY, correction, join);
        parameters[13][1] = float(step); parameters[13][2] = float(cols); parameters[13][3] = float(rows);
        parameters[14] = {float(acrossStart), float(alongStart), float(horizontal), float(direction)};
        parameters[15] = {float(l), float(t), float(r), float(b)};
        parameters[16][0] = float(preciseSeam.size()); parameters[17][3] = float(parameters.size());
        for (int cut : seam) parameters.push_back({float(cut), 0, 0, 0});
        if (!panel.empty()) {
            parameters[18][0] = float(parameters.size());
            for (auto label : panel) parameters.push_back({float(label), 0, 0, 0});
        }
        parameters[18][1] = float(parameters.size());
        for (int cut : preciseSeam) parameters.push_back({float(cut), 0, 0, 0});
        gpu.parameters(parameters);
        stage = "blend";
        gpu.compose(output.pixels, width, height, l, t, r, b, blend && direction != 0);
        stage = "sync";
        require(msync(output.pixels, output.bytes, MS_SYNC) == 0);
        madvise(output.pixels, output.bytes, MADV_DONTNEED);
        recordNativeDiagnostic(ANDROID_LOG_INFO, "PanoramaGpu", "compose_complete canvas=%dx%d direction=%d", width, height, direction);
    } catch (const std::exception& error) {
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu", "compose failed stage=%s canvas=%dx%d direction=%d reason=%s",
            stage, width, height, direction, error.what());
        env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to save panorama");
    } catch (...) {
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "PanoramaGpu", "compose failed stage=%s canvas=%dx%d direction=%d reason=unknown",
            stage, width, height, direction);
        env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to save panorama");
    }
}
jintArray Java_com_unspektrawesome_panorama_PanoramaNative_crop(JNIEnv* env, jobject, jstring file, jint width, jint height) {
    try {
        Mapping image(env, file, width, height);
        panorama::GpuFrame gpu;
        const auto best = gpu.crop(image.pixels, width, height);
        auto result = env->NewIntArray(4);
        if (result) env->SetIntArrayRegion(result, 0, 4, best.data());
        return result;
    } catch (...) { env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to save panorama"); return nullptr; }
}
void Java_com_unspektrawesome_panorama_PanoramaNative_resize(JNIEnv* env, jobject, jobject bitmap, jobject result, jint rotation) {
    try {
        Image source(env, bitmap), target(env, result);
        require(target.info.width <= 1536 && target.info.height <= 1536);
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270);
        panorama::GpuFrame gpu(source.pixels, source.info.width, source.info.height, source.info.stride);
        gpu.resize(target.pixels, target.info.width, target.info.height, target.info.stride, rotation / 90);
    } catch (...) { env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to save panorama"); }
}
}

bool registerPanorama(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"align", "(Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;DZ)[D", reinterpret_cast<void*>(Java_com_unspektrawesome_panorama_PanoramaNative_align)},
        {"compose", "(Landroid/graphics/Bitmap;Ljava/lang/String;IID[DIIIZ)V", reinterpret_cast<void*>(Java_com_unspektrawesome_panorama_PanoramaNative_compose)},
        {"crop", "(Ljava/lang/String;II)[I", reinterpret_cast<void*>(Java_com_unspektrawesome_panorama_PanoramaNative_crop)},
        {"resize", "(Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;I)V", reinterpret_cast<void*>(Java_com_unspektrawesome_panorama_PanoramaNative_resize)},
    };
    return registerNativeMethods(env, "com/unspektrawesome/panorama/PanoramaNative", methods);
}
