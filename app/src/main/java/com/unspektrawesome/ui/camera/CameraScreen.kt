package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.CameraOrientation
import com.unspektrawesome.camera.LensFacing
import com.unspektrawesome.camera.LensRole
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.preview.RawImportCapability
import com.unspektrawesome.preview.RawImportCapabilityState
import com.unspektrawesome.preview.RawImportStreamKey
import com.unspektrawesome.preview.CameraControlState
import com.unspektrawesome.preview.TapMeteringPhase
import com.unspektrawesome.preview.TapMeteringStatus
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.PreviewSlider
import com.unspektrawesome.settings.aeBalance
import com.unspektrawesome.settings.lensCaptureSettings
import com.unspektrawesome.camera.exposure.AeMode
import com.unspektrawesome.settings.withAeBalance
import com.unspektrawesome.settings.withAeEngine
import com.unspektrawesome.settings.LensButtonMapping
import com.unspektrawesome.settings.RawPreviewQuality
import com.unspektrawesome.settings.RawStreamSelection
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraState
import com.unspektrawesome.spektra.SpektraStateRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val Accent = CameraColors.Accent
private val Glass = CameraColors.Glass
private val Muted = CameraColors.Muted

@Composable
fun CameraScreen(
    cameras: List<CameraDescriptor>,
    selectedLensId: String?,
    preferences: CameraPreferences,
    status: String,
    vulkanDiagnostics: String,
    rawImportCapabilities: Map<RawImportStreamKey, RawImportCapability>,
    spektraRepository: SpektraStateRepository,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onLensSelected: (String) -> Unit,
    onPreferencesChanged: (CameraPreferences) -> Unit,
    onShutter: () -> Unit,
    onLockedRecording: () -> Unit = {},
    onOpenGallery: () -> Unit,
    onOpenGalleryOverview: () -> Unit = onOpenGallery,
    viewfinderAspectRatio: Float,
    controlRotationDegrees: Float,
    lensSwitching: Boolean,
    // How the phone is physically held. Level and panorama guides follow it rather than the controls.
    deviceRotationDegrees: Float = controlRotationDegrees,
    startupLoading: Boolean,
    captureBusy: Boolean,
    captureInProgress: Boolean = captureBusy,
    captureFeedback: CaptureFeedback?,
    shutterFeedbackId: Long = 0,
    onManageCaptureQueue: (() -> Unit)? = null,
    lastPhotoUri: android.net.Uri?,
    tapMeteringStatus: TapMeteringStatus,
    exposureMeteringStatus: TapMeteringStatus = TapMeteringStatus(),
    onExposurePointChanged: (Float, Float) -> Unit = { _, _ -> },
    onFocusPointChanged: (Float, Float) -> Unit = { _, _ -> },
    onClearExposurePoint: () -> Unit = {},
    onViewfinderTap: (Float, Float) -> Unit,
    cameraControls: CameraControlState,
    onExposureCompensationChanged: (Int) -> Boolean,
    onAutoExposure: () -> Boolean,
    onIsoChanged: (Int) -> Boolean,
    onExposureTimeChanged: (Long) -> Boolean,
    onAutoFocus: () -> Boolean,
    onRestartPassiveFocus: () -> Boolean = { false },
    onFocusDistanceChanged: (Float) -> Boolean,
    onFocusPeakingVisibilityChanged: (Boolean) -> Unit = {},
    focusLoupeStatus: com.unspektrawesome.preview.FocusLoupeStatus = com.unspektrawesome.preview.FocusLoupeStatus(),
    onFocusLoupeChanged: (com.unspektrawesome.preview.FocusLoupeRequest?) -> Unit = {},
    onAutoWhiteBalance: () -> Boolean,
    onWhiteBalanceTemperatureChanged: (Int) -> Boolean,
    viewfinderSpektraEnabled: Boolean = true,
    onViewfinderSpektraChanged: (Boolean) -> Unit = {},
    modeSwipeHintPending: Boolean = false,
    onModeSwipeHintFinished: () -> Unit = {},
    guideStep: Int = CAMERA_GUIDE_STEPS,
    onGuideStepChanged: (Int) -> Unit = {},
    processingGuideStep: Int = CAMERA_PROCESSING_GUIDE_STEPS,
    onProcessingGuideStepChanged: (Int) -> Unit = {},
    videoGuideStep: Int = VIDEO_GUIDE_STEPS,
    onVideoGuideStepChanged: (Int) -> Unit = {},
    onOpenGalleryGuide: () -> Unit = onOpenGallery,
    onCheckForUpdates: (() -> Unit)? = null,
    updateDialogVisible: Boolean = false,
    lensSessionRecovery: com.unspektrawesome.preview.LensSessionRecoveryStatus? = null,
    onCancelLensSessionRetries: () -> Unit = {},
    onRetryLensSession: () -> Unit = {},
    onHardwareShutterChanged: ((() -> Unit)?) -> Unit = {},
    /** Receives the handler that runs camera actions from buttons, or null while the camera controls are hidden. */
    onHardwareControlsChanged: (((com.unspektrawesome.controls.ControlAction, Int) -> Unit)?) -> Unit = {},
    locationStatus: String = "Off",
    onLocationEnabledChanged: (Boolean) -> Unit = { onPreferencesChanged(preferences.copy(saveLocation = it)) },
    onCameraVisibilityChanged: (Boolean) -> Unit = {},
    onCropPreviewChanged: (com.unspektrawesome.capture.PhotoCrop?) -> Unit = {},
    viewfinderSurfaceAspectRatio: Float = viewfinderAspectRatio,
    cropTransitionRevision: Long = 0L,
    onCropTransitionFinished: (Long) -> Unit = {},
    onAutoLensDiscovery: (() -> Unit)? = null,
    manualLensProfilesRequest: Int = 0,
    cropOutputSize: (com.unspektrawesome.capture.PhotoCrop) -> com.unspektrawesome.camera.Size2d? = { null },
    readPreviewPerformance: () -> com.unspektrawesome.preview.PreviewPerformance = { com.unspektrawesome.preview.PreviewPerformance() },
    readHistogram: suspend (Float, Float) -> com.unspektrawesome.preview.RgbHistogram? = { _, _ -> null },
    onIsoPriority: () -> Boolean = { false },
    onShutterPriority: () -> Boolean = { false },
    onApplyLongShutter: () -> Boolean = { false },
    onCancelLongShutter: () -> Boolean = { false },
    captureExposureTimeNs: Long = 0L,
    captureExposureStartedAtMs: Long? = null,
    spektraAeFeedbackId: Int = 0,
    videoState: com.unspektrawesome.video.VideoRecordingState = com.unspektrawesome.video.VideoRecordingState(),
    burstState: com.unspektrawesome.burst.BurstStatus = com.unspektrawesome.burst.BurstStatus(),
    panoramaState: com.unspektrawesome.panorama.PanoramaState = com.unspektrawesome.panorama.PanoramaState(),
    onPanoramaModeChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onPanoramaExportLater: (Boolean) -> Unit = {},
    onPanoramaProjection: (com.unspektrawesome.panorama.PanoramaProjection) -> Unit = {},
    onPanoramaAuto: () -> Unit = {},
    onGridAimRadiusChanged: (Double) -> Unit = {},
    onStopPanorama: () -> Unit = {},
    onBurstModeChanged: (Boolean) -> Unit = {},
    onVideoModeChanged: (Boolean) -> Unit = {},
    onVideoDownscaleChanged: (com.unspektrawesome.video.VideoDownscale) -> Unit = {},
    onVideoBitrateChanged: (Int) -> Unit = {},
    onVideoFrameRateChanged: (Int?) -> Unit = {},
    onCaptureGyroChanged: (Boolean) -> Unit = {},
    onLiveStabilizationChanged: (Boolean) -> Unit = {},
    onVideoChromaDenoiseChanged: (Boolean) -> Unit = {},
    onVideoChromaDenoiseStrengthChanged: (Float) -> Unit = {},
    onVideoHotPixelRemovalChanged: (Boolean) -> Unit = {},
    onStabilizationSettingsChanged: (com.unspektrawesome.video.LiveStabilizationSettings) -> Unit = {},
    onVideoOisChanged: (Boolean) -> Unit = {},
    onCheckLensFrameRates: LensFrameRateCheck? = null,
    onStopVideo: () -> Unit = {},
    viewfinder: @Composable BoxScope.() -> Unit,
) {
    val cameraView = androidx.compose.ui.platform.LocalView.current
    val cameraHaptics = rememberCameraHaptics(enabled = !videoState.enabled && !videoState.busy)
    val recordingInProgress = videoState.phase == com.unspektrawesome.video.VideoPhase.STARTING ||
        videoState.phase == com.unspektrawesome.video.VideoPhase.RECORDING
    val shutterWarningFrameRate = if (videoState.enabled)
        videoState.frameRateOverride ?: preferences.lensCaptureSettings().frameRate ?: 30 else 30
    val shutterWarningThresholdNs = 1_000_000_000L / shutterWarningFrameRate
    var pendingLensSelection by rememberSaveable { mutableStateOf<String?>(null) }
    val selectLens by rememberUpdatedState(onLensSelected)
    val lensChangeBusy = captureInProgress || burstState.busy || videoState.busy || panoramaState.busy
    LaunchedEffect(pendingLensSelection, lensChangeBusy) {
        if (com.unspektrawesome.BuildConfig.DEBUG) com.unspektrawesome.diagnostics.InternalLogRecorder.event("LensInput",
            "pending=$pendingLensSelection busy=$lensChangeBusy acquiring=$captureInProgress burst=${burstState.busy} video=${videoState.busy} panorama=${panoramaState.busy}")
        if (!lensChangeBusy) pendingLensSelection?.let { target ->
            pendingLensSelection = null
            selectLens(target)
        }
    }
    DisposableEffect(videoState.busy, panoramaState.busy, cameraView) {
        val previous = cameraView.keepScreenOn
        if (videoState.busy || panoramaState.busy) cameraView.keepScreenOn = true
        onDispose { cameraView.keepScreenOn = previous }
    }
    BackHandler(videoState.busy) { onStopVideo() }
    BackHandler(panoramaState.recording) { onStopPanorama() }
    val cameraGuide = CameraGuideStep.entries.getOrNull(guideStep).takeUnless { videoState.enabled || panoramaState.enabled || modeSwipeHintPending }
    val requestBurstStorage = com.unspektrawesome.burst.rememberBurstStorageAccess()
    val videoGuide = VideoGuideStep.entries.getOrNull(videoGuideStep).takeIf { videoState.enabled && !modeSwipeHintPending }
    var spektraVisible by rememberSaveable {
        mutableStateOf(cameraGuide in setOf(CameraGuideStep.CHOOSE_LOOK, CameraGuideStep.REORDER_CARDS, CameraGuideStep.CLOSE_CONTROLS))
    }
    var recordingSliders by remember(recordingInProgress) { mutableStateOf(emptySet<PreviewSlider>()) }
    var previewSliders by remember(preferences.expandedPreviewSliders) {
        mutableStateOf(preferences.expandedPreviewSliders)
    }
    val expandedSliders = if (recordingInProgress) recordingSliders else previewSliders
    fun setPreviewSliderExpanded(slider: PreviewSlider, expanded: Boolean) {
        val current = if (recordingInProgress) recordingSliders else previewSliders
        val selection = if (expanded) current + slider else current - slider
        if (recordingInProgress) recordingSliders = selection
        else {
            previewSliders = selection
            onPreferencesChanged(preferences.copy(expandedPreviewSliders = selection))
        }
    }
    var advancedSpektraVisible by rememberSaveable { mutableStateOf(false) }
    var videoFramingVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(videoState.enabled, videoState.busy) {
        if (!videoState.enabled || videoState.busy) videoFramingVisible = false
    }
    var customSelectionRequest by rememberSaveable { mutableStateOf(0) }
    var processingSelectionRequest by rememberSaveable { mutableStateOf(0) }
    var customSectionVisible by rememberSaveable { mutableStateOf(false) }
    val guideTargets = remember { mutableStateMapOf<String, Rect>() }
    fun openProfileEditor() {
        videoFramingVisible = false
        advancedSpektraVisible = true
        if (cameraGuide == CameraGuideStep.CREATE_PROFILE) onGuideStepChanged(CameraGuideStep.SAVE_PROFILE.ordinal)
        if (!viewfinderSpektraEnabled) onViewfinderSpektraChanged(true)
    }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(manualLensProfilesRequest) { if (manualLensProfilesRequest > 0) settingsVisible = true }
    var compositionToolsOpen by remember { mutableStateOf(false) }
    var previewCrop by remember { mutableStateOf<com.unspektrawesome.capture.PhotoCrop?>(null) }
    CameraBrightness(preferences.maxBrightness, !settingsVisible && !updateDialogVisible)
    val currentVisibilityChanged by rememberUpdatedState(onCameraVisibilityChanged)
    DisposableEffect(settingsVisible) {
        currentVisibilityChanged(!settingsVisible)
        onDispose { currentVisibilityChanged(false) }
    }
    val processingGuideActive = !modeSwipeHintPending && !videoState.enabled && guideStep >= CAMERA_GUIDE_STEPS && processingGuideStep < CAMERA_PROCESSING_GUIDE_STEPS
    fun openGalleryGuide() {
        onProcessingGuideStepChanged(CAMERA_PROCESSING_GUIDE_STEPS)
        onOpenGalleryGuide()
    }
    fun closeSettings() {
        settingsVisible = false
        if (processingGuideActive && processingGuideStep in 1..2) onProcessingGuideStepChanged(3)
    }
    var recoveryDismissed by remember(lensSessionRecovery, selectedLensId, preferences.activeLensProfileId) {
        mutableStateOf(false)
    }
    val showLensFailureCard = lensSessionRecovery?.busy == false && recoveryDismissed
    val visibleFeedback = if (showLensFailureCard) CaptureFeedback(CaptureFeedbackKind.Error,
        "Camera ${lensSessionRecovery.lensName} failed to start", "Details") else captureFeedback
    val bannerFeedback = visibleFeedback?.takeIf { it.requiresAttention }
    if (lensSessionRecovery != null && !settingsVisible && !recoveryDismissed) {
        LensSessionRecoveryDialog(lensSessionRecovery,
            onCancel = onCancelLensSessionRetries,
            onRetry = onRetryLensSession,
            onClose = { recoveryDismissed = true },
            onLensSettings = {
                onCancelLensSessionRetries()
                settingsVisible = true
            })
    }
    var activeCameraParameter by rememberSaveable { mutableStateOf<CameraParameter?>(null) }
    val shutterFlash = remember { Animatable(0f) }
    var lastShutterFeedbackId by remember { mutableStateOf(shutterFeedbackId) }
    LaunchedEffect(shutterFeedbackId) {
        if (shutterFeedbackId != lastShutterFeedbackId) {
            lastShutterFeedbackId = shutterFeedbackId
            shutterFlash.snapTo(1f)
            shutterFlash.animateTo(0f, tween(160, easing = LinearEasing))
        }
    }
    val spektra by spektraRepository.state.collectAsState()
    var showSpektraAeFeedback by remember { mutableStateOf(false) }
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(spektraAeFeedbackId) {
        showSpektraAeFeedback = spektraAeFeedbackId != 0
        if (showSpektraAeFeedback) {
            delay(accessibilityManager?.calculateRecommendedTimeoutMillis(2_000L, containsText = true) ?: 2_000L)
            showSpektraAeFeedback = false
        }
    }
    val guideLook = remember(guideStep, videoState.enabled) { spektra.params }
    LaunchedEffect(guideStep, spektra.params) {
        if (cameraGuide == CameraGuideStep.CHOOSE_LOOK && spektra.params != guideLook)
            onGuideStepChanged(CameraGuideStep.REORDER_CARDS.ordinal)
    }
    fun setSpektraVisible(visible: Boolean) {
        if (visible && panoramaState.busy) return
        if (visible) videoFramingVisible = false
        if (spektraVisible == visible) return
        spektraVisible = visible
        if (visible && cameraGuide == CameraGuideStep.OPEN_CONTROLS) onGuideStepChanged(CameraGuideStep.CHOOSE_LOOK.ordinal)
        if (!visible && cameraGuide in setOf(CameraGuideStep.CHOOSE_LOOK, CameraGuideStep.REORDER_CARDS, CameraGuideStep.CLOSE_CONTROLS))
            onGuideStepChanged(CameraGuideStep.PREVIEW.ordinal)
        if (!visible) advancedSpektraVisible = false
    }
    val enabledCameras = remember(cameras, preferences) {
        LensButtonMapping.resolvedCameras(cameras, preferences)
    }
    var actionsVisible by rememberSaveable { mutableStateOf(false) }
    fun toggleVideoFraming() {
        if (videoState.busy) return
        setSpektraVisible(false)
        advancedSpektraVisible = false
        activeCameraParameter = null
        actionsVisible = false
        videoFramingVisible = !videoFramingVisible
    }
    var resetLookConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val peakingStrength = preferences.peakingStrengthFor(cameraControls.autoFocus)
    val focusPeakingActive = peakingStrength != null &&
        (cameraControls.autoFocus || activeCameraParameter == CameraParameter.FOCUS) && !spektraVisible && !advancedSpektraVisible &&
        !settingsVisible && !actionsVisible && !compositionToolsOpen
    val currentPeakingVisibilityChanged by rememberUpdatedState(onFocusPeakingVisibilityChanged)
    LaunchedEffect(focusPeakingActive) { currentPeakingVisibilityChanged(focusPeakingActive) }
    DisposableEffect(Unit) { onDispose { currentPeakingVisibilityChanged(false) } }
    val currentFocusLoupeChanged by rememberUpdatedState(onFocusLoupeChanged)
    DisposableEffect(Unit) { onDispose { currentFocusLoupeChanged(null) } }
    var viewfinderSurfaceBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var histogramBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    LaunchedEffect(preferences.histogramEnabled) { if (!preferences.histogramEnabled) histogramBounds = null }
    var loupeBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val loupeSelected = preferences.focusAssist == com.unspektrawesome.settings.FocusAssist.LOUPE
    val loupeControlsFree = !spektraVisible && !advancedSpektraVisible && !settingsVisible && !actionsVisible && !compositionToolsOpen
    // Opening FOCUS shows the loupe before the first adjustment, as manual focusing starts there.
    val loupeManual = loupeSelected && preferences.loupeManualFocus && cameraControls.manualFocusSupported &&
        activeCameraParameter == CameraParameter.FOCUS && loupeControlsFree
    val loupeOnTap = loupeSelected && preferences.loupeOnTap && cameraControls.autoFocus && loupeControlsFree
    // While focusing manually with the loupe, a tap chooses the point to magnify instead of metering.
    var loupeTargetRequest by remember { mutableStateOf<LoupeTargetRequest?>(null) }
    if (resetLookConfirmationVisible) {
        ResetLookDialog(
            rotation = controlRotationDegrees,
            onDismiss = { resetLookConfirmationVisible = false },
            onConfirm = {
                spektraRepository.reset()
                resetLookConfirmationVisible = false
            },
        )
    }
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val hardwareShutterReady by rememberUpdatedState(when {
        panoramaState.recording -> true
        panoramaState.finishing || panoramaState.enabled && !cameraControls.manualExposureSupported || panoramaState.grid?.available == false -> false
        videoState.phase == com.unspektrawesome.video.VideoPhase.RECORDING -> true
        else -> !videoState.busy && (!videoState.enabled || videoState.outputSupported) &&
            !captureBusy && !startupLoading && !lensSwitching && cameraControls.pendingExposureTimeNs == null
    })
    val hardwareShutterActive = hasCameraPermission && !settingsVisible && !actionsVisible && !resetLookConfirmationVisible && !compositionToolsOpen && !videoFramingVisible &&
        lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
        (lensSessionRecovery == null || recoveryDismissed)
    val timedShutter = rememberTimedShutter(hardwareShutterActive, hardwareShutterReady,
        if (videoState.enabled || panoramaState.enabled) 0 else preferences.shutterTimerSeconds) {
        if (videoState.enabled && !videoState.busy || panoramaState.enabled) {
            spektraVisible = false
            advancedSpektraVisible = false
            activeCameraParameter = null
        }
        onShutter()
    }
    val captureMode = when {
        panoramaState.enabled -> if (panoramaState.grid != null) CaptureMode.GRID else CaptureMode.PANORAMA
        videoState.enabled -> CaptureMode.VIDEO
        burstState.enabled -> CaptureMode.BURST
        else -> CaptureMode.PHOTO
    }
    val modeSwipeEnabled = hardwareShutterActive && !videoState.busy && !burstState.busy && !panoramaState.busy &&
        !captureInProgress &&
        cameraControls.pendingExposureTimeNs == null && timedShutter.remaining == 0
    val modeHintAllowed = modeSwipeHintPending && modeSwipeEnabled && preferences.modeSwipeEnabled &&
        cameraGuide == null && videoGuide == null && !processingGuideActive &&
        !spektraVisible && !advancedSpektraVisible && activeCameraParameter == null
    var modeHintStarted by remember(modeHintAllowed) { mutableStateOf(false) }
    LaunchedEffect(modeHintAllowed, startupLoading, lensSwitching, captureBusy) {
        if (modeHintAllowed && !startupLoading && !lensSwitching && !captureBusy) modeHintStarted = true
    }
    val modeHintVisible = modeHintAllowed && modeHintStarted
    val changeCaptureMode: (CaptureMode) -> Unit = { mode ->
        if (modeSwipeEnabled && mode != captureMode) {
            val switch = {
                cameraHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                setSpektraVisible(false)
                activeCameraParameter = null
                when (mode) {
                    CaptureMode.PHOTO -> when { panoramaState.enabled -> onPanoramaModeChanged(false, false); videoState.enabled -> onVideoModeChanged(false); else -> onBurstModeChanged(false) }
                    CaptureMode.BURST -> onBurstModeChanged(true)
                    CaptureMode.VIDEO -> onVideoModeChanged(true)
                    CaptureMode.PANORAMA -> onPanoramaModeChanged(true, false)
                    CaptureMode.GRID -> onPanoramaModeChanged(true, true)
                }
            }
            if (mode == CaptureMode.BURST) requestBurstStorage(switch) else switch()
        }
    }
    val currentShutter by rememberUpdatedState(timedShutter.onPress)
    DisposableEffect(hardwareShutterActive, onHardwareShutterChanged) {
        onHardwareShutterChanged(if (hardwareShutterActive) ({
            if (hardwareShutterReady) currentShutter()
        }) else null)
        onDispose { onHardwareShutterChanged(null) }
    }
    // Values just requested by a button, so quick repeated steps continue from them before the camera reports back.
    val requestedControlValues = remember { mutableMapOf<com.unspektrawesome.controls.ControlAction, Pair<Long, Float>>() }
    fun controlBase(action: com.unspektrawesome.controls.ControlAction, current: Float): Float =
        requestedControlValues[action]?.takeIf { android.os.SystemClock.uptimeMillis() - it.first < 700 }?.second ?: current
    fun requestedControl(action: com.unspektrawesome.controls.ControlAction, value: Float) {
        requestedControlValues[action] = android.os.SystemClock.uptimeMillis() to value
    }
    // A photo or recording requested from another capture mode starts once that mode is ready.
    var pendingControlCapture by remember { mutableStateOf<com.unspektrawesome.controls.ControlAction?>(null) }
    // The mode to return to after a recording started with a record button, and whether that recording began.
    var modeAfterControlRecording by remember { mutableStateOf<CaptureMode?>(null) }
    var controlRecordingStarted by remember { mutableStateOf(false) }
    LaunchedEffect(pendingControlCapture) {
        if (pendingControlCapture != null) {
            delay(8_000)
            pendingControlCapture = null
            if (!controlRecordingStarted) modeAfterControlRecording = null
        }
    }
    LaunchedEffect(pendingControlCapture, captureMode, hardwareShutterReady, hardwareShutterActive, videoState.busy) {
        val target = pendingControlCapture ?: return@LaunchedEffect
        val modeReady = if (target == com.unspektrawesome.controls.ControlAction.RECORD_VIDEO) captureMode == CaptureMode.VIDEO
            else captureMode == CaptureMode.PHOTO
        if (modeReady && hardwareShutterActive && hardwareShutterReady && !videoState.busy) {
            pendingControlCapture = null
            currentShutter()
        }
    }
    LaunchedEffect(recordingInProgress) {
        if (recordingInProgress && modeAfterControlRecording != null) controlRecordingStarted = true
    }
    LaunchedEffect(controlRecordingStarted, videoState.busy, modeSwipeEnabled) {
        val mode = modeAfterControlRecording ?: return@LaunchedEffect
        if (controlRecordingStarted && !videoState.busy && modeSwipeEnabled) {
            modeAfterControlRecording = null
            controlRecordingStarted = false
            changeCaptureMode(mode)
        }
    }
    // True while a half press is held, which keeps the focus loupe open.
    var halfPressHeld by remember { mutableStateOf(false) }
    LaunchedEffect(hardwareShutterActive) { if (!hardwareShutterActive) halfPressHeld = false }
    var spektraAeControlFeedback by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(spektraAeControlFeedback) {
        if (spektraAeControlFeedback == 0) return@LaunchedEffect
        showSpektraAeFeedback = true
        delay(accessibilityManager?.calculateRecommendedTimeoutMillis(2_000L, containsText = true) ?: 2_000L)
        showSpektraAeFeedback = false
    }
    val controlHandler: (com.unspektrawesome.controls.ControlAction, Int) -> Unit = handler@{ action, step ->
        when (action) {
            com.unspektrawesome.controls.ControlAction.SHUTTER -> {
                halfPressHeld = false
                if (hardwareShutterReady) currentShutter()
            }
            com.unspektrawesome.controls.ControlAction.TAKE_PHOTO -> {
                halfPressHeld = false
                when {
                    videoState.busy || panoramaState.busy || burstState.busy -> Unit
                    captureMode == CaptureMode.PHOTO || captureMode == CaptureMode.BURST -> if (hardwareShutterReady) currentShutter()
                    else -> {
                        changeCaptureMode(CaptureMode.PHOTO)
                        pendingControlCapture = action
                    }
                }
            }
            com.unspektrawesome.controls.ControlAction.RECORD_VIDEO -> when {
                captureMode == CaptureMode.VIDEO -> if (hardwareShutterReady) currentShutter()
                panoramaState.busy || burstState.busy || captureInProgress -> Unit
                else -> {
                    modeAfterControlRecording = captureMode
                    controlRecordingStarted = false
                    changeCaptureMode(CaptureMode.VIDEO)
                    pendingControlCapture = action
                }
            }
            com.unspektrawesome.controls.ControlAction.HALF_PRESS -> {
                halfPressHeld = step != 0
                if (step != 0 && cameraControls.autoFocus) {
                    // Refocus on the chosen focus point, or the center when none is shown.
                    val point = tapMeteringStatus.takeIf {
                        it.generation != 0L && it.phase != com.unspektrawesome.preview.TapMeteringPhase.IDLE && !it.hidden
                    }
                    onFocusPointChanged(point?.normalizedX ?: .5f, point?.normalizedY ?: .5f)
                }
            }
            com.unspektrawesome.controls.ControlAction.LENS -> {
                if (lensChangeBusy) return@handler
                val front = enabledCameras.firstOrNull { it.route.routeId == selectedLensId }?.facing == LensFacing.FRONT
                val lenses = enabledCameras.filter { (it.facing == LensFacing.FRONT) == front }
                lensStepTarget(lenses, preferences, selectedLensId, step)?.let { (id, _) -> pendingLensSelection = id }
            }
            com.unspektrawesome.controls.ControlAction.CAPTURE_MODE ->
                if (modeSwipeEnabled) changeCaptureMode(captureMode.step(step.coerceIn(-1, 1)))
            com.unspektrawesome.controls.ControlAction.EV -> {
                val minimum = cameraControls.exposureCompensationMin
                val maximum = cameraControls.exposureCompensationMax
                if (!cameraControls.autoExposure || minimum >= maximum) return@handler
                val value = (controlBase(action, cameraControls.exposureCompensation.toFloat()).roundToInt() + step)
                    .coerceIn(minimum, maximum)
                if (onExposureCompensationChanged(value)) requestedControl(action, value.toFloat())
            }
            com.unspektrawesome.controls.ControlAction.ISO -> {
                if (!cameraControls.manualExposureSupported) return@handler
                val values = isoStops(cameraControls.isoMin, cameraControls.isoMax)
                val current = closestIndex(values, controlBase(action, cameraControls.iso.toFloat()).roundToInt())
                val value = values[(current + step).coerceIn(0, values.lastIndex)]
                if (onIsoChanged(value)) requestedControl(action, value.toFloat())
            }
            com.unspektrawesome.controls.ControlAction.SHUTTER_SPEED -> {
                if (!cameraControls.manualExposureSupported) return@handler
                val values = shutterStops(cameraControls.exposureTimeMinNs, cameraControls.exposureTimeMaxNs)
                val base = controlBase(action, cameraControls.selectedExposureTimeNs.toFloat()).toLong()
                val current = closestIndex(values.map(ShutterStop::nanoseconds), base)
                // Positive steps make the shutter faster, like a camera's command dial.
                val value = values[(current - step).coerceIn(0, values.lastIndex)]
                if (onExposureTimeChanged(value.nanoseconds)) requestedControl(action, value.nanoseconds.toFloat())
            }
            com.unspektrawesome.controls.ControlAction.AE_BALANCE -> {
                if (!cameraControls.customAe || cameraControls.exposureMode != AeMode.AUTO) return@handler
                val value = com.unspektrawesome.camera.exposure.AeBalance.normalize(
                    ((controlBase(action, preferences.aeBalance()) * 10).roundToInt() + step) / 10f)
                onPreferencesChanged(preferences.withAeBalance(value))
                requestedControl(action, value)
            }
            com.unspektrawesome.controls.ControlAction.SHADOWS -> spektraRepository.updateParams {
                it.copy(inputShadows = (((it.inputShadows * 20).roundToInt() + step) / 20f).coerceIn(0f, 1f))
            }
            com.unspektrawesome.controls.ControlAction.HIGHLIGHTS -> spektraRepository.updateParams {
                it.copy(inputHighlights = (((it.inputHighlights * 20).roundToInt() + step) / 20f).coerceIn(-1f, 1f))
            }
            com.unspektrawesome.controls.ControlAction.FOCUS -> {
                if (!cameraControls.manualFocusSupported) return@handler
                val nearest = cameraControls.minimumFocusDistance
                val position = (controlBase(action, FocusScale.position(cameraControls.focusDistance, nearest)) + step * .01f)
                    .coerceIn(0f, 1f)
                if (onFocusDistanceChanged(FocusScale.distance(position, nearest))) requestedControl(action, position)
            }
            com.unspektrawesome.controls.ControlAction.WHITE_BALANCE -> {
                if (!cameraControls.manualWhiteBalanceSupported) return@handler
                val base = controlBase(action, (cameraControls.whiteBalanceTemperatureKelvin ?: 5000).toFloat()).roundToInt()
                val value = ((base / 100 + step) * 100).coerceIn(WHITE_BALANCE_STOPS.first(), WHITE_BALANCE_STOPS.last())
                if (onWhiteBalanceTemperatureChanged(value)) requestedControl(action, value.toFloat())
            }
            com.unspektrawesome.controls.ControlAction.AUTO_EXPOSURE -> {
                requestedControlValues.clear()
                onAutoExposure()
            }
            com.unspektrawesome.controls.ControlAction.AUTO_FOCUS -> {
                requestedControlValues.remove(com.unspektrawesome.controls.ControlAction.FOCUS)
                onAutoFocus()
            }
            com.unspektrawesome.controls.ControlAction.SPEKTRA_AE -> {
                // Digital auto exposure has no effect while Unspektra AE runs the camera exposure.
                if (cameraControls.customAe || panoramaState.enabled) return@handler
                spektraRepository.updateParams { com.unspektrawesome.camera.VolumeExposure.cycle(it, step.coerceIn(-1, 1)) }
                spektraAeControlFeedback++
            }
            com.unspektrawesome.controls.ControlAction.GALLERY -> onOpenGallery()
        }
    }
    val currentControlHandler by rememberUpdatedState(controlHandler)
    DisposableEffect(hardwareShutterActive, onHardwareControlsChanged) {
        onHardwareControlsChanged(if (hardwareShutterActive) ({ action, step -> currentControlHandler(action, step) }) else null)
        onDispose { onHardwareControlsChanged(null) }
    }
    val restartFocus by rememberUpdatedState(onRestartPassiveFocus)
    val focusRefreshActive = hardwareShutterActive && hardwareShutterReady && cameraControls.autoFocus && timedShutter.remaining == 0
    LaunchedEffect(focusRefreshActive, preferences.passiveFocusIntervalSeconds) {
        val seconds = preferences.passiveFocusIntervalSeconds
        if (focusRefreshActive && seconds > 0) while (true) {
            delay(seconds * 1000L)
            restartFocus()
        }
    }
    LaunchedEffect(guideStep, processingGuideStep, videoState.enabled, modeSwipeHintPending) {
        if (processingGuideActive) {
            spektraVisible = false
            advancedSpektraVisible = false
            activeCameraParameter = null
            actionsVisible = false
            settingsVisible = processingGuideStep in 1..2
        }
    }
    LaunchedEffect(guideStep, videoState.enabled, modeSwipeHintPending) {
        when (cameraGuide) {
            CameraGuideStep.CHOOSE_LOOK, CameraGuideStep.REORDER_CARDS, CameraGuideStep.CLOSE_CONTROLS -> {
                spektraVisible = true
                advancedSpektraVisible = false
                actionsVisible = false
            }
            CameraGuideStep.EXPOSURE, CameraGuideStep.AUTO_EXPOSURE, CameraGuideStep.AE_BALANCE,
            CameraGuideStep.SHADOWS, CameraGuideStep.HIGHLIGHTS, CameraGuideStep.MANUAL_CONTROLS,
            CameraGuideStep.FOCUS_METERING, CameraGuideStep.TOP_TOOLS, CameraGuideStep.SETTINGS -> {
                spektraVisible = false
                advancedSpektraVisible = false
                activeCameraParameter = null
                actionsVisible = false
            }
            CameraGuideStep.CREATE_PROFILE -> {
                processingSelectionRequest = 0
                spektraVisible = true
                advancedSpektraVisible = false
                actionsVisible = false
                customSelectionRequest++
            }
            CameraGuideStep.SAVE_PROFILE -> {
                spektraRepository.clearProfileSelection()
                openProfileEditor()
            }
            else -> Unit
        }
    }
    LaunchedEffect(videoGuide) {
        if (videoGuide != VideoGuideStep.PROCESSING)
            processingSelectionRequest = 0
        if (videoGuide != null && !videoState.busy) {
            advancedSpektraVisible = false
            activeCameraParameter = null
            videoFramingVisible = false
            actionsVisible = videoGuide in setOf(VideoGuideStep.CAPTURE, VideoGuideStep.STABILIZATION)
            spektraVisible = videoGuide == VideoGuideStep.PROCESSING
            if (spektraVisible) processingSelectionRequest++
        }
    }
    LaunchedEffect(panoramaState.busy) {
        if (panoramaState.busy) {
            spektraVisible = false
            advancedSpektraVisible = false
            activeCameraParameter = null
            actionsVisible = false
            videoFramingVisible = false
        }
    }
    val landscape = abs(controlRotationDegrees) == 90f
    BackHandler(spektraVisible || activeCameraParameter != null || actionsVisible || settingsVisible || advancedSpektraVisible || videoFramingVisible) {
        when {
            settingsVisible -> closeSettings()
            actionsVisible -> actionsVisible = false
            videoFramingVisible -> videoFramingVisible = false
            advancedSpektraVisible -> {
                advancedSpektraVisible = false
                if (cameraGuide == CameraGuideStep.SAVE_PROFILE) onGuideStepChanged(CameraGuideStep.SETTINGS.ordinal)
            }
            activeCameraParameter != null -> activeCameraParameter = null
            else -> setSpektraVisible(false)
        }
    }
    val filmSwipeThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    val changeFilmPanel by rememberUpdatedState<(Boolean) -> Unit> { towardFilmPanel ->
        if (towardFilmPanel) {
            if (actionsVisible) actionsVisible = false else {
                activeCameraParameter = null
                setSpektraVisible(true)
            }
        } else {
            when {
                videoFramingVisible -> videoFramingVisible = false
                spektraVisible || advancedSpektraVisible -> setSpektraVisible(false)
                activeCameraParameter != null -> activeCameraParameter = null
                else -> actionsVisible = true
            }
        }
    }
    val filmSwipeEnabled = !settingsVisible && !compositionToolsOpen && !resetLookConfirmationVisible && !panoramaState.busy
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .traceLensInput("screen") { "pending=$pendingLensSelection busy=$lensChangeBusy" }
        .pointerInput(controlRotationDegrees, filmSwipeEnabled, filmSwipeThreshold) {
            if (!filmSwipeEnabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                var handled = false
                var blocked = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.count { it.pressed } > 1) blocked = true
                    if (change.pressed && change.isConsumed && !handled) blocked = true
                    if (change.pressed && !blocked && !handled) {
                        val delta = change.position - down.position
                        filmPanelSwipe(delta.x, delta.y, filmSwipeThreshold)?.let { visible ->
                            handled = true
                            change.consume()
                            changeFilmPanel(visible)
                        }
                    } else if (handled) change.consume()
                } while (event.changes.any { it.pressed })
            }
        }) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        CompositionLocalProvider(LocalCameraRotation provides controlRotationDegrees,
            LocalCameraWindowRotation provides controlRotationDegrees,
            LocalHapticFeedback provides cameraHaptics,
            LocalCameraControlBackground provides CameraColors.Background.copy(alpha = preferences.controlOpacity.coerceIn(0f, 1f))) {
            CameraPageTransition(settingsVisible, Modifier.fillMaxSize(), order = { if (it) 1 else 0 }) { showSettings ->
            if (showSettings) {
                BackHandler { closeSettings() }
                OrientedLookPanel(abs(controlRotationDegrees) == 90f, controlRotationDegrees, Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalCameraRotation provides 0f) {
                CameraSettingsScreen(cameras, preferences, rawImportCapabilities, onPreferencesChanged,
                    burstState = burstState,
                    onAutoLensDiscovery = onAutoLensDiscovery, onCheckLensFrameRates = onCheckLensFrameRates,
                    locationStatus = locationStatus, onLocationEnabledChanged = onLocationEnabledChanged,
                    onDismiss = ::closeSettings, onCheckForUpdates = onCheckForUpdates,
                    processingGuideStep = if (processingGuideActive) processingGuideStep else CAMERA_PROCESSING_GUIDE_STEPS,
                    onProcessingGuideStepChanged = onProcessingGuideStepChanged,
                    onShowProcessingGuide = {
                        onVideoModeChanged(false)
                        onGuideStepChanged(CAMERA_GUIDE_STEPS)
                        onProcessingGuideStepChanged(0)
                        settingsVisible = false
                    }, onShowGuide = {
                        onVideoModeChanged(false)
                        advancedSpektraVisible = false
                        activeCameraParameter = null
                        actionsVisible = false
                        spektraVisible = false
                        onGuideStepChanged(0)
                        onProcessingGuideStepChanged(0)
                        settingsVisible = false
                    }, onShowVideoGuide = {
                        advancedSpektraVisible = false
                        activeCameraParameter = null
                        actionsVisible = false
                        spektraVisible = false
                        videoFramingVisible = false
                        onVideoGuideStepChanged(0)
                        onVideoModeChanged(true)
                        settingsVisible = false
                    })
                }
                }
            } else {
            var guideOrigin by remember { mutableStateOf(Offset.Zero) }
            val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
            val cameraInsets = if (advancedSpektraVisible) systemInsets else
                WindowInsets.safeDrawing.union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(cameraInsets)
                .onGloballyPositioned { guideOrigin = it.positionInRoot() }.testTag("Camera content area")) {
                val chromeWidth = maxWidth
                val chromeHeight = maxHeight
                val animatedAspectRatio by animateFloatAsState(viewfinderAspectRatio,
                    tween(300, easing = FastOutSlowInEasing), label = "Photo crop framing")
                val cropFramingSettled = animatedAspectRatio == viewfinderAspectRatio
                LaunchedEffect(cropFramingSettled, cropTransitionRevision) {
                    if (cropFramingSettled) onCropTransitionFinished(cropTransitionRevision)
                }
                val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
                val quickMenuControlsAlpha by animateFloatAsState(if (actionsVisible) 0f else 1f,
                    tween(180), label = "Quick menu control visibility")
                val filmExpansion by animateFloatAsState(
                    if (spektraVisible || advancedSpektraVisible) 1f else 0f,
                    tween(220, easing = LinearOutSlowInEasing), label = "Film panel expansion")
                var lastOpenPageWasEditor by remember { mutableStateOf(advancedSpektraVisible) }
                SideEffect {
                    if (spektraVisible || advancedSpektraVisible) {
                        lastOpenPageWasEditor = advancedSpektraVisible
                    }
                }
                val showEditorPage = if (spektraVisible || advancedSpektraVisible) advancedSpektraVisible else lastOpenPageWasEditor
                val sliderExpansion by animateFloatAsState(
                    if (activeCameraParameter != null) 1f else 0f,
                    tween(260, easing = FastOutSlowInEasing), label = "Manual slider expansion")
                var lastManualParameter by remember { mutableStateOf(CameraParameter.ISO) }
                LaunchedEffect(activeCameraParameter) { activeCameraParameter?.let { lastManualParameter = it } }
                val manualCardHeight = manualCameraCardHeight(fontScale)
                val manualRows = if (maxWidth - 24.dp < 300.dp * fontScale) 2 else 1
                val manualBaseHeight = 6.dp + manualCardHeight * manualRows + 6.dp * (manualRows - 1)
                var manualRulerHeight by remember { mutableStateOf(74.dp) }
                val manualSliderHeight = manualRulerHeight + 16.dp
                val manualHeight = manualBaseHeight + manualSliderHeight * sliderExpansion
                val density = LocalDensity.current
                var videoOutputHeight by remember(density) { mutableStateOf(24.dp) }
                var lensControlsTop by remember(density) { mutableStateOf<Dp?>(null) }
                var measuredActionsHeight by remember { mutableStateOf(80.dp) }
                var modeRailHeight by remember { mutableStateOf(44.dp) }
                var measuredSelectorHeight by remember(maxWidth, fontScale, controlRotationDegrees) { mutableStateOf(160.dp) }
                var measuredEditorHeight by remember(maxWidth, fontScale, controlRotationDegrees) { mutableStateOf(268.dp) }
                val actionsHeight = measuredActionsHeight
                var feedbackHeight by remember(density) { mutableStateOf(maxOf(48.dp, with(density) { 32.sp.toDp() } + 16.dp)) }
                val showLongShutterNotice = (!cameraControls.autoExposure && cameraControls.selectedExposureTimeNs > 1_000_000_000L ||
                    captureExposureTimeNs > 1_000_000_000L) && hasCameraPermission && !startupLoading && !lensSwitching &&
                    !actionsVisible && !compositionToolsOpen
                val showFocusPeaking = focusPeakingActive && hasCameraPermission && !startupLoading &&
                    !lensSwitching && !actionsVisible && !compositionToolsOpen
                val captureLift = 8.dp
                val panelCapacity = CameraLayout.panelCapacity(maxWidth.value, maxHeight.value,
                    animatedAspectRatio, (actionsHeight + modeRailHeight).value, captureLift.value).dp
                val panoramaAnimation = rememberPanoramaCaptureAnimation(panoramaState)
                val panoramaCapture = panoramaAnimation.visible
                val panoramaDock = panoramaCapture && panoramaAnimation.state.grid == null
                val panoramaSidePreview = panoramaDock && landscape
                val panoramaPreviewSize = if (landscape) minOf(144.dp, maxWidth * .32f) else minOf(168.dp, maxHeight * .22f, panelCapacity)
                val panoramaDockHeight = if (panoramaDock && !landscape) panoramaPreviewSize * panoramaAnimation.progress else 0.dp
                val manualPanelHeight = minOf(manualHeight, panelCapacity)
                val visibleManualHeight = manualPanelHeight * (1f - filmExpansion) * (1f - panoramaAnimation.progress)
                val dockWidth = maxWidth
                val editorHeight = minOf(if (landscape) 268.dp * fontScale.coerceAtMost(1.4f) else measuredEditorHeight, panelCapacity)
                val selectorHeight = minOf(measuredSelectorHeight, panelCapacity)
                val animatedFilmHeight by animateDpAsState(
                    if (showEditorPage) editorHeight else selectorHeight,
                    tween(280, easing = FastOutSlowInEasing), label = "Profile editor height")
                val filmHeight = minOf(animatedFilmHeight, panelCapacity)
                val dockHeight = actionsHeight + modeRailHeight + visibleManualHeight + filmHeight * filmExpansion + panoramaDockHeight
                val freeHeight = maxHeight - dockHeight - captureLift
                val collapsedPreviewHeight = maxHeight - actionsHeight - modeRailHeight - minOf(manualBaseHeight, panelCapacity) - captureLift
                val previewBehindControls by animateFloatAsState(if (preferences.extendTallCropPreview && !panoramaState.busy) 1f else 0f,
                    tween(PANORAMA_TRANSITION_MILLIS, easing = FastOutSlowInEasing), label = "Preview behind controls")
                val extendPreview = !panoramaState.busy && preferences.extendTallCropPreview && maxWidth / viewfinderAspectRatio > collapsedPreviewHeight + .5.dp
                val previewExtension by animateFloatAsState(if (extendPreview) 1f else 0f,
                    tween(PANORAMA_TRANSITION_MILLIS, easing = FastOutSlowInEasing), label = "Crop behind controls")
                val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
                val previewLeft = with(density) { -cameraInsets.getLeft(this, direction).toDp() } * previewExtension
                val previewTop = with(density) { -cameraInsets.getTop(this).toDp() } * previewExtension
                val previewWidth = maxWidth + (screenWidth - maxWidth) * previewExtension
                val previewHeight = freeHeight + (collapsedPreviewHeight - freeHeight) * previewBehindControls +
                    (screenHeight - collapsedPreviewHeight) * previewExtension
                val panoramaSideSpace = if (panoramaSidePreview) (panoramaPreviewSize + 8.dp) * panoramaAnimation.progress else 0.dp
                val viewport = CameraLayout.fittedViewfinder(CameraArea(
                    (previewLeft + if (panoramaSidePreview && controlRotationDegrees == 90f) panoramaSideSpace else 0.dp).value, previewTop.value,
                    (previewWidth - panoramaSideSpace).coerceAtLeast(1.dp).value, previewHeight.value), animatedAspectRatio)
                val dockOverlap by animateFloatAsState(
                    if (viewport.top + viewport.height > freeHeight.value + .5f) 1f else 0f,
                    tween(220, easing = LinearOutSlowInEasing), label = "Preview dock transparency")
                val systemBottom = systemInsets.getBottom(density)
                val keyboardHeight = if (advancedSpektraVisible) 0.dp else
                    with(density) { (WindowInsets.ime.getBottom(this) - systemBottom).coerceAtLeast(0).toDp() }
                ViewfinderSurface(viewfinderSurfaceAspectRatio, viewport, surfaceHeight = maxHeight + keyboardHeight,
                    cropAspectRatio = animatedAspectRatio) {
                    // The loupe is drawn into the viewfinder surface, so its position is measured against it.
                    Box(Modifier.fillMaxSize().onGloballyPositioned { viewfinderSurfaceBounds = it.visualBoundsInRoot() }) {
                        viewfinder()
                    }
                }
                CameraViewport(Modifier.offset(viewport.left.dp, viewport.top.dp).wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(viewport.width.dp, viewport.height.dp).testTag("Camera viewport")
                    .cameraModeGestures(captureMode, controlRotationDegrees, modeSwipeEnabled, preferences.modeSwipeEnabled, changeCaptureMode)) {
                    CompositionGuides(preferences.compositionGrid, preferences.horizonEnabled && !actionsVisible,
                        deviceRotationDegrees, visible = !panoramaCapture && panoramaState.grid == null)
                    val horizontal = minOf(1f, animatedAspectRatio / viewfinderSurfaceAspectRatio)
                    val vertical = minOf(1f, viewfinderSurfaceAspectRatio / animatedAspectRatio)
                    ViewfinderMeteringOverlay(
                        status = tapMeteringStatus.copy(
                            normalizedX = .5f + (tapMeteringStatus.normalizedX - .5f) / horizontal,
                            normalizedY = .5f + (tapMeteringStatus.normalizedY - .5f) / vertical,
                        ),
                        exposureStatus = exposureMeteringStatus.copy(
                            normalizedX = .5f + (exposureMeteringStatus.normalizedX - .5f) / horizontal,
                            normalizedY = .5f + (exposureMeteringStatus.normalizedY - .5f) / vertical,
                        ),
                        onTap = { x, y ->
                            if (spektraVisible && !advancedSpektraVisible) setSpektraVisible(false)
                            val surfaceX = .5f + (x - .5f) * horizontal
                            val surfaceY = .5f + (y - .5f) * vertical
                            if (loupeManual && hasCameraPermission && !startupLoading && !lensSwitching && !panoramaCapture)
                                loupeTargetRequest = LoupeTargetRequest((loupeTargetRequest?.generation ?: 0L) + 1L, surfaceX, surfaceY)
                            else onViewfinderTap(surfaceX, surfaceY)
                        },
                        onExposure = { x, y -> onExposurePointChanged(.5f + (x - .5f) * horizontal, .5f + (y - .5f) * vertical) },
                        onFocus = { x, y -> onFocusPointChanged(.5f + (x - .5f) * horizontal, .5f + (y - .5f) * vertical) },
                        onResetFocus = { onAutoFocus() },
                        onResetExposure = onClearExposurePoint,
                    )
                    if (loupeSelected && hasCameraPermission && !startupLoading && !lensSwitching && !panoramaCapture) {
                        FocusLoupeLayer(
                            manualActive = loupeManual,
                            tapEnabled = loupeOnTap || halfPressHeld && cameraControls.autoFocus && loupeControlsFree,
                            held = halfPressHeld,
                            tapStatus = tapMeteringStatus,
                            status = focusLoupeStatus,
                            zoom = preferences.loupeZoom,
                            peaking = if (preferences.loupePeaking) preferences.focusPeakingStrength.shaderValue else 0f,
                            horizontal = horizontal,
                            vertical = vertical,
                            surfaceBounds = viewfinderSurfaceBounds,
                            obstacles = listOfNotNull(histogramBounds),
                            topInset = (56.dp - viewport.top.dp).coerceAtLeast(0.dp),
                            rotation = controlRotationDegrees,
                            placement = preferences.loupePlacement,
                            targetRequest = loupeTargetRequest,
                            onPlacementChange = { onPreferencesChanged(preferences.copy(loupePlacement = it)) },
                            onZoomChange = { onPreferencesChanged(preferences.copy(loupeZoom = it)) },
                            onRequest = onFocusLoupeChanged,
                            onBoundsChange = { loupeBounds = it },
                        )
                    } else LaunchedEffect(Unit) { onFocusLoupeChanged(null); loupeBounds = null }
                    LensSwitchLoadingOverlay(
                        lensSwitching || startupLoading,
                        coverPreview = startupLoading,
                    )
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shutterFlash.value * .5f }.background(Color.Black))
                    CameraModeOverlay(captureMode, controlRotationDegrees, enabled = !panoramaCapture)
                    CameraModeHint(modeHintVisible && !panoramaCapture, captureMode, controlRotationDegrees, onModeSwipeHintFinished)
                    PanoramaOverlay(panoramaState, controlRotationDegrees, cameraControls.manualExposureSupported, onGridAimRadiusChanged,
                        deviceRotation = deviceRotationDegrees)
                    if (timedShutter.remaining > 0) FlowRow(Modifier.align(Alignment.Center).offset(y = (-56).dp)
                        .clip(RoundedCornerShape(50)).background(CameraColors.Glass)
                        .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(50))
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), itemVerticalAlignment = Alignment.CenterVertically) {
                        CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_timer, Modifier.size(16.dp), CameraColors.Muted)
                        val count = timedShutter.remaining.toString()
                        val phrase = AppLanguage.format("Photo in {value1}", mapOf("value1" to timedShutter.remaining))
                        val label = androidx.compose.ui.text.buildAnnotatedString {
                            append(phrase)
                            val start = phrase.indexOf(count)
                            if (start >= 0) addStyle(androidx.compose.ui.text.SpanStyle(color = CameraColors.Accent,
                                fontFamily = CameraTypography.Readout, fontSize = 16.sp), start, start + count.length)
                        }
                        androidx.compose.material3.Text(label, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 20.sp,
                            modifier = Modifier.testTag("Shutter countdown"))
                        Box(Modifier.heightIn(min = 36.dp).clip(CameraStyle.ActionShape)
                            .border(1.dp, CameraColors.MenuBorder, CameraStyle.ActionShape).clickable(onClick = timedShutter.onPress)
                            .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
                val railHeight = minOf((freeHeight - 146.dp).coerceIn(120.dp, 360.dp) * .6f,
                    (freeHeight - 76.dp).coerceAtLeast(0.dp))
                val rearCameras = enabledCameras.filter { it.facing != LensFacing.FRONT }
                val frontCamera = enabledCameras.firstOrNull { it.facing == LensFacing.FRONT }
                val selectedFront = enabledCameras.firstOrNull { it.route.routeId == selectedLensId }?.facing == LensFacing.FRONT
                val flipTarget = if (selectedFront) rearCameras.minWithOrNull(compareBy<CameraDescriptor>(
                    { if (it.lensRole == LensRole.MAIN) 0 else 1 },
                    { it.relativeZoom?.let { zoom -> abs(zoom - 1f) } ?: Float.MAX_VALUE },
                )) else frontCamera
                CameraDock(
                    Modifier.align(Alignment.BottomCenter).offset(y = -captureLift).width(dockWidth).height(dockHeight).testTag("Camera dock")
                        .clip(androidx.compose.ui.graphics.RectangleShape)
                        .background(remember(dockOverlap) { CameraColors.viewfinderDockBackground(dockOverlap) })
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                ) {
                    if (panoramaDock && !landscape) PanoramaPreviewDock(panoramaAnimation.state, controlRotationDegrees,
                        Modifier.fillMaxWidth().height(panoramaDockHeight).graphicsLayer {
                            alpha = panoramaAnimation.progress
                            translationY = 12.dp.toPx() * (1f - panoramaAnimation.progress)
                        })
                    if (spektraVisible || advancedSpektraVisible || filmExpansion > 0f) Box(
                        Modifier.fillMaxWidth().height(filmHeight * filmExpansion).clipToBounds()
                            .graphicsLayer { alpha = filmExpansion }
                            .background(remember(dockOverlap, preferences.controlOpacity) {
                                CameraColors.viewfinderPanelBackground(dockOverlap, preferences.controlOpacity)
                            }),
                    ) {
                        CameraPageTransition(showEditorPage, Modifier.fillMaxSize(), order = { if (it) 1 else 0 }) { showEditor ->
                        if (showEditor) {
                            OrientedLookPanel(landscape, controlRotationDegrees,
                                Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(editorHeight)
                                    .onGloballyPositioned { guideTargets["editor"] = it.boundsInRoot() }) {
                                SpektraAdjustmentTray(spektra, spektraRepository, {
                                    advancedSpektraVisible = false
                                    if (cameraGuide == CameraGuideStep.SAVE_PROFILE) onGuideStepChanged(CameraGuideStep.SETTINGS.ordinal)
                                },
                                    landscape, controlRotationDegrees, Modifier.fillMaxSize(), onSaved = {
                                        advancedSpektraVisible = false
                                        setSpektraVisible(true)
                                        customSelectionRequest++
                                        if (cameraGuide == CameraGuideStep.SAVE_PROFILE) onGuideStepChanged(CameraGuideStep.SETTINGS.ordinal)
                                    }, onSaveBounds = { guideTargets["save"] = it }, sensorAeActive = cameraControls.customAe || panoramaState.enabled,
                                    manualExposureOnly = panoramaState.enabled,
                                    videoMode = videoState.enabled,
                                    onContentHeightChanged = { if (!landscape) measuredEditorHeight = it })
                            }
                        } else SpektraLookSelector(spektra, spektraRepository,
                            { if (advancedSpektraVisible) advancedSpektraVisible = false else setSpektraVisible(false) },
                            { openProfileEditor() },
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                .onSizeChanged { measuredSelectorHeight = with(density) { it.height.toDp() } },
                             rotationDegrees = controlRotationDegrees, customSelectionRequest = customSelectionRequest,
                             processingSelectionRequest = processingSelectionRequest,
                             sensorAeActive = cameraControls.customAe || panoramaState.enabled, videoState = videoState,
                             onVideoChromaDenoiseChanged = onVideoChromaDenoiseChanged,
                             onVideoChromaDenoiseStrengthChanged = onVideoChromaDenoiseStrengthChanged,
                             onVideoHotPixelRemovalChanged = onVideoHotPixelRemovalChanged,
                            onCustomSectionChanged = { customSectionVisible = it },
                            onReorderBounds = { guideTargets["reorder"] = it },
                            onReorderFinished = {
                                if (cameraGuide == CameraGuideStep.REORDER_CARDS) onGuideStepChanged(CameraGuideStep.CLOSE_CONTROLS.ordinal)
                            },
                            onCreateBounds = { guideTargets["custom"] = it })
                        }
                    }
                    if (filmExpansion < 1f && panoramaAnimation.progress < 1f) Box(
                        Modifier.fillMaxWidth().height(visibleManualHeight).clipToBounds()
                            .graphicsLayer { alpha = (1f - filmExpansion) * (1f - panoramaAnimation.progress) },
                    ) {
                        ManualCameraPanel(
                            active = activeCameraParameter, controls = cameraControls,
                            onIsoPriority = onIsoPriority, onShutterPriority = onShutterPriority,
                            manualOnly = panoramaState.busy, enabled = !panoramaState.busy && !panoramaCapture,
                            onAutoFocus = onAutoFocus, onAutoWhiteBalance = onAutoWhiteBalance,
                            shutterWarningThresholdNs = shutterWarningThresholdNs,
                            onSelect = {
                                videoFramingVisible = false
                                setSpektraVisible(false)
                                activeCameraParameter = if (activeCameraParameter == it) null else it
                            },
                            sliderExpansion = sliderExpansion,
                            sliderHeight = manualSliderHeight,
                            overlayFraction = dockOverlap,
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(manualHeight)
                                .onGloballyPositioned { guideTargets["manual"] = it.boundsInRoot() },
                        ) {
                            CameraParameterSlider(activeCameraParameter ?: lastManualParameter, cameraControls, onExposureCompensationChanged,
                                onAutoExposure, onIsoChanged, onExposureTimeChanged, onAutoFocus, onFocusDistanceChanged,
                                onAutoWhiteBalance, onWhiteBalanceTemperatureChanged, controlRotationDegrees, Modifier.fillMaxSize(),
                                onContentHeightChanged = { manualRulerHeight = it },
                                shutterWarningThresholdNs = shutterWarningThresholdNs, manualOnly = panoramaState.busy)
                        }
                    }
                    CameraModeRail(captureMode, modeSwipeEnabled, changeCaptureMode,
                        Modifier.onSizeChanged { modeRailHeight = with(density) { it.height.toDp() } })
                    Spacer(Modifier.height(actionsHeight))
                }
                    // What a held shutter locks on the camera in use.
                    val recordingLock = preferences.recordingLock.resolve(cameraControls.manualExposureSupported,
                        cameraControls.customAe)
                    CaptureActions(viewfinderSpektraEnabled, !hardwareShutterReady, lastPhotoUri, {
                        if (processingGuideActive && processingGuideStep == 3) openGalleryGuide()
                        else onOpenGallery()
                    },
                        {
                            onViewfinderSpektraChanged(!viewfinderSpektraEnabled)
                            if (cameraGuide == CameraGuideStep.PREVIEW) onGuideStepChanged(CameraGuideStep.EXPOSURE.ordinal)
                        },
                        timedShutter.onPress, { flipTarget?.let { pendingLensSelection = it.route.routeId } }, flipTarget != null && !videoState.busy && !panoramaState.busy,
                        {
                            if (cameraGuide == CameraGuideStep.SETTINGS) onGuideStepChanged(CAMERA_GUIDE_STEPS)
                            if (processingGuideStep == 0 && (processingGuideActive || cameraGuide == CameraGuideStep.SETTINGS))
                                onProcessingGuideStepChanged(1)
                            settingsVisible = true
                        },
                        Modifier.align(Alignment.BottomCenter).offset(y = -captureLift).fillMaxWidth()
                            .wrapContentHeight(Alignment.Bottom, unbounded = true)
                            .onSizeChanged { measuredActionsHeight = with(density) { it.height.toDp() } },
                        shutterPulse = { shutterFlash.value },
                        exposureTimeNs = captureExposureTimeNs,
                        exposureStartedAtMs = captureExposureStartedAtMs,
                        showBusyProgress = cameraControls.pendingExposureTimeNs == null,
                        onGalleryHold = onOpenGalleryOverview,
                        overlayFraction = dockOverlap,
                        thumbnailFeedback = visibleFeedback?.takeUnless { it.requiresAttention },
                        mode = captureMode,
                        recording = panoramaState.recording || videoState.phase == com.unspektrawesome.video.VideoPhase.RECORDING,
                        panoramaAuto = if (!panoramaState.busy && activeCameraParameter == null) ({ modifier ->
                            PanoramaAutoButton(cameraControls.autoExposure && cameraControls.autoFocus && cameraControls.autoWhiteBalance,
                                cameraControls.manualExposureSupported, controlRotationDegrees, onPanoramaAuto, modifier)
                        }) else null,
                        // With nothing to lock on this camera, holding the shutter is an ordinary press.
                        onShutterLongPress = if (videoState.enabled && !videoState.busy && hardwareShutterReady &&
                            recordingLock.any) ({
                            spektraVisible = false
                            advancedSpektraVisible = false
                            activeCameraParameter = null
                            onLockedRecording()
                        }) else null,
                        recordingLock = recordingLock,
                        onGuideTarget = { name, bounds -> guideTargets[name] = bounds })
                val bannerTop = minOf(64.dp, (freeHeight - feedbackHeight - 8.dp).coerceAtLeast(0.dp))
                Box(Modifier.align(Alignment.TopCenter).offset(y = bannerTop)
                    .fillMaxWidth().heightIn(max = freeHeight.coerceAtLeast(48.dp)).padding(horizontal = 16.dp)) {
                    AnimatedVisibility(visible = bannerFeedback != null,
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 400.dp).fillMaxWidth(),
                        enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                        var lastFeedback by remember { mutableStateOf(bannerFeedback) }
                        SideEffect { if (bannerFeedback != null) lastFeedback = bannerFeedback }
                        (bannerFeedback ?: lastFeedback)?.let { feedback ->
                            RotatingCameraPanel(controlRotationDegrees, Modifier.onSizeChanged {
                                feedbackHeight = with(density) { it.height.toDp() }
                            }, maxWidth = 400.dp) {
                            CaptureStatusCard(feedback,
                                if (showLensFailureCard) ({ recoveryDismissed = false }) else onManageCaptureQueue,
                                Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                CameraPreviewOverlays(viewport, freeHeight, controlRotationDegrees, railHeight,
                    controlsAlpha = quickMenuControlsAlpha,
                    loupe = loupeBounds,
                    videoControls = recordingInProgress,
                    expandedSliders = expandedSliders,
                    idleGrid = panoramaState.grid?.takeUnless { panoramaState.busy },
                    gridRotation = deviceRotationDegrees,
                    video = {
                        if (burstState.enabled) BurstCaptureIndicator(burstState)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!actionsVisible) VideoOutputIndicator(videoState, Modifier.onSizeChanged {
                                videoOutputHeight = with(density) { it.height.toDp() }
                            }, ::toggleVideoFraming)
                            VideoRecordingStatus(videoState, readPreviewPerformance)
                        }
                    },
                    lenses = {
                        Column(Modifier.onGloballyPositioned {
                            lensControlsTop = if (it.size.width > 0 && it.size.height > 0)
                                with(density) { (it.boundsInRoot().top - guideOrigin.y).toDp() } else null
                        },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedVisibility(showSpektraAeFeedback,
                                enter = fadeIn(tween(220)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 4 },
                                exit = fadeOut(tween(180)) + slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 4 }) {
                                RotatingCameraPanel(controlRotationDegrees, Modifier.padding(bottom = 8.dp)) {
                                    Text("spektrafilm AE · {value1}", arguments = mapOf("value1" to (com.unspektrawesome.camera.VolumeExposure.label(spektra.params))),
                                        modifier = Modifier.previewControlSurface(RoundedCornerShape(14.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .semantics { liveRegion = LiveRegionMode.Polite },
                                        color = if (spektra.params.autoExposure) CameraColors.Accent else CameraColors.Text,
                                        fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                                }
                            }
                            if (!videoState.busy && !panoramaState.busy &&
                                timedShutter.remaining == 0 && activeCameraParameter == null &&
                                cameraControls.pendingExposureTimeNs == null && !showLongShutterNotice) {
                            CameraLensRail(if (selectedFront) enabledCameras.filter { it.facing == LensFacing.FRONT } else rearCameras,
                                selectedLensId, { pendingLensSelection = it }, preferences = preferences,
                                onDefaultProfile = { onPreferencesChanged(com.unspektrawesome.settings.LensProfiles.setDefault(preferences, it)) })
                            }
                        }
                    },
                    exposure = if (!panoramaState.busy && railHeight >= 56.dp * fontScale) ({
                        SensorExposureRail(cameraControls, onExposureCompensationChanged,
                            expanded = PreviewSlider.EXPOSURE in expandedSliders,
                            onExpandedChange = { setPreviewSliderExpanded(PreviewSlider.EXPOSURE, it) },
                            modifier = Modifier.onGloballyPositioned { guideTargets["ev"] = it.boundsInRoot() })
                    }) else null,
                    aeBalance = if (!panoramaState.busy && cameraControls.customAe && railHeight >= 56.dp * fontScale) ({
                        AeBalanceRail(preferences.aeBalance(),
                            supported = cameraControls.exposureMode == com.unspektrawesome.camera.exposure.AeMode.AUTO &&
                                !captureBusy && !startupLoading && !lensSwitching,
                            onChange = { onPreferencesChanged(preferences.withAeBalance(it)) },
                            expanded = PreviewSlider.AE_BALANCE in expandedSliders,
                            onExpandedChange = { setPreviewSliderExpanded(PreviewSlider.AE_BALANCE, it) },
                            modifier = Modifier.onGloballyPositioned { guideTargets["aeBalance"] = it.boundsInRoot() })
                    }) else null,
                    highlights = if (!panoramaState.busy && railHeight >= 56.dp * fontScale) ({
                        HighlightCompressionRail(spektra.params.inputHighlights,
                            { value -> spektraRepository.updateParams { it.copy(inputHighlights = value) } },
                            expanded = PreviewSlider.HIGHLIGHTS in expandedSliders,
                            onExpandedChange = { setPreviewSliderExpanded(PreviewSlider.HIGHLIGHTS, it) },
                            modifier = Modifier.onGloballyPositioned { guideTargets["hc"] = it.boundsInRoot() })
                    }) else null,
                    banner = if (bannerFeedback != null) {
                        val width = minOf(400.dp, chromeWidth - 32.dp)
                        CameraArea((chromeWidth - width).value / 2, bannerTop.value, width.value, feedbackHeight.value)
                    } else null,
                    aeSwitch = if (!panoramaState.busy && preferences.aeSwitchOverlay && hasCameraPermission) ({
                        val engine = if (cameraControls.customAe) com.unspektrawesome.camera.exposure.AeEngine.SPEKTRA
                            else com.unspektrawesome.camera.exposure.AeEngine.ANDROID
                        AeEngineOverlay(engine,
                            enabled = cameraControls.manualExposureSupported && !startupLoading && !lensSwitching &&
                                !captureBusy && preferences.aeEngine == engine) {
                            onPreferencesChanged(preferences.withAeEngine(if (cameraControls.customAe)
                                com.unspektrawesome.camera.exposure.AeEngine.ANDROID else com.unspektrawesome.camera.exposure.AeEngine.SPEKTRA))
                        }
                    }) else null,
                    shadows = if (!panoramaState.busy && railHeight >= 56.dp * fontScale) ({
                        ShadowsRail(spektra.params.inputShadows,
                            { value -> spektraRepository.updateParams { it.copy(inputShadows = value) } },
                            expanded = PreviewSlider.SHADOWS in expandedSliders,
                            onExpandedChange = { setPreviewSliderExpanded(PreviewSlider.SHADOWS, it) },
                            modifier = Modifier.onGloballyPositioned { guideTargets["shadows"] = it.boundsInRoot() })
                    }) else null,
                    performance = if (preferences.performanceOverlay && hasCameraPermission && !videoState.busy && !panoramaState.busy) ({
                        PreviewPerformanceOverlay(readPreviewPerformance,
                            listOf(selectedLensId, viewfinderSpektraEnabled, startupLoading, lensSwitching,
                                preferences.previewShortEdge, videoState.phase), videoMode = videoState.enabled)
                    }) else null,
                    longShutter = if (showLongShutterNotice || showFocusPeaking) ({ interactive ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showFocusPeaking) FocusPeakingOverlay(requireNotNull(peakingStrength),
                                { onPreferencesChanged(if (cameraControls.autoFocus) preferences.copy(autofocusPeakingStrength = it)
                                    else preferences.copy(focusPeakingStrength = it)) }, interactive)
                            if (showLongShutterNotice) LongShutterOverlay(cameraControls, captureExposureTimeNs,
                                onApplyLongShutter, onCancelLongShutter, interactive = interactive)
                        }
                    }) else null,
                    histogram = if (preferences.histogramEnabled && hasCameraPermission) ({
                        Box(Modifier.onGloballyPositioned { histogramBounds = it.visualBoundsInRoot() }) {
                        ViewfinderHistogram(preferences.histogramSize,
                            readHistogram = {
                                if (startupLoading || lensSwitching) null
                                else readHistogram(viewfinderSurfaceAspectRatio, animatedAspectRatio)
                            },
                            resetKey = listOf(selectedLensId, spektra.params.enabled, viewfinderSpektraEnabled,
                                startupLoading, lensSwitching, viewfinderSurfaceAspectRatio, viewfinderAspectRatio),
                            onResize = { onPreferencesChanged(preferences.copy(histogramSize = preferences.histogramSize.next())) },
                            onDisable = { onPreferencesChanged(preferences.copy(histogramEnabled = false)) })
                        }
                    }) else null)
                if (panoramaSidePreview) PanoramaPreviewDock(panoramaAnimation.state, controlRotationDegrees,
                    Modifier.offset(x = if (controlRotationDegrees == 90f) 0.dp else chromeWidth - panoramaPreviewSize * panoramaAnimation.progress)
                        .size(panoramaPreviewSize * panoramaAnimation.progress, freeHeight).graphicsLayer {
                            alpha = panoramaAnimation.progress
                            translationX = 12.dp.toPx() * (1f - panoramaAnimation.progress) * if (controlRotationDegrees == 90f) -1f else 1f
                        }.background(CameraColors.MenuBackground))
                val menuBottom = actionsHeight + captureLift + 16.dp
                val quickMenuBottom = maxOf(dockHeight + captureLift + 16.dp,
                    lensControlsTop?.let { chromeHeight - it + 8.dp } ?: 0.dp)
                val menuTop = if (landscape) 16.dp else if (videoState.busy && controlRotationDegrees == 0f)
                    maxOf(56.dp, viewport.top.dp) + videoOutputHeight + 16.dp else 64.dp
                val menuSides = if (landscape && videoState.busy) maxOf(24.dp,
                    maxOf(viewport.left.dp, chromeWidth - (viewport.left + viewport.width).dp) +
                        videoOutputHeight + 18.dp) else 24.dp
                AnimatedVisibility(visible = actionsVisible, enter = fadeIn(tween(240)), exit = fadeOut(tween(200))) {
                    BackHandler(actionsVisible) { actionsVisible = false }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) { actionsVisible = false })
                        Box(Modifier
                            .align(if (landscape) Alignment.Center else Alignment.TopCenter)
                            .padding(start = menuSides, end = menuSides, top = menuTop, bottom = quickMenuBottom)) {
                            RotatingCameraPanel(controlRotationDegrees, maxWidth = minOf(CameraStyle.QuickMenuWidth, chromeWidth * .79f)) {
                                Box(Modifier.fillMaxWidth().clip(CameraStyle.PanelShape)
                                    .background(CameraColors.QuickMenuBackground)
                                    .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
                                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                                    .then(if (videoState.enabled) Modifier.testTag("Video quick menu surface") else Modifier.testTag("Photo quick menu surface"))
                                    .onGloballyPositioned { guideTargets["quickMenu"] = it.boundsInRoot() }) {
                                Column(Modifier.fillMaxWidth().testTag("Camera quick menu")) {
                                    CameraQuickMenuHeader(captureMode.label, when (captureMode) {
                                        CaptureMode.VIDEO -> com.unspektrawesome.R.drawable.ic_sleek_video
                                        CaptureMode.BURST -> com.unspektrawesome.R.drawable.ic_sleek_layers
                                        CaptureMode.PANORAMA -> com.unspektrawesome.R.drawable.ic_sleek_move_horizontal
                                        CaptureMode.GRID -> com.unspektrawesome.R.drawable.ic_sleek_grid_3x3
                                        else -> com.unspektrawesome.R.drawable.ic_sleek_camera
                                    }) { actionsVisible = false }
                                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                                        .testTag("Camera quick menu body")) {
                                    if (panoramaState.enabled) PanoramaMenuControls(panoramaState, onPanoramaExportLater, onPanoramaProjection)
                                    else if (videoState.enabled) VideoMenuControls(videoState, onVideoBitrateChanged,
                                        onVideoFrameRateChanged, onCaptureGyroChanged, onVideoOisChanged,
                                        onLiveStabilizationChanged, onStabilizationSettingsChanged)
                                    else {
                                        if (burstState.enabled) FlowRow(Modifier.fillMaxWidth().padding(CameraStyle.QuickMenuRowPadding),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Before: {count} s · After: 1 s", arguments = mapOf("count" to burstState.preShutterSeconds),
                                                color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                                            Text("{value1} frames", arguments = mapOf("value1" to burstState.buffered),
                                                color = CameraColors.Text, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = CameraTypography.Readout)
                                            Text(if (burstState.memoryBytes > 0) "Automatic RAM · {value1} MB" else "Automatic RAM",
                                                arguments = mapOf("value1" to burstState.memoryBytes / (1024 * 1024)), color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                                        }
                                        if (!burstState.enabled) VideoMenuCard {
                                            CameraPhotoFormatMenuRow(preferences.saveOriginalDng) { keepRaw ->
                                                onPreferencesChanged(preferences.copy(saveOriginalDng = keepRaw))
                                            }
                                        }
                                        VideoMenuCard {
                                            CameraTimerMenuRow(preferences.shutterTimerSeconds) { seconds ->
                                                onPreferencesChanged(preferences.copy(shutterTimerSeconds = seconds))
                                            }
                                        }
                                    }
                                    VideoMenuFooter(preferences.compositionGrid,
                                        { onPreferencesChanged(preferences.copy(compositionGrid = it)) }, {
                                            actionsVisible = false
                                            resetLookConfirmationVisible = true
                                        })
                                    }
                                }
                                }
                            }
                        }
                    }
                }
                VideoFramingDialog(videoFramingVisible, preferences, videoState, controlRotationDegrees,
                    menuTop, menuSides, menuBottom, onPreferencesChanged, onVideoDownscaleChanged, { videoFramingVisible = false })
                val compositionPreferences = if (videoState.enabled) preferences.copy(
                    photoCrop = preferences.videoCrop, selectedPhotoCropId = preferences.selectedVideoCropId) else preferences
                AnimatedVisibility(!panoramaCapture, Modifier.fillMaxSize(),
                    enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
                    Box(Modifier.fillMaxSize()) {
                        CameraCompositionToolbar(compositionPreferences, { updated ->
                            onPreferencesChanged(if (videoState.enabled) updated.copy(
                                photoCrop = preferences.photoCrop, selectedPhotoCropId = preferences.selectedPhotoCropId,
                                videoCrop = updated.photoCrop, selectedVideoCropId = updated.selectedPhotoCropId) else updated)
                        }, actionsVisible,
                            {
                                val opening = !actionsVisible
                                if (opening) {
                                    setSpektraVisible(false)
                                    advancedSpektraVisible = false
                                    activeCameraParameter = null
                                }
                                actionsVisible = opening
                            }, { actionsVisible = false }, controlRotationDegrees,
                            (chromeHeight - actionsHeight - captureLift - 74.dp).coerceAtLeast(64.dp), cropOutputSize,
                            { previewCrop = it; onCropPreviewChanged(it) },
                            { compositionToolsOpen = it },
                            { guideTargets["menu"] = it }, { guideTargets["tools"] = it },
                            flashAvailable = cameraControls.flashAvailable && !panoramaState.enabled, captureBusy = captureBusy || panoramaState.busy,
                            videoMode = videoState.enabled, cropEnabled = !videoState.busy && !panoramaState.busy,
                            onVideoCrop = ::toggleVideoFraming, panelTop = menuTop, panelSides = menuSides, panelBottom = menuBottom)
                    }
                }
                if (!hasCameraPermission) PermissionPrompt(onRequestCameraPermission)
                val visibleVideoGuide = videoGuide.takeIf { hasCameraPermission && !startupLoading && !lensSwitching &&
                    !captureBusy && !videoState.busy && !settingsVisible && !advancedSpektraVisible &&
                    !videoFramingVisible && !compositionToolsOpen && activeCameraParameter == null }
                GuideTransition(visibleVideoGuide) { displayedGuide ->
                    VideoGuide(displayedGuide, guideTargets[when {
                        actionsVisible -> "quickMenu"
                        spektraVisible -> "reorder"
                        displayedGuide == VideoGuideStep.FRAMING -> "tools"
                        displayedGuide == VideoGuideStep.REVIEW -> "gallery"
                        else -> "manual"
                    }], onSkip = { onVideoGuideStepChanged(VIDEO_GUIDE_STEPS) },
                        onNext = { onVideoGuideStepChanged(displayedGuide.ordinal + 1) })
                }
                val visibleCameraGuide = cameraGuide.takeIf { hasCameraPermission && !startupLoading && !lensSwitching &&
                    !captureBusy && (!actionsVisible || cameraGuide == CameraGuideStep.TOP_TOOLS) &&
                    (!advancedSpektraVisible || cameraGuide in setOf(CameraGuideStep.TOP_TOOLS, CameraGuideStep.SAVE_PROFILE)) &&
                    (activeCameraParameter == null || cameraGuide in setOf(CameraGuideStep.MANUAL_CONTROLS, CameraGuideStep.FOCUS_METERING)) &&
                    !settingsVisible && !compositionToolsOpen }?.let { it to (it == CameraGuideStep.TOP_TOOLS && actionsVisible) }
                GuideTransition(visibleCameraGuide) { (displayedGuide, displayedMenuOpen) ->
                    CameraGuideOverlay(
                        step = displayedGuide,
                        dockHeight = dockHeight,
                        captureLift = captureLift,
                        actionsHeight = actionsHeight,
                        targetBounds = guideTargets[when (displayedGuide) {
                            CameraGuideStep.REORDER_CARDS -> "reorder"
                            CameraGuideStep.EXPOSURE -> "ev"
                            CameraGuideStep.AUTO_EXPOSURE -> "settings"
                            CameraGuideStep.AE_BALANCE -> if (cameraControls.customAe) "aeBalance" else ""
                            CameraGuideStep.SHADOWS -> "shadows"
                            CameraGuideStep.HIGHLIGHTS -> "hc"
                            CameraGuideStep.MANUAL_CONTROLS, CameraGuideStep.FOCUS_METERING -> "manual"
                            CameraGuideStep.TOP_TOOLS -> if (displayedMenuOpen) "quickMenu" else "tools"
                            CameraGuideStep.CREATE_PROFILE -> if (spektraVisible) "custom" else "menu"
                            CameraGuideStep.SAVE_PROFILE -> "save"
                            else -> ""
                        }]?.translate(-guideOrigin),
                        menuOpen = displayedMenuOpen,
                        originInRoot = guideOrigin,
                        onSkip = {
                            onProcessingGuideStepChanged(CAMERA_PROCESSING_GUIDE_STEPS)
                            onGuideStepChanged(CAMERA_GUIDE_STEPS)
                        },
                        onNext = {
                            when (displayedGuide) {
                                CameraGuideStep.OPEN_CONTROLS -> setSpektraVisible(true)
                                CameraGuideStep.CLOSE_CONTROLS -> setSpektraVisible(false)
                                CameraGuideStep.CREATE_PROFILE -> {
                                    spektraRepository.clearProfileSelection()
                                    openProfileEditor()
                                }
                                else -> onGuideStepChanged(displayedGuide.ordinal + 1)
                            }
                        },
                    )
                }
                val visibleProcessingGuide = processingGuideStep.takeIf { processingGuideActive && it in setOf(0, 3) && hasCameraPermission &&
                    !startupLoading && !lensSwitching && !captureBusy && !settingsVisible &&
                    !actionsVisible && !spektraVisible && activeCameraParameter == null }
                GuideTransition(visibleProcessingGuide) { displayedGuide ->
                    CameraProcessingGuide(displayedGuide, guideTargets[if (displayedGuide == 0) "settings" else "gallery"],
                        onSkip = { onProcessingGuideStepChanged(CAMERA_PROCESSING_GUIDE_STEPS) },
                        onNext = {
                            if (displayedGuide == CAMERA_PROCESSING_GUIDE_STEPS - 1) openGalleryGuide()
                            else onProcessingGuideStepChanged(displayedGuide + 1)
                        })
                }
            }
            }
            }
        }
    }
}

