package com.unspektrawesome.ui.camera

import kotlin.math.abs

internal fun filmPanelSwipe(dx: Float, dy: Float, threshold: Float): Boolean? {
    // The camera layout stays portrait-locked; only control labels rotate.
    if (abs(dy) < threshold || abs(dy) < abs(dx) * 1.15f) return null
    return dy < 0f
}
