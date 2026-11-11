package com.unspektrawesome.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun photoWriteAccessRequired(context: Context, photos: List<GalleryPhoto>): List<Uri> {
    if (android.os.Environment.isExternalStorageManager()) return emptyList()
    val resolver = context.contentResolver
    return photos.distinctBy { it.uri }.mapNotNull { photo ->
        val needsAccess = resolver.query(photo.uri, arrayOf(MediaStore.Images.Media.OWNER_PACKAGE_NAME),
            if (photo.isBurst) com.unspektrawesome.burst.BurstMedia.SELECTION
            else "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
            if (photo.isBurst) com.unspektrawesome.burst.BurstMedia.selectionArguments()
            else arrayOf("DCIM/Unspektrawesome/", photo.mimeType), null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) != context.packageName &&
                context.checkUriPermission(photo.uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED
        } == true
        photo.uri.takeIf { needsAccess }
    }
}

@Composable
internal fun rememberPhotoDeletion(): suspend (List<GalleryPhoto>, Boolean) -> GalleryDeletionResult {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val requestWrite = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pending?.complete(it.resultCode == Activity.RESULT_OK)
    }
    DisposableEffect(Unit) {
        onDispose { pending?.cancel() }
    }
    return { photos, deleteOriginals ->
        val needsAccess = withContext(Dispatchers.IO) { photoWriteAccessRequired(context, photos) }
        var allowed = true
        for (batch in needsAccess.chunked(2000)) {
            val request = withContext(Dispatchers.IO) { MediaStore.createWriteRequest(context.contentResolver, batch) }
            val answer = CompletableDeferred<Boolean>()
            pending = answer
            try {
                requestWrite.launch(IntentSenderRequest.Builder(request.intentSender).build())
                allowed = answer.await()
            } finally { pending = null }
            if (!allowed) break
        }
        if (allowed) withContext(Dispatchers.IO) { GalleryMedia.deletePhotos(context, photos, deleteOriginals) }
        else GalleryDeletionResult(emptySet(), photos.associate { it.uri to "Could not delete this item." })
    }
}
