package com.unspektrawesome.burst

internal data class BurstMemoryPlan(val limitBytes: Long, val historyBytes: Long, val keepPacked: Boolean) {
    companion object {
        const val FPS = 30
        const val MAX_SECONDS = 5
        const val MAX_FRAMES = (MAX_SECONDS + 1) * FPS
        const val MAX_SAVES = 5
        private const val MB = 1024L * 1024

        fun calculate(seconds: Int, frameBytes: Long, availableBytes: Long, totalBytes: Long): BurstMemoryPlan {
            require(frameBytes > 0)
            val historyFrames = seconds.coerceIn(1, MAX_SECONDS) * FPS
            val desired = frameBytes * (historyFrames + FPS * MAX_SAVES + 8)
            val ceiling = minOf(totalBytes * 2 / 5, availableBytes * 2 / 3).coerceAtLeast(0)
            val limit = minOf(((desired + 256 * MB - 1) / (256 * MB)) * 256 * MB, ceiling)
            val history = (limit - frameBytes * (FPS + 8)).coerceAtLeast(minOf(frameBytes, limit / 2))
            return BurstMemoryPlan(limit, history, frameBytes * historyFrames <= history)
        }
    }
}
