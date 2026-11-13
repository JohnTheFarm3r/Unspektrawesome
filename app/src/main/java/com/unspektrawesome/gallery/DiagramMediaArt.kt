package com.unspektrawesome.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramMenu
import com.unspektrawesome.ui.camera.DiagramPen
import com.unspektrawesome.ui.camera.DiagramStage
import com.unspektrawesome.ui.camera.DiagramText
import com.unspektrawesome.ui.camera.DiagramDrawing
import com.unspektrawesome.ui.camera.SettingsIcon
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.ui.camera.at

/** Shared drawings and small controls of the gallery browser, viewer, information panel and BURST viewer. */
internal object MediaArt {
    val TileShade = Color(0x9E000000)
    val SoftInk = Color(0xFFCFCFCF)
    val Outline = Color(0xFFBDBDBD)
    val Arrow = Color(0xFF6A6A6A)
    val Connector = Color(0xFF444444)
    val Detail = Color(0xFF8C8C8C)
    val Halation = Color(0xFFFF7974)
    val TileShape = RoundedCornerShape(3.dp)
    val BadgeShape = RoundedCornerShape(4.dp)
}

// Browser controls

/** Compact dropdown of the gallery browser: a small caption, the value and a chevron, with an anchored menu. */
@Composable
internal fun MediaDropdownField(
    caption: String,
    value: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuWidth: Dp = 250.dp,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val renderer = LocalAppLanguage.current.renderer
    Box(modifier.heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onExpand),
        contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(Diagram.ControlShape)
            .background(if (expanded) CameraColors.Accent.copy(alpha = .06f) else Diagram.Glass)
            .border(1.dp, if (expanded) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
            .padding(horizontal = 10.dp, vertical = 6.dp).alpha(if (enabled) 1f else .45f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(renderer.text(caption).uppercase(), translate = false, color = Diagram.Hint, fontSize = 9.sp,
                lineHeight = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = if (expanded) CameraColors.Accent else CameraColors.Text, fontSize = 12.5.sp,
                lineHeight = 16.sp, modifier = Modifier.weight(1f))
            SettingsIcon(SettingsSymbol.Dropdown, Modifier.size(12.dp).rotate(if (expanded) 180f else 0f),
                if (expanded) CameraColors.Accent else Diagram.Hint)
        }
        DiagramMenu(expanded && enabled, onDismiss, minWidth = menuWidth, content = content)
    }
}

/**
 * Menu row of the gallery menus: an optional check column, the label with its note and a trailing drawing.
 * Choice menus mark the selected row with lime text and a check.
 */
@Composable
internal fun MediaMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    detail: String? = null,
    enabled: Boolean = true,
    choice: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, role = if (choice) Role.RadioButton else Role.Button, onClick = onClick)
        .then(if (choice) Modifier.semantics { this.selected = selected } else Modifier)
        .background(if (selected) CameraColors.Accent.copy(alpha = .06f) else Color.Transparent)
        .padding(horizontal = 8.dp, vertical = 7.dp).alpha(if (enabled) 1f else .4f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (choice) Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            if (selected) SettingsIcon(SettingsSymbol.Check, Modifier.size(14.dp), CameraColors.Accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (selected) CameraColors.Accent else CameraColors.Text, fontSize = 13.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp) }
        }
        trailing?.invoke()
    }
}

/** Four small marks for camera photos, developed photos, BURST and videos; lit marks are included by a filter. */
@Composable
internal fun FilterTokens(included: List<Boolean>) {
    Canvas(Modifier.size(77.dp, 13.dp)) {
        included.forEachIndexed { index, on ->
            val left = index * 20.dp.toPx()
            val ink = if (on) CameraColors.Accent else Color(0xFF4E4E4E)
            drawRoundRect(if (on) CameraColors.Accent.copy(alpha = .75f) else Color(0xFF333333), Offset(left, 0f),
                Size(17.dp.toPx(), 13.dp.toPx()), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
            val cx = left + 8.5.dp.toPx()
            val cy = 6.5.dp.toPx()
            val stroke = Stroke(1.dp.toPx())
            when (index) {
                0, 1 -> {
                    drawRect(ink, Offset(cx - 4.dp.toPx(), cy - 3.dp.toPx()), Size(8.dp.toPx(), 6.dp.toPx()), style = stroke)
                    if (index == 1) drawCircle(ink, .9.dp.toPx(), Offset(cx - 1.8.dp.toPx(), cy - .8.dp.toPx()))
                }
                2 -> {
                    drawRect(ink, Offset(cx - 4.dp.toPx(), cy - 1.5.dp.toPx()), Size(6.dp.toPx(), 4.5.dp.toPx()), style = stroke)
                    drawLine(ink, Offset(cx - 2.dp.toPx(), cy - 3.dp.toPx()), Offset(cx + 4.dp.toPx(), cy - 3.dp.toPx()), 1.dp.toPx())
                    drawLine(ink, Offset(cx + 4.dp.toPx(), cy - 3.dp.toPx()), Offset(cx + 4.dp.toPx(), cy + 1.5.dp.toPx()), 1.dp.toPx())
                }
                else -> drawPath(Path().apply {
                    moveTo(cx - 2.dp.toPx(), cy - 2.8.dp.toPx()); lineTo(cx + 3.dp.toPx(), cy); lineTo(cx - 2.dp.toPx(), cy + 2.8.dp.toPx()); close()
                }, ink)
            }
        }
    }
}

/** Miniature of the grid layout each grouping produces: header bars over rows of tiles. */
@Composable
internal fun GroupingGlyph(grouping: Int, selected: Boolean) {
    val header = if (selected) CameraColors.Accent else Diagram.Hint
    Canvas(Modifier.size(30.dp, 26.dp)) {
        fun bar(x: Float, y: Float, w: Float) = drawRect(header, Offset(x.dp.toPx(), y.dp.toPx()), Size(w.dp.toPx(), 2.dp.toPx()))
        fun tile(x: Float, y: Float) = drawRect(Diagram.Line, Offset(x.dp.toPx(), y.dp.toPx()), Size(12.dp.toPx(), 4.5.dp.toPx()),
            style = Stroke(1.dp.toPx()))
        when (grouping) {
            0 -> { bar(1f, 1f, 11f); tile(1.5f, 5f); tile(16f, 5f); bar(1f, 11.5f, 8f); tile(1.5f, 15f); bar(1f, 22f, 10f) }
            1 -> { bar(1f, 1f, 14f); listOf(5f, 11.5f, 18f).forEach { tile(1.5f, it); tile(16f, it) } }
            else -> listOf(1.5f, 8f, 14.5f, 21f).forEach { tile(1.5f, it); tile(16f, it) }
        }
    }
}

/** Round icon button of the browser title row. */
@Composable
internal fun MediaRoundButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false,
    onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val ink = if (active) CameraColors.Accent else CameraColors.Text
    Box(modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label) }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(Diagram.Glass, CircleShape)
            .border(1.dp, if (active) CameraColors.Accent else Diagram.Border, CircleShape).alpha(if (enabled) 1f else .4f),
            contentAlignment = Alignment.Center) { icon(ink) }
    }
}

