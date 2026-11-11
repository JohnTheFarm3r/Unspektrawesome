package com.unspektrawesome.ui.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.diagnostics.InternalLogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun DiagnosticLogCard() {
    val context = LocalContext.current
    val state by InternalLogRecorder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingReport by rememberSaveable { mutableStateOf<String?>(null) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = "Unable to complete this action: ${error.cause?.message ?: error.message}" }
            finally { busy = false }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val path = pendingReport
        pendingReport = null
        if (uri != null && path != null) perform {
            InternalLogRecorder.exportReport(File(path), uri)
            message = "Diagnostic report saved."
        }
    }
    SettingsCard("INTERNAL LOG RECORDER", symbol = SettingsSymbol.Logs) {
            Text(if (state.recording) "Recording · ${state.bytes / 1024} KB / 4 MB" else "Starting recorder",
                color = CameraColors.Accent, fontSize = 12.sp)
            Text("After a blank viewfinder, black photo or other issue, share a report here. Recent logs survive reopening the app.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text("Automatic logs: {path}", arguments = mapOf("path" to InternalLogRecorder.automaticLogPath()),
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text("Session logs are saved while the app runs. Startup reports include previous crash details when Android makes them available. Older automatic logs are removed to limit storage use.",
                color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Text("Reports include device details, camera settings and app activity. Photos are excluded. Logs are saved on your phone and are never uploaded automatically.",
                color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
            val errors = listOfNotNull(state.storageError, state.readerError, state.automaticLogError, state.automaticReportError).distinct()
            if (errors.isNotEmpty()) Text(errors.joinToString("\n"), color = CameraColors.Muted, fontSize = 11.sp)
            if (state.droppedLines > 0) Text("{droppedLines} log lines skipped while the recorder was busy.", arguments = mapOf("droppedLines" to (state.droppedLines)), color = CameraColors.Muted, fontSize = 11.sp)
            SettingsFieldPair(first = { modifier ->
                SettingsButton("Share logs", modifier, enabled = !busy && pendingReport == null, symbol = SettingsSymbol.Export, onClick = { perform {
                    val report = InternalLogRecorder.saveReport()
                    context.startActivity(InternalLogRecorder.shareIntent(context, report))
                } })
            }, second = { modifier ->
                SettingsButton("Save logs", modifier, enabled = !busy && pendingReport == null, symbol = SettingsSymbol.Save, onClick = { perform {
                    val report = InternalLogRecorder.saveReport()
                    pendingReport = report.absolutePath
                    try { save.launch(report.name) } catch (error: Exception) { pendingReport = null; throw error }
                } })
            })
            SettingsButton("Clear logs", Modifier.fillMaxWidth(), enabled = !busy && pendingReport == null,
                symbol = SettingsSymbol.Delete, onClick = { perform {
                InternalLogRecorder.clear()
                message = "Local logs cleared. Recording continues."
            } })
            if (busy) Text("Preparing logs…", color = CameraColors.Muted, fontSize = 11.sp)
            message?.let { Text(it, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp) }
    }
}
