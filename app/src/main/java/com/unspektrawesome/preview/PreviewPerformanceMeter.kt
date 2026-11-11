package com.unspektrawesome.preview

import com.unspektrawesome.camera.Size2d

data class PreviewFrameInfo(
    val viewfinderSize: Size2d,
    val rawInputSize: Size2d,
    val effectsEnabled: Boolean,
)

data class PreviewPerformance(
    val framesPerSecond: Double? = null,
    val pipelineMs: Double? = null,
    val frame: PreviewFrameInfo? = null,
    val video: com.unspektrawesome.video.VideoPerformance? = null,
) {
    fun gpuFrameBudgetPercent(frameRate: Double): Double? = pipelineMs?.takeIf {
        it.isFinite() && it >= 0 && frameRate.isFinite() && frameRate > 0
    }?.let { (it * frameRate / 10.0).coerceIn(0.0, 100.0) }
}

internal class PreviewPerformanceMeter {
    private var startedAtMs: Long? = null
    private var frames = 0L
    private var timedFrames = 0L
    private var pipelineTotalMs = 0.0
    private var lastFrame: PreviewFrameInfo? = null
    private var gpuSamplesStartAtMs = 0L

    fun reset(nowMs: Long, gpuSettlingMs: Long = 0) {
        gpuSamplesStartAtMs = nowMs + gpuSettlingMs
        lastFrame = null
        restartWindow(nowMs)
    }

    private fun restartWindow(nowMs: Long) {
        startedAtMs = nowMs
        frames = 0
        timedFrames = 0
        pipelineTotalMs = 0.0
    }

    fun recordFrame(pipelineMs: Double, frame: PreviewFrameInfo? = null, startedAtMs: Long,
        presented: Boolean = true, capturesPerOutput: Int = 1) {
        require(capturesPerOutput >= 1)
        if (presented) frames++
        lastFrame = frame
        if (startedAtMs >= gpuSamplesStartAtMs && pipelineMs.isFinite() && pipelineMs >= 0.0) {
            timedFrames++
            // GPU samples arrive on later captures, including intermediate averaged captures.
            // Express their mean cost per output frame without inflating the displayed FPS.
            pipelineTotalMs += pipelineMs * capturesPerOutput
        }
    }

    fun sample(nowMs: Long): PreviewPerformance {
        val elapsed = startedAtMs?.let { nowMs - it } ?: 0L
        val result = if (elapsed > 0) PreviewPerformance(
            framesPerSecond = frames * 1000.0 / elapsed,
            pipelineMs = if (timedFrames > 0) pipelineTotalMs / timedFrames else null,
            frame = lastFrame,
        ) else PreviewPerformance()
        restartWindow(nowMs)
        return result
    }
}
