package com.unspektrawesome.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor

internal data class PhotoTile(val column: Int, val row: Int, val sample: Int) {
    fun region(width: Int, height: Int): Rect {
        val edge = 512 * sample
        return Rect((column * edge - sample).coerceAtLeast(0), (row * edge - sample).coerceAtLeast(0),
            ((column + 1) * edge + sample).coerceAtMost(width), ((row + 1) * edge + sample).coerceAtMost(height))
    }
}

internal class PhotoRegionSource private constructor(
    private val descriptor: ParcelFileDescriptor?,
    private val decoder: BitmapRegionDecoder?,
    private val bitmap: Bitmap?,
    orientation: Int,
) : Closeable {
    val width = decoder?.width ?: requireNotNull(bitmap).width
    val height = decoder?.height ?: requireNotNull(bitmap).height
    val orientedWidth = if (orientation in 5..8) height else width
    val orientedHeight = if (orientation in 5..8) width else height
    val orientationMatrix = Matrix().apply {
        val w = width.toFloat()
        val h = height.toFloat()
        setValues(when (orientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        })
    }
    private val inverseOrientation = Matrix().also { orientationMatrix.invert(it) }
    val previewSample = generateSequence(1) { it * 2 }.first { maxOf(width, height) / it <= 2048 }
    private val tiles = object : LruCache<PhotoTile, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: PhotoTile, value: Bitmap) = value.allocationByteCount
    }
    private var closed = false

    @Synchronized fun preview(): Bitmap = decode(Rect(0, 0, width, height), previewSample)

    @Synchronized fun tile(key: PhotoTile): Bitmap =
        tiles.get(key) ?: decode(key.region(width, height), key.sample).also { tiles.put(key, it) }

    private fun decode(region: Rect, sample: Int): Bitmap {
        check(!closed) { "Photo is closed" }
        if (bitmap != null) {
            val output = Bitmap.createBitmap((region.width() + sample - 1) / sample, (region.height() + sample - 1) / sample, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(output).drawBitmap(bitmap, region, Rect(0, 0, output.width, output.height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return output
        }
        return requireNotNull(requireNotNull(decoder).decodeRegion(region, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Unable to decode this JPEG." }
    }

    fun visibleTiles(viewport: PhotoViewport, transform: PhotoTransform): List<PhotoTile> {
        val sample = viewport.sampleSize(transform.zoom)
        if (sample >= previewSample) return emptyList()
        val scale = viewport.fitScale * transform.zoom
        val left = orientedWidth / 2f - (viewport.width / 2f + transform.x) / scale
        val top = orientedHeight / 2f - (viewport.height / 2f + transform.y) / scale
        val visible = RectF(left, top, left + viewport.width / scale, top + viewport.height / scale)
        inverseOrientation.mapRect(visible)
        if (!visible.intersect(0f, 0f, width.toFloat(), height.toFloat())) return emptyList()
        val edge = 512 * sample
        return buildList {
            for (row in floor(visible.top / edge).toInt() until ceil(visible.bottom / edge).toInt()) {
                for (column in floor(visible.left / edge).toInt() until ceil(visible.right / edge).toInt()) {
                    add(PhotoTile(column, row, sample))
                }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        tiles.evictAll()
        decoder?.recycle()
        descriptor?.close()
        bitmap?.recycle()
    }

    companion object {
        fun fromBitmap(bitmap: Bitmap) = PhotoRegionSource(null, null, bitmap, ExifInterface.ORIENTATION_NORMAL)

        @Suppress("DEPRECATION")
        fun open(resolver: ContentResolver, uri: Uri): PhotoRegionSource {
            val orientation = resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: error("Unable to open this photo.")
            val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) { "Unable to open this photo." }
            try {
                val decoder = requireNotNull(BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false))
                return PhotoRegionSource(descriptor, decoder, null, orientation)
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
        }
    }
}
