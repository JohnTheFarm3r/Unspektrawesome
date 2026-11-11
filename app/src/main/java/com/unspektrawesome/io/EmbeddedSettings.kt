package com.unspektrawesome.io

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object EmbeddedSettings {
    private const val MAGIC = 0x5553504b
    private const val VERSION: Byte = 1

    // USPK, version, UTF-8 byte count, then a gzip member with its own CRC.
    fun encode(json: String, maximumBytes: Int): ByteArray {
        val source = json.toByteArray(Charsets.UTF_8)
        require(source.size in 1..maximumBytes)
        val compressed = ByteArrayOutputStream().also { target ->
            GZIPOutputStream(target).use { it.write(source) }
        }.toByteArray()
        return ByteBuffer.allocate(9 + compressed.size).putInt(MAGIC).put(VERSION)
            .putInt(source.size).put(compressed).array()
    }

    fun decode(bytes: ByteArray, maximumBytes: Int): String {
        require(bytes.size in 10..maximumBytes + 1024)
        val header = ByteBuffer.wrap(bytes)
        require(header.int == MAGIC && header.get() == VERSION)
        val length = header.int
        require(length in 1..maximumBytes)
        val result = GZIPInputStream(bytes.inputStream(9, bytes.size - 9)).use { it.readUpTo(length + 1) }
        require(result.size == length)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(result)).toString()
    }
}
