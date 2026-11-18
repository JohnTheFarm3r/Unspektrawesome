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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
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
import com.unspektrawesome.settings.LensButtonShape
import com.unspektrawesome.settings.LensButtonStyle
import com.unspektrawesome.settings.LensRail
import com.unspektrawesome.settings.LensRailSlot
import androidx.compose.ui.graphics.Shape
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
    val profiles = com.unspektrawesome.settings.LensProfiles.visible(preferences, cameras)
    val slots = LensRail.slots(profiles, cameras)
    // Photo and video remember the profile last used on each lens separately.
    val mode = preferences.lensProfileMode
    fun memory(slot: LensRailSlot) = "${mode.name}:${slot.key}"
    var lastUsed by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val activeProfile = profiles.firstOrNull { it.id == preferences.activeLensProfileId }
    val defaultProfile = preferences.defaultLensProfileId(mode)
    fun recordUse(id: String) {
        slots.firstOrNull { it.pinned == null && it.contains(id) }?.let { lastUsed = lastUsed + (memory(it) to id) }
    }
    LaunchedEffect(activeProfile?.id) { activeProfile?.let { recordUse(it.id) } }
    val configuration = LocalViewConfiguration.current
    val holdConfiguration = remember(configuration) {
        object : ViewConfiguration by configuration {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
    val rotation = LocalCameraRotation.current
    val buttons = if (slots.isEmpty()) orderedCameras.map { camera ->
        LensRailButton(camera.route.routeId, camera, AppLanguage.text(LensButtonMapping.zoomLabel(camera)),
            LensButtonMapping.zoomLabel(camera), LensButtonStyle(), camera.route.routeId == selected, null, emptyList())
    } else slots.map { slot ->
        val profile = slot.pinned ?: slot.choices.let { choices ->
            choices.firstOrNull { it.id == activeProfile?.id } ?: choices.firstOrNull { it.id == lastUsed[memory(slot)] } ?: choices.first()
        }
        val camera = byRoute.getValue(profile.routeId)
        val focal = profile.focalLength.label(camera)
        LensRailButton(slot.key, camera, profile.button.customLabel ?: AppLanguage.text(focal), focal, profile.button,
            slot.contains(activeProfile?.id), profile, slot.group)
    }
    val metrics = rememberLensButtonMetrics(buttons.map { it.label to it.style }, sidewaysRotation(rotation))
    val count = buttons.size
    val activeIndex = buttons.indexOfFirst { it.active }
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
        BoxWithConstraints(modifier) {
        // Buttons overlap like stacked coins: a little always, and more when needed for every lens to fit on one line.
        // A profile can set its own spacing instead. Scrolling is only a fallback when the buttons still do not fit.
        val layout = lensRailLayout(metrics.map { it.width }, buttons.map { it.style.spacing }, maxWidth)
        val scrollState = rememberScrollState()
        // Drawn as one layer so each button face can replace the overlapped part of its neighbour.
        // The layer reaches past the rail so the enlarged selected button and the shadows are not cut off.
        LensRailRow(layout, Modifier.extendDrawingArea(LENS_LAYER_MARGIN)
            .graphicsLayer { alpha = opacity; compositingStrategy = CompositingStrategy.Offscreen }
            .padding(LENS_LAYER_MARGIN).height(metrics.maxOfOrNull { it.height } ?: LENS_BUTTON_SIZE)
            .traceLensInput("rail") { "enabled=$interactive scroll=${scrollState.isScrollInProgress} offset=${scrollState.value}/${scrollState.maxValue}" }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            // Scrolling clips its content, which would cut off the enlarged selected button, so it is used only when needed.
            .then(if (layout.fits) Modifier else Modifier.horizontalScroll(scrollState, enabled = interactive && scrollState.maxValue > 0,
                overscrollEffect = null))) {
            buttons.forEachIndexed { index, button ->
                val stack = if (index == activeIndex) count + 1f else count - abs(index - activeIndex.coerceAtLeast(0)).toFloat()
                key(button.key) {
                    LensButton(button, metrics[index], defaultProfile, enabled = interactive, onDefaultProfile = onDefaultProfile,
                        stack = stack, rotation = rotation, onSelect = { id ->
                            val chosen = id ?: button.profile?.id
                            if (chosen == null) onSelect(button.camera.route.routeId)
                            else {
                                recordUse(chosen)
                                onSelect(chosen)
                            }
                        })
                }
            }
        }
        }
    }
}

