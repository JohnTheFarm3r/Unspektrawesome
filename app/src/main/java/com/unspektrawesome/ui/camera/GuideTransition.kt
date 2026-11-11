package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

internal val LocalGuideActionsEnabled = compositionLocalOf { true }

@Composable
internal fun <T : Any> GuideTransition(step: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val visibility = remember { MutableTransitionState<T?>(null) }
    visibility.targetState = step
    val transition = rememberTransition(visibility)
    transition.AnimatedContent(modifier = modifier.fillMaxSize(), transitionSpec = {
        (fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(140))).using(null)
    }) { displayedStep ->
        if (displayedStep != null) {
            val active = displayedStep == step
            CompositionLocalProvider(LocalGuideActionsEnabled provides (active && visibility.isIdle)) {
                Box(Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics {})) {
                    content(displayedStep)
                }
            }
        }
    }
}
