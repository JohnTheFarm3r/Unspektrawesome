package com.unspektrawesome.camera

import android.content.Context
import android.os.Build
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.settings.LensProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

data class RawFrameRateReading(val requested: Int, val measured: Double?, val frames: Int = 0) {
    val supported: Boolean get() = measured != null && frames >= 4 &&
        abs(measured - requested) <= maxOf(.4, requested * .02)
    val delivered: Double? get() = measured?.let {
        if (abs(it - it.roundToInt()) <= .2) it.roundToInt().toDouble() else it
    }
}

data class RawFrameRateReport(val readings: List<RawFrameRateReading>) {
    val supported: List<Int> get() = readings.filter { it.supported }.map { it.requested }.distinct().sorted()
    val videoChoices: List<Int> get() = supported.filter { it == 24 || it == 30 }
    fun delivered(requested: Int): Double? = readings.firstOrNull { it.requested == requested }?.delivered
}

class RawFrameRateStore(context: Context) {
    private val preferences = context.getSharedPreferences("raw_frame_rates", Context.MODE_PRIVATE)

    fun load(camera: CameraDescriptor, profile: LensProfile): RawFrameRateReport? = runCatching {
        val text = preferences.getString(key(camera, profile), null) ?: return null
        val entries = JSONArray(text)
        RawFrameRateReport((0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            RawFrameRateReading(entry.getInt("requested"), entry.optDouble("measured").takeIf { it.isFinite() && it > 0 },
                entry.getInt("frames"))
        })
    }.getOrNull()

    fun save(camera: CameraDescriptor, profile: LensProfile, report: RawFrameRateReport) {
        val entries = JSONArray().apply { report.readings.forEach { reading ->
            put(JSONObject().put("requested", reading.requested).put("measured", reading.measured ?: JSONObject.NULL)
                .put("frames", reading.frames))
        } }
        preferences.edit().putString(key(camera, profile), entries.toString()).apply()
    }

    private fun key(camera: CameraDescriptor, profile: LensProfile): String {
        val source = listOf("1", Build.FINGERPRINT, BuildConfig.LENS_DISCOVERY_BUILD,
            camera.route.routeId, camera.rawOutputs.map { listOf(it.format, it.size.width, it.size.height,
                it.minimumFrameDurationNs, it.stallDurationNs, it.maximumResolutionMode) },
            camera.aeFpsRanges, profile.rawStream,
            profile.capture.ispSurface, profile.capture.requestBatchSize, profile.verification?.configuration?.session?.let {
                listOf(it.companionFormat, it.companionSize?.width, it.companionSize?.height, it.physicalCompanion, it.fpsRange)
            },
            profile.rawSession?.let(com.unspektrawesome.settings.RawSessionSettings::encode),
            profile.aeEngine, profile.vendorOverrides.toJson(), profile.rawLayoutOverride.toJson()).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
