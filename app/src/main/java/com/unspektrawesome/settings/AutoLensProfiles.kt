package com.unspektrawesome.settings

import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.LensFacing
import com.unspektrawesome.camera.LensRole
import com.unspektrawesome.camera.discovery.*
import java.util.UUID
import kotlin.math.abs

object AutoLensProfiles {
    fun recommended(report: LensDiscoveryReport): List<DiscoveredLensConfiguration> = select(report.verified)

    private fun select(verified: List<DiscoveredLensConfiguration>): List<DiscoveredLensConfiguration> = verified
        .filter(LensDiscoveryPolicy::accepts)
        .groupBy { it.sensorId }.values.map { configurations ->
            configurations.minWith(LensDiscoveryPolicy.streamPreference
                .thenBy { when { it.physicalCameraId != null -> 0; it.logicalCameraId == null -> 1; else -> 2 } }
                .thenBy { it.routeId }.thenBy { it.key })
        }.sortedWith(compareBy(
            { if (it.facing == LensFacing.BACK) 0 else 1 },
            { if (it.role == LensRole.MAIN) 0 else 1 },
            { abs((it.equivalentFocalLengthMm ?: 1000f) - 26f) }, { it.sensorId }))

    fun apply(report: LensDiscoveryReport, preferences: CameraPreferences, cameras: List<CameraDescriptor>): CameraPreferences {
        val verified = report.verified.filter { configuration -> cameras.any(configuration::matches) }
        val selected = select(verified)
        if (verified.isEmpty() && preferences.lensProfiles.none { it.verification != null }) return preferences
        val previous = preferences.lensProfiles
        val managed = previous.filter { it.managedByDiscovery }.groupBy { it.sensorId }
        val resultsByConfiguration = report.results.associateBy { it.configuration.key }
        val generated = selected.map { configuration ->
            val existing = managed[configuration.sensorId].orEmpty().minWithOrNull(compareBy(
                { if (it.verification?.configuration?.key == configuration.key) 0 else 1 },
                { if (it.id == preferences.activeLensProfileId) 0 else 1 },
                { if (it.id == preferences.defaultLensProfileId) 0 else 1 }, { it.id }))
            val camera = cameras.first(configuration::matches)
            (existing ?: LensProfile(UUID.randomUUID().toString(), configuration.sensorId, configuration.profileName,
                configuration.routeId, configuration.raw,
                color = LensColorSettings(matrixEnabled = camera.sensorMetadata.colorCalibration.hasUsableColorTransform())))
                .copy(name = configuration.profileName, sensorId = configuration.sensorId, routeId = configuration.routeId,
                    rawStream = configuration.raw, verification = LensVerification(report.environment, configuration), autoManaged = true)
        }
        val retained = previous.filterNot { it.managedByDiscovery }.map { old ->
            val proof = old.verification
            val result = proof?.configuration?.key?.let(resultsByConfiguration::get)
            val updated = when {
                proof != null && result?.status == LensProbeStatus.VERIFIED && result.resourcesReleased ->
                    old.copy(name = old.displayName, verification = LensVerification(report.environment, result.configuration))
                proof != null && result?.tested == true && result.status != LensProbeStatus.VERIFIED ->
                    old.copy(verification = proof.copy(verified = false))
                else -> old
            }
            if (generated.isNotEmpty() && !preferences.autoLensDiscoveryConfigured) updated.copy(enabled = false) else updated
        }
        val selectedSensors = generated.mapTo(hashSetOf()) { it.sensorId }
        val pending = if (report.complete) emptyList() else managed.filterKeys { it !in selectedSensors }.values.mapNotNull { profiles ->
            profiles.filter { resultsByConfiguration[it.verification?.configuration?.key]?.tested != true }
                .minWithOrNull(compareBy({ if (it.id == preferences.activeLensProfileId) 0 else 1 }, { it.id }))
        }
        val profiles = generated + retained + pending
        val active = profiles.firstOrNull { it.enabled && it.id == preferences.activeLensProfileId &&
            LensProfiles.available(it, cameras) }
            ?: generated.firstOrNull { it.enabled }
        val startup = profiles.firstOrNull { preferences.autoLensDiscoveryConfigured && it.id == preferences.defaultLensProfileId &&
            it.enabled && LensProfiles.available(it, cameras) }
            ?: generated.firstOrNull { it.enabled }
        return LensProfiles.normalize(preferences.copy(lensProfiles = profiles,
            autoLensDiscoveryConfigured = preferences.autoLensDiscoveryConfigured || verified.isNotEmpty(),
            activeLensProfileId = active?.id, defaultLensProfileId = startup?.id, defaultLensId = startup?.routeId), cameras)
    }
}
