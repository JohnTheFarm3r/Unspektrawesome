package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.abs

@Composable
internal fun AdjustableLookCard(
    amount: Float?,
    amountLabel: String,
    active: Boolean,
    rotationDegrees: Float,
    reordering: Boolean = false,
    enabled: Boolean = true,
    toggleable: Boolean = false,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    maximumAmount: Float = 2f,
    defaultAmount: Float = 1f,
    dragDistanceMultiplier: Float = 1f,
    showGestureHint: Boolean = false,
    onGestureHintFinished: () -> Unit = {},
    onAmountChange: (Float) -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var showAmount by remember { mutableStateOf(false) }
    var resetCount by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val currentAmount by rememberUpdatedState(amount ?: 1f)
    val changeAmount by rememberUpdatedState(onAmountChange)
    val finishHint by rememberUpdatedState(onGestureHintFinished)
    val hintProgress = remember { Animatable(0f) }
    LaunchedEffect(showGestureHint) {
        hintProgress.snapTo(0f)
        if (showGestureHint) {
            hintProgress.animateTo(1f, tween(2400, easing = LinearEasing))
            finishHint()
        }
    }
    val percentage = if (maximumAmount == 1f) {
        String.format(Locale.ROOT, "%.1f", (amount ?: 0f) * 100f).removeSuffix(".0")
    } else (((amount ?: 0f) * 100).roundToInt()).toString()
    LaunchedEffect(dragging, active, resetCount) {
        showAmount = active
        if (!dragging) {
            delay(900)
            showAmount = false
        }
    }
    fun resetAmount() {
        changeAmount(defaultAmount.coerceIn(0f, maximumAmount))
        finishHint()
        resetCount++
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val adjustmentEmphasis by animateFloatAsState(if (showAmount && active) 1f else 0f, label = "Card adjustment emphasis")
    val adjustment = if (amount == null || !enabled || toggleable && !active) Modifier else Modifier
        .pointerInput(showGestureHint) {
            if (showGestureHint) awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) {
                        finishHint()
                        break
                    }
                }
            }
        }
        .languageSemantics { language ->
            stateDescription = language.format("{amountLabel} {percentage} percent. {value3} Double-tap to reset strength.", mapOf("amountLabel" to (amountLabel), "percentage" to (percentage), "value3" to (if (active) "Selected. Swipe up or down to adjust." else "Tap to apply, then swipe up or down to adjust.")))
            progressBarRangeInfo = ProgressBarRangeInfo(amount.coerceIn(0f, maximumAmount), 0f..maximumAmount)
            setProgress { value ->
                if (!value.isFinite()) false else {
                    changeAmount(value.coerceIn(0f, maximumAmount))
                    true
                }
            }
        }
        .pointerInput(active, maximumAmount, dragDistanceMultiplier, reordering) {
            if (!active || reordering) return@pointerInput
            var draft = currentAmount
            detectVerticalDragGestures(
                onDragStart = { draft = currentAmount; dragging = true },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, delta ->
                change.consume()
                draft = (draft - delta * maximumAmount / (size.height.coerceAtLeast(1) * dragDistanceMultiplier))
                    .coerceIn(0f, maximumAmount)
                changeAmount(draft)
            }
        }
    Box(modifier
        .combinedClickable(enabled = enabled && !reordering, role = if (toggleable) Role.Switch else Role.RadioButton, onClick = onSelect,
            onDoubleClick = if (amount != null && active) ({ resetAmount() }) else null)
        .languageSemantics { language ->
            if (toggleable) {
                toggleableState = ToggleableState(active)
                stateDescription = language.text(if (active) "ON" else "OFF")
            } else selected = active
            customActions = accessibilityActions + if (amount != null && enabled && (!toggleable || active)) listOf(CustomAccessibilityAction(language.text("Reset strength to default")) {
                resetAmount()
                true
            }) else emptyList()
        }
        .then(adjustment)) {
        if (amount != null && !reordering && (!toggleable || active)) {
            Canvas(Modifier.matchParentSize()) {
                val inset = 2.dp.toPx()
                val y = inset + (size.height - inset * 2) * (1f - (amount / maximumAmount).coerceIn(0f, 1f))
                val tint = if (active) CameraColors.Accent else CameraColors.Muted
                val opacity = if (active) .22f + .78f * adjustmentEmphasis else .22f
                drawRect(
                    Brush.verticalGradient(
                        listOf(tint.copy(alpha = .28f * opacity), tint.copy(alpha = .14f * opacity)),
                        startY = y, endY = size.height,
                    ),
                    topLeft = Offset(inset, y),
                    size = Size(size.width - inset * 2, size.height - y - inset),
                )
                drawLine(tint.copy(alpha = opacity), Offset(inset, y), Offset(size.width - inset, y), 2.dp.toPx())
            }
            content()
            Text(
                "{percentage}%", arguments = mapOf("percentage" to (percentage)),
                color = CameraColors.Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center).graphicsLayer {
                    alpha = adjustmentEmphasis
                    rotationZ = rotationDegrees
                }.background(CameraColors.Glass, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clearAndSetSemantics { },
            )
            if (showGestureHint) {
                val progress = hintProgress.value
                val opacity = ((progress - .15f) / .08f).coerceIn(0f, 1f) *
                    ((1f - progress) / .1f).coerceIn(0f, 1f)
                Box(Modifier.matchParentSize().graphicsLayer { alpha = opacity }
                    .background(CameraColors.MenuCard.copy(alpha = .72f))
                    .clearAndSetSemantics { testTag = "Card swipe hint" }) {
                    Canvas(Modifier.matchParentSize()) {
                        val x = size.width * .5f
                        val top = size.height * .22f
                        val bottom = size.height * .60f
                        val travel = sin(((progress - .23f) / .6f).coerceIn(0f, 1f) * Math.PI).toFloat()
                        val y = bottom - (bottom - top) * travel * travel
                        val accent = CameraColors.Accent
                        val tip = 4.dp.toPx()
                        drawLine(accent.copy(alpha = .35f), Offset(x, top), Offset(x, bottom), 1.dp.toPx(), StrokeCap.Round)
                        for ((end, direction) in listOf(top to 1f, bottom to -1f)) {
                            drawLine(accent.copy(alpha = .65f), Offset(x - tip, end + direction * tip), Offset(x, end), 1.5.dp.toPx(), StrokeCap.Round)
                            drawLine(accent.copy(alpha = .65f), Offset(x + tip, end + direction * tip), Offset(x, end), 1.5.dp.toPx(), StrokeCap.Round)
                        }
                        drawCircle(accent.copy(alpha = .12f), 14.dp.toPx(), Offset(x, y))
                        drawCircle(CameraColors.MenuCard, 8.dp.toPx(), Offset(x, y))
                        drawCircle(accent, 8.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
                        drawCircle(accent, 3.dp.toPx(), Offset(x, y))
                    }
                    OrientedLookPanel(abs(rotationDegrees) == 90f, rotationDegrees, Modifier.matchParentSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Text("Swipe to adjust", color = CameraColors.Text, fontSize = 10.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                modifier = Modifier.padding(bottom = 10.dp)
                                    .background(CameraColors.Glass, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
        if (amount == null || reordering || toggleable && !active) content()
        if (reordering) CardReorderIndicator(Modifier.align(Alignment.Center))
    }
}
