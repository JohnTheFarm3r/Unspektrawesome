package com.unspektrawesome.calibration

import com.unspektrawesome.camera.ColorCalibration
import com.unspektrawesome.camera.RawColorCalibration
import com.unspektrawesome.settings.LensColorSettings
import com.unspektrawesome.vulkan.params.WhiteBalanceGains

class ResolvedLensColor(
    val settings: LensColorSettings,
    private val deviceCalibration: ColorCalibration,
    val dcp: DcpProfile?,
) {
    init { require(!settings.dcpEnabled || dcp != null) { "Import the missing DCP again or turn custom DCP off in this lens profile." } }

    fun matrix(gains: WhiteBalanceGains): FloatArray = when {
        settings.dcpEnabled -> RawColorCalibration.afterWhiteBalance(requireNotNull(dcp).calibration, gains)
        settings.matrixEnabled -> RawColorCalibration.afterWhiteBalance(deviceCalibration, gains)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    val activeDcp: DcpProfile? get() = dcp.takeIf { settings.dcpEnabled }
    val description: String get() = if (settings.dcpEnabled) "DCP: ${settings.dcpName ?: "Custom"} (color calibration)"
        else if (settings.matrixEnabled) "Device color matrix" else "Color matrix off"

    fun illuminantWeight(gains: WhiteBalanceGains): Float = activeDcp?.let {
        RawColorCalibration.illuminantWeight(it.calibration, gains).toFloat()
    } ?: 1f
}
