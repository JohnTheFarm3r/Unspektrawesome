package com.unspektrawesome.camera.session

import android.graphics.Rect
import android.hardware.camera2.params.Face
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chooses the face that focus and exposure follow and keeps the choice steady between frames.
 * Boxes are fractions of the sensor active array: left, top, right, bottom.
 *
 * [face] follows every camera result so the viewfinder box keeps up with movement.
 * [region] changes only after noticeable movement so focus does not restart on every frame.
 */
class FaceTracker {
    var face: FloatArray? = null
        private set
    var region: FloatArray? = null
        private set
    private var lastSeenNs = 0L
    private var lastRegionChangeNs = 0L

    /** Returns [FACE_CHANGED] and [REGION_CHANGED] flags for this camera result. */
    fun update(faces: List<FloatArray>, nowNs: Long): Int {
        val current = face
        if (faces.isEmpty()) {
            if (current == null || nowNs - lastSeenNs < HOLD_NS) return 0
            face = null
            val changed = region != null
            region = null
            lastRegionChangeNs = nowNs
            return FACE_CHANGED or if (changed) REGION_CHANGED else 0
        }
        lastSeenNs = nowNs
        val largest = faces.maxBy(::area)
        // Stay on the same person unless someone clearly larger takes over.
        val next = current?.let { previous ->
            faces.filter { overlap(it, previous) > .3f && area(it) >= area(largest) * .6f }
                .maxByOrNull { overlap(it, previous) }
        } ?: largest
        var result = 0
        if (current == null || !current.contentEquals(next)) {
            face = next.copyOf()
            result = FACE_CHANGED
        }
        val followed = region
        if (followed == null || movedEnough(followed, next) && nowNs - lastRegionChangeNs >= MIN_INTERVAL_NS) {
            region = next.copyOf()
            lastRegionChangeNs = nowNs
            result = result or REGION_CHANGED
        }
        return result
    }

    fun reset(): Boolean {
        val had = face != null
        face = null
        region = null
        lastSeenNs = 0L
        lastRegionChangeNs = 0L
        return had
    }

    companion object {
        const val FACE_CHANGED = 1
        const val REGION_CHANGED = 2
        private const val HOLD_NS = 700_000_000L
        private const val MIN_INTERVAL_NS = 150_000_000L

        /** Converts camera faces to active-array fractions, dropping empty or invalid boxes. */
        @JvmStatic
        fun normalize(faces: Array<Face>?, activeArray: Rect?): List<FloatArray> {
            if (faces.isNullOrEmpty() || activeArray == null || activeArray.width() <= 0 || activeArray.height() <= 0) return emptyList()
            val width = activeArray.width().toFloat()
            val height = activeArray.height().toFloat()
            return faces.mapNotNull { face ->
                val bounds = face.bounds ?: return@mapNotNull null
                val box = floatArrayOf(
                    ((bounds.left - activeArray.left) / width).coerceIn(0f, 1f),
                    ((bounds.top - activeArray.top) / height).coerceIn(0f, 1f),
                    ((bounds.right - activeArray.left) / width).coerceIn(0f, 1f),
                    ((bounds.bottom - activeArray.top) / height).coerceIn(0f, 1f),
                )
                box.takeIf { it[2] - it[0] > .005f && it[3] - it[1] > .005f }
            }
        }

        private fun area(box: FloatArray) = (box[2] - box[0]) * (box[3] - box[1])

        private fun overlap(a: FloatArray, b: FloatArray): Float {
            val width = min(a[2], b[2]) - max(a[0], b[0])
            val height = min(a[3], b[3]) - max(a[1], b[1])
            if (width <= 0f || height <= 0f) return 0f
            val shared = width * height
            return shared / (area(a) + area(b) - shared)
        }

        private fun movedEnough(previous: FloatArray, next: FloatArray): Boolean {
            val dx = abs((previous[0] + previous[2]) - (next[0] + next[2])) / 2f
            val dy = abs((previous[1] + previous[3]) - (next[1] + next[3])) / 2f
            val ratio = (next[2] - next[0]) / max(previous[2] - previous[0], 1e-4f)
            return dx > .03f || dy > .03f || ratio < .8f || ratio > 1.25f
        }
    }
}