private fun manualCameraCardHeight(fontScale: Float) = 56.dp * fontScale + 24.dp * (fontScale - 1f).coerceAtLeast(0f)

@Composable
private fun ManualCameraPanel(
    active: CameraParameter?,
    controls: CameraControlState,
    onSelect: (CameraParameter) -> Unit,
    onIsoPriority: () -> Boolean,
    onShutterPriority: () -> Boolean,
    onAutoFocus: () -> Boolean,
    onAutoWhiteBalance: () -> Boolean,
    shutterWarningThresholdNs: Long,
    sliderExpansion: Float,
    sliderHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    overlayFraction: Float = 0f,
    manualOnly: Boolean = false,
    enabled: Boolean = true,
    slider: @Composable () -> Unit,
) {
    val choices = listOf(CameraParameter.ISO to "ISO", CameraParameter.SHUTTER to "SHUTTER", CameraParameter.FOCUS to "FOCUS", CameraParameter.WHITE_BALANCE to "WB")
    val rotation = LocalCameraRotation.current
    val sideways = abs(rotation) == 90f
    val cardFontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val controlBackground = LocalCameraControlBackground.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val columns = if (maxWidth < 300.dp * LocalDensity.current.fontScale) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { (parameter, label) ->
                            val locked = controls.customAe && when (parameter) {
                                CameraParameter.ISO -> controls.exposureMode.isoLocked
                                CameraParameter.SHUTTER -> controls.exposureMode.shutterLocked
                                else -> false
                            }
                            val canLock = !manualOnly && controls.customAe && (parameter == CameraParameter.ISO || parameter == CameraParameter.SHUTTER)
                            val canResetFocus = !manualOnly && controls.manualFocusSupported && parameter == CameraParameter.FOCUS && active != CameraParameter.FOCUS
                            val manualWb = parameter == CameraParameter.WHITE_BALANCE && !controls.autoWhiteBalance
                            val canResetWb = !manualOnly && controls.manualWhiteBalanceSupported && parameter == CameraParameter.WHITE_BALANCE && active != CameraParameter.WHITE_BALANCE
                            val slowShutter = parameter == CameraParameter.SHUTTER && controls.selectedExposureTimeNs > shutterWarningThresholdNs
                            val priorityLocked = controls.isPriorityLocked(parameter)
                            val selectionColor = when {
                                manualWb -> CameraColors.whiteBalanceColor(((controls.whiteBalanceTemperatureKelvin ?: 6000) - 2000) / 8000f)
                                priorityLocked -> CameraColors.ExposurePriority
                                else -> Accent
                            }
                            Box(Modifier.weight(1f).height(manualCameraCardHeight(cardFontScale))
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active != null) CameraColors.MenuCard.copy(alpha = 1f - overlayFraction * .3f)
                                    else controlBackground.copy(alpha = controlBackground.alpha * overlayFraction))
                                .border(1.dp, if (active == parameter || locked || manualWb) selectionColor else
                                    CameraColors.MenuBorder.copy(alpha = if (active != null) 1f else overlayFraction), RoundedCornerShape(12.dp))
                                .combinedClickable(enabled = enabled, role = Role.Tab, onClick = { onSelect(parameter) },
                                    onLongClickLabel = when {
                                        canLock -> AppLanguage.format("Toggle {label} priority lock", mapOf("label" to (label)))
                                        canResetFocus -> AppLanguage.text("Reset focus to Auto")
                                        canResetWb -> AppLanguage.text("Reset white balance to Auto")
                                        else -> null
                                    },
                                    onLongClick = when {
                                        canLock -> ({ if (parameter == CameraParameter.ISO) onIsoPriority() else onShutterPriority(); Unit })
                                        canResetFocus -> ({ onAutoFocus(); Unit })
                                        canResetWb -> ({ onAutoWhiteBalance(); Unit })
                                        else -> null
                                    })
                                .testTag("Camera $label control")
                                .languageSemantics { language ->
                                    selected = active == parameter || locked
                                    if (canLock) stateDescription = language.text(
                                        if (priorityLocked) controls.exposureMode.label else if (locked) "Locked" else "Automatic")
                                }
                                .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                val value = when (parameter) {
                                    CameraParameter.ISO -> "${controls.iso}"
                                    CameraParameter.SHUTTER -> "${formatFineShutter(controls.selectedExposureTimeNs)} s"
                                    CameraParameter.FOCUS -> if (controls.autoFocus) "Auto" else if (FocusScale.isInfinity(controls.focusDistance)) "Infinity" else String.format(Locale.ROOT, "%.2f m", 1f / controls.focusDistance)
                                    CameraParameter.WHITE_BALANCE -> controls.whiteBalanceTemperatureKelvin?.let { "$it K" } ?: "Auto"
                                    else -> ""
                                }
                                OrientedLookPanel(sideways, rotation, Modifier.matchParentSize()) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    val labelSize = if (sideways && parameter == CameraParameter.SHUTTER) 9.sp else 10.sp
                                    Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        color = if (slowShutter) CameraColors.SlowShutter else if (active == parameter || locked || manualWb) selectionColor else Muted,
                                        fontSize = labelSize, lineHeight = 13.sp, letterSpacing = .8.sp)
                                    Text(value, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        color = if (slowShutter) CameraColors.SlowShutter else if (active == parameter || locked || manualWb) selectionColor else CameraColors.Text,
                                        fontSize = 14.sp, lineHeight = 18.sp, fontFamily = CameraTypography.Readout)
                                    if (priorityLocked) Text("Priority", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        color = CameraColors.ExposurePriority,
                                        fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, softWrap = false)
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
        val supported = when (active) {
            CameraParameter.ISO, CameraParameter.SHUTTER -> controls.manualExposureSupported
            CameraParameter.FOCUS -> controls.manualFocusSupported
            CameraParameter.WHITE_BALANCE -> controls.manualWhiteBalanceSupported
            else -> true
        }
        if (sliderExpansion > 0f) Box(Modifier.fillMaxWidth().height(sliderHeight * sliderExpansion)
            .padding(top = 4.dp).clipToBounds()
            .graphicsLayer { alpha = sliderExpansion }
            .testTag("Manual control ruler").padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (supported) slider() else Text("Manual adjustment is unavailable on this lens.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun BoxScope.LensSwitchLoadingOverlay(visible: Boolean, coverPreview: Boolean = false) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(if (coverPreview) 0 else 90)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                if (coverPreview) CameraColors.Background else Color.Black.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            LensSwitchSpinner()
        }
    }
}

