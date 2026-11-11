package com.unspektrawesome.panorama

import kotlin.math.*

internal val GRID_CAPTURE_ORDER = listOf(4, 5, 2, 1, 0, 3, 6, 7, 8)
internal val GRID_IDENTITY = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
internal const val GRID_TARGET_RADIUS = .044

internal data class GridCaptureProjection(val width: Int, val height: Int, val focal: Double)

internal fun gridCaptureProjection(sensorWidth: Int, sensorHeight: Int, outputWidth: Int, outputHeight: Int,
    equivalentMm: Double): GridCaptureProjection {
    val scale = 1024.0 / max(outputWidth, outputHeight)
    val width = (outputWidth * scale).roundToInt().coerceAtLeast(2)
    val height = (outputHeight * scale).roundToInt().coerceAtLeast(2)
    return GridCaptureProjection(width, height,
        panoramaFocalPixels(sensorWidth, sensorHeight, outputWidth, width, equivalentMm).coerceAtLeast(width * .35))
}

internal class GridCaptureAnchor {
    var armed = false
        private set
    private var latest: List<Double>? = null
    private var origin: List<Double>? = null
    fun update(pose: List<Double>): List<Double> {
        latest = pose
        if (!armed || origin == null) origin = pose
        return gridMultiply(gridTranspose(requireNotNull(origin)), pose)
    }
    fun start(): Boolean {
        origin = latest ?: return false
        armed = true
        return true
    }
    fun stop() { armed = false; origin = latest }
    fun reset() { armed = false; origin = null; latest = null }
}

internal fun gridMultiply(a: List<Double>, b: List<Double>): List<Double> = List(9) { index ->
    val row = index / 3; val col = index % 3
    (0..2).sumOf { a[row * 3 + it] * b[it * 3 + col] }
}

internal fun gridTranspose(a: List<Double>) = List(9) { a[it % 3 * 3 + it / 3] }

internal fun gridVector(matrix: List<Double>, vector: List<Double>) = List(3) { row ->
    (0..2).sumOf { matrix[row * 3 + it] * vector[it] }
}

internal fun gridTarget(index: Int, width: Int, height: Int, focal: Double): List<Double> {
    require(index in 0..8 && focal > 0)
    val yaw = (index % 3 - 1) * atan(width * .5 / focal) * .86
    val pitch = (index / 3 - 1) * atan(height * .5 / focal) * .86
    val diagonal = atan(hypot(width * .5, height * .5) / focal)
    val limit = (Math.toRadians(115.0) - diagonal).coerceIn(Math.toRadians(3.0), Math.toRadians(85.0))
    val extent = hypot(tan(atan(width * .5 / focal) * .86), tan(atan(height * .5 / focal) * .86))
    val scale = min(1.0, tan(limit) / extent)
    val x = tan(yaw) * scale; val y = tan(pitch) * scale
    val length = sqrt(x * x + y * y + 1)
    return listOf(x / length, y / length, 1 / length)
}

internal fun gridFacing(ray: List<Double>): List<Double> {
    val length = hypot(ray[0], ray[2])
    val right = listOf(ray[2] / length, 0.0, -ray[0] / length)
    val down = listOf(ray[1] * right[2], ray[2] * right[0] - ray[0] * right[2], -ray[1] * right[0])
    return List(9) { index -> listOf(right, down, ray)[index % 3][index / 3] }
}

internal fun gridHomography(rotation: List<Double>, width: Int, height: Int, focal: Double): PanoramaTransform {
    val cx = width * .5; val cy = height * .5
    val k = listOf(focal, 0.0, cx, 0.0, focal, cy, 0.0, 0.0, 1.0)
    val inverse = listOf(1 / focal, 0.0, -cx / focal, 0.0, 1 / focal, -cy / focal, 0.0, 0.0, 1.0)
    val matrix = gridMultiply(gridMultiply(k, rotation), inverse)
    return PanoramaTransform.projective(if (abs(matrix[8]) > .1) matrix.map { it / matrix[8] } else matrix)
}

internal fun gridRotationDistance(a: List<Double>, b: List<Double>): Double {
    val relative = gridMultiply(gridTranspose(a), b)
    // Skew terms avoid a false motion floor from rounded sensor matrices.
    val sine = sqrt((relative[7] - relative[5]).pow(2) + (relative[2] - relative[6]).pow(2) + (relative[3] - relative[1]).pow(2))
    return atan2(sine, relative[0] + relative[4] + relative[8] - 1)
}

data class GridCaptureState(
    val available: Boolean = true,
    val rotation: List<Double> = GRID_IDENTITY,
    val width: Int = 384,
    val height: Int = 512,
    val focal: Double = 420.0,
    val orientationDegrees: Int = 0,
    val next: Int = 4,
    val captured: Set<Int> = emptySet(),
    val hold: Float = 0f,
    val motion: kotlinx.coroutines.flow.StateFlow<GridCaptureState>? = null,
)

internal class GridCaptureDwell {
    private var accumulated = 0L
    private var target = -1
    private var lastTimestamp = 0L
    private var outsideSince = 0L
    var progress = 0f
        private set

    fun reset() {
        accumulated = 0; lastTimestamp = 0; outsideSince = 0; target = -1; progress = 0f
    }

    fun update(timestamp: Long, rotation: List<Double>, index: Int, width: Int, height: Int, focal: Double,
        radius: Double = GRID_TARGET_RADIUS): Boolean {
        val ray = gridVector(gridTranspose(rotation), gridTarget(index, width, height, focal))
        val distance = if (ray[2] > 0) focal * hypot(ray[0], ray[1]) / (ray[2] * min(width, height)) else Double.POSITIVE_INFINITY
        val elapsed = if (lastTimestamp == 0L) 0L else timestamp - lastTimestamp
        if (target != index || elapsed < 0 || elapsed > 500_000_000L) reset()
        val interval = if (lastTimestamp == 0L) 0L else elapsed.coerceAtMost(80_000_000L)
        lastTimestamp = timestamp
        target = index
        if (distance <= radius) {
            outsideSince = 0
            accumulated += interval
        } else {
            if (outsideSince == 0L) outsideSince = timestamp
            if (distance > radius * 1.7 || timestamp - outsideSince > 300_000_000L) accumulated = 0
        }
        progress = (accumulated / 1_500_000_000.0).toFloat().coerceIn(0f, 1f)
        return progress >= 1f && distance <= radius
    }
}
