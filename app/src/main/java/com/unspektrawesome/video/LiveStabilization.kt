package com.unspektrawesome.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.unspektrawesome.camera.session.RawCaptureMetadata
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.capture.NormalizedRect
import com.unspektrawesome.video.telemetry.TelemetryCamera
import com.unspektrawesome.vulkan.params.GeometryShaderParameters
import java.nio.ByteBuffer

internal class LiveStabilization(context: Context, private val camera: TelemetryCamera,
    private val geometry: FrameGeometrySnapshot, private val bounds: NormalizedRect,
    settings: LiveStabilizationSettings, historyNs: Long = 2_000_000_000L) : AutoCloseable {
    private val engine = GyroStabilizer(settings, historyNs)
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = checkNotNull(manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE))
    private val callbacks = HandlerThread("LiveStabilizationGyro")
    private val armedAt = SystemClock.elapsedRealtimeNanos()
    private var lastReadyAt = armedAt
    @Volatile var ready = false
        private set
    @Volatile var reason = "gyro-coverage-incomplete"
        private set
    var latest: GyroStabilizer.Correction? = null
        private set
    val failed get() = !ready && SystemClock.elapsedRealtimeNanos()-lastReadyAt >
        if (lastReadyAt == armedAt) 5_000_000_000L else 1_000_000_000L
    private var lastPose: GyroStabilizer.Quaternion? = null
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0].toDouble(); val y = event.values[1].toDouble(); val z = event.values[2].toDouble()
            if (maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z)) >= sensor.maximumRange*.995)
                engine.add(event.timestamp, Double.NaN, y, z)
            else engine.add(event.timestamp, x, y, z)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        callbacks.start()
        try { check(manager.registerListener(listener, sensor, 2500, 0, Handler(callbacks.looper))) }
        catch (error: Throwable) { manager.unregisterListener(listener); callbacks.quitSafely(); throw error }
    }

    fun update(capture: RawCaptureMetadata): GyroStabilizer.Correction? {
        val correction = calculate(capture)
        latest = correction
        ready = correction != null
        if (correction != null) lastReadyAt = SystemClock.elapsedRealtimeNanos()
        return correction
    }

    private fun calculate(capture: RawCaptureMetadata): GyroStabilizer.Correction? {
        val frame = camera.frame(capture, capture.sensorTimestampNs)
        val issues = frame.getJSONArray("issues")
        // Projection stays in sensor coordinates, before display rotation or reflection.
        // Each capture is warped before linear frame averaging in the renderer.
        val handled = setOf("mirrored-output-unsupported", "readout-direction-unsupported", "camera-matrix-unsupported",
            "android-distortion-unsupported", "averaged-captures-unsupported")
        for (i in 0 until issues.length()) {
            val issue = issues.getString(i)
            if (issue !in handled) { reason = issue; return null }
        }
        val mapping = camera.geometry ?: return null
        val intrinsic = frame.getJSONArray("sensorIntrinsics")
        val pose = frame.getJSONArray("lensPoseXyzw")
        val rotation = GyroStabilizer.Quaternion(pose.getDouble(3), pose.getDouble(0),
            pose.getDouble(1), pose.getDouble(2)).normalized()
        val previous = lastPose
        if (previous != null && kotlin.math.abs(previous.w*rotation.w+previous.x*rotation.x+
                previous.y*rotation.y+previous.z*rotation.z) < .99999) {
            reason = "camera-imu-calibration-changed"; return null
        }
        lastPose = rotation
        val k = mapping.rawNormalizedIntrinsics(FloatArray(intrinsic.length()) { intrinsic.getDouble(it).toFloat() })
        val crop = geometry.sourceCrop
        val sourceBounds = doubleArrayOf(bounds.left.toDouble(), bounds.top.toDouble(), bounds.width.toDouble(), bounds.height.toDouble())
        val coefficients = frame.getJSONArray("androidDistortion").let { array -> DoubleArray(array.length()) { array.getDouble(it) } }
        if (coefficients.size != 5 || coefficients.any { !it.isFinite() }) {
            reason = "android-distortion-unsupported"; return null
        }
        val distortion = if (coefficients.any { it != 0.0 }) GyroLensDistortion.create(k, coefficients, sourceBounds)
            ?: run { reason = "android-distortion-unsupported"; return null } else null
        val calibration = GyroStabilizer.Calibration(rotation, k, mapping.rawReadoutStart, mapping.rawReadoutSpan,
            doubleArrayOf(crop.left.toDouble(), crop.top.toDouble(), crop.width.toDouble(), crop.height.toDouble()),
            sourceBounds, distortion)
        val correction = engine.correction(capture.sensorTimestampNs, frame.getLong("exposureNs"),
            frame.getLong("skewNs"), calibration)
        if (correction == null) { reason = "gyro-coverage-incomplete"; return null }
        reason = ""
        return correction
    }

    fun writeTo(buffer: ByteBuffer, correction: GyroStabilizer.Correction?) {
        val offset = GeometryShaderParameters.STABILIZATION_OFFSET
        buffer.putFloat(offset, if (correction != null) 1f else 0f)
        if (correction == null) return
        buffer.putFloat(offset+4, correction.rowStart)
        buffer.putFloat(offset+8, correction.rowSpan)
        buffer.putFloat(offset+12, if (correction.distortion != null) 1f else 0f)
        buffer.putFloat(offset+16, bounds.left); buffer.putFloat(offset+20, bounds.top)
        buffer.putFloat(offset+24, bounds.width); buffer.putFloat(offset+28, bounds.height)
        correction.rows.forEachIndexed { i, value -> buffer.putFloat(offset+32+i*4, value) }
        correction.distortion?.let { lens ->
            val at = GeometryShaderParameters.STABILIZATION_LENS_OFFSET
            val k = lens.intrinsics; val d = lens.coefficients
            doubleArrayOf(k[0], k[4], k[2], k[5], k[1], d[0], d[1], d[2], d[3], d[4], 0.0, 0.0)
                .forEachIndexed { i, value -> buffer.putFloat(at+i*4, value.toFloat()) }
        }
    }

    override fun close() {
        manager.unregisterListener(listener)
        callbacks.quitSafely()
    }
}
