package com.unspektrawesome.panorama

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

internal object PanoramaPng {
    fun write(raw: File, canvasWidth: Int, crop: IntArray, destination: File, checkpoint: () -> Unit = {}) {
        val (left, top, width, height) = crop
        require(width > 0 && height > 0 && left >= 0 && top >= 0 && left.toLong() + width <= canvasWidth)
        require((top.toLong() + height) * canvasWidth * 4 <= raw.length())
        FileOutputStream(destination).use { file ->
            val output = DataOutputStream(file.buffered(128 * 1024))
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = java.io.ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            chunk(output, "IHDR", header.toByteArray())
            chunk(output, "sRGB", byteArrayOf(0))
            val chunks = object : OutputStream() {
                val buffer = ByteArray(64 * 1024)
                var size = 0
                override fun write(value: Int) { buffer[size++] = value.toByte(); if (size == buffer.size) flush() }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    var start = offset; var remaining = count
                    while (remaining > 0) {
                        val amount = minOf(remaining, buffer.size - size)
                        bytes.copyInto(buffer, size, start, start + amount)
                        start += amount; remaining -= amount; size += amount
                        if (size == buffer.size) flush()
                    }
                }
                override fun flush() { if (size > 0) { chunk(output, "IDAT", buffer, size); size = 0 } }
            }
            val compressor = Deflater(3)
            try {
                val compressed = DeflaterOutputStream(chunks, compressor, 64 * 1024)
                val rgba = ByteArray(width * 4)
                val row = ByteArray(width * 3 + 1)
                row[0] = 1
                RandomAccessFile(raw, "r").use { input ->
                    repeat(height) { y ->
                        if (y % 64 == 0) checkpoint()
                        input.seek(((top.toLong() + y) * canvasWidth + left) * 4)
                        input.readFully(rgba)
                        for (x in 0 until width) for (channel in 0..2) {
                            val value = rgba[x * 4 + channel].toInt() and 255
                            val previous = if (x == 0) 0 else rgba[(x - 1) * 4 + channel].toInt() and 255
                            row[1 + x * 3 + channel] = (value - previous).toByte()
                        }
                        compressed.write(row)
                    }
                }
                compressed.finish(); chunks.flush()
            } finally { compressor.end() }
            chunk(output, "IEND", byteArrayOf())
            output.flush(); file.fd.sync()
        }
    }

    private fun chunk(output: DataOutputStream, name: String, bytes: ByteArray, size: Int = bytes.size) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(bytes, 0, size) }
        output.writeInt(size); output.write(type); output.write(bytes, 0, size); output.writeInt(crc.value.toInt())
    }
}
