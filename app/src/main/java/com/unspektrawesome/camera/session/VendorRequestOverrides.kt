package com.unspektrawesome.camera.session

import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import com.unspektrawesome.camera.CameraRoute
import com.unspektrawesome.settings.VendorOverrides
import com.unspektrawesome.settings.VendorTagTarget

class VendorRequestOverrides(config: VendorOverrides) {
    val sessionMode: Int
    private val entries: List<Entry>

    init {
        require(config.validationError() == null) { config.validationError().orEmpty() }
        sessionMode = if (config.enabled) config.sessionMode() else 0
        entries = if (!config.enabled) emptyList() else config.tags.map { tag ->
            val value = requireNotNull(tag.type).parse(tag.value)
            Entry(CaptureRequest.Key(tag.key.trim(), value.javaClass), value, tag.target)
        }
    }

    fun applyRequest(builder: CaptureRequest.Builder) = apply(builder, VendorTagTarget.REQUEST)
    fun applySession(builder: CaptureRequest.Builder) = apply(builder, VendorTagTarget.SESSION)

    private fun apply(builder: CaptureRequest.Builder, target: VendorTagTarget) {
        entries.filter { it.target == target }.forEach { entry ->
            try {
                builder.set(entry.key, entry.value)
            } catch (error: RuntimeException) {
                throw IllegalArgumentException("${target.label} vendor key ${entry.key.name} was rejected. " +
                    "Check its name, type and value, or turn off custom vendor overrides.", error)
            }
        }
    }

    private data class Entry(val key: CaptureRequest.Key<Any>, val value: Any, val target: VendorTagTarget)
}

data class AvailableVendorKey(val name: String, val session: Boolean)

fun availableVendorKeys(manager: CameraManager, route: CameraRoute): List<AvailableVendorKey> {
    val cameras = listOf(route.openCameraId, route.characteristicsCameraId).distinct()
    val results = cameras.map { id -> runCatching {
        val characteristics = manager.getCameraCharacteristics(id)
        val session = characteristics.availableSessionKeys.orEmpty().map { it.name }.toSet()
        (characteristics.availableCaptureRequestKeys.orEmpty().map { it.name } + session)
            .distinct().map { AvailableVendorKey(it, it in session) }
    } }
    if (results.all { it.isFailure }) throw requireNotNull(results.first().exceptionOrNull())
    return results.flatMap { it.getOrDefault(emptyList()) }.groupBy { it.name }
        .map { (name, keys) -> AvailableVendorKey(name, keys.any { it.session }) }.sortedBy { it.name }
}
