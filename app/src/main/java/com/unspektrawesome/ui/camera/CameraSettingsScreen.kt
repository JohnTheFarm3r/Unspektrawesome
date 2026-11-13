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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.preview.RawImportCapability
import com.unspektrawesome.preview.RawImportStreamKey
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.withAeEngine
import com.unspektrawesome.settings.DEFAULT_CONTROL_OPACITY
import com.unspektrawesome.settings.SensorProfileCatalog
import com.unspektrawesome.settings.ViewfinderResolutions
import com.unspektrawesome.settings.rawLayoutOverride
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class CameraSettingsTab(val label: String) {
    GENERAL("General"),
    VIEWFINDER("Viewfinder"),
    VIDEO("Video"),
    BUTTONS("Buttons"),
    LENS_PROFILES("Lens Profiles"),
    ABOUT("About"),
}

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
    AmbientScreen(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("CAMERA SETUP", color = CameraColors.Muted, fontSize = 12.sp,
                            lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Box(Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .languageSemantics { language -> contentDescription = language.text("Close camera settings") },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(24.dp).border(1.dp, CameraColors.MenuBorder, CircleShape),
                                contentAlignment = Alignment.Center) {
                                SettingsIcon(SettingsSymbol.Close, Modifier.size(12.dp), CameraColors.Text)
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val maximumTabWidth = (maxWidth - 24.dp).coerceAtLeast(48.dp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(IntrinsicSize.Min)
                            .selectableGroup().padding(horizontal = 12.dp)) {
                            CameraSettingsTab.entries.forEachIndexed { index, tab ->
                                val selected = pager.currentPage == index
                                val bringIntoView = remember { BringIntoViewRequester() }
                                LaunchedEffect(selected) {
                                    if (selected) {
                                        withFrameNanos { }
                                        bringIntoView.bringIntoView()
                                    }
                                }
                                Box(Modifier.bringIntoViewRequester(bringIntoView).widthIn(max = maximumTabWidth).width(IntrinsicSize.Max)
                                    .heightIn(min = 48.dp).fillMaxHeight()
                                    .selectable(selected, role = Role.Tab) { scope.launch { pager.animateScrollToPage(index) } },
                                    contentAlignment = Alignment.Center) {
                                    Text(tab.label, color = if (selected) CameraColors.Accent else CameraColors.Muted,
                                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
                                    if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                                        .background(CameraColors.Accent))
                                }
                            }
                        }
                    }
                    SettingsDivider()
                }
            },
        ) { padding ->
            val sliderGesture = remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalCompactSettings provides true, LocalSettingsDesign provides true,
                LocalSliderGestureLock provides sliderGesture) {
                HorizontalPager(state = pager, beyondViewportPageCount = CameraSettingsTab.entries.lastIndex,
                    userScrollEnabled = !sliderGesture.value,
                    modifier = Modifier.fillMaxSize().padding(padding).testTag("Camera settings pages")) { page ->
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
    SettingsPage(scroll) {
        SettingsSection("Capture")
        SettingsCard("Auto exposure", symbol = SettingsSymbol.Aperture) {
            val engines = com.unspektrawesome.camera.exposure.AeEngine.entries
            SettingsChoices(engines.map { it.label }, engines.indexOf(preferences.aeEngine)) { index ->
                onChange(preferences.withAeEngine(engines[index]))
            }
            if (preferences.aeEngine == com.unspektrawesome.camera.exposure.AeEngine.SPEKTRA) {
                SettingsDivider()
                SettingsLabel("Metering")
                val meters = com.unspektrawesome.camera.exposure.AeMeter.entries
                SettingsChoices(meters.map { it.label }, meters.indexOf(preferences.aeMeter)) { index ->
                    onChange(preferences.copy(aeMeter = meters[index]))
                }
            }
            SettingsDivider()
            SettingsToggleRow("AE switch overlay", preferences.aeSwitchOverlay, null) {
                onChange(preferences.copy(aeSwitchOverlay = it))
            }
        }
        SettingsCard("Photo saving", symbol = SettingsSymbol.Save) {
            SettingsLabel("Process new photos")
            SettingsChoices(listOf("Process now", "Later in Gallery"), if (preferences.processPhotosLater) 1 else 0,
                Modifier.bringIntoViewRequester(photoSaving), choiceModifier = { index ->
                    Modifier.onGloballyPositioned { onGuideTarget(if (index == 0) "now" else "later", it.boundsInRoot()) }
                }) { index ->
                onChange(preferences.copy(processPhotosLater = index == 1))
            }
            SettingsDivider()
            CameraLocationSetting(preferences.saveLocation, locationStatus, onLocationEnabledChanged)
        }
        BurstHistorySetting(preferences.burstPreShutterSeconds, burstState.memoryBytes) { onChange(preferences.copy(burstPreShutterSeconds = it)) }
        SettingsSection("Camera controls")
        SettingsCard("Restart passive focus", symbol = SettingsSymbol.Focus) {
            SettingsDropdown("Interval", if (preferences.passiveFocusIntervalSeconds == 0) "Off"
                else com.unspektrawesome.language.AppLanguage.format("{value} s", mapOf("value" to preferences.passiveFocusIntervalSeconds))) { dismiss ->
                listOf(0, 2, 3, 5, 10, 15, 30, 60).forEach { seconds ->
                    SettingsMenuChoice(if (seconds == 0) "Off" else "$seconds s", preferences.passiveFocusIntervalSeconds == seconds) {
                        onChange(preferences.copy(passiveFocusIntervalSeconds = seconds))
                        dismiss()
                    }
                }
            }
        }
        SettingsCard("Mode switching", symbol = SettingsSymbol.Layers) {
            SettingsToggleRow("Swipe on viewfinder", preferences.modeSwipeEnabled,
                "Swipe across the viewfinder to change capture modes. You can also tap a mode name.") {
                onChange(preferences.copy(modeSwipeEnabled = it))
            }
        }
        SettingsCard("Control orientation", symbol = SettingsSymbol.Phone) {
            val orientations = com.unspektrawesome.settings.UiOrientation.entries
            SettingsChoices(orientations.map { it.label }, orientations.indexOf(preferences.uiOrientation),
                Modifier.testTag("Control orientation choices")) { index ->
                onChange(preferences.copy(uiOrientation = orientations[index]))
            }
            SettingsHint(preferences.uiOrientation.detail)
        }
        SettingsSection("App")
        SettingsCard("Language", symbol = SettingsSymbol.Languages) { LanguageSettingsControls() }
        if (onShowGuide != null || onShowProcessingGuide != null || onShowVideoGuide != null) {
            SettingsCard("Guides", symbol = SettingsSymbol.Book) {
                if (onShowGuide != null) SettingsAction("Show camera guide", onClick = onShowGuide)
                if (onShowGuide != null && onShowProcessingGuide != null) SettingsDivider()
                if (onShowProcessingGuide != null) SettingsAction("Show processing guide", onClick = onShowProcessingGuide)
                if (onShowVideoGuide != null) {
                    if (onShowGuide != null || onShowProcessingGuide != null) SettingsDivider()
                    SettingsAction("Show video guide", onClick = onShowVideoGuide)
                }
            }
        }
    }
}

@Composable
private fun VideoCameraSettings(preferences: CameraPreferences, onChange: (CameraPreferences) -> Unit) {
    SettingsPage {
        SettingsSection("Recording")
        SettingsCard("Audio", symbol = SettingsSymbol.Volume) {
            val formats = com.unspektrawesome.settings.VideoAudioFormat.entries
            SettingsLabel("Audio format")
            SettingsChoices(formats.map { it.label }, formats.indexOf(preferences.videoAudioFormat),
                Modifier.testTag("Video audio format choices")) { index ->
                onChange(preferences.copy(videoAudioFormat = formats[index]))
            }
        }
        SettingsCard("Video recording", symbol = SettingsSymbol.Video) {
            RecordingBufferSetting(preferences.recordingBufferMb) { onChange(preferences.copy(recordingBufferMb = it)) }
        }
        SettingsCard("Hold shutter to lock", symbol = SettingsSymbol.Lock) {
            RecordingLockSetting(preferences.recordingLock) { onChange(preferences.copy(recordingLock = it)) }
        }
    }
}

/** Chooses what a recording started by holding the shutter keeps fixed. */
@Composable
internal fun RecordingLockSetting(lock: com.unspektrawesome.video.RecordingLock,
    onChange: (com.unspektrawesome.video.RecordingLock) -> Unit) {
    SettingsHint(if (lock.any) "In video mode, hold the shutter for 1 second to record with the settings chosen here locked. They are released when recording stops."
        else "With everything off, holding the shutter records without a lock.")
    SettingsToggleRow("Lock shutter speed", lock.shutter, null) { onChange(lock.copy(shutter = it)) }
    SettingsDivider()
    SettingsToggleRow("Lock ISO", lock.iso, null) { onChange(lock.copy(iso = it)) }
    // Only one of the two is a choice Android's automatic exposure cannot follow.
    if (lock.shutter != lock.iso) SettingsHint(
        "Lens profiles that use Android AE lock both, because Android AE cannot lock shutter speed and ISO separately.")
    SettingsDivider()
    SettingsToggleRow("Lock focus", lock.focus, null) { onChange(lock.copy(focus = it)) }
    SettingsDivider()
    SettingsToggleRow("Lock white balance", lock.whiteBalance, null) { onChange(lock.copy(whiteBalance = it)) }
}

@Composable
private fun ViewfinderCameraSettings(preferences: CameraPreferences, onChange: (CameraPreferences) -> Unit) {
    SettingsPage {
        SettingsSection("Preview")
        SettingsCard("Preview quality", symbol = SettingsSymbol.Monitor) {
            val sources = com.unspektrawesome.settings.ViewfinderSource.entries
            SettingsLabel("Viewfinder source")
            SettingsChoices(sources.map { it.label }, sources.indexOf(preferences.viewfinderSource)) { index ->
                onChange(preferences.copy(viewfinderSource = sources[index]))
            }
            SettingsHint("Camera ISP shows the processed camera preview in photo mode, with your film look, effects and overlays on top. It uses less power on older phones. Photos and videos are still developed from RAW.")
            SettingsDivider()
            val active = preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId }
            val requestedStream = preferences.rawPreviewStreams[active?.routeId ?: preferences.defaultLensId] ?: active?.rawStream
            val stream = requestedStream?.let { preferences.rawLayoutOverride().stream(it) }
            if (stream != null) {
                val edge = ViewfinderResolutions.selectedEdge(stream, preferences.previewShortEdge, preferences.previewQuality)
                SettingsDropdown("Resolution", ViewfinderResolutions.label(stream, edge)) { dismiss ->
                    ViewfinderResolutions.choices(stream, edge).forEach { choice ->
                        SettingsMenuChoice(ViewfinderResolutions.label(stream, choice),
                            selected = ViewfinderResolutions.size(stream, choice) == ViewfinderResolutions.size(stream, edge)) {
                            onChange(ViewfinderResolutions.applyToAllProfiles(preferences, choice))
                            dismiss()
                        }
                    }
                }
            } else SettingsHint("Select a lens profile to choose a viewfinder resolution.")
        }
        SettingsCard("Framing & controls", symbol = SettingsSymbol.Sliders) {
            SettingsToggleRow("Preview behind controls", preferences.extendTallCropPreview, null) {
                onChange(preferences.copy(extendTallCropPreview = it))
            }
            SettingsDivider()
            ControlOpacitySetting(preferences.controlOpacity) { onChange(preferences.copy(controlOpacity = it)) }
        }
        SettingsSection("Camera controls")
        SettingsCard("Focus peaking", symbol = SettingsSymbol.Focus) {
            val assists = com.unspektrawesome.settings.FocusAssist.entries
            SettingsChoices(assists.map { it.label }, assists.indexOf(preferences.focusAssist)) { index ->
                onChange(preferences.copy(focusAssist = assists[index]))
            }
            SettingsHint(if (preferences.focusAssist == com.unspektrawesome.settings.FocusAssist.LOUPE)
                "Shows a magnified live view from the full-resolution RAW frame. While you focus manually, the meter fills as the view gets sharper and turns lime at the sharpest point."
                else "Highlights sharp edges across the viewfinder.")
            SettingsDivider()
            val strengths = com.unspektrawesome.settings.FocusPeakingStrength.entries
            if (preferences.focusAssist == com.unspektrawesome.settings.FocusAssist.LOUPE) {
                SettingsToggleRow("Loupe during manual focus", preferences.loupeManualFocus, null) {
                    onChange(preferences.copy(loupeManualFocus = it))
                }
                SettingsDivider()
                SettingsToggleRow("Loupe on tap to focus", preferences.loupeOnTap, null) {
                    onChange(preferences.copy(loupeOnTap = it))
                }
                SettingsDivider()
                SettingsLabel("Loupe zoom")
                val zooms = com.unspektrawesome.settings.LoupeZoom.entries
                SettingsChoices(zooms.map { it.label }, zooms.indexOf(preferences.loupeZoom)) { index ->
                    onChange(preferences.copy(loupeZoom = zooms[index]))
                }
                SettingsDivider()
                SettingsToggleRow("Peaking in loupe", preferences.loupePeaking, null) {
                    onChange(preferences.copy(loupePeaking = it))
                }
                if (preferences.loupePeaking) {
                    SettingsChoices(strengths.map { it.label }, strengths.indexOf(preferences.focusPeakingStrength)) { index ->
                        onChange(preferences.copy(focusPeakingStrength = strengths[index]))
                    }
                }
            } else {
                SettingsToggleRow("Focus peaking", preferences.focusPeakingEnabled, null) {
                    onChange(preferences.copy(focusPeakingEnabled = it))
                }
                SettingsDivider()
                SettingsChoices(strengths.map { it.label }, strengths.indexOf(preferences.focusPeakingStrength)) { index ->
                    onChange(preferences.copy(focusPeakingStrength = strengths[index]))
                }
            }
        }
        SettingsCard("Overlays", symbol = SettingsSymbol.Overlays) {
            SettingsToggleRow("Horizon level", preferences.horizonEnabled, null) {
                onChange(preferences.copy(horizonEnabled = it))
            }
            SettingsDivider()
            SettingsToggleRow("Performance overlay", preferences.performanceOverlay, null) {
                onChange(preferences.copy(performanceOverlay = it))
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    detail: String?,
    toggleLabel: String = label,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsLabel(label)
            detail?.let { SettingsHint(it) }
        }
        CameraOverlayToggle(checked, toggleLabel, onChange)
    }
}

@Composable
private fun SettingsAction(label: String, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsLabel(label)
            detail?.let { SettingsHint(it) }
        }
        CameraGlyphIcon(CameraGlyph.Chevron, Modifier.size(16.dp), CameraColors.Accent)
    }
}

