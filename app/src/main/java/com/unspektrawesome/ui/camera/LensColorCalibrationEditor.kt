package com.unspektrawesome.ui.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    Column(Modifier.fillMaxWidth()) {
        ColorChainStage(settings, lensShadingAvailable, lensShadingAppliedInRaw)
        if (lensShadingAvailable) SensorArtSwitchRow("Lens shading", settings.lensShadingEnabled, number = 1,
            detail = if (lensShadingAppliedInRaw) "Correction built into this lens's RAW images cannot be disabled." else null) {
            onChange(settings.copy(lensShadingEnabled = it))
        } else {
            DiagramRow("Lens shading", detail = "Not available for this lens.", number = 1, divider = !lensShadingAppliedInRaw)
            if (lensShadingAppliedInRaw) DiagramHint("Correction built into this lens's RAW images cannot be disabled.",
                Modifier.diagramHairline())
        }
        SensorArtSwitchRow("Color matrix", settings.matrixEnabled, number = 2,
            detail = if (!deviceMatrixAvailable) "Device calibration is missing. Import a DCP to calibrate this lens." else null) {
            onChange(settings.copy(matrixEnabled = it))
        }
        if (settings.dcpId != null) SensorArtSwitchRow("Custom DCP", settings.dcpEnabled, number = 3,
            detail = settings.dcpName ?: "Imported DCP", translateDetail = settings.dcpName == null, toggleLabel = "Use custom DCP") {
            onChange(settings.copy(dcpEnabled = it))
        }
        DiagramButtonRow {
            DiagramButton(if (importing) "Importing…" else if (settings.dcpId == null) "Import DCP" else "Replace DCP",
                enabled = !importing, symbol = SettingsSymbol.DcpImport) { importer.launch(arrayOf("*/*")) }
            if (settings.dcpId != null) DiagramButton("Remove DCP", enabled = !importing, symbol = SettingsSymbol.Erase) {
                onChange(settings.copy(dcpId = null, dcpName = null, dcpEnabled = false))
            }
        }
    }
    if (error != null) DiagramDialog("DCP import failed", onDismiss = { error = null },
        buttons = { DiagramButton("OK", accent = true) { error = null } }) {
        DiagramBody(error.orEmpty())
    }
}

/**
 * The path from RAW to colour. Lens shading and the colour transform are set here; the first enabled transform
 * of custom DCP and colour matrix is used.
 */
@Composable
private fun ColorChainStage(settings: LensColorSettings, shadingAvailable: Boolean, shadingInRaw: Boolean) {
    val dcp = settings.dcpId != null
    val dcpOn = dcp && settings.dcpEnabled
    val matrixOn = !dcpOn && settings.matrixEnabled
    val bothOff = !dcpOn && !matrixOn
    val shadingOn = shadingAvailable && settings.lensShadingEnabled
    val shadingDetail = when {
        !shadingAvailable -> "Not available"
        shadingOn -> "On · lens map"
        shadingInRaw -> "Built into RAW"
        else -> "Off"
    }
    val transformDetail = when {
        dcpOn -> "Custom DCP in use"
        matrixOn -> "Device color matrix"
        else -> "Color matrix off"
    }
    val steps = listOf(
        DiagramStep("Sensor RAW", state = DiagramNodeState.Done),
        DiagramStep("Black / white levels", state = DiagramNodeState.Done),
        DiagramStep("Lens shading", shadingDetail, if (shadingOn) DiagramNodeState.Active else DiagramNodeState.Off),
        DiagramStep("Demosaic · white balance", "set outside this card", DiagramNodeState.Off),
        DiagramStep("Color transform", transformDetail, if (bothOff) DiagramNodeState.Off else DiagramNodeState.Active),
        DiagramStep("spektrafilm look", "film simulation", DiagramNodeState.Off),
        DiagramStep("Viewfinder and photos", "same color in both", DiagramNodeState.Done),
    )
    // Nodes are 46 dp apart so a title or detail can wrap to a second line without reaching the next node.
    fun top(index: Int) = (26 + index * 46).dp
    val transformCenter = top(4) + 16.dp
    val options = listOf(
        Triple(if (!dcp) "No custom DCP" else if (settings.dcpEnabled) "Custom DCP · On" else "Custom DCP · Off", dcpOn, 182),
        Triple(if (settings.matrixEnabled) "Color matrix · On" else "Color matrix · Off", matrixOn, 220),
        Triple("Both off", bothOff, 258),
    )
    SensorArtStage(372.dp, "RAW to color") {
        DiagramDrawing {
            (0 until steps.lastIndex).forEach { index ->
                val next = steps[index + 1].state
                arrow(44.dp, top(index) + 32.dp, 44.dp, top(index + 1) - 1.dp,
                    if (next == DiagramNodeState.Off) SensorArt.Dim else CameraColors.Accent, dashed = next == DiagramNodeState.Off)
            }
            options.forEach { (_, on, y) ->
                box(196.dp, y.dp, 128.dp, 30.dp, 5.dp, if (on) CameraColors.Accent else SensorArt.Dim, 1.3.dp, dashed = !on,
                    fill = SensorArt.Fill)
                arrow(196.dp, (y + 15).dp, 177.dp, transformCenter, if (on) CameraColors.Accent else SensorArt.Dim, dashed = !on)
            }
            callout(174.dp, top(2) + 16.dp, 192.dp, 76.dp, shadingOn)
            line(14.dp, 352.dp, 30.dp, 352.dp, CameraColors.Accent)
            line(84.dp, 352.dp, 100.dp, 352.dp, SensorArt.Dim, dashed = true)
        }
        DiagramLabel("RAW to color", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 160.dp)
        steps.forEachIndexed { index, step ->
            DiagramNode(step.title, Modifier.at(14.dp, top(index)), step.detail, step.state, width = 160.dp)
        }
        SensorArtMarker(1, 166.dp, top(2) - 8.dp, shadingAvailable)
        Column(Modifier.at(196.dp, 62.dp).width(130.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            DiagramLabel("Lens shading", shadingDetail, active = shadingOn, maxWidth = 130.dp)
            DiagramText("Evens out darker, tinted corners using the lens's own map", size = 9f, maxWidth = 130.dp)
        }
        DiagramLabel("First one on wins", modifier = Modifier.at(196.dp, 154.dp), maxWidth = 128.dp)
        options.forEach { (label, on, y) ->
            Box(Modifier.at(196.dp, y.dp).size(128.dp, 30.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                DiagramText(label, color = if (on) CameraColors.Accent else SensorArt.Soft, size = 9.5f,
                    weight = FontWeight.SemiBold, maxWidth = 106.dp)
            }
        }
        if (dcp) SensorArtMarker(3, 314.dp, 174.dp, dcpOn)
        SensorArtMarker(2, 314.dp, 212.dp, matrixOn)
        if (dcp) DiagramText("While on, the Custom DCP replaces the device matrix.", Modifier.at(196.dp, 296.dp), size = 9f,
            maxWidth = 128.dp, maxLines = 3)
        DiagramText("in use", Modifier.at(34.dp, 346.dp), size = 9f, maxWidth = 46.dp)
        DiagramText("standing by", Modifier.at(104.dp, 346.dp), size = 9f, maxWidth = 80.dp)
    }
}
