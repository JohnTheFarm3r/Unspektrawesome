package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.customActions
import com.unspektrawesome.camera.exposure.AeLimits
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.exposure.ExposureBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.preview.RawImportCapability
import com.unspektrawesome.preview.RawImportCapabilityState
import com.unspektrawesome.preview.RawImportStreamKey
import com.unspektrawesome.settings.*
import java.util.Locale

@Composable
internal fun LensProfileSettings(
    profiles: List<ConfiguredSensorProfile>,
    preferences: CameraPreferences,
    rawImportCapabilities: Map<RawImportStreamKey, RawImportCapability>,
    onChange: (CameraPreferences) -> Unit,
    isActive: Boolean = true,
    onAutoLensDiscovery: (() -> Unit)? = null,
    onCheckLensFrameRates: LensFrameRateCheck? = null,
) {
    CompositionLocalProvider(LocalCompactSettings provides true, LocalSettingsDesign provides true) {
        LensProfileSettingsContent(profiles, preferences, rawImportCapabilities, onChange, isActive,
            onAutoLensDiscovery, onCheckLensFrameRates)
    }
}

@Composable
private fun LensProfileSettingsContent(
    profiles: List<ConfiguredSensorProfile>,
    preferences: CameraPreferences,
    rawImportCapabilities: Map<RawImportStreamKey, RawImportCapability>,
    onChange: (CameraPreferences) -> Unit,
    isActive: Boolean,
    onAutoLensDiscovery: (() -> Unit)?,
    onCheckLensFrameRates: LensFrameRateCheck?,
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    // Photo and video keep separate profile lists. The list opens on the capture mode in use.
    var mode by rememberSaveable { mutableStateOf(preferences.lensProfileMode) }
    val sensors = profiles.associateBy { it.profile.id.value }
    val transfer = rememberLensProfileTransfer(preferences, mode, onChange)
    val scroll = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val renderer = LocalAppLanguage.current.renderer
    var currentPreferences by remember { mutableStateOf(preferences) }
    currentPreferences = preferences
    val change by rememberUpdatedState(onChange)
    val modeProfiles = preferences.lensProfiles.filter { it.mode == mode }
    val ids = modeProfiles.map { it.id }
    val reorder = remember(ids.toSet(), scroll) {
        LensProfileReorderState(ids, scroll, { change(LensProfiles.reorder(currentPreferences, it)) },
            { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) })
    }
    LaunchedEffect(ids) { reorder.sync(ids) }
    AnimateLensProfileReordering(reorder)
    val orderedProfiles = reorder.order.mapNotNull { id -> preferences.lensProfiles.firstOrNull { it.id == id } }
    CameraPageTransition(editingId, Modifier.fillMaxSize(), order = { if (it == null) 0 else 1 }) { targetId ->
    val editing = preferences.lensProfiles.firstOrNull { it.id == targetId }
    if (editing != null && sensors[editing.sensorId] != null) {
        LensProfileEditor(editing, sensors.getValue(editing.sensorId).profile, rawImportCapabilities,
            isActive = isActive && editingId == targetId, onCheckLensFrameRates = onCheckLensFrameRates,
            onClose = { editingId = null }, onChange = { updated ->
                val next = currentPreferences.copy(lensProfiles = currentPreferences.lensProfiles.map {
                    if (it.id == updated.id) updated else it
                })
                currentPreferences = next
                change(next)
            })
    } else {
        val allPaths = profiles.flatMap { it.profile.paths }
        fun pathOf(profile: LensProfile) =
            sensors[profile.sensorId]?.profile?.paths?.firstOrNull { it.route.routeId == profile.routeId }
        LazyColumn(Modifier.fillMaxSize().testTag("Lens profile list").reorderLensProfiles(reorder) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }, state = scroll, userScrollEnabled = reorder.draggedKey == null,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "lens-profile-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LensSwitcherStage(orderedProfiles.map { profile ->
                        LensSwitcherEntry(profile.displayName,
                            renderer.text(pathOf(profile)?.let(profile.focalLength::label) ?: "Lens"), profile.enabled,
                            LensProfiles.available(profile, allPaths), preferences.activeLensProfileId == profile.id,
                            preferences.defaultLensProfileId(mode) == profile.id)
                    })
                    LensProfilesHeader {
                        if (onAutoLensDiscovery != null) {
                            DiagramButton("Auto Lens Discovery", Modifier.fillMaxWidth(), symbol = SettingsSymbol.Discover,
                                accent = true, onClick = onAutoLensDiscovery)
                        }
                        DiagramButtonRow(Modifier.padding(vertical = 0.dp)) {
                            DiagramButton("Export", Modifier.weight(1f), enabled = !transfer.busy && modeProfiles.isNotEmpty(),
                                symbol = SettingsSymbol.Export, onClick = transfer.exportProfiles)
                            DiagramButton("Import", Modifier.weight(1f), enabled = !transfer.busy,
                                symbol = SettingsSymbol.Import, onClick = transfer.importProfile)
                        }
                        if (transfer.busy) CircularProgressIndicator(Modifier.padding(4.dp).size(20.dp), color = CameraColors.Accent, strokeWidth = 2.dp)
                        if (modeProfiles.isEmpty()) DiagramHint("No lens profiles. Run discovery or add a profile manually.")
                        var adding by remember { mutableStateOf(false) }
                        DiagramDropdownField("+ Add profile", "Lens", null, adding, enabled = profiles.isNotEmpty(),
                            onExpand = { adding = true }, onDismiss = { adding = false }) {
                            profiles.forEach { configured ->
                                val camera = configured.profile.primary
                                val focal = camera.equivalentFocalLengthMm?.toInt()?.let { "${it}mm" } ?: renderer.text(camera.lensRole.label)
                                DiagramMenuItem(renderer.format("{focal} · Lens ID {id}", mapOf("focal" to focal, "id" to camera.route.routeId)),
                                    role = Role.Button, translate = false) {
                                    val updated = LensProfiles.add(preferences, configured.profile, mode)
                                    onChange(updated)
                                    editingId = updated.lensProfiles.lastOrNull()?.id
                                    adding = false
                                }
                            }
                        }
                    }
                    // Photo and video keep separate profiles. The choice sits directly above the list it switches.
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        DiagramSegmented(listOf("Photo", "Video"), mode.ordinal, Modifier.testTag("Lens profile modes")) {
                            mode = LensProfileMode.entries[it]
                        }
                        DiagramHint(if (mode == LensProfileMode.VIDEO) "These profiles appear in video mode."
                            else "These profiles appear in photo mode.", Modifier.padding(top = 0.dp))
                    }
                    DiagramSection("Hold and drag to reorder", Modifier.padding(top = 0.dp))
                }
            }
            itemsIndexed(orderedProfiles, key = { _, profile -> profile.id }) { index, profile ->
                key(profile.id) {
                    var menu by remember { mutableStateOf(false) }
                    val available = LensProfiles.available(profile, allPaths)
                    val output = pathOf(profile)?.let { LensProfiles.resolveRawOutput(profile.rawStream, it, profile.rawSession) }
                    val active = preferences.activeLensProfileId == profile.id
                    val startup = preferences.defaultLensProfileId(mode) == profile.id
                    val moving = reorder.draggedKey == profile.id
                    val lifted = moving || reorder.settlingKey == profile.id
                    Surface(color = Color.Transparent,
                        border = BorderStroke(if (lifted) 2.dp else 1.dp, if (active || lifted) CameraColors.Accent else Diagram.Border),
                        shape = Diagram.ControlShape, modifier = Modifier.fillMaxWidth()
                            .animateItem(placementSpec = if (lifted) null else tween(180))
                            .zIndex(if (lifted) 1f else 0f).graphicsLayer { translationY = reorder.translation(profile.id) }
                            .pageCardBackground(Diagram.ControlShape).background(Diagram.Glass, Diagram.ControlShape)
                            .testTag("Lens profile ${profile.id}").semantics { customActions = reorder.accessibilityActions(profile.id) }) {
                        Box {
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsIcon(SettingsSymbol.Grip, Modifier.size(16.dp), Color(0xFF8C8C8C))
                            DiagramNumber(index + 1, active = available && profile.enabled)
                            Column(Modifier.weight(1f).clickable(enabled = available, role = Role.Button) { editingId = profile.id }
                                .padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(profile.displayName, translate = false, color = if (active) CameraColors.Accent else CameraColors.Text,
                                    fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOf("Lens ID ${profile.routeId}", profile.rawStream.format.name,
                                    "${profile.rawStream.width}×${profile.rawStream.height}").joinToString(" · "),
                                    color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono)
                                if (!available) ProfileStatus("Lens unavailable", SettingsSymbol.Info, false)
                                else if (output == null) ProfileStatus("Saved stream unavailable.", SettingsSymbol.Info, false)
                                if (available && (active || startup)) ProfileStatus(
                                    when { active && startup -> "Current / startup profile"; startup -> "Startup profile"; else -> "Current profile" },
                                    SettingsSymbol.Startup, active)
                            }
                            Checkbox(checked = profile.enabled, enabled = available || profile.enabled,
                                onCheckedChange = { enabled -> onChange(preferences.copy(lensProfiles = preferences.lensProfiles.map { if (it.id == profile.id) it.copy(enabled = enabled) else it })) },
                                modifier = Modifier.size(44.dp, 48.dp).languageSemantics { language -> contentDescription = language.format("Show {name} in lens switcher", mapOf("name" to (profile.name))) },
                                colors = CheckboxDefaults.colors(checkedColor = CameraColors.Accent,
                                    checkmarkColor = Color.Black, uncheckedColor = Color(0xFF6A6A6A)))
                            Box {
                                Box(Modifier.size(44.dp, 48.dp).clip(Diagram.ControlShape)
                                    .clickable(role = Role.Button) { menu = true }
                                    .languageSemantics { language -> contentDescription = language.format("Actions for {name}", mapOf("name" to (profile.name))) },
                                    contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(28.dp).border(1.dp, if (menu) CameraColors.Accent else Color.Transparent,
                                        RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                        SettingsIcon(SettingsSymbol.More, Modifier.size(16.dp), if (menu) CameraColors.Accent else Color(0xFFCFCFCF))
                                    }
                                }
                                DiagramMenu(menu, { menu = false }, minWidth = 192.dp) {
                                    fun close(action: () -> Unit) { menu = false; action() }
                                    DiagramMenuItem("Edit", enabled = available, symbol = SettingsSymbol.Edit, role = Role.Button) { close { editingId = profile.id } }
                                    DiagramMenuItem("Rename", symbol = SettingsSymbol.Rename, role = Role.Button) { close { renamingId = profile.id } }
                                    DiagramMenuItem("Duplicate", symbol = SettingsSymbol.Duplicate, role = Role.Button) { close { onChange(LensProfiles.duplicate(preferences, profile.id)) } }
                                    DiagramMenuItem("Set as startup", startup, enabled = available && profile.enabled, symbol = SettingsSymbol.Startup) { close {
                                        onChange(LensProfiles.setDefault(preferences, profile.id))
                                    } }
                                    DiagramMenuItem("Delete", enabled = LensProfiles.canDelete(preferences, profile.id), symbol = SettingsSymbol.Delete,
                                        role = Role.Button) { close { onChange(LensProfiles.delete(preferences, profile.id)) } }
                                }
                            }
                        }
                        if (moving) Text("↕", color = CameraColors.Accent, fontSize = 28.sp,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 100.dp)
                                .testTag("Moving lens profile").languageSemantics { language -> contentDescription = language.text("Move up or down. Release to save order.") })
                        }
                    }
                }
            }
        }
    }
    }
    preferences.lensProfiles.firstOrNull { it.id == renamingId }?.let { profile ->
        var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
        DiagramDialog("Rename profile", onDismiss = { renamingId = null }, buttons = {
            DiagramButton("Cancel") { renamingId = null }
            DiagramButton("Save", enabled = name.isNotBlank(), accent = true) {
                onChange(preferences.copy(lensProfiles = preferences.lensProfiles.map { if (it.id == profile.id)
                    it.copy(name = name.trim(), autoManaged = profile.autoManaged && name.trim() == profile.name) else it }))
                renamingId = null
            }
        }) {
            CameraWindowBackdrop()
            ProfileNameField(name) { name = it }
        }
    }
}

