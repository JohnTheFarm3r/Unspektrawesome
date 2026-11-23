package com.unspektrawesome.video

import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lowers the display refresh rate while recording, so the interface and screen composition run
 * at a whole multiple of the video frame rate instead of the panel's highest rate. The rate stays
 * at 60 Hz or above so the recording controls remain responsive.
 */
internal object RecordingDisplayRate {
    fun apply(window: Window, frameRate: Double?) {
        val modeId = frameRate?.let { recordingMode(window, it) } ?: 0
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == modeId) return
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }

    private fun recordingMode(window: Window, frameRate: Double): Int {
        if (!frameRate.isFinite() || frameRate <= 0) return 0
        val display = window.decorView.display ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { mode ->
                val multiple = mode.refreshRate / frameRate
                mode.refreshRate >= MINIMUM_REFRESH_RATE - 0.5f && multiple >= 0.99 &&
                    abs(multiple - multiple.roundToInt()) < 0.02
            }
            .minByOrNull { it.refreshRate }?.modeId ?: 0
    }

    private const val MINIMUM_REFRESH_RATE = 60f
}
