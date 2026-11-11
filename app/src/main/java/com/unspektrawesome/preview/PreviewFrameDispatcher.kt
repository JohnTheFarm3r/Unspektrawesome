package com.unspektrawesome.preview

import com.unspektrawesome.camera.session.RawFrameConsumer
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal class PreviewFrameDispatcher(
    private val executor: Executor,
    private val queueLatestFrame: Boolean = true,
    private val allocateRecordingBuffer: (Int) -> Buffer? = { Buffer(ByteBuffer.allocateDirect(it)) },
) {
    class Buffer(val bytes: ByteBuffer, private val release: () -> Unit = {}) : AutoCloseable {
        private var closed = false
        val capacity get() = bytes.capacity()
        override fun close() { if (!closed) { closed = true; release() } }
    }
    private data class Frame(val buffer: Buffer?, val render: () -> Unit, val release: () -> Unit = {},
        val charge: Long = 0, val generation: Long = 0, val cameraLease: Boolean = false)
    data class BufferUsage(val usedBytes: Long, val capacityBytes: Long, val frameCapacity: Int)
    private val lock = Any()
    @Volatile private var scheduled = false
    private val pending = ArrayDeque<Frame>()
    private val spares = ArrayDeque<Buffer>()
    private var recording = false
    private var generation = 0L
    private var recordingCapacity = 0L
    private var recordingFrameBytes = 0
    private var recordingUsed = 0L
    val bufferUsage: BufferUsage get() = synchronized(lock) {
        BufferUsage(recordingUsed, recordingCapacity, if (recordingFrameBytes > 0)
            (recordingCapacity / recordingFrameBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else RawFrameConsumer.MAX_QUEUED_FRAMES)
    }
    private var accepting = true
    private var replaced = 0L
    private var closed = false
    val busy: Boolean get() = scheduled
    val canAcceptFrame: Boolean get() = synchronized(lock) { canAcceptLocked() }
    val replacedFrames: Long get() = synchronized(lock) { replaced }
    val preserveFrameOrder: Boolean get() = synchronized(lock) { recording && !closed }
    val pendingFrames: Int get() = synchronized(lock) { pending.size }
    val diagnosticState: String get() = synchronized(lock) {
        "queued=${pending.size} camera_queued=${pending.count { it.cameraLease }} raw_used=$recordingUsed raw_capacity=$recordingCapacity " +
            "lease_limit=${RawFrameConsumer.MAX_QUEUED_FRAMES} accepting=$accepting"
    }

    private fun rejectionReasonLocked() = when {
        closed -> "dispatcher_closed"
        !accepting -> "recording_stopping"
        recording && recordingCapacity > 0 -> "raw_buffer_full"
        recording -> "raw_queue_full"
        else -> "preview_busy"
    }

    private fun canAcceptLocked() = !closed && accepting && when {
        recording && recordingCapacity > 0 -> recordingUsed + recordingFrameBytes <= recordingCapacity
        recording -> pending.size < RawFrameConsumer.MAX_QUEUED_FRAMES
        else -> queueLatestFrame || !scheduled
    }

    fun setRecording(enabled: Boolean, capacityBytes: Long = 0, frameBytes: Int = 0) = synchronized(lock) {
        require(capacityBytes >= 0 && (capacityBytes == 0L || frameBytes > 0 && capacityBytes >= frameBytes))
        clearPending()
        while (spares.isNotEmpty()) spares.removeFirst().close()
        generation++
        recordingUsed = 0
        recordingCapacity = if (enabled) capacityBytes else 0
        recordingFrameBytes = if (enabled) frameBytes else 0
        recording = enabled
        accepting = !closed
    }

    fun stopAcceptingFrames() = synchronized(lock) { accepting = false }

    // Run on the rendering executor after stopping submissions, before closing the encoder.
    fun drainPending() {
        check(synchronized(lock) { !accepting })
        try {
            while (true) {
                val frame = synchronized(lock) { pending.removeFirstOrNull() } ?: return
                try { frame.render() } finally { synchronized(lock) { recycle(frame) } }
            }
        } finally { clearPending() }
    }

    fun submit(source: ByteBuffer, onRejected: ((String) -> Unit)? = null, render: (ByteBuffer) -> Unit): Boolean {
        val start = synchronized(lock) {
            if (!canAcceptLocked()) { onRejected?.invoke(rejectionReasonLocked()); return false }
            replacePendingPreview()
            val input = source.duplicate().apply { clear() }
            val spare = spares.indexOfFirst { it.capacity == input.remaining() }
            val charge = if (recordingCapacity > 0) input.remaining().toLong() else 0L
            if (recordingCapacity > 0 && recordingUsed + charge > recordingCapacity) {
                onRejected?.invoke("raw_buffer_full"); return false
            }
            val buffer = if (spare >= 0) spares.removeAt(spare) else {
                trimSpares(charge)
                try {
                    if (recordingCapacity > 0) allocateRecordingBuffer(input.remaining()) ?: run {
                        onRejected?.invoke("raw_allocation_failed"); return false
                    }
                    else Buffer(ByteBuffer.allocateDirect(input.remaining()))
                } catch (_: OutOfMemoryError) { onRejected?.invoke("raw_out_of_memory"); return false }
            }
            try {
                val bytes = buffer.bytes
                bytes.clear()
                bytes.order(source.order())
                bytes.put(input).flip()
                pending.addLast(Frame(buffer, { render(bytes) }, charge = charge, generation = generation))
                recordingUsed += charge
            } catch (error: Throwable) { buffer.close(); throw error }
            (!scheduled).also { scheduled = true }
        }
        if (start && !scheduleNext()) { onRejected?.invoke("executor_closed"); return false }
        return true
    }

    fun <T : AutoCloseable> submitLease(create: () -> T?, onRejected: ((String) -> Unit)? = null,
                                      retainsCameraImage: Boolean = true,
                                      render: (T) -> Unit): Boolean {
        val start = synchronized(lock) {
            if (!canAcceptLocked()) { onRejected?.invoke(rejectionReasonLocked()); return false }
            // Retain only a few camera images; copy deeper backlogs into the recording pool.
            if (recording && retainsCameraImage && pending.count { it.cameraLease } >= RawFrameConsumer.MAX_QUEUED_FRAMES) {
                onRejected?.invoke("retained_frame_limit"); return false
            }
            replacePendingPreview()
            val charge = if (recordingCapacity > 0) recordingFrameBytes.toLong() else 0L
            trimSpares(charge)
            val lease = create() ?: run { onRejected?.invoke("lease_unavailable"); return false }
            recordingUsed += charge
            pending.addLast(Frame(null, { render(lease) }, lease::close, charge, generation, retainsCameraImage))
            (!scheduled).also { scheduled = true }
        }
        if (start && !scheduleNext()) { onRejected?.invoke("executor_closed"); return false }
        return true
    }

    private fun replacePendingPreview() {
        if (!recording) {
            replaced += pending.size
            clearPending()
        }
    }

    private fun trimSpares(additional: Long = 0) {
        if (recordingCapacity <= 0) return
        var available = recordingCapacity - recordingUsed - additional
        for (buffer in spares) available -= buffer.capacity
        while (available < 0 && spares.isNotEmpty()) {
            val spare = spares.removeFirst()
            available += spare.capacity
            spare.close()
        }
    }

    private fun recycle(frame: Frame) {
        frame.release()
        if (frame.generation != generation) { frame.buffer?.close(); return }
        recordingUsed -= frame.charge
        if (closed) { frame.buffer?.close(); return }
        if (frame.buffer != null) {
            spares.addLast(frame.buffer)
            if (recordingCapacity > 0) trimSpares()
            else {
                val limit = if (recording) RawFrameConsumer.MAX_QUEUED_FRAMES + 1 else 1
                while (spares.size > limit) spares.removeFirst().close()
            }
        }
    }

    fun clearPending() = synchronized(lock) {
        while (pending.isNotEmpty()) recycle(pending.removeFirst())
    }

    fun close() = synchronized(lock) {
        closed = true
        accepting = false
        clearPending()
        while (spares.isNotEmpty()) spares.removeFirst().close()
    }

    private fun scheduleNext(): Boolean = try {
        executor.execute {
            val frame = synchronized(lock) {
                pending.removeFirstOrNull().also {
                    if (it == null) scheduled = false
                }
            } ?: return@execute
            try {
                frame.render()
            } finally {
                val again = synchronized(lock) {
                    recycle(frame)
                    pending.isNotEmpty().also { scheduled = it }
                }
                // Yield between frames so surface and lens changes can use the same executor.
                if (again) scheduleNext()
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        synchronized(lock) {
            clearPending()
            scheduled = false
        }
        false
    }
}
