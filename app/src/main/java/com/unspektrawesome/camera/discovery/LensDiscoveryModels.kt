package com.unspektrawesome.camera.discovery

import com.unspektrawesome.camera.*
import com.unspektrawesome.camera.IntRange
import com.unspektrawesome.camera.session.RawSessionConfiguration
import com.unspektrawesome.settings.RawStreamSelection
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class LensProbeStatus(val label: String) {
    VERIFIED("Verified"), UNSUPPORTED("Unsupported"), OPEN_FAILED("Camera open failed"),
    SESSION_FAILED("Session configuration failed"), RAW_FAILED("RAW stream failed"),
    CAPTURE_FAILED("Capture failed"), TIMEOUT("Timeout"), DEVICE_ERROR("Device / camera error"),
    CANCELLED("Cancelled"), NOT_TESTED("Not tested"),
    SKIPPED("Skipped"), BLOCKED("Camera unavailable"),
}

data class DiscoveredLensConfiguration(
    val routeId: String,
    val cameraId: String,
    val characteristicsId: String,
    val logicalCameraId: String?,
    val physicalCameraId: String?,
    val sensorId: String,
    val facing: LensFacing,
    val focalLengthMm: Float?,
    val equivalentFocalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val raw: RawStreamSelection,
    val session: RawSessionConfiguration,
) {
    val key: String by lazy(LazyThreadSafetyMode.PUBLICATION) { discoveryHash(toJson().toString()) }
    val role: LensRole get() = when (facing) {
        LensFacing.FRONT -> LensRole.FRONT
        LensFacing.EXTERNAL -> LensRole.EXTERNAL
        LensFacing.BACK -> when {
            equivalentFocalLengthMm == null -> LensRole.REAR
            equivalentFocalLengthMm < 20f -> LensRole.ULTRA_WIDE
            equivalentFocalLengthMm in 20f..35f -> LensRole.MAIN
            equivalentFocalLengthMm >= 45f -> LensRole.TELEPHOTO
            else -> LensRole.REAR
        }
        else -> LensRole.UNKNOWN
    }
    val name: String get() = listOfNotNull(role.label,
        equivalentFocalLengthMm?.let { "${kotlin.math.round(it).toInt()}mm" }).joinToString(" ")

    val description: String get() = "${raw.format} ${raw.width} × ${raw.height}"
    val sessionDescription: String get() = (if (session.companionFormat == 0) "RAW stream only"
        else "Preview stream ${session.companionSize.width} × ${session.companionSize.height}" +
            (if (session.companionFormat == 35) " YUV" else "") +
            " · camera ${if (session.physicalCompanion) physicalCameraId else cameraId}") +
        " · " + (if (session.fpsRange.lower == session.fpsRange.upper) "${session.fpsRange.upper}"
            else "${session.fpsRange.lower}-${session.fpsRange.upper}") + " fps"
    val profileName: String get() = name

    fun isAutomaticName(value: String): Boolean {
        if (value == name) return true
        val focal = equivalentFocalLengthMm?.let { "${kotlin.math.round(it).toInt()}mm" } ?: "Camera $routeId"
        val sessionNames = listOf(sessionDescription, sessionDescription.replace(
            " · ${session.fpsRange.upper} fps", " · ${session.fpsRange.lower}-${session.fpsRange.upper} fps"))
        val previousRoles = listOf(role) + if (logicalCameraId != null && physicalCameraId == null) listOf(LensRole.REAR) else emptyList()
        return previousRoles.any { role ->
            val title = "${role.label} $focal"
            value == title || sessionNames.any { value == "$title · $description · ID $routeId · $it" }
        }
    }

    fun matchesRoute(camera: CameraDescriptor): Boolean = camera.isUsable && camera.route.let {
        it.routeId == routeId && it.openCameraId == cameraId && it.characteristicsCameraId == characteristicsId &&
            it.physicalCameraId == physicalCameraId
    }

    fun matches(camera: CameraDescriptor): Boolean = matchesRoute(camera) && camera.rawOutputs.any(raw::matches)

    fun toJson(): JSONObject = JSONObject().apply {
        put("route", routeId); put("camera", cameraId); put("characteristics", characteristicsId)
        put("logical", logicalCameraId); put("physical", physicalCameraId); put("sensor", sensorId)
        put("facing", facing.name); put("focal", focalLengthMm); put("equivalent", equivalentFocalLengthMm)
        put("sensorWidth", sensorWidthMm); put("sensorHeight", sensorHeightMm)
        put("format", raw.format.name); put("width", raw.width); put("height", raw.height)
        put("maximum", raw.maximumResolutionMode)
        put("companion", session.companionFormat)
        put("companionWidth", session.companionSize?.width); put("companionHeight", session.companionSize?.height)
        put("physicalCompanion", session.physicalCompanion)
        put("fpsMin", session.fpsRange.lower); put("fpsMax", session.fpsRange.upper)
    }

    companion object {
        fun fromJson(json: JSONObject): DiscoveredLensConfiguration = with(json) {
            DiscoveredLensConfiguration(getString("route"), getString("camera"), getString("characteristics"),
                optionalString("logical"), optionalString("physical"), getString("sensor"),
                LensFacing.valueOf(getString("facing")), optionalFloat("focal"), optionalFloat("equivalent"),
                optionalFloat("sensorWidth"), optionalFloat("sensorHeight"),
                RawStreamSelection(RawFormat.valueOf(getString("format")), getInt("width"), getInt("height"), getBoolean("maximum")),
                RawSessionConfiguration(getInt("companion"), if (getInt("companion") == 0) null
                    else Size2d(getInt("companionWidth"), getInt("companionHeight")), getBoolean("physicalCompanion"),
                    IntRange(getInt("fpsMin"), getInt("fpsMax"))))
        }
    }
}

