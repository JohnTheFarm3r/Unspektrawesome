package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.unspektrawesome.ui.camera.CameraAlertDialog as AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.unspektrawesome.spektra.SpektraProfile
import com.unspektrawesome.spektra.SpektraStateRepository

@Composable
internal fun SpektraProfileDialog(
    repository: SpektraStateRepository,
    profile: SpektraProfile?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    renameOnly: Boolean = false,
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    var saveCopy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CameraColors.Background,
        titleContentColor = CameraColors.Text,
        textContentColor = CameraColors.Muted,
        title = { CameraWindowBackdrop(); Text(if (renameOnly) "Rename profile" else "Save custom profile") },
        text = {
            Column {
                SettingsTextField(value = name, onValueChange = { name = it.take(40); error = null },
                    label = "Profile name", modifier = Modifier.fillMaxWidth(), error = error)
                if (profile != null && !renameOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saveCopy, { saveCopy = it; error = null }, colors = CheckboxDefaults.colors(
                        checkedColor = CameraColors.Accent, checkmarkColor = CameraColors.Background,
                        uncheckedColor = CameraColors.Muted))
                    Text("Save as a new profile")
                }
                if (error == null) Text(if (renameOnly) "Your saved settings stay the same." else "All current spektrafilm settings will be saved to Custom, including EV, AE balance, shadows and highlight compression for each lens.")
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                try {
                    if (renameOnly && profile != null) repository.customProfiles.save(name, profile.params, profile.id, profile.corrections)
                    else repository.saveProfile(name, if (saveCopy) null else profile?.id)
                    onSaved()
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }) { Text(if (renameOnly) "Rename" else "Save", color = if (name.isBlank()) CameraColors.Muted else CameraColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CameraColors.Muted) } },
    )
}
