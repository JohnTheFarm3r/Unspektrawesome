package com.unspektrawesome.burst

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.unspektrawesome.camera.session.RawHardwareFrame
import com.unspektrawesome.preview.PersistedCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class BurstStatus(val enabled: Boolean = false, val buffered: Int = 0, val busy: Boolean = false,
    val captured: Int = 0, val dropped: Int = 0, val message: String? = null, val saving: Int = 0,
    val queueFull: Boolean = false, val preShutterSeconds: Int = 1, val memoryBytes: Long = 0, val memoryLimited: Boolean = false) {
    val ready: Boolean get() = enabled && buffered > 0 && !busy && !queueFull
}

internal data class BurstBufferWork(val copiedFrames: Long, val compressedFrames: Long, val allocatedBytes: Long)

internal class BurstRecorder(context: Context, private val onWindowClosed: () -> Unit = {},
    private val onSaved: (Result<Uri>) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "burst-frames") }
    // Waiting frames are only copied, so two copy threads are plenty.
    private val encoders = Executors.newFixedThreadPool(2) { Thread(it, "burst-encode") }
    // Saved frames are compressed on at most two cores so a save does not heat the phone or slow the viewfinder.
    private val compressors = Executors.newFixedThreadPool(2) { Thread(it, "burst-compress") }
    private val inputLock = Any()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "burst-storage") }
    private val thumbnails = Executors.newSingleThreadExecutor { Thread(it, "burst-thumbnail") }
    private var thumbnailRenderer: BurstRendering? = null
    private val mutableStatus = MutableStateFlow(BurstStatus())
    val status = mutableStatus.asStateFlow()
    private val inFlight = AtomicInteger()
    private val allocated = AtomicLong()
    private val busy = AtomicBoolean()
    private val pendingSaves = AtomicInteger()
    private val generation = AtomicLong()
    private val copiedFrames = AtomicLong()
    private val compressedFrames = AtomicLong()
    private val ring = ArrayDeque<BurstFrame>()
    private val ringBytes: Long get() = ring.sumOf { it.bytes }
    private var recording: Saving? = null
    @Volatile private var lastTimestamp = 0L
    @Volatile private var closed = false
    private val memoryLock = Any()
    @Volatile private var seconds = 1
    @Volatile private var plannedSeconds = 0
    @Volatile private var frameBytes = 0L
    @Volatile private var memory = BurstMemoryPlan(0, 0, true)
    // Compressed size as a fraction of the 16-bit samples, learned from encoded frames.
    @Volatile private var compressedRatio = 0.55
    @Volatile private var sampleBytes = 0L
    private var plannedCompressedBytes = 0L
    private val buffers = BurstBufferPool(0)
    private val statistics = Statistics()
    val work: BurstBufferWork get() = BurstBufferWork(copiedFrames.get(), compressedFrames.get(), allocated.get() + buffers.allocatedBytes)

    fun configureHistory(value: Int) {
        if (closed || seconds == value.coerceIn(1, BurstMemoryPlan.MAX_SECONDS)) return
        seconds = value.coerceIn(1, BurstMemoryPlan.MAX_SECONDS)
        if (frameBytes > 0) updateMemory(frameBytes, sampleBytes)
        synchronized(inputLock) {
            if (!closed) worker.execute {
                trimHistory()
                mutableStatus.update { it.copy(buffered = ring.size) }
                publishMemoryState()
            }
        }
        publishMemoryState()
    }

    private fun trimHistory() {
        val latest = ring.lastOrNull()?.timestamp ?: return
        while (ring.size > 1 && (ring.size > seconds * BurstMemoryPlan.FPS || ringBytes > memory.historyBytes ||
            latest - ring.first().timestamp >= seconds * SECOND)) {
            val old = ring.removeFirst()
            old.close()
        }
    }

    private fun compressedEstimate() = (sampleBytes * compressedRatio).toLong().coerceAtLeast(1)

    private fun learnCompression(encodedBytes: Long) {
        if (sampleBytes <= 0) return
        compressedRatio = (compressedRatio * 0.8 + encodedBytes.toDouble() / sampleBytes * 0.2).coerceIn(0.05, 1.0)
    }

    private fun updateMemory(bytes: Long, samples: Long) = synchronized(memoryLock) {
        if (samples != sampleBytes) { sampleBytes = samples; compressedRatio = 0.55 }
        val compressed = compressedEstimate()
        val drifted = kotlin.math.abs(compressed - plannedCompressedBytes) > plannedCompressedBytes / 10
        if (bytes == frameBytes && seconds == plannedSeconds && !drifted) return@synchronized
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val available = info.availMem + allocated.get() + buffers.allocatedBytes
        val plan = BurstMemoryPlan.calculate(seconds, bytes, available, info.totalMem, compressed)
        memory = plan
        frameBytes = bytes
        plannedSeconds = seconds
        plannedCompressedBytes = compressed
        buffers.resize(plan.limitBytes)
        com.unspektrawesome.diagnostics.InternalLogRecorder.event("Burst", "plan seconds=$seconds frame_bytes=$bytes " +
            "compressed_bytes=$compressed full_history=${plan.fullHistory} limit_mb=${plan.limitBytes / MB} history_mb=${plan.historyBytes / MB} " +
            "available_mb=${info.availMem / MB} total_mb=${info.totalMem / MB}")
    }

    private fun publishMemoryState() {
        val pending = pendingSaves.get()
        val spare = memory.limitBytes - allocated.get() - buffers.inUseBytes
        val full = pending >= BurstMemoryPlan.MAX_SAVES || pending > 0 && spare < frameBytes * (BurstMemoryPlan.FPS + 4)
        mutableStatus.update { it.copy(saving = pending, queueFull = full, preShutterSeconds = seconds,
            memoryBytes = memory.limitBytes, memoryLimited = !memory.fullHistory) }
    }

    fun setEnabled(enabled: Boolean) {
        if (closed) return
        plannedSeconds = 0
        mutableStatus.update { it.copy(enabled = enabled, message = null) }
        reset()
    }

    fun reset(): Unit = synchronized(inputLock) {
        if (closed) return
        generation.incrementAndGet()
        lastTimestamp = 0
        worker.execute {
            finish()
            ring.forEach { it.close() }; ring.clear()
            buffers.reset()
            if (inFlight.get() == 0) RawFrameCompression.releaseScratch()
            mutableStatus.update { it.copy(buffered = 0) }
        }
    }

    fun offer(frame: RawHardwareFrame, capture: () -> PersistedCapture?) {
        val token = generation.get()
        if (closed || !status.value.enabled || frame.metadata.sensorTimestampNs - lastTimestamp < 30_000_000L) return
        if (inFlight.get() >= 4) {
            mutableStatus.update { it.copy(dropped = it.dropped + 1) }
            statistics.busy()
            return
        }
        val recipe = capture() ?: return
        val lease = frame.retain() ?: return
        synchronized(inputLock) {
            if (closed || token != generation.get()) { lease.close(); return }
            lastTimestamp = frame.metadata.sensorTimestampNs
            inFlight.incrementAndGet()
            val encodedFrame = java.util.concurrent.CompletableFuture<BurstFrame?>()
            encoders.execute {
                try {
                    val started = android.os.SystemClock.elapsedRealtimeNanos()
                    val retained = keepFrame(frame, recipe)
                    statistics.kept(retained != null, android.os.SystemClock.elapsedRealtimeNanos() - started)
                    if (retained == null) mutableStatus.update { it.copy(dropped = it.dropped + 1) }
                    encodedFrame.complete(retained)
                } catch (error: Throwable) { encodedFrame.completeExceptionally(error) }
                finally { lease.close() }
            }
            worker.execute {
                try {
                    val encoded = encodedFrame.get() ?: return@execute
                    if (closed || token != generation.get() || !status.value.enabled) { encoded.close(); return@execute }
                    ring.addLast(encoded)
                    trimHistory()
                    recording?.let { save ->
                        if (encoded.timestamp > save.anchor && encoded.timestamp <= save.anchor + SECOND && save.count < save.maximumFrames) {
                            save.append(encoded.retain())
                            mutableStatus.update { it.copy(captured = save.count) }
                        }
                        if (encoded.timestamp >= save.anchor + SECOND || save.count >= save.maximumFrames) finish()
                    }
                    mutableStatus.update { it.copy(buffered = ring.size) }
                    statistics.report(ring.size, ringBytes, memory)
                } catch (error: Exception) {
                    com.unspektrawesome.diagnostics.InternalLogRecorder.error("Burst", "buffer_failed", error)
                    mutableStatus.update { it.copy(message = "Unable to buffer BURST") }
                } finally { inFlight.decrementAndGet(); publishMemoryState() }
            }
        }
    }

    private fun keepFrame(frame: RawHardwareFrame, recipe: PersistedCapture): BurstFrame? {
        val width = recipe.raw.getInt(0)
        val height = recipe.raw.getInt(4)
        val format = recipe.raw.getInt(20)
        val rowBytes = when (format) { 1 -> width / 4 * 5; 2 -> width / 2 * 3; else -> width * 2 }
        val size = rowBytes.toLong() * height
        updateMemory(size, width.toLong() * height * 2)
        // Waiting frames are only copied. Compressing every frame while waiting keeps several cores busy.
        val input = synchronized(memoryLock) {
            if (allocated.get() + buffers.inUseBytes + size > memory.limitBytes) null else buffers.acquire(size.toInt())
        } ?: return null
        try {
            RawFrameCompression.copy(frame.packedRawBytes, input.pixels, width, height, frame.rowStrideBytes, format)
            copiedFrames.incrementAndGet()
            return BurstFrame(input, recipe, rowBytes, onEncoded = {
                allocated.addAndGet(it)
                learnCompression(it)
                compressedFrames.incrementAndGet()
            }, onReleased = { allocated.addAndGet(-it) })
        } catch (error: Throwable) { input.close(); throw error }
    }

    fun capture(): Boolean = synchronized(inputLock) {
        if (closed || !status.value.ready || !busy.compareAndSet(false, true)) return false
        pendingSaves.incrementAndGet()
        val anchor = lastTimestamp
        val capturedAt = System.currentTimeMillis()
        val historySeconds = seconds
        mutableStatus.update { it.copy(busy = true, captured = 0, message = null) }
        publishMemoryState()
        worker.execute {
            try {
                check(ring.isNotEmpty())
                val previous = ring.filter { it.timestamp <= anchor && it.timestamp > anchor - historySeconds * SECOND }
                    .takeLast(historySeconds * BurstMemoryPlan.FPS)
                check(previous.isNotEmpty())
                check(StatFs(context.filesDir.absolutePath).availableBytes > ringBytes * 2 + 128L * 1024 * 1024)
                val save = Saving(anchor, capturedAt, previous.last().retain(), (historySeconds + 1) * BurstMemoryPlan.FPS)
                recording = save
                previous.forEach { save.append(it.retain()) }
                mutableStatus.update { it.copy(captured = save.count) }
                com.unspektrawesome.diagnostics.InternalLogRecorder.event("Burst", "capture history_frames=${previous.size} " +
                    "expected=${historySeconds * BurstMemoryPlan.FPS} history_ms=${(previous.last().timestamp - previous.first().timestamp) / 1_000_000} " +
                    "full_history=${memory.fullHistory} pending=${pendingSaves.get()}")
                worker.schedule({ if (recording === save) finish() }, 1500, TimeUnit.MILLISECONDS)
            } catch (error: Exception) { endWindow(); completed(Result.failure(error)) }
        }
        true
    }

    private fun finish() {
        val save = recording ?: return
        recording = null
        com.unspektrawesome.diagnostics.InternalLogRecorder.event("Burst", "window_closed frames=${save.count} maximum=${save.maximumFrames}")
        save.finish()
        endWindow()
    }

    private fun endWindow() {
        busy.set(false)
        mutableStatus.update { it.copy(busy = false) }
        publishMemoryState()
        onWindowClosed()
    }

    private fun completed(result: Result<Uri>) {
        if (pendingSaves.decrementAndGet() == 0) {
            buffers.trimUnused(frameBytes * 4)
            // Rows are only expanded while saving, so the expansion buffers are not needed until the next save.
            RawFrameCompression.releaseScratch()
        }
        publishMemoryState()
        mutableStatus.update { it.copy(message = if (result.isSuccess) null else "Unable to save BURST") }
        onSaved(result)
    }

    private inner class Saving(val anchor: Long, capturedAt: Long, private val thumbnail: BurstFrame, val maximumFrames: Int) {
        private var output: BurstContainer.Writer? = null
        private var failure: Throwable? = null
        private var thumbnailPixels: ByteArray? = null
        var count = 0
            private set
        init { writer.execute { runCatching { output = BurstContainer.Writer(context, capturedAt) }.onFailure { failure = it } } }
        fun append(frame: BurstFrame) {
            count++
            val prepared = java.util.concurrent.CompletableFuture<Unit>()
            compressors.execute {
                try {
                    frame.prepare()
                    buffers.trimUnused(frameBytes * 4)
                    prepared.complete(Unit)
                } catch (error: Throwable) { prepared.completeExceptionally(error) }
                finally { publishMemoryState() }
            }
            writer.execute {
                frame.use {
                    runCatching {
                        prepared.get()
                        if (failure != null) return@runCatching
                        frame.withEncoded { handle ->
                            requireNotNull(output).append(frame.capture, handle)
                            if (frame === thumbnail) {
                                val source = RawFrameCompression.payload(handle, true)
                                thumbnailPixels = ByteArray(source.remaining()).also { source.get(it) }
                            }
                        }
                    }.onFailure { failure = it }
                }
            }
        }
        fun finish() { writer.execute { thumbnails.execute {
            val result = runCatching {
                failure?.let { throw it }
                val rendering = thumbnailRenderer ?: BurstRendering(context).also { thumbnailRenderer = it }
                val bitmap = rendering.render(thumbnail.capture, requireNotNull(thumbnailPixels), true)
                val jpeg = try { ByteArrayOutputStream().also {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it))
                }.toByteArray() } finally { bitmap.recycle() }
                requireNotNull(output).finish(jpeg, thumbnail.capture.width, thumbnail.capture.height)
            }
            runCatching { output?.close() }
            thumbnail.close()
            completed(result)
        } } }
    }

    override fun close(): Unit = synchronized(inputLock) {
        if (closed) return
        closed = true
        encoders.shutdown()
        generation.incrementAndGet()
        worker.execute {
            finish()
            ring.forEach { it.close() }; ring.clear()
            buffers.close()
            compressors.execute { RawFrameCompression.releaseScratch() }
            compressors.shutdown()
            writer.execute {
                thumbnails.execute { thumbnailRenderer?.close(); thumbnailRenderer = null }
                thumbnails.shutdown()
            }
            writer.shutdown()
        }
        worker.shutdown()
    }

    /** Summarizes buffering every few seconds so diagnostic reports show whether frames keep up. */
    private class Statistics {
        private val kept = AtomicInteger()
        private val rejected = AtomicInteger()
        private val busy = AtomicInteger()
        private val workNs = AtomicLong()
        private val slowestNs = AtomicLong()
        private var windowStart = android.os.SystemClock.elapsedRealtime()

        fun kept(success: Boolean, elapsedNs: Long) {
            (if (success) kept else rejected).incrementAndGet()
            workNs.addAndGet(elapsedNs)
            slowestNs.accumulateAndGet(elapsedNs, ::maxOf)
        }

        fun busy() { busy.incrementAndGet() }

        fun report(buffered: Int, ringBytes: Long, plan: BurstMemoryPlan) {
            val now = android.os.SystemClock.elapsedRealtime()
            val window = now - windowStart
            if (window < 5_000) return
            windowStart = now
            val keptFrames = kept.getAndSet(0)
            val rejectedFrames = rejected.getAndSet(0)
            val busyFrames = busy.getAndSet(0)
            val handled = (keptFrames + rejectedFrames).coerceAtLeast(1)
            com.unspektrawesome.diagnostics.InternalLogRecorder.event("Burst", "buffer window_ms=$window " +
                "kept_fps=${"%.1f".format(java.util.Locale.ROOT, keptFrames * 1000.0 / window)} " +
                "memory_drops=$rejectedFrames busy_drops=$busyFrames buffered=$buffered ring_mb=${ringBytes / MB} " +
                "history_mb=${plan.historyBytes / MB} full_history=${plan.fullHistory} " +
                "frame_ms=${"%.1f".format(java.util.Locale.ROOT, workNs.getAndSet(0) / 1e6 / handled)} " +
                "slowest_ms=${"%.1f".format(java.util.Locale.ROOT, slowestNs.getAndSet(0) / 1e6)}")
        }
    }

    companion object {
        private const val SECOND = 1_000_000_000L
        private const val MB = 1024L * 1024
    }
}
