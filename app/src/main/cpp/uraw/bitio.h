#pragma once

#include "common.h"

namespace uraw::detail {

// LSB-first bit writer. Every put() stores 8 bytes at the current position,
// so the destination needs 8 bytes of slack past the final size.
class BitWriter {
public:
    BitWriter(uint8_t* out) : start_(out), p_(out) {}

    // Appends the low `len` bits of `code`. len <= 56, unused high bits must be zero.
    URAW_INLINE void put(uint64_t code, unsigned len) {
        acc_ |= code << n_;
        n_ += len;
        store64(p_, acc_);
        const unsigned bytes = n_ >> 3;
        p_ += bytes;
        acc_ >>= bytes * 8;  // bytes <= 7, so the shift is always defined
        n_ &= 7;
    }

    // Bytes in use so far, including a partially filled last byte.
    size_t size() const { return static_cast<size_t>(p_ - start_) + (n_ ? 1 : 0); }

    size_t finish() {
        if (n_) store64(p_, acc_);
        return size();
    }

private:
    uint8_t* start_;
    uint8_t* p_;
    uint64_t acc_ = 0;
    unsigned n_ = 0;
};

// LSB-first bit reader. Reads 8 bytes at a time, so the source must stay
// readable well past the end of the stream (the decoder pads its buffers).
class BitReader {
public:
    BitReader(const uint8_t* data) : p_(data) {}

    // Guarantees at least 56 buffered bits.
    URAW_INLINE void refill() {
        buf_ |= load64(p_) << n_;
        p_ += (63 - n_) >> 3;
        n_ |= 56;
    }
    URAW_INLINE uint64_t peek() const { return buf_; }
    URAW_INLINE void consume(unsigned k) {
        buf_ >>= k;
        n_ -= k;
    }
    // k <= 32 and must be buffered.
    URAW_INLINE uint32_t get(unsigned k) {
        const uint32_t v = static_cast<uint32_t>(buf_ & ((1ull << k) - 1));
        consume(k);
        return v;
    }
    // Position of the next unread byte, for bounds checks.
    const uint8_t* position() const { return p_ - (n_ >> 3); }
    const uint8_t* rawPointer() const { return p_; }

private:
    const uint8_t* p_;
    uint64_t buf_ = 0;
    unsigned n_ = 0;
};

}  // namespace uraw::detail
