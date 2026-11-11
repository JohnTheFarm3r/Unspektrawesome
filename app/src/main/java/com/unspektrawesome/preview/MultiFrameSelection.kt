package com.unspektrawesome.preview

import kotlin.math.abs
import kotlin.math.ln

/** Chooses which buffered frames can be merged with the newest one. */
internal object MultiFrameSelection {
    const val MIN_FRAMES = 2
    const val MAX_FRAMES = 15
    const val DEFAULT_FRAMES = 8

    data class Candidate(
        val timestampNs: Long,
        val exposureTimeNs: Long?,
        val iso: Int?,
        val focusDistance: Float?,
        val width: Int,
        val height: Int,
        val rowBytes: Int,
        val format: Int,
    )

    /**
     * Returns how many leading frames of [frames] (newest first) can be merged with the first one.
     * Frames must be consecutive, share the RAW layout and stay close to the reference exposure.
     */
    /** Usable frame count plus the rule that stopped the run, for diagnostics. */
    data class Selection(val count: Int, val reason: String?)

    fun usableCount(
        frames: List<Candidate>,
        maximum: Int,
        maxEvDifference: Float = .5f,
        windowNs: Long = 1_500_000_000L,
        maxFocusDifference: Float = .1f,
    ): Int = select(frames, maximum, maxEvDifference, windowNs, maxFocusDifference).count

    fun select(
        frames: List<Candidate>,
        maximum: Int,
        maxEvDifference: Float = .5f,
        windowNs: Long = 1_500_000_000L,
        maxFocusDifference: Float = .1f,
    ): Selection {
        if (frames.isEmpty()) return Selection(0, "empty")
        val reference = frames[0]
        val limit = minOf(maximum, frames.size, MAX_FRAMES)
        var count = 1
        var reason: String? = null
        while (count < limit) {
            val frame = frames[count]
            if (frame.width != reference.width || frame.height != reference.height ||
                frame.rowBytes != reference.rowBytes || frame.format != reference.format) { reason = "layout"; break }
            if (frame.timestampNs >= frames[count - 1].timestampNs) { reason = "order"; break }
            if (reference.timestampNs - frame.timestampNs > windowNs) { reason = "window"; break }
            val ev = exposureDifferenceEv(reference, frame)
            if (ev == null) { reason = "exposure_unknown ref=${reference.exposureTimeNs}/${reference.iso} frame=${frame.exposureTimeNs}/${frame.iso}"; break }
            if (abs(ev) > maxEvDifference) { reason = "exposure ev=${"%.2f".format(java.util.Locale.ROOT, ev)} ref=${reference.exposureTimeNs}/${reference.iso} frame=${frame.exposureTimeNs}/${frame.iso}"; break }
            val focus = reference.focusDistance
            val frameFocus = frame.focusDistance
            if (focus != null && frameFocus != null && abs(focus - frameFocus) > maxFocusDifference) { reason = "focus ref=$focus frame=$frameFocus"; break }
            count++
        }
        return Selection(count, reason)
    }

    /** Multiplier that brings [frame] to the exposure of [reference]. */
    fun exposureScale(reference: Candidate, frame: Candidate): Float {
        val referenceExposure = exposureProduct(reference) ?: return 1f
        val frameExposure = exposureProduct(frame) ?: return 1f
        return (referenceExposure / frameExposure).toFloat().coerceIn(.25f, 4f)
    }

    private fun exposureDifferenceEv(reference: Candidate, frame: Candidate): Float? {
        val referenceExposure = exposureProduct(reference) ?: return null
        val frameExposure = exposureProduct(frame) ?: return null
        return (ln(frameExposure / referenceExposure) / ln(2.0)).toFloat()
    }

    private fun exposureProduct(candidate: Candidate): Double? {
        val time = candidate.exposureTimeNs?.takeIf { it > 0 } ?: return null
        val iso = candidate.iso?.takeIf { it > 0 } ?: return null
        return time.toDouble() * iso
    }
}
