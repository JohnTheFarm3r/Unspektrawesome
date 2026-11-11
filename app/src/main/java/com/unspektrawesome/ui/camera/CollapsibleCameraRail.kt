package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal const val RAIL_COLLAPSE_HOLD_MILLIS = 500L
private const val RAIL_AUTO_COLLAPSE_MILLIS = 3_000L

@Composable
internal fun CollapsibleCameraRail(
    title: String,
    description: String,
    valueLabel: String,
    valueDescription: String,
    supported: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (onInteraction: () -> Unit) -> Unit,
) {
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    var touching by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, touching, interaction, valueLabel) {
        if (expanded && !touching) {
            delay(RAIL_AUTO_COLLAPSE_MILLIS)
            currentOnExpandedChange(false)
        }
    }
    val haptics = LocalHapticFeedback.current
    val collapse by rememberUpdatedState {
        onExpandedChange(false)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(modifier.width(42.dp).pointerInput(Unit) {
        try {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                interaction++
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                touching = false
                interaction++
            }
        } finally {
            touching = false
        }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!currentExpanded || currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
            val held = withTimeoutOrNull(RAIL_COLLAPSE_HOLD_MILLIS) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || event.changes.count { it.pressed } != 1) break
                    val position = change.position
                    if ((position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        position.x !in 0f..size.width.toFloat() || position.y !in 0f..size.height.toFloat()) break
                }
                false
            } ?: true
            if (!held) return@awaitEachGesture
            collapse()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }.languageSemantics { language ->
        if (expanded) onLongClick(label = language.format("Collapse {title}", mapOf("title" to (title)))) { collapse(); true }
    }) {
        AnimatedContent(
            targetState = expanded,
            modifier = Modifier.testTag("$description transition"),
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                if (targetState) {
                    fadeIn(tween(120, delayMillis = 40)) togetherWith fadeOut(tween(80)) using
                        SizeTransform { _, _ -> tween(200, easing = FastOutSlowInEasing) }
                } else {
                    fadeIn(tween(160, delayMillis = 120)) togetherWith
                        fadeOut(tween(280, easing = FastOutSlowInEasing)) using
                        SizeTransform(clip = false) { _, _ -> tween(280, easing = FastOutSlowInEasing) }
                }
            },
            label = "$description expansion",
        ) { open ->
            if (open) {
                Box(Modifier.testTag("$description expanded")) { content { interaction++ } }
            } else {
                Box(Modifier.size(42.dp).testTag("$description button")
                    .railGlass(if (LocalDensity.current.fontScale > 1.3f) 12.dp else 21.dp)
                    .clickable(role = Role.Button, onClickLabel = AppLanguage.format("Expand {title}", mapOf("title" to (title)))) { onExpandedChange(true) }
                    .languageSemantics { language ->
                        contentDescription = language.text(description)
                        stateDescription = language.format("Collapsed, {valueDescription}", mapOf("valueDescription" to (valueDescription)))
                    }, contentAlignment = Alignment.Center) {
                    RotatingCameraPanel(LocalCameraRotation.current) {
                        CameraRailReadings(title, valueLabel, if (supported) CameraColors.Accent else CameraColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CameraRailReadings(title: String, value: String? = null, valueColor: Color = CameraColors.Accent) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val inherited = LocalTextStyle.current
    val label = AppLanguage.text(title)
    val titleStyle = inherited.merge(TextStyle(fontFamily = CameraTypography.Readout, fontSize = 8.4.sp, lineHeight = 13.2.sp))
    val valueStyle = inherited.merge(TextStyle(fontFamily = CameraTypography.Readout, fontSize = 9.6.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium))
    val fit = remember(label, value, density, inherited) {
        fun fits(scale: Float): Boolean {
            val heading = measurer.measure(label, titleStyle.copy(fontSize = titleStyle.fontSize * scale,
                lineHeight = titleStyle.lineHeight * scale), softWrap = false).size
            val reading = value?.let { measurer.measure(it, valueStyle.copy(fontSize = valueStyle.fontSize * scale,
                lineHeight = valueStyle.lineHeight * scale), softWrap = false).size }
            return with(density) { maxOf(heading.width, reading?.width ?: 0) <= 40.dp.roundToPx() &&
                heading.height + (reading?.height ?: 0) <= 38.dp.roundToPx() }
        }
        if (fits(1f)) 1f else {
            var lower = 0f
            var upper = 1f
            repeat(12) {
                val scale = (lower + upper) / 2
                if (fits(scale)) lower = scale else upper = scale
            }
            lower
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, Modifier.fillMaxWidth(), translate = false, color = CameraColors.Muted, textAlign = TextAlign.Center,
            style = titleStyle.copy(fontSize = titleStyle.fontSize * fit, lineHeight = titleStyle.lineHeight * fit),
            maxLines = 1, softWrap = false)
        if (value != null) Text(value, Modifier.fillMaxWidth(), translate = false, color = valueColor, textAlign = TextAlign.Center,
            style = valueStyle.copy(fontSize = valueStyle.fontSize * fit, lineHeight = valueStyle.lineHeight * fit),
            maxLines = 1, softWrap = false)
    }
}
