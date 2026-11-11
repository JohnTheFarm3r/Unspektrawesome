package com.unspektrawesome.settings

import android.content.Context
import android.content.SharedPreferences
import com.unspektrawesome.video.LiveStabilizationSettings
import com.unspektrawesome.video.VideoRecordingState

class LensStabilizationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("lens_stabilization", Context.MODE_PRIVATE)

    init {
        if (!preferences.contains("defaults.ois")) {
            val legacy = context.applicationContext.getSharedPreferences("video_recording", Context.MODE_PRIVATE)
            write("defaults", read(legacy, "", VideoRecordingState()))
        }
    }

    fun load(key: String?, state: VideoRecordingState): VideoRecordingState =
        read(preferences, "${key ?: "defaults"}.", read(preferences, "defaults.", state))

    fun save(key: String, state: VideoRecordingState) = write(key, state)

    private fun write(key: String, state: VideoRecordingState) {
        val prefix = key + "."
        preferences.edit()
            .putBoolean(prefix + "ois", state.oisEnabled)
            .putBoolean(prefix + "capture_gyro", state.captureGyro)
            .putBoolean(prefix + "live_stabilization", state.liveStabilization)
            .putFloat(prefix + "stabilization_crop", state.stabilizationSettings.cropRatio)
            .putFloat(prefix + "stabilization_smoothing", state.stabilizationSettings.smoothingSeconds)
            .putFloat(prefix + "stabilization_strength", state.stabilizationSettings.strength)
            .apply()
    }

    private fun read(source: SharedPreferences, prefix: String, state: VideoRecordingState): VideoRecordingState {
        fun amount(name: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
            source.getFloat(prefix + name, default).takeIf { it.isFinite() }?.coerceIn(range) ?: default
        val settings = state.stabilizationSettings
        return state.copy(
            oisEnabled = source.getBoolean(prefix + "ois", state.oisEnabled),
            captureGyro = source.getBoolean(prefix + "capture_gyro", state.captureGyro),
            liveStabilization = source.getBoolean(prefix + "live_stabilization", state.liveStabilization),
            stabilizationSettings = LiveStabilizationSettings(
                amount("stabilization_crop", settings.cropRatio, 1f..1.5f),
                amount("stabilization_smoothing", settings.smoothingSeconds, .05f..1f),
                amount("stabilization_strength", settings.strength, 0f..1f)),
        )
    }

    companion object {
        fun key(preferences: CameraPreferences, routeId: String?): String? {
            if (routeId == null) return null
            val profile = preferences.lensProfiles.firstOrNull {
                it.id == preferences.activeLensProfileId && it.enabled && it.routeId == routeId
            }
            return profile?.let { "profile:${it.id}" } ?: "route:$routeId"
        }
    }
}
