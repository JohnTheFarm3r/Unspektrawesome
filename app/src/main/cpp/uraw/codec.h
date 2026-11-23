#pragma once

#include "common.h"

namespace uraw::detail {

enum TileCodec : uint8_t {
    kTileStored = 0,    // fixed width samples
    kTileFast = 1,      // MED prediction + block adaptive Rice
    kTileCompact = 2,   // MED prediction + context modelling + adaptive Rice
    kTileRealtime = 3,  // row-above prediction + fixed width blocks, 16-bit modular
};

// Value domain of the samples in a frame: every sample is in [0, range).
struct SampleDomain {
    uint32_t range = 1;
    unsigned qbpp = 0;  // bits needed for range - 1

    static SampleDomain forRange(uint32_t range) {
        SampleDomain d;
        d.range = range ? range : 1;
        d.qbpp = bitWidth(d.range - 1);
        return d;
    }
};

// The four CFA planes of one tile, each stored contiguously.
struct TilePlanes {
    uint16_t* plane[4] = {};
    uint32_t width[4] = {};
    uint32_t height[4] = {};

    size_t pixels() const {
        size_t n = 0;
        for (int i = 0; i < 4; ++i) n += static_cast<size_t>(width[i]) * height[i];
        return n;
    }
};

// Bytes of a stored tile.
size_t storedTileBytes(const TilePlanes& t, const SampleDomain& d);

// Widest tile the encoder accepts. Bounds how far the encoder can overrun
// its limit between checks.
constexpr uint32_t kMaxTileWidth = 1024;
// Extra bytes the encoder may write past its limit before noticing.
constexpr size_t kEncodeSlack = 4096;
// Bytes the decoder may read past the end of a tile stream.
constexpr size_t kDecodePadding = 512;

// Encodes a tile into `out` and stores the byte count in `written`. Returns
// false when the result would exceed `limit` bytes (stored tiles never fail).
// `out` must hold max(limit, storedTileBytes) + kEncodeSlack bytes.
// `scratch` must hold the largest plane.
bool encodeTile(TileCodec codec, const TilePlanes& t, const SampleDomain& d, uint8_t* out, size_t limit,
                uint16_t* scratch, size_t& written);

// Realtime codec. Works on full 16-bit samples, so it needs no value analysis.
// Plane buffers passed to it need kRealtimePlanePadding spare samples.
constexpr size_t kRealtimePlanePadding = 16;

// Upper bound of the encoded size of a realtime tile.
size_t realtimeMaxBytes(const TilePlanes& t);

// Encodes a tile straight from its sensor rows: rows[r] points at the tile's
// first sample in sensor row r, tw samples per row. The plane buffers of
// `layout` are used as residual scratch. `out` must hold realtimeMaxBytes.
// Returns the encoded size.
size_t encodeTileRealtime(const uint16_t* const* rows, uint32_t tw, uint32_t th, const TilePlanes& layout,
                          uint8_t* out);
bool decodeTileRealtime(const uint8_t* in, size_t size, const TilePlanes& t, uint16_t* scratch);

// Decodes a tile into t's planes. `in` must be readable for size + kDecodePadding bytes.
bool decodeTile(TileCodec codec, const uint8_t* in, size_t size, const TilePlanes& t, const SampleDomain& d,
                uint16_t* scratch);

}  // namespace uraw::detail
