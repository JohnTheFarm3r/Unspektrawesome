package com.unspektrawesome.camera

import kotlin.math.abs

object CameraOrientation {
    private const val CHANGE_THRESHOLD_DEGREES = 60

    fun resolve(rawDegrees: Int, currentDegrees: Int?): Int {
        require(rawDegrees in 0..359) { "Raw orientation must be between 0 and 359 degrees" }
        require(currentDegrees == null || currentDegrees in VALID_ORIENTATIONS) {
            "Current orientation must be 0, 90, 180, or 270 degrees"
        }
        val nearest = ((rawDegrees + 45) / 90 * 90) % 360
        if (currentDegrees == null) return nearest
        val distance = abs(shortestDelta(rawDegrees, currentDegrees))
        return if (distance >= CHANGE_THRESHOLD_DEGREES) nearest else currentDegrees
    }

    fun controlRotation(degrees: Int): Float = when (degrees) {
        0 -> 0f
        90 -> -90f
        180 -> 180f
        270 -> 90f
        else -> throw IllegalArgumentException(
            "Control orientation must be 0, 90, 180, or 270 degrees",
        )
    }

    fun displayRotation(degrees: Int): Int = when (degrees) {
        0 -> 0
        90 -> 270
        180 -> 180
        270 -> 90
        else -> throw IllegalArgumentException(
            "Device orientation must be 0, 90, 180, or 270 degrees",
        )
    }

    fun nearestEquivalentRotation(currentDegrees: Float, targetDegrees: Float): Float {
        require(currentDegrees.isFinite() && targetDegrees.isFinite()) {
            "Rotation values must be finite"
        }
        val delta = (targetDegrees - currentDegrees + 540f) % 360f - 180f
        return currentDegrees + delta
    }

    private fun shortestDelta(value: Int, reference: Int): Int =
        (value - reference + 540) % 360 - 180

    private val VALID_ORIENTATIONS = setOf(0, 90, 180, 270)
}
