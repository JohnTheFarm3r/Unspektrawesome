package com.unspektrawesome.preview

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One sharpness measurement of the loupe area, as reported by the renderer. */
internal class FocusLoupeReading(
    /** Mean edge energy, in units of the modeled sensor noise. */
    val fine: Float,
    /** Edge energy of the flattest tenth of the loupe area. */
    val flatFine: Float,
    /** Manual focus position from 0 at infinity to 1 at the nearest distance, or NaN under autofocus. */
    val focusPosition: Float = Float.NaN,
)

/** What the focus meter shows. */
data class FocusMeterState(
    /** 0 at the softest view seen on this subject, 1 at the sharpest; null until focus has moved enough to compare. */
    val level: Float? = null,
    /** Focus moved noticeably without changing the sharpness: too little detail to judge focus. */
    val lowDetail: Boolean = false,
    /** The view is at the sharpest point seen on this subject. */
    val sharpest: Boolean = false,
)

/**
 * Compares the sharpness of the loupe area while the photographer focuses. Edge energy is corrected
 * for sensor noise and compared with the softest and sharpest views of the same subject. The
 * comparison stays dark until the change in sharpness clearly exceeds the frame to frame variation of
 * the measurement, so noise in dark or featureless areas cannot light it.
 *
 * [fineNoise] is the edge energy that sensor noise alone typically produces after the preview path's
 * demosaic. The flattest parts of the loupe refine it within a factor of two.
 */
internal class FocusLoupeMeter(private val fineNoise: Float = 1f) {
    private var smoothed = Float.NaN
    private var peak = Float.NaN
    private var floor = Float.NaN
    private var previous = Float.NaN
    private var beforePrevious = Float.NaN
    private var jitter = 0f
    private var differences = 0
    private var readings = 0
    private var flat = Float.NaN
    private var nearest = Float.NaN
    private var farthest = Float.NaN
    var state = FocusMeterState()
        private set

    /** Forgets the subject, for example after the camera turned or the loupe moved. */
    fun reset() {
        smoothed = Float.NaN
        peak = Float.NaN
        floor = Float.NaN
        previous = Float.NaN
        beforePrevious = Float.NaN
        jitter = 0f
        differences = 0
        readings = 0
        flat = Float.NaN
        nearest = Float.NaN
        farthest = Float.NaN
        state = FocusMeterState()
    }

    fun update(reading: FocusLoupeReading): FocusMeterState {
        if (!reading.fine.isFinite() || !reading.flatFine.isFinite() || reading.fine < 0f) return state
        // The flattest areas show the noise of this scene; texture there can only raise the estimate.
        flat = if (flat.isNaN()) reading.flatFine else min(reading.flatFine, flat + FLAT_DRIFT * flat)
        val noise = flat.coerceIn(fineNoise * .5f, fineNoise * 2f)

        // Edge energy above the noise; the logarithm makes equal steps mean equal relative changes.
        val signal = max(reading.fine - noise, 0f)
        val value = ln(signal + noise * SIGNAL_FLOOR)
        readings++
        // Second differences ignore the steady change while focusing and leave the measurement noise.
        // The first differences are averaged evenly so the estimate does not start low.
        if (!beforePrevious.isNaN()) {
            differences++
            jitter += max(JITTER_RATE, 1f / differences) * (abs(value - 2f * previous + beforePrevious) / SECOND_DIFFERENCE - jitter)
        }
        beforePrevious = previous
        previous = value
        if (smoothed.isNaN()) {
            smoothed = value
            peak = value
            floor = value
        } else {
            smoothed += SMOOTHING * (value - smoothed)
            // Both marks drift slowly toward the current reading so an old extreme cannot pin the meter.
            peak = max(smoothed, peak - DRIFT)
            floor = min(smoothed, floor + DRIFT)
        }
        val swing = peak - floor
        val comparable = readings > WARM_UP && swing >= max(MIN_SWING, JITTER_MARGIN * jitter)
        val level = if (comparable) ((smoothed - floor) / swing).coerceIn(0f, 1f) else null
        // Focus travelled without a clear change: featureless, too dark or beyond what the lens resolves.
        if (reading.focusPosition.isFinite()) {
            nearest = if (nearest.isNaN()) reading.focusPosition else max(nearest, reading.focusPosition)
            farthest = if (farthest.isNaN()) reading.focusPosition else min(farthest, reading.focusPosition)
        }
        val travelled = !nearest.isNaN() && nearest - farthest >= LOW_DETAIL_TRAVEL
        state = FocusMeterState(
            level = level,
            lowDetail = level == null && readings > WARM_UP && travelled,
            sharpest = level != null && level >= SHARPEST,
        )
        return state
    }

    private companion object {
        // Half the noise level keeps readings near the noise from swinging the logarithm.
        const val SIGNAL_FLOOR = .5f
        const val SMOOTHING = .3f
        const val JITTER_RATE = .1f
        // Mean absolute second difference of unit Gaussian noise: sqrt(6) times sqrt(2 / pi).
        const val SECOND_DIFFERENCE = 1.954f
        const val JITTER_MARGIN = 4f
        const val DRIFT = .0015f
        const val FLAT_DRIFT = .01f
        const val WARM_UP = 8
        // At least 30% more edge energy at the sharpest view than at the softest.
        val MIN_SWING = ln(1.3f)
        const val SHARPEST = .92f
        const val LOW_DETAIL_TRAVEL = .15f
    }
}
