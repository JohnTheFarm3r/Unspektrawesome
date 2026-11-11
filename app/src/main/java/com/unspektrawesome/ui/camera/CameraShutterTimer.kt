package com.unspektrawesome.ui.camera

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class TimedShutter(val remaining: Int, val onPress: () -> Unit)

@Composable
internal fun rememberTimedShutter(active: Boolean, ready: Boolean, seconds: Int, onShutter: () -> Unit): TimedShutter {
    val scope = rememberCoroutineScope()
    var remaining by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Job?>(null) }
    val currentShutter by rememberUpdatedState(onShutter)
    val available by rememberUpdatedState(active && ready)
    DisposableEffect(active, ready, seconds) {
        onDispose { countdown?.cancel(); countdown = null; remaining = 0 }
    }
    return TimedShutter(remaining) {
        if (countdown?.isActive == true) {
            countdown?.cancel()
            countdown = null
            remaining = 0
        } else if (available) {
            if (seconds <= 0) currentShutter() else countdown = scope.launch {
                val deadline = SystemClock.elapsedRealtime() + seconds.coerceAtMost(30) * 1000L
                try {
                    while (true) {
                        val millis = deadline - SystemClock.elapsedRealtime()
                        if (millis <= 0) break
                        remaining = ((millis + 999) / 1000).toInt()
                        delay(minOf(millis, 100L))
                    }
                    remaining = 0
                    if (available) currentShutter()
                } finally { remaining = 0 }
            }
        }
    }
}
