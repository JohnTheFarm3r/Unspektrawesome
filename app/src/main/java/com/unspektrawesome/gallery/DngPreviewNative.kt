package com.unspektrawesome.gallery

import java.nio.ByteBuffer

/** Native renderer for DNG previews and queue thumbnails. Returns false when the input cannot be read directly. */
internal object DngPreviewNative {
    init { System.loadLibrary("unspektra_archive") }

    /** [params] holds black levels (4), level ranges (4), white balance gains (3), the neutral clip level and the color matrix (9). */
    external fun render(pixels: ByteBuffer, width: Int, height: Int, stride: Int, format: Int, left: Int, top: Int,
        channels: IntArray, params: FloatArray, columns: IntArray, rows: IntArray,
        dcpTable: FloatArray?, dcpDimensions: IntArray?, output: IntArray): Boolean
}
