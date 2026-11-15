package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.unspektrawesome.preview.TrackedFaceBox
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.session.FaceDetectionDebug

/** Corner brackets around the face that focus and exposure follow. */
@Composable
internal fun TrackedFaceOverlay(face: TrackedFaceBox?, horizontal: Float, vertical: Float) {
    val visible = face != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "Face box visibility")
    var shown by remember { mutableStateOf(face) }
    if (face != null) shown = face
    val box = shown ?: return
    // The box follows each camera result directly; animating it would add visible lag.
    fun x(value: Float) = .5f + (value - .5f) / horizontal
    fun y(value: Float) = .5f + (value - .5f) / vertical
    val left = x(box.left)
    val top = y(box.top)
    val right = x(box.right)
    val bottom = y(box.bottom)
    if (alpha <= 0f) return
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val l = left * size.width
        val t = top * size.height
        val r = right * size.width
        val b = bottom * size.height
        val arm = minOf(r - l, b - t, 28.dp.toPx()) * .3f
        val color = CameraColors.Accent.copy(alpha = .9f * alpha)
        val width = 1.5.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), width, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), width, StrokeCap.Round)
        }
        corner(l, t, 1f, 1f); corner(r, t, -1f, 1f); corner(l, b, 1f, -1f); corner(r, b, -1f, -1f)
    }
}

/** Debug builds only: draws the camera's own face detection results over the viewfinder. */
@Composable
internal fun FaceDetectionDebugOverlay(horizontal: Float, vertical: Float) {
    if (!FaceDetectionDebug.enabled) return
    val frame by FaceDetectionDebug.frame.collectAsState()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = CameraColors.Accent, fontSize = 12.sp, fontFamily = CameraTypography.Readout)
    Canvas(Modifier.fillMaxSize()) {
        val geometry = FaceDetectionDebug.geometry ?: return@Canvas
        fun point(sensorX: Float, sensorY: Float): Offset {
            val surface = FaceDetectionDebug.toSurface(geometry, sensorX, sensorY)
            return Offset((.5f + (surface.x - .5f) / horizontal) * size.width,
                (.5f + (surface.y - .5f) / vertical) * size.height)
        }
        val stroke = Stroke(2.dp.toPx())
        for (face in frame.faces) {
            val first = point(face.left, face.top)
            val second = point(face.right, face.bottom)
            val topLeft = Offset(minOf(first.x, second.x), minOf(first.y, second.y))
            val bottomRight = Offset(maxOf(first.x, second.x), maxOf(first.y, second.y))
            drawRect(CameraColors.Accent, topLeft, Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y), style = stroke)
            for ((x, y) in face.landmarks) drawCircle(CameraColors.Accent, 3.dp.toPx(), point(x, y))
            val label = (if (face.id >= 0) "#${face.id}  " else "") + face.score
            val layout = measurer.measure(label, labelStyle)
            drawText(layout, topLeft = Offset(topLeft.x, (topLeft.y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
        }
        drawText(measurer.measure("${frame.mode} · ${frame.faces.size}", labelStyle),
            topLeft = Offset(12.dp.toPx(), 12.dp.toPx()))
    }
}
