package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun RotatingCameraPanel(rotation: Float, modifier: Modifier = Modifier, maxWidth: Dp = 380.dp,
    unboundedHeight: Boolean = false,
    content: @Composable () -> Unit) {
    val animatedRotation = rememberAnimatedCameraRotation(rotation)
    val sideways = abs(rotation) == 90f
    Layout(modifier = modifier, content = {
        CompositionLocalProvider(LocalCameraRotation provides 0f, content = content)
    }) { measurables, constraints ->
        val availableWidth = if (sideways) constraints.maxHeight else constraints.maxWidth
        val availableHeight = if (sideways) constraints.maxWidth else constraints.maxHeight
        val child = measurables.single().measure(Constraints(
            maxWidth = minOf(maxWidth.roundToPx(), availableWidth), maxHeight = if (unboundedHeight) Constraints.Infinity else availableHeight,
        ))
        val radians = Math.toRadians(animatedRotation.toDouble())
        val horizontal = abs(cos(radians)).toFloat()
        val vertical = abs(sin(radians)).toFloat()
        val rotatedWidth = child.width * horizontal + child.height * vertical
        val rotatedHeight = child.height * horizontal + child.width * vertical
        val scale = if (child.width == 0 || child.height == 0 || constraints.maxWidth == 0 || constraints.maxHeight == 0) 1f
            else minOf(1f, constraints.maxWidth / rotatedWidth.coerceAtLeast(1f),
                constraints.maxHeight / rotatedHeight.coerceAtLeast(1f))
        val width = constraints.constrainWidth(ceil(rotatedWidth * scale).toInt())
        val height = constraints.constrainHeight(ceil(rotatedHeight * scale).toInt())
        layout(width, height) {
            child.placeWithLayer((width - child.width) / 2, (height - child.height) / 2) {
                rotationZ = animatedRotation
                scaleX = scale
                scaleY = scale
            }
        }
    }
}
