// Renders the small sRGB previews stored inside saved DNG files and shown in the processing queue.
// Matches the CPU reference in DeferredCaptureWriter, which stays in use for remosaiced sensors.
#include "../NativeBindings.h"
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

constexpr int kGammaSize = 65536;

const std::vector<int>& gammaTable() {
    static const std::vector<int> table = [] {
        std::vector<int> values(kGammaSize);
        for (int i = 0; i < kGammaSize; ++i) values[size_t(i)] = int(std::pow(i / 65535.0, 1.0 / 2.2) * 255.0);
        return values;
    }();
    return table;
}

struct HueSatTable {
    const float* values = nullptr;
    int hue = 0;
    int saturation = 0;
    int value = 0;
    bool encoded = false;
};

constexpr double kToProPhoto[9] = {.529337205, .330188454, .140581572, .098361944, .873425969, .028168661, .016873187, .117644399, .865354738};
constexpr double kFromProPhoto[9] = {2.034149267, -.727666256, -.306771900, -.228802244, 1.231806991, -.002927093, -.008557507, -.153274881, 1.161975004};

void applyHueSat(const HueSatTable& table, float* rgb) {
    float pro[3];
    for (int row = 0; row < 3; ++row) {
        pro[row] = std::max(0.f, float(kToProPhoto[row * 3] * rgb[0] + kToProPhoto[row * 3 + 1] * rgb[1] + kToProPhoto[row * 3 + 2] * rgb[2]));
    }
    const float red = pro[0], green = pro[1], blue = pro[2];
    const float v = std::max({red, green, blue});
    const float delta = v - std::min({red, green, blue});
    float h = 0.f;
    if (delta >= 1e-7f) {
        if (v == red) h = (green - blue) / delta;
        else if (v == green) h = 2.f + (blue - red) / delta;
        else h = 4.f + (red - green) / delta;
        h /= 6.f;
    }
    h -= std::floor(h);
    const float s = v > 0.f ? delta / v : 0.f;
    float brightness = table.encoded ? (v <= .0031308f ? v * 12.92f : 1.055f * std::pow(v, 1.f / 2.4f) - .055f) : v;
    const float hp = h * float(table.hue);
    const float sp = std::clamp(s, 0.f, 1.f) * float(table.saturation - 1);
    const float vp = std::clamp(brightness, 0.f, 1.f) * float(table.value - 1);
    const int hl = int(hp), sl = int(sp), vl = int(vp);
    const float hf = hp - float(hl), sf = sp - float(sl), vf = vp - float(vl);
    float hueShift = 0.f, saturationScale = 0.f, valueScale = 0.f;
    for (int corner = 0; corner < 8; ++corner) {
        const bool upperH = corner & 1, upperS = corner & 2, upperV = corner & 4;
        const float contribution = (upperH ? hf : 1.f - hf) * (upperS ? sf : 1.f - sf) * (upperV ? vf : 1.f - vf);
        const int hc = upperH ? (hl + 1) % table.hue : hl;
        const int sc = upperS ? std::min(sl + 1, table.saturation - 1) : sl;
        const int vc = upperV ? std::min(vl + 1, table.value - 1) : vl;
        const size_t entry = size_t(((vc * table.hue + hc) * table.saturation + sc) * 3);
        hueShift += table.values[entry] * contribution;
        saturationScale += table.values[entry + 1] * contribution;
        valueScale += table.values[entry + 2] * contribution;
    }
    h = h + hueShift / 360.f;
    h -= std::floor(h);
    const float saturation = std::clamp(s * saturationScale, 0.f, 1.f);
    brightness *= valueScale;
    if (table.encoded) brightness = brightness <= .04045f ? brightness / 12.92f : std::pow((brightness + .055f) / 1.055f, 2.4f);
    const float chroma = brightness * saturation;
    const float section = h * 6.f;
    const float x = chroma * (1.f - std::fabs(std::fmod(section, 2.f) - 1.f));
    const float offset = brightness - chroma;
    float r, g, b;
    switch (int(section)) {
        case 0: r = chroma; g = x; b = 0.f; break;
        case 1: r = x; g = chroma; b = 0.f; break;
        case 2: r = 0.f; g = chroma; b = x; break;
        case 3: r = 0.f; g = x; b = chroma; break;
        case 4: r = x; g = 0.f; b = chroma; break;
        default: r = chroma; g = 0.f; b = x; break;
    }
    for (int row = 0; row < 3; ++row) {
        rgb[row] = float(kFromProPhoto[row * 3] * (r + offset) + kFromProPhoto[row * 3 + 1] * (g + offset) + kFromProPhoto[row * 3 + 2] * (b + offset));
    }
}

struct Layout {
    const uint8_t* pixels;
    int stride;
    int format;
};

inline int sample(const Layout& layout, int x, int y) {
    const uint8_t* row = layout.pixels + size_t(y) * size_t(layout.stride);
    switch (layout.format) {
        case 1: {
            const uint8_t* base = row + size_t(x / 4) * 5;
            return (base[x % 4] << 2) | ((base[4] >> (x % 4 * 2)) & 3);
        }
        case 2: {
            const uint8_t* base = row + size_t(x / 2) * 3;
            return (base[x % 2] << 4) | ((base[2] >> (x % 2 * 4)) & 15);
        }
        default:
            return row[size_t(x) * 2] | (row[size_t(x) * 2 + 1] << 8);
    }
}

