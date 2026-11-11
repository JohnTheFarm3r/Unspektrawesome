package com.unspektrawesome.ui.camera

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

internal object CameraPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()

    private class PressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed by mutableStateOf(false)
        override fun onAttach() {
            coroutineScope.launch {
                val presses = mutableSetOf<PressInteraction.Press>()
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses.add(interaction)
                        is PressInteraction.Release -> presses.remove(interaction.press)
                        is PressInteraction.Cancel -> presses.remove(interaction.press)
                    }
                    pressed = presses.isNotEmpty()
                }
            }
        }
        override fun onDetach() { pressed = false }
        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(CameraColors.Text.copy(alpha = .08f))
        }
    }
}