/** The card at the top of the list that holds discovery, transfer and the add menu. */
@Composable
private fun LensProfilesHeader(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Diagram.ControlShape).background(Diagram.Glass)
        .border(1.dp, Diagram.Border, Diagram.ControlShape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(22.dp).border(1.dp, Diagram.Border, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                SettingsIcon(SettingsSymbol.Discover, Modifier.size(12.dp), CameraColors.Accent)
            }
            Text("LENS PROFILES", color = CameraColors.Text, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        }
        content()
    }
}

@Composable
private fun ProfileStatus(text: String, symbol: SettingsSymbol, active: Boolean) {
    Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsIcon(symbol, Modifier.size(12.dp), if (active) CameraColors.Accent else Diagram.Hint)
        Text(text, color = if (active) CameraColors.Accent else Diagram.Hint, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun ProfileNameField(name: String, onChange: (String) -> Unit) {
    SettingsTextField(name, onChange, "Profile name",
        error = if (name.isBlank()) "Enter a profile name. The saved name is kept until then." else null)
}

@Composable
private fun LensProfileEditor(
    profile: LensProfile,
    sensor: PhysicalSensorProfile,
    capabilities: Map<RawImportStreamKey, RawImportCapability>,
    onClose: () -> Unit,
    onChange: (LensProfile) -> Unit,
    isActive: Boolean = true,
    onCheckLensFrameRates: LensFrameRateCheck? = null,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var route by rememberSaveable(profile.id) { mutableStateOf(profile.routeId) }
    var stream by remember(profile.id) { mutableStateOf(profile.rawStream) }
    var rawEdgeCrop by remember(profile.id) { mutableStateOf<RawEdgeCrop?>(profile.rawEdgeCrop) }
    var vendorOverrides by rememberSaveable(profile.id, stateSaver = Saver<VendorOverrides, String>(
        save = { it.toJson().toString() }, restore = { VendorOverrides.fromJson(org.json.JSONObject(it)) },
    )) { mutableStateOf(profile.vendorOverrides) }
    var cameraIdExpanded by remember { mutableStateOf(false) }
    var rawExpanded by remember { mutableStateOf(false) }
    val camera = sensor.paths.firstOrNull { it.route.routeId == route } ?: sensor.primary
    val context = LocalContext.current
    val renderer = LocalAppLanguage.current.renderer
    val exposureBounds = remember(camera) { readExposureBounds(context, camera) }
    val photoOisSupported = remember(context, camera) {
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            val selected = manager.getCameraCharacteristics(camera.route.characteristicsCameraId)
            val modes = selected.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?: manager.getCameraCharacteristics(camera.route.openCameraId)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            modes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        }.getOrDefault(false)
    }
    val outputs = (camera.rawOutputs + listOfNotNull(LensProfiles.resolveRawOutput(stream, camera, profile.rawSession))).distinct()
    val sortedOutputs = outputs.sortedWith(compareByDescending<RawOutput> { it.size.area() }.thenBy { it.format.name })
    val selected = outputs.firstOrNull(stream::matches)
    var currentProfile by remember { mutableStateOf(profile) }
    currentProfile = profile
    val change by rememberUpdatedState(onChange)
    fun update(transform: (LensProfile) -> LensProfile) {
        val updated = transform(currentProfile)
        if (updated != currentProfile) {
            currentProfile = updated.copy(autoManaged = false)
            change(currentProfile)
        }
    }
    fun supported(routeId: String, raw: RawStreamSelection, overrides: VendorOverrides,
        layout: com.unspektrawesome.settings.RawLayoutOverride = currentProfile.rawLayoutOverride): Boolean {
        val path = sensor.paths.firstOrNull { it.route.routeId == routeId } ?: return false
        val output = LensProfiles.resolveRawOutput(raw, path, currentProfile.rawSession) ?: return false
        return layout.validationError(output.format, output.size) == null &&
            capabilities[RawImportStreamKey.from(routeId, output, overrides.active(), layout, currentProfile.capture, currentProfile.rawSession)]?.state != RawImportCapabilityState.INCOMPATIBLE
    }
    fun updateRawConfiguration(nextRoute: String = route, nextStream: RawStreamSelection = stream,
                               nextCrop: RawEdgeCrop? = rawEdgeCrop) {
        route = nextRoute
        stream = nextStream
        rawEdgeCrop = nextCrop
        if (nextCrop?.isValid(currentProfile.rawLayoutOverride.size(com.unspektrawesome.camera.Size2d(nextStream.width, nextStream.height))) == true &&
            supported(nextRoute, nextStream, currentProfile.vendorOverrides)) {
            update { it.copy(routeId = nextRoute, rawStream = nextStream, rawEdgeCrop = nextCrop,
                verification = it.verification.takeIf { _ -> nextRoute == it.routeId && nextStream == it.rawStream }) }
        }
    }
    fun streamSummary(output: RawOutput): String = renderer.format("{megapixels} MP · {bits} bits per pixel", mapOf(
        "megapixels" to String.format(Locale.ROOT, "%.1f", output.size.width.toDouble() * output.size.height / 1_000_000.0),
        "bits" to output.format.storageBitsPerPixel))
    BackHandler(enabled = isActive, onBack = onClose)
    DiagramPage {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            DiagramButton("Back", modifier = Modifier.testTag("Back to lens profiles"), symbol = SettingsSymbol.Back, onClick = onClose)
            Text("EDIT PROFILE", color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold)
            Text("{name} · {mode}", arguments = mapOf("name" to profile.displayName,
                "mode" to if (profile.mode == LensProfileMode.VIDEO) "Video" else "Photo"),
                color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        LensProfileOverviewStage(profile, sensor, camera, stream)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileNameField(name) { value ->
                name = value
                if (value.isNotBlank()) update { it.copy(name = value.trim()) }
            }
            if (name.isNotBlank()) Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsIcon(SettingsSymbol.Saved, Modifier.size(14.dp), Diagram.Hint)
                Text("Saved automatically", color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        LensSectionHeading(1, "Sensor input")
        RawStreamDataStage(sortedOutputs, stream) { supported(route, RawStreamSelection.from(it), vendorOverrides) }
        DiagramDropdownField("CAMERA ID", cameraIdLabel(camera), null, cameraIdExpanded, labelWidth = 96.dp,
            onExpand = { cameraIdExpanded = true }, onDismiss = { cameraIdExpanded = false }) {
            sensor.paths.forEach { candidate ->
                DiagramMenuItem(cameraIdLabel(candidate), selected = route == candidate.route.routeId, detail = cameraIdDetail(candidate)) {
                    if (route != candidate.route.routeId) {
                        updateRawConfiguration(nextRoute = candidate.route.routeId)
                    }
                    cameraIdExpanded = false
                }
            }
        }
        DiagramDropdownField("RAW STREAM", rawOutputLabel(stream),
            if (selected != null) outputCapabilityDetail(camera, selected, capabilities, vendorOverrides.active(), profile.rawLayoutOverride, profile.capture, profile.rawSession)
            else "Saved preference, temporarily unavailable", rawExpanded, translateValue = false, labelWidth = 96.dp,
            onExpand = { rawExpanded = true }, onDismiss = { rawExpanded = false }) {
            if (selected == null) DiagramMenuItem(rawOutputLabel(stream), selected = true,
                detail = renderer.text("Saved preference, temporarily unavailable"), translate = false) {
                rawExpanded = false
            }
            sortedOutputs.forEach { output ->
                val enabled = supported(route, RawStreamSelection.from(output), vendorOverrides)
                val detail = outputCapabilityDetail(camera, output, capabilities, vendorOverrides.active(), profile.rawLayoutOverride, profile.capture, profile.rawSession)
                DiagramMenuItem(rawOutputLabel(output), stream.matches(output), detail?.let(renderer::text) ?: streamSummary(output),
                    enabled, translate = false) {
                    updateRawConfiguration(nextStream = RawStreamSelection.from(output)); rawExpanded = false
                }
            }
        }
        if (selected == null) DiagramHint("Saved stream unavailable.")
        if (!supported(route, stream, profile.vendorOverrides)) DiagramHint(
            "This RAW configuration cannot be saved. Choose a compatible camera and RAW stream.", warning = true)
        LensSectionHeading(2, "Capture", tag = if (profile.mode == LensProfileMode.PHOTO) "Photo profile" else "Video profile")
        CaptureStabilizationStage(profile, photoOisSupported)
        if (profile.mode == LensProfileMode.PHOTO) PhotoOisControl(profile.photoOisEnabled, photoOisSupported) { enabled ->
            update { it.copy(photoOisEnabled = enabled) }
        } else VideoStabilizationControls(profile.videoStabilization, photoOisSupported) { stabilization ->
            update { it.copy(videoStabilization = stabilization) }
        }
        LensCaptureControls(camera, profile, onCheckLensFrameRates, isActive = isActive) { updated -> update { updated } }
        LensSectionHeading(7, "Preview")
        val decodedStream = profile.rawLayoutOverride.stream(stream)
        ViewfinderResolutionField(decodedStream,
            ViewfinderResolutions.selectedEdge(decodedStream, profile.previewShortEdge, profile.previewQuality)) { edge ->
            update { it.copy(previewShortEdge = edge) }
        }
        LensSectionHeading(8, "Auto exposure")
        val engines = listOf(AeEngine.SPEKTRA, AeEngine.ANDROID)
        DiagramSegmented(engines.map { if (it == AeEngine.ANDROID) "Android AE" else it.label },
            engines.indexOf(profile.aeEngine)) { index -> update { it.copy(aeEngine = engines[index]) } }
        if (profile.aeEngine == AeEngine.SPEKTRA) {
            AeLimitsEditor(profile.aeLimits, exposureBounds) { limits ->
                update { it.copy(aeLimits = limits) }
            }
        }
        LensSectionHeading(9, "RAW adjustments")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RawCenterCropEditor(profile.rawEdgeCrop, profile.rawLayoutOverride.size(com.unspektrawesome.camera.Size2d(stream.width, stream.height))) {
                updateRawConfiguration(nextCrop = it)
            }
            RawLayoutOverrideEditor(profile.rawLayoutOverride, stream, profile.rawEdgeCrop) { layout ->
                if (supported(route, stream, currentProfile.vendorOverrides, layout)) {
                    update { it.copy(routeId = route, rawStream = stream, rawLayoutOverride = layout,
                        verification = it.verification.takeIf { _ -> route == it.routeId && stream == it.rawStream }) }
                }
            }
            CfaOverrideEditor(profile.cfaOverride) { value -> update { it.copy(cfaOverride = value) } }
            RawEdgeCropEditor(profile.rawEdgeCrop, profile.rawLayoutOverride.size(com.unspektrawesome.camera.Size2d(stream.width, stream.height))) {
                updateRawConfiguration(nextCrop = it)
            }
            Column(Modifier.fillMaxWidth()) {
                VendorOverridesEditor(vendorOverrides, camera.route) { overrides ->
                    vendorOverrides = overrides
                    if (overrides.validationError() == null && supported(currentProfile.routeId, currentProfile.rawStream, overrides)) {
                        update { it.copy(vendorOverrides = overrides) }
                    }
                }
                if (vendorOverrides != profile.vendorOverrides) DiagramHint(
                    "Vendor changes are not saved yet. Complete the entries or turn overrides off to keep them without applying them.",
                    warning = true)
            }
        }
        LensSectionHeading(10, "Color calibration")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RawLevelCalibrationEditor(profile.rawLevels, camera.sensorMetadata) { levels ->
                if (levels?.isValid() == true) update {
                    it.copy(rawLevels = if (levels.mode == it.rawLevels.mode && levels.mode != RawLevelMode.CUSTOM) it.rawLevels else levels)
                }
            }
            LensColorCalibrationEditor(profile.color, camera.sensorMetadata.colorCalibration.hasUsableColorTransform(),
                camera.lensShadingMapAvailable, camera.lensShadingAppliedInRaw,
                onChange = { color -> update { it.copy(color = color) } }, onImporting = {})
        }
    }
}

@Composable
internal fun PhotoOisControl(enabled: Boolean, supported: Boolean, onChange: (Boolean) -> Unit) {
    DiagramSwitchRow("Photo OIS", enabled && supported, detail = "OIS is unavailable for this lens".takeUnless { supported },
        enabled = supported, onChange = onChange)
}

/**
 * Stabilization a video profile starts with. It follows the video menu: gyro capture turns OIS off, and live
 * stabilization replaces both with its own settings.
 */
@Composable
internal fun VideoStabilizationControls(value: VideoStabilization, oisSupported: Boolean, onChange: (VideoStabilization) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("Video profile stabilization")) {
        DiagramHint("This profile starts with these settings. Changes in the video menu last until you switch profiles or close the app.")
        if (!value.liveStabilization) {
            if (!value.captureGyro) DiagramSwitchRow("OIS", value.ois && oisSupported,
                detail = "OIS is unavailable for this lens".takeUnless { oisSupported }, enabled = oisSupported) {
                onChange(value.copy(ois = it))
            }
            DiagramSwitchRow("Capture gyro data", value.captureGyro, detail = "Save gyro data with video") {
                onChange(value.copy(captureGyro = it))
            }
        }
        DiagramSwitchRow("Live gyro stabilization", value.liveStabilization) { onChange(value.copy(liveStabilization = it)) }
        if (value.liveStabilization) {
            val settings = value.settings
            val defaults = com.unspektrawesome.video.LiveStabilizationSettings()
            StabilizationSetting("Crop", "Stabilization crop", "{value1}x", 2, settings.cropRatio, 1f..1.5f,
                defaults.cropRatio) { onChange(value.copy(settings = settings.copy(cropRatio = it))) }
            StabilizationSetting("Smoothing", "Stabilization smoothing", "{value1} s", 2, settings.smoothingSeconds, .05f..1f,
                defaults.smoothingSeconds) { onChange(value.copy(settings = settings.copy(smoothingSeconds = it))) }
            StabilizationSetting("Strength", "Stabilization strength", "{value1}%", -1, settings.strength, 0f..1f,
                defaults.strength) { onChange(value.copy(settings = settings.copy(strength = it))) }
        }
    }
}

