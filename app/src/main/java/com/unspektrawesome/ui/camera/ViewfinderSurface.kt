package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CameraViewport(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, content = content)
}

@Composable
internal fun CameraDock(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
internal fun ViewfinderSurface(
    aspectRatio: Float,
    viewport: CameraArea,
    modifier: Modifier = Modifier,
    surfaceHeight: Dp? = null,
    cropAspectRatio: Float = aspectRatio,
    content: @Composable BoxScope.() -> Unit,
) {
    val sizing = if (surfaceHeight == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        .wrapContentHeight(Alignment.Top, unbounded = true).height(surfaceHeight)
    BoxWithConstraints(modifier.then(sizing)) {
        val surfaceWidth = maxWidth
        val stableHeight = maxHeight
        val native = CameraLayout.bottomAlignedViewfinder(surfaceWidth.value, stableHeight.value, aspectRatio)
        val source = CameraLayout.fittedViewfinder(native, cropAspectRatio)
        val target = viewport
        val scale = maxOf(target.width / source.width, target.height / source.height)
        // The visible frame animates around a stable RAW surface without stretching the image.
        Box(Modifier.offset(target.left.dp, target.top.dp).wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(target.width.dp, target.height.dp).clipToBounds()) {
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(surfaceWidth, stableHeight).graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
            translationX = (target.width / 2 - (source.left + source.width / 2) * scale).dp.toPx()
            translationY = (target.height / 2 - (source.top + source.height / 2) * scale).dp.toPx()
        }, content = content)
        }
    }
}
