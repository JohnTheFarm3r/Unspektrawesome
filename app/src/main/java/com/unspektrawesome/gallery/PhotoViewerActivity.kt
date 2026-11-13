package com.unspektrawesome.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.SavedCameraBrightness
import com.unspektrawesome.ui.camera.UnspektrawesomeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoViewerActivity : ComponentActivity() {
    private var photoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        photoUri = intent.data
        if (photoUri == null) { finish(); return }
        setContent {
            UnspektrawesomeTheme {
                SavedCameraBrightness()
                androidx.compose.runtime.CompositionLocalProvider(com.unspektrawesome.language.LocalOwnTextWidth provides true) {
                    photoUri?.let { uri -> key(uri) { CameraPhotoReview(uri, onDismiss = ::finish) } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        photoUri = intent.data
        if (photoUri == null) finish()
    }

    companion object {
        fun intent(context: Context, photo: Uri) = Intent(context, PhotoViewerActivity::class.java).setData(photo)
    }
}

private data class PhotoReviewData(val photos: List<GalleryPhoto>, val jobs: List<GalleryShot>)

@Composable
private fun CameraPhotoReview(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mediaUri = remember(uri) { com.unspektrawesome.burst.BurstMedia.canonicalUri(uri) }
    var data by remember { mutableStateOf<PhotoReviewData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uri, revision) {
        runCatching {
            withContext(Dispatchers.IO) {
                val jobs = GalleryStore.get(context).shots.value
                val photos = GalleryMedia.load(context, jobs)
                check(photos.any { it.uri == mediaUri }) { "This item is no longer available in the app's media folder." }
                PhotoReviewData(photos, jobs)
            }
        }.onSuccess { data = it; loadError = null }
            .onFailure {
                if (it is CancellationException) throw it
                loadError = it.message ?: "Unable to open this item."
            }
    }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(CameraColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val loaded = data
        when {
            loadError != null -> EmptyGallery("Media unavailable", loadError!!, "Back to camera", onDismiss)
            loaded == null -> EmptyGallery("Opening gallery", "Loading saved photos and videos.", "Back to camera", onDismiss)
            else -> PhotoViewer(loaded.photos, mediaUri.toString(), loaded.jobs, onDismiss,
                onError = { notice = it }, onDeleted = {})
        }
        notice?.let { message ->
            GalleryDialog("Media unavailable", message, "Close", { notice = null }, { notice = null })
        }
    }
}
