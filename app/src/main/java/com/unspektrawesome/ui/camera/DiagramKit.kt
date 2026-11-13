package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LocalDecorativeText
import com.unspektrawesome.language.languageSemantics

/**
 * Shared look of settings and gallery pages: a dotted "stage" holding a line drawing of what the
 * settings affect, numbered lime callouts, and dense hairline rows below it.
 */
internal object Diagram {
    val Ink = Color(0xFFE6E6E6)
    val Line = Color(0xFF5A5A5A)
    val Hairline = Color(0xFF1C1C1C)
    val StageBorder = Color(0xFF1F1F1F)
    val Border = Color(0xFF2A2A2A)
    val Hint = Color(0xFF9A9A9A)
    val Glass = Color(0x8C121212)
    val MenuGlass = Color(0xEB121212)
    val Dim = Color(0xB3000000)
    val StageShape = RoundedCornerShape(16.dp)
    val ControlShape = RoundedCornerShape(8.dp)
    val WindowShape = RoundedCornerShape(10.dp)
    val Mono = CameraTypography.Readout
    /** Width every stage drawing is designed for; wider screens scale it up. */
    val ArtboardWidth = 336.dp
}

/** One scrolling settings or gallery page with the diagram rhythm. */
@Composable
internal fun DiagramPage(
    scroll: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll)
            .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 28.dp), content = content)
    }
}

/** Page heading: a title and a one-line explanation of what the drawing shows. */
@Composable
internal fun DiagramHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CameraColors.Text, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp) }
        }
        trailing?.invoke()
    }
}

/** Small uppercase section label above a group of rows. */
@Composable
internal fun DiagramSection(label: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val renderer = LocalAppLanguage.current.renderer
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(renderer.text(label).uppercase(), translate = false, color = Diagram.Hint, fontSize = 10.sp,
            lineHeight = 14.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/**
 * The dotted panel that holds a drawing. [content] is laid out on a fixed [Diagram.ArtboardWidth]
 * wide artboard of [height], scaled to the available width, so drawings keep their proportions.
 * The drawing only illustrates the controls around it, so its texts are hidden from accessibility
 * services unless [interactive] is set because the stage holds a real control.
 */
@Composable
internal fun DiagramStage(height: Dp, modifier: Modifier = Modifier, description: String? = null,
    interactive: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(Diagram.StageShape)
        .background(Color(0x800A0A0A)).border(1.dp, Diagram.StageBorder, Diagram.StageShape)
        .drawBehind {
            val step = 12.dp.toPx()
            val radius = 1.dp.toPx()
            val dot = Color.White.copy(alpha = .08f)
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawCircle(dot, radius, Offset(x, y)); x += step }
                y += step
            }
        }
        .then(if (description != null) Modifier.languageSemantics { language ->
            contentDescription = language.text(description)
        } else Modifier)) {
        // Always fit the width exactly, so drawings grow and shrink with screen size and display density.
        val scale = (maxWidth / Diagram.ArtboardWidth).coerceAtMost(1.6f)
        Box(Modifier.fillMaxWidth().height(height * scale), contentAlignment = Alignment.TopCenter) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f),
                LocalDecorativeText provides !interactive,
                com.unspektrawesome.language.LocalOwnTextWidth provides true) {
                Box(Modifier.requiredSize(Diagram.ArtboardWidth, height)
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
                    .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f) },
                    content = content)
            }
        }
    }
}

/** Places a stage element at artboard coordinates in dp. */
internal fun Modifier.at(x: Dp, y: Dp): Modifier = offset(x, y)

/** Line work for a stage, drawn in artboard dp coordinates. */
@Composable
internal fun BoxScope.DiagramDrawing(draw: DiagramPen.() -> Unit) {
    Canvas(Modifier.matchParentSize()) { DiagramPen(this).draw() }
}

