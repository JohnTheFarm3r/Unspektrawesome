package com.unspektrawesome.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.unspektrawesome.language.AppLanguage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.settings.CompositionGrid
import kotlin.math.cos
import kotlin.math.sin

internal enum class VideoMenuGlyph { FRAME_RATE, BITRATE, OIS, GYRO_DATA, STABILIZATION, CROP, SMOOTHING, STRENGTH, GRID, RESET }

internal val VideoQuickMenuShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tip = with(density) { 8.dp.toPx() }
        val radius = with(density) { 14.dp.toPx() }
        return Outline.Generic(Path().apply {
            moveTo(size.width / 2 - tip, tip)
            lineTo(size.width / 2, 0f)
            lineTo(size.width / 2 + tip, tip)
            lineTo(size.width - radius, tip)
            quadraticTo(size.width, tip, size.width, tip + radius)
            lineTo(size.width, size.height - radius)
            quadraticTo(size.width, size.height, size.width - radius, size.height)
            lineTo(radius, size.height)
            quadraticTo(0f, size.height, 0f, size.height - radius)
            lineTo(0f, tip + radius)
            quadraticTo(0f, tip, radius, tip)
            close()
        })
    }
}

@Composable
internal fun VideoMenuIcon(glyph: VideoMenuGlyph, modifier: Modifier = Modifier, color: Color = CameraColors.Text) {
    Canvas(modifier.size(22.dp)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(1.5f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), stroke.width, StrokeCap.Round)
        fun path(vararg coordinates: Float) {
            drawPath(Path().apply {
                moveTo(coordinates[0] * unit, coordinates[1] * unit)
                for (i in 2 until coordinates.size step 2) lineTo(coordinates[i] * unit, coordinates[i + 1] * unit)
            }, color, style = stroke)
        }
        when (glyph) {
            VideoMenuGlyph.FRAME_RATE -> {
                drawCircle(color, 10 * unit, point(12f, 12f), style = stroke)
                repeat(6) { index ->
                    rotate(index * 60f, point(12f, 12f)) { path(7f, 3.4f, 12f, 12f, 17f, 12f) }
                }
            }
            VideoMenuGlyph.BITRATE -> {
                drawRoundRect(color, point(3f, 2f), Size(18 * unit, 20 * unit), CornerRadius(unit), style = stroke)
                line(7f, 2f, 7f, 22f); line(17f, 2f, 17f, 22f)
                for (y in 6..18 step 4) { line(3f, y.toFloat(), 7f, y.toFloat()); line(17f, y.toFloat(), 21f, y.toFloat()) }
            }
            VideoMenuGlyph.OIS -> {
                drawCircle(color, 7 * unit, point(12f, 12f), style = stroke)
                drawCircle(color, 3 * unit, point(12f, 12f), style = stroke)
                repeat(8) { index ->
                    val angle = index * Math.PI / 4
                    line(12 + 9 * cos(angle).toFloat(), 12 + 9 * sin(angle).toFloat(),
                        12 + 11 * cos(angle).toFloat(), 12 + 11 * sin(angle).toFloat())
                }
            }
            VideoMenuGlyph.GYRO_DATA -> {
                drawOval(color, point(3f, 2f), Size(18 * unit, 7 * unit), style = stroke)
                line(3f, 5.5f, 3f, 19f); line(21f, 5.5f, 21f, 19f)
                for (y in listOf(8f, 15.5f)) drawArc(color, 0f, 180f, false, point(3f, y), Size(18 * unit, 7 * unit), style = stroke)
            }
            VideoMenuGlyph.STABILIZATION -> {
                drawRoundRect(color, point(9f, 2f), Size(6 * unit, 20 * unit), CornerRadius(3 * unit), style = stroke)
                drawOval(color, point(6f, 6f), Size(12 * unit, 12 * unit), style = stroke)
                drawArc(color, 125f, 110f, false, point(1f, 4f), Size(22 * unit, 16 * unit), style = stroke)
                drawArc(color, -55f, 110f, false, point(1f, 4f), Size(22 * unit, 16 * unit), style = stroke)
            }
            VideoMenuGlyph.CROP -> {
                path(6f, 1f, 6f, 18f, 23f, 18f)
                path(1f, 6f, 18f, 6f, 18f, 23f)
            }
            VideoMenuGlyph.SMOOTHING -> drawPath(Path().apply {
                moveTo(unit, 12 * unit)
                cubicTo(7 * unit, 12 * unit, 5 * unit, 2 * unit, 9 * unit, 5 * unit)
                cubicTo(13 * unit, 8 * unit, 12 * unit, 23 * unit, 17 * unit, 19 * unit)
                cubicTo(20 * unit, 17 * unit, 17 * unit, 12 * unit, 23 * unit, 12 * unit)
            }, color, style = stroke)
            VideoMenuGlyph.STRENGTH -> repeat(4) { index ->
                line(4f + index * 5, 21f, 4f + index * 5, 16f - index * 4)
            }
            VideoMenuGlyph.GRID -> repeat(4) { index ->
                drawRoundRect(color, point(2f + index % 2 * 11, 2f + index / 2 * 11),
                    Size(9 * unit, 9 * unit), CornerRadius(unit), style = stroke)
            }
            VideoMenuGlyph.RESET -> {
                drawArc(color, 205f, 135f, false, point(3f, 3f), Size(18 * unit, 18 * unit), style = stroke)
                drawArc(color, 25f, 135f, false, point(3f, 3f), Size(18 * unit, 18 * unit), style = stroke)
                path(21f, 2f, 21f, 9f, 14f, 9f); path(3f, 22f, 3f, 15f, 10f, 15f)
            }
        }
    }
}

