package com.unspektrawesome.burst

import java.nio.ByteBuffer

/** GPU merge of aligned RAW frames into one 16-bit RAW frame. */
internal object MultiFrameMerge {
    init { System.loadLibrary("unspektra_archive") }

    /**
     * Merges [frames] (reference first, packed rows of [rowBytes]) into [output] as packed 16-bit RAW.
     * [scales] multiplies each frame to the reference exposure. Returns the mean number of frames that
     * contributed per pixel, or 0 when the merge is unavailable. [outputScale] multiplies the output levels,
     * so the average keeps precision finer than one step of the input; black and white scale with it.
     */
    external fun merge(
        frames: Array<ByteBuffer>, scales: FloatArray, width: Int, height: Int, rowBytes: Int, format: Int,
        blockSize: Int, sourcePattern: Int, phaseX: Int, phaseY: Int, black: FloatArray, white: Float,
        noiseS: Float, noiseO: Float, motion: Float, outputScale: Float, output: ByteBuffer,
    ): Float

    /** Largest whole multiple that still fits [white] into 16 bits. */
    fun outputScale(white: Float): Int = if (white.isFinite() && white >= 1f) (65535f / white).toInt().coerceAtLeast(1) else 1
}