internal class DiagramPen(val scope: DrawScope) {
    private fun Dp.px() = with(scope) { toPx() }
    private fun dash(dashed: Boolean) = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.px(), 3.dp.px())) else null

    fun box(x: Dp, y: Dp, w: Dp, h: Dp, radius: Dp = 6.dp, color: Color = Diagram.Ink, stroke: Dp = 1.5.dp,
        dashed: Boolean = false, fill: Color? = null) {
        val topLeft = Offset(x.px(), y.px())
        val size = Size(w.px(), h.px())
        val corner = CornerRadius(radius.px())
        fill?.let { scope.drawRoundRect(it, topLeft, size, corner) }
        scope.drawRoundRect(color, topLeft, size, corner, style = Stroke(stroke.px(), pathEffect = dash(dashed)))
    }

    fun line(x1: Dp, y1: Dp, x2: Dp, y2: Dp, color: Color = Diagram.Ink, stroke: Dp = 1.5.dp, dashed: Boolean = false) {
        scope.drawLine(color, Offset(x1.px(), y1.px()), Offset(x2.px(), y2.px()), stroke.px(), StrokeCap.Round, dash(dashed))
    }

    /** A connector with an arrow head at its end. */
    fun arrow(x1: Dp, y1: Dp, x2: Dp, y2: Dp, color: Color = Diagram.Ink, stroke: Dp = 1.5.dp, dashed: Boolean = false) {
        line(x1, y1, x2, y2, color, stroke, dashed)
        val dx = (x2 - x1).px(); val dy = (y2 - y1).px()
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / length; val uy = dy / length
        val head = 5.dp.px()
        val end = Offset(x2.px(), y2.px())
        val path = Path().apply {
            moveTo(end.x, end.y)
            lineTo(end.x - ux * head - uy * head * .6f, end.y - uy * head + ux * head * .6f)
            lineTo(end.x - ux * head + uy * head * .6f, end.y - uy * head - ux * head * .6f)
            close()
        }
        scope.drawPath(path, color)
    }

    /** Lime callout: a dot on the part being explained and a line to its label. */
    fun callout(x1: Dp, y1: Dp, x2: Dp, y2: Dp, active: Boolean = true) {
        val color = if (active) CameraColors.Accent.copy(alpha = .7f) else Color.White.copy(alpha = .35f)
        scope.drawLine(color, Offset(x1.px(), y1.px()), Offset(x2.px(), y2.px()), 1.dp.px())
        scope.drawCircle(if (active) CameraColors.Accent else Color.White.copy(alpha = .6f), 3.5.dp.px(), Offset(x1.px(), y1.px()))
    }

    fun circle(cx: Dp, cy: Dp, r: Dp, color: Color = Diagram.Ink, stroke: Dp = 1.5.dp, dashed: Boolean = false, fill: Color? = null) {
        fill?.let { scope.drawCircle(it, r.px(), Offset(cx.px(), cy.px())) }
        scope.drawCircle(color, r.px(), Offset(cx.px(), cy.px()), style = Stroke(stroke.px(), pathEffect = dash(dashed)))
    }

    fun dot(cx: Dp, cy: Dp, r: Dp = 3.dp, color: Color = CameraColors.Accent) =
        scope.drawCircle(color, r.px(), Offset(cx.px(), cy.px()))

    fun rect(x: Dp, y: Dp, w: Dp, h: Dp, color: Color) =
        scope.drawRect(color, Offset(x.px(), y.px()), Size(w.px(), h.px()))

    /** Free-form path in artboard dp, for curves such as temperature lines. */
    fun polyline(points: List<Pair<Dp, Dp>>, color: Color = Diagram.Ink, stroke: Dp = 1.5.dp, dashed: Boolean = false) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].first.px(), points[0].second.px())
            points.drop(1).forEach { (x, y) -> lineTo(x.px(), y.px()) }
        }
        scope.drawPath(path, color, style = Stroke(stroke.px(), cap = StrokeCap.Round, pathEffect = dash(dashed)))
    }

    /** Hatched area, used for skipped or unavailable regions. */
    fun hatch(x: Dp, y: Dp, w: Dp, h: Dp, color: Color = Diagram.Line, gap: Dp = 5.dp) {
        val left = x.px(); val top = y.px(); val right = (x + w).px(); val bottom = (y + h).px()
        scope.drawContext.canvas.save()
        scope.drawContext.canvas.clipRect(left, top, right, bottom)
        var start = left - (bottom - top)
        while (start < right) {
            scope.drawLine(color, Offset(start, bottom), Offset(start + (bottom - top), top), 1.dp.px())
            start += gap.px()
        }
        scope.drawContext.canvas.restore()
    }
}

