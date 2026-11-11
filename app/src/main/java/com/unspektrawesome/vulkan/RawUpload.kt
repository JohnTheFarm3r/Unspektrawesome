package com.unspektrawesome.vulkan

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class RawUploadPool(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun copy(source: ByteBuffer, rowLayoutBytes: Long): RawUpload? {
        require(source.isDirect) { "Packed RAW bytes must be direct" }
        if (handle == 0L || rowLayoutBytes <= 0) return null
        return nativeCopy(handle, source, rowLayoutBytes).takeIf { it != 0L }?.let(::RawUpload)
    }

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeRelease(current)
    }

    private external fun nativeCopy(handle: Long, source: ByteBuffer, rowLayoutBytes: Long): Long
    private external fun nativeRelease(handle: Long)
}

class RawUpload internal constructor(private var handle: Long) : AutoCloseable {
    // The buffer is only valid inside this callback. It must not escape the CPU read.
    @Synchronized
    internal fun <T> read(block: (ByteBuffer) -> T): T? {
        if (handle == 0L) return null
        val bytes = nativeBeginRead(handle)?.order(ByteOrder.nativeOrder()) ?: return null
        return try { block(bytes) } finally { nativeEndRead(handle) }
    }

    @Synchronized
    internal fun render(block: (Long) -> Boolean): Boolean = handle != 0L && block(handle)

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeRelease(current)
    }

    private external fun nativeBeginRead(handle: Long): ByteBuffer?
    private external fun nativeEndRead(handle: Long)
    private external fun nativeRelease(handle: Long)
}
