package com.unspektrawesome.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/** Parts of the photography grip drawing that can light up. */
internal enum class GripPart { Shutter, Zoom, Dial, Record, Light, Usb, VolumeUp, VolumeDown, Power, CameraKey }

/** Where a part sits, in grip drawing units (the drawing is about 330 by 180 units). */
internal fun gripAnchor(part: GripPart): Offset = when (part) {
    GripPart.Shutter -> Offset(58f, 27.5f)
    GripPart.Zoom -> Offset(58f, 17f)
    GripPart.Dial -> Offset(88f, 30f)
    GripPart.Record -> Offset(109f, 37f)
    GripPart.Light -> Offset(34f, 40f)
    GripPart.Usb -> Offset(110f, 108f)
    GripPart.VolumeUp -> Offset(153.5f, 45.5f)
    GripPart.VolumeDown -> Offset(168.5f, 45.5f)
    GripPart.Power -> Offset(191f, 45.5f)
    GripPart.CameraKey -> Offset(212f, 45.5f)
}

/** Artboard position of a grip drawing point for a drawing placed at [x], [y] with [scale]. */
internal fun gripPoint(part: GripPart, x: Dp, y: Dp, scale: Float): Pair<Dp, Dp> {
    val anchor = gripAnchor(part)
    return x + (anchor.x * scale).dp to y + (anchor.y * scale).dp
}

/**
 * A phone held sideways with a photography grip on its left end: the grip's shutter with a zoom lever,
 * a knurled dial, a status light, an extra top button and the USB-C plug, and the phone's side keys and
 * camera island. Parts in [lit] are drawn in lime. [clipPhoneAt] cuts the phone off at that drawing x.
 */
