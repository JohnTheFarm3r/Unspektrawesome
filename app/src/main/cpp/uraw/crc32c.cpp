#include "crc32c.h"

#include <cstring>

#if defined(__aarch64__) && !defined(_MSC_VER)
#include <arm_acle.h>
#if defined(__linux__) || defined(__ANDROID__)
#include <sys/auxv.h>
#ifndef HWCAP_CRC32
#define HWCAP_CRC32 (1 << 7)
#endif
#endif
#define URAW_CRC_ARM 1
#elif defined(__x86_64__) || defined(_M_X64)
#include <nmmintrin.h>
#if defined(_MSC_VER)
#include <intrin.h>
#else
#include <cpuid.h>
#endif
#define URAW_CRC_X86 1
#endif

namespace uraw::detail {
namespace {

struct Tables {
    uint32_t t[8][256];
    Tables() {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c >> 1) ^ (0x82F63B78u & (0u - (c & 1u)));
            t[0][i] = c;
        }
        for (uint32_t i = 0; i < 256; ++i)
            for (int s = 1; s < 8; ++s) t[s][i] = (t[s - 1][i] >> 8) ^ t[0][t[s - 1][i] & 0xFF];
    }
};

const Tables& tables() {
    static const Tables t;
    return t;
}

uint32_t crcSoft(uint32_t crc, const uint8_t* p, size_t n) {
    const auto& t = tables().t;
    crc = ~crc;
    while (n >= 8) {
        uint32_t lo, hi;
        std::memcpy(&lo, p, 4);
        std::memcpy(&hi, p + 4, 4);
        lo ^= crc;
        crc = t[7][lo & 0xFF] ^ t[6][(lo >> 8) & 0xFF] ^ t[5][(lo >> 16) & 0xFF] ^ t[4][lo >> 24] ^
              t[3][hi & 0xFF] ^ t[2][(hi >> 8) & 0xFF] ^ t[1][(hi >> 16) & 0xFF] ^ t[0][hi >> 24];
        p += 8;
        n -= 8;
    }
    while (n--) crc = (crc >> 8) ^ t[0][(crc ^ *p++) & 0xFF];
    return ~crc;
}

#if defined(URAW_CRC_ARM)
__attribute__((target("crc"))) uint32_t crcHw(uint32_t crc, const uint8_t* p, size_t n) {
    crc = ~crc;
    while (n >= 32) {
        uint64_t a, b, c, d;
        std::memcpy(&a, p, 8);
        std::memcpy(&b, p + 8, 8);
        std::memcpy(&c, p + 16, 8);
        std::memcpy(&d, p + 24, 8);
        crc = __crc32cd(crc, a);
        crc = __crc32cd(crc, b);
        crc = __crc32cd(crc, c);
        crc = __crc32cd(crc, d);
        p += 32;
        n -= 32;
    }
    while (n >= 8) {
        uint64_t a;
        std::memcpy(&a, p, 8);
        crc = __crc32cd(crc, a);
        p += 8;
        n -= 8;
    }
    while (n--) crc = __crc32cb(crc, *p++);
    return ~crc;
}

bool hasHw() {
#if defined(__APPLE__)
    return true;
#elif defined(__linux__) || defined(__ANDROID__)
    return (getauxval(AT_HWCAP) & HWCAP_CRC32) != 0;
#else
    return false;
#endif
}
#elif defined(URAW_CRC_X86)
#if !defined(_MSC_VER)
__attribute__((target("sse4.2")))
#endif
uint32_t crcHw(uint32_t crc, const uint8_t* p, size_t n) {
    uint64_t c = static_cast<uint32_t>(~crc);
    while (n >= 8) {
        uint64_t a;
        std::memcpy(&a, p, 8);
        c = _mm_crc32_u64(c, a);
        p += 8;
        n -= 8;
    }
    uint32_t c32 = static_cast<uint32_t>(c);
    while (n--) c32 = _mm_crc32_u8(c32, *p++);
    return ~c32;
}

bool hasHw() {
#if defined(_MSC_VER)
    int info[4];
    __cpuid(info, 1);
    return (info[2] & (1 << 20)) != 0;
#else
    unsigned a, b, c, d;
    if (!__get_cpuid(1, &a, &b, &c, &d)) return false;
    return (c & bit_SSE4_2) != 0;
#endif
}
#endif

// Polynomial arithmetic modulo the CRC polynomial, bit-reflected: x^0 is bit 31.
constexpr uint32_t kPoly = 0x82F63B78u;

uint32_t mulModP(uint32_t a, uint32_t b) {
    uint32_t product = 0;
    for (uint32_t m = 1u << 31; m; m >>= 1) {
        if (a & m) product ^= b;
        b = (b & 1) ? (b >> 1) ^ kPoly : b >> 1;
    }
    return product;
}

// powers[k] = x^(2^k) mod P
struct Powers {
    uint32_t p[67];
    Powers() {
        p[0] = 1u << 30;  // x^1
        for (int k = 1; k < 67; ++k) p[k] = mulModP(p[k - 1], p[k - 1]);
    }
};

const Powers& powers() {
    static const Powers t;
    return t;
}

}  // namespace

uint32_t crc32cCombine(uint32_t crcA, uint32_t crcB, uint64_t lengthB) {
    // crc(A || B) = crc(A) * x^(8 * len(B)) + crc(B)
    const auto& pw = powers().p;
    uint32_t shift = 1u << 31;  // x^0
    for (int k = 3; lengthB; lengthB >>= 1, ++k)
        if (lengthB & 1) shift = mulModP(pw[k], shift);
    return mulModP(shift, crcA) ^ crcB;
}

uint32_t crc32c(uint32_t crc, const void* data, size_t size) {
    const auto* p = static_cast<const uint8_t*>(data);
#if defined(URAW_CRC_ARM) || defined(URAW_CRC_X86)
    static const bool hw = hasHw();
    if (hw) return crcHw(crc, p, size);
#endif
    return crcSoft(crc, p, size);
}

}  // namespace uraw::detail
