package com.unspektrawesome

import com.unspektrawesome.diagnostics.InternalLogRecorder

import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguagePicker
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.unspektrawesome.camera.CameraDiscovery
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.CameraOrientation
import com.unspektrawesome.controls.ControlAction
import com.unspektrawesome.controls.ControlBindings
import com.unspektrawesome.controls.ControlInputRouter
import com.unspektrawesome.capture.CaptureGeometry
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.gallery.GalleryActivity
import com.unspektrawesome.gallery.GALLERY_GUIDE_REVISION
import com.unspektrawesome.gallery.PhotoViewerActivity
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.LensProfiles
import com.unspektrawesome.settings.LensProfileMode
import com.unspektrawesome.settings.copyProfiles
import com.unspektrawesome.settings.lensToneKey
import com.unspektrawesome.settings.rawEdgeCrop
import com.unspektrawesome.settings.rawLayoutOverride
import com.unspektrawesome.settings.CameraPreferencesStore
import com.unspektrawesome.settings.LensButtonMapping
import com.unspektrawesome.settings.SensorProfileCatalog
import com.unspektrawesome.preview.RawPreviewPhase
import com.unspektrawesome.preview.RawCapturePhase
import com.unspektrawesome.preview.RawVulkanPreviewController
import com.unspektrawesome.preview.RawPreviewPlanSelector
import com.unspektrawesome.preview.VulkanRawPreviewView
import com.unspektrawesome.spektra.SharedPreferencesSpektraStatePersistence
import com.unspektrawesome.spektra.AsyncSpektraStatePersistence
import com.unspektrawesome.spektra.SpektraStateRepository
import com.unspektrawesome.ui.camera.CameraScreen
import com.unspektrawesome.ui.camera.PhotoQueueDialog
import com.unspektrawesome.ui.camera.ShaderPreparationDialog
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.rememberCaptureFeedbackState
import com.unspektrawesome.ui.camera.CAMERA_GUIDE_STEPS
import com.unspektrawesome.ui.camera.CAMERA_GUIDE_REVISION
import com.unspektrawesome.ui.camera.VIDEO_GUIDE_REVISION
import com.unspektrawesome.ui.camera.VIDEO_GUIDE_STEPS
import com.unspektrawesome.ui.camera.CAMERA_PROCESSING_GUIDE_STEPS
import com.unspektrawesome.ui.camera.UnspektrawesomeTheme
import com.unspektrawesome.ui.camera.AppUpdateHost
import com.unspektrawesome.ui.camera.rememberUpdateController
import com.unspektrawesome.ui.onboarding.OnboardingFlow
import com.unspektrawesome.ui.onboarding.GuideProgressStore
import com.unspektrawesome.ui.onboarding.ONBOARDING_REVISION
import com.unspektrawesome.ui.onboarding.ONBOARDING_PAGES
import com.unspektrawesome.camera.discovery.*
import com.unspektrawesome.settings.AutoLensProfiles
import com.unspektrawesome.ui.camera.AutoLensDiscoveryDialog
import kotlinx.coroutines.*
import com.unspektrawesome.vulkan.VulkanBridge
import com.unspektrawesome.video.VideoEncoding
import com.unspektrawesome.video.VideoFraming
import java.util.concurrent.Executors

