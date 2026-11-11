package com.unspektrawesome.gallery

import android.app.Notification
import com.unspektrawesome.language.AppLanguage
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.unspektrawesome.R
import com.unspektrawesome.UnspektrawesomeApplication
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.diagnostics.ImageDiagnostics
import com.unspektrawesome.capture.JpegMediaStoreWriter
import com.unspektrawesome.preview.PersistedCapture
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.vulkan.VulkanRenderer
import com.unspektrawesome.vulkan.params.GeometryShaderParameters
import com.unspektrawesome.vulkan.params.RawColorShaderParameters
import com.unspektrawesome.vulkan.params.RawShaderParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

internal data class ProcessingStatus(
    val running: Boolean = false,
    val pauseRequested: Boolean = false,
    val waitingForCooling: Boolean = false,
    val currentId: String? = null,
    val stage: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val message: String = "Start when you are ready. Every photo keeps its capture settings.",
)

private const val HEAT_PAUSE_MESSAGE = "Heat limit reached. Exporting will resume automatically once your phone cools. Waiting RAWs are kept."

internal object GalleryProcessing {
    val state = MutableStateFlow(ProcessingStatus())
    @Volatile var cameraVisible = false
    fun pause(context: Context? = null) {
        InternalLogRecorder.event("GalleryExport", "pause_requested running=${state.value.running}")
        context?.let { GalleryStore.get(it).setBatchRunning(false) }
        state.update { it.copy(pauseRequested = it.running, message = if (it.running && !it.waitingForCooling) "Finishing the current photo, then pausing." else "Processing paused. Your photos are kept.") }
    }
    fun start(context: Context, ids: List<String> = emptyList(), resume: Boolean = false, rawAccess: List<android.net.Uri> = emptyList()) {
        InternalLogRecorder.event("GalleryExport", "start_requested count=${ids.size} resume=$resume running=${state.value.running}")
        if (state.value.running) return
        runCatching {
            val intent = Intent(context, GalleryProcessingService::class.java)
                .putExtra("ids", ids.toTypedArray()).putExtra("resume", resume)
            val sharedRaws = rawAccess.filter { it.scheme == "content" }
            if (sharedRaws.isNotEmpty() && !android.os.Environment.isExternalStorageManager()) {
                intent.clipData = android.content.ClipData.newRawUri("Original DNG", sharedRaws.first()).apply {
                    sharedRaws.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            InternalLogRecorder.error("GalleryExport", "service_start_failed", it)
            state.value = ProcessingStatus(message = "Unable to start exporting: ${it.message.orEmpty()}")
        }
    }
}

class GalleryProcessingService : Service() {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        task.run()
    }, "UnspektrawesomeGalleryExport") }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        InternalLogRecorder.event("GalleryExport", "service_start startId=$startId restored=${intent == null} action=${intent?.action}")
        if (intent?.action == "pause") {
            GalleryProcessing.pause(this)
            if (!GalleryProcessing.state.value.running) stopSelf()
            return START_NOT_STICKY
        }
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, AppLanguage.text("Photo processing"), NotificationManager.IMPORTANCE_LOW))
        try {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION, notification(ProcessingStatus(running = true, message = "Preparing exports")), type)
        } catch (error: RuntimeException) {
            InternalLogRecorder.error("GalleryExport", "foreground_start_failed", error)
            GalleryProcessing.state.value = ProcessingStatus(message = "Android could not start processing. ${error.message.orEmpty()}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (GalleryProcessing.state.value.running) return START_STICKY
        GalleryProcessing.state.value = ProcessingStatus(running = true)
        val requested = intent?.getStringArrayExtra("ids")?.toSet().orEmpty()
        val resume = intent == null || intent.getBooleanExtra("resume", false)
        worker.execute { process(requested, resume) }
        return START_STICKY
    }

    private fun process(requested: Set<String>, resume: Boolean) {
        InternalLogRecorder.resources("gallery_export_start")
        var renderer: VulkanRenderer? = null
        val power = getSystemService(PowerManager::class.java)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:gallery-export")
        var endMessage = "Exports finished. Review your photos in Gallery."
        try {
            wakeLock.acquire(6 * 60 * 60 * 1000L)
            val store = GalleryStore.get(this)
            fun waitUntilReady(id: String): Boolean {
                while (true) {
                    val pause = when {
                        GalleryProcessing.state.value.pauseRequested || store.batch()?.running != true -> "Processing paused. Waiting photos and RAWs are safe."
                        GalleryProcessing.cameraVisible -> "Paused for the camera. Start exporting again when you have finished shooting."
                        else -> null
                    }
                    if (pause != null) {
                        store.setBatchRunning(false)
                        endMessage = pause
                        return false
                    }
                    if (store.shots.value.none { it.id == id && it.state in setOf(ExportState.WAITING, ExportState.FAILED) }) return true
                    if (!GalleryThermalState.read(this).blocksExport) {
                        if (GalleryProcessing.state.value.waitingForCooling) {
                            InternalLogRecorder.event("GalleryExport", "thermal_resume")
                            GalleryProcessing.state.update { it.copy(waitingForCooling = false, stage = "Resuming exports", message = "Resuming exports") }
                            notifications.notify(NOTIFICATION, notification(GalleryProcessing.state.value))
                        }
                        return true
                    }
                    if (!GalleryProcessing.state.value.waitingForCooling) {
                        InternalLogRecorder.event("GalleryExport", "thermal_pause")
                        runCatching { renderer?.close() }; renderer = null
                        GalleryProcessing.state.update { it.copy(waitingForCooling = true, currentId = null,
                            stage = "Waiting for phone to cool", message = HEAT_PAUSE_MESSAGE) }
                        notifications.notify(NOTIFICATION, notification(GalleryProcessing.state.value))
                    }
                    Thread.sleep(1000)
                }
            }
            val selected = store.shots.value.filter {
                if (requested.isEmpty()) it.state == ExportState.WAITING
                else it.id in requested && it.state in setOf(ExportState.WAITING, ExportState.FAILED, ExportState.SKIPPED)
            }.sortedBy { it.capturedAt }
            if (!resume) {
                selected.filter { it.state == ExportState.SKIPPED }.forEach { store.update(it.id, ExportState.WAITING) }
                store.beginBatch(selected.map { it.id })
            }
            val batch = store.batch()
            if (batch == null || !batch.running) { endMessage = "Processing is paused. Your queue is kept."; return }
            val jobs = batch.ids.mapNotNull { id -> store.shots.value.firstOrNull { it.id == id } }
            GalleryProcessing.state.update { it.copy(total = batch.ids.size,
                completed = jobs.count { job -> job.state == ExportState.EXPORTED }) }
            val writer = JpegMediaStoreWriter(contentResolver, getSharedPreferences("gallery-export-receipts", MODE_PRIVATE))
            val cleanupPreferences = RawCleanupPreferences(this)
            val cleanup = RawExportCleanup(this)
            for (job in jobs) {
                if (job.state in setOf(ExportState.EXPORTED, ExportState.SKIPPED, ExportState.RAW_REMOVED)) continue
                if (!waitUntilReady(job.id)) return
                var attempts = job.attempts
                if (attempts >= MAX_ATTEMPTS) {
                    if (store.beginAttempt(job.id, attempts)) {
                        val saved = writer.findSavedCapture(job.id)
                        if (saved != null) {
                            store.update(job.id, ExportState.EXPORTED, jpegUri = saved)
                            store.keepRawAfterExport(job.id, "RAW kept: this JPEG was recovered after an interrupted export.")
                            writer.forgetCapture(job.id)
                            GalleryProcessing.state.update { it.copy(completed = it.completed + 1) }
                        } else {
                            store.update(job.id, ExportState.FAILED, "Export was interrupted repeatedly. Retry when you are ready. Original DNG kept.")
                        }
                    }
                    continue
                }
                while (attempts < MAX_ATTEMPTS) {
                    if (!waitUntilReady(job.id)) return
                    if (store.shots.value.none { it.id == job.id && it.state in setOf(ExportState.WAITING, ExportState.FAILED) }) break
                    try {
                        val handled = synchronized(PersistedCapture.exportLock) {
                            if (GalleryProcessing.cameraVisible || GalleryProcessing.state.value.pauseRequested ||
                                store.batch()?.running != true || GalleryThermalState.read(this).blocksExport) return@synchronized false
                            if (!store.beginAttempt(job.id, attempts + 1)) return@synchronized true
                            attempts++
                            val cleanupAuthorization = cleanupPreferences.authorization()
                            InternalLogRecorder.event("GalleryExport", "attempt id=${job.id} number=$attempts")
                            val priority = ProcessingPriority.from(getSharedPreferences("gallery", MODE_PRIVATE).getString("priority", null))
                            android.os.Process.setThreadPriority(priority.cpuPriority)
                            stage(job.id, "Reading original RAW")
                            val capture = store.recipe(job.id)
                            val alreadySaved = writer.findSavedCapture(job.id)
                            val uri = alreadySaved ?: run {
                                if (job.panorama) return@run com.unspektrawesome.panorama.PanoramaExporter(this).export(job.rawUri, writer,
                                    checkpoint = {
                                        if (GalleryProcessing.cameraVisible || GalleryProcessing.state.value.pauseRequested || store.batch()?.running != true ||
                                            GalleryThermalState.read(this).blocksExport) throw com.unspektrawesome.panorama.PanoramaExportPaused()
                                    }, stage = { current, total -> stage(job.id, AppLanguage.format("Stitching panorama · {current} of {total}",
                                        mapOf("current" to current, "total" to total))) })
                                validate(capture)
                                DeferredCaptureWriter.requireSpace(this, capture.width.toLong() * capture.height * 4)
                                val pixels = store.readPixels(job)
                                ImageDiagnostics.input(job.id, capture, pixels)
                                stage(job.id, if (capture.metadata.spektra?.enabled == true) "Processing spektrafilm" else "Processing RAW")
                                val processor = renderer ?: (application as UnspektrawesomeApplication).shaderWarmup
                                    .takeRenderer(forExport = true).also { renderer = it }
                                val bitmap = android.graphics.Bitmap.createBitmap(capture.width, capture.height, android.graphics.Bitmap.Config.ARGB_8888)
                                try {
                                    check(processor.captureAmazeToBitmap(pixels, capture.raw, capture.color, capture.geometry,
                                        capture.film, capture.shading, bitmap, capture.timeSeconds, priority.gpuPauseMillis)) { processor.diagnostics() }
                                    ImageDiagnostics.output(job.id, bitmap)
                                    InternalLogRecorder.event("GalleryExport", "render_complete id=${job.id} diagnostic=${processor.diagnostics()}")
                                    stage(job.id, "Saving JPEG")
                                    writer.write(bitmap, capture.metadata, capturedAtMillis = capture.capturedAtMillis, captureId = job.id)
                                } finally { bitmap.recycle() }
                            }
                            store.update(job.id, ExportState.EXPORTED, jpegUri = uri)
                            if (!job.panorama && alreadySaved == null && cleanupAuthorization != null && !capture.saveOriginalDng) {
                                stage(job.id, "Verifying JPEG before RAW cleanup")
                                cleanup.afterExport(job.id, cleanupAuthorization)
                            } else if (alreadySaved != null) {
                                store.keepRawAfterExport(job.id, "RAW kept: this JPEG was recovered after an interrupted export.")
                            }
                            InternalLogRecorder.event("GalleryExport", "completed id=${job.id} recovered=${alreadySaved != null}")
                            writer.forgetCapture(job.id)
                            GalleryProcessing.state.update { it.copy(completed = it.completed + 1) }
                            true
                        }
                        if (!handled) continue
                        if (GalleryProcessing.cameraVisible || GalleryProcessing.state.value.pauseRequested) {
                            store.setBatchRunning(false)
                            endMessage = "Processing paused. Completed JPEGs and waiting RAWs are kept."
                            return
                        }
                        break
                    } catch (_: com.unspektrawesome.panorama.PanoramaExportPaused) {
                        attempts = (attempts - 1).coerceAtLeast(0)
                        store.update(job.id, ExportState.WAITING, attempts = attempts)
                        if (!GalleryProcessing.cameraVisible && !GalleryProcessing.state.value.pauseRequested && store.batch()?.running == true) continue
                        store.setBatchRunning(false)
                        endMessage = "Panorama export paused. RAWs are kept."
                        return
                    } catch (error: Exception) {
                        InternalLogRecorder.error("GalleryExport", "failed id=${job.id} attempt=$attempts stage=${GalleryProcessing.state.value.stage}", error)
                        store.update(job.id, ExportState.FAILED, error.message ?: error.javaClass.simpleName)
                        runCatching { renderer?.close() }; renderer = null
                        if (attempts < MAX_ATTEMPTS) {
                            stage(job.id, "Retrying · attempt ${attempts + 1} of $MAX_ATTEMPTS")
                            repeat(attempts * 20) { if (!GalleryProcessing.state.value.pauseRequested && !GalleryProcessing.cameraVisible) Thread.sleep(100) }
                        }
                    } catch (error: OutOfMemoryError) {
                        InternalLogRecorder.error("GalleryExport", "out_of_memory id=${job.id}", error)
                        store.update(job.id, ExportState.FAILED, "Not enough memory. Close other apps and retry.")
                        endMessage = "Paused because memory is low. RAWs for unfinished exports are kept."
                        store.setBatchRunning(false)
                        return
                    }
                }
            }
            store.setBatchRunning(false)
            if (store.shots.value.any { it.id in batch.ids && it.state == ExportState.FAILED })
                endMessage = "Some photos need a retry. Completed JPEGs and RAWs for failed exports are kept."
        } catch (error: Exception) {
            InternalLogRecorder.error("GalleryExport", "batch_failed", error)
            endMessage = "Processing paused: ${error.message.orEmpty()}"
        } finally {
            InternalLogRecorder.event("GalleryExport", "batch_finished message=$endMessage")
            runCatching { renderer?.close() }
            if (wakeLock.isHeld) wakeLock.release()
            GalleryProcessing.state.update { it.copy(running = false, pauseRequested = false, waitingForCooling = false,
                currentId = null, stage = "", message = endMessage) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stage(id: String, label: String) {
        InternalLogRecorder.event("GalleryExport", "stage id=$id $label")
        GalleryProcessing.state.update { it.copy(currentId = id, stage = label) }
        notifications.notify(NOTIFICATION, notification(GalleryProcessing.state.value))
    }

    private fun notification(status: ProcessingStatus): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, GalleryActivity::class.java).putExtra("processing", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, GalleryProcessingService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detailed = getSharedPreferences("gallery", MODE_PRIVATE).getBoolean("progress_notifications", true)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_gallery)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, AppLanguage.text(if (status.waitingForCooling) "Stop automatic resume" else "Pause after photo"), pause).build())
        if (status.waitingForCooling) return builder.setContentTitle(AppLanguage.text("Photo exports paused for heat"))
            .setContentText(AppLanguage.text("Will resume automatically when your phone cools"))
            .setStyle(Notification.BigTextStyle().bigText(AppLanguage.text(HEAT_PAUSE_MESSAGE)))
            .setProgress(status.total.coerceAtLeast(1), status.completed, false).build()
        if (!detailed) return builder.setContentTitle(AppLanguage.text("Photo processing is running")).setContentText(AppLanguage.text("Open Gallery to see progress")).build()
        val shot = status.currentId?.let { id -> GalleryStore.get(this).shots.value.firstOrNull { it.id == id } }
        if (shot != null) {
            builder.setStyle(Notification.BigTextStyle().bigText("${AppLanguage.text(status.stage)}\n${shot.lens}\n${shot.width} × ${shot.height} · ${shot.look}"))
            android.graphics.BitmapFactory.decodeFile(GalleryStore.get(this).thumbnail(shot.id).absolutePath)?.let { builder.setLargeIcon(it) }
        }
        return builder
            .setContentTitle(AppLanguage.format("spektrafilm · {completed} of {total} exported", mapOf("completed" to (status.completed), "total" to (status.total))))
            .setContentText(AppLanguage.text(status.stage.ifEmpty { status.message })).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(status.total.coerceAtLeast(1), status.completed, status.total == 0)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        GalleryProcessing.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (GalleryProcessing.state.value.running) GalleryProcessing.pause()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gallery-processing"
        private const val NOTIFICATION = 601
        private const val MAX_ATTEMPTS = 3
        internal fun validate(capture: PersistedCapture) {
            require(capture.width > 0 && capture.height > 0 && capture.width.toLong() * capture.height * 4 <= Int.MAX_VALUE)
            require(capture.raw.remaining() == RawShaderParameters.SIZE_BYTES && capture.color.remaining() == RawColorShaderParameters.SIZE_BYTES &&
                capture.geometry.remaining() == GeometryShaderParameters.SIZE_BYTES && capture.film.remaining() == SpektraFilmParams.STRUCT_SIZE) {
                "Saved processing settings are incompatible with this version"
            }
            require(capture.raw.getInt(20) == 0 && capture.raw.getInt(16) == capture.raw.getInt(0) * 2) { "Invalid saved DNG raster layout" }
        }
    }
}
