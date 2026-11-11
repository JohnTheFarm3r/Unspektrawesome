package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription

@Composable
internal fun GalleryThumbnailButton(
    onClick: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val configuration = LocalViewConfiguration.current
    val holdConfiguration = remember(configuration) {
        object : ViewConfiguration by configuration {
            override val longPressTimeoutMillis = 500L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
        Box(modifier.combinedClickable(role = Role.Button, onClick = onClick,
            onLongClickLabel = AppLanguage.text("Open spektrafilm Gallery"), onLongClick = onOpenGallery)
            .languageSemantics { language -> contentDescription = language.text("Open gallery") },
            contentAlignment = Alignment.Center, content = content)
    }
}