// params: black[4], range[4], gains[3], clip, matrix[9]
jboolean Java_com_unspektrawesome_gallery_DngPreviewNative_render(JNIEnv* env, jobject, jobject buffer, jint width, jint height,
    jint stride, jint format, jint left, jint top, jintArray channelArray, jfloatArray paramArray, jintArray columnArray,
    jintArray rowArray, jfloatArray dcpArray, jintArray dcpDimensions, jintArray outputArray) {
    if (!buffer || !channelArray || !paramArray || !columnArray || !rowArray || !outputArray) return JNI_FALSE;
    if (width <= 0 || height <= 0 || format < 0 || format > 2 || left < 0 || top < 0) return JNI_FALSE;
    const auto* pixels = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const jlong rowBytes = format == 1 ? jlong((width + 3) / 4) * 5 : format == 2 ? jlong((width + 1) / 2) * 3 : jlong(width) * 2;
    if (!pixels || stride < rowBytes || capacity < jlong(height - 1) * stride + rowBytes) return JNI_FALSE;
    if (env->GetArrayLength(channelArray) != 4 || env->GetArrayLength(paramArray) != 21) return JNI_FALSE;
    const jsize w = env->GetArrayLength(columnArray);
    const jsize h = env->GetArrayLength(rowArray);
    if (w <= 0 || h <= 0 || env->GetArrayLength(outputArray) != w * h) return JNI_FALSE;

    jint channels[4];
    jfloat params[21];
    env->GetIntArrayRegion(channelArray, 0, 4, channels);
    env->GetFloatArrayRegion(paramArray, 0, 21, params);
    std::vector<jint> columns(static_cast<size_t>(w));
    std::vector<jint> rows(static_cast<size_t>(h));
    env->GetIntArrayRegion(columnArray, 0, w, columns.data());
    env->GetIntArrayRegion(rowArray, 0, h, rows.data());
    for (int channel : channels) if (channel < 0 || channel > 3) return JNI_FALSE;
    for (jint x : columns) if (x < 0 || left + x + 1 >= width) return JNI_FALSE;
    for (jint y : rows) if (y < 0 || top + y + 1 >= height) return JNI_FALSE;

    std::vector<float> dcpValues;
    HueSatTable dcp;
    if (dcpArray && dcpDimensions) {
        if (env->GetArrayLength(dcpDimensions) != 4) return JNI_FALSE;
        jint dims[4];
        env->GetIntArrayRegion(dcpDimensions, 0, 4, dims);
        if (dims[0] < 1 || dims[1] < 2 || dims[2] < 1) return JNI_FALSE;
        const jlong entries = jlong(dims[0]) * dims[1] * dims[2] * 3;
        if (env->GetArrayLength(dcpArray) != entries) return JNI_FALSE;
        dcpValues.resize(size_t(entries));
        env->GetFloatArrayRegion(dcpArray, 0, jsize(entries), dcpValues.data());
        dcp = {dcpValues.data(), dims[0], dims[1], dims[2], dims[3] != 0};
    }
    if (env->ExceptionCheck()) return JNI_FALSE;

    const float* black = params;
    const float* range = params + 4;
    const float* gains = params + 8;
    const float clip = params[11];
    const float* matrix = params + 12;
    const Layout layout{pixels, stride, format};
    const auto& gamma = gammaTable();
    std::vector<jint> output(size_t(w) * size_t(h));
    auto encode = [&gamma](float linear) {
        return gamma[size_t(std::clamp(linear, 0.f, 1.f) * float(kGammaSize - 1) + .5f)];
    };
    auto renderRows = [&](int first, int step) {
        for (int y = first; y < h; y += step) {
            const int sy = top + rows[size_t(y)];
            jint* destination = output.data() + size_t(y) * size_t(w);
            for (int x = 0; x < w; ++x) {
                const int sx = left + columns[size_t(x)];
                float bayer[4];
                for (int index = 0; index < 4; ++index) {
                    const int channel = channels[index];
                    const int value = sample(layout, sx + (index & 1), sy + (index >> 1));
                    bayer[channel] = std::clamp((float(value) - black[channel]) / range[channel], 0.f, 1.f);
                }
                const float rgb[3] = {
                    std::min(bayer[0] * gains[0], clip),
                    std::min((bayer[1] + bayer[2]) * .5f * gains[1], clip),
                    std::min(bayer[3] * gains[2], clip),
                };
                float color[3];
                for (int row = 0; row < 3; ++row) {
                    color[row] = rgb[0] * matrix[row * 3] + rgb[1] * matrix[row * 3 + 1] + rgb[2] * matrix[row * 3 + 2];
                }
                if (dcp.values) applyHueSat(dcp, color);
                destination[x] = jint(0xff000000u | uint32_t(encode(color[0])) << 16 | uint32_t(encode(color[1])) << 8 |
                    uint32_t(encode(color[2])));
            }
        }
    };
    const int threads = std::min(int(std::clamp(std::thread::hardware_concurrency(), 1u, 8u)), int(h));
    std::vector<std::thread> workers;
    for (int i = 1; i < threads; ++i) workers.emplace_back(renderRows, i, threads);
    renderRows(0, threads);
    for (auto& worker : workers) worker.join();
    env->SetIntArrayRegion(outputArray, 0, w * h, output.data());
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

}  // namespace

bool registerDngPreview(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"render", "(Ljava/nio/ByteBuffer;IIIIII[I[F[I[I[F[I[I)Z", reinterpret_cast<void*>(Java_com_unspektrawesome_gallery_DngPreviewNative_render)},
    };
    return registerNativeMethods(env, "com/unspektrawesome/gallery/DngPreviewNative", methods);
}
