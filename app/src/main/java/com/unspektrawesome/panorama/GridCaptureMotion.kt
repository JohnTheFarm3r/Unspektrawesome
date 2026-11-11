package com.unspektrawesome.panorama

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

internal data class GridCapturePose(val timestamp: Long, val target: Int, val rotation: List<Double>, val ready: Boolean)

internal class GridCaptureMotion(context: Context, private val changed: (GridCaptureState) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var callbacks: HandlerThread? = null
    private var active = false
    private val anchor = GridCaptureAnchor()
    private var basis = listOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)
    private var state = GridCaptureState(available = sensor != null)
    private val mutableReadings = MutableStateFlow(state)
    val readings = mutableReadings.asStateFlow()
    private val dwell = GridCaptureDwell()
    private var aimRadius = GRID_TARGET_RADIUS
    private val history = ArrayDeque<GridCapturePose>()
    private val matrix = FloatArray(9)
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) = synchronized(this@GridCaptureMotion) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            updateRotation(event.timestamp, matrix.map(Float::toDouble))
        }
    }

    @Synchronized fun updateRotation(timestamp: Long, rotation: List<Double>) {
        if (!active) return
        val relative = anchor.update(gridMultiply(rotation, basis))
        val ready = anchor.armed && state.captured.size < 9 && dwell.update(timestamp, relative, state.next, state.width, state.height, state.focal, aimRadius)
        state = state.copy(rotation = relative, hold = if (anchor.armed) dwell.progress else 0f)
        history.addLast(GridCapturePose(timestamp, state.next, relative, ready))
        while (history.isNotEmpty() && timestamp - history.first().timestamp > 3_000_000_000L) history.removeFirst()
        publish()
    }

    @Synchronized fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        manager.unregisterListener(listener)
        history.clear(); dwell.reset(); anchor.reset()
        if (enabled && sensor != null) {
            val thread = callbacks ?: HandlerThread("GridCaptureMotion").apply { start() }.also { callbacks = it }
            state = state.copy(available = manager.registerListener(listener, sensor, 20_000, Handler(thread.looper)))
        }
        state = state.copy(hold = 0f)
        publish()
    }

    @Synchronized fun configure(displayRotation: Int, front: Boolean) {
        val angle = Math.toRadians(-displayRotation.toDouble())
        val c = cos(angle); val s = sin(angle)
        val next = listOf(c, s, 0.0, s, -c, 0.0, 0.0, 0.0, if (front) 1.0 else -1.0)
        if (basis == next) return
        basis = next
        anchor.reset(); history.clear(); dwell.reset()
        state = state.copy(rotation = GRID_IDENTITY, captured = emptySet(), next = GRID_CAPTURE_ORDER.first(), hold = 0f,
            orientationDegrees = displayRotation)
        publish()
    }

    @Synchronized fun configureImage(width: Int, height: Int, focal: Double) {
        if (state.width == width && state.height == height && abs(state.focal - focal) < .001) return
        state = state.copy(width = width, height = height, focal = focal)
        dwell.reset(); history.clear()
    }

    @Synchronized fun configureAimRadius(radius: Double) {
        if (!radius.isFinite() || radius <= 0 || abs(aimRadius - radius) < .000001) return
        aimRadius = radius
        dwell.reset(); history.clear()
        state = state.copy(hold = 0f)
        publish()
    }

    @Synchronized fun start(): Boolean {
        if (!active || !state.available || !anchor.start()) return false
        dwell.reset(); history.clear()
        state = state.copy(rotation = GRID_IDENTITY, captured = emptySet(), next = GRID_CAPTURE_ORDER.first(), hold = 0f)
        publish()
        return true
    }

    @Synchronized fun pose(timestamp: Long): GridCapturePose? {
        if (!anchor.armed || state.hold < 1f) return null
        val sample = history.minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        if (!sample.ready || sample.target != state.next || abs(sample.timestamp - timestamp) > 100_000_000L ||
            gridRotationDistance(sample.rotation, state.rotation) > Math.toRadians(1.5)) return null
        return sample
    }

    @Synchronized fun rotationAt(timestamp: Long): List<Double>? = history.minByOrNull { abs(it.timestamp - timestamp) }
        ?.takeIf { abs(it.timestamp - timestamp) <= 100_000_000L }?.rotation

    @Synchronized fun exposureRotation(timestamp: Long, exposureNs: Long): Double? {
        if (history.size < 2 || exposureNs <= 0) return null
        val start = history.minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        val end = history.minByOrNull { abs(it.timestamp - timestamp - exposureNs) } ?: return null
        if (abs(start.timestamp - timestamp) > 40_000_000L || abs(end.timestamp - timestamp - exposureNs) > 40_000_000L) return null
        val duration = end.timestamp - start.timestamp
        if (duration <= 0) {
            val earlier = history.lastOrNull { it.timestamp <= start.timestamp - 20_000_000L } ?: return null
            return gridRotationDistance(earlier.rotation, start.rotation) * exposureNs / (start.timestamp - earlier.timestamp)
        }
        return gridRotationDistance(start.rotation, end.rotation) * exposureNs / duration
    }

    @Synchronized fun captured(target: Int): Boolean {
        check(anchor.armed && target == state.next && target !in state.captured)
        val captured = state.captured + target
        state = state.copy(captured = captured, next = GRID_CAPTURE_ORDER.firstOrNull { it !in captured } ?: target, hold = 0f)
        dwell.reset(); history.clear(); publish()
        return captured.size == 9
    }

    private fun publish() { mutableReadings.value = state; changed(state) }
    @Synchronized fun stop() {
        anchor.stop(); dwell.reset(); history.clear()
        state = state.copy(rotation = GRID_IDENTITY, captured = emptySet(), next = GRID_CAPTURE_ORDER.first(), hold = 0f)
        publish()
    }
    override fun close() { setActive(false); callbacks?.quitSafely(); callbacks = null }
}
