package com.unspektrawesome.gallery

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

internal data class Mp4ColorInfo(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean?) {
    companion object {
        fun read(channel: SeekableByteChannel, offset: Long = 0, length: Long = channel.size() - offset): Mp4ColorInfo? {
            if (offset < 0 || length < 0 || offset > channel.size() - length) return null
            var remainingBoxes = 10_000
            fun bytes(at: Long, count: Int): ByteBuffer {
                val buffer = ByteBuffer.allocate(count)
                channel.position(at)
                while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
                return buffer.flip() as ByteBuffer
            }
            fun scan(start: Long, end: Long, depth: Int): Mp4ColorInfo? {
                if (depth > 8) return null
                var position = start
                while (end - position >= 8 && remainingBoxes-- > 0) {
                    val header = bytes(position, 8)
                    val size32 = header.int.toLong() and 0xffffffffL
                    val type = header.int
                    val headerSize = if (size32 == 1L) 16 else 8
                    if (end - position < headerSize) return null
                    val size = when (size32) { 0L -> end - position; 1L -> bytes(position + 8, 8).long; else -> size32 }
                    if (size < headerSize || size > end - position) return null
                    val payload = position + headerSize
                    val boxEnd = position + size
                    // Follow the movie's sample descriptions and skip encoded media without reading it.
                    val childStart = when (type) {
                        0x6d6f6f76, 0x7472616b, 0x6d646961, 0x6d696e66, 0x7374626c -> payload
                        0x73747364 -> payload + 8
                        0x61766331, 0x61766333, 0x68766331, 0x68657631, 0x61763031, 0x76703039, 0x656e6376 -> payload + 78
                        else -> null
                    }
                    if (type == 0x636f6c72 && boxEnd - payload >= 10) {
                        val color = bytes(payload, minOf(11L, boxEnd - payload).toInt())
                        val colorType = color.int
                        if (colorType == 0x6e636c78 || colorType == 0x6e636c63) {
                            val primaries = color.short.toInt() and 0xffff
                            val transfer = color.short.toInt() and 0xffff
                            val matrix = color.short.toInt() and 0xffff
                            if (colorType == 0x6e636c78 && !color.hasRemaining()) return null
                            return Mp4ColorInfo(primaries, transfer, matrix,
                                if (colorType == 0x6e636c78) color.get().toInt() and 0x80 != 0 else null)
                        }
                    }
                    if (childStart != null && childStart < boxEnd) scan(childStart, boxEnd, depth + 1)?.let { return it }
                    position = boxEnd
                }
                return null
            }
            return runCatching { scan(offset, offset + length, 0) }.getOrNull()
        }
    }
}
