package com.unspektrawesome.settings

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.exposure.AeLimits
import com.unspektrawesome.camera.exposure.AeBalance
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.exposure.ExposureBounds
import com.unspektrawesome.camera.session.SensorExposureLimits
import org.json.JSONObject

fun AeLimits.toJson(): JSONObject = JSONObject().apply {
    put("minIso", minIso ?: JSONObject.NULL); put("maxIso", maxIso ?: JSONObject.NULL)
    put("minShutterNs", minShutterNs ?: JSONObject.NULL); put("maxShutterNs", maxShutterNs ?: JSONObject.NULL)
}

fun aeLimitsFromJson(json: JSONObject?): AeLimits = AeLimits(
    json?.optInt("minIso")?.takeIf { it > 0 }, json?.optInt("maxIso")?.takeIf { it > 0 },
    json?.optLong("minShutterNs")?.takeIf { it > 0 }, json?.optLong("maxShutterNs")?.takeIf { it > 0 },
)

fun CameraPreferences.selectedAeEngine(): AeEngine =
    lensProfiles.firstOrNull { it.id == activeLensProfileId && it.enabled }?.aeEngine ?: aeEngine

fun CameraPreferences.withAeEngine(value: AeEngine): CameraPreferences = copy(aeEngine = value,
    lensProfiles = lensProfiles.map {
        if (it.id == activeLensProfileId && it.enabled && it.aeEngine != value)
            it.copy(aeEngine = value, autoManaged = false) else it
    })

fun CameraPreferences.aeLimits(): AeLimits =
    lensProfiles.firstOrNull { it.id == activeLensProfileId }?.aeLimits ?: AeLimits()

fun CameraPreferences.aeBalance(): Float = AeBalance.normalize(
    lensProfiles.firstOrNull { it.id == activeLensProfileId }?.aeBalance ?: 0f)

fun CameraPreferences.withAeBalance(value: Float): CameraPreferences = copy(lensProfiles = lensProfiles.map {
    if (it.id == activeLensProfileId) it.copy(aeBalance = AeBalance.normalize(value)) else it
})

fun readExposureBounds(context: Context, camera: CameraDescriptor): ExposureBounds? = runCatching {
    val manager = context.getSystemService(CameraManager::class.java)
    val opened = manager.getCameraCharacteristics(camera.route.openCameraId)
    val selected = manager.getCameraCharacteristics(camera.route.characteristicsCameraId)
    if (listOf(selected, opened).none { characteristics ->
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    }) return null
    val iso = selected.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        ?: opened.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
    val time = SensorExposureLimits.read(selected, opened) ?: return null
    ExposureBounds(iso.lower, iso.upper, time.minimumNs, time.maximumNs)
}.getOrNull()
