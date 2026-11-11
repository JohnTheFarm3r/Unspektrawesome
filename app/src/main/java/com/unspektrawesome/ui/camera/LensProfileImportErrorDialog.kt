package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LensProfileImportErrorDialog(message: String, onDismiss: () -> Unit) {
    CameraDialogWindow(onDismiss, header = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(SettingsSymbol.Info, Modifier.size(24.dp))
            Text("Cannot import lens profiles", color = CameraColors.Text, fontSize = 16.sp, lineHeight = 22.sp,
                modifier = Modifier.weight(1f))
        }
    }, footer = {
        LensToolAction("Close", Modifier.fillMaxWidth(), SettingsSymbol.Close, accent = true, onClick = onDismiss)
    }) {
        Text(message, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
