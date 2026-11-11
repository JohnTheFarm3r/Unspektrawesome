package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.LanguageText as Text
import kotlin.math.roundToInt

@Composable
internal fun CameraModeRail(
    mode: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderer = LocalAppLanguage.current.renderer
    val labels = CaptureMode.entries.map { renderer.text(it.label) }
    val density = LocalDensity.current
    val textStyle = TextStyle(fontFamily = CameraTypography.Readout, fontSize = 11.sp,
        lineHeight = 16.sp, letterSpacing = .4.sp)
    val measurer = rememberTextMeasurer()
    val sizes = labels.map { measurer.measure(it, textStyle, softWrap = false, maxLines = 1).size }
    val labelWidths = sizes.map { size ->
        with(density) { size.width.toDp() + 24.dp }.coerceAtLeast(48.dp)
    }
    val widths = labelWidths.mapIndexed { index, width -> maxOf(width, labelWidths[labelWidths.lastIndex - index]) }
    val height = with(density) { sizes.maxOf { it.height }.toDp() + 16.dp }
        .coerceAtLeast(44.dp)
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp)
        .testTag("Capture mode rail")) {
        val gap = 4.dp
        val extra = ((maxWidth - widths.fold(0.dp) { sum, width -> sum + width } - gap * (widths.size - 1)) / widths.size)
            .coerceAtLeast(0.dp)
        val cells = widths.map { it + extra }
        val viewportWidth = maxWidth
        LaunchedEffect(mode, labels, cells, viewportWidth, scroll.maxValue) {
            val center = cells.take(mode.ordinal).fold(0.dp) { sum, width -> sum + width } +
                gap * mode.ordinal + cells[mode.ordinal] / 2
            val offset = with(density) { (center - viewportWidth / 2).toPx().roundToInt() }
            scroll.animateScrollTo(offset.coerceIn(0, scroll.maxValue))
        }
        androidx.compose.foundation.layout.Row(Modifier.horizontalScroll(scroll, overscrollEffect = null).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            CaptureMode.entries.forEachIndexed { index, candidate ->
                val selected = candidate == mode
                val color = if (selected) CameraColors.Accent else CameraColors.Muted
                Box(Modifier.width(cells[index]).height(height)
                    .selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onSelect(candidate) })
                    .testTag("Capture mode ${candidate.name}")
                    .languageSemantics { language -> contentDescription = language.text(candidate.label) },
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(candidate.label, style = textStyle, maxLines = 1, softWrap = false,
                            color = color.copy(alpha = if (enabled) 1f else .45f))
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.width(18.dp).height(1.dp).then(if (selected)
                            Modifier.background(CameraColors.Accent.copy(alpha = if (enabled) 1f else .45f)) else Modifier))
                    }
                }
            }
        }
    }
}
