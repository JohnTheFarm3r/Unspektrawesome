package com.unspektrawesome.ui.camera

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.preview.PreviewPerformance
import com.unspektrawesome.video.VideoPerformance
import com.unspektrawesome.video.VideoPhase
import com.unspektrawesome.video.VideoRecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

private const val VIDEO_TIMER_FORMAT = "%02d:%02d"
private const val PERCENT_FORMAT = "%.0f%%"

@Composable
internal fun VideoRecordingStatus(state: VideoRecordingState, readPerformance: () -> PreviewPerformance = { PreviewPerformance() }) {
    val context = LocalContext.current
    LaunchedEffect(state.phase, state.message) {
        if (state.phase == VideoPhase.ERROR && state.message != null)
            Toast.makeText(context, AppLanguage.text(state.message), Toast.LENGTH_LONG).show()
    }
    if (!state.enabled || !state.busy) return
    if (state.phase != VideoPhase.RECORDING) {
        Surface(color = LocalCameraControlBackground.current, shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CameraColors.PreviewControlBorder), modifier = Modifier.testTag("Video recording overlay")) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp).testTag("Video transition progress"), color = CameraColors.Accent,
                    strokeWidth = 1.5.dp, trackColor = CameraColors.PreviewControlBorder)
                Text(if (state.phase == VideoPhase.STARTING) "Starting video" else "Saving video",
                    color = CameraColors.Text, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("Video transition status"))
            }
        }
        return
    }
    var seconds by remember(state.startedAtMs) { mutableLongStateOf(0L) }
    var performance by remember(state.startedAtMs) { mutableStateOf(PreviewPerformance()) }
    val currentReader by rememberUpdatedState(readPerformance)
    LaunchedEffect(state.startedAtMs) {
        while (isActive) {
            seconds = ((SystemClock.elapsedRealtime() - state.startedAtMs) / 1000).coerceAtLeast(0)
            delay(200)
        }
    }
    LaunchedEffect(state.startedAtMs) {
        while (isActive) {
            performance = withContext(Dispatchers.Default) { currentReader() }
            delay(1_000)
        }
    }
    RecordingReadings(state, performance, String.format(Locale.ROOT, VIDEO_TIMER_FORMAT, seconds / 60, seconds % 60))
}

private data class RecordingMetric(val label: String, val value: String, val description: String,
    val argument: Any?, val tag: String, val widthReference: String = "100%", val color: Color = CameraColors.Text)