/** Notice above the grid: the RAW to Processing to JPEG chain with the waiting count. */
@Composable
internal fun QueueNoticeFlow(waiting: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(Diagram.ControlShape)
        .background(Color(0x990A0A0A)).border(1.dp, Diagram.Border, Diagram.ControlShape).dottedGrid()
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowChip("{count} RAW", active = true, arguments = mapOf("count" to waiting))
            FlowArrow(Modifier.weight(1f))
            FlowChip("Processing")
            FlowArrow(Modifier.weight(1f))
            FlowChip("JPEG", translate = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("{waiting} RAW {value2} ready to develop", arguments = mapOf("waiting" to waiting,
                "value2" to (if (waiting == 1) "photo" else "photos")), color = CameraColors.Text, fontSize = 12.5.sp,
                lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SettingsIcon(SettingsSymbol.Dropdown, Modifier.size(14.dp).rotate(-90f), Diagram.Hint)
        }
        Text("Captured settings saved with every shot", color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun FlowChip(text: String, active: Boolean = false, translate: Boolean = true, arguments: Map<String, Any?>? = null) {
    Text(text, translate = translate, arguments = arguments, color = if (active) CameraColors.Accent else CameraColors.Text,
        fontSize = 10.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono, maxLines = 1,
        modifier = Modifier.background(Color(0xFF0A0A0A), MediaArt.BadgeShape)
            .border(1.dp, if (active) CameraColors.Accent else MediaArt.Outline, MediaArt.BadgeShape)
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
private fun FlowArrow(modifier: Modifier, color: Color = MediaArt.Arrow) {
    Canvas(modifier.widthIn(min = 16.dp).height(8.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width - 4.dp.toPx(), y), 1.dp.toPx())
        drawPath(Path().apply {
            moveTo(size.width, y); lineTo(size.width - 5.dp.toPx(), y - 3.5.dp.toPx()); lineTo(size.width - 5.dp.toPx(), y + 3.5.dp.toPx()); close()
        }, color)
    }
}

internal fun Modifier.dottedGrid(): Modifier = drawBehind {
    val step = 12.dp.toPx()
    val dot = Color.White.copy(alpha = .08f)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(dot, 1.dp.toPx(), Offset(x, y)); x += step }
        y += step
    }
}

// Tile marks

@Composable
internal fun SpektrafilmBadge(modifier: Modifier = Modifier) {
    Text("spektrafilm", color = CameraColors.Accent, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.background(MediaArt.TileShade, MediaArt.BadgeShape)
            .border(1.dp, CameraColors.Accent.copy(alpha = .5f), MediaArt.BadgeShape).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
internal fun VideoDurationBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Row(modifier.background(MediaArt.TileShade, MediaArt.BadgeShape).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GalleryIcon(4, CameraColors.Text, Modifier.size(9.dp))
        Text(videoDuration(durationMs), translate = false, color = CameraColors.Text, fontSize = 11.sp, lineHeight = 14.sp,
            fontFamily = Diagram.Mono)
    }
}

@Composable
internal fun BurstBadge(modifier: Modifier = Modifier) {
    Text("BURST", color = CameraColors.Text, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = modifier.background(MediaArt.TileShade, MediaArt.BadgeShape)
            .border(1.dp, Color(0xFF555555), MediaArt.BadgeShape).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
internal fun BurstStackBadge(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(modifier.size(size).background(MediaArt.TileShade, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        GallerySymbolIcon(GallerySymbol.STACK, CameraColors.Text, Modifier.size(size * .56f))
    }
}

/** Selection checkbox: white outline when free, lime with a black check when chosen. */
@Composable
internal fun MediaCheckbox(checked: Boolean, modifier: Modifier = Modifier, round: Boolean = false, size: Dp = 18.dp,
    dimmed: Boolean = false) {
    Canvas(modifier.size(size).alpha(if (dimmed) .4f else 1f)) {
        val radius = if (round) this.size.minDimension / 2 else 4.dp.toPx()
        val corner = CornerRadius(radius)
        if (checked) drawRoundRect(CameraColors.Accent, cornerRadius = corner)
        else {
            drawRoundRect(Color.Black.copy(alpha = .5f), cornerRadius = corner)
            val stroke = (if (round) 1.dp else 1.5.dp).toPx()
            drawRoundRect(Diagram.Ink, Offset(stroke / 2, stroke / 2), Size(this.size.width - stroke, this.size.height - stroke),
                CornerRadius((radius - stroke / 2).coerceAtLeast(0f)), style = Stroke(stroke))
        }
        if (checked) drawPath(Path().apply {
            moveTo(this@Canvas.size.width * .26f, this@Canvas.size.height * .52f)
            lineTo(this@Canvas.size.width * .44f, this@Canvas.size.height * .7f)
            lineTo(this@Canvas.size.width * .76f, this@Canvas.size.height * .33f)
        }, Color.Black, style = Stroke((size.value / 7f).dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Small lime spinner used for items that are still being prepared or exported. */
@Composable
internal fun MediaSpinner(modifier: Modifier = Modifier, size: Dp = 22.dp, track: Color = Color(0xFF3A3A3A)) {
    CircularProgressIndicator(modifier.size(size), color = CameraColors.Accent, strokeWidth = 2.dp, trackColor = track)
}

// Viewer controls

/** Share, Details and More as one segmented bar; wraps into separate buttons when translated labels do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewerActionBar(
    detailsVisible: Boolean,
    moreExpanded: Boolean,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onMore: () -> Unit,
    onDismissMore: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.() -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val required = with(density) {
        listOf("Share", "Details", "More").maxOf { measurer.measure(AnnotatedString(AppLanguage.text(it)), style).size.width }.toDp()
    } + 46.dp
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val columns = ((maxWidth + 6.dp) / (required.coerceAtLeast(64.dp) + 6.dp)).toInt().coerceIn(1, 3)
        val more: @Composable (Modifier, Boolean) -> Unit = { cellModifier, framed ->
            Box(cellModifier) {
                ViewerActionCell("More", GallerySymbol.MORE, Modifier.fillMaxWidth(), framed, active = moreExpanded, onClick = onMore)
                DiagramMenu(moreExpanded, onDismissMore, minWidth = 176.dp, content = menu)
            }
        }
        if (columns == 3) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(Diagram.ControlShape).background(Diagram.Glass)
                .border(1.dp, Diagram.Border, Diagram.ControlShape)) {
                ViewerActionCell("Share", GallerySymbol.SHARE, Modifier.weight(1f).fillMaxHeight(), false, onClick = onShare)
                ActionDivider()
                ViewerActionCell("Details", GallerySymbol.DETAILS, Modifier.weight(1f).fillMaxHeight(), false,
                    active = detailsVisible, onClick = onDetails)
                ActionDivider()
                more(Modifier.weight(1f).fillMaxHeight(), false)
            }
        } else {
            val slot = (maxWidth - 6.dp * (columns - 1)) / columns
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ViewerActionCell("Share", GallerySymbol.SHARE, Modifier.width(slot), true, onClick = onShare)
                ViewerActionCell("Details", GallerySymbol.DETAILS, Modifier.width(slot), true, active = detailsVisible, onClick = onDetails)
                more(Modifier.width(slot), true)
            }
        }
    }
}

@Composable
private fun ActionDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF1F1F1F)))
}

@Composable
private fun ViewerActionCell(label: String, icon: GallerySymbol, modifier: Modifier, framed: Boolean, active: Boolean = false,
    onClick: () -> Unit) {
    val ink = if (active) CameraColors.Accent else CameraColors.Text
    Row(modifier.heightIn(min = 48.dp)
        .then(if (framed) Modifier.clip(Diagram.ControlShape).background(Diagram.Glass)
            .border(1.dp, if (active) CameraColors.Accent else Diagram.Border, Diagram.ControlShape) else Modifier)
        .clickable(role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label); selected = active }
        .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        GallerySymbolIcon(icon, ink, Modifier.size(15.dp))
        Text(label, color = ink, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Square outlined icon button of the viewer header with a 48 dp touch area. */
@Composable
internal fun ViewerIconButton(label: String, symbol: GallerySymbol, modifier: Modifier = Modifier, enabled: Boolean = true,
    onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(Diagram.ControlShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label) }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(Diagram.Glass, Diagram.ControlShape).border(1.dp, Diagram.Border, Diagram.ControlShape)
            .alpha(if (enabled) 1f else .4f), contentAlignment = Alignment.Center) {
            GallerySymbolIcon(symbol, CameraColors.Text, Modifier.size(16.dp))
        }
    }
}

/** Centre pill of the viewer header: capture date over time and position in the list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewerDatePill(photo: GalleryPhoto?, index: Int, count: Int, modifier: Modifier = Modifier) {
    Column(modifier.clip(Diagram.ControlShape).background(Diagram.Glass).border(1.dp, Diagram.Border, Diagram.ControlShape)
        .padding(horizontal = 12.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (photo != null) {
            Text(date(photo.capturedAt, "d MMMM yyyy"), translate = false, color = CameraColors.Text, fontSize = 13.sp,
                lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text(date(photo.capturedAt, "HH:mm:ss"), translate = false, color = Diagram.Hint, fontSize = 10.5.sp,
                    lineHeight = 14.sp, fontFamily = Diagram.Mono)
                Text("·", translate = false, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono)
                Text("{value1} / {size}", arguments = mapOf("value1" to index + 1, "size" to count), color = Diagram.Hint,
                    fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono)
            }
        } else {
            Text("Media unavailable", color = Diagram.Hint, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("{value1} / {size}", arguments = mapOf("value1" to 0, "size" to 0), color = Diagram.Hint, fontSize = 10.5.sp,
                lineHeight = 14.sp, fontFamily = Diagram.Mono)
        }
    }
}

/** Format tag on the right of a viewer menu row, such as DNG or JPEG. */
@Composable
internal fun MenuFormatTag(text: String) {
    Text(text, translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
}

internal fun formatTag(mimeType: String): String? = when (mimeType) {
    "image/jpeg" -> "JPEG"
    "image/png" -> "PNG"
    else -> null
}

// Information panel

/** Uppercase section heading of the information panel with an optional note on the right. */
@Composable
internal fun InfoHeading(title: String, modifier: Modifier = Modifier, accent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null) {
    val renderer = LocalAppLanguage.current.renderer
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(renderer.text(title).uppercase(), translate = false, color = if (accent) CameraColors.Accent else Diagram.Hint,
            fontSize = 9.5.sp, lineHeight = 13.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

@Composable
internal fun InfoNote(text: String, modifier: Modifier = Modifier, arguments: Map<String, Any?>? = null) {
    Text(text, modifier, arguments = arguments, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.End)
}

/** Bordered grid of label-over-value cells; a short last row stretches its cells. */
@Composable
internal fun InfoGrid(fields: List<PhotoInfoField>, columns: Int, modifier: Modifier = Modifier,
    accent: (PhotoInfoField) -> Boolean = { false }) {
    if (fields.isEmpty()) return
    val shape = RoundedCornerShape(6.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Color(0xCC0A0A0A)).border(1.dp, Color(0xFF1F1F1F), shape)) {
        fields.chunked(columns.coerceAtLeast(1)).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F1F1F)))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { index, field ->
                    if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF1F1F1F)))
                    val span = if (index == row.lastIndex) columns - row.size + 1 else 1
                    Column(Modifier.weight(span.toFloat()).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (field.label.isNotEmpty()) Text(field.label, color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 13.sp)
                        Text(field.value, translate = field.translateValue, color = if (accent(field)) CameraColors.Accent else CameraColors.Text,
                            fontSize = 11.5.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono)
                        field.detail?.let { Text(it, color = MediaArt.Detail, fontSize = 9.sp, lineHeight = 12.sp) }
                    }
                }
            }
        }
    }
}

/**
 * One stage of the information drawings: a small line drawing with its caption on the left and the fields
 * joined to a vertical rail on the right. Narrow panels and large text drop the drawing and stack the fields.
 */
@Composable
internal fun InfoStage(
    fields: List<PhotoInfoField>,
    compact: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    drawingHeight: Dp = 40.dp,
    accent: (PhotoInfoField) -> Boolean = { false },
    drawing: (DiagramPen.() -> Unit)? = null,
) {
    if (fields.isEmpty()) return
    val renderer = LocalAppLanguage.current.renderer
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact && drawing != null) Column(Modifier.width(58.dp).padding(top = 2.dp).clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Canvas(Modifier.size(58.dp, drawingHeight)) { DiagramPen(this).drawing() }
            caption?.let {
                Text(renderer.text(it).uppercase(), translate = false, color = CameraColors.Accent, fontSize = 8.sp,
                    lineHeight = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.weight(1f).drawBehind {
            drawLine(MediaArt.Connector, Offset(.5.dp.toPx(), 0f), Offset(.5.dp.toPx(), size.height), 1.dp.toPx())
        }.padding(start = 14.dp)) {
            fields.forEach { InfoRailRow(it, compact, accent(it)) }
        }
    }
}

@Composable
private fun InfoRailRow(field: PhotoInfoField, compact: Boolean, accent: Boolean) {
    val marker = Modifier.drawBehind {
        val y = 9.dp.toPx()
        drawLine(MediaArt.Connector, Offset(-14.dp.toPx(), y), Offset(-5.dp.toPx(), y), 1.dp.toPx())
        drawCircle(CameraColors.Accent, 2.5.dp.toPx(), Offset(-3.5.dp.toPx(), y))
    }
    val value: @Composable (Modifier, TextAlign) -> Unit = { valueModifier, align ->
        Column(valueModifier, horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
            Text(field.value, translate = field.translateValue, color = if (accent) CameraColors.Accent else CameraColors.Text,
                fontSize = 10.5.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono, textAlign = align)
            field.detail?.let { Text(it, color = MediaArt.Detail, fontSize = 9.5.sp, lineHeight = 13.sp, textAlign = align) }
        }
    }
    if (compact || field.label.isEmpty()) Column(Modifier.fillMaxWidth().then(marker).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (field.label.isNotEmpty()) Text(field.label, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
        value(Modifier.fillMaxWidth(), TextAlign.Start)
    } else Row(Modifier.fillMaxWidth().then(marker).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(field.label, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(.45f))
        value(Modifier.weight(.55f), TextAlign.End)
    }
}

/** Downward connector between stages with a short mono caption. */
@Composable
internal fun InfoLink(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 28.dp).height(18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.width(7.dp).fillMaxHeight()) {
            val x = size.width / 2
            drawLine(MediaArt.Arrow, Offset(x, 0f), Offset(x, size.height - 4.dp.toPx()), 1.dp.toPx())
            drawPath(Path().apply {
                moveTo(x, size.height); lineTo(x - 3.5.dp.toPx(), size.height - 5.dp.toPx()); lineTo(x + 3.5.dp.toPx(), size.height - 5.dp.toPx()); close()
            }, MediaArt.Arrow)
        }
        Text(text, color = MediaArt.Detail, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono)
    }
}

