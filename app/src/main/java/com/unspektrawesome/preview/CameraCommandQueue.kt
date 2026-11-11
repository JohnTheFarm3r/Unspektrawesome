package com.unspektrawesome.preview

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class CameraCommandQueue {
    private val submissions = Any()
    private val executor = ScheduledThreadPoolExecutor(1) { Thread(it, "UnspektrawesomeControls") }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private var closed = false

    // True means accepted for asynchronous execution; results arrive through controller state.
    fun submit(action: () -> Unit): Boolean = synchronized(submissions) {
        if (closed) return@synchronized false
        executor.execute(action)
        true
    }

    fun schedule(delayMs: Long, action: () -> Unit): ScheduledFuture<*>? = synchronized(submissions) {
        if (closed) return@synchronized null
        executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
    }

    fun closeAfter(action: () -> Unit) = synchronized(submissions) {
        if (closed) return@synchronized
        closed = true
        executor.execute {
            try {
                action()
            } finally {
                executor.shutdown()
            }
        }
    }
}
