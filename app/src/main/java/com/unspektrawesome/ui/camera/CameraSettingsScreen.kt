package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.exposure.AeMeter
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.BuiltInLanguage
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.preview.RawImportCapability
import com.unspektrawesome.preview.RawImportStreamKey
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.withAeEngine
import com.unspektrawesome.settings.DEFAULT_CONTROL_OPACITY
import com.unspektrawesome.settings.FocusAssist
import com.unspektrawesome.settings.FocusPeakingStrength
import com.unspektrawesome.settings.SensorProfileCatalog
import com.unspektrawesome.settings.ViewfinderResolutions
import com.unspektrawesome.settings.ViewfinderSource
import com.unspektrawesome.settings.rawLayoutOverride
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class CameraSettingsTab(val label: String) {
    GENERAL("General"),
    VIEWFINDER("Viewfinder"),
    VIDEO("Video"),
    BUTTONS("Buttons"),
    LENS_PROFILES("Lens Profiles"),
    ABOUT("About"),
}

/** Settings inside a lens profile. Search opens the profile in use to show them. */
private val LENS_PROFILE_SEARCH_TERMS = listOf("Sensor input", "CAMERA ID", "RAW STREAM", "Capture", "Photo OIS", "OIS",
    "Capture gyro data", "Live gyro stabilization", "Frame rate", "RAW FRAME RATE", "ISP COMPANION SURFACE", "Frame averaging",
    "Multi-frame merge", "Lens button", "LENS BUTTON LABEL", "Separate lens button", "Button label", "Text size",
    "Button size", "Shape", "Spacing", "Automatic spacing", "Preview", "Auto exposure", "MINIMUM ISO", "MAXIMUM ISO",
    "FASTEST SHUTTER", "SLOWEST SHUTTER", "RAW adjustments", "SENSOR CROP",
    "CFA / REMOSAIC", "CFA COLOR ORDER", "Color calibration", "BLACK / WHITE LEVELS", "Lens shading")

