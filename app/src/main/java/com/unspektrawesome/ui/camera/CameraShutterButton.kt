package com.unspektrawesome.ui.camera

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role

/** How long the shutter must be held to start a recording with the chosen controls locked. */
internal const val SHUTTER_LOCK_HOLD_MS = 1_000L

@Composable
internal fun CameraShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (onLongClick == null) {
        Box(modifier.clickable(interactionSource, LocalIndication.current, enabled, role = Role.Button, onClick = onClick),
            content = content)
        return
    }
    // A plain tap still fires on release; only a hold of SHUTTER_LOCK_HOLD_MS counts as a long press.
    val base = LocalViewConfiguration.current
    val holdConfiguration = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis = SHUTTER_LOCK_HOLD_MS
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
        Box(modifier.combinedClickable(interactionSource, LocalIndication.current, enabled = enabled,
            role = Role.Button, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick),
            content = content)
    }
}
