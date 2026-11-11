package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.unspektrawesome.preview.RgbHistogram
import com.unspektrawesome.settings.HistogramSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ViewfinderHistogram(
    histogramSize: HistogramSize,
    readHistogram: suspend () -> RgbHistogram?,
    resetKey: Any?,
    onResize: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var histogram by remember(resetKey) { mutableStateOf<RgbHistogram?>(null) }
    val reader by rememberUpdatedState(readHistogram)
    val resize by rememberUpdatedState(onResize)
    val disable by rememberUpdatedState(onDisable)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, resetKey) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    histogram = reader()
                    delay((33 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1))
                }
            } finally {
                histogram = null
            }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(histogramSize.width.dp, histogramSize.height.dp)
        .testTag("Viewfinder histogram").clip(shape)
        .background(CameraColors.Glass.copy(alpha = .78f))
        .border(1.dp, CameraColors.MenuBorder, shape)
        .pointerInput(Unit) { detectTapGestures(onTap = { resize() }, onDoubleTap = { disable() }) }
        .languageSemantics(mergeDescendants = true) { language ->
            contentDescription = language.text("RGB histogram")
            stateDescription = language.format("{value1}, {value2}", mapOf("value1" to (language.text(histogramSize.label)), "value2" to (language.text(if (histogram == null) "waiting for preview" else "live"))))
            role = Role.Button
            onClick(language.text("Change histogram size")) { resize(); true }
            customActions = listOf(CustomAccessibilityAction(language.text("Hide histogram")) { disable(); true })
        }.padding(horizontal = 6.dp, vertical = 5.dp)) {
        val colors = listOf(CameraColors.HistogramRed, CameraColors.HistogramGreen, CameraColors.HistogramBlue)
        Canvas(Modifier.fillMaxSize()) {
            val baseline = size.height - 1.dp.toPx()
            val top = 1.dp.toPx()
            for (fraction in listOf(0f, .25f, .5f, .75f, 1f)) {
                val x = fraction * size.width
                drawLine(CameraColors.MenuBorder.copy(alpha = .22f), Offset(x, top), Offset(x, baseline), 1.dp.toPx())
            }
            drawLine(CameraColors.MenuBorder, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            histogram?.let { values ->
                val channels = listOf(values.red, values.green, values.blue)
                channels.forEachIndexed { channel, bins ->
                    val line = Path()
                    val fill = Path().apply { moveTo(0f, baseline) }
                    bins.forEachIndexed { bin, count ->
                        val x = bin * size.width / 255f
                        val y = baseline - (baseline - top) * count / values.peak
                        if (bin == 0) line.moveTo(x, y) else line.lineTo(x, y)
                        fill.lineTo(x, y)
                    }
                    fill.lineTo(size.width, baseline)
                    fill.close()
                    drawPath(fill, colors[channel].copy(alpha = .16f))
                    drawPath(line, colors[channel].copy(alpha = .9f), style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}
