package com.unspektrawesome.ui.camera

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ShutterExposureRing(startedAtMs: Long, exposureTimeNs: Long, modifier: Modifier = Modifier) {
    val progress = remember(startedAtMs) { Animatable(0f) }
    LaunchedEffect(startedAtMs, exposureTimeNs) {
        val durationMs = (exposureTimeNs / 1_000_000L).coerceAtLeast(1L)
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceIn(0L, durationMs)
        progress.snapTo(elapsedMs.toFloat() / durationMs)
        progress.animateTo(1f, tween((durationMs - elapsedMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), easing = LinearEasing))
    }
    Canvas(modifier.testTag("Shutter exposure progress").semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(progress.value, 0f..1f)
    }) {
        val strokeWidth = 2.dp.toPx()
        drawArc(CameraColors.Accent, -90f, progress.value * 360f, false,
            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}
