#include "codec.h"

#include <algorithm>

#include "bitio.h"

namespace uraw::detail {
namespace {

constexpr unsigned kBlock = 32;  // samples per Rice block in the fast codec

// Longest unary prefix before a sample is written verbatim. Keeps every
// regular code within 31 bits.
URAW_INLINE unsigned escapeQ(unsigned k) { return std::min(24u, 31u - k); }

URAW_INLINE unsigned maxK(const SampleDomain& d) { return d.qbpp ? std::min(d.qbpp - 1, 15u) : 0u; }

// Median edge detector: picks a, b or a + b - c depending on the local edge.
URAW_INLINE int med(int a, int b, int c) {
    const int mx = a > b ? a : b;
    const int mn = a > b ? b : a;
    return c >= mx ? mn : (c <= mn ? mx : a + b - c);
}

// Maps a prediction error to [0, range) using modular reduction and zigzag order.
struct Fold {
    int r, half, hi;
    explicit Fold(uint32_t range)
        : r(static_cast<int>(range)), half(static_cast<int>(range >> 1)), hi(r - half - 1) {}
    URAW_INLINE uint32_t operator()(int d) const {
        d += d < -half ? r : 0;
        d -= d > hi ? r : 0;
        const uint32_t ud = static_cast<uint32_t>(d);
        return (ud << 1) ^ static_cast<uint32_t>(d >> 31);
    }
};

URAW_INLINE int unfold(uint32_t u) { return static_cast<int>(u >> 1) ^ -static_cast<int>(u & 1); }

URAW_INLINE int wrap(int x, int r) {
    x += x < 0 ? r : 0;
    x -= x >= r ? r : 0;
    return x;
}

URAW_INLINE void putRice(BitWriter& bw, uint32_t v, unsigned k, unsigned qbpp) {
    const unsigned lq = escapeQ(k);
    const uint32_t q = v >> k;
    if (q < lq)
        bw.put((1ull << q) | (static_cast<uint64_t>(v & ((1u << k) - 1)) << (q + 1)), q + 1 + k);
    else
        bw.put((1ull << lq) | (static_cast<uint64_t>(v) << (lq + 1)), lq + 1 + qbpp);
}

URAW_INLINE uint32_t getRice(BitReader& br, unsigned k, unsigned qbpp) {
    br.refill();
    const unsigned lq = escapeQ(k);
    const uint64_t b = br.peek();
    const unsigned t = ctz64(b | (1ull << lq));
    if (t < lq) {
        const uint32_t v = (t << k) | static_cast<uint32_t>((b >> (t + 1)) & ((1ull << k) - 1));
        br.consume(t + 1 + k);
        return v;
    }
    br.consume(lq + 1);
    return br.get(qbpp);
}

// ---------------------------------------------------------------------------
// Fast codec

// Prediction residuals of one plane, folded to [0, range).
void computeResiduals(const uint16_t* URAW_RESTRICT P, uint32_t W, uint32_t H, uint32_t range,
                      uint16_t* URAW_RESTRICT U) {
    const Fold fold(range);
    const int half = static_cast<int>(range >> 1);

    U[0] = static_cast<uint16_t>(fold(static_cast<int>(P[0]) - half));
    for (uint32_t x = 1; x < W; ++x) U[x] = static_cast<uint16_t>(fold(static_cast<int>(P[x]) - P[x - 1]));

    for (uint32_t y = 1; y < H; ++y) {
        const uint16_t* URAW_RESTRICT cur = P + static_cast<size_t>(y) * W;
        const uint16_t* URAW_RESTRICT prev = cur - W;
        uint16_t* URAW_RESTRICT u = U + static_cast<size_t>(y) * W;
        u[0] = static_cast<uint16_t>(fold(static_cast<int>(cur[0]) - prev[0]));
        for (uint32_t x = 1; x < W; ++x) {
            const int a = cur[x - 1], b = prev[x], c = prev[x - 1];
            const int mx = a > b ? a : b;
            const int mn = a > b ? b : a;
            const int p = c >= mx ? mn : (c <= mn ? mx : a + b - c);
            u[x] = static_cast<uint16_t>(fold(static_cast<int>(cur[x]) - p));
        }
    }
}

void reconstruct(const uint16_t* URAW_RESTRICT U, uint32_t W, uint32_t H, uint32_t range,
                 uint16_t* URAW_RESTRICT P) {
    const int r = static_cast<int>(range);
    int a = wrap((r >> 1) + unfold(U[0]), r);
    P[0] = static_cast<uint16_t>(a);
    for (uint32_t x = 1; x < W; ++x) {
        a = wrap(a + unfold(U[x]), r);
        P[x] = static_cast<uint16_t>(a);
    }
    for (uint32_t y = 1; y < H; ++y) {
        uint16_t* URAW_RESTRICT cur = P + static_cast<size_t>(y) * W;
        const uint16_t* URAW_RESTRICT prev = cur - W;
        const uint16_t* URAW_RESTRICT u = U + static_cast<size_t>(y) * W;
        int c = prev[0];
        a = wrap(c + unfold(u[0]), r);
        cur[0] = static_cast<uint16_t>(a);
        for (uint32_t x = 1; x < W; ++x) {
            const int b = prev[x];
            a = wrap(med(a, b, c) + unfold(u[x]), r);
            cur[x] = static_cast<uint16_t>(a);
            c = b;
        }
    }
}

// Rice parameter with the lowest estimated cost for one block.
URAW_INLINE unsigned chooseK(const uint16_t* v, unsigned len, unsigned kmax, unsigned qbpp) {
    uint32_t sum = 0;
    for (unsigned j = 0; j < len; ++j) sum += v[j];
    if (sum == 0 || kmax == 0) return 0;
    const uint32_t mean = sum / len;
    const int k0 = mean ? static_cast<int>(bitWidth(mean)) - 1 : 0;
    const int lo = std::min(std::max(0, k0 - 1), static_cast<int>(kmax));
    const int hi = std::min(k0 + 1, static_cast<int>(kmax));
    unsigned best = static_cast<unsigned>(lo);
    uint32_t bestCost = UINT32_MAX;
    for (int k = lo; k <= hi; ++k) {
        const uint32_t cap = escapeQ(static_cast<unsigned>(k)) + qbpp - static_cast<unsigned>(k);
        uint32_t cost = len * static_cast<uint32_t>(k + 1);
        for (unsigned j = 0; j < len; ++j) cost += std::min<uint32_t>(v[j] >> k, cap);
        if (cost < bestCost) {
            bestCost = cost;
            best = static_cast<unsigned>(k);
        }
    }
    return best;
}

// Block header: the Rice parameter relative to the previous block.
URAW_INLINE void putK(BitWriter& bw, unsigned k, unsigned& prev) {
    if (k == prev)
        bw.put(1, 1);
    else if (k == prev + 1)
        bw.put(2, 2);
    else if (k + 1 == prev)
        bw.put(4, 3);
    else
        bw.put(static_cast<uint64_t>(k) << 3, 7);
    prev = k;
}

URAW_INLINE bool getK(BitReader& br, unsigned& prev) {
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
        prev = br.get(4);
    }
    return prev <= 15;
}

bool encodeFastPlane(const uint16_t* U, size_t n, const SampleDomain& d, BitWriter& bw, unsigned& prevK,
                     size_t limit) {
    const unsigned kmax = maxK(d);
    for (size_t i = 0; i < n; i += kBlock) {
        const unsigned len = static_cast<unsigned>(std::min<size_t>(kBlock, n - i));
        const uint16_t* v = U + i;
        const unsigned k = chooseK(v, len, kmax, d.qbpp);
        putK(bw, k, prevK);
        for (unsigned j = 0; j < len; ++j) putRice(bw, v[j], k, d.qbpp);
        if (bw.size() > limit) return false;
    }
    return true;
}

bool decodeFastPlane(BitReader& br, const uint8_t* end, uint16_t* U, size_t n, const SampleDomain& d,
                     unsigned& prevK) {
    const unsigned kmax = maxK(d);
    const unsigned qbpp = d.qbpp;
    const uint32_t range = d.range;
    uint32_t bad = 0;
    for (size_t i = 0; i < n; i += kBlock) {
        if (br.rawPointer() > end + 16) return false;
        if (!getK(br, prevK) || prevK > kmax) return false;
        const unsigned k = prevK;
        const unsigned lq = escapeQ(k);
        const uint64_t mask = (1ull << k) - 1;
        const unsigned len = static_cast<unsigned>(std::min<size_t>(kBlock, n - i));
        uint16_t* out = U + i;
        for (unsigned j = 0; j < len; ++j) {
            br.refill();
            const uint64_t b = br.peek();
            const unsigned t = ctz64(b | (1ull << lq));
            uint32_t v;
            if (t < lq) {
                v = (t << k) | static_cast<uint32_t>((b >> (t + 1)) & mask);
                br.consume(t + 1 + k);
            } else {
                br.consume(lq + 1);
                v = br.get(qbpp);
            }
            bad |= static_cast<uint32_t>(v >= range);
            out[j] = static_cast<uint16_t>(v);
        }
    }
    return bad == 0;
}

// ---------------------------------------------------------------------------
// Compact codec

struct Ctx {
    int32_t A, B, C, N;
};

constexpr int kContexts = 729;  // 9 * 9 * 9 quantized gradient triples

struct Thresholds {
    int t1, t2, t3;
};

Thresholds thresholdsFor(uint32_t range) {
    const int maxv = static_cast<int>(range) - 1;
    int t1, t2, t3;
    if (maxv >= 128) {
        const int f = (std::min(maxv, 4095) + 128) >> 8;
        t1 = f + 2;
        t2 = 4 * f + 3;
        t3 = 17 * f + 4;
    } else {
        const int f = 256 / (maxv + 1);
        t1 = std::max(2, 3 / f);
        t2 = std::max(t1, 7 / f);
        t3 = std::max(t2, 21 / f);
    }
    const int top = std::max(maxv, 1);
    t1 = std::clamp(t1, 1, top);
    t2 = std::clamp(t2, t1, std::max(t1, top));
    t3 = std::clamp(t3, t2, std::max(t2, top));
    return {t1, t2, t3};
}

struct CompactModel {
    Ctx ctx[kContexts];
    int8_t lut[1024];
    int t3 = 0;

