package com.unspektrawesome.burst

internal data class BurstMemoryPlan(val limitBytes: Long, val historyBytes: Long, val fullHistory: Boolean) {
    companion object {
        const val FPS = 30
        const val MAX_SECONDS = 5
        const val MAX_FRAMES = (MAX_SECONDS + 1) * FPS
        const val MAX_SAVES = 5
        private const val MB = 1024L * 1024
        private const val IN_FLIGHT = 8

        /**
         * Plans the burst memory for [frameBytes] packed frames, where [compressedBytes] is the expected size of
         * one compressed frame.
         *
         * The history only holds plain copies, so waiting for the shutter keeps the processor nearly idle. Frames
         * are compressed once they join a save, so the second after the shutter needs room for compressed frames
         * plus the frames still waiting for compression. When the requested history does not fit, the history is
         * shortened to the memory that is left instead of compressing every frame while waiting.
         */
        fun calculate(seconds: Int, frameBytes: Long, availableBytes: Long, totalBytes: Long,
            compressedBytes: Long = frameBytes): BurstMemoryPlan {
            require(frameBytes > 0)
            val history = frameBytes * seconds.coerceIn(1, MAX_SECONDS) * FPS
            val compressed = compressedBytes.coerceIn(1, frameBytes)
            val ceiling = minOf(totalBytes * 2 / 5, availableBytes * 2 / 3).coerceAtLeast(0)
            val reserve = frameBytes * IN_FLIGHT + compressed * FPS
            val desired = history + reserve + compressed * FPS * (MAX_SAVES - 1)
            val limit = minOf(((desired + 256 * MB - 1) / (256 * MB)) * 256 * MB, ceiling)
            val budget = (limit - reserve).coerceAtLeast(minOf(frameBytes, limit / 2))
            return BurstMemoryPlan(limit, budget, history <= budget)
        }
    }
}
