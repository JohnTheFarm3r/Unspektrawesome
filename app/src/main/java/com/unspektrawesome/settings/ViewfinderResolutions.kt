package com.unspektrawesome.settings

import com.unspektrawesome.camera.Size2d
import kotlin.math.min

object ViewfinderResolutions {
    fun applyToAllProfiles(preferences: CameraPreferences, edge: Int): CameraPreferences =
        preferences.copy(previewShortEdge = edge, lensProfiles = preferences.lensProfiles.map {
            it.copy(previewShortEdge = edge)
        })

    fun selectedEdge(stream: RawStreamSelection, edge: Int?, legacy: RawPreviewQuality): Int =
        edge ?: if (legacy == RawPreviewQuality.FULL) 0 else (min(stream.width, stream.height) * legacy.scale).toInt().coerceAtLeast(2)

    fun size(stream: RawStreamSelection, edge: Int): Size2d {
        val shortest = min(stream.width, stream.height)
        val scale = if (edge <= 0) 1.0 else min(1.0, edge.toDouble() / shortest)
        return Size2d(maxOf(2, (stream.width * scale).toInt() and -2), maxOf(2, (stream.height * scale).toInt() and -2))
    }

    fun choices(stream: RawStreamSelection, selected: Int): List<Int> {
        val shortest = min(stream.width, stream.height)
        return (listOf(480, 720, 1080, 1440, 2160) + listOfNotNull(selected.takeIf { it > 0 }))
            .filter { it < shortest }.distinct().sorted().plus(0).distinctBy { size(stream, it) }
    }

    fun label(stream: RawStreamSelection, edge: Int): String {
        val size = size(stream, edge)
        return "${size.width} × ${size.height}" + if (edge == 0) " (Full)" else ""
    }
}
