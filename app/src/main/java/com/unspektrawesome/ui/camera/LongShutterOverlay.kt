package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.session.LongShutterSelection
import com.unspektrawesome.preview.CameraControlState

@Composable
internal fun LongShutterOverlay(
    controls: CameraControlState,
    captureExposureTimeNs: Long,
    onApply: () -> Boolean,
    onCancel: () -> Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val pending = controls.pendingExposureTimeNs
    val capturing = captureExposureTimeNs > 0L
    val appliedLong = maxOf(controls.exposureTimeNs, captureExposureTimeNs) > LongShutterSelection.LIMIT_NS
    Row(modifier.widthIn(max = 304.dp).width(IntrinsicSize.Max).testTag("Long shutter notice")
        .railGlass().padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).languageSemantics(mergeDescendants = true) { language ->
            contentDescription = language.text("RAW preview follows shutter time.")
        }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("RAW preview", color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 13.sp)
            Text(if (capturing) "Capturing · ${formatShutter(captureExposureTimeNs)} s"
                else "Next frame ≈ ${formatShutter(controls.exposureTimeNs)} s",
                color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = CameraTypography.Readout)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pending != null) {
                CameraAssistAction("Apply", "${formatShutter(pending)} s",
                    "Apply long shutter", enabled = !capturing, primary = true, interactive = interactive, onClick = { onApply() })
            }
            if (appliedLong) {
                CameraAssistAction("Cancel", null, "Cancel long shutter", enabled = true, primary = false,
                    interactive = interactive, onClick = { onCancel() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraAssistAction(label: String, value: String?, description: String, enabled: Boolean, primary: Boolean, interactive: Boolean,
    onClick: () -> Unit) {
    val color = if (primary && enabled) CameraColors.Accent
        else CameraColors.Text.copy(alpha = if (enabled) .9f else .4f)
    FlowRow(Modifier.heightIn(min = 36.dp).widthIn(min = 44.dp).clip(CameraStyle.ActionShape)
        .border(1.dp, if (primary && enabled) CameraColors.Accent else CameraColors.MenuBorder, CameraStyle.ActionShape)
        .clickable(enabled = enabled && interactive, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(description) }
        .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 11.sp, lineHeight = 14.sp)
        value?.let { Text(it, color = color, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = CameraTypography.Readout) }
    }
}
