package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

@Composable
internal fun OrientedLookPanel(
    landscape: Boolean,
    rotation: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animatedRotation = rememberAnimatedCameraRotation(if (landscape) rotation else 0f)
    val sideways by animateFloatAsState(if (landscape) 1f else 0f,
        tween(300, easing = FastOutSlowInEasing), label = "Panel orientation")
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val childConstraints = Constraints.fixed(
            (width + (height - width) * sideways).roundToInt(),
            (height + (width - height) * sideways).roundToInt(),
        )
        val child = measurables.single().measure(childConstraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.placeWithLayer(
                (constraints.maxWidth - child.width) / 2,
                (constraints.maxHeight - child.height) / 2,
            ) { rotationZ = animatedRotation }
        }
    }
}
