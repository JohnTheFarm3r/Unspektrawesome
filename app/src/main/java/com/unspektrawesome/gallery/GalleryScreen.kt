package com.unspektrawesome.gallery

import com.unspektrawesome.language.AppLanguage
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.exifinterface.media.ExifInterface
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.MainActivity
import com.unspektrawesome.settings.CameraPreferencesStore
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.pageCardBackground
import com.unspektrawesome.ui.camera.CameraDialogAction
import com.unspektrawesome.ui.camera.GuideOverlay
import com.unspektrawesome.ui.camera.GuideTransition
import com.unspektrawesome.ui.onboarding.GuideProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun GalleryScreen(showProcessing: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf<GalleryStore?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { GalleryStore.get(context) } }
            .onSuccess { store = it }.onFailure { loadError = it.message }
    }
    val jobs by (store?.shots ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val processing by GalleryProcessing.state.collectAsState()
    val thermal = rememberGalleryThermalState()
    var tab by rememberSaveable { mutableIntStateOf(if (showProcessing) 1 else 0) }
    LaunchedEffect(showProcessing) { if (showProcessing) tab = 1 }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.lifecycle.addObserver(observer)
        val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { revision++ } }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        context.contentResolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mediaObserver)
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)
        onDispose { lifecycle.lifecycle.removeObserver(observer); context.contentResolver.unregisterContentObserver(mediaObserver) }
    }
    var photos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(revision, jobs) {
        delay(150)
        runCatching { withContext(Dispatchers.IO) { GalleryMedia.load(context, jobs) } }
            .onSuccess { photos = it; loadError = null }.onFailure { loadError = it.message ?: "Unable to load gallery" }
        loading = false
    }
    var viewerUri by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var rawDetails by remember { mutableStateOf<GalleryShot?>(null) }
    var showStorageAccess by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var recoveryFailed by remember { mutableStateOf(false) }
    var queueActionPending by remember { mutableStateOf(false) }
    val recovery = remember(store) { store?.let { GalleryRecovery(context.applicationContext, it) } }
    val requestRawAccess = rememberRawQueueAccess()
    fun changeQueue(affected: List<GalleryShot>, action: suspend () -> Unit) {
        if (queueActionPending || recovering) return
        queueActionPending = true
        scope.launch {
            try {
                if (requestRawAccess(affected)) action()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "Unable to save queue changes. Allow storage access and try again." }
            finally { queueActionPending = false }
        }
    }
    val pendingCount = jobs.count { it.state == ExportState.WAITING }
    val guideProgress = remember { GuideProgressStore(context) }
    var guideStep by remember { mutableIntStateOf(guideProgress.load("gallery", GALLERY_GUIDE_REVISION, galleryGuideSteps.size)) }
    LaunchedEffect(revision) {
        guideStep = guideProgress.load("gallery", GALLERY_GUIDE_REVISION, galleryGuideSteps.size)
    }
    val guideTargets = remember { mutableStateMapOf<String, Rect>() }
    val processingScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    var guidePrepared by remember(guideStep) { mutableStateOf(false) }
    fun changeGuideStep(step: Int) {
        guideStep = step.coerceIn(0, galleryGuideSteps.size)
        guideProgress.save("gallery", GALLERY_GUIDE_REVISION, guideStep)
    }
    fun guideTarget(name: String, bounds: Rect) { guideTargets[name] = bounds }
    LaunchedEffect(guideStep) {
        galleryGuideSteps.getOrNull(guideStep)?.let { entry ->
            tab = entry.tab
            when (entry.tab) {
                1 -> processingScroll.scrollToItem(entry.scrollItem)
                2 -> settingsScroll.scrollToItem(entry.scrollItem)
            }
            withFrameNanos { }
            guidePrepared = true
        }
    }
    LaunchedEffect(store, revision) {
        val repository = store ?: return@LaunchedEffect
        if (queueActionPending || GalleryProcessing.state.value.running) return@LaunchedEffect
        delay(200)
        recovering = true
        try {
            recoveryFailed = withContext(Dispatchers.IO) { (recovery?.recover()?.failed ?: 0) > 0 }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { recoveryFailed = true }
        finally { recovering = false }
        val batch = withContext(Dispatchers.IO) { repository.batch() }
        if (batch != null && !GalleryProcessing.state.value.running) {
            GalleryProcessing.state.value = GalleryProcessing.state.value.copy(total = batch.ids.size,
                completed = repository.shots.value.count { it.id in batch.ids && it.state == ExportState.EXPORTED })
            if (batch.running) GalleryProcessing.start(context, resume = true)
        }
    }
    fun start(ids: List<String> = emptyList()) {
        val selected = jobs.filter { if (ids.isEmpty()) it.state == ExportState.WAITING else it.id in ids }
        changeQueue(selected) { GalleryProcessing.start(context, ids, rawAccess = selected.filter { it.hasRaw }.map { it.rawUri }) }
    }
    fun openPhoto(uri: Uri) {
        if (photos.none { it.uri == uri }) { notice = "This item is no longer available in the app's media folder."; return }
        viewerUri = uri.toString()
    }
    AmbientScreen(Modifier.fillMaxSize(), active = viewerUri == null && !processing.running) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            if (tab != 0) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { GalleryTitleBadge(); Text(listOf("Gallery", "Processing", "Gallery settings")[tab], color = CameraColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                }
                GalleryCameraAction { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> PhotoLibrary(photos, jobs, processing.currentId, loading, loadError, pendingCount,
                        { tab = 1 }, { revision++ }, ::openPhoto, ::guideTarget, onDeleted = { deleted ->
                            photos = photos.filterNot { it.uri in deleted }
                            revision++
                        }, photoAccess = { GalleryPhotoAccess(revision) { revision++ } })
                    1 -> ProcessingPage(jobs, processing, thermal, ::start, { GalleryProcessing.pause(context) }, { job ->
                        if (job.jpegUri != null) openPhoto(job.jpegUri) else rawDetails = job
                    }, { job -> changeQueue(listOf(job)) { withContext(Dispatchers.IO) { store?.cancelWaiting(setOf(job.id)) } } }, {
                        changeQueue(jobs.filter { it.state in setOf(ExportState.WAITING, ExportState.FAILED) }) {
                            withContext(Dispatchers.IO) { GalleryProcessing.pause(context); store?.cancelWaiting() }
                        }
                    }, processingScroll, ::guideTarget, onClearHistory = { ids ->
                        changeQueue(jobs.filter { it.id in ids }) { withContext(Dispatchers.IO) { store?.clearHistory(ids) } }
                    }, queueBusy = recovering || queueActionPending, recoveryStatus = {
                        GalleryPhotoAccess(revision) { revision++ }
                        if (recovering) Text("Restoring saved RAWs…", color = CameraColors.Muted, fontSize = 12.sp)
                        if (recoveryFailed) GalleryCard {
                            Text("Some RAWs could not be restored. Check storage access and try again.", color = CameraColors.Muted, fontSize = 12.sp)
                            GalleryAction("Retry") { revision++ }
                        }
                    })
                    2 -> GallerySettings(jobs, revision, thermal, settingsScroll, ::guideTarget, { changeGuideStep(0) }, { showStorageAccess = true })
                }
            }
            Row(Modifier.fillMaxWidth()
                .border(androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent))
                .drawBehind { drawLine(CameraColors.MenuBorder, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf("Gallery", "Processing", "Settings").forEachIndexed { index, label ->
                    Column(Modifier.weight(1f).heightIn(min = 64.dp)
                        .drawBehind { if (tab == index) drawLine(CameraColors.Accent, Offset(size.width / 2 - 16.dp.toPx(), 0f), Offset(size.width / 2 + 16.dp.toPx(), 0f), 2.dp.toPx()) }
                        .clickable { tab = index }.padding(vertical = 8.dp).testTag("Gallery tab $label")
                        .onGloballyPositioned { guideTarget("tab$index", it.boundsInRoot()) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            GalleryIcon(index, if (tab == index) CameraColors.Accent else CameraColors.Muted, Modifier.size(20.dp))
                            if (index == 1 && pendingCount > 0) Text(pendingCount.toString(), translate = false,
                                color = CameraColors.Accent, fontSize = 10.sp,
                                modifier = Modifier.border(1.dp, CameraColors.Accent, RoundedCornerShape(50))
                                    .padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                        Text(label, color = if (tab == index) CameraColors.Accent else CameraColors.Muted,
                            fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        if (showStorageAccess) Dialog(onDismissRequest = { showStorageAccess = false; revision++ },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            com.unspektrawesome.ui.onboarding.StorageAccessOnboarding { showStorageAccess = false; revision++ }
        }
        val visibleGuide = guideStep.takeIf { galleryGuideSteps.getOrNull(it)?.let { guide -> guidePrepared && guide.tab == tab && !loading && store != null &&
            viewerUri == null && rawDetails == null && notice == null } == true }
        GuideTransition(visibleGuide) { displayedStep ->
            val guide = galleryGuideSteps[displayedStep]
            GuideOverlay("GALLERY GUIDE", displayedStep, galleryGuideSteps.size, guide.title, guide.description,
                guideTargets[guide.target], onSkip = { changeGuideStep(galleryGuideSteps.size) },
                onNext = { changeGuideStep(displayedStep + 1) }, tag = "Gallery guide")
        }
        if (viewerUri != null) {
            PhotoViewer(photos, viewerUri!!, jobs, onDismiss = { viewerUri = null }, onError = { notice = it }, onDeleted = { revision++ })
        }
        notice?.let { message -> GalleryDialog("Photo unavailable", message, "Close", { notice = null }, { notice = null }) }
        rawDetails?.let { shot ->
            if (shot.panorama) GalleryDialog("Panorama RAWs",
                "Panorama RAW frames are kept on this device. Use Export to stitch them into a full-resolution PNG.",
                "Export", { rawDetails = null }, { rawDetails = null; start(listOf(shot.id)) }, dismissLabel = "Close")
            else
            GalleryDialog("Capture details", "${date(shot.capturedAt, "d MMMM yyyy · HH:mm:ss")}\n${shot.lens}\n${shot.width} × ${shot.height} · ${bytes(shot.rawBytes)}\n${shot.look}\n\nThe original DNG is kept for retries and other editors.",
                "Share original DNG", { rawDetails = null }, {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "image/x-adobe-dng"; putExtra(Intent.EXTRA_STREAM, shot.rawUri)
                        clipData = android.content.ClipData.newRawUri("Original DNG", shot.rawUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share original DNG")) }.onFailure { notice = it.message }
                }, dismissLabel = "Close")
        }
        }
    }
}

@Composable
private fun ProcessingPage(jobs: List<GalleryShot>, status: ProcessingStatus, thermal: GalleryThermalState, onStart: (List<String>) -> Unit,
    onPause: () -> Unit, onOpen: (GalleryShot) -> Unit, onSkip: (GalleryShot) -> Unit, onCancelAll: () -> Unit,
    scroll: LazyListState, onGuideTarget: (String, Rect) -> Unit, onClearHistory: (Set<String>) -> Unit,
    queueBusy: Boolean = false, recoveryStatus: @Composable () -> Unit = {}) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cancelAll by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf<Set<String>?>(null) }
    clearHistory?.let { ids -> GalleryDialog("Clear history?",
        "Remove these entries from the list. JPEGs and original DNGs stay saved.", "Clear history",
        { clearHistory = null }, { clearHistory = null; onClearHistory(ids) }) }
    var cancelShot by remember { mutableStateOf<GalleryShot?>(null) }
    if (cancelAll) GalleryDialog("Cancel waiting exports?", "Waiting and failed exports will be cancelled and their original DNGs kept. The current photo will finish using your export and RAW cleanup settings. Completed JPEGs are kept.", "Cancel waiting exports", { cancelAll = false }, { cancelAll = false; onCancelAll() })
    cancelShot?.let { shot -> GalleryDialog("Cancel this export?", if (shot.panorama) "Panorama RAWs are kept for later export." else "The original DNG stays saved. You can export it again later or share it with another editor.", "Cancel export", { cancelShot = null }, { cancelShot = null; onSkip(shot) }) }
    val waiting = jobs.count { it.state == ExportState.WAITING }
    val errors = jobs.count { it.state == ExportState.FAILED }
    val visible = jobs.filter { !it.historyHidden && when (filter) {
        1 -> it.state == ExportState.EXPORTED
        2 -> it.state in setOf(ExportState.FAILED, ExportState.SKIPPED, ExportState.RAW_REMOVED)
        else -> it.state in setOf(ExportState.WAITING, ExportState.PROCESSING, ExportState.SAVING_RAW)
    } }
    LaunchedEffect(jobs) { selected = selected.filter { id -> jobs.any { it.id == id && it.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.SKIPPED) } }.toSet() }
    LazyColumn(state = scroll, modifier = Modifier.testTag("Gallery processing list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            recoveryStatus()
            GalleryCard(modifier = Modifier.onGloballyPositioned { onGuideTarget("batch", it.boundsInRoot()) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when {
                            status.waitingForCooling -> "PAUSED FOR HEAT"
                            status.running -> "DEVELOPING YOUR PHOTOS"
                            thermal.blocksExport -> "PHONE TOO HOT TO EXPORT"
                            else -> "READY WHEN YOU ARE"
                        }, color = CameraColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = .8.sp)
                        Text(if (status.running) "${status.completed} / ${status.total} exported" else "$waiting waiting", color = CameraColors.Text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    GalleryIcon(1, CameraColors.Accent, Modifier.size(20.dp))
                }
                if ((!status.waitingForCooling && status.running) || !thermal.blocksExport) Text(when {
                    status.waitingForCooling -> if (status.pauseRequested) "Pausing exports." else "Resuming exports automatically."
                    status.running -> if (status.pauseRequested) "Finishing the current photo, then pausing." else status.stage.ifEmpty { "Preparing your queue" }
                    else -> status.message
                }, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (thermal.blocksExport) Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Heat limit reached", color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout)
                    Text("Your phone is too hot for exporting with Pause when hot on. " +
                        if (status.running && status.currentId != null) "The current photo will finish, then processing pauses."
                        else "Your waiting RAWs are kept.", color = CameraColors.Text, fontSize = 12.sp, lineHeight = 18.sp)
                    Text(if (status.pauseRequested) "Automatic resume is stopped. Your queue is kept."
                        else if (status.running) "Exporting will resume automatically once your phone cools."
                        else "Choose Export when cooler to queue photos. Exporting will start automatically once your phone cools.",
                        color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
                if (!status.running && status.total > 0) Text("Last batch · {completed} of {total} exported", arguments = mapOf("completed" to (status.completed), "total" to (status.total)), color = CameraColors.Muted, fontSize = 11.sp)
                if (status.running) LinearProgressIndicator(progress = { status.completed.toFloat() / status.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = CameraColors.Accent, trackColor = CameraColors.MenuCard)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.running) GalleryAction(when {
                    status.waitingForCooling -> if (status.pauseRequested) "Pausing exports" else "Stop automatic resume"
                    status.pauseRequested -> "Pausing after this photo"
                    else -> "Pause after this photo"
                }, selected = true, enabled = !status.pauseRequested, onClick = onPause,
                    modifier = Modifier.onGloballyPositioned { onGuideTarget("start", it.boundsInRoot()) })
                else GalleryAction(when {
                    thermal.blocksExport -> "Export when cooler · ${if (selected.isNotEmpty()) selected.size else waiting}"
                    selected.isNotEmpty() -> "Export ${selected.size} selected"
                    else -> "Start exporting · $waiting"
                }, selected = true, enabled = !queueBusy && (waiting > 0 || selected.isNotEmpty()), icon = 4,
                    modifier = Modifier.testTag("Gallery start exporting").onGloballyPositioned { onGuideTarget("start", it.boundsInRoot()) }) { onStart(selected.toList()); selected = emptySet() }
                if (waiting > 0 || errors > 0) GalleryAction("Cancel waiting exports") { cancelAll = true }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).onGloballyPositioned { onGuideTarget("queue-tabs", it.boundsInRoot()) }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Queue", "Exported", if (errors > 0) "Needs attention · $errors" else "Cancelled").forEachIndexed { index, label ->
                    GalleryAction(label, selected = filter == index) { filter = index; selected = emptySet() }
                }
            }
        }
        val clearable = visible.filter { it.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.RAW_REMOVED) }
        if (filter != 0 && clearable.isNotEmpty()) item {
            GalleryAction("Clear history", enabled = !status.running) { clearHistory = clearable.map { it.id }.toSet() }
        }
        if (visible.isEmpty()) item {
            EmptyGallery(if (filter == 0) "Room for your next session" else if (filter == 1) "Your finished exports" else "All clear",
                if (filter == 0) "Set the camera to Later in Gallery, then shoot. Each RAW waits here until you choose to export."
                else if (filter == 1) "Completed JPEGs appear here and in Gallery. Each card shows whether its original RAW was kept."
                else "Photos needing a retry and RAWs you choose to keep without exporting appear here.")
        }
        items(visible, key = { it.id }) { shot ->
            GalleryQueueRow(selected = shot.id in selected, modifier = Modifier.testTag("Gallery processing shot ${shot.id}")) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(9.dp)).background(CameraColors.MenuCard).clickable { onOpen(shot) }) {
                        PhotoImage(shot.jpegUri ?: shot.rawUri, shot.id.takeIf { shot.jpegUri == null }, Modifier.fillMaxSize(), imageKey = shot.state)
                        Text(if (shot.grid) "3×3" else if (shot.panorama) "PANORAMA" else if (shot.jpegUri == null) "RAW PREVIEW" else "JPEG", color = CameraColors.Text, fontSize = 7.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).background(CameraColors.Glass).fillMaxWidth().padding(3.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(date(shot.capturedAt, "d MMM · HH:mm:ss"), color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout)
                        Text(shot.lens, color = CameraColors.Muted, fontSize = 12.sp, softWrap = true)
                        Text("{width} × {height} · {value3} MP", arguments = mapOf("width" to (shot.width), "height" to (shot.height), "value3" to (megapixels(shot.width, shot.height))), color = CameraColors.Muted, fontSize = 10.sp)
                        Text(shot.look, color = CameraColors.Muted, fontSize = 11.sp, softWrap = true)
                    }
                }
                val active = status.currentId == shot.id && status.running
                Text(if (active) status.stage else when (shot.state) {
                    ExportState.WAITING -> "Waiting · ${bytes(shot.rawBytes)} RAW kept"
                    ExportState.SAVING_RAW -> "Saving original RAW"
                    ExportState.PROCESSING -> "Preparing export"
                    ExportState.EXPORTED -> if (shot.panorama) "Panorama saved · RAW frames kept" else when (shot.rawRetention) {
                        RawRetention.KEPT -> "JPEG saved · original RAW kept"
                        RawRetention.DELETING -> "JPEG saved · removing original RAW"
                        RawRetention.DELETED -> "JPEG verified · original RAW deleted"
                    }
                    ExportState.FAILED -> "Export needs attention"
                    ExportState.SKIPPED -> if (shot.panorama) "Panorama RAWs kept for later export." else "Export cancelled · original DNG kept"
                    ExportState.RAW_REMOVED -> "Original RAW deleted"
                }, color = if (active) CameraColors.Accent else CameraColors.Muted, fontSize = 11.sp)
                if (!status.running && shot.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.RAW_REMOVED)) {
                    GalleryAction("Remove from history") { clearHistory = setOf(shot.id) }
                }
                if (active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CameraColors.Accent, trackColor = CameraColors.MenuCard)
                if (shot.detail.isNotBlank()) Text(shot.detail, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
                if (!status.running && shot.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.SKIPPED)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GalleryAction((if (shot.state == ExportState.FAILED) "Retry" else "Export") +
                            if (thermal.blocksExport) " when cooler" else "", selected = true) { onStart(listOf(shot.id)) }
                        GalleryAction(if (shot.id in selected) "Selected" else "Select", selected = shot.id in selected) { selected = if (shot.id in selected) selected - shot.id else selected + shot.id }
                        if (shot.state != ExportState.SKIPPED) GalleryAction("Cancel", onClick = { cancelShot = shot })
                    }
                }
                if (status.running && shot.state in setOf(ExportState.WAITING, ExportState.FAILED)) {
                    GalleryAction("Cancel this export", onClick = { cancelShot = shot })
                }
            }
        }
    }
}

