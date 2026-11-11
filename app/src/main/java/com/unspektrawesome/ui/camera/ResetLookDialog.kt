package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ResetLookDialog(rotation: Float, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    CameraDialogWindow(onDismiss, modifier = Modifier.testTag("Reset look dialog"), rotation = rotation,
        header = { Text("Reset look?", color = CameraColors.Text, fontWeight = FontWeight.SemiBold) }, footer = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CameraDialogAction("Cancel", primary = false, onClick = onDismiss)
                CameraDialogAction("Reset look", primary = true, onClick = onConfirm)
            }
        }) {
        Text("This restores all current spektrafilm settings to their defaults. Saved custom profiles will be kept.",
            color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
