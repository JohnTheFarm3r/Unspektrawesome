package com.unspektrawesome.ui.camera

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.unspektrawesome.language.languageSemantics

@Composable
internal fun Modifier.cameraModeGestures(
    mode: CaptureMode,
    rotation: Float,
    enabled: Boolean,
    swipeEnabled: Boolean,
    onMode: (CaptureMode) -> Unit,
): Modifier {
    val currentMode by rememberUpdatedState(mode)
    val currentChange by rememberUpdatedState(onMode)
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    return languageSemantics { language ->
        contentDescription = language.text("Viewfinder")
        stateDescription = language.text(mode.label)
        customActions = if (enabled) CaptureMode.entries.filter { it != mode }.map { target ->
            val label = when (target) {
                CaptureMode.PHOTO -> "Switch to photo mode"
                CaptureMode.BURST -> "Switch to BURST mode"
                CaptureMode.VIDEO -> "Switch to video mode"
                CaptureMode.PANORAMA -> "Switch to panorama mode"
                CaptureMode.GRID -> "Switch to 3×3 capture"
            }
            CustomAccessibilityAction(language.text(label)) { currentChange(target); true }
        } else emptyList()
    }.pointerInput(rotation, enabled, swipeEnabled, threshold) {
        if (!enabled || !swipeEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var handled = false
            var blocked = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) blocked = true
                if (change.pressed && change.isConsumed && !handled) blocked = true
                if (change.pressed && !blocked && !handled) {
                    val delta = change.position - down.position
                    captureModeSwipe(delta.x, delta.y, threshold, rotation)?.let { direction ->
                        handled = true
                        change.consume()
                        currentChange(currentMode.step(direction))
                    }
                } else if (handled) change.consume()
            } while (event.changes.any { it.pressed })
        }
    }
}
