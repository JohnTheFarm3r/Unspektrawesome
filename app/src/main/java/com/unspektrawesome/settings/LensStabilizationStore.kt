package com.unspektrawesome.settings

import android.content.Context
import android.content.SharedPreferences
import com.unspektrawesome.video.LiveStabilizationSettings

/** Stabilization that earlier versions saved beside the lens profiles. It now belongs to each profile. */
class LensStabilizationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("lens_stabilization", Context.MODE_PRIVATE)
    private val legacy = context.applicationContext.getSharedPreferences("video_recording", Context.MODE_PRIVATE)

    /** Gives every lens profile the stabilization saved for it. Returns [saved] unchanged once [markMoved] was called. */
    fun moveToProfiles(saved: CameraPreferences): CameraPreferences {
        if (preferences.getBoolean(MOVED, false)) return saved
        val defaults = if (preferences.contains("defaults.ois")) read(preferences, "defaults.", VideoStabilization())
            else read(legacy, "", VideoStabilization())
        return saved.copy(lensProfiles = saved.lensProfiles.map {
            it.copy(videoStabilization = read(preferences, "profile:${it.id}.", defaults))
        })
    }

    /** Call after the result of [moveToProfiles] is saved. */
    fun markMoved() {
        if (!preferences.getBoolean(MOVED, false)) preferences.edit().putBoolean(MOVED, true).apply()
    }

    private fun read(source: SharedPreferences, prefix: String, fallback: VideoStabilization): VideoStabilization {
        fun amount(name: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
            source.getFloat(prefix + name, default).takeIf { it.isFinite() }?.coerceIn(range) ?: default
        val settings = fallback.settings
        return VideoStabilization(
            ois = source.getBoolean(prefix + "ois", fallback.ois),
            captureGyro = source.getBoolean(prefix + "capture_gyro", fallback.captureGyro),
            liveStabilization = source.getBoolean(prefix + "live_stabilization", fallback.liveStabilization),
            settings = LiveStabilizationSettings(
                amount("stabilization_crop", settings.cropRatio, 1f..1.5f),
                amount("stabilization_smoothing", settings.smoothingSeconds, .05f..1f),
                amount("stabilization_strength", settings.strength, 0f..1f)),
        )
    }

    private companion object {
        const val MOVED = "moved_to_profiles"
    }
}
