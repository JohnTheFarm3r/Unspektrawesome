package com.unspektrawesome.burst

import com.unspektrawesome.preview.PersistedCapture
import java.util.concurrent.atomic.AtomicInteger

internal class BurstFrame private constructor(handle: Long, val capture: PersistedCapture, bytes: Long,
    private var input: BurstBufferPool.Lease?, private val stride: Int,
    private val onEncoded: (Long) -> Unit, private val onReleased: (Long) -> Unit) : AutoCloseable {
    constructor(handle: Long, capture: PersistedCapture, onReleased: (Long) -> Unit) :
        this(handle, capture, RawFrameCompression.payload(handle, false).remaining().toLong(), null, 0, {}, onReleased)

    constructor(input: BurstBufferPool.Lease, capture: PersistedCapture, stride: Int,
        onEncoded: (Long) -> Unit = {}, onReleased: (Long) -> Unit = {}) :
        this(0, capture, input.pixels.capacity().toLong(), input, stride, onEncoded, onReleased)

    @Volatile var handle = handle
        private set
    @Volatile var bytes = bytes
        private set

    val timestamp = capture.metadata.sensorTimestampNs
    private val references = AtomicInteger(1)

    @Synchronized fun prepare() {
        check(references.get() > 0)
        val source = input ?: return
        val encoded = RawFrameCompression.encode(source.pixels, capture.raw.getInt(0), capture.raw.getInt(4),
            stride, capture.raw.getInt(20), capture.raw.getInt(64))
        val size = try {
            RawFrameCompression.payload(encoded, false).remaining().toLong().also(onEncoded)
        } catch (error: Throwable) { RawFrameCompression.release(encoded); throw error }
        handle = encoded
        bytes = size
        input = null
        source.close()
    }
    @Synchronized fun <T> withEncoded(action: (Long) -> T): T {
        prepare()
        return action(handle)
    }
    fun retain(): BurstFrame {
        check(references.incrementAndGet() > 1)
        return this
    }
    override fun close() {
        if (references.decrementAndGet() == 0) synchronized(this) {
            if (input != null) input!!.close() else {
                RawFrameCompression.release(handle)
                onReleased(bytes)
            }
        }
    }
}
