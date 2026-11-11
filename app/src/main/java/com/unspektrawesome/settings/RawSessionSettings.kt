package com.unspektrawesome.settings

import com.unspektrawesome.camera.IntRange
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.camera.session.RawSessionConfiguration
import org.json.JSONObject

object RawSessionSettings {
    fun encode(configuration: RawSessionConfiguration): JSONObject = JSONObject().apply {
        put("companionFormat", configuration.companionFormat)
        configuration.companionSize?.let { put("companionWidth", it.width); put("companionHeight", it.height) }
        put("physicalCompanion", configuration.physicalCompanion)
        put("fpsMin", configuration.fpsRange.lower); put("fpsMax", configuration.fpsRange.upper)
        put("allowUnadvertisedRaw", configuration.allowUnadvertisedRaw)
        put("rawReaderUsage", configuration.rawReaderUsage)
        configuration.auxiliaryJpegSize?.let { put("jpegWidth", it.width); put("jpegHeight", it.height) }
        put("jpegReaderUsage", configuration.jpegReaderUsage)
        put("startupDelayMs", configuration.startupDelayMs)
        put("targetCompanion", configuration.targetCompanion)
        put("previewRequestTemplate", configuration.previewRequestTemplate)
        if (configuration.rawStreamUseCase != 0L) put("rawStreamUseCase", configuration.rawStreamUseCase)
    }

    fun decode(json: JSONObject?): RawSessionConfiguration? {
        if (json == null) return null
        val companion = json.optInt("companionFormat", 0)
        require(json.has("jpegWidth") == json.has("jpegHeight"))
        return RawSessionConfiguration(companion, if (companion == 0) null
            else Size2d(json.getInt("companionWidth"), json.getInt("companionHeight")),
            json.optBoolean("physicalCompanion", false), IntRange(json.getInt("fpsMin"), json.getInt("fpsMax")),
            json.optBoolean("allowUnadvertisedRaw", false), json.optLong("rawReaderUsage", 3L),
            if (json.has("jpegWidth")) Size2d(json.getInt("jpegWidth"), json.getInt("jpegHeight")) else null,
            json.optLong("jpegReaderUsage", 3L), json.optInt("startupDelayMs", 0),
            json.optBoolean("targetCompanion", true), json.optInt("previewRequestTemplate", 1),
            json.optLong("rawStreamUseCase", 0L))
    }
}
