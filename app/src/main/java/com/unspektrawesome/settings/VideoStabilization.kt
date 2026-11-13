package com.unspektrawesome.settings

import com.unspektrawesome.video.LiveStabilizationSettings
import com.unspektrawesome.video.VideoRecordingState
import org.json.JSONObject

/** Stabilization a video lens profile starts with. The video menu changes it only until another profile is used. */
data class VideoStabilization(
    val ois: Boolean = true,
    val captureGyro: Boolean = false,
    val liveStabilization: Boolean = false,
    val settings: LiveStabilizationSettings = LiveStabilizationSettings(),
) {
    fun applyTo(state: VideoRecordingState): VideoRecordingState = state.copy(oisEnabled = ois, captureGyro = captureGyro,
        liveStabilization = liveStabilization, stabilizationSettings = settings)

    fun toJson(): JSONObject = JSONObject().put("ois", ois).put("captureGyro", captureGyro)
        .put("liveStabilization", liveStabilization).put("crop", settings.cropRatio.toDouble())
        .put("smoothing", settings.smoothingSeconds.toDouble()).put("strength", settings.strength.toDouble())

    companion object {
        fun fromJson(json: JSONObject?): VideoStabilization {
            if (json == null) return VideoStabilization()
            val defaults = LiveStabilizationSettings()
            fun amount(name: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
                json.optDouble(name, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(range) ?: default
            return VideoStabilization(json.optBoolean("ois", true), json.optBoolean("captureGyro", false),
                json.optBoolean("liveStabilization", false),
                LiveStabilizationSettings(amount("crop", defaults.cropRatio, 1f..1.5f),
                    amount("smoothing", defaults.smoothingSeconds, .05f..1f), amount("strength", defaults.strength, 0f..1f)))
        }
    }
}
