package com.unspektrawesome.preview

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.WorkDuration
import androidx.annotation.RequiresApi
import com.unspektrawesome.diagnostics.InternalLogRecorder

internal fun renderPerformanceHints(context: Context) = RenderPerformanceHints(
    createSession = { tid, target ->
        if (Build.VERSION.SDK_INT >= 31) AndroidRenderHintSession.create(context, tid, target) else null
    },
    threadId = Process::myTid,
    clock = System::nanoTime,
    log = { InternalLogRecorder.event("RenderPerformanceHints", it) },
)

@RequiresApi(31)
private open class AndroidRenderHintSession(protected val session: PerformanceHintManager.Session) : RenderPerformanceHints.Session {
    override val supportsGpuTiming: Boolean = false
    override fun updateTarget(durationNs: Long) = session.updateTargetWorkDuration(durationNs)
    override fun reportCpu(durationNs: Long) = session.reportActualWorkDuration(durationNs)
    override fun reportGpu(startedNs: Long, durationNs: Long) = Unit
    override fun close() = session.close()

    companion object {
        fun create(context: Context, tid: Int, targetNs: Long): RenderPerformanceHints.Session? {
            val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
            val session = manager.createHintSession(intArrayOf(tid), targetNs) ?: return null
            return if (Build.VERSION.SDK_INT >= 35) AndroidGpuHintSession(session) else AndroidRenderHintSession(session)
        }
    }
}

@RequiresApi(35)
private class AndroidGpuHintSession(session: PerformanceHintManager.Session) : AndroidRenderHintSession(session) {
    private val workDuration = WorkDuration()
    override val supportsGpuTiming: Boolean = true

    override fun reportGpu(startedNs: Long, durationNs: Long) {
        workDuration.setWorkPeriodStartTimestampNanos(startedNs)
        workDuration.setActualTotalDurationNanos(durationNs)
        workDuration.setActualCpuDurationNanos(0)
        workDuration.setActualGpuDurationNanos(durationNs)
        session.reportActualWorkDuration(workDuration)
    }
}
