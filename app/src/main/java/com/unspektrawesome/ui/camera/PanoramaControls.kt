package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.R
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.panorama.PanoramaProjection
import com.unspektrawesome.panorama.PanoramaGuide
import com.unspektrawesome.panorama.PanoramaState
import kotlinx.coroutines.delay

internal const val PANORAMA_TRANSITION_MILLIS = 360

@Composable
internal fun PanoramaAutoButton(selected: Boolean, enabled: Boolean, rotation: Float, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val ink = if (!enabled) CameraColors.Muted else if (selected) CameraColors.Accent else CameraColors.Text
    Box(modifier.size(48.dp).previewControlSurface(CircleShape, selected = selected).testTag("Panorama auto")
        .clickable(enabled = enabled, role = Role.Button) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }.languageSemantics { language ->
            contentDescription = language.text("AUTO")
            this.selected = selected
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp, 20.dp).animatedCameraRotation(rotation)) {
            val outline = Path().apply {
                moveTo(size.width * .1f, size.height * .9f)
                lineTo(size.width * .5f, size.height * .1f)
                lineTo(size.width * .9f, size.height * .9f)
            }
            drawPath(outline, ink, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
            drawLine(ink, Offset(size.width * .25f, size.height * .6f),
                Offset(size.width * .75f, size.height * .6f), 1.7.dp.toPx(), StrokeCap.Round)
        }
    }
}

internal data class PanoramaCaptureAnimation(val visible: Boolean, val progress: Float, val state: PanoramaState)

@Composable
internal fun rememberPanoramaCaptureAnimation(state: PanoramaState): PanoramaCaptureAnimation {
    val active = state.enabled && state.busy
    val progress by animateFloatAsState(if (active) 1f else 0f,
        tween(PANORAMA_TRANSITION_MILLIS, easing = FastOutSlowInEasing), label = "Panorama capture transition")
    var retained by remember { mutableStateOf<PanoramaState?>(null) }
    SideEffect {
        if (active) retained = state
        else if (progress == 0f) retained = null
    }
    return PanoramaCaptureAnimation(active || progress > 0f, progress, if (active) state else retained ?: state)
}

@Composable
private fun rememberPanoramaFramePulse(frames: Int): State<Float> {
    val pulse = remember { Animatable(0f) }
    var previous by remember { mutableIntStateOf(frames) }
    LaunchedEffect(frames) {
        val captured = frames > previous && previous > 0
        previous = frames
        if (captured) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
        }
    }
    return pulse.asState()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanoramaMenuControls(state: PanoramaState, onExportLater: (Boolean) -> Unit,
    onProjection: (PanoramaProjection) -> Unit = {}) {
    if (state.grid == null) VideoMenuCard {
        Column(Modifier.fillMaxWidth().padding(CameraStyle.QuickMenuRowPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Projection", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoMenuChoice("Cylindrical", state.projection == PanoramaProjection.CYLINDRICAL,
                    Modifier.testTag("Panorama cylindrical"), enabled = !state.busy) { onProjection(PanoramaProjection.CYLINDRICAL) }
                VideoMenuChoice("Plane", state.projection == PanoramaProjection.PLANE,
                    Modifier.testTag("Panorama plane"), enabled = !state.busy) { onProjection(PanoramaProjection.PLANE) }
            }
        }
    }
    VideoMenuCard {
        Column(Modifier.fillMaxWidth().padding(CameraStyle.QuickMenuRowPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Export", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoMenuChoice("Later in Gallery", state.exportLater, Modifier.weight(1f).fillMaxHeight().testTag("Panorama export later"), enabled = !state.busy) { onExportLater(true) }
                VideoMenuChoice("Now", !state.exportLater, Modifier.weight(1f).fillMaxHeight().testTag("Panorama export now"), enabled = !state.busy) { onExportLater(false) }
            }
        }
    }
}

