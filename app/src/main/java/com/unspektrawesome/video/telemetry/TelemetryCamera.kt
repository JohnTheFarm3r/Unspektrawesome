package com.unspektrawesome.video.telemetry

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.os.Build
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.camera.CameraRoute
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.session.RawCaptureMetadata
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.settings.RawLayoutOverride
import org.json.JSONArray
import org.json.JSONObject

internal class TelemetryCamera(manager: CameraManager, val route: CameraRoute, val raw: RawOutput,
    frame: FrameGeometrySnapshot, width: Int, height: Int, val rotation: Int,
    val averagedFrames: Int = 1, val layoutOverride: RawLayoutOverride = RawLayoutOverride.NONE,
    val liveStabilization: Boolean = false) {
    private val characteristics = manager.getCameraCharacteristics(route.characteristicsCameraId)
    private val maximum = raw.maximumResolutionMode && Build.VERSION.SDK_INT >= 31
    private val pixel = characteristics.get(if (maximum) C.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION else C.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    private val active = characteristics.get(if (maximum) C.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION else C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val pre = characteristics.get(if (maximum) C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION else C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
    private val rawSizes = characteristics.get(if (maximum) C.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        else C.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(raw.format.imageFormat).orEmpty()
    private val rawActiveOnly = pre != null &&
        (raw.size.width == pre.width() && raw.size.height == pre.height() ||
            rawSizes.none { it == pixel } && rawSizes.any { it.width == pre.width() && it.height == pre.height() })
    private fun Rect.array() = intArrayOf(left, top, right, bottom)
    private val decodedSize = layoutOverride.size(raw.size)
    private val sourceMatchesRaw = frame.sourceSize == decodedSize &&
        TelemetryRawLayout.preservesPixelMapping(layoutOverride, raw.size)
    val geometry = if (pixel != null && active != null && pre != null && sourceMatchesRaw) TelemetryGeometry.create(frame,
        width, height, pixel.width, pixel.height, active.array(), pre.array(), rawActiveOnly, raw.size) else null
    val timestampSource = characteristics.get(C.SENSOR_INFO_TIMESTAMP_SOURCE)
    val poseReference = characteristics.get(C.LENS_POSE_REFERENCE)
    val pose = characteristics.get(C.LENS_POSE_ROTATION)
    private val oisModes = characteristics.get(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
    val oisSupported = oisModes?.contains(1) == true
    private val staticIntrinsics = characteristics.get(if (maximum) C.LENS_INTRINSIC_CALIBRATION_MAXIMUM_RESOLUTION else C.LENS_INTRINSIC_CALIBRATION)
    private val staticDistortion = characteristics.get(if (maximum) C.LENS_DISTORTION_MAXIMUM_RESOLUTION else C.LENS_DISTORTION)
    private val physicalSize = characteristics.get(C.SENSOR_INFO_PHYSICAL_SIZE)

    fun header(): JSONObject = JSONObject().put("schema", "com.unspektrawesome.stabilization").put("version", 1)
        .put("gyroflowRevision", "dcf8bfb62cfa0c6e4303b1d862ae2312b159daed")
        .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
        .put("buildFingerprint", Build.FINGERPRINT)
        .put("logicalCameraId", route.openCameraId).put("characteristicsCameraId", route.characteristicsCameraId)
        .put("physicalCameraId", route.physicalCameraId ?: JSONObject.NULL)
        .put("sensorOrientation", characteristics.get(C.SENSOR_ORIENTATION) ?: JSONObject.NULL)
        .put("lensFacing", characteristics.get(C.LENS_FACING) ?: JSONObject.NULL)
        .put("pixelMode", if (maximum) 1 else 0).put("rawWidth", raw.size.width).put("rawHeight", raw.size.height)
        .put("rawFormat", raw.format.name).put("timestampSource", timestampSource ?: JSONObject.NULL)
        .put("lensPoseReference", poseReference ?: JSONObject.NULL).putArray("lensPoseXyzw", pose)
        .put("capturePoseAdvertised", characteristics.availableCaptureResultKeys?.contains(android.hardware.camera2.CaptureResult.LENS_POSE_ROTATION) == true)
        .put("geometry", geometry?.json() ?: JSONObject.NULL).put("displayRotation", rotation)
        .put("physicalWidthMm", physicalSize?.width ?: JSONObject.NULL)
        .put("physicalHeightMm", physicalSize?.height ?: JSONObject.NULL)
        .put("geometricDistortionCorrectionApplied", false).put("averagedCaptures", averagedFrames)
        .put("rawLayoutOverride", layoutOverride.enabled).put("decodedRawWidth", decodedSize.width)
        .put("decodedRawHeight", decodedSize.height).put("gyroUnits", "rad/s")
        .put("gyroAxes", "Android sensor X right, Y up, Z out of screen")
        .put("cameraAxes", "right, down, forward").put("videoTimeBase", "1/1000000")
        .put("liveStabilizationApplied", liveStabilization)

    fun frame(capture: RawCaptureMetadata, imageTimestampNs: Long): JSONObject {
        val c = capture.stabilization
        val sameCamera = c?.physicalCameraId == null || c.physicalCameraId == route.characteristicsCameraId ||
            c.physicalCameraId == route.physicalCameraId
        val calibratedResult = c?.takeIf { it.resultCameraId == route.characteristicsCameraId }
        val dynamicK = calibratedResult?.intrinsics()
        val k = dynamicK ?: staticIntrinsics.takeIf { sameCamera }
        val distortion = calibratedResult?.distortion() ?: staticDistortion.takeIf { sameCamera }
        val reportedPose = c?.lensPose()
        val staticPose = pose.takeIf { sameCamera }
        val poseChoice = TelemetryCameraCompatibility.pose(Build.DEVICE, poseReference,
            characteristics.get(C.SENSOR_ORIENTATION), characteristics.get(C.LENS_FACING), staticPose,
            TelemetryCalibration.pose(staticPose, reportedPose.takeIf { calibratedResult != null }))
        val pose = poseChoice.rotation
        val outputK = k?.let { runCatching { geometry?.outputIntrinsics(it) }.getOrNull() }
        val issues = mutableListOf<String>()
        if (timestampSource != C.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) issues += "camera-clock-unverified"
        if (poseReference !in listOf(0, 1) || !TelemetryCalibration.validRotation(pose)) issues += "camera-imu-calibration-missing"
        if (outputK == null) issues += "intrinsics-missing"
        if (distortion == null) issues += "distortion-unknown"
        if (distortion != null && distortion.any { it != 0f }) issues += "android-distortion-unsupported"
        if (c?.rollingShutterSkewNs == null || c.rollingShutterSkewNs < 0 ||
            capture.exposureTimeNs == null || capture.exposureTimeNs <= 0) issues += "capture-timing-missing"
        if (imageTimestampNs != capture.sensorTimestampNs) issues += "capture-identity-mismatch"
        if (c?.pixelMode != null && c.pixelMode != if (maximum) 1 else 0) issues += "sensor-mode-changed"
        if (geometry == null || !sameCamera || !sourceMatchesRaw)
            issues += "sensor-mapping-unverified"
        if (averagedFrames != 1) issues += "averaged-captures-unsupported"
        if (geometry?.reflected == true) issues += "mirrored-output-unsupported"
        if (geometry != null && geometry.direction != 0) issues += "readout-direction-unsupported"
        if (outputK != null && (outputK[0] <= 0 || outputK[4] <= 0 || kotlin.math.abs(outputK[1]) > 1e-5 || kotlin.math.abs(outputK[3]) > 1e-5))
            issues += "camera-matrix-unsupported"
        val ois = when {
            c?.oisMode == 1 -> "enabled"
            oisModes != null && !oisModes.contains(1) -> "absent"
            c?.oisMode == 0 -> "disabled"
            else -> "unknown"
        }
        if (ois in listOf("enabled", "unknown")) issues += "ois-motion-unavailable"
        return JSONObject().put("captureId", capture.frameNumber).put("imageTimestampNs", imageTimestampNs)
            .put("sensorTimestampNs", capture.sensorTimestampNs).put("exposureNs", capture.exposureTimeNs ?: JSONObject.NULL)
            .put("durationNs", capture.frameDurationNs ?: JSONObject.NULL).put("skewNs", c?.rollingShutterSkewNs ?: JSONObject.NULL)
            .put("physicalCameraId", c?.physicalCameraId ?: route.physicalCameraId ?: JSONObject.NULL)
            .put("captureResultCameraId", c?.resultCameraId ?: JSONObject.NULL)
            .put("pixelMode", c?.pixelMode ?: JSONObject.NULL).put("ois", ois)
            .put("rawBinningFactorUsed", capture.rawBinningFactorUsed ?: JSONObject.NULL)
            .put("zoom", c?.zoomRatio ?: JSONObject.NULL).put("crop", c?.crop?.let { JSONArray(it.array().toList()) } ?: JSONObject.NULL)
            .put("intrinsicsProvenance", if (dynamicK != null) "capture-result" else if (k != null) "camera-characteristics" else "unavailable")
            .putArray("sensorIntrinsics", k).put("outputIntrinsics", outputK?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .putArray("androidDistortion", distortion).putArray("lensPoseXyzw", pose)
            .putArray("captureLensPoseXyzw", reportedPose).put("lensPoseProvenance", poseChoice.provenance)
            .put("focalLengthMm", capture.focalLengthMm ?: JSONObject.NULL).put("focusDiopters", capture.focusDistanceDiopters ?: JSONObject.NULL)
            .put("oisResult", c?.oisMode ?: JSONObject.NULL).put("distortionCorrectionMode", c?.distortionCorrectionMode ?: JSONObject.NULL)
            .put("issues", JSONArray(issues)).put("iso", capture.sensitivityIso ?: JSONObject.NULL)
    }
}

internal fun JSONObject.putArray(key: String, values: FloatArray?): JSONObject = put(key, values?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
