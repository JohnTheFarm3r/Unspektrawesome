package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.preview.PreviewPerformance
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
internal fun PreviewPerformanceOverlay(
    readPerformance: () -> PreviewPerformance,
    resetKey: Any?,
    modifier: Modifier = Modifier,
    videoMode: Boolean = false,
) {
    var performance by remember(resetKey, videoMode) { mutableStateOf(PreviewPerformance()) }
    val currentReader by rememberUpdatedState(readPerformance)
    LaunchedEffect(resetKey, videoMode) {
        performance = currentReader()
        while (isActive) {
            delay(1_000)
            performance = currentReader()
        }
    }
    val fps = performance.framesPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"
    val milliseconds = performance.pipelineMs?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"
    val viewfinder = performance.frame?.viewfinderSize.resolutionText()
    val rawInput = performance.frame?.rawInputSize.resolutionText()
    val effects = performance.frame?.effectsEnabled?.let { if (it) "Yes" else "No" } ?: "--"
    val video = performance.video ?: com.unspektrawesome.video.VideoPerformance()
    val text = if (videoMode)
        "{fps} FPS · {milliseconds} ms\nVF: {viewfinder} · RAW: {rawInput}\nRAW Q: {queued}/{capacity} · Encoder Q: {encoderQueued}\nOutput: {encodedFps} FPS · Dropped: {dropped}"
    else "{fps} FPS · {milliseconds} ms\nVF: {viewfinder} · RAW: {rawInput}\nEffects: {effects}"
    Text(text, arguments = mapOf("fps" to fps, "milliseconds" to milliseconds, "viewfinder" to viewfinder,
        "rawInput" to rawInput, "effects" to effects, "queued" to video.queuedFrames, "capacity" to video.queueCapacity,
        "encoderQueued" to (video.encoderQueuedFrames?.toString() ?: "--"),
        "encodedFps" to (video.encodedFramesPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"),
        "dropped" to video.droppedFrames),
        color = CameraColors.Text.copy(alpha = .88f),
        fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout, fontSize = 11.sp, lineHeight = 14.sp,
        modifier = modifier.testTag("Preview performance").width(IntrinsicSize.Max)
            .background(LocalCameraControlBackground.current, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp))
}

private fun Size2d?.resolutionText(): String = this?.let { "${it.width}×${it.height}" } ?: "--"
