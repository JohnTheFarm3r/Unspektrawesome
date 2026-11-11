package com.unspektrawesome.burst

import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal class BurstBufferPool(private var limit: Long) : AutoCloseable {
    private val free = ArrayDeque<Storage>()
    private var generation = 0L
    private var closed = false
    @Volatile var allocatedBytes = 0L
        private set
    val inUseBytes: Long @Synchronized get() = allocatedBytes - free.sumOf { it.pixels.capacity().toLong() }

    @Synchronized fun resize(bytes: Long) {
        limit = bytes.coerceAtLeast(0)
        while (free.isNotEmpty() && allocatedBytes > limit) dispose(free.removeFirst())
    }

    @Synchronized fun trimUnused(bytes: Long) {
        var unused = free.sumOf { it.pixels.capacity().toLong() }
        while (free.isNotEmpty() && unused > bytes) {
            val storage = free.removeFirst()
            unused -= storage.pixels.capacity()
            dispose(storage)
        }
    }

    @Synchronized fun acquire(size: Int): Lease? {
        require(size > 0)
        if (closed) return null
        while (free.isNotEmpty()) {
            val storage = free.removeFirst()
            if (storage.pixels.capacity() == size) return Lease(storage, ::recycle)
            dispose(storage)
        }
        if (allocatedBytes + size > limit) return null
        val memory = SharedMemory.create("burst-history", size)
        val pixels = try { memory.mapReadWrite().order(ByteOrder.nativeOrder()) }
            catch (error: Throwable) { memory.close(); throw error }
        allocatedBytes += size
        return Lease(Storage(memory, pixels, generation), ::recycle)
    }

    @Synchronized fun reset() {
        generation++
        free.forEach(::dispose)
        free.clear()
    }

    @Synchronized override fun close() {
        closed = true
        reset()
    }

    @Synchronized private fun recycle(storage: Storage) {
        if (closed || storage.generation != generation || allocatedBytes > limit) dispose(storage)
        else free.addLast(storage)
    }

    private fun dispose(storage: Storage) {
        allocatedBytes -= storage.pixels.capacity()
        try { SharedMemory.unmap(storage.pixels) } finally { storage.memory.close() }
    }

    internal class Storage(val memory: SharedMemory, val pixels: ByteBuffer, val generation: Long)

    internal class Lease internal constructor(private val storage: Storage, private val release: (Storage) -> Unit) : AutoCloseable {
        val pixels: ByteBuffer get() = storage.pixels
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release(storage) }
    }
}
