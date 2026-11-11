package com.unspektrawesome.preview

import java.util.Locale

internal class PreviewCadenceDiagnostics {
    enum class Decision { ACCEPT, BUSY, PACED, INACTIVE }

    private var startedNs = Long.MIN_VALUE
    private var previousSensorNs = Long.MIN_VALUE
    private var previousArrivalNs = Long.MIN_VALUE
    private val decisions = LongArray(Decision.entries.size)
    private val sensorGap = Timing()
    private val arrivalGap = Timing()
    private val preparation = Timing()
    private val render = Timing()
    private val feedback = Timing()
    private val queue = Timing()
    private var rendered = 0L
    private var replacedAtStart = 0L

    @Synchronized fun frame(sensorNs: Long, nowNs: Long, decision: Decision, replaced: Long): String? {
        if (startedNs == Long.MIN_VALUE) {
            startedNs = nowNs
            replacedAtStart = replaced
        }
        decisions[decision.ordinal]++
        if (previousSensorNs != Long.MIN_VALUE && sensorNs > previousSensorNs) sensorGap.add(sensorNs - previousSensorNs)
        if (previousArrivalNs != Long.MIN_VALUE) arrivalGap.add(nowNs - previousArrivalNs)
        previousSensorNs = sensorNs
        previousArrivalNs = nowNs
        val elapsedNs = nowNs - startedNs
        if (elapsedNs < 2_000_000_000L) return null
        val detail = String.format(Locale.ROOT,
            "window_ms=%.1f input_fps=%.2f rendered_fps=%.2f accepted=%d busy=%d paced=%d inactive=%d replaced=%d " +
                "sensor_gap_ms=%s arrival_gap_ms=%s queue_ms=%s prepare_ms=%s render_ms=%s feedback_ms=%s",
            elapsedNs / 1e6, decisions.sum() * 1e9 / elapsedNs, rendered * 1e9 / elapsedNs,
            decisions[Decision.ACCEPT.ordinal], decisions[Decision.BUSY.ordinal],
            decisions[Decision.PACED.ordinal], decisions[Decision.INACTIVE.ordinal], replaced - replacedAtStart,
            sensorGap.summary(), arrivalGap.summary(), queue.summary(), preparation.summary(), render.summary(), feedback.summary())
        startedNs = nowNs
        replacedAtStart = replaced
        rendered = 0
        decisions.fill(0)
        listOf(sensorGap, arrivalGap, queue, preparation, render, feedback).forEach { it.reset() }
        return detail
    }

    @Synchronized fun completed(queueNs: Long, preparationNs: Long, renderNs: Long, feedbackNs: Long, succeeded: Boolean) {
        queue.add(queueNs)
        preparation.add(preparationNs)
        render.add(renderNs)
        feedback.add(feedbackNs)
        if (succeeded) rendered++
    }

    private class Timing {
        private var count = 0L
        private var sum = 0L
        private var min = Long.MAX_VALUE
        private var max = 0L
        fun add(ns: Long) {
            count++
            sum += ns
            min = minOf(min, ns)
            max = maxOf(max, ns)
        }
        fun summary(): String = if (count == 0L) "none" else
            String.format(Locale.ROOT, "%.3f/%.3f/%.3f", min / 1e6, sum / (count * 1e6), max / 1e6)
        fun reset() {
            count = 0
            sum = 0
            min = Long.MAX_VALUE
            max = 0
        }
    }
}
