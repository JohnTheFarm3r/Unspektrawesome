package com.unspektrawesome.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraPageTransition
import com.unspektrawesome.ui.camera.CaptureMode
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramBody
import com.unspektrawesome.ui.camera.DiagramButton
import com.unspektrawesome.ui.camera.DiagramHint
import com.unspektrawesome.ui.camera.DiagramNote
import com.unspektrawesome.ui.camera.DiagramNumber
import com.unspektrawesome.ui.camera.DiagramRow
import com.unspektrawesome.ui.camera.DiagramValue
import com.unspektrawesome.ui.camera.IntroDemandStage
import com.unspektrawesome.ui.camera.IntroGalleryStage
import com.unspektrawesome.ui.camera.IntroLensStage
import com.unspektrawesome.ui.camera.IntroLookStage
import com.unspektrawesome.ui.camera.IntroModesStage
import com.unspektrawesome.ui.camera.IntroPermissionsStage
import com.unspektrawesome.ui.camera.IntroPipelineStage
import com.unspektrawesome.ui.camera.IntroVideoStage
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.ui.camera.SetupSection
import com.unspektrawesome.ui.camera.diagramHairline

internal enum class OnboardingPermissionState { Needed, Denied, SettingsRequired, Granted }

internal const val ONBOARDING_REVISION = 13
internal const val ONBOARDING_PAGES = 8
private const val VIDEO_PAGE = 5
private const val PERMISSIONS_PAGE = 6
private const val DISCOVERY_PAGE = 7

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
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var storageUnavailable by remember { mutableStateOf(false) }
    var requestPending by rememberSaveable { mutableStateOf(false) }
    var notificationRequestPending by rememberSaveable { mutableStateOf(false) }
    var microphoneRequestPending by rememberSaveable { mutableStateOf(false) }
    var storageRequestPending by rememberSaveable { mutableStateOf(false) }
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
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        storageGranted = Environment.isExternalStorageManager()
        storageRequestPending = false
        if (permissionSequence == 3) permissionSequence = 4
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
    // All-files access is granted on an Android settings page rather than in a permission prompt.
    fun requestStorage(): Boolean {
        preferences.edit().putBoolean("storage_requested", true).apply()
        storageRequestPending = true
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", activity.packageName, null))
        val opened = runCatching { storageLauncher.launch(specific) }.recoverCatching {
            storageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.isSuccess
        if (!opened) {
            storageRequestPending = false
            storageUnavailable = true
        }
        return opened
    }
    LaunchedEffect(permissionSequence, requestPending, microphoneRequestPending, notificationRequestPending, storageRequestPending) {
        if (requestPending || microphoneRequestPending || notificationRequestPending || storageRequestPending) return@LaunchedEffect
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
            // Opens the all-files settings page once; afterwards the row's own button stays available.
            3 -> if (showIntroduction && !Environment.isExternalStorageManager() &&
                !preferences.getBoolean("storage_requested", false)) {
                if (!requestStorage()) permissionSequence = 4
            } else permissionSequence = 4
            4 -> {
                permission = permissionState()
                storageGranted = Environment.isExternalStorageManager()
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
                storageGranted = Environment.isExternalStorageManager()
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
        storageGranted = storageGranted,
        storageUnavailable = storageUnavailable,
        storageRequestPending = storageRequestPending,
        onRequestStorage = { requestStorage() },
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
    storageGranted: Boolean = false,
    storageUnavailable: Boolean = false,
    storageRequestPending: Boolean = false,
    onRequestStorage: () -> Unit = {},
    onAutoDiscover: () -> Unit = {},
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    var page by rememberSaveable { mutableIntStateOf(if (showIntroduction) initialPage else PERMISSIONS_PAGE) }
    var completedSequence by rememberSaveable { mutableIntStateOf(0) }
    val permissionRequestPending = requestPending || notificationRequestPending || microphoneRequestPending ||
        storageRequestPending || permissionSequencePending
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
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxHeight()) {
            OnboardingHeader(page = page.takeIf { showIntroduction },
                onSkip = if (showIntroduction && page < PERMISSIONS_PAGE) ({ page = PERMISSIONS_PAGE }) else null,
                cameraAccess = !showIntroduction)
            CameraPageTransition(page, Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("Onboarding content"), order = { it }) { displayedPage ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 24.dp)) {
                    when (displayedPage) {
                        0 -> LookPage()
                        1 -> PipelinePage()
                        2 -> ModesPage()
                        3 -> GalleryPage()
                        4 -> DemandPage()
                        VIDEO_PAGE -> VideoPage()
                        PERMISSIONS_PAGE -> PermissionsPage(showIntroduction, permission, requestPending, onRequestPermission,
                            microphonePermission, microphoneRequestPending, onRequestMicrophone,
                            notificationPermission, notificationRequestPending, onRequestNotifications,
                            storageGranted, storageUnavailable, storageRequestPending, onRequestStorage, permissionRequestPending)
                        DISCOVERY_PAGE -> DiscoveryPage(permission == OnboardingPermissionState.Granted, onAutoDiscover)
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
private fun LookPage() {
    PageTitle(1, "Introduction", "A RAW camera with a film look")
    DiagramBody("Unspektrawesome develops the camera's RAW data into film-inspired photos, with spektrafilm running live in the viewfinder.")
    IntroLookStage()
    SetupSection("What you see")
    DiagramRow("Live film look", number = 1, detail = "The viewfinder shows your spektrafilm look while you frame.")
    DiagramRow("Film cards", number = 2, detail = "Swipe up on the viewfinder to adjust format, effects, processing and exposure, and to save looks you like.")
    DiagramRow("RAW capture", number = 3, detail = "Each photo starts from the sensor's RAW data and is developed with the look you saw.")
    SetupSection("Language")
    DiagramHint("You can change your language later in Settings. For more languages, export the English template, translate it and import it as a custom language.")
    SetupSection("What is spektrafilm?")
    DiagramNote("spektrafilm is a third-party film simulation tool. It models film exposure, development, printing and scanning using measured spectral data.")
    DiagramHint("This app is an unofficial partial Android port of spektrafilm OFX, based on the original author's desktop source. It is not affiliated with the original spektrafilm project.")
    DiagramHint("The spektrafilm name and original work belong to its original author, not this app's developer.")
}

@Composable
private fun PipelinePage() {
    PageTitle(2, "The pipeline", "From sensor to photo")
    DiagramBody("Every photo follows the same path, from the sensor's RAW data to a finished picture.")
    IntroPipelineStage()
    SetupSection("Photo path")
    DiagramRow("Sensor data", number = 1, detail = "The camera delivers unprocessed RAW data straight from the sensor.")
    DiagramRow("Development", number = 2, detail = "Demosaicing, white balance, lens shading and color conversion prepare the image.")
    DiagramRow("spektrafilm on the GPU", number = 3, detail = "Film and print simulation shape color, tone and texture.")
    DiagramRow("Saved files", number = 4, detail = "Photos are saved as JPEGs. With Output set to JPEG+DNG, the default, the original RAW is also kept as a DNG.")
    SetupSection("Viewfinder")
    DiagramRow("Live viewfinder", number = 5, divider = false,
        detail = "The viewfinder runs the same look live at a lower resolution. Older phones automatically get a lighter preview.")
    DiagramHint("Viewfinder settings can also switch photo mode to the phone's own preview with the look on top, which uses less power.",
        Modifier.diagramHairline())
}

@Composable
private fun ModesPage() {
    val renderer = LocalAppLanguage.current.renderer
    fun name(mode: CaptureMode) = renderer.text(mode.label)
    PageTitle(3, "Capture modes", "Five ways to shoot")
    DiagramBody("Swipe across the viewfinder or tap a mode name to switch between modes.")
    IntroModesStage()
    SetupSection("Modes")
    DiagramRow(name(CaptureMode.BURST), number = 1, translateTitle = false,
        detail = "Keeps up to 5 seconds of RAW frames from before you press the shutter. Browse them later and export the frames you want. Needs all-files access.")
    DiagramRow(name(CaptureMode.VIDEO), number = 2, translateTitle = false,
        detail = "Records video with your film look applied live.")
    DiagramRow(name(CaptureMode.PHOTO), number = 3, translateTitle = false,
        detail = "Single photos, processed right away or later in Gallery.")
    DiagramRow(name(CaptureMode.GRID), number = 4, translateTitle = false,
        detail = "Tap the shutter and aim at each highlighted circle. Holding your aim for 1.5 seconds captures each of the nine panels.")
    DiagramRow(name(CaptureMode.PANORAMA), number = 5, translateTitle = false, divider = false,
        detail = "Tap the shutter and turn slowly along the guide.")
    DiagramHint("Gallery joins 3×3 and panorama frames into one picture. Both modes lock exposure, white balance and focus when capture starts.",
        Modifier.diagramHairline())
}

@Composable
private fun GalleryPage() {
    PageTitle(4, "Gallery", "Shoot now. Develop later.")
    DiagramBody("Unspektrawesome Gallery brings your photos and processing queue together. Open it from the camera or its own launcher icon.")
    IntroGalleryStage()
    SetupSection("When photos are processed")
    DiagramRow("Process now", number = 1, detail = "Saves JPEGs while you shoot.")
    DiagramRow("Later in Gallery", number = 2,
        detail = "Saves RAWs for a session you start afterward, giving the camera more processing room.")
    DiagramHint("Choose in Camera settings, General, under Photo saving, or in Gallery settings. Process now is the default.",
        Modifier.diagramHairline())
    SetupSection("In Gallery")
    DiagramRow("Processing tab", number = 3,
        detail = "Start all or selected photos, pause or resume, and choose a Low, Normal or High priority. Processing can wait when the phone gets too hot.")
    DiagramRow("Your originals", number = 4, divider = false,
        detail = "Waiting photos keep the look and settings from capture and are developed offline on your phone. Original DNGs are kept unless you turn on Delete RAWs after export.")
}

@Composable
private fun DemandPage() {
    PageTitle(5, "Before you use it", "Demanding by design")
    DiagramBody("spektrafilm is a heavy processing shader ported from a PC tool, and the live RAW viewfinder runs it on your phone's GPU.")
    IntroDemandStage()
    SetupSection("What to expect")
    DiagramRow("Power, heat and preview speed", number = 1,
        detail = "Expect higher power consumption, more heat and a slower viewfinder, especially during prolonged use. Performance depends on your device and settings.")
    DiagramRow("Lighter preview on older phones", number = 2,
        detail = "Older Snapdragon, Tensor and MediaTek chips automatically get a simpler viewfinder preview to stay responsive. Saved photos are processed at full quality.")
    DiagramRow("Still a work in progress", divider = false,
        detail = "Tested on only a small number of phones so far. Other phones may be slow, unstable or unable to run the app.")
    DiagramNote("Compatibility and smooth preview are not guaranteed. Please keep these limits in mind before relying on the app.")
}

@Composable
private fun VideoPage() {
    PageTitle(6, "Video", "Video recording")
    DiagramBody("Video processes the RAW stream live through spektrafilm. Even top phones may not sustain long recordings, so record short clips.")
    IntroVideoStage()
    SetupSection("How video works")
    DiagramRow("Processed live", number = 1, detail = "The RAW stream runs through spektrafilm and is encoded into an H.265 video in real time.")
    DiagramRow("Lighter effects", number = 2, detail = "Halation, diffusion and glare use lighter video versions. Grain and fine detail filters are off in video.")
    DiagramRow("Stabilization", number = 3,
        detail = "Optical stabilization works on lenses that have it. Live gyro stabilization crops the view to reduce shake, and gyro data can be saved for stabilizing later in other software. Both turn optical stabilization off.")
    DiagramRow("Hold shutter to lock", number = 4, divider = false,
        detail = "Hold the shutter to record with shutter speed, ISO, focus and white balance locked. Choose which ones in Camera settings, Video.")
    DiagramHint("Do not compare spektrafilm video directly with the stock camera or apps that record an already processed video stream. They use fundamentally different processing pipelines.",
        Modifier.diagramHairline())
}

@Composable
private fun PermissionsPage(
    showIntroduction: Boolean,
    permission: OnboardingPermissionState,
    requestPending: Boolean,
    onRequestPermission: () -> Unit,
    microphonePermission: OnboardingPermissionState,
    microphoneRequestPending: Boolean,
    onRequestMicrophone: () -> Unit,
    notificationPermission: OnboardingPermissionState,
    notificationRequestPending: Boolean,
    onRequestNotifications: () -> Unit,
    storageGranted: Boolean,
    storageUnavailable: Boolean,
    storageRequestPending: Boolean,
    onRequestStorage: () -> Unit,
    busy: Boolean,
) {
    val granted = OnboardingPermissionState.Granted
    PageTitle(7, "Permissions", if (showIntroduction) "App permissions" else "Allow the camera")
    DiagramBody(if (showIntroduction) "Continue asks for each permission in turn. Only the camera is required; you can change the others later in Android settings."
        else "Camera permission is required to read the RAW stream, show the live viewfinder and take photos.")
    if (showIntroduction) IntroPermissionsStage(permission == granted, microphonePermission == granted,
        notificationPermission == granted, storageGranted)
    SetupSection("Access")
    PermissionRow(1, "Camera", permission == granted, required = true,
        status = when (permission) {
            OnboardingPermissionState.Needed -> "Camera access has not been granted yet."
            OnboardingPermissionState.Denied -> "Camera permission was denied. Try again and make sure you allow camera access."
            OnboardingPermissionState.SettingsRequired -> "Camera access is blocked. Open app settings, choose Permissions, then allow Camera. Return here to continue."
            OnboardingPermissionState.Granted -> "Camera permission granted. You can continue."
        },
        action = when {
            requestPending -> "Waiting for permission"
            permission == OnboardingPermissionState.SettingsRequired -> "Open app settings"
            permission == OnboardingPermissionState.Denied -> "Try again"
            else -> "Allow camera"
        }, enabled = !busy, divider = showIntroduction, onAction = onRequestPermission)
    if (!showIntroduction) return
    PermissionRow(2, "Microphone", microphonePermission == granted,
        status = when (microphonePermission) {
            OnboardingPermissionState.Needed -> "Allow microphone access to record sound with your videos. You can continue without it for photos."
            OnboardingPermissionState.Denied -> "Microphone permission was declined. You can try again or continue taking photos."
            OnboardingPermissionState.SettingsRequired -> "Microphone access is blocked. Open app settings, choose Permissions, then allow Microphone."
            OnboardingPermissionState.Granted -> "Microphone permission granted. Video can record sound."
        },
        action = when {
            microphoneRequestPending -> "Waiting for permission"
            microphonePermission == OnboardingPermissionState.SettingsRequired -> "Open microphone settings"
            microphonePermission == OnboardingPermissionState.Denied -> "Try microphone again"
            else -> "Allow microphone"
        }, enabled = !busy, onAction = onRequestMicrophone)
    PermissionRow(3, "Notifications", notificationPermission == granted,
        status = when (notificationPermission) {
            OnboardingPermissionState.Needed -> "See the current photo, export progress and a pause control outside Gallery. You can continue without notifications and enable them later in settings."
            OnboardingPermissionState.Denied -> "Notifications were declined. You can try again or continue without them."
            OnboardingPermissionState.SettingsRequired -> "Notifications are blocked. Enable them in Android notification settings, or continue without them."
            OnboardingPermissionState.Granted -> "Notifications enabled. Processing progress can appear outside Gallery."
        },
        action = when {
            notificationRequestPending -> "Waiting for notifications permission"
            notificationPermission == OnboardingPermissionState.SettingsRequired -> "Open notification settings"
            notificationPermission == OnboardingPermissionState.Denied -> "Try notifications again"
            else -> "Allow notifications"
        }, enabled = !busy, onAction = onRequestNotifications)
    PermissionRow(4, "All files access", storageGranted, divider = false,
        status = if (storageGranted) "All-files access granted"
            else "Lets Gallery find your photos, videos and waiting RAWs in DCIM/Unspektrawesome again after an update, reinstall or app-data reset. BURST needs it to save its frames.",
        action = if (storageRequestPending) "Waiting for permission" else "Allow all-files access",
        enabled = !busy, actionModifier = Modifier.testTag("Storage grant"), onAction = onRequestStorage)
    if (storageUnavailable && !storageGranted) DiagramNote("Open Android settings to allow all-files access, or use Media access in Gallery.", warning = true)
    else if (!storageGranted) DiagramHint("Android will open its All files access settings. Enable access for Unspektrawesome, then return here. This grants broad access to shared storage; Gallery looks in the app's media folder.")
}

@Composable
private fun DiscoveryPage(cameraGranted: Boolean, onAutoDiscover: () -> Unit) {
    PageTitle(8, "Lens setup", "Configure lens profiles")
    DiagramBody("Android manufacturers expose camera hardware differently. Auto Lens Discovery tests the cameras on this device and configures lenses that work with this app's RAW viewfinder and capture pipeline.")
    IntroLensStage()
    OnboardingAction("Auto Discover / Configure Lenses", Modifier.padding(vertical = 8.dp),
        symbol = SettingsSymbol.Discover, enabled = cameraGranted, onClick = onAutoDiscover)
    SetupSection("How it works")
    DiagramRow("One profile per lens", number = 1,
        detail = "Discovery tests each camera and keeps the first format that works, starting with RAW10 at up to 20 MP. This may take from tens of seconds to a few minutes. You can cancel and continue later.")
    DiagramRow("Photo and video profiles", number = 2, detail = "Separate profiles are created for photo mode and video mode.")
    DiagramRow("Choose what appears", number = 3, divider = false,
        detail = "Use the checkboxes in Lens Profiles to choose which profiles appear in the viewfinder. Profiles you made yourself are kept but may be unchecked.")
    DiagramHint("You can skip and run Auto Lens Discovery later from Lens Profiles in Camera settings.", Modifier.diagramHairline())
}

/** One permission: its number in the drawing, its state, what it is for and the button that asks for it. */
@Composable
private fun PermissionRow(
    number: Int,
    title: String,
    granted: Boolean,
    status: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
    required: Boolean = false,
    divider: Boolean = true,
    actionModifier: Modifier = Modifier,
) {
    Column(Modifier.fillMaxWidth().then(if (divider) Modifier.diagramHairline() else Modifier)
        .padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiagramNumber(number, active = granted)
            Text(title, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            DiagramValue(if (granted) "Allowed" else if (required) "Required" else "Optional", translate = true,
                color = if (granted) CameraColors.Accent else Diagram.Hint)
        }
        Text(status, color = if (granted) CameraColors.Accent else Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        if (!granted) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
            DiagramButton(action, actionModifier, enabled = enabled, accent = required, onClick = onAction)
        }
    }
}

@Composable
private fun PageTitle(number: Int, label: String, title: String) {
    val renderer = LocalAppLanguage.current.renderer
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val eyebrow = number.toString().padStart(2, '0') + " / " + renderer.text(label).uppercase()
        Text(eyebrow, translate = false, color = CameraColors.Accent, fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text(title, color = CameraColors.Text, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
    }
}
