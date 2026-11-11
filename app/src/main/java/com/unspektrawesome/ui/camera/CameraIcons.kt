package com.unspektrawesome.ui.camera

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

@Composable
internal fun CameraIcon(@DrawableRes drawable: Int, modifier: Modifier, color: Color = CameraColors.Text) {
    Icon(painterResource(drawable), contentDescription = null, modifier = modifier, tint = color)
}
