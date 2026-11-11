package com.unspektrawesome.preview

import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.capture.NormalizedPoint
import kotlin.math.roundToInt

/**
 * Focus loupe placement requested by the camera screen. Positions are normalized to the whole
 * viewfinder surface, like tap metering. The native renderer magnifies the RAW frame around the
 * target at [zoom] screen pixels per RAW pixel inside the given rectangle.
 */
data class FocusLoupeRequest(
    val targetX: Float,
    val targetY: Float,
    val rectLeft: Float,
    val rectTop: Float,
    val rectWidth: Float,
    val rectHeight: Float,
    val cornerRadius: Float,
    val widthPx: Float,
    val heightPx: Float,
    val zoom: Float,
    val peaking: Float,
    val opacity: Float = 1f,
) {
    val valid: Boolean
        get() = listOf(targetX, targetY, rectLeft, rectTop, rectWidth, rectHeight, cornerRadius, widthPx, heightPx, zoom,
            peaking, opacity).all { it.isFinite() } && rectWidth > 0f && rectHeight > 0f && widthPx >= 16f &&
            heightPx >= 16f && zoom > 0f

    /** Values for the native renderer, or null when the request cannot be placed on this frame. */
    internal fun nativeValues(geometry: FrameGeometrySnapshot, noise: FloatArray, generation: Long): FloatArray? {
        if (!valid) return null
        val point = FocusLoupeMapping.rawPixel(geometry, targetX, targetY) ?: return null
        val sideways = geometry.transform.orientationDegrees % 180 != 0
        val width = (widthPx / zoom).roundToInt().coerceAtLeast(16)
        val height = (heightPx / zoom).roundToInt().coerceAtLeast(16)
        return floatArrayOf(
            1f, point.x, point.y,
            (if (sideways) height else width).toFloat(), (if (sideways) width else height).toFloat(),
            rectLeft, rectTop, rectWidth, rectHeight, cornerRadius, peaking, opacity,
            noise.getOrElse(0) { 0f }, noise.getOrElse(1) { 0f }, generation.toFloat(), 0f,
        )
    }

    /** True when [other] magnifies the same area at the same zoom, wherever the loupe window is. */
    fun measuresSameArea(other: FocusLoupeRequest): Boolean =
        targetX == other.targetX && targetY == other.targetY && zoom == other.zoom

    companion object {
        val DISABLED = FloatArray(16)
    }
}

/** Layout of the renderer's focus loupe result, described in FocusLoupe.h. */
internal object FocusLoupeResult {
    const val SIZE = 16
    const val SEQUENCE = 11
    const val LIGHTWEIGHT = 12
    private const val FINE = 2
    private const val FLAT_FINE = 9

    fun reading(values: FloatArray, focusPosition: Float) = FocusLoupeReading(values[FINE], values[FLAT_FINE], focusPosition)

    /**
     * Fine edge energy that sensor noise alone typically produces after each path's demosaic, measured
     * on defocused scenes. Demosaicing correlates the noise, and MHC sharpening raises it further.
     * Keep in sync with focus_loupe::fineNoise, which gates the loupe's peaking and sharpening.
     */
    fun fineNoise(lightweight: Boolean): Float = if (lightweight) 1.65f else 3.4f
}

/** What the loupe last showed, in display orientation. */
data class FocusLoupeStatus(
    val requested: Boolean = false,
    val available: Boolean = false,
    val meter: FocusMeterState = FocusMeterState(),
    /** RAW pixels shown by the loupe. */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Centre of the shown area, normalized to the viewfinder surface. */
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    /** RAW pixels across the whole viewfinder surface. */
    val surfaceRawWidth: Float = 0f,
    val surfaceRawHeight: Float = 0f,
)

internal object FocusLoupeMapping {
    /** RAW buffer pixel shown at a point normalized to the viewfinder surface. */
    fun rawPixel(geometry: FrameGeometrySnapshot, x: Float, y: Float): NormalizedPoint? {
        val full = geometry.copy(sourceCrop = geometry.activeArray)
        val uv = runCatching { full.sourceUv(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) }.getOrNull() ?: return null
        return NormalizedPoint(uv.x * geometry.sourceSize.width, uv.y * geometry.sourceSize.height)
    }

    /** Viewfinder surface point, normalized, that shows a RAW buffer pixel. */
    fun surfacePoint(geometry: FrameGeometrySnapshot, rawX: Float, rawY: Float): NormalizedPoint {
        val active = geometry.activeArray
        val sourceU = (rawX / geometry.sourceSize.width - active.left) / active.width
        val sourceV = (rawY / geometry.sourceSize.height - active.top) / active.height
        var (u, v) = when (geometry.transform.orientationDegrees) {
            90 -> 1f - sourceV to sourceU
            180 -> 1f - sourceU to 1f - sourceV
            270 -> sourceV to 1f - sourceU
            else -> sourceU to sourceV
        }
        if (geometry.transform.mirrorHorizontal) u = 1f - u
        return NormalizedPoint(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    /** RAW pixels across the viewfinder surface in display orientation. */
    fun surfaceRawSize(geometry: FrameGeometrySnapshot): Pair<Float, Float> {
        val width = geometry.activeArray.width * geometry.sourceSize.width
        val height = geometry.activeArray.height * geometry.sourceSize.height
        return if (geometry.transform.orientationDegrees % 180 != 0) height to width else width to height
    }
}
