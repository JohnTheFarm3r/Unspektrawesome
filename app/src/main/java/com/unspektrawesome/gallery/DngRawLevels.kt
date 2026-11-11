package com.unspektrawesome.gallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToLong

internal class DngRawLevels private constructor(val black: DoubleArray, val white: Int, val period: Int) {
    /** Replaces the level tags in the RAW IFD written by Android's DngCreator. */
    fun writeTo(input: FileChannel, output: FileChannel) {
        val size = input.size()
        fun read(offset: Long, length: Int, order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteBuffer {
            require(offset >= 0 && offset <= size - length) { "Truncated DNG metadata" }
            return ByteBuffer.allocate(length).order(order).apply {
                while (hasRemaining()) check(input.read(this, offset + position()) > 0) { "Unable to read DNG metadata" }
                flip()
            }
        }
        val header = read(0, 8)
        val order = when (header.getShort(0).toInt() and 65535) {
            0x4949 -> ByteOrder.LITTLE_ENDIAN
            0x4d4d -> ByteOrder.BIG_ENDIAN
            else -> error("Invalid DNG byte order")
        }
        header.order(order)
        require(header.getShort(2).toInt() == 42) { "Unsupported DNG header" }
        fun rawDirectory(): Pair<Long, ByteBuffer> {
            val pending = ArrayDeque<Long>().apply { add(header.getInt(4).toLong() and 0xffffffffL) }
            val visited = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                require(visited.size < 32 && visited.add(offset)) { "Invalid DNG directory chain" }
                require(offset >= 8) { "Invalid DNG directory" }
                val count = read(offset, 2, order).short.toInt() and 65535
                require(count in 1..1024) { "Invalid DNG directory length" }
                val directory = read(offset + 2, count * 12 + 4, order)
                for (index in 0 until count) {
                    val entry = index * 12
                    if ((directory.getShort(entry).toInt() and 65535) == 262 &&
                        directory.getShort(entry + 2).toInt() == 3 && directory.getInt(entry + 4) == 1 &&
                        (directory.getShort(entry + 8).toInt() and 65535) == 32803) return offset to directory
                }
                // Some Android versions put the thumbnail first and the RAW image in a SubIFD.
                for (index in 0 until count) {
                    val entry = index * 12
                    if ((directory.getShort(entry).toInt() and 65535) != 330) continue
                    val children = directory.getInt(entry + 4).toLong() and 0xffffffffL
                    require(directory.getShort(entry + 2).toInt() == 4 && children in 1..32) { "Invalid DNG directory chain" }
                    val at = if (children == 1L) offset + 2 + entry + 8 else directory.getInt(entry + 8).toLong() and 0xffffffffL
                    val links = read(at, children.toInt() * 4, order)
                    repeat(children.toInt()) { (links.int.toLong() and 0xffffffffL).takeIf { it > 0 }?.let(pending::add) }
                }
                (directory.getInt(count * 12).toLong() and 0xffffffffL).takeIf { it > 0 }?.let(pending::add)
            }
            throw IllegalArgumentException("DNG RAW directory is missing")
        }
        val (ifd, directory) = rawDirectory()
        val count = (directory.limit() - 4) / 12
        val fields = (0 until count).associate { (directory.getShort(it * 12).toInt() and 65535) to it * 12 }
        fun field(tag: Int, type: Int, count: Int, bytes: Int): Long {
            val entry = requireNotNull(fields[tag]) { "DNG tag $tag is missing" }
            require(directory.getShort(entry + 2).toInt() == type && directory.getInt(entry + 4) == count) {
                "Unsupported DNG tag $tag"
            }
            val offset = if (bytes <= 4) ifd + 2 + entry + 8 else directory.getInt(entry + 8).toLong() and 0xffffffffL
            require(offset >= 8 && offset <= size - bytes) { "Truncated DNG tag $tag" }
            if (bytes > 4) require(offset + bytes <= ifd || offset >= ifd + 2 + directory.limit()) { "Invalid DNG tag offset" }
            return offset
        }
        val repeatDim = read(field(50713, 3, 2, 4), 4, order)
        require(repeatDim.short.toInt() == period && repeatDim.short.toInt() == period) { "Unsupported DNG black level pattern" }
        val blackOffset = field(50714, 5, black.size, black.size * 8)
        val whiteOffset = field(50717, 4, 1, 4)
        val origin = if (50829 in fields) read(field(50829, 4, 4, 16), 8, order) else null
        val top = (origin?.int ?: 0) % period
        val left = (origin?.int ?: 0) % period
        val blackData = ByteBuffer.allocate(black.size * 8).order(order).apply {
            repeat(black.size) { index ->
                val value = black[((index / period + top) % period) * period + (index % period + left) % period]
                val denominator = minOf(1_000_000.0, 0xffffffffL / maxOf(value, 1.0)).toLong()
                putInt((value * denominator).roundToLong().toInt())
                putInt(denominator.toInt())
            }
            flip()
        }
        val whiteData = ByteBuffer.allocate(4).order(order).putInt(white).apply { flip() }
        for ((offset, data) in listOf(blackOffset to blackData, whiteOffset to whiteData)) {
            while (data.hasRemaining()) check(output.write(data, offset + data.position()) > 0) { "Unable to write DNG levels" }
        }
    }

    companion object {
        fun from(raw: ByteBuffer): DngRawLevels {
            val mosaic = com.unspektrawesome.vulkan.params.RawMosaic(raw)
            val white = raw.getFloat(48)
            require(white.isFinite() && white in 1f..65535f && white == white.toInt().toFloat() &&
                (1..3).all { raw.getFloat(48 + it * 4) == white }) { "Invalid DNG white level" }
            val black = DoubleArray(mosaic.period * mosaic.period) { raw.getFloat(32 + mosaic.sourceLevel(it % mosaic.period, it / mosaic.period) * 4).toDouble() }
            require(black.all { it.isFinite() && it >= 0 && it < white }) { "Invalid DNG black levels" }
            return DngRawLevels(black, white.toInt(), mosaic.period)
        }
    }
}
