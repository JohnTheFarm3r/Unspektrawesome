package com.unspektrawesome.preview

import android.content.Context
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.diagnostics.ImageDiagnostics
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.unspektrawesome.camera.RawFrameRateProbe
import com.unspektrawesome.camera.RawFrameRateReport
import com.unspektrawesome.camera.RawFrameRateStore
import com.unspektrawesome.settings.LensProfile
import com.unspektrawesome.settings.LensCaptureSettings
import com.unspektrawesome.settings.LensFrameRates
import com.unspektrawesome.settings.lensCaptureSettings
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.CameraOrientation
import com.unspektrawesome.camera.RawFormat
import com.unspektrawesome.camera.RawPreviewPolicy
import com.unspektrawesome.camera.exposure.*
import com.unspektrawesome.camera.session.Camera2RawSession
import com.unspektrawesome.camera.session.RawHardwareFrame
import com.unspektrawesome.camera.session.RawStillCaptureCallback
import com.unspektrawesome.capture.CaptureGeometry
import com.unspektrawesome.capture.CaptureMetadata
import com.unspektrawesome.capture.CaptureIdentity
import com.unspektrawesome.capture.SpektraShotMetadata
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.capture.PhotoCrop
import com.unspektrawesome.capture.CaptureLocation
import com.unspektrawesome.capture.JpegMediaStoreWriter
import com.unspektrawesome.capture.TapMeteringMapper
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.VendorOverrides
import com.unspektrawesome.settings.vendorOverrides
import com.unspektrawesome.settings.LensExposureStore
import com.unspektrawesome.settings.LensStabilizationStore
import com.unspektrawesome.settings.aeLimits
import com.unspektrawesome.settings.aeBalance
import com.unspektrawesome.settings.RawLevelCalibration
import com.unspektrawesome.settings.rawLevelCalibration
import com.unspektrawesome.settings.CfaOverride
import com.unspektrawesome.settings.cfaOverride
import com.unspektrawesome.settings.rawEdgeCrop
import com.unspektrawesome.settings.rawLayoutOverride
import com.unspektrawesome.settings.RawLayoutOverride
import com.unspektrawesome.settings.lensColorSettings
import com.unspektrawesome.calibration.DcpRepository
import com.unspektrawesome.calibration.DcpProfile
import com.unspektrawesome.calibration.ResolvedLensColor
import com.unspektrawesome.calibration.RawCalibrationTables
import com.unspektrawesome.spektra.SpektraCaptureSnapshot
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraState
import com.unspektrawesome.spektra.SpektraStateRepository
import com.unspektrawesome.vulkan.RawUpload
import com.unspektrawesome.vulkan.VulkanRenderer
import com.unspektrawesome.vulkan.ExposureMeterBuffer
import com.unspektrawesome.vulkan.params.FrameSeedSnapshot
import com.unspektrawesome.vulkan.params.GeometryShaderParameters
import com.unspektrawesome.vulkan.params.LensShadingMapParameters
import com.unspektrawesome.vulkan.params.RawShaderParameters
import com.unspektrawesome.vulkan.params.RawColorShaderParameters
import com.unspektrawesome.vulkan.params.VulkanCaptureSnapshot
import com.unspektrawesome.vulkan.params.WhiteBalanceGains
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import com.unspektrawesome.video.VideoPhase
import com.unspektrawesome.video.VideoRecording
import com.unspektrawesome.video.VideoRecordingState
import com.unspektrawesome.video.VideoDownscale
import com.unspektrawesome.video.VideoPixelCrop
import com.unspektrawesome.video.VideoEncoding
import com.unspektrawesome.video.VideoFraming

enum class RawPreviewPhase {
    IDLE, PAUSED, WAITING_FOR_SURFACE, OPENING, STREAMING, ERROR, CLOSED,
}

data class RawPreviewStatus(
    val phase: RawPreviewPhase = RawPreviewPhase.IDLE,
    val message: String = "RAW preview is idle",
    val rendererDiagnostic: String = "Renderer has not attached a surface",
    val renderedFrames: Long = 0,
    val skippedFrames: Long = 0,
    val droppedCameraFrames: Long = 0,
    val spektraRevision: Long? = null,
    val lensSwitching: Boolean = false,
    val viewfinderReady: Boolean = false,
    val captureReady: Boolean = false,
    val captureRouteId: String? = null,
    val renderedAspectRatio: Float? = null,
    val surfaceAspectRatio: Float? = null,
    val cropTransitionRevision: Long = 0L,
)

enum class RawCapturePhase {
    IDLE, CAPTURING, SAVING, SUCCEEDED, CANCELED, ERROR,
}

data class RawCaptureStatus(
    val phase: RawCapturePhase = RawCapturePhase.IDLE,
    val message: String = "Ready to capture",
    val revisionId: Long? = null,
    val lastUri: Uri? = null,
    val outstandingShots: Int = 0,
    val acquiringFrame: Boolean = false,
    val retryableShots: Int = 0,
    val deferredShots: Int = 0,
    val processingDeferred: Boolean = false,
    val savingRawShots: Int = 0,
    val rawBufferFull: Boolean = false,
    val feedbackId: Long = 0,
    val shutterFeedbackId: Long = 0,
    val exposureTimeNs: Long = 0,
    val exposureStartedAtMs: Long? = null,
    val canceledCapture: Boolean = false,
) {
    val shutterBusy: Boolean get() = if (acquiringFrame && exposureTimeNs >= 1_000_000_000L) true
        else if (processingDeferred) rawBufferFull
        else acquiringFrame || outstandingShots >= CAPACITY

    companion object {
        const val CAPACITY = 10
    }
}

enum class TapMeteringPhase {
    IDLE, METERING, SUCCESS, FAILED, TIMED_OUT, UNSUPPORTED,
}

data class TapMeteringStatus(
    val generation: Long = 0,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val phase: TapMeteringPhase = TapMeteringPhase.IDLE,
    val hidden: Boolean = false,
)

data class CameraControlState(
    val customAe: Boolean = false,
    val exposureMode: AeMode = AeMode.AUTO,
    val exposureLimited: Boolean = false,
    val flashAvailable: Boolean = false,
    val exposureCompensationMin: Int = 0,
    val exposureCompensationMax: Int = 0,
    val exposureCompensationStepEv: Float = 1f,
    val exposureCompensation: Int = 0,
    val manualExposureSupported: Boolean = false,
    val isoMin: Int = 100,
    val isoMax: Int = 100,
    val exposureTimeMinNs: Long = 1_000_000L,
    val exposureTimeMaxNs: Long = 10_000_000L,
    val autoExposure: Boolean = true,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L,
    val pendingExposureTimeNs: Long? = null,
    val manualFocusSupported: Boolean = false,
    val minimumFocusDistance: Float = 0f,
    val hyperfocalDistance: Float = 0f,
    val autoFocus: Boolean = true,
    val focusDistance: Float = 0f,
    val manualWhiteBalanceSupported: Boolean = false,
    val autoWhiteBalance: Boolean = true,
    val whiteBalanceTemperatureKelvin: Int? = null,
    val whiteBalanceRedGain: Float? = null,
    val whiteBalanceGreenGain: Float? = null,
    val whiteBalanceBlueGain: Float? = null,
) {
    val selectedExposureTimeNs: Long get() = pendingExposureTimeNs ?: exposureTimeNs
    val isoAuto: Boolean get() = if (customAe) !exposureMode.isoLocked else autoExposure
    val shutterAuto: Boolean get() = if (customAe) !exposureMode.shutterLocked else autoExposure

    companion object {
        fun from(state: Camera2RawSession.ControlState) = CameraControlState(
            customAe = state.externalAutoExposure,
            flashAvailable = state.flashAvailable,
            exposureCompensationMin = state.exposureCompensationMin,
            exposureCompensationMax = state.exposureCompensationMax,
            exposureCompensationStepEv = state.exposureCompensationStepEv,
            exposureCompensation = state.exposureCompensation,
            manualExposureSupported = state.manualExposureSupported,
            isoMin = state.isoMin,
            isoMax = state.isoMax,
            exposureTimeMinNs = state.exposureTimeMinNs,
            exposureTimeMaxNs = state.exposureTimeMaxNs,
            autoExposure = state.autoExposure,
            iso = state.iso,
            exposureTimeNs = state.exposureTimeNs,
            pendingExposureTimeNs = state.pendingExposureTimeNs,
            manualFocusSupported = state.manualFocusSupported,
            minimumFocusDistance = state.minimumFocusDistance,
            hyperfocalDistance = state.hyperfocalDistance,
            autoFocus = state.autoFocus,
            focusDistance = state.focusDistance,
            manualWhiteBalanceSupported = state.manualWhiteBalanceSupported,
            autoWhiteBalance = state.autoWhiteBalance,
            whiteBalanceTemperatureKelvin = state.whiteBalanceTemperatureKelvin,
            whiteBalanceRedGain = state.whiteBalanceRedGain,
            whiteBalanceGreenGain = state.whiteBalanceGreenGain,
            whiteBalanceBlueGain = state.whiteBalanceBlueGain,
        )
    }
}