data class LensVerification(val environment: String, val configuration: DiscoveredLensConfiguration, val verified: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("environment", environment).put("configuration", configuration.toJson()).put("verified", verified)
    companion object {
        fun fromJson(json: JSONObject): LensVerification = LensVerification(json.getString("environment"),
            DiscoveredLensConfiguration.fromJson(json.getJSONObject("configuration")), json.optBoolean("verified", true))
    }
}

data class LensProbeResult(
    val configuration: DiscoveredLensConfiguration,
    val status: LensProbeStatus,
    val detail: String,
    val elapsedMs: Long = 0,
    val resourcesReleased: Boolean = true,
) {
    val tested: Boolean get() = status !in listOf(LensProbeStatus.NOT_TESTED, LensProbeStatus.CANCELLED,
        LensProbeStatus.SKIPPED, LensProbeStatus.BLOCKED)
    val resolved: Boolean get() = tested || status == LensProbeStatus.SKIPPED || status == LensProbeStatus.BLOCKED
    val failed: Boolean get() = (tested && status != LensProbeStatus.VERIFIED) || status == LensProbeStatus.BLOCKED
}

data class LensDiscoveryReport(
    val environment: String,
    val completedAtMs: Long,
    val results: List<LensProbeResult>,
    val cancelled: Boolean = false,
    val message: String? = null,
    val candidateCount: Int = results.size,
) {
    val testedCount: Int get() = results.count { it.tested }
    val skippedCount: Int get() = results.count { it.status == LensProbeStatus.SKIPPED }
    val complete: Boolean get() = results.count { it.resolved } == candidateCount
    val verified: List<DiscoveredLensConfiguration> get() = results.filter {
        it.status == LensProbeStatus.VERIFIED && it.resourcesReleased
    }.map { it.configuration }.distinctBy { it.key }

    fun encode(): String = JSONObject().apply {
        put("schema", SCHEMA); put("environment", environment); put("completedAt", completedAtMs)
        put("cancelled", cancelled); put("message", message)
        put("candidateCount", candidateCount)
        put("results", JSONArray().apply { results.forEach { result -> put(JSONObject().apply {
            put("configuration", result.configuration.toJson()); put("status", result.status.name)
            put("detail", result.detail); put("elapsed", result.elapsedMs); put("released", result.resourcesReleased)
        }) } })
    }.toString()

    companion object {
        const val SCHEMA = 6
        fun decode(text: String, environment: String): LensDiscoveryReport? = runCatching {
            val json = JSONObject(text)
            if (json.getInt("schema") != SCHEMA || json.getString("environment") != environment) return null
            val results = json.getJSONArray("results")
            LensDiscoveryReport(environment, json.getLong("completedAt"), (0 until results.length()).map {
                val result = results.getJSONObject(it)
                LensProbeResult(DiscoveredLensConfiguration.fromJson(result.getJSONObject("configuration")),
                    LensProbeStatus.valueOf(result.getString("status")), result.getString("detail"),
                    result.getLong("elapsed"), result.getBoolean("released"))
            }, json.getBoolean("cancelled"), json.optionalString("message"), json.getInt("candidateCount"))
        }.getOrNull()
    }
}

internal fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.optionalFloat(key: String): Float? = if (isNull(key)) null else getDouble(key).toFloat().takeIf { it.isFinite() && it > 0f }
internal fun discoveryHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
