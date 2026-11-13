package com.unspektrawesome.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors

@Composable
internal fun GalleryPhotoAccess(revision: Int, onAccessChanged: () -> Unit) {
    val context = LocalContext.current
    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val fullAccess = remember(context, revision) {
        android.os.Environment.isExternalStorageManager() || permissions.filterNot { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val requestAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onAccessChanged()
    }
    if (!fullAccess) GalleryPhotoAccessCard(
        onRequest = { requestAccess.launch(permissions) },
        onSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        },
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
internal fun GalleryPhotoAccessCard(onRequest: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    GalleryCard(modifier = modifier.testTag("Gallery photo access")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.align(Alignment.CenterVertically).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(CameraColors.Accent, CircleShape))
                Text("Missing photos or videos?", color = CameraColors.Text, fontSize = 12.5.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            GalleryButton("Media access", style = GalleryButtonStyle.Accent, compact = true, onClick = onRequest)
            GalleryButton("Settings", compact = true, onClick = onSettings)
        }
    }
}
