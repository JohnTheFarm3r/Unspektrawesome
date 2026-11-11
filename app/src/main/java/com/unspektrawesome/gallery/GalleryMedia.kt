package com.unspektrawesome.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.unspektrawesome.preview.PersistedCapture

internal data class GalleryPhoto(
    val uri: Uri,
    val name: String,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val developed: Boolean,
    val shotId: String? = null,
    val generation: Long = 0,
    val mimeType: String = "image/jpeg",
    val durationMs: Long = 0,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isBurst: Boolean get() = mimeType == com.unspektrawesome.burst.BurstContainer.MIME_TYPE
}

internal object GalleryMedia {
    fun isVideoUri(uri: Uri): Boolean = uri.authority == MediaStore.AUTHORITY && "video" in uri.pathSegments

    fun latestUri(context: Context): Uri? = query(context, emptyList(), latestOnly = true).firstOrNull()?.uri

    fun deletePhotos(context: Context, photos: List<GalleryPhoto>, deleteOriginals: Boolean = false): GalleryDeletionResult {
        val store = GalleryStore.get(context)
        val deleted = mutableSetOf<Uri>()
        val failed = mutableMapOf<Uri, String>()
        for (photo in photos.distinctBy { it.uri }) {
            try {
                synchronized(PersistedCapture.exportLock) {
                    val shot = store.shots.value.firstOrNull { it.jpegUri == photo.uri }
                    check(shot == null || (shot.state != ExportState.PROCESSING &&
                        GalleryProcessing.state.value.currentId != shot.id)) {
                        "This photo is being exported. Try again when it finishes."
                    }
                    check(context.contentResolver.delete(photo.uri,
                        if (photo.isBurst) com.unspektrawesome.burst.BurstMedia.SELECTION
                        else "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
                        if (photo.isBurst) com.unspektrawesome.burst.BurstMedia.selectionArguments()
                        else arrayOf("DCIM/Unspektrawesome/", photo.mimeType)) == 1) {
                        "Could not delete this item."
                    }
                    deleted += photo.uri
                    if (shot != null) {
                        try {
                            if (deleteOriginals && shot.hasRaw && !shot.panorama) store.deleteVerifiedRaw(shot.id, photo.uri)
                        } catch (_: Exception) {
                            failed[photo.uri] = "JPEG deleted, but its original DNG could not be deleted."
                        } finally { store.jpegDeleted(shot.id) }
                    }
                }
            } catch (error: Exception) {
                failed[photo.uri] = error.message ?: "Could not delete this item."
            }
        }
        return GalleryDeletionResult(deleted, failed)
    }

    fun load(context: Context, shots: List<GalleryShot>): List<GalleryPhoto> = query(context, shots)

    private fun query(context: Context, shots: List<GalleryShot>, latestOnly: Boolean = false): List<GalleryPhoto> {
        val byUri = buildMap {
            shots.forEach { shot -> shot.jpegUri?.let { put(it, shot) } }
        }
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "image/jpeg",
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "image/png",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/mp4",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/quicktime")
        return (com.unspektrawesome.burst.BurstMedia.load(context) + collections.flatMap { (collection, mimeType) ->
            val projection = arrayOf(MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.GENERATION_MODIFIED) +
                if (mimeType.startsWith("video/")) arrayOf(MediaStore.Video.Media.DURATION) else emptyArray()
            val arguments = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("DCIM/Unspektrawesome/", mimeType))
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "COALESCE(NULLIF(${MediaStore.Images.Media.DATE_TAKEN}, 0), ${MediaStore.Images.Media.DATE_ADDED} * 1000) DESC, ${MediaStore.Images.Media._ID} DESC")
                if (latestOnly) putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            }
            context.contentResolver.query(collection, projection, arguments, null)?.use { cursor ->
                buildList<GalleryPhoto> {
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        val shot = byUri[uri]
                        add(GalleryPhoto(uri, cursor.getString(1), shot?.capturedAt ?: cursor.getLong(2).takeIf { it > 0 } ?: (cursor.getLong(3) * 1000),
                            cursor.getInt(4), cursor.getInt(5), cursor.getLong(6), shot != null, shot?.id, cursor.getLong(7),
                            mimeType, if (mimeType.startsWith("video/")) cursor.getLong(8) else 0L))
                    }
                }
            } ?: error("Media storage is unavailable")
        }).sortedWith(compareByDescending<GalleryPhoto> { it.capturedAt }.thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0 })
    }
}

internal data class GalleryDeletionResult(val deleted: Set<Uri>, val failed: Map<Uri, String>)