/** A button of the lens rail: the profile it shows, or only the camera when there are no lens profiles. */
internal data class LensRailButton(val key: String, val camera: CameraDescriptor, val label: String, val focalLabel: String,
    val style: LensButtonStyle, val active: Boolean, val profile: LensProfile?, val group: List<LensProfile>)

/** Size of a lens button and of its label, which shrinks when it would not fit the button. */
internal data class LensButtonMetrics(val width: Dp, val height: Dp, val fontSize: TextUnit)

internal fun sidewaysRotation(rotation: Float) = (abs(rotation) % 180f) in 45f..135f

@Composable
internal fun rememberLensButtonMetrics(buttons: List<Pair<String, LensButtonStyle>>, sideways: Boolean): List<LensButtonMetrics> {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current.copy(fontFamily = CameraTypography.Readout)
    return remember(buttons, sideways, density, base) {
        buttons.map { (label, style) ->
            val size = style.size.dp
            val fontSize = style.textSize.sp
            val textWidth = with(density) {
                measurer.measure(label, base.copy(fontSize = fontSize), softWrap = false, maxLines = 1).size.width.toDp()
            }
            // A pill grows sideways to fit its label, up to three times its height.
            val padding = size * .3f
            val width = if (style.shape == LensButtonShape.PILL) (textWidth + padding * 2).coerceIn(size, size * 3) else size
            // A round outline is narrower above and below its middle, so a label kept clear of the rounded edges
            // fits less of the width. Sideways the label turns with the phone and has to fit the height instead.
            val clear = when (style.shape) {
                LensButtonShape.CIRCLE -> .84f
                LensButtonShape.ROUNDED, LensButtonShape.PILL -> .9f
                LensButtonShape.SQUARE -> .94f
            }
            val room = when {
                sideways -> minOf(width, size) * clear
                style.shape == LensButtonShape.PILL -> width - padding * 2
                else -> width * clear
            }
            LensButtonMetrics(width, size, fontSize * minOf(1f, room / textWidth.coerceAtLeast(1.dp)))
        }
    }
}

/** Gaps between neighbouring lens buttons and whether the whole rail fits the available width. */
internal data class LensRailLayout(val gaps: List<Dp>, val width: Dp, val fits: Boolean)

/**
 * Two neighbours use the larger of their own spacings. Gaps without one share the free width: buttons overlap
 * by at least [MIN_LENS_OVERLAP] and at most [MAX_LENS_OVERLAP] to fit on one line. When they cannot fit even
 * then, the rail scrolls and keeps the light overlap so every label stays readable.
 */
internal fun lensRailLayout(widths: List<Dp>, spacing: List<Float?>, maxWidth: Dp): LensRailLayout {
    val chosen = (0 until (widths.size - 1).coerceAtLeast(0)).map { index ->
        listOfNotNull(spacing.getOrNull(index), spacing.getOrNull(index + 1)).maxOrNull()?.dp
    }
    val buttons = widths.fold(0.dp) { sum, width -> sum + width }
    val fixed = chosen.filterNotNull().fold(buttons) { sum, gap -> sum + gap }
    val open = chosen.count { it == null }
    val squeezed = if (open == 0 || !maxWidth.isFinite) -MIN_LENS_OVERLAP
        else ((maxWidth - fixed) / open).coerceIn(-MAX_LENS_OVERLAP, -MIN_LENS_OVERLAP)
    val automatic = if (fixed + squeezed * open > maxWidth) -MIN_LENS_OVERLAP else squeezed
    val gaps = chosen.map { it ?: automatic }
    val width = gaps.fold(buttons) { sum, gap -> sum + gap }
    return LensRailLayout(gaps, width, width <= maxWidth)
}

/** Places the buttons left to right with the rail's gaps, which are negative where the buttons overlap. */
@Composable
internal fun LensRailRow(layout: LensRailLayout, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val gaps = layout.gaps.map { it.roundToPx() }
        val positions = IntArray(placeables.size)
        var x = 0
        placeables.forEachIndexed { index, placeable ->
            positions[index] = x
            x += placeable.width + (gaps.getOrNull(index) ?: 0)
        }
        val width = placeables.lastIndex.takeIf { it >= 0 }?.let { positions[it] + placeables[it].width } ?: 0
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
        layout(constraints.constrainWidth(width), height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(positions[index], (height - placeable.height) / 2) }
        }
    }
}

