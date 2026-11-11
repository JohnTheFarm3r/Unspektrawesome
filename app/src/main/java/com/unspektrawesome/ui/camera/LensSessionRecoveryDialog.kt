package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.preview.LensSessionRecoveryPhase
import com.unspektrawesome.preview.LensSessionRecoveryStatus

@Composable
internal fun LensSessionRecoveryDialog(
    status: LensSessionRecoveryStatus,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onLensSettings: () -> Unit,
    onClose: () -> Unit = {},
) {
    CameraDialogWindow(onDismiss = { if (status.busy) onCancel() else onClose() },
        properties = DialogProperties(dismissOnClickOutside = !status.busy),
        modifier = Modifier.testTag("Lens session recovery dialog"), header = {
            Text("Camera {lensName} failed to start", arguments = mapOf("lensName" to status.lensName),
                color = CameraColors.Text, fontWeight = FontWeight.SemiBold)
        }, footer = {
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!status.busy) CameraDialogAction("Retry", primary = true, onClick = onRetry)
                CameraDialogAction("Lens Settings", primary = status.busy, onClick = onLensSettings)
                if (status.busy) CameraDialogAction("Cancel", primary = false, onClick = onCancel)
                else CameraDialogAction("Close", primary = false, onClick = onClose)
            }
        }) {
        if (status.busy) {
            Text("Trying again with this lens profile’s settings.", color = CameraColors.Muted,
                fontSize = 13.sp, lineHeight = 19.sp)
            val progressHeight = with(LocalDensity.current) { 38.sp.toDp() }
            Row(Modifier.heightIn(min = progressHeight), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp).testTag("Lens session progress"),
                    color = CameraColors.Accent, strokeWidth = 2.dp)
                Text(when {
                    status.phase == LensSessionRecoveryPhase.WAITING ->
                        "Retry ${status.retriesStarted + 1} of 3 in ${status.retryInSeconds} " +
                            if (status.retryInSeconds == 1) "second…" else "seconds…"
                    status.manual -> "Configuring lens session…"
                    else -> "Retry ${status.retriesStarted} of 3: configuring…"
                }, modifier = Modifier.weight(1f).testTag("Lens session progress text"),
                    color = CameraColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
            }
        } else {
            Text("Lens session configuration failed", color = CameraColors.Text,
                fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("Try again, adjust this lens in Lens Profiles, or close this dialog to choose another lens.",
                color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        status.detail?.let {
            Text(it, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Text("Check the custom vendor overrides in this lens profile, or turn them off to use the regular session.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
