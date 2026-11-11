package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun <T> CameraPageTransition(
    page: T,
    modifier: Modifier = Modifier,
    order: (T) -> Int,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(targetState = page, modifier = modifier, label = "Settings navigation",
        transitionSpec = {
            val direction = if (order(targetState) >= order(initialState)) 1 else -1
            ((fadeIn(tween(240)) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 12 * direction })
                togetherWith (fadeOut(tween(180)) + slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 12 * direction }))
                .using(SizeTransform(clip = false))
        }) { target -> content(target) }
}
