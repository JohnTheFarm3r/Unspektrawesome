package com.unspektrawesome.burst

import android.content.Context
import android.os.SharedMemory
import com.unspektrawesome.gallery.DeferredCaptureWriter
import com.unspektrawesome.gallery.GalleryShot
import java.nio.ByteOrder

internal object BurstExportQueue {
    fun add(context: Context, reader: BurstContainer.Reader, frame: Int, id: String): GalleryShot {
        val capture = reader.capture(frame)
        val encoded = reader.pixels(frame, false)
        val (width, height) = BurstContainer.dimensions(encoded)
        require(width == capture.raw.getInt(0) && height == capture.raw.getInt(4))
        DeferredCaptureWriter.requireSpace(context, width.toLong() * height * 2)
        val memory = SharedMemory.create("burst-queue", width * height * 2)
        try {
            val pixels = memory.mapReadWrite().order(ByteOrder.nativeOrder())
            try {
                RawFrameCompression.decode(encoded, pixels)
                capture.raw.putInt(16, width * 2)
                capture.raw.putInt(20, 0)
                return DeferredCaptureWriter(context).save(capture, pixels, id = id)
            } finally { SharedMemory.unmap(pixels) }
        } finally { memory.close() }
    }
}
