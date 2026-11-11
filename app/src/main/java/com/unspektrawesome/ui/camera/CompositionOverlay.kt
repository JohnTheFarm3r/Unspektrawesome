package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unspektrawesome.camera.CameraLevel
import com.unspektrawesome.settings.CompositionGrid
import kotlin.math.sqrt

@Composable
private fun rememberLevel(enabled: Boolean): CameraLevel? {
    val manager = LocalContext.current.getSystemService(SensorManager::class.java)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reading by remember { mutableStateOf<CameraLevel?>(null) }
    DisposableEffect(manager, lifecycle, enabled) {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val matrix = FloatArray(9)
        var lastUpdate = 0L
        var filtered: CameraLevel? = null
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val gravity = if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    CameraLevel(matrix[6], matrix[7], matrix[8])
                } else {
                    val v = event.values
                    val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                    if (length < .001f) return
                    CameraLevel(v[0] / length, v[1] / length, v[2] / length)
                }
                val old = filtered
                filtered = if (old == null) gravity else CameraLevel(
                    old.x + .2f * (gravity.x - old.x), old.y + .2f * (gravity.y - old.y), old.z + .2f * (gravity.z - old.z))
                if (event.timestamp - lastUpdate >= 30_000_000L) { reading = filtered; lastUpdate = event.timestamp }
            }
        }
        fun update() {
            manager.unregisterListener(listener)
            filtered = null
            reading = null
            if (enabled && sensor != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); manager.unregisterListener(listener) }
    }
    return reading
}

internal fun DrawScope.drawCompositionGrid(grid: CompositionGrid, color: Color, stroke: Float) {
    fun line(start: Offset, end: Offset) {
        drawLine(Color.Black.copy(alpha = .35f), start, end, stroke + 1.dp.toPx())
        drawLine(color, start, end, stroke)
    }
    val positions = when (grid) {
        CompositionGrid.THIRDS -> listOf(1f / 3, 2f / 3)
        CompositionGrid.FINE -> listOf(.25f, .5f, .75f)
        CompositionGrid.GOLDEN -> listOf(.381966f, .618034f)
        else -> emptyList()
    }
    positions.forEach { p ->
        line(Offset(size.width * p, 0f), Offset(size.width * p, size.height))
        line(Offset(0f, size.height * p), Offset(size.width, size.height * p))
    }
    if (grid == CompositionGrid.DIAGONALS) {
        line(Offset.Zero, Offset(size.width, size.height))
        line(Offset(size.width, 0f), Offset(0f, size.height))
    }
    if (grid == CompositionGrid.INTERSECTIONS) {
        val arm = minOf(4.dp.toPx(), size.minDimension * .06f)
        for (x in listOf(1f / 3, 2f / 3)) for (y in listOf(1f / 3, 2f / 3)) {
            val point = Offset(size.width * x, size.height * y)
            line(point - Offset(arm, 0f), point + Offset(arm, 0f))
            line(point - Offset(0f, arm), point + Offset(0f, arm))
        }
    }
}

@Composable
internal fun CompositionGuides(grid: CompositionGrid, horizon: Boolean, rotation: Float, visible: Boolean = true) {
    AnimatedVisibility(visible, Modifier.fillMaxSize().testTag("Composition guides"),
        enter = fadeIn(tween(200)), exit = fadeOut(tween(100))) {
        CompositionOverlay(grid, horizon, rotation)
    }
}

@Composable
internal fun CompositionOverlay(
    grid: CompositionGrid,
    horizon: Boolean,
    rotation: Float,
    level: CameraLevel? = rememberLevel(horizon),
) {
    val haptics = LocalHapticFeedback.current
    var aligned by remember { mutableStateOf<Boolean?>(null) }
    SideEffect {
        val next = if (horizon) level?.aligned(rotation, aligned == true) else null
        if (aligned == false && next == true) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        aligned = next
    }
    Canvas(Modifier.fillMaxSize().testTag("Composition overlay")) {
        drawCompositionGrid(grid, CameraColors.Text.copy(alpha = .38f), .65.dp.toPx())
        if (horizon && level != null) {
            val color = if (aligned == true) CameraColors.Accent else CameraColors.Text.copy(alpha = .8f)
            val radius = minOf(18.dp.toPx(), size.minDimension * .15f)
            if (level.flat) {
                drawCircle(Color.Black.copy(alpha = .35f), radius + 1.dp.toPx(), style = Stroke(3.dp.toPx()))
                drawCircle(CameraColors.Text.copy(alpha = .6f), radius, style = Stroke(1.dp.toPx()))
                val bubble = Offset((-level.x * radius * 3).coerceIn(-radius, radius), (level.y * radius * 3).coerceIn(-radius, radius))
                drawCircle(color, 4.dp.toPx(), center + bubble)
                drawLine(color, center - Offset(4.dp.toPx(), 0f), center + Offset(4.dp.toPx(), 0f), 1.dp.toPx())
                drawLine(color, center - Offset(0f, 4.dp.toPx()), center + Offset(0f, 4.dp.toPx()), 1.dp.toPx())
            } else {
                val length = minOf(54.dp.toPx(), size.minDimension * .3f)
                rotate(rotation) {
                    drawLine(Color.Black.copy(alpha = .45f), center - Offset(length, 0f), center + Offset(length, 0f), 3.dp.toPx())
                    drawLine(CameraColors.Text.copy(alpha = .45f), center - Offset(length, 0f), center + Offset(length, 0f), 1.dp.toPx())
                    for (side in listOf(-1, 1)) drawLine(color, center + Offset(length * side, -4.dp.toPx()),
                        center + Offset(length * side, 4.dp.toPx()), 1.dp.toPx())
                }
                rotate(level.horizonRotation) {
                    drawLine(color, center - Offset(length * .78f, 0f), center + Offset(length * .78f, 0f), 1.6.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
