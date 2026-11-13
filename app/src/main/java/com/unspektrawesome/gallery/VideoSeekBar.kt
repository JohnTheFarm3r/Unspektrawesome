package com.unspektrawesome.gallery

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.unspektrawesome.ui.camera.CameraColors
import kotlin.math.roundToLong

@Composable
internal fun VideoSeekBar(position: Long, duration: Long, enabled: Boolean,
    modifier: Modifier = Modifier, onScrubStart: () -> Unit, onSeek: (Long) -> Unit, onScrubStop: () -> Unit) {
    val start by rememberUpdatedState(onScrubStart)
    val seek by rememberUpdatedState(onSeek)
    val stop by rememberUpdatedState(onScrubStop)
    var dragging by remember { mutableStateOf(false) }
    val limit = duration.coerceAtLeast(1)
    fun jump(target: Long): Boolean {
        if (!enabled) return false
        start()
        try { seek(target.coerceIn(0, duration)) } finally { stop() }
        return true
    }
    Canvas(modifier.fillMaxWidth().height(48.dp).testTag("Video seek")
        .languageSemantics { language ->
            contentDescription = language.text("Video position")
            stateDescription = videoDuration(position)
            progressBarRangeInfo = ProgressBarRangeInfo(position.coerceIn(0, limit).toFloat(), 0f..limit.toFloat())
            if (enabled) setProgress { jump(it.roundToLong()) } else disabled()
        }.onKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionLeft -> jump(position - 5_000)
                Key.DirectionRight -> jump(position + 5_000)
                Key.MoveHome -> jump(0)
                Key.MoveEnd -> jump(duration)
                else -> false
            }
        }.focusable(enabled)
        .pointerInput(enabled, duration) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val inset = 8.dp.toPx()
                fun move(x: Float) {
                    seek((((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f))
                        .coerceIn(0f, 1f) * duration).roundToLong())
                }
                dragging = true
                start()
                try {
                    move(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        move(change.position.x)
                        change.consume()
                    } while (change.pressed)
                } finally {
                    stop()
                    dragging = false
                }
            }
        }) {
        val inset = 8.dp.toPx()
        val left = Offset(inset, center.y)
        val right = Offset((size.width - inset).coerceAtLeast(inset), center.y)
        val thumb = Offset(left.x + (right.x - left.x) * (position.toDouble() / limit).coerceIn(0.0, 1.0).toFloat(), center.y)
        val color = if (enabled) CameraColors.Accent else CameraColors.Muted
        drawLine(Color(0xFF3A3A3A), left, right, 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, left, thumb, 2.dp.toPx(), StrokeCap.Round)
        // The dot grows and gains a dashed ring while it is dragged.
        if (dragging) drawCircle(color, 11.dp.toPx(), thumb, style = Stroke(1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
        drawCircle(color, (if (dragging) 6 else 4).dp.toPx(), thumb)
    }
}
