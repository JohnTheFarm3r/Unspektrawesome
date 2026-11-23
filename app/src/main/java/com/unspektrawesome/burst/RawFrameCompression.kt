package com.unspektrawesome.burst

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

internal object RawFrameCompression {
    init { System.loadLibrary("unspektra_archive") }
    external fun encode(source: ByteBuffer, width: Int, height: Int, stride: Int, format: Int, block: Int): Long
    external fun copy(source: ByteBuffer, destination: ByteBuffer, width: Int, height: Int, stride: Int, format: Int)
    external fun payload(handle: Long, preview: Boolean): ByteBuffer
    external fun release(handle: Long)
    /** Frees the tile buffers kept between frames for compressing RAW frames. */
    external fun releaseScratch()
    external fun decode(encoded: ByteArray, destination: ByteBuffer)
    external fun preview(encoded: ByteArray): ByteArray

    fun compressMetadata(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty())
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream(bytes.size / 4 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk))
            return output.toByteArray()
        } finally { deflater.end() }
    }

    fun decompressMetadata(bytes: ByteArray, maximum: Int): ByteArray {
        require(bytes.isNotEmpty() && maximum > 0)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val output = ByteArrayOutputStream(minOf(maximum, bytes.size * 4 + 64))
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                require(count > 0 || !inflater.needsInput() && !inflater.needsDictionary())
                require(output.size() + count <= maximum)
                output.write(chunk, 0, count)
            }
            require(inflater.remaining == 0)
            return output.toByteArray()
        } finally { inflater.end() }
    }
}