@Composable
internal fun VideoMenuChoice(label: String, selected: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, horizontalPadding: androidx.compose.ui.unit.Dp = 8.dp, description: String? = null, onClick: () -> Unit) {
    val ink by animateColorAsState(if (!enabled) CameraColors.Muted.copy(alpha = .4f)
        else if (selected) CameraColors.Accent else CameraColors.Text, tween(180), label = "Video choice ink")
    val outline by animateColorAsState(if (selected) CameraColors.Accent else CameraColors.MenuBorder,
        tween(180), label = "Video choice outline")
    val background by animateColorAsState(if (selected) CameraColors.MenuSelectedCard else CameraColors.MenuCard,
        tween(180), label = "Video choice background")
    val shape = CameraStyle.ControlShape
    Box(modifier.width(IntrinsicSize.Max).heightIn(min = 36.dp).clip(shape)
        .border(1.dp, outline, shape).selectable(selected, enabled, Role.RadioButton, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(description ?: label) }
        .padding(horizontal = horizontalPadding, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = ink, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun VideoMenuFooter(grid: CompositionGrid, onGrid: (CompositionGrid) -> Unit, onReset: () -> Unit) {
    val gridInk by animateColorAsState(if (grid == CompositionGrid.OFF) CameraColors.Muted else CameraColors.Accent,
        tween(180), label = "Video grid ink")
    HorizontalDivider(color = CameraColors.MenuBorder, modifier = Modifier.padding(top = 2.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val titleStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp)
        val labelStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp)
        val labels = CompositionGrid.entries.map { AppLanguage.text(if (it == CompositionGrid.OFF) "OFF" else it.label) }
        val longestWord = with(density) {
            (labels + AppLanguage.text("Grid") + AppLanguage.text("Reset look")).flatMap { it.split(' ') }
                .maxOf { measurer.measure(it, titleStyle).size.width }.toDp()
        }
        val stacked = density.fontScale > 1.3f || longestWord > (maxWidth - 5.dp) / 2 - 48.dp
        val buttonWidth = if (stacked) maxWidth else with(density) { (maxWidth.roundToPx() / 2).toDp() }
        val gridTitleWidth = with(density) { measurer.measure(AppLanguage.text("Grid"), titleStyle).size.width.toDp() + 2.dp }
        val textWidth = with(density) { (buttonWidth - 56.dp - gridTitleWidth).roundToPx().coerceAtLeast(1) }
        val titleHeight = with(density) {
            measurer.measure(AppLanguage.text("Grid"), titleStyle, constraints = Constraints(maxWidth = textWidth)).size.height.toDp()
        }
        val labelHeight = with(density) {
            labels.maxOf { measurer.measure(it, labelStyle, constraints = Constraints(maxWidth = textWidth)).size.height }.toDp()
        }
        val gridHeight = maxOf(44.dp, maxOf(titleHeight, labelHeight) + 12.dp)
        val resetHeight = with(density) {
            measurer.measure(AppLanguage.text("Reset look"), titleStyle,
                constraints = Constraints(maxWidth = (buttonWidth - 64.dp).roundToPx().coerceAtLeast(1))).size.height.toDp()
        }.plus(8.dp).coerceAtLeast(44.dp)
        FlowRow(Modifier.fillMaxWidth().testTag("Camera menu footer"),
            horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.width(buttonWidth).heightIn(min = if (stacked) gridHeight else maxOf(gridHeight, resetHeight))
                .clip(CameraStyle.ControlShape)
                .clickable(role = Role.Button) { onGrid(CompositionGrid.entries[(grid.ordinal + 1) % CompositionGrid.entries.size]) }
                .languageSemantics { language -> contentDescription = language.text("Composition grid"); stateDescription = language.text(grid.label) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_grid_3x3, Modifier.size(16.dp).testTag("Grid icon ${grid.name}"), gridInk)
                Text("Grid", color = CameraColors.Text, style = titleStyle, modifier = Modifier.width(gridTitleWidth))
                Text(if (grid == CompositionGrid.OFF) "OFF" else grid.label,
                    modifier = Modifier.weight(1f).testTag("Grid type label"), color = gridInk, style = titleStyle, textAlign = TextAlign.End)
            }
            Row(Modifier.width(buttonWidth).heightIn(min = if (stacked) resetHeight else maxOf(gridHeight, resetHeight))
                .clip(CameraStyle.ControlShape).clickable(role = Role.Button, onClick = onReset).testTag("Reset video look")
                .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_rotate_ccw, Modifier.size(16.dp))
                Text("Reset look", Modifier.weight(1f), color = CameraColors.Text, style = titleStyle)
                CameraGlyphIcon(CameraGlyph.Chevron, Modifier.size(12.dp), CameraColors.Muted)
            }
        }
    }
}