/** Start or end of the processing chain: a small file box and what it stands for. */
@Composable
internal fun InfoEndpoint(tag: String, text: String, accent: Boolean, modifier: Modifier = Modifier,
    arguments: Map<String, Any?>? = null) {
    val ink = if (accent) CameraColors.Accent else MediaArt.SoftInk
    Row(modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(26.dp, 18.dp).background(Color(0xFF0A0A0A), RoundedCornerShape(2.dp))
            .border(1.dp, if (accent) CameraColors.Accent else MediaArt.Outline, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
            Text(tag, translate = false, color = ink, fontSize = 6.5.sp, lineHeight = 8.sp, fontFamily = Diagram.Mono)
        }
        Text(text, arguments = arguments, color = ink, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono,
            modifier = Modifier.weight(1f, fill = false))
    }
}

/** Lime outlined value pill used next to section headings. */
@Composable
internal fun InfoPill(label: String, value: String, translateValue: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
        Text(value, translate = translateValue, color = if (active) CameraColors.Accent else Diagram.Hint, fontSize = 10.sp,
            lineHeight = 13.sp, fontFamily = Diagram.Mono, fontWeight = FontWeight.Medium,
            modifier = Modifier.border(1.dp, if (active) CameraColors.Accent.copy(alpha = .55f) else Diagram.Border, MediaArt.BadgeShape)
                .padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** Chain of small label-over-value boxes joined by arrows; stacks vertically when narrow. */
@Composable
internal fun InfoChain(fields: List<PhotoInfoField>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val vertical = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.4f
        val node: @Composable (PhotoInfoField, Boolean, Modifier) -> Unit = { field, last, nodeModifier ->
            Column(nodeModifier.background(Color(0xFF0A0A0A), RoundedCornerShape(3.dp))
                .border(1.dp, if (last) CameraColors.Accent else MediaArt.Outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(field.label, color = Diagram.Hint, fontSize = 8.5.sp, lineHeight = 10.sp)
                Text(field.value, translate = field.translateValue, color = CameraColors.Text, fontSize = 10.sp, lineHeight = 13.sp,
                    fontFamily = Diagram.Mono)
                field.detail?.let { Text(it, color = MediaArt.Detail, fontSize = 8.5.sp, lineHeight = 11.sp) }
            }
        }
        if (vertical) Column(Modifier.fillMaxWidth()) {
            fields.forEachIndexed { index, field ->
                node(field, index == fields.lastIndex, Modifier.fillMaxWidth())
                if (index < fields.lastIndex) InfoLink("", Modifier.padding(start = 0.dp))
            }
        } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            fields.forEachIndexed { index, field ->
                node(field, index == fields.lastIndex, Modifier.weight(1f).fillMaxHeight())
                if (index < fields.lastIndex) FlowArrow(Modifier.width(12.dp).widthIn(max = 12.dp))
            }
        }
    }
}

/**
 * The video file drawn as its streams: a container with a video lane cut from the cover frame, an
 * audio lane when the file has sound, and a playhead at the remembered position.
 */
@Composable
internal fun VideoStreamMap(photo: GalleryPhoto, fileSize: String?, hasAudio: Boolean, positionMs: Long, durationMs: Long,
    modifier: Modifier = Modifier) {
    val renderer = LocalAppLanguage.current.renderer
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val format = photo.mimeType.substringAfter('/').uppercase()
    Column(modifier.fillMaxWidth().padding(top = 7.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().background(Color(0x80000000), MediaArt.BadgeShape)
                .border(1.dp, MediaArt.Outline, MediaArt.BadgeShape).padding(start = 8.dp, end = 8.dp, top = 13.dp, bottom = 7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.width(40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LaneLabel("Video", true)
                        if (hasAudio) LaneLabel("Audio", false)
                    }
                    Box(Modifier.weight(1f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth().height(20.dp).border(1.dp, CameraColors.Accent.copy(alpha = .55f), RoundedCornerShape(2.dp))
                                .padding(1.dp).clip(RoundedCornerShape(1.dp)).clearAndSetSemantics { }) {
                                PhotoImage(photo.uri, null, Modifier.fillMaxSize(), imageKey = photo.generation, isVideo = true,
                                    contentScale = ContentScale.Crop)
                                Canvas(Modifier.matchParentSize()) {
                                    val cells = 9
                                    for (index in 1 until cells) {
                                        val x = size.width * index / cells
                                        drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                                    }
                                }
                            }
                            if (hasAudio) Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                                drawRoundRect(Color(0xFF444444), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
                                val bars = (size.width / 4.dp.toPx()).toInt().coerceAtLeast(1)
                                for (index in 0 until bars) {
                                    val x = 3.dp.toPx() + index * 4.dp.toPx()
                                    if (x > size.width - 2.dp.toPx()) break
                                    val level = .3f + .6f * ((kotlin.math.sin(index * 1.7) * .5 + .5) * .6 + (kotlin.math.sin(index * .43) * .5 + .5) * .4).toFloat()
                                    val half = (size.height / 2 - 2.dp.toPx()) * level
                                    drawLine(MediaArt.Detail, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), 1.4.dp.toPx())
                                }
                            }
                        }
                        Canvas(Modifier.matchParentSize()) {
                            val x = size.width * fraction
                            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                            drawPath(Path().apply {
                                moveTo(x - 3.5.dp.toPx(), -2.dp.toPx()); lineTo(x + 3.5.dp.toPx(), -2.dp.toPx()); lineTo(x, 3.dp.toPx()); close()
                            }, Color.White)
                        }
                    }
                }
            }
            Text(renderer.format("{format} container", mapOf("format" to format)).uppercase(), translate = false,
                color = MediaArt.SoftInk, fontSize = 8.5.sp, lineHeight = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp).offset(y = (-6).dp).background(Color(0xFF0A0A0A)).padding(horizontal = 5.dp))
            fileSize?.let {
                Text(it, translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 11.sp, fontFamily = Diagram.Mono,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).offset(y = (-6).dp).background(Color(0xFF0A0A0A))
                        .padding(horizontal = 5.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 54.dp, end = 8.dp, top = 2.dp)) {
            BoxWithConstraints(Modifier.weight(1f).height(13.dp)) {
                val current = videoDuration(positionMs)
                Text("0:00", translate = false, color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono)
                if (durationMs > 0) Text(current, translate = false, color = CameraColors.Accent, fontSize = 9.sp, lineHeight = 12.sp,
                    fontFamily = Diagram.Mono, modifier = Modifier.offset(x = (maxWidth * fraction - 12.dp).coerceIn(28.dp, (maxWidth - 64.dp).coerceAtLeast(28.dp))))
                Text(videoDuration(durationMs), translate = false, color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp,
                    fontFamily = Diagram.Mono, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun LaneLabel(text: String, accent: Boolean) {
    val renderer = LocalAppLanguage.current.renderer
    Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
        Text(renderer.text(text).uppercase(), translate = false, color = if (accent) CameraColors.Accent else MediaArt.SoftInk,
            fontSize = 8.5.sp, lineHeight = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Markers on a bar for each settings change of a recording, labelled with time and film. */
@Composable
internal fun SettingsChangeTimeline(changes: List<Pair<Double, String?>>, durationSeconds: Double, modifier: Modifier = Modifier) {
    if (changes.isEmpty()) return
    val total = durationSeconds.takeIf { it > 0 } ?: (changes.maxOf { it.first } + 1.0)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 4.dp).height(38.dp).clearAndSetSemantics { }) {
        val width = maxWidth
        val starts = changes.map { (it.first / total).coerceIn(0.0, 1.0).toFloat() }
        Canvas(Modifier.fillMaxWidth().padding(top = 14.dp).height(8.dp)) {
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: 1f
                drawRect(if (index % 2 == 0) CameraColors.Accent else Diagram.Line, Offset(size.width * start, 0f),
                    Size(size.width * (end - start), size.height))
            }
            starts.forEachIndexed { index, start ->
                drawLine(if (index == 0) CameraColors.Accent else Color.White, Offset(size.width * start, -4.dp.toPx()),
                    Offset(size.width * start, size.height + 4.dp.toPx()), 1.dp.toPx())
            }
        }
        changes.forEachIndexed { index, (time, film) ->
            val x = width * starts[index]
            val room = width * ((starts.getOrNull(index + 1) ?: 1f) - starts[index])
            val labelModifier = Modifier.offset(x = x.coerceAtMost((width - 40.dp).coerceAtLeast(0.dp))).widthIn(max = room.coerceAtLeast(40.dp))
            Text(String.format(java.util.Locale.ROOT, "%.3f s", time), translate = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (index == 0) CameraColors.Accent else Color.White, fontSize = 9.sp, lineHeight = 12.sp,
                fontFamily = Diagram.Mono, modifier = labelModifier)
            film?.let {
                Text(it, translate = false, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Diagram.Hint, fontSize = 9.sp,
                    lineHeight = 12.sp, fontFamily = Diagram.Mono, modifier = labelModifier.padding(top = 25.dp))
            }
        }
    }
}

/** Waveform mark next to the audio heading. */
@Composable
internal fun AudioWaveMark() {
    Canvas(Modifier.size(80.dp, 12.dp)) {
        var x = 1.dp.toPx()
        var index = 0
        while (x < size.width) {
            val level = .25f + .7f * ((kotlin.math.sin(index * 1.3) * .5 + .5) * .7 + (kotlin.math.sin(index * .37) * .5 + .5) * .3).toFloat()
            val half = size.height / 2 * level
            drawLine(MediaArt.Detail, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), 1.dp.toPx())
            x += 3.dp.toPx(); index++
        }
    }
}

// Information drawings, in a 58 dp wide column

internal fun DiagramPen.cameraDiffusionArt(enabled: Boolean) {
    scope.drawOval(MediaArt.Outline, Offset(px(15.dp), px(3.dp)), Size(px(14.dp), px(34.dp)), style = Stroke(px(1.dp)))
    scope.drawOval(if (enabled) CameraColors.Accent else Diagram.Line, Offset(px(18.dp), px(7.dp)), Size(px(8.dp), px(26.dp)),
        style = Stroke(px(1.dp), pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(1.5.dp), px(2.dp)))))
    line(0.dp, 8.dp, 22.dp, 14.dp, MediaArt.Arrow, 1.dp)
    line(0.dp, 32.dp, 22.dp, 26.dp, MediaArt.Arrow, 1.dp)
    val ray = if (enabled) CameraColors.Accent.copy(alpha = .6f) else Diagram.Line
    line(22.dp, 14.dp, 52.dp, 16.dp, ray, 1.dp)
    line(22.dp, 26.dp, 52.dp, 24.dp, ray, 1.dp)
    if (enabled) {
        dot(52.dp, 20.dp, 9.dp, CameraColors.Accent.copy(alpha = .07f))
        dot(52.dp, 20.dp, 5.dp, CameraColors.Accent.copy(alpha = .18f))
    }
}

