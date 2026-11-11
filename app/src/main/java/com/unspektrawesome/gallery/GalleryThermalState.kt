package com.unspektrawesome.gallery

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
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

internal data class GalleryThermalState(val pauseWhenHot: Boolean, val thermalStatus: Int) {
    val blocksExport: Boolean
        get() = pauseWhenHot && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    companion object {
        fun read(context: Context) = GalleryThermalState(
            context.getSharedPreferences("gallery", Context.MODE_PRIVATE).getBoolean("pause_when_hot", true),
            context.getSystemService(PowerManager::class.java).currentThermalStatus,
        )
    }
}

@Composable
internal fun rememberGalleryThermalState(): GalleryThermalState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val preferences = remember(context) { context.getSharedPreferences("gallery", Context.MODE_PRIVATE) }
    var state by remember(context) { mutableStateOf(GalleryThermalState.read(context)) }
    DisposableEffect(context, lifecycle, power, preferences) {
        fun refresh() { state = GalleryThermalState.read(context) }
        val thermalListener = PowerManager.OnThermalStatusChangedListener { refresh() }
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "pause_when_hot" || key == null) refresh()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        lifecycle.addObserver(lifecycleObserver)
        refresh()
        onDispose {
            power.removeThermalStatusListener(thermalListener)
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return state
}