// Grows the content by the margin on every side without changing the space it takes in the layout.
private fun Modifier.extendDrawingArea(margin: Dp): Modifier = layout { measurable, constraints ->
    val extra = margin.roundToPx()
    val placeable = measurable.measure(constraints.offset(extra * 2, extra * 2))
    layout((placeable.width - extra * 2).coerceAtLeast(0), (placeable.height - extra * 2).coerceAtLeast(0)) {
        placeable.place(-extra, -extra)
    }
}

private val LENS_BUTTON_SIZE = LensButtonStyle.DEFAULT_SIZE.dp
private val LENS_LAYER_MARGIN = 10.dp
private val MIN_LENS_OVERLAP = 4.dp
private val MAX_LENS_OVERLAP = 16.dp
internal const val ACTIVE_LENS_SCALE = 1.12f

internal fun LensButtonShape.outline(): Shape = when (this) {
    LensButtonShape.CIRCLE, LensButtonShape.PILL -> CircleShape
    LensButtonShape.ROUNDED -> RoundedCornerShape(28)
    LensButtonShape.SQUARE -> RoundedCornerShape(10)
}

/**
 * The look of a lens button, shared by the camera screen and the settings drawing. The face follows the control
 * opacity setting and replaces rather than blends over the rail layer, so an overlapped button and the shadow
 * underneath do not show through a translucent face.
 */
@Composable
internal fun LensButtonFace(label: String, metrics: LensButtonMetrics, shape: LensButtonShape, active: Boolean,
    modifier: Modifier = Modifier, labelModifier: Modifier = Modifier, behaviour: Modifier = Modifier) {
    val outline = shape.outline()
    val face = LocalCameraControlBackground.current
    val shadowColor = Color.Black.copy(alpha = face.alpha)
    Box(modifier.shadow(4.dp, outline, ambientColor = shadowColor, spotColor = shadowColor).size(metrics.width, metrics.height)
        .clip(outline).drawBehind { drawRect(face, blendMode = BlendMode.Src) }
        .border(1.dp, if (active) CameraColors.Accent else CameraColors.PreviewControlBorder, outline)
        .then(behaviour), contentAlignment = Alignment.Center) {
        Text(label, labelModifier, color = if (active) CameraColors.Accent else Color.White,
            fontFamily = CameraTypography.Readout, fontSize = metrics.fontSize, maxLines = 1, softWrap = false, translate = false)
    }
}

@Composable
private fun LensButton(button: LensRailButton, metrics: LensButtonMetrics, defaultProfile: String?, enabled: Boolean,
    onDefaultProfile: (String) -> Unit, stack: Float, rotation: Float, onSelect: (String?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val camera = button.camera
    val profiles = button.group
    val selectedProfile = button.profile?.id
    val active = button.active
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    var bounds by remember { mutableStateOf(IntRect.Zero) }
    // Reset the held gesture when the picker takes or releases touch focus.
    key(selectedProfile, expanded) {
        val scale by animateFloatAsState(if (active) ACTIVE_LENS_SCALE else 1f, tween(150), label = "Lens button scale")
        LensButtonFace(button.label, metrics, button.style.shape, active,
            Modifier.zIndex(stack).graphicsLayer { scaleX = scale; scaleY = scale },
            labelModifier = Modifier.animatedCameraRotation(rotation),
            behaviour = Modifier.traceLensInput("button-${button.key}") { "enabled=$enabled expanded=$expanded" }.onGloballyPositioned {
                val rect = it.boundsInWindow()
                bounds = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
            }.combinedClickable(enabled = enabled, role = Role.RadioButton, onClick = {
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
                contentDescription = language.format("Lens {zoom}", mapOf("zoom" to (button.style.customLabel ?: button.focalLabel)))
                selected = active
                if (!enabled) disabled()
                button.profile?.let { stateDescription = it.name }
            })
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
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = CameraPopupProperties) { AppSizedWindow {
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
    }}
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
