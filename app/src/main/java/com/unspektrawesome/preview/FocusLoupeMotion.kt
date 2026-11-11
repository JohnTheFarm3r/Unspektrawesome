package com.unspektrawesome.preview

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.abs

/**
 * Measures how far the camera has turned since the focus meter started comparing. Turning the camera
 * shows a different subject in the loupe, which the meter must not mistake for a change in focus.
 */
internal class FocusLoupeMotion(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val current = FloatArray(4)
    private val reference = FloatArray(4)
    private var hasCurrent = false
    private var hasReference = false
    private var listening = false

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) = synchronized(this@FocusLoupeMotion) {
            SensorManager.getQuaternionFromVector(current, event.values)
            hasCurrent = true
            if (!hasReference) {
                current.copyInto(reference)
                hasReference = true
            }
        }
    }

    /** Starts or stops listening; stopping forgets the reference orientation. */
    fun setActive(active: Boolean) {
        val target = sensor ?: return
        synchronized(this) {
            if (active == listening) return
            listening = active
            hasCurrent = false
            hasReference = false
        }
        if (active) manager?.registerListener(listener, target, SensorManager.SENSOR_DELAY_GAME)
        else manager?.unregisterListener(listener)
    }

    /** Makes the current orientation the reference. */
    @Synchronized fun resetReference() {
        if (hasCurrent) current.copyInto(reference)
        hasReference = hasCurrent
    }

    /** Rotation in radians since the reference, or 0 without a motion sensor. */
    @Synchronized fun turnedSinceReference(): Float {
        if (!hasCurrent || !hasReference) return 0f
        val dot = abs(current[0] * reference[0] + current[1] * reference[1] + current[2] * reference[2] +
            current[3] * reference[3]).coerceAtMost(1f)
        return 2f * acos(dot)
    }
}