internal fun DiagramPen.filmArt(halation: Boolean) {
    box(3.dp, 5.dp, 52.dp, 30.dp, 0.dp, MediaArt.Outline, 1.dp)
    line(3.dp, 10.dp, 55.dp, 10.dp, Color(0xFF555555), 1.dp)
    line(3.dp, 30.dp, 55.dp, 30.dp, Color(0xFF555555), 1.dp)
    for (index in 0 until 7) {
        val x = (7 + index * 7).dp
        line(x, 7.5.dp, x + 3.dp, 7.5.dp, MediaArt.Outline, 1.dp)
        line(x, 32.5.dp, x + 3.dp, 32.5.dp, MediaArt.Outline, 1.dp)
    }
    box(12.dp, 13.dp, 34.dp, 14.dp, 0.dp, CameraColors.Accent, 1.dp)
    if (halation) {
        dot(36.dp, 18.dp, 2.5.dp, MediaArt.Halation.copy(alpha = .55f))
        circle(36.dp, 18.dp, 5.dp, MediaArt.Halation.copy(alpha = .45f), 1.dp)
    }
}

internal fun DiagramPen.printArt() {
    box(14.dp, 3.dp, 30.dp, 34.dp, 0.dp, MediaArt.Outline, 1.dp)
    box(18.dp, 7.dp, 22.dp, 18.dp, 0.dp, CameraColors.Accent, 1.dp)
    line(18.dp, 30.dp, 32.dp, 30.dp, Color(0xFF555555), 1.dp)
    line(18.dp, 33.dp, 27.dp, 33.dp, Color(0xFF555555), 1.dp)
}