/** Owns Camera2 RAW streaming and synchronous borrowed-buffer Vulkan rendering. */
class RawVulkanPreviewController(
    context: Context,
    private val spektraRepository: SpektraStateRepository,
    private val locationSnapshot: () -> CaptureLocation? = { null },
    private val rendererFactory: ((Boolean) -> VulkanRenderer)? = null,
) : DefaultLifecycleObserver, AutoCloseable {
    private val lock = Any()
    private val renderLock = Any()
    private val commands = CameraCommandQueue()
    private val lensExposureStore = LensExposureStore(context)
    private var controlSessionGeneration = 0L
    private var retainedControls: Camera2RawSession.ControlState? = null
    private var retainedExposureMode = AeMode.AUTO
    private var lensSwitchExposure: SensorExposure? = null
    private val pipelineCacheDirectory = java.io.File(context.cacheDir, "vulkan-pipelines").apply { mkdirs() }
    private val pipelineCachePath = java.io.File(pipelineCacheDirectory, "rendering").absolutePath
    private val cameraManager = context.applicationContext
        .getSystemService(CameraManager::class.java)
    private val rendererExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "UnspektrawesomeVulkanSetup")
    }
    private val previewFrames = PreviewFrameDispatcher(rendererExecutor, allocateRecordingBuffer = RecordingRawBuffer::allocate)
    private val performanceHints = renderPerformanceHints(context.applicationContext)
    private var renderer: VulkanRenderer? = null
    private val captureProcessor = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "UnspektrawesomeCapture")
    }
    private var captureRenderer: VulkanRenderer? = null
    private val mergeRing = MultiFrameRing(context.applicationContext)
    private val mergeProcessor = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "UnspektrawesomeMerge")
    }
    private var mergingShots = 0
    private val captureBufferDirectory = java.io.File(context.filesDir, "capture-queue").apply { mkdirs() }
    private var restoredQueue = false
    private val failedExports = mutableSetOf<java.io.File>()
    private var lastExportError: String? = null
    private var rendererDiagnostic = "Renderer has not attached a surface"
    private var surfaceGeneration = 0L
    private var surfaceReady = false
    private val diagnosticWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "PreviewDiagnostics").apply { isDaemon = true }
    }
    private var diagnosticTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var lastCameraFrameAtMs = 0L
    @Volatile private var lastRenderStartedAtMs = 0L
    @Volatile private var lastRenderCompletedAtMs = 0L
    private var lastRawSampleAtMs = 0L
    private var lastSpektraLogAtMs = 0L
    private var loggedSpektraRevision = Long.MIN_VALUE
    private var loggedSpektraGeneration = Long.MIN_VALUE
    private var loggedSpektraEnabled: Boolean? = null
    private var lastDiagnosticPublishAtMs = 0L
    private val jpegWriter = JpegMediaStoreWriter(
        context.applicationContext.contentResolver,
        context.getSharedPreferences("capture-export-receipts", Context.MODE_PRIVATE),
    )
    private val applicationContext = context.applicationContext
    private val deferredWriter = com.unspektrawesome.gallery.DeferredCaptureWriter(applicationContext)
    private val deferredBuffers: BufferedRawCaptureQueue = BufferedRawCaptureQueue(
        Executors.newSingleThreadExecutor { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "UnspektrawesomeRawSave")
        }, ::onDeferredBufferChanged,
        allocate = { bytes ->
            val memory = android.os.SharedMemory.create("Unspektrawesome RAW", bytes)
            try {
                val pixels = memory.mapReadWrite().order(ByteOrder.nativeOrder())
                BufferedRawCaptureQueue.Storage(pixels) { android.os.SharedMemory.unmap(pixels); memory.close() }
            } catch (error: Throwable) { memory.close(); throw error }
        },
    )
    private val deferredCaptures = ArrayDeque<PendingCapture>()
    private val galleryUpdates = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(RawPreviewStatus())
    private val mutableCaptureStatus = MutableStateFlow(RawCaptureStatus())
    private val mutableTapMeteringStatus = MutableStateFlow(TapMeteringStatus())
    private val mutableExposureMeteringStatus = MutableStateFlow(TapMeteringStatus())
    private val focusScene = MeteringSceneChangeDetector()
    private val exposureScene = MeteringSceneChangeDetector()
    private var lastMeteringSceneSampleMs = 0L
    val exposureMeteringStatus: StateFlow<TapMeteringStatus> = mutableExposureMeteringStatus.asStateFlow()
    private val mutableCameraControls = MutableStateFlow(CameraControlState())
    private val mutableRawImportCapabilities =
        MutableStateFlow<Map<RawImportStreamKey, RawImportCapability>>(emptyMap())

    private var configuredCamera: CameraDescriptor? = null
    private var configuredPreferences = CameraPreferences()
    private var active: ActiveSession? = null
    private var lastContractSession: Camera2RawSession? = null
    private var attachedSurface: Surface? = null
    private var attachedSurfaceWidth = 0
    private var attachedSurfaceHeight = 0
    private var deviceOrientationDegrees = 0
    private var resumed = false
    private var closed = false
    private var blockedConfiguration: ConfigurationKey? = null
    private val sessionRecovery = LensSessionRecovery()
    private val startupGuard = LensStartupGuard.create(context)
    private var crashedConfiguration = startupGuard.crashedConfiguration
    private val mutableSessionRecovery = MutableStateFlow<LensSessionRecoveryStatus?>(null)
    val lensSessionRecovery: StateFlow<LensSessionRecoveryStatus?> = mutableSessionRecovery.asStateFlow()
    private var recoveryGeneration = 0L
    private var retryTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var sessionStartupTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    private var latestWhiteBalance: WhiteBalanceGains? = null
    private var latestLensShadingMap: LensShadingMapParameters? = null
    private var pendingCapture: PendingCapture? = null
    private var latestPreviewGeometry: PreviewGeometry? = null
    private var displayedPreviewGeometry: PreviewGeometry? = null
    private val cropTransition = PreviewCropTransition()
    private var serializedPreviewGeometry: FrameGeometrySnapshot? = null
    private var cropPreview: PhotoCrop? = null

    private val videoPreferences = applicationContext.getSharedPreferences("video_recording", Context.MODE_PRIVATE)
    private val lensStabilizationStore = LensStabilizationStore(context)
    private var stabilizationLensKey: String? = null
    private val mutableVideoState = MutableStateFlow(lensStabilizationStore.load(null, VideoRecordingState(downscale = VideoDownscale.entries.firstOrNull {
        it.name == videoPreferences.getString("downscale", null)
    } ?: VideoDownscale.FULL,
        chromaDenoise = videoPreferences.getBoolean("chroma_denoise", false),
        hotPixelRemoval = videoPreferences.getBoolean("hot_pixel_removal", false),
        chromaDenoiseStrength = videoPreferences.getFloat("chroma_denoise_strength", 1f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
        bitrate = videoPreferences.getInt("bitrate", 45_000_000))))
    val videoState: StateFlow<VideoRecordingState> = mutableVideoState.asStateFlow()
    @Volatile private var videoRecorder: VideoRecording? = null
    private val videoWorker = Executors.newSingleThreadExecutor { Thread(it, "VideoLifecycle") }
    private var videoRenderer: VulkanRenderer? = null
    private var videoWarmupFramesRemaining = 0
    private var videoStartQueued = false
    private var videoWatchdog: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var lastVideoFrameAtMs = 0L
    @Volatile private var lastVideoFrameCount = 0L
    private var videoEncodings = emptyMap<VideoDownscale, VideoEncoding>()
    private var videoRetainedSize: com.unspektrawesome.camera.Size2d? = null
    private val frameRateStore = RawFrameRateStore(context)
    private val frameRateProbeLock = kotlinx.coroutines.sync.Mutex()
    private var recordingGeometry: FrameGeometrySnapshot? = null
    private var recordingCrop: PhotoCrop? = null

    fun setCaptureGyro(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (stabilizationLensKey == null) return@synchronized
        mutableVideoState.value = mutableVideoState.value.copy(captureGyro = enabled)
        saveLensStabilizationLocked()
        active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
    } } }

    fun setVideoChromaDenoise(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (mutableVideoState.value.busy) return@synchronized
        videoPreferences.edit().putBoolean("chroma_denoise", enabled).apply()
        mutableVideoState.value = mutableVideoState.value.copy(chromaDenoise = enabled)
        resetPerformanceLocked()
    } } }

    fun setVideoHotPixelRemoval(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (mutableVideoState.value.busy) return@synchronized
        videoPreferences.edit().putBoolean("hot_pixel_removal", enabled).apply()
        mutableVideoState.value = mutableVideoState.value.copy(hotPixelRemoval = enabled)
        resetPerformanceLocked()
    } } }

    fun setVideoChromaDenoiseStrength(strength: Float) {
        if (!strength.isFinite()) return
        val amount = strength.coerceIn(0f, 1f)
        commands.submit { synchronized(lock) {
            if (mutableVideoState.value.busy) return@synchronized
            videoPreferences.edit().putFloat("chroma_denoise_strength", amount).apply()
            mutableVideoState.value = mutableVideoState.value.copy(chromaDenoiseStrength = amount)
            resetPerformanceLocked()
        } }
    }

    fun setLiveStabilization(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (stabilizationLensKey == null || mutableVideoState.value.busy) return@synchronized
        updateVideoOutputLocked(mutableVideoState.value.copy(liveStabilization = enabled, message = null))
        saveLensStabilizationLocked()
        active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
        cropTransition.reset()
    } } }

    fun setStabilizationSettings(settings: com.unspektrawesome.video.LiveStabilizationSettings) {
        commands.submit { synchronized(lock) {
            if (stabilizationLensKey == null || mutableVideoState.value.busy) return@synchronized
            updateVideoOutputLocked(mutableVideoState.value.copy(stabilizationSettings = settings))
            saveLensStabilizationLocked()
            cropTransition.reset()
        } }
    }

    fun setVideoOis(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (stabilizationLensKey == null || mutableVideoState.value.oisBlockedByGyro) return@synchronized
        val owner = active ?: return@synchronized
        val next = mutableVideoState.value.copy(oisEnabled = enabled)
        if (!owner.session.setOpticalStabilization(next.requestedOis)) return@synchronized
        mutableVideoState.value = next
        saveLensStabilizationLocked()
    } } }

    private fun saveLensStabilizationLocked() {
        stabilizationLensKey?.let { lensStabilizationStore.save(it, mutableVideoState.value) }
    }

    private fun restoreLensStabilizationLocked(): Boolean {
        if (mutableVideoState.value.busy) return false
        val key = LensStabilizationStore.key(configuredPreferences, configuredCamera?.route?.routeId)
        val photoOisEnabled = configuredPreferences.lensProfiles.firstOrNull {
            it.id == configuredPreferences.activeLensProfileId && it.enabled && it.routeId == configuredCamera?.route?.routeId
        }?.photoOisEnabled ?: true
        if (key == stabilizationLensKey && photoOisEnabled == mutableVideoState.value.photoOisEnabled) return false
        stabilizationLensKey = key
        mutableVideoState.value = lensStabilizationStore.load(key, mutableVideoState.value)
            .copy(stabilizationLimited = false, photoOisEnabled = photoOisEnabled)
        cropTransition.reset()
        return true
    }

    fun setVideoMode(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (mutableVideoState.value.busy || pendingCapture != null || burstRecorder.status.value.busy || panoramaState.value.busy) return@synchronized
        leavePanoramaLocked()
        if (enabled) burstRecorder.setEnabled(false)
        mutableVideoState.value = mutableVideoState.value.copy(enabled = enabled, message = null, phase = VideoPhase.IDLE)
        active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
        updateVideoOutputLocked()
        reconcileLocked()
        cropTransition.reset()
        resetPerformanceLocked()
    } } }

    fun restoreVideoMode(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (!mutableVideoState.value.busy) {
            mutableVideoState.value = videoSettings(mutableVideoState.value.copy(enabled = enabled))
            active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
            updateVideoOutputLocked()
            reconcileLocked()
        }
    } } }

    fun setVideoDownscale(downscale: VideoDownscale) { commands.submit { synchronized(lock) {
        if (!mutableVideoState.value.busy && downscale in mutableVideoState.value.available) {
            videoPreferences.edit().putString("downscale", downscale.name).apply()
            mutableVideoState.value = videoSettings(mutableVideoState.value.copy(downscale = downscale, message = null))
            resetPerformanceLocked()
        }
    } } }

    fun setVideoBitrate(bitrate: Int) { commands.submit { synchronized(lock) {
        val state = mutableVideoState.value
        val range = state.bitrateRange ?: return@synchronized
        if (state.busy) return@synchronized
        val selected = range.clamp(bitrate)
        videoPreferences.edit().putInt("bitrate", selected).apply()
        mutableVideoState.value = state.copy(bitrate = selected, message = null)
    } } }

    fun setVideoFrameRate(frameRate: Int?) { commands.submit { synchronized(lock) {
        val state = mutableVideoState.value
        if (state.busy || (frameRate != null && frameRate !in state.availableFrameRates)) return@synchronized
        videoPreferences.edit().putInt("frame_rate_override", frameRate ?: 0).apply()
        mutableVideoState.value = state.copy(frameRateOverride = frameRate)
        updateVideoOutputLocked()
        resetPerformanceLocked()
        reconcileLocked()
    } } }

    private fun videoFrameRateOverride(): Int? = if (burstRecorder.status.value.enabled) 30 else mutableVideoState.value.let {
        it.frameRateOverride.takeIf { _ -> it.enabled }
    }

    internal fun setBurstMode(enabled: Boolean) { commands.submit { synchronized(lock) {
        if (mutableVideoState.value.busy || pendingCapture != null || burstRecorder.status.value.busy || panoramaState.value.busy) return@synchronized
        if (enabled) leavePanoramaLocked()
        if (enabled) {
            mutableVideoState.value = mutableVideoState.value.copy(enabled = false)
            active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
        }
        burstRecorder.setEnabled(enabled)
        reconcileLocked()
    } } }

    suspend fun checkLensFrameRates(camera: CameraDescriptor, profile: LensProfile): RawFrameRateReport {
        frameRateProbeLock.lock()
        var suspended = false
        try {
            check(synchronized(lock) { !mutableVideoState.value.busy && pendingCapture == null && !discoverySuspended })
            suspended = true
            check(suspendForLensDiscovery()) { "Camera is busy" }
            val rates = (LensFrameRates.advertised(camera) + listOfNotNull(profile.capture.frameRate)).distinct().sorted()
            val readings = rates.map { RawFrameRateProbe(cameraManager).measure(camera, profile, it) }
            return RawFrameRateReport(readings).also { frameRateStore.save(camera, profile, it) }
        } finally {
            if (suspended) {
                resumeAfterLensDiscovery()
                commands.submit { synchronized(lock) { updateVideoOutputLocked() } }
            }
            frameRateProbeLock.unlock()
        }
    }

    private fun videoSettings(state: VideoRecordingState): VideoRecordingState {
        val encoding = videoEncodings[state.downscale]
        val range = encoding?.bitrates
        val defaultBitrate = encoding?.defaultBitrate ?: 45_000_000
        val previousKey = videoPreferences.getString("resolution", null)
        val previousBitrate = previousKey?.takeIf { videoPreferences.contains(it) }
            ?.let { videoPreferences.getInt(it, defaultBitrate) } ?: defaultBitrate
        val bitrate = videoPreferences.getInt("bitrate", previousBitrate)
        return state.copy(bitrate = range?.clamp(bitrate) ?: bitrate, bitrateRange = range,
            defaultBitrate = defaultBitrate,
            outputSize = encoding?.size ?: videoRetainedSize?.let { VideoFraming.outputSize(it, state.downscale) },
            outputIssue = when {
                state.rawSize == null -> "Video is unavailable for this lens"
                videoRetainedSize == null -> "Selected pixel crop does not fit this RAW stream"
                encoding == null -> "Increase downscaling for this size and frame rate"
                else -> null
            })
    }

    private fun updateVideoOutputLocked(requested: VideoRecordingState = mutableVideoState.value) {
        val camera = configuredCamera
        videoEncodings = emptyMap()
        videoRetainedSize = null
        var state = requested.copy(rawSize = null, availablePixelCrops = emptyList(), portraitCrop = false)
        if (camera != null) runCatching {
            val profile = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
            val report = profile?.let { frameRateStore.load(camera, it) }
            val choices = (LensFrameRates.advertised(camera) + report?.videoChoices.orEmpty())
                .filter { it == 24 || it == 30 }.distinct().sorted().takeIf { 24 in it && 30 in it }.orEmpty()
            val override = videoPreferences.getInt("frame_rate_override", 0).takeIf { it in choices }
            val capture = configuredPreferences.lensCaptureSettings()
            state = state.copy(frameRateOverride = override, availableFrameRates = choices)
            val plan = RawPreviewPlanSelector.select(camera, configuredPreferences, frameRateOverride = override)
            val delivered = report?.delivered(plan.framesPerSecond) ?: plan.framesPerSecond.toDouble()
            val frameRate = delivered / capture.frameInterval
            state = state.copy(frameRate = frameRate)
            val output = configuredPreferences.rawLayoutOverride().output(plan.acquisitionOutput)
            val transform = CaptureGeometry.transform(camera.sensorMetadata.orientationDegrees, 0, camera.facing)
            val native = checkNotNull(VideoFraming.source(camera.sensorMetadata, output, transform, PhotoCrop(),
                edges = configuredPreferences.rawEdgeCrop()))
            val usable = com.unspektrawesome.camera.Size2d(native.cropPixels.width, native.cropPixels.height)
            state = state.copy(rawSize = usable,
                availablePixelCrops = VideoPixelCrop.entries.filter { it.fits(output.size, configuredPreferences.videoCrop.swapped, usable) })
            val source = VideoFraming.source(camera.sensorMetadata, output, transform,
                configuredPreferences.videoCrop, configuredPreferences.videoPixelCrop, configuredPreferences.rawEdgeCrop())
                ?: return@runCatching
            val retained = if (state.liveStabilization) VideoFraming.stabilized(source, state.stabilizationSettings.cropRatio) else source
            state = state.copy(portraitCrop = retained.cropPixels.width < retained.cropPixels.height)
            val size = VideoFraming.retainedSize(retained)
            videoRetainedSize = size
            videoEncodings = VideoDownscale.entries.mapNotNull { factor ->
                VideoEncoding.supported(size, factor, frameRate)?.let { factor to it }
            }.toMap()
        }.onFailure { InternalLogRecorder.error("VideoRecording", "Output configuration unavailable", it) }
        mutableVideoState.value = videoSettings(state.copy(available = videoEncodings.keys.toList()))
    }

    /** Starts or stops recording. [lockControls] holds exposure, focus and white balance until recording stops. */
    fun toggleVideoRecording(lockControls: Boolean = false) { commands.submit { synchronized(lock) {
        val state = mutableVideoState.value
        if (state.busy) {
            stopVideoLocked()
            return@synchronized
        }
        if (!state.enabled || !state.outputSupported || !captureReadyLocked()) return@synchronized
        val encoding = videoEncodings[state.downscale] ?: return@synchronized
        if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mutableVideoState.value = state.copy(phase = VideoPhase.ERROR, message = "Microphone permission is required for video")
            return@synchronized
        }
        val currentRenderer = renderer ?: return@synchronized
        val owner = active ?: return@synchronized
        val videoGeometry = latestPreviewGeometry?.takeIf { it.owner === owner }?.framing ?: return@synchronized
        if (videoGeometry.crop != configuredPreferences.videoCrop || cropPreview != null) return@synchronized
        val source = VideoFraming.source(owner.camera.sensorMetadata,
            configuredPreferences.rawLayoutOverride().output(owner.plan.acquisitionOutput), videoGeometry.target.transform,
            configuredPreferences.videoCrop, configuredPreferences.videoPixelCrop, configuredPreferences.rawEdgeCrop())
            ?: return@synchronized
        val retained = if (state.liveStabilization)
            VideoFraming.stabilized(source, state.stabilizationSettings.cropRatio) else source
        if (!VideoFraming.fits(retained, encoding.size)) return@synchronized
        val recorded = VideoFraming.recording(retained, encoding.size, state.downscale)
        val portrait = recorded.cropAspect < 1f
        val rotation = ((if (portrait) 90 else 0) + deviceOrientationDegrees) % 360
        recordingGeometry = recorded
        recordingCrop = videoGeometry.crop
        if (lockControls) lockVideoControlsLocked(owner)
        mutableVideoState.value = state.copy(phase = VideoPhase.STARTING, startedAtMs = 0, droppedFrames = 0,
            stabilizationLimited = false, message = null, controlsLocked = videoLockOwner === owner)
        mutableVideoState.value = mutableVideoState.value.copy(clipCaptureGyro = state.captureGyro)
        resetPerformanceLocked()
        val profile = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
        val telemetryAverageFrames = configuredPreferences.lensCaptureSettings().averageFrames
        val telemetryLayoutOverride = configuredPreferences.rawLayoutOverride()
        val recordingConfiguration = org.json.JSONObject().put("route", owner.camera.route.routeId)
            .put("profile", profile?.name).put("rawFormat", owner.plan.acquisitionOutput.format.name)
            .put("rawWidth", owner.plan.acquisitionOutput.size.width).put("rawHeight", owner.plan.acquisitionOutput.size.height)
            .put("capture", owner.key.capture.toJson()).put("requestedFrameRate", owner.plan.framesPerSecond)
            .put("crop", configuredPreferences.videoCrop.toJson()).put("pixelCrop", configuredPreferences.videoPixelCrop?.name)
            .put("downscale", state.downscale.factor)
            .put("chromaDenoise", state.chromaDenoise)
            .put("chromaDenoiseStrength", state.chromaDenoiseStrength)
            .put("hotPixelRemoval", state.hotPixelRemoval)
            .put("focalLength", profile?.focalLength?.toJson())
            .put("liveStabilization", org.json.JSONObject().put("enabled", state.liveStabilization)
                .put("cropRatio", state.stabilizationSettings.cropRatio)
                .put("smoothingSeconds", state.stabilizationSettings.smoothingSeconds)
                .put("strength", state.stabilizationSettings.strength).put("cropLimited", true))
        owner.session.setOpticalStabilization(mutableVideoState.value.requestedOis)
        val recordingBufferBytes = configuredPreferences.recordingBufferMb.coerceIn(512, 4096) * 1024L * 1024
        val audioFormat = configuredPreferences.videoAudioFormat
        val gyroHistoryNs = ((recordingBufferBytes / owner.packedFrameBytes.coerceAtLeast(1) /
            state.frameRate.coerceAtLeast(1.0) + 2.0) * 1_000_000_000).toLong()
        val recording = VideoRecording(applicationContext)
        videoRecorder = recording
        owner.session.setRecording(true, owner.exposureController.mode != AeMode.MANUAL)
        videoStartQueued = false
        lastVideoFrameCount = 0L
        videoWorker.execute {
            try {
                if (!synchronized(lock) { canStartVideoLocked(recording, currentRenderer) }) return@execute
                if (state.captureGyro || state.liveStabilization) runCatching {
                    val telemetryCamera = com.unspektrawesome.video.telemetry.TelemetryCamera(cameraManager,
                        owner.camera.route, owner.plan.acquisitionOutput, recorded, encoding.size.width, encoding.size.height,
                        rotation, telemetryAverageFrames, telemetryLayoutOverride, state.liveStabilization)
                    if (state.captureGyro) recording.captureGyro(telemetryCamera)
                    if (state.liveStabilization) recording.stabilize(telemetryCamera, recorded,
                        source.sourceCrop, state.stabilizationSettings, gyroHistoryNs)
                }.onFailure {
                    InternalLogRecorder.error("VideoTelemetry", "Camera telemetry unavailable", it)
                    if (state.liveStabilization) throw it
                }
                recording.prepare(encoding, state.bitrate, rotation, recordingConfiguration, audioFormat) {
                    commands.submit { synchronized(lock) {
                        if (videoRecorder === recording) stopVideoLocked("Video recording failed")
                    } }
                }
                synchronized(lock) {
                    if (canStartVideoLocked(recording, currentRenderer)) scheduleRendererTask {
                        try {
                            if (!synchronized(lock) { canStartVideoLocked(recording, currentRenderer) }) return@scheduleRendererTask
                            val attachStartedAt = SystemClock.elapsedRealtime()
                            check(currentRenderer.setVideoSurface(recording.surface, encoding.size.width, encoding.size.height,
                                recording.fullRange))
                            val attachMs = SystemClock.elapsedRealtime() - attachStartedAt
                            synchronized(lock) {
                                // Stop and surface teardown run after this attachment on the rendering worker.
                                videoRenderer = currentRenderer
                                videoWarmupFramesRemaining = 2
                                if (canStartVideoLocked(recording, currentRenderer)) {
                                    lastVideoFrameAtMs = SystemClock.elapsedRealtime()
                                    videoWatchdog = diagnosticWorker.scheduleWithFixedDelay({
                                        commands.submit { synchronized(lock) {
                                            if (mutableVideoState.value.phase in setOf(VideoPhase.STARTING, VideoPhase.RECORDING) &&
                                                videoRecorder === recording) {
                                                mutableVideoState.value = mutableVideoState.value.copy(droppedFrames = recording.droppedFrames)
                                                val outputIntervalMs = (1000.0 / mutableVideoState.value.frameRate).toLong()
                                                val timeoutMs = maxOf(if (mutableVideoState.value.phase == VideoPhase.STARTING) 30_000L else 10_000L,
                                                    outputIntervalMs * 4)
                                                if (SystemClock.elapsedRealtime() - lastVideoFrameAtMs > timeoutMs)
                                                    stopVideoLocked("Video recording failed")
                                            }
                                        } }
                                    }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }
                            }
                            InternalLogRecorder.event("VideoRecording", "prepared ${encoding.size.width}x${encoding.size.height} codec=${encoding.codecName} bitrate=${state.bitrate} " +
                                "frame_rate=${encoding.frameRate} operating_rate=${encoding.operatingRate} rotation=$rotation attachMs=$attachMs")
                        } catch (error: Throwable) {
                            failVideoStart(recording, error)
                        }
                    }
                }
            } catch (error: Throwable) {
                failVideoStart(recording, error)
            }
        }
    } } }

    private fun canStartVideoLocked(recording: VideoRecording, currentRenderer: VulkanRenderer): Boolean =
        !closed && resumed && videoRecorder === recording && renderer === currentRenderer &&
            mutableVideoState.value.phase == VideoPhase.STARTING

    private fun completeVideoWarmup(currentRenderer: VulkanRenderer) {
        val recording = videoRecorder ?: return
        synchronized(lock) {
            if (!canStartVideoLocked(recording, currentRenderer) || videoRenderer !== currentRenderer ||
                videoStartQueued || --videoWarmupFramesRemaining > 0 || !recording.telemetryReady ||
                recording.stabilization?.ready == false) return
            videoStartQueued = true
            videoWorker.execute {
                try {
                    if (!synchronized(lock) { canStartVideoLocked(recording, currentRenderer) }) return@execute
                    recording.start()
                    synchronized(lock) {
                        if (canStartVideoLocked(recording, currentRenderer)) {
                            // Discard preview frames captured before the encoder and microphone were ready.
                            previewFrames.setRecording(true, configuredPreferences.recordingBufferMb.coerceIn(512, 4096) * 1024L * 1024,
                                checkNotNull(active).packedFrameBytes)
                            val now = SystemClock.elapsedRealtime()
                            performanceGeneration++
                            performanceMeter.reset(now, gpuSettlingMs = 1_000)
                            mutableVideoState.value = mutableVideoState.value.copy(phase = VideoPhase.RECORDING, startedAtMs = now)
                            lastVideoFrameAtMs = now
                            InternalLogRecorder.event("VideoRecording", "started after preview warmup")
                        }
                    }
                } catch (error: Throwable) {
                    failVideoStart(recording, error)
                }
            }
        }
    }

    private fun failVideoStart(recording: VideoRecording, error: Throwable) {
        InternalLogRecorder.error("VideoRecording", "start failed", error)
        commands.submit { synchronized(lock) {
            if (videoRecorder === recording) stopVideoLocked(if (mutableVideoState.value.liveStabilization)
                "Live stabilization is unavailable for this camera mode" else "Could not start video recording")
        } }
    }

    fun stopVideoRecording() { commands.submit { synchronized(lock) { stopVideoLocked() } } }

    private fun lockVideoControlsLocked(owner: ActiveSession) {
        val controls = mutableCameraControls.value
        if (!controls.manualExposureSupported || owner.session.state() != Camera2RawSession.State.STREAMING) return
        videoLockPreviousControls = controls
        videoLockOwner = owner
        if (controls.customAe) resetCustomExposureLocked(owner, AeMode.MANUAL)
        if (!owner.session.freezeCaptureControls(controls.iso, controls.exposureTimeNs)) {
            unlockVideoControlsLocked()
            return
        }
        mutableCameraControls.value = mutableCameraControls.value.copy(autoExposure = false, exposureMode = AeMode.MANUAL,
            autoFocus = !controls.manualFocusSupported, autoWhiteBalance = !controls.manualWhiteBalanceSupported)
        InternalLogRecorder.event("VideoRecording", "controls locked iso=${controls.iso} exposure_ns=${controls.exposureTimeNs}")
    }

    private fun unlockVideoControlsLocked() {
        val previous = videoLockPreviousControls ?: return
        active?.takeIf { it === videoLockOwner }?.let { applyCameraControlsLocked(it, previous) }
        videoLockPreviousControls = null
        videoLockOwner = null
        mutableVideoState.value = mutableVideoState.value.copy(controlsLocked = false)
    }

    private fun stopVideoLocked(failure: String? = null) {
        if (!mutableVideoState.value.busy || mutableVideoState.value.phase == VideoPhase.SAVING) return
        previewFrames.stopAcceptingFrames()
        if (mutableVideoState.value.phase != VideoPhase.RECORDING) previewFrames.clearPending()
        mutableVideoState.value = mutableVideoState.value.copy(phase = VideoPhase.SAVING)
        active?.session?.setRecording(false)
        unlockVideoControlsLocked()
        videoWatchdog?.cancel(false)
        videoWatchdog = null
        val recording = videoRecorder
        val stoppedAtNs = System.nanoTime()
        recording?.stopAudio(stoppedAtNs)
        scheduleRendererTask {
            val recordingRenderer = videoRenderer
            var drainFailure: Throwable? = null
            try {
                try { previewFrames.drainPending() }
                finally { recordingRenderer?.setVideoSurface(null) }
            } catch (error: Throwable) {
                drainFailure = error
            }
            val frames = lastVideoFrameCount
            if (recording?.started == true) recording.stop(stoppedAtNs)
            synchronized(lock) {
                videoRenderer = null
                recordingGeometry = null
                recordingCrop = null
                previewFrames.setRecording(false)
            }
            // Codec drain, gyro post-roll and file publication must not occupy the rendering worker.
            videoWorker.execute {
                var saved: Uri? = null
                var message = failure
                try {
                    drainFailure?.let { throw it }
                    if (recording?.started == true && frames > 0) saved = recording.finish(stoppedAtNs)
                } catch (error: Throwable) {
                    InternalLogRecorder.error("VideoRecording", "finish failed", error)
                    message = "Could not save video recording"
                } finally {
                    recording?.close()
                }
                synchronized(lock) {
                    if (videoRecorder !== recording) return@synchronized
                    videoRecorder = null
                    mutableVideoState.value = mutableVideoState.value.copy(
                        phase = if (message == null) VideoPhase.IDLE else VideoPhase.ERROR,
                        droppedFrames = recording?.droppedFrames ?: mutableVideoState.value.droppedFrames,
                        lastUri = saved ?: mutableVideoState.value.lastUri,
                        clipCaptureGyro = null,
                        stabilizationLimited = false,
                        message = message ?: if (saved != null) {
                            when {
                                recording?.audioWritten == false -> "Video saved without sound"
                                mutableVideoState.value.clipCaptureGyro == true && recording?.telemetryWritten != true -> "Video saved without gyro data"
                                recording?.telemetryWritten == true && recording.telemetryIssues.isNotEmpty() -> com.unspektrawesome.video.videoTelemetryStatus(recording.telemetryIssues)
                                recording?.metadataWritten == true -> "Video saved"
                                else -> "Video saved without recording metadata"
                            }
                        } else null)
                    if (saved != null) mutableCaptureStatus.value = mutableCaptureStatus.value.copy(lastUri = saved)
                    if (!closed) {
                        restoreLensStabilizationLocked()
                        active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
                        updateVideoOutputLocked()
                    }
                    resetPerformanceLocked()
                }
            }
        }
    }

    fun previewCrop(crop: PhotoCrop?) { commands.submit { synchronized(lock) { cropPreview = crop } } }

    fun completeCropTransition(revision: Long) {
        commands.submit { synchronized(lock) { cropTransition.complete(revision) } }
    }
    private var tapMeteringGeneration = 0L
    private var focusPeakingVisible = false
    private var focusLoupeRequest: FocusLoupeRequest? = null
    private var focusLoupeGeneration = 0L
    private var focusLoupePublishedAtMs = 0L
    // Request generation from which readings describe the current loupe target and zoom.
    @Volatile private var focusLoupeMeterStart = 0L
    private var focusLoupeMeterApplied = -1L
    private var focusLoupeMeterSequence = -1f
    private var focusLoupeMeterLightweight: Boolean? = null
    private var focusLoupeMeter = FocusLoupeMeter()
    private val focusLoupeMotion = FocusLoupeMotion(context)
    // Manual focus position for the meter, 0 at infinity to 1 at the nearest distance; NaN until set.
    @Volatile private var focusLoupeFocusPosition = Float.NaN
    private val mutableFocusLoupeStatus = MutableStateFlow(FocusLoupeStatus())
    val focusLoupeStatus: StateFlow<FocusLoupeStatus> = mutableFocusLoupeStatus.asStateFlow()
    private val focusPeakingResolution = FocusPeakingResolution()
    private var renderedFrames = 0L
    private var skippedFrames = 0L
    private val performanceMeter = PreviewPerformanceMeter()
    private val performanceOutput = directBuffer(40)
    private var performanceGeneration = 0L
    private val rawParameterBuffer = directBuffer(RawShaderParameters.SIZE_BYTES)
    private val ispShaderParameters = com.unspektrawesome.vulkan.params.IspShaderParameters()
    private var loggedIspCurve: android.hardware.camera2.params.TonemapCurve? = null
    private var loggedIspOwner: ActiveSession? = null
    private val rawColorParameterBuffer = directBuffer(RawColorShaderParameters.SIZE_BYTES)
    private val dcpRepository = DcpRepository(context)
    private var configuredColor: Result<ResolvedLensColor>? = null
    private var serializedDcp: DcpProfile? = null
    private val geometryParameterBuffer = directBuffer(GeometryShaderParameters.SIZE_BYTES)
    private val previewSpektraParameterBuffer = directBuffer(SpektraFilmParams.STRUCT_SIZE)
    private var previewSpektraRevision = Long.MIN_VALUE
    private var previewSpektraParams: SpektraFilmParams? = null
    private val viewfinderPreferences = context.applicationContext.getSharedPreferences("viewfinder", Context.MODE_PRIVATE)
    private val mutableViewfinderSpektraEnabled = MutableStateFlow(
        viewfinderPreferences.getBoolean("spektra_enabled", true),
    )
    val viewfinderSpektraEnabled: StateFlow<Boolean> = mutableViewfinderSpektraEnabled.asStateFlow()

    fun setViewfinderSpektraEnabled(enabled: Boolean) { commands.submit { setViewfinderSpektraEnabledOnWorker(enabled) } }

    private fun setViewfinderSpektraEnabledOnWorker(enabled: Boolean) = synchronized(lock) {
        if (mutableViewfinderSpektraEnabled.value != enabled) resetPerformanceLocked()
        InternalLogRecorder.event("Preview", "spektra_enabled=$enabled")
        viewfinderPreferences.edit().putBoolean("spektra_enabled", enabled).apply()
        mutableViewfinderSpektraEnabled.value = enabled
        previewSpektraRevision = Long.MIN_VALUE
    }
    private var lensShadingBuffer: ByteBuffer? = null
    private var serializedLensShadingMap: LensShadingMapParameters? = null
    private var lastSuccessPublishAtMs = 0L
    private var lensSwitching = false
    // The blurred frame keeps its framing while the next camera session opens.
    private var renderedFraming: PreviewCropTransition.Frame? = null
    private val startupReadiness = PreviewStartupReadiness()

    val status: StateFlow<RawPreviewStatus> = mutableStatus.asStateFlow()
    val captureStatus: StateFlow<RawCaptureStatus> = mutableCaptureStatus.asStateFlow()
    private val burstRecorder: com.unspektrawesome.burst.BurstRecorder = com.unspektrawesome.burst.BurstRecorder(applicationContext,
        onWindowClosed = { synchronized(lock) {
            if (!closed && !burstStatus.value.busy && mutableCaptureStatus.value.phase == RawCapturePhase.CAPTURING) {
                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(phase = RawCapturePhase.IDLE, message = "Ready to capture")
                refreshCaptureReadyLocked()
            }
        } }) { result ->
        synchronized(lock) {
            if (!closed) result.onSuccess { uri ->
                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(phase = if (burstStatus.value.busy) mutableCaptureStatus.value.phase else RawCapturePhase.SUCCEEDED,
                    lastUri = uri, message = "BURST saved", feedbackId = mutableCaptureStatus.value.feedbackId + 1)
            }.onFailure { publishCaptureErrorLocked("Unable to save BURST") }
        }
    }
    internal val burstStatus = burstRecorder.status
    private var panoramaRequest: PendingCapture? = null
    private var panoramaPreviousControls: CameraControlState? = null
    private var panoramaEntryControls: CameraControlState? = null
    private var panoramaAutoOwner: ActiveSession? = null
    private var panoramaManualOwner: ActiveSession? = null
    // Controls in effect before a locked video recording, restored when it stops.
    private var videoLockPreviousControls: CameraControlState? = null
    private var videoLockOwner: ActiveSession? = null
    private val mutablePanoramaExportRequest = MutableStateFlow<String?>(null)
    internal val panoramaExportRequest = mutablePanoramaExportRequest.asStateFlow()
    private val panoramaRecorder = com.unspektrawesome.panorama.PanoramaRecorder(applicationContext, preview = { width, height ->
        val current = synchronized(lock) {
            val request = panoramaRequest
            val owner = active
            val target = renderer
            if (closed || !resumed || !surfaceReady || request == null || owner == null || request.owner !== owner || target == null) null
            else {
                val previewTransform = CaptureGeometry.transform(owner.camera.sensorMetadata.orientationDegrees,
                    LOCKED_VIEWFINDER_ROTATION_DEGREES, owner.camera.facing)
                target to com.unspektrawesome.panorama.panoramaPreviewRotation(previewTransform, request.geometry.transform)
            }
        }
        current?.let { (target, rotation) ->
            com.unspektrawesome.panorama.capturePanoramaPreview(width, height, rotation) { bitmap ->
                runCatching { target.previewSnapshot(bitmap) }.getOrDefault(0)
            }
        }
    }, stopped = {
        commands.submit { synchronized(lock) { restorePanoramaControlsLocked() } }
    }) { result ->
        synchronized(lock) {
            panoramaRequest = null
            result.onSuccess { (id, later) ->
                if (!later) mutablePanoramaExportRequest.value = id
                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                    deferredShots = waitingRawShots(com.unspektrawesome.gallery.GalleryStore.get(applicationContext)))
            }.onFailure { InternalLogRecorder.error("Panorama", "save_failed", it) }
        }
    }
    internal val panoramaState = panoramaRecorder.state

    internal fun consumePanoramaExportRequest() { mutablePanoramaExportRequest.value = null }
    internal fun setPanoramaExportLater(later: Boolean) { panoramaRecorder.setExportLater(later) }
    internal fun setPanoramaProjection(projection: com.unspektrawesome.panorama.PanoramaProjection) { panoramaRecorder.setProjection(projection) }
    internal fun setGridAimRadius(radius: Double) { panoramaRecorder.configureGridAimRadius(radius) }
    internal fun setPanoramaMode(enabled: Boolean, grid: Boolean = false) { commands.submit { synchronized(lock) {
        if (panoramaState.value.busy || mutableVideoState.value.busy || burstStatus.value.busy || pendingCapture != null) return@synchronized
        if (enabled && panoramaState.value.enabled && (panoramaState.value.grid != null) == grid) return@synchronized
        if (!enabled) { leavePanoramaLocked(); return@synchronized }
        val entering = !panoramaState.value.enabled
        mutableVideoState.value = mutableVideoState.value.copy(enabled = false, phase = VideoPhase.IDLE)
        active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
        burstRecorder.setEnabled(false)
        panoramaRecorder.setEnabled(true, grid)
        panoramaRecorder.setForeground(resumed)
        configureGridPreviewLocked()
        if (entering) {
            panoramaEntryControls = mutableCameraControls.value
            panoramaManualOwner = null; panoramaAutoOwner = null
        }
        active?.let { preparePanoramaAutoLocked(it) }
        updateVideoOutputLocked()
        reconcileLocked()
        cropTransition.reset()
        resetPerformanceLocked()
    } } }

    internal fun setPanoramaAuto() { commands.submit { synchronized(lock) {
        if (!panoramaState.value.enabled || panoramaState.value.busy) return@synchronized
        active?.let { preparePanoramaAutoLocked(it, reset = true) }
    } } }

    private fun configureGridPreviewLocked() {
        if (panoramaState.value.grid == null || panoramaState.value.busy) return
        val owner = active ?: return
        panoramaRecorder.configureGrid(captureRotationDegrees(), owner.camera.facing == com.unspektrawesome.camera.LensFacing.FRONT)
        val geometry = displayedPreviewGeometry?.takeIf { it.owner === owner }?.framing?.target ?: return
        val transform = CaptureGeometry.transform(owner.camera.sensorMetadata.orientationDegrees, captureRotationDegrees(), owner.camera.facing)
        val size = transform.outputSize(com.unspektrawesome.camera.Size2d(geometry.cropPixels.width, geometry.cropPixels.height))
        val focal = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
            ?.focalLength?.equivalent(owner.camera) ?: owner.camera.equivalentFocalLengthMm ?: 26f
        panoramaRecorder.configureGridImage(com.unspektrawesome.panorama.gridCaptureProjection(
            geometry.sourceSize.width, geometry.sourceSize.height, size.width, size.height, focal.toDouble()))
    }

    private fun preparePanoramaAutoLocked(owner: ActiveSession, reset: Boolean = false) {
        if (panoramaState.value.busy || !reset && panoramaAutoOwner === owner ||
            owner.session.state() != Camera2RawSession.State.STREAMING || !mutableCameraControls.value.manualExposureSupported) return
        panoramaAutoOwner = owner
        if (mutableCameraControls.value.customAe) resetCustomExposureLocked(owner, AeMode.AUTO)
        else owner.session.setAutoExposure()
        owner.session.setAutoFocus(); owner.session.setAutoWhiteBalance()
        owner.session.setFlashMode(com.unspektrawesome.camera.FlashMode.OFF)
    }

    private fun forcePanoramaManualLocked(owner: ActiveSession) {
        val controls = mutableCameraControls.value
        if (!controls.manualExposureSupported || panoramaManualOwner === owner || owner.session.state() != Camera2RawSession.State.STREAMING) return
        panoramaPreviousControls = controls
        panoramaManualOwner = owner
        if (controls.customAe) resetCustomExposureLocked(owner, AeMode.MANUAL)
        owner.session.freezeCaptureControls(controls.iso, controls.exposureTimeNs)
        mutableCameraControls.value = controls.copy(autoExposure = false, exposureMode = AeMode.MANUAL,
            autoFocus = !controls.manualFocusSupported, autoWhiteBalance = !controls.manualWhiteBalanceSupported)
    }

    private fun applyCameraControlsLocked(owner: ActiveSession, controls: CameraControlState) {
        if (configuredPreferences.aeEngine == AeEngine.SPEKTRA) resetCustomExposureLocked(owner, controls.exposureMode, SensorExposure(controls.iso, controls.exposureTimeNs))
        else if (controls.autoExposure) owner.session.setAutoExposure()
        else { owner.session.setIso(controls.iso); owner.session.setExposureTimeNs(controls.exposureTimeNs) }
        if (controls.autoFocus) owner.session.setAutoFocus() else owner.session.setFocusDistance(controls.focusDistance)
        if (controls.autoWhiteBalance) owner.session.setAutoWhiteBalance()
        else controls.whiteBalanceTemperatureKelvin?.let { owner.session.setWhiteBalanceTemperatureKelvin(it) }
    }

    private fun restorePanoramaControlsLocked() {
        if (panoramaState.value.busy) return
        active?.takeIf { it === panoramaManualOwner }?.let { owner ->
            panoramaPreviousControls?.let { applyCameraControlsLocked(owner, it) }
        }
        panoramaManualOwner = null; panoramaPreviousControls = null; panoramaRequest = null
    }

    private fun leavePanoramaLocked() {
        if (!panoramaState.value.enabled) return
        panoramaRecorder.setEnabled(false)
        active?.takeIf { it === panoramaAutoOwner || it === panoramaManualOwner }?.let { owner ->
            panoramaEntryControls?.let { applyCameraControlsLocked(owner, it) }
            owner.session.setFlashMode(configuredPreferences.flashMode)
        }
        panoramaManualOwner = null; panoramaAutoOwner = null
        panoramaPreviousControls = null; panoramaEntryControls = null
    }

    internal fun togglePanorama() { commands.submit { synchronized(lock) {
        if (panoramaState.value.recording) { panoramaRecorder.stop(); return@synchronized }
        val owner = active ?: return@synchronized
        if (!panoramaState.value.enabled || panoramaState.value.busy || !captureReadyLocked() || !mutableCameraControls.value.manualExposureSupported) return@synchronized
        configureGridPreviewLocked()
        forcePanoramaManualLocked(owner)
        panoramaRequest = prepareCaptureLocked()?.copy(processLater = true, saveOriginalDng = false)
        if (panoramaRequest == null || !panoramaRecorder.start()) restorePanoramaControlsLocked()
    } } }

    internal fun stopPanorama() { panoramaRecorder.stop() }
    internal val burstBufferWork get() = burstRecorder.work

    private fun measuresPerformanceLocked() = configuredPreferences.performanceOverlay || mutableVideoState.value.busy

    fun samplePreviewPerformance(): PreviewPerformance {
        val performance = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (!measuresPerformanceLocked() || mutableStatus.value.phase != RawPreviewPhase.STREAMING) {
                performanceMeter.reset(now)
                PreviewPerformance()
            } else {
                val video = mutableVideoState.value
                performanceMeter.sample(now).copy(video = if (video.enabled)
                    videoRecorder?.samplePerformance(now, previewFrames.pendingFrames)
                        ?: com.unspektrawesome.video.VideoPerformance(droppedFrames = video.droppedFrames)
                    else null)
            }
        }
        val video = performance.video ?: return performance
        val buffer = previewFrames.bufferUsage
        return performance.copy(video = video.copy(memoryUsedBytes = buffer.usedBytes, memoryCapacityBytes = buffer.capacityBytes,
            queueCapacity = buffer.frameCapacity))
    }

    private data class HistogramFrame(val renderer: VulkanRenderer, val generation: Long, val cropAspect: Float)
    private var histogramGeneration = 0L
    private val histogramLock = Any()
    private var histogramSample: Pair<HistogramFrame, RgbHistogram>? = null

    suspend fun sampleHistogram(cropAspect: Float): RgbHistogram? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val key = synchronized(lock) {
            renderer?.takeIf { configuredPreferences.histogramEnabled && mutableStatus.value.phase == RawPreviewPhase.STREAMING }
                ?.let { HistogramFrame(it, histogramGeneration, cropAspect) }
        } ?: return@withContext null
        synchronized(histogramLock) {
            if (histogramSample?.first != key) histogramSample = null
            runCatching { key.renderer.histogramBins(cropAspect, key.generation)?.let(RgbHistogram::fromBins) }
                .getOrNull()?.also { histogramSample = key to it }
            val current = synchronized(lock) {
                renderer === key.renderer && histogramGeneration == key.generation && configuredPreferences.histogramEnabled &&
                    mutableStatus.value.phase == RawPreviewPhase.STREAMING
            }
            histogramSample?.second?.takeIf { current }
        }
    }

    private fun resetPerformanceLocked() {
        performanceGeneration++
        histogramGeneration++
        performanceMeter.reset(SystemClock.elapsedRealtime())
    }

    fun refreshLastPhoto() {
        captureProcessor.execute {
            runCatching { com.unspektrawesome.gallery.GalleryMedia.latestUri(applicationContext) }.onSuccess { uri ->
                synchronized(lock) {
                    if (!closed) mutableCaptureStatus.value = mutableCaptureStatus.value.copy(lastUri = uri)
                }
            }
        }
    }
    val tapMeteringStatus: StateFlow<TapMeteringStatus> =
        mutableTapMeteringStatus.asStateFlow()
    val cameraControls: StateFlow<CameraControlState> = mutableCameraControls.asStateFlow()
    val rawImportCapabilities: StateFlow<Map<RawImportStreamKey, RawImportCapability>> =
        mutableRawImportCapabilities.asStateFlow()

    init {
        synchronized(lock) {
            PersistedCapture.removeInterruptedWrites(captureBufferDirectory)
            val saved = captureBufferDirectory.listFiles { file -> file.extension == "shot" }
                ?.sortedBy { it.name }.orEmpty()
            restoredQueue = saved.isNotEmpty()
            saved.forEach { enqueueCaptureLocked(it) }
        }
        galleryUpdates.launch {
            runCatching {
                val store = com.unspektrawesome.gallery.GalleryStore.get(applicationContext)
                store.shots.collect {
                    synchronized(lock) {
                        if (!closed) mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                            deferredShots = waitingRawShots(store))
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    private fun waitingRawShots(store: com.unspektrawesome.gallery.GalleryStore): Int = store.shots.value.count {
        it.state == com.unspektrawesome.gallery.ExportState.WAITING || it.state == com.unspektrawesome.gallery.ExportState.FAILED
    }

    fun retryQueuedCaptures() { commands.submit {
        synchronized(lock) {
            if (closed) return@synchronized
            val failed = failedExports.filter { it.exists() }.sortedBy { it.name }
            if (failed.isEmpty()) return@synchronized
            failedExports.clear()
            lastExportError = null
            restoredQueue = true
            mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                phase = RawCapturePhase.SAVING, retryableShots = 0,
                feedbackId = mutableCaptureStatus.value.feedbackId + 1,
            )
            captureProcessor.execute {
                val previousRenderer = captureRenderer
                captureRenderer = null
                previousRenderer?.close()
            }
            failed.forEach { enqueueCaptureLocked(it) }
        }
    } }

    fun cancelQueuedCaptures() { commands.submit {
        synchronized(lock) {
            synchronized(PersistedCapture.claimLock) {
                captureBufferDirectory.listFiles { file -> file.extension == "shot" }?.forEach { file ->
                    if (file != PersistedCapture.exportingFile) file.delete()
                }
            }
            restoredQueue = false
            mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                phase = if (bufferedShotCount() > 0) RawCapturePhase.SAVING else RawCapturePhase.CANCELED,
                message = "Processing queue canceled",
                canceledCapture = false,
                feedbackId = mutableCaptureStatus.value.feedbackId + 1,
            )
            publishCaptureProgressLocked()
        }
    } }

    private fun enqueueCaptureLocked(file: java.io.File) {
        publishCaptureProgressLocked()
        captureProcessor.execute {
            try {
                synchronized(PersistedCapture.exportLock) {
                    val controllerClosed = synchronized(lock) { closed }
                    val claimed = synchronized(PersistedCapture.claimLock) {
                        if (!controllerClosed && file.exists()) {
                            PersistedCapture.exportingFile = file
                            true
                        } else false
                    }
                    if (claimed) {
                        try {
                            processStillFrame(file)
                        } finally {
                            PersistedCapture.exportingFile = null
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    publishCaptureProgressLocked()
                }
            }
        }
    }

    fun setExposureCompensation(value: Int): Boolean = commands.submit { setExposureCompensationOnWorker(value) }

    fun stepExposureCompensation(direction: Int): Boolean = commands.submit {
        if (panoramaState.value.busy) return@submit
        synchronized(lock) {
            val owner = active
            if (mutableCameraControls.value.customAe && owner?.exposureController?.mode == AeMode.MANUAL) return@synchronized
            if (owner != null) owner.session.stepExposureCompensation(direction.coerceIn(-1, 1)) {
                lensExposureStore.save(owner.camera.route.routeId, it)
            }
        }
    }

    private fun setExposureCompensationOnWorker(value: Int): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        val owner = active ?: return@synchronized false
        val controls = mutableCameraControls.value
        val compensation = value.coerceIn(controls.exposureCompensationMin, controls.exposureCompensationMax)
        if (!owner.session.setExposureCompensation(compensation)) return@synchronized false
        lensExposureStore.save(owner.camera.route.routeId, compensation)
        true
    }

    fun setAutoExposure(): Boolean = commands.submit { setAutoExposureOnWorker() }

    private fun setAutoExposureOnWorker(): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        val owner = active ?: return@synchronized false
        if (mutableCameraControls.value.customAe) resetCustomExposureLocked(owner, AeMode.AUTO)
        else owner.session.setAutoExposure()
    }

    fun setIso(value: Int): Boolean = commands.submit { setIsoOnWorker(value) }

    private fun setIsoOnWorker(value: Int): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        val owner = active ?: return@synchronized false
        if (mutableCameraControls.value.customAe) {
            val mode = if (owner.exposureController.mode == AeMode.ISO_PRIORITY) AeMode.ISO_PRIORITY else AeMode.MANUAL
            val exposure = currentExposure().copy(iso = value)
            if (mode == AeMode.MANUAL) {
                owner.exposureController.reset(mode, exposure)
                publishCustomControlsLocked(owner)
                owner.session.setIso(value)
            } else resetCustomExposureLocked(owner, mode, exposure)
        } else owner.session.setIso(value)
    }

    fun setExposureTimeNs(value: Long): Boolean = commands.submit { setExposureTimeNsOnWorker(value) }

    private fun setExposureTimeNsOnWorker(value: Long): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        val owner = active ?: return@synchronized false
        if (mutableCameraControls.value.customAe) {
            val mode = if (owner.exposureController.mode == AeMode.SHUTTER_PRIORITY) AeMode.SHUTTER_PRIORITY else AeMode.MANUAL
            if (mode == AeMode.MANUAL) {
                owner.exposureController.reset(mode, currentExposure().copy(timeNs = value))
                publishCustomControlsLocked(owner)
                owner.session.setExposureTimeNs(value)
            } else resetCustomExposureLocked(owner, mode, currentExposure().copy(timeNs = value))
        } else owner.session.setExposureTimeNs(value)
    }

    fun toggleIsoPriority(): Boolean = commands.submit { synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized
        val owner = active ?: return@synchronized
        if (mutableCameraControls.value.customAe) resetCustomExposureLocked(owner, owner.exposureController.mode.toggleIso())
    } }

    fun toggleShutterPriority(): Boolean = commands.submit { synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized
        val owner = active ?: return@synchronized
        if (mutableCameraControls.value.customAe) resetCustomExposureLocked(owner, owner.exposureController.mode.toggleShutter())
    } }

    private fun currentExposure() = mutableCameraControls.value.let { SensorExposure(it.iso, it.exposureTimeNs) }

    private fun exposureBounds(): ExposureBounds = mutableCameraControls.value.let {
        ExposureBounds(it.isoMin, it.isoMax, it.exposureTimeMinNs, it.exposureTimeMaxNs)
    }

    private fun resetCustomExposureLocked(owner: ActiveSession, mode: AeMode, exposure: SensorExposure = currentExposure()): Boolean {
        val bounds = if (mode == AeMode.MANUAL) exposureBounds() else configuredPreferences.aeLimits().resolve(exposureBounds())
        val next = bounds.clamp(exposure)
        val generation = owner.exposureController.reset(mode, next)
        owner.lastExposureSampleNs = 0
        publishCustomControlsLocked(owner)
        return owner.session.setSensorExposure(next.iso, next.timeNs, generation, mode != AeMode.MANUAL)
    }

    private fun publishCustomControlsLocked(owner: ActiveSession, controls: CameraControlState = mutableCameraControls.value) {
        mutableCameraControls.value = if (controls.customAe) controls.copy(
            autoExposure = owner.exposureController.mode != AeMode.MANUAL,
            exposureMode = owner.exposureController.mode,
            exposureLimited = owner.exposureController.limited,
        ) else controls
    }

    private fun effectiveCaptureSnapshot(): SpektraCaptureSnapshot {
        val saved = spektraRepository.captureSnapshot()
        val snapshot = saved.copy(state = saved.state.copy(
            params = com.unspektrawesome.spektra.SpektraCameraContract.resolve(saved.params)))
        return if (mutableCameraControls.value.customAe || panoramaState.value.enabled) snapshot.copy(
            state = snapshot.state.copy(params = snapshot.params.copy(autoExposure = false))) else snapshot
    }

    fun applyLongShutter(): Boolean = commands.submit {
        synchronized(lock) { active?.session?.applyLongShutter() }
    }

    fun cancelLongShutter(): Boolean = commands.submit {
        synchronized(lock) { active?.session?.cancelLongShutter() }
    }

    fun restartPassiveFocus(): Boolean = commands.submit {
        synchronized(lock) { active?.session?.restartPassiveFocus() }
    }

    fun setAutoFocus(): Boolean = commands.submit { setAutoFocusOnWorker() }

    private fun setAutoFocusOnWorker(): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        active?.session?.setAutoFocus() ?: false
    }

    fun setFocusDistance(value: Float): Boolean = commands.submit { setFocusDistanceOnWorker(value) }

    /** Shows the focus loupe on the following frames, or hides it with null. */
    fun setFocusLoupe(request: FocusLoupeRequest?) {
        val accepted = request?.takeIf { it.valid }
        synchronized(lock) {
            if (accepted == focusLoupeRequest) return
            val previous = focusLoupeRequest
            focusLoupeRequest = accepted
            focusLoupeGeneration++
            // Moving the loupe window keeps the comparison; a new target or zoom starts a new one.
            if (accepted == null || previous == null || !accepted.measuresSameArea(previous))
                focusLoupeMeterStart = focusLoupeGeneration
        }
        focusLoupeMotion.setActive(accepted != null)
        if (accepted == null) mutableFocusLoupeStatus.value = FocusLoupeStatus()
    }

    /**
     * [focalPixels] is the lens focal length in RAW pixels, which turns camera rotation into the
     * distance the loupe area moved across the subject.
     */
    private fun publishFocusLoupe(values: FloatArray, geometry: FrameGeometrySnapshot, focalPixels: Float) {
        if (values.size < FocusLoupeResult.SIZE) return
        val available = values[1] > .5f
        val now = SystemClock.elapsedRealtime()
        val start = focusLoupeMeterStart
        if (start != focusLoupeMeterApplied) {
            focusLoupeMeterApplied = start
            focusLoupeMeter.reset()
            focusLoupeMotion.resetReference()
        }
        // Turning the camera by a third of the loupe's view shows a different subject: compare afresh.
        val shownPixels = minOf(values[4], values[5])
        if (available && shownPixels > 0f && focalPixels > 0f &&
            focusLoupeMotion.turnedSinceReference() * focalPixels > shownPixels / 3f) {
            focusLoupeMeter.reset()
            focusLoupeMotion.resetReference()
        }
        val sequence = values[FocusLoupeResult.SEQUENCE]
        if (available && values[0] >= start && sequence != focusLoupeMeterSequence) {
            focusLoupeMeterSequence = sequence
            val lightweight = values[FocusLoupeResult.LIGHTWEIGHT] > .5f
            if (focusLoupeMeterLightweight != lightweight) {
                focusLoupeMeterLightweight = lightweight
                focusLoupeMeter = FocusLoupeMeter(FocusLoupeResult.fineNoise(lightweight))
            }
            val position = if (mutableCameraControls.value.autoFocus) Float.NaN else focusLoupeFocusPosition
            focusLoupeMeter.update(FocusLoupeResult.reading(values, position))
        }
        val current = mutableFocusLoupeStatus.value
        // About 15 updates a second are enough for the focus meter.
        if (current.requested && current.available == available && now - focusLoupePublishedAtMs < 66L) return
        focusLoupePublishedAtMs = now
        if (!available) {
            mutableFocusLoupeStatus.value = FocusLoupeStatus(requested = true)
            return
        }
        val sideways = geometry.transform.orientationDegrees % 180 != 0
        val center = FocusLoupeMapping.surfacePoint(geometry, values[6], values[7])
        val (surfaceWidth, surfaceHeight) = FocusLoupeMapping.surfaceRawSize(geometry)
        mutableFocusLoupeStatus.value = FocusLoupeStatus(
            requested = true, available = true, meter = focusLoupeMeter.state,
            sourceWidth = (if (sideways) values[5] else values[4]).toInt(),
            sourceHeight = (if (sideways) values[4] else values[5]).toInt(),
            centerX = center.x, centerY = center.y, surfaceRawWidth = surfaceWidth, surfaceRawHeight = surfaceHeight,
        )
    }

    // The 35 mm equivalent focal length spans the full-frame diagonal, 43.27 mm, across the active array.
    private fun focusLoupeFocalPixels(owner: ActiveSession, geometry: FrameGeometrySnapshot): Float {
        val equivalent = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
            ?.focalLength?.equivalent(owner.camera) ?: owner.camera.equivalentFocalLengthMm ?: return 0f
        val (width, height) = FocusLoupeMapping.surfaceRawSize(geometry)
        return equivalent / 43.2666f * kotlin.math.hypot(width, height)
    }

    fun setFocusPeakingVisible(visible: Boolean) {
        commands.submit { synchronized(lock) { focusPeakingVisible = visible } }
    }

    private fun setFocusDistanceOnWorker(value: Float): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        val nearest = mutableCameraControls.value.minimumFocusDistance
        // Square root of the diopters, as the focus ruler spaces distances.
        focusLoupeFocusPosition = if (value.isFinite() && nearest > 0f) kotlin.math.sqrt((value / nearest).coerceIn(0f, 1f))
            else Float.NaN
        active?.session?.setFocusDistance(value) ?: false
    }

    fun setAutoWhiteBalance(): Boolean = commands.submit { setAutoWhiteBalanceOnWorker() }

    private fun setAutoWhiteBalanceOnWorker(): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        active?.session?.setAutoWhiteBalance() ?: false
    }

    fun setWhiteBalanceTemperatureKelvin(value: Int): Boolean = commands.submit { setWhiteBalanceTemperatureKelvinOnWorker(value) }

    private fun setWhiteBalanceTemperatureKelvinOnWorker(value: Int): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        active?.session?.setWhiteBalanceTemperatureKelvin(value) ?: false
    }

    fun tapToMeter(normalizedX: Float, normalizedY: Float): Boolean = commands.submit {
        val exposure = tapToMeterOnWorker(normalizedX, normalizedY, exposure = true)
        val focus = mutableCameraControls.value.autoFocus && tapToMeterOnWorker(normalizedX, normalizedY, exposure = false)
        exposure || focus
    }

    fun focusAt(normalizedX: Float, normalizedY: Float): Boolean = commands.submit {
        tapToMeterOnWorker(normalizedX, normalizedY, exposure = false)
    }

    fun meterExposure(normalizedX: Float, normalizedY: Float): Boolean = commands.submit {
        tapToMeterOnWorker(normalizedX, normalizedY, exposure = true)
    }

    fun clearExposurePoint(): Boolean = commands.submit {
        synchronized(lock) {
            active?.let { owner ->
                owner.exposurePoint = null
                if (mutableCameraControls.value.customAe) owner.exposureController.resetMetering()
            }
            mutableExposureMeteringStatus.value = TapMeteringStatus()
            active?.session?.clearExposurePoint() ?: false
        }
    }

    private fun tapToMeterOnWorker(normalizedX: Float, normalizedY: Float, exposure: Boolean): Boolean = synchronized(lock) {
        if (panoramaState.value.busy) return@synchronized false
        if (!normalizedX.isFinite() || !normalizedY.isFinite() ||
            normalizedX !in 0f..1f || normalizedY !in 0f..1f ||
            closed || !resumed || pendingCapture != null
        ) {
            return@synchronized false
        }
        val owner = active ?: return@synchronized false
        val previewGeometry = displayedPreviewGeometry
            ?.takeIf { it.owner === owner }
            ?: return@synchronized false
        val sensorPoint = TapMeteringMapper.map(
            previewGeometry.framing.target.let { it.copy(sourceCrop = it.activeArray) },
            normalizedX,
            normalizedY,
        )
        val generation = ++tapMeteringGeneration
        if (exposure && mutableCameraControls.value.customAe) {
            if (owner.exposureController.mode == AeMode.MANUAL) return@synchronized false
            owner.exposurePoint = normalizedX to normalizedY
            mutableExposureMeteringStatus.value = TapMeteringStatus(generation, normalizedX, normalizedY, TapMeteringPhase.METERING)
            exposureScene.reset(SystemClock.elapsedRealtime())
            owner.exposureController.resetMetering()
            publishCustomControlsLocked(owner)
            return@synchronized true
        }
        val accepted = if (exposure) owner.session.setExposurePoint(sensorPoint.x, sensorPoint.y, generation)
            else owner.session.setFocusPoint(sensorPoint.x, sensorPoint.y, generation)
        if (!accepted) {
            return@synchronized false
        }
        val status = if (exposure) mutableExposureMeteringStatus else mutableTapMeteringStatus
        (if (exposure) exposureScene else focusScene).reset(SystemClock.elapsedRealtime())
        status.value = TapMeteringStatus(
            generation = generation,
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            phase = TapMeteringPhase.METERING,
        )
        true
    }

    fun captureStill(): Boolean {
        if (panoramaState.value.enabled) return false
        if (mutableVideoState.value.enabled || mutableVideoState.value.busy) return false
        if (burstRecorder.status.value.enabled) return synchronized(lock) {
            if (!captureReadyLocked() || !burstRecorder.capture()) false else {
                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(phase = RawCapturePhase.CAPTURING,
                    message = "Capturing BURST", shutterFeedbackId = mutableCaptureStatus.value.shutterFeedbackId + 1,
                    feedbackId = mutableCaptureStatus.value.feedbackId + 1)
                true
            }
        }
        val owner = synchronized(lock) {
            active?.takeIf { captureReadyLocked(it) }.also {
                if (it == null) InternalLogRecorder.event("CaptureGate",
                    "rejected route=${active?.camera?.route?.routeId} generation=$surfaceGeneration " +
                        "phase=${mutableStatus.value.phase} sessionReady=${active?.startupReadiness?.isReady}")
            }
        } ?: return false
        return commands.submit { captureStillOnWorker(owner) }
    }

    private fun captureStillOnWorker(expectedOwner: ActiveSession): Boolean {
        val request = synchronized(lock) {
            if (active !== expectedOwner || !captureReadyLocked(expectedOwner)) {
                InternalLogRecorder.event("CaptureGate", "rejected route=${expectedOwner.camera.route.routeId} " +
                    "active=${active?.camera?.route?.routeId} generation=$surfaceGeneration")
                null
            } else prepareCaptureLocked()
        } ?: return false
        if (startMergedCapture(request)) return true
        // Record that merge was on but this photo uses only the frame shown at the shutter press.
        if (request.merge.mergeEnabled) request.mergeFallback = com.unspektrawesome.capture.MergeShotMetadata(
            request.merge.mergeFrames, request.merge.mergeMotion, merged = 1, averagePerPixel = 1f)
        if (request.processLater) {
            val bytes = request.owner.packedFrameBytes
            if (bytes <= 0) return false
            val buffer = runCatching { deferredBuffers.reserve(bytes) }.getOrElse { error ->
                synchronized(lock) { publishCaptureErrorLocked("Unable to buffer RAW: ${error.message}") }
                return false
            } ?: return false
            synchronized(lock) {
                if (closed || !resumed || active !== request.owner || !request.owner.acceptsFrames) {
                    publishCaptureErrorLocked("RAW capture was canceled", request.snapshot.revisionId)
                    buffer.cancel()
                    return false
                }
                deferredCaptures.addLast(request.copy(rawBuffer = buffer))
                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(phase = RawCapturePhase.CAPTURING,
                    feedbackId = mutableCaptureStatus.value.feedbackId + 1,
                    shutterFeedbackId = mutableCaptureStatus.value.shutterFeedbackId + 1)
                publishCaptureProgressLocked(request.snapshot.revisionId)
                startNextDeferredCaptureLocked()
            }
            return true
        }
        return synchronized(lock) { startCaptureLocked(request) }
    }

    /** Multi-frame merge settings come from the active lens profile. */
    private fun mergeRingSettings(owner: ActiveSession): MultiFrameRing.Settings {
        val capture = configuredPreferences.lensCaptureSettings()
        val photoMode = !mutableVideoState.value.enabled && !burstRecorder.status.value.enabled && !panoramaState.value.enabled
        return if (capture.mergeEnabled && photoMode && active === owner && owner.acceptsFrames)
            MultiFrameRing.Settings(true, capture.mergeFrames) else MultiFrameRing.Settings.DISABLED
    }

    /** Merges the buffered viewfinder frames that end with the frame shown at the shutter press. */
    private fun startMergedCapture(request: PendingCapture): Boolean {
        val merge = request.merge
        if (!merge.mergeEnabled || merge.mergeFrames < MultiFrameSelection.MIN_FRAMES) return false
        if (request.exposureTimeNs >= 1_000_000_000L || configuredPreferences.flashMode.usesCaptureFlash()) return false
        mergeRing.awaitPendingCopies(MERGE_COPY_WAIT_MS)
        val frames = mergeRing.snapshot(merge.mergeFrames)
        val expectedOutput = request.owner.key.rawLayoutOverride.output(request.captureOutput)
        val reference = frames.firstOrNull()
        val selection = if (reference != null && reference.output == expectedOutput &&
            SystemClock.elapsedRealtime() - reference.receivedAtMs <= STALE_MERGE_FRAME_MS)
            MultiFrameSelection.select(frames.map { it.candidate() }, merge.mergeFrames)
        else MultiFrameSelection.Selection(0, if (reference == null) "empty" else if (reference.output != expectedOutput) "output" else "stale")
        val usable = selection.count
        if (selection.reason != null) InternalLogRecorder.event("MultiFrame", "selection buffered=${frames.size} usable=$usable " +
            "dropped=${mergeRing.dropped} stopped_by=${selection.reason}")
        if (usable < MultiFrameSelection.MIN_FRAMES) {
            InternalLogRecorder.event("MultiFrame", "skipped buffered=${frames.size} usable=$usable " +
                "dropped=${mergeRing.dropped} route=${request.owner.camera.route.routeId}")
            frames.forEach { it.release() }
            return false
        }
        frames.drop(usable).forEach { it.release() }
        val selected = frames.take(usable)
        val outputBytes = expectedOutput.size.width * expectedOutput.size.height * 2
        val buffer = if (request.processLater) {
            runCatching { deferredBuffers.reserve(outputBytes) }.getOrElse { error ->
                selected.forEach { it.release() }
                synchronized(lock) { publishCaptureErrorLocked("Unable to buffer RAW: ${error.message}") }
                return false
            } ?: run { selected.forEach { it.release() }; return false }
        } else null
        val merged = request.copy(rawBuffer = buffer)
        synchronized(lock) {
            if (closed || !resumed || active !== request.owner || !request.owner.acceptsFrames) {
                publishCaptureErrorLocked("RAW capture was canceled", request.snapshot.revisionId)
                buffer?.cancel()
                selected.forEach { it.release() }
                return false
            }
            mergingShots++
            mutableCaptureStatus.value = mutableCaptureStatus.value.copy(phase = RawCapturePhase.CAPTURING,
                canceledCapture = false, feedbackId = mutableCaptureStatus.value.feedbackId + 1,
                shutterFeedbackId = mutableCaptureStatus.value.shutterFeedbackId + 1)
            publishCaptureProgressLocked(request.snapshot.revisionId)
        }
        InternalLogRecorder.event("Capture", "merge_request capturedAt=${request.capturedAtMillis} frames=$usable " +
            "route=${request.owner.camera.route.routeId} deferred=${request.processLater} revision=${request.snapshot.revisionId}")
        try {
            mergeProcessor.execute { runMergedCapture(merged, selected) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            selected.forEach { it.release() }
            synchronized(lock) {
                mergingShots--
                buffer?.cancel()
                publishCaptureErrorLocked("RAW capture was canceled", request.snapshot.revisionId)
            }
        }
        return true
    }

    private fun runMergedCapture(request: PendingCapture, frames: List<MultiFrameRing.Frame>) {
        val reference = frames[0]
        val startedAt = SystemClock.elapsedRealtime()
        val owned = runCatching {
            val prepared = preparePersistedCapture(request, reference.metadata, reference.output, reference.rowBytes, log = true)
            val width = reference.output.size.width
            val height = reference.output.size.height
            val outputBytes = width * height * 2
            val target = request.rawBuffer?.pixels?.also {
                check(it.capacity() == outputBytes) { "RAW frame size changed during capture" }
            } ?: directBuffer(outputBytes)
            target.clear()
            val effective = runCatching { mergeFrames(prepared, frames, request.merge.mergeMotion, target) }
                .onFailure { InternalLogRecorder.error("MultiFrame", "merge_failed capturedAt=${request.capturedAtMillis}", it) }
                .getOrDefault(0f)
            val merged = effective > 0f
            val capture = prepared.copy(metadata = prepared.metadata.copy(merge = com.unspektrawesome.capture.MergeShotMetadata(
                request.merge.mergeFrames, request.merge.mergeMotion,
                merged = if (merged) frames.size else 1, averagePerPixel = if (merged) effective else 1f)))
            if (merged) {
                capture.raw.putInt(RawShaderParameters.LAYOUT_INFO_OFFSET, width * 2)
                capture.raw.putInt(RawShaderParameters.LAYOUT_INFO_OFFSET + 4, com.unspektrawesome.camera.RawFormat.RAW_SENSOR.ordinal)
                // Averaging frames lowers the noise the chroma denoise should expect.
                val color = capture.color
                color.putFloat(RawColorShaderParameters.NOISE_PROFILE_S_OFFSET,
                    color.getFloat(RawColorShaderParameters.NOISE_PROFILE_S_OFFSET) / effective)
                color.putFloat(RawColorShaderParameters.NOISE_PROFILE_O_OFFSET,
                    color.getFloat(RawColorShaderParameters.NOISE_PROFILE_O_OFFSET) / effective)
            }
            // When the merge is unavailable the photo falls back to the frame shown at the shutter press.
            val pixels = if (merged) target else reference.pixels
            InternalLogRecorder.event("MultiFrame", "capture capturedAt=${request.capturedAtMillis} frames=${frames.size} " +
                "merged=$merged effective=${"%.2f".format(java.util.Locale.ROOT, effective)} " +
                "format=${reference.output.format} ${reference.output.size} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            if (request.processLater) {
                val buffer = requireNotNull(request.rawBuffer)
                if (!merged) {
                    val source = pixels.duplicate().apply { clear() }
                    buffer.pixels.clear()
                    buffer.pixels.put(source).flip()
                } else buffer.pixels.clear()
                val result = reference.captureResult
                buffer.save {
                    val saved = runCatching {
                        deferredWriter.save(capture, buffer.pixels, result,
                            cameraManager.getCameraCharacteristics(request.owner.camera.route.characteristicsCameraId))
                    }
                    synchronized(lock) {
                        if (!closed) saved.onSuccess {
                            mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                                deferredShots = waitingRawShots(com.unspektrawesome.gallery.GalleryStore.get(applicationContext)),
                                message = "RAW saved for Gallery")
                        }.onFailure { error ->
                            InternalLogRecorder.error("Capture", "dng_save_failed capturedAt=${request.capturedAtMillis}", error)
                            publishCaptureErrorLocked("Unable to save original RAW: ${error.message}", request.snapshot.revisionId)
                        }
                    }
                }
                null
            } else capture.store(captureBufferDirectory, pixels)
        }
        if (owned.isFailure) request.rawBuffer?.cancel()
        frames.forEach { it.release() }
        synchronized(lock) {
            mergingShots--
            if (closed) return
            owned.onSuccess { copied ->
                if (copied != null) enqueueCaptureLocked(copied) else publishCaptureProgressLocked()
            }.onFailure { error ->
                InternalLogRecorder.error("Capture", "merge_capture_failed capturedAt=${request.capturedAtMillis}", error)
                publishCaptureErrorLocked("Multi-frame merge failed: ${error.message}", request.snapshot.revisionId)
            }
        }
    }

    private fun mergeFrames(capture: PersistedCapture, frames: List<MultiFrameRing.Frame>, motion: Float, target: ByteBuffer): Float {
        val raw = capture.raw
        val reference = frames[0]
        val candidates = frames.map { it.candidate() }
        val scales = FloatArray(frames.size) { MultiFrameSelection.exposureScale(candidates[0], candidates[it]) }
        val black = FloatArray(4) { raw.getFloat(RawShaderParameters.BLACK_LEVEL_OFFSET + it * 4) }
        // The robustness threshold needs the sensor noise profile even when no denoise is enabled.
        val noise = com.unspektrawesome.vulkan.params.ChromaNoiseProfile.resolveNoiseProfilePair(
            reference.metadata.noiseProfile(), reference.metadata.sensitivityIso ?: 100)
        return com.unspektrawesome.burst.MultiFrameMerge.merge(
            Array(frames.size) { frames[it].pixels }, scales,
            reference.output.size.width, reference.output.size.height, reference.rowBytes, reference.output.format.ordinal,
            raw.getInt(RawShaderParameters.REMOSAIC_OFFSET), raw.getInt(RawShaderParameters.REMOSAIC_OFFSET + 4),
            raw.getInt(RawShaderParameters.REMOSAIC_OFFSET + 8), raw.getInt(RawShaderParameters.REMOSAIC_OFFSET + 12),
            black, raw.getFloat(RawShaderParameters.WHITE_LEVEL_OFFSET),
            noise[0], noise[1],
            motion, target)
    }

    private fun captureReadyLocked(owner: ActiveSession? = active): Boolean =
        owner != null && active === owner && !closed && resumed && surfaceReady &&
            mutableCameraControls.value.pendingExposureTimeNs == null && !mutableCaptureStatus.value.shutterBusy &&
            attachedSurface?.isValid == true && owner.acceptsFrames && !lensSwitching &&
            owner.camera.route == configuredCamera?.route && startupReadiness.isReady &&
            owner.lastRenderSucceeded &&
            displayedPreviewGeometry?.owner === owner &&
            (owner.session.state() == Camera2RawSession.State.STREAMING ||
                configuredPreferences.processPhotosLater && pendingCapture?.owner === owner)

    private fun refreshCaptureReadyLocked(owner: ActiveSession? = active) {
        val ready = mutableStatus.value.phase == RawPreviewPhase.STREAMING && captureReadyLocked(owner)
        mutableStatus.value = mutableStatus.value.copy(
            captureReady = ready,
            captureRouteId = if (ready) owner?.camera?.route?.routeId else null,
        )
    }

    private fun prepareCaptureLocked(): PendingCapture? {
        if (closed || !resumed) {
            publishCaptureErrorLocked("RAW preview is not active")
            return null
        }
        if (!configuredPreferences.processPhotosLater &&
            (pendingCapture != null || deferredCaptures.isNotEmpty() ||
                bufferedShotCount() + mergingShots >= RawCaptureStatus.CAPACITY)) {
            return null
        }
        val owner = active
        if (owner == null || !owner.acceptsFrames ||
            pendingCapture == null && owner.session.state() != Camera2RawSession.State.STREAMING) {
            publishCaptureErrorLocked("RAW preview is not ready for capture")
            return null
        }
        return runCatching {
            val snapshot = effectiveCaptureSnapshot()
            val transform = CaptureGeometry.transform(
                owner.camera.sensorMetadata.orientationDegrees,
                captureRotationDegrees(),
                owner.camera.facing,
            )
            val captureOutput = owner.plan.acquisitionOutput
            val crop = displayedPreviewGeometry?.takeIf { it.owner === owner }?.framing?.crop
                ?: configuredPreferences.photoCrop
            val geometry = FrameGeometrySnapshot.createForPhotoCrop(
                owner.camera.sensorMetadata,
                owner.key.rawLayoutOverride.output(captureOutput),
                transform,
                crop,
                rawEdgeCrop = configuredPreferences.rawEdgeCrop(),
            )
            PendingCapture(
                owner,
                snapshot,
                captureOutput,
                geometry,
                latestLensShadingMap,
                snapshot.params.toByteBuffer(),
                System.nanoTime() * NANOSECONDS_TO_SECONDS,
                System.currentTimeMillis(),
                configuredPreferences.rawLevelCalibration(),
                configuredPreferences.processPhotosLater,
                saveOriginalDng = configuredPreferences.saveOriginalDng,
                cfaOverride = configuredPreferences.cfaOverride(),
                exposureTimeNs = mutableCameraControls.value.exposureTimeNs,
                color = requireNotNull(configuredColor).getOrThrow(),
                location = if (configuredPreferences.saveLocation) locationSnapshot() else null,
                equivalentFocalLengthMm = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
                    ?.focalLength?.equivalent(owner.camera) ?: owner.camera.equivalentFocalLengthMm,
                lensProfile = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
                    ?.let { com.unspektrawesome.capture.LensProfileShotMetadata.from(it, owner.key.rawLayoutOverride.output(captureOutput)) },
                merge = configuredPreferences.lensCaptureSettings(),
            )
        }.getOrElse { error ->
            publishCaptureErrorLocked(
                "Full-resolution RAW capture is unavailable: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun startNextDeferredCaptureLocked() {
        if (pendingCapture != null || closed) return
        while (deferredCaptures.isNotEmpty()) {
            val next = deferredCaptures.removeFirst()
            if (startCaptureLocked(next)) return
        }
    }

    private fun startCaptureLocked(request: PendingCapture): Boolean {
        val owner = request.owner
        InternalLogRecorder.event("Capture", "request capturedAt=${request.capturedAtMillis} route=${owner.camera.route.routeId} " +
            "deferred=${request.processLater} revision=${request.snapshot.revisionId} " +
            "output=${request.captureOutput.format} ${request.captureOutput.size}")
        if (!resumed || active !== owner || !owner.acceptsFrames || owner.session.state() != Camera2RawSession.State.STREAMING) {
            request.rawBuffer?.cancel()
            publishCaptureErrorLocked("RAW capture was canceled", request.snapshot.revisionId)
            return false
        }
        pendingCapture = request
        mutableCaptureStatus.value = mutableCaptureStatus.value.copy(canceledCapture = false)
        if (!request.processLater) mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
            phase = RawCapturePhase.CAPTURING, feedbackId = mutableCaptureStatus.value.feedbackId + 1,
            shutterFeedbackId = mutableCaptureStatus.value.shutterFeedbackId + 1)
        publishCaptureProgressLocked(request.snapshot.revisionId)
        return runCatching {
            if (request.exposureTimeNs < 1_000_000_000L) owner.session.capturePreviewFrame(stillCallback(request))
            else owner.session.captureStill(listOf(request.captureOutput), stillCallback(request))
        }.onFailure { error ->
            pendingCapture = null
            request.rawBuffer?.cancel()
            InternalLogRecorder.error("Capture", "request_failed capturedAt=${request.capturedAtMillis}", error)
            publishCaptureErrorLocked(
                "Full-resolution RAW request failed: ${error.message ?: error.javaClass.simpleName}",
                request.snapshot.revisionId,
            )
        }.isSuccess
    }

    private var discoverySuspended = false

    suspend fun suspendForLensDiscovery(): Boolean {
        val stopped = kotlinx.coroutines.CompletableDeferred<Camera2RawSession?>()
        if (!commands.submit {
            synchronized(lock) {
                discoverySuspended = true
                resetSessionRecoveryLocked()
                val session = active?.session
                stopActiveLocked()
                stopped.complete(session)
            }
        }) return false
        val session = kotlinx.coroutines.withTimeoutOrNull(4_000) { stopped.await() } ?: return stopped.isCompleted
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.awaitReleased(3_000) }
    }

    fun resumeAfterLensDiscovery() { commands.submit {
        synchronized(lock) { discoverySuspended = false; reconcileLocked() }
    } }

    // Stops the camera and viewfinder rendering while an app update is offered.
    private var updateSuspended = false

    fun setUpdateSuspended(suspended: Boolean) { commands.submit {
        synchronized(lock) {
            if (closed || updateSuspended == suspended) return@synchronized
            updateSuspended = suspended
            InternalLogRecorder.event("Preview", "update_suspended=$suspended")
            if (suspended) cancelSessionRecoveryLocked()
            else {
                lastCameraFrameAtMs = SystemClock.elapsedRealtime()
                lastRenderCompletedAtMs = lastCameraFrameAtMs
            }
            reconcileLocked()
        }
    } }

    fun configure(camera: CameraDescriptor?, preferences: CameraPreferences) { commands.submit { configureOnWorker(camera, preferences) } }

    private fun configureOnWorker(camera: CameraDescriptor?, preferences: CameraPreferences) = synchronized(lock) {
        check(!closed) { "Preview controller is closed" }
        if (configuredCamera?.route != camera?.route ||
            configuredPreferences.activeLensProfileId != preferences.activeLensProfileId ||
            configuredPreferences.rawPreviewStreams[configuredCamera?.route?.routeId] !=
                preferences.rawPreviewStreams[camera?.route?.routeId]) {
            lensSwitchExposure = if (configuredPreferences.aeEngine == AeEngine.SPEKTRA &&
                preferences.aeEngine == AeEngine.SPEKTRA) {
                active?.takeIf { it.acceptsFrames && it.controlSessionGeneration == controlSessionGeneration }
                    ?.exposureController?.takeIf { it.generation > 0L }?.requestedExposure
                    ?: retainedControls?.takeIf { it.externalAutoExposure }?.let { SensorExposure(it.iso, it.exposureTimeNs) }
                    ?: lensSwitchExposure
            } else null
            controlSessionGeneration++
            retainedControls = null
            retainedExposureMode = AeMode.AUTO
        }
        val engineChanged = configuredPreferences.aeEngine != preferences.aeEngine
        val meterChanged = configuredPreferences.aeMeter != preferences.aeMeter
        val balanceChanged = configuredPreferences.aeBalance() != preferences.aeBalance()
        val exposureChanged = engineChanged ||
            configuredPreferences.cfaOverride() != preferences.cfaOverride() ||
            configuredPreferences.rawLayoutOverride() != preferences.rawLayoutOverride() ||
            configuredPreferences.rawEdgeCrop() != preferences.rawEdgeCrop() ||
            configuredPreferences.aeLimits() != preferences.aeLimits() ||
            configuredPreferences.activeLensProfileId != preferences.activeLensProfileId
        val sessionChanged = active?.let { owner ->
            camera != null && runCatching {
                val plan = RawPreviewPlanSelector.select(camera, preferences,
                    importCapabilities = mutableRawImportCapabilities.value, frameRateOverride = videoFrameRateOverride())
                owner.key != ConfigurationKey(camera, plan, preferences, videoFrameRateOverride())
            }.getOrDefault(true)
        } == true
        val previousRawSession = configuredPreferences.lensProfiles.firstOrNull {
            it.id == configuredPreferences.activeLensProfileId && it.enabled
        }?.rawSession
        val nextRawSession = preferences.lensProfiles.firstOrNull {
            it.id == preferences.activeLensProfileId && it.enabled
        }?.rawSession
        val changed = previousRawSession != nextRawSession || configuredCamera?.route != camera?.route ||
            configuredPreferences.rawLayoutOverride() != preferences.rawLayoutOverride() ||
            configuredPreferences.vendorOverrides() != preferences.vendorOverrides() ||
            configuredPreferences.lensCaptureSettings().sessionSettings() != preferences.lensCaptureSettings().sessionSettings() ||
            configuredPreferences.previewQuality != preferences.previewQuality ||
            configuredPreferences.previewShortEdge != preferences.previewShortEdge ||
            configuredPreferences.viewfinderSource != preferences.viewfinderSource ||
            configuredPreferences.rawPreviewStreams != preferences.rawPreviewStreams ||
            configuredPreferences.rawEdgeCrop() != preferences.rawEdgeCrop() ||
            configuredPreferences.activeLensProfileId != preferences.activeLensProfileId
        if (sessionChanged) {
            lensSwitching = true
            active?.let { owner ->
                owner.acceptsFrames = false
            }
            publishLocked(RawPreviewPhase.OPENING, "Switching lens")
            scheduleRendererTask {
                val currentRenderer = synchronized(lock) {
                    if (closed) null else renderer
                }
                if (currentRenderer != null) {
                    runCatching { currentRenderer.beginLensSwitchBlur() }
                        .onSuccess { updateRendererDiagnostic(currentRenderer) }
                }
            }
        }
        if (configuredCamera?.route != camera?.route || configuredPreferences != preferences) {
            InternalLogRecorder.event("CameraSettings", "route=${camera?.route?.routeId} capture=${preferences.lensCaptureSettings()} " +
                "previewEdge=${preferences.previewShortEdge} deferred=${preferences.processPhotosLater} " +
                "aeEngine=${preferences.aeEngine} aeMeter=${preferences.aeMeter} " +
                "streams=${preferences.rawPreviewStreams} calibration=${preferences.rawLevelCalibration()}")
        }
        if (camera != null && (configuredCamera !== camera || configuredColor?.isSuccess != true ||
                configuredPreferences.lensColorSettings() != preferences.lensColorSettings())) {
            configuredColor = runCatching {
                val settings = preferences.lensColorSettings()
                val dcp = if (settings.dcpEnabled) dcpRepository.load(requireNotNull(settings.dcpId) {
                    "Import the missing DCP again or turn custom DCP off in this lens profile."
                }) else null
                ResolvedLensColor(settings, camera.sensorMetadata.colorCalibration, dcp)
            }
        }
        if (changed || configuredPreferences.performanceOverlay != preferences.performanceOverlay)
            resetPerformanceLocked()
        if (configuredPreferences.histogramEnabled != preferences.histogramEnabled) histogramGeneration++
        if (configuredPreferences.flashMode != preferences.flashMode) active?.session?.setFlashMode(
            if (panoramaState.value.enabled) com.unspektrawesome.camera.FlashMode.OFF else preferences.flashMode)
        configuredCamera = camera
        configuredPreferences = preferences
        burstRecorder.configureHistory(preferences.burstPreShutterSeconds)
        if (restoreLensStabilizationLocked() && !sessionChanged)
            active?.session?.setOpticalStabilization(mutableVideoState.value.requestedOis)
        if (!mutableVideoState.value.busy) updateVideoOutputLocked()
        if (exposureChanged && !sessionChanged) active?.let { owner ->
            if (engineChanged) owner.session.setExternalAutoExposure(preferences.aeEngine == AeEngine.SPEKTRA)
            if (preferences.aeEngine == AeEngine.SPEKTRA && mutableCameraControls.value.manualExposureSupported) {
                resetCustomExposureLocked(owner, if (panoramaState.value.busy) AeMode.MANUAL else if (engineChanged) AeMode.AUTO else owner.exposureController.mode)
            } else if (panoramaState.value.busy) {
                owner.session.setIso(mutableCameraControls.value.iso)
                owner.session.setExposureTimeNs(mutableCameraControls.value.exposureTimeNs)
            }
        }
        if (meterChanged || balanceChanged) active?.exposureController?.resetMetering()
        mutableCaptureStatus.value = mutableCaptureStatus.value.copy(processingDeferred = preferences.processPhotosLater)
        if (changed) {
            resetSessionRecoveryLocked()
            blockedConfiguration = null
            latestWhiteBalance = null
            latestLensShadingMap = null
            clearTapMeteringLocked()
        }
        scheduleReconcileLocked()
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        commands.submit { attachSurfaceOnWorker(surface, width, height) }
    }

    private fun attachSurfaceOnWorker(surface: Surface, width: Int, height: Int) = synchronized(lock) {
        check(!closed) { "Preview controller is closed" }
        InternalLogRecorder.event("Preview", "attach_surface size=${width}x$height valid=${surface.isValid} generation=$surfaceGeneration")
        if (!surface.isValid) {
            publishLocked(RawPreviewPhase.WAITING_FOR_SURFACE, "Viewfinder surface is not valid")
            return@synchronized
        }
        if (attachedSurface === surface && attachedSurfaceWidth == width &&
            attachedSurfaceHeight == height) return@synchronized
        stopActiveLocked()
        attachedSurface = surface
        attachedSurfaceWidth = width
        attachedSurfaceHeight = height
        renderedFraming = null
        displayedPreviewGeometry = null
        serializedPreviewGeometry = null
        if (renderedFrames > 0) lensSwitching = true
        surfaceReady = false
        blockedConfiguration = null
        val generation = ++surfaceGeneration
        scheduleRendererTask { prepareSurface(surface, generation) }
    }

    fun updateDeviceOrientation(degrees: Int) { commands.submit { updateDeviceOrientationOnWorker(degrees) } }

    private fun updateDeviceOrientationOnWorker(degrees: Int) = synchronized(lock) {
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270) {
            "Device orientation must be 0, 90, 180, or 270 degrees"
        }
        deviceOrientationDegrees = degrees
        configureGridPreviewLocked()
    }

    fun detachSurface(surface: Surface? = null) { commands.submit { detachSurfaceOnWorker(surface) } }

    private fun detachSurfaceOnWorker(surface: Surface?) = synchronized(lock) {
        if (closed || (surface != null && attachedSurface !== surface)) return@synchronized
        InternalLogRecorder.event("Preview", "detach_surface generation=$surfaceGeneration")
        lensSwitching = false
        stopActiveLocked()
        attachedSurface = null
        renderedFraming = null
        surfaceReady = false
        ++surfaceGeneration
        scheduleRendererTask {
            val currentRenderer = synchronized(lock) { renderer }
            if (currentRenderer != null) {
                runCatching { currentRenderer.setSurface(null) }
                updateRendererDiagnostic(currentRenderer)
            }
        }
        publishLocked(RawPreviewPhase.WAITING_FOR_SURFACE, "Waiting for viewfinder surface")
    }

    override fun onResume(owner: LifecycleOwner) { commands.submit { onResumeOnWorker() } }

    private fun onResumeOnWorker() = synchronized(lock) {
        if (closed) return@synchronized
        InternalLogRecorder.event("Preview", "resume generation=$surfaceGeneration surfaceReady=$surfaceReady")
        lastCameraFrameAtMs = SystemClock.elapsedRealtime()
        lastRenderCompletedAtMs = lastCameraFrameAtMs
        diagnosticTask?.cancel(false)
        diagnosticTask = diagnosticWorker.scheduleWithFixedDelay({
            val current = mutableStatus.value
            if (current.phase in setOf(RawPreviewPhase.PAUSED, RawPreviewPhase.CLOSED, RawPreviewPhase.IDLE))
                return@scheduleWithFixedDelay
            val now = SystemClock.elapsedRealtime()
            val detail = "phase=${current.phase} ready=${current.viewfinderReady} frames=${current.renderedFrames} " +
                "skipped=${current.skippedFrames} dropped=${current.droppedCameraFrames} " +
                "cameraGapMs=${now - lastCameraFrameAtMs} renderGapMs=${now - lastRenderCompletedAtMs} " +
                "renderStartedAt=$lastRenderStartedAtMs capture=${mutableCaptureStatus.value.phase} " +
                "message=${current.message} diagnostic=${current.rendererDiagnostic}"
            if (now - lastRenderCompletedAtMs >= 10_000 && !mutableCaptureStatus.value.acquiringFrame)
                InternalLogRecorder.warning("PreviewWatchdog", detail)
            else InternalLogRecorder.event("PreviewHeartbeat", detail)
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
        resumed = true
        panoramaRecorder.setForeground(true)
        blockedConfiguration = null
        scheduleReconcileLocked()
    }

    override fun onPause(owner: LifecycleOwner) { commands.submit { onPauseOnWorker() } }

    private fun onPauseOnWorker() = synchronized(lock) {
        if (closed) return@synchronized
        InternalLogRecorder.event("Preview", "pause generation=$surfaceGeneration")
        diagnosticTask?.cancel(false)
        diagnosticTask = null
        resumed = false
        panoramaRecorder.setForeground(false)
        cancelSessionRecoveryLocked()
        lensSwitching = false
        stopActiveLocked()
        publishLocked(RawPreviewPhase.PAUSED, "RAW preview is paused")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    override fun close() { commands.closeAfter { closeOnWorker() } }

    private fun closeOnWorker() = synchronized(lock) {
        if (closed) return@synchronized
        panoramaRecorder.close()
        focusLoupeMotion.setActive(false)
        stopVideoLocked()
        InternalLogRecorder.event("Preview", "close generation=$surfaceGeneration")
        diagnosticWorker.shutdownNow()
        closed = true
        previewFrames.close()
        galleryUpdates.cancel()
        resetSessionRecoveryLocked()
        resumed = false
        lensSwitching = false
        cancelDeferredCapturesLocked()
        active?.session?.close()
        active = null
        clearTapMeteringLocked()
        attachedSurface = null
        renderedFraming = null
        surfaceReady = false
        ++surfaceGeneration
        pendingCapture = null
        val rendererToClose = renderer
        renderer = null
        rendererDiagnostic = "Renderer is closed"
        scheduleRendererTask {
            try { performanceHints.close(); rendererToClose?.close() }
            finally { videoWorker.shutdown() }
        }
        rendererExecutor.shutdown()
        captureProcessor.execute { captureRenderer?.close(); captureRenderer = null }
        captureProcessor.shutdown()
        mergeProcessor.shutdown()
        mergeRing.close()
        deferredBuffers.close()
        burstRecorder.close()
        publishLocked(RawPreviewPhase.CLOSED, "RAW preview is closed")
    }

    private fun scheduleRendererTask(task: () -> Unit) {
        try {
            rendererExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            // The controller may be closed while a lifecycle callback is being delivered.
        }
    }

    private fun scheduleReconcileLocked() {
        scheduleRendererTask {
            synchronized(lock) {
                reconcileLocked()
            }
        }
    }

    private fun prepareSurface(surface: Surface, generation: Long) {
        val existingRenderer = synchronized(lock) {
            if (closed || generation != surfaceGeneration || attachedSurface !== surface) {
                return
            }
            renderer
        }
        performanceHints.close()
        val currentRenderer = existingRenderer ?: runCatching { createRenderer(forExport = false) }
            .getOrElse { error ->
                synchronized(lock) {
                    if (generation == surfaceGeneration && attachedSurface === surface) {
                        surfaceReady = false
                        attachedSurface = null
                        rendererDiagnostic = "Renderer creation failed: " +
                            (error.message ?: error.javaClass.simpleName)
                        publishLocked(
                            RawPreviewPhase.ERROR,
                            "Vulkan renderer creation failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
                return
            }

        if (existingRenderer == null) {
            val accepted = synchronized(lock) {
                if (closed || generation != surfaceGeneration || attachedSurface !== surface) {
                    false
                } else {
                    renderer = currentRenderer
                    true
                }
            }
            if (!accepted) {
                runCatching { currentRenderer.close() }
                return
            }
        }

        val setup = runCatching { currentRenderer.setSurface(surface) }
        setup.onFailure { InternalLogRecorder.error("Preview", "surface_setup_failed generation=$generation", it) }
        val diagnostic = runCatching { currentRenderer.diagnostics() }
            .getOrElse { error -> error.message ?: error.javaClass.simpleName }
        InternalLogRecorder.event("Preview", "surface_setup generation=$generation success=${setup.getOrDefault(false)} diagnostic=$diagnostic")
        var staleSurface = false
        synchronized(lock) {
            val currentSurface = generation == surfaceGeneration && attachedSurface === surface
            if (!currentSurface || closed) {
                staleSurface = setup.isSuccess && setup.getOrDefault(false)
            } else if (setup.getOrDefault(false)) {
                surfaceReady = true
                rendererDiagnostic = diagnostic
                reconcileLocked()
            } else {
                surfaceReady = false
                attachedSurface = null
                rendererDiagnostic = diagnostic
                publishLocked(
                    RawPreviewPhase.ERROR,
                    "Vulkan preview surface setup failed: $diagnostic",
                )
            }
        }
        if (staleSurface) {
            runCatching { currentRenderer.setSurface(null) }
            updateRendererDiagnostic(currentRenderer)
        }
    }

    private fun updateRendererDiagnostic(currentRenderer: VulkanRenderer) {
        val diagnostic = runCatching { currentRenderer.diagnostics() }
            .getOrElse { error -> error.message ?: error.javaClass.simpleName }
        synchronized(lock) {
            if (renderer === currentRenderer) {
                rendererDiagnostic = diagnostic
            }
        }
    }

    private fun stillCallback(request: PendingCapture) = object : RawStillCaptureCallback {
        override fun onStillCaptureRequested(exposureTimeNs: Long) = synchronized(lock) {
            if (pendingCapture !== request) return@synchronized
            request.exposureTimeNs = exposureTimeNs
            request.exposureStartedAtMs = SystemClock.elapsedRealtime()
            publishCaptureProgressLocked()
        }

        override fun onStillCaptureStarted(exposureTimeNs: Long) = synchronized(lock) {
            if (pendingCapture !== request) return@synchronized
            request.exposureTimeNs = exposureTimeNs
            if (request.exposureStartedAtMs == null) request.exposureStartedAtMs = SystemClock.elapsedRealtime()
            publishCaptureProgressLocked()
        }

        override fun onStillCaptureCanceled() = synchronized(lock) {
            if (pendingCapture !== request || request.frameReceived) return@synchronized
            pendingCapture = null
            request.rawBuffer?.cancel()
            mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                phase = RawCapturePhase.CANCELED, message = "Long exposure canceled", canceledCapture = true,
                feedbackId = mutableCaptureStatus.value.feedbackId + 1)
            publishCaptureProgressLocked()
            commands.submit { synchronized(lock) { startNextDeferredCaptureLocked() } }
            Unit
        }

        override fun onStillFrame(frame: RawHardwareFrame) {
            InternalLogRecorder.event("Capture", "frame_received capturedAt=${request.capturedAtMillis} frame=${frame.metadata.frameNumber} " +
                "timestamp=${frame.metadata.sensorTimestampNs} stride=${frame.rowStrideBytes} bytes=${frame.packedRawBytes.remaining()}")
            synchronized(lock) {
                if (closed || pendingCapture !== request) return
                request.frameReceived = true
            }
            val owned = runCatching {
                val capture = preparePersistedCapture(request, frame)
                if (request.processLater) {
                    val buffer = requireNotNull(request.rawBuffer)
                    val source = frame.packedRawBytes.duplicate().apply { clear() }
                    check(buffer.pixels.capacity() == source.remaining()) { "RAW frame size changed during capture" }
                    buffer.pixels.clear()
                    buffer.pixels.put(source).flip()
                    val result = requireNotNull(frame.captureResult) { "Camera did not provide DNG capture metadata" }
                    buffer.save {
                        val saved = runCatching {
                            deferredWriter.save(capture, buffer.pixels, result,
                                cameraManager.getCameraCharacteristics(request.owner.camera.route.characteristicsCameraId))
                        }
                        synchronized(lock) {
                            if (!closed) saved.onSuccess {
                                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                                    deferredShots = waitingRawShots(com.unspektrawesome.gallery.GalleryStore.get(applicationContext)),
                                    message = "RAW saved for Gallery")
                            }.onFailure { error ->
                                InternalLogRecorder.error("Capture", "dng_save_failed capturedAt=${request.capturedAtMillis}", error)
                                publishCaptureErrorLocked("Unable to save original RAW: ${error.message}", request.snapshot.revisionId)
                            }
                        }
                    }
                    null
                } else capture.store(captureBufferDirectory, frame.packedRawBytes)
            }
            if (owned.isFailure) request.rawBuffer?.cancel()
            synchronized(lock) {
                if (closed) return
                owned.onSuccess { copied ->
                    pendingCapture = null
                    if (copied != null) enqueueCaptureLocked(copied) else publishCaptureProgressLocked()
                    resumePreviewAfterStillLocked(request.owner)
                }.onFailure { error ->
                    pendingCapture = null
                    InternalLogRecorder.error("Capture", "raw_copy_failed capturedAt=${request.capturedAtMillis}", error)
                    publishCaptureErrorLocked("RAW frame copy failed: ${error.message}", request.snapshot.revisionId)
                    resumePreviewAfterStillLocked(request.owner)
                }
                commands.submit { synchronized(lock) { startNextDeferredCaptureLocked() } }
            }
        }

        override fun onPreviewResumed() = Unit

        override fun onStillCaptureError(error: Camera2RawSession.SessionError) = synchronized(lock) {
            if (pendingCapture !== request) return@synchronized
            pendingCapture = null
            request.rawBuffer?.cancel()
            publishCaptureErrorLocked(
                "${error.code}: ${error.message}",
                request.snapshot.revisionId,
            )
            resumePreviewAfterStillLocked(request.owner)
            commands.submit { synchronized(lock) { startNextDeferredCaptureLocked() } }
        }
    }

    private fun createRenderer(forExport: Boolean): VulkanRenderer =
        rendererFactory?.invoke(forExport) ?: VulkanRenderer(pipelineCachePath)

    private fun onDeferredBufferChanged(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        val current = mutableCaptureStatus.value
        if (current.savingRawShots > 0 && deferredBuffers.pendingShots == 0 && current.phase != RawCapturePhase.ERROR) {
            mutableCaptureStatus.value = current.copy(phase = RawCapturePhase.SUCCEEDED,
                feedbackId = current.feedbackId + 1)
        }
        publishCaptureProgressLocked()
    }

    private fun preparePersistedCapture(request: PendingCapture, frame: RawHardwareFrame, log: Boolean = true): PersistedCapture =
        preparePersistedCapture(request, frame.metadata, frame.output, frame.rowStrideBytes, log)

    private fun preparePersistedCapture(
        request: PendingCapture,
        metadata: com.unspektrawesome.camera.session.RawCaptureMetadata,
        output: com.unspektrawesome.camera.RawOutput,
        rowStrideBytes: Int,
        log: Boolean,
    ): PersistedCapture {
        check(output == request.owner.key.rawLayoutOverride.output(request.captureOutput)) {
            "Still RAW output does not match the shutter geometry snapshot"
        }
        val whiteBalance = WhiteBalanceGains.fromRawCaptureMetadata(metadata)
        val captureLensShading = LensShadingMapParameters.preferCaptureMetadata(
            metadata,
            request.lensShadingMap,
        )
        val captureSnapshot = VulkanCaptureSnapshot.from(request.snapshot, metadata)
        val raw = RawShaderParameters.forCapture(
            output,
            request.owner.camera.sensorMetadata,
            metadata,
            rowStrideBytes,
            captureSnapshot,
            request.rawLevels,
            request.cfaOverride,
        )
        val rawColor = RawColorShaderParameters.fromCaptureSnapshot(
            captureSnapshot,
            request.owner.camera.sensorMetadata,
            whiteBalance,
            captureLensShading,
            request.color,
            metadata,
        )
        val outputSize = request.geometry.outputSize
        if (log) logRawContract("capture", request.owner, metadata, output.format, rowStrideBytes,
            raw, whiteBalance, captureLensShading, request.snapshot)
        return PersistedCapture(
            outputSize.width, outputSize.height, request.snapshot.revisionId,
            request.capturedAtMillis, request.spektraTimeSeconds,
            CaptureMetadata(metadata.sensorTimestampNs, metadata.exposureTimeNs,
                metadata.sensitivityIso,
                metadata.focalLengthMm ?: request.owner.camera.focalLengthMm,
                request.owner.camera.displayName(),
                aperture = metadata.aperture,
                equivalentFocalLengthMm = request.equivalentFocalLengthMm,
                identity = CaptureIdentity.current,
                spektra = SpektraShotMetadata.from(request.snapshot.params),
                utcOffsetSeconds = java.util.TimeZone.getDefault().getOffset(request.capturedAtMillis) / 1000,
                location = request.location,
                colorCalibration = request.color.description,
                dcpId = request.color.settings.dcpId.takeIf { request.color.settings.dcpEnabled },
                lensProfile = request.lensProfile,
                merge = request.mergeFallback),
            raw.writeTo(directBuffer(RawShaderParameters.SIZE_BYTES)),
            rawColor.writeTo(directBuffer(RawColorShaderParameters.SIZE_BYTES)),
            GeometryShaderParameters.from(request.geometry).writeTo(directBuffer(GeometryShaderParameters.SIZE_BYTES)),
            request.spektraParameters, RawCalibrationTables.create(captureLensShading, request.color.activeDcp),
            saveOriginalDng = request.saveOriginalDng && !request.processLater,
        )
    }

    private fun processStillFrame(file: java.io.File) {
        val startedAt = SystemClock.elapsedRealtime()
        val id = file.nameWithoutExtension
        val gallery = com.unspektrawesome.gallery.GalleryStore.get(applicationContext)
        fun finish(uri: Uri): Uri {
            if (gallery.shots.value.any { it.id == id }) {
                gallery.update(id, com.unspektrawesome.gallery.ExportState.EXPORTED, jpegUri = uri)
            }
            if (file.delete()) jpegWriter.forgetCapture(id)
            return uri
        }
        InternalLogRecorder.event("ImmediateExport", "start id=${file.nameWithoutExtension}")
        InternalLogRecorder.resources("immediate_export")
        val saved = runCatching {
            gallery.shots.value.firstOrNull { it.id == id && it.state == com.unspektrawesome.gallery.ExportState.EXPORTED }
                ?.jpegUri?.takeIf { uri ->
                    runCatching { applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0 } == true }
                        .getOrDefault(false)
                }?.let { return@runCatching finish(it) }
            jpegWriter.findSavedCapture(id)?.let { return@runCatching finish(it) }
            val (shot, pixels) = PersistedCapture.read(file)
            if (shot.saveOriginalDng) {
                deferredWriter.save(shot, pixels, id = id, forGallery = false)
                gallery.update(id, com.unspektrawesome.gallery.ExportState.PROCESSING)
            }
            ImageDiagnostics.input(file.nameWithoutExtension, shot, pixels)
            val currentRenderer = captureRenderer ?: createRenderer(forExport = true).also { captureRenderer = it }
            val bitmap = android.graphics.Bitmap.createBitmap(shot.width, shot.height, android.graphics.Bitmap.Config.ARGB_8888)
            val uri = try {
                check(currentRenderer.captureAmazeToBitmap(pixels, shot.raw, shot.color, shot.geometry,
                    shot.film, shot.shading, bitmap, shot.timeSeconds)) { currentRenderer.diagnostics() }
                ImageDiagnostics.output(file.nameWithoutExtension, bitmap)
                InternalLogRecorder.event("ImmediateExport", "saving_jpeg id=${file.nameWithoutExtension} diagnostic=${currentRenderer.diagnostics()}")
                jpegWriter.write(bitmap, shot.metadata, capturedAtMillis = shot.capturedAtMillis,
                    captureId = file.nameWithoutExtension)
            } finally { bitmap.recycle() }
            finish(uri)
        }
        synchronized(lock) {
            saved.onSuccess { uri ->
                InternalLogRecorder.event("ImmediateExport", "completed id=${file.nameWithoutExtension} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
                    phase = RawCapturePhase.SUCCEEDED, message = "Photo saved", lastUri = uri,
                    feedbackId = mutableCaptureStatus.value.feedbackId + 1,
                )
            }.onFailure { error ->
                InternalLogRecorder.error("ImmediateExport", "failed id=${file.nameWithoutExtension} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}", error)
                if (gallery.shots.value.any { it.id == id && it.rawBytes > 0 }) {
                    gallery.update(id, com.unspektrawesome.gallery.ExportState.FAILED, error.message.orEmpty())
                }
                failedExports.add(file)
                lastExportError = error.message ?: error.javaClass.simpleName
                publishCaptureErrorLocked("Export paused: $lastExportError")
            }
        }
    }

    private fun bufferedShotCount(): Int =
        captureBufferDirectory.listFiles { file -> file.extension == "shot" }?.size ?: 0

    private fun publishCaptureProgressLocked(revisionId: Long? = mutableCaptureStatus.value.revisionId) {
        val count = bufferedShotCount() + mergingShots + if (pendingCapture?.processLater == false) 1 else 0
        val rawCount = deferredBuffers.pendingShots
        failedExports.removeAll { !it.exists() }
        val current = mutableCaptureStatus.value
        mutableCaptureStatus.value = current.copy(
            phase = when {
                failedExports.isNotEmpty() -> RawCapturePhase.ERROR
                current.phase == RawCapturePhase.ERROR -> RawCapturePhase.ERROR
                pendingCapture?.let { !it.processLater || !it.frameReceived } == true -> RawCapturePhase.CAPTURING
                rawCount > 0 -> RawCapturePhase.SAVING
                count > 0 -> RawCapturePhase.SAVING
                else -> current.phase
            },
            message = when {
                failedExports.isNotEmpty() -> "Paused photos: ${failedExports.size}. ${lastExportError.orEmpty()}".trim()
                current.phase == RawCapturePhase.ERROR -> current.message
                rawCount > 0 -> "Saving photos"
                count >= RawCaptureStatus.CAPACITY -> "Queue full ($count/${RawCaptureStatus.CAPACITY})"
                pendingCapture != null -> "Capturing photo ($count/${RawCaptureStatus.CAPACITY})"
                mergingShots > 0 -> "Merging photo ($count/${RawCaptureStatus.CAPACITY})"
                count > 0 -> "${if (restoredQueue) "Resuming queue:" else "Processing"} · Photos: $count"
                else -> current.message
            },
            revisionId = revisionId,
            outstandingShots = count,
            acquiringFrame = pendingCapture != null,
            exposureTimeNs = pendingCapture?.exposureTimeNs ?: 0L,
            exposureStartedAtMs = pendingCapture?.exposureStartedAtMs,
            retryableShots = failedExports.size,
            processingDeferred = configuredPreferences.processPhotosLater,
            savingRawShots = rawCount,
            rawBufferFull = deferredBuffers.isFullFor(active?.packedFrameBytes ?: 0),
        )
        refreshCaptureReadyLocked()
    }

    private fun resumePreviewAfterStillLocked(owner: ActiveSession) {
        if (closed || !resumed || !owner.acceptsFrames || active !== owner) return
        runCatching { owner.session.resumePreview() }.onFailure { error ->
            publishCaptureErrorLocked(
                "RAW preview resume failed: ${error.message ?: error.javaClass.simpleName}",
                pendingCapture?.snapshot?.revisionId,
            )
        }
    }

    private fun publishCaptureErrorLocked(message: String, revisionId: Long? = null) {
        InternalLogRecorder.error("Capture", "revision=$revisionId $message")
        mutableCaptureStatus.value = mutableCaptureStatus.value.copy(
            phase = RawCapturePhase.ERROR,
            feedbackId = mutableCaptureStatus.value.feedbackId + 1,
            message = message,
            revisionId = revisionId,
            lastUri = mutableCaptureStatus.value.lastUri,
            outstandingShots = bufferedShotCount() + mergingShots + if (pendingCapture?.processLater == false) 1 else 0,
            acquiringFrame = pendingCapture != null,
            exposureTimeNs = pendingCapture?.exposureTimeNs ?: 0L,
            exposureStartedAtMs = pendingCapture?.exposureStartedAtMs,
            retryableShots = failedExports.size,
            savingRawShots = deferredBuffers.pendingShots,
            rawBufferFull = deferredBuffers.isFullFor(active?.packedFrameBytes ?: 0),
        )
    }

    private fun captureRotationDegrees(): Int =
        CameraOrientation.displayRotation(deviceOrientationDegrees)

    private fun reconcileLocked() {
        if (closed) return
        if (discoverySuspended) { stopActiveLocked(); return }
        if (updateSuspended) {
            stopActiveLocked()
            publishLocked(RawPreviewPhase.PAUSED, "RAW preview is paused")
            return
        }
        val camera = configuredCamera
        val surface = attachedSurface
        if (!resumed) {
            stopActiveLocked()
            publishLocked(RawPreviewPhase.PAUSED, "RAW preview is paused")
            return
        }
        if (surface == null || !surface.isValid) {
            stopActiveLocked()
            publishLocked(RawPreviewPhase.WAITING_FOR_SURFACE, "Waiting for viewfinder surface")
            return
        }
        if (!surfaceReady || renderer == null) {
            stopActiveLocked()
            publishLocked(RawPreviewPhase.WAITING_FOR_SURFACE, "Preparing Vulkan RAW preview")
            return
        }
        if (camera == null) {
            stopActiveLocked()
            publishLocked(RawPreviewPhase.IDLE, "Select a RAW camera")
            return
        }

        val recovery = sessionRecovery.status
        if (recovery != null && recovery.phase != LensSessionRecoveryPhase.CONFIGURING) {
            publishLocked(RawPreviewPhase.ERROR, "Lens session configuration failed")
            return
        }
        val plan = runCatching {
            RawPreviewPlanSelector.select(
                camera,
                configuredPreferences,
                importCapabilities = if (recovery != null) emptyMap() else mutableRawImportCapabilities.value,
                frameRateOverride = videoFrameRateOverride(),
            )
        }.getOrElse { error ->
            lensSwitching = false
            stopActiveLocked()
            failSessionRecoveryLocked("RAW preview selection failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        val key = ConfigurationKey(camera, plan, configuredPreferences, videoFrameRateOverride())
        if (blockedConfiguration == key) return
        val current = active
        if (current != null) {
            if (current.acceptsFrames && current.key == key && current.session.state() !in TERMINAL_SESSION_STATES) return
            stopActiveLocked()
            return
        }
        // The app closed unexpectedly while this lens was starting. Report it instead of reopening it.
        crashedConfiguration?.let { crashed ->
            crashedConfiguration = null
            if (crashed == LensStartupGuard.configuration(camera, plan)) {
                InternalLogRecorder.event("Preview", "startup_crash_reported configuration=$crashed")
                blockedConfiguration = key
                failSessionRecoveryLocked("Lens session configuration failed", allowRetry = false)
                return
            }
        }
        runCatching { startLocked(camera, plan, key) }.onFailure { error ->
            failSessionRecoveryLocked(error.message ?: "Lens session configuration failed")
            stopActiveLocked()
        }
    }

    private fun startLocked(
        camera: CameraDescriptor,
        plan: RawPreviewPolicy.Plan,
        key: ConfigurationKey,
    ) {
        InternalLogRecorder.event("Preview", "session_start route=${camera.route.routeId} profile=${key.lensProfileId} " +
            "acquisition=${plan.acquisitionOutput.format} ${plan.acquisitionOutput.size} " +
            "processing=${plan.processingSize} fps=${plan.framesPerSecond} generation=$surfaceGeneration")
        val session = Camera2RawSession(
            cameraManager, camera.sensorMetadata, lensExposureStore.load(camera.route.routeId),
        )
        session.setExactConfiguration(plan.sessionConfiguration)
        session.setCompanionMode(key.capture.ispSurface.sessionValue)
        session.setIspViewfinder(if (key.ispViewfinder) plan.processingSize else null)
        session.setRequestBatchSize(key.capture.requestBatchSize)
        session.setVendorOverrides(key.vendorOverrides)
        session.setRawLayoutOverride(key.rawLayoutOverride)
        session.setFlashMode(if (panoramaState.value.enabled) com.unspektrawesome.camera.FlashMode.OFF else configuredPreferences.flashMode)
        session.setOpticalStabilization(mutableVideoState.value.requestedOis)
        session.setExternalAutoExposure(configuredPreferences.aeEngine == AeEngine.SPEKTRA)
        session.setInitialControls(retainedControls)
        if (retainedControls == null && configuredPreferences.aeEngine == AeEngine.SPEKTRA) {
            lensSwitchExposure?.let { session.setInitialAutoExposure(it, configuredPreferences.aeLimits()) }
        }
        val owner = ActiveSession(session, camera, plan, key, controlSessionGeneration)
        retainedControls?.takeIf { it.externalAutoExposure && configuredPreferences.aeEngine == AeEngine.SPEKTRA }?.let {
            owner.exposureController.reset(retainedExposureMode, SensorExposure(it.iso, it.exposureTimeNs))
        }
        mergeRing.reset()
        active = owner
        startupGuard.starting(LensStartupGuard.configuration(camera, plan))
        scheduleSessionStartupTimeoutLocked(owner)
        if (renderedFrames > 0) lensSwitching = true
        publishLocked(
            RawPreviewPhase.OPENING,
            "Opening ${camera.displayName()} at ${plan.processingSize}",
        )
        session.start(
            camera.route,
            plan.acquisitionOutput,
            plan.aeTargetRange,
            object : com.unspektrawesome.camera.session.RawFrameConsumer {
                override fun preserveFrameOrder() = burstRecorder.status.value.enabled || previewFrames.preserveFrameOrder

                override fun shouldProcessFrame(sensorTimestampNs: Long): Boolean {
                    lastCameraFrameAtMs = SystemClock.elapsedRealtime()
                    // The ISP stream feeds the viewfinder; RAW frames are kept only for capture features.
                    if (ispRendering(owner)) return active === owner && owner.acceptsFrames &&
                        (mergeRingSettings(owner).enabled || burstRecorder.status.value.enabled ||
                            panoramaRecorder.needsFrame(sensorTimestampNs))
                    val preview = shouldProcessPreviewFrame(owner, sensorTimestampNs)
                    return preview || (active === owner && owner.acceptsFrames &&
                        (burstRecorder.status.value.enabled || panoramaRecorder.needsFrame(sensorTimestampNs)))
                }

                override fun onRawFrame(frame: RawHardwareFrame) {
                    lastCameraFrameAtMs = SystemClock.elapsedRealtime()
                    owner.packedFrameBytes = frame.packedRawBytes.capacity()
                    mergeRing.offer(frame, mergeRingSettings(owner))
                    panoramaRecorder.offer(frame) {
                        synchronized(lock) {
                            val request = panoramaRequest
                            if (request == null || active !== owner || request.owner !== owner || !owner.acceptsFrames ||
                                !frame.metadata.panoramaControlsLocked ||
                                !com.unspektrawesome.panorama.panoramaExposureMatches(mutableCameraControls.value.iso, request.exposureTimeNs,
                                    frame.metadata.sensitivityIso, frame.metadata.exposureTimeNs)) {
                                panoramaRecorder.rejectControls(frame, mutableCameraControls.value.iso, request?.exposureTimeNs)
                                null
                            }
                            else preparePersistedCapture(request, frame, log = false)
                        }
                    }
                    burstRecorder.offer(frame) {
                        synchronized(lock) {
                            if (active !== owner || !owner.acceptsFrames || !startupReadiness.isReady) null
                            else prepareCaptureLocked()?.let { preparePersistedCapture(it, frame, log = false) }
                        }
                    }
                    if (ispRendering(owner)) return
                    val metadata = frame.metadata
                    val sample = synchronized(lock) {
                        val state = mutableVideoState.value
                        if (state.phase == VideoPhase.RECORDING) videoRecorder?.frameReceived(metadata.sensorTimestampNs)
                        if (active === owner) {
                            val reported = metadata.stabilization?.oisMode
                            val supported = session.opticalStabilizationSupported()
                            val live = session.opticalStabilizationLive()
                            if (state.oisReported != reported || state.oisSupported != supported || state.oisLive != live)
                                mutableVideoState.value = state.copy(oisReported = reported, oisSupported = supported, oisLive = live)
                        }
                        owner.samples.remove(metadata.sensorTimestampNs)
                    } ?: run {
                        if (videoRecorder?.traceFrames == true && previewFrames.preserveFrameOrder) InternalLogRecorder.event("VideoFrame",
                            "input_rejected sensor_ns=${metadata.sensorTimestampNs} reason=missing_frame_sample ${previewFrames.diagnosticState}")
                        return
                    }
                    val output = frame.output
                    val rowStrideBytes = frame.rowStrideBytes
                    val queuedAtNs = SystemClock.elapsedRealtimeNanos()
                    val traceRecording = videoRecorder?.traceFrames == true && previewFrames.preserveFrameOrder
                    val rejections = if (traceRecording) arrayOf("", "", "") else null
                    if (previewFrames.submitLease(frame::retain,
                            if (rejections != null) ({ rejections[0] = it }) else null) { lease ->
                            renderFrame(owner, metadata, output, rowStrideBytes, lease.frame.packedRawBytes,
                                queuedAtNs, hardwareBuffer = lease.frame.hardwareBuffer, sample = sample)
                        }) {
                        if (traceRecording) InternalLogRecorder.event("VideoFrame",
                            "input sensor_ns=${metadata.sensorTimestampNs} path=camera_lease ${previewFrames.diagnosticState}")
                        return
                    }
                    val uploads = synchronized(lock) {
                        renderer?.rawUploads.takeIf { !closed && active === owner && owner.acceptsFrames }
                    }
                    val uploaded = previewFrames.submitLease({
                        uploads?.copy(frame.packedRawBytes, rowStrideBytes.toLong() * output.size.height)
                    }, if (rejections != null) ({ rejections[1] = it }) else null, retainsCameraImage = false) {
                        renderFrame(owner, metadata, output, rowStrideBytes, null, queuedAtNs, it, sample = sample)
                    }
                    var copied = false
                    if (!uploaded) {
                        copied = previewFrames.submit(frame.packedRawBytes,
                            if (rejections != null) ({ rejections[2] = it }) else null) { bytes ->
                            renderFrame(owner, metadata, output, rowStrideBytes, bytes, queuedAtNs, sample = sample)
                        }
                    }
                    if (rejections != null) InternalLogRecorder.event("VideoFrame",
                        "input sensor_ns=${metadata.sensorTimestampNs} path=${if (uploaded) "mapped_upload" else if (copied) "cpu_copy" else "dropped"} " +
                            "camera_rejected=${rejections[0]} upload_rejected=${rejections[1]} copy_rejected=${rejections[2]} " +
                            "copy_us=${(SystemClock.elapsedRealtimeNanos() - queuedAtNs) / 1_000} ${previewFrames.diagnosticState}")
                }

                override fun shouldProcessIspFrame(sensorTimestampNs: Long): Boolean {
                    lastCameraFrameAtMs = SystemClock.elapsedRealtime()
                    return ispRendering(owner) && shouldProcessPreviewFrame(owner, sensorTimestampNs)
                }

                override fun onIspFrame(frame: com.unspektrawesome.camera.session.IspHardwareFrame) {
                    val metadata = frame.metadata
                    val output = frame.rawOutput
                    if (frame.rawRowStrideBytes > 0 && owner.packedFrameBytes == 0)
                        owner.packedFrameBytes = frame.rawRowStrideBytes * output.size.height
                    val sample = synchronized(lock) {
                        if (active === owner) {
                            val state = mutableVideoState.value
                            val reported = metadata.stabilization?.oisMode
                            val supported = session.opticalStabilizationSupported()
                            val live = session.opticalStabilizationLive()
                            if (state.oisReported != reported || state.oisSupported != supported || state.oisLive != live)
                                mutableVideoState.value = state.copy(oisReported = reported, oisSupported = supported, oisLive = live)
                        }
                        owner.samples.remove(metadata.sensorTimestampNs)
                    } ?: return
                    val rowStrideBytes = frame.rawRowStrideBytes.takeIf { it > 0 }
                        ?: RawLayoutOverride.minimumStride(output.format, output.size.width)
                    val queuedAtNs = SystemClock.elapsedRealtimeNanos()
                    previewFrames.submitLease(frame::retain, null) { lease ->
                        renderFrame(owner, metadata, output, rowStrideBytes, null, queuedAtNs, sample = sample, isp = lease.frame)
                    }
                }
            },
            object : com.unspektrawesome.camera.session.RawSessionListener {
                override fun onStateChanged(state: Camera2RawSession.State) {
                    handleSessionState(owner, state)
                }

                override fun onError(error: Camera2RawSession.SessionError) {
                    handleSessionError(owner, error)
                }

                override fun onFocusStateChanged(generation: Long, state: Camera2RawSession.MeteringState) {
                    handleMeteringState(owner, generation, state, exposure = false)
                }

                override fun onExposureStateChanged(generation: Long, state: Camera2RawSession.MeteringState) {
                    handleMeteringState(owner, generation, state, exposure = true)
                }

                override fun onControlsChanged(state: Camera2RawSession.ControlState) {
                    synchronized(lock) {
                        if (!closed && active === owner) {
                            if (owner.acceptsFrames && owner.controlSessionGeneration == controlSessionGeneration) {
                                retainedControls = state
                                retainedExposureMode = owner.exposureController.mode
                            }
                            if (state.externalAutoExposure && mutableCameraControls.value.exposureCompensation != state.exposureCompensation)
                                owner.exposureController.resetMetering()
                            publishCustomControlsLocked(owner, CameraControlState.from(state))
                            if (panoramaState.value.enabled && !panoramaState.value.busy) preparePanoramaAutoLocked(owner)
                            refreshCaptureReadyLocked(owner)
                        }
                    }
                }
            },
        )
    }

    private fun handleMeteringState(
        owner: ActiveSession,
        generation: Long,
        state: Camera2RawSession.MeteringState,
        exposure: Boolean,
    ) = synchronized(lock) {
        if (closed || active !== owner) return@synchronized
        val status = if (exposure) mutableExposureMeteringStatus else mutableTapMeteringStatus
        val current = status.value
        if (current.generation == 0L || current.generation != generation) return@synchronized
        status.value = current.copy(
            phase = when (state) {
                Camera2RawSession.MeteringState.SCANNING -> TapMeteringPhase.METERING
                Camera2RawSession.MeteringState.SUCCESS -> TapMeteringPhase.SUCCESS
                Camera2RawSession.MeteringState.FAILED -> TapMeteringPhase.FAILED
                Camera2RawSession.MeteringState.TIMED_OUT -> TapMeteringPhase.TIMED_OUT
                Camera2RawSession.MeteringState.UNSUPPORTED -> TapMeteringPhase.UNSUPPORTED
                Camera2RawSession.MeteringState.RESET -> TapMeteringPhase.IDLE
            },
        )
    }

    private fun clearTapMeteringLocked() {
        focusPeakingResolution.reset()
        focusScene.reset()
        exposureScene.reset()
        lastMeteringSceneSampleMs = 0L
        latestPreviewGeometry = null
        displayedPreviewGeometry = null
        cropTransition.reset()
        serializedPreviewGeometry = null
        mutableExposureMeteringStatus.value = TapMeteringStatus()
        val current = mutableTapMeteringStatus.value
        if (current.phase != TapMeteringPhase.IDLE) {
            mutableTapMeteringStatus.value = current.copy(phase = TapMeteringPhase.IDLE)
        }
    }

    private fun cancelDeferredCapturesLocked(owner: ActiveSession? = null) {
        pendingCapture?.takeIf { owner == null || it.owner === owner }?.let {
            if (!it.frameReceived) it.rawBuffer?.cancel()
        }
        val iterator = deferredCaptures.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (owner == null || request.owner === owner) {
                request.rawBuffer?.cancel()
                iterator.remove()
            }
        }
    }

    private fun stopActiveLocked() {
        panoramaRecorder.stop()
        burstRecorder.reset()
        previewFrames.clearPending()
        val rendererToPause = renderer
        scheduleRendererTask {
            performanceHints.close()
            rendererToPause?.setGpuPerformanceActive(false)
        }
        stopVideoLocked()
        sessionStartupTimeout?.cancel(false)
        sessionStartupTimeout = null
        startupReadiness.onRenderFailed()
        startupGuard.finished()
        val current = active ?: return
        cancelDeferredCapturesLocked(current)
        current.acceptsFrames = false
        mutableCameraControls.value = CameraControlState()
        clearTapMeteringLocked()
        if (pendingCapture?.owner === current) {
            val revision = pendingCapture?.snapshot?.revisionId
            val acquiring = pendingCapture?.frameReceived != true
            pendingCapture = null
            if (acquiring) publishCaptureErrorLocked("RAW capture was canceled", revision) else publishCaptureProgressLocked()
        }
        current.session.close()
        if (current.session.state() in TERMINAL_SESSION_STATES) active = null
    }

    private fun handleSessionState(owner: ActiveSession, state: Camera2RawSession.State) =
        synchronized(lock) {
            if (closed || active !== owner) return@synchronized
            if (!owner.acceptsFrames && state != Camera2RawSession.State.CLOSED) return@synchronized
            when (state) {
                Camera2RawSession.State.OPENING, Camera2RawSession.State.CONFIGURING ->
                    publishLocked(RawPreviewPhase.OPENING, "Configuring RAW-only Camera2 session")
                Camera2RawSession.State.STREAMING -> {
                    if (panoramaState.value.enabled && !panoramaState.value.busy) preparePanoramaAutoLocked(owner)
                    if (mutableCameraControls.value.customAe && owner.exposureController.mode != AeMode.MANUAL) {
                        if (owner.exposureController.generation == 0L) resetCustomExposureLocked(owner, owner.exposureController.mode)
                        else {
                            owner.exposureController.invalidateMetering()
                            val exposure = owner.exposureController.requestedExposure
                            owner.session.setSensorExposure(exposure.iso, exposure.timeNs, owner.exposureController.generation)
                        }
                    }
                    publishLocked(RawPreviewPhase.STREAMING, "RAW Vulkan preview streaming")
                }
                Camera2RawSession.State.CLOSED -> {
                    active = null
                    reconcileLocked()
                }
                Camera2RawSession.State.UNSUPPORTED, Camera2RawSession.State.ERROR -> {
                    cancelDeferredCapturesLocked(owner)
                    lensSwitching = false
                    if (pendingCapture?.owner === owner) {
                        val revision = pendingCapture?.snapshot?.revisionId
                        pendingCapture = null
                        publishCaptureErrorLocked("RAW session ended during capture", revision)
                    }
                    active = null
                    blockedConfiguration = owner.key
                    failSessionRecoveryLocked("Lens session configuration failed")
                }
                else -> Unit
            }
        }

    private fun handleSessionError(
        owner: ActiveSession,
        error: Camera2RawSession.SessionError,
    ) = synchronized(lock) {
        if (closed || active !== owner || !owner.acceptsFrames) return@synchronized
        if (error.fatal) {
            failSessionRecoveryLocked("${error.code}: ${error.message}")
            lensSwitching = false
            blockedConfiguration = owner.key
            if (error.code == Camera2RawSession.ErrorCode.BUFFER) {
                val reason = buildString {
                    append("${error.code}: ${error.message}")
                    error.cause?.message?.takeIf(String::isNotBlank)?.let {
                        append(": $it")
                    }
                }
                recordRawImportCapability(
                    owner,
                    RawImportCapability(RawImportCapabilityState.INCOMPATIBLE, reason),
                )
            }
        }
        publishLocked(
            RawPreviewPhase.ERROR,
            "${error.code}: ${error.message}",
            owner,
        )
    }

    fun cancelLensSessionRetries() { commands.submit {
        synchronized(lock) {
            if (closed || sessionRecovery.status == null) return@synchronized
            cancelSessionRecoveryLocked()
            stopActiveLocked()
            publishLocked(RawPreviewPhase.ERROR, "Lens session configuration failed")
        }
    } }

    fun retryLensSession() { commands.submit {
        synchronized(lock) {
            if (closed || !resumed || !sessionRecovery.beginManualRetry()) return@synchronized
            mutableSessionRecovery.value = sessionRecovery.status
            blockedConfiguration = null
            scheduleReconcileLocked()
        }
    } }

    private fun resetSessionRecoveryLocked() {
        recoveryGeneration++
        retryTask?.cancel(false)
        retryTask = null
        sessionStartupTimeout?.cancel(false)
        sessionStartupTimeout = null
        sessionRecovery.reset()
        mutableSessionRecovery.value = null
    }

    private fun cancelSessionRecoveryLocked() {
        recoveryGeneration++
        retryTask?.cancel(false)
        retryTask = null
        sessionStartupTimeout?.cancel(false)
        sessionStartupTimeout = null
        sessionRecovery.cancel()
        mutableSessionRecovery.value = sessionRecovery.status
    }

    private fun failSessionRecoveryLocked(message: String, allowRetry: Boolean = true) {
        sessionStartupTimeout?.cancel(false)
        sessionStartupTimeout = null
        lensSwitching = false
        startupGuard.finished()
        val camera = configuredCamera ?: return
        val lensName = camera.relativeZoom?.let {
            java.lang.String.format(java.util.Locale.ROOT, "%.1f×", it)
        } ?: camera.displayName()
        val retry = sessionRecovery.failed(lensName, message.takeIf { configuredPreferences.vendorOverrides().enabled }, allowRetry)
        mutableSessionRecovery.value = sessionRecovery.status
        publishLocked(RawPreviewPhase.ERROR, message)
        if (!retry) return
        val generation = ++recoveryGeneration
        retryTask?.cancel(false)
        scheduleSessionRetryTickLocked(generation, SystemClock.elapsedRealtime() + LensSessionRecovery.RETRY_DELAY_MS)
    }

    private fun scheduleSessionRetryTickLocked(generation: Long, deadline: Long) {
        val delay = (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, 1_000L)
        retryTask = commands.schedule(delay) {
            synchronized(lock) {
                if (closed || generation != recoveryGeneration) return@synchronized
                if (!resumed || attachedSurface == null) {
                    cancelSessionRecoveryLocked()
                    return@synchronized
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) {
                    sessionRecovery.updateCountdown(((remaining + 999L) / 1_000L).toInt())
                    mutableSessionRecovery.value = sessionRecovery.status
                    scheduleSessionRetryTickLocked(generation, deadline)
                    return@synchronized
                }
                if (!sessionRecovery.beginAutomaticRetry()) return@synchronized
                mutableSessionRecovery.value = sessionRecovery.status
                blockedConfiguration = null
                scheduleReconcileLocked()
            }
        }
    }

    private val exposureMeterBuffer = ExposureMeterBuffer()
    private val pendingExposureMeters = linkedMapOf<Long, (Double) -> Unit>()
    private var exposureMeterRenderer: VulkanRenderer? = null
    private var exposureMeterSurfaceGeneration = -1L
    private var exposureMeterPollScheduled = false

    private fun deliverCustomMeterResult() {
        val id = exposureMeterBuffer.resultFrameId
        val callback = pendingExposureMeters[id]
        pendingExposureMeters.keys.removeAll { it <= id }
        callback?.invoke(exposureMeterBuffer.correctionEv)
    }

    private fun pollCustomMeter() {
        if (pendingExposureMeters.isEmpty()) return
        val currentRenderer = exposureMeterRenderer ?: return
        val valid = synchronized(lock) {
            !closed && resumed && surfaceReady && renderer === currentRenderer &&
                surfaceGeneration == exposureMeterSurfaceGeneration
        }
        if (!valid) {
            pendingExposureMeters.clear()
            return
        }
        if (currentRenderer.pollExposureMeter(exposureMeterBuffer)) deliverCustomMeterResult()
        if (pendingExposureMeters.isNotEmpty() && !exposureMeterPollScheduled) {
            exposureMeterPollScheduled = true
            if (commands.schedule(4L) {
                scheduleRendererTask {
                    exposureMeterPollScheduled = false
                    pollCustomMeter()
                }
            } == null) exposureMeterPollScheduled = false
        }
    }
    private var exposureMeterSequence = 0L

    private data class PreviewSubmission(
        val renderer: VulkanRenderer,
        val generation: Long,
        val revision: Long,
        val aspectRatio: Float,
        val geometry: PreviewGeometry,
        val performanceGeneration: Long?,
        val focusPeaking: Boolean,
        val effectsEnabled: Boolean,
        val render: () -> Boolean,
    )

    private fun prepareCustomMeterLocked(
        owner: ActiveSession, metadata: com.unspektrawesome.camera.session.RawCaptureMetadata,
    ): ((Double) -> Unit)? {
        val controls = mutableCameraControls.value
        val recording = videoRecorder != null
        if (!controls.customAe || owner.exposureController.mode == AeMode.MANUAL || pendingCapture != null ||
            metadata.sensitivityIso == null || metadata.requestedSensitivityIso == null || metadata.exposureTimeNs == null ||
            metadata.exposureControlGeneration != owner.exposureController.generation ||
            metadata.sensorTimestampNs - owner.lastExposureSampleNs < SpektraExposureController.METER_INTERVAL_NS) return null
        owner.lastExposureSampleNs = metadata.sensorTimestampNs
        val bounds = configuredPreferences.aeLimits().resolve(exposureBounds())
        val compensation = (controls.exposureCompensation * controls.exposureCompensationStepEv).toDouble()
        val point = owner.exposurePoint
        val generation = owner.exposureController.generation
        val meteringRevision = owner.exposureController.meteringRevision
        return { meteredEv ->
            synchronized(lock) {
                if (active !== owner || !mutableCameraControls.value.customAe || pendingCapture != null ||
                    owner.exposureController.generation != generation ||
                    owner.exposureController.meteringRevision != meteringRevision) return@synchronized
                val next = owner.exposureController.update(meteredEv,
                    SensorExposure(metadata.sensitivityIso, metadata.exposureTimeNs), metadata.sensorTimestampNs,
                    metadata.exposureControlGeneration, bounds, compensation, configuredPreferences.aeBalance(),
                    mutableCameraControls.value.isoMin, owner.camera.equivalentFocalLengthMm,
                    frameRequestedIso = metadata.requestedSensitivityIso, recording = recording)
                if (next != null) owner.session.setSensorExposure(next.iso, next.timeNs, owner.exposureController.generation)
                publishCustomControlsLocked(owner)
                if (point != null && kotlin.math.abs(owner.exposureController.correctionEv) < .12) {
                    mutableExposureMeteringStatus.value = mutableExposureMeteringStatus.value.copy(phase = TapMeteringPhase.SUCCESS)
                }
            }
        }
    }

    private fun updateMeteringSceneLocked(bytes: ByteBuffer, raw: RawShaderParameters, geometry: FrameGeometrySnapshot) {
        fun TapMeteringStatus.visible() = generation > 0 && phase != TapMeteringPhase.IDLE && !hidden
        val focus = mutableTapMeteringStatus.value
        val exposure = mutableExposureMeteringStatus.value
        if (!focus.visible() && !exposure.visible()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMeteringSceneSampleMs < 400) return
        lastMeteringSceneSampleMs = now
        val sample = RawSceneSampler.sample(bytes, raw, geometry) ?: return
        if (focus.visible() && focusScene.observe(now, sample)) mutableTapMeteringStatus.value = focus.copy(hidden = true)
        if (exposure.visible() && exposureScene.observe(now, sample)) mutableExposureMeteringStatus.value = exposure.copy(hidden = true)
    }

    private fun shouldProcessPreviewFrame(owner: ActiveSession, sensorTimestampNs: Long): Boolean =
        synchronized(lock) {
            val sampling = owner.key.capture
            val sample = owner.decimator.sample(sensorTimestampNs, sampling.frameInterval, sampling.averageFrames)
            val decision = when {
                closed || !resumed || attachedSurface == null || !surfaceReady ||
                    renderer == null || active !== owner || !owner.acceptsFrames -> PreviewCadenceDiagnostics.Decision.INACTIVE
                sample == null -> PreviewCadenceDiagnostics.Decision.PACED
                !previewFrames.canAcceptFrame -> PreviewCadenceDiagnostics.Decision.BUSY
                else -> PreviewCadenceDiagnostics.Decision.ACCEPT
            }
            owner.cadence.frame(sensorTimestampNs, SystemClock.elapsedRealtimeNanos(), decision, previewFrames.replacedFrames)?.let {
                InternalLogRecorder.event("PreviewCadence", "route=${owner.camera.route.routeId} " +
                    "engine=${configuredPreferences.aeEngine} meter=${configuredPreferences.aeMeter} " +
                    "target_fps=${owner.plan.framesPerSecond} interval=${sampling.frameInterval} average=${sampling.averageFrames} thermal=${InternalLogRecorder.thermalStatus()} $it")
            }
            if (decision == PreviewCadenceDiagnostics.Decision.ACCEPT && sample != null) {
                owner.samples[sensorTimestampNs] = sample
                while (owner.samples.size > 8) owner.samples.remove(owner.samples.keys.first())
                true
            } else {
                if (videoRecorder?.traceFrames == true && previewFrames.preserveFrameOrder) InternalLogRecorder.event("VideoFrame",
                    "capture_rejected sensor_ns=$sensorTimestampNs reason=$decision ${previewFrames.diagnosticState}")
                skippedFrames++
                false
            }
        }

    private fun renderFrame(
        owner: ActiveSession,
        metadata: com.unspektrawesome.camera.session.RawCaptureMetadata,
        output: com.unspektrawesome.camera.RawOutput,
        rowStrideBytes: Int,
        rawBytes: ByteBuffer?,
        queuedAtNs: Long,
        rawUpload: RawUpload? = null,
        hardwareBuffer: android.hardware.HardwareBuffer? = null,
        sample: RawFrameDecimator.Sample,
        isp: com.unspektrawesome.camera.session.IspHardwareFrame? = null,
    ) = synchronized(renderLock) renderWork@ {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        var submittedGeneration: Long? = null
        var presented = true
        runCatching {
            val prepareSubmission: (ByteBuffer?) -> PreviewSubmission? = { pixels ->
                synchronized(lock) prepare@ {
                    val currentRenderer = renderer
                    if (closed || !resumed || attachedSurface == null || !surfaceReady ||
                        currentRenderer == null || active !== owner || !owner.acceptsFrames
                    ) {
                        skippedFrames++
                        return@prepare null
                    }
                    runCatching { WhiteBalanceGains.fromRawCaptureMetadata(metadata) }
                        .getOrNull()?.let { latestWhiteBalance = it }
                    if (latestLensShadingMap?.matches(metadata) != true) {
                        runCatching { LensShadingMapParameters.fromRawCaptureMetadataOrNull(metadata) }
                            .getOrNull()?.let { latestLensShadingMap = it }
                    }

                    val whiteBalance = latestWhiteBalance ?: run {
                        skippedFrames++
                        publishLocked(
                            RawPreviewPhase.OPENING,
                            "Waiting for valid RAW white-balance metadata: " +
                                (metadata.neutralColorPoint()?.contentToString() ?: "missing"),
                            owner,
                        )
                        return@prepare null
                    }
                    val state = effectiveCaptureSnapshot()
                    val video = mutableVideoState.value
                    val peakingStrength = configuredPreferences.peakingStrengthFor(mutableCameraControls.value.autoFocus)
                        .takeIf { focusPeakingVisible }
                    val focusPeaking = peakingStrength != null
                    val previewState = viewfinderSpektraState(state.state,
                        mutableViewfinderSpektraEnabled.value && (!focusPeaking || video.enabled), videoMode = video.enabled)
                        .let { if (focusPeaking && !video.enabled) it.copy(params = it.params.copy(inputHighlights = 0f, inputShadows = 0f)) else it }
                    val recordingAttached = videoRenderer === currentRenderer
                    val crop = if (recordingAttached) recordingCrop ?: return@prepare null
                        else cropPreview ?: if (video.enabled) configuredPreferences.videoCrop else configuredPreferences.photoCrop
                    val processingSize = if (recordingAttached) {
                        val recorded = recordingGeometry ?: return@prepare null
                        if (recorded.transform.swapsAxes) com.unspektrawesome.camera.Size2d(recorded.outputSize.height, recorded.outputSize.width)
                        else recorded.outputSize
                    } else if (focusPeaking) {
                        val scale = minOf(.5f, focusPeakingResolution.shortEdge.toFloat() / minOf(output.size.width, output.size.height))
                        com.unspektrawesome.camera.Size2d(
                            (output.size.width * scale).toInt().coerceAtLeast(2) / 2 * 2,
                            (output.size.height * scale).toInt().coerceAtLeast(2) / 2 * 2,
                        )
                    } else owner.plan.processingSize
                    val photoGeometry = FrameGeometrySnapshot.createForPhotoCrop(
                            owner.camera.sensorMetadata,
                            output,
                            CaptureGeometry.transform(
                                owner.camera.sensorMetadata.orientationDegrees,
                                LOCKED_VIEWFINDER_ROTATION_DEGREES,
                                owner.camera.facing,
                            ),
                            crop,
                            rawEdgeCrop = configuredPreferences.rawEdgeCrop(),
                        )
                    val sourceGeometry = if (video.enabled) VideoFraming.source(owner.camera.sensorMetadata,
                        output, photoGeometry.transform, crop,
                        configuredPreferences.videoPixelCrop.takeIf { cropPreview == null },
                        configuredPreferences.rawEdgeCrop()) ?: photoGeometry else photoGeometry
                    val retainedGeometry = if (video.enabled && video.liveStabilization)
                        VideoFraming.stabilized(sourceGeometry, video.stabilizationSettings.cropRatio) else sourceGeometry
                    val encoding = videoEncodings[video.downscale]
                    val target = when {
                        recordingAttached -> recordingGeometry ?: return@prepare null
                        video.enabled -> (if (cropPreview == null && encoding != null &&
                            VideoFraming.fits(retainedGeometry, encoding.size))
                            VideoFraming.recording(retainedGeometry, encoding.size, video.downscale)
                            else retainedGeometry).limitedTo(processingSize)
                        else -> sourceGeometry.limitedTo(processingSize)
                    }
                    val framing = cropTransition.update(crop, target, processingSize, animate = !video.busy)
                    val geometry = framing.geometry
                    latestPreviewGeometry = PreviewGeometry(owner, framing)
                    // The Camera ISP viewfinder has no RAW frame to magnify.
                    val loupeRequest = focusLoupeRequest
                    val loupeGeometry = framing.target
                    val loupeValues = loupeRequest?.takeIf { isp == null }?.nativeValues(loupeGeometry,
                        com.unspektrawesome.vulkan.params.ChromaNoiseProfile.resolveNoiseProfilePair(
                            metadata.noiseProfile(), metadata.sensitivityIso ?: 100), focusLoupeGeneration)
                    if (serializedPreviewGeometry !== geometry) {
                        GeometryShaderParameters.from(geometry).writeTo(geometryParameterBuffer)
                        serializedPreviewGeometry = geometry
                    }
                    val raw = RawShaderParameters.forPreview(
                        output,
                        owner.camera.sensorMetadata,
                        metadata,
                        rowStrideBytes,
                        geometry.outputSize,
                        FrameSeedSnapshot.fromFrameNumber(metadata.frameNumber),
                        configuredPreferences.rawLevelCalibration(),
                        configuredPreferences.cfaOverride(),
                    )
                    if (pixels != null) updateMeteringSceneLocked(pixels, raw, target)
                    val rawColor = RawColorShaderParameters.fromState(
                        previewState,
                        owner.camera.sensorMetadata,
                        whiteBalance,
                        latestLensShadingMap,
                        requireNotNull(configuredColor).getOrThrow(),
                        focusPeakingStrength = peakingStrength?.shaderValue ?: 0f,
                        exposureZebraEnabled = configuredPreferences.exposureZebraEnabled,
                        previewHighlightRecovery = configuredPreferences.previewHighlightRecovery,
                        videoMode = video.enabled,
                        videoChromaDenoise = video.chromaDenoise,
                        videoChromaDenoiseStrength = video.chromaDenoiseStrength,
                        videoHotPixelRemoval = video.hotPixelRemoval,
                        captureMetadata = metadata,
                    )
                    if (lastContractSession !== owner.session) {
                        logRawContract("preview", owner, metadata, output.format, rowStrideBytes, raw, whiteBalance,
                            latestLensShadingMap, state)
                        lastContractSession = owner.session
                    }
                    raw.writeTo(rawParameterBuffer)
                    val sampleAt = SystemClock.elapsedRealtime()
                    if (sampleAt - lastRawSampleAtMs >= 10_000) {
                        lastRawSampleAtMs = sampleAt
                        if (pixels != null)
                            ImageDiagnostics.raw("preview-${owner.camera.route.routeId}-${metadata.frameNumber}", pixels, rawParameterBuffer)
                        InternalLogRecorder.event("PreviewExposure",
                            "route=${owner.camera.route.routeId} generation=$surfaceGeneration frame=${metadata.frameNumber} " +
                                "sensor_ns=${metadata.sensorTimestampNs} iso=${metadata.sensitivityIso} " +
                                "request_iso=${metadata.requestedSensitivityIso} " +
                                "exposure_ns=${metadata.exposureTimeNs} duration_ns=${metadata.frameDurationNs} " +
                                "aperture=${metadata.aperture} vf_s=${previewState.params.enabled} " +
                                "auto_exposure=${previewState.params.autoExposure} wb=$whiteBalance " +
                                "sensor_ae=${mutableCameraControls.value.customAe} ae_mode=${owner.exposureController.mode} " +
                                "ae_error_ev=${owner.exposureController.correctionEv} ae_limit=${owner.exposureController.limited} " +
                                "ae_generation=${metadata.exposureControlGeneration}")
                    }
                    rawColor.writeTo(rawColorParameterBuffer)
                    val spektraParameters = previewSpektraParameters(previewState)
                    val shadingParameters = lensShadingByteBuffer()
                    val timingOutput = performanceOutput.takeIf { measuresPerformanceLocked() || focusPeaking }
                    val meter = prepareCustomMeterLocked(owner, metadata)
                    val meterId = if (meter != null) ++exposureMeterSequence else 0L
                    val meterMethod = configuredPreferences.aeMeter.ordinal
                    val meterPoint = owner.exposurePoint
                    val meterSurfaceGeneration = surfaceGeneration
                    val copiedBytes = pixels.takeIf { rawUpload == null }
                    val directHardware = hardwareBuffer.takeUnless { isp != null || configuredPreferences.rawLayoutOverride().enabled }
                    val profile = configuredPreferences.lensProfiles.firstOrNull { it.id == configuredPreferences.activeLensProfileId }
                    val captureColor = requireNotNull(configuredColor).getOrThrow()
                    val averageRevision = state.revisionId * 31L + configuredPreferences.hashCode().toLong() * 2L +
                        if (previewState.params.enabled) 1L else 0L
                    PreviewSubmission(currentRenderer, surfaceGeneration, state.revisionId,
                        framing.target.cropAspect,
                        requireNotNull(latestPreviewGeometry),
                        performanceGeneration.takeIf { timingOutput != null },
                        focusPeaking,
                        previewState.params.run {
                            enabled && (grainEnabled || halationEnabled || cameraDiffusionEnabled ||
                                printDiffusionEnabled || scannerEnabled)
                    }) {
                        if (exposureMeterRenderer !== currentRenderer || exposureMeterSurfaceGeneration != meterSurfaceGeneration) {
                            pendingExposureMeters.clear()
                            exposureMeterRenderer = currentRenderer
                            exposureMeterSurfaceGeneration = meterSurfaceGeneration
                        }
                        if (meter != null) pendingExposureMeters[meterId] = meter
                        while (pendingExposureMeters.size > 8) pendingExposureMeters.remove(pendingExposureMeters.keys.first())
                        exposureMeterBuffer.request(meterId, meterMethod, meterPoint?.first ?: -1f, meterPoint?.second ?: -1f)
                        val recording = videoRecorder
                        val stabilization = recording?.stabilization.takeIf { recordingAttached }
                        val correction = stabilization?.update(metadata)
                        geometryParameterBuffer.putFloat(GeometryShaderParameters.STABILIZATION_OFFSET, 0f)
                        stabilization?.writeTo(geometryParameterBuffer, correction)
                        if (stabilization != null) synchronized(lock) {
                            if (stabilization.failed) {
                                InternalLogRecorder.event("LiveStabilization", "Unavailable: ${stabilization.reason}")
                                commands.submit { synchronized(lock) {
                                    if (videoRecorder === recording) stopVideoLocked("Live stabilization is unavailable for this camera mode")
                                } }
                            }
                            val limited = correction != null && correction.scale < .99f
                            if (mutableVideoState.value.stabilizationLimited != limited)
                                mutableVideoState.value = mutableVideoState.value.copy(stabilizationLimited = limited)
                        }
                        val timestamp = if (video.phase in listOf(VideoPhase.RECORDING, VideoPhase.SAVING) &&
                            recordingAttached && recording?.started == true && sample.output &&
                            (stabilization == null || correction != null))
                            recording.timestamp(metadata.sensorTimestampNs) else 0L
                        val previousVideoFrames = if (timestamp > 0L) currentRenderer.videoFrames() else -1L
                        if (timestamp > 0L) recording?.submittedFrame(timestamp, metadata)
                        val previousPresented = if (sample.count > 1) currentRenderer.presentedFrames() else -1L
                        val sampling = owner.key.capture
                        val hintTargetNs = RenderPerformanceHints.targetDurationNs(
                            if (video.enabled) video.frameRate * sampling.frameInterval else owner.plan.framesPerSecond.toDouble(),
                            sampling.frameInterval, sampling.averageFrames,
                            if (video.enabled) 0L else metadata.exposureTimeNs ?: 0L,
                            recording = recordingAttached,
                        )
                        val hintStartedNs = performanceHints.beginFrame(hintTargetNs, recordingAttached,
                            gpuReorderDepth = sample.count - 1)
                        val frameTimingOutput = timingOutput ?: performanceOutput.takeIf { performanceHints.measuresGpu }
                        val ispPlanes = isp?.let { prepareIspParameters(owner, it) }
                        currentRenderer.setFocusLoupe(loupeValues ?: FocusLoupeRequest.DISABLED)
                        val rendered = currentRenderer.render(
                            directHardware, copiedBytes,
                            rawParameterBuffer, rawColorParameterBuffer, geometryParameterBuffer,
                            spektraParameters, shadingParameters,
                            geometry.outputSize.width, geometry.outputSize.height,
                            frameTimingOutput,
                            exposureMeterBuffer,
                            timestamp,
                            rawUpload,
                            averageGroup = sample.group,
                            averageIndex = sample.index,
                            averageCount = sample.count,
                            averageRevision = averageRevision,
                            previewTimestampNs = metadata.sensorTimestampNs,
                            ispHardwareBuffer = isp?.hardwareBuffer?.takeIf { ispPlanes == null },
                            ispPlaneBytes = ispPlanes,
                            ispParameters = if (isp != null) ispShaderParameters.buffer else null,
                        )
                        if (rendered) performanceHints.endFrame(hintStartedNs,
                            if (frameTimingOutput != null) performanceOutput.getLong(32) else 0L,
                            if (frameTimingOutput != null) performanceOutput.getDouble(0) else -1.0)
                        else performanceHints.close()
                        if (loupeRequest != null) {
                            val result = if (loupeValues != null) currentRenderer.focusLoupeResult() else FloatArray(FocusLoupeResult.SIZE)
                            result?.let { publishFocusLoupe(it, loupeGeometry, focusLoupeFocalPixels(owner, loupeGeometry)) }
                        }
                        presented = sample.count == 1 || currentRenderer.presentedFrames() > previousPresented
                        if (rendered && timestamp > 0L && timingOutput != null)
                            recording?.encoderInputWait(performanceOutput.getLong(16), performanceOutput.getLong(24).toInt())
                        if (recording?.traceFrames == true && timestamp > 0L) InternalLogRecorder.event("VideoFrame",
                            "render sensor_ns=${metadata.sensorTimestampNs} pts_ns=$timestamp success=$rendered " +
                                "encoder_queued=${currentRenderer.videoFrames() > previousVideoFrames} " +
                                "input_wait_us=${performanceOutput.getLong(16) / 1_000} input_capacity=${performanceOutput.getLong(24)} " +
                                "queue_us=${(startedNs - queuedAtNs) / 1_000} ${previewFrames.diagnosticState}")
                        if (rendered && timestamp > 0L && currentRenderer.videoFrames() > previousVideoFrames) {
                            recording?.encoderFrameSubmitted(timestamp)
                            recording?.metadataFrame(timestamp, averageRevision) {
                                CaptureMetadata(metadata.sensorTimestampNs, metadata.exposureTimeNs, metadata.sensitivityIso,
                                    metadata.focalLengthMm ?: owner.camera.focalLengthMm, owner.camera.displayName(),
                                    aperture = metadata.aperture,
                                    equivalentFocalLengthMm = profile?.focalLength?.equivalent(owner.camera) ?: owner.camera.equivalentFocalLengthMm,
                                    identity = CaptureIdentity.current, spektra = SpektraShotMetadata.from(previewState.params),
                                    colorCalibration = captureColor.description,
                                    dcpId = captureColor.settings.dcpId.takeIf { captureColor.settings.dcpEnabled })
                            }
                        }
                        rendered
                    }
                }
            }
            val submission = (if (isp != null) prepareSubmission(null)
                else if (rawUpload != null) rawUpload.read(prepareSubmission)
                else rawBytes?.let(prepareSubmission)) ?: return@renderWork
            val preparedNs = SystemClock.elapsedRealtimeNanos()
            submittedGeneration = submission.generation
            val rendered = synchronized(submission.renderer) {
                val current = synchronized(lock) {
                    !closed && resumed && active === owner && owner.acceptsFrames &&
                        surfaceReady && surfaceGeneration == submission.generation
                }
                if (!current) return@renderWork
                // Surface replacement and release share the renderer monitor, not the state lock.
                lastRenderStartedAtMs = SystemClock.elapsedRealtime()
                submission.render()
            }
            val submittedNs = SystemClock.elapsedRealtimeNanos()
            if (videoRenderer === submission.renderer) {
                val frames = submission.renderer.videoFrames()
                if (frames < 0) synchronized(lock) { stopVideoLocked("Video recording failed") }
                else if (frames > lastVideoFrameCount) {
                    lastVideoFrameCount = frames
                    lastVideoFrameAtMs = SystemClock.elapsedRealtime()
                }
            }
            if (rendered) {
                if (presented) completeVideoWarmup(submission.renderer)
                deliverCustomMeterResult()
                pollCustomMeter()
                synchronized(lock) {
                    if (active === owner && surfaceGeneration == submission.generation &&
                        measuresPerformanceLocked() && submission.performanceGeneration == performanceGeneration) {
                        val gpuStartedNs = performanceOutput.getLong(32)
                        // Native steady-clock timestamps exclude suspend time; elapsedRealtime includes it.
                        val gpuStartedAtMs = if (gpuStartedNs > 0) SystemClock.elapsedRealtime() -
                            (System.nanoTime() - gpuStartedNs) / 1_000_000 else startedNs / 1_000_000
                        performanceMeter.recordFrame(performanceOutput.getDouble(0), PreviewFrameInfo(
                            submission.geometry.framing.geometry.outputSize, output.size, submission.effectsEnabled),
                            startedAtMs = gpuStartedAtMs,
                            presented = presented, capturesPerOutput = sample.count)
                    }
                }
            } else pendingExposureMeters.clear()
            if (rendered && !presented) return@renderWork
            owner.cadence.completed(startedNs - queuedAtNs, preparedNs - startedNs, submittedNs - preparedNs,
                SystemClock.elapsedRealtimeNanos() - submittedNs, rendered)
            val diagnostic = if (rendered) "" else submission.renderer.diagnostics()
            synchronized(lock) {
                if (closed || active !== owner || !owner.acceptsFrames || !resumed ||
                    surfaceGeneration != submission.generation || !surfaceReady) return@synchronized
                if (rendered) {
                    owner.lastRenderSucceeded = true
                    lastRenderCompletedAtMs = SystemClock.elapsedRealtime()
                    if (submission.focusPeaking) {
                        val gpuMs = performanceOutput.getDouble(0)
                        focusPeakingResolution.record(if (gpuMs.isFinite() && gpuMs >= 0) gpuMs.toLong()
                            else lastRenderCompletedAtMs - lastRenderStartedAtMs)
                    }
                    if (isp != null) owner.ispFailures = 0
                    else recordRawImportCapability(
                        owner,
                        RawImportCapability(
                            RawImportCapabilityState.COMPATIBLE,
                            when (output.format) {
                                RawFormat.RAW_SENSOR ->
                                    "Actual Camera2 RAW_SENSOR plane uploaded to a row-stride-aware Vulkan buffer"
                                RawFormat.RAW10, RawFormat.RAW12 ->
                                    "Actual Camera2 ${output.format} plane uploaded to a Vulkan storage buffer"
                            },
                        ),
                    )
                    renderedFrames++
                    renderedFraming = submission.geometry.framing
                    displayedPreviewGeometry = submission.geometry
                    configureGridPreviewLocked()
                    val completedLensSwitch = lensSwitching
                    lensSwitching = false
                    val now = SystemClock.elapsedRealtime()
                    val variableExposure = mutableCameraControls.value.customAe
                    if (owner.startupReadiness.onFrameCompleted(now, submission.revision, variableExposure)) {
                        sessionStartupTimeout?.cancel(false)
                        sessionStartupTimeout = null
                        startupGuard.finished()
                        sessionRecovery.previewReady()
                        mutableSessionRecovery.value = sessionRecovery.status
                    } else {
                        scheduleSessionStartupTimeoutLocked(owner)
                    }
                    val viewfinderReady = startupReadiness.onFrameCompleted(now, submission.revision, variableExposure)
                    val previous = mutableStatus.value
                    if (completedLensSwitch || previous.phase != RawPreviewPhase.STREAMING ||
                        previous.renderedAspectRatio != submission.aspectRatio ||
                        previous.cropTransitionRevision != submission.geometry.framing.revision ||
                        previous.viewfinderReady != viewfinderReady ||
                        previous.captureReady != captureReadyLocked(owner) ||
                        previous.spektraRevision != submission.revision ||
                        now - lastSuccessPublishAtMs >= STATUS_PUBLISH_INTERVAL_MS
                    ) {
                        lastSuccessPublishAtMs = now
                        publishLocked(
                            RawPreviewPhase.STREAMING,
                            if (isp != null) "Camera ISP viewfinder streaming" else "RAW Vulkan preview streaming",
                            owner,
                            submission.revision,
                        )
                    }
                } else {
                    owner.lastRenderSucceeded = false
                    skippedFrames++
                    startupReadiness.onRenderFailed()
                    val importRejection = if (isp != null) null else
                        RawImportDiagnosticClassifier.importRejectionReason(diagnostic)
                    if (isp != null) {
                        rejectIspFrameLocked(owner, isp, diagnostic)
                    } else if (importRejection != null) {
                        recordRawImportCapability(
                            owner,
                            RawImportCapability(
                                RawImportCapabilityState.INCOMPATIBLE,
                                importRejection,
                            ),
                        )
                        blockedConfiguration = owner.key
                        failSessionRecoveryLocked(importRejection)
                        publishLocked(
                            RawPreviewPhase.ERROR,
                            "${output.format} ${output.size.width} x " +
                                "${output.size.height} cannot use Vulkan zero-copy import: " +
                                importRejection,
                            owner,
                        )
                        owner.session.close()
                    } else {
                        publishLocked(
                            RawPreviewPhase.ERROR,
                            "Vulkan RAW error: $diagnostic",
                            owner,
                        )
                    }
                }
            }
        }.onFailure { error ->
            performanceHints.close()
            synchronized(lock) {
                if (closed || active !== owner || !owner.acceptsFrames || !resumed ||
                    submittedGeneration?.let { it != surfaceGeneration } == true) return@synchronized
                if (SystemClock.elapsedRealtime() - lastDiagnosticPublishAtMs >= 5_000)
                    InternalLogRecorder.error("Preview", "render_failed route=${owner.camera.route.routeId} frame=${metadata.frameNumber}", error)
                skippedFrames++
                owner.lastRenderSucceeded = false
                startupReadiness.onRenderFailed()
                if (isp != null) {
                    rejectIspFrameLocked(owner, isp, error.message ?: error.javaClass.simpleName)
                    return@synchronized
                }
                publishLocked(
                    RawPreviewPhase.ERROR,
                    "RAW frame render failed: ${error.message ?: error.javaClass.simpleName}",
                    owner,
                )
            }
        }
    }

    /** The ISP stream feeds the viewfinder only in photo mode; video and panoramas keep the RAW viewfinder. */
    private fun ispRendering(owner: ActiveSession): Boolean =
        owner.key.ispViewfinder && !owner.ispRejected && !mutableVideoState.value.enabled &&
            !panoramaState.value.enabled && owner.session.ispViewfinderStreaming()

    /** Fills the ISP shader parameters and returns copied planes when the hardware image cannot be sampled. */
    private fun prepareIspParameters(owner: ActiveSession, frame: com.unspektrawesome.camera.session.IspHardwareFrame): ByteBuffer? {
        val yuv = frame.format == android.graphics.ImageFormat.YUV_420_888
        ispShaderParameters.setSource(frame.width, frame.height, fullRange = true, rangeFromBuffer = !yuv)
        val boost = frame.captureResult?.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
        // Match the RAW viewfinder, which never sees the digital gain the ISP adds after RAW.
        ispShaderParameters.setExposureGain(if (boost != null && boost > 0) 100f / boost else 1f)
        val curve = frame.captureResult?.get(android.hardware.camera2.CaptureResult.TONEMAP_CURVE)
        if (curve != loggedIspCurve || owner !== loggedIspOwner) {
            loggedIspCurve = curve
            loggedIspOwner = owner
            val points = curve?.let { FloatArray(it.getPointCount(1) * 2).also { values -> it.copyColorCurve(1, values, 0) } }
            val sample = points?.let { values -> (0 until values.size / 2).filter { it % maxOf(1, values.size / 16) == 0 || it == values.size / 2 - 1 }
                .joinToString(" ") { "%.3f:%.3f".format(java.util.Locale.ROOT, values[it * 2], values[it * 2 + 1]) } }
            InternalLogRecorder.event("IspViewfinder", "route=${owner.camera.route.routeId} format=${frame.format} " +
                "tonemap_mode=${frame.captureResult?.get(android.hardware.camera2.CaptureResult.TONEMAP_MODE)} " +
                "points=${curve?.getPointCount(1)} boost=$boost curve=$sample")
        }
        ispShaderParameters.setTonemapCurve(curve)
        if (!yuv || (frame.hardwareBuffer != null && !owner.ispCopyPlanes)) return null
        val planes = frame.planes() ?: return null
        return ispShaderParameters.packPlanes(planes, frame.width, frame.height)
    }

    private fun rejectIspFrameLocked(owner: ActiveSession, frame: com.unspektrawesome.camera.session.IspHardwareFrame, reason: String) {
        val copyPlanes = frame.format == android.graphics.ImageFormat.YUV_420_888 && frame.hardwareBuffer != null &&
            !owner.ispCopyPlanes
        owner.ispFailures++
        val fallback = when {
            copyPlanes -> "planes"
            owner.ispFailures >= ISP_FAILURE_LIMIT -> "raw"
            else -> "retry"
        }
        InternalLogRecorder.warning("IspViewfinder", "route=${owner.camera.route.routeId} format=${frame.format} " +
            "size=${frame.width}x${frame.height} failures=${owner.ispFailures} fallback=$fallback reason=$reason")
        when (fallback) {
            "planes" -> {
                owner.ispCopyPlanes = true
                owner.ispFailures = 0
            }
            "raw" -> {
                owner.ispRejected = true
                publishLocked(RawPreviewPhase.STREAMING,
                    "Camera ISP viewfinder is unavailable for this lens. Using the RAW viewfinder.", owner)
            }
        }
    }

    private fun lensShadingByteBuffer(
        map: LensShadingMapParameters? = latestLensShadingMap,
    ): ByteBuffer? {
        val dcp = configuredColor?.getOrThrow()?.activeDcp
        if (map == null && dcp?.hasHueSatMap != true) return null
        val size = RawCalibrationTables.sizeBytes(map, dcp)
        val current = lensShadingBuffer
        if (current != null && serializedLensShadingMap === map && serializedDcp === dcp) {
            current.position(0)
            current.limit(size)
            return current
        }
        val target = if (current == null || current.capacity() < size) {
            directBuffer(size).also { lensShadingBuffer = it }
        } else {
            current
        }
        return RawCalibrationTables.writeTo(target, map, dcp).also {
            serializedLensShadingMap = map
            serializedDcp = dcp
        }
    }

    private fun previewSpektraParameters(state: SpektraState): ByteBuffer {
        if (previewSpektraRevision != state.revisionId || previewSpektraParams != state.params) {
            histogramGeneration++
            state.params.writeTo(previewSpektraParameterBuffer)
            previewSpektraRevision = state.revisionId
            previewSpektraParams = state.params
        } else {
            previewSpektraParameterBuffer.position(0)
            previewSpektraParameterBuffer.limit(SpektraFilmParams.STRUCT_SIZE)
        }
        val now = SystemClock.elapsedRealtime()
        if (loggedSpektraGeneration != surfaceGeneration || loggedSpektraEnabled != state.params.enabled ||
            loggedSpektraRevision != state.revisionId && now - lastSpektraLogAtMs >= 2_000) {
            InternalLogRecorder.event("PreviewSpektra",
                "generation=$surfaceGeneration revision=${state.revisionId} params=${state.params.toJsonObject()}")
            loggedSpektraRevision = state.revisionId
            loggedSpektraGeneration = surfaceGeneration
            loggedSpektraEnabled = state.params.enabled
            lastSpektraLogAtMs = now
        }
        return previewSpektraParameterBuffer
    }

    private fun recordRawImportCapability(
        owner: ActiveSession,
        capability: RawImportCapability,
    ) {
        val key = RawImportStreamKey.from(
            owner.camera.route.routeId,
            owner.plan.acquisitionOutput,
            owner.key.vendorOverrides,
            owner.key.rawLayoutOverride,
            owner.key.capture,
            owner.key.profileRawSession,
        )
        if (mutableRawImportCapabilities.value[key] == capability) return
        mutableRawImportCapabilities.value =
            mutableRawImportCapabilities.value + (key to capability)
    }

    private fun publishLocked(
        phase: RawPreviewPhase,
        message: String,
        owner: ActiveSession? = active,
        revision: Long? = mutableStatus.value.spektraRevision,
    ) {
        val diagnostic = rendererDiagnostic
        val previous = mutableStatus.value
        if (previous.phase != phase) resetPerformanceLocked()
        val now = SystemClock.elapsedRealtime()
        val captureReady = phase == RawPreviewPhase.STREAMING && captureReadyLocked(owner)
        if (previous.captureReady != captureReady) {
            InternalLogRecorder.event("CaptureGate", "ready=$captureReady route=${owner?.camera?.route?.routeId} " +
                "generation=$surfaceGeneration phase=$phase sessionReady=${owner?.startupReadiness?.isReady} " +
                "lensSwitching=$lensSwitching")
        }
        if (previous.phase != phase || previous.viewfinderReady != startupReadiness.isReady ||
            (previous.message != message || previous.spektraRevision != revision) && now - lastDiagnosticPublishAtMs >= 5_000) {
            lastDiagnosticPublishAtMs = now
            val detail = "phase=$phase ready=${startupReadiness.isReady} route=${owner?.camera?.route?.routeId} " +
                "generation=$surfaceGeneration frames=$renderedFrames skipped=$skippedFrames revision=$revision " +
                "message=$message diagnostic=$diagnostic"
            if (phase == RawPreviewPhase.ERROR) InternalLogRecorder.error("PreviewState", detail)
            else InternalLogRecorder.event("PreviewState", detail)
        }
        mutableStatus.value = RawPreviewStatus(
            phase = phase,
            message = message,
            rendererDiagnostic = diagnostic,
            renderedFrames = renderedFrames,
            skippedFrames = skippedFrames + previewFrames.replacedFrames,
            droppedCameraFrames = owner?.session?.droppedPendingFrames() ?: 0L,
            spektraRevision = revision,
            lensSwitching = lensSwitching,
            viewfinderReady = startupReadiness.isReady,
            captureReady = captureReady,
            captureRouteId = if (captureReady) owner?.camera?.route?.routeId else null,
            renderedAspectRatio = renderedFraming?.target?.cropAspect,
            surfaceAspectRatio = renderedFraming?.target?.activeAspect,
            cropTransitionRevision = renderedFraming?.revision ?: 0L,
        )
    }

    private data class ConfigurationKey(
        val camera: CameraDescriptor,
        val lensProfileId: String?,
        val acquisitionOutput: com.unspektrawesome.camera.RawOutput,
        val processingWidth: Int,
        val processingHeight: Int,
        val framesPerSecond: Int,
        val sessionConfiguration: com.unspektrawesome.camera.session.RawSessionConfiguration?,
        val vendorOverrides: VendorOverrides,
        val rawLayoutOverride: RawLayoutOverride,
        val capture: LensCaptureSettings,
        val photoOisEnabled: Boolean,
        val profileRawSession: com.unspektrawesome.camera.session.RawSessionConfiguration?,
        val ispViewfinder: Boolean,
    ) {
        constructor(camera: CameraDescriptor, plan: RawPreviewPolicy.Plan, preferences: CameraPreferences, frameRateOverride: Int? = null) : this(
            camera,
            preferences.activeLensProfileId,
            plan.acquisitionOutput,
            plan.processingSize.width,
            plan.processingSize.height,
            plan.framesPerSecond,
            plan.sessionConfiguration,
            preferences.vendorOverrides(),
            preferences.rawLayoutOverride(),
            preferences.lensCaptureSettings().sessionSettings().let { it.copy(frameRate = frameRateOverride ?: it.frameRate) },
            preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId && it.enabled }?.photoOisEnabled ?: true,
            preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId && it.enabled }?.rawSession,
            preferences.viewfinderSource == com.unspektrawesome.settings.ViewfinderSource.ISP,
        )
    }

    private fun logRawContract(
        stage: String,
        owner: ActiveSession,
        metadata: com.unspektrawesome.camera.session.RawCaptureMetadata,
        format: RawFormat,
        rowStrideBytes: Int,
        raw: RawShaderParameters,
        whiteBalance: WhiteBalanceGains,
        shading: LensShadingMapParameters?,
        snapshot: SpektraCaptureSnapshot,
    ) {
        InternalLogRecorder.event("RawContract", "stage=$stage route=${owner.camera.route.routeId} " +
            "frame=${metadata.frameNumber} revision=${snapshot.revisionId} " +
            "iso=${metadata.sensitivityIso} exposure_ns=${metadata.exposureTimeNs} " +
            "format=$format raster=${raw.rawWidth}x${raw.rawHeight} " +
            "stride=$rowStrideBytes cfa=${raw.cfaPattern} block=${raw.cfaBlockSize} reportedBinning=${owner.camera.sensorMetadata.cfaBinning} binningUsed=${metadata.rawBinningFactorUsed} " +
            "black=${raw.blackLevelR},${raw.blackLevelG1},${raw.blackLevelG2},${raw.blackLevelB} " +
            "white=${raw.whiteLevelR} wb=$whiteBalance " +
            "shading=${shading?.width ?: 0}x${shading?.height ?: 0} " +
            "input=${snapshot.params.inputColorSpace} output=${snapshot.params.outputColorSpace} " +
            "role=${snapshot.params.outputRole}")
    }

    private fun scheduleSessionStartupTimeoutLocked(owner: ActiveSession) {
        sessionStartupTimeout?.cancel(false)
        val timeoutGeneration = ++owner.startupTimeoutGeneration
        val size = owner.plan.processingSize
        val pixels = size.width.toLong() * size.height
        val baseTimeout = when {
            // Large previews can compile the full film pipeline on first use.
            pixels > 8L * 1024L * 1024L -> 180_000L
            pixels > 1024L * 1024L -> 60_000L
            else -> 20_000L
        }
        val timeoutMs = maxOf(baseTimeout, mutableCameraControls.value.exposureTimeNs / 1_000_000L * 2 + 5_000L,
            4_000L * owner.key.capture.frameInterval / owner.plan.framesPerSecond.coerceAtLeast(1))
        sessionStartupTimeout = commands.schedule(timeoutMs) {
            synchronized(lock) {
                if (!closed && active === owner && owner.acceptsFrames && !owner.startupReadiness.isReady &&
                    owner.startupTimeoutGeneration == timeoutGeneration) {
                    failSessionRecoveryLocked("Lens session configuration timed out")
                    stopActiveLocked()
                }
            }
        }
    }

    private data class ActiveSession(
        val session: Camera2RawSession,
        val camera: CameraDescriptor,
        val plan: RawPreviewPolicy.Plan,
        val key: ConfigurationKey,
        val controlSessionGeneration: Long,
        var acceptsFrames: Boolean = true,
        var lastRenderSucceeded: Boolean = false,
        val decimator: RawFrameDecimator = RawFrameDecimator(),
        val samples: MutableMap<Long, RawFrameDecimator.Sample> = linkedMapOf(),
        val cadence: PreviewCadenceDiagnostics = PreviewCadenceDiagnostics(),
        val startupReadiness: PreviewStartupReadiness = PreviewStartupReadiness(),
        var startupTimeoutGeneration: Long = 0,
        @Volatile var packedFrameBytes: Int = 0,
        val exposureController: SpektraExposureController = SpektraExposureController(),
        var lastExposureSampleNs: Long = 0,
        var exposurePoint: Pair<Float, Float>? = null,
        @Volatile var ispRejected: Boolean = false,
        @Volatile var ispCopyPlanes: Boolean = false,
        var ispFailures: Int = 0,
    )

    private data class PreviewGeometry(
        val owner: ActiveSession,
        val framing: PreviewCropTransition.Frame,
    )

    private data class PendingCapture(
        val owner: ActiveSession,
        val snapshot: SpektraCaptureSnapshot,
        val captureOutput: com.unspektrawesome.camera.RawOutput,
        val geometry: FrameGeometrySnapshot,
        val lensShadingMap: LensShadingMapParameters?,
        val spektraParameters: ByteBuffer,
        val spektraTimeSeconds: Double,
        val capturedAtMillis: Long,
        val rawLevels: RawLevelCalibration,
        val processLater: Boolean,
        val saveOriginalDng: Boolean,
        val equivalentFocalLengthMm: Float?,
        val lensProfile: com.unspektrawesome.capture.LensProfileShotMetadata?,
        val cfaOverride: CfaOverride,
        val color: ResolvedLensColor,
        var exposureTimeNs: Long,
        var exposureStartedAtMs: Long? = null,
        val location: CaptureLocation? = null,
        val rawBuffer: BufferedRawCaptureQueue.Entry? = null,
        var frameReceived: Boolean = false,
        // Multi-frame merge settings of the active lens profile at the shutter press.
        val merge: LensCaptureSettings = LensCaptureSettings(),
        // Set when merge was on but the photo was taken from a single frame.
        var mergeFallback: com.unspektrawesome.capture.MergeShotMetadata? = null,
    )

    private companion object {
        const val STATUS_PUBLISH_INTERVAL_MS = 1_000L
        const val NANOSECONDS_TO_SECONDS = 1e-9
        const val LOCKED_VIEWFINDER_ROTATION_DEGREES = 0
        const val MERGE_COPY_WAIT_MS = 120L
        const val STALE_MERGE_FRAME_MS = 1_000L
        const val ISP_FAILURE_LIMIT = 3

        fun directBuffer(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size)
            .order(ByteOrder.nativeOrder())

        val TERMINAL_SESSION_STATES = setOf(
            Camera2RawSession.State.CLOSED,
            Camera2RawSession.State.UNSUPPORTED,
            Camera2RawSession.State.ERROR,
        )
    }
}
