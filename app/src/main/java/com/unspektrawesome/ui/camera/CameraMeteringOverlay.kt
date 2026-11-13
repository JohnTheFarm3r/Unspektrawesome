package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
                val label = if (isExposure) "AE" else "AF"
                val description = when (current.phase) {
                    TapMeteringPhase.SUCCESS -> if (isExposure) "Exposure settled" else "Focus confirmed"
                    TapMeteringPhase.FAILED -> "Focus failed"
                    TapMeteringPhase.TIMED_OUT -> "Focus not confirmed in time"
                    TapMeteringPhase.UNSUPPORTED -> if (isExposure) "Exposure metering unavailable" else "Area autofocus unavailable"
                    else -> if (isExposure) "Metering exposure" else "Focusing"
                }
                val color by animateColorAsState(when {
                    dragPoint != null -> CameraColors.Text
                    current.phase == TapMeteringPhase.SUCCESS -> if (isExposure) CameraColors.Accent else CameraColors.FocusLocked
                    current.phase in listOf(TapMeteringPhase.FAILED, TapMeteringPhase.TIMED_OUT) -> CameraColors.FocusFailed
                    current.phase == TapMeteringPhase.UNSUPPORTED -> CameraColors.Muted
                    else -> CameraColors.Text
                }, tween(160), label = "Metering target color")
                // A new target settles in from slightly larger; a lock pulses once and a failure shakes.
                val scale = remember { Animatable(1f) }
                val shake = remember { Animatable(0f) }
                LaunchedEffect(current.generation) {
                    scale.snapTo(1.35f)
                    scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                }
                LaunchedEffect(current.generation, current.phase) {
                    when (current.phase) {
                        TapMeteringPhase.SUCCESS -> if (!isExposure) {
                            scale.animateTo(.86f, tween(90, easing = FastOutSlowInEasing))
                            scale.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
                        }
                        TapMeteringPhase.FAILED, TapMeteringPhase.TIMED_OUT -> if (!isExposure) {
                            for (offset in listOf(4f, -4f, 3f, -2f, 0f)) shake.animateTo(offset, tween(45))
                        }
                        else -> Unit
                    }
                }
                val targetSize = minOf(if (isExposure) 64.dp else 52.dp, maxWidth, maxHeight)
                val targetX = (maxWidth * point.x - targetSize / 2).coerceIn(0.dp, maxWidth - targetSize)
                val targetY = (maxHeight * point.y - targetSize / 2).coerceIn(0.dp, maxHeight - targetSize)
                Box(Modifier.offset(targetX, targetY).size(targetSize).graphicsLayer {
                    alpha = opacity
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = shake.value.dp.toPx()
                }
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
                        val stroke = 1.5.dp.toPx()
                        val tick = 4.dp.toPx()
                        // A soft dark edge keeps the target visible on bright subjects.
                        for ((ink, width) in listOf(Color.Black.copy(alpha = .35f) to stroke + 2.dp.toPx(), color to stroke)) {
                            if (isExposure) {
                                val radius = size.minDimension / 2 - stroke
                                drawCircle(ink, radius, style = Stroke(width))
                                repeat(4) { rotate(it * 90f) {
                                    drawLine(ink, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tick),
                                        width, StrokeCap.Round)
                                } }
                            } else {
                                val inset = stroke
                                val corner = 7.dp.toPx()
                                drawRoundRect(ink, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - inset * 2,
                                    size.height - inset * 2), androidx.compose.ui.geometry.CornerRadius(corner), style = Stroke(width))
                                repeat(4) { rotate(it * 90f) {
                                    drawLine(ink, Offset(center.x, inset), Offset(center.x, inset + tick), width, StrokeCap.Round)
                                } }
                                drawCircle(ink, 1.4.dp.toPx() + (width - stroke) / 2, center)
                            }
                        }
                    }
                }
                if (maxWidth >= 52.dp && maxHeight >= 64.dp) {
                    val rotation = LocalCameraRotation.current
                    val grouped = !isExposure && !exposureStatus.hidden && exposureStatus.generation != 0L && exposureStatus.phase != TapMeteringPhase.IDLE &&
                        kotlin.math.abs(point.x - exposureStatus.normalizedX) < .001f &&
                        kotlin.math.abs(point.y - exposureStatus.normalizedY) < .001f
                    val labelTop = if (grouped) (maxHeight * point.y - 32.dp).coerceIn(0.dp, maxHeight - 64.dp) else targetY
                    Layout(content = { RotatingCameraPanel(rotation) {
                        Text(label, Modifier.testTag(if (isExposure) "Exposure target label" else "Focus target label")
                            .semantics { if (hidden) hideFromAccessibility() }
                            .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                            color = color, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = .6.sp, maxLines = 1, translate = false)
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
