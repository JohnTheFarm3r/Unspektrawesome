package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import com.unspektrawesome.camera.CameraOrientation

@Composable
internal fun rememberAnimatedCameraRotation(target: Float): Float {
    val rotation = remember { Animatable(target) }
    LaunchedEffect(target) {
        rotation.animateTo(
            CameraOrientation.nearestEquivalentRotation(rotation.value, target),
            tween(300, easing = FastOutSlowInEasing),
        )
    }
    return rotation.value
}

@Composable
internal fun Modifier.animatedCameraRotation(target: Float): Modifier =
    rotate(rememberAnimatedCameraRotation(target))
