package com.unspektrawesome.video.telemetry

internal object TelemetryCameraCompatibility {
    fun pose(device: String, reference: Int?, sensorOrientation: Int?, lensFacing: Int?,
        characteristics: FloatArray?, selected: TelemetryCalibration.Pose): TelemetryCalibration.Pose {
        val telephotoBasis = floatArrayOf(0f, 1f, 0f, 0f)
        if (reference == 0 && sensorOrientation == 90 && lensFacing == 1 &&
            characteristics != null && TelemetryCalibration.sameRotation(characteristics, telephotoBasis) &&
            selected.rotation != null && TelemetryCalibration.sameRotation(selected.rotation, telephotoBasis)) {
            // This nominal rear pose omits the RAW sensor's quarter turn. Rotate
            // the camera basis, not the gyro samples or the displayed image.
            val q = selected.rotation
            val c = kotlin.math.sqrt(.5).toFloat()
            return TelemetryCalibration.Pose(floatArrayOf(c * (q[0] - q[1]), c * (q[0] + q[1]),
                c * (q[2] + q[3]), c * (q[3] - q[2])),
                selected.provenance + ":raw-axis-basis-correction")
        }
        val deviceAligned = floatArrayOf(1f, 0f, 0f, 0f)
        if (device == "dash" && reference == 0 && sensorOrientation == 90 && lensFacing == 1 &&
            characteristics != null && TelemetryCalibration.sameRotation(characteristics, deviceAligned) &&
            selected.rotation != null && TelemetryCalibration.sameRotation(selected.rotation, deviceAligned)) {
            // This HAL reports rear camera axes before the RAW sensor's quarter turn.
            // Independent yaw, pitch and roll recordings verify the axis correction.
            val c = kotlin.math.sqrt(.5).toFloat()
            return TelemetryCalibration.Pose(floatArrayOf(c, -c, 0f, 0f),
                selected.provenance + ":raw-axis-basis-correction")
        }
        return selected
    }
}
