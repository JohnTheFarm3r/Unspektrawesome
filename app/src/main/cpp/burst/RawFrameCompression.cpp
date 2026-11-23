#include "../NativeBindings.h"
#include "../NativeDiagnostics.h"
#include "../NativeHost.h"
#include "../uraw/frame.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace {
namespace raw = uraw::detail;
using Bytes = std::vector<uint8_t>;
constexpr size_t headerSize = 40, maximumBytes = 300 * 1024 * 1024;
// Frames store their samples with the lossless RAW tile codec.
constexpr std::array<uint8_t, 8> signature{'S', 'P', 'R', 'A', 'W', '0', '0', '2'};
// Tile payload: u16 tile width, u16 tile height, u32 tile count, one {u32 size, u8 codec, u8[3]} entry per tile,
// then the tile streams in row order.
constexpr uint32_t tileWidth = 512, tileHeight = 256;
constexpr size_t tableHeader = 8, tableEntry = 8;
// The encoded frame is written in place into a buffer of its final size.
struct Frame {
    std::unique_ptr<uint8_t[]> data;
    size_t size = 0;
};
void check(bool valid) { if (!valid) throw std::runtime_error("Invalid compressed RAW frame"); }
size_t pixels(int w, int h) {
    check(w >= 2 && h >= 2 && w <= 16384 && h <= 16384 && !(w & 1) && !(h & 1));
    check(uint64_t(w) * h <= 128 * 1024 * 1024);
    return size_t(w) * h;
}
uint32_t read16(const uint8_t* data) { return uint32_t(data[0]) | uint32_t(data[1]) << 8; }
uint32_t read32(const uint8_t* data) {
    return uint32_t(data[0]) | uint32_t(data[1]) << 8 | uint32_t(data[2]) << 16 | uint32_t(data[3]) << 24;
}
void put16(uint8_t* data, uint32_t value) { data[0] = uint8_t(value); data[1] = uint8_t(value >> 8); }
void put32(uint8_t* data, uint32_t value) { for (unsigned i = 0; i < 4; ++i) data[i] = uint8_t(value >> (i * 8)); }
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
// The codec works on full 16-bit samples, so frames need no value analysis before coding.
raw::ValueMap sampleRange() {
    raw::ValueMap map;
    map.maxValue = 0xFFFF;
    map.domain = raw::SampleDomain::forRange(65536);
    return map;
}
// Tile buffers are reused between frames, so coding a frame touches no fresh memory once they have grown.
using Tiles = std::vector<raw::ByteBuf>;
std::mutex tilesLock;
std::vector<Tiles> idleTiles;
constexpr size_t maximumIdleTiles = 3;
Tiles acquireTiles() {
    std::lock_guard lock(tilesLock);
    if (idleTiles.empty()) return {};
    Tiles found = std::move(idleTiles.back());
    idleTiles.pop_back();
    return found;
}
void releaseTiles(Tiles tiles) {
    if (tiles.empty()) return;
    std::lock_guard lock(tilesLock);
    if (idleTiles.size() >= maximumIdleTiles) idleTiles.erase(idleTiles.begin());
    idleTiles.push_back(std::move(tiles));
}
struct Encoded {
    Tiles tiles;
    std::vector<uint8_t> codecs;
    size_t count = 0, size = 0;
};
// Codes every tile of the frame on the calling thread; callers decide how many frames run at once.
Encoded encodeSamples(const raw::SampleSource& source, Tiles tiles) {
    Encoded result;
    result.tiles = std::move(tiles);
    const auto grid = raw::TileGrid::make(source.width, source.height, tileWidth, tileHeight);
    raw::ThreadPool pool(1);
    std::vector<uint32_t> checksums;
    check(raw::encodeFrame(source, sampleRange(), raw::kTileRealtime, false, grid, pool, result.tiles, result.codecs,
        checksums, nullptr));
    result.count = grid.count();
    result.size = tableHeader + result.count * tableEntry;
    for (size_t i = 0; i < result.count; ++i) result.size += result.tiles[i].size;
    check(result.size <= maximumBytes);
    return result;
}
void writePayload(const Encoded& encoded, uint8_t* output) {
    put16(output, tileWidth); put16(output + 2, tileHeight); put32(output + 4, uint32_t(encoded.count));
    uint8_t* entry = output + tableHeader;
    uint8_t* data = entry + encoded.count * tableEntry;
    for (size_t i = 0; i < encoded.count; ++i, entry += tableEntry) {
        const auto& tile = encoded.tiles[i];
        put32(entry, uint32_t(tile.size));
        entry[4] = encoded.codecs[i]; entry[5] = entry[6] = entry[7] = 0;
        if (tile.size) std::memcpy(data, tile.ptr(), tile.size);
        data += tile.size;
    }
}
// Splits a tile payload into its tiles. The payload must stay readable raw::kDecodePadding bytes past its end.
std::vector<raw::TileRef> parsePayload(const uint8_t* data, size_t size, int w, int h, raw::TileGrid& grid) {
    check(size >= tableHeader);
    const uint32_t tw = read16(data), th = read16(data + 2), count = read32(data + 4);
    check(tw >= 16 && tw <= raw::kMaxTileWidth && th >= 2 && th <= 1024 && !(tw & 1) && !(th & 1));
    grid = raw::TileGrid::make(uint32_t(w), uint32_t(h), tw, th);
    check(count == grid.count() && (size - tableHeader) / tableEntry >= count);
    std::vector<raw::TileRef> tiles(count);
    const uint8_t* entry = data + tableHeader;
    const uint8_t* next = entry + size_t(count) * tableEntry;
    size_t left = size - tableHeader - size_t(count) * tableEntry;
    for (uint32_t i = 0; i < count; ++i, entry += tableEntry) {
        const size_t bytes = read32(entry);
        const uint8_t codec = entry[4];
        check((codec == raw::kTileRealtime || codec == raw::kTileStored) && bytes <= left);
        tiles[i] = {next, bytes, codec};
        next += bytes;
        left -= bytes;
    }
    check(left == 0);
    return tiles;
}
class SampleRows final : public raw::RowSink {
public:
    SampleRows(uint8_t* output, int width) : output(output), width(size_t(width)) {}
    void write(uint32_t y, uint32_t x, uint32_t count, const uint16_t* samples) override {
        std::memcpy(output + (y * width + x) * 2, samples, size_t(count) * 2);
    }
private:
    uint8_t* output;
    size_t width;
};
void decodeSamples(const uint8_t* data, size_t size, int w, int h, uint8_t* output) {
    raw::TileGrid grid;
    const auto tiles = parsePayload(data, size, w, h, grid);
    raw::ThreadPool pool(1);
    SampleRows rows(output, w);
    check(raw::decodeFrame(sampleRange(), grid, tiles, rows, pool, nullptr, nullptr));
}
raw::SampleSource samples(const uint8_t* data, int w, int h, size_t stride, int format) {
    raw::SampleSource source;
    source.width = uint32_t(w);
    source.height = uint32_t(h);
    source.layout = format == 1 ? raw::SampleLayout::Mipi10 : format == 2 ? raw::SampleLayout::Mipi12 : raw::SampleLayout::U16LE;
    source.bits = format == 1 ? 10 : format == 2 ? 12 : 16;
    source.rows.resize(size_t(h));
    for (int y = 0; y < h; ++y) source.rows[size_t(y)] = data + size_t(y) * stride;
    return source;
}
uint16_t sampleAt(const uint8_t* line, int x, int format) {
    if (format == 1) {
        const auto* group = line + size_t(x / 4) * 5;
        const int index = x & 3;
        return uint16_t(unsigned(group[index]) << 2 | (group[4] >> (index * 2) & 3));
    }
    if (format == 2) {
        const auto* pair = line + size_t(x / 2) * 3;
        return x & 1 ? uint16_t(unsigned(pair[1]) << 4 | pair[2] >> 4) : uint16_t(unsigned(pair[0]) << 4 | (pair[2] & 15));
    }
    return uint16_t(line[size_t(x) * 2] | line[size_t(x) * 2 + 1] << 8);
}
void writeHeader(uint8_t* bytes, int w, int h, int period, int pw, int ph, size_t full, size_t preview) {
    std::memset(bytes, 0, headerSize);
    std::copy(signature.begin(), signature.end(), bytes);
    put32(bytes + 8, w); put32(bytes + 12, h); put32(bytes + 16, period);
    put32(bytes + 20, pw); put32(bytes + 24, ph); put32(bytes + 28, uint32_t(full)); put32(bytes + 32, uint32_t(preview));
}
struct Header {
    int w, h, period, pw, ph;
    size_t full, preview;
    Header(const uint8_t* bytes, size_t size) {
        check(size >= headerSize && std::equal(signature.begin(), signature.end(), bytes));
        w = int(read32(bytes + 8)); h = int(read32(bytes + 12));
        period = int(read32(bytes + 16)); pw = int(read32(bytes + 20)); ph = int(read32(bytes + 24));
        full = read32(bytes + 28); preview = read32(bytes + 32);
        pixels(w, h); pixels(pw, ph);
        check(read32(bytes + 36) == 0 && period >= 2 && period <= 16 && !(period & 1));
        check(pw <= w && ph <= h && pw <= 768 && ph <= 768 && pw % period == 0 && ph % period == 0);
        check(full >= tableHeader && full <= size - headerSize &&
            preview == size - headerSize - full && (preview == 0 || preview >= headerSize + tableHeader));
        check(preview > 0 || (w == pw && h == ph));
    }
};
Bytes preview(const Bytes& bytes) {
    const Header header(bytes.data(), bytes.size());
    if (header.preview == 0) return bytes;
    Bytes result(bytes.begin() + headerSize + header.full, bytes.end());
    const Header small(result.data(), result.size());
    check(small.w == header.pw && small.h == header.ph && small.period == header.period && small.preview == 0);
    raw::TileGrid grid;
    parsePayload(result.data() + headerSize, small.full, small.w, small.h, grid);
    return result;
}
size_t rowSize(int w, int format) {
    check(format >= 0 && format <= 2 && (format != 1 || w % 4 == 0));
    return format == 1 ? size_t(w) / 4 * 5 : format == 2 ? size_t(w) / 2 * 3 : size_t(w) * 2;
}
jlong Java_com_unspektrawesome_burst_RawFrameCompression_encode(JNIEnv* env, jobject, jobject source, jint w, jint h, jint stride, jint format, jint block) {
    try {
        pixels(w, h);
        const size_t row = rowSize(w, format);
        check(block >= 1 && block <= 8);
        const int period = block * 2;
        check(period <= w && period <= h && stride >= int(row));
        const auto* input = direct(env, source, size_t(h - 1) * stride + row);
        const int factor = std::max(1, (std::max(w, h) + 767) / 768);
        const int pw = std::max(period, w / factor / period * period), ph = std::max(period, h / factor / period * period);
        Bytes reduced;
        if (w != pw || h != ph) {
            std::vector<uint16_t> small(pixels(pw, ph));
            for (int y = 0; y < ph; ++y) {
                const int sy = std::min(h - period, int(int64_t(y / period) * h / (ph / period)) / period * period) + y % period;
                const auto* line = input + size_t(sy) * stride;
                for (int x = 0; x < pw; ++x) {
                    const int sx = std::min(w - period, int(int64_t(x / period) * w / (pw / period)) / period * period) + x % period;
                    small[size_t(y) * pw + x] = sampleAt(line, sx, format);
                }
            }
            const auto encoded = encodeSamples(samples(reinterpret_cast<const uint8_t*>(small.data()), pw, ph,
                size_t(pw) * 2, 0), {});
            reduced.resize(headerSize + encoded.size);
            writeHeader(reduced.data(), pw, ph, period, pw, ph, encoded.size, 0);
            writePayload(encoded, reduced.data() + headerSize);
        }
        auto encoded = encodeSamples(samples(input, w, h, size_t(stride), format), acquireTiles());
        check(headerSize + encoded.size + reduced.size() <= maximumBytes);
        auto result = std::make_unique<Frame>();
        result->size = headerSize + encoded.size + reduced.size();
        result->data = std::make_unique_for_overwrite<uint8_t[]>(result->size);
        auto* bytes = result->data.get();
        writeHeader(bytes, w, h, period, pw, ph, encoded.size, reduced.size());
        writePayload(encoded, bytes + headerSize);
        if (!reduced.empty()) std::memcpy(bytes + headerSize + encoded.size, reduced.data(), reduced.size());
        releaseTiles(std::move(encoded.tiles));
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
        check(value != nullptr && value->size >= headerSize);
        const size_t full = read32(value->data.get() + 28), preview = read32(value->data.get() + 32);
        check(headerSize + full + preview == value->size);
        if (reduced && preview > 0)
            return env->NewDirectByteBuffer(value->data.get() + headerSize + full, jlong(preview));
        return env->NewDirectByteBuffer(value->data.get(), jlong(value->size));
    } catch (...) { failure(env); return nullptr; }
}
void Java_com_unspektrawesome_burst_RawFrameCompression_release(JNIEnv*, jobject, jlong handle) { delete reinterpret_cast<Frame*>(handle); }
void Java_com_unspektrawesome_burst_RawFrameCompression_releaseScratch(JNIEnv*, jobject) {
    std::vector<Tiles> released;
    { std::lock_guard lock(tilesLock); released.swap(idleTiles); }
}
void Java_com_unspektrawesome_burst_RawFrameCompression_decode(JNIEnv* env, jobject, jbyteArray encoded, jobject destination) {
    try {
        auto bytes = fromJava(env, encoded);
        const Header header(bytes.data(), bytes.size());
        const size_t size = pixels(header.w, header.h) * 2;
        check(env->GetDirectBufferCapacity(destination) == jlong(size));
        auto* output = direct(env, destination, size);
        bytes.resize(bytes.size() + raw::kDecodePadding, 0);
        decodeSamples(bytes.data() + headerSize, header.full, header.w, header.h, output);
    } catch (...) { failure(env); }
}
jbyteArray Java_com_unspektrawesome_burst_RawFrameCompression_preview(JNIEnv* env, jobject, jbyteArray encoded) {
    try { return toJava(env, preview(fromJava(env, encoded))); }
    catch (...) { failure(env); return nullptr; }
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
        {"releaseScratch", "()V", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_releaseScratch)},
        {"decode", "([BLjava/nio/ByteBuffer;)V", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_decode)},
        {"preview", "([B)[B", reinterpret_cast<void*>(Java_com_unspektrawesome_burst_RawFrameCompression_preview)},
    };
    if (!authorizeNativeHost(env) || !registerNativeMethods(env, "com/unspektrawesome/burst/RawFrameCompression", methods) ||
        !registerPanorama(env) || !registerMultiFrameMerge(env)) return JNI_ERR;
    return initializeNativeDiagnostics(vm, env) ? JNI_VERSION_1_6 : JNI_ERR;
}
