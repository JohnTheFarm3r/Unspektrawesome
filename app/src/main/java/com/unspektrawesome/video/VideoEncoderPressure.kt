package com.unspektrawesome.video

import java.util.ArrayDeque

/** Recent input-buffer occupancy and stalls, not hardware codec utilization. */
internal class VideoEncoderPressure(frameRate: Double) {
    init { require(frameRate.isFinite() && frameRate > 0) }
    private val frameIntervalNs = 1_000_000_000.0 / frameRate
    private var readyAtNs = Long.MAX_VALUE
    private data class Wait(val observedAtNs: Long, val durationNs: Long)
    private val waits = ArrayDeque<Wait>()
    private data class Occupancy(val observedAtNs: Long, val frames: Int)
    private val occupancy = ArrayDeque<Occupancy>()
    private val pending = ArrayDeque<Long>()
    private var lastEncodedUs = Long.MIN_VALUE
    private var capacity = 0
    val queuedFrames: Int @Synchronized get() = pending.size

    @Synchronized fun start(nowNs: Long) {
        readyAtNs = nowNs + 1_000_000_000L
        waits.clear()
        occupancy.clear()
        pending.clear()
        lastEncodedUs = Long.MIN_VALUE
        capacity = 0
    }

    @Synchronized fun submitted(timestampUs: Long, nowNs: Long) {
        // Output can arrive on the drain thread before the render call returns.
        if (timestampUs <= lastEncodedUs || pending.peekLast()?.let { timestampUs <= it } == true) return
        pending.addLast(timestampUs)
        recordOccupancy(nowNs)
    }

    @Synchronized fun encoded(timestampUs: Long, nowNs: Long) {
        lastEncodedUs = maxOf(lastEncodedUs, timestampUs)
        // Retire skipped output timestamps too, so losses cannot leave phantom queued frames.
        while (pending.peekFirst()?.let { it <= lastEncodedUs } == true) pending.removeFirst()
        recordOccupancy(nowNs)
    }

    private fun recordOccupancy(nowNs: Long) {
        if (nowNs >= readyAtNs) occupancy.addLast(Occupancy(nowNs, pending.size))
        trim(nowNs)
        while (occupancy.size > 2_048) occupancy.removeFirst()
    }

    @Synchronized fun observe(waitNs: Long, nowNs: Long, inputCapacity: Int = 0) {
        if (inputCapacity > 0) capacity = inputCapacity
        if (waitNs < 0 || nowNs - waitNs < readyAtNs) return
        waits.addLast(Wait(nowNs, waitNs))
        trim(nowNs)
        while (waits.size > 2_048) waits.removeFirst()
    }

    private fun trim(nowNs: Long) {
        while (waits.peekFirst()?.let { nowNs - it.observedAtNs >= 1_000_000_000L } == true) waits.removeFirst()
        while (occupancy.peekFirst()?.let { nowNs - it.observedAtNs >= 1_000_000_000L } == true) occupancy.removeFirst()
    }

    @Synchronized fun sample(nowNs: Long): Double? {
        trim(nowNs)
        if (nowNs < readyAtNs || waits.isEmpty() && capacity == 0) return null
        val waitPressure = (waits.maxOfOrNull { it.durationNs } ?: 0L) * 100.0 / frameIntervalNs
        val queued = maxOf(pending.size, occupancy.maxOfOrNull { it.frames } ?: 0)
        val queuePressure = if (capacity > 0) queued * 100.0 / capacity else 0.0
        return maxOf(waitPressure, queuePressure).coerceIn(0.0, 100.0)
    }
}
