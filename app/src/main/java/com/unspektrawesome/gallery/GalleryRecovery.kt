package com.unspektrawesome.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.unspektrawesome.capture.JpegMediaStoreWriter
import com.unspektrawesome.diagnostics.InternalLogRecorder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class GalleryRecoveryResult(val restored: Int = 0, val failed: Int = 0)

internal class GalleryRecovery(private val context: Context, private val store: GalleryStore) {
    private val resolver = context.contentResolver
    private data class Raw(val uri: Uri, val size: Long, val generation: Long, val owner: String?, val capturedAt: Long)
    private data class Jpeg(val uri: Uri, val width: Int, val height: Int, val identity: String?,
        val date: String?, val subsecond: String?, val offset: String?, val lens: String?, val description: String?)
    private val checked = mutableMapOf<Uri, Pair<Long, Long>>()
    private var mediaVersion: String? = null
    private var jpegSignature: List<Triple<Uri, Long, Long>> = emptyList()

    @Synchronized fun recover(): GalleryRecoveryResult {
        val version = MediaStore.getVersion(context)
        if (version != mediaVersion) { checked.clear(); mediaVersion = version }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val raws = resolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.GENERATION_MODIFIED, MediaStore.Images.Media.OWNER_PACKAGE_NAME, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ? AND " +
                "${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.IS_TRASHED} = 0",
            arrayOf("DCIM/Unspektrawesome/RAW/", "image/x-adobe-dng"), MediaStore.Images.Media._ID)?.use { cursor ->
            buildList<Raw> { while (cursor.moveToNext()) add(Raw(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getLong(4))) }
        } ?: error("Media storage is unavailable")
        val available = raws.map { it.uri }.toSet()
        checked.keys.retainAll(available)
        val byUri = store.shots.value.associateBy { it.rawUri }
        val unknown = raws.filter { it.uri !in byUri }
        val photos = GalleryMedia.load(context, emptyList()).filter { !it.isVideo }
        val signature = photos.map { Triple(it.uri, it.generation, it.bytes) }
        if (signature != jpegSignature) { checked.clear(); jpegSignature = signature }
        val jpegs by lazy { readJpegs(photos) }
        val claimedJpegs = store.shots.value.mapNotNull { it.jpegUri }.toMutableSet()
        var restored = 0
        var failed = 0
        for (raw in raws) {
            val known = byUri[raw.uri]
            val stamp = raw.size to raw.generation
            if (known != null && checked[raw.uri] == stamp) continue
            try {
                if (known != null) {
                    if (known.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.EXPORTED) &&
                        photos.none { it.uri == known.jpegUri } && photos.isNotEmpty()) {
                        val saved = DngRecipe(known.id, context.packageName, store.recipe(known.id), null)
                        matchingJpeg(saved, jpegs, claimedJpegs, raws)?.let {
                            store.update(known.id, ExportState.EXPORTED, jpegUri = it)
                            claimedJpegs.add(it)
                        }
                    }
                    if (store.syncRecoveryRecord(known.id)) checked[raw.uri] = stamp
                    continue
                }
                ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(resolver.openFileDescriptor(raw.uri, "r"))).use { input ->
                    val recipe = DngRecipe.read(input.channel) ?: return@use
                    if (!belongsToThisApp(recipe, raw.owner)) return@use
                    // Verify the original before restoring an exportable entry.
                    DngPixels.read(input.channel, recipe.capture.raw.getInt(0), recipe.capture.raw.getInt(4))
                    val matched = matchingJpeg(recipe, jpegs, claimedJpegs, unknown)
                    if (store.restore(recipe, raw.uri, input.channel.size(), matched, available)) restored++
                    matched?.let(claimedJpegs::add)
                }
                checked[raw.uri] = stamp
            } catch (error: Exception) {
                failed++
                InternalLogRecorder.warning("GalleryRecovery", "restore_failed uri=${raw.uri} reason=${error.javaClass.simpleName}")
            }
        }
        return GalleryRecoveryResult(restored, failed)
    }

    private fun belongsToThisApp(recipe: DngRecipe, owner: String?): Boolean {
        recipe.owner?.let { return it == context.packageName }
        val identity = recipe.capture.metadata.identity
        if (identity != null) return identity.appName == "Unspektrawesome" &&
            identity.appVersion.endsWith("-testing") == context.packageName.endsWith(".testing")
        return owner == context.packageName || !context.packageName.endsWith(".testing")
    }

    private fun readJpegs(photos: List<GalleryPhoto>): List<Jpeg> = photos.mapNotNull { photo ->
        runCatching {
            resolver.openFileDescriptor(photo.uri, "r")?.use { file ->
                val exif = ExifInterface(file.fileDescriptor)
                Jpeg(photo.uri, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, photo.width),
                    exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, photo.height),
                    exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID), exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_LENS_MODEL), exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            }
        }.getOrNull()
    }

    private fun matchingJpeg(recipe: DngRecipe, jpegs: List<Jpeg>, claimed: Set<Uri>, raws: List<Raw>): Uri? {
        val identity = JpegMediaStoreWriter.imageUniqueId(recipe.id)
        val exact = jpegs.filter { it.identity == identity &&
            it.width == recipe.capture.width && it.height == recipe.capture.height }
        if (exact.size == 1) return exact.single().uri
        if (exact.isNotEmpty()) return null
        val capture = recipe.capture
        if (raws.count { it.capturedAt == capture.capturedAtMillis } > 1) return null
        val offset = capture.metadata.utcOffsetSeconds ?: return null
        val date = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
            .format(Instant.ofEpochMilli(capture.capturedAtMillis).atOffset(ZoneOffset.ofTotalSeconds(offset)))
        val millis = String.format(Locale.US, "%03d", Math.floorMod(capture.capturedAtMillis, 1000L))
        val offsetText = if (offset == 0) "+00:00" else ZoneOffset.ofTotalSeconds(offset).id
        val legacy = jpegs.filter { jpeg -> jpeg.uri !in claimed &&
            jpeg.identity.isNullOrBlank() &&
            jpeg.width == capture.width && jpeg.height == capture.height &&
            jpeg.date == date && jpeg.subsecond == millis && jpeg.offset == offsetText &&
            jpeg.lens == capture.metadata.lensModel && jpeg.description.orEmpty() == capture.metadata.description() }
        // A legacy JPEG has no capture ID. Ambiguous matches must remain available to export.
        return legacy.singleOrNull()?.uri
    }
}