/** Whether numbered markers are shown. Off by default, so pages stay free of repeated 1, 2, 3 badges. */
internal val LocalDiagramNumbers = staticCompositionLocalOf { false }

/** Numbered lime marker matching a numbered row. */
@Composable
internal fun DiagramNumber(number: Int, modifier: Modifier = Modifier, active: Boolean = true) {
    // Numbered callouts are kept only where a drawing maps a whole page (the lens profile editor).
    if (!LocalDiagramNumbers.current) return
    // The number only links a row to its callout in the drawing, so screen readers skip it.
    Box(modifier.size(18.dp).background(if (active) CameraColors.Accent else Diagram.Line, CircleShape)
        .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        // Matches the callouts in the drawing, which keep their size at any text scale, so the digit fits its dot.
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f), LocalDecorativeText provides true) {
            Text("$number", translate = false, color = Color.Black, fontSize = 10.5.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

/** A stage label: small uppercase caption over the current value. */
@Composable
internal fun DiagramLabel(caption: String?, value: String? = null, modifier: Modifier = Modifier,
    active: Boolean = true, maxWidth: Dp = 150.dp, valueTranslate: Boolean = true) {
    val renderer = LocalAppLanguage.current.renderer
    Column(modifier.widthIn(max = maxWidth)) {
        caption?.let {
            Text(renderer.text(it).uppercase(), translate = false, color = Diagram.Hint, fontSize = 9.5.sp,
                lineHeight = 12.sp, letterSpacing = .6.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        value?.let {
            Text(it, translate = valueTranslate, color = if (active) CameraColors.Accent else Color(0xFF8C8C8C),
                fontSize = 12.sp, lineHeight = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Small plain text placed on a stage. Labels wrap to [maxLines] lines and end with an ellipsis, so a long
 * translation cannot run into the next part of the drawing; leave room below for the second line.
 */
@Composable
internal fun DiagramText(text: String, modifier: Modifier = Modifier, color: Color = Diagram.Hint, size: Float = 10f,
    weight: FontWeight = FontWeight.Normal, translate: Boolean = true, mono: Boolean = false, maxWidth: Dp = 160.dp,
    align: TextAlign = TextAlign.Start, maxLines: Int = 2) {
    Text(text, translate = translate, color = color, fontSize = size.sp, lineHeight = (size + 3).sp, fontWeight = weight,
        fontFamily = if (mono) Diagram.Mono else null, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = maxWidth))
}

/** A labelled node of a flow, drawn as an outlined box. */
@Composable
internal fun DiagramNode(title: String, modifier: Modifier = Modifier, detail: String? = null,
    state: DiagramNodeState = DiagramNodeState.Idle, translate: Boolean = true, width: Dp? = null) {
    val (border, ink) = when (state) {
        DiagramNodeState.Active -> CameraColors.Accent to CameraColors.Accent
        DiagramNodeState.Done -> Diagram.Ink to CameraColors.Text
        DiagramNodeState.Idle -> Diagram.Line to Color(0xFFBDBDBD)
        DiagramNodeState.Off -> Diagram.Line to Diagram.Hint
    }
    val dashed = state == DiagramNodeState.Off
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier)
        .drawBehind {
            drawRoundRect(border, cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(if (state == DiagramNodeState.Active) 1.5.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
        }
        .background(if (state == DiagramNodeState.Active) CameraColors.Accent.copy(alpha = .06f) else Color.Transparent,
            RoundedCornerShape(6.dp))
        .padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, translate = translate, color = ink, fontSize = 10.5.sp, lineHeight = 13.sp,
            fontWeight = if (state == DiagramNodeState.Active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, translate = translate, color = Diagram.Hint, fontSize = 9.sp, lineHeight = 11.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

internal enum class DiagramNodeState { Active, Done, Idle, Off }

internal data class DiagramStep(val title: String, val detail: String? = null,
    val state: DiagramNodeState = DiagramNodeState.Idle, val translate: Boolean = true)

/**
 * A chain of nodes joined by arrows, outside a stage or inside one. Horizontal chains share the width
 * equally; vertical chains stack with short connectors.
 */
@Composable
internal fun DiagramFlow(steps: List<DiagramStep>, modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) {
        Column(modifier) {
            steps.forEachIndexed { index, step ->
                DiagramNode(step.title, Modifier.fillMaxWidth(), step.detail, step.state, step.translate)
                if (index < steps.lastIndex) {
                    val next = steps[index + 1].state
                    val active = next == DiagramNodeState.Active || next == DiagramNodeState.Done
                    Canvas(Modifier.padding(start = 16.dp).size(10.dp, 12.dp)) {
                        val color = if (active) CameraColors.Accent else Diagram.Line
                        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height - 3.dp.toPx()), 1.5.dp.toPx(),
                            pathEffect = if (next == DiagramNodeState.Off) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null)
                        val path = Path().apply {
                            moveTo(size.width / 2, size.height)
                            lineTo(size.width / 2 - 3.dp.toPx(), size.height - 4.dp.toPx())
                            lineTo(size.width / 2 + 3.dp.toPx(), size.height - 4.dp.toPx())
                            close()
                        }
                        drawPath(path, color)
                    }
                }
            }
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { index, step ->
                DiagramNode(step.title, Modifier.weight(1f), step.detail, step.state, step.translate)
                if (index < steps.lastIndex) {
                    val next = steps[index + 1].state
                    val active = next == DiagramNodeState.Active || next == DiagramNodeState.Done
                    Canvas(Modifier.size(12.dp, 10.dp)) {
                        val color = if (active) CameraColors.Accent else Diagram.Line
                        drawLine(color, Offset(1.dp.toPx(), size.height / 2), Offset(size.width - 3.dp.toPx(), size.height / 2), 1.5.dp.toPx(),
                            pathEffect = if (next == DiagramNodeState.Off) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null)
                        val path = Path().apply {
                            moveTo(size.width, size.height / 2)
                            lineTo(size.width - 4.dp.toPx(), size.height / 2 - 3.dp.toPx())
                            lineTo(size.width - 4.dp.toPx(), size.height / 2 + 3.dp.toPx())
                            close()
                        }
                        drawPath(path, color)
                    }
                }
            }
        }
    }
}

/** One dense row with an optional number, a title and muted detail, and a trailing control. */
@Composable
internal fun DiagramRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    number: Int? = null,
    enabled: Boolean = true,
    translateTitle: Boolean = true,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
        .then(if (divider) Modifier.diagramHairline() else Modifier)
        .padding(horizontal = 4.dp, vertical = 7.dp).alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        number?.let { DiagramNumber(it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, translate = translateTitle, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            detail?.let { Text(it, color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp) }
        }
        trailing?.invoke()
    }
}

/** Bottom hairline shared by every row. */
internal fun Modifier.diagramHairline(): Modifier = drawBehind {
    drawLine(Diagram.Hairline, Offset(0f, size.height - .5.dp.toPx()), Offset(size.width, size.height - .5.dp.toPx()), 1.dp.toPx())
}

/** Compact 34 by 20 switch with a full-size touch area. */
@Composable
internal fun DiagramSwitch(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .languageSemantics { language -> contentDescription = language.text(label) }, contentAlignment = Alignment.Center) {
        DiagramSwitchVisual(checked, enabled)
    }
}

@Composable
private fun DiagramSwitchVisual(checked: Boolean, enabled: Boolean) {
    val position by animateDpAsState(if (checked) 17.dp else 3.dp, label = "Diagram switch")
    Box(Modifier.size(34.dp, 20.dp).alpha(if (enabled) 1f else .4f).clip(CircleShape).background(Color(0xFF141414))
        .border(1.dp, if (checked) CameraColors.Accent else Color(0xFF3A3A3A), CircleShape)) {
        Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(position.roundToPx(), 0) }.size(12.dp)
            .background(if (checked) CameraColors.Accent else Diagram.Hint, CircleShape))
    }
}

/** A row that is itself the switch, so the whole line can be tapped. */
@Composable
internal fun DiagramSwitchRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    number: Int? = null,
    enabled: Boolean = true,
    toggleLabel: String = title,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .languageSemantics(mergeDescendants = true) { language -> contentDescription = language.text(toggleLabel) }
        .then(if (divider) Modifier.diagramHairline() else Modifier)
        .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        number?.let { DiagramNumber(it) }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else .45f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            detail?.let { Text(it, color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp) }
        }
        Box(Modifier.size(40.dp, 28.dp), contentAlignment = Alignment.Center) { DiagramSwitchVisual(checked, enabled) }
    }
}

