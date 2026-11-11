package com.unspektrawesome.capture

import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.settings.LensProfile
import com.unspektrawesome.settings.LensProfiles
import org.json.JSONArray
import org.json.JSONObject

data class LensProfileShotMetadata(
    val name: String,
    val rawFormat: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val cropFactor: Float,
    val settingsJson: String,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("rawFormat", rawFormat)
        .put("rawWidth", rawWidth).put("rawHeight", rawHeight).put("cropWidth", cropWidth).put("cropHeight", cropHeight)
        .put("cropFactor", cropFactor.toDouble()).put("settings", JSONObject(settingsJson))

    companion object {
        fun from(profile: LensProfile, output: RawOutput): LensProfileShotMetadata {
            val bounds = profile.rawEdgeCrop.bounds(output.size)
            return LensProfileShotMetadata(profile.displayName, output.format.name, output.size.width, output.size.height,
                bounds.width(), bounds.height(), profile.rawEdgeCrop.centerFactor,
                JSONArray(LensProfiles.encode(listOf(profile))).getJSONObject(0).toString())
        }

        fun fromJson(value: JSONObject) = LensProfileShotMetadata(value.getString("name"), value.getString("rawFormat"),
            value.getInt("rawWidth"), value.getInt("rawHeight"), value.getInt("cropWidth"), value.getInt("cropHeight"),
            value.getDouble("cropFactor").toFloat(), value.getJSONObject("settings").toString())
    }
}
