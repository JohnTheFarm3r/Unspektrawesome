#pragma once

#include <cstddef>
#include <cstdint>

namespace uraw::detail {

// CRC32C (Castagnoli). Start with crc = 0; pass the previous result to continue.
// Uses the CPU CRC instructions when available.
uint32_t crc32c(uint32_t crc, const void* data, size_t size);

// CRC of A followed by B, from crc(A), crc(B) and the length of B.
uint32_t crc32cCombine(uint32_t crcA, uint32_t crcB, uint64_t lengthB);

}  // namespace uraw::detail
