package com.unspektrawesome.language

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun LanguageRoot(content: @Composable () -> Unit) {
    val language by AppLanguage.state.collectAsState()
    CompositionLocalProvider(LocalAppLanguage provides language,
        LocalLayoutDirection provides if (language.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        content()
    }
}
