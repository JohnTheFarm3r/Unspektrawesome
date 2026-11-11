package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun GuideCard(
    label: String,
    step: Int,
    total: Int,
    title: String,
    description: String,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    nextLabel: String = if (step == total - 1) "Done" else "Next",
    demonstration: (@Composable () -> Unit)? = null,
) {
    Column(modifier.clip(CameraStyle.PanelShape).background(CameraColors.Background.copy(alpha = .9f))
        .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
        .padding(CameraStyle.PanelPadding)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).border(1.dp, CameraColors.Muted.copy(alpha = .6f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_book_open, Modifier.size(12.dp), CameraColors.Muted)
            }
            Text(label, color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = .6.sp, modifier = Modifier.weight(1f))
            Text("${step + 1}/$total", translate = false, color = CameraColors.Muted, fontSize = 10.sp,
                lineHeight = 14.sp, fontFamily = CameraTypography.Readout)
        }
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(CameraColors.MenuBorder, Offset.Zero, Offset(size.width, 0f), size.height)
            drawLine(CameraColors.Accent, Offset.Zero, Offset(size.width * (step + 1f) / total.coerceAtLeast(1), 0f), size.height)
        }
        val bodyScroll = rememberScrollState()
        LaunchedEffect(step) { bodyScroll.scrollTo(0) }
        Column(Modifier.weight(1f, fill = false).verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = CameraColors.Text, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            demonstration?.invoke()
            Text(description, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp)
            val wantedWidth = with(density) {
                measurer.measure(com.unspektrawesome.language.AppLanguage.text("Skip guide"), style).size.width.toDp().plus(24.dp).coerceAtLeast(64.dp) +
                    measurer.measure(com.unspektrawesome.language.AppLanguage.text(nextLabel), style).size.width.toDp().plus(40.dp).coerceAtLeast(64.dp)
            }
            val skip: @Composable () -> Unit = { GuideAction("Skip guide", false, onSkip) }
            val next: @Composable () -> Unit = { GuideAction(nextLabel, true, onNext) }
            if (wantedWidth > maxWidth) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.align(Alignment.Start)) { skip() }
                Box(Modifier.align(Alignment.End)) { next() }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) { skip(); next() }
        }
    }
}

@Composable
private fun GuideAction(label: String, accent: Boolean, onClick: () -> Unit) {
    val enabled = LocalGuideActionsEnabled.current
    Box(Modifier.widthIn(min = 64.dp).heightIn(min = 44.dp).clip(CameraStyle.ActionShape)
        .then(if (accent) Modifier.border(1.dp, CameraColors.Accent, CameraStyle.ActionShape) else Modifier)
        .clickable(enabled = enabled, interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (accent) 20.dp else 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (accent) CameraColors.Accent else CameraColors.Muted,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp), textAlign = TextAlign.Center)
    }
}

@Composable
internal fun GuideOverlay(
    label: String,
    step: Int,
    total: Int,
    title: String,
    description: String,
    targetBounds: Rect?,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    nextLabel: String = if (step == total - 1) "Done" else "Next",
    tag: String = "Guide",
    demonstration: (@Composable () -> Unit)? = null,
) {
    val rotation = LocalCameraRotation.current
    if (abs(rotation) == 90f) {
        OrientedLookPanel(true, rotation, modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalCameraRotation provides 0f) {
                GuideOverlay(label, step, total, title, description, targetBounds, onSkip, onNext,
                    nextLabel = nextLabel, tag = tag, demonstration = demonstration)
            }
        }
        return
    }
    var localTarget by remember { mutableStateOf<Rect?>(null) }
    var guideCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun updateTarget(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        localTarget = targetBounds?.let { bounds ->
            val root = coordinates.findRootCoordinates()
            val points = listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)
                .map { coordinates.localPositionOf(root, it) }
            Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }
    LaunchedEffect(targetBounds, guideCoordinates) { guideCoordinates?.let(::updateTarget) }
    val density = LocalDensity.current
    val animation = rememberInfiniteTransition(label = "Guide pointer")
    val progress by animation.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)), label = "Guide gesture")
    BoxWithConstraints(modifier.fillMaxSize()
        .onGloballyPositioned { coordinates ->
            guideCoordinates = coordinates
            updateTarget(coordinates)
        }.testTag(tag)) {
        val target = localTarget
        val height = with(density) { maxHeight.toPx() }
        val gap = with(density) { 16.dp.toPx() }
        val above = target != null && target.top > height - target.bottom
        val room = target?.let { if (above) it.top - gap else height - it.bottom - gap } ?: (height - gap * 2)
        val availableHeight = (height - gap).coerceAtLeast(1f)
        val textScale = density.fontScale.coerceIn(1f, 2f)
        val preferredHeight = if (maxWidth > maxHeight) 238.dp * textScale.coerceAtMost(1.4f) else 330.dp
        val maxCardHeight = minOf(preferredHeight, with(density) {
            room.coerceIn((144.dp * textScale).toPx().coerceAtMost(availableHeight), availableHeight).toDp()
        })
        Canvas(Modifier.fillMaxSize()) {
            if (target != null) {
                val outline = target.inflate(4.dp.toPx())
                val ink = CameraColors.Accent
                val stroke = 2.dp.toPx()
                drawRoundRect(ink.copy(alpha = .3f + .5f * (1f - progress)), outline.topLeft, outline.size,
                    CornerRadius(12.dp.toPx()), style = Stroke(stroke))
                val direction = if (above) Offset(0f, 1f) else Offset(0f, -1f)
                val tip = Offset(target.center.x, if (above) outline.top - 5.dp.toPx() else outline.bottom + 5.dp.toPx()) -
                    direction * ((1f - progress) * 8.dp.toPx())
                drawLine(ink, tip - direction * 22.dp.toPx(), tip, stroke, StrokeCap.Round)
                for (side in listOf(-1f, 1f)) drawLine(ink, tip,
                    tip - direction * 7.dp.toPx() + Offset(side * 6.dp.toPx(), 0f), stroke, StrokeCap.Round)
            }
        }
        Layout(modifier = Modifier.fillMaxSize(), content = {
            key(step) {
                GuideCard(label, step, total, title, description, onSkip, onNext,
                    Modifier.widthIn(max = 352.dp).fillMaxWidth(.9f).heightIn(max = maxCardHeight)
                        .testTag("$tag card"), nextLabel, demonstration)
            }
        }) { measurables, constraints ->
            val card = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val y = when {
                target == null -> (height - card.height) / 2
                above -> target.top - card.height - gap
                else -> target.bottom + gap
            }.coerceIn(gap / 2, (height - card.height - gap / 2).coerceAtLeast(gap / 2))
            layout(constraints.maxWidth, constraints.maxHeight) {
                card.placeRelative((constraints.maxWidth - card.width) / 2, y.roundToInt())
            }
        }
    }
}
