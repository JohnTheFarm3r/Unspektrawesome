package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import kotlin.math.abs

@Composable
internal fun BoxScope.CameraCropPanel(rotation: Float, top: Dp, sides: Dp, bottom: Dp, content: @Composable () -> Unit) {
    RotatingCameraPanel(rotation, Modifier
        .align(if (abs(rotation) == 90f) Alignment.Center else Alignment.TopCenter)
        .padding(start = sides, end = sides, top = top, bottom = bottom), maxWidth = 300.dp) {
        val shape = CameraStyle.PanelShape
        Box(Modifier.fillMaxWidth().testTag("Camera crop panel").clip(shape).background(CameraColors.QuickMenuBackground)
            .border(1.dp, CameraColors.MenuBorder, shape)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }) { content() }
    }
}

@Composable
internal fun CameraCropHeader(title: String, onClose: () -> Unit, closeModifier: Modifier = Modifier,
    done: Boolean = false, subtitle: (@Composable () -> Unit)? = null) {
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val stacked = maxWidth < 270.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
            val actions: @Composable () -> Unit = {
                if (done) Box(Modifier.heightIn(min = 36.dp).clip(CameraStyle.ControlShape)
                    .border(1.dp, CameraColors.Accent, CameraStyle.ControlShape).clickable(onClick = onClose)
                    .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text("Done", color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Box(closeModifier.size(36.dp).clip(CameraStyle.ControlShape)
                    .border(1.dp, CameraColors.MenuBorder, CameraStyle.ControlShape).clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center) {
                    CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_x, Modifier.size(16.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).border(1.dp, CameraColors.Muted.copy(alpha = .6f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_crop, Modifier.size(12.dp), CameraColors.Muted)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(com.unspektrawesome.language.AppLanguage.text(title).uppercase(java.util.Locale.ROOT), translate = false,
                            color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                        subtitle?.invoke()
                    }
                    if (!stacked) actions()
                }
                if (stacked) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
    }
}
