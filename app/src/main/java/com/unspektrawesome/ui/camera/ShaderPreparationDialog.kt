package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.vulkan.ShaderPreparationState

@Composable
internal fun ShaderPreparationDialog(state: ShaderPreparationState, onHide: () -> Unit) {
    var confirmHide by rememberSaveable { mutableStateOf(false) }
    CameraDialogWindow(onDismiss = { if (confirmHide) confirmHide = false else confirmHide = true },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag("Shader preparation dialog"), header = {
            Text(when {
                state.failed -> "Shader preparation incomplete"
                confirmHide -> "Hide shader preparation?"
                else -> "Compiling shaders"
            }, color = CameraColors.Text, fontWeight = FontWeight.SemiBold)
        }, footer = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    state.failed -> CameraDialogAction("Continue", primary = true, onClick = onHide)
                    confirmHide -> {
                        CameraDialogAction("Keep waiting", primary = true, onClick = { confirmHide = false })
                        CameraDialogAction("Continue anyway", primary = false, onClick = onHide)
                    }
                    else -> CameraDialogAction("Hide", primary = false, onClick = { confirmHide = true })
                }
            }
        }) {
            if (state.failed) {
                Text("Some shaders could not be prepared. The app will try again when they are needed.",
                    color = CameraColors.Muted, fontSize = 14.sp, lineHeight = 21.sp)
            } else if (confirmHide) {
                Text("Shader compilation uses a lot of resources. If you continue now, the camera may be slow and taking photos may be unavailable until compilation finishes.",
                    color = CameraColors.Muted, fontSize = 14.sp, lineHeight = 21.sp)
            } else {
                Text("The app is preparing shaders after an update or a change to its cache. This can take from a few seconds up to 1-2 minutes, depending on your device's SoC performance.",
                    color = CameraColors.Muted, fontSize = 14.sp, lineHeight = 21.sp)
                val fraction = state.completed.toFloat() / state.total.coerceAtLeast(1)
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                    color = CameraColors.Accent, trackColor = CameraColors.MenuCard)
                Text("{percent}% complete", arguments = mapOf("percent" to (fraction * 100).toInt()),
                    color = CameraColors.Accent, fontSize = 14.sp)
            }
    }
}
