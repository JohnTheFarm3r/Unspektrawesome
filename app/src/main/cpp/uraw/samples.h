#pragma once

#include "common.h"

namespace uraw::detail {

// How CFA samples are stored in a source row.
enum class SampleLayout : uint8_t {
    U16LE = 1,
    U16BE = 2,
    PackedMsb = 3,  // DNG / TIFF bit packing, most significant bit first
    Mipi10 = 4,     // Android RAW10
    Mipi12 = 5,     // Android RAW12
    U8 = 6,
};

// Bytes needed for `width` samples of a row.
size_t rowBytesFor(SampleLayout layout, unsigned bits, uint32_t width);

// Column alignment (in pixels) at which a row can be split on a byte boundary.
uint32_t columnAlignment(SampleLayout layout, unsigned bits);

// Unpacks `count` samples starting at column x (x must be a multiple of
// columnAlignment) from the row starting at `row`.
void unpackSamples(SampleLayout layout, unsigned bits, const uint8_t* row, uint32_t x, uint32_t count,
                   uint16_t* out);

// Inverse of unpackSamples. When the segment ends inside a byte (only at the
// end of a row) the unused low bits are written as zero.
void packSamples(SampleLayout layout, unsigned bits, const uint16_t* in, uint32_t x, uint32_t count,
                 uint8_t* row);

// Source of CFA rows for the encoder.
struct SampleSource {
    uint32_t width = 0;
    uint32_t height = 0;
    SampleLayout layout = SampleLayout::U16LE;
    unsigned bits = 16;
    std::vector<const uint8_t*> rows;

    void read(uint32_t y, uint32_t x, uint32_t count, uint16_t* out) const {
        unpackSamples(layout, bits, rows[y], x, count, out);
    }
};

}  // namespace uraw::detail
