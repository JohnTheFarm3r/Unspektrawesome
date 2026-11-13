package com.unspektrawesome.gallery

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramButton
import com.unspektrawesome.ui.camera.DiagramHint
import com.unspektrawesome.ui.camera.DiagramMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
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
    var filterOpen by remember { mutableStateOf(false) }
    var groupingOpen by remember { mutableStateOf(false) }
    val filters = listOf("All media", "Camera", "Developed", "BURST", "Photos", "Videos")
    val filterNotes = listOf("Everything, ordered by capture time", "Photos the camera saved, not exported in Processing",
        "JPEGs exported from the Processing queue", "Burst captures, one tile each", "Camera and developed photos, no BURST",
        "Video recordings only")
    // Which of camera photos, developed photos, BURST and videos each filter keeps.
    val filterContents = listOf(listOf(true, true, true, true), listOf(true, false, false, false), listOf(false, true, false, false),
        listOf(false, false, true, false), listOf(true, true, false, false), listOf(false, false, false, true))
    val groupings = listOf("By day", "By month", "All dates")
    val groupingNotes = listOf("Today, Yesterday, then weekday names", "One header per month", "One continuous grid, no headers")
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
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GalleryTitleBadge()
            Text("Gallery", color = CameraColors.Text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            GalleryCameraAction {
                context.startActivity(android.content.Intent(context, com.unspektrawesome.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            Box {
                MediaRoundButton("More", Modifier.testTag("Gallery more"), enabled = !deleting, active = menu, onClick = { menu = true }) { ink ->
                    GallerySymbolIcon(GallerySymbol.MORE, ink, Modifier.size(16.dp))
                }
                DiagramMenu(menu, { menu = false }, minWidth = 220.dp) {
                    MediaMenuItem("Select", choice = false, enabled = selectableUris.isNotEmpty(),
                        detail = "Checkboxes on tiles to delete several. Long-press a tile does the same.") { menu = false; selecting = true }
                    MediaMenuItem("Import BURST", choice = false, enabled = !importing, detail = "Choose a BURST file from storage") {
                        menu = false; requestBurstStorage { importBurst.launch(arrayOf("*/*")) }
                    }
                    MediaMenuItem("Refresh", choice = false, detail = "Reload photos and videos") { menu = false; onRefresh() }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .onGloballyPositioned { onGuideTarget("filters", it.boundsInRoot()) }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaDropdownField("Show", filters[filter], filterOpen, Modifier.weight(1f).widthIn(min = 140.dp).testTag("Gallery filter"),
                enabled = !deleting, menuWidth = 300.dp, onExpand = { filterOpen = true }, onDismiss = { filterOpen = false }) {
                filters.forEachIndexed { index, option ->
                    MediaMenuItem(option, selected = filter == index, detail = filterNotes[index],
                        trailing = { FilterTokens(filterContents[index]) }) {
                        filterOpen = false
                        if (filter != index) { filter = index; selectedUris = emptyList() }
                    }
                }
                Text("Every filter keeps the same order: newest capture first, grouped by the Group setting.",
                    color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            MediaDropdownField("Group", groupings[grouping], groupingOpen,
                Modifier.weight(1f).widthIn(min = 140.dp).testTag("Gallery grouping"), enabled = !deleting,
                onExpand = { groupingOpen = true }, onDismiss = { groupingOpen = false }) {
                groupings.forEachIndexed { index, option ->
                    MediaMenuItem(option, selected = grouping == index, detail = groupingNotes[index],
                        trailing = { GroupingGlyph(index, grouping == index) }) {
                        groupingOpen = false; grouping = index
                    }
                }
            }
        }
        if (importing) LinearProgressIndicator(color = CameraColors.Accent, trackColor = Diagram.Hairline,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp))
        if (importError) DiagramHint("Unable to open BURST", Modifier.padding(horizontal = 12.dp), warning = true)
        if (!loading && error == null && selecting) {
            val allSelected = selectableUris.isNotEmpty() && selectedUris.size == selectableUris.size
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(Diagram.ControlShape)
                .background(Diagram.Glass).border(1.dp, Diagram.Border, Diagram.ControlShape)) {
                FlowRow(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("{size} selected", arguments = mapOf("size" to (selectedUris.size)), color = CameraColors.Accent, fontSize = 13.sp,
                        lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    DiagramButton(if (allSelected) "Select none" else "Select all", enabled = !deleting && selectableUris.isNotEmpty()) {
                        selectedUris = if (allSelected) emptyList() else selectableUris
                    }
                    DiagramButton(if (deleting) "Deleting" else "Delete ${selectedUris.size}", accent = true,
                        enabled = !deleting && selectedUris.isNotEmpty(), modifier = Modifier.testTag("Gallery delete selected")) {
                        confirmation = selectedUris.toList()
                    }
                    DiagramButton("Done", enabled = !deleting, onClick = ::finishSelection)
                }
                if (deleting) LinearProgressIndicator(color = CameraColors.Accent, trackColor = Color(0xFF1F1F1F),
                    modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            SelectionKey(Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        } else if (deleting) LinearProgressIndicator(color = CameraColors.Accent, trackColor = Diagram.Hairline,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp))
        val queueNotice: @Composable () -> Unit = {
            if (waiting > 0 && !selecting) QueueNoticeFlow(waiting, onClick = onQueue)
        }
        if (error != null || loading || filtered.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                photoAccess()
                queueNotice()
                when {
                    error != null -> EmptyGallery("Unable to load gallery", error, "Try again", onRefresh)
                    loading -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(Diagram.ControlShape).background(Diagram.Glass)
                        .border(1.dp, Diagram.Border, Diagram.ControlShape).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaSpinner(track = Diagram.Border)
                        Text("Loading saved photos and videos.", color = MediaArt.SoftInk, fontSize = 12.5.sp, lineHeight = 17.sp,
                            modifier = Modifier.weight(1f))
                    }
                    else -> {
                        EmptyGallery("Your gallery", "Photos and videos appear together, ordered by the moment you captured them.")
                        EmptyTimelineArt()
                    }
                }
            }
        } else {
            val groups = remember(filtered, grouping) { filtered.groupBy {
                when (grouping) { 0 -> date(it.capturedAt, "yyyy-MM-dd"); 1 -> date(it.capturedAt, "yyyy-MM"); else -> "" }
            } }
            val selected = selectedUris.toSet()
            LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f).testTag("Gallery photos"),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "photo_access", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
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
                        FlowRow(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 10.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(relative ?: date(capturedAt, if (grouping == 1) "MMMM yyyy" else "EEEE"), translate = relative != null,
                                color = CameraColors.Text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(date(capturedAt, if (grouping == 1) "yyyy" else "d MMM"), translate = false, color = Diagram.Hint,
                                    fontSize = 11.sp, fontFamily = Diagram.Mono)
                                Text("·", translate = false, color = Diagram.Hint, fontSize = 11.sp, fontFamily = Diagram.Mono)
                                Text(if (items.size == 1) "1 item" else "{size} items", arguments = mapOf("size" to items.size),
                                    color = Diagram.Hint, fontSize = 11.sp, fontFamily = Diagram.Mono)
                            }
                        }
                    }
                    items(items, key = { it.uri.toString() }, span = { GridItemSpan(if (it.isBurst) maxLineSpan else 1) }) { photo ->
                        val checked = photo.uri.toString() in selected
                        val busy = photo.shotId in busyIds
                        val shape = MediaArt.TileShape
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
                            PhotoImage(photo.uri, null, Modifier.fillMaxSize().then(if (selecting && busy)
                                Modifier.drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = .55f)) } else Modifier),
                                imageKey = photo.generation, isVideo = photo.isVideo)
                            if (photo.isVideo) VideoDurationBadge(photo.durationMs, Modifier.align(Alignment.BottomStart).padding(7.dp))
                            if (photo.developed && !photo.isBurst) SpektrafilmBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                            if (photo.isBurst) {
                                if (selecting) Row(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BurstStackBadge(size = 24.dp)
                                    BurstBadge()
                                } else {
                                    BurstBadge(Modifier.align(Alignment.BottomStart).padding(7.dp))
                                    BurstStackBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
                                }
                            }
                            when {
                                checked -> Box(Modifier.matchParentSize().border(2.dp, CameraColors.Accent, shape))
                                selecting && busy -> Box(Modifier.matchParentSize().drawBehind {
                                    drawRoundRect(Diagram.Line, cornerRadius = CornerRadius(3.dp.toPx()),
                                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                                })
                                selecting -> Box(Modifier.matchParentSize().border(1.dp, Color(0xFF333333), shape))
                            }
                            if (selecting && busy) MediaSpinner(Modifier.align(Alignment.Center))
                            else if (selecting) PhotoSelectionIndicator(checked, false, Modifier.align(Alignment.TopEnd).padding(6.dp))
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

/** Tile checkbox: white outline when free, lime with a check when chosen, dimmed while the item is busy. */
@Composable
internal fun PhotoSelectionIndicator(checked: Boolean, busy: Boolean, modifier: Modifier, round: Boolean = false, size: Dp = 18.dp) {
    MediaCheckbox(checked, modifier, round = round, size = size, dimmed = busy)
}

/** What the tile marks mean while selecting. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionKey(modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MediaCheckbox(true, size = 13.dp)
            Text("Selected", color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MediaCheckbox(false, size = 13.dp)
            Text("Tap to add", color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MediaSpinner(size = 12.dp)
            Text("Exporting, not selectable", color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
    }
}