/** Frames of a merge: used frames lime, unused ones dashed, joined into one result. */
internal fun DiagramPen.mergeArt(merged: Int, total: Int) {
    val count = total.coerceIn(1, 16)
    val columns = (count + 1) / 2
    val gap = 3f
    val cell = ((54f - gap * (columns - 1)) / columns).coerceAtMost(11f)
    for (index in 0 until count) {
        val column = index / 2
        val row = index % 2
        val used = index < merged
        box((2f + column * (cell + gap)).dp, (2f + row * 11f).dp, cell.dp, 8.dp, 0.dp,
            if (used) CameraColors.Accent else Diagram.Line, 1.dp, dashed = !used)
    }
    arrow(22.dp, 23.dp, 22.dp, 32.dp, MediaArt.Arrow, 1.dp)
    box(11.dp, 34.dp, 22.dp, 14.dp, 0.dp, CameraColors.Accent, 1.dp)
}

internal fun DiagramPen.sensorArt() {
    box(15.dp, 2.dp, 28.dp, 52.dp, 5.dp, MediaArt.Outline, 1.dp)
    box(19.dp, 6.dp, 13.dp, 13.dp, 3.dp, MediaArt.Outline, 1.dp)
    circle(25.5.dp, 12.5.dp, 3.5.dp, CameraColors.Accent, 1.dp)
    line(25.5.dp, 19.dp, 25.5.dp, 63.dp, CameraColors.Accent.copy(alpha = .5f), 1.dp, dashed = true)
    box(7.dp, 64.dp, 44.dp, 33.dp, 0.dp, MediaArt.Outline, 1.dp)
    listOf(69, 74, 79, 84, 89).forEach { line(12.dp, it.dp, 46.dp, it.dp, Color(0xFF2E2E2E), 1.dp) }
    polyline(listOf(7.dp to 70.dp, 7.dp to 64.dp, 13.dp to 64.dp), CameraColors.Accent)
    polyline(listOf(45.dp to 64.dp, 51.dp to 64.dp, 51.dp to 70.dp), CameraColors.Accent)
    polyline(listOf(7.dp to 91.dp, 7.dp to 97.dp, 13.dp to 97.dp), CameraColors.Accent)
    polyline(listOf(45.dp to 97.dp, 51.dp to 97.dp, 51.dp to 91.dp), CameraColors.Accent)
}

