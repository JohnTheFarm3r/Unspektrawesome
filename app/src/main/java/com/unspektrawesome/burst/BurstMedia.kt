package com.unspektrawesome.burst

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.unspektrawesome.gallery.GalleryPhoto

internal object BurstMedia {
    const val LEGACY_DIRECTORY = "Download/Unspektrawesome/BURST/"
    const val SELECTION = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
    private const val PUBLISHED = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
    private val collection get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    fun selectionArguments() = arrayOf(BurstContainer.DIRECTORY, LEGACY_DIRECTORY, "%.spektra")

    fun canonicalUri(uri: Uri): Uri {
        val parts = uri.pathSegments
        return if (uri.authority == MediaStore.AUTHORITY && parts.size == 3 && parts[1] == "downloads")
            parts[2].toLongOrNull()?.let { MediaStore.Files.getContentUri(parts[0], it) } ?: uri
        else uri
    }

    fun create(context: Context, name: String): Uri {
        check(Environment.isExternalStorageManager()) { "Allow all-files access to save BURST files in DCIM/Unspektrawesome/BURST." }
        return requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, BurstContainer.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, BurstContainer.DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
    }

    @Synchronized private fun migrateLegacy(context: Context) {
        if (!Environment.isExternalStorageManager()) return
        val resolver = context.contentResolver
        val pending = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND $PUBLISHED AND " +
                "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? OR ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL)",
            arrayOf(LEGACY_DIRECTORY, "%.spektra", context.packageName), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
        pending.forEach { uri ->
            try {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, BurstContainer.DIRECTORY) }, null, null)
            } catch (error: Exception) {
                com.unspektrawesome.diagnostics.InternalLogRecorder.error("Burst", "storage_move_failed", error)
            }
        }
    }

    fun import(context: Context, source: Uri): Uri {
        val resolver = context.contentResolver
        val uri = create(context, "Unspektrawesome-${System.currentTimeMillis()}.spektra")
        try {
            resolver.openInputStream(source)!!.use { input ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(resolver.openFileDescriptor(uri, "w"))).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= 16L * 1024 * 1024 * 1024 &&
                            android.os.StatFs(context.filesDir.absolutePath).availableBytes > 128L * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            BurstContainer.Reader(context, uri).use { reader ->
                reader.thumbnail()
                repeat(reader.count) { frame ->
                    reader.capture(frame)
                    BurstContainer.dimensions(reader.pixels(frame, false))
                    BurstContainer.dimensions(reader.pixels(frame, true))
                }
            }
            check(resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            return uri
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }

    fun isBurst(context: Context, uri: Uri): Boolean {
        val item = canonicalUri(uri)
        if (context.contentResolver.getType(item) == BurstContainer.MIME_TYPE) return true
        return context.contentResolver.query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            it.moveToFirst() && it.getString(0).endsWith(".spektra", ignoreCase = true)
        } == true
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? = BurstContainer.Reader(context, uri).use { reader ->
        val bytes = reader.thumbnail()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun load(context: Context): List<GalleryPhoto> {
        migrateLegacy(context)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.GENERATION_MODIFIED)
        return context.contentResolver.query(collection, projection,
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND $SELECTION",
            selectionArguments(), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    val index = runCatching { BurstContainer.Reader(context, uri).use { it.index } }.getOrNull()
                    add(GalleryPhoto(uri, cursor.getString(1), index?.optLong("capturedAt") ?: cursor.getLong(2) * 1000,
                        index?.optInt("width") ?: 0, index?.optInt("height") ?: 0, cursor.getLong(3),
                        developed = false, generation = cursor.getLong(4), mimeType = BurstContainer.MIME_TYPE))
                }
            }
        } ?: emptyList()
    }
}
