package com.unspektrawesome.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.R
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LocalDecorativeText
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraIcon
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.LocalDiagramNumbers
import com.unspektrawesome.ui.camera.DiagramDrawing
import com.unspektrawesome.ui.camera.DiagramNodeState
import com.unspektrawesome.ui.camera.DiagramNumber
import com.unspektrawesome.ui.camera.DiagramPen
import com.unspektrawesome.ui.camera.DiagramStage
import com.unspektrawesome.ui.camera.at

/** Width of an artboard placed inside a [DiagramPanel], whose content is inset by 14 dp. */
internal val PanelArtboardWidth = 308.dp

private val Wire = Color(0xFF3A3A3A)
private val Dash = Color(0xFF6A6A6A)
private val Soft = Color(0xFFCFCFCF)

/** Dotted panel with the stage look whose content flows normally, for cards with changing text. */
@Composable
internal fun DiagramPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(Diagram.StageShape).background(Color(0x800A0A0A))
        .border(1.dp, Diagram.StageBorder, Diagram.StageShape).drawBehind { dotGrid() }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

private fun DrawScope.dotGrid() {
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

/** A fixed-size drawing scaled to the available width, without a frame of its own. */
@Composable
internal fun DiagramArtboard(height: Dp, modifier: Modifier = Modifier, width: Dp = PanelArtboardWidth,
    content: @Composable BoxScope.() -> Unit) {
    // A wide but short window, such as landscape, would otherwise scale the drawing past the screen height
    // and push the panel's text and buttons out of view.
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = minOf(maxWidth / width, screenHeight * .35f / height).coerceIn(.75f, 1.6f)
        Box(Modifier.fillMaxWidth().height(height * scale), contentAlignment = Alignment.TopCenter) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f), LocalDecorativeText provides true) {
                // The drawing repeats the labels of the controls around it, so screen readers skip it.
                Box(Modifier.requiredSize(width, height).clearAndSetSemantics { }.graphicsLayer {
                    scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(.5f, 0f)
                }, content = content)
            }
        }
    }
}

/** Small uppercase caption used inside panels. */
@Composable
internal fun PanelEyebrow(text: String, modifier: Modifier = Modifier, color: Color = Diagram.Hint) {
    val renderer = LocalAppLanguage.current.renderer
    Text(renderer.text(text).uppercase(), modifier, translate = false, color = color, fontSize = 9.5.sp, lineHeight = 13.sp,
        letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
}

/** Section heading with a numbered marker that matches a callout on the drawing. */
@Composable
internal fun NumberedSection(number: Int, label: String, modifier: Modifier = Modifier) {
    val renderer = LocalAppLanguage.current.renderer
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiagramNumber(number)
        Text(renderer.text(label).uppercase(), translate = false, color = Diagram.Hint, fontSize = 10.sp,
            lineHeight = 14.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).semantics { heading() })
    }
}

internal enum class GalleryButtonStyle { Outline, Accent, Filled }

/**
 * Small 8 dp button. The visible part stays compact while the touch area is at least 44 dp tall.
 * [number] shows the matching callout of a drawing in front of the label.
 */
