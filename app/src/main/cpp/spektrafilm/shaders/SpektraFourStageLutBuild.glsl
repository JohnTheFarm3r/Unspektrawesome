vec3 fourStageFilmExposure(vec3 linearRgb) {
  const vec3 xyz = multiplyInputToReferenceXyz(linearRgb, params.inputColorSpace);
  const vec3 raw = params.rgbToRawMethod == 1 ? mallettRaw(linearRgb) : hanatosRaw(xyz);
  return log(max(raw, vec3(0.0)) + vec3(1.0e-10)) * kLog10E;
}

vec3 fourStageGrid(uint index, uint size) {
  return vec3(index % size, (index / size) % size, index / (size * size)) / float(size - 1u);
}

void bakeFourStageLut() {
  const uvec2 local = gl_GlobalInvocationID.xy;
  if (local.x >= params.activeWidth || local.y >= params.activeHeight) return;
  const uvec2 gid = local + uvec2(params.activeOriginX, params.activeOriginY);
  const uint index = gid.y * params.width + gid.x;
  const uint operation = params._pad0;
  if (operation == 4u) {
    if (index >= kFourStageProbeCount) return;
    const vec3 logExposure = fourStageFilmExposure(fourStageDecodeInput(fourStageGrid(index, kFourStageProbeSize)));
    const vec3 density = max(developFilmDensity(logExposure), vec3(0.0));
    const vec3 printLogExposure = u(0u) == 1u ? vec3(0.0) : printLogRaw(density);
    const uint base = kFourStageProbeOffset + index * 3u;
    fourStageLutData[base] = vec4(logExposure, 0.0);
    fourStageLutData[base + 1u] = vec4(density, 0.0);
    fourStageLutData[base + 2u] = vec4(printLogExposure, 0.0);
    return;
  }
  if (operation == 5u) {
    if (index != 0u) return;
    vec3 filmMinimum = vec3(1.0e30), filmMaximum = vec3(-1.0e30);
    vec3 printMinimum = vec3(1.0e30), printMaximum = vec3(-1.0e30);
    vec3 densityMaximum = vec3(0.0);
    for (uint probe = 0u; probe < kFourStageProbeCount; ++probe) {
      const uint base = kFourStageProbeOffset + probe * 3u;
      filmMinimum = min(filmMinimum, fourStageLutData[base].xyz);
      filmMaximum = max(filmMaximum, fourStageLutData[base].xyz);
      densityMaximum = max(densityMaximum, fourStageLutData[base + 1u].xyz);
      printMinimum = min(printMinimum, fourStageLutData[base + 2u].xyz);
      printMaximum = max(printMaximum, fourStageLutData[base + 2u].xyz);
    }
    // Match the creator's outward rounding, log margins and fog-grain headroom.
    const vec4 range = vec4(min(min(filmMinimum.x, filmMinimum.y), filmMinimum.z) - 0.1,
      max(max(filmMaximum.x, filmMaximum.y), filmMaximum.z) + 0.1,
      min(min(printMinimum.x, printMinimum.y), printMinimum.z) - 0.1,
      max(max(printMaximum.x, printMaximum.y), printMaximum.z) + 0.1);
    fourStageLutData[0] = vec4(floor(range.x * 10000.0), ceil(range.y * 10000.0),
      floor(range.z * 10000.0), ceil(range.w * 10000.0)) / 10000.0;
    fourStageLutData[1] = vec4(-0.2, -0.2, -0.2, 0.0);
    fourStageLutData[2] = vec4(ceil(max(densityMaximum * 1.05, vec3(0.0001)) * 10000.0) / 10000.0, 0.0);
    const uint count = u(SPEKTRA_FOUR_STAGE_DIR_ENABLED_INDEX) != 0u ? params.exposureCount : 0u;
    fourStageLutData[3] = vec4(float(count), read_filmCurveExposure(0u).x,
      read_filmCurveExposure(params.exposureCount - 1u).x, params.filmGamma);
    const uint m = SPEKTRA_FOUR_STAGE_DIR_MATRIX_OFFSET;
    fourStageLutData[4] = vec4(f(m), f(m + 3u), f(m + 6u), float(u(10u)));
    fourStageLutData[5] = vec4(f(m + 1u), f(m + 4u), f(m + 7u), developmentActivity(params.filmPushPullStops));
    fourStageLutData[6] = vec4(f(m + 2u), f(m + 5u), f(m + 8u),
      (params.filmPushPullStops - pushPullSpeedGain(params.filmPushPullStops)) * 0.3010299956639812);
    fourStageLutData[7] = vec4(f(16u), f(17u), f(18u), float(params.filmPushPullMode));
    return;
  }
  if (index >= kFourStageLutCount) return;
  const vec3 code = fourStageGrid(index, kFourStageLutSize);
  vec3 result;
  if (operation == 0u) {
    result = fourStageEncodeLog(fourStageFilmExposure(fourStageDecodeInput(code)), 0u);
  } else if (operation == 1u) {
    result = fourStageEncodeDensity(max(developFilmDensity(fourStageDecodeLog(code, 0u)), vec3(0.0)));
    if (u(SPEKTRA_FOUR_STAGE_DIR_ENABLED_INDEX) != 0u && index < params.exposureCount) {
      const uint offset = (params.exposureCount + index) * 3u;
      fourStageLutData[SPEKTRA_FOUR_STAGE_DIR_CURVE_OFFSET + index] = vec4(read_filmCurveExposure(index).x,
        read_filmDensityCurves(offset), read_filmDensityCurves(offset + 1u), read_filmDensityCurves(offset + 2u));
    }
  } else if (operation == 2u) {
    // Direct film scans carry density through the print stage unchanged.
    result = u(0u) == 1u ? code : fourStageEncodeLog(printLogRaw(fourStageDecodeDensity(code)), 2u);
  } else if (u(0u) == 1u) {
    const vec3 density = fourStageDecodeDensity(code);
    const vec4 scan = scanDensityToOutputRgbLinearY(negativeBleachBypassDyeDensity(density, f(7u)),
      bleachBypassRetainedSilverDensity(density, f(7u), false), false);
    result = finalizeOutputRgb(applyFilmScanOutputContract(scan));
  } else {
    const vec3 density = developPrintDensity(fourStageDecodeLog(code, 2u));
    const vec4 scan = scanDensityToOutputRgbLinearY(bleachBypassDyeDensity(density, f(9u), true),
      bleachBypassRetainedSilverDensity(density, f(9u), true), true);
    result = finalizeOutputRgb(applyPrintScanOutputContract(scan));
  }
  fourStageLutData[kFourStageTableOffset + operation * kFourStageLutCount + index] =
    vec4(clamp(result, vec3(0.0), vec3(1.0)), 0.0);
}
