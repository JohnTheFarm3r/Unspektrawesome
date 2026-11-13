package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.RawStreamSelection
import com.unspektrawesome.settings.ViewfinderResolutions

@Composable
internal fun ViewfinderResolutionField(stream: RawStreamSelection, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        PreviewSizeStage(stream, selected)
        DiagramDropdownField("VIEWFINDER RESOLUTION", ViewfinderResolutions.label(stream, selected),
            null, expanded, translateValue = false, number = 1,
            onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            ViewfinderResolutions.choices(stream, selected).forEach { edge ->
                val active = ViewfinderResolutions.size(stream, edge) == ViewfinderResolutions.size(stream, selected)
                DiagramMenuItem(ViewfinderResolutions.label(stream, edge), selected = active, translate = false) {
                    onSelect(edge); expanded = false
                }
            }
        }
    }
}

/** The RAW frame next to the smaller frame the viewfinder renders, drawn to scale. */
@Composable
private fun PreviewSizeStage(stream: RawStreamSelection, selected: Int) {
    val renderer = LocalAppLanguage.current.renderer
    val preview = ViewfinderResolutions.size(stream, selected)
    val (rawWidth, rawHeight) = sensorArtFit(stream.width, stream.height, 120.dp, 54.dp)
    val ratio = (preview.width.toFloat() / stream.width).coerceIn(.08f, 1f)
    val previewWidth = rawWidth * ratio
    val previewHeight = rawHeight * ratio
    val previewX = 12.dp + rawWidth + 44.dp
    val previewY = 30.dp + (rawHeight - previewHeight) / 2
    SensorArtStage(104.dp, "Preview size") {
        DiagramDrawing {
            box(12.dp, 30.dp, rawWidth, rawHeight, 2.dp, Diagram.Ink, fill = SensorArt.Fill)
            arrow(12.dp + rawWidth + 6.dp, 30.dp + rawHeight / 2, previewX - 6.dp, 30.dp + rawHeight / 2, CameraColors.Accent)
            box(previewX, previewY, previewWidth, previewHeight, 1.5.dp, CameraColors.Accent, fill = SensorArt.AccentFill)
        }
        DiagramLabel("Preview size", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 200.dp)
        DiagramText(renderer.format("RAW {width} × {height}", mapOf("width" to stream.width, "height" to stream.height)),
            Modifier.at(12.dp, 30.dp + rawHeight + 6.dp), translate = false, mono = true, size = 8.5f, maxWidth = 150.dp)
        DiagramText("${preview.width} × ${preview.height}", Modifier.at(previewX, 30.dp + rawHeight + 6.dp), translate = false,
            mono = true, size = 9f, color = CameraColors.Accent, maxWidth = 120.dp)
        SensorArtMarker(1, previewX + previewWidth + 4.dp, 22.dp)
    }
}
