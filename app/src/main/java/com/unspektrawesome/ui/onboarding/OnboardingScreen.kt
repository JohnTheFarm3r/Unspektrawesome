package com.unspektrawesome.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.pageCardBackground
import com.unspektrawesome.ui.camera.CameraPageTransition
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.R

internal enum class OnboardingPermissionState { Needed, Denied, SettingsRequired, Granted }

internal const val ONBOARDING_REVISION = 12
internal const val ONBOARDING_PAGES = 7
private const val VIDEO_PAGE = 4
private const val PERMISSIONS_PAGE = 5
private const val DISCOVERY_PAGE = 6

@Composable
internal fun OnboardingFlow(
    activity: ComponentActivity,
    showIntroduction: Boolean,
    onComplete: () -> Unit,
    onAutoDiscover: () -> Unit = {},
) {
    val preferences = remember { activity.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    fun permissionState(): OnboardingPermissionState = when {
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED -> OnboardingPermissionState.Granted
        activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> OnboardingPermissionState.Denied
        preferences.getBoolean("camera_requested", false) -> OnboardingPermissionState.SettingsRequired
        else -> OnboardingPermissionState.Needed
    }
    fun notificationState(): OnboardingPermissionState = when {
        NotificationManagerCompat.from(activity).areNotificationsEnabled() -> OnboardingPermissionState.Granted
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED -> OnboardingPermissionState.SettingsRequired
        activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> OnboardingPermissionState.Denied
        preferences.getBoolean("notifications_requested", false) -> OnboardingPermissionState.SettingsRequired
        else -> OnboardingPermissionState.Needed
    }
    fun microphoneState(): OnboardingPermissionState = when {
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED -> OnboardingPermissionState.Granted
        activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> OnboardingPermissionState.Denied
        preferences.getBoolean("microphone_requested", false) -> OnboardingPermissionState.SettingsRequired
        else -> OnboardingPermissionState.Needed
    }
    var permission by remember { mutableStateOf(permissionState()) }
    var notificationPermission by remember { mutableStateOf(notificationState()) }
    var microphonePermission by remember { mutableStateOf(microphoneState()) }
    var requestPending by rememberSaveable { mutableStateOf(false) }
    var notificationRequestPending by rememberSaveable { mutableStateOf(false) }
    var microphoneRequestPending by rememberSaveable { mutableStateOf(false) }
    var permissionSequence by rememberSaveable { mutableIntStateOf(-1) }
    var permissionSequenceCompleted by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        preferences.edit().putBoolean("camera_requested", true).apply()
        permission = permissionState()
        requestPending = false
        if (permissionSequence == 0) permissionSequence = 1
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        preferences.edit().putBoolean("notifications_requested", true).apply()
        notificationPermission = notificationState()
        notificationRequestPending = false
        if (permissionSequence == 2) permissionSequence = 3
    }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        preferences.edit().putBoolean("microphone_requested", true).apply()
        microphonePermission = microphoneState()
        microphoneRequestPending = false
        if (permissionSequence == 1) permissionSequence = 2
    }
    val cameraSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permission = permissionState()
        requestPending = false
        if (permissionSequence == 0) permissionSequence = 1
    }
    val requestCamera = {
        requestPending = true
        if (permissionState() == OnboardingPermissionState.SettingsRequired) {
            cameraSettingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        } else launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(permissionSequence, requestPending, microphoneRequestPending, notificationRequestPending) {
        if (requestPending || microphoneRequestPending || notificationRequestPending) return@LaunchedEffect
        when (permissionSequence) {
            0 -> if (permissionState() == OnboardingPermissionState.Granted) permissionSequence = 1 else requestCamera()
            1 -> if (showIntroduction && microphoneState() == OnboardingPermissionState.Needed) {
                microphoneRequestPending = true
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else permissionSequence = 2
            2 -> if (showIntroduction && Build.VERSION.SDK_INT >= 33 && notificationState() == OnboardingPermissionState.Needed) {
                notificationRequestPending = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else permissionSequence = 3
            3 -> {
                permission = permissionState()
                permissionSequenceCompleted++
                permissionSequence = -1
            }
        }
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permission = permissionState()
                notificationPermission = notificationState()
                microphonePermission = microphoneState()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    OnboardingScreen(
        initialPage = GuideProgressStore(activity).load("introduction", ONBOARDING_REVISION, ONBOARDING_PAGES)
            .coerceAtMost(DISCOVERY_PAGE),
        onPageChanged = { GuideProgressStore(activity).save("introduction", ONBOARDING_REVISION, it) },
        onAutoDiscover = onAutoDiscover,
        showIntroduction = showIntroduction,
        permission = permission,
        requestPending = requestPending,
        onRequestPermission = requestCamera,
        onContinuePermissions = { permissionSequence = 0 },
        permissionSequencePending = permissionSequence >= 0,
        permissionSequenceCompleted = permissionSequenceCompleted,
        notificationPermission = notificationPermission,
        notificationRequestPending = notificationRequestPending,
        onRequestNotifications = {
            if (notificationPermission == OnboardingPermissionState.SettingsRequired) {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            } else if (Build.VERSION.SDK_INT >= 33) {
                notificationRequestPending = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        microphonePermission = microphonePermission,
        microphoneRequestPending = microphoneRequestPending,
        onRequestMicrophone = {
            if (microphonePermission == OnboardingPermissionState.SettingsRequired) {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)))
            } else {
                microphoneRequestPending = true
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onComplete = {
            if (showIntroduction) {
                activity.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit()
                    .putBoolean("progress_notifications", notificationState() == OnboardingPermissionState.Granted).apply()
            }
            onComplete()
        },
    )
}

@Composable
internal fun OnboardingScreen(
    showIntroduction: Boolean,
    permission: OnboardingPermissionState,
    requestPending: Boolean = false,
    onRequestPermission: () -> Unit,
    onComplete: () -> Unit,
    onContinuePermissions: () -> Unit,
    permissionSequencePending: Boolean = false,
    permissionSequenceCompleted: Int = 0,
    notificationPermission: OnboardingPermissionState = OnboardingPermissionState.Needed,
    notificationRequestPending: Boolean = false,
    onRequestNotifications: () -> Unit = {},
    microphonePermission: OnboardingPermissionState = OnboardingPermissionState.Needed,
    microphoneRequestPending: Boolean = false,
    onRequestMicrophone: () -> Unit = {},
    onAutoDiscover: () -> Unit = {},
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    var page by rememberSaveable { mutableIntStateOf(if (showIntroduction) initialPage else PERMISSIONS_PAGE) }
    var completedSequence by rememberSaveable { mutableIntStateOf(0) }
    val permissionRequestPending = requestPending || notificationRequestPending || microphoneRequestPending || permissionSequencePending
    LaunchedEffect(permissionSequenceCompleted) {
        if (permissionSequenceCompleted > completedSequence) {
            completedSequence = permissionSequenceCompleted
            if (page == PERMISSIONS_PAGE && permission == OnboardingPermissionState.Granted) {
                if (showIntroduction) page = DISCOVERY_PAGE else onComplete()
            }
        }
    }
    LaunchedEffect(page) { if (showIntroduction) onPageChanged(page) }
    LaunchedEffect(permission) { if (permission != OnboardingPermissionState.Granted && page == DISCOVERY_PAGE) page = PERMISSIONS_PAGE }
    BackHandler(enabled = showIntroduction && page > 0) { if (!permissionRequestPending) page-- }

    AmbientScreen(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxHeight()) {
            OnboardingHeader(page = page.takeIf { showIntroduction }, reserveSkip = showIntroduction,
                onSkip = if (showIntroduction && page < PERMISSIONS_PAGE) ({ page = PERMISSIONS_PAGE }) else null,
                cameraAccess = !showIntroduction)
            CameraPageTransition(page, Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("Onboarding content"), order = { it }) { displayedPage ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(top = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (displayedPage) {
                        DISCOVERY_PAGE -> {
                            PageTitle("07 / LENS SETUP", "Configure\nlens profiles.")
                            Body("Android manufacturers expose camera hardware differently. Auto Lens Discovery tests the cameras on this device and configures lenses that work with this app's RAW viewfinder and capture pipeline.")
                            OnboardingAction("Auto Discover / Configure Lenses",
                                symbol = SettingsSymbol.Discover, enabled = permission == OnboardingPermissionState.Granted, onClick = onAutoDiscover)
                            InfoCard("Configure each lens", "Discovery finds one working profile per lens, starting with the largest normal or binned RAW10 sizes and using RAW_SENSOR as a fallback. Once a lens is verified, its remaining alternatives are skipped. This may take tens of seconds to a few minutes. You can cancel and continue later.", R.drawable.ic_settings_scan_search)
                            InfoCard("Manual profiles stay available", "Your saved profiles are kept. Use the checkboxes in Lens Profiles to choose which profiles appear in the viewfinder. You can also skip and run Auto Lens Discovery later beside Lens Profiles in settings.", R.drawable.ic_settings_sliders_horizontal)
                        }
                        0 -> {
                            PageTitle("01 / INTRODUCTION", "A RAW camera.\nWith spektrafilm.")
                            Body("Unspektrawesome processes RAW camera data into film-inspired JPEG photos, with a live spektrafilm viewfinder and controls for your film look.")
                            OnboardingPanel(R.drawable.ic_settings_languages) {
                                Body("You can change your language later in Settings. For more languages, export the English template, translate it and import it as a custom language.")
                            }
                            InfoCard("What is spektrafilm?",
                                "spektrafilm is a third-party film simulation tool. It models film exposure, development, printing and scanning using measured spectral data.", R.drawable.ic_onboarding_film) {
                                Body("This app is an unofficial partial Android port of spektrafilm OFX, based on the original author's desktop source. It is not affiliated with the original spektrafilm project.")
                                OnboardingDivider()
                                Text("The spektrafilm name and original work belong to its original author, not this app's developer.",
                                    color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                        1 -> {
                            PageTitle("02 / THE PIPELINE", "From sensor\nto JPEG.")
                            Body("The image starts with RAW sensor data. The app develops it, applies spektrafilm, then saves the processed photo.")
                            Column(Modifier.fillMaxWidth().pageCardBackground(RoundedCornerShape(18.dp))
                                .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(18.dp)).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                PipelineStage(R.drawable.ic_onboarding_database, "RAW stream / buffer", "Sensor data arrives directly from the camera.")
                                PipelineStage(R.drawable.ic_settings_sliders_horizontal, "RAW processing", "Demosaicing, white balance and color conversion prepare the image.")
                                PipelineStage(R.drawable.ic_onboarding_cpu, "spektrafilm on the GPU", "Film and print simulation shape color, tone and texture.", active = true)
                                PipelineStage(R.drawable.ic_onboarding_image, "JPEG photo", "The processed image is encoded and saved to your gallery.")
                            }
                            OnboardingPanel(R.drawable.ic_onboarding_monitor_smartphone) {
                                Body("The live viewfinder follows the same RAW-to-spektrafilm pipeline on the GPU. JPEG encoding happens when a photo is saved.")
                            }
                        }
                        2 -> {
                            PageTitle("03 / GALLERY", "Shoot now.\nDevelop later.")
                            Body("Unspektrawesome Gallery brings your photos and processing queue together. Open it from the camera or its own launcher icon.")
                            InfoCard("Process now or Later in Gallery",
                                "Process now saves JPEGs as you shoot and is the default. Choose Later in Gallery under Camera settings, General to save RAW shots and start their exports afterward in Gallery, Processing.", R.drawable.ic_settings_layers)
                            InfoCard("Offline processing on your phone",
                                "Deferred photos keep the spektrafilm look and settings used at capture. Gallery develops them with the same image quality, entirely on your phone without an internet connection.", R.drawable.ic_onboarding_wifi_off)
                            InfoCard("Your originals are kept",
                                "Original DNGs remain available after exports, failures and cancellations. Pause or resume a batch, choose its processing pace, and share finished photos or originals.", R.drawable.ic_onboarding_archive)
                            OnboardingDivider()
                            Body("After setup, the camera guide leads into the Gallery tour so you can try each part of the workflow.")
                        }
                        3 -> {
                            PageTitle("04 / BEFORE YOU USE IT", "Demanding\nby design.")
                            Body("This app is intended for higher end devices. spektrafilm is a heavy processing shader ported from a PC tool, and the live RAW viewfinder runs that pipeline on your GPU.")
                            InfoCard("Power, heat and preview speed",
                                "Expect higher power consumption, more heat and a slower viewfinder, especially during prolonged use. Performance depends on your device and settings.", R.drawable.ic_onboarding_thermometer)
                            InfoCard("Still a work in progress",
                                "Currently tested only on a few flagship Xiaomi Ultra series devices. Other devices may be slow, unstable or unable to run the app.", R.drawable.ic_onboarding_flask_conical) {
                                OnboardingDivider()
                                Body("Compatibility and smooth preview are not guaranteed. Please keep these limits in mind before relying on the app.")
                            }
                        }
                        VIDEO_PAGE -> {
                            PageTitle("05 / EXPERIMENTAL VIDEO", "Experimental\nvideo recording.")
                            Body("Video uses the RAW stream, processed live through the demanding spektrafilm pipeline and encoded into a video in real time.")
                            Body(com.unspektrawesome.ui.camera.CAPTURE_MODE_GESTURE_GUIDE)
                            InfoCard("Short clips on flagship devices",
                                "Prefer the latest Qualcomm (QCOM) flagships. MediaTek (MTK) is not guaranteed to work well, even with a flagship chip. Processing is demanding: even top devices may not sustain stable long recordings. Use short bursts.", R.drawable.ic_settings_video)
                            InfoCard("Heavy effects are disabled",
                                "Diffusion, halation and grain are disabled for video. Their extra processing load is too high for current phones to sustain alongside live RAW video recording.", R.drawable.ic_onboarding_ban)
                            InfoCard("OIS only, no EIS",
                                "The RAW stream does not provide electronic image stabilization (EIS). Only optical stabilization (OIS) is available, where the lens supports it.", R.drawable.ic_onboarding_vibrate_off)
                            OnboardingDivider()
                            Body("Do not compare spektrafilm video directly with the stock camera or apps that record an already processed video stream. They use fundamentally different processing pipelines.")
                            OnboardingDivider()
                            Text("Video recording is experimental. I'm aware of its issues and device limitations. Please do not send video bug reports or suggestions. Thank you for understanding.",
                                modifier = Modifier.fillMaxWidth(), color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        PERMISSIONS_PAGE -> {
                            PageTitle("06 / PERMISSIONS", if (showIntroduction) "App\npermissions." else "Allow the\ncamera.")
                            Body("Camera permission is required to read the RAW stream, show the live viewfinder and take photos.")
                            InfoCard("Required camera access",
                                if (showIntroduction) "Continue requests camera, microphone and notification access one at a time. Camera access is required; microphone and notifications are optional."
                                else "Use Continue or the button below to allow camera access.", R.drawable.ic_onboarding_camera) {
                                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(when (permission) {
                                        OnboardingPermissionState.Needed -> "Camera access has not been granted yet."
                                        OnboardingPermissionState.Denied -> "Camera permission was denied. Try again and make sure you allow camera access."
                                        OnboardingPermissionState.SettingsRequired -> "Camera access is blocked. Open app settings, choose Permissions, then allow Camera. Return here to continue."
                                        OnboardingPermissionState.Granted -> "Camera permission granted. You can continue."
                                    }, color = if (permission == OnboardingPermissionState.Granted) CameraColors.Accent else CameraColors.Muted,
                                        fontSize = 15.sp, lineHeight = 23.sp)
                                    if (permission != OnboardingPermissionState.Granted) {
                                        OnboardingAction(when {
                                            requestPending -> "Waiting for permission"
                                            permission == OnboardingPermissionState.SettingsRequired -> "Open app settings"
                                            permission == OnboardingPermissionState.Denied -> "Try again"
                                            else -> "Allow camera"
                                        }, enabled = !permissionRequestPending, onClick = onRequestPermission)
                                    }
                                }
                            }
                            if (showIntroduction) {
                                InfoCard("Microphone for video",
                                    "Allow microphone access to record sound with your videos. You can continue without it for photos.", R.drawable.ic_onboarding_mic) {
                                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(when (microphonePermission) {
                                            OnboardingPermissionState.Needed -> "Microphone access has not been granted yet."
                                            OnboardingPermissionState.Denied -> "Microphone permission was declined. You can try again or continue taking photos."
                                            OnboardingPermissionState.SettingsRequired -> "Microphone access is blocked. Open app settings, choose Permissions, then allow Microphone."
                                            OnboardingPermissionState.Granted -> "Microphone permission granted. Video can record sound."
                                        }, color = if (microphonePermission == OnboardingPermissionState.Granted) CameraColors.Accent else CameraColors.Muted,
                                            fontSize = 15.sp, lineHeight = 23.sp)
                                        if (microphonePermission != OnboardingPermissionState.Granted) {
                                            OnboardingAction(when {
                                                microphoneRequestPending -> "Waiting for permission"
                                                microphonePermission == OnboardingPermissionState.SettingsRequired -> "Open microphone settings"
                                                microphonePermission == OnboardingPermissionState.Denied -> "Try microphone again"
                                                else -> "Allow microphone"
                                            }, enabled = !permissionRequestPending, onClick = onRequestMicrophone)
                                        }
                                    }
                                }
                                InfoCard("Optional processing notifications",
                                    "See the current photo, export progress and a pause control outside Gallery. You can continue without notifications and enable them later in settings.", R.drawable.ic_onboarding_bell) {
                                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(when (notificationPermission) {
                                            OnboardingPermissionState.Needed -> "Notifications are optional. Continue will ask for permission, or you can use the button below."
                                            OnboardingPermissionState.Denied -> "Notifications were declined. You can try again or continue without them."
                                            OnboardingPermissionState.SettingsRequired -> "Notifications are blocked. Enable them in Android notification settings, or continue without them."
                                            OnboardingPermissionState.Granted -> "Notifications enabled. Processing progress can appear outside Gallery."
                                        }, color = if (notificationPermission == OnboardingPermissionState.Granted) CameraColors.Accent else CameraColors.Muted,
                                            fontSize = 15.sp, lineHeight = 23.sp)
                                        if (notificationPermission != OnboardingPermissionState.Granted) {
                                            OnboardingAction(when {
                                                notificationRequestPending -> "Waiting for notifications permission"
                                                notificationPermission == OnboardingPermissionState.SettingsRequired -> "Open notification settings"
                                                notificationPermission == OnboardingPermissionState.Denied -> "Try notifications again"
                                                else -> "Allow notifications"
                                            }, enabled = !permissionRequestPending, onClick = onRequestNotifications)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OnboardingNavigation(
                primaryLabel = if (page == DISCOVERY_PAGE) "Skip" else if (!showIntroduction) "Open camera" else "Continue",
                primaryModifier = Modifier.testTag("Onboarding primary"),
                primaryEnabled = !permissionRequestPending,
                secondaryLabel = "Back".takeIf { showIntroduction },
                secondaryModifier = Modifier.testTag("Onboarding back"),
                secondaryEnabled = page > 0 && !permissionRequestPending,
                onSecondary = { page-- },
                sizingLabels = if (showIntroduction) listOf("Back", "Continue", "Skip") else listOf("Open camera"),
                onPrimary = {
                    if (page == PERMISSIONS_PAGE) onContinuePermissions()
                    else if (page < PERMISSIONS_PAGE) page++
                    else if (permission == OnboardingPermissionState.Granted) onComplete()
                },
            )
        }
    }
    }
}

@Composable
private fun PageTitle(label: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = CameraColors.Muted, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Text(title, color = CameraColors.Text, fontSize = 32.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Body(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), color = CameraColors.Muted, fontSize = 15.sp, lineHeight = 23.sp)
}

@Composable
private fun InfoCard(title: String, description: String, @DrawableRes symbol: Int,
    content: @Composable ColumnScope.() -> Unit = {}) {
    OnboardingPanel(symbol, title) {
        Body(description)
        content()
    }
}

@Composable
private fun PipelineStage(@DrawableRes symbol: Int, title: String, description: String, active: Boolean = false) {
    Row(Modifier.fillMaxWidth().then(if (active) Modifier.border(1.dp, CameraColors.Accent, RoundedCornerShape(14.dp))
        .padding(12.dp) else Modifier), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OnboardingGlyph(symbol, active, Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (active) CameraColors.Accent else CameraColors.Text,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
