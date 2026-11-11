package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CaptureStatusCard(feedback: CaptureFeedback, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier.clip(shape).background(CameraColors.MenuCard)
            .border(1.dp, CameraColors.MenuBorder, shape)
            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("Capture status").semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val textMinimum = minOf(maxWidth, 160.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).widthIn(min = textMinimum), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptureStatusIcon(feedback.kind)
        Text(feedback.label, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (feedback.action != null && onAction != null) {
            TextButton(onClick = onAction, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.languageSemantics { language ->
                    contentDescription = language.text(if (feedback.action == "Details") "Photo error details" else "Manage photo queue")
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CameraColors.Accent)) {
                Text(feedback.action, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                CameraGlyphIcon(CameraGlyph.Chevron, Modifier.padding(start = 4.dp).size(12.dp), CameraColors.Accent)
            }
        }
        }
    }
}

@Composable
internal fun CaptureStatusIcon(kind: CaptureFeedbackKind, modifier: Modifier = Modifier.size(18.dp)) {
    if (kind == CaptureFeedbackKind.Processing) {
        CircularProgressIndicator(modifier, color = CameraColors.Accent,
            strokeWidth = 2.dp, trackColor = CameraColors.MenuBorder)
    } else {
        Canvas(modifier) {
            val stroke = 1.7.dp.toPx()
            val color = if (kind == CaptureFeedbackKind.Saved) CameraColors.Accent else CameraColors.Muted
            when (kind) {
                CaptureFeedbackKind.Paused -> {
                    for (x in listOf(.35f, .65f)) drawLine(color,
                        Offset(size.width * x, size.height * .2f), Offset(size.width * x, size.height * .8f), stroke, StrokeCap.Round)
                }
                CaptureFeedbackKind.Saved -> {
                    drawLine(color, Offset(size.width * .15f, size.height * .5f),
                        Offset(size.width * .4f, size.height * .75f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .4f, size.height * .75f),
                        Offset(size.width * .85f, size.height * .25f), stroke, StrokeCap.Round)
                }
                CaptureFeedbackKind.Error -> {
                    drawCircle(color, size.minDimension / 2 - stroke, style = Stroke(stroke))
                    drawLine(color, Offset(center.x, size.height * .25f),
                        Offset(center.x, size.height * .52f), stroke, StrokeCap.Round)
                    drawCircle(color, stroke / 2, Offset(center.x, size.height * .72f))
                }
                CaptureFeedbackKind.Canceled -> {
                    drawCircle(color, size.minDimension / 2 - stroke, style = Stroke(stroke))
                    drawLine(color, Offset(size.width * .3f, center.y),
                        Offset(size.width * .7f, center.y), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