    void reset(uint32_t range) {
        const Thresholds th = thresholdsFor(range);
        t3 = th.t3;
        for (int g = -t3; g <= t3; ++g) {
            int q;
            if (g <= -th.t3) q = -4;
            else if (g <= -th.t2) q = -3;
            else if (g <= -th.t1) q = -2;
            else if (g < 0) q = -1;
            else if (g == 0) q = 0;
            else if (g < th.t1) q = 1;
            else if (g < th.t2) q = 2;
            else if (g < th.t3) q = 3;
            else q = 4;
            lut[g + t3] = static_cast<int8_t>(q);
        }
        const int a0 = std::max(2, static_cast<int>((range + 32) >> 6));
        for (auto& c : ctx) c = {a0, 0, 0, 1};
    }

    URAW_INLINE int quant(int g) const {
        g = g < -t3 ? -t3 : (g > t3 ? t3 : g);
        return lut[g + t3];
    }
};

URAW_INLINE unsigned contextK(const Ctx& c, unsigned kmax) {
    unsigned k = 0;
    while ((c.N << k) < c.A && k < kmax) ++k;
    return k;
}

URAW_INLINE void updateContext(Ctx& c, int e) {
    c.B += e;
    c.A += e < 0 ? -e : e;
    if (c.N == 64) {
        c.A >>= 1;
        c.B = c.B >= 0 ? c.B >> 1 : -((1 - c.B) >> 1);
        c.N >>= 1;
    }
    c.N++;
    if (c.B <= -c.N) {
        c.B += c.N;
        if (c.C > -128) c.C--;
        if (c.B <= -c.N) c.B = -c.N + 1;
    } else if (c.B > 0) {
        c.B -= c.N;
        if (c.C < 127) c.C++;
        if (c.B > 0) c.B = 0;
    }
}

// Shared by encoder and decoder so both build exactly the same model.
template <bool kEncode>
bool compactPlane(CompactModel& m, uint16_t* P, uint32_t W, uint32_t H, const SampleDomain& d, BitWriter* bw,
                  size_t limit, BitReader* br, const uint8_t* end) {
    const int r = static_cast<int>(d.range);
    const int half = r >> 1;
    const unsigned kmax = maxK(d);
    const Fold fold(d.range);
    m.reset(d.range);

    for (uint32_t y = 0; y < H; ++y) {
        uint16_t* cur = P + static_cast<size_t>(y) * W;
        const uint16_t* prev = y ? cur - W : nullptr;
        for (uint32_t x = 0; x < W; ++x) {
            if constexpr (!kEncode) {
                if ((x & 31) == 0 && br->rawPointer() > end + 16) return false;
            }
            int a, b, c, dn;
            if (!prev) {
                a = b = c = dn = x ? cur[x - 1] : half;
            } else {
                b = prev[x];
                if (x) {
                    a = cur[x - 1];
                    c = prev[x - 1];
                } else {
                    a = c = b;
                }
                dn = x + 1 < W ? prev[x + 1] : b;
            }
            int q1 = m.quant(dn - b), q2 = m.quant(b - c), q3 = m.quant(c - a);
            int sign = 1;
            if (q1 < 0 || (q1 == 0 && (q2 < 0 || (q2 == 0 && q3 < 0)))) {
                q1 = -q1;
                q2 = -q2;
                q3 = -q3;
                sign = -1;
            }
            Ctx& cx = m.ctx[(q1 + 4) * 81 + (q2 + 4) * 9 + (q3 + 4)];
            int px = med(a, b, c) + (sign > 0 ? cx.C : -cx.C);
            px = px < 0 ? 0 : (px >= r ? r - 1 : px);
            const unsigned k = contextK(cx, kmax);
            int e;
            if constexpr (kEncode) {
                int diff = static_cast<int>(cur[x]) - px;
                if (sign < 0) diff = -diff;
                const uint32_t u = fold(diff);
                e = unfold(u);
                putRice(*bw, u, k, d.qbpp);
            } else {
                const uint32_t u = getRice(*br, k, d.qbpp);
                if (u >= d.range) return false;
                e = unfold(u);
                cur[x] = static_cast<uint16_t>(wrap(px + (sign > 0 ? e : -e), r));
            }
            updateContext(cx, e);
        }
        if constexpr (kEncode) {
            if (bw->size() > limit) return false;
        }
    }
    return true;
}

// One model per thread, allocated on first use.
CompactModel& compactModel() {
    thread_local std::unique_ptr<CompactModel> model(new CompactModel());
    return *model;
}

}  // namespace

