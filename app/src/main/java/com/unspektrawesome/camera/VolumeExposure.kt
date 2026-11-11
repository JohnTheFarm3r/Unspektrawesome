package com.unspektrawesome.camera

import com.unspektrawesome.spektra.SpektraFilmParams

internal object VolumeExposure {
    fun cycle(params: SpektraFilmParams, direction: Int): SpektraFilmParams {
        val current = if (!params.autoExposure) 0 else params.autoExposureMethod.coerceIn(0, 1) + 1
        val next = Math.floorMod(current + direction.coerceIn(-1, 1), 3)
        return params.copy(autoExposure = next != 0,
            autoExposureMethod = if (next == 0) params.autoExposureMethod else next - 1)
    }

    fun label(params: SpektraFilmParams): String = when {
        !params.autoExposure -> "Off"
        params.autoExposureMethod == 1 -> "Median"
        else -> "Center weighted"
    }
}
