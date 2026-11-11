// Shadow lift adapted from darktable's "shadows and highlights" module:
// https://github.com/darktable-org/darktable/blob/master/src/iop/shadhi.c
// Copyright (C) 2012-2026 darktable developers.
// Licensed under the GNU General Public License version 3 or later. Modified for Vulkan shaders.
#ifndef UNSPEKTRA_SHADOWS_GLSL
#define UNSPEKTRA_SHADOWS_GLSL

layout(set = 0, binding = 12) uniform sampler2D shadowGuideTexture;

float shadhiOverlayLightness(float la, float lb) {
    return (la > 0.5)
        ? 1.0 - 2.0 * (1.0 - la) * (1.0 - lb)
        : 2.0 * la * lb;
}

vec3 applyShadows(vec3 color, vec2 guideCoord, float shadows) {
    if (abs(shadows) <= 0.001) return color;
    float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float perceptualLum = sqrt(max(lum, 0.0));
    if (shadows > 0.0) {
        float guideLum = texture(shadowGuideTexture, guideCoord).r;
        float invertedGuide = 1.0 - clamp(guideLum, 0.0, 1.0);
        const float compress = 0.50;
        float xform = clamp((invertedGuide - compress) / (1.0 - compress), 0.0, 1.0);

        float shadowStrength = shadows * 0.70;
        float targetPerceptualLum = perceptualLum;
        float opacity2 = min(shadowStrength * 2.0, 2.0);
        opacity2 *= opacity2;
        for (int i = 0; i < 4; ++i) {
            if (opacity2 <= 0.0) break;
            float chunk = min(opacity2, 1.0);
            float optrans = chunk * xform;
            float overlayLum = shadhiOverlayLightness(targetPerceptualLum, invertedGuide);
            targetPerceptualLum = mix(targetPerceptualLum, overlayLum, optrans);
            opacity2 -= 1.0;
        }
        float targetLum = targetPerceptualLum * targetPerceptualLum;
        if (lum > 1e-5) {
            color *= targetLum / lum;
        }
    } else {
        float toeProtect = smoothstep(0.025, 0.11, perceptualLum);
        float midtoneProtect = 1.0 - smoothstep(0.24, 0.44, perceptualLum);
        float shadowMask = toeProtect * midtoneProtect;
        float targetPerceptualLum = perceptualLum;
        targetPerceptualLum -= (-shadows) * shadowMask * perceptualLum * 0.14;
        if (lum > 1e-5) {
            float targetLum = targetPerceptualLum * targetPerceptualLum;
            color *= targetLum / lum;
        }
    }
    return max(color, vec3(0.0));
}

#endif
