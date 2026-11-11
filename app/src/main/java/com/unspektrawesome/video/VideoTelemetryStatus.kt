package com.unspektrawesome.video

internal fun videoTelemetryStatus(issues: Set<String>): String = when {
    "live-stabilization-already-applied" in issues -> "Stabilized video saved"
    "gyro-unavailable" in issues -> "Video saved without gyro data"
    "ois-motion-unavailable" in issues -> "Gyro data saved. Automatic stabilization is unavailable with OIS."
    "camera-clock-unverified" in issues || "capture-timing-missing" in issues ->
        "Gyro data saved. Camera timing is unverified."
    issues.any { it.startsWith("gyro-") } -> "Gyro data saved. Motion data is incomplete."
    issues.any { it.startsWith("camera-imu-") || it == "intrinsics-missing" || it == "distortion-unknown" } ->
        "Gyro data saved. Camera calibration is unavailable."
    else -> "Gyro data saved. This recording mode is not supported by the stabilizer."
}
