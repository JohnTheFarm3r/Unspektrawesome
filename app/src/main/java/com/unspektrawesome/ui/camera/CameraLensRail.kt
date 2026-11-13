package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.LensProfile
import com.unspektrawesome.settings.LensButtonMapping
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.diagnostics.InternalLogRecorder
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun CameraLensRail(
    cameras: List<CameraDescriptor>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    preferences: CameraPreferences = CameraPreferences(),
    visible: Boolean = true,
    enabled: Boolean = true,
    onDefaultProfile: (String) -> Unit = {},
) {
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "Lens controls visibility")
    val interactive = visible && enabled
    val byRoute = cameras.associateBy { it.route.routeId }
    val orderedCameras = LensButtonMapping.opticalOrder(cameras)
    val routeOrder = orderedCameras.mapIndexed { index, camera -> camera.route.routeId to index }.toMap()
    val profiles = com.unspektrawesome.settings.LensProfiles.visible(preferences, cameras)
    val groups = profiles.groupBy { it.focalGroup }.toList().sortedWith(compareBy(
        { (_, choices) -> byRoute.getValue(choices.first().routeId).facing.ordinal },
        { (_, choices) -> choices.minOf { it.focalLength.equivalent(byRoute.getValue(it.routeId)) ?: Float.MAX_VALUE } },
        { (_, choices) -> choices.minOf { routeOrder.getValue(it.routeId) } }))
    // Photo and video remember the profile last used on each lens separately.
    val mode = preferences.lensProfileMode
    fun memory(group: String) = "${mode.name}:$group"
    var lastUsed by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val activeProfile = profiles.firstOrNull { it.id == preferences.activeLensProfileId }
    val defaultProfile = preferences.defaultLensProfileId(mode)
    LaunchedEffect(activeProfile?.id) {
        activeProfile?.let { lastUsed = lastUsed + (memory(it.focalGroup) to it.id) }
    }
    val configuration = LocalViewConfiguration.current
    val holdConfiguration = remember(configuration) {
        object : ViewConfiguration by configuration {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
        val scrollState = rememberScrollState()
        Row(modifier.height(44.dp).graphicsLayer { alpha = opacity }
            .traceLensInput("rail") { "enabled=$interactive scroll=${scrollState.isScrollInProgress} offset=${scrollState.value}/${scrollState.maxValue}" }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .horizontalScroll(scrollState, enabled = interactive && scrollState.maxValue > 0, overscrollEffect = null),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (groups.isEmpty()) {
                orderedCameras.forEach { camera ->
                    key(camera.route.routeId) {
                        LensButton(camera, camera.route.routeId == selected, emptyList(), null, null,
                            enabled = interactive, onDefaultProfile = {},
                            onSelect = { onSelect(camera.route.routeId) })
                    }
                }
            } else {
                groups.forEach { (sensorId, choices) ->
                    key(sensorId) {
                        val profile = activeProfile?.takeIf { it.focalGroup == sensorId }
                            ?: choices.firstOrNull { it.id == lastUsed[memory(sensorId)] } ?: choices.first()
                        LensButton(byRoute.getValue(profile.routeId), activeProfile?.focalGroup == sensorId,
                            choices, profile.id, defaultProfile, enabled = interactive, onDefaultProfile = onDefaultProfile,
                            onSelect = { id ->
                                val chosen = id ?: profile.id
                                lastUsed = lastUsed + (memory(sensorId) to chosen)
                                onSelect(chosen)
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun LensButton(camera: CameraDescriptor, active: Boolean, profiles: List<LensProfile>,
    selectedProfile: String?, defaultProfile: String?, enabled: Boolean, onDefaultProfile: (String) -> Unit,
    onSelect: (String?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val rotation = LocalCameraRotation.current
    val zoom = profiles.firstOrNull { it.id == selectedProfile }?.focalLength?.label(camera) ?: LensButtonMapping.zoomLabel(camera)
    val label = AppLanguage.text(zoom)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = CameraTypography.Readout)
    val labelSize = remember(label, density, labelStyle) {
        val width = measurer.measure(label, labelStyle, softWrap = false, maxLines = 1).size.width
        12.sp * minOf(1f, with(density) { 44.dp.toPx() } / width.coerceAtLeast(1))
    }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    var bounds by remember { mutableStateOf(IntRect.Zero) }
    // Reset the held gesture when the picker takes or releases touch focus.
    key(selectedProfile, expanded) {
        Box(Modifier.size(44.dp).traceLensInput("button-${camera.route.routeId}") { "enabled=$enabled expanded=$expanded" }.onGloballyPositioned {
            val rect = it.boundsInWindow()
            bounds = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }.clip(CircleShape)
            .previewControlSurface(CircleShape, selected = active)
            .combinedClickable(enabled = enabled, role = Role.RadioButton, onClick = {
                if (BuildConfig.DEBUG) InternalLogRecorder.event("LensInput", "click route=${camera.route.routeId} profile=$selectedProfile")
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSelect(null)
            },
                onLongClickLabel = if (profiles.isNotEmpty()) AppLanguage.text("Choose lens profile") else null,
                onLongClick = if (profiles.isNotEmpty()) ({
                    if (BuildConfig.DEBUG) InternalLogRecorder.event("LensInput", "hold route=${camera.route.routeId}")
                    expanded = true
                }) else null)
            .languageSemantics { language ->
                contentDescription = language.format("Lens {zoom}", mapOf("zoom" to (zoom)))
                selected = active
                if (!enabled) disabled()
                profiles.firstOrNull { it.id == selectedProfile }?.let { stateDescription = it.name }
            }, contentAlignment = Alignment.Center) {
            Text(label, Modifier.animatedCameraRotation(rotation), color = if (active) CameraColors.Accent else Color.White,
                fontFamily = CameraTypography.Readout, fontSize = labelSize, maxLines = 1, softWrap = false, translate = false)
        }
    }
    if (expanded && enabled) {
        LensProfilePicker(profiles, selectedProfile, defaultProfile, bounds, rotation, { expanded = false }, onDefaultProfile) {
            expanded = false
            if (enabled) onSelect(it)
        }
    }
}

@Composable
private fun LensProfilePicker(profiles: List<LensProfile>, selectedId: String?, defaultId: String?, anchor: IntRect,
    rotation: Float, onDismiss: () -> Unit, onDefaultProfile: (String) -> Unit, onSelect: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scroll = rememberLazyListState()
    val scrollable = scroll.canScrollBackward || scroll.canScrollForward
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = remember(anchor, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (windowSize.width - popupContentSize.width) / 2
                val y = anchor.top - popupContentSize.height - margin
                return IntOffset(x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = CameraPopupProperties) {
        CameraWindowBackdrop()
        Layout(content = {
            val shape = CameraStyle.PanelShape
            Column(Modifier.clip(shape).background(CameraColors.QuickMenuBackground).border(1.dp, CameraColors.MenuBorder, shape)) {
                CameraQuickMenuHeader("Lens Profiles", com.unspektrawesome.R.drawable.ic_sleek_aperture, onDismiss)
                Box(Modifier.weight(1f, fill = false)) {
                    LazyColumn(state = scroll, modifier = Modifier.padding(end = if (scrollable) 16.dp else 0.dp),
                        contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), overscrollEffect = null) {
                        items(profiles, key = { it.id }) { profile ->
                            val current = profile.id == selectedId
                            // A tap switches to the profile. Holding it makes it the profile this capture mode starts with.
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                .border(1.dp, if (current) CameraColors.Accent else CameraColors.MenuBorder, RoundedCornerShape(12.dp))
                                .combinedClickable(role = Role.RadioButton,
                                    onLongClickLabel = AppLanguage.text("Set as default"),
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onDefaultProfile(profile.id)
                                    }, onClick = { onSelect(profile.id) })
                                .semantics { selected = current }
                                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(profile.displayName, Modifier.weight(1f, fill = false), translate = false,
                                            color = if (current) CameraColors.Accent else Color.White,
                                            fontSize = 12.sp, lineHeight = 16.sp,
                                            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
                                        if (profile.id == defaultId) Text("Default", Modifier.testTag("Default lens profile")
                                            .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                            color = CameraColors.Text, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false)
                                    }
                                    Text(profile.rawStream.let { "${it.format} · ${it.width} × ${it.height}" }, translate = false,
                                        color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = CameraTypography.Readout)
                                }
                                if (current) CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_check, Modifier.padding(start = 6.dp).size(16.dp), CameraColors.Accent)
                            }
                        }
                    }
                    if (scrollable) LensProfileScrollIndicator(scroll, Modifier.matchParentSize().padding(4.dp))
                }
            }
        }) { measurables, constraints ->
            val sideways = abs(rotation) == 90f
            val availableWidth = if (sideways) constraints.maxHeight else constraints.maxWidth
            val availableHeight = if (sideways) constraints.maxWidth else constraints.maxHeight
            val width = minOf(300.dp.roundToPx(), (availableWidth - margin * 2).coerceAtLeast(1))
            val height = minOf(320.dp.roundToPx(), (availableHeight - margin * 2).coerceAtLeast(1))
            val child = measurables.single().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
            val outerWidth = if (sideways) child.height else child.width
            val outerHeight = if (sideways) child.width else child.height
            layout(outerWidth, outerHeight) {
                child.placeWithLayer((outerWidth - child.width) / 2, (outerHeight - child.height) / 2) {
                    rotationZ = rotation
                }
            }
        }
    }
}

@Composable
private fun LensProfileScrollIndicator(scroll: LazyListState, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight().padding(vertical = 14.dp)) {
            val indicator = scroll.scrollIndicatorState ?: return@Canvas
            if (indicator.contentSize <= indicator.viewportSize || size.height <= 0f) return@Canvas
            val width = 2.dp.toPx()
            val x = (size.width - width) / 2
            val thumbHeight = (size.height * indicator.viewportSize / indicator.contentSize)
                .coerceIn(minOf(16.dp.toPx(), size.height), size.height)
            val progress = when {
                !scroll.canScrollBackward -> 0f
                !scroll.canScrollForward -> 1f
                else -> (indicator.scrollOffset.toFloat() / (indicator.contentSize - indicator.viewportSize)).coerceIn(0f, 1f)
            }
            drawRoundRect(CameraColors.MenuBorder, Offset(x, 0f), Size(width, size.height), CornerRadius(width))
            drawRoundRect(CameraColors.Accent, Offset(x, progress * (size.height - thumbHeight)),
                Size(width, thumbHeight), CornerRadius(width))
        }
        if (scroll.canScrollBackward) CameraGlyphIcon(CameraGlyph.Chevron,
            Modifier.align(Alignment.TopEnd).size(12.dp).rotate(-90f)
                .languageSemantics { language -> contentDescription = language.text("More profiles above") }, CameraColors.Accent)
        if (scroll.canScrollForward) CameraGlyphIcon(CameraGlyph.Chevron,
            Modifier.align(Alignment.BottomEnd).size(12.dp).rotate(90f)
                .languageSemantics { language -> contentDescription = language.text("More profiles below") }, CameraColors.Accent)
    }
}
