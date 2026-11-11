package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.diagnostics.InternalLogRecorder

@Composable
internal fun Modifier.traceLensInput(name: String, state: () -> String = { "" }): Modifier {
    if (!BuildConfig.DEBUG) return this
    val currentState by rememberUpdatedState(state)
    return pointerInput(name) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val edges = event.changes.filter { it.pressed != it.previousPressed }
                    if (edges.isNotEmpty()) {
                        InternalLogRecorder.event("LensInput", "$name initial ${event.type} " +
                            edges.joinToString { "${it.id.value}:${it.position}:${it.isConsumed}" } + " ${currentState()}")
                    }
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    if (edges.isNotEmpty()) {
                        InternalLogRecorder.event("LensInput", "$name final ${final.type} " +
                            final.changes.joinToString { "${it.id.value}:${it.isConsumed}" })
                    }
                }
            }
        } finally {
            InternalLogRecorder.event("LensInput", "$name detached ${currentState()}")
        }
    }
}
