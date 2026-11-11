package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.AppLanguage
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal const val LENS_PROFILE_HOLD_MILLIS = 500L

internal class LensProfileReorderState(
    initialOrder: List<String>,
    val scroll: LazyListState,
    private val saveOrder: (List<String>) -> Unit,
    private val onMove: () -> Unit,
) {
    var order by mutableStateOf(initialOrder)
        private set
    var draggedKey by mutableStateOf<String?>(null)
        private set
    var settlingKey by mutableStateOf<String?>(null)
        private set
    var pointerY by mutableFloatStateOf(0f)
        private set
    private var draggedTop by mutableFloatStateOf(0f)
    private var draggedHeight = 0
    private var settlingOffset by mutableFloatStateOf(0f)
    private var settlingStart = 0f

    fun sync(ids: List<String>) {
        if (draggedKey == null) order = ids
    }

    fun keyAt(y: Float): String? {
        val layout = scroll.layoutInfo
        return layout.visibleItemsInfo.firstOrNull {
            val top = it.offset - layout.viewportStartOffset
            it.key in order && y >= top && y < top + it.size
        }?.key as? String
    }

    fun start(key: String, y: Float): Boolean {
        val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        if (order.size < 2 || key !in order) return false
        settlingKey = null
        draggedTop = (item.offset - scroll.layoutInfo.viewportStartOffset).toFloat()
        draggedHeight = item.size
        pointerY = y
        draggedKey = key
        return true
    }

    fun drag(y: Float) {
        draggedTop += y - pointerY
        pointerY = y
        updateOrder()
    }

    fun updateOrder() {
        val key = draggedKey ?: return
        val layout = scroll.layoutInfo
        val current = order.indexOf(key)
        val visible = layout.visibleItemsInfo.filter { it.key in order }
        val dragged = visible.firstOrNull { it.key == key } ?: return
        val first = visible.firstOrNull { it.key == order.first() }
        val last = visible.firstOrNull { it.key == order.last() }
        val minimum = maxOf(0f, first?.let { (it.offset - layout.viewportStartOffset).toFloat() } ?: 0f)
        val maximum = minOf((layout.viewportSize.height - draggedHeight).toFloat(),
            last?.let { (it.offset - layout.viewportStartOffset + it.size - draggedHeight).toFloat() }
                ?: Float.MAX_VALUE).coerceAtLeast(minimum)
        draggedTop = draggedTop.coerceIn(minimum, maximum)
        val restingTop = dragged.offset - layout.viewportStartOffset
        val direction = when {
            draggedTop < restingTop -> -1
            draggedTop > restingTop -> 1
            else -> return
        }
        val targetIndex = current + direction
        val targetKey = order.getOrNull(targetIndex) ?: return
        val target = visible.firstOrNull { it.key == targetKey } ?: return
        // Wait for the list to lay out the previous move before swapping again.
        if (target.index != dragged.index + direction) return
        val middle = target.offset - layout.viewportStartOffset + target.size / 2f
        val crossed = if (direction < 0) draggedTop < middle else draggedTop + draggedHeight > middle
        if (crossed) move(current, targetIndex)
    }

    fun translation(key: String): Float {
        if (key == settlingKey) return settlingOffset
        if (key != draggedKey) return 0f
        val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return draggedTop - (item.offset - scroll.layoutInfo.viewportStartOffset)
    }

    fun finish() {
        val key = draggedKey ?: return
        settlingStart = translation(key)
        settlingOffset = settlingStart
        settlingKey = key
        draggedKey = null
    }

    fun settle(progress: Float) {
        settlingOffset = settlingStart * (1f - progress)
        if (progress >= 1f) settlingKey = null
    }

    fun accessibilityActions(key: String): List<CustomAccessibilityAction> {
        val index = order.indexOf(key)
        return buildList {
            if (index > 0) add(CustomAccessibilityAction(AppLanguage.text("Move up")) { move(index, index - 1); true })
            if (index in 0 until order.lastIndex) add(CustomAccessibilityAction(AppLanguage.text("Move down")) { move(index, index + 1); true })
        }
    }

    private fun move(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        scroll.requestScrollToItem(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        saveOrder(order)
        onMove()
    }
}

internal fun Modifier.reorderLensProfiles(state: LensProfileReorderState, onStart: () -> Unit): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (state.scroll.isScrollInProgress || currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
        val key = state.keyAt(down.position.y) ?: return@awaitEachGesture
        var latest = down
        val held = withTimeoutOrNull(LENS_PROFILE_HOLD_MILLIS) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || event.changes.count { it.pressed } != 1) break
                latest = change
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop || change.isConsumed) break
                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
            }
            false
        } ?: true
        if (!held || !state.start(key, latest.position.y)) return@awaitEachGesture
        try {
            onStart()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null || !change.pressed || event.changes.count { it.pressed } != 1) break
                state.drag(change.position.y.coerceIn(0f, size.height.toFloat()))
            }
        } finally { state.finish() }
    }
}

@Composable
internal fun AnimateLensProfileReordering(state: LensProfileReorderState) {
    val density = LocalDensity.current
    LaunchedEffect(state, state.settlingKey) {
        if (state.settlingKey != null) animate(0f, 1f, animationSpec = tween(160)) { progress, _ -> state.settle(progress) }
    }
    LaunchedEffect(state, state.draggedKey, density) {
        if (state.draggedKey == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val speed = with(density) { 600.dp.toPx() }
        var previous = withFrameNanos { it }
        while (state.draggedKey != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val height = state.scroll.layoutInfo.viewportSize.height
            val direction = when {
                state.pointerY < edge -> -((edge - state.pointerY) / edge).coerceIn(0f, 1f)
                state.pointerY > height - edge -> ((state.pointerY - height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (abs(direction) > 0f) state.scroll.scrollBy(direction * speed * seconds)
            state.updateOrder()
        }
    }
}