@Composable
private fun LensSwitchSpinner() {
    val transition = rememberInfiniteTransition(label = "Lens switch spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(760, easing = LinearEasing)),
        label = "Lens switch rotation",
    )
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.58f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                color = Accent,
                startAngle = rotation,
                sweepAngle = 248f,
                useCenter = false,
                style = stroke,
            )
        }
    }
}

@Composable
private fun BoxScope.ViewfinderScrims() {
    Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp).background(
            Brush.verticalGradient(listOf(Color(0xC9000000), Color.Transparent)),
        ),
    )
}

@Composable
private fun BoxScope.TopSpektraControl(
    active: Boolean,
    filmStock: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.align(Alignment.TopStart).padding(start = 8.dp, end = 72.dp).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Accent else Color(0xE8191B1A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "spektrafilm",
            color = if (active) Color.Black else Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Text(
            filmStock,
            color = if (active) Color(0xFF35382F) else Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
private fun BoxScope.TopSettingsControl(rotation: Float, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 4.dp, end = 8.dp),
    ) {
        Text("SET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.animatedCameraRotation(rotation))
    }
}

@Composable
private fun BoxScope.PermissionPrompt(onRequest: () -> Unit) {
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(28.dp),
        color = Glass,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Camera access is required for the RAW viewfinder", color = Color.White)
            Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Allow camera", color = Color.Black)
            }
        }
    }
}

