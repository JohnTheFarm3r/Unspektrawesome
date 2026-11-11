package com.unspektrawesome.ui.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unspektrawesome.settings.CameraPreferencesStore

internal fun Context.cameraActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.cameraActivity()
    else -> null
}

@Composable
internal fun CameraBrightness(enabled: Boolean, visible: Boolean) {
    val window = LocalContext.current.cameraActivity()?.window
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(window, lifecycle, enabled, visible) {
        fun apply(resumed: Boolean) {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = if (enabled && visible && resumed) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        val observer = LifecycleEventObserver { _, _ -> apply(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        apply(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); apply(false) }
    }
}

@Composable
internal fun SavedCameraBrightness() {
    val context = LocalContext.current
    val preferences = remember(context) { CameraPreferencesStore(context) }
    var enabled by remember(preferences) { mutableStateOf(preferences.load().maxBrightness) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(preferences, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = preferences.load().maxBrightness
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    CameraBrightness(enabled, visible = true)
}
