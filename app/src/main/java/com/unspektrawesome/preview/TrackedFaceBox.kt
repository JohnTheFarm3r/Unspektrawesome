package com.unspektrawesome.preview

import com.unspektrawesome.capture.FaceGeometry
import com.unspektrawesome.capture.FrameGeometrySnapshot

/** A followed face as fractions of the viewfinder surface. */
data class TrackedFaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        fun from(geometry: FrameGeometrySnapshot, face: FloatArray): TrackedFaceBox? {
            val box = FaceGeometry.boxToSurface(geometry, face)
            if (box.any { !it.isFinite() }) return null
            return TrackedFaceBox(box[0], box[1], box[2], box[3])
        }
    }
}
