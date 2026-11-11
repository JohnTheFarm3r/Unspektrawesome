package com.unspektrawesome.panorama

import android.content.Context
import android.graphics.Bitmap
import android.os.StatFs
import com.unspektrawesome.burst.RawFrameCompression
import com.unspektrawesome.camera.session.RawHardwareFrame
import com.unspektrawesome.gallery.GalleryStore
import com.unspektrawesome.preview.PersistedCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

internal class PanoramaRecorder(private val context: Context, private val preview: (Int, Int) -> PanoramaPreviewFrame?, private val stopped: () -> Unit,
    private val saved: (Result<Pair<String, Boolean>>) -> Unit) : AutoCloseable {
    private val preferences = context.getSharedPreferences("panorama", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(PanoramaState(exportLater = preferences.getBoolean("export_later", true),
        projection = PanoramaProjection.entries.firstOrNull { it.name == preferences.getString("projection", null) } ?: PanoramaProjection.CYLINDRICAL))
    val state = mutableState.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PanoramaCapture") }
    private val inFlight = AtomicBoolean(false)
    @Volatile private var accepting = false
    @Volatile private var closed = false
    @Volatile private var lastSample = 0L
    @Volatile private var lastDiagnostic = 0L
    private var project: PanoramaProject? = null
    private var mosaic: PanoramaMosaic? = null
    private var previous: Bitmap? = null
    private var transform = PanoramaTransform()
    private var lastAccepted = PanoramaTransform()
    private var frozen: PersistedCapture? = null
    private var axis = 0
    private var direction = 0
    private var failures = 0
    private var startedAt = 0L
    private var lastPreviewTimestamp = 0L
    private var foreground = false
    private val gridMotion = GridCaptureMotion(context) { grid ->
        mutableState.update { if (it.grid != null) it.copy(grid = grid.copy(rotation = GRID_IDENTITY, hold = 0f, motion = it.grid.motion)) else it }
    }

    fun setEnabled(enabled: Boolean, grid: Boolean = false) {
        if (state.value.busy || closed) return
        mutableState.update { it.copy(enabled = enabled, guide = PanoramaGuide.READY, preview = null, panels = emptyList(), frames = 0,
            grid = if (enabled && grid) gridMotion.readings.value.copy(motion = gridMotion.readings) else null,
            exportLater = if (enabled) preferences.getBoolean(if (grid) "grid_export_later" else "export_later", true) else it.exportLater) }
        gridMotion.setActive(enabled && foreground)
    }

    fun setForeground(visible: Boolean) { foreground = visible; gridMotion.setActive(visible && state.value.enabled) }
    fun configureGrid(displayRotation: Int, front: Boolean) { if (!state.value.busy) gridMotion.configure(displayRotation, front) }
    fun configureGridImage(projection: GridCaptureProjection) {
        if (!state.value.busy) gridMotion.configureImage(projection.width, projection.height, projection.focal)
    }
    fun configureGridAimRadius(radius: Double) { gridMotion.configureAimRadius(radius) }

    fun setExportLater(later: Boolean) {
        if (state.value.busy) return
        preferences.edit().putBoolean(if (state.value.grid != null) "grid_export_later" else "export_later", later).apply()
        mutableState.update { it.copy(exportLater = later) }
    }

    fun setProjection(projection: PanoramaProjection) {
        if (state.value.busy || !state.value.enabled || state.value.grid != null || closed) return
        preferences.edit().putString("projection", projection.name).apply()
        mutableState.update { it.copy(projection = projection, preview = null, frames = 0) }
    }

    fun start(): Boolean {
        if (closed || !state.value.enabled || state.value.busy) return false
        if (state.value.grid != null && !gridMotion.start()) return false
        if (state.value.grid == null) gridMotion.start()
        mutableState.update { it.copy(recording = true, frames = 0, preview = null, panels = emptyList(), guide = PanoramaGuide.START, progress = 0f, drift = 0f, direction = 0) }
        lastSample = 0L
        accepting = true
        worker.execute {
            project = null; previous?.recycle(); previous = null; mosaic?.close(); mosaic = null
            frozen = null
            transform = if (state.value.grid == null && state.value.projection == PanoramaProjection.PLANE)
                PanoramaTransform.projective(GRID_IDENTITY, plane = true) else PanoramaTransform()
            lastAccepted = transform; axis = 0; direction = 0; failures = 0; lastPreviewTimestamp = 0
            startedAt = android.os.SystemClock.elapsedRealtime()
        }
        return true
    }

    fun needsFrame(timestamp: Long): Boolean = accepting && !inFlight.get() && timestamp - lastSample >= 200_000_000L

    fun offer(frame: RawHardwareFrame, capture: () -> PersistedCapture?) {
        if (!needsFrame(frame.metadata.sensorTimestampNs) || !inFlight.compareAndSet(false, true)) return
        val recipe = try { capture() } catch (error: Exception) {
            accepting = false
            inFlight.set(false)
            worker.execute {
                com.unspektrawesome.diagnostics.InternalLogRecorder.error("Panorama", "capture_preparation_failed", error)
                finish(error)
            }
            return
        }
        if (recipe == null) { inFlight.set(false); return }
        val lease = frame.retain()
        if (lease == null) { inFlight.set(false); return }
        lastSample = frame.metadata.sensorTimestampNs
        worker.execute {
            try {
                if (!closed) process(lease.frame, recipe)
            } catch (error: Throwable) {
                com.unspektrawesome.diagnostics.InternalLogRecorder.error("Panorama", "capture_failed", error)
                accepting = false
                finish(error)
            } finally { lease.close(); inFlight.set(false) }
        }
    }

    private fun process(frame: RawHardwareFrame, recipe: PersistedCapture) {
        val locked = frozen
        if (locked != null && !panoramaExposureMatches(locked.metadata.sensitivityIso ?: 0, locked.metadata.exposureTimeNs ?: 0,
                recipe.metadata.sensitivityIso, recipe.metadata.exposureTimeNs, settled = true)) return
        val capture = if (locked == null) recipe else recipe.copy(color = locked.color, film = locked.film, shading = locked.shading,
            timeSeconds = locked.timeSeconds, capturedAtMillis = locked.capturedAtMillis, metadata = locked.metadata)
        if (locked != null) capture.raw.putInt(28, locked.raw.getInt(28))
        val grid = state.value.grid != null
        val plane = !grid && state.value.projection == PanoramaProjection.PLANE
        val projection = if (grid) gridCaptureProjection(capture.raw.getInt(0), capture.raw.getInt(4), capture.width, capture.height,
            capture.metadata.equivalentFocalLengthMm?.toDouble() ?: 26.0) else null
        val scale = (if (grid) 1024.0 else 512.0) / max(capture.width, capture.height)
        val width = projection?.width ?: (capture.width * scale).roundToInt().coerceAtLeast(2)
        val height = projection?.height ?: (capture.height * scale).roundToInt().coerceAtLeast(2)
        val focal = project?.focal ?: projection?.focal ?: panoramaFocalPixels(capture.raw.getInt(0), capture.raw.getInt(4), capture.width, width,
            capture.metadata.equivalentFocalLengthMm?.toDouble() ?: 26.0).coerceAtLeast(width * .35)
        val gridPose = if (grid) {
            gridMotion.configureImage(width, height, focal)
            gridMotion.pose(frame.metadata.sensorTimestampNs) ?: return
        } else null
        val snapshot = preview(width, height) ?: run { rejected(frame.metadata.sensorTimestampNs, "preview_unavailable"); return }
        val candidate = snapshot.bitmap
        var retained = false
        try {
            if (snapshot.timestamp <= lastPreviewTimestamp || abs(snapshot.timestamp - frame.metadata.sensorTimestampNs) > 150_000_000L) {
                rejected(frame.metadata.sensorTimestampNs, "preview_age=${snapshot.timestamp - frame.metadata.sensorTimestampNs}"); return
            }
            lastPreviewTimestamp = snapshot.timestamp
            val rotation = gridMotion.exposureRotation(frame.metadata.sensorTimestampNs, frame.metadata.exposureTimeNs ?: 0L)
            if (panoramaMotionTooFast(rotation, focal)) {
                rejected(frame.metadata.sensorTimestampNs, "motion_preview_pixels=${rotation!! * focal}")
                mutableState.update { it.copy(guide = PanoramaGuide.SLOW) }
                return
            }
            if (grid || previous == null) mutableState.update { if (it.guide == PanoramaGuide.SLOW) it.copy(guide = PanoramaGuide.START) else it }
            val old = previous
            var accepted = old == null || grid
            if (gridPose != null) transform = gridHomography(gridPose.rotation, candidate.width, candidate.height, focal)
            if (old != null && !grid) {
                val local = PanoramaNative.align(old, candidate, requireNotNull(project).focal, plane)
                if (local == null) {
                    failures++
                    mutableState.update { it.copy(guide = if (failures > 3) PanoramaGuide.RETURN else PanoramaGuide.DETAIL) }
                    return
                }
                failures = 0
                val localTransform = if (plane) PanoramaTransform.projective(local.toList(), plane = true)
                    else PanoramaTransform(local[0], local[1], local[2], local[3])
                val next = transform.then(localTransform)
                val center = localTransform.point(candidate.width * .5, candidate.height * .5)
                val dx = if (plane) center.first - candidate.width * .5 else next.x - lastAccepted.x
                val dy = if (plane) center.second - candidate.height * .5 else next.y - lastAccepted.y
                if (axis == 0 && max(abs(dx) / candidate.width, abs(dy) / candidate.height) >= .12) {
                    axis = if (abs(dx) / candidate.width >= abs(dy) / candidate.height) 1 else 2
                    direction = if ((if (axis == 1) dx else dy) > 0) axis else -axis
                }
                val along = if (axis == 1) dx / candidate.width else dy / candidate.height
                val across = if (axis == 1) dy / candidate.height else dx / candidate.width
                val backwards = axis != 0 && along * direction < -.03
                val global = next.point(candidate.width * .5, candidate.height * .5)
                val drift = if (plane) {
                    if (axis == 1) (global.second - candidate.height * .5) / candidate.height else (global.first - candidate.width * .5) / candidate.width
                } else if (axis == 1) next.y / candidate.height else next.x / candidate.width
                val tangent = localTransform.point(candidate.width * .5 + 1, candidate.height * .5)
                val roll = if (plane) atan2(tangent.second - center.second, tangent.first - center.first) else atan2(next.b, next.a)
                val level = abs(across) < .16 && abs(roll) < .14
                accepted = axis != 0 && !backwards && level && abs(along) >= .22
                mutableState.update { it.copy(horizontal = axis != 2, direction = direction.sign,
                    progress = (abs(along) / .22).toFloat().coerceIn(0f, 1f), drift = drift.toFloat(), guide = when {
                        backwards -> PanoramaGuide.RETURN
                        !level -> PanoramaGuide.LEVEL
                        abs(along) > .48 -> PanoramaGuide.SLOW
                        direction == -1 -> PanoramaGuide.LEFT
                        direction == 1 -> PanoramaGuide.RIGHT
                        direction == -2 -> PanoramaGuide.UP
                        direction == 2 -> PanoramaGuide.DOWN
                        else -> PanoramaGuide.START
                    }) }
                if (abs(along) > .6 || !level || backwards) return
                if (plane && accepted && !panoramaPlaneLayout(requireNotNull(project).frames.map { it.transform } + next,
                        candidate.width, candidate.height, focal).fits) {
                    mutableState.update { it.copy(guide = PanoramaGuide.LIMIT, progress = 1f) }
                    return
                }
                if (accepted) transform = next
            }
            if (!accepted) return
            if (!grid) { previous?.recycle(); previous = candidate; retained = true }
            if (project == null) {
                frozen = capture
                project = PanoramaProject.create(context, candidate.width, candidate.height, focal, grid,
                    if (plane) PanoramaProjection.PLANE else PanoramaProjection.CYLINDRICAL)
                if (!grid) mosaic = PanoramaMosaic(requireNotNull(project).directory, focal)
            }
            val destination = requireNotNull(project)
            val available = StatFs(context.filesDir.absolutePath).availableBytes
            require(available > capture.raw.getInt(0).toLong() * capture.raw.getInt(4) * 3 + 256L * 1024 * 1024) { "Storage is nearly full. Saved panorama frames are kept." }
            val name = "${destination.frames.size}.raw"
            val handle = RawFrameCompression.encode(frame.packedRawBytes, capture.raw.getInt(0), capture.raw.getInt(4), frame.rowStrideBytes,
                capture.raw.getInt(20), com.unspektrawesome.vulkan.params.RawMosaic(capture.raw).blockSize)
            try {
                val temporary = File(destination.directory, "$name.tmp")
                FileOutputStream(temporary).use { output ->
                    val payload = RawFrameCompression.payload(handle, false).duplicate()
                    while (payload.hasRemaining()) output.channel.write(payload)
                    output.fd.sync()
                }
                check(temporary.renameTo(File(destination.directory, name)))
            } finally { RawFrameCompression.release(handle) }
            destination.frames.add(PanoramaFrame(name, capture, transform))
            destination.checkpoint()
            lastAccepted = transform
            if (gridPose != null) {
                val rotation = gridMotion.rotationAt(snapshot.timestamp) ?: gridPose.rotation
                val panel = GridCapturedPanel(candidate, rotation)
                if (destination.frames.size == 1) destination.preview(candidate)
                retained = true
                mutableState.update { it.copy(panels = it.panels + panel, frames = destination.frames.size, progress = 0f) }
            } else {
                val combined = requireNotNull(mosaic).append(candidate, transform, if (destination.frames.size == 1) 0 else direction)
                destination.preview(combined)
                mutableState.update { it.copy(preview = combined, frames = destination.frames.size, progress = 0f) }
            }
            if (gridPose != null) gridMotion.captured(gridPose.target)
            if (panoramaCaptureComplete(grid, destination.frames.size, transform, destination.focal,
                    android.os.SystemClock.elapsedRealtime() - startedAt)) { accepting = false; finish() }
        } finally { if (!retained) candidate.recycle() }
    }

    fun stop() {
        if (!accepting) return
        accepting = false
        mutableState.update { it.copy(recording = false, finishing = true, guide = PanoramaGuide.SAVING) }
        worker.execute { finish() }
    }

    fun rejectControls(frame: RawHardwareFrame, expectedIso: Int, expectedExposureNs: Long?) {
        if (frame.metadata.sensorTimestampNs - lastDiagnostic < 2_000_000_000L) return
        rejected(frame.metadata.sensorTimestampNs, "controls locked=${frame.metadata.panoramaControlsLocked} " +
            "iso=${frame.metadata.sensitivityIso} exposure=${frame.metadata.exposureTimeNs} " +
            "expected_iso=$expectedIso expected_exposure=$expectedExposureNs")
    }

    private fun rejected(timestamp: Long, reason: String) {
        if (timestamp - lastDiagnostic < 2_000_000_000L) return
        lastDiagnostic = timestamp
        com.unspektrawesome.diagnostics.InternalLogRecorder.event("Panorama", "sample_rejected sensor_ns=$timestamp reason=$reason")
    }

    private fun finish(failure: Throwable? = null) {
        if (!state.value.busy && project == null) return
        mutableState.update { if (it.recording) it.copy(recording = false, finishing = true, guide = PanoramaGuide.SAVING) else it }
        gridMotion.stop()
        val destination = project
        project = null
        previous?.recycle(); previous = null; mosaic?.close(); mosaic = null
        val result = runCatching {
            if (destination == null || destination.frames.size < 2) {
                destination?.directory?.listFiles()?.forEach { it.delete() }; destination?.directory?.delete()
                return@runCatching null
            }
            destination.complete = true
            destination.checkpoint()
            GalleryStore.get(context).insertPanorama(destination)
            destination.id to state.value.exportLater
        }
        mutableState.update { it.copy(recording = false, finishing = false, guide = when {
            result.isFailure || failure != null -> PanoramaGuide.FAILED
            result.getOrNull() == null -> PanoramaGuide.SHORT
            else -> PanoramaGuide.SAVED
        }) }
        stopped()
        result.getOrNull()?.let { saved(Result.success(it)) }
        result.exceptionOrNull()?.let { saved(Result.failure(it)) }
        if (failure != null && result.isSuccess) saved(Result.failure(failure))
    }

    override fun close() {
        if (closed) return
        accepting = false
        closed = true
        gridMotion.close()
        worker.execute { finish() }
        worker.shutdown()
    }
}
