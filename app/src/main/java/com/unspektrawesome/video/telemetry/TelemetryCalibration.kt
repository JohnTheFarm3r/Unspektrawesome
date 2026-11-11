package com.unspektrawesome.video.telemetry

internal object TelemetryCalibration {
    data class Pose(val rotation: FloatArray?, val provenance: String)

    fun sameRotation(a: FloatArray, b: FloatArray): Boolean = a.size == 4 && b.size == 4 &&
        (a.indices.all { kotlin.math.abs(a[it] - b[it]) <= 1e-5f } ||
            a.indices.all { kotlin.math.abs(a[it] + b[it]) <= 1e-5f })

    fun validRotation(value: FloatArray?): Boolean = value?.size == 4 && value.all { it.isFinite() } &&
        kotlin.math.abs(value.sumOf { it.toDouble() * it } - 1.0) <= .02

    fun pose(characteristics: FloatArray?, result: FloatArray?): Pose {
        val calibrated = characteristics?.takeIf(::validRotation)
        val captured = result?.takeIf(::validRotation)
        val identity = captured != null && captured.take(3).all { it == 0f }
        // Some camera HALs publish identity in capture results even though the
        // same camera supplies a calibrated mount rotation in its characteristics.
        if (identity && calibrated != null && !sameRotation(calibrated, captured))
            return Pose(calibrated, "camera-characteristics:result-identity-placeholder")
        if (captured != null) return Pose(captured, "capture-result")
        return Pose(calibrated, if (calibrated != null) "camera-characteristics" else "unavailable")
    }
}
