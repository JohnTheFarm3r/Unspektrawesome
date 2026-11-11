package com.unspektrawesome.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CaptureResult
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import com.unspektrawesome.camera.session.RawHardwareFrame
import com.unspektrawesome.preview.PersistedCapture
import com.unspektrawesome.calibration.DcpHueSatTransform
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.diagnostics.ImageDiagnostics
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

internal class DeferredCaptureWriter(private val context: Context) {
    fun save(capture: PersistedCapture, frame: RawHardwareFrame, characteristics: CameraCharacteristics): GalleryShot {
        return save(capture, frame.packedRawBytes,
            requireNotNull(frame.captureResult) { "Camera did not provide DNG capture metadata" }, characteristics)
    }

    fun save(capture: PersistedCapture, packedRawBytes: ByteBuffer, result: CaptureResult? = null,
        characteristics: CameraCharacteristics? = null,
        id: String = "${capture.capturedAtMillis}-${UUID.randomUUID()}", forGallery: Boolean = true): GalleryShot {
        val store = GalleryStore.get(context)
        val existing = store.shots.value.firstOrNull { it.id == id }
        if (existing != null && existing.rawBytes > 0) {
            check(existing.hasRaw) { "Original DNG was removed" }
            context.contentResolver.openFileDescriptor(existing.rawUri, "r")?.use {
                check(it.statSize == existing.rawBytes) { "Original DNG size changed" }
            } ?: error("Original DNG is unavailable")
            return existing
        }
        val width = capture.raw.getInt(0)
        val height = capture.raw.getInt(4)
        val region = capture.rawStorageRegion()
        requireSpace(context, region.width().toLong() * region.height() * 2)
        val saved = capture.croppedRaw16()
        val size = Size(region.width(), region.height())
        val compatibilityWriter = result == null || characteristics == null ||
            com.unspektrawesome.vulkan.params.RawMosaic(saved.raw).blockSize > 1 ||
            saved.raw.getInt(24) != com.unspektrawesome.camera.CfaPattern.fromCamera2(
                characteristics[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]).ordinal || region.width() != width || region.height() != height || CalibrationDngWriter.needed(characteristics, size)
        InternalLogRecorder.event("DeferredCapture", "saving_dng id=$id raster=$size writer=${if (compatibilityWriter) "compatibility" else "android"}")
        ImageDiagnostics.input(id, capture, packedRawBytes)
        val pixels = Raw16Input(packedRawBytes, width, height, capture.raw.getInt(16), capture.raw.getInt(20), region)
        val thumbnail = thumbnail(saved, pixels)
        try {
            val creator = if (compatibilityWriter) null else DngCreator(requireNotNull(characteristics), requireNotNull(result))
            creator.use {
                val description = JSONObject().put("unspektrawesomeRecipe", 1).put("capture", saved.toJson())
                    .put("captureId", id).put("packageName", context.packageName).toString()
                creator?.setOrientation(exifOrientation(capture))
                capture.metadata.location?.let { creator?.setLocation(it.toLocation()) }
                creator?.setDescription(description)
                creator?.setThumbnail(thumbnail)
                val resolver = context.contentResolver
                val uri = existing?.rawUri ?: requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Unspektrawesome-$id.dng")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Unspektrawesome/RAW")
                    put(MediaStore.Images.Media.DATE_TAKEN, capture.capturedAtMillis)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                })) { "Unable to create original DNG" }
                try {
                    if (existing == null) store.insert(id, uri, saved)
                    else store.update(id, ExportState.SAVING_RAW)
                } catch (error: Throwable) {
                    if (existing == null) resolver.delete(uri, null, null)
                    throw error
                }
                try {
                    resolver.openFileDescriptor(uri, "wt")?.use { descriptor ->
                        java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                            if (creator != null) creator.writeInputStream(output, size, pixels, 0)
                            else CalibrationDngWriter.write(saved, pixels, thumbnail, exifOrientation(capture), description, output)
                            output.fd.sync()
                        }
                    } ?: error("Unable to open original DNG")
                    // The queue thumbnail is oriented independently of the sensor-oriented DNG thumbnail.
                    val oriented = orientThumbnail(thumbnail, capture)
                    try {
                        runCatching { store.thumbnail(id).outputStream().use { oriented.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                    } finally { if (oriented !== thumbnail) oriented.recycle() }
                    val preview = thumbnail(saved, pixels, DngJpegPreview.MAX_DIMENSION, upscale = false)
                    val jpeg = try {
                        java.io.ByteArrayOutputStream().also {
                            check(preview.compress(Bitmap.CompressFormat.JPEG, DngJpegPreview.JPEG_QUALITY, it)) { "Unable to create DNG preview" }
                        }.toByteArray()
                    } finally { preview.recycle() }
                    val bytes = store.finishRaw(uri, saved, jpeg)
                    store.update(id, if (forGallery) ExportState.WAITING else ExportState.PROCESSING, rawBytes = bytes)
                    InternalLogRecorder.event("DeferredCapture", "dng_saved id=$id bytes=$bytes")
                } catch (error: Throwable) {
                    InternalLogRecorder.error("DeferredCapture", "dng_save_failed id=$id", error)
                    store.update(id, ExportState.FAILED, "Unable to finish saving RAW: ${error.message.orEmpty()}")
                    throw error
                }
                return store.shots.value.first { it.id == id }
            }
        } finally { thumbnail.recycle() }
    }

    private fun thumbnail(capture: PersistedCapture, pixels: Raw16Input, maximumDimension: Int = 256, upscale: Boolean = true): Bitmap {
        val width = capture.raw.getInt(0)
        val height = capture.raw.getInt(4)
        val left = (capture.geometry.getFloat(0) * width).roundToInt() and -2
        val top = (capture.geometry.getFloat(4) * height).roundToInt() and -2
        val cropWidth = (capture.geometry.getFloat(8) * width).roundToInt().coerceAtLeast(2)
        val cropHeight = (capture.geometry.getFloat(12) * height).roundToInt().coerceAtLeast(2)
        val scale = (maximumDimension.toFloat() / maxOf(cropWidth, cropHeight)).let { if (upscale) it else minOf(1f, it) }
        val w = (cropWidth * scale).toInt().coerceAtLeast(1)
        val h = (cropHeight * scale).toInt().coerceAtLeast(1)
        val channels = when (capture.raw.getInt(24)) {
            1 -> intArrayOf(1, 0, 3, 2)
            2 -> intArrayOf(1, 3, 0, 2)
            3 -> intArrayOf(3, 1, 2, 0)
            else -> intArrayOf(0, 1, 2, 3)
        }
        val sampler = if (com.unspektrawesome.vulkan.params.RawMosaic(capture.raw).blockSize > 1)
            com.unspektrawesome.vulkan.params.RemosaicSampler(capture.raw, pixels::sample) else null
        val colors = IntArray(w * h)
        val rgb = FloatArray(3)
        val bayer = FloatArray(4)
        val black = FloatArray(4) { capture.raw.getFloat(32 + it * 4) }
        val range = FloatArray(4) { (capture.raw.getFloat(48 + it * 4) - black[it]).coerceAtLeast(1f) }
        val gains = FloatArray(3) { capture.color.getFloat(it * 4) }
        val matrix = FloatArray(9) { capture.color.getFloat(16 + it % 3 * 16 + it / 3 * 4) }
        val columns = IntArray(w) { (left + ((it / scale).toInt() and -2)).coerceIn(0, (width - 2) and -2) }
        val dcp = DcpHueSatTransform.from(capture.color, capture.shading)
        val calibrated = if (dcp != null) FloatArray(3) else null
        fun linear(row: Int) = (rgb[0] * matrix[row * 3]).toDouble() +
            (rgb[1] * matrix[row * 3 + 1]).toDouble() + (rgb[2] * matrix[row * 3 + 2]).toDouble()
        for (y in 0 until h) {
            val sy = (top + ((y / scale).toInt() and -2)).coerceIn(0, (height - 2) and -2)
            for (x in 0 until w) {
                val sx = columns[x]
                repeat(4) { index ->
                    val channel = channels[index]
                    val value = sampler?.sample(sx + index % 2, sy + index / 2) ?: pixels.sample(sx + index % 2, sy + index / 2)
                    bayer[channel] = ((value - black[channel]) / range[channel]).coerceAtLeast(0f)
                }
                rgb[0] = bayer[0] * gains[0]
                rgb[1] = (bayer[1] + bayer[2]) * .5f * gains[1]
                rgb[2] = bayer[3] * gains[2]
                if (dcp != null && calibrated != null) {
                    repeat(3) { calibrated[it] = linear(it).toFloat() }
                    dcp.apply(calibrated, calibrated)
                }
                fun output(row: Int): Int {
                    val linear = (calibrated?.get(row)?.toDouble() ?: linear(row)).coerceIn(0.0, 1.0)
                    return (linear.pow(1.0 / 2.2) * 255).toInt()
                }
                colors[y * w + x] = Color.rgb(output(0), output(1), output(2))
            }
        }
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun orientThumbnail(bitmap: Bitmap, capture: PersistedCapture): Bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply {
            postRotate(capture.geometry.getInt(32) * 90f)
            if (capture.geometry.getInt(36) != 0) postScale(-1f, 1f)
        }, true)

    fun writeQueueThumbnail(capture: PersistedCapture, bytes: ByteBuffer, destination: java.io.File) {
        val raw = Raw16Input(bytes, capture.raw.getInt(0), capture.raw.getInt(4), capture.raw.getInt(16), capture.raw.getInt(20), capture.rawStorageRegion())
        val sensorThumbnail = thumbnail(capture.croppedRaw16(), raw)
        val oriented = orientThumbnail(sensorThumbnail, capture)
        try {
            val file = android.util.AtomicFile(destination)
            val stream = file.startWrite()
            try {
                check(oriented.compress(Bitmap.CompressFormat.JPEG, 85, stream))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        } finally {
            if (oriented !== sensorThumbnail) oriented.recycle()
            sensorThumbnail.recycle()
        }
    }

    private fun exifOrientation(capture: PersistedCapture): Int {
        val rotation = capture.geometry.getInt(32)
        val mirrored = capture.geometry.getInt(36) != 0
        return if (mirrored) intArrayOf(2, 5, 4, 7)[rotation] else intArrayOf(1, 6, 3, 8)[rotation]
    }

    companion object {
        fun requireSpace(context: Context, rawBytes: Long) {
            require(StatFs(context.filesDir.absolutePath).availableBytes > rawBytes + 128L * 1024 * 1024) {
                "Storage is nearly full. Free space before taking another RAW photo."
            }
        }
    }
}