@Composable
private fun CameraLocationSetting(enabled: Boolean, status: String, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsToggleRow("Save location", enabled,
            if (enabled) status.takeUnless { it == "Enabled" } else null,
            toggleLabel = "Save photo location", onChange = onChange)
        if (enabled && status == "Location is off on this device") {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text("Location settings", color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
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
private fun SettingsDropdown(
    label: String,
    value: String,
    enabled: Boolean = true,
    content: @Composable (() -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsDropdownField(label, value, null, expanded, enabled,
        onExpand = { expanded = true }, onDismiss = { expanded = false }) {
        content { expanded = false }
    }
}

@Composable
private fun ControlOpacitySetting(value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SettingsLabel("Control opacity") }
            Text("{value1}%", arguments = mapOf("value1" to ((value * 100).roundToInt())), color = CameraColors.Accent, fontSize = 14.sp,
                lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        }
        CameraCompactSlider(value, onChange, enabled = true, thumbSize = 16.dp, requireThumb = false,
            modifier = Modifier.fillMaxWidth().languageSemantics { language -> contentDescription = language.text("Control opacity") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transparent", color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.weight(1f))
            Text("Opaque", color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        SettingsButton("Default", Modifier.fillMaxWidth().languageSemantics { language ->
            contentDescription = language.text("Reset control opacity")
        }, symbol = SettingsSymbol.Reset) { onChange(DEFAULT_CONTROL_OPACITY) }
    }
}

@Composable
internal fun RecordingBufferSetting(value: Int, onChange: (Int) -> Unit) {
    val bounded = value.coerceIn(512, 4096)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        SettingsLabel("Recording buffer")
        Text(if (bounded < 1024) "{value1} MB" else "{value1} GB",
            arguments = mapOf("value1" to if (bounded < 1024) bounded.toString()
                else java.text.DecimalFormat("0.#").format(bounded / 1024.0)),
            color = CameraColors.Accent, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    }
    CameraCompactSlider(bounded.toFloat(), { onChange(it.roundToInt()) }, enabled = true,
        valueRange = 512f..4096f, valueStep = 512f, thumbSize = 16.dp, requireThumb = false,
        modifier = Modifier.fillMaxWidth().testTag("Recording buffer slider").languageSemantics { language ->
            contentDescription = language.text("Recording buffer")
        })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("{value1} MB", arguments = mapOf("value1" to 512), color = CameraColors.Muted, fontSize = 11.sp)
        Text("{value1} GB", arguments = mapOf("value1" to 4), color = CameraColors.Muted, fontSize = 11.sp)
    }
}

@Composable
internal fun BurstHistorySetting(seconds: Int, memoryBytes: Long, onChange: (Int) -> Unit) {
    SettingsCard("BURST", symbol = SettingsSymbol.Layers) {
        SettingsLabel("Before shutter")
        SettingsChoices((1..com.unspektrawesome.burst.BurstMemoryPlan.MAX_SECONDS).map { value ->
            com.unspektrawesome.language.AppLanguage.format("{value} s", mapOf("value" to value))
        }, seconds - 1, Modifier.testTag("Burst history choices")) { onChange(it + 1) }
        SettingsDivider()
        Text(if (memoryBytes > 0) "Automatic RAM · {value1} MB" else "Automatic RAM",
            arguments = mapOf("value1" to (memoryBytes + 1024 * 1024 - 1) / (1024 * 1024)),
            color = CameraColors.Accent, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