internal fun DiagramPen.locationArt() {
    circle(29.dp, 17.dp, 12.dp, MediaArt.Outline, 1.dp)
    dot(29.dp, 17.dp, 2.5.dp)
    line(29.dp, 1.dp, 29.dp, 9.dp, MediaArt.Outline, 1.dp)
    line(29.dp, 25.dp, 29.dp, 33.dp, MediaArt.Outline, 1.dp)
    line(9.dp, 17.dp, 17.dp, 17.dp, MediaArt.Outline, 1.dp)
    line(41.dp, 17.dp, 49.dp, 17.dp, MediaArt.Outline, 1.dp)
}

internal fun DiagramPen.videoTrackArt() {
    box(17.dp, 4.dp, 34.dp, 20.dp, 1.dp, Color(0xFF3A3A3A), 1.dp)
    box(12.dp, 9.dp, 34.dp, 20.dp, 1.dp, MediaArt.Arrow, 1.dp)
    box(7.dp, 14.dp, 34.dp, 20.dp, 1.dp, CameraColors.Accent, 1.dp)
    line(7.dp, 42.dp, 41.dp, 42.dp, MediaArt.Arrow, 1.dp)
    listOf(7, 15, 24, 32, 41).forEach { line(it.dp, 40.dp, it.dp, 44.dp, MediaArt.Arrow, 1.dp) }
}

/** Gyro stabilization: the recorded frame and, when on, the cropped steady window inside it. */
internal fun DiagramPen.gyroArt(enabled: Boolean, crop: Double?) {
    box(4.dp, 4.dp, 50.dp, 32.dp, 1.dp, MediaArt.Arrow, 1.dp, dashed = true)
    val scale = if (enabled) (1.0 / (crop ?: 1.2)).coerceIn(.5, 1.0).toFloat() else 1f
    val w = 50f * scale
    val h = 32f * scale
    box((29f - w / 2).dp, (20f - h / 2).dp, w.dp, h.dp, 1.dp, if (enabled) CameraColors.Accent else Diagram.Line, 1.dp)
    line(29.dp, 15.dp, 29.dp, 25.dp, MediaArt.Outline, 1.dp)
    line(24.dp, 20.dp, 34.dp, 20.dp, MediaArt.Outline, 1.dp)
    polyline((0..20).map { step ->
        val x = 14f + step * 1.5f
        (x.dp) to ((43f + 2.5f * kotlin.math.sin(step * .9f)).dp)
    }, if (enabled) MediaArt.Outline else Diagram.Line, 1.dp)
}

