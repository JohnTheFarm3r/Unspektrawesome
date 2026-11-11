package com.unspektrawesome.settings

import org.json.JSONObject

data class LensColorSettings(
    val matrixEnabled: Boolean = true,
    val dcpId: String? = null,
    val dcpName: String? = null,
    val dcpEnabled: Boolean = false,
    val lensShadingEnabled: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("matrix", matrixEnabled)
        put("dcpId", dcpId)
        put("dcpName", dcpName)
        put("dcpEnabled", dcpEnabled)
        put("lensShading", lensShadingEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject?) = LensColorSettings(
            matrixEnabled = json?.optBoolean("matrix", true) ?: true,
            dcpId = json?.optString("dcpId")?.takeIf { it.isNotBlank() },
            dcpName = json?.optString("dcpName")?.takeIf { it.isNotBlank() },
            dcpEnabled = json?.optBoolean("dcpEnabled", false) ?: false,
            lensShadingEnabled = json?.optBoolean("lensShading", true) ?: true,
        )
    }
}

fun CameraPreferences.lensColorSettings(): LensColorSettings =
    lensProfiles.firstOrNull { it.id == activeLensProfileId }?.color ?: LensColorSettings()