@Composable
internal fun CameraSettingsScreen(
    cameras: List<CameraDescriptor>,
    preferences: CameraPreferences,
    rawImportCapabilities: Map<RawImportStreamKey, RawImportCapability>,
    onChange: (CameraPreferences) -> Unit,
    onDismiss: () -> Unit,
    onShowGuide: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    onShowProcessingGuide: (() -> Unit)? = null,
    processingGuideStep: Int = CAMERA_PROCESSING_GUIDE_STEPS,
    onProcessingGuideStepChanged: (Int) -> Unit = {},
    locationStatus: String = "Off",
    onLocationEnabledChanged: (Boolean) -> Unit = { onChange(preferences.copy(saveLocation = it)) },
    onAutoLensDiscovery: (() -> Unit)? = null,
    onCheckLensFrameRates: LensFrameRateCheck? = null,
    onShowVideoGuide: (() -> Unit)? = null,
    burstState: com.unspektrawesome.burst.BurstStatus = com.unspektrawesome.burst.BurstStatus(),
) {
    val pager = rememberPagerState(initialPage = if (processingGuideStep in 1..2)
        CameraSettingsTab.GENERAL.ordinal else CameraSettingsTab.LENS_PROFILES.ordinal) { CameraSettingsTab.entries.size }
    val guideTargets = remember { mutableStateMapOf<String, Rect>() }
    LaunchedEffect(processingGuideStep) {
        if (processingGuideStep in 1..2) pager.scrollToPage(CameraSettingsTab.GENERAL.ordinal)
    }
    val scope = rememberCoroutineScope()
    var assigningAction by remember { mutableStateOf<com.unspektrawesome.controls.ControlAction?>(null) }
    val profiles = remember(cameras, preferences) { SensorProfileCatalog.resolve(cameras, preferences) }
    val search = remember { SettingsSearchIndex() }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    androidx.activity.compose.BackHandler(enabled = query.isNotEmpty()) { query = "" }
    LaunchedEffect(search) {
        snapshotFlow { search.pending }.filterNotNull().collectLatest { jump ->
            pager.scrollToPage(jump.page)
            if (jump.openProfileEditor) search.profileEditorRequest++
            val target = withTimeoutOrNull(1500) {
                snapshotFlow { search.targets.firstOrNull { it.page == jump.page && it.title == jump.title } }.filterNotNull().first()
            }
            withFrameNanos { }
            target?.requester?.bringIntoView()
            search.highlighted = target
            search.pending = null
        }
    }
    // The camera screen stays upright and turns this page itself in landscape. The phone reports its safe areas
    // (status bar, camera cutout, navigation bar) for the upright screen, so they are turned here to match.
    val density = LocalDensity.current
    val windowRotation = LocalCameraWindowRotation.current
    val safeArea = WindowInsets.safeDrawing
    val upright = with(density) {
        listOf(safeArea.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toDp(), safeArea.getTop(density).toDp(),
            safeArea.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toDp(), safeArea.getBottom(density).toDp())
    }
    val (safeLeft, safeTop, safeRight, safeBottom) = when (windowRotation) {
        90f -> listOf(upright[1], upright[2], upright[3], upright[0])
        -90f -> listOf(upright[3], upright[0], upright[1], upright[2])
        else -> upright
    }
    val tabRequesters = remember { List(CameraSettingsTab.entries.size) { BringIntoViewRequester() } }
    AmbientScreen(Modifier.fillMaxSize()) {
      BoxWithConstraints(Modifier.fillMaxSize().absolutePadding(safeLeft, safeTop, safeRight, safeBottom)) {
        val wide = maxWidth > maxHeight
        val closeButton: @Composable () -> Unit = {
            Box(Modifier.size(48.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDismiss)
                .languageSemantics { language -> contentDescription = language.text("Close camera settings") },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).background(Diagram.Glass, CircleShape).border(1.dp, Diagram.Border, CircleShape),
                    contentAlignment = Alignment.Center) {
                    SettingsIcon(SettingsSymbol.Close, Modifier.size(14.dp), CameraColors.Text)
                }
            }
        }
        val title: @Composable (Modifier) -> Unit = { modifier ->
            Text("CAMERA SETUP", color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp, maxLines = 2, modifier = modifier)
        }
        LaunchedEffect(pager.currentPage, wide) {
            withFrameNanos { }
            tabRequesters[pager.currentPage].bringIntoView()
        }
        val tabLabels = CameraSettingsTab.entries.map { it.label }
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (wide) {
                    // Landscape keeps the title, search and close button on one short line to leave room for the page.
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        title(Modifier.widthIn(max = 160.dp))
                        SettingsSearchField(query, { query = it }, Modifier.weight(1f))
                        closeButton()
                    }
                } else Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        title(Modifier.weight(1f).padding(end = 12.dp))
                        closeButton()
                    }
                    SettingsSearchField(query, { query = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    DiagramTabs(tabLabels, pager.currentPage,
                        tabModifier = { Modifier.bringIntoViewRequester(tabRequesters[it]) }) { index ->
                        scope.launch { pager.animateScrollToPage(index) }
                    }
                }
            },
        ) { padding ->
            val sliderGesture = remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalCompactSettings provides true, LocalSettingsDesign provides true,
                LocalSliderGestureLock provides sliderGesture, LocalSettingsSearch provides search) {
             Row(Modifier.fillMaxSize().padding(padding)) {
              if (wide) DiagramSideTabs(tabLabels, pager.currentPage, Modifier.width(150.dp).testTag("Camera settings side tabs"),
                  tabModifier = { Modifier.bringIntoViewRequester(tabRequesters[it]) }) { index ->
                  scope.launch { pager.animateScrollToPage(index) }
              }
              BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
               // Landscape pages are wide but short, so drawings are limited by the visible height instead of the width.
               CompositionLocalProvider(LocalDiagramMaxStageHeight provides if (wide) maxHeight * .85f else Dp.Unspecified) {
                HorizontalPager(state = pager, beyondViewportPageCount = CameraSettingsTab.entries.lastIndex,
                    userScrollEnabled = !sliderGesture.value,
                    modifier = Modifier.fillMaxSize().testTag("Camera settings pages")) { page ->
                  CompositionLocalProvider(LocalSettingsSearchPage provides page) {
                    when (CameraSettingsTab.entries[page]) {
                        CameraSettingsTab.GENERAL -> GeneralCameraSettings(preferences, onChange, onShowGuide,
                            onShowProcessingGuide, processingGuideStep, { name, bounds -> guideTargets[name] = bounds },
                            locationStatus, onLocationEnabledChanged, onShowVideoGuide, burstState)
                        CameraSettingsTab.VIEWFINDER -> ViewfinderCameraSettings(preferences, onChange)
                        CameraSettingsTab.VIDEO -> VideoCameraSettings(preferences, onChange)
                        CameraSettingsTab.BUTTONS -> ButtonSettings(isActive = pager.currentPage == page) { assigningAction = it }
                        CameraSettingsTab.LENS_PROFILES -> LensProfileSettings(profiles, preferences, rawImportCapabilities,
                            onChange, isActive = pager.currentPage == page, onAutoLensDiscovery = onAutoLensDiscovery, onCheckLensFrameRates = onCheckLensFrameRates)
                        CameraSettingsTab.ABOUT -> AboutScreen(onCheckForUpdates)
                    }
                  }
                }
               }
                if (query.isNotBlank()) {
                    val renderer = LocalAppLanguage.current.renderer
                    SettingsSearchResults(query, search, CameraSettingsTab.entries.map { renderer.text(it.label) },
                        LENS_PROFILE_SEARCH_TERMS.map { SettingsSearchExtra(it, CameraSettingsTab.LENS_PROFILES.ordinal) }) { jump ->
                        focus.clearFocus()
                        query = ""
                        search.pending = jump
                    }
                }
              }
             }
            }
        }
      }
        assigningAction?.let { action -> ButtonAssignOverlay(action) { assigningAction = null } }
        val visibleGuide = processingGuideStep.takeIf { it in 1..2 && pager.currentPage == CameraSettingsTab.GENERAL.ordinal }
        GuideTransition(visibleGuide) { displayedStep ->
            CameraProcessingGuide(displayedStep, guideTargets[if (displayedStep == 1) "later" else "now"],
                onSkip = { onProcessingGuideStepChanged(CAMERA_PROCESSING_GUIDE_STEPS) },
                onNext = { onProcessingGuideStepChanged(displayedStep + 1) })
        }
    }
}

