package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.settings.RawEdgeCrop
import com.unspektrawesome.settings.RawLayoutOverride
import com.unspektrawesome.settings.RawStreamSelection

@Composable
internal fun RawLayoutOverrideEditor(initial: RawLayoutOverride, stream: RawStreamSelection,
    edgeCrop: RawEdgeCrop, onChange: (RawLayoutOverride) -> Unit) {
    var enabled by rememberSaveable(initial) { mutableStateOf(initial.enabled) }
    var width by rememberSaveable(initial) { mutableStateOf(initial.width?.toString().orEmpty()) }
    var height by rememberSaveable(initial) { mutableStateOf(initial.height?.toString().orEmpty()) }
    var stride by rememberSaveable(initial) { mutableStateOf(initial.rowStrideBytes?.toString().orEmpty()) }
    var tightStride by rememberSaveable(initial) { mutableStateOf(initial.tightStride) }
    val reported = Size2d(stream.width, stream.height)
    val malformed = (listOf(width, height) + if (tightStride) emptyList() else listOf(stride)).any { it.isNotBlank() && it.toIntOrNull() == null }
    val candidate = if (enabled) RawLayoutOverride(true, width.toIntOrNull(), height.toIntOrNull(), stride.toIntOrNull(), tightStride)
        else initial.copy(enabled = false)
    val error = when {
        enabled && malformed -> "Enter whole numbers, or leave a field blank for Auto."
        candidate.validationError(stream.format, reported) != null -> candidate.validationError(stream.format, reported)
        !edgeCrop.isValid(candidate.size(reported)) -> "Reduce the RAW edge crop before applying these dimensions."
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RAW LAYOUT OVERRIDE", color = CameraColors.Accent, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CameraOverlayToggle(enabled, "Override RAW layout") { enabled = it }
        }
        if (enabled) {
            SettingsFieldPair(
                first = { modifier -> RawLayoutField("Width (pixels)", width, modifier) { width = it } },
                second = { modifier -> RawLayoutField("Height (pixels)", height, modifier) { height = it } },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tight row stride", color = CameraColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                CameraOverlayToggle(tightStride, "Tight row stride") { tightStride = it }
            }
            if (!tightStride) RawLayoutField("Row stride (bytes)", stride, Modifier.fillMaxWidth()) { stride = it }
            val columns = width.toIntOrNull() ?: stream.width
            if (columns in 2..65536) {
                val minimum = RawLayoutOverride.minimumStride(stream.format, columns)
                Text("Minimum row stride: {bytes} bytes", arguments = mapOf("bytes" to minimum),
                    color = CameraColors.Muted, fontSize = 11.sp)
                CameraOverlayAction("Align stride to 64 bytes") {
                    stride = (((minimum + 63) / 64) * 64).toString()
                    tightStride = false
                }
            }
        }
        if (error != null) Text(error, color = CameraColors.FocusFailed, fontSize = 11.sp, lineHeight = 15.sp)
        if (candidate != initial) {
            CameraOverlayAction("Apply RAW layout", enabled = error == null,
                modifier = Modifier.testTag("Apply RAW layout")) { onChange(candidate) }
        }
    }
}

@Composable
private fun RawLayoutField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    SettingsTextField(value, { if (it.length <= 10 && it.all(Char::isDigit)) onChange(it) }, label, modifier,
        placeholder = "Auto", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
