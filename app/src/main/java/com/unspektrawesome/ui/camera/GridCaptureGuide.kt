package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.panorama.GridCaptureState
import com.unspektrawesome.panorama.gridTarget
import com.unspektrawesome.panorama.gridTranspose
import com.unspektrawesome.panorama.gridVector
import com.unspektrawesome.panorama.gridMultiply
import com.unspektrawesome.panorama.GridCapturedPanel
import kotlin.math.*

@Composable
internal fun GridCaptureGuide(snapshot: GridCaptureState, recording: Boolean, panels: List<GridCapturedPanel>,
    onAimRadiusChanged: (Double) -> Unit = {}) {
    val state = snapshot.motion?.collectAsState()?.value ?: snapshot
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val updateAimRadius by rememberUpdatedState(onAimRadiusChanged)
    LaunchedEffect(viewport, density) {
        if (viewport.width > 0 && viewport.height > 0)
            updateAimRadius(with(density) { 18.dp.toPx().toDouble() } / min(viewport.width, viewport.height))
    }
    val lastFrameRotation = remember { arrayOf(state.rotation) }
    SideEffect { if (recording) lastFrameRotation[0] = state.rotation }
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableIntStateOf(state.captured.size) }
    LaunchedEffect(state.captured.size) {
        if (state.captured.size > previous) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        previous = state.captured.size
    }
    val hold by animateFloatAsState(state.hold, tween(80), label = "Grid hold progress")
    val panelOpacity by animateFloatAsState(if (recording) 1f else 0f, tween(280), label = "Captured grid visibility")
    val panelReveals = panels.map { panel ->
        val reveal = remember(panel) { Animatable(0f) }
        LaunchedEffect(panel) { reveal.animateTo(1f, tween(240)) }
        reveal.value
    }
    val textures = remember(panels) { panels.map { panel ->
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(panel.bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
    } }
    val vertices = remember { FloatArray(24 * 24 * 12) }
    val coordinates = remember { FloatArray(vertices.size) }
    val projected = remember { FloatArray(25 * 25 * 3) }
    val corners = remember { IntArray(4) }
    val triangles = remember { intArrayOf(0, 1, 2, 1, 3, 2) }
    val visibility = (0..8).map { index ->
        animateFloatAsState(if (index in state.captured) .35f else 1f, tween(280), label = "Grid target $index").value
    }
    val radii = (0..8).map { index ->
        animateFloatAsState(if (index in state.captured) 4f else if (index == state.next) 18f else 10f,
            tween(280), label = "Grid target radius $index").value
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }.clipToBounds().testTag("3×3 capture targets").languageSemantics { language ->
        contentDescription = language.text("Aim at the highlighted circle and hold steady")
        stateDescription = language.format("{count} of 9 panels", mapOf("count" to state.captured.size))
    }) {
        val inverse = gridTranspose(state.rotation)
        if (panelOpacity > 0f) drawIntoCanvas { canvas ->
            val frameInverse = if (recording) inverse else gridTranspose(lastFrameRotation[0])
            panels.forEachIndexed { index, panel ->
                val relative = gridMultiply(frameInverse, panel.rotation)
                var count = 0
                for (y in 0..24) for (x in 0..24) {
                    val u = (x / 24.0 - .5) * state.width / state.focal
                    val v = (y / 24.0 - .5) * state.height / state.focal
                    val z = relative[6] * u + relative[7] * v + relative[8]
                    val offset = (y * 25 + x) * 3
                    projected[offset] = (size.width * (.5 + state.focal / state.width * (relative[0] * u + relative[1] * v + relative[2]) / z)).toFloat()
                    projected[offset + 1] = (size.height * (.5 + state.focal / state.height * (relative[3] * u + relative[4] * v + relative[5]) / z)).toFloat()
                    projected[offset + 2] = z.toFloat()
                }
                for (y in 0 until 24) for (x in 0 until 24) {
                    corners[0] = (y * 25 + x) * 3; corners[1] = corners[0] + 3
                    corners[2] = corners[0] + 75; corners[3] = corners[2] + 3
                    if (corners.any { projected[it + 2] <= .15f || !projected[it].isFinite() || !projected[it + 1].isFinite() }) continue
                    if (corners.all { projected[it] < 0 } || corners.all { projected[it] > size.width } ||
                        corners.all { projected[it + 1] < 0 } || corners.all { projected[it + 1] > size.height }) continue
                    for (corner in triangles) {
                        vertices[count] = projected[corners[corner]]
                        coordinates[count++] = (x + corner % 2) / 24f * panel.bitmap.width
                        vertices[count] = projected[corners[corner] + 1]
                        coordinates[count++] = (y + corner / 2) / 24f * panel.bitmap.height
                    }
                }
                textures[index].alpha = (panelOpacity * panelReveals[index] * 255).roundToInt()
                if (count > 0) canvas.nativeCanvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES,
                    count, vertices, 0, coordinates, 0, null, 0, null, 0, 0, textures[index])
            }
        }
        fun project(ray: List<Double>): Offset? {
            val point = gridVector(inverse, ray)
            if (point[2] <= .05) return null
            return Offset((size.width * (.5 + state.focal / state.width * point[0] / point[2])).toFloat(),
                (size.height * (.5 + state.focal / state.height * point[1] / point[2])).toFloat())
        }
        for (index in 0..8) {
            val ray = gridTarget(index, state.width, state.height, state.focal)
            val position = project(ray) ?: continue
            val captured = index in state.captured
            val active = index == state.next && !captured
            val alpha = visibility[index]
            val color = if (captured || active && recording) CameraColors.Accent else CameraColors.Text.copy(alpha = .6f)
            val inset = 12.dp.toPx()
            if (position.x !in inset..size.width - inset || position.y !in inset..size.height - inset) {
                val direction = position - center
                val scale = minOf((size.width * .5f - inset) / abs(direction.x).coerceAtLeast(1f),
                    (size.height * .5f - inset) / abs(direction.y).coerceAtLeast(1f)).coerceAtMost(1f)
                drawCircle(Color.Black.copy(alpha = .6f), 4.5.dp.toPx(), center + direction * scale)
                drawCircle(color.copy(alpha = color.alpha * alpha), 2.5.dp.toPx(), center + direction * scale,
                    style = Stroke(1.dp.toPx()))
                continue
            }
            val radius = radii[index].dp.toPx()
            drawCircle(Color.Black.copy(alpha = .65f * alpha), radius, position, style = Stroke(4.dp.toPx()))
            drawCircle(color.copy(alpha = color.alpha * alpha * if (active && recording) .45f else 1f), radius, position,
                style = Stroke((if (active) 1.8f else 1.2f).dp.toPx()))
            if (active && recording && hold > 0) drawArc(CameraColors.Accent, -90f, 360f * hold, false,
                position - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(3.2.dp.toPx()))
            if (captured) drawCircle(CameraColors.Accent.copy(alpha = .8f), 1.5.dp.toPx(), position)
        }
        drawCircle(Color.Black.copy(alpha = .65f), 5.dp.toPx(), style = Stroke(3.dp.toPx()))
        drawCircle(CameraColors.Text.copy(alpha = .9f), 5.dp.toPx(), style = Stroke(1.dp.toPx()))
        drawCircle(CameraColors.Text, 1.2.dp.toPx())
        if (recording) for (index in 0..8) {
            val point = Offset(size.width - 27.dp.toPx() + index % 3 * 6.dp.toPx(), 15.dp.toPx() + index / 3 * 6.dp.toPx())
            drawRoundRect(Color.Black.copy(alpha = .5f), point - Offset(1.dp.toPx(), 1.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
            drawRoundRect(if (index in state.captured) CameraColors.Accent else CameraColors.Text.copy(alpha = .45f), point, Size(4.dp.toPx(), 4.dp.toPx()))
        }
    }
}