@Composable
private fun GeneralCameraSettings(
    preferences: CameraPreferences,
    onChange: (CameraPreferences) -> Unit,
    onShowGuide: (() -> Unit)?,
    onShowProcessingGuide: (() -> Unit)?,
    processingGuideStep: Int,
    onGuideTarget: (String, Rect) -> Unit,
    locationStatus: String,
    onLocationEnabledChanged: (Boolean) -> Unit,
    onShowVideoGuide: (() -> Unit)?,
    burstState: com.unspektrawesome.burst.BurstStatus,
) {
    val scroll = rememberScrollState()
    val photoSaving = remember { BringIntoViewRequester() }
    LaunchedEffect(processingGuideStep, preferences.aeEngine) {
        if (processingGuideStep in 1..2) {
            withFrameNanos { }
            photoSaving.bringIntoView()
        }
    }
    val spektra = preferences.aeEngine == AeEngine.SPEKTRA
    DiagramPage(scroll) {
        SetupSection("Capture")
        SetupExposureStage(spektra, preferences.aeMeter == AeMeter.MEDIAN, preferences.aeSwitchOverlay)
        val engines = AeEngine.entries
        SetupChoice("Auto exposure", engines.map { it.label }, engines.indexOf(preferences.aeEngine), number = 1) { index ->
            onChange(preferences.withAeEngine(engines[index]))
        }
        if (spektra) {
            val meters = AeMeter.entries
            SetupChoice("Metering", meters.map { it.label }, meters.indexOf(preferences.aeMeter), number = 2,
                detail = "With Unspektra AE") { index ->
                onChange(preferences.copy(aeMeter = meters[index]))
            }
        }
        DiagramSwitchRow("AE switch overlay", preferences.aeSwitchOverlay, number = 3) {
            onChange(preferences.copy(aeSwitchOverlay = it))
        }

        SetupSection("Face tracking")
        SetupFaceTrackingStage(preferences.faceTracking, preferences.faceFocus, preferences.faceExposure)
        DiagramSwitchRow("Face tracking", preferences.faceTracking, Modifier.testTag("Face tracking"), number = 1,
            detail = "Follows people's faces") {
            onChange(preferences.copy(faceTracking = it))
        }
        DiagramSwitchRow("Focus on faces", preferences.faceFocus, number = 2, enabled = preferences.faceTracking) {
            onChange(preferences.copy(faceFocus = it))
        }
        DiagramSwitchRow("Expose for faces", preferences.faceExposure, number = 3, enabled = preferences.faceTracking,
            divider = false) {
            onChange(preferences.copy(faceExposure = it))
        }
        DiagramHint("Works on cameras that report faces to apps. Other cameras keep their normal focus and exposure. A tapped focus or exposure point takes priority until you reset it.",
            Modifier.diagramHairline().padding(start = 24.dp))

        SetupSection("Photo saving")
        SetupPhotoSavingStage(preferences.processPhotosLater, preferences.saveLocation)
        DiagramRow("Process new photos", number = 1, detail = "Same setting as Choose when to process in Gallery settings",
            divider = false) {
            // The selected choice below already states this value for screen readers.
            DiagramValue(if (preferences.processPhotosLater) "Later in Gallery" else "Process now", Modifier.clearAndSetSemantics { },
                translate = true)
        }
        Column(Modifier.bringIntoViewRequester(photoSaving).selectableGroup()) {
            listOf("Process now" to "Saves JPEGs while you shoot.",
                "Later in Gallery" to "Saves RAWs for a session you start afterward, giving the camera more processing room.")
                .forEachIndexed { index, (title, detail) ->
                    DiagramRadioRow(title, preferences.processPhotosLater == (index == 1),
                        Modifier.onGloballyPositioned { onGuideTarget(if (index == 0) "now" else "later", it.boundsInRoot()) },
                        detail) { onChange(preferences.copy(processPhotosLater = index == 1)) }
                }
        }
        CameraLocationSetting(preferences.saveLocation, locationStatus, onLocationEnabledChanged)
        SetupThumbnailStage(preferences.processPhotosLater)
        DiagramHint("Existing waiting photos stay in Gallery with their saved settings.")

        BurstHistorySetting(preferences.burstPreShutterSeconds, burstState.memoryBytes) { onChange(preferences.copy(burstPreShutterSeconds = it)) }

        SetupSection("Camera controls")
        SetupPassiveFocusStage(preferences.passiveFocusIntervalSeconds)
        PassiveFocusInterval(preferences.passiveFocusIntervalSeconds) { onChange(preferences.copy(passiveFocusIntervalSeconds = it)) }
        SetupModeSwipeStage(preferences.modeSwipeEnabled)
        DiagramSwitchRow("Swipe on viewfinder", preferences.modeSwipeEnabled, number = 1, detail = "Mode switching", divider = false) {
            onChange(preferences.copy(modeSwipeEnabled = it))
        }
        DiagramHint("Swipe across the viewfinder to change capture modes. You can also tap a mode name.",
            Modifier.diagramHairline().padding(start = 24.dp))
        SetupOrientationStage(preferences.uiOrientation)
        DiagramRow("Control orientation", number = 1, divider = false) {
            DiagramValue(preferences.uiOrientation.label, Modifier.clearAndSetSemantics { }, translate = true)
        }
        SetupOrientationTiles(preferences.uiOrientation, Modifier.testTag("Control orientation choices")) {
            onChange(preferences.copy(uiOrientation = it))
        }

        AppSizeSettings(scroll)

        SetupSection("App")
        val language by AppLanguage.state.collectAsState()
        val guides = listOfNotNull(onShowGuide, onShowProcessingGuide, onShowVideoGuide).size
        val custom = language.custom
        if (custom == null) {
            SetupLanguageStage(BuiltInLanguage.all.firstOrNull { it.code == language.selectedCode }?.nativeName,
                englishSelected = !language.enabled && language.selectedCode == "en", customEnabled = language.enabled,
                userName = preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId }?.name, guides = guides)
        } else SetupCustomLanguageStage(custom.name, language.enabled, custom.strings.size, language.english.size, BuildConfig.VERSION_NAME)
        LanguageSettingsControls()
        SettingsBackupControls()
        if (guides > 0) {
            SetupSection("Guides", number = if (custom == null) 3 else null)
            if (onShowGuide != null) SetupGuideRow("Show camera guide", CAMERA_GUIDE_STEPS, onShowGuide)
            if (onShowProcessingGuide != null) SetupGuideRow("Show processing guide", CAMERA_PROCESSING_GUIDE_STEPS, onShowProcessingGuide)
            if (onShowVideoGuide != null) SetupGuideRow("Show video guide", VIDEO_GUIDE_STEPS, onShowVideoGuide)
        }
    }
}

