package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BoxScope.CameraModeOverlay(mode: CaptureMode, rotation: Float, enabled: Boolean = true) {
    var previous by remember { mutableStateOf(mode) }
    var visible by remember { mutableStateOf(false) }
    val shade = remember { Animatable(0f) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(mode, enabled) {
        if (!enabled) {
            previous = mode
            visible = false
            shade.animateTo(0f, tween(100))
            return@LaunchedEffect
        }
        if (previous == mode) return@LaunchedEffect
        previous = mode
        visible = true
        launch {
            shade.animateTo(.35f, tween(100))
            shade.animateTo(0f, tween(280))
        }
        delay(accessibility?.calculateRecommendedTimeoutMillis(900L, containsText = true) ?: 900L)
        visible = false
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shade.value }.background(CameraColors.Background))
    AnimatedVisibility(visible && enabled, modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(tween(140)), exit = fadeOut(tween(if (enabled) 220 else 100))) {
        RotatingCameraPanel(rotation) {
            AnimatedContent(mode, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
                label = "CaptureMode") { displayed ->
                Text(displayed.label, color = CameraColors.Accent, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("Camera mode overlay")
                        .background(CameraColors.MenuBackground, CameraStyle.PanelShape)
                        .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
