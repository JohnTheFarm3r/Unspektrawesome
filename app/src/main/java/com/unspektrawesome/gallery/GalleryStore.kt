package com.unspektrawesome.gallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.unspektrawesome.preview.PersistedCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

internal enum class ExportState { SAVING_RAW, WAITING, PROCESSING, EXPORTED, FAILED, SKIPPED, RAW_REMOVED }
internal enum class RawRetention { KEPT, DELETING, DELETED }

internal data class GalleryShot(
    val id: String,
    val rawUri: Uri,
    val jpegUri: Uri?,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val lens: String,
    val look: String,
    val state: ExportState,
    val detail: String,
    val rawBytes: Long,
    val attempts: Int = 0,
    val rawRetention: RawRetention = RawRetention.KEPT,
    val historyHidden: Boolean = false,
    val panorama: Boolean = false,
    val grid: Boolean = false,
) {
    val hasRaw: Boolean get() = rawRetention == RawRetention.KEPT && state != ExportState.RAW_REMOVED
}

internal data class ExportBatch(val ids: List<String>, val running: Boolean)

internal class GalleryStore private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val resolver = context.contentResolver
    private val database = object : SQLiteOpenHelper(context, "gallery.db", null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE shots (id TEXT PRIMARY KEY, raw_uri TEXT NOT NULL, jpeg_uri TEXT, captured_at INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, lens TEXT NOT NULL, look TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', raw_bytes INTEGER NOT NULL DEFAULT 0, recipe TEXT NOT NULL)")
            db.execSQL("CREATE INDEX shots_date ON shots(captured_at DESC)")
            addBatchStorage(db)
            addRawStorage(db)
            addHistoryVisibility(db)
            addPanorama(db)
            addGrid(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) addBatchStorage(db)
            if (oldVersion < 3) addRawStorage(db)
            if (oldVersion < 4) addHistoryVisibility(db)
            if (oldVersion < 5) addPanorama(db)
            if (oldVersion < 6) addGrid(db)
        }
        private fun addGrid(db: SQLiteDatabase) { db.execSQL("ALTER TABLE shots ADD COLUMN grid INTEGER NOT NULL DEFAULT 0") }
        private fun addPanorama(db: SQLiteDatabase) { db.execSQL("ALTER TABLE shots ADD COLUMN panorama INTEGER NOT NULL DEFAULT 0") }
        private fun addHistoryVisibility(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE shots ADD COLUMN history_hidden INTEGER NOT NULL DEFAULT 0")
        }
        private fun addRawStorage(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE shots ADD COLUMN raw_retention TEXT NOT NULL DEFAULT 'KEPT'")
            db.execSQL("UPDATE shots SET raw_retention = 'DELETED', raw_bytes = 0 WHERE state = 'RAW_REMOVED'")
        }
        private fun addBatchStorage(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE shots ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE processing_batch (id INTEGER PRIMARY KEY CHECK(id = 1), ids TEXT NOT NULL, running INTEGER NOT NULL)")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }.writableDatabase
    private val mutableShots = MutableStateFlow<List<GalleryShot>>(emptyList())
    val shots = mutableShots.asStateFlow()
    val thumbnailDirectory = File(context.filesDir, "gallery-thumbnails-v4").apply { mkdirs() }
    private val previousThumbnailDirectory = File(context.filesDir, "gallery-thumbnails-v3")

    init {
        database.execSQL("UPDATE shots SET state = 'WAITING', detail = 'Export interrupted. Ready to resume.' WHERE state = 'PROCESSING'")
        reload()
        mutableShots.value.filter { it.rawRetention == RawRetention.DELETING }.forEach { shot ->
            runCatching {
                val exists = resolver.query(shot.rawUri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() }
                    ?: error("Original storage is unavailable")
                setRawRetention(shot.id, if (exists) RawRetention.KEPT else RawRetention.DELETED,
                    if (exists) "RAW kept: cleanup was interrupted." else "")
            }
        }
        mutableShots.value.filter {
            !it.panorama && it.hasRaw && (it.state == ExportState.SAVING_RAW || it.state == ExportState.FAILED && it.rawBytes == 0L)
        }.forEach { shot ->
            runCatching {
                readPixels(shot)
                finishRaw(shot.rawUri, recipe(shot.id))
                val bytes = requireNotNull(resolver.openFileDescriptor(shot.rawUri, "r")).use { it.statSize }
                update(shot.id, ExportState.WAITING, rawBytes = bytes)
            }.onFailure { update(shot.id, ExportState.FAILED, "RAW saving was interrupted. ${it.message.orEmpty()}") }
        }
        com.unspektrawesome.panorama.PanoramaProject.root(applicationContext).listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            runCatching {
                val project = com.unspektrawesome.panorama.PanoramaProject.read(applicationContext, Uri.fromFile(File(folder, "panorama.json")))
                listOf("export.rgba", "export.png", "tracking.rgba", "tracking-next.rgba").forEach { File(folder, it).delete() }
                if (project.frames.size >= 2 && shots.value.none { it.id == project.id }) insertPanorama(project)
            }
        }
    }

    @Synchronized internal fun insertPanorama(project: com.unspektrawesome.panorama.PanoramaProject) {
        if (shots.value.any { it.id == project.id }) return
        require(project.frames.size >= 2)
        val record = runCatching { JSONObject(File(project.directory, "queue.json").readText()) }.getOrNull()
        val state = record?.optString("state")?.let { runCatching { ExportState.valueOf(it) }.getOrNull() }
            ?.takeIf { it in setOf(ExportState.WAITING, ExportState.EXPORTED, ExportState.SKIPPED, ExportState.FAILED) } ?: ExportState.WAITING
        val bounds = project.bounds
        database.insertOrThrow("shots", null, captureValues(project.id, Uri.fromFile(project.manifest), project.capture).apply {
            put("panorama", 1); put("state", state.name); put("width", bounds.width); put("height", bounds.height)
            put("grid", if (project.grid) 1 else 0)
            put("raw_bytes", project.bytes); put("history_hidden", if (record?.optBoolean("hidden") == true) 1 else 0)
            record?.optString("jpeg")?.takeIf { it.isNotEmpty() }?.let { put("jpeg_uri", it) }
        })
        reload()
    }

    @Synchronized fun insert(id: String, rawUri: Uri, capture: PersistedCapture) {
        database.insertOrThrow("shots", null, captureValues(id, rawUri, capture))
        reload()
    }

    private fun captureValues(id: String, rawUri: Uri, capture: PersistedCapture) = ContentValues().apply {
        put("id", id); put("raw_uri", rawUri.toString()); put("captured_at", capture.capturedAtMillis)
        put("width", capture.width); put("height", capture.height); put("lens", capture.metadata.lensModel)
        put("look", capture.metadata.spektra?.let { if (it.enabled) "${it.film} · ${it.print}" else "spektrafilm off" } ?: "Original capture")
        put("state", ExportState.SAVING_RAW.name)
        put("recipe", JSONObject().put("schema", 1).put("capture", capture.toJson()).toString())
    }

    @Synchronized fun update(id: String, state: ExportState, detail: String = "", jpegUri: Uri? = null, rawBytes: Long? = null, attempts: Int? = null) {
        val shot = shots.value.firstOrNull { it.id == id }
        val savedBytes = shot?.takeIf { it.hasRaw && (rawBytes ?: it.rawBytes) > 0 && state != ExportState.SAVING_RAW }?.let {
            persistQueueRecord(it.copy(state = state, jpegUri = jpegUri ?: it.jpegUri, historyHidden = if (state == ExportState.WAITING) false else it.historyHidden))
        }
        database.update("shots", ContentValues().apply {
            put("state", state.name); put("detail", detail)
            if (state == ExportState.WAITING) put("history_hidden", 0)
            jpegUri?.let { put("jpeg_uri", it.toString()) }
            (savedBytes ?: rawBytes)?.let { put("raw_bytes", it) }
            attempts?.let { put("attempts", it) }
        }, "id = ?", arrayOf(id))
        reload()
    }

    @Synchronized fun restore(recipe: DngRecipe, uri: Uri, bytes: Long, jpeg: Uri?, availableRaws: Set<Uri>): Boolean {
        val previous = shots.value.firstOrNull { it.id == recipe.id }
        if (previous != null && previous.rawUri in availableRaws) return false
        val state = when {
            jpeg != null -> ExportState.EXPORTED
            recipe.record?.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.FAILED) -> recipe.record!!.state
            else -> ExportState.WAITING
        }
        val values = if (previous == null) captureValues(recipe.id, uri, recipe.capture).apply {
            put("state", state.name)
            put("history_hidden", if (recipe.record?.hidden == true && state != ExportState.WAITING) 1 else 0)
        } else ContentValues()
        values.put("raw_uri", uri.toString())
        values.put("raw_bytes", bytes)
        values.put("raw_retention", RawRetention.KEPT.name)
        jpeg?.let { values.put("jpeg_uri", it.toString()) }
        if (previous == null) database.insertOrThrow("shots", null, values)
        else {
            require(this.recipe(previous.id).toJson().toString() == recipe.capture.toJson().toString())
            database.update("shots", values, "id = ?", arrayOf(recipe.id))
            thumbnail(previous.id).delete()
            File(previousThumbnailDirectory, "${previous.id}.jpg").delete()
        }
        reload()
        return true
    }

    @Synchronized fun syncRecoveryRecord(id: String): Boolean {
        val shot = shots.value.firstOrNull { it.id == id && it.hasRaw && it.rawBytes > 0 } ?: return true
        val size = persistQueueRecord(shot) ?: return false
        if (size != shot.rawBytes) {
            database.update("shots", ContentValues().apply { put("raw_bytes", size) }, "id = ?", arrayOf(id))
            reload()
        }
        return true
    }

    private fun persistQueueRecord(shot: GalleryShot, required: Boolean = false): Long? = try {
        if (shot.panorama) {
            val project = com.unspektrawesome.panorama.PanoramaProject.read(applicationContext, shot.rawUri)
            val file = android.util.AtomicFile(File(project.directory, "queue.json"))
            val stream = file.startWrite()
            try {
                stream.write(JSONObject().put("state", shot.state.name).put("hidden", shot.historyHidden)
                    .put("jpeg", shot.jpegUri?.toString().orEmpty()).toString().toByteArray())
                file.finishWrite(stream)
            } catch (error: Throwable) { file.failWrite(stream); throw error }
            project.bytes
        } else {
            val record = DngQueueRecord(shot.id, applicationContext.packageName, shot.state, shot.historyHidden)
            ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(resolver.openFileDescriptor(shot.rawUri, "r"))).use { input ->
                if (DngQueueJournal.read(input.channel) == record) input.channel.size()
                else ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(resolver.openFileDescriptor(shot.rawUri, "rw"))).use { output ->
                    DngQueueJournal.write(input.channel, output.channel, record)
                }
            }
        }
    } catch (error: Exception) {
        com.unspektrawesome.diagnostics.InternalLogRecorder.warning("GalleryRecovery", "state_save_failed id=${shot.id} reason=${error.javaClass.simpleName}")
        if (required) throw java.io.IOException("Unable to save queue changes. Allow storage access and try again.", error)
        null
    }

    @Synchronized fun beginAttempt(id: String, attempts: Int): Boolean {
        val changed = database.update("shots", ContentValues().apply {
            put("state", ExportState.PROCESSING.name)
            put("detail", "")
            put("attempts", attempts)
        }, "id = ? AND state IN ('WAITING', 'FAILED')", arrayOf(id)) == 1
        if (changed) reload()
        return changed
    }

    @Synchronized fun recipe(id: String): PersistedCapture {
        // Large DCP tables can exceed a CursorWindow, so read the recipe in bounded chunks.
        val text = StringBuilder()
        val chunkSize = 256 * 1024
        do {
            val chunk = database.rawQuery("SELECT substr(recipe, ?, ?) FROM shots WHERE id = ?",
                arrayOf((text.length + 1).toString(), chunkSize.toString(), id)).use {
                check(it.moveToFirst()) { "Capture recipe is missing" }
                it.getString(0)
            }
            require(text.length + chunk.length <= 32_000_000) { "Capture recipe is too large" }
            text.append(chunk)
        } while (chunk.length == chunkSize)
        val value = JSONObject(text.toString())
        require(value.getInt("schema") == 1) { "This capture needs a newer app version" }
        return PersistedCapture.fromJson(value.getJSONObject("capture"))
    }

    fun readPixels(shot: GalleryShot): java.nio.ByteBuffer {
        val capture = recipe(shot.id)
        return resolver.openFileDescriptor(shot.rawUri, "r")?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).channel.use {
                DngPixels.read(it, capture.raw.getInt(0), capture.raw.getInt(4))
            }
        } ?: error("Original DNG is unavailable. Restore it before retrying.")
    }

    fun finishRaw(uri: Uri, capture: PersistedCapture, previewJpeg: ByteArray? = null): Long {
        val levels = DngRawLevels.from(capture.raw)
        val size = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(resolver.openFileDescriptor(uri, "r")) {
            "Unable to read original DNG"
        }).use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(resolver.openFileDescriptor(uri, "rw")) {
                "Unable to update original DNG"
            }).use { output ->
                levels.writeTo(input.channel, output.channel)
                previewJpeg?.let { DngJpegPreview.writeTo(input.channel, output.channel, it) }
                output.fd.sync()
                output.channel.size()
            }
        }
        publishRaw(uri, "${com.unspektrawesome.capture.PhotoFileNames.baseName(capture.capturedAtMillis, capture.metadata.utcOffsetSeconds)}.dng")
        return size
    }

    fun publishRaw(uri: Uri, displayName: String? = null) {
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
            displayName?.let { put(MediaStore.Images.Media.DISPLAY_NAME, it) }
        }, null, null) == 1) {
            "Unable to publish original DNG"
        }
    }

    fun thumbnail(id: String): File {
        val shot = shots.value.firstOrNull { it.id == id }
        return if (shot?.panorama == true) File(requireNotNull(shot.rawUri.path)).parentFile!!.resolve(com.unspektrawesome.panorama.PanoramaProject.PREVIEW_FILE)
        else File(thumbnailDirectory, "$id.jpg")
    }

    fun ensureThumbnail(id: String): File = synchronized(thumbnailDirectory) {
        val file = thumbnail(id)
        if (!file.exists()) runCatching {
            val shot = shots.value.first { it.id == id }
            if (shot.panorama) {
                com.unspektrawesome.panorama.PanoramaProject.read(applicationContext, shot.rawUri).ensurePreview(applicationContext)
                return@synchronized file
            }
            if (shot.state == ExportState.SAVING_RAW) return@synchronized file
            val capture = recipe(id)
            val previous = File(previousThumbnailDirectory, "$id.jpg")
            if (capture.color.getFloat(96) == 0f && com.unspektrawesome.vulkan.params.RawMosaic(capture.raw).blockSize == 1 && previous.isFile) {
                previous.copyTo(file)
            } else if (!shot.hasRaw && shot.jpegUri != null) {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(shot.jpegUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                options.inJustDecodeBounds = false
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 256).coerceAtLeast(1)
                val bitmap = resolver.openInputStream(shot.jpegUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                    ?: error("Exported thumbnail is unavailable")
                try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) } }
                finally { bitmap.recycle() }
            } else DeferredCaptureWriter(applicationContext).writeQueueThumbnail(capture, readPixels(shot), file)
        }
        file
    }

    @Synchronized fun beginBatch(ids: List<String>) {
        database.beginTransaction()
        try {
            database.insertWithOnConflict("processing_batch", null, ContentValues().apply {
                put("id", 1); put("ids", org.json.JSONArray(ids).toString()); put("running", 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            ids.forEach { id -> database.update("shots", ContentValues().apply { put("attempts", 0) }, "id = ?", arrayOf(id)) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        reload()
    }

    @Synchronized fun batch(): ExportBatch? = database.query("processing_batch", arrayOf("ids", "running"), "id = 1", null, null, null, null).use {
        if (!it.moveToFirst()) return@use null
        val ids = org.json.JSONArray(it.getString(0))
        ExportBatch(List(ids.length()) { index -> ids.getString(index) }, it.getInt(1) != 0)
    }

    @Synchronized fun setBatchRunning(running: Boolean) {
        database.update("processing_batch", ContentValues().apply { put("running", if (running) 1 else 0) }, "id = 1", null)
    }

    @Synchronized fun cancelWaiting(ids: Set<String>? = null) {
        val cancelled = shots.value.filter { (ids == null || it.id in ids) && it.state in setOf(ExportState.WAITING, ExportState.FAILED) }
        if (ids == null) setBatchRunning(false)
        try {
            cancelled.forEach {
                val size = if (it.hasRaw && it.rawBytes > 0) persistQueueRecord(it.copy(state = ExportState.SKIPPED), required = true) else null
                database.update("shots", ContentValues().apply {
                    put("state", ExportState.SKIPPED.name); put("detail", if (it.panorama) "Panorama RAWs kept for later export." else "Export cancelled. Original DNG kept for later use.")
                    size?.let { put("raw_bytes", it) }
                }, "id = ?", arrayOf(it.id))
            }
        } finally { reload() }
    }

    @Synchronized fun clearHistory(ids: Set<String>) {
        val activeIds = batch()?.takeIf { it.running }?.ids.orEmpty().toSet()
        val hidden = shots.value.filter { it.id in ids && it.id !in activeIds &&
            it.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.RAW_REMOVED) &&
            it.rawRetention != RawRetention.DELETING }
        try {
            hidden.forEach { shot ->
                val size = if (shot.hasRaw && shot.rawBytes > 0) persistQueueRecord(shot.copy(historyHidden = true), required = true) else null
                database.update("shots", ContentValues().apply {
                    put("history_hidden", 1)
                    size?.let { put("raw_bytes", it) }
                }, "id = ?", arrayOf(shot.id))
            }
        } finally { reload() }
    }

    @Synchronized fun jpegDeleted(id: String) {
        database.update("shots", ContentValues().apply { putNull("jpeg_uri") }, "id = ?", arrayOf(id))
        reload()
    }

    @Synchronized fun keepRawAfterExport(id: String, detail: String) {
        val shot = shots.value.firstOrNull { it.id == id } ?: return
        if (shot.state == ExportState.EXPORTED && shot.hasRaw) update(id, ExportState.EXPORTED, detail)
    }

    @Synchronized fun deleteVerifiedRaw(id: String, jpegUri: Uri) {
        val shot = shots.value.firstOrNull { it.id == id } ?: error("Capture is unavailable")
        check(!shot.panorama) { "Panorama RAWs are kept for later export." }
        val capture = recipe(id)
        val displayName = "${com.unspektrawesome.capture.PhotoFileNames.baseName(capture.capturedAtMillis, capture.metadata.utcOffsetSeconds)}.dng"
        check(shot.state == ExportState.EXPORTED && shot.jpegUri == jpegUri && shot.hasRaw && shot.rawUri != jpegUri) {
            "Capture is not ready for RAW cleanup"
        }
        check(shots.value.count { it.rawUri == shot.rawUri } == 1) { "Original RAW is used by another capture" }
        check(shot.rawUri.scheme == "content" && shot.rawUri.authority == MediaStore.AUTHORITY) { "Original is outside photo storage" }
        val owned = resolver.query(shot.rawUri, arrayOf(MediaStore.Images.Media.OWNER_PACKAGE_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.IS_TRASHED), null, null, null)?.use {
            if (!it.moveToFirst()) {
                setRawRetention(id, RawRetention.DELETED, "")
                return
            }
            it.getString(0) == applicationContext.packageName &&
                it.getString(1) == "DCIM/Unspektrawesome/RAW/" && it.getString(2) == "image/x-adobe-dng" &&
                it.getString(3) in setOf("Unspektrawesome-$id.dng", displayName) && it.getInt(4) == 0 && it.getInt(5) == 0
        } == true
        check(owned) { "Original RAW ownership could not be verified" }
        setRawRetention(id, RawRetention.DELETING, "Verifying RAW cleanup")
        var deleted = false
        try {
            check(resolver.delete(shot.rawUri, null, null) == 1) { "Android could not remove the original RAW" }
            deleted = true
            setRawRetention(id, RawRetention.DELETED, "")
        } catch (error: Exception) {
            if (!deleted) setRawRetention(id, RawRetention.KEPT, "RAW kept: cleanup did not finish.")
            throw error
        }
    }

    @Synchronized private fun setRawRetention(id: String, retention: RawRetention, detail: String) {
        database.update("shots", ContentValues().apply {
            put("raw_retention", retention.name)
            put("detail", detail)
            if (retention == RawRetention.DELETED) put("raw_bytes", 0)
        }, "id = ?", arrayOf(id))
        reload()
    }

    @Synchronized private fun reload() {
        mutableShots.value = database.query("shots", arrayOf("id", "raw_uri", "jpeg_uri", "captured_at", "width", "height", "lens", "look", "state", "detail", "raw_bytes", "attempts", "raw_retention", "history_hidden", "panorama", "grid"),
            null, null, null, null, "captured_at DESC, id DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(GalleryShot(cursor.getString(0), Uri.parse(cursor.getString(1)),
                    cursor.getString(2)?.let(Uri::parse), cursor.getLong(3), cursor.getInt(4), cursor.getInt(5),
                    cursor.getString(6), cursor.getString(7), ExportState.valueOf(cursor.getString(8)), cursor.getString(9), cursor.getLong(10), cursor.getInt(11),
                    RawRetention.valueOf(cursor.getString(12)), cursor.getInt(13) != 0, cursor.getInt(14) != 0, cursor.getInt(15) != 0))
            }
        }
    }

    companion object {
        @Volatile private var instance: GalleryStore? = null
        fun get(context: Context): GalleryStore = instance ?: synchronized(this) {
            instance ?: GalleryStore(context.applicationContext).also { instance = it }
        }
    }
}
