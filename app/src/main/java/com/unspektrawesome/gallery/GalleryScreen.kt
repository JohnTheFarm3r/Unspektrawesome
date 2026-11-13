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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.MainActivity
import com.unspektrawesome.R
import com.unspektrawesome.settings.CameraPreferencesStore
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramBody
import com.unspektrawesome.ui.camera.DiagramButton
import com.unspektrawesome.ui.camera.DiagramButtonRow
import com.unspektrawesome.ui.camera.DiagramDialog
import com.unspektrawesome.ui.camera.DiagramFlow
import com.unspektrawesome.ui.camera.DiagramHint
import com.unspektrawesome.ui.camera.DiagramNodeState
import com.unspektrawesome.ui.camera.DiagramNumber
import com.unspektrawesome.ui.camera.DiagramRadioGroup
import com.unspektrawesome.ui.camera.DiagramSection
import com.unspektrawesome.ui.camera.DiagramSegmented
import com.unspektrawesome.ui.camera.DiagramStep
import com.unspektrawesome.ui.camera.DiagramSwitchRow
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.ui.camera.GuideOverlay
import com.unspektrawesome.ui.camera.GuideTransition
import com.unspektrawesome.ui.camera.diagramHairline
import com.unspektrawesome.ui.onboarding.GuideProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SoftInk = Color(0xFFCFCFCF)
private val RowDetail = Color(0xFF8C8C8C)

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
            if (tab != 0) GalleryHeader(listOf("Gallery", "Processing", "Gallery settings")[tab], divider = tab == 2) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
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
                        if (recovering) DiagramHint("Restoring saved RAWs…")
                        if (recoveryFailed) GalleryCard(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("Some RAWs could not be restored. Check storage access and try again.", color = RowDetail,
                                fontSize = 11.5.sp, lineHeight = 16.sp)
                            GalleryAction("Retry") { revision++ }
                        }
                    })
                    2 -> GallerySettings(jobs, revision, thermal, processing, guideStep, settingsScroll, ::guideTarget,
                        { changeGuideStep(0) }, { showStorageAccess = true })
                }
            }
            GalleryNavigation(tab, pendingCount, { tab = it }, ::guideTarget)
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
                "Export", { rawDetails = null }, { rawDetails = null; start(listOf(shot.id)) }, dismissLabel = "Close", drawing = {
                    DiagramFlow(listOf(DiagramStep("RAW frames", state = DiagramNodeState.Done),
                        DiagramStep("Stitching panorama", state = DiagramNodeState.Idle),
                        DiagramStep("PNG", state = DiagramNodeState.Active, translate = false)))
                })
            else CaptureDetailsDialog(shot, onDismiss = { rawDetails = null }) {
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/x-adobe-dng"; putExtra(Intent.EXTRA_STREAM, shot.rawUri)
                    clipData = android.content.ClipData.newRawUri("Original DNG", shot.rawUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share original DNG")) }.onFailure { notice = it.message }
            }
        }
        }
    }
}

@Composable
private fun GalleryHeader(title: String, divider: Boolean, onCamera: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (divider) Modifier.drawBehind {
            drawLine(Diagram.StageBorder, Offset(0f, size.height - .5.dp.toPx()), Offset(size.width, size.height - .5.dp.toPx()), 1.dp.toPx())
        } else Modifier)
        .padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GalleryTitleBadge()
        Text(title, color = CameraColors.Text, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        GalleryCameraAction(onCamera)
    }
}

