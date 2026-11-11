package com.unspektrawesome.preview

import kotlin.math.abs

internal class MeteringSceneChangeDetector {
    private var setAtMs = 0L
    private var reference: FloatArray? = null
    private var changedSinceMs: Long? = null
    private var changedSamples = 0

    fun reset(nowMs: Long = 0L) {
        setAtMs = nowMs
        reference = null
        changedSinceMs = null
        changedSamples = 0
    }

    fun observe(nowMs: Long, sample: FloatArray): Boolean {
        if (sample.isEmpty() || sample.any { !it.isFinite() }) return false
        val previous = reference
        if (previous == null || previous.size != sample.size || nowMs - setAtMs < 1_200) {
            reference = sample.copyOf()
            return false
        }
        if (nowMs - setAtMs < 5_000) return false
        val differences = FloatArray(sample.size) { sample[it] - previous[it] }
        // Remove a global exposure change before comparing the scene's spatial structure.
        val sorted = differences.sortedArray()
        val exposureShift = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) * .5f
        var changed = 0
        var totalDifference = 0f
        for (difference in differences) {
            val residual = abs(difference - exposureShift)
            if (residual > .45f) changed++
            totalDifference += residual
        }
        if (changed < sample.size * .45f || totalDifference < sample.size * .35f) {
            changedSinceMs = null
            changedSamples = 0
            return false
        }
        if (changedSinceMs == null) changedSinceMs = nowMs
        changedSamples++
        return changedSamples >= 2 && nowMs - requireNotNull(changedSinceMs) >= 600
    }
}
