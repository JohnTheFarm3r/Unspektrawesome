package com.unspektrawesome.gallery

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.ui.camera.CameraColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PhotoLibrary(
    photos: List<GalleryPhoto>, jobs: List<GalleryShot>, processingId: String?,
    loading: Boolean, error: String?, waiting: Int, onQueue: () -> Unit,
    onRefresh: () -> Unit, onPhoto: (Uri) -> Unit, onGuideTarget: (String, Rect) -> Unit,
    onDeleted: (Set<Uri>) -> Unit,
    photoAccess: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val requestBurstStorage = com.unspektrawesome.burst.rememberBurstStorageAccess()
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    val importBurst = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) scope.launch {
            importing = true; importError = false
            try {
                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.unspektrawesome.burst.BurstMedia.import(context, source)
                }
                onRefresh()
                context.startActivity(PhotoViewerActivity.intent(context, uri))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { importError = true }
            finally { importing = false }
        }
    }
    val deletePhotos = rememberPhotoDeletion()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var grouping by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val filters = listOf("All media", "Camera", "Developed", "BURST", "Photos", "Videos")
    val groupings = listOf("By day", "By month", "All dates")
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmation by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deletionError by remember { mutableStateOf<String?>(null) }
    val filtered = remember(photos, filter) {
        photos.filter { when (filter) {
            1 -> !it.developed && !it.isBurst && !it.isVideo
            2 -> it.developed
            3 -> it.isBurst
            4 -> !it.isVideo && !it.isBurst
            5 -> it.isVideo
            else -> true
        } }
    }
    val busyIds = remember(jobs, processingId) {
        jobs.filter { it.state == ExportState.PROCESSING }.map { it.id }.toSet() + listOfNotNull(processingId)
    }
    val selectableUris = remember(filtered, busyIds) {
        filtered.filter { it.shotId !in busyIds }.map { it.uri.toString() }
    }
    LaunchedEffect(selectableUris, loading) {
        if (!loading) {
            val available = selectableUris.toSet()
            selectedUris = selectedUris.filter { it in available }
            confirmation = confirmation?.filter { it in available }?.takeIf { it.isNotEmpty() }
        }
    }
    fun finishSelection() { selecting = false; selectedUris = emptyList() }
    fun toggle(photo: GalleryPhoto) {
        val key = photo.uri.toString()
        selectedUris = if (key in selectedUris) selectedUris - key else selectedUris + key
    }
    BackHandler(selecting) { if (!deleting) finishSelection() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GalleryTitleBadge()
            Text("Gallery", color = CameraColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            GalleryCameraAction {
                context.startActivity(android.content.Intent(context, com.unspektrawesome.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            Box {
                GalleryIconButton("More", Modifier.padding(start = 8.dp).border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(50)).size(36.dp).testTag("Gallery more"), enabled = !deleting, onClick = { menu = true }) {
                    GallerySymbolIcon(GallerySymbol.MORE, CameraColors.Text, Modifier.size(18.dp))
                }
                GalleryMenu(menu, { menu = false }) {
                    GalleryMenuItem("Select", enabled = selectableUris.isNotEmpty()) { menu = false; selecting = true }
                    GalleryMenuItem("Import BURST", enabled = !importing) { menu = false; requestBurstStorage { importBurst.launch(arrayOf("*/*")) } }
                    GalleryMenuItem("Refresh") { menu = false; onRefresh() }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .onGloballyPositioned { onGuideTarget("filters", it.boundsInRoot()) }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GalleryDropdown(filters[filter], filters, filter, Modifier.weight(1f).testTag("Gallery filter"), enabled = !deleting) { index ->
                if (filter != index) { filter = index; selectedUris = emptyList() }
            }
            GalleryDropdown(groupings[grouping], groupings, grouping, Modifier.weight(1f)
                .testTag("Gallery grouping"), enabled = !deleting) { grouping = it }
        }
        if (importing) LinearProgressIndicator(color = CameraColors.Accent, trackColor = CameraColors.MenuCard, modifier = Modifier.fillMaxWidth())
        if (importError) Text("Unable to open BURST", color = CameraColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
        if (!loading && error == null && selecting) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("{size} selected", arguments = mapOf("size" to (selectedUris.size)), color = CameraColors.Accent, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
                val allSelected = selectableUris.isNotEmpty() && selectedUris.size == selectableUris.size
                GalleryAction(if (allSelected) "Select none" else "Select all", enabled = !deleting && selectableUris.isNotEmpty()) {
                    selectedUris = if (allSelected) emptyList() else selectableUris
                }
                GalleryAction(if (deleting) "Deleting" else "Delete ${selectedUris.size}", selected = true,
                    enabled = !deleting && selectedUris.isNotEmpty(), modifier = Modifier.testTag("Gallery delete selected")) {
                    confirmation = selectedUris.toList()
                }
                GalleryAction("Done", enabled = !deleting, onClick = ::finishSelection)
            }
        }
        if (deleting) LinearProgressIndicator(color = CameraColors.Accent, trackColor = CameraColors.MenuCard,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        val queueNotice: @Composable () -> Unit = {
            if (waiting > 0 && !selecting) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(12.dp))
                .background(CameraColors.MenuCard).clickable(onClick = onQueue).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GalleryIcon(1, CameraColors.Accent, Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("{waiting} RAW {value2} ready to develop", arguments = mapOf("waiting" to (waiting), "value2" to (if (waiting == 1) "photo" else "photos")), color = CameraColors.Text, fontSize = 12.sp)
                    Text("Captured settings saved with every shot", color = CameraColors.Muted, fontSize = 10.sp)
                }
                Text("›", color = CameraColors.Accent, fontSize = 24.sp)
            }
        }
        if (error != null || loading || filtered.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(horizontal = 20.dp)) { photoAccess() }
                Box(Modifier.padding(horizontal = 20.dp)) { queueNotice() }
                when {
                    error != null -> EmptyGallery("Unable to load gallery", error, "Try again", onRefresh)
                    loading -> Column(Modifier.padding(24.dp)) {
                        Text("Loading saved photos and videos.", color = CameraColors.Muted)
                        LinearProgressIndicator(color = CameraColors.Accent, trackColor = CameraColors.MenuCard,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    else -> EmptyGallery("Your gallery", "Photos and videos appear together, ordered by the moment you captured them.")
                }
            }
        } else {
            val groups = remember(filtered, grouping) { filtered.groupBy {
                when (grouping) { 0 -> date(it.capturedAt, "yyyy-MM-dd"); 1 -> date(it.capturedAt, "yyyy-MM"); else -> "" }
            } }
            val selected = selectedUris.toSet()
            LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f).testTag("Gallery photos"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "photo_access", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 10.dp)) {
                        photoAccess()
                        queueNotice()
                    }
                }
                groups.forEach { (day, items) ->
                    if (grouping != 2) item(key = day, span = { GridItemSpan(maxLineSpan) }) {
                        val capturedAt = items.first().capturedAt
                        val today = java.time.LocalDate.now()
                        val capturedDay = java.time.Instant.ofEpochMilli(capturedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        val relative = when { grouping != 0 -> null; capturedDay == today -> "Today"; capturedDay == today.minusDays(1) -> "Yesterday"; else -> null }
                        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(relative ?: date(capturedAt, if (grouping == 1) "MMMM yyyy" else "EEEE"), translate = relative != null,
                                color = CameraColors.Text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(date(capturedAt, if (grouping == 1) "yyyy" else "d MMM"), translate = false, color = CameraColors.Muted, fontSize = 11.sp)
                                Text("·", color = CameraColors.Muted, fontSize = 11.sp)
                                Text(if (items.size == 1) "1 item" else "{size} items", arguments = mapOf("size" to items.size), color = CameraColors.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    items(items, key = { it.uri.toString() }, span = { GridItemSpan(if (it.isBurst) maxLineSpan else 1) }) { photo ->
                        val checked = photo.uri.toString() in selected
                        val busy = photo.shotId in busyIds
                        val shape = RoundedCornerShape(9.dp)
                        Box(Modifier.aspectRatio(if (photo.isBurst) 2.65f else 4f / 3f).clip(shape).background(CameraColors.MenuCard)
                            .combinedClickable(enabled = !deleting && (!selecting || !busy),
                                role = if (selecting) Role.Checkbox else Role.Button,
                                onLongClickLabel = AppLanguage.text(if (photo.isVideo) "Select video" else "Select photo"), onLongClick = {
                                    if (!busy) { selecting = true; if (!checked) toggle(photo) }
                                }, onClick = { if (selecting) toggle(photo) else onPhoto(photo.uri) })
                            .testTag("Gallery photo ${photo.uri}").languageSemantics { language ->
                                contentDescription = photo.name
                                if (selecting) {
                                    this.selected = checked
                                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                                    if (busy) stateDescription = language.text("Exporting")
                                }
                            }) {
                            PhotoImage(photo.uri, null, Modifier.fillMaxSize(), imageKey = photo.generation, isVideo = photo.isVideo)
                            if (photo.isVideo) Row(Modifier.align(Alignment.BottomStart).padding(8.dp)
                                .background(CameraColors.Glass, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                GalleryIcon(4, CameraColors.Text, Modifier.size(12.dp))
                                Text(videoDuration(photo.durationMs), translate = false, color = CameraColors.Text, fontSize = 12.sp)
                            }
                            if (photo.developed || photo.isBurst) Text(if (photo.isBurst) "BURST" else "spektrafilm", color = if (photo.isBurst) CameraColors.Text else CameraColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.align(if (photo.isBurst) Alignment.BottomStart else Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(50))
                                    .background(CameraColors.Glass).padding(horizontal = 6.dp, vertical = 2.dp))
                            if (photo.isBurst && !selecting) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(CameraColors.Glass, RoundedCornerShape(6.dp)).padding(6.dp)) {
                                GallerySymbolIcon(GallerySymbol.STACK, CameraColors.Text, Modifier.size(20.dp))
                            }
                            if (checked) Box(Modifier.matchParentSize().border(2.dp, CameraColors.Accent, shape))
                            if (selecting) PhotoSelectionIndicator(checked, busy, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { keys ->
        val targets = photos.filter { it.uri.toString() in keys }
        val count = targets.size
        DeletePhotosDialog(count, targets.any { photo -> jobs.any { it.id == photo.shotId && it.hasRaw && !it.panorama } },
            { confirmation = null }, containsVideo = targets.any { it.isVideo }) { deleteOriginals ->
                confirmation = null
                deleting = true
                scope.launch {
                    try {
                        val result = deletePhotos(targets, deleteOriginals)
                        selectedUris = (result.failed.keys - result.deleted).map(Uri::toString)
                        selecting = selectedUris.isNotEmpty()
                        onDeleted(result.deleted)
                        if (result.failed.isNotEmpty()) deletionError = result.failed.values.first()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        deletionError = failure.message ?: "Unable to delete photos."
                        onRefresh()
                    } finally { deleting = false }
                }
            }
    }
    deletionError?.let { message ->
        GalleryDialog("Deletion incomplete", message, "Close", { deletionError = null }, { deletionError = null })
    }
}

@Composable
internal fun PhotoSelectionIndicator(checked: Boolean, busy: Boolean, modifier: Modifier) {
    val color = if (checked) CameraColors.Accent else CameraColors.Muted.copy(alpha = if (busy) .4f else 1f)
    val shape = RoundedCornerShape(6.dp)
    Canvas(modifier.size(24.dp).background(CameraColors.Background, shape)
        .background(if (checked) CameraColors.MenuSelectedCard else CameraColors.MenuCard, shape)
        .border(1.dp, color, shape).padding(5.dp)) {
        if (checked) {
            drawLine(color, Offset(0f, size.height * .5f), Offset(size.width * .35f, size.height * .85f),
                2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(size.width * .35f, size.height * .85f), Offset(size.width, size.height * .15f),
                2.dp.toPx(), StrokeCap.Round)
        }
    }
}
