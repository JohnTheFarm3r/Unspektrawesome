package com.unspektrawesome.ui.camera

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.railGlass(cornerRadius: Dp = 14.4.dp): Modifier {
    val background = LocalCameraControlBackground.current
    return railGlass(background, cornerRadius)
}

private fun Modifier.railGlass(background: Color, cornerRadius: Dp): Modifier = clip(RoundedCornerShape(cornerRadius))
    .background(background).border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(cornerRadius))

internal fun Modifier.railSelection(enabled: Boolean): Modifier = drawWithCache {
    val inset = 2.4.dp.toPx()
    val lensSize = Size(size.width - inset * 2, size.height)
    val radius = CornerRadius(10.2.dp.toPx())
    val sheen = Brush.linearGradient(
        0f to CameraColors.Text.copy(alpha = .07f), .45f to Color.Transparent,
        1f to CameraColors.Text.copy(alpha = .035f),
        start = Offset.Zero, end = Offset(size.width, size.height))
    val rim = Brush.verticalGradient(listOf(CameraColors.Text.copy(alpha = .11f),
        CameraColors.MenuBorder.copy(alpha = .02f), CameraColors.Text.copy(alpha = .08f)))
    val glow = Brush.radialGradient(
        listOf(CameraColors.Accent.copy(alpha = if (enabled) .1f else 0f), Color.Transparent),
        center = Offset(size.width / 2, size.height / 2), radius = size.width * .8f)
    onDrawBehind {
        drawRect(glow, topLeft = Offset(-inset * 2, -size.height / 2),
            size = Size(size.width + inset * 4, size.height * 2))
        drawRoundRect(CameraColors.RailSelection, Offset(inset, 0f), lensSize, radius)
        drawRoundRect(sheen, Offset(inset, 0f), lensSize, radius)
        drawRoundRect(rim, Offset(inset, 0f), lensSize, radius, style = Stroke(.5.dp.toPx()))
        if (enabled) listOf(inset - .6.dp.toPx(), size.width - inset + .6.dp.toPx()).forEach { x ->
            drawLine(CameraColors.Accent, Offset(x, center.y - 3.dp.toPx()),
                Offset(x, center.y + 3.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
        }
    }
}
