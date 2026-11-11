package com.unspektrawesome.settings

import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.IntRange
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

enum class IspPreviewSurface(val label: String, val sessionValue: Int) {
    AUTO("Automatic", 0), OFF("Off", 1), PRIVATE("Private surface", 2), YUV("YUV surface", 3),
}

data class LensCaptureSettings(
    val frameRate: Int? = null,
    val frameInterval: Int = 1,
    val ispSurface: IspPreviewSurface = IspPreviewSurface.AUTO,
    val averageFrames: Int = 1,
    val requestBatchSize: Int = 1,
    // Multi-frame merge of the newest viewfinder frames when a photo is taken with this lens.
    val mergeEnabled: Boolean = false,
    val mergeFrames: Int = MERGE_FRAMES_DEFAULT,
    val mergeMotion: Float = MERGE_MOTION_DEFAULT,
) {
    init {
        require(frameRate == null || frameRate in 1..1000)
        require(frameInterval in 1..120)
        require(averageFrames in 1..frameInterval)
        require(requestBatchSize in 1..32)
        require(mergeFrames in MERGE_FRAMES_MIN..MERGE_FRAMES_MAX)
        require(mergeMotion.isFinite() && mergeMotion in 0f..1f)
    }

    /** The settings that shape the camera session; merge settings only affect photo processing. */
    fun sessionSettings(): LensCaptureSettings =
        copy(mergeEnabled = false, mergeFrames = MERGE_FRAMES_DEFAULT, mergeMotion = MERGE_MOTION_DEFAULT)

    fun toJson(): JSONObject = JSONObject().put("frameRate", frameRate ?: JSONObject.NULL)
        .put("frameInterval", frameInterval).put("ispSurface", ispSurface.name).put("averageFrames", averageFrames)
        .put("requestBatchSize", requestBatchSize)
        .put("mergeEnabled", mergeEnabled).put("mergeFrames", mergeFrames).put("mergeMotion", mergeMotion.toDouble())

    companion object {
        const val MERGE_FRAMES_MIN = 2
        const val MERGE_FRAMES_MAX = 15
        const val MERGE_FRAMES_DEFAULT = 8
        const val MERGE_MOTION_DEFAULT = 0.5f

        fun fromJson(json: JSONObject?): LensCaptureSettings = LensCaptureSettings(
            json?.optInt("frameRate")?.takeIf { it in 1..1000 },
            json?.optInt("frameInterval", 1)?.takeIf { it in 1..120 } ?: 1,
            IspPreviewSurface.entries.firstOrNull { it.name == json?.optString("ispSurface") } ?: IspPreviewSurface.AUTO,
            (json?.optInt("averageFrames", 1) ?: 1).coerceIn(1,
                json?.optInt("frameInterval", 1)?.takeIf { it in 1..120 } ?: 1),
            json?.optInt("requestBatchSize", 1)?.takeIf { it in 1..32 } ?: 1,
            json?.optBoolean("mergeEnabled", false) ?: false,
            (json?.optInt("mergeFrames", MERGE_FRAMES_DEFAULT) ?: MERGE_FRAMES_DEFAULT).coerceIn(MERGE_FRAMES_MIN, MERGE_FRAMES_MAX),
            json?.optDouble("mergeMotion", MERGE_MOTION_DEFAULT.toDouble())?.toFloat()?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f) ?: MERGE_MOTION_DEFAULT)
    }
}

/** Updates the capture settings of the active lens profile. */
fun CameraPreferences.withActiveLensCapture(transform: (LensCaptureSettings) -> LensCaptureSettings): CameraPreferences =
    copy(lensProfiles = lensProfiles.map {
        if (it.id == activeLensProfileId) it.copy(capture = transform(it.capture)) else it
    })

/** Capture settings of the active lens profile, or null when no lens profile is active. */
fun CameraPreferences.activeLensCapture(): LensCaptureSettings? =
    lensProfiles.firstOrNull { it.id == activeLensProfileId && it.enabled }?.capture

enum class LensFocalLengthLabel(val label: String) { ZOOM("Zoom ratio"), EQUIVALENT("35 mm equivalent") }

data class LensFocalLengthSettings(
    val factor: Float = 1f,
    val label: LensFocalLengthLabel = LensFocalLengthLabel.ZOOM,
) {
    init { require(factor.isFinite() && factor in .1f..20f) }

    fun toJson(): JSONObject = JSONObject().put("factor", factor).put("label", label.name)

    fun equivalent(camera: CameraDescriptor): Float? = camera.equivalentFocalLengthMm
        ?.takeIf { it.isFinite() && it > 0f }?.times(factor)

    fun label(camera: CameraDescriptor): String {
        if (label == LensFocalLengthLabel.EQUIVALENT) equivalent(camera)?.let { return "${it.roundToInt()}mm" }
        val zoom = camera.relativeZoom?.takeIf { it.isFinite() && it > 0f }?.times(factor)
        return zoom?.let {
            if (it == it.roundToInt().toFloat()) "${it.roundToInt()}×" else String.format(Locale.ROOT, "%.1f×", it)
        } ?: "Lens"
    }

    companion object {
        fun fromJson(json: JSONObject?): LensFocalLengthSettings = LensFocalLengthSettings(
            json?.optDouble("factor", 1.0)?.toFloat()?.takeIf { it.isFinite() && it in .1f..20f } ?: 1f,
            LensFocalLengthLabel.entries.firstOrNull { it.name == json?.optString("label") } ?: LensFocalLengthLabel.ZOOM)
    }
}

fun CameraPreferences.lensCaptureSettings(): LensCaptureSettings =
    lensProfiles.firstOrNull { it.id == activeLensProfileId && it.enabled }?.capture ?: LensCaptureSettings()

object LensFrameRates {
    fun advertised(camera: CameraDescriptor): List<Int> = camera.aeFpsRanges
        .flatMap { listOf(it.lower, it.upper) }.filter { it in 1..1000 }.distinct().sorted()

    fun range(camera: CameraDescriptor, framesPerSecond: Int): IntRange =
        camera.aeFpsRanges.firstOrNull { it.lower == framesPerSecond && it.upper == framesPerSecond }
            ?: camera.aeFpsRanges.filter { it.upper == framesPerSecond }.maxByOrNull { it.lower }
            ?: IntRange(framesPerSecond, framesPerSecond)
}
