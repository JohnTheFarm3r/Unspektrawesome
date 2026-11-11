#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <sys/mman.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

class CameraRawLayoutError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class CameraBayerBridge {
public:
    CameraBayerBridge() = default;
    CameraBayerBridge(const CameraBayerBridge&) = delete;
    CameraBayerBridge& operator=(const CameraBayerBridge&) = delete;
    ~CameraBayerBridge() { close(); }

    AHardwareBuffer* sample(AHardwareBuffer* source, int width, int height, int step,
                            int format, int rowStride, const uint8_t* plane, size_t availableBytes, int cfa,
                            const float* black, const float* white, const int32_t* bounds,
                            bool hotPixels, float noiseS, float noiseO)
    {
        check(plane != nullptr, "Camera import requires a readable RAW reference");
        AHardwareBuffer_Desc inputDescription{};
        AHardwareBuffer_describe(source, &inputDescription);
        const bool packedBlob = format != 0 && inputDescription.format == AHARDWAREBUFFER_FORMAT_BLOB;
        if (inputDescription.layers != 1 || (packedBlob ? inputDescription.height != 1 :
            inputDescription.width != static_cast<uint32_t>(width) ||
            inputDescription.height != static_cast<uint32_t>(height)))
            throw CameraRawLayoutError("Camera hardware dimensions differ from the RAW plane");
        const std::array<int, 11> layout{width, height, step, format, rowStride, cfa,
            bounds[0], bounds[1], bounds[2], bounds[3], hotPixels ? 1 : 0};
        if (layout != validatedLayout_) {
            resetValidation();
            validatedLayout_ = layout;
        }
        initialize();
        check(eglMakeCurrent(display_, surface_, surface_, context_) == EGL_TRUE, "EGL camera context");
        EGLImageKHR input = EGL_NO_IMAGE_KHR;
        GLuint texture = 0, buffer = 0;
        try {
            GLuint program = program_;
            if (format != 0) {
                check(format == 1 || format == 2, "Camera packed format");
                check(width > 0 && height > 0 && rowStride > 0, "Camera packed dimensions");
                check((inputDescription.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) != 0,
                    "Camera packed buffer has no GPU data usage");
                check(bufferStorageExternal_ != nullptr, "Camera external buffer extension unavailable");
                check(externalBufferReleaseIsSafe(), "Camera external buffer release unmaps process memory");
                GLint fragmentBuffers = 0;
                glGetIntegerv(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, &fragmentBuffers);
                check(fragmentBuffers > 0, "Camera fragment storage buffers unavailable");
                const uint64_t rowBytes = format == 1 ? ((uint64_t(width) + 3) / 4) * 5 :
                    ((uint64_t(width) + 1) / 2) * 3;
                const uint64_t requiredBytes = uint64_t(rowStride) * (height - 1) + rowBytes;
                const uint64_t bufferBytes = (requiredBytes + 3) & ~uint64_t{3};
                check(rowStride >= static_cast<int64_t>(rowBytes) &&
                    bufferBytes <= availableBytes && (!packedBlob || bufferBytes <= inputDescription.width),
                    "Camera packed buffer bounds");
                auto& selected = hotPixels ? hotPackedProgram_ : packedProgram_;
                if (!selected) selected = createProgram(true, hotPixels);
                program = selected;
                glGenBuffers(1, &buffer);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
                bufferStorageExternal_(GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(bufferBytes),
                    clientBuffer_(source), 0);
                const GLenum importError = glGetError();
                if (importError != GL_NO_ERROR) throw std::runtime_error(
                    "Camera packed buffer import error=" + std::to_string(importError));
                // ANGLE can reject an import without setting a GL error. Never draw with empty storage.
                GLint64 importedBytes = 0;
                glGetBufferParameteri64v(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE, &importedBytes);
                check(glGetError() == GL_NO_ERROR && importedBytes == static_cast<GLint64>(bufferBytes),
                    "Camera packed buffer storage unavailable");
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
            } else {
                if (hotPixels) {
                    if (!hotProgram_) hotProgram_ = createProgram(false, true);
                    program = hotProgram_;
                }
                input = importImage(source);
                glGenTextures(1, &texture);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);
                imageTarget_(GL_TEXTURE_2D, input);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                check(glGetError() == GL_NO_ERROR, "EGL camera integer texture");
            }
            ensureOutput((width + step - 1) / step, (height + step - 1) / step);
            if (texture) glBindTexture(GL_TEXTURE_2D, texture);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
            glViewport(0, 0, width_, height_);
            glUseProgram(program);
            glUniform2i(glGetUniformLocation(program, "rawSize"), width, height);
            glUniform1i(glGetUniformLocation(program, "cellStep"), step);
            glUniform1i(glGetUniformLocation(program, "rawFormat"), format);
            glUniform1i(glGetUniformLocation(program, "rowStride"), rowStride);
            glUniform1i(glGetUniformLocation(program, "cfa"), cfa);
            glUniform4iv(glGetUniformLocation(program, "rawBounds"), 1, bounds);
            glUniform4fv(glGetUniformLocation(program, "blackLevel"), 1, black);
            glUniform4fv(glGetUniformLocation(program, "whiteLevel"), 1, white);
            if (hotPixels) glUniform2f(glGetUniformLocation(program, "noiseProfile"), noiseS, noiseO);
            glBindVertexArray(vertexArray_);
            if (beginQuery_) beginQuery_(GL_TIME_ELAPSED_EXT, query_);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (endQuery_) endQuery_(GL_TIME_ELAPSED_EXT);
            glFinish();
            check(glGetError() == GL_NO_ERROR, "EGL camera sampling");
            if (std::find(validatedBuffers_.begin(), validatedBuffers_.end(), source) == validatedBuffers_.end()) {
                if (validateSamples(plane, availableBytes, width, height, step, format, rowStride, cfa,
                                    black, white, bounds, hotPixels, noiseS, noiseO)) {
                    if (validatedBuffers_.size() >= 16) resetValidation();
                    AHardwareBuffer_acquire(source);
                    validatedBuffers_.push_back(source);
                }
            }
            gpuMs_ = -1.0;
            if (queryResult_) {
                GLint disjoint = 0;
                glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
                GLuint64 elapsed = 0;
                queryResult_(query_, GL_QUERY_RESULT_EXT, &elapsed);
                if (!disjoint) gpuMs_ = static_cast<double>(elapsed) / 1e6;
            }
            if (buffer) glDeleteBuffers(1, &buffer);
            if (texture) glDeleteTextures(1, &texture);
            if (input != EGL_NO_IMAGE_KHR) destroyImage_(display_, input);
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            return output_;
        } catch (...) {
            glFinish();
            if (buffer) glDeleteBuffers(1, &buffer);
            if (texture) glDeleteTextures(1, &texture);
            if (input != EGL_NO_IMAGE_KHR) destroyImage_(display_, input);
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            throw;
        }
    }

    double gpuMs() const { return gpuMs_; }

    void resetValidation()
    {
        for (auto* buffer : validatedBuffers_) AHardwareBuffer_release(buffer);
        validatedBuffers_.clear();
    }

