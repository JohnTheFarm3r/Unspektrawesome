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
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.R

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
        .padding(horizontal = 20.dp).testTag("Storage access onboarding"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxHeight()) {
            OnboardingHeader()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("GALLERY & STORAGE", color = CameraColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Text("Find your photos and RAW queue", color = CameraColors.Text, fontSize = 32.sp, lineHeight = 36.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                OnboardingPanel(R.drawable.ic_onboarding_folder_search) {
                    Text("Allow all-files access so Gallery can find photos, videos and waiting RAWs in DCIM/Unspektrawesome after an update, reinstall or app-data reset. Saved DNGs keep the settings needed to restore your processing queue, including older captures.",
                        color = CameraColors.Muted, fontSize = 15.sp, lineHeight = 23.sp)
                }
                OnboardingPanel(R.drawable.ic_onboarding_settings_2) {
                    Text("Android will open its All files access settings. Enable access for Unspektrawesome, then return here. This grants broad access to shared storage; Gallery looks in the app's media folder.",
                        color = CameraColors.Muted, fontSize = 15.sp, lineHeight = 23.sp)
                    if (unavailable) {
                        OnboardingDivider()
                        Text("Open Android settings to allow all-files access, or use Media access in Gallery.",
                            color = CameraColors.Text, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
                OnboardingPanel(R.drawable.ic_onboarding_shield_check) {
                    Text("You can skip this. Gallery will still show Missing photos or videos? and Media access when access is limited. Your saved photos and RAWs stay on the phone.",
                        color = CameraColors.Muted, fontSize = 15.sp, lineHeight = 23.sp)
                }
                if (granted) {
                    Text("All-files access granted", color = CameraColors.Accent, fontSize = 14.sp)
                }
            }
            OnboardingNavigation(primaryLabel = if (granted) "Continue" else "Allow all-files access",
                primaryModifier = Modifier.testTag("Storage grant"), onPrimary = if (granted) onComplete else onRequest,
                secondaryLabel = "Not now", secondaryModifier = Modifier.testTag("Storage skip"),
                onSecondary = onComplete, sizingLabels = listOf("Allow all-files access", "Not now", "Continue"))
        }
    }
    }
}
