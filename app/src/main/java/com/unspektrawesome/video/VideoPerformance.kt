package com.unspektrawesome.video

import com.unspektrawesome.camera.session.RawFrameConsumer

data class VideoPerformance(
    val queuedFrames: Int = 0,
    val queueCapacity: Int = RawFrameConsumer.MAX_QUEUED_FRAMES,
    val encoderQueuedFrames: Long? = null,
    val encoderPressurePercent: Double? = null,
    val encoderInputCapacity: Int? = null,
    val encodedFramesPerSecond: Double? = null,
    val droppedFrames: Long = 0,
    val fileSizeBytes: Long? = null,
    val memoryUsedBytes: Long = 0,
    val memoryCapacityBytes: Long = 0,
) {
    val memoryUsedPercent: Double? get() = memoryCapacityBytes.takeIf { it > 0 }?.let {
        memoryUsedBytes.coerceIn(0, it) * 100.0 / it
    }
}
