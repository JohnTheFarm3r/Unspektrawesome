package com.unspektrawesome.gallery

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.ui.camera.CameraColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoViewer(photos: List<GalleryPhoto>, initialUri: String, jobs: List<GalleryShot>, onDismiss: () -> Unit,
    onError: (String) -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deletePhotos = rememberPhotoDeletion()
    val processing by GalleryProcessing.state.collectAsState()
    // Pager callbacks can outlive the layout that captured the photo list.
    val currentPhotos by rememberUpdatedState(photos)
    var selectedMediaUri by rememberSaveable(initialUri) { mutableStateOf(initialUri) }
    val pager = remember(initialUri) {
        object : PagerState(photos.indexOfFirst { it.uri.toString() == selectedMediaUri }.coerceAtLeast(0)) {
            override val pageCount: Int get() = currentPhotos.size
        }
    }
    val photoKey: (Int) -> Any = remember {
        { index -> currentPhotos.getOrNull(index)?.uri?.toString() ?: index }
    }
    val photo = currentPhotos.getOrNull(pager.currentPage)
    var inlineVideo by rememberSaveable(initialUri) { mutableStateOf(photos.firstOrNull { it.uri.toString() == initialUri }?.isVideo == true) }
    var nestedUri by rememberSaveable(initialUri) { mutableStateOf<String?>(null) }
    var lastPageUri by rememberSaveable(initialUri) { mutableStateOf<String?>(null) }
    val settled = currentPhotos.getOrNull(pager.settledPage)
    LaunchedEffect(settled?.uri, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress && settled != null) selectedMediaUri = settled.uri.toString()
        if (!pager.isScrollInProgress && settled != null && lastPageUri != settled.uri.toString()) {
            lastPageUri = settled.uri.toString()
            if (!settled.isVideo) inlineVideo = false
            if (settled.isVideo && !inlineVideo) nestedUri = settled.uri.toString()
        }
    }
    val nestedMedia = currentPhotos.firstOrNull { it.uri.toString() == nestedUri }
    val videoPage = photo?.isVideo == true && inlineVideo
    val shot = jobs.firstOrNull { it.id == photo?.shotId }
    var info by rememberSaveable { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var deleting by remember { mutableStateOf<GalleryPhoto?>(null) }
    var zoomed by remember { mutableStateOf(false) }
    var videoPositions by rememberSaveable { mutableStateOf(hashMapOf<String, Long>()) }
    val density = LocalDensity.current
    var videoHeaderHeight by remember(density) { mutableStateOf(64.dp) }
    var videoControlsHeight by remember(density) { mutableStateOf(112.dp) }
    var photoControlsHeight by remember(density) { mutableStateOf(84.dp) }
    fun saveVideoPosition(uri: String, position: Long) {
        if (videoPositions[uri] != position) videoPositions = HashMap(videoPositions).apply { put(uri, position) }
    }
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    fun closeViewer() { visibility.targetState = false }
    LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) dismiss()
    }
    LaunchedEffect(pager.currentPage) { zoomed = false; moreVisible = false }
    BackHandler { closeViewer() }
    fun share(uri: Uri, raw: Boolean) {
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (raw) "image/x-adobe-dng" else photo?.mimeType ?: "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, AppLanguage.text(if (raw) "Share original DNG" else "Share"))) }.onFailure { onError(it.message ?: "No sharing app available") }
    }
    val viewerHeader: @Composable (Boolean, () -> Unit) -> Unit = { compact, onInteraction ->
        Row(Modifier.fillMaxWidth().testTag("Video viewer header")
            .onSizeChanged { videoHeaderHeight = with(density) { it.height.toDp() } + 20.dp }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryIconButton("Back", onClick = { onInteraction(); closeViewer() }) {
                GallerySymbolIcon(GallerySymbol.BACK, CameraColors.Text, Modifier.size(24.dp))
            }
            if (!compact) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                photo?.let {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(CameraColors.MenuBackground, alpha = .45f)
                        .padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(date(it.capturedAt, "d MMMM yyyy"), translate = false, color = CameraColors.Text,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(date(it.capturedAt, "HH:mm:ss"), translate = false, color = CameraColors.Muted, fontSize = 10.sp)
                            Text("{value1} / {size}", arguments = mapOf("value1" to pager.currentPage + 1, "size" to photos.size),
                                color = CameraColors.Muted, fontSize = 10.sp)
                        }
                    }
                }
                if (photo == null) {
                    Text("Media unavailable", color = CameraColors.Muted, fontSize = 13.sp)
                    Text("{value1} / {size}", arguments = mapOf("value1" to 0, "size" to 0), color = CameraColors.Muted, fontSize = 10.sp)
                }
            }
            if (!compact) Spacer(Modifier.width(48.dp))
        }
    }
    val videoHeader: @Composable (() -> Unit) -> Unit = { viewerHeader(false, it) }
    val viewerActions: @Composable (() -> Unit) -> Unit = { onInteraction ->
        photo?.let { target -> key(target.uri) {
            GalleryViewerActions(info, { onInteraction(); share(target.uri, false) }, { onInteraction(); info = !info },
                modifier = if (target.isVideo) Modifier.testTag("Video viewer actions") else Modifier.testTag("Photo viewer actions"),
                onMoreChanged = { moreVisible = it; onInteraction() }) { dismissMenu ->
                if (shot?.hasRaw == true && !shot.panorama) GalleryMenuItem("Share RAW") { dismissMenu(); share(shot.rawUri, true) }
                GalleryMenuItem("Delete", enabled = processing.currentId != shot?.id || shot == null) {
                    dismissMenu(); onInteraction(); deleting = target
                }
            }
        } }
    }
    Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(visibility, Modifier.fillMaxSize().then(if (nestedMedia != null) Modifier.clearAndSetSemantics { } else Modifier),
        enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = .94f),
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = .96f)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(CameraColors.Background).testTag("Photo viewer")) {
            if (maxWidth > maxHeight) {
                val metadataWidth = (maxWidth * .4f).coerceAtMost(320.dp)
                val density = LocalDensity.current
                val direction = LocalLayoutDirection.current
                val leftInset = WindowInsets.safeDrawing.getLeft(density, direction)
                val rightInset = WindowInsets.safeDrawing.getRight(density, direction)
                Box(Modifier.fillMaxSize().absolutePadding(
                    left = with(density) { (rightInset - leftInset).coerceAtLeast(0).toDp() },
                    right = with(density) { (leftInset - rightInset).coerceAtLeast(0).toDp() },
                )) {
                    HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize(), key = photoKey) { index ->
                        val pagePhoto = currentPhotos.getOrNull(index) ?: return@HorizontalPager
                        ViewerMedia(pagePhoto, active = index == pager.settledPage && !pager.isScrollInProgress && nestedMedia == null && deleting == null && visibility.targetState,
                            videoPosition = videoPositions[pagePhoto.uri.toString()] ?: 0,
                            onVideoPositionChanged = { saveVideoPosition(pagePhoto.uri.toString(), it) },
                            videoInfoVisible = info || moreVisible, onVideoControlsHidden = { info = false },
                            onVideoControlsHeightChanged = { videoControlsHeight = it },
                            videoHeader = videoHeader, videoActions = viewerActions, inlineVideo = inlineVideo,
                            onOpenMedia = { nestedUri = pagePhoto.uri.toString() },
                            onZoomChanged = { zoomed = it }, onTap = {
                            controlsVisible = !controlsVisible
                            if (!controlsVisible) info = false
                        })
                    }
                    AnimatedVisibility(info && photo != null,
                        modifier = Modifier.align(Alignment.Center).padding(start = 8.dp, end = 8.dp,
                            top = if (videoPage) videoHeaderHeight else 8.dp,
                            bottom = if (videoPage) videoControlsHeight else photoControlsHeight + 16.dp)
                            .width(metadataWidth).heightIn(max = 320.dp).fillMaxHeight(),
                        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 4 },
                        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 4 }) {
                        photo?.let { PhotoMetadata(it, shot, Modifier.fillMaxSize(), showDate = true,
                            onClose = { info = false }) }
                    }
                    if (controlsVisible && !videoPage) Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .padding(start = 8.dp, top = 8.dp, end = if (info) metadataWidth + 16.dp else 8.dp)) {
                        viewerHeader(info && photo != null) { }
                    }
                    AnimatedVisibility(controlsVisible && !videoPage, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                        enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(CameraColors.MenuBackground).testTag("Photo viewer floating controls")
                            .onSizeChanged { photoControlsHeight = with(density) { it.height.toDp() } }) {
                            viewerActions { }
                        }
                    }
                }
            } else {
                val informationHeight = (maxHeight * .44f).coerceAtMost(360.dp)
                Column(Modifier.fillMaxSize()) {
                    if (!videoPage) Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        videoHeader { }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize(), key = photoKey) { index ->
                            val pagePhoto = currentPhotos.getOrNull(index) ?: return@HorizontalPager
                            ViewerMedia(pagePhoto, active = index == pager.settledPage && !pager.isScrollInProgress && nestedMedia == null && deleting == null && visibility.targetState,
                                videoPosition = videoPositions[pagePhoto.uri.toString()] ?: 0,
                                onVideoPositionChanged = { saveVideoPosition(pagePhoto.uri.toString(), it) },
                                videoInfoVisible = info || moreVisible, onVideoControlsHidden = { info = false },
                                onVideoControlsHeightChanged = { videoControlsHeight = it },
                                videoHeader = videoHeader, videoActions = viewerActions, inlineVideo = inlineVideo,
                                onOpenMedia = { nestedUri = pagePhoto.uri.toString() },
                                onZoomChanged = { zoomed = it })
                        }
                        androidx.compose.animation.AnimatedVisibility(info && photo != null,
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 8.dp,
                                    bottom = if (videoPage) videoControlsHeight else 8.dp)
                                .height(informationHeight),
                            enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it },
                            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it }) {
                            photo?.let { PhotoMetadata(it, shot, Modifier.fillMaxSize(), onClose = { info = false }) }
                        }
                    }
                    if (photo != null && !videoPage) {
                        PhotoFilmstrip(photos, pager.currentPage) { index ->
                            val target = currentPhotos.getOrNull(index)
                            if (index == pager.currentPage && target?.isVideo == true) nestedUri = target.uri.toString()
                            else if (index != pager.currentPage) scope.launch { pager.animateScrollToPage(index) }
                        }
                        viewerActions { }
                    }
                }
            }
        }
    }
    nestedMedia?.let { media ->
        Box(Modifier.fillMaxSize().background(CameraColors.Background).testTag("Nested media viewer")) {
            if (media.isBurst) com.unspektrawesome.burst.BurstViewer(media.uri, { nestedUri = null }, onDeleted)
            else PhotoViewer(listOf(media), media.uri.toString(), jobs, { nestedUri = null }, onError, onDeleted)
        }
    }
    }
    deleting?.let { target -> DeletePhotosDialog(1,
        jobs.firstOrNull { it.id == target.shotId }?.let { it.hasRaw && !it.panorama } == true, { deleting = null }, containsVideo = target.isVideo) { deleteOriginals ->
        deleting = null
        scope.launch {
            runCatching { deletePhotos(listOf(target), deleteOriginals) }
                .onSuccess { result ->
                    if (result.failed.isEmpty()) { onDeleted(); closeViewer() }
                    else {
                        if (target.uri in result.deleted) { onDeleted(); closeViewer() }
                        onError(result.failed.values.first())
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    onError(it.message ?: "Unable to delete photo")
                }
        }
    } }
}

