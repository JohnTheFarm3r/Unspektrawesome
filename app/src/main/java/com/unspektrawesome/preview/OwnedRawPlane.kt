package com.unspektrawesome.preview

import java.nio.ByteBuffer

internal fun copyRawPlane(source: ByteBuffer): ByteBuffer {
    val input = source.duplicate().apply { clear() }
    return ByteBuffer.allocateDirect(input.remaining()).order(source.order()).apply {
        put(input)
        flip()
    }
}
