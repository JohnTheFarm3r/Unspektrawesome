package com.unspektrawesome.ui.camera

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import com.unspektrawesome.preview.RgbHistogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal suspend fun sampleViewfinderHistogram(
    view: SurfaceView,
    surfaceAspectRatio: Float,
    cropAspectRatio: Float,
): RgbHistogram? {
    val surface = view.holder.surface
    val frame = Rect(view.holder.surfaceFrame)
    if (!view.isAttachedToWindow || !surface.isValid || frame.isEmpty ||
        !surfaceAspectRatio.isFinite() || surfaceAspectRatio <= 0f ||
        !cropAspectRatio.isFinite() || cropAspectRatio <= 0f) return null
    val native = CameraLayout.bottomAlignedViewfinder(frame.width().toFloat(), frame.height().toFloat(), surfaceAspectRatio)
    val photo = CameraLayout.fittedViewfinder(native, cropAspectRatio)
    val region = Rect(ceil(photo.left).toInt(), ceil(photo.top).toInt(),
        floor(photo.left + photo.width).toInt(), floor(photo.top + photo.height).toInt())
    if (!region.intersect(frame)) return null
    val scale = 256f / maxOf(region.width(), region.height())
    val width = (region.width() * scale).roundToInt().coerceAtLeast(1)
    val height = (region.height() * scale).roundToInt().coerceAtLeast(1)
    return withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // Copy the final surface before UI composition, including spektrafilm when it is visible.
            val result = suspendCoroutine { continuation ->
                try {
                    PixelCopy.request(surface, region, bitmap, { continuation.resume(it) }, Handler(Looper.getMainLooper()))
                } catch (_: IllegalArgumentException) {
                    continuation.resume(PixelCopy.ERROR_SOURCE_INVALID)
                }
            }
            // Wait for PixelCopy to finish before a canceled request releases its bitmap.
            coroutineContext.ensureActive()
            if (result != PixelCopy.SUCCESS) return@withContext null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            RgbHistogram.fromArgb(pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