size_t storedTileBytes(const TilePlanes& t, const SampleDomain& d) {
    return (t.pixels() * d.qbpp + 7) / 8;
}

bool encodeTile(TileCodec codec, const TilePlanes& t, const SampleDomain& d, uint8_t* out, size_t limit,
                uint16_t* scratch, size_t& written) {
    BitWriter bw(out);
    written = 0;
    switch (codec) {
        case kTileStored:
            for (int p = 0; p < 4; ++p) {
                const size_t n = static_cast<size_t>(t.width[p]) * t.height[p];
                const uint16_t* v = t.plane[p];
                for (size_t i = 0; i < n; ++i) bw.put(v[i], d.qbpp);
            }
            written = bw.finish();
            return true;
        case kTileFast: {
            unsigned prevK = 0;
            for (int p = 0; p < 4; ++p) {
                const uint32_t W = t.width[p], H = t.height[p];
                if (W == 0 || H == 0) continue;
                computeResiduals(t.plane[p], W, H, d.range, scratch);
                if (!encodeFastPlane(scratch, static_cast<size_t>(W) * H, d, bw, prevK, limit)) return false;
            }
            break;
        }
        case kTileCompact: {
            CompactModel& m = compactModel();
            for (int p = 0; p < 4; ++p) {
                const uint32_t W = t.width[p], H = t.height[p];
                if (W == 0 || H == 0) continue;
                if (!compactPlane<true>(m, t.plane[p], W, H, d, &bw, limit, nullptr, nullptr)) return false;
            }
            break;
        }
        default:
            return false;
    }
    written = bw.finish();
    return written <= limit;
}

