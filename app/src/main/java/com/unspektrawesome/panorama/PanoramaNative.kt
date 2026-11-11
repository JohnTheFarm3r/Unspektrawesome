package com.unspektrawesome.panorama

import android.graphics.Bitmap

internal object PanoramaNative {
    init { System.loadLibrary("unspektra_archive") }
    external fun align(previous: Bitmap, current: Bitmap, focal: Double, plane: Boolean = false): DoubleArray?
    external fun compose(source: Bitmap, file: String, width: Int, height: Int, focal: Double,
        transform: DoubleArray, originX: Int, originY: Int, direction: Int, blend: Boolean = true)
    external fun crop(file: String, width: Int, height: Int): IntArray
    external fun resize(source: Bitmap, output: Bitmap, rotationDegrees: Int = 0)
}

internal fun capturePanoramaPreview(width: Int, height: Int, rotationDegrees: Int, read: (Bitmap) -> Long): PanoramaPreviewFrame? {
    require(rotationDegrees in listOf(0, 90, 180, 270))
    val sideways = rotationDegrees % 180 != 0
    val source = Bitmap.createBitmap(if (sideways) height else width, if (sideways) width else height, Bitmap.Config.ARGB_8888)
    var retained = false
    try {
        val timestamp = read(source)
        if (timestamp < 0) throw java.io.IOException("Unable to save panorama")
        if (timestamp == 0L) return null
        if (rotationDegrees == 0) {
            retained = true
            return PanoramaPreviewFrame(source, timestamp)
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { PanoramaNative.resize(source, output, rotationDegrees) }
        catch (error: Throwable) { output.recycle(); throw error }
        return PanoramaPreviewFrame(output, timestamp)
    } finally { if (!retained) source.recycle() }
}
