package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text

@Composable
internal fun ShutterModeLabel(mode: CaptureMode, color: Color, modifier: Modifier = Modifier) {
    val label = AppLanguage.text(mode.label)
    val style = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = .8.sp)
    Layout(modifier = modifier, content = {
        Text(label, translate = false, color = color, style = style,
            maxLines = 1, softWrap = false, textAlign = TextAlign.Start,
            modifier = Modifier.testTag("Shutter mode label"))
    }) { measurables, constraints ->
        val text = measurables.single().measure(Constraints())
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scale = minOf(1f, width.toFloat() / text.width.coerceAtLeast(1), height.toFloat() / text.height.coerceAtLeast(1))
        layout(width, height) {
            if (width > 0 && height > 0) text.placeWithLayer((width - text.width) / 2, (height - text.height) / 2) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}
