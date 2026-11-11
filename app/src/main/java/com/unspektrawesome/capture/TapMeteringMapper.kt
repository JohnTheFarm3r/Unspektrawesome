package com.unspektrawesome.capture

data class SensorMeteringPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
            "Sensor metering point must be normalized"
        }
    }
}

object TapMeteringMapper {
    fun map(
        geometry: FrameGeometrySnapshot,
        normalizedViewfinderX: Float,
        normalizedViewfinderY: Float,
    ): SensorMeteringPoint {
        val source = geometry.sourceUv(normalizedViewfinderX, normalizedViewfinderY)
        val active = geometry.calibrationArray
        return SensorMeteringPoint(
            ((source.x - active.left) / active.width).coerceIn(0f, 1f),
            ((source.y - active.top) / active.height).coerceIn(0f, 1f),
        )
    }
}
