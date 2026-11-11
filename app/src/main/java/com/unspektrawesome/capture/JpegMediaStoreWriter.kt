package com.unspektrawesome.capture

import android.content.ContentResolver
import android.content.ContentUris
import android.content.SharedPreferences
import android.content.ContentValues
import android.graphics.Bitmap
import com.unspektrawesome.camera.Size2d
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.util.Locale

class JpegMediaStoreWriter(
    private val contentResolver: ContentResolver,
    private val exportReceipts: SharedPreferences? = null,
) {
    fun findLatestPhoto(): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val query = android.os.Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Images.Media.MIME_TYPE} = ? AND " +
                    "${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.IS_TRASHED} = 0")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("$PICTURES_DIRECTORY/$ALBUM_NAME/", "image/jpeg"))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), query, null)?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    fun write(
        image: ProcessedImage,
        metadata: CaptureMetadata,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
        capturedAtMillis: Long = System.currentTimeMillis(),
        captureId: String? = null,
    ): Uri = publish(metadata, jpegQuality, capturedAtMillis, captureId, imageSize = Size2d(image.width, image.height)) { uri ->
        writePixels(uri, image, jpegQuality)
    }

    fun write(
        bitmap: Bitmap,
        metadata: CaptureMetadata,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
        capturedAtMillis: Long = System.currentTimeMillis(),
        captureId: String? = null,
    ): Uri = publish(metadata, jpegQuality, capturedAtMillis, captureId, imageSize = Size2d(bitmap.width, bitmap.height)) { uri ->
        contentResolver.openOutputStream(uri, "w")?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)) {
                throw IOException("Android JPEG encoder failed")
            }
        } ?: throw IOException("Unable to open MediaStore image for writing")
    }

    fun writePng(file: java.io.File, metadata: CaptureMetadata, capturedAtMillis: Long, captureId: String): Uri {
        val size = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, size)
        require(size.outWidth > 0 && size.outHeight > 0) { "Unable to save panorama" }
        return publish(metadata, DEFAULT_JPEG_QUALITY, capturedAtMillis, captureId, "image/png", "png",
            Size2d(size.outWidth, size.outHeight)) { uri ->
            contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Unable to save panorama")
        }
    }

    private fun publish(metadata: CaptureMetadata, jpegQuality: Int, capturedAtMillis: Long,
        captureId: String?, mimeType: String = "image/jpeg", extension: String = "jpg",
        imageSize: Size2d, writeImage: (Uri) -> Unit): Uri {
        require(jpegQuality in 1..100) { "JPEG quality must be in 1..100" }
        require(captureId == null || exportReceipts != null) { "Queued exports require a recovery store" }
        val displayName = "${PhotoFileNames.baseName(capturedAtMillis, metadata.utcOffsetSeconds)}.$extension"
        if (captureId != null) findSavedCapture(captureId)?.let { return it }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, captureId?.let { "Unspektrawesome-$it.$extension" } ?: displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$PICTURES_DIRECTORY/$ALBUM_NAME")
            put(MediaStore.Images.Media.DATE_TAKEN, capturedAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore rejected the image")
        try {
            writeImage(uri)
            writeExif(uri, metadata, capturedAtMillis, captureId, mimeType, imageSize)
            metadata.spektra?.takeIf { mimeType == "image/jpeg" }?.let { shot ->
                contentResolver.openFileDescriptor(uri, "rw")?.use { file ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { input ->
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { output ->
                            com.unspektrawesome.spektra.SpektraProfileJpeg.write(input.channel, output.channel, shot)
                        }
                    }
                } ?: throw IOException("Unable to open MediaStore image for EXIF writing")
            }
            contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                android.system.Os.fsync(descriptor.fileDescriptor)
            } ?: throw IOException("Unable to flush the exported photo to storage")
            // Persist the URI before publishing the readable filename so recovery can find it.
            if (captureId != null && !requireNotNull(exportReceipts).edit()
                    .putString(captureId, uri.toString()).commit()) {
                throw IOException("Unable to record the exported photo for recovery")
            }
            val published = contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Unable to publish the exported photo")
            return uri
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    fun forgetCapture(captureId: String) {
        exportReceipts?.edit()?.remove(captureId)?.apply()
    }

    fun findSavedCapture(captureId: String): Uri? {
        exportReceipts?.getString(captureId, null)?.let { saved ->
            val uri = Uri.parse(saved)
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (cursor.getInt(0) == 0) return uri
                    contentResolver.delete(uri, null, null)
                }
            }
            forgetCapture(captureId)
        }
        // Also recover older exports and interrupted writes that still have their temporary name.
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        contentResolver.query(collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media.DISPLAY_NAME} IN (?, ?) AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf("Unspektrawesome-$captureId.jpg", "Unspektrawesome-$captureId.png", "$PICTURES_DIRECTORY/$ALBUM_NAME/"), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                if (cursor.getInt(1) == 0) return uri
                contentResolver.delete(uri, null, null)
            }
        }
        return null
    }

    private fun writePixels(uri: Uri, image: ProcessedImage, jpegQuality: Int) {
        val pixels = image.rgba8888.duplicate().apply {
            position(0)
            limit(image.width * image.height * ProcessedImage.BYTES_PER_PIXEL)
        }
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(pixels)
            contentResolver.openOutputStream(uri, "w")?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)) {
                    throw IOException("Android JPEG encoder failed")
                }
            } ?: throw IOException("Unable to open MediaStore image for writing")
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeExif(uri: Uri, metadata: CaptureMetadata, capturedAtMillis: Long, captureId: String?, mimeType: String,
        imageSize: Size2d) {
        contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            val offset = metadata.utcOffsetSeconds?.let(java.time.ZoneOffset::ofTotalSeconds)
                ?: java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(capturedAtMillis))
            val date = java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
                .format(java.time.Instant.ofEpochMilli(capturedAtMillis).atOffset(offset))
            val offsetText = if (offset.totalSeconds == 0) "+00:00" else offset.id
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetText)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetText)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetText)
            val milliseconds = String.format(Locale.US, "%03d", Math.floorMod(capturedAtMillis, 1000L))
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, milliseconds)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, milliseconds)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, milliseconds)
            exif.setAttribute(ExifInterface.TAG_DATETIME, date)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
            exif.setAttribute(ExifInterface.TAG_MAKE, metadata.identity?.manufacturer ?: android.os.Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, metadata.identity?.model ?: android.os.Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, metadata.identity?.software ?: "Unspektrawesome")
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, metadata.description())
            metadata.lensProfile?.let {
                val profile = org.json.JSONObject().put("lensProfile", it.toJson()).toString()
                // JSON escapes preserve profile names in EXIF readers that only write ASCII comments.
                val comment = buildString {
                    profile.forEach { c ->
                        if (c.code < 128) append(c) else append('\\').append('u').append(c.code.toString(16).padStart(4, '0'))
                    }
                }
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
                exif.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, it.cropFactor.toString())
            }
            if (mimeType == "image/png") {
                metadata.spektra?.let { exif.setAttribute(ExifInterface.TAG_XMP, com.unspektrawesome.spektra.SpektraProfileXmp.encode(it)) }
            }
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, imageSize.width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, imageSize.height.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, imageSize.width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, imageSize.height.toString())
            captureId?.let { exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, imageUniqueId(it)) }
            exif.setAttribute(ExifInterface.TAG_LENS_MODEL, metadata.lensModel)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            metadata.location?.let { exif.setGpsInfo(it.toLocation()) }
            metadata.exposureTimeNs?.takeIf { it > 0L }?.let {
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1_000_000_000.0).toString())
            }
            metadata.sensitivityIso?.takeIf { it > 0 }?.let {
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
            }
            metadata.focalLengthMm?.takeIf { it.isFinite() && it > 0f }?.let {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000f).toInt()}/1000")
            }
            metadata.aperture?.takeIf { it.isFinite() && it > 0f }?.let {
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
            }
            metadata.equivalentFocalLengthMm?.times(metadata.lensProfile?.cropFactor ?: 1f)
                ?.takeIf { it.isFinite() && it >= 1f && it < 65536f }?.let {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, kotlin.math.round(it).toInt().toString())
            }
            exif.saveAttributes()
        } ?: throw IOException("Unable to open MediaStore image for EXIF writing")
    }

    companion object {
        internal fun imageUniqueId(captureId: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(captureId.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

        const val DEFAULT_JPEG_QUALITY = 100
        private val PICTURES_DIRECTORY = Environment.DIRECTORY_DCIM
        private const val ALBUM_NAME = "Unspektrawesome"
    }
}
