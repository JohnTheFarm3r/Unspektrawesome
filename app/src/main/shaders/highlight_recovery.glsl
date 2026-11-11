// Highlight reconstruction adapted from darktable's "inpaint opposed" method:
// https://github.com/darktable-org/darktable/blob/master/src/iop/hlreconstruct/opposed.c
// Copyright (C) 2022-2026 darktable developers. The algorithm was developed by garagecoder and Iain
// of the G'MIC team and jenshannoschwalm of darktable.
// Licensed under the GNU General Public License version 3 or later. Modified for Vulkan shaders.
#ifndef UNSPEKTRA_HR_OPPOSED_COMMON_GLSL
#define UNSPEKTRA_HR_OPPOSED_COMMON_GLSL

const highp float HR_CLIP_VALUE = 0.987;
const highp float HR_POWERF = 3.0;
const highp float HR_REDUCE_SCALE = 65535.0;
const int HR_BORDER = 8;

highp vec3 hrWhiteBalance(highp vec3 wb) {
    return max(wb, vec3(0.001));
}

highp vec3 hrClips(highp vec3 wb) {
    return HR_CLIP_VALUE * wb;
}

highp vec3 hrClipDark(highp vec3 clips) {
    return vec3(0.03 * clips.r, 0.125 * clips.g, 0.03 * clips.b);
}

bool hrAnyClipped(highp vec3 scaled, highp vec3 clips) {
    return scaled.r >= clips.r || scaled.g >= clips.g || scaled.b >= clips.b;
}

highp vec3 hrRefavg(highp vec3 mean) {
    highp vec3 rootMean = pow(max(mean / 9.0, vec3(0.0)), vec3(1.0 / HR_POWERF));
    highp vec3 rootRefavg = vec3(
        0.5 * (rootMean.g + rootMean.b),
        0.5 * (rootMean.r + rootMean.b),
        0.5 * (rootMean.r + rootMean.g));
    return pow(rootRefavg, vec3(HR_POWERF));
}

highp vec3 applyOpposedHighlightRecovery(highp vec3 cameraRgb,
                                           highp vec3 tmpScaled,
                                           highp vec3 chrominance,
                                           highp vec3 wb) {
    highp vec3 scaled = max(cameraRgb * wb, vec3(0.0));
    highp vec3 clips = hrClips(wb);
    if (scaled.r >= clips.r) {
        scaled.r = max(scaled.r, tmpScaled.r + chrominance.r);
    }
    if (scaled.g >= clips.g) {
        scaled.g = max(scaled.g, tmpScaled.g + chrominance.g);
    }
    if (scaled.b >= clips.b) {
        scaled.b = max(scaled.b, tmpScaled.b + chrominance.b);
    }
    return scaled / wb;
}

#endif
