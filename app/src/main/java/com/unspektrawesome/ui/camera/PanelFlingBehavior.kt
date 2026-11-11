package com.unspektrawesome.ui.camera

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity

@Composable
internal fun rememberPanelFlingBehavior(horizontal: Boolean = false,
    default: FlingBehavior = ScrollableDefaults.flingBehavior()): PanelFlingBehavior {
    val maximumVelocity = LocalViewConfiguration.current.maximumFlingVelocity
    return remember(default, maximumVelocity, horizontal) { PanelFlingBehavior(default, maximumVelocity, horizontal) }
}

internal class PanelFlingBehavior(private val default: FlingBehavior, private val maximumVelocity: Float,
    private val horizontal: Boolean = false) : FlingBehavior {
    private val tracker = VelocityTracker()
    private var lastMoveTime = 0L
    private var releaseVelocity: Float? = null

    fun start(change: PointerInputChange) {
        releaseVelocity = null
        tracker.resetTracking()
        track(change)
    }

    fun track(change: PointerInputChange) {
        // Compose 1.11 tracks fling velocity in screen coordinates, unlike rotated drag positions.
        change.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
        tracker.addPosition(change.uptimeMillis, change.position)
        lastMoveTime = change.uptimeMillis
    }

    fun release(change: PointerInputChange) {
        val velocity = tracker.calculateVelocity(Velocity(maximumVelocity, maximumVelocity))
        releaseVelocity = if (change.uptimeMillis - lastMoveTime > 40L) 0f else
            -(if (horizontal) velocity.x else velocity.y)
    }

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val velocity = releaseVelocity ?: initialVelocity
        releaseVelocity = null
        return with(default) { performFling(velocity) }
    }
}

internal fun Modifier.trackPanelFling(behavior: PanelFlingBehavior): Modifier = pointerInput(behavior) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
        var pointer = down.id
        behavior.start(down)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            if (change.pressed) behavior.track(change)
            else {
                val replacement = event.changes.firstOrNull { it.pressed }
                if (replacement == null) behavior.release(change)
                else { pointer = replacement.id; behavior.start(replacement) }
            }
        } while (event.changes.any { it.pressed })
    }
}
