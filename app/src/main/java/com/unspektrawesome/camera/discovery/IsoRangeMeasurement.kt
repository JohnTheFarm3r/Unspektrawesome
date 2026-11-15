package com.unspektrawesome.camera.discovery

import com.unspektrawesome.camera.RawFormat
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log2

/**
 * Finds how far ISO really goes above a camera's advertised maximum. Some cameras apply more gain
 * than they advertise, so each doubling is requested and the ISO the camera reports applying is
 * kept. The RAW brightness only checks that the image really brightened, not by how much.
 */
class IsoRangeMeasurement(
    private val reportedMax: Int,
    private val minTimeNs: Long,
    maxTimeNs: Long,
) {
    data class Request(val iso: Int, val timeNs: Long)

    sealed interface Step {
        data class Next(val request: Request) : Step
        /** [isoMax] is null when nothing above the advertised maximum was confirmed. */
        data class Done(val isoMax: Int?, val reason: Reason) : Step
    }

    enum class Reason { CONFIRMED, NO_GAIN, NOT_APPLIED, NOT_BRIGHTER }

    private val longestTimeNs = minOf(maxTimeNs, MAX_TIME_NS).coerceAtLeast(minTimeNs)
    private var calibrating = true
    private var request = Request(reportedMax, START_TIME_NS.coerceIn(minTimeNs, longestTimeNs))
    private var stops = 0
    private var waitingFrames = 0
    private var skipped = false
    private val levels = mutableListOf<Double>()
    private val results = mutableListOf<Int>()
    private var adjustments = 0
    private var baseline = Double.NaN
    private var topLevel = Double.NaN
    private var confirmed = reportedMax

    fun begin(): Request = request

    fun onFrame(requestedIso: Int, resultIso: Int, timeNs: Long, brightness: Double): Step? {
        // Frames from earlier requests are still in flight.
        if (requestedIso != request.iso || abs(timeNs - request.timeNs) > request.timeNs * .1) {
            return if (++waitingFrames > MAX_WAITING_FRAMES) finish(Reason.NOT_APPLIED) else null
        }
        if (!skipped) {
            skipped = true
            return null
        }
        levels += brightness
        if (resultIso > 0) results += resultIso
        if (levels.size < READINGS) return null
        val level = levels.filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN
        return if (calibrating) calibrate(level) else climb(level, results.sorted().getOrNull(results.size / 2) ?: 0)
    }

    /** Picks a shutter that keeps the brightness check readable. It never ends the measurement. */
    private fun calibrate(level: Double): Step {
        if (level.isFinite() && level !in GOOD_LOW..GOOD_HIGH && adjustments++ < MAX_ADJUSTMENTS) {
            val time = (request.timeNs * TARGET_LEVEL / level.coerceAtLeast(1e-5)).toLong().coerceIn(minTimeNs, longestTimeNs)
            if (time != request.timeNs) return next(Request(reportedMax, time))
        }
        baseline = level
        calibrating = false
        return climb()
    }

    private fun climb(): Step {
        stops++
        val iso = (reportedMax.toLong() shl stops).coerceAtMost(MAX_ISO.toLong()).toInt()
        if (stops > MAX_STOPS || iso <= confirmed) return finish(Reason.CONFIRMED)
        return next(Request(iso, request.timeNs))
    }

    private fun climb(level: Double, applied: Int): Step {
        if (applied < confirmed * MIN_STEP) return finish(Reason.CONFIRMED)
        confirmed = minOf(applied, request.iso)
        topLevel = level
        // A camera that stops partway reports the ISO it reached.
        return if (applied < request.iso * FULL_STEP) finish(Reason.CONFIRMED) else climb()
    }

    private fun finish(reason: Reason): Step.Done {
        if (confirmed < reportedMax * MIN_STEP) return Step.Done(null, if (reason == Reason.CONFIRMED) Reason.NO_GAIN else reason)
        // A camera can report a value it did not apply; the image must brighten noticeably.
        if (baseline in USABLE_LOW..USABLE_HIGH && topLevel.isFinite() && topLevel < CLIPPED &&
            log2(topLevel / baseline) < log2(confirmed.toDouble() / reportedMax) * .5) return Step.Done(null, Reason.NOT_BRIGHTER)
        return Step.Done(confirmed, Reason.CONFIRMED)
    }

    private fun next(next: Request): Step {
        request = next
        waitingFrames = 0
        skipped = false
        levels.clear()
        results.clear()
        return Step.Next(next)
    }

    companion object {
        const val MAX_ISO = 409_600
        private const val MAX_STOPS = 5
        private const val START_TIME_NS = 16_666_667L
        private const val MAX_TIME_NS = 66_666_667L
        private const val TARGET_LEVEL = .015
        private const val GOOD_LOW = .006
        private const val GOOD_HIGH = .04
        private const val USABLE_LOW = .002
        private const val USABLE_HIGH = .3
        private const val CLIPPED = .85
        private const val MIN_STEP = 1.1
        private const val FULL_STEP = .95
        private const val READINGS = 2
        private const val MAX_ADJUSTMENTS = 4
        private const val MAX_WAITING_FRAMES = 30
    }
}

/** Average RAW level above black, as a fraction of the usable range. */
internal object RawBrightness {
    private const val COLUMNS = 48
    private const val ROWS = 36

    fun mean(bytes: ByteBuffer, format: RawFormat, width: Int, height: Int, rowStrideBytes: Int,
             blackLevel: FloatArray, whiteLevel: Int): Double {
        val rowBytes = when (format) {
            RawFormat.RAW_SENSOR -> width.toLong() * 2
            RawFormat.RAW10 -> (width.toLong() + 3) / 4 * 5
            RawFormat.RAW12 -> (width.toLong() + 1) / 2 * 3
        }
        if (width < 4 || height < 4 || blackLevel.size != 4 || rowStrideBytes < rowBytes ||
            (height - 1L) * rowStrideBytes + rowBytes > bytes.limit()) return Double.NaN
        val black = blackLevel.average()
        val range = whiteLevel - black
        if (range <= 1.0) return Double.NaN
        var sum = 0.0
        for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
            val x = ((column * 2 + 1) * width / (COLUMNS * 2)) and -2
            val y = ((row * 2 + 1) * height / (ROWS * 2)) and -2
            for (dy in 0..1) for (dx in 0..1) sum += read(bytes, format, rowStrideBytes, x + dx, y + dy)
        }
        return (sum / (ROWS * COLUMNS * 4) - black) / range
    }

    private fun read(bytes: ByteBuffer, format: RawFormat, stride: Int, x: Int, y: Int): Int {
        val row = y * stride
        return when (format) {
            RawFormat.RAW10 -> {
                val group = row + x / 4 * 5
                ((bytes[group + x % 4].toInt() and 255) shl 2) or ((bytes[group + 4].toInt() ushr (x % 4 * 2)) and 3)
            }
            RawFormat.RAW12 -> {
                val group = row + x / 2 * 3
                ((bytes[group + x % 2].toInt() and 255) shl 4) or ((bytes[group + 2].toInt() ushr (x % 2 * 4)) and 15)
            }
            RawFormat.RAW_SENSOR -> (bytes[row + x * 2].toInt() and 255) or ((bytes[row + x * 2 + 1].toInt() and 255) shl 8)
        }
    }
}
