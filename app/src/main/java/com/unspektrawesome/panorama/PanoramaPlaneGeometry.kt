package com.unspektrawesome.panorama

import kotlin.math.*

internal data class PanoramaPlaneLayout(val transforms: List<PanoramaTransform>, val fits: Boolean)

internal fun panoramaPlaneLayout(transforms: List<PanoramaTransform>, width: Int, height: Int, focal: Double): PanoramaPlaneLayout {
    require(transforms.isNotEmpty() && focal.isFinite() && focal > 1)
    val corners = listOf(0.0 to 0.0, width.toDouble() to 0.0, 0.0 to height.toDouble(), width.toDouble() to height.toDouble())
    fun rays(transform: PanoramaTransform): List<List<Double>> {
        val m = requireNotNull(transform.matrix)
        val direction = sign(projectiveDeterminant(m))
        return corners.map { (x, y) ->
            val z = m[6] * x + m[7] * y + m[8]
            listOf(((m[0] * x + m[1] * y + m[2]) - width * .5 * z) / focal * direction,
                ((m[3] * x + m[4] * y + m[5]) - height * .5 * z) / focal * direction, z * direction)
        }
    }
    val directions = transforms.flatMap(::rays).map { ray ->
        val length = sqrt(ray.sumOf { it * it })
        ray.map { it / length }
    }
    var forward = List(3) { axis -> directions.sumOf { it[axis] } }
    var facing = GRID_IDENTITY
    repeat(12) {
        val length = sqrt(forward.sumOf { it * it })
        if (!length.isFinite() || length < 1e-8) return PanoramaPlaneLayout(emptyList(), false)
        facing = gridFacing(forward.map { it / length })
        val local = directions.map { gridVector(gridTranspose(facing), it) }
        val yaw = local.map { atan2(it[0], it[2]) }
        val pitch = local.map { atan2(it[1], it[2]) }
        val horizontal = (yaw.min() + yaw.max()) * .5
        val vertical = (pitch.min() + pitch.max()) * .5
        forward = gridVector(facing, listOf(tan(horizontal), tan(vertical), 1.0))
    }
    val k = listOf(focal, 0.0, width * .5, 0.0, focal, height * .5, 0.0, 0.0, 1.0)
    val inverse = listOf(1 / focal, 0.0, -width * .5 / focal, 0.0, 1 / focal, -height * .5 / focal, 0.0, 0.0, 1.0)
    val view = gridMultiply(gridMultiply(k, gridTranspose(facing)), inverse)
    val centered = transforms.map { PanoramaTransform.projective(gridMultiply(view, requireNotNull(it.matrix)), plane = true) }
    val limit = Math.toRadians(130.0)
    val local = centered.flatMap(::rays)
    val yaw = local.map { atan2(it[0], it[2]) }
    val pitch = local.map { atan2(it[1], it[2]) }
    val fits = yaw.max() - yaw.min() <= limit && pitch.max() - pitch.min() <= limit && local.all {
        val length = sqrt(it.sumOf { v -> v * v })
        length.isFinite() && length > 1e-8 && it[2] / length >= cos(Math.toRadians(75.0))
    }
    return PanoramaPlaneLayout(centered, fits)
}
