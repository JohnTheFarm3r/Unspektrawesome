package com.unspektrawesome.calibration

import com.unspektrawesome.camera.ColorCalibration
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DcpProfile internal constructor(
    val name: String,
    val cameraModel: String?,
    val copyright: String?,
    val calibration: ColorCalibration,
    val dualIlluminant: Boolean,
    val hueDivisions: Int,
    val saturationDivisions: Int,
    val valueDivisions: Int,
    val hueSatEncoding: Int,
    private val hueSatMap1: FloatArray?,
    private val hueSatMap2: FloatArray?,
    val hasLookTable: Boolean,
    val hasToneCurve: Boolean,
) {
    val hasHueSatMap: Boolean get() = hueSatMap1 != null
    val tableEntries: Int get() = hueDivisions * saturationDivisions * valueDivisions
    val tableBytes: Int get() = if (!hasHueSatMap) 0 else (2 + tableEntries * (if (hueSatMap2 == null) 1 else 2)) * 16
    private val packedTables: ByteBuffer by lazy { packTables() }

    fun writeTables(target: ByteBuffer, byteOffset: Int) {
        require(hasHueSatMap && byteOffset >= 0 && byteOffset % 16 == 0 && target.capacity() - byteOffset >= tableBytes)
        target.duplicate().apply { limit(byteOffset + tableBytes); position(byteOffset); put(packedTables.duplicate()) }
    }

    private fun packTables(): ByteBuffer {
        val target = ByteBuffer.allocateDirect(tableBytes).order(ByteOrder.nativeOrder())
        fun entry(index: Int, a: Float, b: Float, c: Float, d: Float = 0f) {
            val offset = index * 16
            target.putFloat(offset, a); target.putFloat(offset + 4, b)
            target.putFloat(offset + 8, c); target.putFloat(offset + 12, d)
        }
        entry(0, hueDivisions.toFloat(), saturationDivisions.toFloat(), valueDivisions.toFloat(), hueSatEncoding.toFloat())
        entry(1, 2f, if (hueSatMap2 == null) 2f else (2 + tableEntries).toFloat(), tableBytes / 16f)
        fun table(values: FloatArray, start: Int) {
            repeat(tableEntries) { i -> entry(start + i, values[i * 3], values[i * 3 + 1], values[i * 3 + 2]) }
        }
        table(requireNotNull(hueSatMap1), 2)
        hueSatMap2?.let { table(it, 2 + tableEntries) }
        return target.asReadOnlyBuffer()
    }
}