private fun DiagramPen.px(value: Dp): Float = with(scope) { value.toPx() }

// Empty gallery, delete dialog and BURST drawings

/** Newest capture on the right, older ones along the capture-time arrow. */
@Composable
internal fun EmptyTimelineArt(modifier: Modifier = Modifier) {
    DiagramStage(76.dp, modifier, description = "Photos and videos appear together, ordered by the moment you captured them.") {
        DiagramDrawing {
            arrow(312.dp, 46.dp, 22.dp, 46.dp, MediaArt.Arrow, 1.dp)
            box(276.dp, 20.dp, 28.dp, 20.dp, 2.dp, CameraColors.Accent, 1.3.dp)
            line(290.dp, 40.dp, 290.dp, 46.dp, CameraColors.Accent, 1.dp)
            box(224.dp, 20.dp, 28.dp, 20.dp, 2.dp, Diagram.Ink, 1.3.dp)
            scope.drawPath(Path().apply {
                moveTo(px(235.dp), px(25.dp)); lineTo(px(242.dp), px(30.dp)); lineTo(px(235.dp), px(35.dp)); close()
            }, Diagram.Ink)
            line(238.dp, 40.dp, 238.dp, 46.dp, Diagram.Line, 1.dp)
            box(164.dp, 17.dp, 46.dp, 20.dp, 2.dp, Color(0xFF3A3A3A), 1.dp)
            box(160.dp, 20.dp, 46.dp, 20.dp, 2.dp, Diagram.Ink, 1.3.dp)
            line(183.dp, 40.dp, 183.dp, 46.dp, Diagram.Line, 1.dp)
            box(118.dp, 20.dp, 28.dp, 20.dp, 2.dp, Diagram.Ink, 1.3.dp)
            line(132.dp, 40.dp, 132.dp, 46.dp, Diagram.Line, 1.dp)
            box(74.dp, 20.dp, 28.dp, 20.dp, 2.dp, Diagram.Ink, 1.3.dp)
            scope.drawPath(Path().apply {
                moveTo(px(85.dp), px(25.dp)); lineTo(px(92.dp), px(30.dp)); lineTo(px(85.dp), px(35.dp)); close()
            }, Diagram.Ink)
            line(88.dp, 40.dp, 88.dp, 46.dp, Diagram.Line, 1.dp)
        }
        DiagramText("newest", Modifier.at(262.dp, 52.dp), CameraColors.Accent, 8.5f, mono = true, maxWidth = 70.dp)
        DiagramText("BURST", Modifier.at(166.dp, 52.dp), size = 8.5f, mono = true, translate = false, maxWidth = 60.dp)
        DiagramText("capture time", Modifier.at(22.dp, 52.dp), size = 8.5f, mono = true, maxWidth = 120.dp)
    }
}

private fun DiagramPen.document(x: Float, y: Float, color: Color, dashed: Boolean = false) {
    polyline(listOf(x.dp to y.dp, (x + 16).dp to y.dp, (x + 22).dp to (y + 6).dp, (x + 22).dp to (y + 30).dp,
        x.dp to (y + 30).dp, x.dp to y.dp), color, 1.3.dp, dashed)
}

private fun DiagramPen.documentStack(x: Float, y: Float, front: Color) {
    polyline(listOf((x + 12).dp to (y + 24).dp, (x + 12).dp to y.dp, (x + 28).dp to y.dp, (x + 34).dp to (y + 6).dp, (x + 34).dp to (y + 30).dp),
        Color(0xFF3A3A3A), 1.3.dp)
    polyline(listOf((x + 6).dp to (y + 30).dp, (x + 6).dp to (y + 6).dp, (x + 22).dp to (y + 6).dp, (x + 28).dp to (y + 12).dp, (x + 28).dp to (y + 36).dp),
        Diagram.Line, 1.3.dp)
    document(x, y + 12, front)
}

private fun DiagramPen.bin(x: Float, y: Float, color: Color) {
    line(x.dp, y.dp, (x + 28).dp, y.dp, color, 1.4.dp)
    polyline(listOf((x + 6).dp to y.dp, (x + 6).dp to (y - 4).dp, (x + 22).dp to (y - 4).dp, (x + 22).dp to y.dp), color, 1.4.dp)
    polyline(listOf((x + 4).dp to y.dp, (x + 6).dp to (y + 22).dp, (x + 22).dp to (y + 22).dp, (x + 24).dp to y.dp), color, 1.4.dp)
    line((x + 12).dp, (y + 6).dp, (x + 12).dp, (y + 18).dp, color, 1.4.dp)
    line((x + 16).dp, (y + 6).dp, (x + 16).dp, (y + 18).dp, color, 1.4.dp)
}

private fun DiagramPen.folder(x: Float, y: Float, color: Color) {
    polyline(listOf(x.dp to y.dp, (x + 10).dp to y.dp, (x + 13).dp to (y + 4).dp, (x + 34).dp to (y + 4).dp, (x + 34).dp to (y + 24).dp,
        x.dp to (y + 24).dp, x.dp to y.dp), color, 1.3.dp)
}

