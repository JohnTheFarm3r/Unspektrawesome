package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SKIP RAW EDGE PIXELS", color = CameraColors.Accent, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CameraOverlayToggle(enabled, "Skip RAW edge pixels") { enabled = it }
        }
        if (enabled) {
            Text("Pixels to skip from each edge (0-{MAX_EDGE_PIXELS})", arguments = mapOf("MAX_EDGE_PIXELS" to maximum), color = CameraColors.Muted, fontSize = 11.sp)
            SettingsFieldPair(
                first = { modifier -> RawEdgeField("Left", left, modifier) { left = it } },
                second = { modifier -> RawEdgeField("Right", right, modifier) { right = it } },
            )
            SettingsFieldPair(
                first = { modifier -> RawEdgeField("Top", top, modifier) { top = it } },
                second = { modifier -> RawEdgeField("Bottom", bottom, modifier) { bottom = it } },
            )
            if (!valid) Text("Use 0-{MAX_EDGE_PIXELS} per edge and leave at least 2 × 2 RAW pixels.", arguments = mapOf("MAX_EDGE_PIXELS" to maximum),
                color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun RawEdgeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    SettingsTextField(value, { if (it.length <= 5 && it.all(Char::isDigit)) onChange(it) }, label, modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
