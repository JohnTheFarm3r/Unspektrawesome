package com.unspektrawesome.camera.discovery

import android.hardware.camera2.CameraManager
import android.os.SystemClock
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.session.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Camera2LensProbe(
    private val manager: CameraManager,
    cameras: List<CameraDescriptor>,
    private val timeoutMs: Long = 18_000,
) : LensConfigurationProbe {
    private val camerasByRoute = cameras.associateBy { it.route.routeId }

    override suspend fun probe(configuration: DiscoveredLensConfiguration, phase: (String) -> Unit): LensProbeResult {
        val camera = camerasByRoute[configuration.routeId]
        val output = camera?.rawOutputs?.firstOrNull(configuration.raw::matches)
        if (camera == null || !configuration.matches(camera) || output == null || output.maximumResolutionMode) {
            return LensProbeResult(configuration, LensProbeStatus.UNSUPPORTED,
                "This stream cannot drive the app's regular RAW viewfinder")
        }
        val started = SystemClock.elapsedRealtime()
        val cameraReady = withTimeoutOrNull(5_000) {
            while (!runCatching {
                if (manager.cameraIdList.isEmpty()) false else {
                    // The ID list can remain cached while the camera service restarts.
                    manager.getCameraCharacteristics(configuration.characteristicsId)
                    true
                }
            }.getOrDefault(false)) {
                phase("Waiting for the camera service to recover…")
                delay(200)
            }
            true
        } == true
        if (!cameraReady) {
            return LensProbeResult(configuration, LensProbeStatus.TIMEOUT,
                "Camera metadata did not become available before the timeout", SystemClock.elapsedRealtime() - started)
        }
        val state = AtomicReference(Camera2RawSession.State.IDLE)
        val completion = CompletableDeferred<Pair<LensProbeStatus, String>>()
        val session = Camera2RawSession(manager, camera.sensorMetadata)
        session.setExactConfiguration(configuration.session)
        val frames = AtomicInteger()
        val timestamp = AtomicLong()
        val captureTimestamp = AtomicLong()
        val stillTimestamp = AtomicLong()
        val captureRequested = java.util.concurrent.atomic.AtomicBoolean()
        val captureReceived = java.util.concurrent.atomic.AtomicBoolean()
        val starter = Executors.newSingleThreadExecutor { Thread(it, "LensProbeStart").apply { isDaemon = true } }
        fun complete(status: LensProbeStatus, detail: String) { completion.complete(status to detail) }
        fun validFrame(frame: RawHardwareFrame): Boolean = configuration.raw.matches(frame.output) &&
            frame.metadata.sensorTimestampNs > 0 && frame.metadata.sensorTimestampNs > timestamp.getAndSet(frame.metadata.sensorTimestampNs) &&
            frame.rowStrideBytes > 0 && frame.packedRawBytes.isDirect && frame.packedRawBytes.remaining() > 0 &&
            (frame.metadata.exposureTimeNs ?: 0) > 0 && (frame.metadata.sensitivityIso ?: 0) > 0

        val stillCallback = object : RawStillCaptureCallback {
            override fun onStillFrame(frame: RawHardwareFrame) {
                if (completion.isCompleted) return
                if (!configuration.raw.matches(frame.output) || frame.captureResult == null ||
                    frame.metadata.sensorTimestampNs <= captureTimestamp.get() || frame.packedRawBytes.remaining() <= 0) {
                    complete(LensProbeStatus.CAPTURE_FAILED, "RAW still image or matching capture metadata is invalid")
                    return
                }
                captureReceived.set(true)
                stillTimestamp.set(frame.metadata.sensorTimestampNs)
                phase("Checking viewfinder after capture…")
                // The callback borrows its image. Resume after it has returned and released the buffer.
                starter.execute {
                    runCatching { session.resumePreview() }.onFailure {
                        complete(LensProbeStatus.CAPTURE_FAILED, it.message ?: "Could not resume the viewfinder")
                    }
                }
            }
            override fun onPreviewResumed() = Unit
            override fun onStillCaptureError(error: Camera2RawSession.SessionError) {
                complete(LensProbeStatus.CAPTURE_FAILED, error.message)
            }
        }
        var released = false
        var result: Pair<LensProbeStatus, String>
        try {
            starter.execute {
                try {
                    session.start(camera.route, output, configuration.session.fpsRange, object : RawFrameConsumer {
                        override fun onRawFrame(frame: RawHardwareFrame) {
                            if (completion.isCompleted) return
                            if (captureReceived.get() && frame.metadata.sensorTimestampNs <= stillTimestamp.get()) return
                            if (!validFrame(frame)) {
                                complete(LensProbeStatus.RAW_FAILED, "RAW frame layout, timestamp or exposure metadata is invalid")
                                return
                            }
                            if (captureReceived.get() && session.state() == Camera2RawSession.State.STREAMING) {
                                complete(LensProbeStatus.VERIFIED, "RAW preview, still capture and resumed viewfinder verified")
                            } else if (frames.incrementAndGet() >= 3 &&
                                (configuration.session.companionFormat == 0 || !configuration.session.targetCompanion || session.companionFrames() > 0) &&
                                captureRequested.compareAndSet(false, true)) {
                                captureTimestamp.set(frame.metadata.sensorTimestampNs)
                                phase("Verifying RAW capture…")
                                session.captureStill(listOf(output), stillCallback)
                            }
                        }
                    }, object : RawSessionListener {
                        override fun onStateChanged(next: Camera2RawSession.State) {
                            state.set(next)
                            when (next) {
                                Camera2RawSession.State.OPENING -> phase("Opening camera…")
                                Camera2RawSession.State.CONFIGURING -> phase("Creating RAW and viewfinder session…")
                                Camera2RawSession.State.STREAMING -> if (!captureRequested.get()) phase("Waiting for valid RAW viewfinder frames…")
                                Camera2RawSession.State.UNSUPPORTED -> complete(LensProbeStatus.UNSUPPORTED, "Camera session API is unavailable")
                                else -> Unit
                            }
                        }
                        override fun onError(error: Camera2RawSession.SessionError) {
                            val status = when (error.code) {
                                Camera2RawSession.ErrorCode.API_UNSUPPORTED -> LensProbeStatus.UNSUPPORTED
                                Camera2RawSession.ErrorCode.CAMERA_ACCESS -> LensProbeStatus.OPEN_FAILED
                                Camera2RawSession.ErrorCode.SESSION_CONFIGURATION -> LensProbeStatus.SESSION_FAILED
                                Camera2RawSession.ErrorCode.BUFFER, Camera2RawSession.ErrorCode.CONSUMER -> LensProbeStatus.RAW_FAILED
                                Camera2RawSession.ErrorCode.CAPTURE_FAILURE, Camera2RawSession.ErrorCode.REQUEST ->
                                    if (captureRequested.get()) LensProbeStatus.CAPTURE_FAILED else LensProbeStatus.RAW_FAILED
                                else -> LensProbeStatus.DEVICE_ERROR
                            }
                            complete(status, listOfNotNull(error.message, error.cause?.message).joinToString(": "))
                        }
                    })
                } catch (error: Exception) {
                    complete(if (state.get() in listOf(Camera2RawSession.State.IDLE, Camera2RawSession.State.OPENING))
                        LensProbeStatus.OPEN_FAILED else LensProbeStatus.DEVICE_ERROR, error.message ?: error.javaClass.simpleName)
                }
            }
            result = withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: (LensProbeStatus.TIMEOUT to "Timed out in ${state.get().name.lowercase().replace('_', ' ')}")
        } finally {
            completion.cancel()
            phase("Closing camera…")
            withContext(NonCancellable + Dispatchers.IO) {
                closer.execute { session.requestClose() }
                released = session.awaitReleased(2_500)
                starter.shutdownNow()
                starter.awaitTermination(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        if (!released && result.first == LensProbeStatus.VERIFIED) {
            result = LensProbeStatus.DEVICE_ERROR to "Frames worked, but the camera did not acknowledge closure"
        }
        if (result.first != LensProbeStatus.VERIFIED) delay(250)
        return LensProbeResult(configuration, result.first, result.second, SystemClock.elapsedRealtime() - started, released)
    }

    companion object {
        private val closer = Executors.newCachedThreadPool { Thread(it, "LensProbeClose").apply { isDaemon = true } }
    }
}