/** A radio row with its real hint as a muted line. */
@Composable
internal fun DiagramRadioRow(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    translateTitle: Boolean = true,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .then(if (divider) Modifier.diagramHairline() else Modifier)
        .padding(horizontal = 4.dp, vertical = 10.dp).alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 1.dp).size(16.dp).border(1.5.dp, if (selected) CameraColors.Accent else Diagram.Line, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).background(CameraColors.Accent, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, translate = translateTitle, color = if (selected) CameraColors.Accent else CameraColors.Text,
                fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = Color(0xFF8C8C8C), fontSize = 11.5.sp, lineHeight = 16.sp) }
        }
        trailing?.invoke()
    }
}

/** Radio rows for a list of choices, one per line. */
@Composable
internal fun DiagramRadioGroup(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    details: List<String?> = emptyList(),
    enabled: (Int) -> Boolean = { true },
    choiceModifier: (Int) -> Modifier = { Modifier },
    onSelect: (Int) -> Unit,
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        labels.forEachIndexed { index, label ->
            DiagramRadioRow(label, selected == index, choiceModifier(index), details.getOrNull(index), enabled(index)) { onSelect(index) }
        }
    }
}

/**
 * Compact segmented choice. Segments share the width when they fit and wrap onto further rows
 * when translated labels are too long.
 */