private val recordingValueStyle = TextStyle(fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout, fontSize = 12.sp,
    lineHeight = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
private val recordingLabelStyle = TextStyle(fontSize = 9.sp, lineHeight = 11.sp,
    lineBreak = LineBreak(LineBreak.Strategy.Simple, LineBreak.Strictness.Loose, LineBreak.WordBreak.Default),
    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
// Standard camera abbreviations, shown the same in every language.
private const val LOCKED_CONTROLS_LABEL = "AE/AF/WB"
private val recordingTimerStyle = TextStyle(fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout, fontSize = 14.sp,
    lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun RecordingReadings(state: VideoRecordingState, performance: PreviewPerformance, duration: String) {
    val video = performance.video ?: VideoPerformance(droppedFrames = state.droppedFrames)
    val gpu = performance.gpuFrameBudgetPercent(state.frameRate)
    val memory = video.memoryUsedPercent?.let { String.format(Locale.ROOT, PERCENT_FORMAT, it) } ?: "--"
    val gpuText = gpu?.let { String.format(Locale.ROOT, PERCENT_FORMAT, it) } ?: "--"
    val encoder = video.encoderPressurePercent?.let { String.format(Locale.ROOT, PERCENT_FORMAT, it) } ?: "--"
    val fps = video.encodedFramesPerSecond?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "--"
    val size = video.fileSizeBytes?.let { bytes ->
        if (bytes >= 1_000_000_000L) AppLanguage.format("{value1} GB", mapOf("value1" to String.format(Locale.ROOT, "%.2f", bytes / 1e9)))
        else AppLanguage.format("{value1} MB", mapOf("value1" to String.format(Locale.ROOT, "%.0f", bytes / 1e6)))
    } ?: "--"
    val drops = maxOf(state.droppedFrames, video.droppedFrames)
    val metrics = listOf(
        RecordingMetric(AppLanguage.text("Memory"), memory, "Recording buffer used: {value1}", memory, tag = "Recording memory",
            color = when { (video.memoryUsedPercent ?: 0.0) >= 95 -> CameraColors.FocusFailed
                (video.memoryUsedPercent ?: 0.0) >= 80 -> CameraColors.Warning; else -> CameraColors.Text }),
        RecordingMetric(AppLanguage.text("GPU"), gpuText, "GPU frame budget used: {value1}", gpuText, tag = "Recording GPU",
            color = if (gpu != null && gpu >= 100) CameraColors.FocusFailed else CameraColors.Text),
        RecordingMetric(AppLanguage.text("Encoder"), encoder,
            "Encoder pressure: {value1}", encoder, tag = "Recording encoder",
            color = when { (video.encoderPressurePercent ?: 0.0) >= 100 -> CameraColors.FocusFailed
                (video.encoderPressurePercent ?: 0.0) >= 80 -> CameraColors.Warning; else -> CameraColors.Text }),
        RecordingMetric(AppLanguage.text("FPS"), fps, "Output frame rate: {value1} FPS", fps, tag = "Recording FPS", widthReference = "99.99"),
        RecordingMetric(AppLanguage.text("Size"), size, "Recording size: {value1}", size, tag = "Recording size",
            widthReference = AppLanguage.format("{value1} GB", mapOf("value1" to "99.99"))),
        RecordingMetric(AppLanguage.text("Dropped"), drops.toString(), "Dropped frames: {value1}", drops, tag = "Dropped video frames",
            widthReference = "99999",
            color = when { drops > 20 -> CameraColors.FocusFailed; drops > 0 -> CameraColors.Warning; else -> CameraColors.Text }),
    )
    BoxWithConstraints(Modifier.widthIn(max = 416.dp)) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val inheritedStyle = LocalTextStyle.current
        val references = metrics.map { it.label to it.widthReference }
        val geometry = remember(maxWidth, density, references, inheritedStyle) {
            fun measure(scale: Float, columnCount: Int = 3, bounded: Boolean = false): RecordingReadingsLayout {
                val valueStyle = inheritedStyle.merge(recordingValueStyle).copy(fontSize = recordingValueStyle.fontSize * scale,
                    lineHeight = recordingValueStyle.lineHeight * scale)
                val labelStyle = inheritedStyle.merge(recordingLabelStyle).copy(fontSize = recordingLabelStyle.fontSize * scale,
                    lineHeight = recordingLabelStyle.lineHeight * scale)
                val timerStyle = inheritedStyle.merge(recordingTimerStyle).copy(fontSize = recordingTimerStyle.fontSize * scale,
                    lineHeight = recordingTimerStyle.lineHeight * scale)
                fun textWidth(text: String, style: TextStyle) = with(density) {
                    measurer.measure(text, style, softWrap = false).size.width.toDp()
                }
                val widths = references.map { (label, value) -> maxOf(textWidth(label, labelStyle), textWidth(value, valueStyle)) + 4.dp }
                val timerWidth = textWidth("999:59", timerStyle) + 14.dp
                val maximumCell = ((maxWidth - timerWidth - 36.dp) / (columnCount * 2)).coerceAtLeast(12.dp)
                val columns = List(columnCount) { index ->
                    val width = widths.indices.filter { it % 3 % columnCount == index }.maxOf { widths[it] }
                    if (bounded) minOf(width, maximumCell) else width
                }
                val valueHeight = with(density) { references.maxOf {
                    measurer.measure(it.second, valueStyle, softWrap = false).size.height
                }.toDp() } + 1.dp
                val labelHeight = with(density) { references.mapIndexed { index, reference ->
                    measurer.measure(reference.first, labelStyle,
                        constraints = Constraints(maxWidth = (columns[index % 3 % columnCount] - 4.dp).roundToPx().coerceAtLeast(1))).size.height
                }.max().toDp() } + 1.dp
                return RecordingReadingsLayout(columns, timerWidth, valueStyle, labelStyle, timerStyle, valueHeight, labelHeight)
            }
            var layout = measure(1f)
            if (density.fontScale > 1.1f) {
                for (columns in listOf(2, 1)) {
                    if (layout.width <= maxWidth) break
                    layout = measure(1f, columns)
                }
                if (layout.width > maxWidth) layout = measure(1f, 1, bounded = true)
                return@remember layout
            }
            if (layout.width > maxWidth) {
                var lower = 0f
                var upper = 1f
                repeat(12) {
                    val scale = (lower + upper) / 2
                    val candidate = measure(scale)
                    if (candidate.width <= maxWidth) { lower = scale; layout = candidate } else upper = scale
                }
            }
            layout
        }
        val cellWidths = geometry.widths
        val timerWidth = geometry.timerWidth
        val metricValueStyle = geometry.valueStyle
        val metricLabelStyle = geometry.labelStyle
        val timerStyle = geometry.timerStyle
        val valueHeight = geometry.valueHeight
        val labelHeight = geometry.labelHeight
        Surface(color = LocalCameraControlBackground.current, shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, CameraColors.Accent), modifier = Modifier.width(minOf(maxWidth, geometry.width))
                .testTag("Video recording overlay")) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RecordingMetricGroup(metrics.take(3), cellWidths, metricValueStyle, metricLabelStyle, valueHeight, labelHeight, Modifier.weight(1f))
                Column(Modifier.width(timerWidth).testTag("Recording duration").languageSemantics(mergeDescendants = true) { language ->
                    contentDescription = language.format(if (state.controlsLocked)
                        "Recording {value1}, exposure, focus and white balance locked" else "Recording {value1}",
                        mapOf("value1" to duration))
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    // While locked, the timer shares the metric rows' height so the lock label fits without growing the bar.
                    val rowStyle = if (state.controlsLocked) timerStyle.copy(lineHeight = metricValueStyle.lineHeight) else timerStyle
                    Row(if (state.controlsLocked) Modifier.height(valueHeight) else Modifier,
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(5.dp)) { drawCircle(CameraColors.Accent) }
                        Spacer(Modifier.width(5.dp))
                        Text(duration, translate = false, style = fittingRecordingStyle(duration, rowStyle, timerWidth - 14.dp),
                            color = CameraColors.Text, maxLines = 1, softWrap = false)
                    }
                    if (state.controlsLocked) {
                        Row(Modifier.height(labelHeight).testTag("Recording controls locked"),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            RecordingLockIcon(Modifier.size(6.dp, 7.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(LOCKED_CONTROLS_LABEL, translate = false, color = CameraColors.Accent,
                                style = fittingRecordingStyle(LOCKED_CONTROLS_LABEL, metricLabelStyle, timerWidth - 10.dp),
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
                RecordingMetricGroup(metrics.drop(3), cellWidths, metricValueStyle, metricLabelStyle, valueHeight, labelHeight, Modifier.weight(1f))
            }
        }
    }
}

private data class RecordingReadingsLayout(val widths: List<Dp>, val timerWidth: Dp,
    val valueStyle: TextStyle, val labelStyle: TextStyle, val timerStyle: TextStyle,
    val valueHeight: Dp, val labelHeight: Dp) {
    val width: Dp get() = widths.fold(0.dp) { sum, width -> sum + width } * 2 + timerWidth + 36.dp
}

@Composable
private fun RecordingMetricGroup(metrics: List<RecordingMetric>, widths: List<Dp>, valueStyle: TextStyle, labelStyle: TextStyle,
    valueHeight: Dp, labelHeight: Dp, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in metrics.chunked(widths.size)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                for ((index, metric) in row.withIndex()) {
                    Column(Modifier.width(widths[index]).testTag(metric.tag).languageSemantics(mergeDescendants = true) { language ->
                        contentDescription = language.format(metric.description, mapOf("value1" to metric.argument))
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(metric.value, translate = false, color = metric.color,
                            style = fittingRecordingStyle(metric.value, valueStyle, widths[index] - 4.dp), maxLines = 1, softWrap = false,
                            modifier = Modifier.fillMaxWidth().height(valueHeight))
                        Text(metric.label, translate = false, color = CameraColors.Muted,
                            style = labelStyle,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(labelHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun fittingRecordingStyle(text: String, style: TextStyle, width: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { width.toPx() }.coerceAtLeast(1f)
    val measured = measurer.measure(text, style, softWrap = false).size.width.coerceAtLeast(1)
    return if (measured <= available) style else style.copy(fontSize = style.fontSize * (available / measured))
}

/** Small lime padlock shown in place of the recording dot while controls are locked. */
@Composable
private fun RecordingLockIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.width * .2f
        val bodyTop = size.height * .45f
        drawArc(CameraColors.Accent, 180f, 180f, false,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * .2f, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width * .6f, bodyTop * 1.6f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRoundRect(CameraColors.Accent, topLeft = androidx.compose.ui.geometry.Offset(0f, bodyTop),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - bodyTop),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .15f))
    }
}
