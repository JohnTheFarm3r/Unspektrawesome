package com.unspektrawesome.camera.discovery

import com.unspektrawesome.camera.RawFormat

object LensDiscoveryPolicy {
    // Allows normal 12, 12.5 and 16 MP sizes while excluding full-resolution 48/50 MP streams.
    private const val MAX_NORMAL_PIXELS = 20_000_000L

    fun accepts(configuration: DiscoveredLensConfiguration): Boolean = configuration.raw.let {
        !it.maximumResolutionMode && it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= MAX_NORMAL_PIXELS
    }

    val streamPreference: Comparator<DiscoveredLensConfiguration> = compareBy(
        { if (accepts(it)) 0 else 1 },
        { formatPreference(it.raw.format) },
        { -it.raw.width.toLong() * it.raw.height })

    fun order(candidates: List<DiscoveredLensConfiguration>): List<DiscoveredLensConfiguration> {
        val lenses = candidates.distinctBy { it.key }.groupBy { it.sensorId }.values.map { configurations ->
            val routes = configurations.map { it.routeId }.distinct().withIndex().associate { it.value to it.index }
            // Try the catalog's preferred camera access before chasing larger sizes on alternative IDs.
            configurations.sortedWith(compareBy({ if (accepts(it)) 0 else 1 },
                { formatPreference(it.raw.format) }, { routes.getValue(it.routeId) }, { -it.raw.width.toLong() * it.raw.height }))
        }
        return (0 until (lenses.maxOfOrNull { it.size } ?: 0)).flatMap { index -> lenses.mapNotNull { it.getOrNull(index) } }
    }

    private fun formatPreference(format: RawFormat) = when (format) {
        RawFormat.RAW10 -> 0; RawFormat.RAW_SENSOR -> 1; RawFormat.RAW12 -> 2
    }
}