internal fun DiagramPen.photographyGrip(x: Dp, y: Dp, scale: Float, lit: Set<GripPart> = emptySet(),
    clipPhoneAt: Float? = null) = with(scope) {
    val ox = x.toPx()
    val oy = y.toPx()
    val unit = 1.dp.toPx() * scale
    fun p(px: Float, py: Float) = Offset(ox + px * unit, oy + py * unit)
    fun stroke(width: Float, dash: FloatArray? = null) = Stroke(width.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = dash?.let { PathEffect.dashPathEffect(FloatArray(it.size) { index -> it[index] * unit }) })
    fun color(part: GripPart, base: Color = Diagram.Ink) = if (part in lit) CameraColors.Accent else base
    // Paths are written in drawing units; the last point is tracked to turn quadratic curves into cubics.
    var last = Offset.Zero
    fun Path.m(px: Float, py: Float) = p(px, py).let { last = it; moveTo(it.x, it.y) }
    fun Path.l(px: Float, py: Float) = p(px, py).let { last = it; lineTo(it.x, it.y) }
    fun Path.q(cx: Float, cy: Float, ex: Float, ey: Float) {
        val c = p(cx, cy)
        val e = p(ex, ey)
        cubicTo(last.x + 2f / 3f * (c.x - last.x), last.y + 2f / 3f * (c.y - last.y),
            e.x + 2f / 3f * (c.x - e.x), e.y + 2f / 3f * (c.y - e.y), e.x, e.y)
        last = e
    }
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Rect(p(cx - rx, cy - ry), Size(2 * rx * unit, 2 * ry * unit))
    fun drawOvalAt(cx: Float, cy: Float, rx: Float, ry: Float, line: Color, width: Float, fill: Color? = null) {
        val rect = oval(cx, cy, rx, ry)
        fill?.let { drawOval(it, rect.topLeft, rect.size) }
        drawOval(line, rect.topLeft, rect.size, style = stroke(width))
    }
    fun circleAt(cx: Float, cy: Float, r: Float, line: Color, width: Float = 1f, fill: Color? = null, dash: FloatArray? = null) {
        fill?.let { drawCircle(it, r * unit, p(cx, cy)) }
        drawCircle(line, r * unit, p(cx, cy), style = stroke(width, dash))
    }
    fun roundRect(rx: Float, ry: Float, w: Float, h: Float, radius: Float, line: Color, width: Float, fill: Color? = null,
        dash: FloatArray? = null) {
        val corner = androidx.compose.ui.geometry.CornerRadius(radius * unit)
        fill?.let { drawRoundRect(it, p(rx, ry), Size(w * unit, h * unit), corner) }
        drawRoundRect(line, p(rx, ry), Size(w * unit, h * unit), corner, style = stroke(width, dash))
    }
    fun segment(x1: Float, y1: Float, x2: Float, y2: Float, line: Color, width: Float = 1f, dash: FloatArray? = null) =
        drawLine(line, p(x1, y1), p(x2, y2), width.dp.toPx(), StrokeCap.Round,
            dash?.let { PathEffect.dashPathEffect(FloatArray(it.size) { index -> it[index] * unit }) })
    // Lower half of an ellipse joined to two short sides: the visible rim of a round control.
    fun rim(cx: Float, top: Float, bottom: Float, rx: Float, ry: Float) = Path().apply {
        m(cx - rx, top); l(cx - rx, bottom)
        arcTo(oval(cx, bottom, rx, ry), 180f, -180f, false)
        l(cx + rx, top)
    }

    // Phone
    val phone = {
        val side = Path().apply { m(118f, 56f); l(118f, 49f); l(300f, 49f); q(316f, 49f, 316f, 66f); l(316f, 70f); q(316f, 56f, 302f, 56f); close() }
        drawPath(side, Color(0xFF151515))
        roundRect(100f, 56f, 216f, 104f, 14f, Diagram.Ink, 1.5f, Color(0xFF0C0C0C))
        drawPath(Path().apply { m(118f, 49f); l(300f, 49f); q(316f, 49f, 316f, 66f) }, Diagram.Ink, style = stroke(1.5f))
        listOf(Triple(GripPart.VolumeUp, 146f, 15f), Triple(GripPart.VolumeDown, 161f, 15f), Triple(GripPart.Power, 186f, 10f),
            Triple(GripPart.CameraKey, 206f, 12f)).forEach { (part, kx, kw) ->
            roundRect(kx, 45.5f, kw, 4f, 2f, color(part), 1.2f,
                if (part in lit) CameraColors.Accent.copy(alpha = .18f) else Color(0xFF0C0C0C))
        }
        drawPath(Path().apply { m(122f, 64f); l(129f, 64f); q(133f, 64f, 133f, 68f); l(133f, 148f); q(133f, 152f, 129f, 152f); l(122f, 152f) },
            Diagram.Line, style = stroke(1f))
        circleAt(268f, 106f, 40f, Diagram.Ink, 1.5f, Color(0xFF101010))
        circleAt(268f, 106f, 37.5f, Color(0xFF4A4A4A), 1f, dash = floatArrayOf(1f, 2.2f))
        circleAt(268f, 106f, 33f, Color(0xFF3A3A3A), 1f, Color(0xFF070707))
        listOf(Triple(256f, 92f, 10f), Triple(284f, 96f, 8f), Triple(254f, 121f, 7.5f), Triple(281f, 122f, 11.5f)).forEach { (cx, cy, r) ->
            circleAt(cx, cy, r, Diagram.Ink)
            circleAt(cx, cy, r * .5f, Diagram.Line)
            drawArc(Diagram.Hint, 200f, 60f, false, p(cx - r * .6f, cy - r * .6f), Size(1.2f * r * unit, 1.2f * r * unit),
                style = stroke(1f))
        }
        circleAt(293f, 104f, 1.6f, Diagram.Line)
        circleAt(293f, 110.5f, 1.6f, Diagram.Line)
    }
    if (clipPhoneAt != null) {
        clipRect(left = p(-10f, 0f).x, top = 0f, right = p(clipPhoneAt, 0f).x, bottom = size.height) { phone() }
        segment(clipPhoneAt, 42f, clipPhoneAt, 166f, Diagram.Line, dash = floatArrayOf(3f, 3f))
    } else phone()

    // Grip body
    val silhouette = Path().apply {
        m(38f, 22f); l(104f, 22f); q(114f, 22f, 116f, 30f); l(122f, 50f); l(122f, 150f); q(122f, 170f, 102f, 170f)
        l(40f, 170f); q(20f, 170f, 20f, 150f); l(20f, 50f); l(26f, 30f); q(28f, 22f, 38f, 22f); close()
    }
    drawPath(silhouette, Color(0xFF0E0E0E))
    drawPath(Path().apply { m(38f, 22f); l(104f, 22f); q(114f, 22f, 116f, 30f); l(122f, 50f); l(20f, 50f); l(26f, 30f); q(28f, 22f, 38f, 22f); close() },
        Color(0xFF171717))
    val front = Path().apply { m(20f, 50f); l(122f, 50f); l(122f, 150f); q(122f, 170f, 102f, 170f); l(40f, 170f); q(20f, 170f, 20f, 150f); close() }
    clipPath(front) {
        // Fine diamond texture of the grip rubber.
        var offset = -120f
        while (offset < 120f) {
            drawLine(Color(0xFF2C2C2C), p(20f + offset, 50f), p(20f + offset + 120f, 170f), .6.dp.toPx())
            drawLine(Color(0xFF2C2C2C), p(122f - offset, 50f), p(122f - offset - 120f, 170f), .6.dp.toPx())
            offset += 6f * 1.414f
        }
    }
    segment(27f, 58f, 27f, 152f, Color(0xFF232323))
    segment(115f, 58f, 115f, 152f, Color(0xFF232323))
    segment(20f, 50f, 122f, 50f, Color(0xFF3A3A3A))
    drawPath(silhouette, Diagram.Ink, style = stroke(1.5f))

    // USB-C plug where the grip enters the phone
    val usbOn = GripPart.Usb in lit
    val usb = if (usbOn) CameraColors.Accent else Diagram.Hint
    val usbDash = if (usbOn) null else floatArrayOf(2.5f, 2f)
    segment(100f, 64f, 100f, 152f, Color(0xFF3A3A3A), dash = floatArrayOf(3f, 3f))
    roundRect(88f, 102f, 14f, 12f, 3f, usb, 1f, Color(0xFF0E0E0E), usbDash)
    roundRect(102f, 104.5f, 8f, 7f, 2f, usb, 1f, if (usbOn) CameraColors.Accent.copy(alpha = .2f) else Color(0xFF0E0E0E), usbDash)

    // Status light
    circleAt(34f, 40f, 3.2f, Diagram.Line, fill = Color(0xFF0B0B0B))
    drawCircle(color(GripPart.Light), 1.4f * unit, p(34f, 40f))

    // Zoom lever around the shutter, with a tab that rocks left and right
    val zoom = color(GripPart.Zoom)
    val tab = Path().apply { m(51f, 28f); l(49.5f, 19.5f); q(49.5f, 17f, 52f, 17f); l(64f, 17f); q(66.5f, 17f, 66.5f, 19.5f); l(65f, 28f) }
    drawPath(tab, Color(0xFF121212))
    drawPath(tab, zoom, style = stroke(1.3f))
    segment(54f, 20.5f, 62f, 20.5f, Diagram.Line)
    drawOvalAt(58f, 35f, 14f, 9f, zoom, 1.3f, Color(0xFF121212))
    if (GripPart.Zoom in lit) {
        val arrows = Path().apply {
            m(46f, 17.5f); l(40f, 17.5f); m(42.2f, 15.3f); l(40f, 17.5f); l(42.2f, 19.7f)
            m(70f, 17.5f); l(76f, 17.5f); m(73.8f, 15.3f); l(76f, 17.5f); l(73.8f, 19.7f)
        }
        drawPath(arrows, CameraColors.Accent, style = stroke(1.3f))
    }

    // Shutter button
    val shutterOn = GripPart.Shutter in lit
    val shutter = color(GripPart.Shutter)
    val shutterRim = rim(58f, 33f, 36.5f, 8.5f, 5.5f)
    drawPath(shutterRim, Color(0xFF101010))
    drawPath(shutterRim, shutter, style = stroke(1.3f))
    drawOvalAt(58f, 33f, 8.5f, 5.5f, shutter, 1.5f, if (shutterOn) CameraColors.Accent.copy(alpha = .16f) else Color(0xFF1A1A1A))
    drawOvalAt(58f, 33f, 4.8f, 3f, if (shutterOn) CameraColors.Accent else Diagram.Line, 1f)

    // Knurled dial
    val dialOn = GripPart.Dial in lit
    val dial = color(GripPart.Dial)
    val dialRim = rim(88f, 30f, 37f, 12f, 7.5f)
    drawPath(dialRim, Color(0xFF0F0F0F))
    drawPath(dialRim, dial, style = stroke(1.3f))
    var knurl = 77.4f
    while (knurl < 99f) {
        val top = 30f + 7.5f * sqrt((1f - ((knurl - 88f) / 12f).let { it * it }).coerceAtLeast(0f))
        segment(knurl, top + .9f, knurl, top + 6.3f, if (dialOn) CameraColors.Accent.copy(alpha = .55f) else Color(0xFF4A4A4A), .8f)
        knurl += 2.1f
    }
    drawOvalAt(88f, 30f, 12f, 7.5f, dial, 1.5f, Color(0xFF161616))
    drawOvalAt(88f, 30f, 7.5f, 4.6f, Color(0xFF2E2E2E), 1f)
    drawOvalAt(88f, 30f, 3.5f, 2.1f, Color(0xFF3A3A3A), 1f)
    if (dialOn) {
        val turn = Path().apply {
            m(73f, 41f); q(88f, 55f, 103f, 41f)
            m(103f, 41f); l(98.6f, 42f); m(103f, 41f); l(101.7f, 45.3f)
            m(73f, 41f); l(74.3f, 45.3f); m(73f, 41f); l(77.4f, 42f)
        }
        drawPath(turn, CameraColors.Accent, style = stroke(1.3f))
    }

    // Extra button on the top edge near the phone
    val record = color(GripPart.Record)
    val recordRim = rim(109f, 37f, 39.5f, 4.5f, 2.8f)
    drawPath(recordRim, Color(0xFF0F0F0F))
    drawPath(recordRim, record, style = stroke(1.2f))
    drawOvalAt(109f, 37f, 4.5f, 2.8f, record, 1.3f,
        if (GripPart.Record in lit) CameraColors.Accent.copy(alpha = .16f) else Color(0xFF1A1A1A))
}