@Composable
internal fun DiagramSegmented(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
    translate: Boolean = true,
    choiceModifier: (Int) -> Modifier = { Modifier },
    onSelect: (Int) -> Unit,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val renderer = LocalAppLanguage.current.renderer
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val style = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        val needed = with(density) {
            val widest = labels.maxOfOrNull { measurer.measure(if (translate) renderer.text(it) else it, style).size.width } ?: 0
            (widest + 20.dp.toPx()) * labels.size + (labels.size - 1).coerceAtLeast(0) * 4.dp.toPx()
        }
        val fits = needed <= with(density) { maxWidth.toPx() }
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                val allowed = enabled(index)
                Box(choiceModifier(index).then(if (fits) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max))
                    .heightIn(min = 40.dp).clip(Diagram.ControlShape)
                    .background(if (on) CameraColors.Accent.copy(alpha = .06f) else Color(0x66121212))
                    .border(1.dp, if (on) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
                    .selectable(on, enabled = allowed, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp).alpha(if (allowed) 1f else .4f),
                    contentAlignment = Alignment.Center) {
                    Text(label, translate = translate, color = if (on) CameraColors.Accent else CameraColors.Text,
                        fontSize = 12.5.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Value in the lime monospace readout style. */
@Composable
internal fun DiagramValue(text: String, modifier: Modifier = Modifier, translate: Boolean = false,
    color: Color = CameraColors.Accent, arguments: Map<String, Any?>? = null) {
    Text(text, modifier, translate = translate, arguments = arguments, color = color, fontSize = 12.sp, lineHeight = 16.sp,
        fontFamily = Diagram.Mono)
}

/** Small outlined button: 8 dp corners, never a big rounded pill. */
@Composable
internal fun DiagramButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    symbol: SettingsSymbol? = null,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (accent) CameraColors.Accent else CameraColors.Text
    Row(modifier.heightIn(min = 44.dp).clip(Diagram.ControlShape)
        .background(if (accent) CameraColors.Accent.copy(alpha = .06f) else Diagram.Glass)
        .border(1.dp, if (accent) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp).alpha(if (enabled) 1f else .4f),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        symbol?.let { SettingsIcon(it, Modifier.size(15.dp), ink) }
        Text(label, color = ink, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Buttons side by side that wrap onto new rows when the text does not fit. */
@Composable
internal fun DiagramButtonRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Underlined lime text action such as "Change" or "Assign". */
@Composable
internal fun DiagramLink(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clip(Diagram.ControlShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) CameraColors.Accent else Diagram.Hint, fontSize = 11.5.sp, lineHeight = 15.sp,
            textDecoration = TextDecoration.Underline)
    }
}

/** Square 32 dp icon button with a full-size touch area. */
@Composable
internal fun DiagramIconButton(symbol: SettingsSymbol, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    accent: Boolean = false, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(Diagram.ControlShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label) }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(Diagram.Glass, Diagram.ControlShape)
            .border(1.dp, if (accent) CameraColors.Accent else Diagram.Border, Diagram.ControlShape).alpha(if (enabled) 1f else .4f),
            contentAlignment = Alignment.Center) {
            SettingsIcon(symbol, Modifier.size(15.dp), if (accent) CameraColors.Accent else CameraColors.Text)
        }
    }
}

