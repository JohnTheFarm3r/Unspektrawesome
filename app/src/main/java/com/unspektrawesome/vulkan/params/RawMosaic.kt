package com.unspektrawesome.vulkan.params

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.round

internal class RawMosaic(raw: ByteBuffer) {
    val blockSize = if (raw.limit() >= RawShaderParameters.SIZE_BYTES) raw.getInt(64).coerceAtLeast(1) else 1
    val pattern = raw.getInt(24)
    private val sourcePattern = if (blockSize > 1) raw.getInt(68) else pattern
    private val phaseX = if (blockSize > 1) raw.getInt(72) else 0
    private val phaseY = if (blockSize > 1) raw.getInt(76) else 0
    val period = blockSize * 2

    init {
        require(blockSize in 1..4 && pattern in 0..3 && sourcePattern in 0..3 &&
            phaseX in 0 until period && phaseY in 0 until period) { "Invalid RAW remosaic layout" }
    }

    fun outputLevel(x: Int, y: Int): Int = level(x and 1, y and 1, pattern)

    fun sourceLevel(x: Int, y: Int): Int {
        val value = level(((x + phaseX) / blockSize) and 1, ((y + phaseY) / blockSize) and 1, sourcePattern)
        return if (value in 1..2) ((value - 1) xor (phaseY and 1)) + 1 else value
    }

    fun sourceColor(x: Int, y: Int): Int = color(sourceLevel(x, y))

    companion object {
        fun color(level: Int): Int = if (level == 3) 2 else if (level == 0) 0 else 1

        private fun level(x: Int, y: Int, pattern: Int): Int = when (y * 2 + x) {
            pattern -> 0
            pattern xor 3 -> 3
            else -> y + 1
        }
    }
}

/** Sparse CPU counterpart of the GPU remosaic for metering samples and queue thumbnails. */
internal class RemosaicSampler(raw: ByteBuffer, private val read: (Int, Int) -> Int) {
    private val mosaic = RawMosaic(raw)
    private val width = raw.getInt(0)
    private val height = raw.getInt(4)
    private val black = FloatArray(4) { raw.getFloat(32 + it * 4) }
    private val white = FloatArray(4) { raw.getFloat(48 + it * 4) }

    fun sample(x: Int, y: Int): Int {
        if (mosaic.blockSize == 1) return read(x, y)
        val level = mosaic.outputLevel(x, y)
        val target = RawMosaic.color(level)
        fun encode(sx: Int, sy: Int): Float {
            val source = mosaic.sourceLevel(sx, sy)
            val normalized = ((read(sx, sy) - black[source]) / (white[source] - black[source]).coerceAtLeast(1f)).coerceIn(0f, 1.25f)
            return round(normalized * (white[level] - black[level]) + black[level]).coerceIn(black[level], white[level])
        }
        if (mosaic.sourceColor(x, y) == target) return encode(x, y).toInt()
        var bestValue = -1f
        var bestScore = Float.POSITIVE_INFINITY
        for (axis in 0..3) {
            val dx = if (axis == 1) 0 else 1
            val dy = when (axis) { 0 -> 0; 3 -> -1; else -> 1 }
            fun distance(side: Int): Int {
                for (d in 1..mosaic.period) {
                    val sx = x + side * dx * d
                    val sy = y + side * dy * d
                    if (sx !in 0 until width || sy !in 0 until height) return 0
                    if (mosaic.sourceColor(sx, sy) == target) return d
                }
                return 0
            }
            val negative = distance(-1)
            val positive = distance(1)
            val a = if (negative > 0) encode(x - dx * negative, y - dy * negative) else 0f
            val b = if (positive > 0) encode(x + dx * positive, y + dy * positive) else 0f
            val value: Float
            val score: Float
            if (negative > 0 && positive > 0) {
                val total = (negative + positive).toFloat()
                value = (a * positive + b * negative) / total
                score = abs(b - a) / total + .015f * total
            } else if (negative > 0 || positive > 0) {
                value = if (negative > 0) a else b
                score = 256f + (negative + positive) * 8f
            } else continue
            if (score < bestScore) { bestScore = score; bestValue = value }
        }
        if (bestValue < 0f) {
            var sum = 0f
            var weights = 0f
            val radius = mosaic.period - 1
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val sx = x + dx
                val sy = y + dy
                if (sx !in 0 until width || sy !in 0 until height || dx == 0 && dy == 0 ||
                    mosaic.sourceColor(sx, sy) != target) continue
                val weight = 1f / (dx * dx + dy * dy)
                sum += encode(sx, sy) * weight
                weights += weight
            }
            bestValue = if (weights > 0f) sum / weights else black[level]
        }
        return round(bestValue).coerceIn(black[level], white[level]).toInt()
    }
}