/** Small outline icon of a photography grip, used where an accessory is named. */
@Composable
internal fun GripIcon(modifier: Modifier, color: Color = CameraColors.Accent) {
    Canvas(modifier) { drawGripIcon(color) }
}

private fun DrawScope.drawGripIcon(color: Color) {
    val unit = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * unit, y * unit)
    val style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val grip = Path().apply {
        moveTo(o(3f, 8.5f).x, o(3f, 8.5f).y)
        cubicTo(o(3f, 7f).x, o(3f, 7f).y, o(4f, 6f).x, o(4f, 6f).y, o(5.5f, 6f).x, o(5.5f, 6f).y)
        lineTo(o(9f, 6f).x, o(9f, 6f).y); lineTo(o(9f, 19f).x, o(9f, 19f).y); lineTo(o(5.5f, 19f).x, o(5.5f, 19f).y)
        cubicTo(o(4f, 19f).x, o(4f, 19f).y, o(3f, 18f).x, o(3f, 18f).y, o(3f, 16.5f).x, o(3f, 16.5f).y)
        close()
    }
    val phone = Path().apply {
        moveTo(o(9f, 8f).x, o(9f, 8f).y); lineTo(o(19.5f, 8f).x, o(19.5f, 8f).y)
        cubicTo(o(21f, 8f).x, o(21f, 8f).y, o(22f, 9f).x, o(22f, 9f).y, o(22f, 10.5f).x, o(22f, 10.5f).y)
        lineTo(o(22f, 16.5f).x, o(22f, 16.5f).y)
        cubicTo(o(22f, 17f).x, o(22f, 17f).y, o(21f, 18f).x, o(21f, 18f).y, o(19.5f, 18f).x, o(19.5f, 18f).y)
        lineTo(o(9f, 18f).x, o(9f, 18f).y)
        moveTo(o(5f, 6f).x, o(5f, 6f).y); lineTo(o(5f, 4.5f).x, o(5f, 4.5f).y); lineTo(o(7.5f, 4.5f).x, o(7.5f, 4.5f).y)
        lineTo(o(7.5f, 6f).x, o(7.5f, 6f).y)
    }
    drawPath(grip, color, style = style)
    drawPath(phone, color, style = style)
    drawCircle(color, 2.5f * unit, o(17f, 13f), style = style)
}
