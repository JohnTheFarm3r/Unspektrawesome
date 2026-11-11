package com.unspektrawesome.camera

import android.view.KeyEvent
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.VolumeButtonAction

internal class CameraHardwareKeyHandler {
    private val pressedKeys = mutableMapOf<Pair<Int, Int>, Long>()
    private val shutterKeys = setOf(
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_SPACE,
    )

    fun actionFor(keyCode: Int, isExternal: Boolean, preferences: CameraPreferences): VolumeButtonAction =
        if (keyCode == KeyEvent.KEYCODE_CAMERA || (isExternal && keyCode in shutterKeys)) {
            VolumeButtonAction.SHUTTER
        } else preferences.volumeActionFor(keyCode)

    fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
        action: VolumeButtonAction,
        deviceId: Int,
        downTime: Long,
        onAction: (VolumeButtonAction) -> Unit,
    ): Boolean {
        val key = deviceId to keyCode
        if (pressedKeys[key] == downTime) return true
        pressedKeys.remove(key)
        if (keyCode !in shutterKeys || action == VolumeButtonAction.SYSTEM_VOLUME) return false
        pressedKeys[key] = downTime
        if (repeatCount == 0) onAction(action)
        return true
    }

    fun onKeyUp(keyCode: Int, deviceId: Int, downTime: Long): Boolean {
        val key = deviceId to keyCode
        if (pressedKeys[key] != downTime) return false
        pressedKeys.remove(key)
        return true
    }

    fun reset() {
        pressedKeys.clear()
    }
}