bool decodeTile(TileCodec codec, const uint8_t* in, size_t size, const TilePlanes& t, const SampleDomain& d,
                uint16_t* scratch) {
    BitReader br(in);
    const uint8_t* end = in + size;
    switch (codec) {
        case kTileStored: {
            if (size < storedTileBytes(t, d)) return false;
            uint32_t bad = 0;
            for (int p = 0; p < 4; ++p) {
                const size_t n = static_cast<size_t>(t.width[p]) * t.height[p];
                uint16_t* v = t.plane[p];
                for (size_t i = 0; i < n; ++i) {
                    br.refill();
                    const uint32_t s = br.get(d.qbpp);
                    bad |= static_cast<uint32_t>(s >= d.range);
                    v[i] = static_cast<uint16_t>(s);
                }
            }
            return bad == 0;
        }
        case kTileFast: {
            unsigned prevK = 0;
            for (int p = 0; p < 4; ++p) {
                const uint32_t W = t.width[p], H = t.height[p];
                if (W == 0 || H == 0) continue;
                if (!decodeFastPlane(br, end, scratch, static_cast<size_t>(W) * H, d, prevK)) return false;
                reconstruct(scratch, W, H, d.range, t.plane[p]);
            }
            break;
        }
        case kTileCompact: {
            CompactModel& m = compactModel();
            for (int p = 0; p < 4; ++p) {
                const uint32_t W = t.width[p], H = t.height[p];
                if (W == 0 || H == 0) continue;
                if (!compactPlane<false>(m, t.plane[p], W, H, d, nullptr, 0, &br, end)) return false;
            }
            break;
        }
        case kTileRealtime:
            return decodeTileRealtime(in, size, t, scratch);
        default:
            return false;
    }
    return br.position() <= end + 8;
}

}  // namespace uraw::detail
