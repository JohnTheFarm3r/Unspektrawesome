package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.SensorMetadata
import com.unspektrawesome.language.LocalAppLanguage
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
    val sensorBlack = remember(sensor) { sensor.blackLevel().toList() }
    val shownWhite = when (mode) {
        RawLevelMode.DYNAMIC -> sensor.whiteLevel
        RawLevelMode.CUSTOM -> parsedWhite
        else -> mode.white
    }
    val shownBlack = when (mode) {
        RawLevelMode.DYNAMIC -> sensorBlack
        RawLevelMode.CUSTOM -> blacks
        else -> List(4) { mode.black }
    }
    Column(Modifier.fillMaxWidth()) {
        LevelScaleStage(mode, shownWhite, shownBlack)
        DiagramDropdownField("BLACK / WHITE LEVELS", mode.label,
            null, expanded, number = 1,
            onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            RawLevelMode.entries.forEach { value ->
                DiagramMenuItem(value.label, selected = value == mode) { mode = value; expanded = false }
            }
        }
        AnimatedVisibility(mode == RawLevelMode.CUSTOM) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagramNumber(2)
                    Text("Black · sensor 2×2 order", color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
                }
                SettingsFieldPair(
                    first = { modifier -> LevelField("Top left", black00, modifier) { black00 = it } },
                    second = { modifier -> LevelField("Top right", black01, modifier) { black01 = it } },
                )
                SettingsFieldPair(
                    first = { modifier -> LevelField("Bottom left", black10, modifier) { black10 = it } },
                    second = { modifier -> LevelField("Bottom right", black11, modifier) { black11 = it } },
                )
                LevelField("White", white, Modifier.fillMaxWidth(), wholeNumber = true, number = 3) { white = it }
            }
        }
        if (!valid) Text("White must be 1–{MAX_WHITE_LEVEL}. Each black value must be nonnegative and lower than white.",
            Modifier.padding(horizontal = 4.dp, vertical = 6.dp), arguments = mapOf("MAX_WHITE_LEVEL" to (RawLevelCalibration.MAX_WHITE_LEVEL)),
            color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/**
 * The RAW value scale from no light to clipping, with the black range hatched, and the black level of each
 * position of the sensor's 2×2 colour group.
 */
@Composable
private fun LevelScaleStage(mode: RawLevelMode, white: Int?, blacks: List<Float?>) {
    val renderer = LocalAppLanguage.current.renderer
    val dynamic = mode == RawLevelMode.DYNAMIC
    val accent = if (dynamic) Diagram.Hint else CameraColors.Accent
    val known = blacks.filterNotNull()
    val highest = known.maxOrNull()
    val scaleWhite = white?.takeIf { it > 0 }
    val blackX = if (scaleWhite != null && highest != null) 14f + 286f * (highest / scaleWhite).coerceIn(0f, 1f) else 14f
    val blackText = when {
        known.size < 4 || highest == null -> renderer.format("Black {value}", mapOf("value" to "?"))
        known.all { it == highest } -> renderer.format("Black {value}", mapOf("value" to levelText(highest)))
        else -> renderer.format("Black {low} to {high}", mapOf("low" to levelText(known.min()), "high" to levelText(highest)))
    }
    val positions = listOf("Top left", "Top right", "Bottom left", "Bottom right")
    SensorArtStage(184.dp, "RAW value scale") {
        DiagramDrawing {
            sensorHatch(14.dp, 40.dp, (blackX - 14f).dp, 18.dp, false)
            rect(blackX.dp, 40.dp, (300f - blackX).coerceAtLeast(0f).dp, 18.dp, accent.copy(alpha = .12f))
            box(14.dp, 40.dp, 306.dp, 18.dp, 0.dp, Diagram.Ink, 1.dp, dashed = dynamic)
            line(blackX.dp, 35.dp, blackX.dp, 63.dp, accent)
            line(300.dp, 35.dp, 300.dp, 63.dp, accent)
            positions.indices.forEach { index ->
                box((14 + index % 2 * 64).dp, (114 + index / 2 * 30).dp, 64.dp, 30.dp, 0.dp, Diagram.Ink, 1.dp, dashed = dynamic,
                    fill = androidx.compose.ui.graphics.Color.White.copy(alpha = .03f))
            }
            if (dynamic) repeat(3) { index ->
                val x = (183 + index * 50).dp
                arrow(x, 106.dp, x, 116.dp, SensorArt.Dim, dashed = true)
                box(x - 17.dp, 118.dp, 34.dp, 24.dp, 0.dp, Diagram.Hint, 1.dp, fill = SensorArt.Fill)
            } else {
                repeat(3) { index -> box((166 + index * 50).dp, 118.dp, 34.dp, 24.dp, 0.dp, Diagram.Hint, 1.dp, fill = SensorArt.Fill) }
                polyline(listOf(166.dp to 147.dp, 166.dp to 152.dp, 300.dp to 152.dp, 300.dp to 147.dp), CameraColors.Accent)
            }
        }
        DiagramLabel("RAW value scale", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 150.dp)
        DiagramText("no light", Modifier.at(14.dp, 24.dp), color = SensorArt.Detail, size = 8.5f, maxWidth = 80.dp)
        DiagramText("clipped", Modifier.at(240.dp, 24.dp).width(80.dp), color = SensorArt.Detail, size = 8.5f, maxWidth = 80.dp,
            align = TextAlign.End)
        DiagramText("0", Modifier.at(14.dp, 64.dp), translate = false, mono = true, size = 9f, maxWidth = 20.dp)
        DiagramText(blackText, Modifier.at((blackX + 4f).dp, 64.dp), translate = false, mono = true, size = 9.5f, color = accent,
            maxWidth = 120.dp)
        DiagramText("image signal", Modifier.at(120.dp, 64.dp).width(100.dp), size = 8.5f, maxWidth = 100.dp, align = TextAlign.Center)
        DiagramText(renderer.format("White {value}", mapOf("value" to (white?.toString() ?: "?"))), Modifier.at(206.dp, 64.dp).width(94.dp),
            translate = false, mono = true, size = 9.5f, color = accent, maxWidth = 94.dp, align = TextAlign.End)
        SensorArtMarker(3, 290.dp, 76.dp, mode == RawLevelMode.CUSTOM)
        DiagramText("Black · sensor 2×2 order", Modifier.at(14.dp, 88.dp), size = 9f, maxWidth = 116.dp)
        SensorArtMarker(2, 133.dp, 87.dp, mode == RawLevelMode.CUSTOM)
        positions.forEachIndexed { index, label ->
            val x = (20 + index % 2 * 64).dp
            val y = (116 + index / 2 * 30).dp
            DiagramText(label, Modifier.at(x, y), size = 8f, maxWidth = 56.dp, maxLines = 1)
            DiagramText(blacks.getOrNull(index)?.let(::levelText) ?: "?", Modifier.at(x, y + 12.dp), translate = false, mono = true,
                size = 10f, color = accent, maxWidth = 56.dp)
        }
        DiagramText(if (dynamic) "Dynamic (per frame from sensor)" else "Same every frame", Modifier.at(160.dp, 90.dp),
            color = if (dynamic) CameraColors.Accent else Diagram.Hint, size = 8.5f, maxWidth = 170.dp)
        (1..3).forEach { frame ->
            DiagramText("$frame", Modifier.at((166 + (frame - 1) * 50).dp, 124.dp).width(34.dp),
                translate = false, mono = true, size = 8f, color = SensorArt.Soft, maxWidth = 34.dp, align = TextAlign.Center)
        }
        if (!dynamic) DiagramText(mode.label, Modifier.at(160.dp, 156.dp), color = CameraColors.Accent, size = 8.5f, maxWidth = 170.dp)
        SensorArtMarker(1, 306.dp, 6.dp)
    }
}

private fun levelText(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

@Composable
private fun LevelField(label: String, value: String, modifier: Modifier, wholeNumber: Boolean = false, number: Int? = null,
    onChange: (String) -> Unit) {
    SensorArtField(value, onChange, label, modifier, number = number,
        keyboardOptions = KeyboardOptions(keyboardType = if (wholeNumber) KeyboardType.Number else KeyboardType.Decimal))
}
