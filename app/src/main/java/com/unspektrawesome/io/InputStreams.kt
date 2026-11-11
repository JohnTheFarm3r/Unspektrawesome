package com.unspektrawesome.io

import java.io.InputStream

internal fun InputStream.readUpTo(maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val bytes = ByteArray(maxBytes)
    var offset = 0
    while (offset < maxBytes) {
        val count = read(bytes, offset, maxBytes - offset)
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            bytes[offset++] = next.toByte()
        } else {
            offset += count
        }
    }
    return if (offset == maxBytes) bytes else bytes.copyOf(offset)
}
