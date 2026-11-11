package com.unspektrawesome.gallery

import com.unspektrawesome.camera.Rect2d
import java.io.InputStream
import java.nio.ByteBuffer

/** Unpacks one row at a time, keeping full-resolution RAW copies off the Java heap. */
internal class Raw16Input(
    private val pixels: ByteBuffer,
    private val width: Int,
    private val height: Int,
    private val stride: Int,
    private val format: Int,
    private val region: Rect2d = Rect2d(0, 0, width, height),
) : InputStream() {
    private val row = ByteArray(region.width() * 2)
    private var rowIndex = 0
    private var position = row.size

    init {
        require(width > 0 && height > 0 && format in 0..2)
        require(region.left >= 0 && region.top >= 0 && region.right <= width && region.bottom <= height &&
            region.width() > 0 && region.height() > 0)
        val rowBytes = when (format) { 1 -> ((width + 3) / 4) * 5; 2 -> ((width + 1) / 2) * 3; else -> width * 2 }
        require(stride >= rowBytes && pixels.limit().toLong() >= (height - 1L) * stride + rowBytes)
    }

    fun sample(localX: Int, localY: Int): Int {
        val x = localX + region.left
        val start = (localY + region.top) * stride
        fun byte(index: Int) = pixels.get(index).toInt() and 255
        return when (format) {
            1 -> {
                val base = start + x / 4 * 5
                (byte(base + x % 4) shl 2) or ((byte(base + 4) ushr (x % 4 * 2)) and 3)
            }
            2 -> {
                val base = start + x / 2 * 3
                (byte(base + x % 2) shl 4) or ((byte(base + 2) ushr (x % 2 * 4)) and 15)
            }
            else -> byte(start + x * 2) or (byte(start + x * 2 + 1) shl 8)
        }
    }

    private fun ensureRow(): Boolean {
        if (position < row.size) return true
        if (rowIndex == region.height()) return false
        if (format == 0) {
            pixels.duplicate().apply { position((rowIndex + region.top) * stride + region.left * 2); get(row) }
        } else {
            for (x in 0 until region.width()) {
                val value = sample(x, rowIndex)
                row[x * 2] = value.toByte()
                row[x * 2 + 1] = (value ushr 8).toByte()
            }
        }
        rowIndex++
        position = 0
        return true
    }

    override fun read(): Int = if (ensureRow()) row[position++].toInt() and 255 else -1

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length)
        if (length == 0) return 0
        if (!ensureRow()) return -1
        val count = minOf(length, row.size - position)
        row.copyInto(target, offset, position, position + count)
        position += count
        return count
    }
}
