package com.unspektrawesome.gallery

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberRawQueueAccess(): suspend (List<GalleryShot>) -> Boolean {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pending?.complete(it.resultCode == Activity.RESULT_OK)
    }
    DisposableEffect(Unit) { onDispose { pending?.cancel() } }
    return request@ { shots ->
        if (Environment.isExternalStorageManager()) return@request true
        val needed = withContext(Dispatchers.IO) {
            shots.filter { !it.panorama && it.hasRaw && it.rawBytes > 0 }.map { it.rawUri }.distinct().filter { uri ->
                context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED && context.contentResolver.query(uri,
                    arrayOf(MediaStore.Images.Media.OWNER_PACKAGE_NAME), null, null, null)?.use {
                    it.moveToFirst() && it.getString(0) != context.packageName
                } == true
            }
        }
        for (batch in needed.chunked(2000)) {
            val request = withContext(Dispatchers.IO) { MediaStore.createWriteRequest(context.contentResolver, batch) }
            val answer = CompletableDeferred<Boolean>()
            pending = answer
            val allowed = try {
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                answer.await()
            } finally { pending = null }
            if (!allowed) return@request false
        }
        true
    }
}
