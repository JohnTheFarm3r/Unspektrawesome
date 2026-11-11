#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace multiframe {

struct Layout {
    int width = 0;
    int height = 0;
    int rowBytes = 0;
    int format = 0;        // 0 RAW16, 1 RAW10, 2 RAW12
    int blockSize = 1;     // CFA block size, 1 for Bayer
    int sourcePattern = 0; // RGGB, GRBG, GBRG, BGGR
    int phaseX = 0;
    int phaseY = 0;
    float black[4]{};      // R, G1, G2, B
    float white = 1023.f;
    float noiseS = 0.f;
    float noiseO = 0.f;
    float motion = .5f;    // 0 tolerant, 1 strict
};

struct Frame {
    const uint8_t* pixels = nullptr;
    float exposureScale = 1.f; // multiplies this frame to match the reference exposure
};

struct Result {
    float meanWeight = 0.f;  // average number of frames that contributed per pixel
    int64_t elapsedMs = 0;
    std::string error;
};

// Merges frames[0] (the reference) with the remaining frames into packed 16-bit RAW.
// The output keeps the reference black and white levels. meanWeight is 0 on failure.
Result merge(const Layout& layout, const std::vector<Frame>& frames, uint16_t* output);

}
