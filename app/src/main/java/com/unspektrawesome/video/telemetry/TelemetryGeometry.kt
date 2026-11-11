package com.unspektrawesome.video.telemetry

import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.camera.Size2d
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Pixel centers use integer indices here; Android intrinsics use pixel edges. */
internal class TelemetryGeometry(
    val width: Int, val height: Int, val sensorWidth: Int, val sensorHeight: Int,
    val active: IntArray, val preCorrection: IntArray, val sensorFromOutput: DoubleArray,
    val rawSensorCrop: DoubleArray = doubleArrayOf(0.0, 0.0, sensorWidth.toDouble(), sensorHeight.toDouble()),
) {
    init {
        require(width > 1 && height > 1 && sensorWidth > 0 && sensorHeight > 0)
        require(active.size == 4 && preCorrection.size == 4 && active[3] > active[1])
        require(sensorFromOutput.size == 6 && sensorFromOutput.all(Double::isFinite))
        require(rawSensorCrop.size == 4 && rawSensorCrop.all(Double::isFinite) &&
            rawSensorCrop[2] > 0 && rawSensorCrop[3] > 0)
    }

    private val determinant get() = sensorFromOutput[0] * sensorFromOutput[4] - sensorFromOutput[1] * sensorFromOutput[3]
    val reflected get() = determinant < 0
    val direction: Int get() {
        val a = sensorFromOutput
        return if (abs(a[3]) > abs(a[4])) { if (a[3] > 0) 3 else 2 } else if (a[4] > 0) 0 else 1
    }

    fun rowExtents(): DoubleArray {
        val a = sensorFromOutput
        val corners = doubleArrayOf(a[5], a[5] + a[3] * (width - 1),
            a[5] + a[4] * (height - 1), a[5] + a[3] * (width - 1) + a[4] * (height - 1))
        return doubleArrayOf(corners.min(), corners.max())
    }

    fun sensorCrop(): DoubleArray {
        val a = sensorFromOutput
        val points = listOf(-.5 to -.5, (width - .5) to -.5, -.5 to (height - .5), (width - .5) to (height - .5))
        val xs = points.map { (x, y) -> a[0] * x + a[1] * y + a[2] + .5 }
        val ys = points.map { (x, y) -> a[3] * x + a[4] * y + a[5] + .5 }
        return doubleArrayOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }

    fun readoutUs(timestampNs: Long, originNs: Long, exposureNs: Long, skewNs: Long): DoubleArray {
        require(exposureNs > 0 && skewNs >= 0)
        val rows = rowExtents()
        val base = (timestampNs - originNs) / 1000.0 + exposureNs / 1000.0
        val rowTime = skewNs / 1000.0 / (active[3] - active[1])
        return DoubleArray(2) { base + rowTime * (rows[it] - active[1]) }
    }

    fun outputIntrinsics(k: FloatArray): DoubleArray {
        require(k.size == 5 && k.all(Float::isFinite) && k[0] > 0 && k[1] > 0)
        val a = sensorFromOutput
        require(abs(determinant) > 1e-12)
        val xx = a[4] / determinant; val xy = -a[1] / determinant
        val yx = -a[3] / determinant; val yy = a[0] / determinant
        val cx = preCorrection[0] + k[2] - 0.5 - a[2]
        val cy = preCorrection[1] + k[3] - 0.5 - a[5]
        return doubleArrayOf(xx * k[0], xx * k[4] + xy * k[1], xx * cx + xy * cy,
            yx * k[0], yx * k[4] + yy * k[1], yx * cx + yy * cy, 0.0, 0.0, 1.0)
    }

    fun rawNormalizedIntrinsics(k: FloatArray): DoubleArray {
        require(k.size == 5 && k.all(Float::isFinite) && k[0] > 0 && k[1] > 0)
        val (left, top, width, height) = rawSensorCrop
        return doubleArrayOf(k[0] / width, k[4] / width, (preCorrection[0] + k[2] - left) / width,
            0.0, k[1] / height, (preCorrection[1] + k[3] - top) / height, 0.0, 0.0, 1.0)
    }

    val rawReadoutStart get() = (active[1] - rawSensorCrop[1]) / rawSensorCrop[3]
    val rawReadoutSpan get() = (active[3] - active[1]) / rawSensorCrop[3]

    fun json() = JSONObject().put("outputWidth", width).put("outputHeight", height)
        .put("pixelArray", JSONArray(listOf(sensorWidth, sensorHeight)))
        .put("activeArray", JSONArray(active.toList())).put("preCorrectionActiveArray", JSONArray(preCorrection.toList()))
        .put("rawSensorCrop", JSONArray(rawSensorCrop.toList()))
        .put("sensorFromOutput", JSONArray(sensorFromOutput.toList())).put("readoutDirection", direction)
        .put("pixelCenters", "integer-index").put("reflected", reflected)

    companion object {
        fun create(frame: FrameGeometrySnapshot, width: Int, height: Int, sensorWidth: Int, sensorHeight: Int,
            active: IntArray, preCorrection: IntArray,
            rawActiveOnly: Boolean = frame.sourceSize.width == preCorrection[2] - preCorrection[0] &&
                frame.sourceSize.height == preCorrection[3] - preCorrection[1],
            acquisitionSize: Size2d = frame.sourceSize): TelemetryGeometry {
            val crop = frame.cropPixels
            val raw = frame.sourceSize
            require(raw.width <= acquisitionSize.width && raw.height <= acquisitionSize.height)
            val left = if (rawActiveOnly) preCorrection[0].toDouble() else 0.0
            val top = if (rawActiveOnly) preCorrection[1].toDouble() else 0.0
            val sourceWidth = if (rawActiveOnly) (preCorrection[2] - preCorrection[0]).toDouble() else sensorWidth.toDouble()
            val sourceHeight = if (rawActiveOnly) (preCorrection[3] - preCorrection[1]).toDouble() else sensorHeight.toDouble()
            // RAW aspect ratios select a centered sensor window. Binning changes
            // pixel density, while calibration remains in sensor coordinates.
            val scale = minOf(sourceWidth / acquisitionSize.width, sourceHeight / acquisitionSize.height)
            val w = minOf(sourceWidth, acquisitionSize.width * scale)
            val h = minOf(sourceHeight, acquisitionSize.height * scale)
            // A smaller raster with the original row stride keeps the acquisition's
            // origin and pixel scale. It discards the right and bottom pixels.
            val rawCrop = doubleArrayOf(left + (sourceWidth - w) / 2, top + (sourceHeight - h) / 2,
                raw.width * scale, raw.height * scale)
            fun point(x: Double, y: Double): DoubleArray {
                var u = (x + 0.5) / width; var v = (y + 0.5) / height
                if (frame.cropAspect < 1f) { val previous = u; u = 1 - v; v = previous }
                if (frame.transform.mirrorHorizontal) u = 1 - u
                val (rx, ry) = when (frame.transform.orientationDegrees) {
                    90 -> v to 1 - u
                    180 -> 1 - u to 1 - v
                    270 -> 1 - v to u
                    else -> u to v
                }
                return doubleArrayOf(rawCrop[0] + (crop.left + rx * crop.width) * rawCrop[2] / raw.width - 0.5,
                    rawCrop[1] + (crop.top + ry * crop.height) * rawCrop[3] / raw.height - 0.5)
            }
            val p = point(0.0, 0.0); val x = point(1.0, 0.0); val y = point(0.0, 1.0)
            return TelemetryGeometry(width, height, sensorWidth, sensorHeight, active, preCorrection,
                doubleArrayOf(x[0]-p[0], y[0]-p[0], p[0], x[1]-p[1], y[1]-p[1], p[1]), rawCrop)
        }
    }
}
