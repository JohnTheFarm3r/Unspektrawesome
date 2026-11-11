package com.unspektrawesome.burst

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.unspektrawesome.gallery.GalleryDialog

@Composable
internal fun rememberBurstStorageAccess(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requesting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pending
        pending = null
        requesting = false
        if (Environment.isExternalStorageManager()) action?.invoke()
    }
    if (pending != null && !requesting) GalleryDialog("BURST",
        "Allow all-files access to save BURST files in DCIM/Unspektrawesome/BURST.", "Allow all-files access",
        onDismiss = { pending = null }, onAction = {
            requesting = true
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null))
            runCatching { launcher.launch(intent) }.recoverCatching {
                launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { requesting = false }
        })
    return { action ->
        if (Environment.isExternalStorageManager()) action()
        else pending = action
    }
}
