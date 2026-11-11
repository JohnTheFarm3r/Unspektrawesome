package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.CameraRoute
import com.unspektrawesome.preview.RawImportCapability
import com.unspektrawesome.preview.RawImportCapabilityState
import com.unspektrawesome.preview.RawImportStreamKey
import com.unspektrawesome.settings.*

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
    val sensors = profiles.associateBy { it.profile.id.value }
    val transfer = rememberLensProfileTransfer(preferences, onChange)
    val scroll = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var currentPreferences by remember { mutableStateOf(preferences) }
    currentPreferences = preferences
    val change by rememberUpdatedState(onChange)
    val ids = preferences.lensProfiles.map { it.id }
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
        LazyColumn(Modifier.fillMaxSize().testTag("Lens profile list").reorderLensProfiles(reorder) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }, state = scroll, userScrollEnabled = reorder.draggedKey == null,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "lens-profile-header") {
                SettingsCard("LENS PROFILES", symbol = SettingsSymbol.Discover) {
                    if (onAutoLensDiscovery != null) {
                        SettingsButton("Auto Lens Discovery", Modifier.fillMaxWidth(), symbol = SettingsSymbol.Discover,
                            accent = true, onClick = onAutoLensDiscovery)
                    }
                    SettingsFieldPair(
                        first = { modifier -> SettingsButton("Export", modifier,
                            enabled = !transfer.busy && preferences.lensProfiles.isNotEmpty(), symbol = SettingsSymbol.Export,
                            onClick = transfer.exportProfiles) },
                        second = { modifier -> SettingsButton("Import", modifier, enabled = !transfer.busy,
                            symbol = SettingsSymbol.Import, onClick = transfer.importProfile) },
                    )
                    if (transfer.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = CameraColors.Accent, strokeWidth = 2.dp)
                    if (preferences.lensProfiles.isEmpty()) Text("No lens profiles. Run discovery or add a profile manually.", color = CameraColors.Muted)
                    var adding by remember { mutableStateOf(false) }
                    SettingsDropdownField("+ Add profile", "Lens", null, adding, enabled = profiles.isNotEmpty(),
                        onExpand = { adding = true }, onDismiss = { adding = false }) {
                        profiles.forEach { configured ->
                            val camera = configured.profile.primary
                            ProfileMenuItem("${camera.equivalentFocalLengthMm?.toInt()?.let { "${it}mm" } ?: camera.lensRole.label} · Lens ID ${camera.route.routeId}") {
                                val updated = LensProfiles.add(preferences, configured.profile)
                                onChange(updated)
                                editingId = updated.lensProfiles.lastOrNull()?.id
                                adding = false
                            }
                        }
                    }
                }
                SettingsSection("Hold and drag to reorder")
            }
            items(orderedProfiles, key = { it.id }) { profile ->
                key(profile.id) {
                    var menu by remember { mutableStateOf(false) }
                    val available = LensProfiles.available(profile, profiles.flatMap { it.profile.paths })
                    val output = sensors[profile.sensorId]?.profile?.paths?.firstOrNull { it.route.routeId == profile.routeId }
                        ?.let { LensProfiles.resolveRawOutput(profile.rawStream, it, profile.rawSession) }
                    val active = preferences.activeLensProfileId == profile.id
                    val startup = preferences.defaultLensProfileId == profile.id
                    val moving = reorder.draggedKey == profile.id
                    val lifted = moving || reorder.settlingKey == profile.id
                    Surface(color = Color.Transparent,
                        border = BorderStroke(if (lifted) 2.dp else 1.dp, if (active || lifted) CameraColors.Accent else CameraColors.MenuBorder),
                        shape = SettingsStyle.PanelShape, modifier = Modifier.fillMaxWidth()
                            .animateItem(placementSpec = if (lifted) null else tween(180))
                            .zIndex(if (lifted) 1f else 0f).graphicsLayer { translationY = reorder.translation(profile.id) }
                            .pageCardBackground(SettingsStyle.PanelShape)
                            .testTag("Lens profile ${profile.id}").semantics { customActions = reorder.accessibilityActions(profile.id) }) {
                        Box {
                        ProfileCardLayout(details = { modifier ->
                            Column(modifier.clickable(enabled = available, role = Role.Button) { editingId = profile.id }.padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(profile.displayName, translate = false, color = if (active) CameraColors.Accent else CameraColors.Text,
                                    fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOf("Lens ID ${profile.routeId}", profile.rawStream.format.name,
                                    "${profile.rawStream.width}×${profile.rawStream.height}").joinToString(" · "),
                                    color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                                if (!available) Text("Lens unavailable", color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 14.sp)
                                else if (output == null) Text("Saved stream unavailable.",
                                    color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 14.sp)
                                if (available && (active || startup)) Text(
                                    when { active && startup -> "Current / startup profile"; startup -> "Startup profile"; else -> "Current profile" },
                                    color = if (active) CameraColors.Accent else CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                        }, actions = {
                            Checkbox(checked = profile.enabled, enabled = available || profile.enabled,
                                onCheckedChange = { enabled -> onChange(preferences.copy(lensProfiles = preferences.lensProfiles.map { if (it.id == profile.id) it.copy(enabled = enabled) else it })) },
                                modifier = Modifier.size(40.dp, 48.dp).languageSemantics { language -> contentDescription = language.format("Show {name} in lens switcher", mapOf("name" to (profile.name))) },
                                colors = CheckboxDefaults.colors(checkedColor = CameraColors.MenuSelectedCard,
                                    checkmarkColor = CameraColors.Accent, uncheckedColor = CameraColors.Muted))
                            Box {
                                TextButton(onClick = { menu = true }, modifier = Modifier.size(40.dp, 48.dp).languageSemantics { language -> contentDescription = language.format("Actions for {name}", mapOf("name" to (profile.name))) }, contentPadding = PaddingValues(0.dp)) {
                                    SettingsIcon(SettingsSymbol.More, color = CameraColors.Text)
                                }
                                CompactCameraMenu(menu, { menu = false }, Modifier.widthIn(min = 220.dp, max = 320.dp)) {
                                    fun close(action: () -> Unit) { menu = false; action() }
                                    ProfileMenuItem("Edit", available, symbol = SettingsSymbol.Edit) { close { editingId = profile.id } }
                                    ProfileMenuItem("Rename", symbol = SettingsSymbol.Rename) { close { renamingId = profile.id } }
                                    ProfileMenuItem("Duplicate", symbol = SettingsSymbol.Duplicate) { close { onChange(LensProfiles.duplicate(preferences, profile.id)) } }
                                    ProfileMenuItem("Set as startup", available && profile.enabled, selected = startup, symbol = SettingsSymbol.Startup) { close {
                                        onChange(preferences.copy(defaultLensProfileId = profile.id, defaultLensId = profile.routeId))
                                    } }
                                    ProfileMenuItem("Delete", LensProfiles.canDelete(preferences, profile.id), symbol = SettingsSymbol.Delete) { close { onChange(LensProfiles.delete(preferences, profile.id)) } }
                                }
                            }
                        })
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
        AlertDialog(onDismissRequest = { renamingId = null }, containerColor = CameraColors.Background,
            title = { CameraWindowBackdrop(); Text("Rename profile", color = Color.White) },
            text = { ProfileNameField(name) { name = it } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                onChange(preferences.copy(lensProfiles = preferences.lensProfiles.map { if (it.id == profile.id)
                    it.copy(name = name.trim(), autoManaged = profile.autoManaged && name.trim() == profile.name) else it }))
                renamingId = null
            }) { Text("Save", color = if (name.isBlank()) CameraColors.Muted else CameraColors.Accent) } },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel", color = CameraColors.Muted) } })
    }
}

@Composable
private fun ProfileMenuItem(label: String, enabled: Boolean = true, detail: String? = null, selected: Boolean? = null,
    symbol: SettingsSymbol? = null, onClick: () -> Unit) {
    SettingsMenuChoice(label, selected == true, detail, enabled = enabled,
        role = if (selected == null) Role.Button else Role.RadioButton, symbol = symbol, onClick = onClick)
}

@Composable
private fun ProfileCardLayout(details: @Composable (Modifier) -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsIcon(SettingsSymbol.Grip, Modifier.size(16.dp))
        details(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
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
    BackHandler(enabled = isActive, onBack = onClose)
    SettingsPage {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SettingsButton("Back", modifier = Modifier.testTag("Back to lens profiles"), symbol = SettingsSymbol.Back, onClick = onClose)
            Text("EDIT PROFILE", color = CameraColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        SettingsCard {
            ProfileNameField(name) { value ->
                name = value
                if (value.isNotBlank()) update { it.copy(name = value.trim()) }
            }
            if (name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsIcon(SettingsSymbol.Saved, Modifier.size(16.dp))
                Text("Saved automatically", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        SettingsSection("Sensor input")
        SettingsCard {
            SettingsDropdownField("CAMERA ID", cameraIdLabel(camera), null, cameraIdExpanded,
                onExpand = { cameraIdExpanded = true }, onDismiss = { cameraIdExpanded = false }) {
                sensor.paths.forEach { candidate ->
                    ProfileMenuItem(cameraIdLabel(candidate), selected = route == candidate.route.routeId) {
                        if (route != candidate.route.routeId) {
                            updateRawConfiguration(nextRoute = candidate.route.routeId)
                        }
                        cameraIdExpanded = false
                    }
                }
            }
            SettingsDropdownField("RAW STREAM", rawOutputLabel(stream),
                if (selected != null) outputCapabilityDetail(camera, selected, capabilities, vendorOverrides.active(), profile.rawLayoutOverride, profile.capture, profile.rawSession)
                else "Saved preference, temporarily unavailable", rawExpanded,
                onExpand = { rawExpanded = true }, onDismiss = { rawExpanded = false }) {
                if (selected == null) ProfileMenuItem(rawOutputLabel(stream), detail = "Saved preference, temporarily unavailable", selected = true) {
                    rawExpanded = false
                }
                outputs.sortedWith(compareByDescending<com.unspektrawesome.camera.RawOutput> { it.size.area() }.thenBy { it.format.name }).forEach { output ->
                    val enabled = supported(route, RawStreamSelection.from(output), vendorOverrides)
                    ProfileMenuItem(rawOutputLabel(output), enabled, outputCapabilityDetail(camera, output, capabilities, vendorOverrides.active(), profile.rawLayoutOverride, profile.capture, profile.rawSession), stream.matches(output)) {
                        updateRawConfiguration(nextStream = RawStreamSelection.from(output)); rawExpanded = false
                    }
                }
            }
            if (selected == null) Text("Saved stream unavailable.",
                color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
            if (!supported(route, stream, profile.vendorOverrides)) Text(
                "This RAW configuration cannot be saved. Choose a compatible camera and RAW stream.",
                color = CameraColors.FocusFailed, fontSize = 11.sp, lineHeight = 15.sp)
        }
        SettingsSection("Capture")
        SettingsCard {
            PhotoOisControl(profile.photoOisEnabled, photoOisSupported) { enabled -> update { it.copy(photoOisEnabled = enabled) } }
        }
        LensCaptureControls(camera, profile, onCheckLensFrameRates, isActive = isActive) { updated -> update { updated } }
        SettingsSection("Preview")
        SettingsCard {
            val decodedStream = profile.rawLayoutOverride.stream(stream)
            ViewfinderResolutionField(decodedStream,
                ViewfinderResolutions.selectedEdge(decodedStream, profile.previewShortEdge, profile.previewQuality)) { edge ->
                update { it.copy(previewShortEdge = edge) }
            }
        }
        SettingsSection("Auto exposure")
        SettingsCard {
            val engines = listOf(AeEngine.SPEKTRA, AeEngine.ANDROID)
            SettingsChoices(engines.map { if (it == AeEngine.ANDROID) "Android AE" else it.label },
                engines.indexOf(profile.aeEngine)) { index -> update { it.copy(aeEngine = engines[index]) } }
            if (profile.aeEngine == AeEngine.SPEKTRA) {
                SettingsDivider()
                AeLimitsEditor(profile.aeLimits, exposureBounds) { limits ->
                    update { it.copy(aeLimits = limits) }
                }
            }
        }
        SettingsSection("RAW adjustments")
        SettingsCard {
            RawCenterCropEditor(profile.rawEdgeCrop, profile.rawLayoutOverride.size(com.unspektrawesome.camera.Size2d(stream.width, stream.height))) {
                updateRawConfiguration(nextCrop = it)
            }
        }
        SettingsCard {
            RawLayoutOverrideEditor(profile.rawLayoutOverride, stream, profile.rawEdgeCrop) { layout ->
                if (supported(route, stream, currentProfile.vendorOverrides, layout)) {
                    update { it.copy(routeId = route, rawStream = stream, rawLayoutOverride = layout,
                        verification = it.verification.takeIf { _ -> route == it.routeId && stream == it.rawStream }) }
                }
            }
        }
        SettingsCard {
            CfaOverrideEditor(profile.cfaOverride) { value -> update { it.copy(cfaOverride = value) } }
        }
        SettingsCard {
            RawEdgeCropEditor(profile.rawEdgeCrop, profile.rawLayoutOverride.size(com.unspektrawesome.camera.Size2d(stream.width, stream.height))) {
                updateRawConfiguration(nextCrop = it)
            }
        }
        SettingsCard {
            VendorOverridesEditor(vendorOverrides, camera.route) { overrides ->
                vendorOverrides = overrides
                if (overrides.validationError() == null && supported(currentProfile.routeId, currentProfile.rawStream, overrides)) {
                    update { it.copy(vendorOverrides = overrides) }
                }
            }
            if (vendorOverrides != profile.vendorOverrides) Text(
                "Vendor changes are not saved yet. Complete the entries or turn overrides off to keep them without applying them.",
                color = CameraColors.FocusFailed, fontSize = 11.sp, lineHeight = 15.sp)
        }
        SettingsSection("Color calibration")
        SettingsCard {
            RawLevelCalibrationEditor(profile.rawLevels, camera.sensorMetadata) { levels ->
                if (levels?.isValid() == true) update {
                    it.copy(rawLevels = if (levels.mode == it.rawLevels.mode && levels.mode != RawLevelMode.CUSTOM) it.rawLevels else levels)
                }
            }
        }
        SettingsCard {
            LensColorCalibrationEditor(profile.color, camera.sensorMetadata.colorCalibration.hasUsableColorTransform(),
                camera.lensShadingMapAvailable, camera.lensShadingAppliedInRaw,
                onChange = { color -> update { it.copy(color = color) } }, onImporting = {})
        }
    }
}

@Composable
internal fun PhotoOisControl(enabled: Boolean, supported: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Photo OIS", modifier = Modifier.padding(end = 12.dp), color = CameraColors.Text, fontSize = 13.sp)
            CameraOverlayToggle(enabled && supported, "Photo OIS", supported, onChange)
        }
        if (!supported) Text("OIS is unavailable for this lens", color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun CfaOverrideEditor(value: CfaOverride, onChange: (CfaOverride) -> Unit) {
    var layoutExpanded by remember { mutableStateOf(false) }
    var orderExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsDropdownField("CFA / REMOSAIC", value.layout.label, null, layoutExpanded,
            onExpand = { layoutExpanded = true }, onDismiss = { layoutExpanded = false }) {
            CfaLayout.entries.forEach { layout ->
                ProfileMenuItem(layout.label, selected = value.layout == layout) {
                    onChange(value.copy(layout = layout)); layoutExpanded = false
                }
            }
        }
        SettingsDropdownField("CFA COLOR ORDER", value.order.label, null, orderExpanded,
            onExpand = { orderExpanded = true }, onDismiss = { orderExpanded = false }) {
            CfaOrder.entries.forEach { order ->
                ProfileMenuItem(order.label, selected = value.order == order) {
                    onChange(value.copy(order = order)); orderExpanded = false
                }
            }
        }
    }
}

@Composable
private fun AeLimitsEditor(limits: AeLimits, device: ExposureBounds?, onChange: (AeLimits) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("UNSPEKTRA AE LIMITS", color = CameraColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (device == null) {
            Text("Manual sensor exposure is unavailable on this camera. Android AE will be used.", color = CameraColors.Muted, fontSize = 12.sp)
        } else {
            val bounds = limits.resolve(device)
            val iso = remember(device) { isoStops(device.minIso, device.maxIso) }
            val shutter = remember(device) { shutterStops(device.minShutterNs, device.maxShutterNs) }
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
            TextButton(onClick = { onChange(AeLimits()) }) { Text("Reset AE limits", color = CameraColors.Accent) }
        }
    }
}

@Composable
private fun AeLimitChoice(label: String, value: String, choices: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingsDropdownField(label, value, null, expanded, onExpand = { expanded = true }, onDismiss = { expanded = false }) {
        choices.forEachIndexed { index, choice ->
            ProfileMenuItem(choice, selected = index == selected) { onSelect(index); expanded = false }
        }
    }
}

private fun cameraIdLabel(camera: CameraDescriptor): String = when (camera.route.kind) {
    CameraRoute.Kind.LOGICAL, CameraRoute.Kind.HIDDEN_LOGICAL -> "Logical ID ${camera.route.openCameraId}"
    CameraRoute.Kind.PHYSICAL -> "Logical ID ${camera.route.openCameraId} · Physical ID ${camera.route.physicalCameraId}"
    CameraRoute.Kind.DIRECT, CameraRoute.Kind.HIDDEN_DIRECT -> "Physical ID ${camera.route.openCameraId}"
}