@Composable
internal fun GalleryButton(
    label: String,
    modifier: Modifier = Modifier,
    style: GalleryButtonStyle = GalleryButtonStyle.Outline,
    enabled: Boolean = true,
    compact: Boolean = false,
    number: Int? = null,
    arguments: Map<String, Any?>? = null,
    sizingLabels: List<String> = emptyList(),
    leading: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val fontSize = if (compact) 11.sp else 12.sp
    val weight = if (style == GalleryButtonStyle.Filled) FontWeight.SemiBold else FontWeight.Medium
    val sizing = if (sizingLabels.isEmpty()) Modifier else Modifier.sizedForLabels(sizingLabels,
        androidx.compose.material3.LocalTextStyle.current.copy(fontSize = fontSize, lineHeight = 15.sp, fontWeight = weight),
        if (compact) 9.dp else 12.dp, 5.dp)
    val ink = when (style) {
        GalleryButtonStyle.Filled -> Color.Black
        GalleryButtonStyle.Accent -> CameraColors.Accent
        GalleryButtonStyle.Outline -> CameraColors.Text
    }
    Box(modifier.heightIn(min = 44.dp).clip(Diagram.ControlShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(sizing.heightIn(min = if (compact) 28.dp else 34.dp).alpha(if (enabled) 1f else .4f).clip(Diagram.ControlShape)
            .background(when (style) {
                GalleryButtonStyle.Filled -> CameraColors.Accent
                GalleryButtonStyle.Accent -> CameraColors.Accent.copy(alpha = .06f)
                GalleryButtonStyle.Outline -> Diagram.Glass
            })
            .border(1.dp, if (style == GalleryButtonStyle.Outline) Diagram.Border else CameraColors.Accent, Diagram.ControlShape)
            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            number?.takeIf { LocalDiagramNumbers.current }?.let {
                Box(Modifier.size(15.dp).background(if (style == GalleryButtonStyle.Filled) Color.Black else CameraColors.Accent, CircleShape)
                    .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    // Like the drawing callouts it matches, the digit keeps its size so it fits its dot.
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f), LocalDecorativeText provides true) {
                        Text("$it", translate = false, color = if (style == GalleryButtonStyle.Filled) CameraColors.Accent else Color.Black,
                            fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            leading?.invoke(ink)
            Text(label, arguments = arguments, color = ink, fontSize = fontSize, lineHeight = 15.sp, fontWeight = weight,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * Gives a button the size of the largest of [labels], so a button whose label changes with its state
 * keeps its place and size. Labels wrap within the available width.
 */
@Composable
private fun Modifier.sizedForLabels(labels: List<String>, style: androidx.compose.ui.text.TextStyle, horizontal: Dp,
    vertical: Dp): Modifier {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val renderer = LocalAppLanguage.current.renderer
    val textStyle = style.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content)
    return layout { measurable, constraints ->
        // Padding is rounded per side, as the button's own padding is.
        val padding = horizontal.roundToPx() * 2 + 2
        val widest = labels.maxOf { measurer.measure(renderer.text(it), textStyle, density = this).size.width } + padding
        val width = widest.coerceIn(constraints.minWidth, constraints.maxWidth)
        val tallest = labels.maxOf {
            measurer.measure(renderer.text(it), textStyle, density = this,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (width - padding).coerceAtLeast(1))).size.height
        } + vertical.roundToPx() * 2
        val height = tallest.coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width,
            minHeight = height.coerceAtLeast(constraints.minHeight)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** Filter chip for the processing lists. */
@Composable
internal fun GalleryFilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier,
    arguments: Map<String, Any?>? = null, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clip(Diagram.ControlShape).selectable(selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = 30.dp).clip(Diagram.ControlShape)
            .background(if (selected) CameraColors.Accent.copy(alpha = .06f) else Diagram.Glass)
            .border(1.dp, if (selected) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
            .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, arguments = arguments, color = if (selected) CameraColors.Accent else Soft, fontSize = 11.5.sp,
                lineHeight = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

/** Thin determinate progress bar of the status panels. */
@Composable
internal fun PanelProgress(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(3.dp),
        color = CameraColors.Accent, trackColor = Color(0xFF1F1F1F), gapSize = 0.dp, drawStopIndicator = {})
}

/** Dashed rounded outline for planned or inactive parts. */
internal fun Modifier.dashedOutline(color: Color = Wire, radius: Dp = 8.dp): Modifier = drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

private val Number.u: Dp get() = toFloat().dp

private fun DiagramPen.b(x: Number, y: Number, w: Number, h: Number, color: Color = Diagram.Ink, r: Number = 3,
    stroke: Number = 1, dashed: Boolean = false, fill: Color? = null) = box(x.u, y.u, w.u, h.u, r.u, color, stroke.u, dashed, fill)

private fun DiagramPen.l(x1: Number, y1: Number, x2: Number, y2: Number, color: Color = Diagram.Ink, stroke: Number = 1.5,
    dashed: Boolean = false) = line(x1.u, y1.u, x2.u, y2.u, color, stroke.u, dashed)

private fun DiagramPen.a(x1: Number, y1: Number, x2: Number, y2: Number, color: Color = Diagram.Ink, stroke: Number = 1.5,
    dashed: Boolean = false) = arrow(x1.u, y1.u, x2.u, y2.u, color, stroke.u, dashed)

private fun DiagramPen.p(color: Color, stroke: Number, dashed: Boolean, vararg v: Number) =
    polyline(v.toList().chunked(2) { it[0].u to it[1].u }, color, stroke.u, dashed)

private fun DiagramPen.folder(x: Number, y: Number, color: Color) {
    val left = x.toFloat(); val top = y.toFloat()
    p(color, 1.25, false, left, top, left + 6, top, left + 8, top + 2, left + 18, top + 2, left + 18, top + 12, left, top + 12, left, top)
}

private fun DiagramPen.diamond(cx: Number, cy: Number, r: Number, color: Color) {
    val x = cx.toFloat(); val y = cy.toFloat(); val s = r.toFloat()
    p(color, 1.5, false, x, y - s, x + s, y, x, y + s, x - s, y, x, y - s)
}

/** Plain stage text at artboard coordinates. A centred or right-aligned text fills [w]. */
@Composable
private fun StageText(text: String, x: Number, y: Number, color: Color = Diagram.Hint, size: Float = 9f, w: Number = 160,
    align: TextAlign = TextAlign.Start, mono: Boolean = false, weight: FontWeight = FontWeight.Normal,
    translate: Boolean = true, arguments: Map<String, Any?>? = null, lines: Int = 2) {
    Text(text, Modifier.at(x.u, y.u).then(if (align == TextAlign.Start) Modifier.widthIn(max = w.u) else Modifier.width(w.u)),
        translate = translate, arguments = arguments, color = color, fontSize = size.sp, lineHeight = (size + 3).sp,
        fontWeight = weight, fontFamily = if (mono) Diagram.Mono else null, textAlign = align, maxLines = lines,
        overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StageCaption(text: String, x: Number, y: Number, color: Color = Diagram.Hint, w: Number = 160, lines: Int = 2,
    modifier: Modifier = Modifier.at(x.u, y.u)) {
    val renderer = LocalAppLanguage.current.renderer
    Text(renderer.text(text).uppercase(), modifier.widthIn(max = w.u), translate = false, color = color,
        fontSize = 8.sp, lineHeight = 11.sp, letterSpacing = .8.sp, fontWeight = FontWeight.SemiBold, maxLines = lines,
        overflow = TextOverflow.Ellipsis)
}

/** Text centred in a node box of the drawing. */
@Composable
private fun NodeText(text: String, x: Number, y: Number, w: Number, h: Number, color: Color = Diagram.Ink, size: Float = 9f,
    mono: Boolean = false, weight: FontWeight = FontWeight.Normal, translate: Boolean = true,
    arguments: Map<String, Any?>? = null) {
    Box(Modifier.at(x.u, y.u).size(w.u, h.u).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Text(text, translate = translate, arguments = arguments, color = color, fontSize = size.sp, lineHeight = (size + 2).sp,
            fontWeight = weight, fontFamily = if (mono) Diagram.Mono else null, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

private fun branch(on: Boolean) = if (on) CameraColors.Accent else Dash
private fun ink(on: Boolean) = if (on) Diagram.Ink else Diagram.Line

/** When new captures are processed, how heat pauses exports and how hard the phone works. */
@Composable
internal fun GalleryCaptureFlowStage(later: Boolean, pauseWhenHot: Boolean, priority: ProcessingPriority, modifier: Modifier = Modifier) {
    DiagramStage(212.dp, modifier) {
        DiagramDrawing {
            circle(24.dp, 62.dp, 11.dp, fill = Color.Black)
            circle(24.dp, 62.dp, 4.5.dp, CameraColors.Accent)
            l(35, 62, 52, 62, CameraColors.Accent)
            diamond(59, 62, 7, CameraColors.Accent)
            val now = branch(!later)
            l(59, 55, 59, 30, now, dashed = later); a(59, 30, 84, 30, now, dashed = later)
            b(86, 20, 80, 20, ink(!later), dashed = later, fill = Color.Black)
            a(166, 30, 190, 30, now, dashed = later)
            b(192, 20, 76, 20, ink(!later), dashed = later, fill = Color.Black)
            val deferred = branch(later)
            l(59, 69, 59, 96, deferred, dashed = !later); a(59, 96, 84, 96, deferred, dashed = !later)
            b(86, 86, 54, 20, ink(later), dashed = !later, fill = Color.Black)
            a(140, 96, 156, 96, deferred, dashed = !later)
            b(158, 86, 54, 20, ink(later), dashed = !later, fill = Color.Black)
            a(212, 96, 228, 96, deferred, dashed = !later)
            b(230, 86, 52, 20, deferred, stroke = 1.5, dashed = !later,
                fill = if (later) CameraColors.Accent.copy(alpha = .08f) else Color.Black)
            a(282, 96, 296, 96, deferred, dashed = !later)
            b(298, 86, 30, 20, deferred, dashed = !later, fill = Color.Black)
            val link = CameraColors.Accent.copy(alpha = .55f)
            dot(256.dp, 108.dp, 2.5.dp)
            // Runs below the two-line captions under the queue nodes.
            p(link, 1, false, 256, 108, 256, 131, 88, 131, 88, 134)
            l(250, 131, 250, 134, link, 1)
            b(10, 134, 156, 70, Wire, r = 4, fill = Color.Black.copy(alpha = .55f))
            l(18, 158, 158, 158, Diagram.Hint, 1, dashed = true)
            p(Diagram.Ink, 1.25, false, 18, 186, 38, 182, 52, 168, 62, 158, 70, 151, 84, 150, 92, 154, 100, 160, 112, 167, 136, 171, 158, 172)
            if (pauseWhenHot) {
                l(72, 158, 72, 187, Diagram.Line, 1, dashed = true); l(100, 158, 100, 187, Diagram.Line, 1, dashed = true)
                l(18, 190, 72, 190, CameraColors.Accent, 3); l(100, 190, 158, 190, CameraColors.Accent, 3)
                l(76, 190, 96, 190, Dash, 1.5, dashed = true)
            } else l(18, 190, 158, 190, CameraColors.Accent, 3)
            b(176, 134, 148, 70, Wire, r = 4, fill = Color.Black.copy(alpha = .55f))
            ProcessingPriority.entries.forEachIndexed { index, choice ->
                val y = 156 + index * 14
                b(222, y, 94, 7, Wire, r = 2)
                val fill = if (choice == priority) CameraColors.Accent else Dash
                b(222, y, listOf(24, 50, 82)[index], 7, fill, r = 2, fill = fill)
            }
            b(186, 194, 6, 6, CameraColors.Accent, r = 1, fill = CameraColors.Accent)
            b(236, 194, 6, 6, Diagram.Line, r = 1)
        }
        StageText("Shutter", 0, 77, w = 48, align = TextAlign.Center)
        StageCaption("Process now", 86, 5, branch(!later), w = 180, lines = 1)
        NodeText("spektrafilm", 86, 20, 80, 20, if (!later) Diagram.Ink else Diagram.Hint, mono = true, translate = false)
        NodeText("JPEG now", 192, 20, 76, 20, if (!later) Diagram.Ink else Diagram.Hint, mono = true)
        StageCaption("Later in Gallery", 86, 72, branch(later), w = 180, lines = 1)
        NodeText("DNG", 86, 86, 54, 20, if (later) Diagram.Ink else Diagram.Hint, mono = true, translate = false)
        StageText("look + crop saved", 73, 107, w = 80, align = TextAlign.Center, size = 8.5f)
        NodeText("Queue", 158, 86, 54, 20, if (later) Diagram.Ink else Diagram.Hint)
        StageText("Gallery", 155, 107, w = 60, align = TextAlign.Center, size = 8.5f)
        NodeText("Export", 230, 86, 52, 20, branch(later), weight = FontWeight.SemiBold)
        NodeText("JPEG", 298, 86, 30, 20, branch(later), size = 8f, mono = true, translate = false)
        DiagramNumber(1, Modifier.at(68.dp, 53.dp))
        DiagramNumber(2, Modifier.at(11.dp, 135.dp))
        // Caption and the heat limit label share one line above the limit, so neither can run into the other.
        Row(Modifier.at(32.dp, 139.dp).width(126.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StageCaption("Temperature", 0, 0, w = 126, lines = 1, modifier = Modifier.weight(1f))
            Text("heat limit", Modifier.widthIn(max = 60.dp), color = Diagram.Hint, fontSize = 8.5.sp, lineHeight = 11.5.sp,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pauseWhenHot) {
            StageText("exports", 16, 193, size = 8.5f, w = 48, lines = 1)
            StageText("paused", 66, 193, size = 8.5f, w = 40, align = TextAlign.Center, lines = 1)
            StageText("resumes", 106, 193, CameraColors.Accent, size = 8.5f, w = 54, align = TextAlign.Center, lines = 1)
        } else StageText("not paused for heat", 16, 193, size = 8.5f, w = 144, lines = 1)
        DiagramNumber(3, Modifier.at(177.dp, 135.dp))
        StageCaption("Phone's effort", 198, 139, w = 124, lines = 1)
        ProcessingPriority.entries.forEachIndexed { index, choice ->
            StageText(choice.label, 182, 152 + index * 14, if (choice == priority) CameraColors.Accent else Diagram.Hint, size = 8.5f,
                w = 38, weight = if (choice == priority) FontWeight.SemiBold else FontWeight.Normal, lines = 1)
        }
        StageText("exports", 195, 192, size = 8f, w = 40, lines = 1)
        StageText("other apps", 245, 192, size = 8f, w = 76, lines = 1)
    }
}

/** How the detailed progress card looks, and the service notice that remains when it is off. */
@Composable
internal fun GalleryNotificationStage(detailed: Boolean, status: ProcessingStatus, shot: GalleryShot?, modifier: Modifier = Modifier) {
    DiagramStage(94.dp, modifier) {
        val card = RoundedCornerShape(8.dp)
        Row(Modifier.at(12.dp, 12.dp).width(216.dp).alpha(if (detailed) 1f else .4f).clip(card).background(Color(0xEB161616))
            .border(1.dp, Wire, card).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(CameraColors.MenuCard)) {
                shot?.let { PhotoImage(it.jpegUri ?: it.rawUri, it.id.takeIf { _ -> it.jpegUri == null }, Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (status.total > 0) Text("spektrafilm · {completed} of {total} exported",
                    arguments = mapOf("completed" to status.completed, "total" to status.total), color = CameraColors.Text,
                    fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text("spektrafilm", translate = false, color = CameraColors.Text, fontSize = 9.5.sp, lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                if (status.running) Text(status.stage.ifEmpty { status.message }, color = Diagram.Hint, fontSize = 8.5.sp,
                    lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Box(Modifier.padding(vertical = 3.dp).fillMaxWidth(.7f).height(4.dp).background(Wire, RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth().height(3.dp).background(Diagram.Border, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(if (status.total > 0) status.completed.toFloat() / status.total else .33f).fillMaxHeight()
                        .background(CameraColors.Accent))
                }
                Text(if (status.waitingForCooling) "Stop automatic resume" else "Pause after photo", color = CameraColors.Accent,
                    fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DiagramNumber(4, Modifier.at(4.dp, 4.dp))
        Column(Modifier.at(240.dp, 14.dp).size(82.dp, 36.dp).dashedOutline(if (detailed) Wire else CameraColors.Accent, 6.dp)
            .padding(horizontal = 8.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(.6f).height(4.dp).background(Wire, RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(.85f).height(4.dp).background(Diagram.Border, RoundedCornerShape(2.dp)))
        }
        StageCaption("When off", 240, 54, w = 90, lines = 1)
        StageText("Service notice only", 240, 66, if (detailed) Soft else CameraColors.Accent, size = 9.5f, w = 90)
    }
}

/** Free space, captured RAWs, the two folders and the way Gallery reaches them. */
@Composable
internal fun GalleryStorageStage(freeBytes: Long, totalBytes: Long, rawCount: Int, rawBytes: Long, deleteRaws: Boolean,
    onStorageAccess: () -> Unit, modifier: Modifier = Modifier) {
    DiagramStage(198.dp, modifier, interactive = true) {
        val total = totalBytes.coerceAtLeast(1L).toFloat()
        val used = ((totalBytes - freeBytes).coerceAtLeast(0L) / total).coerceIn(0f, 1f)
        val raws = (rawBytes / total).coerceIn(0f, used)
        DiagramDrawing {
            b(12, 52, 310, 6, Diagram.Border, r = 3, fill = Color(0xFF141414))
            val usedWidth = 310 * (used - raws)
            if (usedWidth > 0f) b(12, 52, usedWidth, 6, Color(0xFF4A4A4A), r = 3, fill = Color(0xFF4A4A4A))
            if (rawCount > 0) rect((12 + usedWidth).dp, 52.dp, (310 * raws).coerceAtLeast(2f).dp, 6.dp, CameraColors.Accent)
            folder(12, 86, Diagram.Ink)
            p(Diagram.Line, 1, false, 20, 99, 20, 122, 40, 122)
            p(Diagram.Line, 1, false, 50, 129, 50, 154, 70, 154)
            folder(42, 116, CameraColors.Accent)
            folder(72, 148, Diagram.Ink)
            val cleanup = if (deleteRaws) CameraColors.FocusFailed else Dash
            p(cleanup, 1, true, 81, 161, 81, 179, 96, 179)
            b(258, 88, 64, 24, Diagram.Ink, r = 4, fill = Color.Black)
            p(CameraColors.Accent, 1.25, false, 258, 100, 200, 100, 200, 116)
            a(200, 116, 152, 116, CameraColors.Accent, 1.25)
            l(290, 112, 290, 152, CameraColors.Accent, 1.25)
            a(290, 152, 242, 152, CameraColors.Accent, 1.25)
        }
        StageText("{value1} available", 12, 7, CameraColors.Text, size = 16f, w = 280, weight = FontWeight.SemiBold,
            arguments = mapOf("value1" to bytes(freeBytes)), lines = 1)
        StageText("{value1} captured RAWs · {value2}", 12, 30, size = 11f, w = 280, lines = 1,
            arguments = mapOf("value1" to rawCount, "value2" to bytes(rawBytes)))
        DiagramNumber(5, Modifier.at(304.dp, 10.dp))
        FlowRow(Modifier.at(12.dp, 63.dp).widthIn(max = 310.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Legend("used", Color(0xFF4A4A4A), filled = true)
            Legend("captured RAWs", CameraColors.Accent, filled = true)
            Legend("available", Diagram.Line, filled = false)
        }
        StageText("DCIM", 36, 88, Diagram.Ink, size = 9.5f, mono = true, translate = false)
        // Folder names stay on one line each, clear of the Gallery arrows that end beside them.
        Column(Modifier.at(66.dp, 109.dp)) {
            Text("JPEG photos", Modifier.widthIn(max = 82.dp), color = CameraColors.Text, fontSize = 11.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("DCIM/Unspektrawesome", translate = false, color = Soft, fontSize = 9.5.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
        }
        Column(Modifier.at(96.dp, 143.dp)) {
            Text("Original RAWs", Modifier.widthIn(max = 140.dp), color = CameraColors.Text, fontSize = 11.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("DCIM/Unspektrawesome/RAW", translate = false, color = Soft, fontSize = 9.5.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
        }
        DiagramNumber(6, Modifier.at(97.dp, 170.dp))
        StageText(if (deleteRaws) "on · verified exports remove their DNGs" else "off · DNGs kept after export", 118, 173,
            if (deleteRaws) CameraColors.FocusFailed else Diagram.Hint, size = 8.5f, w = 210)
        Row(Modifier.at(264.dp, 94.dp).widthIn(max = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CameraIcon(R.drawable.ic_sleek_aperture, Modifier.size(12.dp), CameraColors.Accent)
            Text("Gallery", color = CameraColors.Text, fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        DiagramNumber(7, Modifier.at(194.dp, 123.dp))
        Box(Modifier.at(214.dp, 111.dp).heightIn(min = 44.dp).widthIn(max = 120.dp).clip(Diagram.ControlShape)
            .clickable(role = Role.Button, onClick = onStorageAccess), contentAlignment = Alignment.Center) {
            Row(Modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE0000000))
                .border(1.dp, CameraColors.Accent, RoundedCornerShape(6.dp)).padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("All files access", color = CameraColors.Accent, fontSize = 10.5.sp, lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                CameraIcon(R.drawable.ic_sleek_chevron_right, Modifier.size(11.dp), CameraColors.Accent)
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp)).then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color, RoundedCornerShape(1.dp))))
        Text(label, color = Diagram.Hint, fontSize = 8.5.sp, lineHeight = 11.sp)
    }
}

/** Both capture paths share one pipeline; exports keep capture time order in Gallery. */
@Composable
internal fun GalleryPreservedStage(captureTimes: List<String>, modifier: Modifier = Modifier) {
    val captured = listOf(48, 104, 158, 262)
    val exported = listOf(30, 80, 162, 212)
    DiagramStage(244.dp, modifier) {
        DiagramDrawing {
            b(12, 12, 78, 22, fill = Color.Black); b(12, 44, 78, 22, fill = Color.Black)
            p(CameraColors.Accent, 1.5, false, 90, 23, 106, 23, 106, 55, 90, 55)
            a(106, 39, 120, 39, CameraColors.Accent)
            b(122, 25, 110, 30, CameraColors.Accent, r = 4, stroke = 1.5, fill = CameraColors.Accent.copy(alpha = .06f))
            a(177, 70, 177, 58, CameraColors.Accent.copy(alpha = .6f), 1)
            a(232, 40, 252, 40, CameraColors.Accent)
            b(254, 30, 42, 20, CameraColors.Accent, fill = Color.Black)
            l(12, 90, 322, 90, Color(0xFF262626), 1, dashed = true)
            l(12, 124, 322, 124, Diagram.Line, 1)
            captured.forEach { circle(it.u, 124.dp, 3.dp, Diagram.Ink, 1.25.dp, fill = Color.Black) }
            exported.forEach { b(it, 150, 46, 12, CameraColors.Accent, r = 2, fill = CameraColors.Accent.copy(alpha = .1f)) }
            rect(139.dp, 151.dp, 3.dp, 10.dp, Diagram.Hint); rect(146.dp, 151.dp, 3.dp, 10.dp, Diagram.Hint)
            l(126, 156, 137, 156, Diagram.Line, 1, dashed = true); l(151, 156, 162, 156, Diagram.Line, 1, dashed = true)
            captured.zip(exported).forEach { (to, from) -> l(from + 23, 162, to, 196, CameraColors.Accent.copy(alpha = .45f), 1) }
            l(12, 200, 322, 200, CameraColors.Accent.copy(alpha = .5f), 1)
            captured.forEach { b(it - 3, 197, 6, 6, CameraColors.Accent, r = 1, fill = CameraColors.Accent) }
        }
        NodeText("Camera", 12, 12, 78, 22, size = 9.5f)
        NodeText("Gallery export", 12, 44, 78, 22, size = 9.5f)
        Column(Modifier.at(122.dp, 25.dp).size(110.dp, 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("spektrafilm", translate = false, color = CameraColors.Accent, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("full processing pipeline", color = Diagram.Hint, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        StageText("each shot's saved settings", 107, 71, size = 8.5f, w = 140, align = TextAlign.Center, lines = 1)
        NodeText("JPEG", 254, 30, 42, 20, CameraColors.Accent, mono = true, translate = false)
        DiagramNumber(8, Modifier.at(304.dp, 31.dp))
        StageCaption("Captured", 12, 96, lines = 1)
        StageCaption("Export order", 12, 134, w = 96, lines = 1)
        StageText("back to camera", 104, 133, size = 8f, w = 80, align = TextAlign.Center, lines = 1)
        StageText("screen off", 264, 151, size = 8f, w = 60, lines = 1)
        exported.forEachIndexed { index, x -> NodeText("${index + 1}", x, 150, 46, 12, CameraColors.Accent, size = 8f, mono = true, translate = false) }
        captureTimes.take(4).forEachIndexed { index, time ->
            StageText(time, captured[index] - 20, 106, Soft, size = 9f, w = 40, align = TextAlign.Center, mono = true, translate = false, lines = 1)
            StageText(time, captured[index] - 20, 205, CameraColors.Accent, size = 9f, w = 40, align = TextAlign.Center, mono = true,
                translate = false, lines = 1)
        }
        StageCaption("JPEG dates in Gallery, from capture time", 12, 219, w = 310)
    }
}

/** Steps of the Gallery guide, completed steps in lime. */
@Composable
internal fun GalleryGuideStepsStage(steps: Int, completed: Int, modifier: Modifier = Modifier) {
    DiagramStage(40.dp, modifier) {
        val gap = 244f / (steps - 1).coerceAtLeast(1)
        DiagramDrawing {
            l(18, 20, 262, 20, CameraColors.Accent.copy(alpha = .5f), 1, dashed = true)
            repeat(steps) { index ->
                val x = (18 + gap * index).dp
                when {
                    index < completed -> dot(x, 20.dp, 3.5.dp)
                    index == completed -> circle(x, 20.dp, 5.dp, CameraColors.Accent, fill = Color.Black)
                    else -> circle(x, 20.dp, 3.dp, Diagram.Hint, 1.dp, fill = Color.Black)
                }
            }
        }
        StageText("{count} steps", 274, 14, Diagram.Ink, size = 9f, mono = true, w = 60, arguments = mapOf("count" to steps), lines = 1)
    }
}

/** Verified exports delete their DNG; uncertain ones keep it. */
@Composable
internal fun RawDeletionDrawing(modifier: Modifier = Modifier) {
    DiagramStage(80.dp, modifier) {
        DiagramDrawing {
            b(34, 14, 72, 20, fill = Color.Black)
            a(106, 24, 118, 24, Soft, 1)
            b(120, 14, 50, 20, CameraColors.Accent, fill = CameraColors.Accent.copy(alpha = .06f))
            a(170, 24, 200, 24, CameraColors.Accent, 1.25)
            b(202, 14, 40, 20, CameraColors.FocusFailed, fill = Color.Black)
            l(206, 16, 238, 32, CameraColors.FocusFailed, 1.25); l(238, 16, 206, 32, CameraColors.FocusFailed, 1.25)
            p(Soft, 1, false, 145, 34, 145, 60)
            a(145, 60, 200, 60, Soft, 1)
            b(202, 50, 40, 20, fill = Color.Black)
        }
        NodeText("JPEG export", 34, 14, 72, 20)
        NodeText("verify", 120, 14, 50, 20, CameraColors.Accent, weight = FontWeight.SemiBold)
        StageText("passes", 160, 2, size = 8f, w = 50, align = TextAlign.Center, lines = 1)
        NodeText("DNG", 202, 14, 40, 20, CameraColors.FocusFailed, mono = true, translate = false)
        StageText("deleted", 250, 18, CameraColors.FocusFailed, w = 80, lines = 1)
        StageText("uncertain", 149, 47, size = 8f, w = 50, align = TextAlign.Center, lines = 1)
        NodeText("DNG", 202, 50, 40, 20, mono = true, translate = false)
        StageText("kept", 250, 54, Soft, w = 80, lines = 1)
    }
}

/** What all-files access lets Gallery find again after an update, reinstall or data reset. */
@Composable
internal fun StorageAccessDrawing(granted: Boolean, modifier: Modifier = Modifier) {
    DiagramStage(252.dp, modifier) {
        val access = branch(granted)
        DiagramDrawing {
            b(14, 14, 90, 136, Diagram.Ink, r = 12, stroke = 1.5, fill = Color.Black)
            dot(59.dp, 22.dp, 1.5.dp, Diagram.Line)
            b(22, 34, 74, 42, Diagram.Line)
            listOf(28, 50, 72).forEach { b(it, 52, 19, 14, Diagram.Line, r = 1, fill = Color(0xFF2F2F2F)) }
            b(22, 84, 74, 40, Diagram.Line)
            listOf(104, 111, 118).forEach { y -> dot(30.dp, y.u, 1.6.dp) }
            l(35, 104, 88, 104, Color(0xFF4A4A4A), 2.5); l(35, 111, 82, 111, Color(0xFF4A4A4A), 2.5); l(35, 118, 86, 118, Color(0xFF4A4A4A), 2.5)
            b(22, 130, 74, 14, Dash, r = 2, dashed = true)
            b(172, 14, 150, 172, Wire, r = 6, fill = Color.Black.copy(alpha = .5f))
            folder(180, 22, CameraColors.Accent)
            listOf(182, 208, 234, 260).forEach { b(it, 44, 22, 16, Diagram.Line, r = 1, fill = Color(0xFF2F2F2F)) }
            p(Diagram.Line, 1, false, 186, 76, 186, 92, 194, 92)
            folder(196, 86, Diagram.Ink)
            listOf(200, 226, 252).forEach { x ->
                p(Diagram.Ink, 1, false, x, 106, x + 12, 106, x + 18, 112, x + 18, 128, x, 128, x, 106)
                dot((x + 9).u, 121.dp, 2.2.dp)
            }
            dot(185.dp, 155.dp, 2.5.dp)
            a(104, 82, 170, 82, access)
            circle(138.dp, 82.dp, 10.dp, access, fill = Color.Black)
            p(access.copy(alpha = .6f), 1, true, 180, 52, 112, 52, 112, 56)
            a(112, 56, 98, 56, access.copy(alpha = .6f), 1, dashed = true)
            a(198, 120, 98, 120, access.copy(alpha = .6f), 1, dashed = true)
            l(12, 196, 322, 196, Color(0xFF262626), 1, dashed = true)
            b(12, 218, 92, 24, fill = Color.Black)
            a(104, 230, 118, 230, CameraColors.Accent, 1.25)
            b(120, 218, 116, 24, CameraColors.Accent, fill = CameraColors.Accent.copy(alpha = .06f))
            b(210, 225, 18, 10, CameraColors.Accent, r = 5, fill = CameraColors.Accent.copy(alpha = .12f))
            dot(223.dp, 230.dp, 3.dp)
            a(236, 230, 250, 230, CameraColors.Accent, 1.25)
            b(252, 218, 70, 24, fill = Color.Black)
        }
        StageText("Gallery", 28, 37, Diagram.Ink, size = 8.5f, w = 66, lines = 1)
        StageText("Processing", 28, 87, Diagram.Ink, size = 8.5f, w = 66, lines = 1)
        NodeText("app data", 22, 130, 74, 14, Diagram.Hint, size = 7.5f)
        StageText("after an update, reinstall or app-data reset", 14, 157, w = 150)
        StageText("DCIM/Unspektrawesome", 202, 25, Diagram.Ink, size = 8.5f, mono = true, translate = false, w = 118, lines = 1)
        StageText("photos, videos", 182, 64, size = 8f, w = 130, lines = 1)
        StageText("RAW", 218, 89, Diagram.Ink, size = 8.5f, mono = true, translate = false)
        StageText("waiting RAWs", 182, 134, size = 8f, w = 130, lines = 1)
        StageText("settings saved in each DNG", 191, 150, Soft, size = 8f, w = 128, lines = 1)
        StageText("including older captures", 191, 162, size = 8f, w = 128, lines = 1)
        Box(Modifier.at(132.dp, 76.dp).size(12.dp), contentAlignment = Alignment.Center) {
            CameraIcon(R.drawable.ic_sleek_check, Modifier.size(11.dp), access)
        }
        StageText("All files access", 104, 94, access, size = 8.5f, w = 68, align = TextAlign.Center, weight = FontWeight.SemiBold)
        StageText("found again", 114, 38, size = 8f, w = 56, align = TextAlign.Center, lines = 1)
        StageText("queue restored", 106, 123, access, size = 8f, w = 72, align = TextAlign.Center, lines = 1)
        StageCaption("Allow, then", 12, 202, w = 300, lines = 1)
        NodeText("Android settings", 12, 218, 92, 24)
        StageText("Unspektrawesome", 126, 224, Diagram.Ink, size = 8.5f, translate = false, w = 82, lines = 1)
        NodeText("return here", 252, 218, 70, 24)
    }
}

/** Ready state: captures wait as RAWs until Start, then export one at a time. */
@Composable
internal fun ProcessingReadyFlow(waiting: Int, modifier: Modifier = Modifier) {
    val queued = waiting > 0
    val node = Color(0xFF0A0A0A)
    DiagramArtboard(110.dp, modifier) {
        DiagramDrawing {
            b(0, 14, 52, 26, Soft, r = 4, fill = node)
            a(54, 27, 68, 27, CameraColors.Accent)
            b(76, 4, 58, 34, Color(0xFF333333), r = 4); b(73, 7, 58, 34, Color(0xFF555555), r = 4)
            b(70, 10, 58, 34, branch(queued), r = 4, dashed = !queued, fill = node)
            a(130, 27, 145, 27, CameraColors.Accent)
            diamond(159, 27, 11, CameraColors.Accent)
            a(172, 27, 188, 27, Dash, dashed = true)
            b(190, 14, 58, 26, Dash, r = 4, dashed = true, fill = node)
            a(250, 27, 262, 27, Dash, dashed = true)
            b(264, 14, 42, 26, Dash, r = 4, dashed = true, fill = node)
            l(86, 45, 86, 94, Dash, 1, dashed = true); l(86, 94, 100, 94, Dash, 1, dashed = true)
            b(102, 83, 156, 22, Dash, r = 4, dashed = true, fill = node)
        }
        NodeText("Camera", 0, 14, 52, 26, Soft, size = 10f, mono = true)
        StageText("Later in Gallery", 0, 44, size = 8.5f, w = 64, align = TextAlign.Center)
        Column(Modifier.at(70.dp, 10.dp).size(58.dp, 34.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("{count} RAW", arguments = mapOf("count" to waiting), color = branch(queued), fontSize = 10.sp, lineHeight = 12.sp,
                fontFamily = Diagram.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("waiting", color = Diagram.Hint, fontSize = 8.5.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DiagramNumber(1, Modifier.at(174.dp, 0.dp))
        // Start and the export note sit side by side under their nodes without sharing any width.
        Column(Modifier.at(128.dp, 44.dp).width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Start", color = Diagram.Hint, fontSize = 8.5.sp, lineHeight = 11.sp, textAlign = TextAlign.Center, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("all or selected", color = CameraColors.Accent, fontSize = 8.5.sp, lineHeight = 11.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        NodeText("Export", 190, 14, 58, 26, Diagram.Hint, size = 10f, mono = true)
        StageText("spektrafilm, one at a time", 192, 44, size = 8.5f, w = 62, align = TextAlign.Center)
        NodeText("JPEG", 264, 14, 42, 26, Diagram.Hint, size = 10f, mono = true, translate = false)
        StageText("Gallery", 259, 44, size = 8.5f, w = 52, align = TextAlign.Center)
        DiagramNumber(2, Modifier.at(77.dp, 58.dp))
        NodeText("Cancelled · DNGs kept", 102, 83, 156, 22, Diagram.Hint, mono = true)
    }
}

/** Legend under the ready flow. */
@Composable
internal fun ProcessingFlowLegend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("captured" to false, "after Start" to true).forEach { (label, dashed) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(16.dp, 2.dp).drawBehind {
                    drawLine(if (dashed) Dash else CameraColors.Accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        1.5.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null)
                })
                Text(label, color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp)
            }
        }
    }
}

/**
 * The photos of the running batch as cells: exported, current and waiting. The dashed marker is where
 * Pause after this photo stops. Large batches only show the progress bar.
 */
@Composable
internal fun ProcessingBatchStrip(total: Int, completed: Int, current: GalleryShot?, pauseWhenHot: Boolean, modifier: Modifier = Modifier) {
    val count = total.coerceAtLeast(1)
    if (count > 40) return
    val gap = 4f
    val cell = ((308f - gap * (count - 1)) / count).coerceAtMost(22f)
    val done = completed.coerceIn(0, count)
    val marker = ((done + 1).coerceAtMost(count) * (cell + gap) - gap / 2).coerceAtMost(306f)
    val labelX = (marker - 8f).coerceIn(0f, 150f)
    DiagramArtboard(100.dp, modifier) {
        DiagramDrawing {
            repeat(count) { index ->
                val x = index * (cell + gap)
                when {
                    index < done -> b(x, 4, cell, 16, CameraColors.Accent.copy(alpha = .75f), r = 2, fill = CameraColors.Accent.copy(alpha = .1f))
                    index == done && current != null -> b(x, 4, cell, 16, CameraColors.Accent, r = 2, stroke = 1.5)
                    else -> b(x, 4, cell, 16, Wire.copy(alpha = .7f), r = 2)
                }
            }
            l(marker, 24, marker, 40, CameraColors.Accent, 2, dashed = true)
            l(marker, 40, labelX, 40, CameraColors.Accent, 1)
        }
        if (current != null && done < count) Box(Modifier.at((done * (cell + gap) + 1.5f).u, 5.5.dp).size((cell - 3f).u, 13.dp)
            .clip(RoundedCornerShape(1.dp))) {
            PhotoImage(current.jpegUri ?: current.rawUri, current.id.takeIf { current.jpegUri == null }, Modifier.fillMaxSize())
        }
        Column(Modifier.at(labelX.u, 44.dp).widthIn(max = (308f - labelX).u), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val renderer = LocalAppLanguage.current.renderer
            Text(renderer.text("Checked before each photo").uppercase(), translate = false, color = Diagram.Hint, fontSize = 8.sp,
                lineHeight = 11.sp, letterSpacing = .6.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DiagramNumber(1)
                Text("Pause after this photo stops here", color = CameraColors.Text, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Text(if (pauseWhenHot) "Pause when hot · On" else "Pause when hot · Off", color = if (pauseWhenHot) CameraColors.Accent else Diagram.Hint,
                fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal data class PipelineStep(
    val icon: Int,
    val title: String,
    val detail: String? = null,
    val state: DiagramNodeState = DiagramNodeState.Idle,
    val detailMono: Boolean = false,
    val detailTranslate: Boolean = true,
    val noteCaption: String? = null,
    val note: String? = null,
    val noteDetail: String? = null,
    val noteActive: Boolean = true,
)

/** The current photo's way through export, one step per row, with notes about the settings that apply. */
@Composable
internal fun ExportPipeline(steps: List<PipelineStep>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        steps.forEachIndexed { index, step -> PipelineRow(step, last = index == steps.lastIndex) }
    }
}

@Composable
private fun PipelineRow(step: PipelineStep, last: Boolean) {
    val active = step.state == DiagramNodeState.Active
    val done = step.state == DiagramNodeState.Done
    val off = step.state == DiagramNodeState.Off
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).drawBehind {
        if (!last) {
            val x = 13.dp.toPx()
            drawLine(if (done) CameraColors.Accent else Diagram.Line, Offset(x, 28.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx(),
                pathEffect = if (done) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        }
    }, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(26.dp)) {
            val shape = RoundedCornerShape(6.dp)
            val color = when { active -> CameraColors.Accent; done -> Diagram.Ink; else -> Color(0xFF8C8C8C) }
            Box(Modifier.size(26.dp).clip(shape).background(if (active) CameraColors.Accent.copy(alpha = .12f) else Color.Black)
                .then(if (active || done) Modifier.border(1.5.dp, color, shape) else Modifier.dashedOutline(Diagram.Line, 6.dp)),
                contentAlignment = Alignment.Center) {
                CameraIcon(step.icon, Modifier.size(13.dp), color)
            }
            if (done) Box(Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).size(10.dp).border(2.dp, Color.Black, CircleShape)
                .padding(1.dp).background(CameraColors.Accent, CircleShape))
        }
        Column(Modifier.weight(1f).padding(bottom = 8.dp).alpha(if (off) .6f else 1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(step.title, color = when { active -> CameraColors.Accent; done -> CameraColors.Text; else -> Soft },
                fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
            step.detail?.let {
                Text(it, translate = step.detailTranslate, color = Diagram.Hint, fontSize = if (step.detailMono) 9.sp else 9.5.sp,
                    lineHeight = 12.sp, fontFamily = if (step.detailMono) Diagram.Mono else null)
            }
            if (active) LinearProgressIndicator(Modifier.padding(top = 4.dp).width(110.dp).height(2.dp),
                color = CameraColors.Accent, trackColor = Color(0xFF1F1F1F))
        }
        if (step.note != null) Row(Modifier.widthIn(max = 132.dp)) {
            val lead = if (step.noteActive) CameraColors.Accent else Diagram.Line
            Box(Modifier.padding(top = 11.dp).size(10.dp, 5.dp).drawBehind {
                drawLine(lead.copy(alpha = .7f), Offset(2.dp.toPx(), size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
                drawCircle(lead, 2.5.dp.toPx(), Offset(2.5.dp.toPx(), size.height / 2))
            })
            Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB8000000)).border(1.dp, Diagram.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp)) {
                step.noteCaption?.let {
                    val renderer = LocalAppLanguage.current.renderer
                    Text(renderer.text(it).uppercase(), translate = false, color = Diagram.Hint, fontSize = 7.5.sp, lineHeight = 10.sp,
                        letterSpacing = .6.sp)
                }
                Text(step.note, color = if (step.noteActive) CameraColors.Accent else Soft, fontSize = 10.5.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold)
                step.noteDetail?.let { Text(it, color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp) }
            }
        }
    }
}

/** Phone heat over time while exports wait for cooling, and what happens next. */
@Composable
internal fun HeatPauseChart(completed: Int, total: Int, pauseRequested: Boolean, modifier: Modifier = Modifier) {
    val resume = !pauseRequested
    val count = total.coerceAtLeast(1)
    val done = completed.coerceIn(0, count)
    val segment = (300f - 67f) / count
    fun segmentX(index: Int) = 10f + if (index < done) index * segment else done * segment + 67f + (index - done) * segment
    // The bottom labels share the row: the waiting label starts after the exported one and may wrap to two lines.
    val exportedWidth = if (done > 0) (done * segment).coerceAtLeast(60f) else 0f
    val waitingX = maxOf(if (done > 0) 10f + exportedWidth + 4f else 10f, done * segment - 12.5f).coerceAtMost(240f)
    val waitingWidth = (314f - waitingX).coerceAtMost(110f)
    DiagramArtboard(188.dp, modifier) {
        Box(Modifier.offset(x = (-7).dp).requiredSize(318.dp, 188.dp)) {
            DiagramDrawing {
                hatch(178.dp, 14.dp, 66.dp, 132.dp, Color.White.copy(alpha = .07f), 6.dp)
                p(Wire, 1, false, 10, 14, 10, 146, 310, 146)
                l(10, 48, 310, 48, CameraColors.FocusFailed, 1, dashed = true)
                p(Dash, 1.5, true, 10, 122, 40, 116, 90, 92, 130, 62, 145, 52, 160, 40, 178, 36, 196, 33, 214, 40, 238, 50,
                    258, 58, 284, 60, 310, 52)
                p(Diagram.Ink, 1.5, false, 10, 122, 40, 116, 90, 92, 130, 62, 145, 52, 160, 40, 178, 36, 190, 34, 204, 37, 215, 42)
                l(215, 18, 215, 146, Diagram.Line, 1, dashed = true)
                circle(151.dp, 48.dp, 3.dp, CameraColors.FocusFailed, 1.dp, fill = Color.Black)
                dot(178.dp, 36.dp, 4.dp)
                circle(238.dp, 50.dp, 4.dp, branch(resume), fill = Color.Black)
                l(196, 69, 235, 54, if (resume) CameraColors.Accent.copy(alpha = .7f) else Dash, 1)
                repeat(count) { index ->
                    val x = segmentX(index)
                    if (index < done) b(x, 150, segment - 2, 9, CameraColors.Accent.copy(alpha = .85f), r = 2, fill = CameraColors.Accent.copy(alpha = .85f))
                    else b(x, 150.5, segment - 2, 8, Dash, r = 2, dashed = true)
                }
                b(10 + done * segment, 150.5, 65, 8, Diagram.Line, r = 2, dashed = true)
            }
            StageCaption("Phone heat", 16, 4)
            StageCaption("Time", 276, 132, w = 34)
            StageText("High heat threshold", 160, 35, CameraColors.FocusFailed, size = 8.5f, w = 150, align = TextAlign.End, lines = 1)
            StageText("Pause when hot · On", 14, 51, CameraColors.Accent, size = 8.5f, w = 130, weight = FontWeight.SemiBold, lines = 1)
            StageText("now", 195, 2, size = 8f, w = 40, align = TextAlign.Center, mono = true, lines = 1)
            StageText("Finishes this photo, then pauses", 12, 19, Diagram.Ink, size = 8.5f, w = 143, align = TextAlign.End, lines = 1)
            DiagramNumber(1, Modifier.at(157.dp, 13.dp))
            DiagramNumber(2, Modifier.at(181.dp, 65.dp), active = resume)
            StageText("Resumes automatically", 202, 67, if (resume) Diagram.Ink else Diagram.Hint, size = 8.5f, w = 112, lines = 1)
            StageText("once below the limit", 202, 78, size = 8.5f, w = 112, lines = 1)
            DiagramNumber(3, Modifier.at(181.dp, 99.dp), active = pauseRequested)
            StageText("Stop automatic resume", 202, 101, if (pauseRequested) Diagram.Ink else Diagram.Hint, size = 8.5f, w = 112, lines = 1)
            StageText("stays paused, queue kept", 202, 112, size = 8.5f, w = 112, lines = 1)
            if (count <= 20 && segment >= 12f) repeat(count) { index ->
                NodeText("${index + 1}", segmentX(index), 150, segment - 2, 9, if (index < done) Color.Black else Diagram.Hint, size = 7f,
                    mono = true, translate = false)
            }
            if (done > 0) StageText("{count} exported", 10, 163, CameraColors.Accent, size = 8f, w = exportedWidth,
                align = TextAlign.Center, arguments = mapOf("count" to done))
            StageText("Waiting for phone to cool", waitingX, 163, size = 8f, w = waitingWidth, align = TextAlign.Center)
        }
    }
}

/** Where each export ends, for the Exported and Needs attention lists. */
@Composable
internal fun ExportOutcomesStage(exportedList: Boolean, attentionLabel: String, attentionArguments: Map<String, Any?>?,
    modifier: Modifier = Modifier) {
    DiagramStage(178.dp, modifier) {
        DiagramDrawing {
            b(14, 84, 50, 26, Soft, r = 4, fill = Color(0xFF0A0A0A))
            l(64, 97, 76, 97, Dash, 1); l(76, 51, 76, 161, Diagram.Line, 1)
            listOf(51 to CameraColors.Accent, 69 to CameraColors.Accent, 97 to CameraColors.Accent,
                137 to CameraColors.FocusFailed, 155 to Dash).forEachIndexed { index, (y, color) ->
                l(76, y, 85, y, color, 1, dashed = index == 4)
                dot(76.dp, y.u, 2.5.dp, color)
            }
        }
        StageCaption("Where each export ends", 14, 8, w = 300, lines = 1)
        NodeText("Export", 14, 84, 50, 26, Soft, size = 10f, mono = true)
        OutcomeTag("Exported", 88, 26, exportedList)
        StageText("JPEG saved · original RAW kept", 89, 45, Diagram.Ink, size = 9.5f, mono = true, w = 236, lines = 1)
        Column(Modifier.at(89.dp, 63.dp).widthIn(max = 236.dp)) {
            Text("JPEG verified · original RAW deleted", color = Diagram.Ink, fontSize = 9.5.sp, lineHeight = 12.sp,
                fontFamily = Diagram.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Delete RAWs after export on, checks passed", color = Diagram.Hint, fontSize = 8.5.sp, lineHeight = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StageText("Panorama saved · RAW frames kept", 89, 91, Diagram.Ink, size = 9.5f, mono = true, w = 236, lines = 1)
        OutcomeTag(attentionLabel, 88, 112, !exportedList, attentionArguments)
        Row(Modifier.at(89.dp, 131.dp).widthIn(max = 236.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Export needs attention", color = Diagram.Ink, fontSize = 9.5.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("Retry", color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1)
        }
        StageText("Export cancelled · original DNG kept", 89, 149, Diagram.Ink, size = 9.5f, mono = true, w = 236, lines = 1)
    }
}

@Composable
private fun OutcomeTag(label: String, x: Number, y: Number, active: Boolean, arguments: Map<String, Any?>? = null) {
    val renderer = LocalAppLanguage.current.renderer
    val text = if (arguments != null) renderer.format(label, arguments) else renderer.text(label)
    Box(Modifier.at(x.u, y.u).height(16.dp).widthIn(max = 236.dp)
        .border(1.dp, if (active) CameraColors.Accent else Diagram.Line, RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), translate = false, color = if (active) CameraColors.Accent else Soft, fontSize = 8.sp, lineHeight = 10.sp,
            letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
