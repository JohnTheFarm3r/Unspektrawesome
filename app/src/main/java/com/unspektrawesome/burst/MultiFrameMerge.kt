package com.unspektrawesome.burst

import java.nio.ByteBuffer

/** GPU merge of aligned RAW frames into one 16-bit RAW frame. */
internal object MultiFrameMerge {
    init { System.loadLibrary("unspektra_archive") }

    /**
     * Merges [frames] (reference first, packed rows of [rowBytes]) into [output] as packed 16-bit RAW.
     * [scales] multiplies each frame to the reference exposure. Returns the mean number of frames that
     * contributed per pixel, or 0 when the merge is unavailable.
     */
    external fun merge(
        frames: Array<ByteBuffer>, scales: FloatArray, width: Int, height: Int, rowBytes: Int, format: Int,
        blockSize: Int, sourcePattern: Int, phaseX: Int, phaseY: Int, black: FloatArray, white: Float,
        noiseS: Float, noiseO: Float, motion: Float, output: ByteBuffer,
    ): Float
}
