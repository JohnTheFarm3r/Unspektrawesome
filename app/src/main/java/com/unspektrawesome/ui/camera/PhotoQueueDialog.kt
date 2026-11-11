package com.unspektrawesome.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import com.unspektrawesome.language.AppLanguage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
internal fun PhotoQueueDialog(
    outstandingShots: Int,
    onDismiss: () -> Unit,
    onCancelWaiting: () -> Unit,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    var confirmingCancellation by rememberSaveable { mutableStateOf(false) }
    CameraDialogWindow(onDismiss = { if (confirmingCancellation) confirmingCancellation = false else onDismiss() },
        modifier = Modifier.testTag("Photo queue dialog"), header = {
            Text(if (confirmingCancellation) "Cancel processing queue?"
                else if (outstandingShots == 0 && errorMessage != null) "Photo not saved" else "Photo queue ($outstandingShots)",
                color = CameraColors.Text, fontWeight = FontWeight.SemiBold)
        }, footer = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (confirmingCancellation) {
                    CameraDialogAction("Keep queue", primary = true, onClick = { confirmingCancellation = false })
                    CameraDialogAction("Cancel processing queue", primary = false, onClick = onCancelWaiting)
                } else {
                    if (onRetry != null) CameraDialogAction("Retry", primary = true, onClick = onRetry)
                    CameraDialogAction(if (outstandingShots > 0 && errorMessage == null) "Keep processing" else "Close",
                        primary = onRetry == null, onClick = onDismiss)
                    if (outstandingShots > 0) CameraDialogAction("Cancel processing queue", primary = false,
                        onClick = { confirmingCancellation = true })
                }
            }
        }) {
            if (confirmingCancellation) {
                Text("Queued photos will be removed and cannot be recovered. The current export will finish. Saved photos are kept.",
                    color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
            } else {
                if (errorMessage != null) Text(errorMessage, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
                if (outstandingShots > 0) Text(
                    if (onRetry != null) "Retry processes paused photos again with their original settings. Cancel removes waiting photos; the current export will finish."
                    else "Each photo keeps its original settings. Cancel removes waiting photos; the current export will finish.",
                    color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
    }
}

@Composable
internal fun CameraDialogAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 19.sp)
    val wantedWidth = with(density) { measurer.measure(AppLanguage.text(label), style).size.width.toDp() } + 30.dp
    BoxWithConstraints {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.width(minOf(maxWidth, wantedWidth.coerceAtLeast(64.dp))).heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            shape = CameraStyle.ActionShape,
            border = BorderStroke(1.dp, if (primary) CameraColors.Accent else CameraColors.MenuBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (primary) CameraColors.Accent else CameraColors.Text,
            ),
        ) {
            Text(label, Modifier.fillMaxWidth(), style = style, textAlign = TextAlign.Center)
        }
    }
}