@Composable
private fun ViewerMedia(photo: GalleryPhoto, active: Boolean,
    videoPosition: Long, onVideoPositionChanged: (Long) -> Unit,
    onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit = {},
    videoInfoVisible: Boolean = false, onVideoControlsHidden: () -> Unit = {},
    onVideoControlsHeightChanged: (Dp) -> Unit = {},
    videoHeader: (@Composable (() -> Unit) -> Unit)? = null,
    videoActions: (@Composable (() -> Unit) -> Unit)? = null,
    inlineVideo: Boolean = true, onOpenMedia: () -> Unit = {}) {
    if (photo.isBurst || photo.isVideo && !inlineVideo) Box(Modifier.fillMaxSize().testTag("Media cover"), contentAlignment = Alignment.Center) {
        PhotoImage(photo.uri, null, Modifier.fillMaxSize(), imageKey = photo.generation, isVideo = photo.isVideo,
            contentScale = if (photo.isBurst) ContentScale.Fit else ContentScale.Crop)
        if (photo.isBurst) BurstCoverButton(active, onOpenMedia)
        else GalleryIconButton("Play", Modifier.background(CameraColors.Glass, RoundedCornerShape(24.dp)), onClick = onOpenMedia) {
            GallerySymbolIcon(GallerySymbol.PLAY, CameraColors.Text, Modifier.size(24.dp))
        }
    }
    else if (photo.isVideo) GalleryVideo(photo, active, videoPosition, onVideoPositionChanged,
        keepControlsVisible = videoInfoVisible, onControlsHidden = onVideoControlsHidden,
        onControlsHeightChanged = onVideoControlsHeightChanged,
        topControls = videoHeader, bottomActions = videoActions)
    else ZoomablePhoto(photo, active, onZoomChanged, onTap)
}

