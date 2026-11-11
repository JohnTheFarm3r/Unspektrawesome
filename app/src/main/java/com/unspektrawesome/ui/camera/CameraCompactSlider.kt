package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Set to true while a slider gesture is in progress so a surrounding pager does not swipe. */
internal val LocalSliderGestureLock = compositionLocalOf<MutableState<Boolean>?> { null }

@Composable
internal fun CameraCompactSlider(value: Float, onValueChange: (Float) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, thumbSize: Dp = 12.dp, minimumHeight: Dp = 48.dp,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, sensitivity: Float = 1f,
    onReset: (() -> Unit)? = null, requireThumb: Boolean = true,
    valueStep: Float? = null) {
    val span = valueRange.endInclusive - valueRange.start
    val currentValue by rememberUpdatedState(((value - valueRange.start) / span).coerceIn(0f, 1f))
    val currentNumber by rememberUpdatedState(value)
    val changeValue by rememberUpdatedState(onValueChange)
    val reset by rememberUpdatedState(onReset)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gestureLock = LocalSliderGestureLock.current
    var dragging by remember { mutableStateOf(false) }
    var touchFraction by remember(sensitivity, valueRange) { mutableStateOf<Float?>(null) }
    var emittedValue by remember(sensitivity, valueRange) { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!dragging && value != emittedValue) touchFraction = null
    }
    val accent = if (enabled) CameraColors.Accent else CameraColors.Muted
    fun setValue(next: Float): Boolean {
        if (!enabled || !next.isFinite()) return false
        val selected = next.coerceIn(0f, 1f)
        if (selected == currentValue) return false
        val amount = when (selected) {
            0f -> valueRange.start
            1f -> valueRange.endInclusive
            else -> valueRange.start + selected * span
        }
        val snapped = valueStep?.let { (amount / it).roundToInt() * it }?.coerceIn(valueRange) ?: amount
        if (snapped == currentNumber) return false
        emittedValue = snapped
        changeValue(snapped)
        return true
    }
    Canvas(modifier.heightIn(min = minimumHeight)
        .languageSemantics { language ->
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
            if (valueRange == 0f..1f) stateDescription = language.format("{value1}%", mapOf("value1" to ((currentValue * 100).roundToInt())))
            if (enabled) {
                setProgress { touchFraction = null; setValue((it - valueRange.start) / span) }
                if (onReset != null) customActions = listOf(CustomAccessibilityAction(language.text("Reset")) {
                    touchFraction = null; reset?.invoke(); true
                })
            } else disabled()
        }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val next = when (event.key) {
                Key.DirectionLeft -> currentValue + if (rtl) .01f else -.01f
                Key.DirectionRight -> currentValue + if (rtl) -.01f else .01f
                Key.DirectionUp -> currentValue + .01f
                Key.DirectionDown -> currentValue - .01f
                Key.PageUp -> currentValue + .1f
                Key.PageDown -> currentValue - .1f
                Key.MoveHome -> 0f
                Key.MoveEnd -> 1f
                else -> return@onKeyEvent false
            }
            touchFraction = null
            setValue(next)
            true
        }
        .focusable(enabled)
        .pointerInput(enabled, onReset != null) {
            if (enabled && onReset != null) detectTapGestures(onDoubleTap = {
                touchFraction = null
                reset?.invoke()
            })
        }
        .pointerInput(enabled, rtl, thumbSize, sensitivity, valueRange, requireThumb, valueStep) {
            if (enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = onReset == null)
                    val radius = thumbSize.toPx() / 2f
                    val width = (size.width - thumbSize.toPx()).coerceAtLeast(1f)
                    val displayed = touchFraction ?: currentValue
                    val thumbX = radius + width * if (rtl) 1f - displayed else displayed
                    val touchRadius = maxOf(radius, 24.dp.toPx())
                    if (requireThumb && (abs(down.position.x - thumbX) > touchRadius ||
                        abs(down.position.y - size.height / 2f) > touchRadius)) return@awaitEachGesture
                    gestureLock?.value = true
                    try {
                    val initialValue = currentValue
                    val downFraction = ((down.position.x - radius) / width).coerceIn(0f, 1f)
                    val initialFraction = if (rtl) 1f - downFraction else downFraction
                    var position = initialValue
                    fun move(x: Float, delta: Float) {
                        if (!requireThumb) {
                            val fraction = ((x - radius) / width).coerceIn(0f, 1f)
                            val nextFraction = if (rtl) 1f - fraction else fraction
                            touchFraction = nextFraction
                            position = editorSliderValue(nextFraction, initialFraction, initialValue, sensitivity)
                        } else {
                            position = (position + delta / width * sensitivity * if (rtl) -1f else 1f).coerceIn(0f, 1f)
                        }
                        setValue(position)
                    }
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        dragging = true
                        move(change.position.x, overSlop)
                    }
                    if (drag != null) {
                        dragging = true
                        try {
                            horizontalDrag(drag.id) { change ->
                                move(change.position.x, change.positionChange().x)
                                change.consume()
                            }
                        } finally {
                            dragging = false
                        }
                    }
                    } finally {
                        gestureLock?.value = false
                    }
                }
            }
        }) {
        val radius = thumbSize.toPx() / 2f
        val left = Offset(radius, center.y)
        val right = Offset(size.width - radius, center.y)
        val displayed = touchFraction ?: currentValue
        val fraction = if (rtl) 1f - displayed else displayed
        val thumb = Offset(left.x + (right.x - left.x) * fraction, center.y)
        val stroke = thumbSize.toPx() / 4f
        drawLine(CameraColors.Surface, left, right, stroke, StrokeCap.Round)
        drawLine(accent, if (rtl) right else left, thumb, stroke, StrokeCap.Round)
        if (enabled && (dragging || focused)) drawCircle(accent, radius + 4.dp.toPx(), thumb, style = Stroke(1.dp.toPx()))
        drawCircle(accent, radius, thumb)
    }
}
