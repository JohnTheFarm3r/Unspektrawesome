int flowPatchOffset = 0;
float flowGray(ivec2 point, int level, bool reference) {
    vec4 info = p[1 + level];
    if (any(lessThan(point, ivec2(0))) || any(greaterThanEqual(point, ivec2(info.xy)))) return -1;
    int i = flowPatchOffset + int(reference ? info.w : info.z) + point.y * int(info.x) + point.x;
    return queries[i / 4][i % 4];
}
float flowSample(vec2 point, int level, bool reference) {
    if (any(lessThan(point, vec2(0))) || any(greaterThanEqual(point, p[1 + level].xy - 1))) return -1;
    ivec2 base = ivec2(point); vec2 fraction = fract(point);
    float a = flowGray(base, level, reference), b = flowGray(base + ivec2(1, 0), level, reference);
    float c = flowGray(base + ivec2(0, 1), level, reference), d = flowGray(base + 1, level, reference);
    return min(min(a, b), min(c, d)) < 0 ? -1 : mix(mix(a, b, fraction.x), mix(c, d, fraction.x), fraction.y);
}
float flowCorner(ivec2 point) {
    float xx = 0, yy = 0, xy = 0;
    for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
        ivec2 q = point + ivec2(x, y);
        float a = flowGray(q - ivec2(1, 0), 0, false), b = flowGray(q + ivec2(1, 0), 0, false);
        float c = flowGray(q - ivec2(0, 1), 0, false), d = flowGray(q + ivec2(0, 1), 0, false);
        if (min(min(a, b), min(c, d)) < 0) return 0;
        vec2 gradient = vec2(b - a, d - c) * .5;
        xx += gradient.x * gradient.x; yy += gradient.y * gradient.y; xy += gradient.x * gradient.y;
    }
    return .5 * (xx + yy - length(vec2(xx - yy, 2 * xy)));
}
float flowCorrelation(vec2 from, vec2 to, int level, bool reverse) {
    float a = 0, b = 0, aa = 0, bb = 0, ab = 0;
    for (int y = -5; y <= 5; ++y) for (int x = -5; x <= 5; ++x) {
        float s = flowSample(from + vec2(x, y), level, reverse), t = flowSample(to + vec2(x, y), level, !reverse);
        if (s < 0 || t < 0) return -1;
        s -= 128; t -= 128;
        a += s; b += t; aa += s * s; bb += t * t; ab += s * t;
    }
    float va = aa - a * a / 121, vb = bb - b * b / 121;
    if (min(va, vb) < 1089) return -1;
    return (ab - a * b / 121) / sqrt(va * vb);
}
bool flowRefine(vec2 from, inout vec2 to, int level, bool reverse) {
    float source[121]; float mean = 0, variance = 0; int i = 0;
    for (int y = -5; y <= 5; ++y) for (int x = -5; x <= 5; ++x) {
        float value = flowSample(from + vec2(x, y), level, reverse);
        if (value < 0) return false;
        source[i++] = value; mean += value;
    }
    mean /= 121;
    for (i = 0; i < 121; ++i) { source[i] -= mean; variance += source[i] * source[i]; }
    if (variance < 1089) return false;
    for (int iteration = 0; iteration < 18; ++iteration) {
        vec3 values[121]; float average = 0, averageX = 0, averageY = 0, covariance = 0; i = 0;
        for (int y = -5; y <= 5; ++y) for (int x = -5; x <= 5; ++x) {
            vec2 q = to + vec2(x, y);
            float value = flowSample(q, level, !reverse), left = flowSample(q - vec2(1, 0), level, !reverse), right = flowSample(q + vec2(1, 0), level, !reverse);
            float top = flowSample(q - vec2(0, 1), level, !reverse), bottom = flowSample(q + vec2(0, 1), level, !reverse);
            if (min(min(value, left), min(right, min(top, bottom))) < 0) return false;
            vec3 v = vec3(value, (right - left) * .5, (bottom - top) * .5);
            values[i] = v; average += v.x; averageX += v.y; averageY += v.z; covariance += source[i] * value; ++i;
        }
        average /= 121; averageX /= 121; averageY /= 121;
        float gain = covariance / variance;
        if (gain < .5 || gain > 2) return false;
        float xx = 0, yy = 0, xy = 0, bx = 0, by = 0;
        for (i = 0; i < 121; ++i) {
            vec2 gradient = values[i].yz - vec2(averageX, averageY);
            float error = source[i] * gain - values[i].x + average;
            xx += gradient.x * gradient.x; yy += gradient.y * gradient.y; xy += gradient.x * gradient.y;
            bx += gradient.x * error; by += gradient.y * error;
        }
        float determinant = xx * yy - xy * xy;
        if (determinant < 1e-5 || .5 * (xx + yy - length(vec2(xx - yy, 2 * xy))) < 12.1) return false;
        vec2 delta = vec2(yy * bx - xy * by, xx * by - xy * bx) / determinant;
        float distance = length(delta);
        to += delta * min(1, 2 / max(distance, 1e-9));
        if (distance < .015) break;
    }
    return true;
}
bool flowTrack(vec2 from, inout vec2 to, bool reverse, float search) {
    vec2 initial = to;
    int top = int(p[0].z) - 1;
    for (int level = top; level >= 0; --level) {
        float scale = float(1 << level);
        vec2 a = (from + .5) / scale - .5, b = (to + .5) / scale - .5;
        if (level == top && search > 0) {
            float best = -1; vec2 center = b; int distance = int(ceil(search / scale));
            for (int y = -distance; y <= distance; ++y) for (int x = -distance; x <= distance; ++x) {
                vec2 candidate = center + vec2(x, y); float score = flowCorrelation(a, candidate, level, reverse);
                if (score > best) { best = score; b = candidate; }
            }
            if (best < .75) return false;
        }
        if (!flowRefine(a, b, level, reverse)) return false;
        to = (b + .5) * scale - .5;
        if (search > 0 && length(to - initial) > search * 1.25) return false;
    }
    return flowCorrelation(from, to, 0, reverse) >= .94;
}
vec4 warpPixel(vec2 point, vec2 shift, ivec2 offset, int level) {
    vec2 q = point + vec2(offset), source = q + shift;
    float value = flowSample(source, level, false), old = flowSample(q, level, true);
    float left = flowSample(source - vec2(1, 0), level, false), right = flowSample(source + vec2(1, 0), level, false);
    float top = flowSample(source - vec2(0, 1), level, false), bottom = flowSample(source + vec2(0, 1), level, false);
    if (min(min(value, old), min(min(left, right), min(top, bottom))) < 0) return vec4(-1);
    return vec4(value, old, (right - left) * .5, (bottom - top) * .5);
}
vec3 joinPixel(vec2 point) {
    float value = flowSample(point, 0, false);
    float left = flowSample(point - vec2(1, 0), 0, false), right = flowSample(point + vec2(1, 0), 0, false);
    float top = flowSample(point - vec2(0, 1), 0, false), bottom = flowSample(point + vec2(0, 1), 0, false);
    return min(min(value, left), min(right, min(top, bottom))) < 0 ? vec3(-1) : vec3(value, (right - left) * .5, (bottom - top) * .5);
}
float joinCorrelation(vec2 point, float mean, float variance) {
    float sum = 0, square = 0, product = 0;
    for (int y = -9; y <= 9; ++y) for (int x = -9; x <= 9; ++x) {
        float value = flowSample(point + vec2(x, y), 0, false);
        if (value < 0) return -1;
        value -= 128;
        sum += value; square += value * value;
        product += (flowGray(ivec2(20 + x, 20 + y), 0, true) - 128 - mean) * value;
    }
    float v = square - sum * sum / 361;
    return v > 1 ? product / sqrt(variance * v) : -1;
}
bool joinRefine(out vec2 shift) {
    float mean = 0, variance = 0;
    for (int y = -9; y <= 9; ++y) for (int x = -9; x <= 9; ++x) {
        float value = flowGray(ivec2(20 + x, 20 + y), 0, true);
        if (value < 0) return false;
        value -= 128; mean += value; variance += value * value;
    }
    variance -= mean * mean / 361; mean /= 361;
    if (variance < 180.5) return false;
    vec2 point = vec2(20);
    float before = joinCorrelation(point, mean, variance);
    for (int iteration = 0; iteration < 12; ++iteration) {
        vec3 average = vec3(0); float covariance = 0;
        for (int y = -9; y <= 9; ++y) for (int x = -9; x <= 9; ++x) {
            vec3 value = joinPixel(point + vec2(x, y));
            if (value.x < 0) return false;
            value.x -= 128; average += value;
            covariance += (flowGray(ivec2(20 + x, 20 + y), 0, true) - 128 - mean) * value.x;
        }
        float gain = covariance / variance;
        if (gain < .8 || gain > 1.25) return false;
        average /= 361;
        float xx = 0, yy = 0, xy = 0, bx = 0, by = 0;
        for (int y = -9; y <= 9; ++y) for (int x = -9; x <= 9; ++x) {
            vec3 value = joinPixel(point + vec2(x, y));
            vec2 gradient = value.yz - average.yz;
            float error = (flowGray(ivec2(20 + x, 20 + y), 0, true) - 128 - mean) * gain - value.x + 128 + average.x;
            xx += gradient.x * gradient.x; yy += gradient.y * gradient.y; xy += gradient.x * gradient.y;
            bx += gradient.x * error; by += gradient.y * error;
        }
        float largest = .5 * (xx + yy + length(vec2(xx - yy, 2 * xy)));
        if (largest < 21.66) return false;
        float regularization = max(1, largest * .01);
        xx += regularization; yy += regularization;
        float determinant = xx * yy - xy * xy;
        vec2 delta = vec2(yy * bx - xy * by, xx * by - xy * bx) / determinant;
        float distance = length(delta);
        point += delta * min(1, 1 / max(distance, 1e-9));
        if (length(point - 20) > 3) return false;
        if (distance < .02) break;
    }
    float after = joinCorrelation(point, mean, variance);
    if (after < .92 || after < before + .002) return false;
    shift = point - 20; return true;
}
bool flowJob(uint index) {
    if (job.x == 28) {
        int edge = int(p[19].x), stride = int(p[19].y), pixel = int(index) % stride, patchIndex = int(index) / stride;
        vec2 offset = vec2(pixel % edge, pixel / edge) - float(edge / 2);
        vec4 center = p[int(p[19].z) + patchIndex];
        vec2 point = center.xy + offset;
        vec2 source = p[19].w > .5 ? correctedPoint(point) : sourcePoint(point + p[1].zw);
        float current = -1, previous = -1;
        if (pixel < edge * edge) {
            if (valid(source)) current = dot(p[19].w > .5 ? color(rgb(source), point, source) : rgb(source), vec3(.2126, .7152, .0722));
            vec4 old = referenceAt(center.zw + offset);
            if (old.a > 0) previous = dot(old.rgb, vec3(.2126, .7152, .0722));
        }
        int reference = int(index) + job.y;
        queries[index / 4][index % 4] = current; queries[reference / 4][reference % 4] = previous; return true;
    }
    if (job.x == 25) {
        int stride = int(p[2].z), edge = int(p[0].x), patchIndex = int(index) / stride, pixel = int(index) % stride;
        flowPatchOffset = patchIndex * stride;
        ivec2 point = ivec2(pixel % edge, pixel / edge);
        vec2 sum = vec2(0); bool validPatch = pixel < edge * edge && all(greaterThanEqual(point, ivec2(1))) && all(lessThan(point, ivec2(edge - 1)));
        if (validPatch) for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
            ivec2 q = point + ivec2(x, y);
            vec2 value = job.z == 0 ? vec2(flowGray(q, 0, false), flowGray(q, 0, true)) : result[flowPatchOffset + q.y * edge + q.x].xy;
            validPatch = validPatch && all(greaterThanEqual(value, vec2(0)));
            sum += value * float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
        }
        vec2 value = validPatch ? sum / 16 : vec2(-1);
        if (job.z == 0) result[index] = vec4(value, 0, 0);
        else {
            int reference = int(index) + int(p[1].w);
            queries[index / 4][index % 4] = value.x; queries[reference / 4][reference % 4] = value.y;
        }
        return true;
    }
    if (job.x == 29) {
        flowPatchOffset = int(index) * int(p[2].z);
        vec2 delta = vec2(0); bool accepted;
        if (p[2].y > .5) accepted = joinRefine(delta);
        else {
            vec2 center = vec2(16), point = center;
            accepted = flowRefine(center, point, 0, false) && flowCorrelation(center, point, 0, false) >= .94 && length(point - center) <= p[2].x;
            if (accepted) {
                vec2 reverse = center;
                accepted = flowRefine(point, reverse, 0, true) && length(reverse - center) <= .25;
            }
            delta = point - center;
        }
        result[index] = vec4(delta, 0, accepted ? 1 : 0); return true;
    }
    if (job.x == 26) {
        vec4 points = p[20 + index]; vec2 to = points.zw;
        result[index + job.y] = vec4(-1);
        if (!flowRefine(points.xy, to, 0, false) || length(to - points.zw) > 2.5) return true;
        float confidence = flowCorrelation(points.xy, to, 0, false);
        if (confidence < .94) return true;
        vec2 reverse = points.xy;
        if (!flowRefine(to, reverse, 0, true) || length(reverse - points.xy) > .35) return true;
        result[index] = vec4(points.xy, to); result[index + job.y] = vec4(confidence); return true;
    }
    if (job.x == 27) {
        vec4 offset = p[20 + index]; int spacing = int(offset.z);
        float a = 0, b = 0, aa = 0, bb = 0, ab = 0; int count = 0;
        for (int y = 12; y < int(p[0].y) - 12; y += spacing) for (int x = 12; x < int(p[0].x) - 12; x += spacing) {
            vec2 point = vec2(x, y) + offset.xy;
            if (any(lessThan(point, vec2(1))) || any(greaterThanEqual(point, p[0].xy - 1))) continue;
            float s = flowSample(point, 0, true), t = flowGray(ivec2(x, y), 0, false);
            if (s < 0 || t < 0) continue;
            s -= 128; t -= 128;
            a += s; b += t; aa += s * s; bb += t * t; ab += s * t; ++count;
        }
        float va = aa - a * a / max(1, count), vb = bb - b * b / max(1, count);
        float score = count < 150 || min(va, vb) / max(1, count) < 36 ? -1 : (ab - a * b / count) / sqrt(va * vb);
        result[index] = vec4(score); return true;
    }
    if (job.x == 20) {
        int level = job.z; ivec2 size = ivec2(p[1 + level].xy);
        int count = size.x * size.y; bool reference = index >= uint(count); int i = int(index) % count;
        ivec2 point = ivec2(i % size.x, i / size.x) * 2;
        float a = flowGray(point, level - 1, reference), b = flowGray(point + ivec2(1, 0), level - 1, reference);
        float c = flowGray(point + ivec2(0, 1), level - 1, reference), d = flowGray(point + 1, level - 1, reference);
        int offset = int(reference ? p[1 + level].w : p[1 + level].z) + i;
        queries[offset / 4][offset % 4] = min(min(a, b), min(c, d)) < 0 ? -1 : (a + b + c + d) * .25;
        return true;
    }
    if (job.x == 21) {
        ivec2 origin = ivec2(12) + ivec2(int(index) % int(p[6].y), int(index) / int(p[6].y)) * int(p[6].x);
        vec2 point = vec2(0); float strongest = 1;
        for (int y = origin.y; y < min(origin.y + int(p[6].x), int(p[0].y) - 12); y += 3)
            for (int x = origin.x; x < min(origin.x + int(p[6].x), int(p[0].x) - 12); x += 3) {
                if (flowGray(ivec2(x, y), 0, true) < 0) continue;
                float strength = flowCorner(ivec2(x, y));
                if (strength > strongest) { strongest = strength; point = vec2(x, y); }
            }
        result[index + job.y] = vec4(-1);
        if (strongest == 1) return true;
        vec2 to = point;
        if (!flowTrack(point, to, false, p[6].w)) return true;
        vec2 reverse = point;
        if (!flowTrack(to, reverse, true, 0) || length(reverse - point) > .35) return true;
        result[index] = vec4(to, point - to);
        result[index + job.y] = vec4(flowCorrelation(point, to, 0, false)); return true;
    }
    if (job.x < 22 || job.x > 24) return false;
    ivec2 cell = ivec2(int(index) % int(p[6].x), int(index) / int(p[6].x));
    vec4 base = p[int(p[8].x) + index], current = p[job.w + index];
    vec2 neighbor = vec2(0); int neighbors = 0;
    ivec2 offsets[4] = ivec2[](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    for (int i = 0; i < 4; ++i) {
        ivec2 q = cell + offsets[i]; if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, ivec2(p[6].xy)))) continue;
        int other = q.y * int(p[6].x) + q.x;
        vec2 difference = p[job.w + other].xy - p[int(p[8].x) + other].xy;
        if (job.x == 24) {
            difference -= current.xy - base.xy; float distance = length(difference);
            float maximum = p[7].z * (offsets[i].x != 0 ? p[6].z : p[6].w);
            if (distance > maximum) neighbor += difference * ((1 - maximum / distance) * .24);
        } else neighbor += difference;
        ++neighbors;
    }
    if (job.x == 24) { p[tile.x + index] = vec4(current.xy + neighbor / (1 + current.w), current.zw); return true; }
    if (job.x == 23) {
        p[tile.x + index] = current.z >= 12 ? current : vec4(base.xy + neighbor * (.98 / max(1, neighbors)), current.zw); return true;
    }
    int level = job.z; float scale = float(1 << level);
    int radius = clamp(int(p[0].w / scale), 4, 12), stride = max(1, radius / 4);
    vec2 point = (vec2(cell) * p[6].zw + .5) / scale - .5, shift = current.xy / scale;
    float a = 0, b = 0, aa = 0, ab = 0, gx = 0, gy = 0, count = 0;
    for (int y = -radius; y <= radius; y += stride) for (int x = -radius; x <= radius; x += stride) {
        vec4 v = warpPixel(point, shift, ivec2(x, y), level); if (v.x < 0) continue;
        a += v.x; b += v.y; aa += v.y * v.y; ab += v.x * v.y; gx += v.z; gy += v.w; ++count;
    }
    float xx = 0, yy = 0, xy = 0, bx = 0, by = 0;
    if (count >= 30) {
        float variance = aa - b * b / count;
        float gain = variance > count ? clamp((ab - a * b / count) / variance, .8, 1.25) : 1;
        a /= count; b /= count; gx /= count; gy /= count;
        for (int y = -radius; y <= radius; y += stride) for (int x = -radius; x <= radius; x += stride) {
            vec4 v = warpPixel(point, shift, ivec2(x, y), level); if (v.x < 0) continue;
            float error = (v.y - b) * gain - v.x + a;
            float weight = min(1, 4 / max(.01, abs(error))); vec2 gradient = v.zw - vec2(gx, gy);
            xx += weight * gradient.x * gradient.x; yy += weight * gradient.y * gradient.y; xy += weight * gradient.x * gradient.y;
            bx += weight * gradient.x * error; by += weight * gradient.y * error;
        }
    }
    float support = xx + yy;
    vec2 prior = base.xy + neighbor * (.97 / max(1, neighbors)); float regularization = max(3, (xx + yy) * .035);
    vec2 deltaPrior = (prior - current.xy) / scale;
    xx += regularization; yy += regularization; bx += regularization * deltaPrior.x; by += regularization * deltaPrior.y;
    float anchoring = regularization * min(16, current.w * 8);
    vec2 anchor = (base.xy + base.zw - current.xy) / scale;
    xx += anchoring; yy += anchoring; bx += anchoring * anchor.x; by += anchoring * anchor.y;
    float determinant = xx * yy - xy * xy;
    vec2 delta = vec2(yy * bx - xy * by, xx * by - xy * bx) / determinant;
    delta *= min(1, 1.5 / max(length(delta), 1e-9));
    vec2 residual = current.xy + delta * (scale * .7) - base.xy;
    residual *= min(1, max(p[7].x, p[7].y) * .035 / max(length(residual), 1e-9));
    p[tile.x + index] = vec4(base.xy + residual, support, current.w); return true;
}
