package com.unspektrawesome.panorama

import org.json.JSONArray
import kotlin.math.*

internal fun panoramaFocalPixels(sensorWidth: Int, sensorHeight: Int, outputWidth: Int, trackingWidth: Int, equivalentMm: Double): Double =
    equivalentMm / 43.2666 * hypot(sensorWidth.toDouble(), sensorHeight.toDouble()) * trackingWidth / outputWidth

internal fun panoramaMotionTooFast(rotation: Double?, previewFocal: Double): Boolean =
    rotation != null && rotation.isFinite() && rotation * previewFocal > 3

internal fun panoramaCaptureComplete(grid: Boolean, frames: Int, transform: PanoramaTransform, focal: Double, elapsedMs: Long): Boolean =
    if (grid) frames >= 9 else frames >= 120 || abs(transform.x) / focal > 5.6 || elapsedMs > 300_000

enum class PanoramaProjection { CYLINDRICAL, PLANE }

internal fun panoramaPreviewRotation(preview: com.unspektrawesome.capture.CaptureTransform, capture: com.unspektrawesome.capture.CaptureTransform): Int {
    require(preview.mirrorHorizontal == capture.mirrorHorizontal)
    val direction = if (capture.mirrorHorizontal) -1 else 1
    return ((capture.orientationDegrees - preview.orientationDegrees) * direction + 360) % 360
}

internal fun panoramaExposureMatches(expectedIso: Int, expectedTimeNs: Long, actualIso: Int?, actualTimeNs: Long?, settled: Boolean = false): Boolean {
    if (actualIso == null || actualTimeNs == null || expectedIso <= 0 || expectedTimeNs <= 0) return false
    val divisor = if (settled) 500 else 100
    return abs(actualIso.toLong() - expectedIso) <= maxOf(if (settled) 1 else 5, expectedIso / divisor) &&
        abs(actualTimeNs - expectedTimeNs) <= maxOf(1000L, expectedTimeNs / divisor)
}

internal fun projectiveDeterminant(m: List<Double>) = m[0] * (m[4] * m[8] - m[5] * m[7]) -
    m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

internal data class PanoramaTransform(val a: Double = 1.0, val b: Double = 0.0, val x: Double = 0.0, val y: Double = 0.0,
    val matrix: List<Double>? = null, val plane: Boolean = false) {
    fun then(local: PanoramaTransform): PanoramaTransform {
        if (plane && local.plane) return projective(gridMultiply(requireNotNull(matrix), requireNotNull(local.matrix)), plane = true)
        return PanoramaTransform(a * local.a - b * local.b, b * local.a + a * local.b,
            a * local.x - b * local.y + x, b * local.x + a * local.y + y)
    }
    fun point(px: Double, py: Double): Pair<Double, Double> {
        val m = matrix ?: return (a * px - b * py + x) to (b * px + a * py + y)
        val z = m[6] * px + m[7] * py + m[8]
        require(abs(z) > .000001)
        return (m[0] * px + m[1] * py + m[2]) / z to (m[3] * px + m[4] * py + m[5]) / z
    }
    fun projectedPoint(px: Double, py: Double, width: Int, height: Int, focal: Double): Pair<Double, Double> {
        if (plane) return point(px, py)
        val m = matrix ?: return point(px, py)
        val direction = sign(projectiveDeterminant(m))
        val z = (m[6] * px + m[7] * py + m[8]) * direction
        val x = (m[0] * px + m[1] * py + m[2]) * direction - width * .5 * z
        val y = (m[3] * px + m[4] * py + m[5]) * direction - height * .5 * z
        val scale = 2 * focal / (sqrt(x * x + y * y + focal * focal * z * z) + focal * z)
        return width * .5 + x * scale to height * .5 + y * scale
    }
    fun scaled(scale: Double) = matrix?.let { m -> projective(m.mapIndexed { i, v ->
        when (i) { 2, 5 -> v * scale; 6, 7 -> v / scale; else -> v }
    }, plane) } ?: copy(x = x * scale, y = y * scale)
    fun values() = matrix?.let { (if (plane) it + 1.0 else it).toDoubleArray() } ?: doubleArrayOf(a, b, x, y)
    fun json() = JSONArray(values().toList())
    companion object {
        fun projective(matrix: List<Double>, plane: Boolean = false): PanoramaTransform {
            require(matrix.size == 9 && matrix.all(Double::isFinite))
            val m = matrix
            val determinant = projectiveDeterminant(m)
            require(abs(determinant) > 1e-8 && m.all { abs(it) <= 1_000_000 })
            return PanoramaTransform(x = m[2], y = m[5], matrix = m, plane = plane)
        }
        fun read(json: JSONArray): PanoramaTransform {
            if (json.length() == 10) {
                require(json.getDouble(9) == 1.0)
                return projective(List(9) { json.getDouble(it) }, plane = true)
            }
            if (json.length() == 9) return projective(List(9) { json.getDouble(it) })
            require(json.length() == 4)
            return PanoramaTransform(json.getDouble(0), json.getDouble(1), json.getDouble(2), json.getDouble(3)).also {
                require(it.values().all(Double::isFinite) && hypot(it.a, it.b) in .8..1.2 && abs(it.x) <= 1_000_000 && abs(it.y) <= 1_000_000)
            }
        }
    }
}

