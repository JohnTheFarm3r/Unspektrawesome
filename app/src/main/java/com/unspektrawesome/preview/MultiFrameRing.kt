package com.unspektrawesome.preview

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import com.unspektrawesome.burst.BurstBufferPool
import com.unspektrawesome.burst.RawFrameCompression
import com.unspektrawesome.camera.RawFormat
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.session.RawCaptureMetadata
import com.unspektrawesome.camera.session.RawHardwareFrame
import com.unspektrawesome.diagnostics.InternalLogRecorder
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Keeps copies of the newest RAW preview frames so a photo can merge the frames before the shutter press. */
internal class MultiFrameRing(context: Context) : AutoCloseable {
    data class Settings(val enabled: Boolean, val frames: Int) {
        companion object { val DISABLED = Settings(false, 0) }
    }

    class Frame internal constructor(
        private val storage: BurstBufferPool.Lease,
        val metadata: RawCaptureMetadata,
        val output: RawOutput,
        val rowBytes: Int,
        val captureResult: CaptureResult?,
    ) {
        val pixels: ByteBuffer get() = storage.pixels
        val receivedAtMs: Long = SystemClock.elapsedRealtime()
        private var references = 1

        @Synchronized internal fun retain(): Boolean {
            if (references == 0) return false
            references++
            return true
        }

        @Synchronized fun release() {
            if (references > 0 && --references == 0) storage.close()
        }

        fun candidate() = MultiFrameSelection.Candidate(
            metadata.sensorTimestampNs, metadata.exposureTimeNs, metadata.sensitivityIso, metadata.focusDistanceDiopters,
            output.size.width, output.size.height, rowBytes, output.format.ordinal,
        )
    }

    private val context = context.applicationContext
    private val copier = Executors.newSingleThreadExecutor { Thread(it, "merge-frames") }
    private val buffers = BurstBufferPool(0)
    private val lock = Any()
    private val ring = ArrayDeque<Frame>()
    private val pending = AtomicInteger()
    private val droppedFrames = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var capacity = 0
    private var frameBytes = 0L
    private var requestedFrames = 0

    val dropped: Long get() = droppedFrames.get()
    val buffered: Int get() = synchronized(lock) { ring.size }

    fun offer(frame: RawHardwareFrame, settings: Settings) {
        if (closed) return
        if (!settings.enabled || settings.frames < MultiFrameSelection.MIN_FRAMES) {
            if (buffered > 0) reset()
            return
        }
        val width = frame.output.size.width
        if (width % 2 != 0 || frame.output.format == RawFormat.RAW10 && width % 4 != 0) return
        if (pending.get() >= 2) { droppedFrames.incrementAndGet(); return }
        val lease = frame.retain() ?: return
        val token = generation
        pending.incrementAndGet()
        try {
            copier.execute {
                try { copy(lease.frame, settings, token) }
                catch (error: Throwable) { InternalLogRecorder.error("MultiFrame", "copy_failed", error) }
                finally { lease.close(); pending.decrementAndGet() }
            }
        } catch (error: RejectedExecutionException) {
            lease.close()
            pending.decrementAndGet()
        }
    }

    private fun copy(frame: RawHardwareFrame, settings: Settings, token: Long) {
        val output = frame.output
        val width = output.size.width
        val height = output.size.height
        val rowBytes = packedRowBytes(width, output.format)
        val size = rowBytes.toLong() * height
        if (size > Int.MAX_VALUE) return
        val storage = synchronized(lock) {
            if (closed || token != generation) return
            plan(size, settings.frames)
            if (capacity < MultiFrameSelection.MIN_FRAMES) return
            buffers.acquire(size.toInt())
        } ?: run { droppedFrames.incrementAndGet(); return }
        val entry = try {
            RawFrameCompression.copy(frame.packedRawBytes, storage.pixels, width, height, frame.rowStrideBytes, output.format.ordinal)
            Frame(storage, frame.metadata, output, rowBytes, frame.captureResult)
        } catch (error: Throwable) {
            storage.close()
            throw error
        }
        synchronized(lock) {
            if (closed || token != generation) { entry.release(); return }
            val last = ring.lastOrNull()
            if (last != null && (last.output != output || last.rowBytes != rowBytes ||
                    last.metadata.sensorTimestampNs >= entry.metadata.sensorTimestampNs)) clearLocked()
            ring.addLast(entry)
            while (ring.size > capacity) ring.removeFirst().release()
        }
    }

    /** Sizes the ring from the requested frame count and the memory the device can spare. */
    private fun plan(size: Long, frames: Int) {
        val requested = frames.coerceIn(MultiFrameSelection.MIN_FRAMES, MultiFrameSelection.MAX_FRAMES)
        if (size == frameBytes && requested == requestedFrames) return
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val limit = minOf(info.totalMem * 3 / 10, (info.availMem + buffers.allocatedBytes) / 2).coerceAtLeast(0)
        // The pool holds the ring twice over so frames retained by a running merge do not starve new frames.
        val affordable = (limit / (size * 2)).coerceAtMost(requested.toLong()).toInt()
        capacity = if (affordable >= MultiFrameSelection.MIN_FRAMES) affordable else 0
        frameBytes = size
        requestedFrames = requested
        buffers.resize(size * (capacity * 2 + 2))
        while (ring.size > capacity) ring.removeFirst().release()
        InternalLogRecorder.event("MultiFrame", "ring frames=$requested capacity=$capacity frame_bytes=$size limit_bytes=$limit")
    }

    /** Waits briefly for frames that are still being copied, so the newest frame is the one shown at the shutter press. */
    fun awaitPendingCopies(timeoutMs: Long) {
        if (closed || pending.get() == 0) return
        try { copier.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    /** Returns up to [maximum] newest frames, newest first. Each frame must be released by the caller. */
    fun snapshot(maximum: Int): List<Frame> = synchronized(lock) {
        val frames = ring.takeLast(maximum.coerceAtLeast(0)).asReversed()
        frames.filter { it.retain() }
    }

    fun reset() {
        synchronized(lock) {
            generation++
            clearLocked()
            buffers.reset()
        }
    }

    private fun clearLocked() {
        ring.forEach { it.release() }
        ring.clear()
    }

    override fun close() {
        closed = true
        reset()
        copier.shutdown()
        synchronized(lock) { buffers.close() }
    }

    companion object {
        fun packedRowBytes(width: Int, format: RawFormat): Int = when (format) {
            RawFormat.RAW_SENSOR -> width * 2
            RawFormat.RAW10 -> (width + 3) / 4 * 5
            RawFormat.RAW12 -> (width + 1) / 2 * 3
        }
    }
}
