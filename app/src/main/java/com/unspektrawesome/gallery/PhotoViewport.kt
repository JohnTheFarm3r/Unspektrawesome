package com.unspektrawesome.gallery

import kotlin.math.max
import kotlin.math.min

internal data class PhotoTransform(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun interpolate(target: PhotoTransform, fraction: Float) = PhotoTransform(
        zoom + (target.zoom - zoom) * fraction,
        x + (target.x - x) * fraction,
        y + (target.y - y) * fraction,
    )
}

internal data class PhotoViewport(val imageWidth: Int, val imageHeight: Int, val width: Int, val height: Int) {
    init {
        require(imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0)
    }

    val fitScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
    val maximumZoom = max(5f, 2f / fitScale)

    fun constrain(value: PhotoTransform): PhotoTransform {
        val zoom = value.zoom.coerceIn(1f, maximumZoom)
        val maxX = max(0f, (imageWidth * fitScale * zoom - width) / 2)
        val maxY = max(0f, (imageHeight * fitScale * zoom - height) / 2)
        return PhotoTransform(zoom,
            if (maxX == 0f) 0f else value.x.coerceIn(-maxX, maxX),
            if (maxY == 0f) 0f else value.y.coerceIn(-maxY, maxY))
    }

    fun transform(value: PhotoTransform, zoom: Float, focusX: Float, focusY: Float,
        panX: Float = 0f, panY: Float = 0f): PhotoTransform {
        val nextZoom = zoom.coerceIn(1f, maximumZoom)
        val ratio = nextZoom / value.zoom
        return constrain(PhotoTransform(nextZoom,
            value.x * ratio + (focusX - width / 2f) * (1f - ratio) + panX,
            value.y * ratio + (focusY - height / 2f) * (1f - ratio) + panY))
    }

    fun doubleTap(value: PhotoTransform, x: Float, y: Float): PhotoTransform =
        if (value.zoom > 1.01f) PhotoTransform()
        else transform(value, max(2.5f, 1f / fitScale), x, y)

    fun sampleSize(zoom: Float): Int {
        var sample = 1
        val scale = fitScale * zoom
        while (sample < 512 && scale * sample * 2 <= 1f) sample *= 2
        return sample
    }
}
