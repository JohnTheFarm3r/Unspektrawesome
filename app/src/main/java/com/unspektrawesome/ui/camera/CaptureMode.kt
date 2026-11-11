package com.unspektrawesome.ui.camera

import kotlin.math.abs

internal const val CAPTURE_MODE_GESTURE_GUIDE = "Swipe left or right on the viewfinder in portrait, or up or down in landscape, to cycle through BURST, VIDEO, PHOTO, 3×3 and PANORAMA. Swipe the other way to go back. In 3×3, tap the shutter and aim at each highlighted circle. Hold your aim for 1.5 seconds to capture each of the nine panels automatically. The shutter stops capture early. Export later in Gallery is the default.\n\nPanorama and 3×3 adjust exposure, white balance and focus automatically until capture starts, then lock their current values. AUTO restores these settings after manual adjustments. Captured 3×3 frames stay in place in the viewfinder."

internal enum class CaptureMode(val label: String) {
    BURST("BURST"), VIDEO("VIDEO"), PHOTO("PHOTO"), GRID("3×3"), PANORAMA("PANORAMA");

    fun step(direction: Int): CaptureMode = entries[Math.floorMod(ordinal + direction, entries.size)]
}

internal fun captureModeSwipe(dx: Float, dy: Float, threshold: Float, rotation: Float): Int? {
    if (abs(dx) < threshold || abs(dx) < abs(dy) * 1.35f) return null
    // The viewfinder stays portrait-locked while its controls rotate.
    val reversed = rotation == 90f || abs(rotation) == 180f
    return if ((dx < 0f) != reversed) 1 else -1
}
