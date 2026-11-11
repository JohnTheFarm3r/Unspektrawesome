package com.unspektrawesome.ui.camera

import kotlin.math.sqrt

internal object FocusScale {
    private const val farthestFiniteDiopters = 1f / 100f

    fun isInfinity(distance: Float): Boolean = !distance.isFinite() || distance < farthestFiniteDiopters

    fun distance(position: Float, nearest: Float): Float {
        if (!nearest.isFinite() || nearest < farthestFiniteDiopters || !position.isFinite()) return 0f
        val remaining = 1f - position.coerceIn(0f, 1f)
        if (remaining == 0f) return 0f
        // Smaller changes near infinity give distant subjects more room on the ruler.
        return (farthestFiniteDiopters + (nearest - farthestFiniteDiopters) * remaining * remaining)
            .coerceIn(farthestFiniteDiopters, nearest)
    }

    fun position(distance: Float, nearest: Float): Float {
        if (!nearest.isFinite() || nearest < farthestFiniteDiopters || isInfinity(distance)) return 1f
        if (distance >= nearest) return 0f
        val fraction = ((distance - farthestFiniteDiopters) / (nearest - farthestFiniteDiopters)).coerceIn(0f, 1f)
        // Keep 100 m distinct from the infinity endpoint.
        return (1f - sqrt(fraction)).coerceAtMost(Math.nextDown(1f))
    }
}
