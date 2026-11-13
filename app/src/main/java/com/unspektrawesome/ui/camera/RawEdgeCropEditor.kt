package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.RawEdgeCrop

@Composable
internal fun RawEdgeCropEditor(initial: RawEdgeCrop, size: Size2d, onChange: (RawEdgeCrop?) -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var left by rememberSaveable { mutableStateOf(initial.left.toString()) }
    var right by rememberSaveable { mutableStateOf(initial.right.toString()) }
    var top by rememberSaveable { mutableStateOf(initial.top.toString()) }
    var bottom by rememberSaveable { mutableStateOf(initial.bottom.toString()) }
    val numbers = listOf(left, right, top, bottom).map { if (it.isBlank()) 0 else it.toIntOrNull() }
    val parsed = if (numbers.all { it != null }) RawEdgeCrop(enabled, numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!,
        initial.centerFactor) else null
    val valid = parsed?.isValid(size) == true
    val maximum = minOf(RawEdgeCrop.MAX_EDGE_PIXELS, maxOf(size.width, size.height) - 2)
    val selected = if (!enabled) (parsed?.takeIf { it.isValid(size) } ?: initial).copy(enabled = false) else parsed?.takeIf { valid }
    LaunchedEffect(selected) { onChange(selected) }
    Column(Modifier.fillMaxWidth()) {
        EdgeCropStage(size, enabled && valid, numbers.map { it ?: 0 })
        SensorArtSwitchRow("SKIP RAW EDGE PIXELS", enabled, caption = true, number = 1, toggleLabel = "Skip RAW edge pixels") { enabled = it }
        if (enabled) {
            DiagramHint("Pixels to skip from each edge (0-{MAX_EDGE_PIXELS})", arguments = mapOf("MAX_EDGE_PIXELS" to maximum))
            SettingsFieldPair(
                first = { modifier -> RawEdgeField("Left", left, modifier) { left = it } },
                second = { modifier -> RawEdgeField("Right", right, modifier) { right = it } },
            )
            SettingsFieldPair(
                first = { modifier -> RawEdgeField("Top", top, modifier) { top = it } },
                second = { modifier -> RawEdgeField("Bottom", bottom, modifier) { bottom = it } },
            )
            if (!valid) Text("Use 0-{MAX_EDGE_PIXELS} per edge and leave at least 2 × 2 RAW pixels.",
                Modifier.padding(horizontal = 4.dp, vertical = 6.dp), arguments = mapOf("MAX_EDGE_PIXELS" to maximum),
                color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** The sensor frame with the skipped edges hatched and the kept area in lime. Edges are drawn wider than scale. */
@Composable
private fun EdgeCropStage(size: Size2d, active: Boolean, edges: List<Int>) {
    val renderer = LocalAppLanguage.current.renderer
    val (frameWidth, frameHeight) = sensorArtFit(size.width, size.height, 160.dp, 112.dp)
    val x0 = 14.dp
    val y0 = 28.dp
    val (left, right, top, bottom) = edges
    fun inset(pixels: Int) = if (!active || pixels <= 0) 0.dp else 10.dp
    val keptX = x0 + inset(left)
    val keptY = y0 + inset(top)
    val keptWidth = (frameWidth - inset(left) - inset(right)).coerceAtLeast(2.dp)
    val keptHeight = (frameHeight - inset(top) - inset(bottom)).coerceAtLeast(2.dp)
    val keptPixels = if (active) Size2d(size.width - left - right, size.height - top - bottom) else size
    SensorArtStage(168.dp) {
        DiagramDrawing {
            if (active) {
                sensorHatch(x0, y0, inset(left), frameHeight, false)
                sensorHatch(x0 + frameWidth - inset(right), y0, inset(right), frameHeight, false)
                sensorHatch(x0, y0, frameWidth, inset(top), false)
                sensorHatch(x0, y0 + frameHeight - inset(bottom), frameWidth, inset(bottom), false)
            }
            box(keptX, keptY, keptWidth, keptHeight, 0.dp, if (active) CameraColors.Accent else Diagram.Line, 1.dp,
                dashed = !active, fill = if (active) SensorArt.AccentFill else null)
            box(x0, y0, frameWidth, frameHeight, 2.dp, Diagram.Ink)
            if (active) callout(keptX + keptWidth, keptY + keptHeight / 2, 196.dp, keptY + keptHeight / 2)
        }
        DiagramLabel(renderer.format("RAW {width} × {height}", mapOf("width" to size.width, "height" to size.height)),
            modifier = Modifier.at(12.dp, 9.dp), maxWidth = 200.dp)
        if (active) {
            DiagramText(renderer.format("Top {pixels}", mapOf("pixels" to top)), Modifier.at(x0, keptY + 4.dp).width(frameWidth),
                translate = false, mono = true, size = 8.5f, color = SensorArt.Soft, maxWidth = 160.dp, align = TextAlign.Center)
            DiagramText(renderer.format("Bottom {pixels}", mapOf("pixels" to bottom)),
                Modifier.at(x0, keptY + keptHeight - 16.dp).width(frameWidth), translate = false, mono = true, size = 8.5f,
                color = SensorArt.Soft, maxWidth = 160.dp, align = TextAlign.Center)
            DiagramText(renderer.format("Left {pixels}", mapOf("pixels" to left)), Modifier.at(keptX + 6.dp, y0 + frameHeight / 2 - 6.dp),
                translate = false, mono = true, size = 8.5f, color = SensorArt.Soft, maxWidth = 70.dp)
            DiagramText(renderer.format("Right {pixels}", mapOf("pixels" to right)),
                Modifier.at(keptX + keptWidth - 76.dp, y0 + frameHeight / 2 - 6.dp).width(70.dp), translate = false, mono = true,
                size = 8.5f, color = SensorArt.Soft, maxWidth = 70.dp, align = TextAlign.End)
            DiagramText("Hatched edge drawn wider than scale", Modifier.at(14.dp, y0 + frameHeight + 6.dp), size = 8.5f,
                color = SensorArt.Detail, maxWidth = 180.dp)
        }
        DiagramLabel("Kept RAW area", renderer.format("{width} × {height}", mapOf("width" to keptPixels.width,
            "height" to keptPixels.height)), Modifier.at(200.dp, y0 + frameHeight / 2 - 6.dp), active = active, maxWidth = 130.dp,
            valueTranslate = false)
        SensorArtMarker(1, 306.dp, 6.dp, active)
    }
}

@Composable
private fun RawEdgeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    SensorArtField(value, { if (it.length <= 5 && it.all(Char::isDigit)) onChange(it) }, label, modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
