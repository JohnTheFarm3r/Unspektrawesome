package com.unspektrawesome.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.unspektrawesome.gallery.GalleryButton
import com.unspektrawesome.gallery.GalleryButtonStyle
import com.unspektrawesome.gallery.StorageAccessDrawing
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramNote

internal object StorageAccessSetup {
    const val REVISION = 2
    fun needed(context: Context): Boolean = GuideProgressStore(context).load("storage-access", REVISION, 1) == 0
    fun complete(context: Context) = GuideProgressStore(context).save("storage-access", REVISION, 1)
}

@Composable
internal fun StorageAccessOnboarding(onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var unavailable by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = Environment.isExternalStorageManager()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun finish() { StorageAccessSetup.complete(context); onComplete() }
    BackHandler { finish() }
    StorageAccessPage(granted, unavailable, onRequest = {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null))
        runCatching { launcher.launch(specific) }.recoverCatching {
            launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.onFailure { unavailable = true }
    }, onComplete = ::finish)
}

@Composable
internal fun StorageAccessPage(granted: Boolean, unavailable: Boolean = false, onRequest: () -> Unit, onComplete: () -> Unit) {
    AmbientScreen(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("Storage access onboarding"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 20.dp)) {
                StorageAccessDrawing(granted)
                Column(Modifier.padding(horizontal = 6.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("GALLERY & STORAGE", color = CameraColors.Accent, fontSize = 10.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                    Text("Find your photos and RAW queue", color = CameraColors.Text, fontSize = 21.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    StorageParagraph("Allow all-files access so Gallery can find photos, videos and waiting RAWs in DCIM/Unspektrawesome after an update, reinstall or app-data reset. Saved DNGs keep the settings needed to restore your processing queue, including older captures.")
                    StorageParagraph("Android will open its All files access settings. Enable access for Unspektrawesome, then return here. This grants broad access to shared storage; Gallery looks in the app's media folder.")
                    if (unavailable) DiagramNote("Open Android settings to allow all-files access, or use Media access in Gallery.", warning = true)
                    StorageParagraph("You can skip this. Gallery will still show Missing photos or videos? and Media access when access is limited. Your saved photos and RAWs stay on the phone.")
                }
            }
            Column(Modifier.fillMaxWidth().drawBehind {
                drawLine(Diagram.StageBorder, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
            }.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (granted) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).background(CameraColors.Accent, CircleShape))
                    Text("All-files access granted", color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    GalleryButton("Not now", Modifier.testTag("Storage skip"), onClick = onComplete)
                    GalleryButton(if (granted) "Continue" else "Allow all-files access", Modifier.testTag("Storage grant"),
                        style = GalleryButtonStyle.Filled, sizingLabels = listOf("Allow all-files access", "Continue"),
                        onClick = if (granted) onComplete else onRequest)
                }
            }
        }
    }
    }
}

@Composable
private fun StorageParagraph(text: String) {
    Text(text, color = Color(0xFFB0B0B0), fontSize = 12.5.sp, lineHeight = 18.sp)
}
