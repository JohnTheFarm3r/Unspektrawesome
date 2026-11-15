package com.unspektrawesome.ui.camera

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import com.unspektrawesome.settings.AppDisplaySize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Distance of a control from the top of the visible page before a size change, kept until the page is laid out again. */
private data class SizeAnchor(val control: String, val visibleY: Float, val density: Float)

/** Interface size of the whole app: a fixed standard size, or Auto to follow the phone's display size. */
@Composable
internal fun AppSizeSettings(scroll: ScrollState) {
    val context = LocalContext.current
    val size by AppDisplaySize.state.collectAsState()
    val density = LocalDensity.current.density
    val currentDensity by rememberUpdatedState(density)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The phone's own size, measured from the density it gives the app while Auto is on.
    val systemDensity = context.resources.displayMetrics.density
    val systemPercent = remember(context, systemDensity, density) { AppDisplaySize.systemPercent(context, systemDensity) }

    // The page scrolls after a size change so the control being used stays where it was on screen.
    val positions = remember { mutableMapOf<String, Float>() }
    var anchor by remember { mutableStateOf<SizeAnchor?>(null) }
    LaunchedEffect(anchor) {
        // A choice that keeps the same size never relays the page, so the anchor must not linger.
        if (anchor != null) { delay(1500); anchor = null }
    }
    fun apply(control: String, next: AppDisplaySize.State) {
        if (next == size) return
        positions[control]?.let { anchor = SizeAnchor(control, it - scroll.value, density) }
        AppDisplaySize.update(context, next)
    }
    // Positions are measured inside the scrolling page, so this also works while the page is shown sideways.
    fun Modifier.keepInPlace(control: String) = onGloballyPositioned { coordinates ->
        val y = coordinates.positionInParent().y
        positions[control] = y
        val held = anchor
        if (held != null && held.control == control && held.density != currentDensity) {
            anchor = null
            val shift = y - held.visibleY - scroll.value
            if (abs(shift) > .5f) scope.launch { scroll.scrollBy(shift) }
        }
    }

    // The slider moves between steps at once, but the app resizes only after the finger rests on a step.
    var pending by remember { mutableIntStateOf(size.percent) }
    LaunchedEffect(size.percent) { pending = size.percent }
    LaunchedEffect(pending) {
        if (pending != size.percent) {
            delay(500)
            apply("slider", size.copy(percent = pending))
        }
    }
    fun select(percent: Int) {
        if (percent == pending) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        pending = percent
    }

    SetupSection("App size")
    SetupAppSizeStage(size.auto, pending, systemPercent)
    SetupChoice("Interface size", listOf("Standard", "Auto"), if (size.auto) 1 else 0, Modifier.keepInPlace("choice"),
        number = 1) { index ->
        apply("choice", size.copy(auto = index == 1))
    }
    if (!size.auto) {
        val steps = AppDisplaySize.STEPS
        DiagramSliderRow("Size", steps.indexOf(pending).coerceAtLeast(0).toFloat(), "{value}%",
            { select(steps[it.roundToInt().coerceIn(0, steps.lastIndex)]) },
            Modifier.then(settingsSearchTarget("Size")).keepInPlace("slider").testTag("App size slider"),
            range = 0f..steps.lastIndex.toFloat(), step = 1f, number = 2,
            description = "App size", valueArguments = mapOf("value" to pending),
            onReset = { select(100) }, stepLabels = steps.map { it.toString() })
        DiagramHint("Standard size is 100%. It looks the same on every phone and ignores the phone's display size setting.")
    } else DiagramHint("The app follows the phone's display size setting.")
}
