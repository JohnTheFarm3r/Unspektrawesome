#version 450

layout(location = 0) in vec2 textureCoordinate;
layout(location = 0) out vec4 fragmentColor;
layout(set = 0, binding = 0) uniform sampler2D processedImage;
layout(push_constant) uniform VideoParameters {
    float rotate;
} video;

void main()
{
    vec2 uv = textureCoordinate;
    if (video.rotate > 0.5) uv = vec2(1.0 - uv.y, uv.x);
    vec3 color = texture(processedImage, uv).rgb;
    // The processing image is sRGB; the recording surface uses the BT.709 transfer curve.
    color = clamp(color, vec3(0.0), vec3(1.0));
    vec3 linear = mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
                      step(vec3(0.04045), color));
    vec3 encoded = mix(4.5 * linear, 1.099 * pow(linear, vec3(0.45)) - 0.099,
                       step(vec3(0.018), linear));
    fragmentColor = vec4(encoded, 1.0);
}
