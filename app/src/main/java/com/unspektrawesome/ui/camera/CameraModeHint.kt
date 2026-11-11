package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import kotlinx.coroutines.delay
import kotlin.math.abs

internal const val MODE_SWIPE_HINT_REVISION = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.CameraModeHint(visible: Boolean, mode: CaptureMode, rotation: Float, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(visible, rotation) {
        if (visible) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(4_000L, containsIcons = true, containsText = true) ?: 4_000L)
            finished()
        }
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.Center).padding(16.dp),
        enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
        val sideways = abs(rotation) == 90f
        RotatingCameraPanel(rotation, maxWidth = if (sideways) 380.dp else 300.dp) {
            Column(Modifier.testTag("Camera mode hint").background(CameraColors.MenuBackground, CameraStyle.PanelShape)
                .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape).padding(CameraStyle.PanelPadding)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val motion by rememberInfiniteTransition(label = "Mode swipe hint").animateFloat(0f, 1f,
                    infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "Swipe arrows")
                Canvas(Modifier.size(if (sideways) 32.dp else 80.dp, if (sideways) 40.dp else 28.dp)) {
                    fun point(along: Float, across: Float) = if (sideways)
                        Offset(size.width / 2 + across, size.height / 2 + along)
                    else Offset(size.width / 2 + along, size.height / 2 + across)
                    val distance = (if (sideways) 14.dp else 24.dp).toPx() + motion * 4.dp.toPx()
                    val wing = 6.dp.toPx()
                    for (direction in listOf(-1, 1)) {
                        val tip = direction * distance
                        for (side in listOf(-1, 1)) drawLine(CameraColors.Accent,
                            point(tip - direction * wing, side * wing), point(tip, 0f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
                Text(if (sideways) "Swipe up or down to switch modes." else "Swipe left or right to switch modes.",
                    modifier = Modifier.fillMaxWidth(), color = CameraColors.Text,
                    fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    CaptureMode.entries.forEach { item ->
                        Text(item.label, color = if (item == mode) CameraColors.Accent else CameraColors.Muted,
                            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