/** Dashed note box for states, warnings and short explanations. */
@Composable
internal fun DiagramNote(text: String, modifier: Modifier = Modifier, warning: Boolean = false,
    arguments: Map<String, Any?>? = null, title: String? = null) {
    val color = if (warning) CameraColors.FocusFailed else Diagram.Line
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)
        .drawBehind {
            drawRoundRect(color, cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        title?.let { Text(it, color = if (warning) CameraColors.FocusFailed else CameraColors.Text, fontSize = 11.5.sp,
            lineHeight = 15.sp, fontWeight = FontWeight.SemiBold) }
        Text(text, arguments = arguments, color = if (warning) CameraColors.FocusFailed else Color(0xFF8C8C8C),
            fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/** Plain muted hint line under a row. */
@Composable
internal fun DiagramHint(text: String, modifier: Modifier = Modifier, warning: Boolean = false,
    arguments: Map<String, Any?>? = null) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), arguments = arguments,
        color = if (warning) CameraColors.FocusFailed else Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp)
}

/**
 * Dropdown field with an anchored glass menu below it (or above when there is no room). The open
 * field turns lime and a thin lime leader joins it to the menu.
 */
@Composable
internal fun DiagramDropdownField(
    label: String,
    value: String,
    detail: String?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    translateValue: Boolean = true,
    number: Int? = null,
    /** When set, the label keeps this width and the value box fills the rest of the row, so fields sharing it line up. */
    labelWidth: Dp? = null,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { focus.clearFocus(); onExpand() }
            .languageSemantics { language -> contentDescription = language.text(label)
                stateDescription = if (translateValue) language.text(value) else value }
            .alpha(if (enabled) 1f else .45f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            number?.let { DiagramNumber(it) }
            LabelBesideValue(Modifier.weight(1f), labelWidth, label = {
                Text(label, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            }) {
            Box {
                Row(Modifier.heightIn(min = 34.dp).widthIn(min = 72.dp).then(if (labelWidth != null) Modifier.fillMaxWidth() else Modifier)
                    .clip(Diagram.ControlShape)
                    .background(if (expanded) CameraColors.Accent.copy(alpha = .06f) else Diagram.Glass)
                    .border(1.dp, if (expanded) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(value, translate = translateValue, color = if (expanded) CameraColors.Accent else CameraColors.Text,
                        fontSize = 12.sp, lineHeight = 16.sp, fontFamily = Diagram.Mono, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = labelWidth != null))
                    SettingsIcon(SettingsSymbol.Dropdown, Modifier.size(13.dp).rotate(if (expanded) 180f else 0f),
                        if (expanded) CameraColors.Accent else Diagram.Hint)
                }
                DiagramMenu(expanded && enabled, onDismiss, content = content)
            }
            }
        }
        detail?.let { Text(it, color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(start = if (number != null) 28.dp else 0.dp, bottom = 4.dp)) }
    }
}

/**
 * A label with its value box at the end. When large text or a long translation leaves the label too little
 * room for its longest word, the value moves below the label, so neither is clipped.
 */
