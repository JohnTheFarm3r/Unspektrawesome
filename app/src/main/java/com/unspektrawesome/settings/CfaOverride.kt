package com.unspektrawesome.settings

import com.unspektrawesome.camera.CfaPattern
import com.unspektrawesome.camera.SensorMetadata
import org.json.JSONObject

enum class CfaLayout(val label: String, val blockSize: Int) {
    BAYER("Off (Bayer)", 1),
    QUAD("Quad Bayer (2×2)", 2),
    NONA("Nona Bayer (3×3)", 3),
    HEXADECA("Hexadeca Bayer (4×4)", 4),
}

enum class CfaOrder(val label: String, val pattern: CfaPattern?) {
    SENSOR("Sensor color order", null),
    RGGB("RGGB", CfaPattern.RGGB),
    GRBG("GRBG", CfaPattern.GRBG),
    GBRG("GBRG", CfaPattern.GBRG),
    BGGR("BGGR", CfaPattern.BGGR),
}

data class CfaOverride(val layout: CfaLayout = CfaLayout.BAYER, val order: CfaOrder = CfaOrder.SENSOR) {
    fun pattern(sensor: SensorMetadata): CfaPattern = order.pattern ?: sensor.cfaPattern

    fun toJson() = JSONObject().put("layout", layout.name).put("order", order.name)

    companion object {
        fun fromJson(json: JSONObject?) = CfaOverride(
            runCatching { CfaLayout.valueOf(json?.optString("layout").orEmpty()) }.getOrDefault(CfaLayout.BAYER),
            runCatching { CfaOrder.valueOf(json?.optString("order").orEmpty()) }.getOrDefault(CfaOrder.SENSOR),
        )
    }
}

fun CameraPreferences.cfaOverride(): CfaOverride =
    lensProfiles.firstOrNull { it.id == activeLensProfileId }?.cfaOverride ?: CfaOverride()
