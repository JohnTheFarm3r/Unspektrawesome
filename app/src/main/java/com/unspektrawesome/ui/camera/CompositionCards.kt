package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.AppLanguage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class CompositionCard(
    val key: String,
    val label: String,
    val description: String,
    val selected: Boolean,
    val subtitle: String? = null,
    val translateLabel: Boolean = true,
    val onClick: () -> Unit,
    val onEdit: (() -> Unit)? = null,
    val drawing: DrawScope.(Color) -> Unit,
)

@Composable
internal fun CompositionCards(cards: List<CompositionCard>, state: LazyListState, tag: String, modifier: Modifier = Modifier) {
    val fling = rememberPanelFlingBehavior(horizontal = true)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(modifier.fillMaxWidth()) {
        val cardWidth = 80.dp * kotlin.math.sqrt(fontScale)
        LazyRow(state = state, flingBehavior = fling, overscrollEffect = null,
            modifier = Modifier.fillMaxWidth().trackPanelFling(fling).testTag(tag).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(cards, key = { it.key }) { card ->
                VideoCropCard(if (card.translateLabel) AppLanguage.text(card.label) else card.label,
                    card.subtitle?.let(AppLanguage::text), 1f, card.selected, true, card.key, cardWidth,
                    minimumHeight = 68.dp,
                    description = card.description, onEdit = card.onEdit, drawing = card.drawing, onClick = card.onClick)
            }
        }
    }
}

internal fun DrawScope.drawCropCard(aspect: Float, ink: Color) {
    val padding = 1.dp.toPx()
    val width = minOf(size.width - padding * 2, (size.height - padding * 2) * aspect)
    val height = width / aspect
    drawRect(ink.copy(alpha = .08f), Offset((size.width - width) / 2, (size.height - height) / 2), Size(width, height))
    drawRect(ink, Offset((size.width - width) / 2, (size.height - height) / 2), Size(width, height), style = Stroke(1.dp.toPx()))
}

internal fun DrawScope.drawCustomCropCard(ink: Color) {
    drawCropCard(1f, ink)
    val arm = size.height * .18f
    drawLine(ink, center - Offset(arm, arm), center + Offset(arm, arm), 1.2.dp.toPx())
    drawLine(ink, center + Offset(arm, -arm), center + Offset(-arm, arm), 1.2.dp.toPx())
}
