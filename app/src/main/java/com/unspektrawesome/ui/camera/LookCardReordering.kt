package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal const val LOOK_CARD_HOLD_MILLIS = 1_500L
internal const val LOOK_TAB_HOLD_MILLIS = 500L

internal class LookCardReorderState(
    initialOrder: List<String>,
    private val pinnedCount: Int,
    val scroll: LazyListState,
    private val saveOrder: (List<String>) -> Unit,
    private val onMove: () -> Unit,
    private val onFinished: () -> Unit,
    private val itemWidth: ((String) -> Int)? = null,
) {
    var order by mutableStateOf(initialOrder)
        private set
    var draggedKey by mutableStateOf<String?>(null)
        private set
    var settlingKey by mutableStateOf<String?>(null)
        private set
    private var draggedLeft by mutableFloatStateOf(0f)
    private var settlingProgress by mutableFloatStateOf(0f)
    private var settlingStart = 0f
    private var settlingTarget = 0f
    var pointerX by mutableFloatStateOf(0f)
        private set
    private var orderAtStart = initialOrder

    private fun slotLeft(index: Int): Float {
        val layout = scroll.layoutInfo
        val reference = layout.visibleItemsInfo.firstOrNull() ?: return 0f
        val distance = if (itemWidth == null) {
            (index - reference.index) * (reference.size + layout.mainAxisItemSpacing)
        } else {
            val range = minOf(index, reference.index) until maxOf(index, reference.index)
            val length = range.sumOf { itemWidth.invoke(order[it]) + layout.mainAxisItemSpacing }
            if (index < reference.index) -length else length
        }
        return (reference.offset - layout.viewportStartOffset + distance).toFloat()
    }

    fun keyAt(x: Float): String? {
        val layout = scroll.layoutInfo
        return layout.visibleItemsInfo.firstOrNull {
            val left = it.offset - layout.viewportStartOffset
            x >= left && x < left + it.size && it.index >= pinnedCount
        }?.key as? String
    }

    fun start(key: String, x: Float): Boolean {
        val index = order.indexOf(key)
        if (index < pinnedCount || order.size - pinnedCount < 2) return false
        stopSettling()
        draggedLeft = slotLeft(index)
        orderAtStart = order
        pointerX = x
        draggedKey = key
        return true
    }

    fun drag(x: Float) {
        draggedLeft += x - pointerX
        pointerX = x
        updateOrder()
    }

    fun updateOrder() {
        val key = draggedKey ?: return
        val width = itemWidth?.invoke(key) ?: scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return
        val lastWidth = itemWidth?.invoke(order.last()) ?: width
        draggedLeft = draggedLeft.coerceIn(slotLeft(pinnedCount), slotLeft(order.lastIndex) + lastWidth - width)
        val spacing = scroll.layoutInfo.mainAxisItemSpacing
        var index = order.indexOf(key)
        while (index >= pinnedCount) {
            val delta = draggedLeft - slotLeft(index)
            val target = when {
                index < order.lastIndex && delta > (itemWidth?.invoke(order[index + 1]) ?: width) / 2f + spacing -> index + 1
                index > pinnedCount && delta < -((itemWidth?.invoke(order[index - 1]) ?: width) / 2f + spacing) -> index - 1
                else -> break
            }
            move(index, target)
            index = target
        }
    }

    fun translation(key: String): Float {
        val left = when (key) {
            draggedKey -> draggedLeft
            settlingKey -> settlingStart + (settlingTarget - settlingStart) * settlingProgress
            else -> return 0f
        }
        val layout = scroll.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        // Follow the measured slot, including the frame when its index changes.
        return left - (item.offset - layout.viewportStartOffset)
    }

    fun finish() {
        val key = draggedKey ?: return
        val changed = order != orderAtStart
        settlingStart = draggedLeft
        settlingTarget = slotLeft(order.indexOf(key))
        settlingProgress = 0f
        settlingKey = key
        draggedKey = null
        if (changed) onFinished()
    }

    fun settle(progress: Float) {
        settlingProgress = progress
        if (progress >= 1f) stopSettling()
    }

    fun stopSettling() { settlingKey = null }

    fun accessibilityActions(key: String): List<CustomAccessibilityAction> {
        val index = order.indexOf(key)
        if (index < pinnedCount) return emptyList()
        return buildList {
            if (index > pinnedCount) add(CustomAccessibilityAction(AppLanguage.text("Move left")) { move(index, index - 1); onFinished(); true })
            if (index < order.lastIndex) add(CustomAccessibilityAction(AppLanguage.text("Move right")) { move(index, index + 1); onFinished(); true })
        }
    }

    private fun move(from: Int, to: Int) {
        // Keep the same visible slots when the first visible card changes position.
        scroll.requestScrollToItem(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        saveOrder(order.drop(pinnedCount))
        onMove()
    }
}

internal fun Modifier.reorderLookCards(
    state: LookCardReorderState,
    rotationDegrees: Float,
    holdMillis: Long = LOOK_CARD_HOLD_MILLIS,
    onStart: () -> Unit,
): Modifier = pointerInput(state, rotationDegrees, holdMillis) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.stopSettling()
        if (state.scroll.isScrollInProgress || currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
        val key = state.keyAt(down.position.x) ?: return@awaitEachGesture
        val tolerance = minOf(viewConfiguration.touchSlop / 2f, 4.dp.toPx())
        var latest = down
        var travel = 0f
        val held = withTimeoutOrNull(holdMillis) {
            var cancelled = false
            while (!cancelled) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || event.changes.count { it.pressed } != 1) break
                travel += (change.position - latest.position).getDistance()
                latest = change
                cancelled = (change.position - down.position).getDistance() > tolerance ||
                    travel > viewConfiguration.touchSlop || change.isConsumed
                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) cancelled = true
            }
            false
        } ?: true
        if (!held || !state.start(key, latest.position.x)) return@awaitEachGesture
        try {
            onStart()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null || !change.pressed || event.changes.count { it.pressed } != 1) break
                state.drag(change.position.x.coerceIn(0f, size.width.toFloat()))
            }
        } finally { state.finish() }
    }
}

