package com.unspektrawesome.ui.camera

import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.update.UpdateController
import com.unspektrawesome.update.UpdatePhase
import com.unspektrawesome.update.UpdateState

@Composable
internal fun rememberUpdateController(): UpdateController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { UpdateController(context, scope) }
}

@Composable
internal fun AppUpdateHost(controller: UpdateController, autoCheck: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(autoCheck) {
        if (autoCheck && BuildConfig.UPDATES_ENABLED) controller.check(manual = false)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) controller.install()
        else controller.showMessage("Allow updates from Unspektrawesome in Android settings, then tap Install again.")
    }
    if (controller.visible) {
        AppUpdateDialog(controller.state, onDismiss = controller::dismiss, onAction = {
            when (controller.state.phase) {
                UpdatePhase.AVAILABLE -> controller.download()
                UpdatePhase.ERROR -> controller.check(manual = true)
                UpdatePhase.READY -> {
                    if (context.packageManager.canRequestPackageInstalls()) controller.install()
                    else try {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri()))
                    } catch (_: Exception) {
                        controller.showMessage("Open Android settings and allow Unspektrawesome to install updates.")
                    }
                }
                else -> controller.dismiss()
            }
        })
    }
}

@Composable
internal fun AppUpdateDialog(state: UpdateState, onDismiss: () -> Unit, onAction: () -> Unit) {
    val downloading = state.phase == UpdatePhase.DOWNLOADING
    CameraDialogWindow(onDismiss, properties = DialogProperties(
        dismissOnBackPress = !downloading, dismissOnClickOutside = !downloading),
        modifier = Modifier.testTag("appUpdateDialog"), header = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("APP UPDATE", color = CameraColors.Accent, fontSize = 10.sp,
                letterSpacing = .8.sp, fontWeight = FontWeight.Bold)
            Text(when (state.phase) {
                UpdatePhase.CHECKING -> "Checking for updates"
                UpdatePhase.CURRENT -> "You're up to date"
                UpdatePhase.AVAILABLE -> "Update available"
                UpdatePhase.DOWNLOADING -> "Downloading update"
                UpdatePhase.READY -> "Ready to install"
                UpdatePhase.ERROR -> "Could not check for updates"
            }, color = CameraColors.Text, fontWeight = FontWeight.SemiBold)
        }
        }, footer = {
            if (!downloading) FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.ERROR)) {
                    UpdateButton("Later", false, onDismiss)
                    UpdateButton(when (state.phase) {
                        UpdatePhase.READY -> "Install"
                        UpdatePhase.ERROR -> "Retry"
                        else -> "Download"
                    }, true, onAction)
                } else UpdateButton("Close", false, onDismiss)
            }
        }) {
                Text(state.release?.let { "Version ${BuildConfig.VERSION_NAME.removeSuffix("-testing")} → ${it.version}" }
                    ?: "Version ${BuildConfig.VERSION_NAME.removeSuffix("-testing")}",
                    color = CameraColors.Muted, fontSize = 13.sp)
                state.release?.notes?.takeIf(String::isNotBlank)?.let { notes ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(CameraColors.MenuCard)
                        .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WHAT'S NEW", color = CameraColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(notes, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                if (state.phase == UpdatePhase.READY) Text("Android will ask you to confirm installation. Your photos and settings stay in place.",
                    color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
                state.message?.let { Text(it, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp) }
            if (state.phase == UpdatePhase.CHECKING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CameraColors.Accent,
                    trackColor = CameraColors.MenuCard)
            }
            if (downloading) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(),
                    color = CameraColors.Accent, trackColor = CameraColors.MenuCard)
                Text("{value1}%", arguments = mapOf("value1" to ((state.progress * 100).toInt())), color = CameraColors.Muted, fontSize = 12.sp)
            }
    }
}

@Composable
private fun UpdateButton(label: String, primary: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = CameraStyle.ActionShape, modifier = Modifier.heightIn(min = 44.dp),
        border = BorderStroke(1.dp, if (primary) CameraColors.Accent else CameraColors.MenuBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (primary) CameraColors.MenuSelectedCard else CameraColors.MenuCard,
            contentColor = if (primary) CameraColors.Accent else CameraColors.Muted)) {
        Text(label, fontSize = 13.sp)
    }
}
