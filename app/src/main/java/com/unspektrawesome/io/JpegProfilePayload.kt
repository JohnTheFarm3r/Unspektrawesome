package com.unspektrawesome.io

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal object JpegProfilePayload {
    private val signature = "UnspektraProfile\u0000".toByteArray(Charsets.US_ASCII)
    private const val APP15 = 0xef
    const val MAX_HEADER_BYTES = 16 * 1024 * 1024

    fun read(source: InputStream): ByteArray? {
        val input = DataInputStream(source)
        require(input.readUnsignedShort() == 0xffd8)
        var consumed = 2
        var found: ByteArray? = null
        while (consumed < MAX_HEADER_BYTES) {
            require(input.readUnsignedByte() == 0xff)
            consumed++
            var marker: Int
            do { marker = input.readUnsignedByte(); consumed++ } while (marker == 0xff && consumed < MAX_HEADER_BYTES)
            if (marker == 0xda || marker == 0xd9) return found
            require(marker in 0xc0..0xfe && marker !in 0xd0..0xd8)
            val length = input.readUnsignedShort()
            require(length >= 2 && consumed + length <= MAX_HEADER_BYTES)
            val segment = ByteArray(length - 2)
            input.readFully(segment)
            consumed += length
            if (marker == APP15 && segment.startsWithSignature()) {
                require(found == null)
                found = segment.copyOfRange(signature.size, segment.size)
            }
        }
        throw IllegalArgumentException()
    }

    fun write(input: FileChannel, output: FileChannel, payload: ByteArray) {
        val segmentLength = 2 + signature.size + payload.size
        require(segmentLength <= 65535)
        require(read(input, 0, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        val originalSize = input.size()
        var insertion = 2L
        // Keep the encoder's JFIF and EXIF headers in their original order.
        while (insertion + 4 <= originalSize && insertion < MAX_HEADER_BYTES) {
            val header = read(input, insertion, 4)
            require(header[0] == 0xff.toByte())
            val marker = header[1].toInt() and 255
            if (marker !in 0xe0..0xef && marker != 0xfe) break
            val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            require(length >= 2 && insertion + 2 + length <= originalSize)
            if (marker == APP15) require(!read(input, insertion + 4, length - 2).startsWithSignature())
            insertion += 2 + length
        }
        require(insertion < MAX_HEADER_BYTES && insertion + 2 <= originalSize)
        val segment = ByteBuffer.allocate(2 + segmentLength).put(0xff.toByte()).put(APP15.toByte())
            .putShort(segmentLength.toShort()).put(signature).put(payload).array()
        var end = originalSize
        while (end > insertion) {
            val start = (end - 64 * 1024).coerceAtLeast(insertion)
            write(output, start + segment.size, read(input, start, (end - start).toInt()))
            end = start
        }
        write(output, insertion, segment)
    }

    private fun ByteArray.startsWithSignature(): Boolean = size >= signature.size &&
        signature.indices.all { this[it] == signature[it] }

    private fun read(input: FileChannel, offset: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) check(input.read(buffer, offset + buffer.position()) > 0)
        return buffer.array()
    }

    private fun write(output: FileChannel, offset: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) check(output.write(buffer, offset + buffer.position()) > 0)
    }
}