@Composable
internal fun AnimateReorderingCards(state: LookCardReorderState) {
    val density = LocalDensity.current
    LaunchedEffect(state, state.settlingKey) {
        if (state.settlingKey == null) return@LaunchedEffect
        animate(0f, 1f, animationSpec = tween(160)) { progress, _ -> state.settle(progress) }
    }
    LaunchedEffect(state, state.draggedKey, density) {
        if (state.draggedKey == null) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val speed = with(density) { 630.dp.toPx() }
        var previous = withFrameNanos { it }
        while (state.draggedKey != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val width = state.scroll.layoutInfo.viewportSize.width
            val direction = when {
                state.pointerX < edge -> -((edge - state.pointerX) / edge).coerceIn(0f, 1f)
                state.pointerX > width - edge -> ((state.pointerX - width + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (abs(direction) > 0f) {
                state.scroll.scrollBy(direction * speed * seconds)
                state.updateOrder()
            }
        }
    }
}

@Composable
internal fun CardReorderIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Move card hint")
    val pulse by transition.animateFloat(.35f, 1f,
        infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "Move card pulse")
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(64.dp, 28.dp)
        .background(CameraColors.MenuSelectedCard.copy(alpha = .98f), shape)
        .border(1.dp, CameraColors.Accent.copy(alpha = pulse), shape)
        .languageSemantics { language ->
            contentDescription = language.text("Move left or right. Release to save order.")
            liveRegion = LiveRegionMode.Polite
        }) {
        Canvas(Modifier.size(64.dp, 28.dp)) {
            val color = CameraColors.Accent.copy(alpha = pulse)
            val y = size.height / 2
            val center = size.width / 2
            for (direction in listOf(-1, 1)) {
                val tip = center + direction * (17f + pulse * 3f).dp.toPx()
                drawLine(color, Offset(center + direction * 6.dp.toPx(), y), Offset(tip, y), 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(tip - direction * 4.dp.toPx(), y - 4.dp.toPx()), Offset(tip, y), 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(tip - direction * 4.dp.toPx(), y + 4.dp.toPx()), Offset(tip, y), 1.7.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(color, 2.dp.toPx(), Offset(center, y))
        }
    }
}
