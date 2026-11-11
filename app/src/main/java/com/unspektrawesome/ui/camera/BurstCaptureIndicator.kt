package com.unspektrawesome.ui.camera

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.burst.BurstStatus
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.LanguageText as Text

@Composable
internal fun BurstCaptureIndicator(state: BurstStatus) {
    val context = LocalContext.current
    val message = state.message?.let { AppLanguage.text(it) }
    DisposableEffect(message) {
        val toast = message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).apply { show() } }
        onDispose { toast?.cancel() }
    }
    val frames = if (state.busy) state.captured else state.buffered
    val expected = (state.preShutterSeconds + if (state.busy) 1 else 0) * 30
    val progress = rememberUpdatedState((frames.toFloat() / expected.coerceAtLeast(1)).coerceIn(0f, 1f))
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.testTag("Burst status").clip(shape)
        .background(CameraColors.MenuBackground)
        .drawWithCache {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val left = inset
            val top = inset
            val right = size.width - inset
            val bottom = size.height - inset
            val radius = (12.dp.toPx() - inset).coerceIn(0f, minOf(right - left, bottom - top).coerceAtLeast(0f) / 2)
            val diameter = radius * 2
            val outline = Path().apply {
                moveTo(size.width / 2, top)
                lineTo(right - radius, top)
                arcTo(Rect(right - diameter, top, right, top + diameter), -90f, 90f, false)
                lineTo(right, bottom - radius)
                arcTo(Rect(right - diameter, bottom - diameter, right, bottom), 0f, 90f, false)
                lineTo(left + radius, bottom)
                arcTo(Rect(left, bottom - diameter, left + diameter, bottom), 90f, 90f, false)
                lineTo(left, top + radius)
                arcTo(Rect(left, top, left + diameter, top + diameter), 180f, 90f, false)
                close()
            }
            val measure = PathMeasure().apply { setPath(outline, false) }
            val segment = Path()
            val style = Stroke(stroke, cap = StrokeCap.Round)
            onDrawBehind {
                drawPath(outline, CameraColors.MenuBorder, style = style)
                val fraction = progress.value
                if (fraction >= 1f) drawPath(outline, CameraColors.Accent, style = style)
                else if (fraction > 0f) {
                    segment.reset()
                    measure.getSegment(0f, measure.length * fraction, segment)
                    drawPath(segment, CameraColors.Accent, style = style)
                }
            }
        }
        .languageSemantics { language ->
            contentDescription = language.text("BURST")
            stateDescription = listOfNotNull(
                language.format("Before: {count} s · After: 1 s", mapOf("count" to state.preShutterSeconds)),
                language.format(if (state.busy) "BURST · {count} captured" else "BURST · {count} buffered", mapOf("count" to frames)),
                if (state.saving > 0) language.format("Saving BURST · {count}", mapOf("count" to state.saving)) else null,
            ).joinToString(". ")
        }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("−{count} s · +1 s", arguments = mapOf("count" to state.preShutterSeconds),
            color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
