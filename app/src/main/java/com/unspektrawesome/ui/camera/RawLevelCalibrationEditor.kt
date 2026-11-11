package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.SensorMetadata
import com.unspektrawesome.settings.RawLevelCalibration
import com.unspektrawesome.settings.RawLevelMode

@Composable
internal fun RawLevelCalibrationEditor(
    initial: RawLevelCalibration,
    sensor: SensorMetadata,
    onChange: (RawLevelCalibration?) -> Unit,
) {
    val seed = remember {
        if (initial == RawLevelCalibration()) {
            val black = sensor.blackLevel()
            initial.copy(white = sensor.whiteLevel, black00 = black[0], black01 = black[1], black10 = black[2], black11 = black[3])
        } else initial
    }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var white by rememberSaveable { mutableStateOf(seed.white.toString()) }
    var black00 by rememberSaveable { mutableStateOf(levelText(seed.black00)) }
    var black01 by rememberSaveable { mutableStateOf(levelText(seed.black01)) }
    var black10 by rememberSaveable { mutableStateOf(levelText(seed.black10)) }
    var black11 by rememberSaveable { mutableStateOf(levelText(seed.black11)) }
    var expanded by remember { mutableStateOf(false) }
    val parsedWhite = white.toIntOrNull()
    val blacks = listOf(black00, black01, black10, black11).map { it.toFloatOrNull() }
    val parsed = if (parsedWhite != null && blacks.all { it != null && it.isFinite() }) RawLevelCalibration(
        mode, parsedWhite, blacks[0]!!, blacks[1]!!, blacks[2]!!, blacks[3]!!,
    ) else if (mode != RawLevelMode.CUSTOM) seed.copy(mode = mode) else null
    val valid = parsed?.isValid() == true
    LaunchedEffect(parsed, valid) { onChange(parsed.takeIf { valid }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsDropdownField("BLACK / WHITE LEVELS", mode.label,
            null, expanded,
            onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            RawLevelMode.entries.forEach { value ->
                SettingsMenuChoice(value.label, selected = value == mode) { mode = value; expanded = false }
            }
        }
        AnimatedVisibility(mode == RawLevelMode.CUSTOM) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Black · sensor 2×2 order", color = CameraColors.Muted, fontSize = 11.sp)
                SettingsFieldPair(
                    first = { modifier -> LevelField("Top left", black00, modifier) { black00 = it } },
                    second = { modifier -> LevelField("Top right", black01, modifier) { black01 = it } },
                )
                SettingsFieldPair(
                    first = { modifier -> LevelField("Bottom left", black10, modifier) { black10 = it } },
                    second = { modifier -> LevelField("Bottom right", black11, modifier) { black11 = it } },
                )
                LevelField("White", white, Modifier.fillMaxWidth(), wholeNumber = true) { white = it }
            }
        }
        if (!valid) Text("White must be 1–{MAX_WHITE_LEVEL}. Each black value must be nonnegative and lower than white.", arguments = mapOf("MAX_WHITE_LEVEL" to (RawLevelCalibration.MAX_WHITE_LEVEL)),
            color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

private fun levelText(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

@Composable
private fun LevelField(label: String, value: String, modifier: Modifier, wholeNumber: Boolean = false, onChange: (String) -> Unit) {
    SettingsTextField(value, onChange, label, modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (wholeNumber) KeyboardType.Number else KeyboardType.Decimal))
}
