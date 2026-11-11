package com.unspektrawesome.burst

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SharedMemory
import com.unspektrawesome.capture.JpegMediaStoreWriter
import com.unspektrawesome.preview.PersistedCapture
import com.unspektrawesome.vulkan.VulkanRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class BurstRendering(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val renderer by lazy {
        VulkanRenderer(java.io.File(applicationContext.cacheDir, "vulkan-pipelines/burst").apply { parentFile?.mkdirs() }.absolutePath)
    }
    private var used = false

    fun render(capture: PersistedCapture, encoded: ByteArray, preview: Boolean): Bitmap {
        val (width, height) = BurstContainer.dimensions(encoded)
        val savedWidth = capture.raw.getInt(0); val savedHeight = capture.raw.getInt(4)
        require(if (preview) width <= 768 && height <= 768 else width == savedWidth && height == savedHeight)
        require(capture.raw.remaining() == com.unspektrawesome.vulkan.params.RawShaderParameters.SIZE_BYTES &&
            capture.color.remaining() == com.unspektrawesome.vulkan.params.RawColorShaderParameters.SIZE_BYTES &&
            capture.geometry.remaining() == com.unspektrawesome.vulkan.params.GeometryShaderParameters.SIZE_BYTES &&
            capture.film.remaining() == com.unspektrawesome.spektra.SpektraFilmParams.STRUCT_SIZE)
        val raw = copy(capture.raw).apply { putInt(0, width); putInt(4, height); putInt(16, width * 2); putInt(20, 0) }
        val geometry = copy(capture.geometry)
        val scale = if (preview) minOf(width.toFloat() / savedWidth, height.toFloat() / savedHeight) else 1f
        val outputWidth = (capture.width * scale).toInt().coerceAtLeast(2)
        val outputHeight = (capture.height * scale).toInt().coerceAtLeast(2)
        raw.putInt(8, outputWidth); raw.putInt(12, outputHeight)
        geometry.putInt(40, outputWidth); geometry.putInt(44, outputHeight)
        geometry.putFloat(48, outputWidth.toFloat()); geometry.putFloat(52, outputHeight.toFloat())
        if (preview) repeat(4) { index ->
            val ratio = if (index % 2 == 0) width.toDouble() / savedWidth else height.toDouble() / savedHeight
            geometry.putInt(80 + index * 4, (geometry.getInt(80 + index * 4) * ratio).toInt() and -2)
        }
        val memory = SharedMemory.create("burst-render", width * height * 2)
        try {
            val pixels = memory.mapReadWrite().order(ByteOrder.nativeOrder())
            try {
                RawFrameCompression.decode(encoded, pixels)
                val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                try {
                    synchronized(PersistedCapture.exportLock) {
                        used = true
                        check(renderer.captureAmazeToBitmap(pixels, raw, capture.color, geometry,
                            capture.film, capture.shading, bitmap, capture.timeSeconds)) { "Unable to preview burst" }
                    }
                    return bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            } finally { SharedMemory.unmap(pixels) }
        } finally { memory.close() }
    }

    fun export(reader: BurstContainer.Reader, frame: Int): Uri {
        val capture = reader.capture(frame)
        val bitmap = render(capture, reader.pixels(frame, false), false)
        try { return JpegMediaStoreWriter(applicationContext.contentResolver).write(bitmap, capture.metadata,
            capturedAtMillis = capture.capturedAtMillis) } finally { bitmap.recycle() }
    }

    override fun close() { if (used) renderer.close() }

    private fun copy(bytes: ByteBuffer): ByteBuffer = ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.nativeOrder())
        .apply { put(bytes.duplicate()); flip() }
}
