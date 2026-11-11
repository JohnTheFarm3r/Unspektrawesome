package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class TransferProfileChoice(val id: String, val name: String, val detail: String = "")

@Composable
internal fun ProfileSelectionDialog(
    title: String,
    description: String,
    action: String,
    profiles: List<TransferProfileChoice>,
    modifier: Modifier = Modifier,
    options: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by rememberSaveable(profiles.map { it.id }) { mutableStateOf(profiles.map { it.id }) }
    val symbol = if (action == "Export") SettingsSymbol.Export else SettingsSymbol.Import
    LensToolDialog(title, symbol, onDismiss, modifier, footer = {
        LensToolActionPair("Cancel", action, onDismiss, { onConfirm(selected.toSet()) },
            Modifier.testTag("Profile transfer footer"), firstSymbol = SettingsSymbol.Close, secondSymbol = symbol,
            secondAccent = true, secondEnabled = selected.isNotEmpty())
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("Profile transfer list"),
            contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "description") { LensToolNotice(description) }
            if (options != null) item(key = "options") { options() }
            item(key = "selection") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("{size} of {size_2} selected", arguments = mapOf("size" to (selected.size), "size_2" to (profiles.size)),
                        color = CameraColors.Text, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                    LensToolActionPair("Select none", "Select all", { selected = emptyList() }, { selected = profiles.map { it.id } },
                        firstEnabled = selected.isNotEmpty(), secondEnabled = selected.size != profiles.size)
                }
            }
            items(profiles, key = { "profile:${it.id}" }) { profile ->
                val checked = profile.id in selected
                Row(Modifier.fillMaxWidth().clip(SettingsStyle.PanelShape)
                    .background(if (checked) CameraColors.MenuSelectedCard else CameraColors.MenuCard)
                    .border(1.dp, if (checked) CameraColors.Accent else CameraColors.MenuBorder, SettingsStyle.PanelShape)
                    .toggleable(checked, role = Role.Checkbox) { selected = if (it) selected + profile.id else selected - profile.id }
                    .languageSemantics { language -> contentDescription = language.format("Select {name}", mapOf("name" to (profile.name))) }
                    .padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LensToolCheck(checked)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.name, translate = false, color = if (checked) CameraColors.Accent else CameraColors.Text, fontSize = 15.sp,
                            lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                        if (profile.detail.isNotBlank()) Text(profile.detail, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
