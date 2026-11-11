package com.unspektrawesome.vulkan.params

import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Uniform block for the ISP viewfinder shader: source size and flags, exposure gain, the plane
 * layout of copied YUV_420_888 bytes and the inverse of the tone curve the camera applied.
 */
class IspShaderParameters {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder())
    private var planeBytes: ByteBuffer? = null
    private var curveSource: TonemapCurve? = null
    private var curveReady = false

    init {
        buffer.putFloat(EXPOSURE_OFFSET, 1f)
    }

    /** Copies Y, U and V planes into one direct buffer and records their layout. */
    fun packPlanes(planes: Array<Image.Plane>, width: Int, height: Int): ByteBuffer {
        require(planes.size == 3) { INVALID_FRAME }
        var total = 0
        val sizes = IntArray(3)
        for (index in 0 until 3) {
            sizes[index] = planes[index].buffer.remaining()
            total = align4(total + sizes[index])
        }
        val target = planeBytes?.takeIf { it.capacity() >= total }
            ?: ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder()).also { planeBytes = it }
        target.clear()
        var offset = 0
        for (index in 0 until 3) {
            val plane = planes[index]
            val source = plane.buffer.duplicate()
            target.position(offset)
            target.put(source)
            val planeWidth = if (index == 0) width else (width + 1) / 2
            val planeHeight = if (index == 0) height else (height + 1) / 2
            val required = (planeHeight - 1) * plane.rowStride + (planeWidth - 1) * plane.pixelStride + 1
            require(sizes[index] >= required) { INVALID_FRAME }
            buffer.putInt(PLANES_OFFSET + index * 16, offset)
            buffer.putInt(PLANES_OFFSET + index * 16 + 4, plane.rowStride)
            buffer.putInt(PLANES_OFFSET + index * 16 + 8, plane.pixelStride)
            buffer.putInt(PLANES_OFFSET + index * 16 + 12, 0)
            offset = align4(offset + sizes[index])
        }
        target.position(0)
        target.limit(total)
        return target
    }

    /** Camera YUV_420_888 is full-range JFIF; opaque buffers report their range to Vulkan. */
    fun setSource(width: Int, height: Int, fullRange: Boolean, rangeFromBuffer: Boolean = false) {
        require(width > 0 && height > 0) { INVALID_FRAME }
        buffer.putInt(0, width)
        buffer.putInt(4, height)
        buffer.putInt(8, (if (fullRange) FLAG_FULL_RANGE else 0) or (if (rangeFromBuffer) FLAG_RANGE_FROM_BUFFER else 0))
        buffer.putInt(12, 0)
    }

    fun setExposureGain(gain: Float) {
        require(gain.isFinite() && gain > 0f) { INVALID_FRAME }
        buffer.putFloat(EXPOSURE_OFFSET, gain)
    }

    /** Rebuilds the inverse tone curve when the camera reports a different curve. */
    fun setTonemapCurve(curve: TonemapCurve?) {
        if (curveReady && curve == curveSource) return
        for (channel in 0 until 3) {
            val points = curve?.takeIf { it.getPointCount(channel) >= 2 }?.let {
                FloatArray(it.getPointCount(channel) * 2).also { values -> it.copyColorCurve(channel, values, 0) }
            }
            val table = inverseTable(points)
            for (index in 0 until CURVE_POINTS) {
                buffer.putFloat(CURVE_OFFSET + (index * 4 + channel) * 4, table[index])
            }
        }
        curveSource = curve
        curveReady = true
    }

    companion object {
        const val SIZE_BYTES = 80 + 256 * 16
        private const val INVALID_FRAME = "ISP viewfinder frame is invalid"
        const val FLAG_FULL_RANGE = 1
        const val FLAG_RANGE_FROM_BUFFER = 4
        private const val CURVE_POINTS = 256
        private const val EXPOSURE_OFFSET = 16
        private const val PLANES_OFFSET = 32
        private const val CURVE_OFFSET = 80

        private fun align4(value: Int) = (value + 3) and 3.inv()

        fun srgbToLinear(encoded: Float): Float {
            val value = encoded.coerceIn(0f, 1f)
            return if (value <= 0.04045f) value / 12.92f
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }

        /**
         * Finds, for each 8-bit output code, the scene-linear input the curve mapped onto it.
         * Points are (input, output) pairs of a monotonic curve. Clipped highlights map to the first
         * input that reaches the top output, so a clipping curve does not brighten the viewfinder.
         * Without a curve the camera's standard sRGB encoding is assumed.
         */
        fun inverseTable(points: FloatArray?): FloatArray {
            val table = FloatArray(CURVE_POINTS)
            val count = (points?.size ?: 0) / 2
            for (index in 0 until CURVE_POINTS) {
                val output = index / 255f
                if (points == null || count < 2) {
                    table[index] = srgbToLinear(output)
                    continue
                }
                var result = when {
                    output <= points[1] -> points[0]
                    output >= points[(count - 1) * 2 + 1] -> {
                        val top = points[(count - 1) * 2 + 1]
                        var first = count - 1
                        while (first > 0 && points[(first - 1) * 2 + 1] >= top) first--
                        points[first * 2]
                    }
                    else -> {
                        var low = 0
                        var high = count - 1
                        while (high - low > 1) {
                            val middle = (low + high) ushr 1
                            if (points[middle * 2 + 1] <= output) low = middle else high = middle
                        }
                        val inLow = points[low * 2]
                        val outLow = points[low * 2 + 1]
                        val span = points[high * 2 + 1] - outLow
                        if (span <= 1e-6f) inLow else inLow + (points[high * 2] - inLow) * ((output - outLow) / span)
                    }
                }
                if (!result.isFinite()) result = srgbToLinear(output)
                table[index] = result.coerceIn(0f, 1f)
            }
            return table
        }
    }
}
