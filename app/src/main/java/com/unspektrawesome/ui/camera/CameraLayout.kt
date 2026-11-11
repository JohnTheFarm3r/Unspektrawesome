package com.unspektrawesome.ui.camera

internal data class CameraArea(val left: Float, val top: Float, val width: Float, val height: Float)

internal object CameraLayout {
    fun overlayArea(bounds: CameraArea, width: Float, height: Float, obstacles: List<CameraArea>,
        gap: Float, alignEnd: Boolean): CameraArea? {
        if (bounds.width <= 0f || bounds.height <= 0f || width <= 0f || height <= 0f) return null
        val scale = minOf(1f, bounds.width / width, bounds.height / height)
        val fittedWidth = width * scale
        val fittedHeight = height * scale
        val left = if (alignEnd) bounds.left + bounds.width - fittedWidth else bounds.left
        val bottom = bounds.top + bounds.height
        val blocked = obstacles.filter {
            it.width > 0f && it.height > 0f && left < it.left + it.width + gap && left + fittedWidth > it.left - gap &&
                it.top < bottom + gap && it.top + it.height > bounds.top - gap
        }.sortedBy { it.top }
        var top = bounds.top
        var bestTop = top
        var bestHeight = 0f
        for (obstacle in blocked) {
            val space = (minOf(bottom, obstacle.top - gap) - top).coerceAtLeast(0f)
            if (space >= fittedHeight) return CameraArea(left, top, fittedWidth, fittedHeight)
            if (space > bestHeight) { bestTop = top; bestHeight = space }
            top = maxOf(top, obstacle.top + obstacle.height + gap).coerceAtMost(bottom)
        }
        val remaining = bottom - top
        if (remaining >= fittedHeight) return CameraArea(left, top, fittedWidth, fittedHeight)
        if (remaining > bestHeight) { bestTop = top; bestHeight = remaining }
        if (bestHeight <= 0f) return null
        val reducedWidth = fittedWidth * bestHeight / fittedHeight
        return CameraArea(if (alignEnd) bounds.left + bounds.width - reducedWidth else bounds.left,
            bestTop, reducedWidth, bestHeight)
    }

    fun fittedViewfinder(bounds: CameraArea, aspect: Float): CameraArea =
        fittedViewfinder(bounds.width, bounds.height, aspect).let {
            it.copy(left = bounds.left + it.left, top = bounds.top + it.top)
        }

    fun panelCapacity(width: Float, height: Float, aspect: Float, actions: Float, bottomGap: Float = 12f): Float {
        require(width > 0 && height > 0 && aspect > 0 && actions >= 0 && bottomGap >= 0)
        val available = (height - actions - bottomGap).coerceAtLeast(0f)
        val previewMinimum = minOf(width / aspect, 180f, available * .45f)
        return (available - previewMinimum).coerceAtLeast(0f)
    }

    fun fittedViewfinder(width: Float, height: Float, aspect: Float): CameraArea {
        val availableHeight = height.coerceAtLeast(1f)
        val imageHeight = minOf(availableHeight, width / aspect)
        val imageWidth = imageHeight * aspect
        return CameraArea((width - imageWidth) / 2, (availableHeight - imageHeight) / 2,
            imageWidth, imageHeight)
    }

    fun bottomAlignedViewfinder(width: Float, bottom: Float, aspect: Float): CameraArea =
        fittedViewfinder(width, bottom, aspect).let { it.copy(top = bottom - it.height) }

}