@Composable
private fun PassiveFocusInterval(seconds: Int, onChange: (Int) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    var expanded by remember { mutableStateOf(false) }
    fun label(value: Int) = if (value == 0) "Off" else renderer.format("{value} s", mapOf("value" to value))
    DiagramDropdownField("Interval", label(seconds), "Restart passive focus", expanded, translateValue = seconds == 0, number = 1,
        onExpand = { expanded = true }, onDismiss = { expanded = false }) {
        listOf(0, 2, 3, 5, 10, 15, 30, 60).forEach { value ->
            DiagramMenuItem(label(value), seconds == value, translate = value == 0) {
                onChange(value)
                expanded = false
            }
        }
    }
}

@Composable
private fun VideoCameraSettings(preferences: CameraPreferences, onChange: (CameraPreferences) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    val buffer = preferences.recordingBufferMb.coerceIn(512, 4096)
    val formats = com.unspektrawesome.settings.VideoAudioFormat.entries
    DiagramPage {
        SetupSection("Recording")
        SetupRecordingStage(preferences.videoAudioFormat == com.unspektrawesome.settings.VideoAudioFormat.AAC, buffer,
            renderer.format(if (buffer < 1024) "{value1} MB" else "{value1} GB", mapOf("value1" to bufferAmount(buffer))),
            preferences.microphoneProcessing.label)
        DiagramRow("Audio format", number = 1, divider = false) {
            DiagramValue(preferences.videoAudioFormat.label, Modifier.clearAndSetSemantics { })
        }
        Column(Modifier.selectableGroup().testTag("Video audio format choices")) {
            formats.forEach { format ->
                DiagramRadioRow(format.label, format == preferences.videoAudioFormat, translateTitle = false) {
                    onChange(preferences.copy(videoAudioFormat = format))
                }
            }
        }
        RecordingBufferSetting(preferences.recordingBufferMb) { onChange(preferences.copy(recordingBufferMb = it)) }
        MicrophoneProcessingSetting(preferences.microphoneProcessing) { onChange(preferences.copy(microphoneProcessing = it)) }
        SetupSection("Hold shutter to lock")
        val lock = preferences.recordingLock
        SetupRecordingLockStage(lock.shutter, lock.iso, lock.focus, lock.whiteBalance)
        RecordingLockSetting(lock) { onChange(preferences.copy(recordingLock = it)) }
    }
}

