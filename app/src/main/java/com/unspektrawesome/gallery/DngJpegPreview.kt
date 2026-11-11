package com.unspektrawesome.gallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Adds a rendered preview without moving the original image or camera metadata. */
internal object DngJpegPreview {
    const val MAX_DIMENSION = 2048
    const val JPEG_QUALITY = 90

    fun writeTo(input: FileChannel, output: FileChannel, jpeg: ByteArray) {
        val frame = frame(jpeg)
        val size = input.size()
        fun read(offset: Long, length: Int, order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteBuffer {
            require(offset >= 0 && length >= 0 && offset <= size - length) { "Truncated DNG metadata" }
            return ByteBuffer.allocate(length).order(order).apply {
                while (hasRemaining()) check(input.read(this, offset + position()) > 0)
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
        fun uint(value: Int) = value.toLong() and 0xffffffffL
        val original = uint(header.getInt(4))
        require(original >= 8)
        val count = read(original, 2, order).short.toInt() and 65535
        require(count in 1..1023) { "Invalid DNG directory length" }
        val directory = read(original + 2, count * 12 + 4, order)
        val fields = sortedMapOf<Int, ByteArray>()
        repeat(count) {
            val entry = ByteArray(12).also(directory::get)
            val tag = ByteBuffer.wrap(entry).order(order).short.toInt() and 65535
            require(fields.put(tag, entry) == null) { "Invalid DNG directory" }
        }
        val next = directory.int
        val children = fields[330]?.let {
            val entry = ByteBuffer.wrap(it).order(order)
            val length = uint(entry.getInt(4))
            require(entry.getShort(2).toInt() == 4 && length in 1..30) { "Invalid DNG directory chain" }
            if (length == 1L) listOf(entry.getInt(8)) else {
                val values = read(uint(entry.getInt(8)), length.toInt() * 4, order)
                List(length.toInt()) { values.int }
            }
        }.orEmpty()
        require(children.all { uint(it) in 8 until size && uint(it) != original }) { "Invalid DNG directory chain" }

        fun entry(tag: Int, type: Int, count: Int, value: Int) = ByteBuffer.allocate(12).order(order).apply {
            putShort(tag.toShort()); putShort(type.toShort()); putInt(count); putInt(value)
        }.array()
        val rootOffset = (size + 3) and -4L
        val rootCount = fields.size + if (330 in fields) 0 else 1
        val childrenOffset = rootOffset + 2 + rootCount * 12 + 4
        val previewOffset = childrenOffset + (children.size + 1) * 4
        val preview = sortedMapOf<Int, ByteArray>()
        fun long(tag: Int, value: Int) { preview[tag] = entry(tag, 4, 1, value) }
        fun short(tag: Int, vararg values: Int) {
            val data = ByteBuffer.allocate(4).order(order).apply { values.forEach { putShort(it.toShort()) } }
            preview[tag] = entry(tag, 3, values.size, data.getInt(0))
        }
        long(254, 1)
        long(256, frame.width); long(257, frame.height)
        preview[258] = entry(258, 3, 3, 0)
        short(259, 7); short(262, 6)
        long(273, 0); short(277, 3); long(278, frame.height); long(279, jpeg.size)
        short(284, 1); short(530, frame.horizontalSampling, frame.verticalSampling); short(531, 1)
        long(50970, 2)
        val bitsOffset = previewOffset + 2 + preview.size * 12 + 4
        val jpegOffset = (bitsOffset + 6 + 3) and -4L
        require(jpegOffset + jpeg.size <= Int.MAX_VALUE) { "Invalid DNG size" }
        preview[258] = entry(258, 3, 3, bitsOffset.toInt())
        long(273, jpegOffset.toInt())
        fields[330] = entry(330, 4, children.size + 1,
            if (children.isEmpty()) previewOffset.toInt() else childrenOffset.toInt())
        val metadata = ByteBuffer.allocate((jpegOffset - rootOffset).toInt()).order(order)
        metadata.putShort(fields.size.toShort())
        fields.values.forEach(metadata::put)
        metadata.putInt(next)
        children.forEach(metadata::putInt)
        metadata.putInt(previewOffset.toInt())
        metadata.putShort(preview.size.toShort())
        preview.values.forEach(metadata::put)
        metadata.putInt(0)
        repeat(3) { metadata.putShort(8) }
        metadata.position(0)
        fun write(offset: Long, bytes: ByteBuffer) {
            while (bytes.hasRemaining()) check(output.write(bytes, offset + bytes.position()) > 0)
        }
        write(rootOffset, metadata)
        write(jpegOffset, ByteBuffer.wrap(jpeg))
        output.force(true)
        // Publish the new tree only after its complete preview is durable.
        write(4, ByteBuffer.allocate(4).order(order).putInt(rootOffset.toInt()).apply { flip() })
        output.force(true)
    }

    private data class Frame(val width: Int, val height: Int, val horizontalSampling: Int, val verticalSampling: Int)

    private fun frame(jpeg: ByteArray): Frame {
        fun byte(at: Int) = jpeg[at].toInt() and 255
        fun word(at: Int) = (byte(at) shl 8) or byte(at + 1)
        require(jpeg.size >= 4 && word(0) == 0xffd8 && word(jpeg.size - 2) == 0xffd9) { "Unable to create DNG preview" }
        var at = 2
        while (at + 4 <= jpeg.size) {
            require(byte(at) == 255) { "Unable to create DNG preview" }
            val marker = byte(at + 1)
            val length = word(at + 2)
            require(length >= 2 && at + 2L + length <= jpeg.size) { "Unable to create DNG preview" }
            if (marker == 0xc0) {
                require(length == 17 && byte(at + 4) == 8 && byte(at + 9) == 3 &&
                    byte(at + 10) == 1 && byte(at + 13) == 2 && byte(at + 16) == 3 &&
                    byte(at + 14) == 0x11 && byte(at + 17) == 0x11) { "Unable to create DNG preview" }
                val width = word(at + 7)
                val height = word(at + 5)
                val horizontal = byte(at + 11) ushr 4
                val vertical = byte(at + 11) and 15
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
                    horizontal in 1..2 && vertical in 1..2) { "Unable to create DNG preview" }
                return Frame(width, height, horizontal, vertical)
            }
            require(marker != 0xda && marker != 0xd9) { "Unable to create DNG preview" }
            at += 2 + length
        }
        error("Unable to create DNG preview")
    }
}
