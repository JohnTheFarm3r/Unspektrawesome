package com.unspektrawesome.gallery

import com.unspektrawesome.language.languageSemantics
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.unspektrawesome.ui.camera.CameraColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class LoadedPhoto(val source: PhotoRegionSource, val preview: Bitmap)
private data class DecodedPhotoTile(val key: PhotoTile, val bitmap: Bitmap)

@Composable
internal fun ZoomablePhoto(photo: GalleryPhoto, active: Boolean, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit = {}) {
    key(photo.uri, photo.generation) {
        PhotoContent(photo, active, onZoomChanged, onTap)
    }
}

@Composable
internal fun ZoomableBitmap(bitmap: Bitmap) {
    key(bitmap) { PhotoContent(null, true, {}, {}, bitmap) }
}

@Composable
private fun PhotoContent(photo: GalleryPhoto?, active: Boolean, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit, bitmap: Bitmap? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifyZoom by rememberUpdatedState(onZoomChanged)
    val notifyTap by rememberUpdatedState(onTap)
    var loaded by remember { mutableStateOf<LoadedPhoto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf(PhotoTransform()) }
    var dragging by remember { mutableStateOf(false) }
    var zoomAnimation by remember { mutableStateOf<Job?>(null) }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(photo?.uri, photo?.generation, bitmap) {
        var source: PhotoRegionSource? = null
        try {
            loaded = withContext(Dispatchers.IO) {
                val opened = if (bitmap != null) PhotoRegionSource.fromBitmap(bitmap)
                    else PhotoRegionSource.open(context.contentResolver, requireNotNull(photo).uri)
                source = opened
                LoadedPhoto(opened, opened.preview())
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to decode this photo."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { source?.close() }
        }
    }
    LaunchedEffect(loaded) { if (loaded != null) opacity.animateTo(1f, tween(180)) }
    val image = loaded
    val viewport = remember(image, size) {
        image?.takeIf { size.width > 0 && size.height > 0 }?.let {
            PhotoViewport(it.source.orientedWidth, it.source.orientedHeight, size.width, size.height)
        }
    }
    LaunchedEffect(viewport) {
        zoomAnimation?.cancel()
        viewport?.let { transform = it.constrain(transform) }
    }
    LaunchedEffect(active) {
        if (!active) {
            zoomAnimation?.cancel()
            transform = PhotoTransform()
            dragging = false
        }
    }
    LaunchedEffect(active, transform.zoom, dragging) {
        if (active) notifyZoom(transform.zoom > 1.01f || dragging)
    }
    val requests = if (image != null && viewport != null && active) image.source.visibleTiles(viewport, transform) else emptyList()
    var tiles by remember(image) { mutableStateOf<List<DecodedPhotoTile>>(emptyList()) }
    LaunchedEffect(image, requests) {
        if (image == null) return@LaunchedEffect
        try {
            tiles = withContext(Dispatchers.IO) {
                requests.map { tile ->
                    currentCoroutineContext().ensureActive()
                    DecodedPhotoTile(tile, image.source.tile(tile))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to load photo detail."
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
        .then(if (bitmap == null) Modifier.testTag("Zoomable photo") else Modifier.testTag("Native burst preview"))
        .languageSemantics { language -> stateDescription = language.format("{value1}% zoom", mapOf("value1" to ((transform.zoom * 100).roundToInt()))); selected = active }
        .pointerInput(viewport, active) {
            if (!active || viewport == null) return@pointerInput
            detectTapGestures(onTap = { notifyTap() }, onDoubleTap = { point ->
                zoomAnimation?.cancel()
                val start = transform
                val target = viewport.doubleTap(start, point.x, point.y)
                zoomAnimation = scope.launch {
                    animate(0f, 1f, animationSpec = tween(280, easing = FastOutSlowInEasing)) { fraction, _ ->
                        transform = viewport.constrain(start.interpolate(target, fraction))
                    }
                }
            })
        }.pointerInput(viewport, active) {
            if (!active || viewport == null) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var accumulatedZoom = 1f
                var accumulatedPan = Offset.Zero
                var moved = false
                try {
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        accumulatedZoom *= zoom
                        accumulatedPan += pan
                        if (!moved && (transform.zoom > 1.01f || event.changes.count { it.pressed } > 1)) {
                            val zoomDistance = abs(1f - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
                            if (zoomDistance > viewConfiguration.touchSlop || accumulatedPan.getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                dragging = true
                                zoomAnimation?.cancel()
                            }
                        }
                        if (moved) {
                            val focus = event.calculateCentroid(useCurrent = false)
                            if (focus.isSpecified) transform = viewport.transform(transform, transform.zoom * zoom, focus.x, focus.y, pan.x, pan.y)
                            event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    dragging = false
                }
            }
        }, contentAlignment = Alignment.Center) {
        when {
            error != null -> EmptyGallery("Photo unavailable", error!!)
            image == null || viewport == null -> GalleryIcon(0, CameraColors.Muted, Modifier.size(28.dp))
            else -> {
                val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }
                    .languageSemantics { language -> contentDescription = language.text(if (bitmap == null) "Photo preview" else "BURST preview") }) {
                    drawIntoCanvas { canvas ->
                        val target = canvas.nativeCanvas
                        target.save()
                        target.translate(viewport.width / 2f + transform.x, viewport.height / 2f + transform.y)
                        val scale = viewport.fitScale * transform.zoom
                        target.scale(scale, scale)
                        target.translate(-image.source.orientedWidth / 2f, -image.source.orientedHeight / 2f)
                        target.concat(image.source.orientationMatrix)
                        target.drawBitmap(image.preview, null, Rect(0, 0, image.source.width, image.source.height), paint)
                        tiles.forEach { tile ->
                            target.drawBitmap(tile.bitmap, null, tile.key.region(image.source.width, image.source.height), paint)
                        }
                        target.restore()
                    }
                }
            }
        }
    }
}
