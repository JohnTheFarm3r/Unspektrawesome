#ifndef UNSPEKTRA_FRAME_GEOMETRY_GLSL
#define UNSPEKTRA_FRAME_GEOMETRY_GLSL

#ifndef RAW_GEOMETRY_BINDING
#define RAW_GEOMETRY_BINDING 6
#endif
layout(set = 0, binding = RAW_GEOMETRY_BINDING, std140) uniform GeometryParameters {
    vec4 sourceCrop;
    vec4 activeArray;
    ivec4 transform;
    vec4 target;
    vec4 calibrationArray;
    ivec4 rawBounds;
    vec4 stabilization;
    vec4 stabilizationBounds;
    vec4 stabilizationRows[99];
    vec4 stabilizationIntrinsics;
    vec4 stabilizationRadial;
    vec4 stabilizationTangential;
} frameGeometry;

#ifdef UNSPEKTRA_LIVE_STABILIZATION
vec2 stabilizationCameraPoint(vec2 uv)
{
    vec4 k = frameGeometry.stabilizationIntrinsics;
    float y = (uv.y-k.w)/k.y;
    return vec2((uv.x-k.z-frameGeometry.stabilizationRadial.x*y)/k.x, y);
}

vec2 stabilizationSensorPoint(vec2 point)
{
    vec4 k = frameGeometry.stabilizationIntrinsics;
    return vec2(k.x*point.x+frameGeometry.stabilizationRadial.x*point.y+k.z, k.y*point.y+k.w);
}

vec2 stabilizationDistort(vec2 uv)
{
    vec2 p = stabilizationCameraPoint(uv);
    vec3 k = frameGeometry.stabilizationRadial.yzw;
    vec2 t = frameGeometry.stabilizationTangential.xy;
    float r2 = dot(p, p);
    float radial = 1.0+r2*(k.x+r2*(k.y+r2*k.z));
    return stabilizationSensorPoint(p*radial+2.0*p.x*p.y*t.xy+t.yx*(r2+2.0*p*p));
}

vec2 stabilizationUndistort(vec2 uv)
{
    vec2 observed = stabilizationCameraPoint(uv);
    vec2 p = observed;
    vec3 k = frameGeometry.stabilizationRadial.yzw;
    vec2 t = frameGeometry.stabilizationTangential.xy;
    for (int iteration = 0; iteration < 6; ++iteration) {
        float r2 = dot(p, p);
        float radial = 1.0+r2*(k.x+r2*(k.y+r2*k.z));
        float derivative = k.x+r2*(2.0*k.y+3.0*r2*k.z);
        vec2 error = p*radial+2.0*p.x*p.y*t.xy+t.yx*(r2+2.0*p*p)-observed;
        float xx = radial+2.0*p.x*p.x*derivative+2.0*t.x*p.y+6.0*t.y*p.x;
        float yy = radial+2.0*p.y*p.y*derivative+6.0*t.x*p.y+2.0*t.y*p.x;
        float xy = 2.0*p.x*p.y*derivative+2.0*t.x*p.x+2.0*t.y*p.y;
        p -= vec2(yy*error.x-xy*error.y, xx*error.y-xy*error.x)/max(xx*yy-xy*xy, 0.00000001);
    }
    return stabilizationSensorPoint(p);
}
#endif

vec2 geometryStabilize(vec2 uv)
{
#ifdef UNSPEKTRA_LIVE_STABILIZATION
    if (frameGeometry.stabilization.x < 0.5) return uv;
    bool distorted = frameGeometry.stabilization.w > 0.5;
    vec3 position = vec3(distorted ? stabilizationUndistort(uv) : uv, 1.0);
    vec2 source = uv;
    for (int iteration = 0; iteration < 3; ++iteration) {
        float band = clamp((source.y - frameGeometry.stabilization.y) /
            frameGeometry.stabilization.z * 32.0, 0.0, 32.0);
        int index = min(int(floor(band)), 31) * 3;
        float fraction = band - float(index / 3);
        vec3 a = mix(frameGeometry.stabilizationRows[index].xyz, frameGeometry.stabilizationRows[index+3].xyz, fraction);
        vec3 b = mix(frameGeometry.stabilizationRows[index+1].xyz, frameGeometry.stabilizationRows[index+4].xyz, fraction);
        vec3 c = mix(frameGeometry.stabilizationRows[index+2].xyz, frameGeometry.stabilizationRows[index+5].xyz, fraction);
        source = vec2(dot(a, position), dot(b, position)) / max(dot(c, position), 0.00000001);
        if (distorted) source = stabilizationDistort(source);
    }
    // The planner limits correction to the crop; contain floating-point edge rounding too.
    return clamp(source, frameGeometry.stabilizationBounds.xy,
        frameGeometry.stabilizationBounds.xy + frameGeometry.stabilizationBounds.zw);
#else
    return uv;
#endif
}

vec2 geometrySourceUv(ivec2 outputPosition)
{
    vec2 orientedUv = (vec2(outputPosition) + 0.5) /
                      vec2(frameGeometry.transform.zw);
    if (frameGeometry.transform.y != 0)
        orientedUv.x = 1.0 - orientedUv.x;

    vec2 sourceUv;
    if (frameGeometry.transform.x == 1)
        sourceUv = vec2(orientedUv.y, 1.0 - orientedUv.x);
    else if (frameGeometry.transform.x == 2)
        sourceUv = vec2(1.0) - orientedUv;
    else if (frameGeometry.transform.x == 3)
        sourceUv = vec2(1.0 - orientedUv.y, orientedUv.x);
    else
        sourceUv = orientedUv;
    return geometryStabilize(frameGeometry.sourceCrop.xy + sourceUv * frameGeometry.sourceCrop.zw);
}

ivec2 geometrySourcePosition(ivec2 outputPosition, ivec2 sourceDimensions)
{
    vec2 sourcePixel = geometrySourceUv(outputPosition) * vec2(sourceDimensions);
    return clamp(ivec2(floor(sourcePixel)), ivec2(0), sourceDimensions - 1);
}

ivec2 geometryUnrotatePosition(ivec2 position)
{
    ivec2 size = frameGeometry.transform.zw;
    if (frameGeometry.transform.y != 0) position.x = size.x - 1 - position.x;
    if (frameGeometry.transform.x == 1) return ivec2(position.y, size.x - 1 - position.x);
    if (frameGeometry.transform.x == 2) return size - 1 - position;
    if (frameGeometry.transform.x == 3) return ivec2(size.y - 1 - position.y, position.x);
    return position;
}

vec2 geometryLensShadingUv(vec2 sourceUv, ivec2 sourceDimensions)
{
    vec2 dimensions = vec2(sourceDimensions);
    vec2 activePixel = (sourceUv - frameGeometry.calibrationArray.xy) * dimensions - 0.5;
    vec2 lastActivePixel = frameGeometry.calibrationArray.zw * dimensions - 1.0;
    return clamp(
        activePixel / max(lastActivePixel, vec2(1.0)),
        vec2(0.0), vec2(1.0));
}

#endif
