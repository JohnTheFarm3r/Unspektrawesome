#version 450

// Draws the focus loupe over the viewfinder: rounded corners, the magnified RAW image and optional
// peaking edges. The loupe image is display encoded, in sensor orientation, with the peaking edge
// amount in alpha.

layout(location = 0) in vec2 textureCoordinate;
layout(location = 0) out vec4 fragmentColor;

layout(set = 0, binding = 0) uniform sampler2D loupeImage;

layout(push_constant) uniform LoupeDraw {
    vec4 rect;       // framebuffer x, y, width, height in pixels
    vec4 shape;      // corner radius in pixels, opacity, sRGB attachment, peaking strength
    ivec4 transform; // orientation in quarter turns, mirror, texture width, texture height
} draw;

vec3 srgbToLinear(vec3 color)
{
    return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), color));
}

// Catmull-Rom interpolation from nine bilinear taps. It keeps edges sharper than bilinear filtering.
vec3 catmullRom(vec2 texel, vec2 textureSize)
{
    vec2 base = floor(texel - 0.5) + 0.5;
    vec2 f = texel - base;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 p0 = (base - 1.0) / textureSize;
    vec2 p12 = (base + w2 / w12) / textureSize;
    vec2 p3 = (base + 2.0) / textureSize;
    vec3 result = textureLod(loupeImage, vec2(p0.x, p0.y), 0.0).rgb * w0.x * w0.y;
    result += textureLod(loupeImage, vec2(p12.x, p0.y), 0.0).rgb * w12.x * w0.y;
    result += textureLod(loupeImage, vec2(p3.x, p0.y), 0.0).rgb * w3.x * w0.y;
    result += textureLod(loupeImage, vec2(p0.x, p12.y), 0.0).rgb * w0.x * w12.y;
    result += textureLod(loupeImage, vec2(p12.x, p12.y), 0.0).rgb * w12.x * w12.y;
    result += textureLod(loupeImage, vec2(p3.x, p12.y), 0.0).rgb * w3.x * w12.y;
    result += textureLod(loupeImage, vec2(p0.x, p3.y), 0.0).rgb * w0.x * w3.y;
    result += textureLod(loupeImage, vec2(p12.x, p3.y), 0.0).rgb * w12.x * w3.y;
    result += textureLod(loupeImage, vec2(p3.x, p3.y), 0.0).rgb * w3.x * w3.y;
    return clamp(result, vec3(0.0), vec3(1.0));
}

void main()
{
    vec2 size = draw.rect.zw;
    vec2 local = gl_FragCoord.xy - draw.rect.xy;
    float radius = min(draw.shape.x, 0.5 * min(size.x, size.y));
    vec2 corner = abs(local - 0.5 * size) - (0.5 * size - radius);
    float distance = length(max(corner, vec2(0.0))) + min(max(corner.x, corner.y), 0.0) - radius;
    float coverage = clamp(0.5 - distance, 0.0, 1.0) * draw.shape.y;
    if (coverage <= 0.0) discard;

    // Display orientation to sensor orientation, as the viewfinder geometry does.
    vec2 uv = clamp(local / size, vec2(0.0), vec2(1.0));
    if (draw.transform.y != 0) uv.x = 1.0 - uv.x;
    vec2 source = uv;
    if (draw.transform.x == 1) source = vec2(uv.y, 1.0 - uv.x);
    else if (draw.transform.x == 2) source = vec2(1.0 - uv.x, 1.0 - uv.y);
    else if (draw.transform.x == 3) source = vec2(1.0 - uv.y, uv.x);

    vec2 textureSize = vec2(draw.transform.zw);
    vec2 texel = source * textureSize;
    vec2 footprint = max(fwidth(texel), vec2(0.0001));
    vec3 color;
    if (max(footprint.x, footprint.y) > 0.75) {
        // Near actual size: keep RAW pixels crisp with a one-pixel antialiased edge.
        vec2 sharp = floor(texel) + 0.5 + clamp((fract(texel) - 0.5) / footprint, vec2(-0.5), vec2(0.5));
        color = textureLod(loupeImage, sharp / textureSize, 0.0).rgb;
    } else {
        // Magnified: smooth, sharp interpolation instead of visible pixel blocks.
        color = catmullRom(texel, textureSize);
    }

    if (draw.shape.w > 0.5) {
        float edge = textureLod(loupeImage, source, 0.0).a;
        color = mix(color, vec3(0.8667, 0.9961, 0.3216), 0.85 * edge);
    }
    if (draw.shape.z > 0.5) color = srgbToLinear(color);
    fragmentColor = vec4(color, coverage);
}
