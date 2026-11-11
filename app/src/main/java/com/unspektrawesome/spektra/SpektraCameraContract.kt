package com.unspektrawesome.spektra

/** The camera supplies scene-linear Rec.709 RGB and delivers sRGB display/JPEG pixels. */
object SpektraCameraContract {
    const val INPUT_LINEAR_REC709 = 15
    const val OUTPUT_SRGB = 17
    const val DISPLAY_SDR = 0

    fun resolve(params: SpektraFilmParams): SpektraFilmParams = params.copy(
        inputColorSpace = INPUT_LINEAR_REC709,
        outputColorSpace = OUTPUT_SRGB,
        outputRole = DISPLAY_SDR,
    )
}
