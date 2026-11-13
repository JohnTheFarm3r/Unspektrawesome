package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.RawEdgeCrop
import kotlin.math.roundToInt

private val CropPresets = listOf(1f, 1.5f, 2f, 2.5f, 3f)

@Composable
internal fun RawCenterCropEditor(value: RawEdgeCrop, size: Size2d, onChange: (RawEdgeCrop) -> Unit) {
    val presets = CropPresets
    var expanded by remember { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(value.centerFactor !in presets) }
    var text by rememberSaveable { mutableStateOf(value.centerFactor.toString()) }
    val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..RawEdgeCrop.MAX_CENTER_FACTOR }
    Column(Modifier.fillMaxWidth()) {
        SensorCropStage(value, size, custom)
        DiagramDropdownField("SENSOR CROP", if (custom) "Custom" else if (value.centerFactor == 1f) "Off"
            else "${value.centerFactor}×", null, expanded, translateValue = custom || value.centerFactor == 1f, number = 1,
            onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            presets.forEach { factor ->
                DiagramMenuItem(if (factor == 1f) "Off" else "${factor}×", !custom && value.centerFactor == factor,
                    translate = factor == 1f) {
                    custom = false
                    expanded = false
                    onChange(value.copy(centerFactor = factor))
                }
            }
            DiagramMenuItem("Custom", custom) {
                custom = true
                text = value.centerFactor.toString()
                expanded = false
            }
        }
        if (custom) SensorArtField(text, {
            if (it.length <= 6 && it.all { c -> c.isDigit() || c == '.' || c == ',' }) {
                text = it
                it.replace(',', '.').toFloatOrNull()?.takeIf { factor -> factor.isFinite() && factor in 1f..RawEdgeCrop.MAX_CENTER_FACTOR }
                    ?.let { factor -> onChange(value.copy(centerFactor = factor)) }
            }
        }, "Crop factor", Modifier.padding(horizontal = 4.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            error = if (parsed == null) "Enter a crop factor from 1 to 16." else null)
        DiagramHint("Crops the center of this profile's RAW stream before processing. Preview and saved photos use the same crop. A 2× crop keeps one quarter of the pixels.")
        val bounds = value.bounds(size)
        DiagramValue("Active RAW resolution: {value1} × {value2}", Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            arguments = mapOf("value1" to bounds.width(), "value2" to bounds.height()))
    }
}

/** The sensor frame with the kept center in lime, the other presets dashed, and the preset scale below. */
@Composable
private fun SensorCropStage(value: RawEdgeCrop, size: Size2d, custom: Boolean) {
    val renderer = LocalAppLanguage.current.renderer
    val (frameWidth, frameHeight) = sensorArtFit(size.width, size.height, 144.dp, 108.dp)
    val x0 = 14.dp
    val y0 = 26.dp
    val bounds = value.bounds(size)
    val scaleX = frameWidth / size.width.toFloat()
    val scaleY = frameHeight / size.height.toFloat()
    val cropX = x0 + scaleX * bounds.left.toFloat()
    val cropY = y0 + scaleY * bounds.top.toFloat()
    val cropWidth = scaleX * bounds.width().toFloat()
    val cropHeight = scaleY * bounds.height().toFloat()
    val cropped = value.centerFactor > 1f
    val selected = if (custom) CropPresets.size else CropPresets.indexOf(value.centerFactor)
    val percent = (100f * bounds.width() * bounds.height() / (size.width.toFloat() * size.height)).roundToInt()
    val factorText = if (cropped) "${value.centerFactor}×" else renderer.text("Off")
    SensorArtStage(176.dp, "Sensor crop") {
        DiagramDrawing {
            CropPresets.drop(1).filter { it != value.centerFactor }.forEach { factor ->
                val w = frameWidth / factor
                val h = frameHeight / factor
                box(x0 + (frameWidth - w) / 2, y0 + (frameHeight - h) / 2, w, h, 0.dp, Diagram.Line, 1.dp, dashed = true)
            }
            if (cropped) {
                val shade = Color.Black.copy(alpha = .5f)
                rect(x0, y0, frameWidth, cropY - y0, shade)
                rect(x0, cropY + cropHeight, frameWidth, y0 + frameHeight - cropY - cropHeight, shade)
                rect(x0, cropY, cropX - x0, cropHeight, shade)
                rect(cropX + cropWidth, cropY, x0 + frameWidth - cropX - cropWidth, cropHeight, shade)
                box(cropX, cropY, cropWidth, cropHeight, 0.dp, CameraColors.Accent, fill = SensorArt.AccentFill)
                callout(cropX + cropWidth, cropY + cropHeight / 2, 196.dp, cropY + cropHeight / 2)
            }
            box(x0, y0, frameWidth, frameHeight, 2.dp, Diagram.Ink)
            line(14.dp, 150.dp, 194.dp, 150.dp, Diagram.Line, 1.dp)
            repeat(CropPresets.size + 1) { index ->
                val x = (14 + index * 36).dp
                val on = index == selected
                line(x, 146.dp, x, 154.dp, if (on) CameraColors.Accent else Diagram.Hint, 1.dp, dashed = index == CropPresets.size)
                if (on) dot(x, 150.dp, 3.5.dp)
            }
        }
        DiagramLabel(renderer.format("RAW {width} × {height}", mapOf("width" to size.width, "height" to size.height)),
            modifier = Modifier.at(12.dp, 9.dp), maxWidth = 180.dp)
        if (cropped) DiagramText(factorText, Modifier.at(cropX + 3.dp, cropY + 2.dp), translate = false, mono = true, size = 8.5f,
            color = CameraColors.Accent, maxWidth = 60.dp)
        Column(Modifier.at(200.dp, 30.dp).width(128.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DiagramLabel("Sensor crop", factorText, active = cropped, maxWidth = 128.dp, valueTranslate = false)
            DiagramText("${bounds.width()} × ${bounds.height()}", translate = false, mono = true,
                size = 10f, color = CameraColors.Accent, maxWidth = 128.dp)
            DiagramText(renderer.format("{percent}% of the pixels", mapOf("percent" to percent)),
                translate = false, size = 9f, maxWidth = 128.dp)
        }
        SensorArtMarker(1, 300.dp, 6.dp)
        // Labels are centred under their ticks; the last one (Custom) has the free space on its right.
        val labels = CropPresets.map { if (it == 1f) renderer.text("Off") else "${it}×" } + renderer.text("Custom")
        labels.forEachIndexed { index, label ->
            val tick = 14 + index * 36
            val last = index == labels.lastIndex
            DiagramText(label, Modifier.at((if (last) tick - 12 else tick - 17).dp, 158.dp).width(if (last) 140.dp else 34.dp),
                translate = false, mono = true, size = 8.5f, color = if (index == selected) CameraColors.Accent else Diagram.Hint,
                maxWidth = if (last) 140.dp else 34.dp, align = if (last) TextAlign.Start else TextAlign.Center, maxLines = 1)
        }
    }
}
