package com.unspektrawesome.preview

import kotlin.math.max
import kotlin.math.roundToLong

/** Accessed only on the rendering worker, including session creation and teardown. */
internal class RenderPerformanceHints(
    private val createSession: (Int, Long) -> Session?,
    private val threadId: () -> Int,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    internal interface Session : AutoCloseable {
        val supportsGpuTiming: Boolean
        fun updateTarget(durationNs: Long)
        fun reportCpu(durationNs: Long)
        fun reportGpu(startedNs: Long, durationNs: Long)
    }

    private var session: Session? = null
    private var attempted = false
    private var workerId = 0
    private var recording: Boolean? = null
    private var targetNs = 0L
    private var acceptGpuAfterNs = 0L
    private var lastGpuStartedNs = 0L
    private var gpuReorderDepth = 0
    private val pendingGpu = java.util.TreeMap<Long, Long>()
    internal var cpuReports = 0L
        private set
    internal var gpuReports = 0L
        private set
    internal val active: Boolean get() = session != null
    val measuresGpu: Boolean get() = session?.supportsGpuTiming == true

    fun beginFrame(targetDurationNs: Long, recording: Boolean, gpuReorderDepth: Int = 0): Long {
        require(gpuReorderDepth >= 0)
        if (targetDurationNs <= 0) {
            close()
            return 0
        }
        val tid = threadId()
        if (workerId != tid || this.recording != recording || this.gpuReorderDepth != gpuReorderDepth) {
            close()
            workerId = tid
            this.recording = recording
            this.gpuReorderDepth = gpuReorderDepth
        }
        return try {
            if (!attempted) {
                attempted = true
                session = createSession(tid, targetDurationNs)
                targetNs = targetDurationNs
                acceptGpuAfterNs = clock()
                log("session active=$active tid=$tid target_ns=$targetNs gpu=$measuresGpu recording=$recording")
            }
            val current = session ?: return 0
            if (targetNs != targetDurationNs) {
                current.updateTarget(targetDurationNs)
                targetNs = targetDurationNs
                acceptGpuAfterNs = clock()
                lastGpuStartedNs = 0
                pendingGpu.clear()
            }
            clock()
        } catch (error: Exception) {
            disable(error)
            0
        }
    }

    fun endFrame(startedNs: Long, gpuStartedNs: Long, gpuDurationMs: Double) {
        val current = session ?: return
        if (startedNs <= 0) return
        try {
            if (current.supportsGpuTiming && gpuStartedNs > 0 && gpuDurationMs.isFinite() && gpuDurationMs > 0) {
                if (gpuStartedNs < acceptGpuAfterNs || gpuStartedNs <= lastGpuStartedNs) return
                val duration = gpuDurationMs * 1_000_000.0
                if (duration >= Long.MAX_VALUE.toDouble()) return
                val durationNs = duration.roundToLong()
                if (durationNs <= 0 || durationNs > clock() - gpuStartedNs) return
                // Intermediate averages reuse one frame slot before the other slot is collected.
                // Order the delayed samples so the final, more expensive capture is also reported.
                pendingGpu[gpuStartedNs] = durationNs
                while (pendingGpu.size > gpuReorderDepth) {
                    val sample = pendingGpu.pollFirstEntry()
                    current.reportGpu(sample.key, sample.value)
                    lastGpuStartedNs = sample.key
                    gpuReports++
                }
            } else {
                val durationNs = clock() - startedNs
                if (durationNs <= 0) return
                current.reportCpu(durationNs)
                cpuReports++
            }
        } catch (error: Exception) {
            disable(error)
        }
    }

    private fun disable(error: Exception) {
        log("session_unavailable type=${error.javaClass.simpleName}")
        releaseSession()
        attempted = true
    }

    private fun releaseSession() {
        val previous = session
        session = null
        if (previous != null) {
            try { previous.close() }
            catch (error: Exception) { log("session_close_failed type=${error.javaClass.simpleName}") }
            log("session closed cpu_reports=$cpuReports gpu_reports=$gpuReports")
        }
    }

    override fun close() {
        releaseSession()
        attempted = false
        workerId = 0
        recording = null
        targetNs = 0
        acceptGpuAfterNs = 0
        lastGpuStartedNs = 0
        gpuReorderDepth = 0
        pendingGpu.clear()
        cpuReports = 0
        gpuReports = 0
    }

    companion object {
        fun targetDurationNs(sourceFrameRate: Double, frameInterval: Int = 1, averageFrames: Int = 1,
                             minimumFrameDurationNs: Long = 0, recording: Boolean = false): Long {
            if (!sourceFrameRate.isFinite() || sourceFrameRate <= 0 || frameInterval < 1 ||
                averageFrames !in 1..frameInterval) return 0
            // Averaged frames arrive consecutively and each needs one sensor frame's budget.
            val interval = if (averageFrames > 1) 1 else frameInterval
            // Finish recording work early enough to leave room for camera and encoder scheduling spikes.
            val margin = if (recording) .75 else 1.0
            val duration = max(1_000_000_000.0 / sourceFrameRate, minimumFrameDurationNs.toDouble()) * interval * margin
            return if (duration >= Long.MAX_VALUE.toDouble()) 0 else duration.roundToLong().coerceAtLeast(1)
        }
    }
}