@Composable
private fun GallerySettings(jobs: List<GalleryShot>, revision: Int, thermal: GalleryThermalState, scroll: LazyListState,
    onGuideTarget: (String, Rect) -> Unit, onShowGuide: () -> Unit, onStorageAccess: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { CameraPreferencesStore(context) }
    var later by remember(revision) { mutableStateOf(preferences.load().processPhotosLater) }
    val galleryPreferences = remember { context.getSharedPreferences("gallery", 0) }
    val rawCleanup = remember { RawCleanupPreferences(context) }
    var deleteRaws by remember { mutableStateOf(rawCleanup.authorization() != null) }
    var confirmRawDeletion by rememberSaveable { mutableStateOf(false) }
    if (confirmRawDeletion) GalleryDialog("Delete RAWs after export?",
        "This permanently deletes each capture's original RAW after its JPEG export completes and passes verification. If exporting has issues, or rendering looks wrong because of an app bug, the RAW cannot be recovered and you may lose the capture.\n\nChecks catch incomplete, unusually small, nearly black or uniform JPEGs, but cannot detect every rendering problem. Uncertain exports keep their RAW.\n\nApplies to exports started after you enable this option. Existing finished exports are untouched.",
        "Enable RAW deletion", { confirmRawDeletion = false }, {
            if (rawCleanup.setEnabled(true)) {
                deleteRaws = true
                confirmRawDeletion = false
            }
        }, dismissLabel = "Keep RAWs")
    val pauseHot = thermal.pauseWhenHot
    var priority by remember { mutableStateOf(ProcessingPriority.from(galleryPreferences.getString("priority", null))) }
    var progressCard by remember(revision) {
        mutableStateOf(galleryPreferences.getBoolean("progress_notifications", true) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val free by produceState(0L, revision, jobs) { value = withContext(Dispatchers.IO) { StatFs(context.filesDir.absolutePath).availableBytes } }
    LazyColumn(state = scroll, modifier = Modifier.testTag("Gallery settings list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { GallerySettingsSection {
            Eyebrow("NEW CAMERA CAPTURES")
            Text("Choose when to process", color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text("This also changes the camera's processing setting. Waiting photos keep their saved settings.", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            FlowRow(Modifier.onGloballyPositioned { onGuideTarget("capture-mode", it.boundsInRoot()) },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryAction("Process now", selected = !later) { later = false; preferences.save(preferences.load().copy(processPhotosLater = false)) }
                GalleryAction("Later in Gallery", selected = later) { later = true; preferences.save(preferences.load().copy(processPhotosLater = true)) }
            }
        } }
        item { GallerySettingsSection {
            GallerySettingToggle("Pause when hot", pauseHot) { galleryPreferences.edit().putBoolean("pause_when_hot", it).apply() }
            Text("Pauses at your phone's high heat threshold, after the current photo finishes, and resumes automatically below that limit. Processing shows when exports are waiting for cooling.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Eyebrow("PROCESSING PRIORITY")
            FlowRow(Modifier.onGloballyPositioned { onGuideTarget("priority", it.boundsInRoot()) },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProcessingPriority.entries.forEach { choice -> GalleryAction(choice.label, selected = priority == choice) {
                    priority = choice; galleryPreferences.edit().putString("priority", choice.name).apply()
                } }
            }
            Text("{detail} Quality stays identical. Changes apply to the next photo.", arguments = mapOf("detail" to (priority.detail)), color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        } }
        item { GallerySettingsSection {
            Eyebrow("NOTIFICATIONS")
            GallerySettingToggle("Detailed progress card", progressCard,
                modifier = Modifier.onGloballyPositioned { onGuideTarget("notifications", it.boundsInRoot()) }) { enabled ->
                progressCard = enabled; galleryPreferences.edit().putBoolean("progress_notifications", progressCard).apply()
                if (progressCard && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            Text("Show the current photo, export progress, and pause control. Android requires a service notice while background processing runs.", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            GalleryAction("Android notification settings") { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        } }
        item { GallerySettingsSection(modifier = Modifier.onGloballyPositioned { onGuideTarget("originals", it.boundsInRoot()) }) {
            Eyebrow("ORIGINALS & STORAGE")
            Text("{value1} available", arguments = mapOf("value1" to (bytes(free))), color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text("{value1} captured RAWs · {value2}", arguments = mapOf("value1" to (jobs.count { it.hasRaw }), "value2" to (bytes(jobs.sumOf { it.rawBytes }))), color = CameraColors.Muted, fontSize = 12.sp)
            GallerySettingToggle("Delete RAWs after export", deleteRaws, modifier = Modifier.testTag("RAW cleanup setting")) { enabled ->
                if (enabled) confirmRawDeletion = true
                else if (rawCleanup.setEnabled(false)) deleteRaws = false
            }
            Text(if (deleteRaws) "Verified exports can permanently remove their original DNGs. Failed, cancelled, recovered or suspicious exports keep their RAWs. Turn off to keep originals for future exports."
                else "Original DNGs are kept by default, including after failed or cancelled exports. Share a kept RAW from a processing card or exported photo to use another editor.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            StorageLine("JPEG photos", "DCIM/Unspektrawesome")
            StorageLine("Original RAWs", "DCIM/Unspektrawesome/RAW")
            GalleryAction("All files access", onClick = onStorageAccess)
        } }
        item { GallerySettingsSection {
            Eyebrow("CAPTURE, PRESERVED")
            GallerySettingsDisclosure("The same spektrafilm processing") {
                Text("Give your phone a breather", color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Text("Exports run one at a time. Returning to the camera pauses after the current photo. You can continue with the screen off.", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("Exports use the camera's full processing pipeline and each shot's saved settings. Photo dates come from capture time, so your timeline stays in order.", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("Unspektrawesome {VERSION_NAME}", arguments = mapOf("VERSION_NAME" to (BuildConfig.VERSION_NAME)), color = CameraColors.Muted, fontSize = 10.sp)
            }
        } }
        item { GallerySettingsSection {
            Eyebrow("GALLERY GUIDE")
            Text("Walk through photos, processing and settings again.", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            GalleryAction("Show Gallery guide", selected = true, onClick = onShowGuide)
        } }
    }
}

@Composable private fun StorageLine(label: String, path: String) {
    Column { Text(label, color = CameraColors.Text, fontSize = 12.sp); Text(path, translate = false, color = CameraColors.Muted, fontSize = 10.sp) }
}

@Composable
internal fun GalleryAction(label: String, selected: Boolean = false, enabled: Boolean = true, icon: Int? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (!enabled) CameraColors.Muted.copy(alpha = .4f) else if (selected) CameraColors.Accent else CameraColors.Text
    Row(modifier.clip(RoundedCornerShape(50))
        .border(1.dp, if (selected && enabled) CameraColors.Accent else CameraColors.MenuBorder, RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { GalleryIcon(it, color, Modifier.size(16.dp)) }
        Text(label, color = color, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable internal fun GalleryCard(selected: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(com.unspektrawesome.ui.camera.CameraStyle.PanelShape).pageCardBackground()
        .border(1.dp, if (selected) CameraColors.Accent else CameraColors.MenuBorder, com.unspektrawesome.ui.camera.CameraStyle.PanelShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable private fun Eyebrow(label: String) { Text(label, color = CameraColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = .8.sp) }

@Composable internal fun EmptyGallery(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GalleryIcon(0, CameraColors.Muted, Modifier.size(44.dp))
        Text(title, color = CameraColors.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(detail, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.let { GalleryAction(it, onClick = onAction) }
    }
}

@Composable internal fun GalleryIcon(kind: Int, color: Color, modifier: Modifier = Modifier) {
    com.unspektrawesome.ui.camera.CameraIcon(when (kind) {
        0 -> com.unspektrawesome.R.drawable.ic_sleek_images
        1 -> com.unspektrawesome.R.drawable.ic_sleek_list_todo
        2 -> com.unspektrawesome.R.drawable.ic_sleek_settings
        3 -> com.unspektrawesome.R.drawable.ic_sleek_camera
        else -> com.unspektrawesome.R.drawable.ic_sleek_play
    }, modifier, color)
}

@Composable internal fun PhotoImage(uri: Uri, shotId: String?, modifier: Modifier, imageKey: Any? = null,
    isVideo: Boolean = false, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, shotId, imageKey) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (com.unspektrawesome.burst.BurstMedia.isBurst(context, uri))
                    return@runCatching com.unspektrawesome.burst.BurstMedia.thumbnail(context, uri)
                if (shotId != null) {
                    val file = GalleryStore.get(context).ensureThumbnail(shotId)
                    if (file.exists()) return@runCatching BitmapFactory.decodeFile(file.absolutePath)
                }
                context.contentResolver.loadThumbnail(uri, android.util.Size(360, 360), null)
            }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = AppLanguage.text(if (isVideo) "Video preview" else "Photo preview"), modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        else GalleryIcon(0, CameraColors.Muted.copy(alpha = .5f), Modifier.size(28.dp))
    }
}

@Composable internal fun GalleryDialog(title: String, detail: String, action: String, onDismiss: () -> Unit, onAction: () -> Unit, dismissLabel: String = "Cancel", extraContent: @Composable () -> Unit = {}) {
    com.unspektrawesome.ui.camera.CameraDialogWindow(onDismiss, header = {
        Text(title, color = CameraColors.Text, fontWeight = FontWeight.SemiBold)
    }, footer = {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (action != "Close") CameraDialogAction(dismissLabel, false, onDismiss)
            CameraDialogAction(action, true, onAction)
        }
    }) {
            Text(detail, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
            extraContent()
    }
}

internal fun date(time: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
internal fun bytes(value: Long): String = if (value >= 1024L * 1024 * 1024) String.format(Locale.getDefault(), "%.1f GB", value / (1024.0 * 1024 * 1024)) else String.format(Locale.getDefault(), "%.1f MB", value / (1024.0 * 1024))
internal fun megapixels(width: Int, height: Int): String = String.format(Locale.getDefault(), "%.1f", width.toLong() * height / 1_000_000.0)
