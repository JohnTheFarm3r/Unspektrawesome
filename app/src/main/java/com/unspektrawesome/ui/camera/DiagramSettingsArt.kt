package com.unspektrawesome.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.UiOrientation

// Drawings and row helpers for the camera settings pages. Coordinates are artboard dp.

private val Lime = CameraColors.Accent
private val Soft = Color(0xFFCFCFCF)
private val Dim = Color(0xFF8C8C8C)
private val Faint = Color(0xFF3A3A3A)
private val Rule = Color(0xFF2A2A2A)
private val Mid = Color(0xFF6A6A6A)

private fun Number.u(): Dp = toFloat().dp

internal fun pts(vararg values: Number): List<Pair<Dp, Dp>> =
    values.toList().chunked(2) { (x, y) -> x.u() to y.u() }

private fun DrawScope.p(value: Number) = value.toFloat().dp.toPx()

private fun DiagramPen.rbox(x: Number, y: Number, w: Number, h: Number, r: Number = 6, color: Color = Diagram.Ink,
    stroke: Number = 1.5, dashed: Boolean = false, fill: Color? = Color.Black) =
    box(x.u(), y.u(), w.u(), h.u(), r.u(), color, stroke.u(), dashed, fill)

private fun DiagramPen.fill(x: Number, y: Number, w: Number, h: Number, color: Color, r: Number = 0) = with(scope) {
    drawRoundRect(color, Offset(p(x), p(y)), Size(p(w), p(h)), CornerRadius(p(r)))
}

private fun DiagramPen.ln(x1: Number, y1: Number, x2: Number, y2: Number, color: Color = Diagram.Ink, stroke: Number = 1.5,
    dashed: Boolean = false) = line(x1.u(), y1.u(), x2.u(), y2.u(), color, stroke.u(), dashed)

private fun DiagramPen.ring(cx: Number, cy: Number, r: Number, color: Color = Diagram.Ink, stroke: Number = 1.5,
    dashed: Boolean = false, fill: Color? = null) = circle(cx.u(), cy.u(), r.u(), color, stroke.u(), dashed, fill)

private fun DiagramPen.spot(cx: Number, cy: Number, r: Number = 3, color: Color = Lime) = dot(cx.u(), cy.u(), r.u(), color)

private fun DiagramPen.poly(points: List<Pair<Dp, Dp>>, color: Color = Diagram.Ink, stroke: Number = 1.5, dashed: Boolean = false) =
    polyline(points, color, stroke.u(), dashed)

/** A connector that is lime and solid on the current branch, grey and dashed otherwise. */
private fun DiagramPen.route(points: List<Pair<Dp, Dp>>, active: Boolean, arrow: Boolean = true, color: Color? = null) {
    val ink = color ?: if (active) Lime else Diagram.Line
    if (!arrow || points.size < 2) { polyline(points, ink, dashed = !active); return }
    if (points.size > 2) polyline(points.dropLast(1), ink, dashed = !active)
    val (a, b) = points[points.size - 2] to points.last()
    arrow(a.first, a.second, b.first, b.second, ink, dashed = !active)
}

private fun DiagramPen.curve(x1: Number, y1: Number, c1x: Number, c1y: Number, c2x: Number, c2y: Number, x2: Number, y2: Number,
    color: Color, stroke: Number = 1.5, dashed: Boolean = false) = with(scope) {
    val path = Path().apply { moveTo(p(x1), p(y1)); cubicTo(p(c1x), p(c1y), p(c2x), p(c2y), p(x2), p(y2)) }
    drawPath(path, color, style = Stroke(p(stroke), cap = StrokeCap.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
}

private fun DiagramPen.arc(cx: Number, cy: Number, r: Number, start: Float, sweep: Float, color: Color, stroke: Number = 1.5) = with(scope) {
    drawArc(color, start, sweep, false, Offset(p(cx) - p(r), p(cy) - p(r)), Size(p(r) * 2, p(r) * 2),
        style = Stroke(p(stroke), cap = StrokeCap.Round))
}

private fun DiagramPen.pin(cx: Float, top: Float, active: Boolean) {
    val color = if (active) Lime else Diagram.Line
    poly(pts(cx - 4.4f, top + 7.5f, cx, top + 14f, cx + 4.4f, top + 7.5f), color, 1.2)
    ring(cx, top + 5f, 5, color, 1.2, dashed = !active, fill = Color.Black)
    if (active) spot(cx, top + 5f, 1.6)
}

private fun DiagramPen.lock(x: Float, y: Float, closed: Boolean, color: Color) {
    rbox(x + 1, y + 6, 10, 8, 1.5, color, 1.3, fill = null)
    if (closed) arc(x + 6, y + 6, 2.5, 180f, 180f, color, 1.3)
    else arc(x + 6, y + 4, 2.5, 180f, 150f, color, 1.3)
}

private fun DiagramPen.mountains(points: List<Pair<Dp, Dp>>, color: Color = Faint) = poly(points, color, 1)

// Stage text

internal enum class NoteTone { Plain, On, Dim }

@Composable
private fun Txt(text: String, x: Number, y: Number, color: Color = Diagram.Hint, size: Float = 10f,
    weight: FontWeight = FontWeight.Normal, translate: Boolean = true, mono: Boolean = false, width: Number? = null,
    maxWidth: Number = 160, align: TextAlign = TextAlign.Start, maxLines: Int = 2) {
    val modifier = Modifier.at(x.u(), y.u()).then(if (width != null) Modifier.width(width.u()) else Modifier)
    DiagramText(text, modifier, color, size, weight, translate, mono, (width ?: maxWidth).u(), align, maxLines)
}

/** Text centred on [cx]. */
@Composable
private fun TxtC(text: String, cx: Number, y: Number, width: Number, color: Color = Diagram.Hint, size: Float = 10f,
    weight: FontWeight = FontWeight.Normal, translate: Boolean = true, mono: Boolean = false, maxLines: Int = 2) =
    Txt(text, cx.toFloat() - width.toFloat() / 2, y, color, size, weight, translate, mono, width, align = TextAlign.Center,
        maxLines = maxLines)

@Composable
private fun Cap(text: String, x: Number, y: Number, color: Color = Diagram.Hint, maxWidth: Number = 220, size: Float = 9.5f) {
    val renderer = LocalAppLanguage.current.renderer
    Text(renderer.text(text).uppercase(), Modifier.at(x.u(), y.u()).widthIn(max = maxWidth.u()), translate = false, color = color,
        fontSize = size.sp, lineHeight = (size + 3).sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
        overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Note(title: String, detail: String?, x: Number, y: Number, tone: NoteTone = NoteTone.Plain, maxWidth: Number = 120,
    translate: Boolean = true) {
    val (head, sub) = when (tone) {
        NoteTone.Plain -> CameraColors.Text to Diagram.Hint
        NoteTone.On -> Lime to Soft
        NoteTone.Dim -> Diagram.Hint to Dim
    }
    Column(Modifier.at(x.u(), y.u()).widthIn(max = maxWidth.u())) {
        DiagramText(title, color = head, size = 10.5f, weight = FontWeight.SemiBold, translate = translate, maxWidth = maxWidth.u())
        detail?.let { DiagramText(it, color = sub, size = 9f, maxWidth = maxWidth.u()) }
    }
}

/** Caption, current value and a short explanation. */
@Composable
private fun Lab(caption: String?, value: String?, em: String?, x: Number, y: Number, active: Boolean = true, maxWidth: Number = 130,
    valueTranslate: Boolean = true, mono: Boolean = false, valueColor: Color? = null, emLines: Int = 2, valueLines: Int = 2) {
    val renderer = LocalAppLanguage.current.renderer
    Column(Modifier.at(x.u(), y.u()).widthIn(max = maxWidth.u())) {
        caption?.let {
            Text(renderer.text(it).uppercase(), translate = false, color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 12.sp,
                letterSpacing = .6.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        value?.let {
            Text(it, translate = valueTranslate, color = valueColor ?: if (active) Lime else Dim, fontSize = 12.sp, lineHeight = 15.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, fontFamily = if (mono) Diagram.Mono else null,
                maxLines = valueLines, overflow = TextOverflow.Ellipsis)
        }
        em?.let { Text(it, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp, maxLines = emLines, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Num(number: Int, x: Number, y: Number, active: Boolean = true) = DiagramNumber(number, Modifier.at(x.u(), y.u()), active)

internal enum class LegendLine { Current, Other, Always }

@Composable
private fun Legend(x: Number, y: Number, items: List<Pair<String, LegendLine>>, width: Number = 310) {
    // Wraps onto a second row when translated names do not fit side by side.
    FlowRow(Modifier.at(x.u(), y.u()).widthIn(max = width.u()), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, kind) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(18.dp, 4.dp)) {
                    val color = when (kind) { LegendLine.Current -> Lime; LegendLine.Other -> Mid; LegendLine.Always -> Soft }
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(),
                        pathEffect = if (kind == LegendLine.Other) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
                }
                DiagramText(label, size = 9.5f, maxWidth = 120.dp)
            }
        }
    }
}

// Rows

/** Card title over a section label on the right, used to start each part of a page. */
@Composable
internal fun SetupPartHeader(title: String, section: String) {
    val renderer = LocalAppLanguage.current.renderer
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        val shown = renderer.text(section)
        Text(shown.uppercase(), translate = false, color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 14.sp,
            letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 2.dp).clearAndSetSemantics { text = AnnotatedString(shown) })
    }
}

/**
 * Small uppercase section label with an optional number that matches a callout in the drawing above.
 * Screen readers get the label in its normal case.
 */
@Composable
internal fun SetupSection(label: String, number: Int? = null) {
    val shown = LocalAppLanguage.current.renderer.text(label)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        number?.let { DiagramNumber(it) }
        Text(shown.uppercase(), translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).clearAndSetSemantics { heading(); text = AnnotatedString(shown) })
    }
}

/** A drawing stage. The drawing repeats what the rows below say, so it stays out of the accessibility tree. */
@Composable
private fun SetupStage(height: Int, content: @Composable BoxScope.() -> Unit) {
    DiagramStage(height.dp) { Box(Modifier.matchParentSize().clearAndSetSemantics { }, content = content) }
}

/** A numbered title with a full-width segmented choice under it, wrapping on long translations. */
@Composable
internal fun SetupChoice(
    title: String?,
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    number: Int? = null,
    detail: String? = null,
    translate: Boolean = true,
    enabled: (Int) -> Boolean = { true },
    segmentedModifier: Modifier = Modifier,
    choiceModifier: (Int) -> Modifier = { Modifier },
    onSelect: (Int) -> Unit,
) {
    Column(modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp).padding(top = 6.dp, bottom = 4.dp)) {
        if (title == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                number?.let { DiagramNumber(it) }
                DiagramSegmented(labels, selected, segmentedModifier.weight(1f), enabled, translate, choiceModifier, onSelect)
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                number?.let { DiagramNumber(it) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    detail?.let { Text(it, color = Dim, fontSize = 11.sp, lineHeight = 15.sp) }
                }
            }
            DiagramSegmented(labels, selected, segmentedModifier, enabled, translate, choiceModifier, onSelect)
        }
    }
}