/** Microphone presets this phone accepts. The selected one stays listed even when the phone no longer offers it. */
@Composable
private fun MicrophoneProcessingSetting(selected: com.unspektrawesome.settings.MicrophoneProcessing,
    onChange: (com.unspektrawesome.settings.MicrophoneProcessing) -> Unit) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Set<com.unspektrawesome.settings.MicrophoneProcessing>?>(null) }
    LaunchedEffect(context) {
        available = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.unspektrawesome.settings.MicrophoneProcessing.available(context)
        }
        checked = true
    }
    DiagramRow("Microphone processing", number = 3, divider = false) {
        DiagramValue(selected.label, Modifier.clearAndSetSemantics { }, translate = true)
    }
    Column(Modifier.selectableGroup().testTag("Microphone processing choices")) {
        com.unspektrawesome.settings.MicrophoneProcessing.entries.forEach { choice ->
            val supported = available?.contains(choice) ?: true
            if (supported || choice == selected) DiagramRadioRow(choice.label, choice == selected,
                detail = if (supported) choice.detail else "Not available on this phone. Videos use Camcorder.") {
                onChange(choice)
            }
        }
    }
    if (checked) DiagramHint(if (available == null) "Allow microphone access to see which presets this phone supports."
        else "Presets come from Android and your phone maker. Some phones process several presets the same way.",
        Modifier.diagramHairline())
}

