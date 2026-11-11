#include "../NativeBindings.h"
#include "../NativeDiagnostics.h"
#include "../NativeHost.h"
#include <blosc.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
using Bytes = std::vector<uint8_t>;
constexpr size_t headerSize = 40, maximumBytes = 300 * 1024 * 1024;
constexpr std::array<uint8_t, 8> signature{'S', 'P', 'R', 'A', 'W', '0', '0', '1'};
struct Frame { Bytes full; };
void check(bool valid) { if (!valid) throw std::runtime_error("Invalid compressed RAW frame"); }
size_t pixels(int w, int h) {
    check(w >= 2 && h >= 2 && w <= 16384 && h <= 16384 && !(w & 1) && !(h & 1));
    check(uint64_t(w) * h <= 128 * 1024 * 1024);
    return size_t(w) * h;
}
uint32_t read32(const uint8_t* data) {
    return uint32_t(data[0]) | uint32_t(data[1]) << 8 | uint32_t(data[2]) << 16 | uint32_t(data[3]) << 24;
}
void put32(Bytes& bytes, size_t offset, uint32_t value) {
    for (unsigned i = 0; i < 4; ++i) bytes[offset + i] = uint8_t(value >> (i * 8));
}
uint8_t* direct(JNIEnv* env, jobject value, size_t size) {
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(value));
    const auto capacity = env->GetDirectBufferCapacity(value);
    check(data && capacity >= 0 && uint64_t(capacity) >= size);
    return data;
}
Bytes fromJava(JNIEnv* env, jbyteArray value) {
    const auto size = env->GetArrayLength(value);
    check(size > 0 && size_t(size) <= maximumBytes);
    Bytes bytes(size, 0);
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    check(!env->ExceptionCheck());
    return bytes;
}
jbyteArray toJava(JNIEnv* env, const Bytes& bytes) {
    auto value = env->NewByteArray(jsize(bytes.size()));
    if (value) env->SetByteArrayRegion(value, 0, jsize(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return value;
}
void failure(JNIEnv* env) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to read or save compressed RAW data");
}
Bytes compress(const uint8_t* source, size_t size, bool raw) {
    check(size > 0 && size <= maximumBytes - BLOSC_MAX_OVERHEAD);
    Bytes output(size + BLOSC_MAX_OVERHEAD);
    const int written = blosc_compress_ctx(3, raw ? BLOSC_BITSHUFFLE : BLOSC_NOSHUFFLE,
        raw ? 2 : 1, size, source, output.data(), output.size(), BLOSC_ZSTD_COMPNAME, 256 * 1024, 1);
    check(written > 0);
    output.resize(size_t(written));
    output.shrink_to_fit();
    return output;
}
size_t expandedSize(const uint8_t* data, size_t size, size_t maximum) {
    size_t expanded = 0;
    check(size >= BLOSC_MIN_HEADER_LENGTH && size <= maximumBytes);
    check(blosc_cbuffer_validate(data, size, &expanded) == 0 && expanded > 0 && expanded <= maximum);
    const auto* library = blosc_cbuffer_complib(data);
    check(library && std::strcmp(library, BLOSC_ZSTD_LIBNAME) == 0);
    return expanded;
}
void decompress(const uint8_t* data, size_t size, uint8_t* output, size_t expected) {
    check(expandedSize(data, size, expected) == expected);
    check(blosc_decompress_ctx(data, output, expected, 1) == int(expected));
}
struct Header {
    int w, h, period, pw, ph;
    size_t full, preview;
    explicit Header(const Bytes& bytes) {
        check(bytes.size() >= headerSize && std::equal(signature.begin(), signature.end(), bytes.begin()));
        w = int(read32(bytes.data() + 8)); h = int(read32(bytes.data() + 12));
        period = int(read32(bytes.data() + 16)); pw = int(read32(bytes.data() + 20)); ph = int(read32(bytes.data() + 24));
        full = read32(bytes.data() + 28); preview = read32(bytes.data() + 32);
        pixels(w, h); pixels(pw, ph);
        check(read32(bytes.data() + 36) == 0 && period >= 2 && period <= 16 && !(period & 1));
        check(pw <= w && ph <= h && pw <= 768 && ph <= 768 && pw % period == 0 && ph % period == 0);
        check(full >= BLOSC_MIN_HEADER_LENGTH && full <= bytes.size() - headerSize &&
            preview == bytes.size() - headerSize - full && (preview == 0 || preview >= headerSize + BLOSC_MIN_HEADER_LENGTH));
        check(preview > 0 || (w == pw && h == ph));
    }
};
Bytes frame(int w, int h, int period, int pw, int ph, const Bytes& raw, const Bytes& preview) {
    Bytes bytes(headerSize, 0);
    std::copy(signature.begin(), signature.end(), bytes.begin());
    put32(bytes, 8, w); put32(bytes, 12, h); put32(bytes, 16, period);
    put32(bytes, 20, pw); put32(bytes, 24, ph); put32(bytes, 28, uint32_t(raw.size())); put32(bytes, 32, uint32_t(preview.size()));
    check(headerSize + raw.size() + preview.size() <= maximumBytes);
    bytes.reserve(headerSize + raw.size() + preview.size());
    bytes.insert(bytes.end(), raw.begin(), raw.end()); bytes.insert(bytes.end(), preview.begin(), preview.end());
    return bytes;
}
Bytes preview(const Bytes& bytes) {
    const Header header(bytes);
    if (header.preview == 0) return bytes;
    Bytes result(bytes.begin() + headerSize + header.full, bytes.end());
    const Header small(result);
    check(small.w == header.pw && small.h == header.ph && small.period == header.period && small.preview == 0);
    check(expandedSize(result.data() + headerSize, small.full, pixels(small.w, small.h) * 2) == pixels(small.w, small.h) * 2);
    return result;
}
size_t rowSize(int w, int format) {
    check(format >= 0 && format <= 2 && (format != 1 || w % 4 == 0));
    return format == 1 ? size_t(w) / 4 * 5 : format == 2 ? size_t(w) / 2 * 3 : size_t(w) * 2;
}
jlong Java_com_unspektrawesome_burst_RawFrameCompression_encode(JNIEnv* env, jobject, jobject source, jint w, jint h, jint stride, jint format, jint block) {
    try {
        const size_t count = pixels(w, h), row = rowSize(w, format);
        check(block >= 1 && block <= 8);
        const int period = block * 2;
        check(period <= w && period <= h && stride >= int(row));
        const auto* input = direct(env, source, size_t(h - 1) * stride + row);
        Bytes raw(count * 2);
        for (int y = 0; y < h; ++y) {
            const auto* line = input + size_t(y) * stride;
            auto* target = raw.data() + size_t(y) * w * 2;
            if (format == 0) { std::memcpy(target, line, size_t(w) * 2); continue; }
            for (int x = 0; x < w; ++x) {
                const unsigned value = format == 1 ? (unsigned(line[x / 4 * 5 + x % 4]) << 2) |
                    ((line[x / 4 * 5 + 4] >> (x % 4 * 2)) & 3) : (unsigned(line[x / 2 * 3 + x % 2]) << 4) |
                    ((line[x / 2 * 3 + 2] >> (x % 2 * 4)) & 15);
                target[x * 2] = uint8_t(value); target[x * 2 + 1] = uint8_t(value >> 8);
            }
        }
        const int factor = std::max(1, (std::max(w, h) + 767) / 768);
        const int pw = std::max(period, w / factor / period * period), ph = std::max(period, h / factor / period * period);
        Bytes small(pixels(pw, ph) * 2);
        for (int y = 0; y < ph; ++y) for (int x = 0; x < pw; ++x) {
            const int sx = std::min(w - period, int(int64_t(x / period) * w / (pw / period)) / period * period) + x % period;
            const int sy = std::min(h - period, int(int64_t(y / period) * h / (ph / period)) / period * period) + y % period;
            std::memcpy(small.data() + (size_t(y) * pw + x) * 2, raw.data() + (size_t(sy) * w + sx) * 2, 2);
        }
        auto result = std::make_unique<Frame>();
        const auto full = compress(raw.data(), raw.size(), true);
        const auto reduced = w == pw && h == ph ? Bytes{} : frame(pw, ph, period, pw, ph,
            compress(small.data(), small.size(), true), {});
        result->full = frame(w, h, period, pw, ph, full, reduced);
        return reinterpret_cast<jlong>(result.release());
    } catch (...) { failure(env); return 0; }
}
void Java_com_unspektrawesome_burst_RawFrameCompression_copy(JNIEnv* env, jobject, jobject source, jobject destination, jint w, jint h, jint stride, jint format) {
    try {
        pixels(w, h);
        const size_t row = rowSize(w, format);
        check(stride >= int(row));
        const auto* input = direct(env, source, size_t(h - 1) * stride + row);
        auto* output = direct(env, destination, row * h);
        for (int y = 0; y < h; ++y) std::memcpy(output + size_t(y) * row, input + size_t(y) * stride, row);
    } catch (...) { failure(env); }
}
jobject Java_com_unspektrawesome_burst_RawFrameCompression_payload(JNIEnv* env, jobject, jlong handle, jboolean reduced) {
    try {
        auto* value = reinterpret_cast<Frame*>(handle);
        check(value != nullptr);
        const Header header(value->full);
        if (reduced && header.preview > 0)
            return env->NewDirectByteBuffer(value->full.data() + headerSize + header.full, jlong(header.preview));
        return env->NewDirectByteBuffer(value->full.data(), jlong(value->full.size()));
    } catch (...) { failure(env); return nullptr; }
}
void Java_com_unspektrawesome_burst_RawFrameCompression_release(JNIEnv*, jobject, jlong handle) { delete reinterpret_cast<Frame*>(handle); }
void Java_com_unspektrawesome_burst_RawFrameCompression_decode(JNIEnv* env, jobject, jbyteArray encoded, jobject destination) {
    try {
        const auto bytes = fromJava(env, encoded);
        const Header header(bytes);
        const size_t size = pixels(header.w, header.h) * 2;
        check(env->GetDirectBufferCapacity(destination) == jlong(size));
        decompress(bytes.data() + headerSize, header.full, direct(env, destination, size), size);
    } catch (...) { failure(env); }
}
jbyteArray Java_com_unspektrawesome_burst_RawFrameCompression_preview(JNIEnv* env, jobject, jbyteArray encoded) {
    try { return toJava(env, preview(fromJava(env, encoded))); }
    catch (...) { failure(env); return nullptr; }
}
jbyteArray Java_com_unspektrawesome_burst_RawFrameCompression_compressMetadata(JNIEnv* env, jobject, jbyteArray source) {
    try { const auto bytes = fromJava(env, source); return toJava(env, compress(bytes.data(), bytes.size(), false)); }
    catch (...) { failure(env); return nullptr; }
}
jbyteArray Java_com_unspektrawesome_burst_RawFrameCompression_decompressMetadata(JNIEnv* env, jobject, jbyteArray source, jint maximum) {
    try {
        check(maximum > 0 && size_t(maximum) <= maximumBytes);
        const auto bytes = fromJava(env, source);
        Bytes output(expandedSize(bytes.data(), bytes.size(), maximum));
        decompress(bytes.data(), bytes.size(), output.data(), output.size());
        return toJava(env, output);
    } catch (...) { failure(env); return nullptr; }
}
}

bool registerPanorama(JNIEnv* environment);
bool registerMultiFrameMerge(JNIEnv* environment);

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"encode", "(Ljava/nio/ByteBuffer;IIIII)J", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_encode)},
        {"copy", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIII)V", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_copy)},
        {"payload", "(JZ)Ljava/nio/ByteBuffer;", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_payload)},
        {"release", "(J)V", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_release)},
        {"decode", "([BLjava/nio/ByteBuffer;)V", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_decode)},
        {"preview", "([B)[B", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_preview)},
        {"compressMetadata", "([B)[B", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_compressMetadata)},
        {"decompressMetadata", "([BI)[B", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_decompressMetadata)},
    };
    if (!authorizeNativeHost(env) || !registerNativeMethods(env, "com/unspektrawesome/burst/RawFrameCompression", methods) ||
        !registerPanorama(env) || !registerMultiFrameMerge(env)) return JNI_ERR;
    return initializeNativeDiagnostics(vm, env) ? JNI_VERSION_1_6 : JNI_ERR;
}
