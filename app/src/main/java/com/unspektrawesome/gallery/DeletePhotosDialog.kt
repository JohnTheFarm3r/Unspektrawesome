package com.unspektrawesome.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraOverlayToggle

@Composable
internal fun DeletePhotosDialog(count: Int, hasOriginals: Boolean, onDismiss: () -> Unit,
    containsVideo: Boolean = false, onDelete: (Boolean) -> Unit) {
    var deleteOriginals by remember { mutableStateOf(false) }
    GalleryDialog(if (containsVideo) "Delete selected items?" else if (count == 1) "Delete photo?" else "Delete $count photos?",
        if (containsVideo && deleteOriginals) "Permanently delete the selected photos, videos and linked original DNGs."
        else if (containsVideo) "Permanently delete the selected photos and videos. Original DNGs are kept."
        else if (deleteOriginals) "Permanently delete the selected JPEGs and their linked original DNGs."
        else "Permanently delete the selected JPEGs. Original DNGs are kept.",
        "Delete", onDismiss, { onDelete(deleteOriginals) }, extraContent = {
            if (hasOriginals) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Also delete original DNGs", color = CameraColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                CameraOverlayToggle(deleteOriginals, "Also delete original DNGs") { deleteOriginals = it }
            }
        })
}
