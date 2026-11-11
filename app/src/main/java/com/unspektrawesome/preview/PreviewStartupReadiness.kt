package com.unspektrawesome.preview

/** Latches readiness after a sustained window of regular processed-frame delivery. */
internal class PreviewStartupReadiness {
    var isReady = false
        private set
    private val completions = ArrayDeque<Long>()
    private var revision: Long? = null

    fun onFrameCompleted(nowMs: Long, spektraRevision: Long, variableExposure: Boolean = false): Boolean {
        if (isReady) return true
        if (revision != spektraRevision || completions.lastOrNull()?.let { nowMs <= it } == true) {
            completions.clear()
        }
        revision = spektraRevision
        completions.addLast(nowMs)
        while (completions.size > MIN_INTERVALS + 1 && nowMs - completions[1] >= STABLE_WINDOW_MS) {
            completions.removeFirst()
        }
        if (completions.size < MIN_INTERVALS + 1 || nowMs - completions.first() < STABLE_WINDOW_MS) {
            return false
        }
        val intervals = completions.zipWithNext { first, second -> second - first }.sorted()
        val median = intervals[intervals.size / 2]
        // Sensor AE changes the frame duration while it converges, so regular cadence is not expected.
        isReady = if (variableExposure) intervals.last() <= maxOf(500L, median * 3)
            else intervals.first() >= median / 2.0 && intervals.last() <= median * 1.75
        return isReady
    }

    fun onRenderFailed() {
        if (!isReady) completions.clear()
    }

    private companion object {
        const val STABLE_WINDOW_MS = 1_000L
        const val MIN_INTERVALS = 8
    }
}
