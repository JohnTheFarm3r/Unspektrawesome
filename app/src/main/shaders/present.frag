#version 450

layout(location = 0) in vec2 textureCoordinate;
layout(location = 0) out vec4 fragmentColor;

layout(set = 0, binding = 0) uniform sampler2D processedImage;
layout(set = 0, binding = 1) uniform sampler2D exposureImage;
layout(std430, set = 0, binding = 2) readonly buffer ExposureRange {
    vec4 black;
    vec4 white;
} exposureRange;

layout(push_constant) uniform PresentationParameters {
    float sourceAspect;
    float outputAspect;
    float srgbAttachment;
    float blurStrength;
    vec4 sourceCrop;
    vec4 zebra;
} presentation;

vec3 exposureZebra(vec4 displayed, vec2 uv)
{
    if (presentation.zebra.x < 0.5 || presentation.blurStrength > 0.001)
        return displayed.rgb;
    // Peaking colors must not change exposure warnings. Its input is linear RGB.
    bool linearInput = presentation.zebra.y > 0.5;
    vec4 original = linearInput || presentation.zebra.w > 0.5 ? texture(exposureImage, uv) : displayed;
    bool sensorHighlights = original.a > 1.5;
    vec3 measured = linearInput ? original.rgb : displayed.rgb;
    if (presentation.zebra.w > 0.5 && exposureRange.black.w > 0.5) {
        vec3 range = exposureRange.white.rgb - exposureRange.black.rgb;
        // Ignore collapsed color channels instead of amplifying their noise.
        measured = mix(measured, (measured - exposureRange.black.rgb) / max(range, vec3(0.05)),
                       greaterThan(range, vec3(0.05)));
    }
    float brightest = max(measured.r, max(measured.g, measured.b));
    // The same 2% and 98% sRGB thresholds expressed in each input's encoding.
    float shadowLimit = linearInput ? 0.00154799 : 0.02;
    float highlightLimit = linearInput ? 0.9551046 : 0.98;
    bool shadows = !sensorHighlights && brightest <= shadowLimit;
    bool highlights = sensorHighlights || brightest >= highlightLimit;
    if (!shadows && !highlights)
        return displayed.rgb;
    float period = presentation.zebra.z;
    float phase = mod(gl_FragCoord.x + gl_FragCoord.y, period);
    float stripe = smoothstep(0.0, 1.0, phase) - smoothstep(period * 0.32, period * 0.32 + 1.0, phase);
    return shadows ? mix(vec3(0.015), vec3(0.36, 0.68, 0.94), stripe)
                   : mix(vec3(0.96), vec3(0.82, 0.20, 0.23), stripe);
}

vec3 srgbToLinear(vec3 value)
{
    vec3 low = value / 12.92;
    vec3 high = pow((value + 0.055) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), value));
}

vec4 sampleProcessed(vec2 uv)
{
    if (presentation.blurStrength < 0.001)
        return texture(processedImage, uv);

    vec2 texel = 1.0 / vec2(textureSize(processedImage, 0));
    float sampleStep = 4.5 * presentation.blurStrength;
    const float weights[5] = float[](0.0625, 0.25, 0.375, 0.25, 0.0625);
    vec4 value = vec4(0.0);
    for (int y = 0; y < 5; ++y) {
        for (int x = 0; x < 5; ++x) {
            vec2 offset = vec2(float(x - 2), float(y - 2)) * sampleStep * texel;
            value += texture(processedImage, clamp(uv + offset, 0.0, 1.0)) *
                weights[x] * weights[y];
        }
    }
    return value;
}

void main()
{
    vec2 uv = textureCoordinate;
    if (presentation.outputAspect > presentation.sourceAspect) {
        float contentWidth = presentation.sourceAspect / presentation.outputAspect;
        if (abs(uv.x - 0.5) > contentWidth * 0.5) {
            fragmentColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        uv.x = (uv.x - 0.5) / contentWidth + 0.5;
    } else {
        float contentHeight = presentation.outputAspect / presentation.sourceAspect;
        float contentTop = 1.0 - contentHeight;
        if (uv.y < contentTop) {
            fragmentColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        uv.y = (uv.y - contentTop) / contentHeight;
    }
    uv = (uv - presentation.sourceCrop.xy) / presentation.sourceCrop.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragmentColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec4 sampled = sampleProcessed(uv);
    sampled.rgb = exposureZebra(sampled, uv);
    if (presentation.srgbAttachment > 0.5)
        sampled.rgb = srgbToLinear(sampled.rgb);
    fragmentColor = vec4(sampled.rgb, 1.0);
}
