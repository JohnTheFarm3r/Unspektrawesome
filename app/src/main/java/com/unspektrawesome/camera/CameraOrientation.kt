package com.unspektrawesome.camera

import com.unspektrawesome.settings.UiOrientation
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

    /**
     * Orientation of on-screen text and buttons. [systemLockedDegrees] is the orientation held by
     * the system rotation lock, or null while system auto-rotate is on.
     */
    fun uiOrientation(mode: UiOrientation, deviceDegrees: Int, landscapeDegrees: Int, systemLockedDegrees: Int?): Int =
        when (mode) {
            UiOrientation.AUTOMATIC -> deviceDegrees
            UiOrientation.FOLLOW_SYSTEM -> systemLockedDegrees ?: deviceDegrees
            UiOrientation.PORTRAIT -> 0
            UiOrientation.LANDSCAPE -> landscapeDegrees
        }

    /** Landscape side to keep for a forced landscape layout: the one the phone was last held in. */
    fun landscapeSide(deviceDegrees: Int, currentDegrees: Int): Int =
        if (deviceDegrees == 90 || deviceDegrees == 270) deviceDegrees else currentDegrees

    /** Device orientation that matches a system display rotation (Surface.ROTATION_0 to ROTATION_270). */
    fun fromDisplayRotation(surfaceRotation: Int): Int =
        if (surfaceRotation in 0..3) (360 - surfaceRotation * 90) % 360 else 0

    fun nearestEquivalentRotation(currentDegrees: Float, targetDegrees: Float): Float {
        require(currentDegrees.isFinite() && targetDegrees.isFinite()) {
            "Rotation values must be finite"
        }
        val delta = (targetDegrees - currentDegrees + 540f) % 360f - 180f
        return currentDegrees + delta
    }

    private fun shortestDelta(value: Int, reference: Int): Int =
        (value - reference + 540) % 360 - 180

    /** Standard landscape, with the top of the phone pointing left. */
    const val DEFAULT_LANDSCAPE_DEGREES = 270

    private val VALID_ORIENTATIONS = setOf(0, 90, 180, 270)
}