/** What a deletion removes: the selected files always, the original DNGs only when the switch is on. */
@Composable
internal fun DeletionArt(deleteOriginals: Boolean, hasOriginals: Boolean, containsVideo: Boolean, modifier: Modifier = Modifier) {
    DiagramStage(180.dp, modifier) {
        DiagramDrawing {
            documentStack(22f, 18f, Diagram.Ink)
            arrow(66.dp, 42.dp, 222.dp, 42.dp, CameraColors.Accent, 1.5.dp)
            bin(232f, 32f, CameraColors.Accent)
            documentStack(22f, 100f, Diagram.Ink)
            if (deleteOriginals) {
                arrow(66.dp, 124.dp, 222.dp, 124.dp, CameraColors.Accent, 1.5.dp)
                bin(232f, 114f, CameraColors.Accent)
            } else {
                arrow(66.dp, 124.dp, 222.dp, 124.dp, MediaArt.SoftInk, 1.5.dp)
                folder(228f, 112f, MediaArt.SoftInk)
                if (hasOriginals) {
                    // Branches off to the right of the folder names so it never crosses them.
                    line(212.dp, 124.dp, 212.dp, 90.dp, MediaArt.Arrow, 1.dp, dashed = true)
                    line(212.dp, 90.dp, 246.dp, 90.dp, MediaArt.Arrow, 1.dp, dashed = true)
                    arrow(246.dp, 90.dp, 246.dp, 60.dp, MediaArt.Arrow, 1.dp, dashed = true)
                }
            }
        }
        if (!containsVideo) DiagramText("JPG", Modifier.at(25.dp, 44.dp), CameraColors.Accent, 7f, mono = true, translate = false)
        DiagramText("DNG", Modifier.at(25.dp, 126.dp), MediaArt.SoftInk, 7f, mono = true, translate = false)
        // Titles end just above their arrows, growing upward when a translation needs a second line.
        Box(Modifier.at(74.dp, 15.dp).height(26.dp), contentAlignment = Alignment.BottomStart) {
            DiagramText(if (containsVideo) "Selected photos and videos" else "Selected JPEGs", weight = FontWeight.SemiBold,
                maxWidth = 150.dp)
        }
        if (!containsVideo) DiagramText("DCIM/Unspektrawesome", Modifier.at(74.dp, 48.dp), size = 9f, mono = true,
            translate = false, maxWidth = 150.dp)
        DiagramText("Deleted", Modifier.at(266.dp, 34.dp), CameraColors.Accent, 12f, FontWeight.SemiBold, maxWidth = 66.dp)
        Box(Modifier.at(74.dp, 97.dp).height(26.dp), contentAlignment = Alignment.BottomStart) {
            DiagramText("Original DNGs", weight = FontWeight.SemiBold, maxWidth = 132.dp)
        }
        DiagramText("DCIM/Unspektrawesome/RAW", Modifier.at(74.dp, 130.dp), size = 9f, mono = true, translate = false, maxWidth = 150.dp)
        DiagramText(if (deleteOriginals) "Deleted" else "Kept", Modifier.at(266.dp, 116.dp),
            if (deleteOriginals) CameraColors.Accent else CameraColors.Text, 12f, FontWeight.SemiBold, maxWidth = 66.dp)
        if (!deleteOriginals && hasOriginals) DiagramText("only if the switch below is on", Modifier.at(74.dp, 66.dp).width(132.dp),
            size = 9.5f, maxWidth = 132.dp, align = TextAlign.End)
        DiagramText(if (deleteOriginals) "JPEGs and their original DNGs are removed permanently."
            else "Kept DNGs stay in the RAW folder and can still be shared.", Modifier.at(14.dp, 152.dp),
            MediaArt.Detail, 9.5f, maxWidth = 308.dp)
    }
}

/**
 * BURST frames on the capture timeline: one bar per frame at its sensor time, grey once its preview is
 * ready, outlined while it is prepared, lime for the frame on screen and lime outlined when selected.
 */
@Composable
internal fun BurstTimelineArt(timestamps: List<Long>, prepared: Int, current: Int, selecting: Boolean, selectedFrames: Set<Int>,
    modifier: Modifier = Modifier) {
    if (timestamps.isEmpty()) return
    val first = timestamps.first()
    val span = (timestamps.last() - first).coerceAtLeast(1L)
    val seconds = span / 1_000_000_000.0
    val positions = timestamps.map { if (timestamps.size == 1) .5f else ((it - first).toDouble() / span).toFloat() }
    val left = 14f
    val width = 308f
    val barWidth = (width / timestamps.size - 2f).coerceIn(1f, 6f)
    DiagramStage(78.dp, modifier) {
        DiagramDrawing {
            positions.forEachIndexed { index, position ->
                val x = left + position * (width - barWidth)
                val on = index == current
                val height = if (on) 28f else 18f
                val top = 50f - height
                when {
                    on -> rect(x.dp, top.dp, barWidth.dp, height.dp, CameraColors.Accent)
                    selecting && index in selectedFrames -> box(x.dp, top.dp, barWidth.dp, height.dp, 0.dp, CameraColors.Accent, 1.dp)
                    index < prepared -> rect(x.dp, top.dp, barWidth.dp, height.dp, MediaArt.Detail)
                    else -> box(x.dp, top.dp, barWidth.dp, height.dp, 0.dp, Color(0xFF3A3A3A), 1.dp)
                }
            }
            line(left.dp, 56.dp, (left + width).dp, 56.dp, Diagram.Line, 1.dp)
        }
        val currentX = left + positions.getOrElse(current) { 0f } * (width - barWidth)
        DiagramText("${current + 1}", Modifier.at((currentX - 4f).coerceIn(left, left + width - 20f).dp, 6.dp), CameraColors.Accent, 9f,
            mono = true, translate = false)
        DiagramText("0 s", Modifier.at(left.dp, 60.dp), size = 9f, mono = true, translate = false)
        BurstSecondsLabel(seconds, Modifier.at((left + width - 60f).dp, 60.dp))
    }
}

@Composable
private fun BurstSecondsLabel(seconds: Double, modifier: Modifier) {
    Text(String.format(java.util.Locale.ROOT, "%.1f s", seconds), modifier.width(60.dp), translate = false, color = Diagram.Hint,
        fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono, textAlign = TextAlign.End)
}

/** Legend of the BURST timeline, wrapping onto more rows for long translations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BurstTimelineLegend(selecting: Boolean, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendSwatch("Preview ready") { drawRect(MediaArt.Detail) }
        LegendSwatch("Preparing") { drawRect(Color(0xFF3A3A3A), style = Stroke(1.dp.toPx())) }
        LegendSwatch("On screen") { drawRect(CameraColors.Accent) }
        if (selecting) LegendSwatch("Selected") { drawRect(CameraColors.Accent, style = Stroke(1.dp.toPx())) }
    }
}

@Composable
private fun LegendSwatch(label: String, draw: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(9.dp), onDraw = draw)
        Text(label, color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 12.sp)
    }
}

/** Mono tag laid over an image, such as the full resolution marker. */
@Composable
internal fun ImageTag(text: String, modifier: Modifier = Modifier, accent: Boolean = true) {
    Text(text, modifier.background(Color.Black.copy(alpha = .8f), RoundedCornerShape(3.dp))
        .border(1.dp, if (accent) CameraColors.Accent else Diagram.Line, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (accent) CameraColors.Accent else MediaArt.SoftInk, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
}
