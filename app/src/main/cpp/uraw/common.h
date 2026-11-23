#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#if defined(_MSC_VER)
#include <intrin.h>
#define URAW_INLINE __forceinline
#define URAW_RESTRICT __restrict
#else
#define URAW_INLINE inline __attribute__((always_inline))
#define URAW_RESTRICT __restrict__
#endif

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "uraw requires a little-endian target"
#endif

namespace uraw::detail {

URAW_INLINE unsigned ctz64(uint64_t v) {  // v must be non-zero
#if defined(_MSC_VER)
    unsigned long i;
    _BitScanForward64(&i, v);
    return static_cast<unsigned>(i);
#else
    return static_cast<unsigned>(__builtin_ctzll(v));
#endif
}

// Number of bits needed to represent v (0 for v == 0).
URAW_INLINE unsigned bitWidth(uint32_t v) {
#if defined(_MSC_VER)
    unsigned long i;
    return _BitScanReverse(&i, v) ? static_cast<unsigned>(i) + 1 : 0;
#else
    return v ? 32u - static_cast<unsigned>(__builtin_clz(v)) : 0;
#endif
}

URAW_INLINE unsigned popcount64(uint64_t v) {
#if defined(_MSC_VER) && (defined(_M_X64) || defined(_M_ARM64))
    v = v - ((v >> 1) & 0x5555555555555555ull);
    v = (v & 0x3333333333333333ull) + ((v >> 2) & 0x3333333333333333ull);
    v = (v + (v >> 4)) & 0x0F0F0F0F0F0F0F0Full;
    return static_cast<unsigned>((v * 0x0101010101010101ull) >> 56);
#else
    return static_cast<unsigned>(__builtin_popcountll(v));
#endif
}

URAW_INLINE uint64_t load64(const void* p) {
    uint64_t v;
    std::memcpy(&v, p, 8);
    return v;
}
URAW_INLINE void store64(void* p, uint64_t v) { std::memcpy(p, &v, 8); }
URAW_INLINE uint32_t load32(const void* p) {
    uint32_t v;
    std::memcpy(&v, p, 4);
    return v;
}
URAW_INLINE uint16_t load16(const void* p) {
    uint16_t v;
    std::memcpy(&v, p, 2);
    return v;
}
URAW_INLINE uint16_t bswap16(uint16_t v) { return static_cast<uint16_t>((v >> 8) | (v << 8)); }

// Growable byte buffer that does not zero its memory.
struct ByteBuf {
    std::unique_ptr<uint8_t[]> data;
    size_t size = 0;
    size_t capacity = 0;

    // Ensures capacity for n bytes and empties the buffer. Old contents are lost.
    void prepare(size_t n) {
        if (n > capacity) {
            data.reset(new uint8_t[n]);
            capacity = n;
        }
        size = 0;
    }
    uint8_t* ptr() { return data.get(); }
    const uint8_t* ptr() const { return data.get(); }
};

// Little-endian serializer for headers.
class ByteWriter {
public:
    std::vector<uint8_t> buf;

    void u8(uint8_t v) { buf.push_back(v); }
    void u16(uint16_t v) { raw(&v, 2); }
    void u32(uint32_t v) { raw(&v, 4); }
    void u64(uint64_t v) { raw(&v, 8); }
    void i64(int64_t v) { raw(&v, 8); }
    void raw(const void* p, size_t n) {
        if (n == 0) return;
        const auto* b = static_cast<const uint8_t*>(p);
        buf.insert(buf.end(), b, b + n);
    }
    size_t size() const { return buf.size(); }
};

// Bounds-checked little-endian deserializer. Any overrun clears `ok`.
struct ByteCursor {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;

    ByteCursor(const uint8_t* data, size_t size) : p(data), end(data + size) {}

    bool need(size_t n) {
        if (!ok || static_cast<size_t>(end - p) < n) {
            ok = false;
            return false;
        }
        return true;
    }
    template <typename T>
    T get() {
        T v{};
        if (need(sizeof(T))) {
            std::memcpy(&v, p, sizeof(T));
            p += sizeof(T);
        }
        return v;
    }
    uint8_t u8() { return get<uint8_t>(); }
    uint16_t u16() { return get<uint16_t>(); }
    uint32_t u32() { return get<uint32_t>(); }
    uint64_t u64() { return get<uint64_t>(); }
    int64_t i64() { return get<int64_t>(); }
    const uint8_t* bytes(size_t n) {
        if (!need(n)) return nullptr;
        const uint8_t* r = p;
        p += n;
        return r;
    }
    size_t remaining() const { return static_cast<size_t>(end - p); }
};

inline void setError(std::string* err, const std::string& msg) {
    if (err) *err = msg;
}

}  // namespace uraw::detail
