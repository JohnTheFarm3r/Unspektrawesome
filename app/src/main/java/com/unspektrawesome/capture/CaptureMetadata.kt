package com.unspektrawesome.capture

import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraLook
import org.json.JSONObject
import kotlin.math.roundToInt

data class CaptureMetadata(
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val focalLengthMm: Float?,
    val lensModel: String,
    val aperture: Float? = null,
    val equivalentFocalLengthMm: Float? = null,
    val identity: CaptureIdentity? = null,
    val spektra: SpektraShotMetadata? = null,
    val utcOffsetSeconds: Int? = null,
    val location: CaptureLocation? = null,
    val colorCalibration: String? = null,
    val dcpId: String? = null,
    val lensProfile: LensProfileShotMetadata? = null,
    val merge: MergeShotMetadata? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", sensorTimestampNs)
        put("exposure", exposureTimeNs)
        put("iso", sensitivityIso)
        put("focalLength", focalLengthMm?.takeIf { it.isFinite() })
        put("lens", lensModel)
        put("utcOffsetSeconds", utcOffsetSeconds)
        put("colorCalibration", colorCalibration)
        put("dcpId", dcpId)
        put("aperture", aperture?.takeIf { it.isFinite() })
        put("equivalentFocalLength", equivalentFocalLengthMm?.takeIf { it.isFinite() })
        identity?.let { put("identity", it.toJson()) }
        spektra?.let { put("spektra", it.toJson()) }
        location?.let { put("location", it.toJson()) }
        lensProfile?.let { put("lensProfile", it.toJson()) }
        merge?.let { put("merge", it.toJson()) }
    }

    fun description(): String = buildList {
        identity?.let {
            add("Device: ${it.marketingName}")
            add("Codename: ${it.codename}")
        }
        colorCalibration?.let { add("Color calibration: $it") }
        lensProfile?.let {
            add("Lens profile: ${it.name}")
            add("RAW stream: ${it.rawFormat} ${it.rawWidth} × ${it.rawHeight}")
            add("RAW crop: ${it.cropWidth} × ${it.cropHeight} (${it.cropFactor}×)")
        }
        merge?.let { addAll(it.description()) }
        spektra?.let {
            add("Spektra: ${if (it.enabled) "Enabled" else "Disabled"}")
            val look = "FILM: ${it.film}; FORMAT: ${it.format}"
            add(if (it.print.isEmpty()) look else "$look; PRINT: ${it.print}")
            addAll(it.effectDescription())
        }
    }.joinToString("; ")

    companion object {
        fun fromJson(value: JSONObject) = CaptureMetadata(
            sensorTimestampNs = value.getLong("timestamp"),
            exposureTimeNs = if (value.isNull("exposure")) null else value.getLong("exposure"),
            sensitivityIso = if (value.isNull("iso")) null else value.getInt("iso"),
            focalLengthMm = value.optionalFloat("focalLength"),
            lensModel = value.getString("lens"),
            aperture = value.optionalFloat("aperture"),
            equivalentFocalLengthMm = value.optionalFloat("equivalentFocalLength"),
            identity = value.optJSONObject("identity")?.let(CaptureIdentity::fromJson),
            spektra = value.optJSONObject("spektra")?.let(SpektraShotMetadata::fromJson),
            utcOffsetSeconds = if (value.isNull("utcOffsetSeconds")) null else value.getInt("utcOffsetSeconds"),
            location = CaptureLocation.fromJson(value.optJSONObject("location")),
            colorCalibration = value.optString("colorCalibration").takeIf { it.isNotBlank() },
            dcpId = value.optString("dcpId").takeIf { it.isNotBlank() },
            lensProfile = value.optJSONObject("lensProfile")?.let(LensProfileShotMetadata::fromJson),
            merge = value.optJSONObject("merge")?.let { runCatching { MergeShotMetadata.fromJson(it) }.getOrNull() },
        )

        private fun JSONObject.optionalFloat(key: String): Float? =
            if (isNull(key)) null else getDouble(key).toFloat().takeIf { it.isFinite() }
    }
}

data class SpektraShotMetadata(
    val enabled: Boolean,
    val film: String,
    val format: String,
    val print: String,
    val settingsJson: String,
) {
    fun effectDescription(): List<String> {
        val params = SpektraFilmParams.fromJson(JSONObject(settingsJson))
        fun diffusion(label: String, active: Boolean, family: Int, strength: Float): String {
            if (!enabled || !active) return "$label: Disabled"
            val name = SpektraLook.diffusionFamilies.getOrElse(family) { "Unknown ($family)" }
            return "$label: $name; Strength: $strength"
        }
        return listOf(
            if (enabled && params.halationEnabled)
                "HALATION: Enabled; Amount: ${params.halationAmount}; Scale: ${params.halationScale}"
            else "HALATION: Disabled",
            diffusion("CAMERA DIFFUSION", params.cameraDiffusionEnabled,
                params.cameraDiffusionFamily, params.cameraDiffusionStrength),
            diffusion("PRINT DIFFUSION", params.printDiffusionEnabled && !params.isMonochromeFilm,
                params.printDiffusionFamily, params.printDiffusionStrength),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("film", film)
        put("format", format)
        put("print", print)
        put("settings", JSONObject(settingsJson))
    }

    companion object {
        fun from(params: SpektraFilmParams) = SpektraShotMetadata(
            params.enabled,
            SpektraFilmParams.FILM_LABELS.getOrElse(params.film) { "Unknown (${params.film})" },
            SpektraLook.formats.getOrElse(params.filmFormat) { "Unknown (${params.filmFormat})" },
            if (params.isMonochromeFilm) "" else SpektraFilmParams.PAPER_LABELS.getOrElse(params.paper) { "Unknown (${params.paper})" },
            params.toJsonObject().toString(),
        )

        fun fromJson(value: JSONObject) = SpektraShotMetadata(
            value.getBoolean("enabled"), value.getString("film"), value.getString("format"),
            value.getString("print"), value.getJSONObject("settings").toString(),
        )
    }
}

/** Multi-frame merge settings at the shutter press and what the merge actually used. */
data class MergeShotMetadata(
    /** Frames to merge, as set in the lens profile. */
    val frames: Int,
    /** Motion sensitivity slider, 0 to 1. */
    val motion: Float,
    /** Frames merged, including the frame shown at the shutter press. 1 means the photo was not merged. */
    val merged: Int,
    /** Average number of frames that contributed to each pixel after moving areas were rejected. */
    val averagePerPixel: Float,
) {
    /** Share of the extra frames' pixels left out because something moved, 0 to 100. */
    val motionRejectedPercent: Int?
        get() = if (merged < 2) null
        else (100f * (1f - (averagePerPixel - 1f) / (merged - 1)).coerceIn(0f, 1f)).roundToInt()

    fun description(): List<String> = buildList {
        add("Frames to merge: $frames frames")
        add("Motion sensitivity: ${(motion * 100).roundToInt()}%")
        add("Frames merged: $merged of $frames")
        motionRejectedPercent?.let { add("Rejected for motion: $it%") }
    }

    fun toJson(): JSONObject = JSONObject().put("frames", frames).put("motion", motion.toDouble())
        .put("merged", merged).put("averagePerPixel", averagePerPixel.toDouble())

    companion object {
        fun fromJson(value: JSONObject) = MergeShotMetadata(value.getInt("frames"), value.getDouble("motion").toFloat(),
            value.getInt("merged"), value.getDouble("averagePerPixel").toFloat())
    }
}
