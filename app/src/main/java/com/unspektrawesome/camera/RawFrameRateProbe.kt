package com.unspektrawesome.camera

import android.hardware.camera2.CameraManager
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.exposure.AeLimits
import com.unspektrawesome.camera.exposure.SensorExposure
import com.unspektrawesome.camera.session.*
import com.unspektrawesome.preview.RawPreviewPlanSelector
import com.unspektrawesome.settings.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class RawFrameRateProbe(private val manager: CameraManager) {
    suspend fun measure(camera: CameraDescriptor, profile: LensProfile, frameRate: Int): RawFrameRateReading {
        val requested = profile.copy(capture = profile.capture.copy(frameRate = frameRate))
        val preferences = LensProfiles.effective(CameraPreferences(lensProfiles = listOf(requested),
            activeLensProfileId = requested.id, rawPreviewStreams = mapOf(requested.routeId to requested.rawStream)))
        val plan = RawPreviewPlanSelector.select(camera, preferences)
        val decodedOutput = profile.rawLayoutOverride.output(plan.acquisitionOutput)
        val session = Camera2RawSession(manager, camera.sensorMetadata)
        session.setExactConfiguration(plan.sessionConfiguration)
        session.setCompanionMode(profile.capture.ispSurface.sessionValue)
        session.setRequestBatchSize(profile.capture.requestBatchSize)
        session.setVendorOverrides(profile.vendorOverrides.active())
        session.setRawLayoutOverride(profile.rawLayoutOverride)
        session.setExternalAutoExposure(profile.aeEngine == AeEngine.SPEKTRA)
        session.setInitialAutoExposure(SensorExposure(400, minOf(8_333_333L, 500_000_000L / frameRate)), AeLimits())
        val done = CompletableDeferred<RawFrameRateReading>()
        val timestamps = ArrayList<Long>()
        var warmup = (frameRate / 4).coerceIn(2, 8)
        val sampleCount = (frameRate / 2).coerceIn(4, 24)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "FrameRateProbe").apply { isDaemon = true } }
        try {
            executor.execute {
                runCatching {
                    session.start(camera.route, plan.acquisitionOutput, plan.aeTargetRange, { frame ->
                        if (!done.isCompleted && frame.output.format == decodedOutput.format &&
                            frame.output.size == decodedOutput.size) {
                            val timestamp = frame.metadata.sensorTimestampNs
                            if (warmup > 0) warmup--
                            else if (timestamp > (timestamps.lastOrNull() ?: 0)) {
                                timestamps += timestamp
                                if (timestamps.size >= sampleCount && timestamp - timestamps.first() >= 750_000_000L) {
                                    val measured = (timestamps.size - 1) * 1e9 / (timestamp - timestamps.first())
                                    done.complete(RawFrameRateReading(frameRate, measured, timestamps.size))
                                }
                            }
                        }
                    }, object : RawSessionListener {
                        override fun onStateChanged(state: Camera2RawSession.State) = Unit
                        override fun onError(error: Camera2RawSession.SessionError) {
                            done.complete(RawFrameRateReading(frameRate, null))
                        }
                    })
                }.onFailure { done.complete(RawFrameRateReading(frameRate, null)) }
            }
            return withTimeoutOrNull(8_000) { done.await() } ?: RawFrameRateReading(frameRate, null)
        } finally {
            done.cancel()
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    session.requestClose()
                    check(session.awaitReleased(3_000)) { "Camera did not close after checking frame rates" }
                } finally { executor.shutdownNow() }
            }
        }
    }
}
