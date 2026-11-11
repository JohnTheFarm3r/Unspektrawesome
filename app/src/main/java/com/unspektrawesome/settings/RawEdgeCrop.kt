package com.unspektrawesome.settings

import com.unspektrawesome.camera.Rect2d
import com.unspektrawesome.camera.Size2d
import org.json.JSONObject

data class RawEdgeCrop(
    val enabled: Boolean = false,
    val left: Int = 0,
    val right: Int = 0,
    val top: Int = 0,
    val bottom: Int = 0,
    val centerFactor: Float = 1f,
) {
    val isActive: Boolean get() = enabled || centerFactor > 1f

    fun isValid(size: Size2d): Boolean = listOf(left, right, top, bottom).all { it in 0..MAX_EDGE_PIXELS } &&
        (!enabled || left + right <= size.width - 2 && top + bottom <= size.height - 2) &&
        centerFactor.isFinite() && centerFactor in 1f..MAX_CENTER_FACTOR

    fun bounds(size: Size2d): Rect2d {
        require(size.width >= 2 && size.height >= 2)
        val x = if (enabled) left.coerceIn(0, minOf(MAX_EDGE_PIXELS, size.width - 2)) else 0
        val y = if (enabled) top.coerceIn(0, minOf(MAX_EDGE_PIXELS, size.height - 2)) else 0
        val r = if (enabled) right.coerceIn(0, minOf(MAX_EDGE_PIXELS, size.width - x - 2)) else 0
        val b = if (enabled) bottom.coerceIn(0, minOf(MAX_EDGE_PIXELS, size.height - y - 2)) else 0
        val width = size.width - x - r
        val height = size.height - y - b
        val factor = normalizedFactor(centerFactor)
        if (factor == 1f) return Rect2d(x, y, x + width, y + height)
        val croppedWidth = (width / factor / 2).toInt().coerceAtLeast(1) * 2
        val croppedHeight = (height / factor / 2).toInt().coerceAtLeast(1) * 2
        val croppedX = x + (width - croppedWidth) / 4 * 2
        val croppedY = y + (height - croppedHeight) / 4 * 2
        return Rect2d(croppedX, croppedY, croppedX + croppedWidth, croppedY + croppedHeight)
    }

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("left", left).put("right", right)
        .put("top", top).put("bottom", bottom).put("centerFactor", centerFactor.toDouble())

    companion object {
        const val MAX_EDGE_PIXELS = 65534
        const val MAX_CENTER_FACTOR = 16f
        private fun normalizedFactor(value: Float) = if (value.isFinite()) value.coerceIn(1f, MAX_CENTER_FACTOR) else 1f
        fun fromJson(value: JSONObject?): RawEdgeCrop = RawEdgeCrop(
            value?.optBoolean("enabled", false) ?: false,
            value?.optInt("left", 0)?.coerceIn(0, MAX_EDGE_PIXELS) ?: 0,
            value?.optInt("right", 0)?.coerceIn(0, MAX_EDGE_PIXELS) ?: 0,
            value?.optInt("top", 0)?.coerceIn(0, MAX_EDGE_PIXELS) ?: 0,
            value?.optInt("bottom", 0)?.coerceIn(0, MAX_EDGE_PIXELS) ?: 0,
            normalizedFactor(value?.optDouble("centerFactor", 1.0)?.toFloat() ?: 1f),
        )
    }
}

fun CameraPreferences.rawEdgeCrop(): RawEdgeCrop =
    lensProfiles.firstOrNull { it.id == activeLensProfileId && it.enabled }?.rawEdgeCrop ?: RawEdgeCrop()
