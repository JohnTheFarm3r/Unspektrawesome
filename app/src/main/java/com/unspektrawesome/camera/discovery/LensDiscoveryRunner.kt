package com.unspektrawesome.camera.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class LensDiscoveryProgress(
    val running: Boolean = false,
    val tested: Int = 0,
    val total: Int = 0,
    val cameraNumber: Int = 0,
    val cameraCount: Int = 0,
    val phase: String = "Discovering cameras…",
    val current: DiscoveredLensConfiguration? = null,
    val results: List<LensProbeResult> = emptyList(),
)

fun interface LensConfigurationProbe {
    suspend fun probe(configuration: DiscoveredLensConfiguration, phase: (String) -> Unit): LensProbeResult
}

/** Keeps hardware failures local and never promotes an untested alternative. */
class LensDiscoveryRunner(private val probe: LensConfigurationProbe, private val now: () -> Long = System::currentTimeMillis,
    private val probeTimeoutMs: Long = 30_000) {
    suspend fun run(
        environment: String,
        candidates: List<DiscoveredLensConfiguration>,
        progress: (LensDiscoveryProgress) -> Unit,
        checkpoint: (LensDiscoveryReport) -> Unit = {},
        previous: LensDiscoveryReport? = null,
        requiredConfigurations: Set<String> = emptySet(),
    ): LensDiscoveryReport {
        val uniqueCandidates = LensDiscoveryPolicy.order(candidates)
        val keys = uniqueCandidates.mapTo(hashSetOf()) { it.key }
        val results = linkedMapOf<String, LensProbeResult>()
        previous?.takeIf { it.environment == environment }?.results?.filter {
            it.configuration.key in keys && it.tested
        }?.forEach { results[it.configuration.key] = it }
        val quarantinedCameras = mutableSetOf<String>()
        val verifiedLenses = results.values.filter { it.status == LensProbeStatus.VERIFIED && it.resourcesReleased &&
            LensDiscoveryPolicy.accepts(it.configuration) }.mapTo(hashSetOf()) { it.configuration.sensorId }
        val cameras = uniqueCandidates.map { it.sensorId }.distinct()
        var cancelled = false
        var message: String? = null
        fun skipUnneeded() {
            uniqueCandidates.filter { it.key !in results && it.key !in requiredConfigurations &&
                (!LensDiscoveryPolicy.accepts(it) || it.sensorId in verifiedLenses) }
                .forEach { candidate ->
                    results[candidate.key] = LensProbeResult(candidate, LensProbeStatus.SKIPPED,
                        if (candidate.sensorId in verifiedLenses) "A suitable profile is already verified for this lens"
                        else "Outside the resolution range used for automatic profiles")
                }
        }
        skipUnneeded()
        fun report(message: String? = null) = LensDiscoveryReport(environment, now(), uniqueCandidates.mapNotNull { results[it.key] },
            cancelled, message, uniqueCandidates.size)
        try {
            for (candidate in uniqueCandidates) {
                coroutineContext.ensureActive()
                if (candidate.key in results) continue
                if (candidate.cameraId in quarantinedCameras) {
                    results[candidate.key] = LensProbeResult(candidate, LensProbeStatus.BLOCKED,
                        "Camera did not finish closing after an earlier test. Rescan to retry.")
                    continue
                }
                val state = LensDiscoveryProgress(true, results.values.count { it.tested },
                    uniqueCandidates.size, cameras.indexOf(candidate.sensorId) + 1, cameras.size,
                    "Opening camera…", candidate, results.values.toList())
                progress(state)
                val result = try {
                    withTimeoutOrNull(probeTimeoutMs) { probe.probe(candidate) { progress(state.copy(phase = it)) } }
                        ?: LensProbeResult(candidate, LensProbeStatus.TIMEOUT, "Probe deadline exceeded", resourcesReleased = false)
                } catch (error: CancellationException) {
                    results[candidate.key] = LensProbeResult(candidate, LensProbeStatus.CANCELLED, "Discovery cancelled")
                    throw error
                } catch (error: Exception) {
                    LensProbeResult(candidate, LensProbeStatus.DEVICE_ERROR, error.message ?: error.javaClass.simpleName)
                }
                results[candidate.key] = if (result.status == LensProbeStatus.VERIFIED && !result.resourcesReleased)
                    result.copy(status = LensProbeStatus.DEVICE_ERROR, detail = "Camera did not finish closing after capture") else result
                if (!result.resourcesReleased) quarantinedCameras += candidate.cameraId
                else if (result.status == LensProbeStatus.VERIFIED) {
                    if (LensDiscoveryPolicy.accepts(candidate)) verifiedLenses += candidate.sensorId
                    skipUnneeded()
                }
                val saved = report()
                checkpoint(if (saved.complete) saved else saved.copy(
                    message = "Discovery was interrupted. Continue to finish configuring the remaining lenses."))
            }
        } catch (_: CancellationException) {
            cancelled = true
            message = "Discovery cancelled. Results are saved. Continue discovery to configure the remaining lenses."
        }
        if (!cancelled && results.values.any { it.status == LensProbeStatus.BLOCKED }) {
            message = "Some camera IDs did not close after testing. Rescan to retry those IDs or use manual Lens Profiles."
        }
        return report(message).also {
            progress(LensDiscoveryProgress(results = it.results, tested = it.testedCount,
                total = uniqueCandidates.size, phase = message ?: "Discovery complete"))
        }
    }
}