@Composable
private fun LabelBesideValue(modifier: Modifier, labelWidth: Dp?, label: @Composable () -> Unit, value: @Composable () -> Unit) {
    Layout(contents = listOf(label, value), modifier = modifier) { (labels, values), constraints ->
        val labelMeasurable = labels.single()
        val valueMeasurable = values.single()
        val width = constraints.maxWidth
        val gap = 10.dp.roundToPx()
        val labelWord = labelMeasurable.minIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        // The value may use all room the label does not need on one line, so long values stay on one row.
        val valueWidth = if (labelWidth != null) width - gap - maxOf(labelWidth.roundToPx(), labelWord)
            else valueMeasurable.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity).coerceAtMost(minOf(width,
                maxOf(220.dp.roundToPx(), width - gap - labelMeasurable.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity))))
        val room = width - valueWidth - gap
        if (room > 0 && valueWidth >= 72.dp.roundToPx() && labelWord <= room) {
            val labelPlaceable = labelMeasurable.measure(androidx.compose.ui.unit.Constraints.fixedWidth(room))
            val valuePlaceable = valueMeasurable.measure(if (labelWidth != null) androidx.compose.ui.unit.Constraints.fixedWidth(valueWidth)
                else androidx.compose.ui.unit.Constraints(maxWidth = valueWidth))
            val height = maxOf(labelPlaceable.height, valuePlaceable.height, constraints.minHeight)
            layout(width, height) {
                labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.place(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        } else {
            val labelPlaceable = labelMeasurable.measure(androidx.compose.ui.unit.Constraints.fixedWidth(width))
            val valuePlaceable = valueMeasurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = width))
            val spacing = 6.dp.roundToPx()
            val height = maxOf(labelPlaceable.height + spacing + valuePlaceable.height, constraints.minHeight)
            layout(width, height) {
                labelPlaceable.place(0, 0)
                valuePlaceable.place(width - valuePlaceable.width, labelPlaceable.height + spacing)
            }
        }
    }
}

/** Anchored glass menu. Place it inside the Box that holds its anchor. */
@Composable
internal fun DiagramMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    minWidth: Dp = 200.dp, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val maximumHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceIn(120.dp, 440.dp)
    Popup(popupPositionProvider = remember(margin) { AnchoredMenuPosition(margin) }, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Column(modifier.widthIn(min = minWidth, max = 320.dp).heightIn(max = maximumHeight).width(IntrinsicSize.Max)
            .clip(Diagram.ControlShape).background(Diagram.MenuGlass)
            .border(1.dp, Diagram.Border, Diagram.ControlShape)
            .verticalScroll(rememberScrollState()).padding(vertical = 4.dp), content = content)
    }
}

private class AnchoredMenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + margin / 2
        val y = if (below + popupContentSize.height <= windowSize.height - margin) below
            else (anchorBounds.top - popupContentSize.height - margin / 2).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/** One menu item: lime with a check when selected, optional muted detail line. */
