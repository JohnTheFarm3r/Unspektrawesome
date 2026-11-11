package com.unspektrawesome.camera

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class CameraLevel(val x: Float, val y: Float, val z: Float) {
    val flat: Boolean get() = abs(z) > .88f
    val horizonRotation: Float get() = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()

    fun error(rotation: Float): Float = if (flat) Math.toDegrees(atan2(hypot(x, y), abs(z)).toDouble()).toFloat()
        else abs(((horizonRotation - rotation + 540f) % 180f) - 90f).let { abs(90f - it) }

    fun aligned(rotation: Float, previouslyAligned: Boolean): Boolean = error(rotation) <= if (previouslyAligned) 1.5f else 1f
}
