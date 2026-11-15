package com.unspektrawesome.capture

object FaceGeometry {
    /**
     * Maps a point given as a fraction of the sensor active array to a fraction of the
     * viewfinder surface. This is the inverse of [TapMeteringMapper].
     */
    fun toSurface(geometry: FrameGeometrySnapshot, sensorX: Float, sensorY: Float): NormalizedPoint {
        val calibration = geometry.calibrationArray
        val crop = geometry.activeArray
        val sourceU = (calibration.left + sensorX * calibration.width - crop.left) / crop.width
        val sourceV = (calibration.top + sensorY * calibration.height - crop.top) / crop.height
        var u: Float
        val v: Float
        when (geometry.transform.orientationDegrees) {
            90 -> { u = 1f - sourceV; v = sourceU }
            180 -> { u = 1f - sourceU; v = 1f - sourceV }
            270 -> { u = sourceV; v = 1f - sourceU }
            else -> { u = sourceU; v = sourceV }
        }
        if (geometry.transform.mirrorHorizontal) u = 1f - u
        return NormalizedPoint(u, v)
    }

    /** Maps a face box to the viewfinder surface as left, top, right, bottom fractions. */
    fun boxToSurface(geometry: FrameGeometrySnapshot, box: FloatArray): FloatArray {
        val first = toSurface(geometry, box[0], box[1])
        val second = toSurface(geometry, box[2], box[3])
        return floatArrayOf(minOf(first.x, second.x), minOf(first.y, second.y),
            maxOf(first.x, second.x), maxOf(first.y, second.y))
    }
}
