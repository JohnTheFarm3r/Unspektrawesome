#ifndef UNSPEKTRA_RAW_DCP_GLSL
#define UNSPEKTRA_RAW_DCP_GLSL

#ifdef UNSPEKTRA_PREVIEW_SCALAR_SHADING
layout(constant_id = 3) const bool previewDcpEnabled = true;
#endif

vec3 dcpRgbToHsv(vec3 rgb)
{
    float value = max(rgb.r, max(rgb.g, rgb.b));
    float low = min(rgb.r, min(rgb.g, rgb.b));
    float delta = value - low;
    float hue = 0.0;
    if (delta > 0.0000001) {
        if (value == rgb.r) hue = (rgb.g - rgb.b) / delta;
        else if (value == rgb.g) hue = 2.0 + (rgb.b - rgb.r) / delta;
        else hue = 4.0 + (rgb.r - rgb.g) / delta;
    }
    return vec3(fract(hue / 6.0), value > 0.0 ? delta / value : 0.0, value);
}

vec3 dcpHsvToRgb(vec3 hsv)
{
    vec3 ramp = clamp(abs(fract(hsv.x + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return hsv.z * mix(vec3(1.0), ramp, hsv.y);
}

vec3 dcpTableAt(int start, ivec3 cell, ivec3 dims)
{
    return lensShading.gain[start + (cell.z * dims.x + cell.x) * dims.y + cell.y].xyz;
}

vec3 dcpSampleTable(int start, vec3 hsv, ivec3 dims)
{
    vec3 position = vec3(fract(hsv.x) * float(dims.x), clamp(hsv.yz, 0.0, 1.0) * vec2(dims.yz - 1));
    ivec3 low = ivec3(floor(position));
    ivec3 high = ivec3((low.x + 1) % dims.x, min(low.yz + 1, dims.yz - 1));
    vec3 f = fract(position);
    vec3 a = mix(mix(dcpTableAt(start, low, dims), dcpTableAt(start, ivec3(high.x, low.yz), dims), f.x),
                 mix(dcpTableAt(start, ivec3(low.x, high.y, low.z), dims), dcpTableAt(start, ivec3(high.xy, low.z), dims), f.x), f.y);
    vec3 b = mix(mix(dcpTableAt(start, ivec3(low.xy, high.z), dims), dcpTableAt(start, ivec3(high.x, low.y, high.z), dims), f.x),
                 mix(dcpTableAt(start, ivec3(low.x, high.yz), dims), dcpTableAt(start, high, dims), f.x), f.y);
    return mix(a, b, f.z);
}

vec3 rawDcpCalibration(vec3 working)
{
#ifdef UNSPEKTRA_PREVIEW_NO_DCP
    return working;
#else
#ifdef UNSPEKTRA_PREVIEW_SCALAR_SHADING
    if (!previewDcpEnabled) return working;
#endif
    int base = int(rawColor.reserved1.x);
    if (base == 0) return working;
    vec4 header = lensShading.gain[base];
    ivec3 dims = ivec3(header.xyz);
    ivec2 maps = ivec2(lensShading.gain[base + 1].xy) + base;
    const mat3 workingToProPhoto = mat3(
        0.529337205, 0.098361944, 0.016873187,
        0.330188454, 0.873425969, 0.117644399,
        0.140581572, 0.028168661, 0.865354738);
    const mat3 proPhotoToWorking = mat3(
        2.034149267, -0.228802244, -0.008557507,
        -0.727666256, 1.231806991, -0.153274881,
        -0.306771900, -0.002927093, 1.161975004);
    vec3 hsv = dcpRgbToHsv(max(workingToProPhoto * working, vec3(0.0)));
    bool encodedValue = header.w > 0.5 && dims.z > 1;
    if (encodedValue)
        hsv.z = hsv.z <= 0.0031308 ? hsv.z * 12.92 : 1.055 * pow(hsv.z, 1.0 / 2.4) - 0.055;
    vec3 adjustment = dcpSampleTable(maps.x, hsv, dims);
    if (maps.y != maps.x)
        adjustment = mix(dcpSampleTable(maps.y, hsv, dims), adjustment, rawColor.reserved1.y);
    hsv.x = fract(hsv.x + adjustment.x / 360.0);
    hsv.y = clamp(hsv.y * adjustment.y, 0.0, 1.0);
    // Clamp table coordinates, retaining RAW highlight headroom for spektrafilm.
    hsv.z *= adjustment.z;
    if (encodedValue)
        hsv.z = hsv.z <= 0.04045 ? hsv.z / 12.92 : pow((hsv.z + 0.055) / 1.055, 2.4);
    return proPhotoToWorking * dcpHsvToRgb(hsv);
#endif
}

#endif
