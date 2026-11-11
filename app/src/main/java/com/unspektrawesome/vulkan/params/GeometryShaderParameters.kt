package com.unspektrawesome.vulkan.params

import com.unspektrawesome.capture.FrameGeometrySnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeometryShaderParameters private constructor(
    private val snapshot: FrameGeometrySnapshot,
) {
    fun toByteBuffer(): ByteBuffer = writeTo(
        ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder()),
    )

    fun writeTo(target: ByteBuffer): ByteBuffer {
        require(target.isDirect && target.capacity() >= SIZE_BYTES) {
            "Geometry parameter target must be a direct buffer of at least $SIZE_BYTES bytes"
        }
        val crop = snapshot.sourceCrop
        val active = snapshot.activeArray
        return target.order(ByteOrder.nativeOrder()).apply {
            putFloat(SOURCE_CROP_OFFSET, crop.left)
            putFloat(SOURCE_CROP_OFFSET + 4, crop.top)
            putFloat(SOURCE_CROP_OFFSET + 8, crop.width)
            putFloat(SOURCE_CROP_OFFSET + 12, crop.height)
            putFloat(ACTIVE_ARRAY_OFFSET, active.left)
            putFloat(ACTIVE_ARRAY_OFFSET + 4, active.top)
            putFloat(ACTIVE_ARRAY_OFFSET + 8, active.width)
            putFloat(ACTIVE_ARRAY_OFFSET + 12, active.height)
            putInt(TRANSFORM_OFFSET, snapshot.transform.orientationDegrees / 90)
            putInt(TRANSFORM_OFFSET + 4, if (snapshot.transform.mirrorHorizontal) 1 else 0)
            putInt(TRANSFORM_OFFSET + 8, snapshot.outputSize.width)
            putInt(TRANSFORM_OFFSET + 12, snapshot.outputSize.height)
            putFloat(TARGET_OFFSET, snapshot.targetViewport.width.toFloat())
            putFloat(TARGET_OFFSET + 4, snapshot.targetViewport.height.toFloat())
            putFloat(TARGET_OFFSET + 8, snapshot.targetAspect)
            putFloat(TARGET_OFFSET + 12, 1f / snapshot.targetAspect)
            val calibration = snapshot.calibrationArray
            putFloat(CALIBRATION_ARRAY_OFFSET, calibration.left)
            putFloat(CALIBRATION_ARRAY_OFFSET + 4, calibration.top)
            putFloat(CALIBRATION_ARRAY_OFFSET + 8, calibration.width)
            putFloat(CALIBRATION_ARRAY_OFFSET + 12, calibration.height)
            putInt(RAW_BOUNDS_OFFSET, snapshot.rawBounds.left)
            putInt(RAW_BOUNDS_OFFSET + 4, snapshot.rawBounds.top)
            putInt(RAW_BOUNDS_OFFSET + 8, snapshot.rawBounds.width())
            putInt(RAW_BOUNDS_OFFSET + 12, snapshot.rawBounds.height())
            putFloat(STABILIZATION_OFFSET, 0f)
            position(0)
            limit(SIZE_BYTES)
        }
    }

    companion object {
        const val SOURCE_CROP_OFFSET = 0
        const val ACTIVE_ARRAY_OFFSET = 16
        const val TRANSFORM_OFFSET = 32
        const val TARGET_OFFSET = 48
        const val CALIBRATION_ARRAY_OFFSET = 64
        const val RAW_BOUNDS_OFFSET = 80
        const val STABILIZATION_OFFSET = 96
        const val STABILIZATION_LENS_OFFSET = STABILIZATION_OFFSET + 32 + 33 * 48
        const val SIZE_BYTES = STABILIZATION_LENS_OFFSET + 48

        fun upgrade(buffer: ByteBuffer, rawWidth: Int, rawHeight: Int): ByteBuffer {
            if (buffer.remaining() == SIZE_BYTES) return buffer
            require(buffer.remaining() in listOf(64, 96, STABILIZATION_LENS_OFFSET)) { "Unsupported capture geometry" }
            val legacy = buffer.remaining() == 64
            val pinhole = buffer.remaining() == STABILIZATION_LENS_OFFSET
            return ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
                put(buffer.duplicate())
                if (pinhole) putFloat(STABILIZATION_OFFSET + 12, 0f)
                if (legacy) {
                    repeat(4) { putFloat(CALIBRATION_ARRAY_OFFSET + it * 4, getFloat(ACTIVE_ARRAY_OFFSET + it * 4)) }
                    putInt(RAW_BOUNDS_OFFSET, 0); putInt(RAW_BOUNDS_OFFSET + 4, 0)
                    putInt(RAW_BOUNDS_OFFSET + 8, rawWidth); putInt(RAW_BOUNDS_OFFSET + 12, rawHeight)
                }
                position(0); limit(SIZE_BYTES)
            }
        }

        fun from(snapshot: FrameGeometrySnapshot) = GeometryShaderParameters(snapshot)
    }
}
