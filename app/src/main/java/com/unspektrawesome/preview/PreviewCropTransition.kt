package com.unspektrawesome.preview

import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.capture.PhotoCrop

internal class PreviewCropTransition {
    data class Frame(
        val revision: Long,
        val crop: PhotoCrop,
        val target: FrameGeometrySnapshot,
        val geometry: FrameGeometrySnapshot,
    )

    var frame: Frame? = null
        private set
    private var revision = 0L

    fun update(crop: PhotoCrop, target: FrameGeometrySnapshot, processingLimit: Size2d, animate: Boolean = true): Frame {
        val previous = frame
        if (previous?.crop == crop && previous.target == target && (animate || previous.geometry == target)) return previous
        // Retain every visible pixel until the viewfinder finishes moving its borders.
        val geometry = previous?.geometry?.takeIf { animate && it.sourceSize == target.sourceSize && it.activeArray == target.activeArray &&
            it.rawBounds == target.rawBounds && it.calibrationArray == target.calibrationArray && it.transform == target.transform }
            ?.covering(target, processingLimit) ?: target
        return Frame(++revision, crop, target, geometry).also { frame = it }
    }

    fun complete(revision: Long) {
        frame?.takeIf { it.revision == revision }?.let { frame = it.copy(geometry = it.target) }
    }

    fun reset() { frame = null }
}