/** Action row with a small outlined icon, a title and an optional file name. */
@Composable
internal fun SetupActionRow(title: String, symbol: SettingsSymbol, modifier: Modifier = Modifier, detail: String? = null,
    number: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp).alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(26.dp).border(1.dp, Diagram.Border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            SettingsIcon(symbol, Modifier.size(14.dp), Soft)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            detail?.let { Text(it, translate = false, color = Dim, fontSize = 9.5.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono,
                modifier = Modifier.clearAndSetSemantics { }) }
        }
        number?.let { DiagramNumber(it) }
    }
}

/** Guide replay row: title, step count, chevron and one dot per step. */
@Composable
internal fun SetupGuideRow(title: String, steps: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).diagramHairline()
        .padding(horizontal = 4.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            Text("{count} steps", arguments = mapOf("count" to steps), color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp,
                fontFamily = Diagram.Mono)
            CameraGlyphIcon(CameraGlyph.Chevron, Modifier.size(14.dp), Lime)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val y = size.height / 2
            val inset = 4.dp.toPx()
            drawLine(Faint, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
            val count = steps.coerceAtLeast(1)
            for (index in 0 until count) {
                val x = if (count == 1) inset else inset + (size.width - inset * 2) * index / (count - 1)
                if (index == 0) drawCircle(Lime, 3.dp.toPx(), Offset(x, y))
                else {
                    drawCircle(Color.Black, 2.2.dp.toPx(), Offset(x, y))
                    drawCircle(Dim, 2.2.dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

/** Two by two picture tiles for the control orientation choices. */
@Composable
internal fun SetupOrientationTiles(selected: UiOrientation, modifier: Modifier = Modifier, onSelect: (UiOrientation) -> Unit) {
    Column(modifier.fillMaxWidth().selectableGroup().diagramHairline().padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UiOrientation.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    val on = option == selected
                    Column(Modifier.weight(1f).fillMaxHeight().clip(Diagram.ControlShape)
                        .background(if (on) Lime.copy(alpha = .04f) else Diagram.Glass)
                        .border(1.dp, if (on) Lime else Diagram.Border, Diagram.ControlShape)
                        .selectable(on, role = Role.RadioButton) { onSelect(option) }
                        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        OrientationGlyph(option, on)
                        Text(option.label, color = if (on) Lime else CameraColors.Text, fontSize = 12.5.sp, lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium)
                        Text(option.detail, color = if (on) Soft else Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OrientationGlyph(option: UiOrientation, on: Boolean) {
    val portraitPhone = option == UiOrientation.LANDSCAPE
    val turned = option == UiOrientation.PORTRAIT || option == UiOrientation.LANDSCAPE
    Box(Modifier.size(64.dp, 36.dp).clearAndSetSemantics { }) {
        DiagramDrawing {
            if (portraitPhone) rbox(18, 1, 22, 34, 4, Soft, 1.3, fill = null) else rbox(4, 6, 46, 26, 5, Soft, 1.3, fill = null)
            when (option) {
                UiOrientation.AUTOMATIC -> lock(53f, 4f, true, Diagram.Hint)
                else -> Unit
            }
        }
        if (option == UiOrientation.FOLLOW_SYSTEM)
            SettingsIcon(SettingsSymbol.RotateClockwise, Modifier.offset(52.dp, 6.dp).size(12.dp), Lime)
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
            Box(Modifier.offset(if (portraitPhone) 17.dp else 15.dp, 10.dp).size(24.dp, 16.dp), contentAlignment = Alignment.Center) {
                Text("Aa", translate = false, color = if (on && option == UiOrientation.FOLLOW_SYSTEM) Lime else Diagram.Ink,
                    fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.rotate(if (turned) -90f else 0f))
            }
        }
    }
}

// General

@Composable
internal fun SetupExposureStage(spektra: Boolean, median: Boolean, overlay: Boolean) {
    val center = spektra && !median
    val whole = spektra && median
    SetupStage(344) {
        DiagramDrawing {
            rbox(14, 58, 52, 40, 4)
            val red = Color(0x73FF7974); val green = Color(0x738DDB7B); val blue = Color(0x807FA7FF)
            for (row in 0..2) for (col in 0..3) {
                val color = if (row % 2 == 0) (if (col % 2 == 0) red else green) else (if (col % 2 == 0) green else blue)
                fill(22 + col * 9, 64 + row * 9, 9, 9, color)
            }
            ln(66, 78, 84, 78, Lime)
            route(pts(84, 78, 84, 48, 97, 48), !spektra)
            route(pts(84, 78, 84, 112, 97, 112), spektra)
            rbox(100, 26, 112, 44, 6, if (!spektra) Lime else Diagram.Line, dashed = spektra)
            rbox(100, 92, 112, 44, 6, if (spektra) Lime else Diagram.Line, dashed = !spektra)
            route(pts(212, 48, 226, 48, 226, 79), !spektra, arrow = false)
            route(pts(212, 112, 226, 112, 226, 79), spektra, arrow = false)
            arrow(226.dp, 79.dp, 237.dp, 79.dp, Lime)
            rbox(240, 54, 84, 50, 6)
            route(pts(156, 136, 156, 146, 192, 146, 192, 156), whole, arrow = false)
            route(pts(156, 146, 108, 146, 108, 156), center, arrow = false)
            rbox(76, 158, 64, 64, 4, if (center) Lime else Diagram.Line, if (center) 1.5 else 1)
            ring(108, 190, 26, Color(0xFF2E2E2E), 1, fill = Color.White.copy(alpha = .04f))
            ring(108, 190, 17, Faint, 1, fill = Color.White.copy(alpha = .07f))
            ring(108, 190, 8, Diagram.Line, 1, fill = Color.White.copy(alpha = .22f))
            rbox(160, 158, 64, 64, 4, if (whole) Lime else Diagram.Line, if (whole) 1.5 else 1)
            for (i in 1..3) { ln(160 + i * 16, 158, 160 + i * 16, 222, Color(0xFF262626), 1); ln(160, 158 + i * 16, 224, 158 + i * 16, Color(0xFF262626), 1) }
            for (row in 0..3) for (col in 0..3) spot(168 + col * 16, 166 + row * 16, 2, if (whole) Lime.copy(alpha = .6f) else Diagram.Line)
            rbox(252, 122, 70, 146, 12)
            rbox(258, 134, 58, 84, 3, Faint, 1, dashed = true, fill = null)
            mountains(pts(258, 190, 274, 180, 288, 188, 302, 176, 316, 186))
            rbox(262, 140, 30, 13, 6.5, if (overlay) Lime else Diagram.Line, 1, dashed = !overlay)
            fill(258, 224, 58, 38, Color.White.copy(alpha = .05f), 3)
            ring(287, 243, 9, Diagram.Ink, 1)
            rbox(264, 238, 10, 10, 2, Diagram.Line, 1, fill = null)
            ring(306, 243, 4, Diagram.Line, 1)
        }
        Cap("Who sets exposure", 12, 8, maxWidth = 300)
        Note("RAW frame", "from the sensor", 14, 102, maxWidth = 66)
        Note("Android", "camera system decides", 110, 30, if (spektra) NoteTone.Dim else NoteTone.On, 98)
        Note("Unspektra AE", "app meters the image", 110, 96, if (spektra) NoteTone.On else NoteTone.Dim, 98)
        Note("Exposure", "Shutter speed and ISO", 247, 59, maxWidth = 72)
        TxtC("AE ⇄", 277, 141.5, 30, if (overlay) Lime else Diagram.Hint, 7f, FontWeight.SemiBold, translate = false, maxLines = 1)
        Num(1, 75, 69)
        Lab("Metering", if (!spektra) "With Unspektra AE" else if (median) "Median" else "Center weighted", null, 14, 164, spektra, 60,
            valueLines = 3)
        Note("Center weighted", "center counts more", 62, 228, if (center) NoteTone.On else NoteTone.Dim, 92)
        Note("Median", "whole frame", 160, 228, if (whole) NoteTone.On else NoteTone.Dim, 54)
        if (spektra) Num(2, 215, 149)
        Num(3, 243, 138)
        Lab("AE switch overlay", if (overlay) "On" else "Off", "tap to switch AE", 216, 272, overlay, 116)
        Legend(14, 314, listOf("Current choice" to LegendLine.Current, "Other choice" to LegendLine.Other), 196)
    }
}

@Composable
internal fun SetupPhotoSavingStage(later: Boolean, location: Boolean) {
    val now = !later
    SetupStage(180) {
        DiagramDrawing {
            pin(28f, 40f, location)
            ln(28, 60, 28, 76, Dim, 1, dashed = true)
            ring(28, 90, 13, fill = Color.Black)
            ring(28, 90, 6, Lime, 1)
            ln(41, 90, 56, 90, Lime)
            poly(pts(64, 82, 72, 90, 64, 98, 56, 90, 64, 82), Lime)
            route(pts(64, 82, 64, 44, 92, 44), now)
            rbox(94, 32, 80, 24, 4, if (now) Diagram.Ink else Diagram.Line, dashed = later)
            route(pts(174, 44, 198, 44), now)
            rbox(200, 32, 44, 24, 4, if (now) Lime else Diagram.Line, dashed = later)
            route(pts(64, 98, 64, 136, 92, 136), later)
            rbox(94, 124, 40, 24, 4, if (later) Diagram.Ink else Diagram.Line, dashed = now)
            route(pts(134, 136, 158, 136), later)
            rbox(160, 124, 56, 24, 4, if (later) Diagram.Ink else Diagram.Line, dashed = now)
            route(pts(216, 136, 240, 136), later)
            rbox(242, 124, 62, 24, 4, if (later) Lime else Diagram.Line, dashed = now)
        }
        Cap("Process now", 96, 16, if (now) Lime else Dim, 170, 8.5f)
        Cap("Later in Gallery", 96, 108, if (later) Lime else Dim, 200, 8.5f)
        TxtC("spektrafilm", 134, 38, 78, if (now) CameraColors.Text else Dim, 9.5f, translate = false, mono = true, maxLines = 1)
        TxtC("JPEG", 222, 38, 42, if (now) Lime else Dim, 9.5f, translate = false, mono = true, maxLines = 1)
        TxtC("saved now", 222, 60, 76, if (now) Lime else Diagram.Hint, 9f)
        TxtC("DNG", 114, 130, 38, if (later) CameraColors.Text else Dim, 9.5f, translate = false, mono = true, maxLines = 1)
        TxtC("Queue", 188, 130, 54, if (later) CameraColors.Text else Dim, 9.5f, mono = true, maxLines = 1)
        TxtC("Export", 273, 130, 60, if (later) Lime else Dim, 9.5f, mono = true, maxLines = 1)
        TxtC("look · crop", 114, 152, 72, if (later) Lime else Diagram.Hint, 9f)
        TxtC("Gallery", 188, 152, 62, Diagram.Hint, 9f)
        TxtC("later", 273, 152, 62, if (later) Lime else Diagram.Hint, 9f)
        TxtC("Shutter", 28, 106, 54, Diagram.Hint, 9f)
        Num(1, 70, 70)
        Num(2, 38, 34, location)
    }
}

/** What tapping and holding the camera thumbnail opens in each processing choice. */
@Composable
internal fun SetupThumbnailStage(later: Boolean) {
    val now = !later
    val nowInk = if (now) Lime else Mid
    val laterInk = if (later) Lime else Mid
    SetupStage(300) {
        DiagramDrawing {
            rbox(14, 14, 96, 192, 14, fill = null)
            rbox(21, 21, 82, 126, 6, Color(0xFF4A4A4A), 1, dashed = true, fill = null)
            mountains(pts(27, 112, 50, 92, 64, 104, 80, 86, 97, 108), Diagram.Line)
            ring(80, 44, 7, Diagram.Line, 1.2)
            fill(21, 150, 82, 49, Color.White.copy(alpha = .07f), 6)
            ring(62, 174, 12)
            ring(62, 174, 8, Color.Transparent, 0, fill = Diagram.Ink.copy(alpha = .15f))
            rbox(28, 165, 18, 18, 3, nowInk, 1.5, fill = nowInk.copy(alpha = .15f))
            curve(46, 170, 80, 170, 108, 56, 140, 56, nowInk, dashed = later)
            route(pts(140, 56, 192, 56), now, color = if (now) null else Mid)
            curve(46, 178, 90, 178, 110, 136, 140, 136, nowInk, dashed = later)
            route(pts(140, 136, 192, 136), now, color = if (now) null else Mid)
            spot(46, 174, 3, nowInk)
            ring(164, 136, 8, Faint, 2, fill = Color.Black)
            arc(164, 136, 8, -90f, 270f, nowInk, 2)
            rbox(196, 24, 54, 64, 6, if (now) Diagram.Ink else Mid, dashed = later)
            rbox(201, 34, 44, 34, 2, Dim, 1, fill = null)
            poly(pts(203, 64, 216, 51, 224, 59, 232, 49, 243, 64), nowInk, 1.2)
            for (i in 0..2) fill(205 + i * 13, 76, 9, 6, Diagram.Line, 1)
            rbox(196, 106, 54, 64, 6, if (now) Diagram.Ink else Mid, dashed = later)
            for (row in 0..2) for (col in 0..1) rbox(201 + col * 23, 112 + row * 19, 21, 15, 1, Dim, 1, fill = null)
            ln(10, 232, 324, 232, Rule, 1, dashed = true)
            rbox(28, 262, 18, 18, 3, laterInk, 1.2, fill = if (later) Lime.copy(alpha = .15f) else null)
            route(pts(46, 271, 192, 271), later, color = if (later) null else Mid)
            rbox(196, 250, 54, 42, 5, if (later) Diagram.Ink else Mid, dashed = now)
            for (row in 0..1) for (col in 0..1) rbox(201 + col * 23, 255 + row * 18, 21, 14, 1, Diagram.Line, 1, fill = null)
        }
        Cap("Process now", 196, 4, if (now) Lime else Diagram.Hint, 136, 8.5f)
        Cap("Later in Gallery", 36, 241, if (later) Lime else Diagram.Hint, 160, 8.5f)
        Txt("Tap", 146, 41, nowInk, 9f, mono = true, maxWidth = 44)
        Txt("Hold 0.5 s", 128, 149, nowInk, 9f, mono = true, maxWidth = 66)
        Txt("Tap", 112, 256, laterInk, 9f, mono = true, maxWidth = 70)
        Note("Last photo", "Photo viewer", 256, 40, if (now) NoteTone.Plain else NoteTone.Dim, 76)
        Note("Gallery", "Photos and queue", 256, 122, if (now) NoteTone.Plain else NoteTone.Dim, 76)
        Note("Gallery", "Photos and queue", 256, 262, if (later) NoteTone.Plain else NoteTone.Dim, 76)
        TxtC("Camera thumbnail", 62, 209, 104, Diagram.Hint, 9f)
        Num(1, 174, 2, now)
        Num(1, 12, 239, later)
    }
}

@Composable
internal fun SetupBurstStage(seconds: Int, memoryBytes: Long) {
    val renderer = LocalAppLanguage.current.renderer
    val kept = seconds.coerceIn(1, com.unspektrawesome.burst.BurstMemoryPlan.MAX_SECONDS)
    val start = 280 - 50 * kept
    SetupStage(212) {
        DiagramDrawing {
            var x = 34
            while (x + 10 <= 276) {
                val inside = x >= start + 4
                rbox(x, 82, 10, 26, 1, if (inside) Lime else Faint, 1, dashed = !inside,
                    fill = if (inside) Lime.copy(alpha = .16f) else null)
                x += 14
            }
            rbox(start, 74, 280 - start, 40, 3, Lime, 1, fill = null)
            fill(start, 74, 280 - start, 40, Lime.copy(alpha = .06f), 3)
            ln(24, 118, 300, 118)
            for (second in 0..5) {
                val tick = 280 - second * 50
                ln(tick, 114, tick, 122, if (second == 0 || tick == start) Lime else Diagram.Ink, 1)
            }
            ln(280, 58, 280, 122, Lime)
            ring(280, 50, 8, Lime, fill = Color.Black)
            spot(280, 50, 4)
            ln(start + 25, 140, start + 25, 174, Lime, 1, dashed = true)
            rbox(150, 174, 172, 28, 6, Rule, 1)
            rbox(159, 183, 14, 10, 1.5, Lime, 1, fill = null)
            for (i in 0..2) { ln(162 + i * 4, 180, 162 + i * 4, 183, Lime, 1); ln(162 + i * 4, 193, 162 + i * 4, 196, Lime, 1) }
        }
        Cap("BURST · before shutter", 12, 10)
        if (start - 50 >= 90) Txt("older frames are not kept", 34, 56, Dim, 9.5f, maxWidth = start - 50)
        Txt(renderer.format("{value} s kept", mapOf("value" to kept)), start + 31, 143, Lime, 10f, translate = false, mono = true,
            maxWidth = (330 - start - 31).coerceAtMost(140))
        Txt("shutter press", 206, 30, Diagram.Hint, 9.5f, maxWidth = 64)
        for (second in 0..5) {
            val tick = 280 - second * 50
            val label = if (second == 0) "0" else renderer.format("{value} s", mapOf("value" to "−$second"))
            TxtC(label, tick, 126, 40, if (second == 0 || tick == start) Lime else Diagram.Hint, 9f, translate = false, mono = true, maxLines = 1)
        }
        Num(1, start - 9, 65)
        Txt("Held in phone memory", 14, 178, Diagram.Hint, 9.5f, maxWidth = 128)
        Box(Modifier.at(180.dp, 174.dp).size(138.dp, 28.dp), contentAlignment = Alignment.CenterStart) {
            DiagramText(if (memoryBytes > 0) renderer.format("Automatic RAM · {value1} MB",
                mapOf("value1" to (memoryBytes + 1024 * 1024 - 1) / (1024 * 1024))) else renderer.text("Automatic RAM"),
                color = Lime, size = 10f, translate = false, mono = true, maxWidth = 138.dp)
        }
    }
}

@Composable
internal fun SetupPassiveFocusStage(seconds: Int) {
    val renderer = LocalAppLanguage.current.renderer
    val on = seconds > 0
    SetupStage(200) {
        DiagramDrawing {
            rbox(16, 36, 92, 124, 8)
            ln(16, 132, 108, 132, Color(0xFF2E2E2E), 1)
            mountains(pts(16, 126, 34, 114, 50, 122, 70, 106, 108, 128))
            ring(92, 104, 7, Faint, 1)
            ln(92, 111, 92, 125, Faint, 1)
            if (on) arc(62, 84, 22, 20f, 300f, Lime.copy(alpha = .55f), 1.2)
            val bracket = if (on) Lime else Diagram.Line
            poly(pts(49, 77, 49, 71, 55, 71), bracket); poly(pts(75, 77, 75, 71, 69, 71), bracket)
            poly(pts(49, 91, 49, 97, 55, 97), bracket); poly(pts(75, 91, 75, 97, 69, 97), bracket)
            ln(128, 118, 322, 118, if (on) Diagram.Ink else Diagram.Line, dashed = !on)
            ln(132, 114, 132, 122, Diagram.Ink, 1)
            if (on) for (i in 1..4) {
                val x = 132 + i * 46
                ln(x, 102, x, 122, Lime)
                poly(pts(x - 7, 88, x - 7, 84, x - 3, 84), Lime, 1.3); poly(pts(x + 7, 88, x + 7, 84, x + 3, 84), Lime, 1.3)
                poly(pts(x - 7, 94, x - 7, 98, x - 3, 98), Lime, 1.3); poly(pts(x + 7, 94, x + 7, 98, x + 3, 98), Lime, 1.3)
            }
            if (on) poly(pts(178, 76, 178, 70, 224, 70, 224, 76), Lime, 1)
        }
        Cap("Restart passive focus", 12, 10)
        Note("Passive focus", "restarts on a timer", 16, 166, maxWidth = 110)
        TxtC(renderer.format("{value} s", mapOf("value" to 0)), 132, 127, 34, Diagram.Hint, 9f, translate = false, mono = true, maxLines = 1)
        if (on) {
            for (i in 1..4) TxtC(renderer.format("{value} s", mapOf("value" to i * seconds)), 132 + i * 46, 127, 40,
                if (i == 1) Lime else Diagram.Hint, 9f, translate = false, mono = true, maxLines = 1)
            TxtC(renderer.format("every {value} s", mapOf("value" to seconds)), 201, 55, 70, Lime, 9.5f, translate = false, mono = true, maxLines = 1)
            Num(1, 234, 61)
            Txt("With Off, focus is not restarted on a timer.", 132, 150, Diagram.Hint, 9.5f, maxWidth = 190)
        } else {
            Num(1, 234, 100)
            Txt("Off: focus is not restarted on a timer.", 132, 140, Diagram.Hint, 9.5f, maxWidth = 190)
        }
    }
}

@Composable
internal fun SetupModeSwipeStage(enabled: Boolean) {
    val renderer = LocalAppLanguage.current.renderer
    val modes = CaptureMode.entries
    val photo = CaptureMode.PHOTO
    fun name(mode: CaptureMode) = renderer.text(mode.label)
    SetupStage(326) {
        DiagramDrawing {
            rbox(22, 34, 140, 236, 18)
            rbox(30, 46, 124, 160, 4, Faint, 1, fill = Color.White.copy(alpha = .02f))
            ln(30, 186, 154, 186, Color(0xFF2E2E2E), 1)
            mountains(pts(30, 172, 58, 152, 80, 164, 106, 144, 154, 170))
            ring(134, 150, 8, Faint, 1)
            ln(134, 158, 134, 172, Faint, 1)
            route(pts(48, 70, 130, 70), false, color = if (enabled) Diagram.Line else Faint)
            route(pts(131, 128, 46, 128), enabled, color = if (enabled) null else Faint)
            if (enabled) ring(140, 128, 8, Lime, 1.2, fill = Lime.copy(alpha = .2f))
            rbox(74, 92, 36, 16, 8, if (enabled) Lime else Diagram.Line, 1)
            ln(83, 226, 101, 226, Lime, 1.3)
            ring(122, 219, 9, Lime, 1, dashed = true)
            rbox(40, 240, 16, 16, 3, Diagram.Line, 1, fill = null)
            ring(92, 248, 12)
            ring(92, 248, 8.5, Diagram.Ink, 0, fill = Diagram.Ink)
            ring(140, 248, 6, Diagram.Line, 1)
            ln(149, 128, 176, 128, if (enabled) Lime.copy(alpha = .7f) else Faint, 1)
            ln(134, 70, 176, 70, Color.White.copy(alpha = .3f), 1)
            ln(131, 219, 176, 219, Lime.copy(alpha = .5f), 1)
            spot(176, 128, 2.5, if (enabled) Lime else Diagram.Line)
            rbox(12, 288, 308, 30, 6, Rule, 1)
            ln(151, 312, 181, 312, Lime)
            route(pts(188, 303, 206, 303), enabled, color = if (enabled) null else Faint)
            route(pts(144, 303, 126, 303), false, color = if (enabled) Diagram.Line else Faint)
        }
        Cap("Swipe on viewfinder", 12, 10)
        Num(1, 144, 101, enabled)
        TxtC(name(CaptureMode.GRID), 92, 95, 34, if (enabled) Lime else Diagram.Hint, 8.5f, translate = false, mono = true, maxLines = 1)
        listOf(CaptureMode.VIDEO to 58, photo to 92, CaptureMode.GRID to 122).forEach { (mode, x) ->
            TxtC(name(mode), x, 213, 34, if (mode == photo) Lime else Dim, 7.5f, translate = false, mono = true, maxLines = 1)
        }
        val arrow = "{from} → {to}"
        Lab("Swipe right", "Previous mode", renderer.format(arrow, mapOf("from" to name(photo), "to" to name(photo.step(-1)))),
            180, 52, false, 148)
        Lab("Swipe left", if (enabled) "Next mode" else "Off", renderer.format(arrow, mapOf("from" to name(photo), "to" to name(photo.step(1)))),
            180, 110, enabled, 148)
        Lab("Or tap", "a mode name", null, 180, 204, true, 148)
        Cap("Mode rail", 12, 274)
        val positions = listOf(44, 104, 166, 222, 280)
        modes.forEachIndexed { index, mode ->
            TxtC(name(mode), positions.getOrElse(index) { 280 }, 297, if (mode == CaptureMode.PANORAMA) 70 else 54,
                if (mode == photo) Lime else Diagram.Hint, 10f, translate = false, mono = true, maxLines = 1)
        }
    }
}

@Composable
internal fun SetupOrientationStage(orientation: UiOrientation) {
    val withAutoRotate = orientation != UiOrientation.PORTRAIT
    val withLock = orientation == UiOrientation.AUTOMATIC || orientation == UiOrientation.LANDSCAPE
    SetupStage(160) {
        DiagramDrawing {
            for (left in listOf(20, 180)) {
                rbox(left, 48, 132, 76, 12)
                rbox(left + 8, 56, 92, 60, 3, Faint, 1, dashed = true, fill = null)
                fill(left + 104, 56, 20, 60, Color.White.copy(alpha = .06f), 3)
                ring(left + 114, 86, 6, Diagram.Ink, 1)
            }
            lock(295f, 25f, true, Diagram.Hint)
        }
        SettingsIcon(SettingsSymbol.RotateClockwise, Modifier.at(124.dp, 30.dp).size(14.dp), Lime)
        Num(1, 10, 7)
        Txt(orientation.label, 20, 8, Diagram.Hint, 9.5f, FontWeight.SemiBold, maxWidth = 280, maxLines = 1)
        Lab("Auto-rotate on", null, null, 20, 24, maxWidth = 104)
        Lab("Rotation lock on", null, null, 180, 24, maxWidth = 110)
        OrientationLetters(74, 86, withAutoRotate)
        OrientationLetters(234, 86, withLock)
        Lab(null, if (withAutoRotate) "Text and buttons turn" else "Text and buttons stay put", null, 20, 130, withAutoRotate, 150)
        Lab(null, if (withLock) "Text and buttons turn" else "Text and buttons stay put", null, 180, 130, withLock, 150)
    }
}

@Composable
private fun OrientationLetters(cx: Int, cy: Int, turned: Boolean) {
    Box(Modifier.at((cx - 20).dp, (cy - 13).dp).size(40.dp, 26.dp), contentAlignment = Alignment.Center) {
        Text("Aa", translate = false, color = if (turned) Lime else Diagram.Hint, fontSize = 20.sp, lineHeight = 24.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.rotate(if (turned) 0f else -90f))
    }
}

/** App language before a custom file is imported: where the language applies. */
@Composable
internal fun SetupLanguageStage(languageName: String?, englishSelected: Boolean, customEnabled: Boolean, userName: String?, guides: Int) {
    val renderer = LocalAppLanguage.current.renderer
    SetupStage(224) {
        DiagramDrawing {
            rbox(22, 16, 92, 180, 16)
            rbox(29, 23, 78, 166, 10, Faint, 1, fill = null)
            fill(36, 31, 14, 3, Lime, 1); fill(54, 31, 14, 3, Diagram.Line, 1); fill(72, 31, 12, 3, Diagram.Line, 1); fill(88, 31, 12, 3, Diagram.Line, 1)
            fill(36, 44, 58, 3, Soft, 1); fill(36, 51, 40, 2, Diagram.Line, 1); fill(36, 57, 50, 2, Diagram.Line, 1)
            rbox(35, 70, 66, 30, 4, Diagram.Line, 1, fill = null)
            fill(41, 77, 22, 2, Soft, 1)
            rbox(41, 84, 16, 7, 2, if (englishSelected) Lime else Diagram.Line, 1, dashed = !englishSelected, fill = null)
            rbox(60, 84, 16, 7, 2, if (customEnabled) Lime else Diagram.Line, 1, dashed = !customEnabled, fill = null)
            if (userName != null) rbox(36, 108, 46, 13, 3, Dim, 1, fill = null)
            rbox(34, 136, 68, 46, 5, Lime.copy(alpha = .75f), 1, fill = null)
            fill(39, 142, 24, 2, Lime, 1); fill(39, 149, 54, 2, Diagram.Line, 1); fill(39, 155, 44, 2, Diagram.Line, 1)
            fill(82, 169, 16, 8, Lime, 2)
            val callout = Lime.copy(alpha = .7f)
            poly(pts(96, 46, 118, 46, 134, 21), callout, 1); spot(96, 46)
            poly(pts(101, 86, 118, 86, 134, 73), callout, 1); spot(101, 86)
            if (guides > 0) { poly(pts(103, 162, 118, 162, 134, 177), callout, 1); spot(103, 162) }
            if (userName != null) { poly(pts(82, 114, 118, 114, 154, 120), Color.White.copy(alpha = .35f), 1); spot(82, 114, 3, Diagram.Hint) }
        }
        if (userName != null) TxtC(userName, 59, 109.5, 44, Soft, 6.5f, translate = false, mono = true, maxLines = 1)
        Num(1, 134, 12)
        Num(2, 134, 64)
        if (guides > 0) Num(3, 134, 168)
        Lab("Language", languageName, "Menus, dialogs, notifications", 160, 10, true, 170, valueTranslate = false, emLines = 1, valueLines = 1)
        Lab("Custom language", "Not imported", "Custom unlocks after an import", 160, 62, false, 170, emLines = 1, valueLines = 1)
        if (userName != null) Lab("Names you create", userName, "Kept exactly as typed", 160, 114, true, 170,
            valueTranslate = false, valueColor = CameraColors.Text, emLines = 1, valueLines = 1)
        if (guides > 0) Lab("Guides", renderer.format("{count} tours", mapOf("count" to guides)), "Replay any tour from here",
            160, 166, true, 170, valueTranslate = false, emLines = 1, valueLines = 1)
    }
}

/** Round trip of a custom language file: export, translate, import and update. */
@Composable
internal fun SetupCustomLanguageStage(customName: String, customEnabled: Boolean, available: Int, total: Int, version: String) {
    val renderer = LocalAppLanguage.current.renderer
    val fraction = if (total > 0) (available.toFloat() / total).coerceIn(0f, 1f) else 0f
    SetupStage(244) {
        DiagramDrawing {
            rbox(12, 22, 64, 34, 5)
            route(pts(76, 39, 102, 39), true)
            poly(pts(106, 22, 124, 22, 132, 30, 132, 58, 106, 58, 106, 22))
            poly(pts(124, 22, 124, 30, 132, 30), Diagram.Ink, 1)
            route(pts(134, 39, 166, 39), true, color = Dim)
            rbox(170, 22, 78, 34, 5, Dim, 1, dashed = true)
            route(pts(248, 39, 278, 39), true, color = Dim)
            poly(pts(282, 22, 300, 22, 308, 30, 308, 58, 282, 58, 282, 22))
            poly(pts(300, 22, 300, 30, 308, 30), Diagram.Ink, 1)
            route(pts(295, 58, 295, 100, 80, 100), true)
            rbox(12, 84, 64, 34, 5, if (customEnabled) Lime else Diagram.Line, dashed = !customEnabled,
                fill = if (customEnabled) Lime.copy(alpha = .06f) else Color.Black)
            route(pts(44, 118, 44, 140, 209, 140, 209, 60), true)
            fill(38, 202, 284, 6, Rule, 3)
            hatch(38.dp, 202.dp, 284.dp, 6.dp, Faint, 4.dp)
            fill(38, 202, 284 * fraction, 6, Lime, 3)
        }
        Note("App", "English", 20, 26, maxWidth = 54)
        TxtC("EN", 119, 36, 24, Soft, 8.5f, translate = false, mono = true, maxLines = 1)
        Note("Translate", "your language", 176, 26, maxWidth = 70)
        TxtC(customName.take(2).uppercase(), 295, 36, 24, Soft, 8.5f, translate = false, mono = true, maxLines = 1)
        Txt(renderer.format("English-{version}.json", mapOf("version" to version)), 100, 62, Diagram.Hint, 8f, translate = false,
            mono = true, maxWidth = 130, maxLines = 1)
        Txt(customName, 236, 62, Diagram.Hint, 9f, translate = false, maxWidth = 92, align = TextAlign.End, width = 92, maxLines = 1)
        Note("App", if (customEnabled) "Custom" else "English", 20, 88, if (customEnabled) NoteTone.On else NoteTone.Plain, 54)
        Txt("Import language", 116, 104, Lime, 8.5f, mono = true, maxWidth = 88)
        Note("Export updated custom language", "Your entries kept, latest English entries added", 54, 146, maxWidth = 270)
        Num(1, 80, 15)
        Num(2, 226, 91)
        Num(3, 35, 121)
        Num(4, 12, 186)
        Row(Modifier.at(38.dp, 186.dp).width(284.dp)) {
            DiagramText("Entries", size = 9.5f, maxWidth = 200.dp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("{available} / {total}", arguments = mapOf("available" to available, "total" to total), color = Diagram.Hint,
                fontSize = 9.5.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono)
        }
        Row(Modifier.at(38.dp, 212.dp).width(284.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Translated, shown in {name}", arguments = mapOf("name" to customName), color = Diagram.Hint, fontSize = 10.sp,
                lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("{count} use English", arguments = mapOf("count" to (total - available).coerceAtLeast(0)), color = Diagram.Hint,
                fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 130.dp))
        }
    }
}

// Viewfinder

@Composable
internal fun SetupPreviewSourceStage(raw: Boolean, resolution: String?) {
    SetupStage(348) {
        DiagramDrawing {
            rbox(12, 132, 48, 48, 3)
            for (i in 1..3) { ln(12 + i * 12, 133, 12 + i * 12, 179, Faint, 1); ln(13, 132 + i * 12, 59, 132 + i * 12, Faint, 1) }
            fill(25, 145, 10, 10, Lime.copy(alpha = .25f))
            ln(60, 156, 76, 156, Lime)
            poly(pts(82, 150, 88, 156, 82, 162, 76, 156, 82, 150), Lime, 1.3)
            route(pts(82, 150, 82, 73, 98, 73), raw)
            route(pts(172, 73, 186, 73), raw)
            route(pts(260, 73, 297, 73, 297, 93), raw)
            route(pts(88, 156, 98, 156), !raw)
            route(pts(172, 157, 186, 157), !raw)
            route(pts(260, 157, 270, 157), !raw)
            rbox(272, 96, 50, 96, 8)
            rbox(276, 108, 42, 56, 2, Lime, 1, fill = Lime.copy(alpha = .06f))
            ln(276, 146, 318, 146, Mid, 1)
            ring(303, 124, 4, Mid, 1)
            ring(297, 178, 6, Diagram.Hint, 1)
            ln(100, 240, 324, 240, Rule, 1, dashed = true)
            route(pts(36, 180, 36, 279, 98, 279), true, color = Soft)
            route(pts(172, 279, 186, 279), true, color = Soft)
        }
        Cap("Live preview", 12, 10)
        Txt("Sensor", 12, 116, Soft, 9f, mono = true)
        Num(1, 58, 94)
        Cap("RAW sensor", 100, 42, if (raw) Lime else Diagram.Hint, 170, 9f)
        DiagramNode("RAW frame", Modifier.at(100.dp, 56.dp), "unprocessed", if (raw) DiagramNodeState.Active else DiagramNodeState.Off, width = 72.dp)
        DiagramNode("Developed", Modifier.at(188.dp, 56.dp), "with your look", if (raw) DiagramNodeState.Active else DiagramNodeState.Off, width = 72.dp)
        Cap("Camera ISP", 100, 126, if (raw) Diagram.Hint else Lime, 170, 9f)
        // The note follows the taller of the two boxes, so wrapped details never run into it.
        Column(Modifier.at(100.dp, 140.dp).width(160.dp)) {
            Row {
                DiagramNode("ISP preview", Modifier, "phone processed", if (raw) DiagramNodeState.Off else DiagramNodeState.Active, width = 72.dp)
                Spacer(Modifier.width(16.dp))
                DiagramNode("Look on top", Modifier, "look, effects, overlays", if (raw) DiagramNodeState.Off else DiagramNodeState.Active, width = 72.dp)
            }
            DiagramText("In photo mode · uses less power on older phones", Modifier.padding(top = 6.dp), Diagram.Hint, 9.5f, maxWidth = 160.dp)
        }
        Num(2, 314, 86)
        Column(Modifier.at(262.dp, 196.dp).width(70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DiagramText("Viewfinder", color = CameraColors.Text, size = 10.5f, weight = FontWeight.SemiBold, maxWidth = 70.dp, align = TextAlign.Center)
            resolution?.let { DiagramText(it, color = Lime, size = 9.5f, translate = false, mono = true, maxWidth = 70.dp, align = TextAlign.Center) }
        }
        Cap("Capture", 270, 246, Diagram.Hint, 60, 9f)
        Cap("Both sources", 100, 248, Diagram.Hint, 160, 9f)
        DiagramNode("RAW capture", Modifier.at(100.dp, 262.dp), "on shutter", DiagramNodeState.Done, width = 72.dp)
        DiagramNode("Photos and videos", Modifier.at(188.dp, 262.dp), "still developed from RAW", DiagramNodeState.Done, width = 134.dp)
        Legend(12, 320, listOf("Current source" to LegendLine.Current, "Other choice" to LegendLine.Other, "Always" to LegendLine.Always))
    }
}

/** Sizes of each viewfinder resolution against the RAW stream, drawn to scale. */
@Composable
internal fun SetupResolutionStage(streamWidth: Int, streamHeight: Int, sizes: List<Pair<Int, Int>>, selected: Pair<Int, Int>) {
    val renderer = LocalAppLanguage.current.renderer
    val scale = minOf(200f / streamWidth, 150f / streamHeight)
    val fullW = streamWidth * scale
    val fullH = streamHeight * scale
    val bottom = 182f
    val selectedW = selected.first * scale
    val selectedH = selected.second * scale
    val decimal = java.text.DecimalFormat("0.#")
    SetupStage(222) {
        DiagramDrawing {
            rbox(14, bottom - fullH, fullW, fullH, 0, fill = Color.White.copy(alpha = .02f))
            sizes.filter { it != selected && it != (streamWidth to streamHeight) }.forEach { (w, h) ->
                rbox(14, bottom - h * scale, w * scale, h * scale, 0, Diagram.Line, 1, fill = null)
            }
            rbox(14, bottom - selectedH, selectedW, selectedH, 0, Lime, fill = Lime.copy(alpha = .08f))
            ln(14, 192, 14 + fullW, 192, Diagram.Line, 1)
            ln(14, 188, 14, 196, Diagram.Line, 1); ln(14 + fullW, 188, 14 + fullW, 196, Diagram.Line, 1)
            ln(14, 200, 14 + selectedW, 200, Lime, 1)
            ln(14, 197, 14, 203, Lime, 1); ln(14 + selectedW, 197, 14 + selectedW, 203, Lime, 1)
            ln(14 + fullW, bottom - fullH + 18, 226, bottom - fullH + 18, Color.White.copy(alpha = .35f), 1)
            callout((14 + selectedW).dp, (bottom - selectedH / 2).dp, 226.dp, 128.dp)
        }
        Cap("Sensor frame · RAW stream", 14, 10)
        sizes.forEach { (w, h) ->
            val on = (w to h) == selected
            val full = w == streamWidth && h == streamHeight
            Txt("$w", 14 + w * scale - 46, bottom - h * scale + 2, if (on) Lime else if (full) Soft else Diagram.Hint, 8.5f,
                translate = false, mono = true, width = 42, align = TextAlign.End)
        }
        // Wide streams have a lower top edge; keep the sensor label clear of the viewfinder label below it.
        Lab("Sensor", "$streamWidth × $streamHeight", "Full", 232, (bottom - fullH + 8).coerceAtMost(66f), false, 96,
            valueTranslate = false, mono = true, valueColor = Soft)
        Lab("Viewfinder", "${selected.first} × ${selected.second}",
            renderer.format("{value} of {total} MP", mapOf("value" to decimal.format(selected.first * selected.second / 1_000_000.0),
                "total" to decimal.format(streamWidth.toLong() * streamHeight / 1_000_000.0))),
            232, 120, true, 96, valueTranslate = false, mono = true)
        Txt(renderer.format("{value}% of the width", mapOf("value" to (selected.first * 100 / streamWidth))),
            (14 + selectedW - 200).coerceAtLeast(14f), 205, Lime, 9f, translate = false, mono = true, width = 200,
            align = if (selectedW >= 200) TextAlign.End else TextAlign.Start, maxLines = 1)
    }
}

@Composable
internal fun SetupFramingStage(behindControls: Boolean, opacity: Float, isDefault: Boolean) {
    val renderer = LocalAppLanguage.current.renderer
    val levels = listOf(.06f, .3f, .55f, .8f, 1f)
    val nearest = levels.indices.minBy { kotlin.math.abs(levels[it] - opacity) }
    val percent = (opacity * 100).toInt()
    SetupStage(428) {
        DiagramDrawing {
            val sceneBottom = if (behindControls) 314 else 236
            with(scope) {
                drawContext.canvas.save()
                drawContext.canvas.clipRect(p(26), p(22), p(164), p(sceneBottom))
            }
            ring(132, 84, 10, Diagram.Line, 1)
            poly(pts(26, 190, 26, 150, 50, 150, 50, 128, 72, 128, 72, 160, 86, 160, 86, 190), Mid, 1)
            poly(pts(120, 190, 120, 140, 150, 140, 150, 112, 164, 112), Mid, 1)
            ln(26, 190, 164, 190, Color(0xFF8A8A8A), 1)
            ln(44, 314, 88, 190, Color(0xFF4A4A4A), 1); ln(146, 314, 108, 190, Color(0xFF4A4A4A), 1); ln(96, 314, 96, 190, Color(0xFF4A4A4A), 1)
            ring(70, 206, 8, Soft, 1)
            curve(56, 266, 57, 232, 83, 232, 84, 266, Soft, 1)
            with(scope) { drawContext.canvas.restore() }
            fill(26, 22, 138, 22, Color.White.copy(alpha = .07f))
            for (x in listOf(42, 62, 82, 148)) ring(x, 33, 3.5, Diagram.Hint, 1)
            fill(26, 236, 138, 78, Color(0xFF080808).copy(alpha = if (behindControls) opacity.coerceIn(0f, 1f) else 1f))
            ln(26, 236, 164, 236, Lime.copy(alpha = .45f), 1)
            for (x in listOf(50, 72, 102, 124)) fill(x, 250, 16, 3, Mid, 1.5)
            fill(86, 249, 18, 5, Lime, 2.5)
            rbox(40, 276, 18, 18, 3, Diagram.Hint, 1, fill = null)
            ring(95, 285, 13)
            ring(95, 285, 9, Color.Transparent, 0, fill = Color.White.copy(alpha = .1f))
            ring(146, 285, 7, Diagram.Hint, 1)
            rbox(27.5, 45, 135, if (behindControls) 267 else 190, 3, Lime.copy(alpha = .75f), 1, dashed = true, fill = null)
            rbox(20, 16, 150, 304, 20, fill = null)
            rbox(222, 24, 64, 124, 10, Mid, 1, dashed = true, fill = null)
            ln(226, 92, 282, 92, Diagram.Line, 1)
            poly(pts(226, 92, 226, 76, 238, 76, 238, 68, 248, 68, 248, 92), Color(0xFF4A4A4A), 1)
            fill(226, 112, 56, 30, if (behindControls) Color(0xFF1A1A1A) else Color(0x991A1A1A))
            ln(226, 112, 282, 112, Diagram.Hint, 1, dashed = true)
            ring(254, 127, 6, Mid, 1)
            levels.forEachIndexed { index, level ->
                val x = 12 + index * 64
                fill(x, 366, 56, 30, Color(0xFF8A9BAB), 4)
                fill(x, 383, 56, 13, Color(0xFF2C3328))
                fill(x + 7, 371, 10, 12, Color(0xFF3A3F4A)); fill(x + 35, 374, 10, 9, Color(0xFF454B57))
                spot(x + 41, 376, 4, Color(0xFFC9B98F))
                fill(x, 366, 56, 30, Color(0xFF080808).copy(alpha = if (index == nearest) opacity.coerceIn(0f, 1f) else level), 4)
                rbox(x, 366, 56, 30, 4, if (index == nearest) Lime else Rule, 1, fill = null)
            }
        }
        Num(1, 11, 246)
        Num(2, 11, 286)
        DiagramDrawing {
            callout(152.dp, 252.dp, 196.dp, 222.dp, behindControls)
            callout(158.dp, 300.dp, 196.dp, 286.dp)
        }
        // Each label on the right has room for a two-line caption and a two-line explanation.
        Lab("Preview behind controls", if (behindControls) "On" else "Off",
            if (behindControls) "The live image continues under the control area" else "Image stops above the controls", 202, 196, behindControls, 128)
        Lab("Control opacity", renderer.format("{value1}%", mapOf("value1" to percent)), "How solid the control area looks",
            202, 268, true, 128, valueTranslate = false, mono = true)
        Lab(if (behindControls) "Off" else "On", null,
            if (behindControls) "Image stops above the controls" else "The live image continues under the control area", 206, 152, false, 122)
        Txt("image area", 30, 50, Lime, 9f, mono = true, maxWidth = 128, maxLines = 1)
        Cap("How much image shows through", 12, 346, Diagram.Hint, 312, 9f)
        TxtC("Transparent", 40, 400, 60, Diagram.Hint, 9.5f)
        TxtC("Opaque", 296, 400, 60, Diagram.Hint, 9.5f)
        if (nearest in 1..3) TxtC(if (isDefault) renderer.format("{value1}% · Default", mapOf("value1" to percent))
            else renderer.format("{value1}%", mapOf("value1" to percent)), 40 + nearest * 64, 400, 60, Lime, 9.5f, translate = false)
    }
}

@Composable
internal fun SetupPeakingStage(enabled: Boolean, strength: Int) {
    SetupStage(268) {
        DiagramDrawing {
            with(scope) { drawContext.canvas.save(); drawContext.canvas.clipRect(p(14), p(26), p(164), p(226)) }
            ln(14, 168, 164, 168, Color(0xFF4A4A4A), 1)
            poly(pts(118, 168, 118, 72, 164, 72), Diagram.Line, 1)
            for (row in 0..2) for (col in 0..1) rbox(126 + col * 20, 84 + row * 24, 10, 12, 0, Faint, 1, fill = null)
            ring(38, 110, 18, Diagram.Line, 1)
            ln(38, 128, 38, 168, Diagram.Line, 1)
            ring(82, 112, 15, Soft, 1)
            curve(54, 226, 56, 166, 108, 166, 110, 226, Soft, 1)
            if (enabled) {
                val glow = Lime.copy(alpha = .3f + .2f * strength)
                ring(82, 112, 15, glow, 1.2 + .4 * strength)
                curve(54, 226, 56, 166, 108, 166, 110, 226, glow, 1.2 + .4 * strength)
                curve(76, 140, 78, 150, 86, 150, 88, 140, Lime.copy(alpha = .7f), 1.4)
            }
            with(scope) { drawContext.canvas.restore() }
            rbox(14, 26, 150, 200, 3, fill = null)
            for (index in 0..2) {
                val y = 150 + index * 38
                val on = index == strength
                rbox(182, y, 144, 30, 4, if (on) Lime else Rule, 1, fill = if (on) Lime.copy(alpha = .05f) else Color(0xFF0A0A0A))
                curve(246, y + 26, 258, y + 8, 286, y + 20, 318, y + 4, Mid, 1)
                curve(246, y + 26, 258, y + 8, 286, y + 20, 318, y + 4, Lime.copy(alpha = if (enabled) .4f + .25f * index else .15f),
                    1 + index * .9)
            }
            callout(164.dp, 73.dp, 180.dp, 73.dp, false)
            callout(98.dp, 112.dp, 180.dp, 112.dp, enabled)
        }
        Cap(if (enabled) "Live view · Peaking on" else "Live view · Peaking off", 30, 8, Diagram.Hint, 290, 9f)
        Num(1, 5, 17)
        Num(2, 40, 186, enabled)
        Lab("Soft edges", "Not highlighted", null, 186, 56, false, 140)
        Lab("Sharp edges", if (enabled) "Highlighted" else "Not highlighted", null, 186, 96, enabled, 140)
        Cap("Strength", 182, 134, Diagram.Hint, 120, 9f)
        Num(3, 306, 130, enabled)
        listOf("Low", "Medium", "High").forEachIndexed { index, label ->
            Box(Modifier.at(190.dp, (150 + index * 38).dp).size(54.dp, 30.dp), contentAlignment = Alignment.CenterStart) {
                DiagramText(label, color = if (index == strength) Lime else Soft, size = 11f, weight = FontWeight.Medium, maxWidth = 54.dp)
            }
        }
        Txt("Sharp edges are marked. Strength sets how strongly.", 14, 236, Diagram.Hint, 9f, maxWidth = 160)
    }
}

@Composable
internal fun SetupLoupeStage(manual: Boolean, onTap: Boolean, zoom: Int, zoomLabels: List<String>, peaking: Boolean) {
    val squares = listOf(Triple(68, 42, 80), Triple(88, 62, 40), Triple(98, 72, 20))
    val chosen = squares[zoom.coerceIn(0, 2)]
    SetupStage(244) {
        DiagramDrawing {
            with(scope) { drawContext.canvas.save(); drawContext.canvas.clipRect(p(12), p(28), p(172), p(148)) }
            ln(12, 112, 172, 112, Color(0xFF4A4A4A), 1)
            rbox(24, 52, 34, 60, 0, Color(0xFF4A4A4A), 1, fill = null)
            ring(108, 82, 7, Diagram.Hint, 1)
            ln(108, 89, 108, 148, Mid, 1)
            with(scope) { drawContext.canvas.restore() }
            rbox(12, 28, 160, 120, 2, fill = null)
            squares.forEachIndexed { index, (x, y, size) ->
                val on = index == zoom
                rbox(x, y, size, size, 0, if (on) Lime else if (index == 0) Mid else Color(0xFF8A8A8A), if (on) 1.5 else 1,
                    dashed = !on, fill = if (on) Lime.copy(alpha = .06f) else null)
            }
            ln(chosen.first + chosen.third, chosen.second, 196, 18, Lime.copy(alpha = .35f), 1)
            ln(chosen.first + chosen.third, chosen.second + chosen.third, 196, 164, Lime.copy(alpha = .35f), 1)
            rbox(196, 18, 130, 146, 4, fill = Color(0xFF070707))
            ln(197, 40, 325, 40, Color(0xFF1F1F1F), 1)
            ln(309, 25, 317, 33, Diagram.Hint, 1.3); ln(317, 25, 309, 33, Diagram.Hint, 1.3)
            ring(261, 88, 30, Diagram.Hint)
            ring(261, 88, 10, Mid, 1)
            ln(261, 118, 261, 138, Mid, 1)
            arc(261, 88, 30, 180f, 90f, Lime, 1.8)
            arc(261, 88, 30, 0f, 60f, Lime, 1.8)
            rbox(302, 120, 18, 13, 0, Mid, 1, fill = null)
            if (peaking) fill(309, 124.5, 4, 4, Lime)
            for (i in 0..9) fill(236 + i * 8.5, 147, 6, 9, if (i < 7) Diagram.Ink else Rule)
            rbox(12, 194, 152, 42, 8, if (manual) Lime else Diagram.Line, 1, dashed = !manual, fill = Color.Black.copy(alpha = .6f))
            rbox(172, 194, 152, 42, 8, if (onTap) Lime else Diagram.Line, 1, dashed = !onTap, fill = Color.Black.copy(alpha = .6f))
            ring(31, 215, 7, if (manual) Lime else Diagram.Hint, 1.4)
            ring(31, 215, 1.8, if (manual) Lime else Diagram.Hint, 1.4)
            ring(191, 215, 3, if (onTap) Lime else Diagram.Hint, 1.4)
            ring(191, 215, 7, if (onTap) Lime else Diagram.Hint, 1.4, dashed = true)
        }
        Cap("Full-resolution RAW frame", 12, 4, Diagram.Hint, 180, 9f)
        Cap("Loupe", 200, 5, Diagram.Hint, 120, 9f)
        listOf(Triple(71, 44, 0), Triple(90, 92, 1), Triple(149, 78, 2)).forEach { (x, y, index) ->
            Txt(zoomLabels.getOrElse(index) { "" }, x, y, if (index == zoom) Lime else Diagram.Hint, 8.5f, translate = false, mono = true)
        }
        Txt(zoomLabels.getOrElse(zoom) { "" }, 204, 24, Lime, 10f, translate = false, mono = true)
        Txt("Focusing", 236, 25, Soft, 9.5f, maxWidth = 66, maxLines = 1)
        Txt("Focus", 204, 147, Diagram.Hint, 8f, mono = true, maxWidth = 32, maxLines = 1)
        Txt("Squares: the area each Loupe zoom magnifies", 12, 154, Diagram.Hint, 9f, maxWidth = 160)
        Txt("Meter fills as it sharpens, all lime at the sharpest point", 196, 168, Diagram.Hint, 9f, maxWidth = 132)
        Num(3, 48, 32)
        Num(4, 220, 50, peaking)
        if (peaking) Num(5, 294, 94)
        Note("Manual focus", if (manual) "opens the loupe" else "Off", 44, 200, if (manual) NoteTone.On else NoteTone.Dim, 116)
        Note("Tap to focus", if (onTap) "opens the loupe" else "off · no loupe", 204, 200, if (onTap) NoteTone.On else NoteTone.Dim, 116)
        Num(1, 4, 186, manual)
        Num(2, 164, 186, onTap)
    }
}

@Composable
internal fun SetupOverlaysStage(horizon: Boolean, performance: Boolean, viewfinder: String?) {
    val renderer = LocalAppLanguage.current.renderer
    SetupStage(490) {
        DiagramDrawing {
            fill(26, 22, 158, 26, Color.White.copy(alpha = .07f))
            for (x in listOf(44, 64, 84, 104, 124, 166)) ring(x, 35, 3.5, Diagram.Hint, 1)
            ln(26, 206, 184, 206, Color(0xFF4A4A4A), 1)
            poly(pts(26, 206, 26, 160, 54, 160, 54, 136, 76, 136, 76, 206), Color(0xFF4A4A4A), 1)
            poly(pts(140, 206, 140, 128, 168, 128, 168, 158, 184, 158), Color(0xFF4A4A4A), 1)
            ring(110, 182, 14, Color(0xFF4A4A4A), 1)
            ln(110, 196, 110, 206, Color(0xFF4A4A4A), 1)
            if (horizon) {
                ln(46, 162, 97, 162, Lime, 1.6); ln(113, 162, 164, 162, Lime, 1.6)
                ring(105, 162, 4, Lime, 1.3)
            }
            fill(26, 276, 158, 78, Color.White.copy(alpha = .08f))
            for (x in listOf(54, 76, 118, 140)) fill(x, 288, 16, 3, Mid, 1.5)
            fill(96, 287, 18, 5, Lime, 2.5)
            rbox(42, 313, 18, 18, 3, Diagram.Hint, 1, fill = null)
            ring(105, 322, 14)
            ring(105, 322, 10, Color.Transparent, 0, fill = Color.White.copy(alpha = .1f))
            ring(166, 322, 7, Diagram.Hint, 1)
            rbox(27, 48, 156, 228, 0, Faint, 1, dashed = true, fill = null)
            rbox(20, 16, 170, 344, 22, fill = null)
            if (performance) rbox(32, 54, 92, 38, 3, Lime.copy(alpha = .55f), 1, fill = Color.Black.copy(alpha = .6f))
            callout(124.dp, 70.dp, 200.dp, 70.dp, performance)
            callout(166.dp, 162.dp, 200.dp, 162.dp, horizon)
            rbox(12, 400, 100, 78, 6, Rule, 1, fill = Color.Black.copy(alpha = .4f))
            rbox(118, 400, 100, 78, 6, Lime.copy(alpha = .6f), 1, fill = Lime.copy(alpha = .04f))
            rbox(224, 400, 100, 78, 6, Rule, 1, fill = Color.Black.copy(alpha = .4f))
            rbox(30, 410, 64, 40, 3, Diagram.Line, 1, fill = null)
            ln(38, 437, 59, 432); ln(65, 430.5, 86, 425.5)
            ring(62, 431, 2.5, Diagram.Ink, 1)
            rbox(136, 410, 64, 40, 3, Diagram.Line, 1, fill = null)
            ln(144, 430, 165, 430, Lime); ln(171, 430, 192, 430, Lime)
            ring(168, 430, 2.5, Lime, 1)
            ring(274, 430, 17, Diagram.Line, 1)
            ln(259, 430, 289, 430, Faint, 1); ln(274, 415, 274, 445, Faint, 1)
            ring(274, 430, 6, Diagram.Line, 1)
            spot(279, 426, 4, Diagram.Ink)
        }
        if (performance) Column(Modifier.at(36.dp, 57.dp)) {
            DiagramText("FPS · ms", color = Diagram.Ink, size = 7.5f, mono = true, maxWidth = 86.dp)
            viewfinder?.let { DiagramText(renderer.format("VF: {viewfinder}", mapOf("viewfinder" to it)), color = Diagram.Ink, size = 7.5f,
                translate = false, mono = true, maxWidth = 86.dp) }
            DiagramText("Effects", color = Diagram.Ink, size = 7.5f, mono = true, maxWidth = 86.dp)
        }
        Num(2, 11, 56, performance)
        Num(1, 11, 153, horizon)
        Lab("Performance overlay", if (performance) "On" else "Off", "Frame rate and frame time, viewfinder size, effects", 206, 46,
            performance, 124)
        Txt("VF matches the Preview quality resolution", 206, 114, Soft, 10f, maxWidth = 124)
        Lab("Horizon level", if (horizon) "On" else "Off", "Line across the viewfinder, lime when level", 206, 148, horizon, 124)
        Txt("viewfinder", 30, 260, Diagram.Hint, 9f, mono = true, maxWidth = 150, maxLines = 1)
        Txt("Control area below the image", 200, 300, Diagram.Hint, 9f, maxWidth = 126)
        Cap("Horizon level states", 12, 382, Diagram.Hint, 300, 9f)
        TxtC("Tilted", 62, 460, 96, Soft, 10f)
        TxtC("Level", 168, 460, 96, Lime, 10f)
        TxtC("Lying flat", 274, 460, 96, Soft, 10f)
    }
}

// Video

@Composable
internal fun SetupRecordingStage(aac: Boolean, bufferMb: Int, bufferLabel: String) {
    val filled = ((bufferMb - 512) / 512 + 1).coerceIn(1, 8)
    SetupStage(290) {
        DiagramDrawing {
            rbox(14, 50, 56, 56, 6, fill = null)
            rbox(24, 60, 36, 36, 0, Diagram.Line, 1, fill = null)
            for (i in 1..3) { ln(24 + i * 9, 60, 24 + i * 9, 96, Faint, 1); ln(24, 60 + i * 9, 60, 60 + i * 9, Faint, 1) }
            route(pts(72, 78, 94, 78), true)
            rbox(98, 46, 136, 64, 6, fill = null)
            for (i in 0..7) {
                val x = 107 + i * 15.5
                val on = i < filled
                rbox(x, 70, 13, 24, 1.5, if (on) Lime else Faint, 1, fill = if (on) Lime.copy(alpha = .8f) else null)
            }
            val marker = 107 + (filled - 1) * 15.5 + 6.5
            poly(pts(marker - 4, 119, marker, 113, marker + 4, 119, marker - 4, 119), Lime, 1)
            route(pts(236, 78, 258, 78), true)
            poly(pts(262, 44, 304, 44, 318, 58, 318, 112, 262, 112, 262, 44))
            poly(pts(304, 44, 304, 58, 318, 58))
            rbox(270, 66, 40, 28, 2, Diagram.Line, 1, fill = null)
            ln(270, 72, 310, 72, Diagram.Line, 1); ln(270, 88, 310, 88, Diagram.Line, 1)
            rbox(34, 186, 16, 26, 8, fill = null)
            arc(42, 204, 14, 0f, 180f, Diagram.Ink)
            ln(42, 218, 42, 226); ln(35, 226, 49, 226)
            ln(58, 200, 90, 200, Lime)
            curve(90, 200, 104, 200, 106, 182, 120, 182, if (aac) Lime else Mid, dashed = !aac)
            curve(90, 200, 104, 200, 106, 218, 120, 218, if (!aac) Lime else Mid, dashed = aac)
            rbox(120, 170, 56, 24, 12, if (aac) Lime else Mid, dashed = !aac, fill = if (aac) Lime.copy(alpha = .08f) else Color.Black)
            rbox(120, 206, 56, 24, 12, if (!aac) Lime else Mid, dashed = aac, fill = if (!aac) Lime.copy(alpha = .08f) else Color.Black)
            route(pts(176, 182, 286, 182, 286, 116), aac)
            route(pts(176, 218, 298, 218, 298, 116), !aac)
        }
        Lab("Sensor", null, null, 14, 24, maxWidth = 76)
        Lab("Recording buffer", bufferLabel, null, 98, 8, true, 124, valueTranslate = false, mono = true)
        Num(2, 225, 37)
        Lab("Video file", null, null, 262, 20, maxWidth = 70)
        Cap("Phone memory", 106, 54, Diagram.Hint, 120, 7.5f)
        Txt("512 MB", 98, 120, Diagram.Hint, 8f, translate = false, mono = true, maxLines = 1)
        Txt("4 GB", 194, 120, Diagram.Hint, 8f, translate = false, mono = true, width = 40, align = TextAlign.End, maxLines = 1)
        TxtC("AAC", 148, 176, 50, if (aac) Lime else Diagram.Hint, 10f, FontWeight.SemiBold, translate = false, mono = true, maxLines = 1)
        TxtC("PCM", 148, 212, 50, if (!aac) Lime else Diagram.Hint, 10f, translate = false, mono = true, maxLines = 1)
        Txt("compressed", 184, 169, if (aac) Lime else Diagram.Hint, 8f, maxWidth = 96, maxLines = 1)
        Txt("uncompressed", 184, 205, if (!aac) Lime else Diagram.Hint, 8f, maxWidth = 96, maxLines = 1)
        Lab("Audio format", if (aac) "AAC" else "PCM", null, 120, 136, true, 120, valueTranslate = false, mono = true)
        Num(1, 96, 146)
        Lab("Microphone", null, null, 14, 236, maxWidth = 160)
        Legend(14, 266, listOf("Current choice" to LegendLine.Current, "Other choice" to LegendLine.Other))
    }
}

/** Which settings a recording started by holding the shutter keeps fixed. */
@Composable
internal fun SetupRecordingLockStage(shutter: Boolean, iso: Boolean, focus: Boolean, whiteBalance: Boolean) {
    val locks = listOf("Shutter speed" to shutter, "ISO" to iso, "Focus" to focus, "White balance" to whiteBalance)
    val any = locks.any { it.second }
    val exposureSplit = shutter != iso
    SetupStage(330) {
        DiagramDrawing {
            ring(62, 92, 36)
            ring(62, 92, 27, Diagram.Line, 1, fill = Color.White.copy(alpha = .04f))
            arc(62, 92, 44, -90f, 300f, Lime, 2)
            fill(50, 118, 24, 44, Color(0xFF0B0B0B))
            rbox(50, 106, 24, 56, 12, fill = null)
            ln(104, 152, 104, 174, Diagram.Line, 1); ln(150, 152, 150, 174, Diagram.Line, 1)
            rbox(104, 156, 46, 14, 0, Diagram.Ink, 1, fill = null)
            rbox(150, 156, 140, 14, 0, Lime, 1, fill = Lime.copy(alpha = .12f))
            ln(150, 174, 150, 320, if (any) Lime.copy(alpha = .55f) else Faint, 1, dashed = true)
            ln(290, 174, 290, 320, Diagram.Hint, 1, dashed = true)
            locks.forEachIndexed { index, (_, on) ->
                val y = 228 + index * 26
                lock(126f, (y - 11).toFloat(), on, if (on) Lime else Diagram.Hint)
                if (on) ln(150, y, 290, y, Lime, 2.5) else {
                    ln(150, y, 290, y, Diagram.Line, 1, dashed = true)
                }
            }
        }
        Txt("1 s", 14, 42, Lime, 10f, FontWeight.SemiBold, translate = false, mono = true)
        DiagramDrawing { callout(100.dp, 72.dp, 116.dp, 72.dp) }
        Lab("Hold the shutter", "1 second, then recording starts", null, 120, 50, true, 200)
        Lab("Locks", if (any) "Locked while recording" else "None, records without a lock", null, 120, 96, any, 200)
        Txt("hold", 108, 157.5, Soft, 8f, maxWidth = 40, maxLines = 1)
        Txt("Recording", 156, 157.5, Lime, 8f, maxWidth = 130, maxLines = 1)
        TxtC("0 s", 104, 176, 30, Diagram.Hint, 8f, translate = false, mono = true, maxLines = 1)
        TxtC("1 s", 150, 176, 30, if (any) Lime else Diagram.Hint, 8f, translate = false, mono = true, maxLines = 1)
        TxtC("stop", 290, 176, 40, Diagram.Hint, 8f, mono = true, maxLines = 1)
        Cap("Locked while recording", 34, 192, Diagram.Hint, 110, 7.5f)
        locks.forEachIndexed { index, (label, on) ->
            val y = 221 + index * 26
            Num(index + 1, 9, y - 2, on)
            Txt(label, 34, y, if (on) CameraColors.Text else Soft, 9.5f, maxWidth = 88)
            val status = if (exposureSplit && !on && index < 2) "Android AE profile: locked too" else if (on) "locked" else "can change"
            Txt(status, 150, y - 5, if (on || status != "can change") Lime else Diagram.Hint, 8f, width = 182, align = TextAlign.End,
                maxLines = 1)
        }
    }
}
