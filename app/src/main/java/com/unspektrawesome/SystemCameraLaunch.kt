package com.unspektrawesome

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the system camera intent (for example a double press of the power button on an
 * unlocked phone) and brings the existing camera forward instead of stacking a second one.
 */
class CameraLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

/**
 * Camera shown above the lock screen. It runs in its own task, closes when the screen turns off
 * and asks for an unlock before anything outside the camera, such as the gallery, is opened.
 */
class SecureCameraActivity : MainActivity() {
    override val lockScreenCamera = true
}
