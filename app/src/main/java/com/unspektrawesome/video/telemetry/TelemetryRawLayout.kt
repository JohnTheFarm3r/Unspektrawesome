package com.unspektrawesome.video.telemetry

import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.settings.RawLayoutOverride

internal object TelemetryRawLayout {
    fun preservesPixelMapping(layout: RawLayoutOverride, acquisition: Size2d): Boolean {
        if (!layout.enabled) return true
        val decoded = layout.size(acquisition)
        // Repacking rows can change which sensor pixel each decoded value belongs to.
        return !layout.tightStride && layout.rowStrideBytes == null &&
            decoded.width in 2..acquisition.width && decoded.height in 2..acquisition.height
    }
}
