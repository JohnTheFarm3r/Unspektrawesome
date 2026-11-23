#include "samples.h"

namespace uraw::detail {

size_t rowBytesFor(SampleLayout layout, unsigned bits, uint32_t width) {
    switch (layout) {
        case SampleLayout::U16LE:
        case SampleLayout::U16BE:
            return static_cast<size_t>(width) * 2;
        case SampleLayout::U8:
            return width;
        case SampleLayout::PackedMsb:
            return (static_cast<size_t>(width) * bits + 7) / 8;
        case SampleLayout::Mipi10:
            return static_cast<size_t>(width) / 4 * 5;
        case SampleLayout::Mipi12:
            return static_cast<size_t>(width) / 2 * 3;
    }
    return 0;
}

uint32_t columnAlignment(SampleLayout layout, unsigned) {
    switch (layout) {
        case SampleLayout::U16LE:
        case SampleLayout::U16BE:
        case SampleLayout::U8:
            return 1;
        default:
            return 8;
    }
}

void unpackSamples(SampleLayout layout, unsigned bits, const uint8_t* row, uint32_t x, uint32_t count,
                   uint16_t* URAW_RESTRICT out) {
    switch (layout) {
        case SampleLayout::U16LE:
            std::memcpy(out, row + static_cast<size_t>(x) * 2, static_cast<size_t>(count) * 2);
            return;
        case SampleLayout::U16BE: {
            const uint8_t* p = row + static_cast<size_t>(x) * 2;
            for (uint32_t i = 0; i < count; ++i) out[i] = static_cast<uint16_t>((p[2 * i] << 8) | p[2 * i + 1]);
            return;
        }
        case SampleLayout::U8: {
            const uint8_t* p = row + x;
            for (uint32_t i = 0; i < count; ++i) out[i] = p[i];
            return;
        }
        case SampleLayout::PackedMsb: {
            const uint8_t* p = row + static_cast<size_t>(x) * bits / 8;
            const uint32_t mask = (1u << bits) - 1;
            uint64_t acc = 0;
            unsigned n = 0;
            for (uint32_t i = 0; i < count; ++i) {
                while (n < bits) {
                    acc = (acc << 8) | *p++;
                    n += 8;
                }
                n -= bits;
                out[i] = static_cast<uint16_t>((acc >> n) & mask);
            }
            return;
        }
        case SampleLayout::Mipi10: {
            const uint8_t* p = row + static_cast<size_t>(x) / 4 * 5;
            for (uint32_t i = 0; i + 4 <= count; i += 4, p += 5) {
                const unsigned lo = p[4];
                out[i + 0] = static_cast<uint16_t>((p[0] << 2) | (lo & 3));
                out[i + 1] = static_cast<uint16_t>((p[1] << 2) | ((lo >> 2) & 3));
                out[i + 2] = static_cast<uint16_t>((p[2] << 2) | ((lo >> 4) & 3));
                out[i + 3] = static_cast<uint16_t>((p[3] << 2) | (lo >> 6));
            }
            return;
        }
        case SampleLayout::Mipi12: {
            const uint8_t* p = row + static_cast<size_t>(x) / 2 * 3;
            for (uint32_t i = 0; i + 2 <= count; i += 2, p += 3) {
                const unsigned lo = p[2];
                out[i + 0] = static_cast<uint16_t>((p[0] << 4) | (lo & 15));
                out[i + 1] = static_cast<uint16_t>((p[1] << 4) | (lo >> 4));
            }
            return;
        }
    }
}

void packSamples(SampleLayout layout, unsigned bits, const uint16_t* URAW_RESTRICT in, uint32_t x, uint32_t count,
                 uint8_t* row) {
    switch (layout) {
        case SampleLayout::U16LE:
            std::memcpy(row + static_cast<size_t>(x) * 2, in, static_cast<size_t>(count) * 2);
            return;
        case SampleLayout::U16BE: {
            uint8_t* p = row + static_cast<size_t>(x) * 2;
            for (uint32_t i = 0; i < count; ++i) {
                p[2 * i] = static_cast<uint8_t>(in[i] >> 8);
                p[2 * i + 1] = static_cast<uint8_t>(in[i]);
            }
            return;
        }
        case SampleLayout::U8: {
            uint8_t* p = row + x;
            for (uint32_t i = 0; i < count; ++i) p[i] = static_cast<uint8_t>(in[i]);
            return;
        }
        case SampleLayout::PackedMsb: {
            uint8_t* p = row + static_cast<size_t>(x) * bits / 8;
            const uint32_t mask = (1u << bits) - 1;
            uint64_t acc = 0;
            unsigned n = 0;
            for (uint32_t i = 0; i < count; ++i) {
                acc = (acc << bits) | (in[i] & mask);
                n += bits;
                while (n >= 8) {
                    n -= 8;
                    *p++ = static_cast<uint8_t>(acc >> n);
                }
            }
            if (n) *p = static_cast<uint8_t>(acc << (8 - n));
            return;
        }
        case SampleLayout::Mipi10: {
            uint8_t* p = row + static_cast<size_t>(x) / 4 * 5;
            for (uint32_t i = 0; i + 4 <= count; i += 4, p += 5) {
                p[0] = static_cast<uint8_t>(in[i] >> 2);
                p[1] = static_cast<uint8_t>(in[i + 1] >> 2);
                p[2] = static_cast<uint8_t>(in[i + 2] >> 2);
                p[3] = static_cast<uint8_t>(in[i + 3] >> 2);
                p[4] = static_cast<uint8_t>((in[i] & 3) | ((in[i + 1] & 3) << 2) | ((in[i + 2] & 3) << 4) |
                                            ((in[i + 3] & 3) << 6));
            }
            return;
        }
        case SampleLayout::Mipi12: {
            uint8_t* p = row + static_cast<size_t>(x) / 2 * 3;
            for (uint32_t i = 0; i + 2 <= count; i += 2, p += 3) {
                p[0] = static_cast<uint8_t>(in[i] >> 4);
                p[1] = static_cast<uint8_t>(in[i + 1] >> 4);
                p[2] = static_cast<uint8_t>((in[i] & 15) | ((in[i + 1] & 15) << 4));
            }
            return;
        }
    }
}

}  // namespace uraw::detail
