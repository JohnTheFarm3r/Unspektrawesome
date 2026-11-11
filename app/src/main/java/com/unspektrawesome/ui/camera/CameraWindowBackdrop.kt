package com.unspektrawesome.ui.camera

import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties

internal class CameraBackdropWindows {
    var count by mutableIntStateOf(0)
}

internal val LocalCameraBackdropWindows = staticCompositionLocalOf<CameraBackdropWindows?> { null }

internal val CameraPopupProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
)

@Composable
internal fun CameraWindowBackdrop() {
    val view = LocalView.current
    val windows = LocalCameraBackdropWindows.current
    DisposableEffect(view, windows) {
        val window = generateSequence(view as View?) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(CameraColors.OverlayDimAmount)
        } else {
            val root = view.rootView
            (root.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                params.dimAmount = CameraColors.OverlayDimAmount
                view.context.getSystemService(WindowManager::class.java).updateViewLayout(root, params)
            }
        }
        windows?.let { it.count++ }
        onDispose { windows?.let { it.count-- } }
    }
}