@Composable
private fun GalleryNavigation(tab: Int, pending: Int, onSelect: (Int) -> Unit, onGuideTarget: (String, Rect) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0x99000000))
        .drawBehind { drawLine(Diagram.StageBorder, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }.selectableGroup()) {
        listOf("Gallery", "Processing", "Settings").forEachIndexed { index, label ->
            val on = tab == index
            val color = if (on) CameraColors.Accent else Diagram.Hint
            Box(Modifier.weight(1f).heightIn(min = 56.dp)
                .drawBehind {
                    if (on) drawLine(CameraColors.Accent, Offset(size.width / 2 - 11.dp.toPx(), 1.dp.toPx()),
                        Offset(size.width / 2 + 11.dp.toPx(), 1.dp.toPx()), 2.dp.toPx())
                }
                .selectable(on, role = Role.Tab) { onSelect(index) }.testTag("Gallery tab $label")
                .onGloballyPositioned { onGuideTarget("tab$index", it.boundsInRoot()) }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GalleryIcon(index, color, Modifier.size(18.dp))
                    Text(label, color = color, fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center)
                }
                if (index == 1 && pending > 0) Box(Modifier.align(Alignment.TopCenter).offset(x = 16.dp, y = 6.dp)
                    .heightIn(min = 15.dp).widthIn(min = 16.dp).background(CameraColors.Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(pending.toString(), translate = false, color = Color.Black, fontSize = 9.sp, lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CaptureDetailsDialog(shot: GalleryShot, onDismiss: () -> Unit, onShare: () -> Unit) {
    DiagramDialog("Capture details", onDismiss, buttons = {
        DiagramButton("Close", onClick = onDismiss)
        DiagramButton("Share original DNG", accent = true, onClick = onShare)
    }) {
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(date(shot.capturedAt, "d MMMM yyyy · HH:mm:ss"), translate = false, color = SoftInk, fontSize = 10.5.sp,
                lineHeight = 15.sp, fontFamily = Diagram.Mono)
            Text(shot.lens, color = SoftInk, fontSize = 10.5.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono)
            Text("{width} × {height} · {value3}", arguments = mapOf("width" to shot.width, "height" to shot.height,
                "value3" to bytes(shot.rawBytes)), color = SoftInk, fontSize = 10.5.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono)
            Text(shot.look, color = SoftInk, fontSize = 10.5.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono)
        }
        DiagramBody("The original DNG is kept for retries and other editors.")
        DiagramFlow(listOf(DiagramStep("DNG", state = DiagramNodeState.Active, translate = false),
            DiagramStep("Share original DNG", state = DiagramNodeState.Done),
            DiagramStep("other editor", state = DiagramNodeState.Off)), Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ProcessingPage(jobs: List<GalleryShot>, status: ProcessingStatus, thermal: GalleryThermalState, onStart: (List<String>) -> Unit,
    onPause: () -> Unit, onOpen: (GalleryShot) -> Unit, onSkip: (GalleryShot) -> Unit, onCancelAll: () -> Unit,
    scroll: LazyListState, onGuideTarget: (String, Rect) -> Unit, onClearHistory: (Set<String>) -> Unit,
    queueBusy: Boolean = false, recoveryStatus: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cancelAll by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf<Set<String>?>(null) }
    clearHistory?.let { ids -> GalleryDialog("Clear history?",
        "Remove these entries from the list. JPEGs and original DNGs stay saved.", "Clear history",
        { clearHistory = null }, { clearHistory = null; onClearHistory(ids) }) }
    var cancelShot by remember { mutableStateOf<GalleryShot?>(null) }
    if (cancelAll) GalleryDialog("Cancel waiting exports?", "Waiting and failed exports will be cancelled and their original DNGs kept. The current photo will finish using your export and RAW cleanup settings. Completed JPEGs are kept.", "Cancel waiting exports", { cancelAll = false }, { cancelAll = false; onCancelAll() })
    cancelShot?.let { shot -> GalleryDialog("Cancel this export?", if (shot.panorama) "Panorama RAWs are kept for later export." else "The original DNG stays saved. You can export it again later or share it with another editor.", "Cancel export", { cancelShot = null }, { cancelShot = null; onSkip(shot) }, drawing = {
        DiagramFlow(listOf(DiagramStep("Queue", state = DiagramNodeState.Done), DiagramStep("Cancelled", state = DiagramNodeState.Off),
            DiagramStep(if (shot.panorama) "RAW frames kept" else "DNG stays saved", state = DiagramNodeState.Active)))
    }) }
    val waiting = jobs.count { it.state == ExportState.WAITING }
    val errors = jobs.count { it.state == ExportState.FAILED }
    val visible = jobs.filter { !it.historyHidden && when (filter) {
        1 -> it.state == ExportState.EXPORTED
        2 -> it.state in setOf(ExportState.FAILED, ExportState.SKIPPED, ExportState.RAW_REMOVED)
        else -> it.state in setOf(ExportState.WAITING, ExportState.PROCESSING, ExportState.SAVING_RAW)
    } }
    LaunchedEffect(jobs) { selected = selected.filter { id -> jobs.any { it.id == id && it.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.SKIPPED) } }.toSet() }
    val preferences = remember { context.getSharedPreferences("gallery", 0) }
    val rawCleanup = remember { RawCleanupPreferences(context) }
    val cleanupOn = remember(status.currentId, status.stage, status.running) { rawCleanup.authorization() != null }
    val priority = remember(status.currentId, status.stage) { ProcessingPriority.from(preferences.getString("priority", null)) }
    val current = jobs.firstOrNull { it.id == status.currentId }
    LazyColumn(state = scroll, modifier = Modifier.testTag("Gallery processing list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
        item {
            recoveryStatus()
            ProcessingStatusPanel(status, thermal, waiting, errors, selected.size, current, cleanupOn, priority, queueBusy, onGuideTarget,
                onPause, onStart = { onStart(selected.toList()); selected = emptySet() }, onCancelAll = { cancelAll = true })
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)
                .onGloballyPositioned { onGuideTarget("queue-tabs", it.boundsInRoot()) }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GalleryFilterChip("Queue", filter == 0) { filter = 0; selected = emptySet() }
                GalleryFilterChip("Exported", filter == 1) { filter = 1; selected = emptySet() }
                if (errors > 0) GalleryFilterChip("Needs attention · {errors}", filter == 2, arguments = mapOf("errors" to errors)) { filter = 2; selected = emptySet() }
                else GalleryFilterChip("Cancelled", filter == 2) { filter = 2; selected = emptySet() }
            }
        }
        if (filter != 0) item {
            if (errors > 0) ExportOutcomesStage(filter == 1, "Needs attention · {errors}", mapOf("errors" to errors))
            else ExportOutcomesStage(filter == 1, "Cancelled", null)
        }
        val clearable = visible.filter { it.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.RAW_REMOVED) }
        if (filter != 0 && clearable.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GalleryButton("Clear history", compact = true, enabled = !status.running) { clearHistory = clearable.map { it.id }.toSet() }
                Box(Modifier.padding(horizontal = 6.dp).size(14.dp, 1.dp).background(CameraColors.Accent.copy(alpha = .7f)))
                Text("Remove these entries from the list. JPEGs and original DNGs stay saved.", color = Diagram.Hint,
                    fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        if (visible.isEmpty()) item {
            EmptyGallery(if (filter == 0) "Room for your next session" else if (filter == 1) "Your finished exports" else "All clear",
                if (filter == 0) "Set the camera to Later in Gallery, then shoot. Each RAW waits here until you choose to export."
                else if (filter == 1) "Completed JPEGs appear here and in Gallery. Each card shows whether its original RAW was kept."
                else "Photos needing a retry and RAWs you choose to keep without exporting appear here.")
        }
        items(visible, key = { it.id }) { shot ->
            val active = status.currentId == shot.id && status.running
            GalleryQueueRow(selected = shot.id in selected, modifier = Modifier.testTag("Gallery processing shot ${shot.id}")) {
                val actions = mutableListOf<@Composable () -> Unit>()
                if (!status.running && shot.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.SKIPPED)) {
                    val retry = shot.state == ExportState.FAILED
                    actions += {
                        GalleryButton(if (thermal.blocksExport) { if (retry) "Retry when cooler" else "Export when cooler" }
                            else if (retry) "Retry" else "Export", style = GalleryButtonStyle.Accent, compact = true) { onStart(listOf(shot.id)) }
                    }
                    actions += {
                        GalleryButton(if (shot.id in selected) "Selected" else "Select", compact = true,
                            style = if (shot.id in selected) GalleryButtonStyle.Accent else GalleryButtonStyle.Outline) {
                            selected = if (shot.id in selected) selected - shot.id else selected + shot.id
                        }
                    }
                    if (shot.state != ExportState.SKIPPED) actions += { GalleryButton("Cancel", compact = true) { cancelShot = shot } }
                }
                if (!status.running && shot.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.RAW_REMOVED)) {
                    actions += { GalleryButton("Remove from history", compact = true) { clearHistory = setOf(shot.id) } }
                }
                if (status.running && shot.state in setOf(ExportState.WAITING, ExportState.FAILED)) {
                    actions += { GalleryButton("Cancel this export", compact = true) { cancelShot = shot } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QueueThumbnail(shot, active) { onOpen(shot) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(date(shot.capturedAt, "d MMM · HH:mm:ss"), translate = false, color = CameraColors.Text, fontSize = 11.sp,
                            lineHeight = 14.sp, fontFamily = Diagram.Mono)
                        Text(shot.lens, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 13.sp)
                        Text("{width} × {height} · {value3} MP", arguments = mapOf("width" to (shot.width), "height" to (shot.height),
                            "value3" to (megapixels(shot.width, shot.height))), color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 12.sp,
                            fontFamily = Diagram.Mono)
                        Text(shot.look, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 13.sp)
                    }
                    if (actions.size == 1) Box(Modifier.widthIn(max = 140.dp)) { actions[0]() }
                }
                Column(Modifier.padding(start = 62.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    QueueStatusLine(shot, active, status.stage)
                    if (active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = CameraColors.Accent,
                        trackColor = Color(0xFF1F1F1F))
                    if (shot.detail.isNotBlank()) Text(shot.detail, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
                    if (actions.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions.forEach { it() } }
                }
            }
        }
    }
}

@Composable
private fun ProcessingStatusPanel(status: ProcessingStatus, thermal: GalleryThermalState, waiting: Int, errors: Int, selectedCount: Int,
    current: GalleryShot?, cleanupOn: Boolean, priority: ProcessingPriority, queueBusy: Boolean,
    onGuideTarget: (String, Rect) -> Unit, onPause: () -> Unit, onStart: () -> Unit, onCancelAll: () -> Unit) {
    val heat = status.waitingForCooling
    val ready = !status.running && !thermal.blocksExport
    val strip = status.running && !heat && status.total in 1..40
    DiagramPanel(Modifier.onGloballyPositioned { onGuideTarget("batch", it.boundsInRoot()) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (heat) HeatGlyph() else if (status.running) Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).background(CameraColors.Accent.copy(alpha = .18f), CircleShape))
                Box(Modifier.size(6.dp).background(CameraColors.Accent, CircleShape))
            }
            PanelEyebrow(when {
                status.waitingForCooling -> "PAUSED FOR HEAT"
                status.running -> "DEVELOPING YOUR PHOTOS"
                thermal.blocksExport -> "PHONE TOO HOT TO EXPORT"
                else -> "READY WHEN YOU ARE"
            }, Modifier.weight(1f))
            if (heat) Text("{waiting} waiting", arguments = mapOf("waiting" to waiting), color = Diagram.Hint, fontSize = 9.5.sp,
                lineHeight = 13.sp, fontFamily = Diagram.Mono)
            else if (status.running) Text("one photo at a time", color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 13.sp,
                fontFamily = Diagram.Mono)
        }
        if (status.running) Text("{completed} / {total} exported", arguments = mapOf("completed" to status.completed, "total" to status.total),
            color = CameraColors.Text, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
        else Text("{waiting} waiting", arguments = mapOf("waiting" to waiting), color = CameraColors.Text, fontSize = 21.sp,
            lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
        if ((!status.waitingForCooling && status.running) || !thermal.blocksExport) Text(when {
            status.waitingForCooling -> if (status.pauseRequested) "Pausing exports." else "Resuming exports automatically."
            status.running -> if (status.pauseRequested) "Finishing the current photo, then pausing." else status.stage.ifEmpty { "Preparing your queue" }
            else -> status.message
        }, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (status.running) PanelProgress(status.completed.toFloat() / status.total.coerceAtLeast(1), Modifier.padding(vertical = 6.dp))
        when {
            // A short window, such as landscape, drops the chart so the heat notice and its buttons stay in view.
            heat -> if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 480)
                HeatPauseChart(status.completed, status.total, status.pauseRequested, Modifier.padding(top = 4.dp))
            status.running -> {
                if (strip) ProcessingBatchStrip(status.total, status.completed, current, thermal.pauseWhenHot, Modifier.padding(top = 4.dp))
                if (current != null) {
                    PanelEyebrow("Current photo", Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val style: @Composable (String, Boolean) -> Unit = { value, translate ->
                            Text(value, translate = translate, color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
                        }
                        style(date(current.capturedAt, "d MMM · HH:mm:ss"), false); style("·", false)
                        style(current.lens, true); style("·", false)
                        style("${current.width} × ${current.height}", false)
                    }
                    ExportPipeline(pipelineSteps(current, status.stage, cleanupOn, priority))
                }
            }
            ready -> ProcessingReadyFlow(waiting, Modifier.padding(top = 8.dp))
        }
        val lastBatch = !status.running && status.total > 0
        if (lastBatch || ready) Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (lastBatch) Text("Last batch · {completed} of {total} exported", arguments = mapOf("completed" to (status.completed),
                    "total" to (status.total)), color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
            }
            if (ready) ProcessingFlowLegend()
        }
    }
    if (thermal.blocksExport) GalleryCard(modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text("Heat limit reached", color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono)
        Text(if (status.running && status.currentId != null)
            "Your phone is too hot for exporting with Pause when hot on. The current photo will finish, then processing pauses."
            else "Your phone is too hot for exporting with Pause when hot on. Your waiting RAWs are kept.",
            color = CameraColors.Text, fontSize = 11.5.sp, lineHeight = 16.sp)
        Text(if (status.pauseRequested) "Automatic resume is stopped. Your queue is kept."
            else if (status.running) "Exporting will resume automatically once your phone cools."
            else "Choose Export when cooler to queue photos. Exporting will start automatically once your phone cools.",
            color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.running) {
            val label = when {
                status.waitingForCooling -> if (status.pauseRequested) "Pausing exports" else "Stop automatic resume"
                status.pauseRequested -> "Pausing after this photo"
                else -> "Pause after this photo"
            }
            GalleryButton(label, Modifier.onGloballyPositioned { onGuideTarget("start", it.boundsInRoot()) },
                style = GalleryButtonStyle.Accent, enabled = !status.pauseRequested,
                number = if (heat && !status.pauseRequested) 3 else if (strip) 1 else null,
                leading = if (heat) null else { color -> GallerySymbolIcon(GallerySymbol.PAUSE, color, Modifier.size(13.dp)) },
                onClick = onPause)
        } else {
            val count = if (selectedCount > 0) selectedCount else waiting
            GalleryButton(when {
                thermal.blocksExport -> "Export when cooler · {value1}"
                selectedCount > 0 -> "Export {size} selected"
                else -> "Start exporting · {waiting}"
            }, Modifier.testTag("Gallery start exporting").onGloballyPositioned { onGuideTarget("start", it.boundsInRoot()) },
                style = GalleryButtonStyle.Accent,
                enabled = !queueBusy && (waiting > 0 || selectedCount > 0), number = if (ready) 1 else null,
                arguments = mapOf("value1" to count, "size" to selectedCount, "waiting" to waiting),
                leading = { color -> GallerySymbolIcon(GallerySymbol.EXPORT, color, Modifier.size(13.dp)) }, onClick = onStart)
        }
        if (waiting > 0 || errors > 0) GalleryButton("Cancel waiting exports", number = if (ready) 2 else null, onClick = onCancelAll)
    }
}

private fun pipelineSteps(shot: GalleryShot, stage: String, cleanupOn: Boolean, priority: ProcessingPriority): List<PipelineStep> {
    val retrying = stage.startsWith("Retrying")
    val processing = stage == "Processing spektrafilm" || stage == "Processing RAW"
    val active = when {
        retrying || stage == "Reading original RAW" -> 0
        processing -> 1
        shot.panorama && stage.isNotEmpty() -> 1
        stage == "Saving JPEG" -> 2
        stage == "Verifying JPEG before RAW cleanup" -> 3
        else -> -1
    }
    fun state(index: Int) = when {
        active < 0 || index > active -> DiagramNodeState.Idle
        index < active -> DiagramNodeState.Done
        else -> DiagramNodeState.Active
    }
    val read = PipelineStep(R.drawable.ic_settings_file_text, "Reading original RAW",
        AppLanguage.format("Original DNG · {value1}", mapOf("value1" to bytes(shot.rawBytes))), state(0), detailTranslate = false,
        noteCaption = "On error".takeIf { retrying }, note = stage.takeIf { retrying })
    if (shot.panorama) return listOf(read,
        PipelineStep(R.drawable.ic_sleek_layers, if (active == 1) stage else "Stitching panorama", null, state(1)),
        PipelineStep(R.drawable.ic_sleek_images, "Exported · in Gallery", "Panorama saved · RAW frames kept"))
    return listOf(read,
        PipelineStep(R.drawable.ic_sleek_aperture, if (processing) stage else if (shot.look == "spektrafilm off" || shot.look == "Original capture")
            "Processing RAW" else "Processing spektrafilm", shot.look, state(1),
            noteCaption = "Processing priority", note = priority.label, noteDetail = "next photo"),
        PipelineStep(R.drawable.ic_settings_save, "Saving JPEG", "DCIM/Unspektrawesome", state(2), detailMono = true, detailTranslate = false),
        PipelineStep(R.drawable.ic_settings_check, "Verifying JPEG before RAW cleanup", "RAW removed if checks pass",
            if (cleanupOn) state(3) else DiagramNodeState.Off, noteCaption = "Delete RAWs after export",
            note = if (cleanupOn) "On" else "Off", noteDetail = "runs only when on", noteActive = cleanupOn),
        PipelineStep(R.drawable.ic_sleek_images, "Exported · in Gallery",
            if (cleanupOn) "JPEG verified · original RAW deleted" else "JPEG saved · original RAW kept"))
}

@Composable
private fun HeatGlyph() {
    Canvas(Modifier.size(9.dp, 13.dp)) {
        val stroke = Stroke(1.4.dp.toPx())
        val stem = 3.6.dp.toPx()
        drawRoundRect(CameraColors.Accent, Offset((size.width - stem) / 2, 0f), Size(stem, size.height * .62f),
            CornerRadius(stem / 2), style = stroke)
        drawCircle(CameraColors.Accent, size.width / 2 - .7.dp.toPx(), Offset(size.width / 2, size.height - size.width / 2), style = stroke)
    }
}

@Composable
private fun QueueThumbnail(shot: GalleryShot, current: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(52.dp).then(if (current) Modifier.border(1.5.dp, CameraColors.Accent, RoundedCornerShape(5.dp)).padding(2.dp) else Modifier)
        .clip(shape).background(CameraColors.MenuCard).border(1.dp, Diagram.Border, shape).clickable(role = Role.Button, onClick = onClick)) {
        PhotoImage(shot.jpegUri ?: shot.rawUri, shot.id.takeIf { shot.jpegUri == null }, Modifier.fillMaxSize(), imageKey = shot.state)
        Text(if (shot.grid) "3×3" else if (shot.panorama) "PANORAMA" else if (shot.jpegUri == null) "RAW PREVIEW" else "JPEG",
            color = CameraColors.Text, fontSize = 6.5.sp, lineHeight = 8.sp, letterSpacing = .4.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xAD000000)).padding(vertical = 2.dp))
    }
}

@Composable
private fun QueueStatusLine(shot: GalleryShot, active: Boolean, stage: String) {
    val color = when {
        active -> CameraColors.Accent
        shot.state == ExportState.EXPORTED -> SoftInk
        shot.state == ExportState.FAILED -> CameraColors.Text
        else -> Diagram.Hint
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when {
            active -> {}
            shot.state == ExportState.EXPORTED ->
                com.unspektrawesome.ui.camera.CameraIcon(R.drawable.ic_sleek_check, Modifier.size(11.dp), CameraColors.Accent)
            shot.state == ExportState.FAILED -> Box(Modifier.size(6.dp).background(CameraColors.FocusFailed, CircleShape))
            else -> Box(Modifier.size(6.dp).border(1.dp, Diagram.Hint, CircleShape))
        }
        if (!active && shot.state == ExportState.WAITING) Text("Waiting · {value1} RAW kept", arguments = mapOf("value1" to bytes(shot.rawBytes)),
            color = color, fontSize = 10.5.sp, lineHeight = 14.sp)
        else Text(if (active) stage else when (shot.state) {
            ExportState.WAITING -> ""
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
        }, color = color, fontSize = 10.5.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun GallerySettings(jobs: List<GalleryShot>, revision: Int, thermal: GalleryThermalState, status: ProcessingStatus, guideStep: Int,
    scroll: LazyListState, onGuideTarget: (String, Rect) -> Unit, onShowGuide: () -> Unit, onStorageAccess: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { CameraPreferencesStore(context) }
    var later by remember(revision) { mutableStateOf(preferences.load().processPhotosLater) }
    val galleryPreferences = remember { context.getSharedPreferences("gallery", 0) }
    val rawCleanup = remember { RawCleanupPreferences(context) }
    var deleteRaws by remember { mutableStateOf(rawCleanup.authorization() != null) }
    var confirmRawDeletion by rememberSaveable { mutableStateOf(false) }
    if (confirmRawDeletion) DiagramDialog("Delete RAWs after export?", { confirmRawDeletion = false }, buttons = {
        DiagramButton("Keep RAWs", onClick = { confirmRawDeletion = false })
        DiagramButton("Enable RAW deletion", accent = true, onClick = {
            if (rawCleanup.setEnabled(true)) {
                deleteRaws = true
                confirmRawDeletion = false
            }
        })
    }) {
        RawDeletionDrawing()
        DiagramBody("This permanently deletes each capture's original RAW after its JPEG export completes and passes verification. If exporting has issues, or rendering looks wrong because of an app bug, the RAW cannot be recovered and you may lose the capture.\n\nChecks catch incomplete, unusually small, nearly black or uniform JPEGs, but cannot detect every rendering problem. Uncertain exports keep their RAW.\n\nApplies to exports started after you enable this option. Existing finished exports are untouched.")
    }
    val pauseHot = thermal.pauseWhenHot
    var priority by remember { mutableStateOf(ProcessingPriority.from(galleryPreferences.getString("priority", null))) }
    var progressCard by remember(revision) {
        mutableStateOf(galleryPreferences.getBoolean("progress_notifications", true) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val space by produceState(0L to 0L, revision, jobs) {
        value = withContext(Dispatchers.IO) { StatFs(context.filesDir.absolutePath).let { it.availableBytes to it.totalBytes } }
    }
    val previewShot = jobs.firstOrNull { it.id == status.currentId } ?: jobs.firstOrNull { it.state == ExportState.WAITING } ?: jobs.firstOrNull()
    val captureTimes = jobs.sortedByDescending { it.capturedAt }.take(4).sortedBy { it.capturedAt }.map { date(it.capturedAt, "HH:mm") }
    LazyColumn(state = scroll, modifier = Modifier.testTag("Gallery settings list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        item {
            GalleryCaptureFlowStage(later, pauseHot, priority, Modifier.padding(top = 4.dp))
            DiagramSection("New camera captures")
            Column(Modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DiagramNumber(1)
                    Text("Choose when to process", color = CameraColors.Text, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                }
                DiagramSegmented(listOf("Process now", "Later in Gallery"), if (later) 1 else 0,
                    Modifier.padding(start = 28.dp).onGloballyPositioned { onGuideTarget("capture-mode", it.boundsInRoot()) }) { index ->
                    later = index == 1; preferences.save(preferences.load().copy(processPhotosLater = later))
                }
                Text("This also changes the camera's processing setting. Waiting photos keep their saved settings.", color = RowDetail,
                    fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 28.dp))
            }
        }
        item {
            DiagramSwitchRow("Pause when hot", pauseHot, number = 2,
                detail = "Pauses at your phone's high heat threshold, after the current photo finishes, and resumes automatically below that limit. Processing shows when exports are waiting for cooling.") {
                galleryPreferences.edit().putBoolean("pause_when_hot", it).apply()
            }
            NumberedSection(3, "Processing priority")
            DiagramRadioGroup(ProcessingPriority.entries.map { it.label }, priority.ordinal,
                Modifier.onGloballyPositioned { onGuideTarget("priority", it.boundsInRoot()) },
                details = ProcessingPriority.entries.map { it.detail }) { index ->
                val choice = ProcessingPriority.entries[index]
                priority = choice; galleryPreferences.edit().putString("priority", choice.name).apply()
            }
            DiagramHint("Quality stays identical. Changes apply to the next photo.")
        }
        item {
            DiagramSection("Notifications")
            GalleryNotificationStage(progressCard, status, previewShot)
            DiagramSwitchRow("Detailed progress card", progressCard, Modifier.onGloballyPositioned { onGuideTarget("notifications", it.boundsInRoot()) },
                number = 4, detail = "Show the current photo, export progress, and pause control. Android requires a service notice while background processing runs.") { enabled ->
                progressCard = enabled; galleryPreferences.edit().putBoolean("progress_notifications", progressCard).apply()
                if (progressCard && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            DiagramButtonRow(Modifier.padding(start = 28.dp)) {
                DiagramButton("Android notification settings", symbol = SettingsSymbol.ExternalLink) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
        }
        item {
            Column(Modifier.onGloballyPositioned { onGuideTarget("originals", it.boundsInRoot()) }) {
                DiagramSection("Originals & storage")
                GalleryStorageStage(space.first, space.second, jobs.count { it.hasRaw }, jobs.sumOf { it.rawBytes }, deleteRaws, onStorageAccess)
                DiagramSwitchRow("Delete RAWs after export", deleteRaws, Modifier.testTag("RAW cleanup setting"), number = 6,
                    detail = if (deleteRaws) "Verified exports can permanently remove their original DNGs. Failed, cancelled, recovered or suspicious exports keep their RAWs. Turn off to keep originals for future exports."
                        else "Original DNGs are kept by default, including after failed or cancelled exports. Share a kept RAW from a processing card or exported photo to use another editor.") { enabled ->
                    if (enabled) confirmRawDeletion = true
                    else if (rawCleanup.setEnabled(false)) deleteRaws = false
                }
            }
        }
        item {
            DiagramSection("Capture, preserved")
            GallerySettingsDisclosure("The same spektrafilm processing", number = 8) {
                GalleryPreservedStage(captureTimes)
                Text("Give your phone a breather", color = CameraColors.Accent, fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
                Text("Exports run one at a time. Returning to the camera pauses after the current photo. You can continue with the screen off.",
                    color = Color(0xFFB0B0B0), fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Text("Exports use the camera's full processing pipeline and each shot's saved settings. Photo dates come from capture time, so your timeline stays in order.",
                    color = Color(0xFFB0B0B0), fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Text("Unspektrawesome {VERSION_NAME}", arguments = mapOf("VERSION_NAME" to (BuildConfig.VERSION_NAME)), color = Diagram.Hint,
                    fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono,
                    modifier = Modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
        item {
            NumberedSection(9, "Gallery guide")
            GalleryGuideStepsStage(galleryGuideSteps.size, guideStep, Modifier.padding(start = 28.dp))
            DiagramHint("Walk through photos, processing and settings again.", Modifier.padding(start = 28.dp))
            DiagramButtonRow(Modifier.padding(start = 28.dp)) { DiagramButton("Show Gallery guide", onClick = onShowGuide) }
        }
    }
}

@Composable
internal fun GalleryAction(label: String, selected: Boolean = false, enabled: Boolean = true, icon: Int? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GalleryButton(label, modifier, if (selected) GalleryButtonStyle.Accent else GalleryButtonStyle.Outline, enabled,
        leading = icon?.let { kind -> { color: Color -> GalleryIcon(kind, color, Modifier.size(14.dp)) } }, onClick = onClick)
}

@Composable internal fun GalleryCard(selected: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Diagram.WindowShape).background(Diagram.Glass)
        .border(1.dp, if (selected) CameraColors.Accent else Diagram.Border, Diagram.WindowShape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable internal fun EmptyGallery(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).dashedOutline(Diagram.Line, 10.dp), contentAlignment = Alignment.Center) {
            GalleryIcon(0, Diagram.Hint, Modifier.size(26.dp))
        }
        Text(title, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(detail, color = RowDetail, fontSize = 11.5.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
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

@Composable internal fun GalleryDialog(title: String, detail: String, action: String, onDismiss: () -> Unit, onAction: () -> Unit,
    dismissLabel: String = "Cancel", drawing: (@Composable ColumnScope.() -> Unit)? = null, extraContent: @Composable () -> Unit = {}) {
    DiagramDialog(title, onDismiss, buttons = {
        if (action != "Close") DiagramButton(dismissLabel, onClick = onDismiss)
        DiagramButton(action, accent = true, onClick = onAction)
    }) {
        DiagramBody(detail)
        drawing?.let { draw -> Column(Modifier.fillMaxWidth().padding(top = 6.dp)) { draw() } }
        extraContent()
    }
}

internal fun date(time: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
internal fun bytes(value: Long): String = if (value >= 1024L * 1024 * 1024) String.format(Locale.getDefault(), "%.1f GB", value / (1024.0 * 1024 * 1024)) else String.format(Locale.getDefault(), "%.1f MB", value / (1024.0 * 1024))
internal fun megapixels(width: Int, height: Int): String = String.format(Locale.getDefault(), "%.1f", width.toLong() * height / 1_000_000.0)
