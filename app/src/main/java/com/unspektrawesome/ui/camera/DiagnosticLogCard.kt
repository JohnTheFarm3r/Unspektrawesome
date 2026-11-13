package com.unspektrawesome.ui.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.language.LocalAppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Size the recorder keeps before it starts over. */
private const val LOG_LIMIT_BYTES = 4L * 1024 * 1024

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
    Column(Modifier.fillMaxWidth()) {
        DiagramSection("INTERNAL LOG RECORDER")
        LogRow(1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(7.dp).background(if (state.recording) CameraColors.Accent else Diagram.Hint, CircleShape))
                if (state.recording) Text("Recording · {value1} KB / 4 MB", arguments = mapOf("value1" to state.bytes / 1024),
                    color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = Diagram.Mono)
                else Text("Starting recorder", color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp)
            }
            LogText("After a blank viewfinder, black photo or other issue, share a report here. Recent logs survive reopening the app.")
        }
        LogRow(2) {
            Text("Automatic logs: {path}", arguments = mapOf("path" to InternalLogRecorder.automaticLogPath()),
                color = Color(0xFFCFCFCF), fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        LogRow(3) {
            LogText("Session logs are saved while the app runs. Startup reports include previous crash details when Android makes them available. Older automatic logs are removed to limit storage use.")
        }
        LogRow(4) {
            LogText("Reports include device details, camera settings and app activity. Photos are excluded. Logs are saved on your phone and are never uploaded automatically.")
            val errors = listOfNotNull(state.storageError, state.readerError, state.automaticLogError, state.automaticReportError).distinct()
            if (errors.isNotEmpty()) Text(errors.joinToString("\n"), color = CameraColors.FocusFailed, fontSize = 11.sp, lineHeight = 15.sp)
            if (state.droppedLines > 0) Text("{droppedLines} log lines skipped while the recorder was busy.",
                arguments = mapOf("droppedLines" to (state.droppedLines)), color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        LogRow(5, divider = false) {
            DiagramButtonRow(Modifier.padding(vertical = 0.dp)) {
                DiagramButton("Share logs", enabled = !busy && pendingReport == null, symbol = SettingsSymbol.Export, onClick = { perform {
                    val report = InternalLogRecorder.saveReport()
                    context.startActivity(InternalLogRecorder.shareIntent(context, report))
                } })
                DiagramButton("Save logs", enabled = !busy && pendingReport == null, symbol = SettingsSymbol.Save, onClick = { perform {
                    val report = InternalLogRecorder.saveReport()
                    pendingReport = report.absolutePath
                    try { save.launch(report.name) } catch (error: Exception) { pendingReport = null; throw error }
                } })
                DiagramButton("Clear logs", enabled = !busy && pendingReport == null, symbol = SettingsSymbol.Delete, onClick = { perform {
                    InternalLogRecorder.clear()
                    message = "Local logs cleared. Recording continues."
                } })
            }
            if (busy) Text("Preparing logs…", color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
            message?.let { Text(it, color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp) }
        }
    }
}

@Composable
private fun LogRow(number: Int, divider: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (divider) Modifier.diagramHairline() else Modifier)
        .padding(horizontal = 4.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DiagramNumber(number)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun LogText(text: String) {
    Text(text, color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp)
}

/** How a report travels: the app writes session logs to a folder on the phone, and only you share or save them. */
@Composable
internal fun DiagnosticLogStage() {
    val state by InternalLogRecorder.state.collectAsState()
    val renderer = LocalAppLanguage.current.renderer
    val description = renderer.text("Reports include device details, camera settings and app activity. Photos are excluded. Logs are saved on your phone and are never uploaded automatically.")
    val fraction = (state.bytes.toFloat() / LOG_LIMIT_BYTES).coerceIn(0f, 1f)
    DiagramStage(206.dp, Modifier.clearAndSetSemantics { contentDescription = description }) {
        DiagramDrawing {
            // The phone running the app.
            box(14.dp, 38.dp, 50.dp, 92.dp, 9.dp)
            box(20.dp, 46.dp, 38.dp, 76.dp, 4.dp, Color(0xFF3A3A3A), 1.dp)
            listOf(24, 16, 21, 12, 18).forEachIndexed { index, width ->
                line(26.dp, (58 + index * 8).dp, (26 + width).dp, (58 + index * 8).dp, Diagram.Line, 1.dp)
            }
            dot(39.dp, 108.dp, 4.dp, if (state.recording) CameraColors.Accent else Diagram.Hint)
            arrow(66.dp, 76.dp, 86.dp, 76.dp, CameraColors.Accent)
            // Session logs filling up to the limit.
            box(90.dp, 52.dp, 92.dp, 46.dp)
            box(98.dp, 80.dp, 76.dp, 6.dp, 3.dp, Color(0xFF3A3A3A), 1.dp)
            if (fraction > 0f) rect(98.dp, 80.dp, (76f * fraction).coerceAtLeast(3f).dp, 6.dp, CameraColors.Accent)
            arrow(184.dp, 76.dp, 206.dp, 76.dp, CameraColors.Accent)
            // Folder on the phone.
            polyline(listOf(210.dp to 56.dp, 244.dp to 56.dp, 250.dp to 62.dp, 322.dp to 62.dp, 322.dp to 102.dp,
                210.dp to 102.dp, 210.dp to 56.dp))
            polyline(listOf(226.dp to 102.dp, 226.dp to 124.dp, 143.dp to 124.dp, 143.dp to 140.dp), CameraColors.Accent)
            line(219.dp, 124.dp, 219.dp, 140.dp, CameraColors.Accent)
            box(104.dp, 140.dp, 76.dp, 22.dp, 11.dp, Diagram.Ink, 1.2.dp)
            box(186.dp, 140.dp, 66.dp, 22.dp, 11.dp, Diagram.Ink, 1.2.dp)
            // Nothing goes to the cloud.
            line(300.dp, 102.dp, 300.dp, 128.dp, Color(0xFF6A6A6A), 1.dp, dashed = true)
            box(283.dp, 140.dp, 36.dp, 20.dp, 10.dp, Color(0xFF6A6A6A), 1.dp, dashed = true)
            circle(298.dp, 140.dp, 8.dp, Color(0xFF6A6A6A), 1.dp, dashed = true)
            line(278.dp, 166.dp, 318.dp, 130.dp, Color(0xFFCFCFCF))
            // Photos stay out of reports.
            box(14.dp, 176.dp, 18.dp, 14.dp, 2.dp, Color(0xFF6A6A6A), 1.dp)
            polyline(listOf(16.dp to 188.dp, 21.dp to 183.dp, 25.dp to 186.dp, 28.dp to 183.dp, 31.dp to 186.dp), Color(0xFF6A6A6A), 1.dp)
            line(12.dp, 192.dp, 34.dp, 174.dp, Color(0xFFCFCFCF))
        }
        DiagramLabel("App", null, Modifier.at(12.dp, 12.dp), maxWidth = 70.dp)
        Column(Modifier.at(90.dp, 12.dp).widthIn(max = 116.dp)) {
            DiagramLabel("Recording", null)
            Text(if (state.recording) "{value1} KB / 4 MB" else "Starting recorder",
                arguments = if (state.recording) mapOf("value1" to state.bytes / 1024) else null, color = CameraColors.Accent,
                fontSize = 11.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.at(212.dp, 12.dp).widthIn(max = 116.dp)) {
            DiagramLabel("Automatic logs", null)
            Text("On your phone", color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        StageText("Session logs", 98.dp, 60.dp, 80.dp, CameraColors.Text, 9.5f, FontWeight.Medium)
        Text(InternalLogRecorder.automaticLogPath(), translate = false, color = Color(0xFFCFCFCF), fontSize = 7.5.sp,
            lineHeight = 10.sp, fontFamily = Diagram.Mono, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.at(216.dp, 66.dp).width(102.dp))
        StageText("Share logs", 104.dp, 145.dp, 76.dp, CameraColors.Text, 9f, align = TextAlign.Center)
        StageText("Save logs", 186.dp, 145.dp, 66.dp, CameraColors.Text, 9f, align = TextAlign.Center)
        StageText("Photos excluded", 40.dp, 178.dp, 136.dp, Diagram.Hint, 9f)
        // Right-aligned under the crossed-out cloud, wide enough for two full lines.
        Box(Modifier.at(184.dp, 170.dp).width(146.dp), contentAlignment = Alignment.TopEnd) {
            DiagramLabel("Never uploaded automatically", null, maxWidth = 146.dp)
        }
        DiagramNumber(3, Modifier.at(55.dp, 30.dp))
        DiagramNumber(1, Modifier.at(81.dp, 44.dp))
        DiagramNumber(2, Modifier.at(201.dp, 47.dp))
        DiagramNumber(5, Modifier.at(95.dp, 117.dp))
        DiagramNumber(4, Modifier.at(269.dp, 122.dp))
    }
}

@Composable
private fun StageText(text: String, x: Dp, y: Dp, width: Dp, color: Color, size: Float, weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start) {
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 3).sp, fontWeight = weight, textAlign = align, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.at(x, y).width(width))
}
