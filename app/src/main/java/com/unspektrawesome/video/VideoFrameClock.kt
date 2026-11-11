package com.unspektrawesome.video

internal class VideoFrameClock(private val monotonicTime: () -> Long = System::nanoTime) {
    private var firstSensorNs: Long? = null
    private var firstMonotonicNs = 0L
    private var lastTimestampNs = 0L

    @Synchronized fun anchor(sensorTimestampNs: Long) {
        if (firstSensorNs == null) {
            firstSensorNs = sensorTimestampNs
            firstMonotonicNs = monotonicTime()
        }
    }

    @Synchronized fun timestamp(sensorTimestampNs: Long): Long {
        anchor(sensorTimestampNs)
        val timestamp = firstMonotonicNs + (sensorTimestampNs - firstSensorNs!!).coerceAtLeast(0L)
        lastTimestampNs = maxOf(timestamp, lastTimestampNs + 1)
        return lastTimestampNs
    }
}