@Composable
private fun LensSelector(
    cameras: List<CameraDescriptor>,
    selectedLensId: String?,
    controlRotationDegrees: Float,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedRotation = remember { Animatable(controlRotationDegrees) }
    LaunchedEffect(controlRotationDegrees) {
        animatedRotation.animateTo(
            targetValue = CameraOrientation.nearestEquivalentRotation(
                animatedRotation.value,
                controlRotationDegrees,
            ),
            animationSpec = tween(
                durationMillis = LENS_ROTATION_DURATION_MILLIS,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cameras.forEach { camera ->
            val selected = camera.route.routeId == selectedLensId
            val label = lensButtonLabel(camera)
            Text(
                label,
                color = if (selected) Color.Black else Color.White,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 5.dp)
                    .rotate(animatedRotation.value)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Accent else Color(0x55191B1A))
                    .clickable { onSelect(camera.route.routeId) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            )
        }
    }
}

internal enum class CameraParameter { WHITE_BALANCE, EV, SHUTTER, ISO, FOCUS }

private fun CameraControlState.isPriorityLocked(parameter: CameraParameter?): Boolean = customAe && when (parameter) {
    CameraParameter.ISO -> exposureMode == AeMode.ISO_PRIORITY
    CameraParameter.SHUTTER -> exposureMode == AeMode.SHUTTER_PRIORITY
    else -> false
}

private enum class SliderLeadingAction { AUTO, RESET, NONE }

internal data class ShutterStop(val nanoseconds: Long, val label: String)

@Composable
private fun CameraParameterPanel(
    controls: CameraControlState,
    active: CameraParameter?,
    controlRotationDegrees: Float,
    panelHeight: androidx.compose.ui.unit.Dp,
    onSelect: (CameraParameter) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(panelHeight).background(Color.Black).clipToBounds()) {
        val slotWidth = maxWidth / CameraParameter.entries.size
        val cardWidth = slotWidth * 0.66f
        val cardHeight = maxHeight * 0.82f
        val horizontalPadding = slotWidth * 0.035f
        Row(Modifier.fillMaxSize()) {
            CameraParameterCard(
                controls.whiteBalanceTemperatureKelvin?.let { "${it}K" }
                    ?: "--",
                "WB",
                active == CameraParameter.WHITE_BALANCE,
                onClick = { onSelect(CameraParameter.WHITE_BALANCE) },
                controlRotationDegrees = controlRotationDegrees,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                horizontalPadding = horizontalPadding,
            )
            CameraParameterCard(
                formatFineShutter(controls.selectedExposureTimeNs),
                "S",
                active == CameraParameter.SHUTTER,
                { onSelect(CameraParameter.SHUTTER) },
                auto = controls.autoExposure,
                controlRotationDegrees = controlRotationDegrees,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                horizontalPadding = horizontalPadding,
            )
            CameraParameterCard(
                controls.iso.toString(),
                "ISO",
                active == CameraParameter.ISO,
                { onSelect(CameraParameter.ISO) },
                auto = controls.autoExposure,
                controlRotationDegrees = controlRotationDegrees,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                horizontalPadding = horizontalPadding,
            )
            CameraParameterCard(
                formatEv(controls.exposureCompensation * controls.exposureCompensationStepEv),
                "EV",
                active == CameraParameter.EV,
                onClick = { onSelect(CameraParameter.EV) },
                controlRotationDegrees = controlRotationDegrees,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                horizontalPadding = horizontalPadding,
            )
            CameraParameterCard(
                if (controls.autoFocus) "AF" else "MF",
                "F",
                active == CameraParameter.FOCUS,
                { onSelect(CameraParameter.FOCUS) },
                controlRotationDegrees = controlRotationDegrees,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                horizontalPadding = horizontalPadding,
            )
        }
    }
}

