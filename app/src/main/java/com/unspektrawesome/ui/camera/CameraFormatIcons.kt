package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text

@Composable
internal fun CameraFormatIconChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: DrawScope.(Color) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val ink = if (!enabled) CameraColors.Muted else if (selected) CameraColors.Accent else CameraColors.Text
    Box(modifier.widthIn(min = 48.dp).height(52.dp).clip(RoundedCornerShape(12.dp))
        .selectable(selected, enabled = enabled, role = Role.RadioButton) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }.languageSemantics { language -> contentDescription = language.text(label) }) {
        Canvas(Modifier.matchParentSize()) {
            val edge = minOf(size.width - 12.dp.toPx(), 34.dp.toPx())
            translate((size.width - edge) / 2f, (size.height - edge) / 2f - 2.dp.toPx()) {
                scale(edge / 32f, edge / 32f, Offset.Zero) { icon(ink) }
            }
            if (selected) drawLine(ink, Offset(size.width / 2 - 9.dp.toPx(), size.height - 4.dp.toPx()),
                Offset(size.width / 2 + 9.dp.toPx(), size.height - 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun CameraFormatTextChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val ink = if (selected) CameraColors.Accent else CameraColors.Text
    Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .selectable(selected, role = Role.RadioButton) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }.languageSemantics { language -> contentDescription = language.text(label) }
        .drawBehind {
            if (selected) drawLine(ink, Offset(size.width / 2 - 9.dp.toPx(), size.height - 4.dp.toPx()),
                Offset(size.width / 2 + 9.dp.toPx(), size.height - 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }.padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = ink, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
            fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout, maxLines = 1)
    }
}
