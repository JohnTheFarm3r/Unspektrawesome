package com.unspektrawesome.camera.session;

import java.nio.FloatBuffer;

public final class RawCaptureMetadata {
    public final long sensorTimestampNs;
    public final long frameNumber;
    public final long exposureControlGeneration;
    public final boolean panoramaControlsLocked;
    public final Long exposureTimeNs;
    public final Long frameDurationNs;
    public final Integer sensitivityIso;
    /** ISO from this frame's manual request, absent when Android AE controls exposure. */
    public final Integer requestedSensitivityIso;
    public final Float focusDistanceDiopters;
    public final Float aperture;
    public final Float focalLengthMm;
    public final Integer dynamicWhiteLevel;
    public final Boolean rawBinningFactorUsed;
    public final StabilizationCapture stabilization;
    public final int lensShadingMapWidth;
    public final int lensShadingMapHeight;
    private final float[] dynamicBlackLevel;
    private final float[] neutralColorPoint;
    private final float[] lensShadingGainFactors;
    private final float[] noiseProfile;

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso,
                              Float focusDistanceDiopters, float[] dynamicBlackLevel,
                              Integer dynamicWhiteLevel, float[] neutralColorPoint) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                0, 0, null);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso,
                              Float focusDistanceDiopters, float[] dynamicBlackLevel,
                              Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight,
                              float[] lensShadingGainFactors) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, null, null);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso,
                              Float focusDistanceDiopters, float[] dynamicBlackLevel,
                              Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight,
                              float[] lensShadingGainFactors, Float aperture, Float focalLengthMm) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, aperture,
                focalLengthMm, null);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso,
                              Float focusDistanceDiopters, float[] dynamicBlackLevel,
                              Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight,
                              float[] lensShadingGainFactors, Float aperture, Float focalLengthMm,
                              float[] noiseProfile) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, aperture,
                focalLengthMm, noiseProfile, 0L);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso, Float focusDistanceDiopters,
                              float[] dynamicBlackLevel, Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight, float[] lensShadingGainFactors,
                              Float aperture, Float focalLengthMm, float[] noiseProfile, long exposureControlGeneration) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, aperture,
                focalLengthMm, noiseProfile, exposureControlGeneration, null);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso, Float focusDistanceDiopters,
                              float[] dynamicBlackLevel, Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight, float[] lensShadingGainFactors,
                              Float aperture, Float focalLengthMm, float[] noiseProfile, long exposureControlGeneration,
                              Integer requestedSensitivityIso) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, aperture,
                focalLengthMm, noiseProfile, exposureControlGeneration, requestedSensitivityIso, null);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso, Float focusDistanceDiopters,
                              float[] dynamicBlackLevel, Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight, float[] lensShadingGainFactors,
                              Float aperture, Float focalLengthMm, float[] noiseProfile, long exposureControlGeneration,
                              Integer requestedSensitivityIso, Boolean rawBinningFactorUsed) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, aperture,
                focalLengthMm, noiseProfile, exposureControlGeneration, requestedSensitivityIso,
                rawBinningFactorUsed, null);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso, Float focusDistanceDiopters,
                              float[] dynamicBlackLevel, Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight, float[] lensShadingGainFactors,
                              Float aperture, Float focalLengthMm, float[] noiseProfile, long exposureControlGeneration,
                              Integer requestedSensitivityIso, Boolean rawBinningFactorUsed,
                              StabilizationCapture stabilization) {
        this(sensorTimestampNs, frameNumber, exposureTimeNs, frameDurationNs, sensitivityIso,
                focusDistanceDiopters, dynamicBlackLevel, dynamicWhiteLevel, neutralColorPoint,
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors, aperture,
                focalLengthMm, noiseProfile, exposureControlGeneration, requestedSensitivityIso,
                rawBinningFactorUsed, stabilization, false);
    }

    public RawCaptureMetadata(long sensorTimestampNs, long frameNumber, Long exposureTimeNs,
                              Long frameDurationNs, Integer sensitivityIso, Float focusDistanceDiopters,
                              float[] dynamicBlackLevel, Integer dynamicWhiteLevel, float[] neutralColorPoint,
                              int lensShadingMapWidth, int lensShadingMapHeight, float[] lensShadingGainFactors,
                              Float aperture, Float focalLengthMm, float[] noiseProfile, long exposureControlGeneration,
                              Integer requestedSensitivityIso, Boolean rawBinningFactorUsed,
                              StabilizationCapture stabilization, boolean panoramaControlsLocked) {
        this.panoramaControlsLocked = panoramaControlsLocked;
        this.stabilization = stabilization;
        this.rawBinningFactorUsed = rawBinningFactorUsed;
        this.exposureControlGeneration = exposureControlGeneration;
        if (sensorTimestampNs <= 0) throw new IllegalArgumentException("Timestamp must be positive");
        this.sensorTimestampNs = sensorTimestampNs;
        this.frameNumber = frameNumber;
        this.exposureTimeNs = exposureTimeNs;
        this.frameDurationNs = frameDurationNs;
        this.sensitivityIso = sensitivityIso;
        this.requestedSensitivityIso = requestedSensitivityIso;
        this.focusDistanceDiopters = focusDistanceDiopters;
        this.aperture = aperture;
        this.focalLengthMm = focalLengthMm;
        this.noiseProfile = noiseProfile == null ? null : noiseProfile.clone();
        this.dynamicBlackLevel = copyLength(dynamicBlackLevel, 4, "Dynamic black level");
        this.dynamicWhiteLevel = dynamicWhiteLevel;
        this.neutralColorPoint = copyLength(neutralColorPoint, 3, "Neutral color point");
        this.lensShadingGainFactors = validateLensShadingMap(
                lensShadingMapWidth, lensShadingMapHeight, lensShadingGainFactors);
        this.lensShadingMapWidth = lensShadingMapWidth;
        this.lensShadingMapHeight = lensShadingMapHeight;
    }

    public float[] dynamicBlackLevel() {
        return dynamicBlackLevel == null ? null : dynamicBlackLevel.clone();
    }

    public float[] noiseProfile() {
        return noiseProfile == null ? null : noiseProfile.clone();
    }

    public float[] neutralColorPoint() {
        return neutralColorPoint == null ? null : neutralColorPoint.clone();
    }

    public boolean hasLensShadingMap() { return lensShadingGainFactors != null; }

    /** Row-major grid containing interleaved R, G-even, G-odd, and B gains. */
    public float[] lensShadingGainFactors() {
        return lensShadingGainFactors == null ? null : lensShadingGainFactors.clone();
    }

    /** Shares immutable gains without exposing the backing array. */
    public FloatBuffer lensShadingGainBuffer() {
        return lensShadingGainFactors == null ? null
                : FloatBuffer.wrap(lensShadingGainFactors).asReadOnlyBuffer();
    }

    private static float[] copyLength(float[] value, int length, String name) {
        if (value == null) return null;
        if (value.length != length) throw new IllegalArgumentException(name + " has invalid length");
        return value.clone();
    }

    private static float[] validateLensShadingMap(int width, int height, float[] gains) {
        if (gains == null) {
            if (width != 0 || height != 0) {
                throw new IllegalArgumentException("Lens shading dimensions require gain factors");
            }
            return null;
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Lens shading map dimensions must be positive");
        }
        long required = (long) width * height * 4;
        if (required != gains.length) {
            throw new IllegalArgumentException("Lens shading map gain count does not match dimensions");
        }
        float[] copy = gains.clone();
        for (float gain : copy) {
            if (!Float.isFinite(gain) || gain <= 0f) {
                throw new IllegalArgumentException("Lens shading gains must be finite and positive");
            }
        }
        return copy;
    }
}
