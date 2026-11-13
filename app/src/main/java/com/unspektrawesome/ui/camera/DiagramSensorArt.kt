package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics

/** Colours of the sensor drawings. */
internal object SensorArt {
    val Dim = Color(0xFF6A6A6A)
    val Soft = Color(0xFFCFCFCF)
    val Detail = Color(0xFF8C8C8C)
    val Fill = Color(0xFF050505)
    val AccentFill = CameraColors.Accent.copy(alpha = .07f)
}

/**
 * A stage whose drawing only illustrates the rows below it. Its texts are hidden from accessibility
 * services so screen readers and tests reach each value once, through its row.
 */
@Composable
internal fun SensorArtStage(height: Dp, description: String? = null, content: @Composable BoxScope.() -> Unit) {
    DiagramStage(height, description = description) {
        Box(Modifier.matchParentSize().clearAndSetSemantics {}, content = content)
    }
}

/** Fits a width by height raster inside a box of at most [maxWidth] by [maxHeight], keeping its aspect ratio. */
internal fun sensorArtFit(width: Int, height: Int, maxWidth: Dp, maxHeight: Dp): Pair<Dp, Dp> {
    if (width <= 0 || height <= 0) return maxWidth to maxHeight
    val scale = minOf(maxWidth.value / width, maxHeight.value / height)
    return (width * scale).dp to (height * scale).dp
}

/** Small boxed word on a stage, lime when it is the current choice. */
@Composable
internal fun SensorArtTag(text: String, modifier: Modifier = Modifier, active: Boolean = false, translate: Boolean = true,
    dashed: Boolean = false) {
    val border = if (active) CameraColors.Accent else Color(0xFF3A3A3A)
    Box(modifier.heightIn(min = 18.dp)
        .drawBehind {
            drawRoundRect(border, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null))
        }
        .background(SensorArt.Fill, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center) {
        Text(text, translate = translate, color = if (active) CameraColors.Accent else SensorArt.Soft, fontSize = 9.5.sp,
            lineHeight = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** Small numbered marker for a stage, matching a numbered row below it. */
@Composable
internal fun SensorArtMarker(number: Int, x: Dp, y: Dp, active: Boolean = true) {
    DiagramNumber(number, Modifier.at(x, y), active)
}

/**
 * Compact text field in the diagram style: a small grey label over an outlined value box. The label
 * lives inside the field, so the field is found and read by its label.
 */
@Composable
internal fun SensorArtField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String? = null,
    error: String? = null,
    number: Int? = null,
    mono: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(CameraColors.Accent, CameraColors.Accent.copy(alpha = .3f))) {
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4, keyboardOptions = keyboardOptions,
            interactionSource = interaction, cursorBrush = SolidColor(CameraColors.Accent),
            textStyle = TextStyle(color = if (enabled) CameraColors.Text else Diagram.Hint, fontSize = 12.5.sp, lineHeight = 17.sp,
                fontFamily = if (mono) Diagram.Mono else null),
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
                .languageSemantics { language -> if (error != null) this.error(language.text(error)) },
            decorationBox = { field ->
                Column(Modifier.alpha(if (enabled) 1f else .5f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        number?.let { DiagramNumber(it) }
                        Text(label, color = if (focused) CameraColors.Accent else Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(Diagram.ControlShape)
                        .background(if (focused) Diagram.Glass else Color.Transparent)
                        .border(1.dp, when { error != null -> CameraColors.FocusFailed; focused -> CameraColors.Accent; else -> Diagram.Border },
                            Diagram.ControlShape)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, color = SensorArt.Detail, fontSize = 12.5.sp,
                            lineHeight = 17.sp, fontFamily = if (mono) Diagram.Mono else null)
                        field()
                    }
                    error?.let { Text(it, color = CameraColors.FocusFailed, fontSize = 11.sp, lineHeight = 15.sp) }
                }
            })
    }
}

/**
 * A switch row like [DiagramSwitchRow] that can keep its detail untranslated (file names) and can show its
 * title as a lime caption, used for cards that start with an on/off header.
 */
@Composable
internal fun SensorArtSwitchRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    translateDetail: Boolean = true,
    number: Int? = null,
    enabled: Boolean = true,
    caption: Boolean = false,
    toggleLabel: String = title,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .languageSemantics(mergeDescendants = true) { language -> contentDescription = language.text(toggleLabel) }
        .then(if (divider) Modifier.diagramHairline() else Modifier)
        .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        number?.let { DiagramNumber(it) }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else .45f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (caption) Text(title, color = CameraColors.Accent, fontSize = 10.5.sp, lineHeight = 14.sp,
                letterSpacing = .8.sp, fontWeight = FontWeight.SemiBold)
            else Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            detail?.let { Text(it, translate = translateDetail, color = SensorArt.Detail, fontSize = 11.sp, lineHeight = 15.sp,
                fontFamily = if (translateDetail) null else Diagram.Mono) }
        }
        Box(Modifier.size(40.dp, 28.dp), contentAlignment = Alignment.Center) { SensorArtSwitchVisual(checked, enabled) }
    }
}

@Composable
private fun SensorArtSwitchVisual(checked: Boolean, enabled: Boolean) {
    val position by animateDpAsState(if (checked) 17.dp else 3.dp, label = "Sensor art switch")
    Box(Modifier.size(34.dp, 20.dp).alpha(if (enabled) 1f else .4f).clip(CircleShape).background(Color(0xFF141414))
        .border(1.dp, if (checked) CameraColors.Accent else Color(0xFF3A3A3A), CircleShape)) {
        Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(position.roundToPx(), 0) }.size(12.dp)
            .background(if (checked) CameraColors.Accent else Diagram.Hint, CircleShape))
    }
}

/** Quiet grey text action such as "Remove", with a full-size touch area. */
@Composable
internal fun SensorArtQuietAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(Diagram.ControlShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
    }
}

/** Thin outlined group for one entry of a list, dashed while the entry is incomplete. */
internal fun Modifier.sensorArtGroup(ready: Boolean): Modifier = drawBehind {
    drawRoundRect(if (ready) Diagram.Border else Color(0xFF3A3A3A), cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = if (ready) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

/** Hatched square pattern for the sensor's skipped edge or black range, drawn with the diagram pen. */
internal fun DiagramPen.sensorHatch(x: Dp, y: Dp, w: Dp, h: Dp, active: Boolean) {
    if (w.value <= 0f || h.value <= 0f) return
    hatch(x, y, w, h, if (active) CameraColors.Accent.copy(alpha = .6f) else Color.White.copy(alpha = .35f), 4.dp)
}
