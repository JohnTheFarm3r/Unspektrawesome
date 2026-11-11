package com.unspektrawesome.diagnostics

import android.graphics.Bitmap
import com.unspektrawesome.gallery.Raw16Input
import com.unspektrawesome.preview.PersistedCapture
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class SampleSummary(val count: Int, val minimum: Int, val maximum: Int, val mean: Double, val nonzero: Int) {
    companion object {
        fun collect(width: Int, height: Int, sample: (Int, Int) -> Int): SampleSummary {
            require(width > 0 && height > 0)
            var minimum = Int.MAX_VALUE
            var maximum = Int.MIN_VALUE
            var sum = 0L
            var nonzero = 0
            var count = 0
            for (gy in 0 until minOf(height, 32)) for (gx in 0 until minOf(width, 32)) {
                val x = if (width <= 32) gx else (gx.toLong() * (width - 2) / 31).toInt().and(-2) + (gx and 1)
                val y = if (height <= 32) gy else (gy.toLong() * (height - 2) / 31).toInt().and(-2) + (gy and 1)
                val value = sample(x, y)
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
                sum += value
                if (value != 0) nonzero++
                count++
            }
            return SampleSummary(count, minimum, maximum, sum.toDouble() / count, nonzero)
        }
    }
}

internal object ImageDiagnostics {
    fun input(id: String, capture: PersistedCapture, pixels: ByteBuffer) {
        InternalLogRecorder.event("CaptureInput", "id=$id capturedAt=${capture.capturedAtMillis} revision=${capture.revision} " +
            "output=${capture.width}x${capture.height} iso=${capture.metadata.sensitivityIso} exposureNs=${capture.metadata.exposureTimeNs} " +
            "spektra=${capture.metadata.spektra} shadingBytes=${capture.shading?.remaining() ?: 0}")
        raw(id, pixels, capture.raw)
    }

    fun raw(id: String, pixels: ByteBuffer, parameters: ByteBuffer) {
        runCatching {
            val raw = parameters.duplicate().order(ByteOrder.nativeOrder())
            val width = raw.getInt(0)
            val height = raw.getInt(4)
            val reader = Raw16Input(pixels, width, height, raw.getInt(16), raw.getInt(20))
            val summary = SampleSummary.collect(width, height, reader::sample)
            val message = "id=$id raster=${width}x$height stride=${raw.getInt(16)} format=${raw.getInt(20)} " +
                "cfa=${raw.getInt(24)} black=${(0..3).map { raw.getFloat(32 + it * 4) }} " +
                "white=${(0..3).map { raw.getFloat(48 + it * 4) }} bytes=${pixels.remaining()} samples=$summary"
            if (summary.nonzero == 0) InternalLogRecorder.warning("RawSamples", "$message all_samples_zero=true")
            else InternalLogRecorder.event("RawSamples", message)
        }.onFailure { InternalLogRecorder.error("RawSamples", "id=$id sampling_failed", it) }
    }

    fun output(id: String, bitmap: Bitmap) {
        runCatching {
            val summary = SampleSummary.collect(bitmap.width, bitmap.height) { x, y ->
                val color = bitmap.getPixel(x, y)
                maxOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
            }
            val message = "id=$id output=${bitmap.width}x${bitmap.height} sampled_max_rgb=$summary"
            if (summary.maximum <= 1) InternalLogRecorder.warning("OutputSamples", "$message near_black_samples=true; dark scenes can also produce this result")
            else InternalLogRecorder.event("OutputSamples", message)
        }.onFailure { InternalLogRecorder.error("OutputSamples", "id=$id sampling_failed", it) }
    }
}
