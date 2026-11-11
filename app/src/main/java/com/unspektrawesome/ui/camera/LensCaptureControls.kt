package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.RawFrameRateReport
import com.unspektrawesome.camera.RawFrameRateStore
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import com.unspektrawesome.language.languageSemantics
import java.util.Locale
import kotlin.math.roundToInt

internal typealias LensFrameRateCheck = suspend (CameraDescriptor, LensProfile) -> RawFrameRateReport

@Composable
internal fun LensCaptureControls(camera: CameraDescriptor, profile: LensProfile,
    onCheck: LensFrameRateCheck?, isActive: Boolean = true, onChange: (LensProfile) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { RawFrameRateStore(context) }
    var reportRevision by remember { mutableIntStateOf(0) }
    val report = remember(camera, profile, reportRevision) { store.load(camera, profile) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var showResults by remember(profile.id, camera.route.routeId) { mutableStateOf(false) }
    val active by rememberUpdatedState(isActive)
    val scope = rememberCoroutineScope()
    LaunchedEffect(isActive) {
        if (!isActive) {
            showResults = false
            failed = false
            scope.coroutineContext.cancelChildren()
        }
    }
    val capture = profile.capture
    var fpsExpanded by remember { mutableStateOf(false) }
    var ispExpanded by remember { mutableStateOf(false) }
    var labelExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (LocalCompactSettings.current) 10.dp else 14.dp)) {
        SettingsCard("Frame rate", symbol = SettingsSymbol.Video) {
            SettingsDropdownField("ISP COMPANION SURFACE", capture.ispSurface.label,
                null,
                ispExpanded, onExpand = { if (!checking) ispExpanded = true }, onDismiss = { ispExpanded = false }) {
                IspPreviewSurface.entries.forEach { mode ->
                    SettingsMenuChoice(mode.label, selected = mode == capture.ispSurface) {
                        onChange(profile.copy(capture = capture.copy(ispSurface = mode))); ispExpanded = false
                    }
                }
            }
            SettingsDropdownField("RAW FRAME RATE", capture.frameRate?.let { fps -> "$fps fps" } ?: "Lens default",
                null,
                fpsExpanded, onExpand = { if (!checking) fpsExpanded = true }, onDismiss = { fpsExpanded = false }) {
                SettingsMenuChoice("Lens default", selected = capture.frameRate == null) {
                    onChange(profile.copy(capture = capture.copy(frameRate = null))); fpsExpanded = false
                }
                (LensFrameRates.advertised(camera) + report?.supported.orEmpty()).distinct().sorted().forEach { rate ->
                    SettingsMenuChoice(com.unspektrawesome.language.AppLanguage.format("{fps} fps", mapOf("fps" to rate)), selected = capture.frameRate == rate) {
                        onChange(profile.copy(capture = capture.copy(frameRate = rate))); fpsExpanded = false
                    }
                }
            }
            ProfileNumberField("Custom requested FPS", capture.frameRate?.toString().orEmpty(), !checking,
                valid = { it.toIntOrNull() in 1..1000 }) { value ->
                onChange(profile.copy(capture = capture.copy(frameRate = value.toInt())))
            }
            ProfileNumberField("RAW request batch size", capture.requestBatchSize.toString(), !checking,
                valid = { it.toIntOrNull() in 1..32 }) { value ->
                onChange(profile.copy(capture = capture.copy(requestBatchSize = value.toInt())))
            }
            Text("Keep 1 unless the camera mode requires grouped RAW requests.",
                color = CameraColors.Muted, fontSize = 11.sp)
            if (onCheck != null) TextButton(enabled = isActive && !checking, modifier = Modifier.testTag("Check frame rates"), onClick = {
                scope.launch {
                    checking = true
                    failed = false
                    showResults = false
                    try {
                        onCheck(camera, profile)
                        reportRevision++
                        showResults = active
                    }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed = true }
                    finally { checking = false }
                }
            }) { Text(if (checking) "Checking RAW frame rates…" else "Check frame rates", color = CameraColors.Accent) }
            if (failed) Text("Frame rate check failed. Close other camera apps and try again.", color = CameraColors.FocusFailed, fontSize = 11.sp)
            report?.readings?.takeIf { showResults }?.forEach { reading ->
                val measured = reading.measured
                if (measured != null) Text("Requested {requested} fps: measured {measured} fps",
                    arguments = mapOf("requested" to reading.requested, "measured" to String.format(Locale.ROOT, "%.1f", measured)),
                    color = if (reading.supported) CameraColors.Accent else CameraColors.Muted, fontSize = 11.sp)
                else Text("Requested {requested} fps: unavailable", arguments = mapOf("requested" to reading.requested),
                    color = CameraColors.Muted, fontSize = 11.sp)
            }
        }
        SettingsCard("Frame averaging", symbol = SettingsSymbol.Layers) {
            ProfileNumberField("Source frames per output", capture.frameInterval.toString(), !checking,
                valid = { it.toIntOrNull() in 1..120 }) { value ->
                val interval = value.toInt()
                onChange(profile.copy(capture = capture.copy(frameInterval = interval, averageFrames = minOf(capture.averageFrames, interval))))
            }
            ProfileNumberField("Frames to average", capture.averageFrames.toString(), !checking,
                valid = { it.toIntOrNull() in 1..capture.frameInterval }) { value ->
                onChange(profile.copy(capture = capture.copy(averageFrames = value.toInt())))
            }
        }
        SettingsCard("Multi-frame merge", symbol = SettingsSymbol.Layers) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Multi-frame merge", modifier = Modifier.padding(end = 12.dp), color = CameraColors.Text, fontSize = 13.sp)
                CameraOverlayToggle(capture.mergeEnabled, "Multi-frame merge", !checking) { enabled ->
                    onChange(profile.copy(capture = capture.copy(mergeEnabled = enabled)))
                }
            }
            val mergeEnabled = !checking && capture.mergeEnabled
            MergeSliderSetting("Frames to merge", "{frames} frames", mapOf("frames" to capture.mergeFrames),
                capture.mergeFrames.toFloat(), mergeEnabled,
                valueRange = LensCaptureSettings.MERGE_FRAMES_MIN.toFloat()..LensCaptureSettings.MERGE_FRAMES_MAX.toFloat(), valueStep = 1f,
                hint = "More frames lower noise further but take longer to merge.") { value ->
                onChange(profile.copy(capture = capture.copy(mergeFrames = value.roundToInt()
                    .coerceIn(LensCaptureSettings.MERGE_FRAMES_MIN, LensCaptureSettings.MERGE_FRAMES_MAX))))
            }
            MergeSliderSetting("Motion sensitivity", "{value1}%", mapOf("value1" to (capture.mergeMotion * 100).roundToInt()),
                capture.mergeMotion, mergeEnabled, endpoints = "Lower noise" to "Less ghosting",
                hint = "Low values blend more frames for less noise, but moving subjects can ghost.") { value ->
                onChange(profile.copy(capture = capture.copy(mergeMotion = value.coerceIn(0f, 1f))))
            }
        }
        SettingsCard("Lens button", symbol = SettingsSymbol.Aperture) {
            ProfileNumberField("Focal length factor", profile.focalLength.factor.toString(), !checking,
                valid = { it.toFloatOrNull()?.let { factor -> factor.isFinite() && factor in .1f..20f } == true }) { value ->
                onChange(profile.copy(focalLength = profile.focalLength.copy(factor = value.toFloat())))
            }
            SettingsDropdownField("LENS BUTTON LABEL", profile.focalLength.label.label, null, labelExpanded,
                onExpand = { labelExpanded = true }, onDismiss = { labelExpanded = false }) {
                LensFocalLengthLabel.entries.forEach { label ->
                    SettingsMenuChoice(label.label, selected = label == profile.focalLength.label) {
                        onChange(profile.copy(focalLength = profile.focalLength.copy(label = label))); labelExpanded = false
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeSliderSetting(label: String, valueText: String, valueArguments: Map<String, Any>, value: Float, enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, valueStep: Float? = null,
    endpoints: Pair<String, String>? = null, hint: String, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SettingsLabel(label) }
            Text(valueText, arguments = valueArguments,
                color = if (enabled) CameraColors.Accent else CameraColors.Muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        CameraCompactSlider(value, onChange, enabled = enabled, thumbSize = 16.dp, requireThumb = false,
            valueRange = valueRange, valueStep = valueStep,
            modifier = Modifier.fillMaxWidth().languageSemantics { language -> contentDescription = language.text(label) })
        if (endpoints != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(endpoints.first, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            Text(endpoints.second, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
        }
        Text(hint, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun ProfileNumberField(label: String, value: String, enabled: Boolean,
    valid: (String) -> Boolean, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (!valid(text) || text.toDoubleOrNull() != value.toDoubleOrNull()) text = value
    }
    SettingsTextField(text, {
        text = it
        if (valid(it)) onChange(it)
    }, label, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(label))
}