/** Slider for one live stabilization value. [digits] below zero shows the value as a whole percentage. */
@Composable
private fun StabilizationSetting(label: String, description: String, format: String, digits: Int, value: Float,
    range: ClosedFloatingPointRange<Float>, default: Float, onChange: (Float) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    fun number(amount: Float) = if (digits < 0) kotlin.math.round(amount * 100).toInt().toString()
        else String.format(Locale.ROOT, "%.${digits}f", amount)
    DiagramSliderRow(label, value, format, { onChange(it.coerceIn(range)) }, range = range, step = .01f,
        startLabel = renderer.format(format, mapOf("value1" to number(range.start))),
        endLabel = renderer.format(format, mapOf("value1" to number(range.endInclusive))), description = description,
        valueArguments = mapOf("value1" to number(value)), translateEnds = false, onReset = { onChange(default) })
}

@Composable
internal fun CfaOverrideEditor(value: CfaOverride, onChange: (CfaOverride) -> Unit) {
    var layoutExpanded by remember { mutableStateOf(false) }
    var orderExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        CfaGridStage(value)
        DiagramDropdownField("CFA / REMOSAIC", value.layout.label, null, layoutExpanded, number = 1,
            onExpand = { layoutExpanded = true }, onDismiss = { layoutExpanded = false }) {
            CfaLayout.entries.forEach { layout ->
                DiagramMenuItem(layout.label, selected = value.layout == layout) {
                    onChange(value.copy(layout = layout)); layoutExpanded = false
                }
            }
        }
        DiagramDropdownField("CFA COLOR ORDER", value.order.label, null, orderExpanded, number = 2,
            onExpand = { orderExpanded = true }, onDismiss = { orderExpanded = false }) {
            CfaOrder.entries.forEach { order ->
                DiagramMenuItem(order.label, selected = value.order == order) {
                    onChange(value.copy(order = order)); orderExpanded = false
                }
            }
        }
    }
}

