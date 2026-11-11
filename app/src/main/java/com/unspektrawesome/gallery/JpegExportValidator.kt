package com.unspektrawesome.gallery

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream

internal data class VerifiedJpeg(val uri: Uri, val bytes: Long, val generation: Long)

internal class JpegExportValidator(private val resolver: ContentResolver, private val owner: String) {
    fun verify(uri: Uri, width: Int, height: Int): VerifiedJpeg {
        require(width > 0 && height > 0) { "Expected export dimensions are missing" }
        val initial = published(uri)
        val actualBytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: error("JPEG is unavailable")
        check(actualBytes == initial.bytes && actualBytes >= ExportImageHealth.minimumBytes(width, height)) {
            "JPEG is unexpectedly small for its resolution"
        }
        resolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input, 64 * 1024).use { stream ->
                check(stream.read() == 0xff && stream.read() == 0xd8) { "File is not a JPEG" }
                val buffer = ByteArray(64 * 1024)
                var count = 2L
                var previous = -1
                var last = -1
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    previous = if (read > 1) buffer[read - 2].toInt() and 255 else last
                    last = buffer[read - 1].toInt() and 255
                    count += read
                }
                check(count == actualBytes && previous == 0xff && last == 0xd9) { "JPEG is incomplete" }
            }
        } ?: error("JPEG cannot be read")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            check(info.mimeType == "image/jpeg" && info.size.width == width && info.size.height == height) {
                "JPEG dimensions do not match the saved crop"
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
            decoder.setTargetSampleSize(maxOf(1, (maxOf(width, height) + 511) / 512))
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ExportImageHealth.concern(pixels)?.let { error(it) }
        } finally { bitmap.recycle() }
        check(initial == published(uri)) { "JPEG changed during verification" }
        return initial
    }

    fun unchanged(verified: VerifiedJpeg): Boolean = runCatching { published(verified.uri) == verified }.getOrDefault(false)

    private fun published(uri: Uri): VerifiedJpeg {
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "JPEG is outside photo storage" }
        return resolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE, MediaStore.Images.Media.GENERATION_MODIFIED,
            MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.IS_TRASHED, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)?.use {
            check(it.moveToFirst() && it.getInt(2) == 0 && it.getInt(3) == 0 && it.getString(4) == "image/jpeg" &&
                it.getString(5) == owner && it.getString(6) == "DCIM/Unspektrawesome/") { "JPEG has not been published or is unavailable" }
            VerifiedJpeg(uri, it.getLong(0), it.getLong(1))
        } ?: error("JPEG cannot be checked")
    }
}
