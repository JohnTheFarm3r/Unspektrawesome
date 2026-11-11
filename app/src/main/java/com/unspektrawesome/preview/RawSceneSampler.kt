package com.unspektrawesome.preview

import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.vulkan.params.RawShaderParameters
import java.nio.ByteBuffer
import kotlin.math.ln

internal object RawSceneSampler {
    private const val COLUMNS = 12
    private const val ROWS = 8

    fun sample(bytes: ByteBuffer, raw: RawShaderParameters, geometry: FrameGeometrySnapshot): FloatArray? {
        val width = raw.rawWidth
        val height = raw.rawHeight
        if (width < 2 || height < 2 || geometry.sourceSize.width != width || geometry.sourceSize.height != height) return null
        val rowBytes = when (raw.rawFormat) {
            RawShaderParameters.RAW_FORMAT_SENSOR -> width.toLong() * 2
            RawShaderParameters.RAW_FORMAT_10 -> (width.toLong() + 3) / 4 * 5
            RawShaderParameters.RAW_FORMAT_12 -> (width.toLong() + 1) / 2 * 3
            else -> return null
        }
        if (raw.rowStrideBytes < rowBytes || (height - 1L) * raw.rowStrideBytes + rowBytes > bytes.limit()) return null
        val firstGreenX = if (raw.cfaPattern == RawShaderParameters.CFA_RGGB ||
            raw.cfaPattern == RawShaderParameters.CFA_BGGR) 1 else 0
        val firstRange = (raw.whiteLevelG1 - raw.blackLevelG1).coerceAtLeast(1f)
        val secondRange = (raw.whiteLevelG2 - raw.blackLevelG2).coerceAtLeast(1f)
        val result = FloatArray(COLUMNS * ROWS)
        val bounds = geometry.rawBounds
        val sampler = if (raw.cfaBlockSize > 1) com.unspektrawesome.vulkan.params.RemosaicSampler(raw.toByteBuffer()) { x, y -> read(bytes, raw, x, y) } else null
        fun retainedSample(x: Int, y: Int): Int {
            val minX = bounds.left + ((bounds.left xor x) and 1)
            val minY = bounds.top + ((bounds.top xor y) and 1)
            val maxX = bounds.right - 1 - (((bounds.right - 1) xor x) and 1)
            val maxY = bounds.bottom - 1 - (((bounds.bottom - 1) xor y) and 1)
            return sampler?.sample(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
                ?: read(bytes, raw, x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
        }
        for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
            var green = 0f
            for (y in 0..2) for (x in 0..2) {
                val point = geometry.sourceUv((column + .2f + x * .3f) / COLUMNS,
                    (row + .2f + y * .3f) / ROWS)
                val rawX = ((point.x * width).toInt() and -2).coerceIn(0, (width - 2) and -2)
                val rawY = ((point.y * height).toInt() and -2).coerceIn(0, (height - 2) and -2)
                green += ((retainedSample(rawX + firstGreenX, rawY) - raw.blackLevelG1) / firstRange).coerceIn(0f, 1f)
                green += ((retainedSample(rawX + 1 - firstGreenX, rawY + 1) - raw.blackLevelG2) / secondRange).coerceIn(0f, 1f)
            }
            result[row * COLUMNS + column] = ln((green / 18f).coerceAtLeast(.0001f))
        }
        return result
    }

    internal fun read(bytes: ByteBuffer, raw: RawShaderParameters, x: Int, y: Int): Int {
        val row = y * raw.rowStrideBytes
        return when (raw.rawFormat) {
            RawShaderParameters.RAW_FORMAT_10 -> {
                val group = row + x / 4 * 5
                ((bytes[group + x % 4].toInt() and 255) shl 2) or
                    ((bytes[group + 4].toInt() ushr (x % 4 * 2)) and 3)
            }
            RawShaderParameters.RAW_FORMAT_12 -> {
                val group = row + x / 2 * 3
                ((bytes[group + x % 2].toInt() and 255) shl 4) or
                    ((bytes[group + 2].toInt() ushr (x % 2 * 4)) and 15)
            }
            else -> (bytes[row + x * 2].toInt() and 255) or
                ((bytes[row + x * 2 + 1].toInt() and 255) shl 8)
        }
    }
}
