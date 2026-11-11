package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.unspektrawesome.preview.TapMeteringPhase
import com.unspektrawesome.preview.TapMeteringStatus
import kotlinx.coroutines.delay

internal val MeteringTargetOpacity = SemanticsPropertyKey<Float>("Metering target opacity")

@Composable
internal fun ViewfinderMeteringOverlay(
    status: TapMeteringStatus,
    exposureStatus: TapMeteringStatus = TapMeteringStatus(),
    onExposure: (Float, Float) -> Unit = { _, _ -> },
    onResetFocus: () -> Unit = {},
    onResetExposure: () -> Unit = {},
    onFocus: ((Float, Float) -> Unit)? = null,
    onTap: (Float, Float) -> Unit,
) {
    val focus by rememberUpdatedState(onTap)
    val exposure by rememberUpdatedState(onExposure)
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (currentEvent.changes.size != 1) return@awaitEachGesture
            down.consume()
            val up = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) { awaitMeteringTapRelease(down) }
            } catch (_: PointerEventTimeoutCancellationException) {
                exposure(down.position.x / size.width, down.position.y / size.height)
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                null
            }
            if (up != null) {
                up.consume()
                focus(up.position.x / size.width, up.position.y / size.height)
            }
        }
    }) {
        if (constraints.maxWidth == 0 || constraints.maxHeight == 0) return@BoxWithConstraints
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        for (isExposure in listOf(true, false)) {
            val current = if (isExposure) exposureStatus else status
            if (current.generation == 0L || current.phase == TapMeteringPhase.IDLE ||
                current.normalizedX !in 0f..1f || current.normalizedY !in 0f..1f) continue
            key(isExposure) {
                val latestStatus by rememberUpdatedState(current)
                var dragPoint by remember { mutableStateOf<Offset?>(null) }
                var interaction by remember { mutableIntStateOf(0) }
                var faded by remember { mutableStateOf(false) }
                LaunchedEffect(current.generation, interaction) {
                    faded = false
                    delay(5_000)
                    faded = true
                }
                val hidden = current.hidden && dragPoint == null
                val opacity by animateFloatAsState(when {
                    hidden -> 0f
                    dragPoint != null || !faded -> 1f
                    else -> .35f
                }, tween(350), label = "Metering target opacity")
                val point = dragPoint ?: Offset(current.normalizedX, current.normalizedY)
                val onMove by rememberUpdatedState(if (isExposure) onExposure else onFocus ?: onTap)
                val onReset by rememberUpdatedState(if (isExposure) onResetExposure else onResetFocus)
                val label = if (isExposure) "AE" else when (current.phase) {
                    TapMeteringPhase.SUCCESS -> "AF ✓"
                    TapMeteringPhase.FAILED -> "AF ×"
                    TapMeteringPhase.TIMED_OUT, TapMeteringPhase.UNSUPPORTED -> "AF ?"
                    else -> "AF"
                }
                val description = when (current.phase) {
                    TapMeteringPhase.SUCCESS -> if (isExposure) "Exposure settled" else "Focus confirmed"
                    TapMeteringPhase.FAILED -> "Focus failed"
                    TapMeteringPhase.TIMED_OUT -> "Focus not confirmed in time"
                    TapMeteringPhase.UNSUPPORTED -> if (isExposure) "Exposure metering unavailable" else "Area autofocus unavailable"
                    else -> if (isExposure) "Metering exposure" else "Focusing"
                }
                val color by animateColorAsState(when {
                    dragPoint != null -> CameraColors.Text
                    current.phase == TapMeteringPhase.SUCCESS -> CameraColors.Accent
                    current.phase in listOf(TapMeteringPhase.FAILED, TapMeteringPhase.TIMED_OUT) -> CameraColors.FocusFailed
                    current.phase == TapMeteringPhase.UNSUPPORTED -> CameraColors.Muted
                    else -> CameraColors.Text
                }, label = "Metering target color")
                val targetSize = minOf(if (isExposure) 88.dp else 66.dp, maxWidth, maxHeight)
                val targetX = (maxWidth * point.x - targetSize / 2).coerceIn(0.dp, maxWidth - targetSize)
                val targetY = (maxHeight * point.y - targetSize / 2).coerceIn(0.dp, maxHeight - targetSize)
                Box(Modifier.offset(targetX, targetY).size(targetSize).graphicsLayer { alpha = opacity }
                    .testTag(if (isExposure) "Exposure metering reticle" else "Tap metering reticle")
                    .languageSemantics { language ->
                        this[MeteringTargetOpacity] = opacity
                        if (hidden) hideFromAccessibility() else {
                            contentDescription = language.text(if (isExposure) "Exposure target" else "Focus target")
                            stateDescription = language.text(description)
                        }
                    }.pointerInput(viewportWidth, viewportHeight, hidden) {
                        if (hidden) return@pointerInput
                        detectDragGestures(
                            onDragStart = { interaction++; dragPoint = Offset(latestStatus.normalizedX, latestStatus.normalizedY) },
                            onDragCancel = { interaction++; dragPoint = null },
                            onDragEnd = {
                                interaction++
                                dragPoint?.let { onMove(it.x, it.y) }
                                dragPoint = null
                            },
                        ) { change, amount ->
                            change.consume()
                            val previous = dragPoint ?: Offset(latestStatus.normalizedX, latestStatus.normalizedY)
                            dragPoint = Offset((previous.x + amount.x / viewportWidth).coerceIn(0f, 1f),
                                (previous.y + amount.y / viewportHeight).coerceIn(0f, 1f))
                        }
                    }.then(if (hidden) Modifier else Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { interaction++; onMove(latestStatus.normalizedX, latestStatus.normalizedY) },
                            onDoubleTap = { interaction++; onReset() })
                    })) {
                    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                        val radius = size.minDimension / 2
                        val corner = Path().apply {
                            val leg = size.minDimension * .25f
                            val rounding = size.minDimension * .10f
                            moveTo(0f, leg)
                            lineTo(0f, rounding)
                            quadraticTo(0f, 0f, rounding, 0f)
                            lineTo(leg, 0f)
                        }
                        for ((outline, width) in listOf(Color.Black.copy(alpha = .8f) to 4.dp.toPx(), color to 1.6.dp.toPx())) {
                            if (isExposure) {
                                drawCircle(outline, radius, style = Stroke(width))
                                repeat(4) { rotate(it * 90f) {
                                    drawLine(outline, Offset(center.x, 0f), Offset(center.x, 4.dp.toPx()), width, StrokeCap.Round)
                                } }
                            } else {
                                repeat(4) { rotate(it * 90f) { drawPath(corner, outline, style = Stroke(width, cap = StrokeCap.Round)) } }
                                val cross = 3.dp.toPx()
                                drawLine(outline, center - Offset(cross, 0f), center + Offset(cross, 0f), width, StrokeCap.Round)
                                drawLine(outline, center - Offset(0f, cross), center + Offset(0f, cross), width, StrokeCap.Round)
                            }
                        }
                    }
                }
                if (maxWidth >= 66.dp && maxHeight >= 88.dp) {
                    val rotation = LocalCameraRotation.current
                    val grouped = !isExposure && !exposureStatus.hidden && exposureStatus.generation != 0L && exposureStatus.phase != TapMeteringPhase.IDLE &&
                        kotlin.math.abs(point.x - exposureStatus.normalizedX) < .001f &&
                        kotlin.math.abs(point.y - exposureStatus.normalizedY) < .001f
                    val labelTop = if (grouped) (maxHeight * point.y - 44.dp).coerceIn(0.dp, maxHeight - 88.dp) else targetY
                    Layout(content = { RotatingCameraPanel(rotation) {
                        Text(label, Modifier.testTag(if (isExposure) "Exposure target label" else "Focus target label")
                            .semantics { if (hidden) hideFromAccessibility() }
                            .background(CameraColors.MenuBackground, RoundedCornerShape(5.dp))
                            .border(.5.dp, color.copy(alpha = .5f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = color, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    } }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity }) { measurables, constraints ->
                        val label = measurables.single().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
                        val left = (targetX.roundToPx() + (targetSize.roundToPx() - label.width) / 2)
                            .coerceIn(0, constraints.maxWidth - label.width)
                        val top = (if (isExposure) (targetY + targetSize + 3.dp).roundToPx()
                            else (labelTop - 3.dp).roundToPx() - label.height).coerceIn(0, constraints.maxHeight - label.height)
                        layout(constraints.maxWidth, constraints.maxHeight) { label.place(left, top) }
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitMeteringTapRelease(down: PointerInputChange): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.singleOrNull() ?: return null
        if (change.id != down.id || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) ||
            (change.position - down.position).getDistance() > slop ||
            change.historical.any { (it.position - down.position).getDistance() > slop }) return null
        if (change.changedToUp()) return change
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return null
    }
}
