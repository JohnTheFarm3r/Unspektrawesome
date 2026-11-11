package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.settings.CompositionGrid
import com.unspektrawesome.camera.exposure.AeEngine
import kotlin.math.ceil

internal fun Modifier.flowRowIntrinsicWidth(spacing: Dp, itemCount: Int) = then(object : LayoutModifier {
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int {
        // FlowRow wraps with ceiling pixel gaps but reports rounded gaps in its intrinsic width.
        val extraGap = ceil(spacing.toPx()).toInt() - spacing.roundToPx()
        return measurable.maxIntrinsicWidth(height) + extraGap * (itemCount - 1).coerceAtLeast(0)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
})

@Composable
internal fun Modifier.previewControlSurface(
    shape: Shape,
    overlayFraction: Float = 1f,
    selected: Boolean = false,
    selectedColor: Color = CameraColors.Accent,
): Modifier {
    val fraction = overlayFraction.coerceIn(0f, 1f)
    val background = LocalCameraControlBackground.current
    return clip(shape)
        .background(background.copy(alpha = background.alpha * fraction))
        .border(1.dp, if (selected) selectedColor else
            CameraColors.PreviewControlBorder.copy(alpha = CameraColors.PreviewControlBorder.alpha * fraction), shape)
}

@Composable
internal fun CameraOverlayToggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    CameraOverlayToggle(checked, label, true, onChange)
}

@Composable
internal fun CameraOverlayToggle(checked: Boolean, label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    if (LocalSettingsDesign.current) {
        SettingsSwitch(checked, label, enabled, onChange)
        return
    }
    Box(Modifier.heightIn(min = 40.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
        .background(if (checked) CameraColors.MenuSelectedCard else CameraColors.MenuCard)
        .border(1.dp, if (checked) CameraColors.Accent else CameraColors.MenuBorder, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
        .languageSemantics { language -> contentDescription = language.text(label); toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(if (checked) "ON" else "OFF", color = if (checked) CameraColors.Accent else CameraColors.Muted, fontSize = 10.sp)
    }
}

@Composable
internal fun AeEngineOverlay(engine: AeEngine, enabled: Boolean, onToggle: () -> Unit) {
    val custom = engine == AeEngine.SPEKTRA
    val next = if (custom) AeEngine.ANDROID else AeEngine.SPEKTRA
    val haptics = LocalHapticFeedback.current
    // Compact switch: lime while Unspektra AE runs, white for the Android engine.
    val ink = if (!enabled) CameraColors.Muted else if (custom) CameraColors.Accent else CameraColors.Text
    Row(Modifier.heightIn(min = 36.dp)
        .previewControlSurface(RoundedCornerShape(18.dp), selected = custom)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = AppLanguage.format("Switch to {label}", mapOf("label" to (next.label)))) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onToggle()
        }.languageSemantics { language -> contentDescription = language.text("Switch AE engine"); stateDescription = language.text(engine.label) }
        .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("AE", color = ink, fontSize = 11.sp)
        Text("⇄", color = ink, fontSize = 13.sp)
    }
}

@Composable
internal fun CameraOverlayAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    if (LocalSettingsDesign.current) {
        SettingsButton(label, modifier, enabled, onClick = onClick)
        return
    }
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(CameraColors.MenuCard)
        .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = CameraColors.Muted.copy(alpha = if (enabled) 1f else .4f), fontSize = 11.sp)
    }
}

@Composable
internal fun CameraQuickMenuAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = CameraColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        CameraGlyphIcon(CameraGlyph.Chevron, Modifier.size(14.dp), CameraColors.Muted)
    }
}

@Composable
internal fun CameraGridMenuAction(grid: CompositionGrid, onChange: (CompositionGrid) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val ink = if (grid == CompositionGrid.OFF) CameraColors.Muted else CameraColors.Accent
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
        .clickable(role = Role.Button) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onChange(CompositionGrid.entries[(grid.ordinal + 1) % CompositionGrid.entries.size])
        }.languageSemantics { language -> contentDescription = language.text("Composition grid"); stateDescription = language.text(grid.label) }
        .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CameraGridIcon(grid, ink)
        FlowRow(Modifier.weight(1f).flowRowIntrinsicWidth(8.dp, 2), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Grid", modifier = Modifier.weight(1f), color = CameraColors.Text, fontSize = 12.sp)
            Text(grid.label, color = ink, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun CameraPhotoFormatMenuRow(saveOriginalDng: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup()
        .languageSemantics { language -> contentDescription = language.text("Photo format") }
        .padding(CameraStyle.QuickMenuRowPadding), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Output", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.width(CameraStyle.QuickMenuLabelWidth))
        BoxWithConstraints(Modifier.weight(1f)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp)
            val padding = if (maxWidth < 170.dp) 6.dp else 8.dp
            val minimumWidth = with(density) { measurer.measure("JPEG+DNG", style, softWrap = false).size.width.toDp() } + padding * 2 + 2.dp
            val columns = if (minimumWidth * 2 + 6.dp <= maxWidth) 2 else 1
            val width = with(density) { ((maxWidth.roundToPx() - kotlin.math.ceil(6.dp.toPx()).toInt() * (columns - 1)) / columns).toDp() }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false, true).forEach { keepRaw ->
                    VideoMenuChoice(if (keepRaw) "JPEG+DNG" else "JPEG", keepRaw == saveOriginalDng,
                        modifier = Modifier.width(width), horizontalPadding = padding, onClick = { onChange(keepRaw) })
                }
            }
        }
    }
}

@Composable
internal fun CameraGridIcon(grid: CompositionGrid, ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp).border(1.dp, ink, RoundedCornerShape(2.dp))) {
        if (grid == CompositionGrid.OFF) drawLine(ink, Offset(0f, size.height), Offset(size.width, 0f), 1.dp.toPx())
        else drawCompositionGrid(grid, ink, .7.dp.toPx())
    }
}

@Composable
internal fun CameraTimerMenuRow(seconds: Int, onChange: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val choices = listOf(0, 3, 5, 10)
    Row(Modifier.fillMaxWidth().selectableGroup()
        .languageSemantics { language -> contentDescription = language.text("Self-timer") }
        .padding(CameraStyle.QuickMenuRowPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.width(CameraStyle.QuickMenuLabelWidth), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraGlyphIcon(CameraGlyph.Timer, Modifier.size(14.dp), CameraColors.Muted)
            Text("Timer", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp)
            val labels = choices.map { if (it == 0) AppLanguage.text("Off") else AppLanguage.format("{value1} s", mapOf("value1" to it)) }
            val minimumWidth = with(density) { labels.maxOf { measurer.measure(it, style, softWrap = false).size.width }.toDp() } + 10.dp
            val columns = ((maxWidth + 6.dp) / (minimumWidth + 6.dp)).toInt().coerceIn(1, 4)
            val width = with(density) { ((maxWidth.roundToPx() - kotlin.math.ceil(6.dp.toPx()).toInt() * (columns - 1)) / columns).toDp() }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEachIndexed { index, value ->
                    VideoMenuChoice(labels[index], value == seconds, Modifier.width(width), horizontalPadding = 4.dp) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onChange(value)
                    }
                }
            }
        }
    }
}
