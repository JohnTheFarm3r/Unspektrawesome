package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.language.LocalAppLanguage
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
    val columns = if (enabled) width.toIntOrNull() ?: stream.width else stream.width
    val minimum = if (columns in 2..65536) RawLayoutOverride.minimumStride(stream.format, columns) else null
    Column(Modifier.fillMaxWidth()) {
        RowLayoutStage(stream, enabled, width, height, columns, minimum,
            if (!enabled) null else if (tightStride) minimum else stride.toIntOrNull())
        SensorArtSwitchRow("RAW LAYOUT OVERRIDE", enabled, caption = true, number = 1, toggleLabel = "Override RAW layout") { enabled = it }
        if (enabled) {
            SettingsFieldPair(
                first = { modifier -> RawLayoutField("Width (pixels)", width, modifier) { width = it } },
                second = { modifier -> RawLayoutField("Height (pixels)", height, modifier) { height = it } },
            )
            SensorArtSwitchRow("Tight row stride", tightStride) { tightStride = it }
            if (!tightStride) RawLayoutField("Row stride (bytes)", stride, Modifier.fillMaxWidth(), number = 2) { stride = it }
            if (minimum != null) DiagramHint("Minimum row stride: {bytes} bytes", arguments = mapOf("bytes" to minimum))
        }
        if (error != null) DiagramHint(error, warning = true)
        val showAlign = enabled && minimum != null
        if (showAlign || candidate != initial) DiagramButtonRow {
            if (showAlign && minimum != null) DiagramButton("Align stride to 64 bytes") {
                stride = (((minimum + 63) / 64) * 64).toString()
                tightStride = false
            }
            if (candidate != initial) DiagramButton("Apply RAW layout", Modifier.testTag("Apply RAW layout"),
                enabled = error == null, accent = true) { onChange(candidate) }
        }
    }
}

/** One RAW row as it sits in memory: the packed pixels and the padding up to the row stride. */
@Composable
private fun RowLayoutStage(stream: RawStreamSelection, enabled: Boolean, width: String, height: String, columns: Int,
    minimum: Int?, stride: Int?) {
    val renderer = LocalAppLanguage.current.renderer
    val padding = if (minimum != null && stride != null && stride > minimum) stride - minimum else 0
    val barWidth = 306f
    val dataWidth = if (minimum != null && stride != null && stride > minimum) barWidth * minimum / stride else barWidth
    val auto = renderer.text("Auto")
    SensorArtStage(104.dp, "One row in memory") {
        DiagramDrawing {
            box(14.dp, 54.dp, dataWidth.dp, 18.dp, 0.dp, if (enabled) Diagram.Ink else Diagram.Line, 1.2.dp, fill = Diagram.Glass)
            var x = 18f
            while (x < dataWidth + 10f) { line(x.dp, 54.dp, x.dp, 59.dp, Diagram.Line, 1.dp); x += 4f }
            if (padding > 0) {
                box((14f + dataWidth).dp, 54.dp, (barWidth - dataWidth).coerceAtLeast(4f).dp, 18.dp, 0.dp, CameraColors.Accent, 1.2.dp)
                sensorHatch((14f + dataWidth).dp, 54.dp, (barWidth - dataWidth).coerceAtLeast(4f).dp, 18.dp, true)
            }
        }
        DiagramLabel(renderer.format("{format} · {width} × {height}", mapOf("format" to stream.format.name,
            "width" to stream.width, "height" to stream.height)), modifier = Modifier.at(12.dp, 9.dp), maxWidth = 260.dp)
        DiagramText(if (enabled) renderer.format("Width {width} · Height {height}",
                mapOf("width" to width.ifBlank { auto }, "height" to height.ifBlank { auto }))
            else renderer.format("{width} × {height} as reported", mapOf("width" to stream.width, "height" to stream.height)),
            Modifier.at(14.dp, 30.dp), translate = false, size = 10f, color = if (enabled) CameraColors.Accent else Diagram.Hint,
            maxWidth = 280.dp)
        SensorArtMarker(1, 308.dp, 26.dp, enabled)
        DiagramText(renderer.format("{columns} px · {format} · {bytes} bytes", mapOf("columns" to columns,
            "format" to stream.format.name, "bytes" to (minimum ?: 0))), Modifier.at(14.dp, 56.dp).width(dataWidth.dp),
            translate = false, mono = true, size = 8.5f, color = SensorArt.Soft, maxWidth = 300.dp, align = TextAlign.Center)
        DiagramText("One row in memory", Modifier.at(14.dp, 80.dp), size = 8.5f, maxWidth = 130.dp)
        DiagramText(listOfNotNull(minimum?.let { renderer.format("min {bytes}", mapOf("bytes" to it)) },
                renderer.format("stride {bytes}", mapOf("bytes" to (stride?.toString() ?: auto)))).joinToString("  "),
            Modifier.at(150.dp, 80.dp).width(170.dp), translate = false, mono = true, size = 8.5f,
            color = if (padding > 0) CameraColors.Accent else Diagram.Hint, maxWidth = 170.dp, align = TextAlign.End)
        if (enabled) SensorArtMarker(2, (14f + dataWidth - 9f).coerceIn(14f, 306f).dp, 44.dp)
    }
}

@Composable
private fun RawLayoutField(label: String, value: String, modifier: Modifier, number: Int? = null, onChange: (String) -> Unit) {
    SensorArtField(value, { if (it.length <= 10 && it.all(Char::isDigit)) onChange(it) }, label, modifier, number = number,
        placeholder = "Auto", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