private:
    // Some Adreno GLES drivers unmap the process range [0, size) when a buffer imported with
    // glBufferStorageExternalEXT is deleted, which destroys the Java heap for large RAW frames.
    // Probe once per process while this test owns the whole low range it could affect.
    bool externalBufferReleaseIsSafe()
    {
        static std::mutex mutex;
        static int state = -1;
        std::lock_guard<std::mutex> lock(mutex);
        if (state < 0) state = probeExternalBufferRelease() ? 1 : 0;
        return state == 1;
    }

    bool probeExternalBufferRelease()
    {
        constexpr uintptr_t probeBytes = uintptr_t{2} << 20;
        const uintptr_t page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
        uintptr_t start = 0;
        // Addresses below the kernel's minimum mapping address cannot hold anything.
        for (uintptr_t address = page; address <= 0x10000 && start == 0; address += page) {
            void* reserved = mmap(reinterpret_cast<void*>(address), probeBytes - address, PROT_NONE,
                MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
            if (reserved == reinterpret_cast<void*>(address)) start = address;
            else if (reserved != MAP_FAILED) munmap(reserved, probeBytes - address);
            else if (errno == EEXIST) return false;
        }
        if (start == 0) return false;

        AHardwareBuffer_Desc description{};
        description.width = static_cast<uint32_t>(probeBytes);
        description.height = 1;
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_BLOB;
        description.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
        AHardwareBuffer* probe = nullptr;
        bool imported = false;
        if (AHardwareBuffer_allocate(&description, &probe) == 0) {
            while (glGetError() != GL_NO_ERROR) {}
            GLuint buffer = 0;
            glGenBuffers(1, &buffer);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            bufferStorageExternal_(GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(probeBytes), clientBuffer_(probe), 0);
            GLint64 importedBytes = 0;
            glGetBufferParameteri64v(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE, &importedBytes);
            imported = glGetError() == GL_NO_ERROR && importedBytes == static_cast<GLint64>(probeBytes);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            glDeleteBuffers(1, &buffer);
            glFinish();
            while (glGetError() != GL_NO_ERROR) {}
            AHardwareBuffer_release(probe);
        }

        bool intact = true;
        unsigned char residency = 0;
        for (uintptr_t address = start; address < probeBytes; address += page) {
            if (mincore(reinterpret_cast<void*>(address), page, &residency) == 0) munmap(reinterpret_cast<void*>(address), page);
            else intact = false;
        }
        return imported && intact;
    }

    bool validateSamples(const uint8_t* plane, size_t bytes, int width, int height, int step,
                         int format, int stride, int cfa, const float* black, const float* white,
                         const int32_t* bounds, bool hotPixels, float noiseS, float noiseO)
    {
        const size_t rowBytes = format == 1 ? ((size_t(width) + 3) / 4) * 5 :
            format == 2 ? ((size_t(width) + 1) / 2) * 3 : size_t(width) * 2;
        check(stride >= static_cast<int64_t>(rowBytes) &&
            size_t(stride) * (height - 1) + rowBytes <= bytes, "Camera reference plane bounds");
        const int redX = cfa & 1, redY = cfa >> 1;
        const int greenX = redY == 0 ? 1 - redX : redX;
        const std::array<int, 4> dx{redX, greenX, greenX ^ 1, redX ^ 1};
        const std::array<int, 4> dy{redY, 0, 1, redY ^ 1};
        std::array<float, 4> low{INFINITY, INFINITY, INFINITY, INFINITY};
        std::array<float, 4> high{};
        std::vector<float> row(size_t(width_) * 4);
        for (int iy = 0; iy < 5; ++iy) {
            const int y = (height_ - 1) * iy / 4;
            glReadPixels(0, y, width_, 1, GL_RGBA, GL_FLOAT, row.data());
            check(glGetError() == GL_NO_ERROR, "Camera import validation readback");
            for (int ix = 0; ix < 9; ++ix) {
                const int x = (width_ - 1) * ix / 8;
                for (int channel = 0; channel < 4; ++channel) {
                    int px = std::min(x * step, (width - 2) & ~1) + dx[channel];
                    int py = std::min(y * step, (height - 2) & ~1) + dy[channel];
                    const auto bounded = [](int p, int start, int length) {
                        const int parity = p & 1;
                        const int low = start + ((start & 1) ^ parity);
                        const int end = start + length - 1;
                        return std::clamp(p, low, end - ((end & 1) ^ parity));
                    };
                    px = bounded(px, bounds[0], bounds[2]);
                    py = bounded(py, bounds[1], bounds[3]);
                    const auto read = [&](int x, int y) -> float {
                        const uint8_t* raw = plane + size_t(y) * stride;
                        if (format == 1) {
                            const int group = x / 4 * 5;
                            return uint32_t(raw[group + x % 4]) * 4 + ((raw[group + 4] >> (x % 4 * 2)) & 3);
                        }
                        if (format == 2) {
                            const int group = x / 2 * 3;
                            return uint32_t(raw[group + x % 2]) * 16 + ((raw[group + 2] >> (x % 2 * 4)) & 15);
                        }
                        return uint32_t(raw[x * 2]) | (uint32_t(raw[x * 2 + 1]) << 8);
                    };
                    float value = read(px, py);
                    const float range = std::max(white[channel] - black[channel], 1.0f);
                    if (hotPixels) {
                        const auto mirror = [](int p, int offset, int start, int length) {
                            const int low = start + ((start & 1) ^ (p & 1));
                            const int end = start + length - 1;
                            const int high = end - ((end & 1) ^ (p & 1));
                            return std::clamp(high - std::abs(high - (low + std::abs(p + offset * 2 - low))), low, std::max(low, high));
                        };
                        float maximum = 0, minimum = 65535, sum = 0;
                        for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
                            if (x == 0 && y == 0) continue;
                            const float neighbor = read(mirror(px, x, bounds[0], bounds[2]), mirror(py, y, bounds[1], bounds[3]));
                            maximum = std::max(maximum, neighbor);
                            minimum = std::min(minimum, neighbor);
                            sum += neighbor;
                        }
                        const float replacement = (sum - maximum - minimum) / 6.0f;
                        const float signal = std::max(replacement - black[channel], 0.0f) / range;
                        const float margin = std::max(range * 0.002f, 6.0f * std::sqrt(std::max(noiseS * signal + noiseO, 0.0f)) * range);
                        if (value > maximum + margin && value - black[channel] > 2.0f * std::max(maximum - black[channel], 0.0f) + margin)
                            value = std::round(replacement);
                    }
                    const float expected = std::max(value - black[channel], 0.0f) / range;
                    const float actual = row[size_t(x) * 4 + channel];
                    low[channel] = std::min(low[channel], expected);
                    high[channel] = std::max(high[channel], expected);
                    // Allow half-float rounding or two sensor code values near black.
                    const float tolerance = std::max(2.0f / range, std::abs(expected) * 0.002f);
                    if (!std::isfinite(actual) || std::abs(actual - expected) > tolerance)
                        throw CameraRawLayoutError("Camera import differs from RAW plane at " +
                            std::to_string(px) + "," + std::to_string(py) + " channel=" +
                            std::to_string(channel) + " expected=" + std::to_string(expected) +
                            " actual=" + std::to_string(actual));
                }
            }
        }
        // A blank frame cannot establish that the imported layout is correct.
        for (int channel = 0; channel < 4; ++channel)
            if ((high[channel] - low[channel]) * std::max(white[channel] - black[channel], 1.0f) > 8.0f)
                return true;
        return false;
    }

    static void check(bool success, const char* operation)
    {
        if (!success) throw std::runtime_error(operation);
    }

    GLuint shader(GLenum stage, const char* source)
    {
        GLuint result = glCreateShader(stage);
        glShaderSource(result, 1, &source, nullptr);
        glCompileShader(result);
        GLint compiled = 0;
        glGetShaderiv(result, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            std::array<char, 2048> message{};
            glGetShaderInfoLog(result, message.size(), nullptr, message.data());
            glDeleteShader(result);
            throw std::runtime_error(std::string("Camera sampling shader: ") + message.data());
        }
        return result;
    }

    void initialize()
    {
        if (program_) return;
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        check(display_ != EGL_NO_DISPLAY && eglInitialize(display_, nullptr, nullptr), "EGL camera display");
        check(eglBindAPI(EGL_OPENGL_ES_API), "EGL camera API");
        const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        EGLConfig config{};
        EGLint count = 0;
        check(eglChooseConfig(display_, attributes, &config, 1, &count) && count == 1, "EGL camera configuration");
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttributes);
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface_ = eglCreatePbufferSurface(display_, config, surfaceAttributes);
        check(context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE &&
            eglMakeCurrent(display_, surface_, surface_, context_), "EGL camera initialization");
        createImage_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        clientBuffer_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        imageTarget_ = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        check(createImage_ && destroyImage_ && clientBuffer_ && imageTarget_, "EGL camera image extensions");
        const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        const std::string extensionList = std::string(" ") + (extensions ? extensions : "") + " ";
        if (extensionList.find(" GL_EXT_external_buffer ") != std::string::npos)
            bufferStorageExternal_ = reinterpret_cast<PFNGLBUFFERSTORAGEEXTERNALEXTPROC>(eglGetProcAddress("glBufferStorageExternalEXT"));
        program_ = createProgram(false);
        glGenVertexArrays(1, &vertexArray_);
        if (extensions && std::string(extensions).find("GL_EXT_disjoint_timer_query") != std::string::npos) {
            auto gen = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(eglGetProcAddress("glGenQueriesEXT"));
            beginQuery_ = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(eglGetProcAddress("glBeginQueryEXT"));
            endQuery_ = reinterpret_cast<PFNGLENDQUERYEXTPROC>(eglGetProcAddress("glEndQueryEXT"));
            queryResult_ = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
            if (gen && beginQuery_ && endQuery_ && queryResult_) gen(1, &query_);
            else { beginQuery_ = nullptr; endQuery_ = nullptr; queryResult_ = nullptr; }
        }
    }

    GLuint createProgram(bool packed, bool hotPixels = false)
    {
        const std::string version = (packed ? std::string("#version 310 es\n#define CAMERA_PACKED\n") : "#version 300 es\n") +
            (hotPixels ? "#define CAMERA_HOT_PIXELS\n" : "");
        const std::string vertex = version + R"(
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";
        const std::string fragment = version + R"(
precision highp float;
precision highp int;
#ifdef CAMERA_PACKED
layout(std430, binding = 0) readonly buffer CameraInput { uint words[]; } cameraPacked;
uniform int rowStride;
uniform int rawFormat;
uint rawByte(uint offset) {
    return (cameraPacked.words[offset >> 2u] >> ((offset & 3u) * 8u)) & 255u;
}
#else
uniform highp usampler2D cameraRaw;
#endif
uniform ivec2 rawSize;
uniform int cellStep;
uniform int cfa;
uniform ivec4 rawBounds;
uniform vec4 blackLevel;
uniform vec4 whiteLevel;
layout(location = 0) out vec4 cells;
ivec2 boundedSite(ivec2 p) {
    ivec2 parity = p & ivec2(1);
    ivec2 low = rawBounds.xy + ((rawBounds.xy & ivec2(1)) ^ parity);
    ivec2 high = rawBounds.xy + rawBounds.zw - 1;
    high -= (high & ivec2(1)) ^ parity;
    return clamp(p, low, high);
}
float sensorRaw(ivec2 p) {
#ifdef CAMERA_PACKED
    uint x = uint(p.x);
    uint row = uint(p.y * rowStride);
    uint group = row + (rawFormat == 1 ? (x >> 2u) * 5u : (x >> 1u) * 3u);
    uint first = rawByte(group + (rawFormat == 1 ? x & 3u : x & 1u));
    uint second = rawByte(group + (rawFormat == 1 ? 4u : 2u));
    uint lowBits = rawFormat == 1 ? (second >> ((x & 3u) * 2u)) & 3u :
        (second >> ((x & 1u) * 4u)) & 15u;
    return float(first * (rawFormat == 1 ? 4u : 16u) + lowBits);
#else
    return float(texelFetch(cameraRaw, p, 0).r);
#endif
}
#ifdef CAMERA_HOT_PIXELS
uniform vec2 noiseProfile;
ivec2 mirroredSite(ivec2 p, ivec2 offset) {
    ivec2 parity = p & ivec2(1);
    ivec2 low = rawBounds.xy + ((rawBounds.xy & ivec2(1)) ^ parity);
    ivec2 high = rawBounds.xy + rawBounds.zw - 1;
    high -= (high & ivec2(1)) ^ parity;
    return clamp(high - abs(high - (low + abs(p + offset * 2 - low))), low, max(low, high));
}
float sampleRaw(ivec2 p, int channel) {
    p = boundedSite(p);
    float value = sensorRaw(p);
    float black = blackLevel[channel];
    float range = max(whiteLevel[channel] - black, 1.0);
    float minimumMargin = max(range * 0.002, 6.0 * sqrt(max(noiseProfile.y, 0.0)) * range);
    float neighbor = sensorRaw(mirroredSite(p, ivec2(-1, 0)));
    if (value <= neighbor + minimumMargin || value - black <= 2.0 * max(neighbor - black, 0.0) + minimumMargin) return value;
    float maximum = neighbor, minimum = neighbor, sum = neighbor;
    for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
        if (y == 0 && x <= 0) continue;
        float v = sensorRaw(mirroredSite(p, ivec2(x, y)));
        maximum = max(maximum, v);
        minimum = min(minimum, v);
        sum += v;
    }
    float replacement = (sum - maximum - minimum) / 6.0;
    float signal = max(replacement - black, 0.0) / range;
    float margin = max(range * 0.002, 6.0 * sqrt(max(noiseProfile.x * signal + noiseProfile.y, 0.0)) * range);
    return value > maximum + margin && value - black > 2.0 * max(maximum - black, 0.0) + margin ? round(replacement) : value;
}
#else
float sampleRaw(ivec2 p, int channel) { return sensorRaw(boundedSite(p)); }
#endif
void main() {
    ivec2 cell = min(ivec2(gl_FragCoord.xy) * cellStep, (rawSize - 2) & ~ivec2(1));
    ivec2 red = ivec2(cfa & 1, cfa >> 1);
    ivec2 green = ivec2(red.y == 0 ? 1 - red.x : red.x, 0);
    vec4 sensor = vec4(sampleRaw(cell + red, 0), sampleRaw(cell + green, 1),
        sampleRaw(cell + (green ^ ivec2(1)), 2), sampleRaw(cell + (red ^ ivec2(1)), 3));
    cells = max(sensor - blackLevel, vec4(0.0)) / max(whiteLevel - blackLevel, vec4(1.0));
})";
        GLuint vs = shader(GL_VERTEX_SHADER, vertex.c_str());
        GLuint fs = 0;
        try { fs = shader(GL_FRAGMENT_SHADER, fragment.c_str()); }
        catch (...) { glDeleteShader(vs); throw; }
        GLuint program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = 0;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (!linked) {
            std::array<char, 2048> message{};
            glGetProgramInfoLog(program, message.size(), nullptr, message.data());
            glDeleteProgram(program);
            throw std::runtime_error(std::string("Camera sampling program: ") + message.data());
        }
        return program;
    }

    EGLImageKHR importImage(AHardwareBuffer* buffer)
    {
        const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        EGLImageKHR image = createImage_(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer_(buffer), attributes);
        check(image != EGL_NO_IMAGE_KHR, "EGL camera buffer import");
        return image;
    }

    void releaseOutput()
    {
        if (framebuffer_) glDeleteFramebuffers(1, &framebuffer_);
        if (outputTexture_) glDeleteTextures(1, &outputTexture_);
        if (outputImage_ != EGL_NO_IMAGE_KHR) destroyImage_(display_, outputImage_);
        if (output_) AHardwareBuffer_release(output_);
        framebuffer_ = outputTexture_ = 0;
        outputImage_ = EGL_NO_IMAGE_KHR;
        output_ = nullptr;
    }

    void ensureOutput(int width, int height)
    {
        if (output_ && width == width_ && height == height_) return;
        releaseOutput();
        AHardwareBuffer_Desc description{};
        description.width = width;
        description.height = height;
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
        description.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        check(AHardwareBuffer_allocate(&description, &output_) == 0, "Camera cell allocation");
        outputImage_ = importImage(output_);
        glGenTextures(1, &outputTexture_);
        glBindTexture(GL_TEXTURE_2D, outputTexture_);
        imageTarget_(GL_TEXTURE_2D, outputImage_);
        glGenFramebuffers(1, &framebuffer_);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTexture_, 0);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Camera cell framebuffer");
        width_ = width;
        height_ = height;
    }

    void close()
    {
        resetValidation();
        if (display_ == EGL_NO_DISPLAY) return;
        if (context_ != EGL_NO_CONTEXT && eglMakeCurrent(display_, surface_, surface_, context_)) {
            releaseOutput();
            if (query_) glDeleteQueries(1, &query_);
            if (vertexArray_) glDeleteVertexArrays(1, &vertexArray_);
            if (program_) glDeleteProgram(program_);
            if (packedProgram_) glDeleteProgram(packedProgram_);
            if (hotProgram_) glDeleteProgram(hotProgram_);
            if (hotPackedProgram_) glDeleteProgram(hotPackedProgram_);
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    PFNEGLCREATEIMAGEKHRPROC createImage_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage_ = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC clientBuffer_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTarget_ = nullptr;
    PFNGLBEGINQUERYEXTPROC beginQuery_ = nullptr;
    PFNGLENDQUERYEXTPROC endQuery_ = nullptr;
    PFNGLGETQUERYOBJECTUI64VEXTPROC queryResult_ = nullptr;
    PFNGLBUFFERSTORAGEEXTERNALEXTPROC bufferStorageExternal_ = nullptr;
    AHardwareBuffer* output_ = nullptr;
    EGLImageKHR outputImage_ = EGL_NO_IMAGE_KHR;
    GLuint program_ = 0, packedProgram_ = 0, vertexArray_ = 0, outputTexture_ = 0, framebuffer_ = 0, query_ = 0;
    GLuint hotProgram_ = 0, hotPackedProgram_ = 0;
    int width_ = 0, height_ = 0;
    double gpuMs_ = -1.0;
    std::array<int, 11> validatedLayout_{};
    std::vector<AHardwareBuffer*> validatedBuffers_;
};
