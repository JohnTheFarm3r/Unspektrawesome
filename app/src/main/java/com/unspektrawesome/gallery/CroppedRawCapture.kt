package com.unspektrawesome.gallery

import com.unspektrawesome.camera.Rect2d
import com.unspektrawesome.preview.PersistedCapture
import com.unspektrawesome.vulkan.params.GeometryShaderParameters
import com.unspektrawesome.vulkan.params.RawColorShaderParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun PersistedCapture.rawRegion(): Rect2d {
    val geometry = GeometryShaderParameters.upgrade(geometry, raw.getInt(0), raw.getInt(4))
    val offset = GeometryShaderParameters.RAW_BOUNDS_OFFSET
    val left = geometry.getInt(offset)
    val top = geometry.getInt(offset + 4)
    return Rect2d(left, top, left + geometry.getInt(offset + 8), top + geometry.getInt(offset + 12)).also {
        require(it.left >= 0 && it.top >= 0 && it.width() >= 2 && it.height() >= 2 &&
            it.right <= raw.getInt(0) && it.bottom <= raw.getInt(4)) { "Invalid saved RAW crop" }
    }
}

internal fun PersistedCapture.rawStorageRegion(): Rect2d =
    if (com.unspektrawesome.vulkan.params.RawMosaic(raw).blockSize > 1) Rect2d(0, 0, raw.getInt(0), raw.getInt(4)) else rawRegion()

internal fun PersistedCapture.croppedRaw16(): PersistedCapture {
    val region = rawStorageRegion()
    val retained = rawRegion()
    val sourceWidth = raw.getInt(0)
    val sourceHeight = raw.getInt(4)
    val raw = raw.copyDirect().apply {
        putInt(0, region.width()); putInt(4, region.height())
        putInt(16, region.width() * 2); putInt(20, 0)
        putInt(24, getInt(24) xor (region.left and 1) xor ((region.top and 1) shl 1))
        if (region.top and 1 != 0) {
            swapFloats(36, 40)
            swapFloats(52, 56)
        }
    }
    val geometry = GeometryShaderParameters.upgrade(geometry, sourceWidth, sourceHeight).copyDirect().apply {
        for (offset in listOf(GeometryShaderParameters.SOURCE_CROP_OFFSET, GeometryShaderParameters.ACTIVE_ARRAY_OFFSET,
            GeometryShaderParameters.CALIBRATION_ARRAY_OFFSET)) {
            putFloat(offset, ((pixels(getFloat(offset), sourceWidth) - region.left) / region.width()).toFloat())
            putFloat(offset + 4, ((pixels(getFloat(offset + 4), sourceHeight) - region.top) / region.height()).toFloat())
            putFloat(offset + 8, (pixels(getFloat(offset + 8), sourceWidth) / region.width()).toFloat())
            putFloat(offset + 12, (pixels(getFloat(offset + 12), sourceHeight) / region.height()).toFloat())
        }
        val cropLeft = getFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET).coerceIn(0f, 1f)
        val cropTop = getFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET + 4).coerceIn(0f, 1f)
        putFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET, cropLeft)
        putFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET + 4, cropTop)
        putFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET + 8,
            getFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET + 8).coerceAtMost(1f - cropLeft))
        putFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET + 12,
            getFloat(GeometryShaderParameters.SOURCE_CROP_OFFSET + 12).coerceAtMost(1f - cropTop))
        putInt(GeometryShaderParameters.RAW_BOUNDS_OFFSET, retained.left - region.left)
        putInt(GeometryShaderParameters.RAW_BOUNDS_OFFSET + 4, retained.top - region.top)
        putInt(GeometryShaderParameters.RAW_BOUNDS_OFFSET + 8, retained.width())
        putInt(GeometryShaderParameters.RAW_BOUNDS_OFFSET + 12, retained.height())
    }
    // Green calibration channels are indexed by the sensor row parity.
    val shading = if (region.top and 1 != 0 && shading != null) shading.copyDirect().apply {
        val columns = color.getFloat(RawColorShaderParameters.LENS_SHADING_WIDTH_OFFSET).toInt()
        val rows = color.getFloat(RawColorShaderParameters.LENS_SHADING_HEIGHT_OFFSET).toInt()
        require(columns.toLong() * rows * 16 <= remaining()) { "Incomplete lens shading map" }
        repeat(columns * rows) { swapFloats(it * 16 + 4, it * 16 + 8) }
    } else shading
    return copy(raw = raw, geometry = geometry, shading = shading)
}

// Saved edges are whole pixels; snap away the float error of the normalized value.
private fun pixels(normalized: Float, size: Int): Double {
    val value = normalized.toDouble() * size
    val whole = kotlin.math.round(value)
    return if (kotlin.math.abs(value - whole) < 0.01) whole else value
}

private fun ByteBuffer.copyDirect(): ByteBuffer = ByteBuffer.allocateDirect(remaining()).order(ByteOrder.nativeOrder()).apply {
    put(this@copyDirect.duplicate()); flip()
}

private fun ByteBuffer.swapFloats(first: Int, second: Int) {
    val value = getFloat(first)
    putFloat(first, getFloat(second)); putFloat(second, value)
}