private fun bufferAmount(megabytes: Int): String =
    if (megabytes < 1024) megabytes.toString() else java.text.DecimalFormat("0.#").format(megabytes / 1024.0)

/** Chooses what a recording started by holding the shutter keeps fixed. */
@Composable
internal fun RecordingLockSetting(lock: com.unspektrawesome.video.RecordingLock,
    onChange: (com.unspektrawesome.video.RecordingLock) -> Unit) {
    Row(settingsSearchTarget("Hold shutter to lock").fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        SettingsIcon(SettingsSymbol.Lock, Modifier.padding(top = 1.dp).size(14.dp), if (lock.any) CameraColors.Accent else Diagram.Hint)
        Text(if (lock.any) "In video mode, hold the shutter for 1 second to record with the settings chosen here locked. They are released when recording stops."
            else "With everything off, holding the shutter records without a lock.",
            color = Color(0xFFCFCFCF), fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
    DiagramSwitchRow("Lock shutter speed", lock.shutter, number = 1) { onChange(lock.copy(shutter = it)) }
    // Only one of the two is a choice Android's automatic exposure cannot follow.
    val split = lock.shutter != lock.iso
    DiagramSwitchRow("Lock ISO", lock.iso, number = 2, divider = !split) { onChange(lock.copy(iso = it)) }
    if (split) Row(Modifier.fillMaxWidth().diagramHairline().padding(start = 32.dp, end = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsIcon(SettingsSymbol.Info, Modifier.padding(top = 1.dp).size(13.dp), Diagram.Hint)
        Text("Lens profiles that use Android AE lock both, because Android AE cannot lock shutter speed and ISO separately.",
            color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
    }
    DiagramSwitchRow("Lock focus", lock.focus, number = 3) { onChange(lock.copy(focus = it)) }
    DiagramSwitchRow("Lock white balance", lock.whiteBalance, number = 4) { onChange(lock.copy(whiteBalance = it)) }
}

@Composable
private fun ViewfinderCameraSettings(preferences: CameraPreferences, onChange: (CameraPreferences) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    val active = preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId }
    val requestedStream = preferences.rawPreviewStreams[active?.routeId ?: preferences.defaultLensId] ?: active?.rawStream
    val stream = requestedStream?.let { preferences.rawLayoutOverride().stream(it) }
    val edge = stream?.let { ViewfinderResolutions.selectedEdge(it, preferences.previewShortEdge, preferences.previewQuality) }
    val currentSize = if (stream != null && edge != null) ViewfinderResolutions.size(stream, edge) else null
    val currentLabel = currentSize?.let { "${it.width} × ${it.height}" }
    DiagramPage {
        SetupPartHeader("Preview quality", "Preview")
        SetupPreviewSourceStage(preferences.viewfinderSource == ViewfinderSource.RAW, currentLabel)
        val sources = ViewfinderSource.entries
        SetupChoice("Viewfinder source", sources.map { it.label }, sources.indexOf(preferences.viewfinderSource), number = 1) { index ->
            onChange(preferences.copy(viewfinderSource = sources[index]))
        }
        DiagramHint("Camera ISP shows the processed camera preview in photo mode, with your film look, effects and overlays on top. It uses less power on older phones. Photos and videos are still developed from RAW.",
            Modifier.diagramHairline())
        if (stream != null && edge != null && currentSize != null) {
            val choices = ViewfinderResolutions.choices(stream, edge)
            fun label(choice: Int): String {
                val size = ViewfinderResolutions.size(stream, choice)
                val text = "${size.width} × ${size.height}"
                return if (choice == 0) renderer.format("{size} (Full)", mapOf("size" to text)) else text
            }
            SetupResolutionStage(stream.width, stream.height,
                choices.map { ViewfinderResolutions.size(stream, it).let { size -> size.width to size.height } },
                currentSize.width to currentSize.height)
            var expanded by remember { mutableStateOf(false) }
            DiagramDropdownField("Resolution", label(edge), "Applies to all lens profiles", expanded, translateValue = false, number = 2,
                onExpand = { expanded = true }, onDismiss = { expanded = false }) {
                choices.forEach { choice ->
                    DiagramMenuItem(label(choice), selected = ViewfinderResolutions.size(stream, choice) == currentSize, translate = false) {
                        onChange(ViewfinderResolutions.applyToAllProfiles(preferences, choice))
                        expanded = false
                    }
                }
            }
        } else DiagramNote("Select a lens profile to choose a viewfinder resolution.", title = "No lens profile active")

        SetupPartHeader("Framing & controls", "Preview")
        SetupFramingStage(preferences.extendTallCropPreview, preferences.controlOpacity,
            abs(preferences.controlOpacity - DEFAULT_CONTROL_OPACITY) < .005f)
        DiagramSwitchRow("Preview behind controls", preferences.extendTallCropPreview, number = 1,
            detail = "The live image continues under the control area") {
            onChange(preferences.copy(extendTallCropPreview = it))
        }
        ControlOpacitySetting(preferences.controlOpacity) { onChange(preferences.copy(controlOpacity = it)) }

        SetupPartHeader("Focus peaking", "Camera controls")
        val loupe = preferences.focusAssist == FocusAssist.LOUPE
        val strengths = FocusPeakingStrength.entries
        val strength = strengths.indexOf(preferences.focusPeakingStrength)
        val zooms = com.unspektrawesome.settings.LoupeZoom.entries
        if (loupe) SetupLoupeStage(preferences.loupeManualFocus, preferences.loupeOnTap, zooms.indexOf(preferences.loupeZoom),
            zooms.map { it.label }, preferences.loupePeaking)
        else SetupPeakingStage(preferences.focusPeakingEnabled, strength)
        val assists = FocusAssist.entries
        SetupChoice(null, assists.map { it.label }, assists.indexOf(preferences.focusAssist), number = if (loupe) null else 1) { index ->
            onChange(preferences.copy(focusAssist = assists[index]))
        }
        DiagramHint(if (loupe)
            "Shows a magnified live view from the full-resolution RAW frame. While you focus manually, the meter fills as the view gets sharper and turns lime at the sharpest point."
            else "Highlights sharp edges across the viewfinder.", Modifier.diagramHairline())
        if (loupe) {
            DiagramSwitchRow("Loupe during manual focus", preferences.loupeManualFocus, number = 1) {
                onChange(preferences.copy(loupeManualFocus = it))
            }
            DiagramSwitchRow("Loupe on tap to focus", preferences.loupeOnTap, number = 2) {
                onChange(preferences.copy(loupeOnTap = it))
            }
            SetupChoice("Loupe zoom", zooms.map { it.label }, zooms.indexOf(preferences.loupeZoom), number = 3, translate = false) { index ->
                onChange(preferences.copy(loupeZoom = zooms[index]))
            }
            DiagramSwitchRow("Peaking in loupe", preferences.loupePeaking, number = 4) {
                onChange(preferences.copy(loupePeaking = it))
            }
            if (preferences.loupePeaking) {
                SetupChoice(null, strengths.map { it.label }, strength, number = 5) { index ->
                    onChange(preferences.copy(focusPeakingStrength = strengths[index]))
                }
            }
        } else {
            DiagramSwitchRow("Focus peaking", preferences.focusPeakingEnabled, number = 2) {
                onChange(preferences.copy(focusPeakingEnabled = it))
            }
            SetupChoice(null, strengths.map { it.label }, strength, number = 3) { index ->
                onChange(preferences.copy(focusPeakingStrength = strengths[index]))
            }
        }

        SetupPartHeader("Overlays", "Camera controls")
        SetupOverlaysStage(preferences.horizonEnabled, preferences.performanceOverlay, currentSize?.let { "${it.width}×${it.height}" })
        DiagramSwitchRow("Horizon level", preferences.horizonEnabled, number = 1) {
            onChange(preferences.copy(horizonEnabled = it))
        }
        DiagramSwitchRow("Performance overlay", preferences.performanceOverlay, number = 2) {
            onChange(preferences.copy(performanceOverlay = it))
        }
    }
}

@Composable
private fun CameraLocationSetting(enabled: Boolean, status: String, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val showSettings = enabled && status == "Location is off on this device"
    DiagramSwitchRow("Save location", enabled, number = 2, detail = if (enabled) status.takeUnless { it == "Enabled" } else null,
        toggleLabel = "Save photo location", divider = !showSettings, onChange = onChange)
    if (showSettings) Box(Modifier.fillMaxWidth().diagramHairline().padding(start = 26.dp, bottom = 4.dp)) {
        DiagramLink("Location settings") { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }
}

@Composable
internal fun SettingsChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = SettingsStyle.ChoiceShape
    Box(modifier.heightIn(min = 48.dp).clip(shape)
        .pageCardBackground(shape)
        .border(1.dp, if (selected) CameraColors.Accent else CameraColors.MenuBorder, shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (!enabled) CameraColors.Muted.copy(alpha = .4f) else if (selected) CameraColors.Accent else CameraColors.Text,
            fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ControlOpacitySetting(value: Float, onChange: (Float) -> Unit) {
    Column(settingsSearchTarget("Control opacity").fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiagramNumber(2)
            Text("Control opacity", color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            DiagramValue("{value1}%", arguments = mapOf("value1" to (value * 100).roundToInt()))
            DiagramButton("Default", Modifier.languageSemantics { language ->
                contentDescription = language.text("Reset control opacity")
            }, symbol = SettingsSymbol.Reset) { onChange(DEFAULT_CONTROL_OPACITY) }
        }
        CameraCompactSlider(value, onChange, enabled = true, thumbSize = 14.dp, requireThumb = false, minimumHeight = 40.dp,
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp).languageSemantics { language ->
                contentDescription = language.text("Control opacity")
            })
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transparent", color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
            Text("Opaque", color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun RecordingBufferSetting(value: Int, onChange: (Int) -> Unit) {
    val bounded = value.coerceIn(512, 4096)
    Column(settingsSearchTarget("Recording buffer").fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiagramNumber(2)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Recording buffer", color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
                Text("512 MB steps", color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp)
            }
            DiagramValue(if (bounded < 1024) "{value1} MB" else "{value1} GB", arguments = mapOf("value1" to bufferAmount(bounded)))
        }
        CameraCompactSlider(bounded.toFloat(), { onChange(it.roundToInt()) }, enabled = true,
            valueRange = 512f..4096f, valueStep = 512f, thumbSize = 14.dp, requireThumb = false, minimumHeight = 40.dp,
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp).testTag("Recording buffer slider").languageSemantics { language ->
                contentDescription = language.text("Recording buffer")
            })
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("{value1} MB", arguments = mapOf("value1" to 512), color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
            Text("{value1} GB", arguments = mapOf("value1" to 4), color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
internal fun BurstHistorySetting(seconds: Int, memoryBytes: Long, onChange: (Int) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    SetupSection("BURST")
    SetupBurstStage(seconds, memoryBytes)
    SetupChoice("Before shutter", (1..com.unspektrawesome.burst.BurstMemoryPlan.MAX_SECONDS).map { value ->
        renderer.format("{value} s", mapOf("value" to value))
    }, seconds - 1, number = 1, detail = "Kept before the shutter press", translate = false,
        segmentedModifier = Modifier.testTag("Burst history choices")) { onChange(it + 1) }
    Box(Modifier.fillMaxWidth().diagramHairline().padding(start = 32.dp, top = 10.dp, bottom = 10.dp)) {
        DiagramValue(if (memoryBytes > 0) "Automatic RAM · {value1} MB" else "Automatic RAM", translate = true,
            arguments = if (memoryBytes > 0) mapOf("value1" to (memoryBytes + 1024 * 1024 - 1) / (1024 * 1024)) else null)
    }
}
