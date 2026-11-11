package com.unspektrawesome.burst

import java.nio.ByteBuffer

internal object RawFrameCompression {
    init { System.loadLibrary("unspektra_archive") }
    external fun encode(source: ByteBuffer, width: Int, height: Int, stride: Int, format: Int, block: Int): Long
    external fun copy(source: ByteBuffer, destination: ByteBuffer, width: Int, height: Int, stride: Int, format: Int)
    external fun payload(handle: Long, preview: Boolean): ByteBuffer
    external fun release(handle: Long)
    external fun decode(encoded: ByteArray, destination: ByteBuffer)
    external fun preview(encoded: ByteArray): ByteArray
    external fun compressMetadata(bytes: ByteArray): ByteArray
    external fun decompressMetadata(bytes: ByteArray, maximum: Int): ByteArray
}
