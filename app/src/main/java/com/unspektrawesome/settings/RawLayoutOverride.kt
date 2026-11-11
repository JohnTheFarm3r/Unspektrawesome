package com.unspektrawesome.settings

import com.unspektrawesome.camera.RawFormat
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.Size2d
import org.json.JSONObject

data class RawLayoutOverride(
    val enabled: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val rowStrideBytes: Int? = null,
    val tightStride: Boolean = false,
) {
    fun active(): RawLayoutOverride = if (enabled) this else NONE

    fun size(reported: Size2d): Size2d = if (enabled)
        Size2d(width ?: reported.width, height ?: reported.height) else reported

    fun stride(reported: Int): Int = if (enabled) rowStrideBytes ?: reported else reported

    fun stride(reported: Int, format: RawFormat, reportedSize: Size2d): Int =
        if (enabled && tightStride) minimumStride(format, size(reportedSize).width) else stride(reported)

    fun stream(reported: RawStreamSelection): RawStreamSelection = if (!enabled) reported else
        reported.copy(width = width ?: reported.width, height = height ?: reported.height)

    fun output(reported: RawOutput): RawOutput = if (!enabled) reported else
        RawOutput(reported.format, size(reported.size), reported.minimumFrameDurationNs,
            reported.stallDurationNs, reported.maximumResolutionMode)

    fun validationError(format: RawFormat, reported: Size2d): String? {
        if (!enabled) return null
        if (listOfNotNull(width, height).any { it !in 2..65_536 })
            return "Width and height must be between 2 and 65536 pixels, or Auto."
        if (!tightStride && rowStrideBytes != null && rowStrideBytes <= 0) return "Row stride must be positive, or Auto."
        val columns = (width ?: reported.width).toLong()
        val rowBytes = when (format) {
            RawFormat.RAW10 -> (columns + 3) / 4 * 5
            RawFormat.RAW12 -> (columns + 1) / 2 * 3
            RawFormat.RAW_SENSOR -> columns * 2
        }
        if (!tightStride && rowStrideBytes != null && rowStrideBytes < rowBytes)
            return "Row stride must be at least $rowBytes bytes for this width and format."
        if ((if (tightStride) rowBytes else rowStrideBytes?.toLong() ?: rowBytes) * (height ?: reported.height) > Int.MAX_VALUE)
            return "The RAW layout is too large."
        return null
    }

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled)
        .put("width", width ?: JSONObject.NULL).put("height", height ?: JSONObject.NULL)
        .put("rowStrideBytes", rowStrideBytes ?: JSONObject.NULL).put("tightStride", tightStride)

    companion object {
        fun minimumStride(format: RawFormat, width: Int): Int = when (format) {
            RawFormat.RAW10 -> ((width.toLong() + 3) / 4 * 5).toInt()
            RawFormat.RAW12 -> ((width.toLong() + 1) / 2 * 3).toInt()
            RawFormat.RAW_SENSOR -> Math.multiplyExact(width, 2)
        }

        @JvmField val NONE = RawLayoutOverride()
        fun fromJson(value: JSONObject?): RawLayoutOverride {
            fun number(key: String): Int? = if (value == null || value.isNull(key)) null else value.getInt(key)
            return RawLayoutOverride(value?.optBoolean("enabled", false) ?: false,
                number("width"), number("height"), number("rowStrideBytes"), value?.optBoolean("tightStride", false) ?: false)
        }
    }
}

fun CameraPreferences.rawLayoutOverride(): RawLayoutOverride =
    lensProfiles.firstOrNull { it.id == activeLensProfileId && it.enabled }?.rawLayoutOverride?.active()
        ?: RawLayoutOverride.NONE
