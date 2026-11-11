package com.unspektrawesome.capture

import android.location.Location
import org.json.JSONObject

data class CaptureLocation(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(timeMillis > 0)
        require(altitudeMeters == null || altitudeMeters.isFinite())
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0)
    }

    fun toLocation() = Location("camera").also {
        it.latitude = latitude; it.longitude = longitude; it.time = timeMillis
        altitudeMeters?.let { altitude -> it.altitude = altitude }
        accuracyMeters?.let { accuracy -> it.accuracy = accuracy }
    }

    fun toJson() = JSONObject().put("latitude", latitude).put("longitude", longitude)
        .put("time", timeMillis).put("altitude", altitudeMeters).put("accuracy", accuracyMeters)

    companion object {
        fun fromJson(value: JSONObject?): CaptureLocation? = runCatching {
            if (value == null) return null
            CaptureLocation(value.getDouble("latitude"), value.getDouble("longitude"), value.getLong("time"),
                if (value.isNull("altitude")) null else value.getDouble("altitude"),
                if (value.isNull("accuracy")) null else value.getDouble("accuracy").toFloat())
        }.getOrNull()

        fun isFresh(fixElapsedNanos: Long, nowElapsedNanos: Long): Boolean =
            fixElapsedNanos > 0 && nowElapsedNanos >= fixElapsedNanos &&
                nowElapsedNanos - fixElapsedNanos <= 30_000_000_000L
    }
}
