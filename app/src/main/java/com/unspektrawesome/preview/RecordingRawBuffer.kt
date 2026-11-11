package com.unspektrawesome.preview

import java.nio.ByteBuffer

internal object RecordingRawBuffer {
    init { System.loadLibrary("unspektrawesome_vulkan") }

    // JNI ownership avoids the managed direct-buffer limit and releases the pool at stop.
    fun allocate(bytes: Int): PreviewFrameDispatcher.Buffer? = nativeAllocate(bytes)?.let { buffer ->
        try { PreviewFrameDispatcher.Buffer(buffer) { nativeRelease(buffer) } }
        catch (error: Throwable) { nativeRelease(buffer); throw error }
    }

    private external fun nativeAllocate(bytes: Int): ByteBuffer?
    private external fun nativeRelease(buffer: ByteBuffer)
}
