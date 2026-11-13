package com.unspektrawesome.ui.camera

import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val installed = BuildConfig.VERSION_NAME.removeSuffix("-testing")
    // Shown over the camera too, so the window turns with the phone like the camera's other windows.
    val rotation = LocalCameraWindowRotation.current
    Dialog(onDismiss, properties = DialogProperties(dismissOnBackPress = !downloading, dismissOnClickOutside = !downloading,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CameraWindowBackdrop()
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
          CompositionLocalProvider(com.unspektrawesome.language.LocalOwnTextWidth provides true) {
            if (!downloading) Box(Modifier.matchParentSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss))
            RotatingCameraPanel(rotation, maxWidth = 360.dp) {
                DiagramWindow(Modifier.testTag("appUpdateDialog").pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    title = when (state.phase) {
                        UpdatePhase.CHECKING -> "Checking for updates"
                        UpdatePhase.CURRENT -> "You're up to date"
                        UpdatePhase.AVAILABLE -> "Update available"
                        UpdatePhase.DOWNLOADING -> "Downloading update"
                        UpdatePhase.READY -> "Ready to install"
                        UpdatePhase.ERROR -> "Could not check for updates"
                    }, eyebrow = "APP UPDATE", buttons = {
                        if (!downloading) {
                            if (state.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.ERROR)) {
                                DiagramButton("Later", onClick = onDismiss)
                                DiagramButton(when (state.phase) {
                                    UpdatePhase.READY -> "Install"
                                    UpdatePhase.ERROR -> "Retry"
                                    else -> "Download"
                                }, accent = true, onClick = onAction)
                            } else DiagramButton("Close", onClick = onDismiss)
                        }
                    }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val release = state.release
                        if (release != null) Text("Version {current} → {next}", arguments = mapOf("current" to installed,
                            "next" to release.version), color = Diagram.Hint, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = Diagram.Mono)
                        else Text("Version {value1}", arguments = mapOf("value1" to installed), color = Diagram.Hint, fontSize = 12.sp,
                            lineHeight = 16.sp, fontFamily = Diagram.Mono)
                        if (release != null && state.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.READY))
                            UpdatePath(installed, release.version, state.phase, state.progress)
                        release?.notes?.takeIf(String::isNotBlank)?.let { notes ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("WHAT'S NEW", color = CameraColors.Accent, fontSize = 10.sp, lineHeight = 14.sp,
                                    letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                                ReleaseNotes(notes)
                            }
                        }
                        if (state.phase == UpdatePhase.READY) DiagramNote("Android will ask you to confirm installation. Your photos and settings stay in place.")
                        state.message?.let { DiagramBody(it) }
                        if (state.phase == UpdatePhase.CHECKING) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = CameraColors.Accent,
                                trackColor = Diagram.Border)
                        }
                        if (downloading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.weight(1f).height(3.dp),
                                color = CameraColors.Accent, trackColor = Diagram.Border)
                            Text("{value1}%", arguments = mapOf("value1" to ((state.progress * 100).toInt())), color = CameraColors.Accent,
                                fontSize = 11.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono)
                        }
                    }
                }
            }
          }
        }
    }
}

/** Release notes with their Added, Changed and Fixed headings set apart from the entries. */
@Composable
private fun ReleaseNotes(notes: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        notes.lines().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val heading = line.startsWith("#") || (!line.startsWith("-") && !line.startsWith("*") && line.length <= 24 &&
                !line.endsWith("."))
            if (heading) Text(line.trimStart('#', ' '), translate = false, color = CameraColors.Text, fontSize = 12.sp,
                lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            else Text(line.removePrefix("-").removePrefix("*").trim(), translate = false, color = Color(0xFFB0B0B0), fontSize = 12.5.sp,
                lineHeight = 18.sp)
        }
    }
}

/** Installed version, the download and install steps, and the new version. */
@Composable
private fun UpdatePath(installed: String, next: String, phase: UpdatePhase, progress: Float) {
    val downloaded = phase == UpdatePhase.READY
    Row(Modifier.fillMaxWidth().height(52.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        VersionBox(installed, "installed", current = true)
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(Modifier.matchParentSize()) {
                val y = size.height / 2
                val download = size.width / 3
                val install = size.width * 2 / 3
                val stroke = 1.5.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                drawLine(CameraColors.Accent, Offset(0f, y), Offset(download, y), stroke)
                val reached = when (phase) {
                    UpdatePhase.READY -> install
                    UpdatePhase.DOWNLOADING -> download + (install - download) * progress.coerceIn(0f, 1f)
                    else -> download
                }
                if (reached > download) drawLine(CameraColors.Accent, Offset(download, y), Offset(reached, y), stroke)
                drawLine(Color(0xFF6A6A6A), Offset(reached, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
                listOf(download to true, install to downloaded).forEach { (x, on) ->
                    drawCircle(Color.Black, 4.dp.toPx(), Offset(x, y))
                    drawCircle(if (on) CameraColors.Accent else Color(0xFF6A6A6A), 4.dp.toPx(), Offset(x, y), style = Stroke(stroke))
                }
            }
            val third = maxWidth / 3
            Text("Download", color = if (downloaded) Diagram.Hint else CameraColors.Accent, fontSize = 8.5.sp, lineHeight = 11.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(x = third / 2).width(third).padding(top = 6.dp))
            Text("Install", color = if (downloaded) CameraColors.Accent else Diagram.Hint, fontSize = 8.5.sp, lineHeight = 11.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(x = third * 3 / 2).width(third).padding(top = 6.dp))
            Text("Android asks to confirm", color = Diagram.Hint, fontSize = 7.5.sp, lineHeight = 10.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).offset(x = third * 3 / 2).width(third))
        }
        VersionBox(next, "photos stay", current = false)
    }
}

@Composable
private fun VersionBox(version: String, caption: String, current: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.widthIn(min = 62.dp, max = 96.dp).then(if (current) Modifier.border(1.3.dp, Diagram.Ink, shape)
        else Modifier.dashedOutline(CameraColors.Accent)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(version, translate = false, color = if (current) CameraColors.Text else CameraColors.Accent, fontSize = 10.sp,
            lineHeight = 13.sp, fontFamily = Diagram.Mono, maxLines = 1)
        Text(caption, color = Diagram.Hint, fontSize = 7.5.sp, lineHeight = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}

private fun Modifier.dashedOutline(color: Color) = drawBehind {
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
}
