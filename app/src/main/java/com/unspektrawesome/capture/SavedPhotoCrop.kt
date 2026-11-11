package com.unspektrawesome.capture

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedPhotoCrop(val id: String, val name: String, val crop: PhotoCrop) {
    init {
        require(id.isNotBlank() && id.length <= 80)
        require(name.length <= 32 && crop.ratio == CropRatio.CUSTOM)
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("crop", crop.toJson())

    companion object {
        fun create(name: String, crop: PhotoCrop) = SavedPhotoCrop(UUID.randomUUID().toString(), name.trim().take(32), crop)

        fun decode(value: String?): List<SavedPhotoCrop> = runCatching {
            val array = JSONArray(value ?: return emptyList())
            (0 until array.length()).mapNotNull { index -> runCatching {
                val item = array.getJSONObject(index)
                SavedPhotoCrop(item.getString("id"), item.optString("name").trim().take(32),
                    PhotoCrop.fromJson(item.getJSONObject("crop")))
            }.getOrNull() }.distinctBy { it.id }
        }.getOrDefault(emptyList())

        fun encode(crops: List<SavedPhotoCrop>): String = JSONArray().apply { crops.forEach { put(it.toJson()) } }.toString()
    }
}
