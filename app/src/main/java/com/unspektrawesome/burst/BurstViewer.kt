package com.unspektrawesome.burst

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.gallery.GallerySymbol
import com.unspektrawesome.gallery.GallerySymbolIcon
import com.unspektrawesome.gallery.PhotoSelectionIndicator
import com.unspektrawesome.gallery.ZoomableBitmap
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraTypography
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BurstViewer(uri: Uri, onDismiss: () -> Unit, onExported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val executor = remember(uri) { Executors.newSingleThreadExecutor { Thread(it, "burst-viewer") } }
    val dispatcher = remember(executor) { executor.asCoroutineDispatcher() }
    var session by remember(uri) { mutableStateOf<BurstViewSession?>(null) }
    val ownedSession = remember(uri) { java.util.concurrent.atomic.AtomicReference<BurstViewSession?>() }
    var selected by rememberSaveable(uri.toString()) { mutableIntStateOf(0) }
    var prepared by remember(uri) { mutableIntStateOf(0) }
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var displayed by remember(uri) { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var selecting by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var selectedFrames by rememberSaveable(uri.toString()) { mutableStateOf(emptyList<Int>()) }
    var queueIds by rememberSaveable(uri.toString()) { mutableStateOf(emptyMap<Int, String>()) }
    var queued by remember { mutableIntStateOf(0) }
    var queueTotal by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var nativeRequested by remember(uri) { mutableStateOf(false) }
    var nativeBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var nativeFrame by remember(uri) { mutableIntStateOf(-1) }
    var nativeLoading by remember(uri) { mutableStateOf(false) }
    val noticeText = notice?.let { AppLanguage.text(it) }
    DisposableEffect(noticeText) {
        val toast = noticeText?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).apply { show() } }
        onDispose { toast?.cancel() }
    }
    fun selectFrame(frame: Int) {
        playing = false
        if (frame == selected) return
        nativeRequested = false; nativeBitmap = null; nativeFrame = -1; selected = frame
    }
    fun finishSelection() { selecting = false; selectedFrames = emptyList() }
    fun goBack() { if (selecting) finishSelection() else onDismiss() }
    DisposableEffect(uri) {
        onDispose {
            executor.execute { ownedSession.getAndSet(null)?.close() }
            executor.shutdown()
        }
    }
    LaunchedEffect(uri) {
        try {
            session = withContext(dispatcher) { BurstViewSession(context, uri).also { ownedSession.set(it) } }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { notice = "Unable to open BURST"; failed = true }
    }
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        selected = selected.coerceIn(0, current.reader.count - 1)
        try {
            repeat(current.reader.count) { frame ->
                withContext(dispatcher) { current.prepare(frame) }
                prepared = frame + 1
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { notice = "Unable to preview burst" }
    }
    LaunchedEffect(session, selected) {
        val current = session ?: return@LaunchedEffect
        val frame = selected
        try {
            bitmap = withContext(dispatcher) { current.preview(frame) }
            displayed = frame
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { bitmap = null; notice = "Unable to preview burst" }
    }
    LaunchedEffect(session, selected, nativeRequested) {
        nativeBitmap = null; nativeFrame = -1
        val current = session
        if (!nativeRequested || current == null) { nativeLoading = false; return@LaunchedEffect }
        val frame = selected
        var rendered: Bitmap? = null
        var delivered = false
        nativeLoading = true
        try {
            nativeBitmap = withContext(dispatcher) {
                current.rendering.render(current.reader.capture(frame), current.reader.pixels(frame, false), false).also { rendered = it }
            }
            nativeFrame = frame
            delivered = true
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: OutOfMemoryError) { notice = "Unable to preview burst"; nativeRequested = false }
        catch (_: Exception) { notice = "Unable to preview burst"; nativeRequested = false }
        finally { if (!delivered) rendered?.recycle(); nativeLoading = false }
    }
    LaunchedEffect(playing, session) {
        val current = session ?: return@LaunchedEffect
        while (playing) {
            val next = (selected + 1) % current.reader.count
            val timestamps = current.reader.frames
            val interval = if (next == 0) 33L else ((timestamps.getJSONObject(next).getLong("timestamp") -
                timestamps.getJSONObject(selected).getLong("timestamp")) / 1_000_000).coerceIn(16, 1000)
            delay(interval)
            nativeRequested = false; selected = next
        }
    }
    BackHandler { if (!exporting) goBack() }
    BoxWithConstraints(Modifier.fillMaxSize().background(CameraColors.Background).testTag("Burst viewer")) {
        val controlsHeight = (maxHeight * .45f).coerceAtMost(250.dp)
        val count = session?.reader?.count ?: 0
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val native = nativeBitmap?.takeIf { nativeRequested && nativeFrame == selected && !it.isRecycled }
            if (native != null) ZoomableBitmap(native)
            else bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().languageSemantics { language ->
                contentDescription = language.text("BURST preview")
            }, contentScale = ContentScale.Fit) }
                ?: Text(if (failed) "Unable to open BURST" else "Loading preview", color = CameraColors.Muted)
        }
        BoxWithConstraints(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(min = 56.dp)
            .background(CameraColors.Background.copy(alpha = .6f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val backWidth = with(density) { measurer.measure(AppLanguage.text("Back"),
                LocalTextStyle.current.copy(fontSize = 15.sp)).size.width.toDp() } + 40.dp
            val back: @Composable () -> Unit = {
                Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable(enabled = !exporting,
                    role = Role.Button, onClick = ::goBack).languageSemantics { language -> contentDescription = language.text("Back") }
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GallerySymbolIcon(GallerySymbol.BACK, CameraColors.Text, Modifier.size(20.dp))
                    Text("Back", color = CameraColors.Text, fontSize = 15.sp)
                }
            }
            val heading: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("BURST", color = CameraColors.Muted, fontFamily = CameraTypography.Readout, fontSize = 11.sp)
                        if (count > 0) Text("{value1} / {size}", arguments = mapOf("value1" to selected + 1, "size" to count),
                            color = CameraColors.Text, fontFamily = CameraTypography.Readout, fontSize = 11.sp,
                            modifier = Modifier.testTag("Burst frame position"))
                    }
                    session?.let { current ->
                        Text(com.unspektrawesome.gallery.date(current.reader.index.getLong("capturedAt"), "d MMM yyyy · HH:mm"),
                            translate = false, color = CameraColors.Text, fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            if (maxWidth < backWidth * 2 + 120.dp * density.fontScale) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.align(Alignment.Start)) { back() }
                    heading()
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(backWidth)) { back() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { heading() }
                Spacer(Modifier.width(backWidth))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = controlsHeight)
            .background(CameraColors.Background.copy(alpha = .6f)).verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (nativeLoading || count > 0 && prepared < count) {
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().border(1.dp, CameraColors.MenuBorder, shape)
                    .background(CameraColors.Background.copy(alpha = .7f), shape).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = CameraColors.Accent, strokeWidth = 1.dp)
                    Text(if (nativeLoading) "Loading preview" else "Preparing previews · {count} / {total}",
                        arguments = mapOf("count" to prepared, "total" to count), color = CameraColors.Text,
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (!nativeLoading) androidx.compose.material3.LinearProgressIndicator(progress = { prepared.toFloat() / count },
                        color = CameraColors.Accent, trackColor = CameraColors.MenuBorder, modifier = Modifier.width(64.dp).height(1.dp))
                }
            }
            session?.let { current ->
                BurstFilmstrip(current, dispatcher, prepared, selected, selecting, selectedFrames, !exporting, { frame ->
                    selectFrame(frame)
                    if (selecting) selectedFrames = if (frame in selectedFrames) selectedFrames - frame else selectedFrames + frame
                }) { playing = false }
            }
            if (selecting) Text(if (exporting) "Adding to queue · {count} / {total}" else "{size} selected",
                arguments = mapOf("count" to queued, "total" to queueTotal, "size" to selectedFrames.size),
                color = CameraColors.Accent, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("Burst selection count"))
            val labels = listOf("Full resolution", "Exporting", "Export JPEG", "Select frames", "Select all", "Select none", "Done", "Add to export queue")
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val actionWidth = with(density) { labels.maxOf {
                measurer.measure(AnnotatedString(AppLanguage.text(it)), LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 19.sp)).size.width
            }.toDp() + 52.dp }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val columns = when {
                    density.fontScale <= 1.2f && maxWidth >= 300.dp -> 2
                    maxWidth >= actionWidth * 2 + 8.dp -> 2
                    else -> 1
                }
                val cellWidth = with(density) { ((maxWidth.roundToPx() - 4.dp.roundToPx() * (columns - 1)) / columns).toDp() }
                FlowRow(Modifier.fillMaxWidth().testTag("Burst frame actions"), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!selecting) BurstAction(if (playing) "Pause" else "Play", if (playing) GallerySymbol.PAUSE else GallerySymbol.PLAY,
                        enabled = count > 0 && prepared == count && !exporting, modifier = Modifier.width(cellWidth).testTag("Burst playback")) {
                        nativeRequested = false; playing = !playing
                    }
                    BurstAction(if (selecting) "Done" else "Select frames", GallerySymbol.STACK, active = selecting,
                        enabled = session != null && !exporting, modifier = Modifier.width(cellWidth).testTag("Burst select frames")) {
                        playing = false
                        if (selecting) finishSelection() else { selecting = true; selectedFrames = listOf(selected) }
                    }
                    if (selecting) BurstAction(if (selectedFrames.size == count) "Select none" else "Select all", GallerySymbol.STACK,
                        enabled = !exporting, modifier = Modifier.width(cellWidth).testTag("Burst select all")) {
                        selectedFrames = if (selectedFrames.size == count) emptyList() else (0 until count).toList()
                    }
                    BurstAction("Full resolution", GallerySymbol.EXPAND, active = nativeRequested,
                        resolution = nativeBitmap?.takeIf { nativeRequested && nativeFrame == selected && !it.isRecycled },
                        enabled = session != null && !exporting, modifier = Modifier.width(cellWidth).testTag("Burst full resolution")) {
                        playing = false; nativeBitmap = null; nativeFrame = -1; nativeRequested = !nativeRequested; notice = null
                    }
                    if (selecting) BurstAction("Add to export queue", GallerySymbol.EXPORT,
                        enabled = session != null && selectedFrames.isNotEmpty() && !exporting,
                        modifier = Modifier.width(cellWidth).testTag("Queue burst frames")) {
                        val current = session ?: return@BurstAction
                        val frames = selectedFrames.sorted()
                        playing = false; exporting = true; notice = null; queued = 0; queueTotal = frames.size
                        scope.launch {
                            try {
                                for (frame in frames) {
                                    val id = queueIds[frame] ?: "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}".also {
                                        queueIds = queueIds + (frame to it)
                                    }
                                    withContext(dispatcher) { BurstExportQueue.add(context, current.reader, frame, id) }
                                    queueIds = queueIds - frame
                                    selectedFrames = selectedFrames - frame
                                    queued++
                                    onExported()
                                }
                                finishSelection()
                                notice = "Frames added to export queue. Open Processing to export them."
                            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                            catch (_: OutOfMemoryError) { notice = "Unable to add BURST frames to the export queue. Try again." }
                            catch (error: Exception) {
                                com.unspektrawesome.diagnostics.InternalLogRecorder.error("Burst", "queue_failed", error)
                                notice = "Unable to add BURST frames to the export queue. Try again."
                            }
                            finally { exporting = false }
                        }
                    }
                    else BurstAction(if (exporting) "Exporting" else "Export JPEG", GallerySymbol.EXPORT,
                        enabled = session != null && bitmap != null && displayed == selected && !exporting,
                        modifier = Modifier.width(cellWidth).testTag("Export burst frame")) {
                        val current = session ?: return@BurstAction
                        val frame = selected
                        playing = false; exporting = true; notice = null
                        scope.launch {
                            try {
                                withContext(dispatcher) { current.rendering.export(current.reader, frame) }
                                notice = "JPEG saved"
                                onExported()
                            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                            catch (_: Exception) { notice = "Unable to export BURST frame" }
                            finally { exporting = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BurstAction(label: String, icon: GallerySymbol, modifier: Modifier, active: Boolean = false,
    enabled: Boolean = true, resolution: Bitmap? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val color = if (active) CameraColors.Accent else CameraColors.Text
    Column(modifier.heightIn(min = 48.dp).clip(shape).background(CameraColors.Background.copy(alpha = .7f))
        .border(1.dp, if (active) CameraColors.Accent else CameraColors.MenuBorder, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language ->
            contentDescription = language.text(label)
            selected = active
            resolution?.let { stateDescription = language.format("{width} × {height}", mapOf("width" to it.width, "height" to it.height)) }
        }
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            GallerySymbolIcon(icon, if (enabled) color else CameraColors.Muted.copy(alpha = .5f), Modifier.size(18.dp))
            Text(label, color = if (enabled) color else CameraColors.Muted.copy(alpha = .5f), fontSize = 14.sp, lineHeight = 16.sp,
                modifier = Modifier.weight(1f).fillMaxWidth())
        }
        resolution?.let {
            Text("{width} × {height}", arguments = mapOf("width" to it.width, "height" to it.height), color = CameraColors.Muted,
                fontFamily = CameraTypography.Readout, fontSize = 11.sp, modifier = Modifier.testTag("Burst preview resolution"))
        }
    }
}

@Composable
private fun BurstFilmstrip(session: BurstViewSession, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    prepared: Int, selected: Int, selecting: Boolean, selectedFrames: List<Int>, enabled: Boolean,
    onSelect: (Int) -> Unit, onDrag: () -> Unit) {
    val scroll = rememberLazyListState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) onDrag() }
    LaunchedEffect(selected) {
        val visible = scroll.layoutInfo.visibleItemsInfo.any { it.index == selected && it.offset >= scroll.layoutInfo.viewportStartOffset &&
            it.offset + it.size <= scroll.layoutInfo.viewportEndOffset }
        if (!visible && !scroll.isScrollInProgress) scroll.scrollToItem((selected - 2).coerceAtLeast(0))
    }
    LazyRow(state = scroll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().testTag("Burst filmstrip")
            .languageSemantics { language -> contentDescription = language.text("Choose BURST frame") }) {
        items(session.reader.count, key = { it }) { frame ->
            val thumbnail by produceState<Bitmap?>(null, session, frame, frame < prepared) {
                if (frame < prepared) value = withContext(dispatcher) { session.thumbnail(frame) }
            }
            val shape = RoundedCornerShape(8.dp)
            Box(Modifier.size(44.dp).clip(shape).background(CameraColors.MenuCard)
                .clickable(enabled = enabled, role = if (selecting) Role.Checkbox else Role.Button) { onSelect(frame) }.testTag("Burst thumbnail $frame")
                .languageSemantics { language ->
                    contentDescription = language.format("{value1} / {size}", mapOf("value1" to frame + 1, "size" to session.reader.count))
                    this.selected = if (selecting) frame in selectedFrames else frame == selected
                    if (selecting) toggleableState = if (frame in selectedFrames) androidx.compose.ui.state.ToggleableState.On
                        else androidx.compose.ui.state.ToggleableState.Off
                }, contentAlignment = Alignment.Center) {
                thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: GallerySymbolIcon(GallerySymbol.STACK, CameraColors.Muted, Modifier.size(20.dp))
                Box(Modifier.matchParentSize().border(1.dp, if (if (selecting) frame in selectedFrames else frame == selected)
                    CameraColors.Accent else CameraColors.MenuBorder, shape))
                if (selecting) PhotoSelectionIndicator(frame in selectedFrames, !enabled, Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
    }
}

private class BurstViewSession(context: Context, uri: Uri) : AutoCloseable {
    val reader = BurstContainer.Reader(context, uri)
    val rendering = BurstRendering(context)
    private val previews = arrayOfNulls<ByteArray>(reader.count)

    fun prepare(frame: Int) {
        if (previews[frame] != null) return
        val bitmap = rendering.render(reader.capture(frame), reader.pixels(frame, true), true)
        try {
            previews[frame] = ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            }.toByteArray()
        } finally { bitmap.recycle() }
    }

    fun preview(frame: Int): Bitmap {
        prepare(frame)
        val bytes = requireNotNull(previews[frame])
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    fun thumbnail(frame: Int): Bitmap {
        val bytes = requireNotNull(previews[frame])
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = 4 }))
    }

    override fun close() { try { rendering.close() } finally { reader.close() } }
}
