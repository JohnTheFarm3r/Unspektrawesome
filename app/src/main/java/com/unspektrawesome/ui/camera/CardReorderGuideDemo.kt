package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

@Composable
internal fun CardReorderGuideDemo() {
    val holdDuration = LOOK_CARD_HOLD_MILLIS.toFloat()
    val dragStart = holdDuration + 600f
    val dragEnd = dragStart + 1_200f
    val releaseTime = dragEnd + 200f
    val duration = (releaseTime + 600f).toInt()
    val transition = rememberInfiniteTransition(label = "Rearrange cards demonstration")
    val time by transition.animateFloat(0f, duration.toFloat(),
        infiniteRepeatable(tween(duration, easing = LinearEasing)), label = "Hold and drag demonstration")
    val travel = ((time - dragStart) / 1_200f).coerceIn(0f, 1f)
    val swap = ((travel - .55f) / .3f).coerceIn(0f, 1f)
    val pulse = .4f + .6f * ((sin((time - holdDuration) / 180f) + 1f) / 2f)
    Column(Modifier.fillMaxWidth().testTag("Card reorder demonstration"), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val width = minOf(52.dp.toPx(), size.width / 4)
            val gap = 10.dp.toPx()
            val stride = width + gap
            val center = size.width / 2
            val top = 10.dp.toPx()
            val cardSize = Size(width, 60.dp.toPx())
            fun card(x: Float, active: Boolean) {
                val left = x - width / 2
                val color = if (active) CameraColors.Accent else CameraColors.MenuBorder
                drawRoundRect(if (active) CameraColors.MenuSelectedCard else CameraColors.MenuCard,
                    Offset(left, top), cardSize, CornerRadius(8.dp.toPx()))
                drawRoundRect(color, Offset(left, top), cardSize, CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                for (line in 0..2) drawLine(CameraColors.Muted.copy(alpha = .5f),
                    Offset(left + 9.dp.toPx(), top + (14 + line * 8).dp.toPx()),
                    Offset(left + width - 9.dp.toPx(), top + (14 + line * 8).dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            }
            card(center - stride, false)
            card(center + stride * (1f - swap), false)
            val moving = center + stride * travel
            card(moving, true)
            val finger = Offset(moving, top + cardSize.height * .68f)
            if (time < releaseTime) {
                drawCircle(CameraColors.Background, 8.dp.toPx(), finger)
                drawCircle(CameraColors.Accent, 8.dp.toPx(), finger, style = Stroke(1.5.dp.toPx()))
                if (time < holdDuration) drawArc(CameraColors.Accent, -90f, 360f * time / holdDuration, false,
                    finger - Offset(12.dp.toPx(), 12.dp.toPx()), Size(24.dp.toPx(), 24.dp.toPx()), style = Stroke(2.dp.toPx()))
                else for (direction in listOf(-1, 1)) {
                    val tip = finger + Offset(direction * 20.dp.toPx(), 0f)
                    val ink = CameraColors.Accent.copy(alpha = pulse)
                    drawLine(ink, finger + Offset(direction * 11.dp.toPx(), 0f), tip, 2.dp.toPx(), StrokeCap.Round)
                    for (side in listOf(-1, 1)) drawLine(ink, tip,
                        tip + Offset(-direction * 4.dp.toPx(), side * 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Text(when {
            time < holdDuration -> "Hold still · ${holdDuration / 1_000f} s"
            time < dragStart -> "Pulsing arrows mean ready"
            time < dragEnd -> "Drag past halfway"
            else -> "Release to save"
        }, color = CameraColors.Accent, fontSize = 11.sp)
    }
}
