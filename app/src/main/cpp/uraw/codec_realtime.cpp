// Realtime tile codec.
//
// Each CFA plane is predicted from the plane row above only:
//     pred = avg(up, avg(up_left, up_right))      (floor averages)
// The first row of a plane uses its left neighbour. Residuals are taken
// modulo 2^16 and zigzag mapped, so any 16-bit input works without a value
// analysis pass. Because no prediction depends on the current row, both
// directions run 8 samples per instruction.
//
// Residuals are coded in blocks of 16 at the bit width of the block maximum.
// A block of width w takes exactly 2w bytes, so blocks stay byte aligned.
//
// Low bits that are zero in every sample of the tile (for example 10-bit data
// scaled to 14 bits) are shifted out before prediction.
//
// Tile stream: u32 payloadBytes, u8 shift, payload (all blocks, plane order),
// then a bit stream with one width code per block:
//     "1" same as previous, "01" previous + 1, "001" previous - 1, "000" + 5 bits.

#include <algorithm>

#include "bitio.h"
#include "codec.h"

#if defined(__aarch64__) && !defined(_MSC_VER)
#include <arm_neon.h>
#define URAW_RT_NEON 1
#endif

namespace uraw::detail {
namespace {

constexpr unsigned kBlock = 16;

URAW_INLINE uint16_t avg(uint16_t a, uint16_t b) { return static_cast<uint16_t>((a & b) + ((a ^ b) >> 1)); }

URAW_INLINE uint16_t zigzag(uint16_t e) {
    return static_cast<uint16_t>((e << 1) ^ static_cast<uint16_t>(-(e >> 15)));
}

URAW_INLINE uint16_t unzigzag(uint16_t u) { return static_cast<uint16_t>((u >> 1) ^ static_cast<uint16_t>(-(u & 1))); }

size_t paddedCount(size_t n) { return (n + kBlock - 1) / kBlock * kBlock; }

#if defined(URAW_RT_NEON)
URAW_INLINE uint16x8_t zigzagV(uint16x8_t e) {
    return veorq_u16(vshlq_n_u16(e, 1), vreinterpretq_u16_s16(vshrq_n_s16(vreinterpretq_s16_u16(e), 15)));
}
URAW_INLINE uint16x8_t unzigzagV(uint16x8_t u) {
    return veorq_u16(vshrq_n_u16(u, 1), vreinterpretq_u16_s16(vnegq_s16(vreinterpretq_s16_u16(vandq_u16(u, vdupq_n_u16(1))))));
}
#endif

// OR of all samples of the tile rows.
uint16_t orRows(const uint16_t* const* rows, uint32_t tw, uint32_t th) {
    uint16_t acc = 0;
    for (uint32_t r = 0; r < th; ++r) {
        const uint16_t* v = rows[r];
        uint32_t x = 0;
#if defined(URAW_RT_NEON)
        uint16x8_t a = vdupq_n_u16(0);
        for (; x + 8 <= tw; x += 8) a = vorrq_u16(a, vld1q_u16(v + x));
        const uint16x4_t h = vorr_u16(vget_low_u16(a), vget_high_u16(a));
        acc |= static_cast<uint16_t>(vget_lane_u16(h, 0) | vget_lane_u16(h, 1) | vget_lane_u16(h, 2) |
                                     vget_lane_u16(h, 3));
#endif
        for (; x < tw; ++x) acc |= v[x];
        if (acc & 1) break;  // no shared zero bits left
    }
    return acc;
}

// Residuals of one sensor row for both plane parities, samples shifted right by s.
// cur/up: sensor rows (up = two rows above, nullptr for the first plane row).
void residualRow(const uint16_t* URAW_RESTRICT cur, const uint16_t* URAW_RESTRICT up, uint32_t tw, unsigned s,
                 uint16_t* URAW_RESTRICT r0, uint16_t* URAW_RESTRICT r1) {
    const uint32_t pw[2] = {(tw + 1) / 2, tw / 2};
    uint16_t* r[2] = {r0, r1};
    if (!up) {
        for (int p = 0; p < 2; ++p) {
            uint16_t prev = 0;
            for (uint32_t x = 0; x < pw[p]; ++x) {
                const uint16_t v = static_cast<uint16_t>(cur[2 * x + p] >> s);
                r[p][x] = zigzag(static_cast<uint16_t>(v - prev));
                prev = v;
            }
        }
        return;
    }
    uint32_t x = 1;
#if defined(URAW_RT_NEON)
    const int16x8_t sh = vdupq_n_s16(static_cast<int16_t>(-static_cast<int>(s)));
    for (; 2 * x + 18 <= tw; x += 8) {
        const uint16x8x2_t cv = vld2q_u16(cur + 2 * x);
        const uint16x8x2_t bv = vld2q_u16(up + 2 * x);
        const uint16x8x2_t lv = vld2q_u16(up + 2 * x - 2);
        const uint16x8x2_t rv = vld2q_u16(up + 2 * x + 2);
        for (int p = 0; p < 2; ++p) {
            const uint16x8_t b = vshlq_u16(bv.val[p], sh);
            const uint16x8_t l = vshlq_u16(lv.val[p], sh);
            const uint16x8_t rr = vshlq_u16(rv.val[p], sh);
            const uint16x8_t pred = vhaddq_u16(b, vhaddq_u16(l, rr));
            vst1q_u16(r[p] + x, zigzagV(vsubq_u16(vshlq_u16(cv.val[p], sh), pred)));
        }
    }
#endif
    for (int p = 0; p < 2; ++p) {
        const uint32_t w = pw[p];
        if (w == 0) continue;
        auto one = [&](uint32_t i) {
            const uint16_t b = static_cast<uint16_t>(up[2 * i + p] >> s);
            const uint16_t c = i ? static_cast<uint16_t>(up[2 * i - 2 + p] >> s) : b;
            const uint16_t d = i + 1 < w ? static_cast<uint16_t>(up[2 * i + 2 + p] >> s) : b;
            r[p][i] = zigzag(static_cast<uint16_t>((cur[2 * i + p] >> s) - avg(b, avg(c, d))));
        };
        one(0);
        for (uint32_t i = x; i < w; ++i) one(i);
    }
}

// Rebuilds one plane from its residuals.
void reconstructPlane(const uint16_t* URAW_RESTRICT u, uint32_t W, uint32_t H, uint16_t* URAW_RESTRICT P) {
    uint16_t prev = 0;
    for (uint32_t x = 0; x < W; ++x) {
        prev = static_cast<uint16_t>(prev + unzigzag(u[x]));
        P[x] = prev;
    }
    for (uint32_t y = 1; y < H; ++y) {
        const uint16_t* URAW_RESTRICT up = P + static_cast<size_t>(y - 1) * W;
        uint16_t* URAW_RESTRICT cur = P + static_cast<size_t>(y) * W;
        const uint16_t* URAW_RESTRICT ur = u + static_cast<size_t>(y) * W;
        auto one = [&](uint32_t i) {
            const uint16_t b = up[i];
            const uint16_t c = i ? up[i - 1] : b;
            const uint16_t d = i + 1 < W ? up[i + 1] : b;
            cur[i] = static_cast<uint16_t>(avg(b, avg(c, d)) + unzigzag(ur[i]));
        };
        one(0);
        uint32_t x = 1;
#if defined(URAW_RT_NEON)
        for (; x + 9 <= W; x += 8) {
            const uint16x8_t pred = vhaddq_u16(vld1q_u16(up + x), vhaddq_u16(vld1q_u16(up + x - 1), vld1q_u16(up + x + 1)));
            vst1q_u16(cur + x, vaddq_u16(pred, unzigzagV(vld1q_u16(ur + x))));
        }
#else
        for (; x + 1 < W; ++x)
            cur[x] = static_cast<uint16_t>(avg(up[x], avg(up[x - 1], up[x + 1])) + unzigzag(ur[x]));
#endif
        for (; x < W; ++x) one(x);
    }
}

URAW_INLINE unsigned blockWidth(const uint16_t* v) {
#if defined(URAW_RT_NEON)
    return bitWidth(vmaxvq_u16(vmaxq_u16(vld1q_u16(v), vld1q_u16(v + 8))));
#else
    uint16_t m = 0;
    for (unsigned j = 0; j < kBlock; ++j) m |= v[j];
    return bitWidth(m);
#endif
}

// 16 values of w bits -> 2w bytes. May write up to 8 bytes past the block.
URAW_INLINE void pack16(const uint16_t* v, unsigned w, uint8_t* p) {
    uint64_t acc = 0;
    unsigned n = 0;
    for (unsigned j = 0; j < kBlock; j += 2) {
        acc |= (static_cast<uint64_t>(v[j]) | (static_cast<uint64_t>(v[j + 1]) << w)) << n;
        n += 2 * w;
        store64(p, acc);
        const unsigned bytes = n >> 3;
        p += bytes;
        acc >>= bytes * 8;
        n &= 7;
    }
}

URAW_INLINE void unpack16(const uint8_t* p, unsigned w, uint16_t* v) {
    const uint64_t m = (1ull << w) - 1;
    for (unsigned j = 0; j < kBlock; j += 2) {
        const unsigned bit = j * w;
        const uint64_t x = load64(p + (bit >> 3)) >> (bit & 7);
        v[j] = static_cast<uint16_t>(x & m);
        v[j + 1] = static_cast<uint16_t>((x >> w) & m);
    }
}

URAW_INLINE void putWidth(BitWriter& bw, unsigned w, unsigned& prev) {
    if (w == prev)
        bw.put(1, 1);
    else if (w == prev + 1)
        bw.put(2, 2);
    else if (w + 1 == prev)
        bw.put(4, 3);
    else
        bw.put(static_cast<uint64_t>(w) << 3, 8);
    prev = w;
}

URAW_INLINE bool getWidth(BitReader& br, unsigned& prev) {
    br.refill();
    const unsigned t = ctz64(br.peek() | 8);
    if (t == 0) {
        br.consume(1);
    } else if (t == 1) {
        br.consume(2);
        prev += 1;
    } else if (t == 2) {
        br.consume(3);
        prev -= 1;
    } else {
        br.consume(3);
        prev = br.get(5);
    }
    return prev <= 16;
}

}  // namespace

size_t realtimeMaxBytes(const TilePlanes& t) {
    size_t blocks = 0;
    for (int p = 0; p < 4; ++p) blocks += paddedCount(static_cast<size_t>(t.width[p]) * t.height[p]) / kBlock;
    return 5 + blocks * 2 * kBlock + blocks + 64;
}

size_t encodeTileRealtime(const uint16_t* const* rows, uint32_t tw, uint32_t th, const TilePlanes& layout,
                          uint8_t* out) {
    const uint16_t orv = orRows(rows, tw, th);
    const unsigned shift = orv ? std::min(ctz64(orv), 15u) : 0u;

    // Residuals of every plane.
    for (uint32_t r = 0; r < th; ++r) {
        const int py = static_cast<int>(r & 1);
        const uint32_t prow = r >> 1;
        uint16_t* r0 = layout.plane[py * 2] + static_cast<size_t>(prow) * layout.width[py * 2];
        uint16_t* r1 = layout.plane[py * 2 + 1] + static_cast<size_t>(prow) * layout.width[py * 2 + 1];
        residualRow(rows[r], prow ? rows[r - 2] : nullptr, tw, shift, r0, r1);
    }

    size_t totalBlocks = 0;
    for (int pl = 0; pl < 4; ++pl)
        totalBlocks += paddedCount(static_cast<size_t>(layout.width[pl]) * layout.height[pl]) / kBlock;

    // Width codes go after the payload; collect them separately (at most 8 bits per block).
    thread_local std::vector<uint8_t> hdrBuf;
    if (hdrBuf.size() < totalBlocks + 64) hdrBuf.resize(totalBlocks + 64);
    BitWriter hw(hdrBuf.data());

    out[4] = static_cast<uint8_t>(shift);
    uint8_t* payload = out + 5;
    uint8_t* p = payload;
    unsigned prev = 0;
    for (int pl = 0; pl < 4; ++pl) {
        const size_t n = static_cast<size_t>(layout.width[pl]) * layout.height[pl];
        if (n == 0) continue;
        uint16_t* u = layout.plane[pl];
        const size_t padded = paddedCount(n);
        std::fill(u + n, u + padded, static_cast<uint16_t>(0));
        for (size_t i = 0; i < padded; i += kBlock) {
            const unsigned w = blockWidth(u + i);
            putWidth(hw, w, prev);
            if (w) {
                pack16(u + i, w, p);
                p += 2 * w;
            }
        }
    }
    const uint32_t payloadBytes = static_cast<uint32_t>(p - payload);
    std::memcpy(out, &payloadBytes, 4);
    const size_t hdrBytes = hw.finish();
    std::memcpy(p, hdrBuf.data(), hdrBytes);
    return 5 + payloadBytes + hdrBytes;
}

bool decodeTileRealtime(const uint8_t* in, size_t size, const TilePlanes& t, uint16_t* scratch) {
    if (size < 5) return false;
    const uint32_t payloadBytes = load32(in);
    const unsigned shift = in[4];
    if (payloadBytes > size - 5 || shift > 15) return false;
    const uint8_t* p = in + 5;
    const uint8_t* end = p + payloadBytes;
    const uint8_t* hdrEnd = in + size;
    BitReader br(end);
    unsigned prev = 0;
    for (int pl = 0; pl < 4; ++pl) {
        const uint32_t W = t.width[pl], H = t.height[pl];
        const size_t n = static_cast<size_t>(W) * H;
        if (n == 0) continue;
        const size_t padded = paddedCount(n);
        for (size_t i = 0; i < padded; i += kBlock) {
            if (br.rawPointer() > hdrEnd + 16) return false;
            if (!getWidth(br, prev)) return false;
            const unsigned w = prev;
            if (static_cast<size_t>(end - p) < 2 * w) return false;
            if (w) {
                unpack16(p, w, scratch + i);
                p += 2 * w;
            } else {
                std::memset(scratch + i, 0, kBlock * 2);
            }
        }
        reconstructPlane(scratch, W, H, t.plane[pl]);
        if (shift) {
            uint16_t* v = t.plane[pl];
            for (size_t i = 0; i < n; ++i) v[i] = static_cast<uint16_t>(v[i] << shift);
        }
    }
    return p == end && br.position() <= hdrEnd + 8;
}

}  // namespace uraw::detail