@Composable
internal fun BoxScope.PanoramaOverlay(state: PanoramaState, rotation: Float, supported: Boolean,
    onGridAimRadiusChanged: (Double) -> Unit = {}, deviceRotation: Float = rotation) {
    var showFailure by remember(state.enabled, state.guide) { mutableStateOf(state.enabled && state.guide in setOf(PanoramaGuide.FAILED, PanoramaGuide.SHORT)) }
    LaunchedEffect(state.enabled, state.guide) { if (showFailure) { delay(2_500); showFailure = false } }
    val warning = when {
        !supported -> "Manual exposure is unavailable on this lens"
        state.grid?.available == false -> "Motion sensor unavailable"
        showFailure && state.guide == PanoramaGuide.FAILED -> "Capture stopped"
        showFailure && state.grid != null -> "Capture stopped"
        showFailure -> "Move farther"
        state.recording -> when (state.guide) {
            PanoramaGuide.LEVEL -> "Keep level"
            PanoramaGuide.SLOW -> "Slow down"
            PanoramaGuide.DETAIL -> "More overlap"
            PanoramaGuide.RETURN -> "Move back"
            PanoramaGuide.LIMIT -> "Tap the shutter to finish"
            else -> null
        }
        else -> null
    }
    val visible = state.enabled && (state.recording || state.grid != null || warning != null)
    var retainedState by remember { mutableStateOf(state.copy(preview = null)) }
    var retainedWarning by remember { mutableStateOf(warning) }
    SideEffect { if (visible) { retainedState = state.copy(preview = null); retainedWarning = warning } }
    val displayedState = if (visible) state else retainedState
    val displayedWarning = if (visible) warning else retainedWarning
    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(tween(180, delayMillis = 140)), exit = fadeOut(tween(260))) {
        val guideRotation = displayedState.grid?.takeIf { displayedState.recording }?.orientationDegrees
            ?.let { if (it == 270) -90f else it.toFloat() }
            // Guides are drawn relative to the captured frame, so they follow how the phone is held.
            ?: if (displayedState.recording || displayedState.grid != null) deviceRotation else rotation
        RotatingCameraPanel(guideRotation, Modifier.fillMaxSize(), maxWidth = Dp.Infinity) {
            Box(Modifier.fillMaxSize().testTag("Panorama guidance").languageSemantics { language ->
                contentDescription = language.text(if (!supported) "Manual exposure is unavailable on this lens" else if (displayedState.grid != null) "Aim at the highlighted circle and hold steady" else displayedState.guide.text)
                liveRegion = LiveRegionMode.Polite
            }) {
                if (displayedState.grid != null && supported && displayedState.grid.available)
                    GridCaptureGuide(displayedState.grid, displayedState.recording, displayedState.panels, onGridAimRadiusChanged)
                AnimatedVisibility(displayedState.recording && displayedState.grid == null, Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(180)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = .94f),
                    exit = fadeOut(tween(180)) + scaleOut(tween(260), targetScale = .97f)) { PanoramaAlignmentGuide(displayedState) }
                var lastWarning by remember { mutableStateOf(displayedWarning) }
                SideEffect { if (displayedWarning != null) lastWarning = displayedWarning }
                AnimatedVisibility(displayedWarning != null,
                    Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 260.dp),
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)), exit = fadeOut(tween(120)) + shrinkVertically(tween(200))) {
                    (displayedWarning ?: lastWarning)?.let { Text(it, modifier = Modifier.fillMaxWidth(), color = CameraColors.Text, fontSize = 13.sp, lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                        style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), 4f))) }
                }
            }
        }
    }
}

