package com.unspektrawesome.camera.session;

import android.graphics.Rect;
import android.hardware.camera2.CaptureResult;

/** Immutable metadata from the capture result matched to a RAW image. */
public final class StabilizationCapture {
    public final Long rollingShutterSkewNs;
    public final Integer oisMode;
    public final Integer pixelMode;
    public final Integer distortionCorrectionMode;
    public final String physicalCameraId;
    public final String resultCameraId;
    public final Float zoomRatio;
    public final Rect crop;
    private final float[] intrinsics;
    private final float[] distortion;
    private final float[] lensPose;

    public StabilizationCapture(CaptureResult result, String physicalCameraId, String resultCameraId) {
        rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        oisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        pixelMode = android.os.Build.VERSION.SDK_INT >= 31 ? result.get(CaptureResult.SENSOR_PIXEL_MODE) : null;
        distortionCorrectionMode = result.get(CaptureResult.DISTORTION_CORRECTION_MODE);
        this.physicalCameraId = physicalCameraId;
        this.resultCameraId = resultCameraId;
        zoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
        Rect rectangle = result.get(CaptureResult.SCALER_CROP_REGION);
        crop = rectangle == null ? null : new Rect(rectangle);
        intrinsics = copy(result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION));
        distortion = copy(result.get(CaptureResult.LENS_DISTORTION));
        lensPose = copy(result.get(CaptureResult.LENS_POSE_ROTATION));
    }

    private static float[] copy(float[] values) { return values == null ? null : values.clone(); }
    public float[] intrinsics() { return copy(intrinsics); }
    public float[] distortion() { return copy(distortion); }
    public float[] lensPose() { return copy(lensPose); }
}
