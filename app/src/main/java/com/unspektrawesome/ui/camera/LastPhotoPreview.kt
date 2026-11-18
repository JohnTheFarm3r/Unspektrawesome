package com.unspektrawesome.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Large preview of the last saved photo or video, shown in the free space of the split camera layout.
 * Tapping it opens the photo like the gallery thumbnail does.
 */
@Composable
internal fun LastPhotoPreview(
    lastPhoto: Uri?,
    onOpen: () -> Unit,
    onOpenGallery: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Decode close to the width shown, so the photo stays sharp without loading it at full size.
        val target = with(LocalDensity.current) { maxWidth.roundToPx() }
            .let { (it + PREVIEW_SIZE_STEP - 1) / PREVIEW_SIZE_STEP * PREVIEW_SIZE_STEP }
            .coerceIn(MIN_PREVIEW_SIZE, MAX_PREVIEW_SIZE)
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(lastPhoto, target) {
            // The previous photo stays visible until the new one is ready.
            bitmap = lastPhoto?.let { uri -> withContext(Dispatchers.IO) { loadPreview(context, uri, target) } ?: bitmap }
        }
        val shown = bitmap ?: return@BoxWithConstraints
        if (maxWidth < 48.dp || maxHeight < 48.dp) return@BoxWithConstraints
        val ratio = shown.width.toFloat() / shown.height.coerceAtLeast(1)
        val shape = RoundedCornerShape(12.dp)
        val frame = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < maxWidth / maxHeight)
            .clip(shape).border(1.dp, CameraColors.MenuBorder, shape)
        val image: @Composable () -> Unit = {
            Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (interactive) GalleryThumbnailButton(onOpen, onOpenGallery, frame) { image() }
        else Box(frame) { image() }
    }
}

private fun loadPreview(context: Context, uri: Uri, size: Int): Bitmap? = runCatching {
    if (com.unspektrawesome.burst.BurstMedia.isBurst(context, uri))
        return@runCatching com.unspektrawesome.burst.BurstMedia.thumbnail(context, uri)
    val resolver = context.contentResolver
    if (resolver.getType(uri)?.startsWith("video/") == true)
        return@runCatching resolver.loadThumbnail(uri, android.util.Size(size, size), null)
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val scale = minOf(1f, size.toFloat() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
            (info.size.height * scale).roundToInt().coerceAtLeast(1))
    }
}.getOrNull() ?: runCatching {
    context.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
}.getOrNull()

private const val PREVIEW_SIZE_STEP = 256
private const val MIN_PREVIEW_SIZE = 512
private const val MAX_PREVIEW_SIZE = 2048