@Composable
private fun BurstCoverButton(active: Boolean, onClick: () -> Unit) {
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            opacity.snapTo(.5f)
            opacity.animateTo(1f, tween(300))
            opacity.animateTo(.65f, tween(420))
            opacity.animateTo(1f, tween(420))
        } else opacity.snapTo(1f)
    }
    val shape = RoundedCornerShape(24.dp)
    GalleryIconButton("BURST", Modifier.size(64.dp).testTag("Burst cover action")
        .graphicsLayer { alpha = opacity.value }
        .background(CameraColors.Glass, shape).border(2.dp, CameraColors.Accent, shape),
        enabled = active, onClick = onClick) {
        GallerySymbolIcon(GallerySymbol.STACK, CameraColors.Accent, Modifier.size(32.dp))
    }
}

@Composable
private fun PhotoFilmstrip(photos: List<GalleryPhoto>, selected: Int, onSelect: (Int) -> Unit) {
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selected) { scroll.animateScrollToItem((selected - 2).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(state = scroll, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().testTag("Photo filmstrip")) {
        items(photos.size, key = { photos[it].uri.toString() }) { index ->
            val item = photos[index]
            val shape = RoundedCornerShape(7.dp)
            Box(Modifier.size(60.dp, 44.dp).clip(shape).background(CameraColors.MenuCard)
                .clickable(role = Role.Button) { onSelect(index) }
                .languageSemantics { contentDescription = item.name; this.selected = index == selected }) {
                PhotoImage(item.uri, null, Modifier.fillMaxSize(), imageKey = item.generation, isVideo = item.isVideo)
                if (index == selected) Box(Modifier.matchParentSize().border(2.dp, CameraColors.Accent, shape))
            }
        }
    }
}
