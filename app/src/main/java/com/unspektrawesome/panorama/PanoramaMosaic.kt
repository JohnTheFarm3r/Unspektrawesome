package com.unspektrawesome.panorama

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal class PanoramaMosaic(private val directory: File, private val focal: Double) : AutoCloseable {
    private var file = File(directory, "tracking.rgba")
    var bounds: PanoramaBounds? = null
        private set
    private val planeFrames = mutableListOf<Pair<File, PanoramaTransform>>()

    fun append(bitmap: Bitmap, transform: PanoramaTransform, direction: Int): Bitmap {
        if (transform.plane) return appendPlane(bitmap, transform)
        val next = PanoramaBounds.frame(bitmap.width, bitmap.height, transform, focal).let { bounds?.union(it) ?: it }
        require(next.width in 1..12000 && next.height in 1..12000 && next.width.toLong() * next.height <= 12_000_000)
        if (bounds != next) {
            val temporary = File(directory, "tracking-next.rgba")
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(next.width.toLong() * next.height * 4)
                bounds?.let { old ->
                    RandomAccessFile(file, "r").use { input ->
                        val row = ByteArray(old.width * 4)
                        repeat(old.height) { y ->
                            input.readFully(row)
                            output.seek(((y + old.top - next.top).toLong() * next.width + old.left - next.left) * 4)
                            output.write(row)
                        }
                    }
                }
            }
            check(temporary.renameTo(file) || (file.delete() && temporary.renameTo(file)))
            bounds = next
        }
        PanoramaNative.compose(bitmap, file.absolutePath, next.width, next.height, focal, transform.values(), next.left, next.top, direction, blend = false)
        return readPreview(next)
    }

    private fun appendPlane(bitmap: Bitmap, transform: PanoramaTransform): Bitmap {
        val layout = panoramaPlaneLayout(planeFrames.map { it.second } + transform, bitmap.width, bitmap.height, focal)
        require(layout.fits)
        val next = layout.transforms.map { PanoramaBounds.frame(bitmap.width, bitmap.height, it, focal) }.reduce(PanoramaBounds::union)
        require(next.width in 1..12000 && next.height in 1..12000 && next.width.toLong() * next.height <= 12_000_000)
        val frame = File(directory, "tracking-plane-${planeFrames.size}.png")
        try { frame.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        catch (error: Throwable) { frame.delete(); throw error }
        planeFrames.add(frame to transform)
        RandomAccessFile(file, "rw").use { it.setLength(0); it.setLength(next.width.toLong() * next.height * 4) }
        planeFrames.forEachIndexed { index, entry ->
            val source = requireNotNull(BitmapFactory.decodeFile(entry.first.path))
            try {
                PanoramaNative.compose(source, file.path, next.width, next.height, focal, layout.transforms[index].values(),
                    next.left, next.top, 0, blend = false)
            } finally { source.recycle() }
        }
        bounds = next
        return readPreview(next)
    }

    private fun readPreview(next: PanoramaBounds): Bitmap {
        val pixels = ByteBuffer.allocate(next.width * next.height * 4)
        RandomAccessFile(file, "r").use { input -> while (pixels.hasRemaining()) check(input.channel.read(pixels) > 0) }
        pixels.flip()
        return Bitmap.createBitmap(next.width, next.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
    }

    override fun close() {
        file.delete(); File(directory, "tracking-next.rgba").delete()
        planeFrames.forEach { it.first.delete() }; planeFrames.clear()
    }
}
