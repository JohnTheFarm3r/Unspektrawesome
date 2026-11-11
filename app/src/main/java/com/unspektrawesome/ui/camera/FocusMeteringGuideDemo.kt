package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.settings.FocusPeakingStrength

@Composable
internal fun FocusMeteringGuideDemo() {
    val animation = rememberInfiniteTransition(label = "Focus guide")
    val progress by animation.animateFloat(0f, 1f,
        infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "Focus demonstration")
    val manual = progress >= .5f
    val strength = FocusPeakingStrength.entries[((progress - .5f).coerceAtLeast(0f) * 6).toInt().coerceIn(0, 2)]
    Column(Modifier.fillMaxWidth().testTag("Focus and exposure demonstration"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when {
            manual -> "FOCUS open + manual · green detail"
            progress >= .44f -> "Tap again · targets return"
            progress >= .38f -> "Large scene change · targets hide"
            progress >= .3125f -> "After 5 s · translucent targets"
            else -> "Tap together · drag either target apart"
        },
            color = CameraColors.Muted, fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(70.dp).background(CameraColors.MenuCard)) {
            val stroke = Stroke(1.5.dp.toPx())
            if (manual) {
                for (x in 1..9) {
                    val start = Offset(size.width * x / 10, size.height * .18f)
                    val end = start + Offset(0f, size.height * .64f)
                    val highlighted = x in (5 - strength.ordinal)..(5 + strength.ordinal)
                    val width = (1.5f + strength.ordinal).dp.toPx()
                    if (highlighted) drawLine(CameraColors.MenuBackground, start, end, width + 2.dp.toPx())
                    drawLine(if (highlighted) CameraColors.Accent else CameraColors.Muted,
                        start, end, if (highlighted) width else 1.5.dp.toPx())
                }
            } else {
                val separation = if (progress >= .44f) 0f else ((progress - .04f) / .14f).coerceIn(0f, 1f)
                val opacity = when {
                    progress >= .44f -> 1f
                    progress >= .38f -> 0f
                    else -> 1f - .65f * ((progress - .3125f) / .025f).coerceIn(0f, 1f)
                }
                val center = Offset(size.width * (.5f - .22f * separation), size.height * .5f)
                drawRect((if (progress > .12f) CameraColors.Accent else CameraColors.Text).copy(alpha = opacity),
                    center - Offset(16.dp.toPx(), 16.dp.toPx()), Size(32.dp.toPx(), 32.dp.toPx()), style = stroke)
                drawCircle(CameraColors.Accent.copy(alpha = opacity), 22.dp.toPx(), Offset(size.width * .5f, center.y), style = stroke)
            }
        }
        FocusPeakingOverlay(strength, {}, interactive = false)
    }
}
