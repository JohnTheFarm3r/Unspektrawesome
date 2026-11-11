package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.settings.RawEdgeCrop

@Composable
internal fun RawCenterCropEditor(value: RawEdgeCrop, size: Size2d, onChange: (RawEdgeCrop) -> Unit) {
    val presets = listOf(1f, 1.5f, 2f, 2.5f, 3f)
    var expanded by remember { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(value.centerFactor !in presets) }
    var text by rememberSaveable { mutableStateOf(value.centerFactor.toString()) }
    val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..RawEdgeCrop.MAX_CENTER_FACTOR }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsDropdownField("SENSOR CROP", if (custom) "Custom" else if (value.centerFactor == 1f) "Off"
            else "${value.centerFactor}×", null, expanded, onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            presets.forEach { factor ->
                SettingsMenuChoice(if (factor == 1f) "Off" else "${factor}×", !custom && value.centerFactor == factor) {
                    custom = false
                    expanded = false
                    onChange(value.copy(centerFactor = factor))
                }
            }
            SettingsMenuChoice("Custom", custom) {
                custom = true
                text = value.centerFactor.toString()
                expanded = false
            }
        }
        if (custom) SettingsTextField(text, {
            if (it.length <= 6 && it.all { c -> c.isDigit() || c == '.' || c == ',' }) {
                text = it
                it.replace(',', '.').toFloatOrNull()?.takeIf { factor -> factor.isFinite() && factor in 1f..RawEdgeCrop.MAX_CENTER_FACTOR }
                    ?.let { factor -> onChange(value.copy(centerFactor = factor)) }
            }
        }, "Crop factor", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            error = if (parsed == null) "Enter a crop factor from 1 to 16." else null)
        Text("Crops the center of this profile's RAW stream before processing. Preview and saved photos use the same crop. A 2× crop keeps one quarter of the pixels.",
            color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
        val bounds = value.bounds(size)
        Text("Active RAW resolution: {value1} × {value2}", arguments = mapOf("value1" to bounds.width(), "value2" to bounds.height()),
            color = CameraColors.Accent, fontSize = 12.sp)
    }
}
