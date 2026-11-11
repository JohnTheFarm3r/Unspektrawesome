package com.unspektrawesome.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

internal class BufferedRawCaptureQueue(
    private val executor: ExecutorService,
    private val onChanged: () -> Unit,
    private val byteBudget: Long = 2L * 1024 * 1024 * 1024,
    private val capacity: Int = 20,
    private val allocate: (Int) -> Storage = { Storage(ByteBuffer.allocateDirect(it).order(ByteOrder.nativeOrder())) },
) : AutoCloseable {
    class Storage(val pixels: ByteBuffer, private val release: () -> Unit = {}) : AutoCloseable {
        override fun close() = release()
    }
    class Entry internal constructor(val pixels: ByteBuffer) {
        internal val work = CompletableFuture<(() -> Unit)?>()
        fun save(action: () -> Unit) { check(work.complete(action)) { "RAW capture was already completed" } }
        fun cancel() { work.complete(null) }
    }

    private val lock = Any()
    private val available = ArrayDeque<Storage>()
    private var reservedBytes = 0L
    private var closed = false
    @Volatile var pendingShots = 0
        private set

    fun isFullFor(bytes: Int): Boolean = synchronized(lock) {
        closed || pendingShots >= capacity || pendingShots > 0 && reservedBytes + bytes > byteBudget
    }

    fun reserve(bytes: Int): Entry? {
        require(bytes > 0)
        val entry = synchronized(lock) {
            if (isFullFor(bytes)) return null
            val index = available.indexOfFirst { it.pixels.capacity() == bytes }
            val reused = if (index >= 0) available.removeAt(index) else null
            if (reused == null) {
                available.forEach { it.close() }
                available.clear()
            }
            val buffer = reused ?: allocate(bytes)
            Entry(buffer.pixels).also {
                reservedBytes += bytes
                pendingShots++
                try {
                    executor.execute {
                        try {
                            it.work.get()?.invoke()
                        } finally {
                            synchronized(lock) {
                                reservedBytes -= bytes
                                pendingShots--
                                if (!closed && available.size < 2) available.addLast(buffer) else buffer.close()
                            }
                            onChanged()
                        }
                    }
                } catch (error: Throwable) {
                    reservedBytes -= bytes
                    pendingShots--
                    buffer.close()
                    throw error
                }
            }
        }
        onChanged()
        return entry
    }

    override fun close() = synchronized(lock) {
        closed = true
        available.forEach { it.close() }
        available.clear()
        executor.shutdown()
    }
}