open class MainActivity : ComponentActivity() {
    /** True when started from the lock screen; see [SecureCameraActivity]. */
    protected open val lockScreenCamera = false
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) = finish()
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val spektraPersistence by lazy {
        AsyncSpektraStatePersistence(
            SharedPreferencesSpektraStatePersistence(
                getSharedPreferences("spektra", MODE_PRIVATE),
            ),
        )
    }
    private val spektraRepository by lazy {
        SpektraStateRepository(spektraPersistence,
            customProfiles = com.unspektrawesome.spektra.SpektraProfiles(
                com.unspektrawesome.spektra.SharedPreferencesSpektraProfilePersistence(getSharedPreferences("spektra", MODE_PRIVATE)),
            ),
            cardOrders = com.unspektrawesome.spektra.SpektraCardOrders(
                com.unspektrawesome.spektra.SharedPreferencesSpektraCardOrderPersistence(getSharedPreferences("spektra", MODE_PRIVATE)),
            ),
            lensCorrections = lensCorrections,
        )
    }
    private val lensTones by lazy { com.unspektrawesome.settings.SharedPreferencesLensToneStorage(this) }
    private val lensCorrections: com.unspektrawesome.settings.LensCorrectionCoordinator by lazy {
        com.unspektrawesome.settings.LensCorrectionCoordinator(
            tones = lensTones,
            exposure = com.unspektrawesome.settings.LensExposureStore(this),
            preferences = { cameraPreferences },
            selectedRouteId = { selectedLensId },
            cameraRouteIds = { cameras.map { it.route.routeId } },
            savePreferences = ::saveCameraPreferences,
            setActiveExposureCompensation = { previewController.setExposureCompensation(it) },
        )
    }
    private val cameraPreferencesStore by lazy { CameraPreferencesStore(this) }
    private var spektraAeFeedbackId by mutableIntStateOf(0)
    private var hardwareControls: ((ControlAction, Int) -> Unit)? = null
    private val controlInputs = ControlInputRouter(bindings = { ControlBindings.state.value }, handler = {
        hardwareControls?.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() }
    }, feedback = { step ->
        // No vibration while recording, where the microphone and the image would pick it up.
        if (!previewController.videoState.value.busy) window.decorView.performHapticFeedback(
            if (step) android.view.HapticFeedbackConstants.CLOCK_TICK else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    })
    private val usbControls by lazy {
        com.unspektrawesome.controls.UsbControlReader(this, controlInputs::onDirectButton, controlInputs::onDirectWheel)
    }
    private val onboardingPreferences by lazy { getSharedPreferences("onboarding", MODE_PRIVATE) }
    private var onboardingCompleted by mutableStateOf(false)
    private var storageAccessReviewed by mutableStateOf(false)
    private var cameraGuideStep by mutableIntStateOf(0)
    private var processingGuideStep by mutableIntStateOf(0)
    private var videoGuideStep by mutableIntStateOf(0)
    private var modeSwipeHintPending by mutableStateOf(false)
    private val guideProgress by lazy { GuideProgressStore(this) }
    private var cameraPermissionGranted by mutableStateOf(false)
    private var cameraStarted = false
    private var cameraDiscoveryNeeded = true
    private val cameraDiscovery by lazy { CameraDiscovery(this) }
    private val discoveryStore by lazy { LensDiscoveryStore(this) }
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryJob: Job? = null
    private var discoveryVisible by mutableStateOf(false)
    private var discoveryProgress by mutableStateOf(LensDiscoveryProgress())
    private var discoveryReport by mutableStateOf<LensDiscoveryReport?>(null)
    private var discoveryMessage by mutableStateOf<String?>(null)
    private var manualLensProfilesRequest by mutableIntStateOf(0)
    private val cameraLocation by lazy { com.unspektrawesome.camera.CameraLocationController(this) }
    private var cameraToolsVisible by mutableStateOf(false)
    private val shaderWarmup get() = (application as UnspektrawesomeApplication).shaderWarmup
    private val previewController by lazy {
        RawVulkanPreviewController(this, spektraRepository, cameraLocation::snapshot,
            rendererFactory = shaderWarmup::takeRenderer)
    }

    private var cameras by mutableStateOf<List<CameraDescriptor>>(emptyList())
    private var cameraPreferences by mutableStateOf(CameraPreferences())
    private var selectedLensId by mutableStateOf<String?>(null)
    private var deviceOrientationDegrees by mutableIntStateOf(0)
    private var status by mutableStateOf("Checking Vulkan")
    private var vulkanDiagnostics by mutableStateOf("Vulkan probe has not run")
    private val orientationListener by lazy {
        object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val resolved = CameraOrientation.resolve(orientation, deviceOrientationDegrees)
                if (resolved == deviceOrientationDegrees) return
                deviceOrientationDegrees = resolved
                landscapeSideDegrees = CameraOrientation.landscapeSide(resolved, landscapeSideDegrees)
                previewController.updateDeviceOrientation(resolved)
            }
        }
    }
    private var landscapeSideDegrees by mutableIntStateOf(CameraOrientation.DEFAULT_LANDSCAPE_DEGREES)
    // Orientation held by the system rotation lock, or null while system auto-rotate is on.
    private var systemLockedOrientationDegrees by mutableStateOf<Int?>(null)
    private val systemRotationObserver =
        object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = readSystemRotation()
        }
    // Text and buttons follow the chosen setting. Capture always follows deviceOrientationDegrees.
    private val uiOrientationDegrees: Int
        get() = CameraOrientation.uiOrientation(cameraPreferences.uiOrientation, deviceOrientationDegrees,
            landscapeSideDegrees, systemLockedOrientationDegrees)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        hideStatusBar()
        cameraPreferences = loadCameraPreferences()
        ControlBindings.initialize(this, cameraPreferences)
        com.unspektrawesome.controls.DirectUsbControls.initialize(this)
        discoveryScope.launch {
            com.unspektrawesome.controls.DirectUsbControls.enabled.collect { usbControls.refresh() }
        }
        selectedLensId = cameraPreferences.defaultLensId
        onboardingCompleted = guideProgress.load("introduction", ONBOARDING_REVISION, ONBOARDING_PAGES) == ONBOARDING_PAGES
        storageAccessReviewed = !com.unspektrawesome.ui.onboarding.StorageAccessSetup.needed(this)
        val savedGuide = guideProgress.load("camera", CAMERA_GUIDE_REVISION,
            CAMERA_GUIDE_STEPS + CAMERA_PROCESSING_GUIDE_STEPS)
        cameraGuideStep = savedGuide.coerceAtMost(CAMERA_GUIDE_STEPS)
        processingGuideStep = (savedGuide - CAMERA_GUIDE_STEPS).coerceAtLeast(0)
        videoGuideStep = guideProgress.load("video", VIDEO_GUIDE_REVISION, VIDEO_GUIDE_STEPS)
        modeSwipeHintPending = guideProgress.load("mode_swipe", com.unspektrawesome.ui.camera.MODE_SWIPE_HINT_REVISION, 1) == 0
        cameraPermissionGranted = hasCameraPermission()
        savedInstanceState?.takeIf { it.containsKey("video_mode") }?.let {
            previewController.restoreVideoMode(it.getBoolean("video_mode"))
            if (it.getBoolean("burst_mode") && android.os.Environment.isExternalStorageManager()) previewController.setBurstMode(true)
            if (it.getBoolean("panorama_mode")) previewController.setPanoramaMode(true, it.getBoolean("grid_mode"))
        }
        if (savedInstanceState == null) intent.requestedVideoMode()?.let(previewController::restoreVideoMode)
        if (lockScreenCamera) {
            ContextCompat.registerReceiver(this, screenOffReceiver,
                android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            // First-run setup and permission prompts cannot appear above the lock screen.
            if (!AppLanguage.state.value.choiceMade || !storageAccessReviewed || !onboardingCompleted || !cameraPermissionGranted)
                whenUnlocked(onCancelled = ::finish) {}
        }
        setContent {
            val language by AppLanguage.state.collectAsState()
            if (!language.choiceMade) {
                UnspektrawesomeTheme(CameraOrientation.controlRotation(uiOrientationDegrees)) {
                    LanguagePicker(firstLaunch = true) {
                        if (onboardingCompleted && cameraPermissionGranted) startCamera()
                    }
                }
                return@setContent
            }
            if (!storageAccessReviewed) {
                UnspektrawesomeTheme(CameraOrientation.controlRotation(uiOrientationDegrees)) {
                    com.unspektrawesome.ui.onboarding.StorageAccessOnboarding {
                        storageAccessReviewed = true
                        if (onboardingCompleted && cameraPermissionGranted) startCamera()
                    }
                }
                return@setContent
            }
            if (discoveryVisible) {
                UnspektrawesomeTheme(CameraOrientation.controlRotation(uiOrientationDegrees)) {
                    AutoLensDiscoveryDialog(discoveryProgress, discoveryReport, discoveryMessage,
                        onStart = ::startAutoLensDiscovery,
                        onResume = ::resumeAutoLensDiscovery,
                        onCancel = { discoveryProgress = discoveryProgress.copy(phase = "Cancelling and releasing camera…"); discoveryJob?.cancel() },
                        onClose = { discoveryVisible = false; if (!onboardingCompleted) completeOnboarding() },
                        onManualProfiles = {
                            discoveryVisible = false
                            if (!onboardingCompleted) completeOnboarding()
                            manualLensProfilesRequest++
                        })
                }
            }
            if (!onboardingCompleted || !cameraPermissionGranted) {
                UnspektrawesomeTheme(CameraOrientation.controlRotation(uiOrientationDegrees)) {
                    OnboardingFlow(
                        activity = this@MainActivity,
                        showIntroduction = !onboardingCompleted,
                        onComplete = ::completeOnboarding,
                        onAutoDiscover = ::resumeAutoLensDiscovery,
                    )
                }
                return@setContent
            }
            val shaderPreparation by shaderWarmup.state.collectAsState()
            LaunchedEffect(shaderPreparation.blocksCamera) {
                if (!shaderPreparation.blocksCamera && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startCamera()
            }
            if (shaderPreparation.blocksCamera) {
                UnspektrawesomeTheme(CameraOrientation.controlRotation(uiOrientationDegrees)) {
                    Box(Modifier.fillMaxSize().background(CameraColors.Background))
                    if (shaderPreparation.showDialog) ShaderPreparationDialog(shaderPreparation, shaderWarmup::hideProgress)
                }
                return@setContent
            }
            val previewStatus by previewController.status.collectAsState()
            val lensSessionRecovery by previewController.lensSessionRecovery.collectAsState()
            val captureStatus by previewController.captureStatus.collectAsState()
            val videoState by previewController.videoState.collectAsState()
            LaunchedEffect(videoState.enabled) { showLensProfiles(videoState.enabled) }
            val burstState by previewController.burstStatus.collectAsState()
            val panoramaState by previewController.panoramaState.collectAsState()
            val panoramaExport by previewController.panoramaExportRequest.collectAsState()
            val cameraLifecycle by lifecycle.currentStateFlow.collectAsState()
            LaunchedEffect(panoramaExport, cameraLifecycle) {
                panoramaExport?.takeIf { cameraLifecycle.isAtLeast(Lifecycle.State.RESUMED) }?.let { id ->
                    previewController.consumePanoramaExportRequest()
                    whenUnlocked { startActivity(GalleryActivity.intent(this@MainActivity, processing = true, panoramaExport = id)) }
                }
            }
            var startVideoAfterPermission by remember { mutableStateOf(false) }
            var lockVideoAfterPermission by remember { mutableStateOf(false) }
            val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) startVideoAfterPermission = true
                else android.widget.Toast.makeText(this@MainActivity,
                    AppLanguage.text("Microphone permission is required for video"), android.widget.Toast.LENGTH_LONG).show()
            }
            LaunchedEffect(startVideoAfterPermission, previewStatus.captureReady, videoState.enabled) {
                if (!videoState.enabled) startVideoAfterPermission = false
                if (startVideoAfterPermission && previewStatus.captureReady && videoState.enabled) {
                    startVideoAfterPermission = false
                    previewController.toggleVideoRecording(lockControls = lockVideoAfterPermission)
                }
            }
            fun startVideo(lockControls: Boolean) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) previewController.toggleVideoRecording(lockControls)
                else {
                    lockVideoAfterPermission = lockControls
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            val tapMeteringStatus by previewController.tapMeteringStatus.collectAsState()
            val focusLoupeStatus by previewController.focusLoupeStatus.collectAsState()
            val exposureMeteringStatus by previewController.exposureMeteringStatus.collectAsState()
            var showCaptureQueue by remember { mutableStateOf(false) }
            val captureFeedback = rememberCaptureFeedbackState(captureStatus)
            val cameraControls by previewController.cameraControls.collectAsState()
            val rawImportCapabilities by previewController.rawImportCapabilities.collectAsState()
            val selectedCamera = cameras.firstOrNull { it.route.routeId == selectedLensId }
            val effectivePreferences = LensProfiles.effective(cameraPreferences)
            val viewfinderAspectRatio = remember(
                selectedCamera,
                cameraPreferences,
                rawImportCapabilities,
                videoState.enabled,
            ) {
                selectedCamera?.let { camera ->
                    runCatching {
                        val plan = RawPreviewPlanSelector.select(
                            camera,
                            effectivePreferences,
                            importCapabilities = rawImportCapabilities,
                        )
                        val transform = CaptureGeometry.transform(
                            camera.sensorMetadata.orientationDegrees,
                            LOCKED_VIEWFINDER_ROTATION_DEGREES,
                            camera.facing,
                        )
                        val oriented = FrameGeometrySnapshot.createForPhotoCrop(camera.sensorMetadata,
                            effectivePreferences.rawLayoutOverride().output(plan.acquisitionOutput), transform,
                            if (videoState.enabled) effectivePreferences.videoCrop else effectivePreferences.photoCrop,
                            rawEdgeCrop = cameraPreferences.rawEdgeCrop()).outputSize
                        oriented.width.toFloat() / oriented.height
                    }.getOrNull()
                } ?: DEFAULT_VIEWFINDER_ASPECT_RATIO
            }
            val lensToneKey = cameraPreferences.lensToneKey(selectedLensId)
            LaunchedEffect(lensToneKey) { lensCorrections.selectLens(lensToneKey, spektraRepository) }
            val previewView = remember { VulkanRawPreviewView(this@MainActivity) }
            LaunchedEffect(selectedCamera, cameraPreferences, hasCameraPermission()) {
                previewController.configure(
                    selectedCamera.takeIf { hasCameraPermission() },
                    effectivePreferences,
                )
            }
            DisposableEffect(previewView) {
                previewView.bind(previewController, this@MainActivity)
                onDispose { previewView.unbind() }
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) discoverCameras() else status = "Camera permission denied"
            }
            val viewfinderSpektraEnabled by previewController.viewfinderSpektraEnabled.collectAsState()
            val locationStatus by cameraLocation.status.collectAsState()
            var locationAsked by rememberSaveable { mutableStateOf(false) }
            val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                saveCameraPreferences(cameraPreferences.copy(saveLocation = cameraLocation.hasPermission()))
            }
            LaunchedEffect(cameraPreferences.saveLocation, cameraToolsVisible) {
                cameraLocation.configure(cameraPreferences.saveLocation,
                    cameraToolsVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            UnspektrawesomeTheme(CameraOrientation.controlRotation(uiOrientationDegrees)) {
                val updates = rememberUpdateController()
                // Pause the camera while an update is offered, but never interrupt a capture or recording.
                val pauseForUpdate = updates.visible && updates.state.release != null && !captureStatus.acquiringFrame &&
                    !videoState.busy && !panoramaState.busy && !burstState.busy
                LaunchedEffect(pauseForUpdate) { previewController.setUpdateSuspended(pauseForUpdate) }
                AppUpdateHost(updates, autoCheck = !lockScreenCamera && if (videoState.enabled) videoGuideStep >= VIDEO_GUIDE_STEPS else
                    cameraGuideStep >= CAMERA_GUIDE_STEPS && processingGuideStep >= CAMERA_PROCESSING_GUIDE_STEPS)
                if (showCaptureQueue) {
                    PhotoQueueDialog(
                        outstandingShots = captureStatus.outstandingShots,
                        errorMessage = captureStatus.message.takeIf { captureStatus.phase == RawCapturePhase.ERROR },
                        onRetry = if (captureStatus.retryableShots > 0) ({
                            previewController.retryQueuedCaptures()
                            showCaptureQueue = false
                        }) else null,
                        onDismiss = {
                            showCaptureQueue = false
                            if (captureStatus.outstandingShots == 0) captureFeedback.dismiss()
                        },
                        onCancelWaiting = {
                            previewController.cancelQueuedCaptures()
                            showCaptureQueue = false
                        },
                    )
                }

                CameraScreen(
                    onAutoLensDiscovery = { discoveryVisible = true },
                    manualLensProfilesRequest = manualLensProfilesRequest,
                    locationStatus = locationStatus,
                    onLocationEnabledChanged = { enabled ->
                        if (!enabled || cameraLocation.hasPermission()) {
                            saveCameraPreferences(cameraPreferences.copy(saveLocation = enabled))
                        } else if (locationAsked && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                            whenUnlocked { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null))) }
                        } else {
                            locationAsked = true
                            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    },
                    onCameraVisibilityChanged = { cameraToolsVisible = it; if (!it) { previewController.stopVideoRecording(); previewController.stopPanorama() } },
                    onCropPreviewChanged = previewController::previewCrop,
                    cropOutputSize = { crop -> selectedCamera?.let { camera -> runCatching {
                        val plan = RawPreviewPlanSelector.select(camera, effectivePreferences, importCapabilities = rawImportCapabilities)
                        val source = FrameGeometrySnapshot.createForPhotoCrop(camera.sensorMetadata,
                            effectivePreferences.rawLayoutOverride().output(plan.acquisitionOutput),
                            CaptureGeometry.transform(camera.sensorMetadata.orientationDegrees,
                            CameraOrientation.displayRotation(deviceOrientationDegrees), camera.facing), crop,
                            rawEdgeCrop = cameraPreferences.rawEdgeCrop())
                        if (videoState.enabled) {
                            val retained = if (videoState.liveStabilization)
                                VideoFraming.stabilized(source, videoState.stabilizationSettings.cropRatio) else source
                            VideoEncoding.supported(VideoFraming.retainedSize(retained), videoState.downscale,
                                videoState.frameRate)?.let { VideoFraming.recording(retained, it.size, videoState.downscale).outputSize }
                        } else source.outputSize
                    }.getOrNull() } },
                    lensSessionRecovery = lensSessionRecovery,
                    onCancelLensSessionRetries = previewController::cancelLensSessionRetries,
                    onRetryLensSession = previewController::retryLensSession,
                    onCheckForUpdates = if (BuildConfig.UPDATES_ENABLED && !lockScreenCamera) ({ updates.check(manual = true) }) else null,
                    updateDialogVisible = updates.visible,
                    guideStep = cameraGuideStep,
                    modeSwipeHintPending = modeSwipeHintPending,
                    onModeSwipeHintFinished = {
                        modeSwipeHintPending = false
                        guideProgress.save("mode_swipe", com.unspektrawesome.ui.camera.MODE_SWIPE_HINT_REVISION, 1)
                    },
                    videoGuideStep = videoGuideStep,
                    onVideoGuideStepChanged = { step ->
                        videoGuideStep = step.coerceIn(0, VIDEO_GUIDE_STEPS)
                        guideProgress.save("video", VIDEO_GUIDE_REVISION, videoGuideStep)
                    },
                    onGuideStepChanged = { step ->
                        if (step == 0) {
                            modeSwipeHintPending = true
                            guideProgress.save("mode_swipe", com.unspektrawesome.ui.camera.MODE_SWIPE_HINT_REVISION, 0)
                        }
                        cameraGuideStep = step.coerceIn(0, CAMERA_GUIDE_STEPS)
                        guideProgress.save("camera", CAMERA_GUIDE_REVISION,
                            if (cameraGuideStep < CAMERA_GUIDE_STEPS) cameraGuideStep else CAMERA_GUIDE_STEPS + processingGuideStep)
                    },
                    processingGuideStep = processingGuideStep,
                    onProcessingGuideStepChanged = { step ->
                        processingGuideStep = step.coerceIn(0, CAMERA_PROCESSING_GUIDE_STEPS)
                        guideProgress.save("camera", CAMERA_GUIDE_REVISION,
                            if (cameraGuideStep < CAMERA_GUIDE_STEPS) cameraGuideStep else CAMERA_GUIDE_STEPS + processingGuideStep)
                    },
                    cameras = cameras,
                    selectedLensId = selectedLensId,
                    preferences = cameraPreferences,
                    status = when {
                        captureStatus.phase != RawCapturePhase.IDLE -> captureStatus.message
                        selectedCamera != null && previewStatus.phase != RawPreviewPhase.IDLE ->
                            previewStatus.message
                        else -> status
                    },
                    vulkanDiagnostics = "$vulkanDiagnostics\n${previewStatus.rendererDiagnostic}",
                    rawImportCapabilities = rawImportCapabilities,
                    spektraRepository = spektraRepository,
                    spektraAeFeedbackId = spektraAeFeedbackId,
                    viewfinderSpektraEnabled = viewfinderSpektraEnabled,
                    onViewfinderSpektraChanged = previewController::setViewfinderSpektraEnabled,
                    hasCameraPermission = hasCameraPermission(),
                    onRequestCameraPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onLensSelected = { target ->
                        val busy = videoState.busy || burstState.busy || panoramaState.busy
                        InternalLogRecorder.event("LensSelection", "request=$target busy=$busy acquiring=${captureStatus.acquiringFrame}")
                        if (!busy) selectLens(target)
                    },
                    onPreferencesChanged = ::saveCameraPreferences,
                    videoState = videoState,
                    burstState = burstState,
                    panoramaState = panoramaState,
                    onPanoramaModeChanged = previewController::setPanoramaMode,
                    onPanoramaExportLater = previewController::setPanoramaExportLater,
                    onPanoramaProjection = previewController::setPanoramaProjection,
                    onPanoramaAuto = previewController::setPanoramaAuto,
                    onGridAimRadiusChanged = previewController::setGridAimRadius,
                    onStopPanorama = previewController::stopPanorama,
                    onBurstModeChanged = previewController::setBurstMode,
                    onVideoModeChanged = previewController::setVideoMode,
                    onVideoDownscaleChanged = previewController::setVideoDownscale,
                    onVideoBitrateChanged = previewController::setVideoBitrate,
                    onVideoFrameRateChanged = previewController::setVideoFrameRate,
                    onCaptureGyroChanged = previewController::setCaptureGyro,
                    onLiveStabilizationChanged = previewController::setLiveStabilization,
                    onVideoChromaDenoiseChanged = previewController::setVideoChromaDenoise,
                    onVideoChromaDenoiseStrengthChanged = previewController::setVideoChromaDenoiseStrength,
                    onVideoHotPixelRemovalChanged = previewController::setVideoHotPixelRemoval,
                    onStabilizationSettingsChanged = previewController::setStabilizationSettings,
                    onVideoOisChanged = previewController::setVideoOis,
                    onCheckLensFrameRates = previewController::checkLensFrameRates,
                    onStopVideo = previewController::stopVideoRecording,
                    onShutter = {
                        if (panoramaState.enabled) previewController.togglePanorama()
                        else if (videoState.enabled) {
                            if (videoState.busy) previewController.toggleVideoRecording() else startVideo(lockControls = false)
                        } else previewController.captureStill()
                    },
                    onLockedRecording = { if (videoState.enabled && !videoState.busy) startVideo(lockControls = true) },
                    onHardwareControlsChanged = { hardwareControls = it },
                    onOpenGallery = { if (!burstState.busy && !panoramaState.busy) whenUnlocked {
                        if (panoramaState.enabled && panoramaState.frames >= 2) startActivity(GalleryActivity.intent(this@MainActivity, processing = true))
                        else openGallery(captureStatus.lastUri)
                    } },
                    onOpenGalleryOverview = { if (!burstState.busy && !panoramaState.busy) whenUnlocked { startActivity(GalleryActivity.intent(this@MainActivity)) } },
                    onOpenGalleryGuide = {
                        whenUnlocked {
                            guideProgress.save("gallery", GALLERY_GUIDE_REVISION, 0)
                            startActivity(GalleryActivity.intent(this@MainActivity))
                        }
                    },
                    lastPhotoUri = captureStatus.lastUri,
                    viewfinderAspectRatio = previewStatus.renderedAspectRatio ?: viewfinderAspectRatio,
                    viewfinderSurfaceAspectRatio = previewStatus.surfaceAspectRatio ?: viewfinderAspectRatio,
                    cropTransitionRevision = previewStatus.cropTransitionRevision,
                    onCropTransitionFinished = previewController::completeCropTransition,
                    readPreviewPerformance = previewController::samplePreviewPerformance,
                    readHistogram = { _, cropAspect -> previewController.sampleHistogram(cropAspect) },
                    controlRotationDegrees = CameraOrientation.controlRotation(uiOrientationDegrees),
                    deviceRotationDegrees = CameraOrientation.controlRotation(deviceOrientationDegrees),
                    lensSwitching = previewStatus.lensSwitching || (hasCameraPermission() && previewStatus.phase in setOf(
                        RawPreviewPhase.IDLE, RawPreviewPhase.PAUSED, RawPreviewPhase.WAITING_FOR_SURFACE, RawPreviewPhase.OPENING)),
                    startupLoading = hasCameraPermission() && !previewStatus.viewfinderReady &&
                        previewStatus.phase !in setOf(RawPreviewPhase.ERROR, RawPreviewPhase.CLOSED),
                    captureBusy = captureStatus.shutterBusy || (burstState.enabled && !burstState.ready) || !previewStatus.captureReady ||
                        previewStatus.captureRouteId != selectedLensId,
                    captureInProgress = captureStatus.acquiringFrame,
                    shutterFeedbackId = captureStatus.shutterFeedbackId,
                    captureExposureTimeNs = captureStatus.exposureTimeNs,
                    captureExposureStartedAtMs = captureStatus.exposureStartedAtMs,
                    onManageCaptureQueue = if (captureFeedback.visible?.action == "Gallery")
                        ({ whenUnlocked { startActivity(com.unspektrawesome.gallery.GalleryActivity.intent(this@MainActivity, processing = true)) } })
                        else if (captureStatus.outstandingShots > 0 || captureStatus.phase == RawCapturePhase.ERROR)
                        ({ showCaptureQueue = true }) else null,
                    captureFeedback = captureFeedback.visible,
                    tapMeteringStatus = tapMeteringStatus,
                    exposureMeteringStatus = exposureMeteringStatus,
                    onExposurePointChanged = { x, y -> previewController.meterExposure(x, y) },
                    onFocusPointChanged = { x, y -> previewController.focusAt(x, y) },
                    onClearExposurePoint = { previewController.clearExposurePoint() },
                    onViewfinderTap = { x, y -> previewController.tapToMeter(x, y) },
                    cameraControls = cameraControls,
                    onExposureCompensationChanged = previewController::setExposureCompensation,
                    onAutoExposure = previewController::setAutoExposure,
                    onIsoPriority = previewController::toggleIsoPriority,
                    onShutterPriority = previewController::toggleShutterPriority,
                    onIsoChanged = previewController::setIso,
                    onExposureTimeChanged = previewController::setExposureTimeNs,
                    onApplyLongShutter = previewController::applyLongShutter,
                    onCancelLongShutter = previewController::cancelLongShutter,
                    onAutoFocus = previewController::setAutoFocus,
                    onRestartPassiveFocus = previewController::restartPassiveFocus,
                    onFocusDistanceChanged = previewController::setFocusDistance,
                    onFocusPeakingVisibilityChanged = previewController::setFocusPeakingVisible,
                    focusLoupeStatus = focusLoupeStatus,
                    onFocusLoupeChanged = previewController::setFocusLoupe,
                    onAutoWhiteBalance = previewController::setAutoWhiteBalance,
                    onWhiteBalanceTemperatureChanged =
                        previewController::setWhiteBalanceTemperatureKelvin,
                    viewfinder = {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.requestedVideoMode()?.let(previewController::setVideoMode)
    }

    override fun onResume() {
        super.onResume()
        com.unspektrawesome.gallery.GalleryProcessing.cameraVisible = true
        if (com.unspektrawesome.gallery.GalleryProcessing.state.value.running) com.unspektrawesome.gallery.GalleryProcessing.pause()
        cameraPreferences = loadCameraPreferences()
        hideStatusBar()
        readSystemRotation()
        for (setting in listOf(android.provider.Settings.System.ACCELEROMETER_ROTATION, android.provider.Settings.System.USER_ROTATION))
            contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(setting), false, systemRotationObserver)
        cameraLocation.configure(cameraPreferences.saveLocation, cameraToolsVisible)
        cameraPermissionGranted = hasCameraPermission()
        usbControls.start()
        if (AppLanguage.state.value.choiceMade && onboardingCompleted && cameraPermissionGranted) {
            startCamera()
            if (cameraStarted) {
                if (!lockedSession()) previewController.refreshLastPhoto()
                previewController.updateDeviceOrientation(deviceOrientationDegrees)
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) controlInputs.reset()
        if (hasFocus) hideStatusBar()
    }

    // This public Activity hook inherits the restriction on AndroidX's internal base class.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controlInputs.onKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        controlInputs.onMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun onPause() {
        discoveryJob?.cancel()
        cameraLocation.configure(cameraPreferences.saveLocation, false)
        usbControls.stop()
        controlInputs.reset()
        com.unspektrawesome.gallery.GalleryProcessing.cameraVisible = false
        spektraAeFeedbackId = 0
        if (cameraStarted) orientationListener.disable()
        contentResolver.unregisterContentObserver(systemRotationObserver)
        super.onPause()
    }

    private fun readSystemRotation() {
        val autoRotate = android.provider.Settings.System.getInt(contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        systemLockedOrientationDegrees = if (autoRotate) null else CameraOrientation.fromDisplayRotation(
            android.provider.Settings.System.getInt(contentResolver,
                android.provider.Settings.System.USER_ROTATION, android.view.Surface.ROTATION_0))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (cameraStarted) {
            val video = previewController.videoState.value
            outState.putBoolean("video_mode", video.enabled)
            outState.putBoolean("burst_mode", previewController.burstStatus.value.enabled)
            outState.putBoolean("panorama_mode", previewController.panoramaState.value.enabled)
            outState.putBoolean("grid_mode", previewController.panoramaState.value.grid != null)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        cameraDiscoveryNeeded = true
        super.onStop()
    }

    override fun onDestroy() {
        if (lockScreenCamera) unregisterReceiver(screenOffReceiver)
        discoveryScope.cancel()
        cameraLocation.close()
        if (cameraStarted) {
            previewController.close()
            spektraPersistence.close()
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun lockedSession(): Boolean =
        lockScreenCamera && getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    /** Runs [action] right away, or after the user unlocks when the camera is shown above the lock screen. */
    private fun whenUnlocked(onCancelled: () -> Unit = {}, action: () -> Unit) {
        if (!lockedSession()) { action(); return }
        getSystemService(android.app.KeyguardManager::class.java).requestDismissKeyguard(this,
            object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
                override fun onDismissCancelled() = onCancelled()
                override fun onDismissError() = onCancelled()
            })
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!storageAccessReviewed) return
        if (!AppLanguage.state.value.choiceMade) return
        if (shaderWarmup.state.value.blocksCamera) return
        if (discoveryProgress.running) return
        if (cameraStarted) {
            if (cameraDiscoveryNeeded) discoverCameras()
            return
        }
        cameraStarted = true
        probeVulkan()
        discoverCameras(selectDefaultProfile = true)
        // Above the lock screen only photos taken in this session are shown.
        if (!lockedSession()) previewController.refreshLastPhoto()
        previewController.updateDeviceOrientation(deviceOrientationDegrees)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun probeVulkan() {
        worker.execute {
            val result = runCatching { VulkanBridge.probe() }
                .getOrElse { "Unsupported: ${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread {
                vulkanDiagnostics = result
                status = if (result.contains("unsupported", ignoreCase = true)) {
                    "Vulkan unavailable"
                } else {
                    "Vulkan ready, waiting for RAW buffer"
                }
            }
        }
    }

    private fun discoverCameras(selectDefaultProfile: Boolean = false) {
        if (discoveryProgress.running) return
        cameraDiscoveryNeeded = false
        status = "Discovering RAW cameras"
        worker.execute {
            val result = runCatching { cameraDiscovery.discover() }
            val environment = result.getOrNull()?.let { LensDiscoveryStore.environment(this, it) }
            val cachedReport = environment?.let(discoveryStore::load)
            runOnUiThread {
                if (isDestroyed || discoveryProgress.running) return@runOnUiThread
                result.onSuccess { discovered ->
                    // The lens list follows the capture mode the camera is in right now.
                    var currentPreferences = cameraPreferences.copy(lensProfileMode = captureLensProfileMode())
                    if (cachedReport != null && currentPreferences.lensProfiles.isEmpty()) {
                        currentPreferences = AutoLensProfiles.apply(cachedReport, currentPreferences, discovered)
                    }
                    val initializedPreferences = if (selectDefaultProfile) {
                        LensProfiles.startup(discovered, currentPreferences)
                    } else {
                        LensProfiles.initialize(discovered, currentPreferences)
                    }
                    discoveryReport = cachedReport
                    if (initializedPreferences != cameraPreferences) {
                        cameraPreferences = initializedPreferences
                        cameraPreferencesStore.save(initializedPreferences)
                    }
                    cameras = discovered
                    val enabled = enabledCameras(discovered, initializedPreferences)
                    val preferredId = initializedPreferences.lensProfiles.firstOrNull {
                        it.id == initializedPreferences.activeLensProfileId
                    }?.routeId ?: selectedLensId.takeUnless { selectDefaultProfile }
                        ?: initializedPreferences.defaultLensId
                    val preferred = preferredId?.let { id ->
                        enabled.firstOrNull { it.route.routeId == id }
                    }
                    selectedLensId = (preferred ?: enabled.firstOrNull())?.route?.routeId
                    status = when {
                        discovered.isEmpty() -> "No cameras discovered"
                        enabled.none(CameraDescriptor::isUsable) -> "No usable RAW camera"
                        else -> "RAW cameras ready"
                    }
                }.onFailure {
                    status = "Camera discovery failed: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    private fun captureLensProfileMode(): LensProfileMode =
        if (previewController.videoState.value.enabled) LensProfileMode.VIDEO else LensProfileMode.PHOTO

    /**
     * Loads the saved settings. Stabilization saved beside the lens profiles by earlier versions moves into the
     * profiles, and lens profiles saved before photo and video had separate lists are copied to video.
     */
    private fun loadCameraPreferences(): CameraPreferences {
        val saved = cameraPreferencesStore.load()
        val stabilization = com.unspektrawesome.settings.LensStabilizationStore(this)
        val (filled, copies) = LensProfiles.fillModes(stabilization.moveToProfiles(saved))
        if (copies.isNotEmpty())
            lensTones.copyProfiles(copies, com.unspektrawesome.spektra.LensTone.of(spektraRepository.state.value.params))
        if (filled != saved) cameraPreferencesStore.save(filled)
        stabilization.markMoved()
        return filled
    }

    /** Offers the video lens profiles in video mode and the photo lens profiles otherwise. */
    private fun showLensProfiles(video: Boolean) {
        val mode = if (video) LensProfileMode.VIDEO else LensProfileMode.PHOTO
        if (cameraPreferences.lensProfileMode == mode) return
        if (cameras.isEmpty()) {
            // Lenses are not known yet. Camera discovery selects the profile for this mode.
            cameraPreferences = cameraPreferences.copy(lensProfileMode = mode)
            cameraPreferencesStore.save(cameraPreferences)
        } else saveCameraPreferences(LensProfiles.switchMode(cameraPreferences, mode, cameras))
    }

    private fun selectLens(routeId: String) {
        if (cameraPreferences.lensProfiles.isNotEmpty()) {
            val visible = LensProfiles.visible(cameraPreferences, cameras)
            val profile = visible.firstOrNull { it.id == routeId }
                ?: visible.firstOrNull { it.routeId == routeId }
                ?: return
            saveCameraPreferences(cameraPreferences.copy(activeLensProfileId = profile.id))
            return
        }
        selectedLensId = routeId
        saveCameraPreferences(cameraPreferences)
    }

    private fun saveCameraPreferences(value: CameraPreferences) {
        if (value.lensProfiles.isNotEmpty()) {
            val normalized = LensProfiles.normalize(value, cameras)
            selectedLensId = normalized.lensProfiles.firstOrNull { it.id == normalized.activeLensProfileId }?.routeId
            cameraPreferences = normalized
            cameraPreferencesStore.save(normalized)
            return
        }
        val enabled = enabledCameras(cameras, value)
        if (selectedLensId !in enabled.map { it.route.routeId }) {
            selectedLensId = enabled.firstOrNull()?.route?.routeId
        }
        val normalized = value.copy(defaultLensId = value.defaultLensId?.takeIf { id ->
            enabled.any { it.route.routeId == id }
        } ?: enabled.firstOrNull()?.route?.routeId)
        cameraPreferences = normalized
        cameraPreferencesStore.save(normalized)
    }

    private fun openGallery(lastPhoto: Uri?) {
        val target = if (lastPhoto != null && (!cameraPreferences.processPhotosLater ||
                com.unspektrawesome.gallery.GalleryMedia.isVideoUri(lastPhoto))) {
            PhotoViewerActivity.intent(this, lastPhoto)
        } else GalleryActivity.intent(this)
        startActivity(target)
    }

    private fun completeOnboarding() {
        if (!hasCameraPermission()) return
        onboardingPreferences.edit().putBoolean("completed", true).apply()
        guideProgress.save("introduction", ONBOARDING_REVISION, ONBOARDING_PAGES)
        cameraPermissionGranted = true
        onboardingCompleted = true
        startCamera()
    }

    private fun startAutoLensDiscovery() = runAutoLensDiscovery(resume = false)

    private fun resumeAutoLensDiscovery() = runAutoLensDiscovery(resume = true)

    private fun runAutoLensDiscovery(resume: Boolean) {
        if (discoveryProgress.running || !hasCameraPermission()) return
        discoveryVisible = true
        discoveryMessage = null
        discoveryProgress = LensDiscoveryProgress(running = true)
        val customConfigurations = cameraPreferences.lensProfiles.filterNot { it.managedByDiscovery }
            .mapNotNull { it.verification?.configuration?.key }.toSet()
        discoveryJob = discoveryScope.launch {
            var completed: LensDiscoveryReport? = null
            var discovered: List<CameraDescriptor>? = null
            try {
                if (cameraStarted && !previewController.suspendForLensDiscovery()) {
                    discoveryMessage = "The viewfinder camera has not finished closing. Try discovery again."
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val inventory = cameraDiscovery.discover()
                    discovered = inventory
                    val environment = LensDiscoveryStore.environment(this@MainActivity, inventory)
                    val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
                    val candidates = LensDiscoveryCandidates.enumerate(manager, inventory)
                    val runner = LensDiscoveryRunner(Camera2LensProbe(manager, inventory))
                    var savedAt = 0L
                    completed = runner.run(environment, candidates, progress = { value ->
                        runOnUiThread { if (!isDestroyed) discoveryProgress = value.copy(running = true) }
                    }, checkpoint = { partial ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - savedAt >= 5_000) {
                            discoveryStore.save(partial)
                            savedAt = now
                        }
                    }, previous = if (resume) discoveryStore.load(environment) else null,
                        requiredConfigurations = customConfigurations)
                    discoveryStore.save(requireNotNull(completed))
                }
            } catch (_: CancellationException) {
                if (completed == null) discoveryMessage = "Discovery cancelled. Your lens profiles are unchanged."
            } catch (error: Exception) {
                discoveryMessage = "Discovery could not finish: ${error.message ?: error.javaClass.simpleName}. You can retry or use manual Lens Profiles."
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (!isDestroyed) {
                        completed?.let { report ->
                            discoveryReport = report
                            cameras = discovered.orEmpty()
                            discoveryProgress = discoveryProgress.copy(phase = "Saving verified profiles…")
                            val preferences = cameraPreferences
                            val mapped = withContext(Dispatchers.Default) { AutoLensProfiles.apply(report, preferences, cameras) }
                            withContext(Dispatchers.IO) { cameraPreferencesStore.save(mapped) }
                            cameraPreferences = mapped
                            selectedLensId = mapped.lensProfiles.firstOrNull { it.id == mapped.activeLensProfileId }?.routeId
                        }
                        discoveryProgress = discoveryProgress.copy(running = false, current = null)
                        if (cameraStarted) {
                            previewController.configure(cameras.firstOrNull { it.route.routeId == selectedLensId }, LensProfiles.effective(cameraPreferences))
                            previewController.resumeAfterLensDiscovery()
                        }
                    }
                }
            }
        }
    }

    private fun enabledCameras(
        values: List<CameraDescriptor>,
        preferences: CameraPreferences,
    ): List<CameraDescriptor> = LensButtonMapping.resolvedCameras(values, preferences)

    private companion object {
        const val DEFAULT_VIEWFINDER_ASPECT_RATIO = 3f / 4f
        const val LOCKED_VIEWFINDER_ROTATION_DEGREES = 0
    }
}
