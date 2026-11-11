package com.unspektrawesome.vulkan

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exchanges a frame token and the original metering shader's EV without applying digital gain. */
class ExposureMeterBuffer {
    val bytes: ByteBuffer = ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder())

    fun request(frameId: Long, method: Int, x: Float = -1f, y: Float = -1f) {
        require(method in 0..1)
        bytes.putLong(0, frameId)
        bytes.putInt(8, method)
        bytes.putFloat(12, x)
        bytes.putFloat(16, y)
        bytes.putLong(24, 0)
        bytes.putDouble(32, Double.NaN)
    }

    val resultFrameId: Long get() = bytes.getLong(24)
    val correctionEv: Double get() = bytes.getDouble(32)

    companion object { const val SIZE_BYTES = 40 }
}
