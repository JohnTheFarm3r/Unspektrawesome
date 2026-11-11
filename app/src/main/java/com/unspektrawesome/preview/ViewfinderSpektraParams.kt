package com.unspektrawesome.preview

import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraState

internal fun viewfinderSpektraParams(params: SpektraFilmParams, enabled: Boolean, videoMode: Boolean = false): SpektraFilmParams {
    // Video renders halation, diffusion and glare with its own pyramid effects; grain and fine detail filters stay off.
    val preview = if (videoMode && (params.grainEnabled || params.dirCouplersDiffusionUm != 0f ||
        params.dirCouplersDiffusionTailUm != 0f || params.dirCouplersDiffusionTailWeight != 0f ||
        params.scannerMtf50LpMm != 0f || params.scannerUnsharpRadiusUm != 0f || params.scannerUnsharpAmount != 0f)) params.copy(
        grainEnabled = false, dirCouplersDiffusionUm = 0f, dirCouplersDiffusionTailUm = 0f,
        dirCouplersDiffusionTailWeight = 0f, scannerMtf50LpMm = 0f, scannerUnsharpRadiusUm = 0f, scannerUnsharpAmount = 0f,
    ) else params
    return if (enabled || !preview.enabled) preview else preview.copy(enabled = false)
}

internal fun viewfinderSpektraState(state: SpektraState, enabled: Boolean, videoMode: Boolean = false): SpektraState {
    val params = viewfinderSpektraParams(state.params, enabled, videoMode)
    return if (params === state.params) state else state.copy(params = params)
}
