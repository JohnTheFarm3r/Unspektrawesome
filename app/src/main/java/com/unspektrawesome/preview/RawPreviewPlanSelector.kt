package com.unspektrawesome.preview

import android.os.Build
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.RawPreviewPolicy
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.RawPreviewQuality
import com.unspektrawesome.settings.vendorOverrides
import com.unspektrawesome.settings.rawLayoutOverride
import com.unspektrawesome.settings.LensFrameRates
import com.unspektrawesome.settings.lensCaptureSettings

object RawPreviewPlanSelector {
    const val TARGET_FRAMES_PER_SECOND = RawPreviewPolicy.TARGET_FRAMES_PER_SECOND
    internal val isMediaTekDevice: Boolean
        get() = isMediaTek(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null, Build.HARDWARE)

    fun select(
        camera: CameraDescriptor,
        preferences: CameraPreferences,
        importCapabilities: Map<RawImportStreamKey, RawImportCapability> = emptyMap(),
        preferAdvertisedFpsRange: Boolean = isMediaTekDevice,
        frameRateOverride: Int? = null,
    ): RawPreviewPolicy.Plan {
        require(camera.isUsable) { "Selected camera is not usable for RAW preview" }
        val profile = preferences.lensProfiles.firstOrNull {
            it.id == preferences.activeLensProfileId && it.enabled && it.routeId == camera.route.routeId
        }
        val selectedStream = requireNotNull(
            profile?.rawStream ?: preferences.rawPreviewStreams[camera.route.routeId],
        ) {
            "No RAW stream is configured for camera route ${camera.route.routeId}"
        }
        val requestedOutput = requireNotNull(
            com.unspektrawesome.settings.LensProfiles.resolveRawOutput(selectedStream, camera, profile?.rawSession),
        ) {
            "Configured RAW stream ${selectedStream.format} ${selectedStream.width} x " +
                "${selectedStream.height} is not advertised by camera route ${camera.route.routeId}"
        }
        val overrides = preferences.vendorOverrides()
        require(overrides.validationError() == null) { overrides.validationError().orEmpty() }
        val layout = preferences.rawLayoutOverride()
        require(layout.validationError(requestedOutput.format, requestedOutput.size) == null) {
            layout.validationError(requestedOutput.format, requestedOutput.size).orEmpty()
        }
        val capture = preferences.lensCaptureSettings().let { it.copy(frameRate = frameRateOverride ?: it.frameRate) }
        val capability = importCapabilities[RawImportStreamKey.from(camera.route.routeId, requestedOutput, overrides, layout, capture, profile?.rawSession)]
        require(capability?.state != RawImportCapabilityState.INCOMPATIBLE) {
            "${requestedOutput.format} ${requestedOutput.size.width} x " +
                "${requestedOutput.size.height} cannot be imported by Vulkan: ${capability?.reason}"
        }
        var plan = RawPreviewPolicy.select(
            listOf(requestedOutput),
            camera.aeFpsRanges,
            when (preferences.previewQuality) {
                RawPreviewQuality.LOW -> RawPreviewPolicy.Quality.LOW
                RawPreviewQuality.MEDIUM -> RawPreviewPolicy.Quality.MEDIUM
                RawPreviewQuality.HIGH -> RawPreviewPolicy.Quality.HIGH
                RawPreviewQuality.FULL -> RawPreviewPolicy.Quality.FULL
            },
            TARGET_FRAMES_PER_SECOND,
            setOf(requestedOutput.format),
            preferAdvertisedFpsRange,
        )
        if (profile?.rawSession != null) plan = plan.withSessionConfiguration(profile.rawSession)
        else profile?.verification?.let { proof ->
            require(proof.verified && proof.configuration.matchesRoute(camera) &&
                proof.configuration.raw == profile.rawStream) { "Saved lens configuration is unavailable" }
            plan = plan.withSessionConfiguration(proof.configuration.session)
        }
        (frameRateOverride ?: profile?.capture?.frameRate)?.let { fps ->
            plan = plan.withFrameRate(fps, LensFrameRates.range(camera, fps))
        }
        val decoded = layout.size(requestedOutput.size)
        if (preferences.previewShortEdge == null && !layout.enabled) return plan
        val processingStream = selectedStream.copy(width = decoded.width, height = decoded.height)
        val edge = com.unspektrawesome.settings.ViewfinderResolutions.selectedEdge(
            processingStream, preferences.previewShortEdge, preferences.previewQuality)
        return plan.withProcessingSize(com.unspektrawesome.settings.ViewfinderResolutions.size(processingStream, edge))
    }

    internal fun isMediaTek(socManufacturer: String?, hardware: String?): Boolean =
        socManufacturer.equals("MediaTek", ignoreCase = true) ||
            hardware.orEmpty().matches(Regex("mt[0-9].*", RegexOption.IGNORE_CASE))
}
