package com.unspektrawesome.gallery

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Size
import com.unspektrawesome.capture.CaptureIdentity
import com.unspektrawesome.preview.PersistedCapture
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Retains CFA originals when Android's DNG writer cannot use the RAW size or calibration. */
internal object CalibrationDngWriter {
    fun needed(characteristics: CameraCharacteristics, size: Size): Boolean {
        if (!supportsSize(characteristics, size)) return true
        if (characteristics[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1] == null ||
            characteristics[CameraCharacteristics.SENSOR_COLOR_TRANSFORM1] == null ||
            characteristics[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1] == null ||
            characteristics[CameraCharacteristics.SENSOR_FORWARD_MATRIX1] == null) return true
        return characteristics[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2] != null &&
            (characteristics[CameraCharacteristics.SENSOR_COLOR_TRANSFORM2] == null ||
                characteristics[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2] == null ||
                characteristics[CameraCharacteristics.SENSOR_FORWARD_MATRIX2] == null)
    }

    private fun supportsSize(characteristics: CameraCharacteristics, size: Size): Boolean {
        fun matches(pixelArray: Size?, activeArray: Rect?): Boolean = pixelArray != null && activeArray != null &&
            (size == pixelArray || (size.width == activeArray.width() && size.height == activeArray.height()))

        // DngCreator infers maximum-resolution mode from the raster dimensions.
        if (Build.VERSION.SDK_INT >= 31 && characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true) {
            val pixelArray = characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION]
            val activeArray = characteristics[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION]
            if (pixelArray == null || activeArray == null) return false
            if (matches(pixelArray, activeArray)) return true
        }
        return matches(characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE],
            characteristics[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE])
    }

    fun write(capture: PersistedCapture, pixels: InputStream, thumbnail: Bitmap, orientation: Int,
        description: String, output: OutputStream) {
        val width = capture.raw.getInt(0)
        val height = capture.raw.getInt(4)
        val rawBytes = width.toLong() * height * 2
        require(width > 0 && height > 0 && rawBytes <= Int.MAX_VALUE)
        val levels = DngRawLevels.from(capture.raw)
        val metadata = capture.metadata
        val identity = metadata.identity ?: CaptureIdentity.current
        val date = Instant.ofEpochMilli(capture.capturedAtMillis).atOffset(ZoneOffset.ofTotalSeconds(metadata.utcOffsetSeconds ?: 0))
        val dateText = date.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT))
        val mosaic = com.unspektrawesome.vulkan.params.RawMosaic(capture.raw)
        val cfa = ByteArray(mosaic.period * mosaic.period) { mosaic.sourceColor(it % mosaic.period, it / mosaic.period).toByte() }
        val forward = DoubleArray(9) { i -> (0..2).sumOf { XYZ_FROM_SRGB[i / 3 * 3 + it] * capture.color.getFloat(16 + i % 3 * 16 + it * 4) } }
        val inverse = inverse(forward)
        val neutral = DoubleArray(3) { 1.0 / capture.color.getFloat(it * 4) }
        val color = DoubleArray(9) { inverse[it] * neutral[it / 3] }
        val raw = Ifd().apply {
            longs(254, 0); longs(256, width); longs(257, height); shorts(258, 16); shorts(259, 1); shorts(262, 32803)
            text(270, description); text(271, identity.manufacturer); text(272, identity.model)
            longs(273, 0); shorts(274, orientation); shorts(277, 1); longs(278, height); longs(279, rawBytes.toInt())
            shorts(284, 1); text(305, identity.software); text(306, dateText)
            shorts(33421, mosaic.period, mosaic.period); bytes(33422, cfa)
            longs(34665, 0)
            if (metadata.location != null) longs(34853, 0)
            bytes(50706, byteArrayOf(1, 4, 0, 0)); bytes(50707, byteArrayOf(1, 3, 0, 0))
            text(50708, "${identity.manufacturer} ${identity.model} ${metadata.lensModel}")
            bytes(50710, byteArrayOf(0, 1, 2)); shorts(50711, 1); shorts(50713, levels.period, levels.period)
            rationals(50714, levels.black)
            longs(50717, levels.white)
            rationals(50718, doubleArrayOf(1.0, 1.0)); longs(50719, 0, 0); longs(50720, width, height)
            rationals(50721, color, signed = true); rationals(50728, neutral); shorts(50778, 23)
            longs(50829, 0, 0, height, width); rationals(50964, forward, signed = true)
        }
        val exif = Ifd().apply {
            metadata.exposureTimeNs?.let { rationals(33434, doubleArrayOf(it / 1e9)) }
            metadata.aperture?.let { rationals(33437, doubleArrayOf(it.toDouble())) }
            metadata.sensitivityIso?.let { shorts(34855, it.coerceIn(0, 65535)) }
            bytes(36864, "0232".toByteArray(), type = 7)
            text(36867, dateText); text(36881, if (date.offset == ZoneOffset.UTC) "+00:00" else date.offset.id)
            text(37521, String.format(Locale.ROOT, "%03d", capture.capturedAtMillis % 1000))
            metadata.focalLengthMm?.let { rationals(37386, doubleArrayOf(it.toDouble())) }
            text(42036, metadata.lensModel)
        }
        val gps = metadata.location?.let { fix -> Ifd().apply {
            bytes(0, byteArrayOf(2, 3, 0, 0))
            text(1, if (fix.latitude >= 0) "N" else "S"); rationals(2, coordinate(fix.latitude))
            text(3, if (fix.longitude >= 0) "E" else "W"); rationals(4, coordinate(fix.longitude))
            fix.altitudeMeters?.let { bytes(5, byteArrayOf(if (it >= 0) 0 else 1)); rationals(6, doubleArrayOf(abs(it))) }
            val utc = Instant.ofEpochMilli(fix.timeMillis).atOffset(ZoneOffset.UTC)
            rationals(7, doubleArrayOf(utc.hour.toDouble(), utc.minute.toDouble(), utc.second + utc.nano / 1e9))
            text(29, utc.format(DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.ROOT)))
            fix.accuracyMeters?.let { rationals(31, doubleArrayOf(it.toDouble())) }
        } }
        val thumbBytes = ByteArray(thumbnail.width * thumbnail.height * 3)
        val thumbPixels = IntArray(thumbnail.width * thumbnail.height)
        thumbnail.getPixels(thumbPixels, 0, thumbnail.width, 0, 0, thumbnail.width, thumbnail.height)
        thumbPixels.forEachIndexed { i, pixel -> repeat(3) { thumbBytes[i * 3 + it] = (pixel ushr (16 - it * 8)).toByte() } }
        val thumb = Ifd().apply {
            longs(254, 1); longs(256, thumbnail.width); longs(257, thumbnail.height); shorts(258, 8, 8, 8)
            shorts(259, 1); shorts(262, 2); longs(273, 0); shorts(274, orientation); shorts(277, 3)
            longs(278, thumbnail.height); longs(279, thumbBytes.size); shorts(284, 1)
        }
        val directories = listOfNotNull(raw, exif, gps, thumb)
        var offset = 8
        directories.forEach { it.offset = offset; offset += 2 + it.fields.size * 12 + 4 }
        raw.longs(34665, exif.offset)
        gps?.let { raw.longs(34853, it.offset) }
        directories.forEach { directory -> directory.fields.values.forEach { field ->
            if (field.data.size > 4) { field.offset = offset; offset += (field.data.size + 3) and -4 }
        } }
        require(offset <= 32_000_000) { "DNG metadata is too large" }
        raw.longs(273, offset + thumbBytes.size)
        thumb.longs(273, offset)
        val header = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0x4949); header.putShort(42); header.putInt(8)
        directories.forEach { directory ->
            header.position(directory.offset); header.putShort(directory.fields.size.toShort())
            directory.fields.forEach { (tag, field) ->
                header.putShort(tag.toShort()); header.putShort(field.type.toShort()); header.putInt(field.count)
                if (field.data.size <= 4) header.put(field.data.copyOf(4)) else {
                    header.putInt(field.offset)
                    header.duplicate().apply { position(field.offset); put(field.data) }
                }
            }
            header.putInt(if (directory === raw) thumb.offset else 0)
        }
        output.write(header.array()); output.write(thumbBytes)
        check(pixels.copyTo(output, 64 * 1024) == rawBytes) { "Incomplete original RAW data" }
    }

    private class Field(val type: Int, val count: Int, val data: ByteArray, var offset: Int = 0)
    private class Ifd {
        var offset = 0
        val fields = sortedMapOf<Int, Field>()
        fun bytes(tag: Int, values: ByteArray, type: Int = 1) { fields[tag] = Field(type, values.size, values) }
        fun text(tag: Int, text: String) = bytes(tag, (text + '\u0000').toByteArray(Charsets.UTF_8), 2)
        fun shorts(tag: Int, vararg values: Int) { fields[tag] = Field(3, values.size,
            ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()) }
        fun longs(tag: Int, vararg values: Int) { fields[tag] = Field(4, values.size,
            ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()) }
        fun rationals(tag: Int, values: DoubleArray, signed: Boolean = false) { fields[tag] = Field(if (signed) 10 else 5, values.size,
            ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { value ->
                require(value.isFinite() && abs(value) < Int.MAX_VALUE && (signed || value >= 0)) { "Invalid DNG calibration value" }
                val denominator = minOf(1_000_000.0, Int.MAX_VALUE / maxOf(abs(value), 1.0)).toInt().coerceAtLeast(1)
                putInt((value * denominator).roundToInt()); putInt(denominator)
            } }.array()) }
    }
    private fun coordinate(value: Double): DoubleArray {
        val degrees = floor(abs(value)); val minutes = floor((abs(value) - degrees) * 60)
        return doubleArrayOf(degrees, minutes, ((abs(value) - degrees) * 60 - minutes) * 60)
    }
    private fun inverse(m: DoubleArray): DoubleArray {
        val c = doubleArrayOf(m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3])
        val determinant = m[0] * c[0] + m[1] * c[3] + m[2] * c[6]
        require(determinant.isFinite() && abs(determinant) > 1e-12) { "Invalid DNG color calibration" }
        return DoubleArray(9) { c[it] / determinant }
    }
    private val XYZ_FROM_SRGB = doubleArrayOf(.43606574, .38515147, .14307845, .22249319, .71688705, .06061976, .01392390, .09708129, .71409941)
}
