package com.unspektrawesome.gallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Reads the uncompressed CFA strips retained with each capture. */
internal object DngPixels {
    fun read(channel: FileChannel, width: Int, height: Int): ByteBuffer {
        val size = channel.size()
        require(size in 8..Int.MAX_VALUE.toLong()) { "Invalid DNG size" }
        val data = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        val order = when (data.getShort(0).toInt() and 65535) {
            0x4949 -> ByteOrder.LITTLE_ENDIAN
            0x4d4d -> ByteOrder.BIG_ENDIAN
            else -> error("Invalid DNG byte order")
        }
        data.order(order)
        fun uint(offset: Int) = data.getInt(offset).toLong() and 0xffffffffL
        require((data.getShort(2).toInt() and 65535) == 42) { "Unsupported DNG header" }
        fun values(entry: Int): LongArray {
            val type = data.getShort(entry + 2).toInt() and 65535
            val count = uint(entry + 4)
            require(count in 1..65536) { "Invalid DNG field length" }
            val bytes = when (type) { 3 -> 2; 4 -> 4; else -> error("Unsupported DNG field type") }
            val offset = if (count * bytes <= 4) entry + 8L else uint(entry + 8)
            require(offset >= 0 && offset + count * bytes <= size) { "Truncated DNG field" }
            return LongArray(count.toInt()) { index ->
                val at = offset.toInt() + index * bytes
                if (bytes == 2) (data.getShort(at).toInt() and 65535).toLong() else uint(at)
            }
        }
        val pending = ArrayDeque<Long>().apply { add(uint(4)) }
        val visited = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            require(visited.size < 32 && visited.add(offset)) { "Invalid DNG directory chain" }
            require(offset in 8..size - 2) { "Invalid DNG directory" }
            val count = data.getShort(offset.toInt()).toInt() and 65535
            require(count <= 1024 && offset + 2 + count * 12 + 4 <= size) { "Truncated DNG directory" }
            val fields = mutableMapOf<Int, Int>()
            repeat(count) {
                val entry = offset.toInt() + 2 + it * 12
                fields[data.getShort(entry).toInt() and 65535] = entry
            }
            fun field(tag: Int) = fields[tag]?.let(::values)
            if (field(256)?.first() == width.toLong() && field(257)?.first() == height.toLong() &&
                field(262)?.first() == 32803L) {
                require(field(258)?.single() == 16L && (field(259)?.single() ?: 1L) == 1L &&
                    (field(277)?.single() ?: 1L) == 1L && (field(284)?.single() ?: 1L) == 1L) {
                    "Unsupported DNG RAW encoding"
                }
                val offsets = requireNotNull(field(273)) { "DNG RAW strips are missing" }
                val lengths = requireNotNull(field(279)) { "DNG RAW strip lengths are missing" }
                val expected = width.toLong() * height * 2
                require(expected in 1..Int.MAX_VALUE.toLong() && offsets.size == lengths.size && lengths.sum() == expected)
                offsets.indices.forEach { require(offsets[it] >= 8 && lengths[it] > 0 && offsets[it] <= size - lengths[it]) { "Truncated DNG RAW pixels" } }
                if (order == ByteOrder.nativeOrder() && offsets.indices.drop(1).all { offsets[it] == offsets[it - 1] + lengths[it - 1] }) {
                    return data.duplicate().apply { position(offsets[0].toInt()); limit((offsets[0] + expected).toInt()) }
                        .slice().order(ByteOrder.nativeOrder())
                }
                val result = ByteBuffer.allocateDirect(expected.toInt()).order(ByteOrder.nativeOrder())
                offsets.indices.forEach { index ->
                    val strip = data.duplicate().order(order).apply {
                        position(offsets[index].toInt()); limit((offsets[index] + lengths[index]).toInt())
                    }
                    while (strip.hasRemaining()) result.putShort(strip.short)
                }
                return result.apply { flip() }
            }
            field(330)?.forEach { if (it > 0) pending.add(it) }
            uint(offset.toInt() + 2 + count * 12).takeIf { it > 0 }?.let(pending::add)
        }
        error("DNG does not contain the original RAW raster")
    }
}
