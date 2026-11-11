package com.unspektrawesome.language

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

@Composable
internal fun Modifier.languageSemantics(
    mergeDescendants: Boolean = false,
    properties: SemanticsPropertyReceiver.(LanguageRenderer) -> Unit,
): Modifier {
    // Capture language during composition so detached semantics nodes do not observe language changes.
    val renderer = LocalAppLanguage.current.renderer
    return semantics(mergeDescendants) { properties(renderer) }
}