internal data class PanoramaBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun union(other: PanoramaBounds) = PanoramaBounds(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    companion object {
        fun frame(width: Int, height: Int, transform: PanoramaTransform, focal: Double? = null): PanoramaBounds {
            val points = if (transform.matrix != null && !transform.plane && focal != null) (0..64).flatMap { step ->
                val x = width * step / 64.0; val y = height * step / 64.0
                listOf(x to 0.0, x to height.toDouble(), 0.0 to y, width.toDouble() to y)
            }.map { transform.projectedPoint(it.first, it.second, width, height, focal) }
            else listOf(0.0 to 0.0, width.toDouble() to 0.0, 0.0 to height.toDouble(), width.toDouble() to height.toDouble())
                .map { transform.point(it.first, it.second) }
            return PanoramaBounds(floor(points.minOf { it.first }).toInt(), floor(points.minOf { it.second }).toInt(),
                ceil(points.maxOf { it.first }).toInt(), ceil(points.maxOf { it.second }).toInt())
        }
    }
}

enum class PanoramaGuide(val text: String) {
    READY("Start panorama"), START("Move slowly left or right"),
    LEFT("Keep moving left"), RIGHT("Keep moving right"), UP("Keep moving up"), DOWN("Keep moving down"),
    LEVEL("Keep the camera on the guide line"), SLOW("Move more slowly"),
    DETAIL("Aim at more detail and overlap the last frame"), RETURN("Return to the last captured area"),
    LIMIT("Tap the shutter to finish"),
    SAVING("Saving panorama RAWs"), SAVED("Panorama saved for Gallery"), SHORT("Move farther to capture a panorama"),
    FAILED("Panorama could not finish. Saved frames are kept.")
}

data class PanoramaState(
    val enabled: Boolean = false,
    val recording: Boolean = false,
    val finishing: Boolean = false,
    val exportLater: Boolean = true,
    val projection: PanoramaProjection = PanoramaProjection.CYLINDRICAL,
    val frames: Int = 0,
    val guide: PanoramaGuide = PanoramaGuide.READY,
    val horizontal: Boolean = true,
    val direction: Int = 0,
    val progress: Float = 0f,
    val drift: Float = 0f,
    val preview: android.graphics.Bitmap? = null,
    val grid: GridCaptureState? = null,
    val panels: List<GridCapturedPanel> = emptyList(),
) {
    val busy get() = recording || finishing
}

data class GridCapturedPanel(val bitmap: android.graphics.Bitmap, val rotation: List<Double>)

internal data class PanoramaPreviewFrame(val bitmap: android.graphics.Bitmap, val timestamp: Long)
