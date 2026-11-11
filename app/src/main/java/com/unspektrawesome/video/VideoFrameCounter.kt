package com.unspektrawesome.video

import kotlin.math.abs

/** Counts missing output frames without counting encoder latency or intentional decimation. */
internal class VideoFrameCounter(frameRate: Double = 30.0) {
    init { require(frameRate.isFinite() && frameRate > 0) }
    private val intervalUs = (1_000_000.0 / frameRate).toLong().coerceAtLeast(1)
    private var nextFrameUs: Long? = null
    private var previousUs = Long.MIN_VALUE
    @Volatile var droppedFrames = 0L
        private set
    @Volatile var encodedFrames = 0L
        private set
    private var lastProgressAtMs: Long? = null
    private var sampledFrames = 0L
    private val outputTimesUs = java.util.ArrayDeque<Long>()

    @Synchronized fun sampleFramesPerSecond(nowMs: Long): Double? {
        val frames = encodedFrames
        if (frames != sampledFrames) lastProgressAtMs = nowMs
        sampledFrames = frames
        if (lastProgressAtMs?.let { nowMs - it >= 2_000 } == true) return 0.0
        if (outputTimesUs.size < 2) return null
        return (outputTimesUs.size - 1) * 1_000_000.0 / (outputTimesUs.last - outputTimesUs.first)
    }

    @Synchronized fun encoded(presentationTimeUs: Long) {
        if (presentationTimeUs <= previousUs) return
        outputTimesUs.addLast(presentationTimeUs)
        while (outputTimesUs.size > 2 && presentationTimeUs - outputTimesUs.first > 2_000_000)
            outputTimesUs.removeFirst()
        encodedFrames++
        previousUs = presentationTimeUs
        val expected = nextFrameUs
        if (expected == null) {
            nextFrameUs = presentationTimeUs + intervalUs
            return
        }
        val missing = ((presentationTimeUs - expected + intervalUs / 2) / intervalUs).coerceAtLeast(0)
        droppedFrames += missing
        val slot = expected + missing * intervalUs
        // Follow small sensor clock differences, including 29.97 fps, without accumulating false drops.
        nextFrameUs = if (abs(presentationTimeUs - slot) <= 1_000) presentationTimeUs + intervalUs
            else slot + intervalUs
    }
}
