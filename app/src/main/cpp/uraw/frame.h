#pragma once

#include "codec.h"
#include "common.h"
#include "samples.h"
#include "thread_pool.h"

namespace uraw::detail {

// Mapping between source samples and the coded value domain.
struct ValueMap {
    uint8_t shift = 0;        // trailing zero bits shared by every sample
    bool remap = false;       // samples replaced by their rank among the used values
    uint32_t maxValue = 0;    // largest source sample
    SampleDomain domain;
    std::vector<uint8_t> bitmap;     // used source values, maxValue + 1 bits (remap only)
    std::vector<uint16_t> forward;   // source value -> coded value (remap only)
    std::vector<uint16_t> inverse;   // coded value -> source value (remap only)

    // Rebuilds forward/inverse tables from `bitmap`. Used by the decoder.
    bool buildTables();
};

// Scans every sample once to find the value domain.
void analyzeSamples(const SampleSource& src, bool allowRemap, ThreadPool& pool, ValueMap& out);

struct TileGrid {
    uint32_t width = 0, height = 0;
    uint32_t tileWidth = 0, tileHeight = 0;
    uint32_t cols = 0, rows = 0;

    static TileGrid make(uint32_t width, uint32_t height, uint32_t tileWidth, uint32_t tileHeight) {
        TileGrid g;
        g.width = width;
        g.height = height;
        g.tileWidth = tileWidth;
        g.tileHeight = tileHeight;
        g.cols = (width + tileWidth - 1) / tileWidth;
        g.rows = (height + tileHeight - 1) / tileHeight;
        return g;
    }
    size_t count() const { return static_cast<size_t>(cols) * rows; }
};

bool encodeFrame(const SampleSource& src, const ValueMap& map, TileCodec codec, bool verify, const TileGrid& grid,
                 ThreadPool& pool, std::vector<ByteBuf>& tiles, std::vector<uint8_t>& tileCodecs,
                 std::vector<uint32_t>& tileCrcs, std::string* error);

// Receives decoded rows. Called concurrently for disjoint row segments.
class RowSink {
public:
    virtual ~RowSink() = default;
    virtual void write(uint32_t y, uint32_t x, uint32_t count, const uint16_t* samples) = 0;
};

struct TileRef {
    const uint8_t* data = nullptr;  // readable for size + kDecodePadding bytes
    size_t size = 0;
    uint8_t codec = 0;
};

// When tileCrcs is given, the CRC32C of every tile stream is computed on the way.
bool decodeFrame(const ValueMap& map, const TileGrid& grid, const std::vector<TileRef>& tiles, RowSink& sink,
                 ThreadPool& pool, std::vector<uint32_t>* tileCrcs, std::string* error);

}  // namespace uraw::detail