@Composable
private fun RowScope.CameraParameterCard(
    value: String,
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    auto: Boolean = false,
    controlRotationDegrees: Float,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
) {
    val valueColor = Color.White
    val detailColor = Color(0xFF929292)
    val valueFontSize = 12.sp
    val labelFontSize = 9.sp
    Box(
        Modifier.weight(1f).fillMaxHeight()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .width(cardWidth)
                .height(cardHeight)
                .background(if (selected) Color(0xFF3F3F3F) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = horizontalPadding, vertical = cardHeight * 0.04f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(cardHeight * 0.08f, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (auto) {
                    Text("A", color = Accent, fontSize = valueFontSize, lineHeight = valueFontSize)
                }
                Text(
                    value,
                    color = valueColor,
                    fontSize = valueFontSize,
                    lineHeight = valueFontSize,
                    maxLines = 1,
                    softWrap = false,
                    modifier = if (auto) Modifier.padding(start = 3.dp) else Modifier,
                )
            }
            Text(
                label,
                color = detailColor,
                fontSize = labelFontSize,
                lineHeight = labelFontSize,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun CameraParameterSlider(
    parameter: CameraParameter?,
    controls: CameraControlState,
    onExposureCompensationChanged: (Int) -> Boolean,
    onAutoExposure: () -> Boolean,
    onIsoChanged: (Int) -> Boolean,
    onExposureTimeChanged: (Long) -> Boolean,
    onAutoFocus: () -> Boolean,
    onFocusDistanceChanged: (Float) -> Boolean,
    onAutoWhiteBalance: () -> Boolean,
    onWhiteBalanceTemperatureChanged: (Int) -> Boolean,
    controlRotationDegrees: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onContentHeightChanged: ((Dp) -> Unit)? = null,
    shutterWarningThresholdNs: Long = 1_000_000_000L / 30,
    manualOnly: Boolean = false,
) {
    val selectionColor = if (controls.isPriorityLocked(parameter)) CameraColors.ExposurePriority else Accent
    val layoutLabels = remember(controls.isoMin, controls.isoMax,
        controls.exposureTimeMinNs, controls.exposureTimeMaxNs) {
        isoStops(controls.isoMin, controls.isoMax).map(Int::toString) +
            shutterStops(controls.exposureTimeMinNs, controls.exposureTimeMaxNs).map(ShutterStop::label) +
            (2000..10000 step 1000).map { "${it}K" } + listOf("AF", "MF")
    }
    when (parameter) {
        CameraParameter.WHITE_BALANCE -> {
            val values = WHITE_BALANCE_STOPS
            ParameterRuler(
                layoutLabels = layoutLabels,
                labels = values.map { "${it}K" },
                index = controls.whiteBalanceTemperatureKelvin
                    ?.let { closestIndex(values, it) } ?: 0,
                majorTickInterval = 50,
                fineSteps = true,
                leadingAction = if (manualOnly) SliderLeadingAction.NONE else SliderLeadingAction.AUTO,
                autoActive = controls.autoWhiteBalance,
                autoLabel = controls.whiteBalanceTemperatureKelvin
                    ?.let { "${it}K" } ?: "--",
                focusScale = false,
                temperatureScale = true,
                onIndexChanged = { onWhiteBalanceTemperatureChanged(values[it]) },
                onLeadingAction = { onAutoWhiteBalance() },
                controlRotationDegrees = controlRotationDegrees,
                modifier = modifier,
                vertical = vertical,
                onContentHeightChanged = onContentHeightChanged,
            )
        }
        CameraParameter.EV -> {
            val values = (controls.exposureCompensationMin..controls.exposureCompensationMax).toList()
            val labels = values.map {
                "${formatEv(it * controls.exposureCompensationStepEv)} EV"
            }
            ParameterRuler(
                layoutLabels = layoutLabels,
                labels = labels,
                index = values.indexOf(controls.exposureCompensation).coerceAtLeast(0),
                majorTickInterval = 4,
                leadingAction = SliderLeadingAction.RESET,
                autoActive = false,
                focusScale = false,
                onIndexChanged = { onExposureCompensationChanged(values[it]) },
                onLeadingAction = { onExposureCompensationChanged(0) },
                controlRotationDegrees = controlRotationDegrees,
                modifier = modifier,
                vertical = vertical,
                onContentHeightChanged = onContentHeightChanged,
            )
        }
        CameraParameter.SHUTTER -> {
            val values = remember(controls.exposureTimeMinNs, controls.exposureTimeMaxNs) {
                shutterStops(controls.exposureTimeMinNs, controls.exposureTimeMaxNs)
            }
            val scale = remember(values) { ExposureRulerScale(values.map(ShutterStop::nanoseconds)) }
            ParameterRuler(
                layoutLabels = layoutLabels,
                labels = values.map(ShutterStop::label),
                index = closestIndex(values.map(ShutterStop::nanoseconds), controls.selectedExposureTimeNs),
                majorTickInterval = 4,
                leadingAction = if (manualOnly) SliderLeadingAction.NONE else SliderLeadingAction.AUTO,
                autoActive = controls.shutterAuto,
                focusScale = false,
                onIndexChanged = { onExposureTimeChanged(values[it].nanoseconds) },
                onLeadingAction = { onAutoExposure() },
                fractionalIndex = scale.position(controls.selectedExposureTimeNs),
                onPositionChanged = { onExposureTimeChanged(scale.shutterValue(it)) },
                positionLabel = { formatFineShutter(scale.shutterValue(it)) },
                positionColor = { if (scale.shutterValue(it) > shutterWarningThresholdNs) CameraColors.SlowShutter else selectionColor },
                selectionColor = selectionColor,
                controlRotationDegrees = controlRotationDegrees,
                modifier = modifier,
                vertical = vertical,
                onContentHeightChanged = onContentHeightChanged,
            )
        }
        CameraParameter.ISO -> {
            val values = remember(controls.isoMin, controls.isoMax) {
                isoStops(controls.isoMin, controls.isoMax)
            }
            val scale = remember(values) { ExposureRulerScale(values.map(Int::toLong)) }
            ParameterRuler(
                layoutLabels = layoutLabels,
                labels = values.map(Int::toString),
                index = closestIndex(values.map(Int::toLong), controls.iso.toLong()),
                majorTickInterval = 4,
                leadingAction = if (manualOnly) SliderLeadingAction.NONE else SliderLeadingAction.AUTO,
                autoActive = controls.isoAuto,
                focusScale = false,
                onIndexChanged = { onIsoChanged(values[it]) },
                onLeadingAction = { onAutoExposure() },
                fractionalIndex = scale.position(controls.iso.toLong()),
                onPositionChanged = { onIsoChanged(scale.value(it).toInt()) },
                positionLabel = { scale.value(it).toString() },
                selectionColor = selectionColor,
                controlRotationDegrees = controlRotationDegrees,
                modifier = modifier,
                vertical = vertical,
                onContentHeightChanged = onContentHeightChanged,
            )
        }
        CameraParameter.FOCUS -> {
            // Marks only control the ruler's appearance; focus uses fractional positions between them.
            val marks = remember { List(201) { "" } }
            val position = FocusScale.position(controls.focusDistance, controls.minimumFocusDistance) * marks.lastIndex
            val changeFocus: (Float) -> Unit = {
                onFocusDistanceChanged(FocusScale.distance(it / marks.lastIndex, controls.minimumFocusDistance))
            }
            ParameterRuler(
                layoutLabels = layoutLabels,
                labels = marks,
                index = position.roundToInt(),
                majorTickInterval = 20,
                leadingAction = if (manualOnly) SliderLeadingAction.NONE else SliderLeadingAction.AUTO,
                autoActive = controls.autoFocus,
                focusScale = true,
                onIndexChanged = { changeFocus(it.toFloat()) },
                onLeadingAction = { onAutoFocus() },
                fractionalIndex = position,
                onPositionChanged = changeFocus,
                controlRotationDegrees = controlRotationDegrees,
                modifier = modifier,
                vertical = vertical,
                onContentHeightChanged = onContentHeightChanged,
            )
        }
        null -> Unit
    }
}

@Composable
private fun ParameterRuler(
    labels: List<String>,
    layoutLabels: List<String>,
    index: Int,
    majorTickInterval: Int,
    leadingAction: SliderLeadingAction,
    autoActive: Boolean,
    autoLabel: String? = null,
    focusScale: Boolean,
    onIndexChanged: (Int) -> Unit,
    onLeadingAction: () -> Unit,
    controlRotationDegrees: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    fractionalIndex: Float? = null,
    onPositionChanged: ((Float) -> Unit)? = null,
    positionLabel: ((Float) -> String)? = null,
    positionColor: ((Float) -> Color)? = null,
    selectionColor: Color = Accent,
    temperatureScale: Boolean = false,
    // Many small steps: compact spacing, a tick every fifth step and faster travel on quick drags.
    fineSteps: Boolean = false,
    onContentHeightChanged: ((Dp) -> Unit)? = null,
) {
    if (labels.isEmpty()) return
    val tickStride = if (focusScale || fineSteps) 5 else 1
    val animatedRotation = rememberAnimatedCameraRotation(controlRotationDegrees)
    val density = LocalDensity.current
    val view = LocalView.current
    val controlBackground = LocalCameraControlBackground.current
    val rulerTypeface = CameraTypography.loadNativeReadout(LocalContext.current)
    val rulerMeasurePaint = remember(rulerTypeface) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = rulerTypeface } }
    val dragThresholdPx = with(density) { 5.dp.toPx() }
    var position by remember(labels) { mutableStateOf(fractionalIndex ?: index.toFloat()) }
    var localIndex by remember(labels) { mutableStateOf(index.coerceIn(labels.indices)) }
    var dragging by remember { mutableStateOf(false) }
    val currentAutoActive by rememberUpdatedState(autoActive)
    val currentOnIndexChanged by rememberUpdatedState(onIndexChanged)
    val currentOnLeadingAction by rememberUpdatedState(onLeadingAction)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)

    LaunchedEffect(index, fractionalIndex, labels) {
        if (!dragging) {
            localIndex = index.coerceIn(labels.indices)
            position = fractionalIndex ?: localIndex.toFloat()
        }
    }

    val activeLabel = autoLabel?.takeIf { autoActive }
        ?: positionLabel?.invoke(position)
        ?: if (focusScale) if (autoActive) "AF" else "MF" else labels[localIndex]

    BoxWithConstraints(modifier.fillMaxWidth().background(controlBackground, RoundedCornerShape(12.dp))) {
        val selectedTextSize = with(density) { 12.sp.toPx() }
        val nearbyTextSize = with(density) { 10.sp.toPx() }
        val actionTextSize = with(density) { 10.sp.toPx() }
        val radians = Math.toRadians(animatedRotation.toDouble())
        val horizontalExtent = abs(cos(radians)).toFloat()
        val verticalExtent = abs(sin(radians)).toFloat()
        fun labelExtent(label: String, textSize: Float, rotate: Boolean = true): Offset {
            rulerMeasurePaint.textSize = textSize
            val width = rulerMeasurePaint.measureText(AppLanguage.text(label))
            val metrics = rulerMeasurePaint.fontMetrics
            val height = metrics.descent - metrics.ascent
            if (!rotate) return Offset(width, height)
            return Offset(width * horizontalExtent + height * verticalExtent,
                width * verticalExtent + height * horizontalExtent)
        }
        val actionLabel = if (leadingAction == SliderLeadingAction.AUTO) "Auto" else "Reset"
        val actionExtent = labelExtent(actionLabel, actionTextSize)
        val selectedExtent = labelExtent(activeLabel, selectedTextSize, rotate = false)
        val actionWidth = with(density) { maxOf(44.dp, actionExtent.x.toDp() + 16.dp) }
        val actionHeight = with(density) { maxOf(32.dp, actionExtent.y.toDp() + 8.dp) }
        val headerHeightPx = with(density) { selectedExtent.y + 6.dp.toPx() }
        val labelHeight = maxOf(layoutLabels.maxOf { labelExtent(it, nearbyTextSize).y }, with(density) { 12.dp.toPx() })
        val contentHeight = with(density) { (headerHeightPx + labelHeight).toDp() + 30.dp }
        LaunchedEffect(contentHeight, vertical) {
            if (!vertical) onContentHeightChanged?.invoke(contentHeight)
        }
        fun scaleColor(value: Float): Color = CameraColors.whiteBalanceColor(value / labels.lastIndex.coerceAtLeast(1))
        val spacingPx = with(density) {
            val available = if (vertical) maxHeight else maxWidth
            if (focusScale) (available * 0.0085f).coerceIn(3.dp, 6.dp).toPx()
            else if (fineSteps) (available * 0.016f).coerceIn(5.dp, 8.dp).toPx()
            else (available * 0.058f).coerceIn(12.dp, 24.dp).toPx()
        }
        val leadingEdgePx = with(density) {
            val available = if (vertical) maxHeight else maxWidth
            (available * 0.12f).coerceIn(42.dp, 68.dp).toPx()
        }
        Canvas(
            Modifier.fillMaxSize()
            .languageSemantics { language ->
                contentDescription = language.text("Manual adjustment")
                stateDescription = language.text(activeLabel)
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..labels.lastIndex.toFloat(),
                    if (fractionalIndex != null) 0 else (labels.size - 2).coerceAtLeast(0))
                setProgress { value ->
                    val next = value.roundToInt().coerceIn(labels.indices)
                    position = if (fractionalIndex != null) value.coerceIn(0f, labels.lastIndex.toFloat()) else next.toFloat()
                    localIndex = next
                    if (onPositionChanged != null) onPositionChanged(position) else onIndexChanged(next)
                    true
                }
            }
            .pointerInput(labels, spacingPx, leadingAction, vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val leadingPressed = if (vertical && leadingAction != SliderLeadingAction.NONE) {
                        down.position.y > size.height - leadingEdgePx
                    } else false
                    val startPosition = position
                    var totalDrag = 0f
                    var moved = false
                    val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    dragging = !leadingPressed
                    var manualStarted = false
                    try {
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        if (change.pressed) {
                            val delta = if (vertical) change.positionChange().y else change.positionChange().x
                            totalDrag += delta
                            val wasMoved = moved
                            moved = moved || abs(totalDrag) >= dragThresholdPx
                            if (fineSteps) velocity.addPointerInputChange(change)
                            if (!leadingPressed && moved) {
                                val previousPosition = position
                                position = (if (fineSteps) {
                                    // Slow drags move one step at a time; quick drags cover the range up to ten times faster.
                                    val speed = velocity.calculateVelocity().let { if (vertical) it.y else it.x }
                                    val speedDp = abs(speed).toDp().value / 1000f
                                    val gain = (1f + (speedDp - 0.25f) * 6f).coerceIn(1f, 10f)
                                    position - (if (wasMoved) delta else totalDrag) * gain / spacingPx
                                } else {
                                    startPosition - totalDrag / spacingPx
                                }).coerceIn(0f, labels.lastIndex.toFloat())
                                val next = position.roundToInt().coerceIn(labels.indices)
                                if (currentOnPositionChanged != null && (position != previousPosition || !manualStarted && currentAutoActive)) {
                                    currentOnPositionChanged?.invoke(position)
                                }
                                if (next != localIndex || !manualStarted && currentAutoActive) {
                                    localIndex = next
                                    if (next % tickStride == 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    if (currentOnPositionChanged == null) currentOnIndexChanged(next)
                                }
                                manualStarted = true
                            }
                            change.consume()
                        } else {
                            pressed = false
                        }
                    }
                    if (leadingPressed && !moved) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnLeadingAction()
                    } else if (!leadingPressed) {
                        if (!moved) {
                            val tappedPosition = if (vertical) {
                                (startPosition + (down.position.y - size.height / 2f) / spacingPx)
                            } else {
                                (startPosition + (down.position.x - (size.width + if (leadingAction == SliderLeadingAction.NONE) 0f else actionWidth.toPx()) / 2f) / spacingPx)
                            }
                                .coerceIn(0f, labels.lastIndex.toFloat())
                            val tapped = tappedPosition.roundToInt().coerceIn(labels.indices)
                            if (currentOnPositionChanged != null) {
                                position = tappedPosition
                                currentOnPositionChanged?.invoke(position)
                            } else if (tapped != localIndex || currentAutoActive) currentOnIndexChanged(tapped)
                            localIndex = tapped
                        }
                        if (currentOnPositionChanged == null) position = localIndex.toFloat()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    } finally {
                        dragging = false
                    }
                }
            },
        ) {
            if (vertical) {
                val centerY = size.height / 2f
                val tickStartX = size.width * 0.62f
                val tickEndX = size.width * 0.76f
                val minorTickStartX = size.width * 0.68f
                val labelX = size.width * 0.40f
                val actionX = size.width * 0.42f
                val actionY = size.height * 0.90f
                val actionRadius = (size.width * 0.07f).coerceIn(10.dp.toPx(), 14.dp.toPx())
                val rulerStartY = size.height * 0.08f
                val rulerEndY = size.height * 0.82f
                val first = (position - centerY / spacingPx).toInt().coerceAtLeast(0)
                val last = (position + centerY / spacingPx + 2).toInt().coerceAtMost(labels.lastIndex)

                for (i in first..last) {
                    if (i % tickStride != 0 && i != labels.lastIndex) continue
                    val y = centerY + (i - position) * spacingPx
                    if (y < rulerStartY || y > rulerEndY) continue
                    val major = i % majorTickInterval == 0 || i == 0 || i == labels.lastIndex
                    drawLine(
                        color = if (temperatureScale) scaleColor(i.toFloat()) else Color.White.copy(alpha = if (major) 0.68f else 0.44f),
                        start = androidx.compose.ui.geometry.Offset(if (major) tickStartX else minorTickStartX, y),
                        end = androidx.compose.ui.geometry.Offset(tickEndX, y),
                        strokeWidth = if (major) 1.1.dp.toPx() else 0.8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    if (focusScale && abs(i - localIndex) > 1) {
                        if (i == 0) drawFocusFlower(labelX, y)
                        if (i == labels.lastIndex) drawFocusMountain(labelX, y, 0f)
                    } else if (!focusScale && major && abs(i - localIndex) > 1) {
                        drawRulerText(
                            labels[i],
                            labelX,
                            y,
                            if (temperatureScale) scaleColor(i.toFloat()) else Muted,
                            (size.width * 0.055f).coerceIn(9.sp.toPx(), 11.sp.toPx()),
                        )
                    }
                }

                drawLine(
                    selectionColor,
                    androidx.compose.ui.geometry.Offset(tickStartX, centerY),
                    androidx.compose.ui.geometry.Offset(tickEndX, centerY),
                    2.4.dp.toPx(),
                    StrokeCap.Round,
                )
                drawRulerText(
                    activeLabel,
                    labelX,
                    centerY,
                    positionColor?.invoke(position) ?: if (temperatureScale) scaleColor(position) else selectionColor,
                    (size.width * 0.065f).coerceIn(11.sp.toPx(), 13.sp.toPx()),
                )

                if (leadingAction == SliderLeadingAction.NONE) return@Canvas
                if (leadingAction == SliderLeadingAction.AUTO && autoActive) {
                    drawCircle(Accent, actionRadius, androidx.compose.ui.geometry.Offset(actionX, actionY))
                } else {
                    drawCircle(
                        Accent.copy(alpha = 0.55f),
                        actionRadius,
                        androidx.compose.ui.geometry.Offset(actionX, actionY),
                        style = Stroke(1.dp.toPx()),
                    )
                }
                drawRulerAction(
                    if (leadingAction == SliderLeadingAction.AUTO) "A" else "↺",
                    actionX,
                    actionY,
                    if (leadingAction == SliderLeadingAction.AUTO && autoActive) Color.Black else Accent,
                    (actionRadius * 0.95f).coerceIn(10.sp.toPx(), 13.sp.toPx()),
                )
                return@Canvas
            }

            val centerX = (size.width + if (leadingAction == SliderLeadingAction.NONE) 0f else actionWidth.toPx()) / 2f
            val inset = 12.dp.toPx()
            val rulerStartX = if (leadingAction == SliderLeadingAction.NONE) inset else actionWidth.toPx() + 8.dp.toPx()
            val rulerEndX = size.width - inset
            val labelY = headerHeightPx + 26.dp.toPx() + labelHeight / 2
            val tickCenterY = headerHeightPx + 9.dp.toPx()
            val tickHalfHeight = 6.dp.toPx()
            val tickTop = tickCenterY - tickHalfHeight
            val tickBottom = tickCenterY + tickHalfHeight
            val headerValueX = maxOf(centerX, actionWidth.toPx() + selectedExtent.x / 2 + 12.dp.toPx())
                .coerceAtMost(size.width - selectedExtent.x / 2 - inset)
            drawRoundRect(
                color = controlBackground,
                topLeft = Offset(headerValueX - selectedExtent.x / 2 - 6.dp.toPx(),
                    (headerHeightPx - selectedExtent.y) / 2 - 3.dp.toPx()),
                size = Size(selectedExtent.x + 12.dp.toPx(), selectedExtent.y + 6.dp.toPx()),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            drawRoundRect(CameraColors.MenuBorder,
                topLeft = Offset(headerValueX - selectedExtent.x / 2 - 6.dp.toPx(), (headerHeightPx - selectedExtent.y) / 2 - 3.dp.toPx()),
                size = Size(selectedExtent.x + 12.dp.toPx(), selectedExtent.y + 6.dp.toPx()),
                cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
            if (temperatureScale) {
                val gradientStops = (0..16).map { step ->
                    val fraction = step / 16f
                    val x = rulerStartX + (rulerEndX - rulerStartX) * fraction
                    fraction to scaleColor(position + (x - centerX) / spacingPx)
                }.toTypedArray()
                drawLine(Brush.horizontalGradient(*gradientStops, startX = rulerStartX, endX = rulerEndX),
                    Offset(rulerStartX, tickCenterY), Offset(rulerEndX, tickCenterY), 2.dp.toPx())
            }
            val first = (position - centerX / spacingPx).toInt().coerceAtLeast(0)
            val last = (position + centerX / spacingPx + 2).toInt().coerceAtMost(labels.lastIndex)
            var previousLabelEnd = 0f

            for (i in first..last) {
                if (i % tickStride != 0 && i != labels.lastIndex) continue
                val x = centerX + (i - position) * spacingPx
                if (x < rulerStartX || x > rulerEndX) continue
                val major = i % majorTickInterval == 0 || i == 0 || i == labels.lastIndex
                val halfHeight = if (major) tickHalfHeight else tickHalfHeight * .4f
                drawLine(
                    color = if (temperatureScale) scaleColor(i.toFloat())
                        else CameraColors.Text.copy(alpha = if (major) .85f else .35f),
                    start = Offset(x, tickCenterY - halfHeight),
                    end = Offset(x, tickCenterY + halfHeight),
                    strokeWidth = if (major) 1.2.dp.toPx() else .8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (focusScale) {
                    if (i == 0) drawFocusFlower(x, labelY)
                    if (i == labels.lastIndex) drawFocusMountain(x, labelY, animatedRotation)
                } else if (major) {
                    val halfWidth = labelExtent(labels[i], nearbyTextSize).x / 2
                    if (x - halfWidth >= previousLabelEnd && x + halfWidth <= size.width - 2.dp.toPx()) {
                        drawRulerText(labels[i], x, labelY,
                            if (temperatureScale) scaleColor(i.toFloat()) else Muted,
                            nearbyTextSize, animatedRotation)
                        previousLabelEnd = x + halfWidth + 12.dp.toPx()
                    }
                }
            }

            drawLine(selectionColor, Offset(centerX, tickTop - 2.dp.toPx()),
                Offset(centerX, tickBottom + 2.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            val pointerTop = tickBottom + 3.dp.toPx()
            val pointer = androidx.compose.ui.graphics.Path().apply {
                moveTo(centerX, pointerTop)
                lineTo(centerX - 3.dp.toPx(), pointerTop + 3.dp.toPx())
                lineTo(centerX + 3.dp.toPx(), pointerTop + 3.dp.toPx())
                close()
            }
            drawPath(pointer, selectionColor)
            drawRulerText(activeLabel, headerValueX, headerHeightPx / 2,
                positionColor?.invoke(position) ?: if (temperatureScale) scaleColor(position) else selectionColor,
                selectedTextSize)
        }
        if (!vertical && leadingAction != SliderLeadingAction.NONE) {
            val selected = leadingAction == SliderLeadingAction.AUTO && autoActive
            Box(Modifier.align(Alignment.TopStart)
                .offset(y = with(density) { headerHeightPx.toDp() + 9.dp - actionHeight / 2 })
                .size(actionWidth, actionHeight)
                .previewControlSurface(RoundedCornerShape(16.dp), selected = selected)
                .clickable(role = Role.Button) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentOnLeadingAction()
                }
                .testTag("Manual ruler action")
                .languageSemantics { language ->
                    contentDescription = language.text(actionLabel)
                    this.selected = selected
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRulerAction(actionLabel, center.x, center.y,
                        if (selected) Accent else CameraColors.Text, actionTextSize, animatedRotation)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRulerAction(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    size: Float,
    rotationDegrees: Float = 0f,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val bounds = android.graphics.Rect()
        val label = AppLanguage.text(text)
        paint.getTextBounds(label, 0, label.length, bounds)
        val nativeCanvas = canvas.nativeCanvas
        nativeCanvas.save()
        nativeCanvas.rotate(rotationDegrees, x, y)
        nativeCanvas.drawText(label, x - bounds.exactCenterX(), y - bounds.exactCenterY(), paint)
        nativeCanvas.restore()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRulerText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    size: Float,
    rotationDegrees: Float = 0f,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            typeface = CameraTypography.nativeReadout ?: Typeface.MONOSPACE
        }
        val bounds = android.graphics.Rect()
        val label = AppLanguage.text(text)
        paint.getTextBounds(label, 0, label.length, bounds)
        val nativeCanvas = canvas.nativeCanvas
        nativeCanvas.save()
        nativeCanvas.rotate(rotationDegrees, x, y)
        nativeCanvas.drawText(label, x - bounds.exactCenterX(), y - bounds.exactCenterY(), paint)
        nativeCanvas.restore()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusFlower(x: Float, y: Float) {
    val color = Color(0xFF919191)
    val petal = 3.dp.toPx()
    drawCircle(color, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
    drawCircle(color, petal, androidx.compose.ui.geometry.Offset(x - petal, y))
    drawCircle(color, petal, androidx.compose.ui.geometry.Offset(x + petal, y))
    drawCircle(color, petal, androidx.compose.ui.geometry.Offset(x, y - petal))
    drawCircle(color, petal, androidx.compose.ui.geometry.Offset(x, y + petal))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusMountain(
    x: Float,
    y: Float,
    rotationDegrees: Float,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(145, 145, 145)
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(x - 10.dp.toPx(), y + 5.dp.toPx())
            lineTo(x - 3.dp.toPx(), y - 6.dp.toPx())
            lineTo(x + 2.dp.toPx(), y + 5.dp.toPx())
            close()
            moveTo(x - 1.dp.toPx(), y + 5.dp.toPx())
            lineTo(x + 5.dp.toPx(), y - 4.dp.toPx())
            lineTo(x + 11.dp.toPx(), y + 5.dp.toPx())
            close()
        }
        val nativeCanvas = canvas.nativeCanvas
        nativeCanvas.save()
        nativeCanvas.rotate(rotationDegrees, x, y)
        nativeCanvas.drawPath(path, paint)
        nativeCanvas.restore()
    }
}

internal fun isoStops(min: Int, max: Int): List<Int> {
    val standard = listOf(
        25, 50, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
        1250, 1600, 2000, 2500, 3200, 6400, 12800, 25600,
    )
    return (listOf(min) + standard.filter { it in min..max } + max).distinct().sorted()
}

internal fun shutterStops(min: Long, max: Long): List<ShutterStop> {
    val denominators = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640,
        500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 48, 40, 30, 25, 24,
        20, 15, 13, 10, 8, 6, 5, 4, 3,
    )
    val fractional = denominators.map { ShutterStop(1_000_000_000L / it, "1/$it") }
    val seconds = listOf(0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.0, 4.0,
        5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0).map {
        ShutterStop((it * 1_000_000_000L).toLong(), String.format(Locale.ROOT, "%.1f", it))
    }
    val available = (fractional + seconds + listOf(ShutterStop(min, formatShutter(min)), ShutterStop(max, formatShutter(max))))
        .filter { it.nanoseconds in min..max }.distinctBy { it.nanoseconds }.sortedBy { it.nanoseconds }
    return if (available.isNotEmpty()) available else listOf(ShutterStop(min, formatShutter(min)))
}

// 20 K steps across the whole range; fast drags move further per finger movement.
internal val WHITE_BALANCE_STOPS: List<Int> = (2000..10000 step 20).toList()

private fun closestIndex(values: List<Long>, target: Long): Int =
    values.indices.minByOrNull { abs(values[it] - target) } ?: 0

private fun closestIndex(values: List<Int>, target: Int): Int =
    values.indices.minByOrNull { abs(values[it] - target) } ?: 0

private fun formatEv(value: Float): String = when {
    abs(value) < 0.005f -> "0"
    abs(value - value.roundToInt()) < 0.005f -> value.roundToInt().toString()
    else -> String.format(Locale.ROOT, "%.1f", value)
}

internal fun formatShutter(nanoseconds: Long): String {
    if (nanoseconds <= 0L) return "-"
    if (nanoseconds < 1_000_000_000L) return "1/${(1_000_000_000.0 / nanoseconds).roundToInt()}"
    return String.format(Locale.ROOT, "%.1f", nanoseconds / 1_000_000_000.0)
}

@Composable
private fun CaptureBar(
    onShutter: () -> Unit,
    enabled: Boolean,
    shutterSize: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(shutterSize).languageSemantics { language ->
                contentDescription = language.text("Take photo")
                role = Role.Button
            }.border(2.dp, if (enabled) Color.White else Accent, CircleShape).padding(5.dp)
                .clip(CircleShape).background(if (enabled) Color.White else CameraColors.Surface)
                .clickable(enabled = enabled, onClick = onShutter),
        )
    }
}

@Composable
internal fun CompactDropdownField(
    label: String,
    value: String,
    detail: String?,
    expanded: Boolean,
    enabled: Boolean = true,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(CameraColors.MenuCard)
                    .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(11.dp))
                    .clickable(enabled = enabled, onClick = onExpand)
                    .languageSemantics { language -> contentDescription = language.text(label) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(value, color = if (enabled) Color.White else Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    detail?.let { Text(it, color = Muted, fontSize = 8.sp, lineHeight = 12.sp) }
                }
                Text("▾", color = Accent, fontSize = 13.sp)
            }
            CompactCameraMenu(expanded, onDismiss, Modifier.width(maxWidth.coerceAtMost(320.dp)).heightIn(max = 280.dp)) {
                content()
            }
        }
    }
}

private fun sensorProfileTitle(camera: CameraDescriptor): String = buildString {
    append(lensButtonLabel(camera))
    camera.equivalentFocalLengthMm?.let {
        append(String.format(Locale.ROOT, "  %.0f mm eq", it))
    }
}

private fun lensButtonLabel(camera: CameraDescriptor): String = when (camera.facing) {
    LensFacing.FRONT -> "Front"
    LensFacing.EXTERNAL -> "External"
    else -> camera.relativeZoom?.let(::formatZoom) ?: camera.lensRole.label
}

private const val LENS_ROTATION_DURATION_MILLIS = 300

private fun sensorProfileDetail(camera: CameraDescriptor): String = buildString {
    append(camera.facing.name.lowercase().replaceFirstChar(Char::uppercase))
    camera.focalLengthMm?.let {
        append(String.format(Locale.ROOT, " · %.2f mm", it))
    }
    if (camera.sensorWidthMm != null && camera.sensorHeightMm != null) {
        append(String.format(Locale.ROOT, " · %.2f x %.2f mm sensor", camera.sensorWidthMm, camera.sensorHeightMm))
    }
}

internal fun rawOutputLabel(output: RawOutput): String = rawOutputLabel(RawStreamSelection.from(output))

internal fun rawOutputLabel(stream: RawStreamSelection): String = String.format(
    Locale.ROOT,
    "%d x %d (%s, %.2f)",
    stream.width,
    stream.height,
    stream.format.name,
    stream.width.toFloat() / stream.height,
) + if (stream.maximumResolutionMode) " · " + AppLanguage.text("Maximum resolution") else ""

internal fun outputCapabilityDetail(
    camera: CameraDescriptor,
    output: RawOutput,
    capabilities: Map<RawImportStreamKey, RawImportCapability>,
    overrides: com.unspektrawesome.settings.VendorOverrides = com.unspektrawesome.settings.VendorOverrides.NONE,
    layout: com.unspektrawesome.settings.RawLayoutOverride = com.unspektrawesome.settings.RawLayoutOverride.NONE,
    capture: com.unspektrawesome.settings.LensCaptureSettings = com.unspektrawesome.settings.LensCaptureSettings(),
    rawSession: com.unspektrawesome.camera.session.RawSessionConfiguration? = null,
): String? {
    val capability = capabilities[RawImportStreamKey.from(camera.route.routeId, output, overrides, layout, capture, rawSession)]
    return when (capability?.state) {
        RawImportCapabilityState.COMPATIBLE -> null
        RawImportCapabilityState.INCOMPATIBLE -> "Unavailable: ${capability.reason}"
        null -> null
    }
}

private fun formatZoom(value: Float): String = if (value == value.toInt().toFloat()) {
    "${value.toInt()}x"
} else {
    String.format(Locale.ROOT, "%.1fx", value)
}

private fun formatValue(value: Float, suffix: String?): String {
    val number = String.format(Locale.ROOT, "%+.2f", value)
    return if (suffix == null) number else "$number $suffix"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun UnspektrawesomeTheme(rotation: Float = 0f, content: @Composable () -> Unit) {
    val backdropWindows = remember { CameraBackdropWindows() }
    MaterialTheme(
        typography = CameraTypography.Body,
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            surface = CameraColors.Tray.copy(alpha = 1f),
            surfaceVariant = CameraColors.Surface,
            surfaceContainer = CameraColors.Surface,
            surfaceContainerHigh = CameraColors.Surface,
            surfaceContainerHighest = CameraColors.Surface,
            onSurface = Color.White,
            onSurfaceVariant = CameraColors.Muted,
            secondary = Accent,
            onSecondary = Color.Black,
            outline = CameraColors.Muted,
            background = Color.Black,
        ),
        content = { CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides CameraPressIndication,
            androidx.compose.material3.LocalRippleConfiguration provides null, LocalCameraBackdropWindows provides backdropWindows,
            LocalCameraWindowRotation provides rotation) {
            com.unspektrawesome.language.LanguageRoot(content)
        } },
    )
}