@Composable
internal fun DiagramMenuItem(
    label: String,
    selected: Boolean = false,
    detail: String? = null,
    enabled: Boolean = true,
    symbol: SettingsSymbol? = null,
    role: Role = Role.RadioButton,
    translate: Boolean = true,
    description: String = label,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .clickable(enabled = enabled, role = role, onClick = onClick)
        .languageSemantics { language ->
            contentDescription = if (translate) language.text(description) else description
            if (role == Role.RadioButton) this.selected = selected
        }
        .background(if (selected) CameraColors.Accent.copy(alpha = .06f) else Color.Transparent)
        .padding(horizontal = 12.dp, vertical = 8.dp).alpha(if (enabled) 1f else .4f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        symbol?.let { SettingsIcon(it, Modifier.size(15.dp), if (selected) CameraColors.Accent else Diagram.Hint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, translate = translate, color = if (selected) CameraColors.Accent else CameraColors.Text,
                fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            detail?.let { Text(it, translate = translate, color = Color(0xFF8C8C8C), fontSize = 10.5.sp, lineHeight = 14.sp) }
        }
        if (selected) SettingsIcon(SettingsSymbol.Check, Modifier.size(14.dp), CameraColors.Accent)
    }
}

/** Slider row: title and lime value, a thin slider, and the two end labels. */
@Composable
internal fun DiagramSliderRow(
    title: String,
    value: Float,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    step: Float? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    number: Int? = null,
    description: String = title,
    valueArguments: Map<String, Any?>? = null,
    translateEnds: Boolean = true,
    onReset: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            number?.let { DiagramNumber(it) }
            Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            DiagramValue(valueText, arguments = valueArguments, translate = valueArguments != null)
        }
        CameraCompactSlider(value, onChange, enabled = true, valueRange = range, valueStep = step, thumbSize = 14.dp,
            requireThumb = false, onReset = onReset, minimumHeight = 40.dp,
            modifier = Modifier.fillMaxWidth().languageSemantics { language -> contentDescription = language.text(description) })
        if (startLabel != null || endLabel != null) Row(Modifier.fillMaxWidth()) {
            Text(startLabel ?: "", translate = translateEnds && startLabel != null, color = Diagram.Hint, fontSize = 10.5.sp,
                lineHeight = 14.sp, modifier = Modifier.weight(1f))
            Text(endLabel ?: "", translate = translateEnds && endLabel != null, color = Diagram.Hint, fontSize = 10.5.sp,
                lineHeight = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** Small chip such as a button binding or a connected device. */
@Composable
internal fun DiagramChip(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    active: Boolean = false,
    translate: Boolean = false,
    leadingDot: Boolean = false,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(6.dp)).background(Diagram.Glass)
        .border(1.dp, if (active) CameraColors.Accent else Diagram.Border, RoundedCornerShape(6.dp))
        .padding(start = 9.dp, end = if (onRemove != null) 0.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (leadingDot) Box(Modifier.size(6.dp).background(CameraColors.Accent, CircleShape))
        Text(text, translate = translate, color = if (active) CameraColors.Accent else CameraColors.Text, fontSize = 12.sp,
            lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        detail?.let { Text(it, translate = translate, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) }
        if (onRemove != null) Box(Modifier.size(36.dp).clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = removeLabel ?: text }, contentAlignment = Alignment.Center) {
            SettingsIcon(SettingsSymbol.Close, Modifier.size(11.dp), Diagram.Hint)
        }
    }
}

/** Compact tab row with a lime underline under the current tab. */
@Composable
internal fun DiagramTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier,
    tabModifier: (Int) -> Modifier = { Modifier }, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 8.dp)
        .drawBehind {
            drawLine(Diagram.Hairline, Offset(0f, size.height - .5.dp.toPx()), Offset(size.width, size.height - .5.dp.toPx()), 1.dp.toPx())
        }) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(tabModifier(index).heightIn(min = 44.dp).selectable(on, role = Role.Tab) { onSelect(index) }
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) CameraColors.Accent else Diagram.Hint, fontSize = 13.sp, lineHeight = 18.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(CameraColors.Accent))
            }
        }
    }
}

/** Centred window over a dimmed page: title, body, a small drawing, buttons on the right. */
@Composable
internal fun DiagramDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    titleArguments: Map<String, Any?>? = null,
    buttons: @Composable FlowRowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            DiagramWindow(modifier, title, eyebrow, titleArguments, buttons, content)
        }
    }
}

/** The window body of [DiagramDialog], for overlays that are not separate dialogs. */
@Composable
internal fun DiagramWindow(
    modifier: Modifier = Modifier,
    title: String,
    eyebrow: String? = null,
    titleArguments: Map<String, Any?>? = null,
    buttons: @Composable FlowRowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val renderer = LocalAppLanguage.current.renderer
    Column(modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(max = 680.dp).clip(Diagram.WindowShape)
        .background(Color(0xF20E0E0E)).border(1.dp, Diagram.Border, Diagram.WindowShape)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        eyebrow?.let { Text(renderer.text(it).uppercase(), translate = false, color = CameraColors.Accent, fontSize = 10.sp,
            lineHeight = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold) }
        Text(title, arguments = titleArguments, color = CameraColors.Text, fontSize = 16.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp).semantics { heading() })
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
    }
}

/** Muted body paragraph used inside dialogs. */
@Composable
internal fun DiagramBody(text: String, modifier: Modifier = Modifier, arguments: Map<String, Any?>? = null,
    color: Color = Color(0xFFB0B0B0)) {
    Text(text, modifier.padding(vertical = 3.dp), arguments = arguments, color = color, fontSize = 12.5.sp, lineHeight = 18.sp)
}

/** Lays children out at fixed artboard positions without measuring them against each other. */
@Composable
internal fun DiagramFreeLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

internal fun diagramToggleState(on: Boolean) = if (on) ToggleableState.On else ToggleableState.Off