@Composable
private fun AeLimitsEditor(limits: AeLimits, device: ExposureBounds?, onChange: (AeLimits) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("UNSPEKTRA AE LIMITS", color = CameraColors.Accent, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
        if (device == null) {
            DiagramNote("Manual sensor exposure is unavailable on this camera. Android AE will be used.")
        } else {
            val bounds = limits.resolve(device)
            val iso = remember(device) { isoStops(device.minIso, device.maxIso) }
            val shutter = remember(device) { shutterStops(device.minShutterNs, device.maxShutterNs) }
            AeLimitsStage(device, bounds)
            AeLimitChoice("MINIMUM ISO", bounds.minIso.toString(), iso.map(Int::toString), iso.indexOf(bounds.minIso)) {
                onChange(limits.copy(minIso = iso[it], maxIso = maxOf(iso[it], bounds.maxIso)))
            }
            AeLimitChoice("MAXIMUM ISO", bounds.maxIso.toString(), iso.map(Int::toString), iso.indexOf(bounds.maxIso)) {
                onChange(limits.copy(maxIso = iso[it], minIso = minOf(iso[it], bounds.minIso)))
            }
            AeLimitChoice("FASTEST SHUTTER", "${formatShutter(bounds.minShutterNs)} s", shutter.map { "${it.label} s" }, shutter.indexOfFirst { it.nanoseconds == bounds.minShutterNs }) {
                val value = shutter[it].nanoseconds
                onChange(limits.copy(minShutterNs = value, maxShutterNs = maxOf(value, bounds.maxShutterNs)))
            }
            AeLimitChoice("SLOWEST SHUTTER", "${formatShutter(bounds.maxShutterNs)} s", shutter.map { "${it.label} s" }, shutter.indexOfFirst { it.nanoseconds == bounds.maxShutterNs }) {
                val value = shutter[it].nanoseconds
                onChange(limits.copy(maxShutterNs = value, minShutterNs = minOf(value, bounds.minShutterNs)))
            }
            DiagramLink("Reset AE limits", Modifier.padding(top = 2.dp)) { onChange(AeLimits()) }
        }
    }
}

@Composable
private fun AeLimitChoice(label: String, value: String, choices: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    DiagramDropdownField(label, value, null, expanded, onExpand = { expanded = true }, onDismiss = { expanded = false }) {
        choices.forEachIndexed { index, choice ->
            DiagramMenuItem(choice, selected = index == selected) { onSelect(index); expanded = false }
        }
    }
}
