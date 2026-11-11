package com.unspektrawesome.ui.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.unspektrawesome.ui.camera.CameraAlertDialog as AlertDialog
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.calibration.DcpRepository
import com.unspektrawesome.settings.LensColorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LensColorCalibrationEditor(
    settings: LensColorSettings,
    deviceMatrixAvailable: Boolean,
    lensShadingAvailable: Boolean,
    lensShadingAppliedInRaw: Boolean,
    onChange: (LensColorSettings) -> Unit,
    onImporting: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { DcpRepository(context) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentSettings by rememberUpdatedState(settings)
    val currentChange by rememberUpdatedState(onChange)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            onImporting(true)
            try {
                val imported = withContext(Dispatchers.IO) { repository.import(uri) }
                currentChange(currentSettings.copy(dcpId = imported.id, dcpName = imported.fileName, dcpEnabled = true))
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = failure.message ?: "The DCP could not be imported."
            } finally {
                importing = false
                onImporting(false)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Lens shading", color = Color.White, fontSize = 13.sp)
                if (!lensShadingAvailable) Text("Not available for this lens.",
                    color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                if (lensShadingAppliedInRaw) Text("Correction built into this lens's RAW images cannot be disabled.",
                    color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (lensShadingAvailable) CameraOverlayToggle(settings.lensShadingEnabled, "Lens shading") {
                onChange(settings.copy(lensShadingEnabled = it))
            }
        }
        SettingsDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Color matrix", color = Color.White, fontSize = 13.sp)
                if (!deviceMatrixAvailable) Text("Device calibration is missing. Import a DCP to calibrate this lens.",
                    color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            CameraOverlayToggle(settings.matrixEnabled, "Color matrix") { onChange(settings.copy(matrixEnabled = it)) }
        }
        SettingsDivider()
        if (settings.dcpId != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Custom DCP", color = Color.White, fontSize = 13.sp)
                    if (settings.dcpName != null) Text(settings.dcpName, translate = false, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    else Text("Imported DCP", color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
                CameraOverlayToggle(settings.dcpEnabled, "Use custom DCP") { onChange(settings.copy(dcpEnabled = it)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(if (importing) "Importing…" else if (settings.dcpId == null) "Import DCP" else "Replace DCP",
                enabled = !importing, symbol = SettingsSymbol.DcpImport) { importer.launch(arrayOf("*/*")) }
            if (settings.dcpId != null) SettingsButton("Remove DCP", enabled = !importing, symbol = SettingsSymbol.Erase) {
                onChange(settings.copy(dcpId = null, dcpName = null, dcpEnabled = false))
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, containerColor = CameraColors.Background,
        title = { CameraWindowBackdrop(); Text("DCP import failed", color = Color.White) },
        text = { Text(error.orEmpty(), color = CameraColors.Muted) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK", color = CameraColors.Accent) } })
}
