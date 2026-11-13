package com.unspektrawesome.language

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

internal val LocalAppLanguage = staticCompositionLocalOf { LanguageState() }

/**
 * True inside drawings whose labels only repeat or illustrate real controls. Their text is left out of
 * the accessibility tree, so screen readers and tests find each label once, on its control.
 */
internal val LocalDecorativeText = staticCompositionLocalOf { false }

/** Text is laid out at its own width (see [OwnTextWidth]) everywhere except the camera screen, which keeps its original layout. */
internal val LocalOwnTextWidth = staticCompositionLocalOf { true }

@Composable
internal fun LanguageText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current,
    translate: Boolean = true,
    arguments: Map<String, Any?>? = null,
) {
    val renderer = LocalAppLanguage.current.renderer
    val display = if (arguments != null) renderer.format(text, arguments) else if (translate) renderer.text(text) else text
    val sized = modifier.then(if (LocalOwnTextWidth.current) OwnTextWidth else Modifier)
        .then(if (LocalDecorativeText.current) Modifier.clearAndSetSemantics { } else Modifier)
    androidx.compose.material3.Text(text = display,
        modifier = sized, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout,
        style = if (style.textDirection == TextDirection.Unspecified) style.copy(textDirection = TextDirection.Content) else style)
}

/**
 * Lays text out at its own width within the space offered. Text narrower than that space is otherwise
 * laid out across all of it, which shifts centered labels and clips them inside their box.
 * Intrinsic sizes are passed through unchanged.
 */
private object OwnTextWidth : ModifierNodeElement<OwnTextWidthNode>() {
    override fun create() = OwnTextWidthNode()
    override fun update(node: OwnTextWidthNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "ownTextWidth" }
    override fun hashCode() = javaClass.hashCode()
    override fun equals(other: Any?) = other === this
}

private class OwnTextWidthNode : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val fixed = if (constraints.hasFixedWidth || !constraints.hasBoundedWidth) constraints else {
            val width = measurable.maxIntrinsicWidth(constraints.maxHeight).coerceIn(constraints.minWidth, constraints.maxWidth)
            constraints.copy(minWidth = width, maxWidth = width)
        }
        val placeable = measurable.measure(fixed)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.minIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.maxIntrinsicHeight(width)
}
