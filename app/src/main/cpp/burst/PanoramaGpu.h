#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <vector>

namespace panorama {
using Float4 = std::array<float, 4>;
struct Gray;
struct Warp;
struct FlowMatch;
struct ColorMatch;
struct ExposureField;
std::vector<Float4> gpuFeatureScores(const float* gray, int width, int height);
std::vector<float> gpuDescribe(const float* gray, int width, int height, const std::vector<Float4>& points);
std::vector<Float4> gpuMatch(const std::vector<float>& before, const std::vector<float>& after);

class GpuFrame {
public:
    GpuFrame();
    GpuFrame(const uint8_t* pixels, int width, int height, int stride);
    ~GpuFrame();
    GpuFrame(const GpuFrame&) = delete;
    GpuFrame& operator=(const GpuFrame&) = delete;
    void parameters(const std::vector<Float4>& values);
    std::vector<Float4> sample(const std::vector<Float4>& points, int mode = 0);
    std::vector<float> trackingGray(double focal, bool rectilinear = false);
    std::vector<float> referenceGray(const uint8_t* canvas, int width, int height, int left, int top, int cols, int rows, double step, int taps);
    std::vector<FlowMatch> flow(const Gray& source, const Gray& reference);
    Warp refineWarp(const Gray& source, const Gray& reference, const Warp& initial, const std::vector<FlowMatch>& matches);
    std::vector<uint8_t> panelSeam(const std::vector<uint8_t>& coverage, const std::vector<float>& costs, int cols, int rows);
    std::vector<Float4> seamCosts(const uint8_t* canvas, int width, int height, const std::vector<Float4>& points, bool fine);
    std::vector<Float4> refinePoints(const Gray& source, const Gray& reference, const std::vector<Float4>& points);
    std::vector<Float4> correlations(const Gray& source, const Gray& reference, const std::vector<Float4>& offsets);
    std::vector<ColorMatch> colorMatches(const Gray& source, const Gray& reference, const uint8_t* canvas, int width, int height, std::vector<Float4> parameters);
    std::array<int, 4> crop(const uint8_t* canvas, int width, int height);
    void resize(uint8_t* output, int width, int height, int stride, int rotation = 0);
    ExposureField exposure(const std::vector<ColorMatch>& samples, int width, int height);
    std::vector<int> sweepSeam(const std::vector<float>& costs, int cols, const std::vector<int>& origins, int low, int high, int step, bool fine);
    std::vector<Float4> patchAlignment(const uint8_t* canvas, int width, int height, const std::vector<Float4>& points, const std::vector<Float4>& parameters, bool seam, double maxShift);
    void compose(uint8_t* canvas, int width, int height, int left, int top, int right, int bottom, bool blend = false);
private:
    struct State;
    std::unique_ptr<State> state_;
};
}
