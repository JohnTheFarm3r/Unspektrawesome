package com.unspektrawesome.calibration

import com.unspektrawesome.vulkan.params.LensShadingMapParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binding 4 holds the shading grid followed by optional DCP calibration tables. */
object RawCalibrationTables {
    fun dcpOffset(shading: LensShadingMapParameters?, dcp: DcpProfile?): Int =
        if (dcp?.hasHueSatMap == true) maxOf(1, shading?.texelCount ?: 0) else 0

    fun sizeBytes(shading: LensShadingMapParameters?, dcp: DcpProfile?): Int =
        maxOf(16, shading?.sizeBytes ?: 0) + (dcp?.tableBytes ?: 0)

    fun create(shading: LensShadingMapParameters?, dcp: DcpProfile?): ByteBuffer? =
        if (shading == null && dcp?.hasHueSatMap != true) null
        else writeTo(ByteBuffer.allocateDirect(sizeBytes(shading, dcp)), shading, dcp)

    fun writeTo(target: ByteBuffer, shading: LensShadingMapParameters?, dcp: DcpProfile?): ByteBuffer {
        val size = sizeBytes(shading, dcp)
        require(target.isDirect && target.capacity() >= size)
        target.clear()
        target.order(ByteOrder.nativeOrder())
        if (shading != null) shading.writeTo(target) else repeat(4) { target.putFloat(it * 4, 1f) }
        target.limit(size)
        if (dcp?.hasHueSatMap == true) dcp.writeTables(target, dcpOffset(shading, dcp) * 16)
        return target.apply { position(0) }
    }
}
