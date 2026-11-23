#include "frame.h"

#include <algorithm>
#include <atomic>
#include <mutex>

#include "crc32c.h"

namespace uraw::detail {
namespace {

constexpr uint32_t kBandRows = 32;
constexpr size_t kBitmapWords = 65536 / 64;

struct TileScratch {
    std::vector<uint16_t> planes;
    std::vector<uint16_t> check;
    std::vector<uint16_t> residual;
    std::vector<uint16_t> row;
    std::vector<uint16_t> tileRows;        // unpacked sensor rows (realtime, non 16-bit sources)
    std::vector<const uint16_t*> rowPtr;   // sensor rows of the tile (realtime)
    size_t planeStride = 0;

    void prepare(const TileGrid& g) {
        planeStride = static_cast<size_t>((g.tileWidth + 1) / 2) * ((g.tileHeight + 1) / 2) + kRealtimePlanePadding;
        if (planes.size() < planeStride * 4) planes.resize(planeStride * 4);
        if (residual.size() < planeStride) residual.resize(planeStride);
        if (row.size() < g.tileWidth) row.resize(g.tileWidth);
        if (rowPtr.size() < g.tileHeight) rowPtr.resize(g.tileHeight);
    }
};

TileScratch& scratch() {
    thread_local TileScratch s;
    return s;
}

struct TileRect {
    uint32_t x0, y0, w, h;
};

TileRect tileRect(const TileGrid& g, size_t i) {
    const uint32_t tx = static_cast<uint32_t>(i % g.cols), ty = static_cast<uint32_t>(i / g.cols);
    TileRect r;
    r.x0 = tx * g.tileWidth;
    r.y0 = ty * g.tileHeight;
    r.w = std::min(g.tileWidth, g.width - r.x0);
    r.h = std::min(g.tileHeight, g.height - r.y0);
    return r;
}

// Plane p holds CFA position (p >> 1, p & 1) of the tile.
TilePlanes planesFor(uint16_t* base, size_t stride, const TileRect& r) {
    TilePlanes t;
    const uint32_t pw[2] = {(r.w + 1) / 2, r.w / 2};
    const uint32_t ph[2] = {(r.h + 1) / 2, r.h / 2};
    for (int p = 0; p < 4; ++p) {
        t.plane[p] = base + stride * p;
        t.width[p] = pw[p & 1];
        t.height[p] = ph[p >> 1];
    }
    return t;
}

}  // namespace

bool ValueMap::buildTables() {
    forward.clear();
    inverse.clear();
    if (!remap) return true;
    if (bitmap.size() < (static_cast<size_t>(maxValue) + 8) / 8) return false;
    forward.assign(static_cast<size_t>(maxValue) + 1, 0);
    inverse.reserve(domain.range);
    for (uint32_t v = 0; v <= maxValue; ++v) {
        if (bitmap[v >> 3] & (1u << (v & 7))) {
            forward[v] = static_cast<uint16_t>(inverse.size());
            inverse.push_back(static_cast<uint16_t>(v));
        }
    }
    return inverse.size() == domain.range && !inverse.empty();
}

void analyzeSamples(const SampleSource& src, bool allowRemap, ThreadPool& pool, ValueMap& out) {
    const uint32_t bands = (src.height + kBandRows - 1) / kBandRows;
    std::atomic<uint32_t> orAll{0}, maxAll{0};
    std::vector<uint64_t> used(allowRemap ? kBitmapWords : 0, 0);
    std::mutex usedMutex;

    pool.parallelFor(bands, [&](size_t band) {
        thread_local std::vector<uint16_t> row;
        thread_local std::vector<uint64_t> bits;
        if (row.size() < src.width) row.resize(src.width);
        if (allowRemap) bits.assign(kBitmapWords, 0);

        uint32_t o = 0, m = 0;
        const uint32_t y0 = static_cast<uint32_t>(band) * kBandRows;
        const uint32_t y1 = std::min(src.height, y0 + kBandRows);
        for (uint32_t y = y0; y < y1; ++y) {
            src.read(y, 0, src.width, row.data());
            const uint16_t* v = row.data();
            if (allowRemap) {
                uint64_t* b = bits.data();
                for (uint32_t x = 0; x < src.width; ++x) {
                    const uint32_t s = v[x];
                    o |= s;
                    m = std::max(m, s);
                    b[s >> 6] |= 1ull << (s & 63);
                }
            } else {
                for (uint32_t x = 0; x < src.width; ++x) {
                    o |= v[x];
                    m = std::max<uint32_t>(m, v[x]);
                }
            }
        }
        orAll.fetch_or(o, std::memory_order_relaxed);
        uint32_t prev = maxAll.load(std::memory_order_relaxed);
        while (prev < m && !maxAll.compare_exchange_weak(prev, m, std::memory_order_relaxed)) {
        }
        if (allowRemap) {
            std::lock_guard<std::mutex> lock(usedMutex);
            for (size_t i = 0; i < kBitmapWords; ++i) used[i] |= bits[i];
        }
    });

    const uint32_t orv = orAll.load();
    const uint32_t maxv = maxAll.load();
    out = ValueMap();
    out.maxValue = maxv;
    out.shift = orv ? static_cast<uint8_t>(ctz64(orv)) : 0;
    const uint32_t shiftedRange = (maxv >> out.shift) + 1;

    if (allowRemap) {
        uint32_t count = 0;
        for (uint32_t w = 0; w <= (maxv >> 6); ++w) count += popcount64(used[w]);
        if (static_cast<uint64_t>(count) * 4 <= static_cast<uint64_t>(shiftedRange) * 3) {
            out.remap = true;
            out.shift = 0;
            out.domain = SampleDomain::forRange(count);
            out.bitmap.assign((static_cast<size_t>(maxv) + 8) / 8, 0);
            for (uint32_t v = 0; v <= maxv; ++v)
                if (used[v >> 6] & (1ull << (v & 63))) out.bitmap[v >> 3] |= static_cast<uint8_t>(1u << (v & 7));
            out.buildTables();
            return;
        }
    }
    out.domain = SampleDomain::forRange(shiftedRange);
}

bool encodeFrame(const SampleSource& src, const ValueMap& map, TileCodec codec, bool verify, const TileGrid& grid,
                 ThreadPool& pool, std::vector<ByteBuf>& tiles, std::vector<uint8_t>& tileCodecs,
                 std::vector<uint32_t>& tileCrcs, std::string* error) {
    const size_t count = grid.count();
    if (tiles.size() < count) tiles.resize(count);
    tileCodecs.assign(count, kTileStored);
    tileCrcs.assign(count, 0);
    std::atomic<bool> failed{false};

    pool.parallelFor(count, [&](size_t i) {
        TileScratch& s = scratch();
        s.prepare(grid);
        const TileRect r = tileRect(grid, i);
        const TilePlanes tp = planesFor(s.planes.data(), s.planeStride, r);

        // Splits the tile into its four planes, applying the value map.
        auto gather = [&]() {
            const uint32_t top = map.domain.range - 1;
            uint16_t* row = s.row.data();
            for (uint32_t ry = 0; ry < r.h; ++ry) {
                src.read(r.y0 + ry, r.x0, r.w, row);
                if (map.remap) {
                    const uint16_t* fwd = map.forward.data();
                    const uint32_t mx = map.maxValue;
                    for (uint32_t x = 0; x < r.w; ++x) row[x] = fwd[std::min<uint32_t>(row[x], mx)];
                } else if (map.shift) {
                    for (uint32_t x = 0; x < r.w; ++x)
                        row[x] = static_cast<uint16_t>(std::min<uint32_t>(row[x] >> map.shift, top));
                } else if (top < 0xFFFF) {
                    for (uint32_t x = 0; x < r.w; ++x) row[x] = static_cast<uint16_t>(std::min<uint32_t>(row[x], top));
                }
                const int py = static_cast<int>(ry & 1);
                uint16_t* even = tp.plane[py * 2] + static_cast<size_t>(ry >> 1) * tp.width[py * 2];
                uint16_t* odd = tp.plane[py * 2 + 1] + static_cast<size_t>(ry >> 1) * tp.width[py * 2 + 1];
                uint32_t x = 0;
                for (; x + 1 < r.w; x += 2) {
                    even[x >> 1] = row[x];
                    odd[x >> 1] = row[x + 1];
                }
                if (x < r.w) even[x >> 1] = row[x];
            }
        };

        const size_t stored = storedTileBytes(tp, map.domain);
        ByteBuf& out = tiles[i];
        size_t written = 0;
        uint8_t used = codec;

        if (codec == kTileRealtime) {
            // Read the sensor rows in place when they already are 16-bit little-endian.
            const bool direct = src.layout == SampleLayout::U16LE;
            for (uint32_t ry = 0; ry < r.h; ++ry) {
                const uint8_t* rowStart = src.rows[r.y0 + ry];
                if (direct && (reinterpret_cast<uintptr_t>(rowStart) & 1) == 0) {
                    s.rowPtr[ry] = reinterpret_cast<const uint16_t*>(rowStart) + r.x0;
                } else {
                    if (s.tileRows.size() < static_cast<size_t>(grid.tileWidth) * grid.tileHeight)
                        s.tileRows.resize(static_cast<size_t>(grid.tileWidth) * grid.tileHeight);
                    uint16_t* dst = s.tileRows.data() + static_cast<size_t>(ry) * grid.tileWidth;
                    src.read(r.y0 + ry, r.x0, r.w, dst);
                    s.rowPtr[ry] = dst;
                }
            }
            out.prepare(std::max(stored + kEncodeSlack, realtimeMaxBytes(tp) + 16));
            written = encodeTileRealtime(s.rowPtr.data(), r.w, r.h, tp, out.ptr());
            if (written >= stored) {
                gather();
                used = kTileStored;
                encodeTile(kTileStored, tp, map.domain, out.ptr(), stored, s.residual.data(), written);
            } else if (verify) {
                gather();  // planes were used as residual scratch; restore the samples to compare against
            }
        } else {
            gather();
            out.prepare(stored + kEncodeSlack);
            if (codec == kTileStored ||
                !encodeTile(codec, tp, map.domain, out.ptr(), stored, s.residual.data(), written) ||
                written >= stored) {
                used = kTileStored;
                encodeTile(kTileStored, tp, map.domain, out.ptr(), stored, s.residual.data(), written);
            }
        }
        out.size = written;
        tileCodecs[i] = used;
        tileCrcs[i] = crc32c(0, out.ptr(), written);  // while the tile is still in cache

        if (verify) {
            if (s.check.size() < s.planeStride * 4) s.check.resize(s.planeStride * 4);
            // The decoder may read past the stream end; check against a padded copy.
            std::vector<uint8_t> padded(written + kDecodePadding, 0);
            std::memcpy(padded.data(), out.ptr(), written);
            const TilePlanes back = planesFor(s.check.data(), s.planeStride, r);
            bool same = decodeTile(static_cast<TileCodec>(used), padded.data(), written, back, map.domain,
                                   s.residual.data());
            for (int p = 0; same && p < 4; ++p)
                same = std::memcmp(back.plane[p], tp.plane[p],
                                   static_cast<size_t>(tp.width[p]) * tp.height[p] * 2) == 0;
            if (!same) failed.store(true);
        }
    });

    if (failed.load()) {
        setError(error, "verification failed: a tile did not decode to its source");
        return false;
    }
    return true;
}

bool decodeFrame(const ValueMap& map, const TileGrid& grid, const std::vector<TileRef>& tiles, RowSink& sink,
                 ThreadPool& pool, std::vector<uint32_t>* tileCrcs, std::string* error) {
    const size_t count = grid.count();
    if (tiles.size() != count) {
        setError(error, "tile count mismatch");
        return false;
    }
    if (tileCrcs) tileCrcs->assign(count, 0);
    std::atomic<bool> failed{false};

    pool.parallelFor(count, [&](size_t i) {
        if (failed.load(std::memory_order_relaxed)) return;
        TileScratch& s = scratch();
        s.prepare(grid);
        const TileRect r = tileRect(grid, i);
        const TilePlanes tp = planesFor(s.planes.data(), s.planeStride, r);
        const TileRef& t = tiles[i];
        if (tileCrcs) (*tileCrcs)[i] = crc32c(0, t.data, t.size);
        if (!decodeTile(static_cast<TileCodec>(t.codec), t.data, t.size, tp, map.domain, s.residual.data())) {
            failed.store(true);
            return;
        }
        uint16_t* row = s.row.data();
        for (uint32_t ry = 0; ry < r.h; ++ry) {
            const int py = static_cast<int>(ry & 1);
            const uint16_t* even = tp.plane[py * 2] + static_cast<size_t>(ry >> 1) * tp.width[py * 2];
            const uint16_t* odd = tp.plane[py * 2 + 1] + static_cast<size_t>(ry >> 1) * tp.width[py * 2 + 1];
            uint32_t x = 0;
            for (; x + 1 < r.w; x += 2) {
                row[x] = even[x >> 1];
                row[x + 1] = odd[x >> 1];
            }
            if (x < r.w) row[x] = even[x >> 1];
            if (map.remap) {
                const uint16_t* inv = map.inverse.data();
                for (uint32_t k = 0; k < r.w; ++k) row[k] = inv[row[k]];
            } else if (map.shift) {
                for (uint32_t k = 0; k < r.w; ++k) row[k] = static_cast<uint16_t>(row[k] << map.shift);
            }
            sink.write(r.y0 + ry, r.x0, r.w, row);
        }
    });

    if (failed.load()) {
        setError(error, "corrupt tile data");
        return false;
    }
    return true;
}

}  // namespace uraw::detail
