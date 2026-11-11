package com.unspektrawesome.preview

internal class FocusPeakingResolution {
    private val sizes = intArrayOf(720, 960, 1440)
    private var index = 0
    private var ceiling = sizes.lastIndex
    private var warmup = 3
    private var samples = 0
    private var renderTotalMs = 0L
    val shortEdge: Int get() = sizes[index]

    fun reset() {
        index = 0
        ceiling = sizes.lastIndex
        restartSamples()
    }

    fun record(renderMs: Long) {
        if (renderMs < 0) return
        if (warmup > 0) { warmup--; return }
        renderTotalMs += renderMs
        if (++samples < 6) return
        val average = renderTotalMs.toDouble() / samples
        val previous = index
        if (average > 110 && index > 0) {
            index--
            ceiling = index
        } else if (average < 35 && index < ceiling) {
            index++
        }
        samples = 0
        renderTotalMs = 0
        if (index != previous) restartSamples()
    }

    private fun restartSamples() {
        warmup = 3
        samples = 0
        renderTotalMs = 0
    }
}