@Composable
private fun PanoramaAlignmentGuide(state: PanoramaState) {
    val position by animateFloatAsState(-state.direction.coerceIn(-1, 1) * (1f - state.progress.coerceIn(0f, 1f)),
        tween(180), label = "Panorama movement")
    val drift by animateFloatAsState(if (state.guide == PanoramaGuide.LEVEL) state.drift.coerceIn(-.2f, .2f) else 0f,
        tween(180), label = "Panorama level")
    val captured by rememberPanoramaFramePulse(state.frames)
    val arrow = painterResource(R.drawable.ic_panorama_direction)
    Canvas(Modifier.size(if (state.horizontal) 176.dp else 56.dp, if (state.horizontal) 56.dp else 176.dp)
        .testTag("Panorama alignment guide")) {
        fun point(along: Float, across: Float = 0f) = center + if (state.horizontal) Offset(along, across) else Offset(across, along)
        for (sign in listOf(-1, 1)) {
            val from = point(sign * 13.dp.toPx())
            val to = point(sign * 52.dp.toPx())
            drawLine(Color.Black.copy(alpha = .55f), from, to, 3.dp.toPx(), StrokeCap.Round)
            drawLine(CameraColors.Text.copy(alpha = .65f), from, to, 1.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(Color.Black.copy(alpha = .2f), 9.dp.toPx())
        drawCircle(Color.Black.copy(alpha = .55f), 9.dp.toPx(), style = Stroke(3.5.dp.toPx()))
        drawCircle(lerp(CameraColors.Text.copy(alpha = .9f), CameraColors.Accent, captured), 9.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        if (captured > 0f) drawCircle(CameraColors.Accent.copy(alpha = captured * .6f), (11 + (1 - captured) * 6).dp.toPx(),
            style = Stroke(1.dp.toPx()))
        val marker = point(position * 42.dp.toPx(), drift * 64.dp.toPx())
        drawCircle(Color.Black.copy(alpha = .7f), 5.5.dp.toPx(), marker)
        drawCircle(CameraColors.Accent, 3.5.dp.toPx(), marker)
        for (sign in if (state.direction == 0) listOf(-1, 1) else listOf(state.direction)) {
            val location = point(sign * 73.dp.toPx())
            val side = 24.dp.toPx()
            withTransform({
                translate(location.x, location.y)
                rotate(if (state.horizontal) { if (sign > 0) 0f else 180f } else if (sign > 0) 90f else -90f, pivot = Offset.Zero)
                translate(-side / 2, -side / 2)
            }) { with(arrow) { draw(Size(side, side)) } }
        }
    }
}

@Composable
internal fun PanoramaPreviewDock(state: PanoramaState, rotation: Float, modifier: Modifier = Modifier) {
    RotatingCameraPanel(rotation, modifier.testTag("Panorama preview dock"), maxWidth = Dp.Infinity) {
        val bitmap = state.preview
        val firstImage = remember { Animatable(0f) }
        val captured by rememberPanoramaFramePulse(state.frames)
        LaunchedEffect(bitmap != null) {
            if (bitmap != null) firstImage.animateTo(1f, tween(240, easing = LinearOutSlowInEasing))
            else firstImage.snapTo(0f)
        }
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("Panorama live preview").languageSemantics { language ->
            contentDescription = language.text(if (state.grid != null) "3×3" else "PANORAMA")
            stateDescription = language.format(if (state.grid != null) "{count} of 9 panels · exposure locked" else "{count} panorama frames · exposure locked", mapOf("count" to state.frames))
        }) {
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                val scroll = rememberScrollState()
                val fit = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
                val imageWidth = if (state.grid != null) fit * bitmap.width else if (state.horizontal) maxHeight * bitmap.width / bitmap.height else maxWidth
                val imageHeight = if (state.grid != null) fit * bitmap.height else if (state.horizontal) maxHeight else maxWidth * bitmap.height / bitmap.width
                val imageAlignment = when {
                    state.grid != null || state.direction == 0 -> Alignment.Center
                    state.horizontal -> if (state.direction < 0) Alignment.CenterEnd else Alignment.CenterStart
                    else -> if (state.direction < 0) Alignment.BottomCenter else Alignment.TopCenter
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = firstImage.value }
                    .then(if (state.grid != null) Modifier else if (state.horizontal) Modifier.horizontalScroll(scroll) else Modifier.verticalScroll(scroll)),
                    contentAlignment = imageAlignment) {
                    Image(image, null, Modifier.requiredSize(imageWidth, imageHeight).testTag("Panorama preview image"), contentScale = ContentScale.FillBounds)
                }
                LaunchedEffect(bitmap, state.direction, scroll.maxValue) {
                    if (scroll.maxValue != Int.MAX_VALUE) scroll.animateScrollTo(if (state.direction < 0) 0 else scroll.maxValue, tween(240))
                }
                if (captured > 0f && state.direction != 0) Canvas(Modifier.fillMaxSize()) {
                    val color = CameraColors.Accent.copy(alpha = captured * .7f)
                    if (state.horizontal) {
                        val width = imageWidth.toPx()
                        val edge = if (state.direction > 0) minOf(size.width, width - scroll.value) else maxOf(0f, size.width - width)
                        drawLine(color, Offset(edge, 0f), Offset(edge, size.height), 2.dp.toPx(), StrokeCap.Round)
                    } else {
                        val height = imageHeight.toPx()
                        val edge = if (state.direction > 0) minOf(size.height, height - scroll.value) else maxOf(0f, size.height - height)
                        drawLine(color, Offset(0f, edge), Offset(size.width, edge), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
                if (scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) Canvas(Modifier.fillMaxSize()) {
                    val length = if (state.horizontal) size.width else size.height
                    val total = length + scroll.maxValue
                    val start = length * scroll.value / total
                    val end = start + length * length / total
                    if (state.horizontal) {
                        drawLine(CameraColors.MenuBorder, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                        drawLine(CameraColors.Accent, Offset(start, size.height - 1.dp.toPx()), Offset(end, size.height - 1.dp.toPx()), 2.dp.toPx())
                    } else {
                        drawLine(CameraColors.MenuBorder, Offset(size.width - 1.dp.toPx(), 0f), Offset(size.width - 1.dp.toPx(), size.height), 2.dp.toPx())
                        drawLine(CameraColors.Accent, Offset(size.width - 1.dp.toPx(), start), Offset(size.width - 1.dp.toPx(), end), 2.dp.toPx())
                    }
                }
            }
        }
    }
}
