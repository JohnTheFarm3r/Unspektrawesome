package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import com.unspektrawesome.settings.RawStreamSelection
import com.unspektrawesome.settings.ViewfinderResolutions

@Composable
internal fun ViewfinderResolutionField(stream: RawStreamSelection, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingsDropdownField("VIEWFINDER RESOLUTION", ViewfinderResolutions.label(stream, selected),
        null, expanded,
        onExpand = { expanded = true }, onDismiss = { expanded = false }) {
        ViewfinderResolutions.choices(stream, selected).forEach { edge ->
            val active = ViewfinderResolutions.size(stream, edge) == ViewfinderResolutions.size(stream, selected)
            SettingsMenuChoice(ViewfinderResolutions.label(stream, edge), selected = active) { onSelect(edge); expanded = false }
        }
    }
}
