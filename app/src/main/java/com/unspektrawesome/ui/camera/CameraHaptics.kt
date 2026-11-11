package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

@Composable
internal fun rememberCameraHaptics(enabled: Boolean): HapticFeedback {
    val delegate = LocalHapticFeedback.current
    val allowed by rememberUpdatedState(enabled)
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.isHapticFeedbackEnabled
        if (!enabled) view.isHapticFeedbackEnabled = false
        onDispose { view.isHapticFeedbackEnabled = previous }
    }
    return remember(delegate) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (allowed) delegate.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
}
