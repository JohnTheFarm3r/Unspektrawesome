package com.unspektrawesome.vulkan

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class ParallelShaderPreparation<T : AutoCloseable>(
    private val executor: Executor,
    private val workerCount: Int,
    private val create: () -> T,
    private val prepare: (T) -> Unit,
    steps: List<(T) -> Unit>,
    private val onFailure: (Throwable) -> Unit,
    private val onComplete: () -> Unit,
) {
    private val pending = ConcurrentLinkedQueue(steps)
    private val stopped = AtomicBoolean()
    private val started = AtomicBoolean()

    init { require(workerCount > 0) }

    fun start() {
        if (!started.compareAndSet(false, true) || stopped.get()) return
        val workers = List(workerCount) {
            CompletableFuture.runAsync({
                try {
                    if (!stopped.get()) create().use { renderer ->
                        prepare(renderer)
                        while (!stopped.get()) {
                            val step = pending.poll() ?: break
                            step(renderer)
                        }
                    }
                } catch (error: Throwable) {
                    if (stopped.compareAndSet(false, true)) {
                        pending.clear()
                        onFailure(error)
                    }
                }
            }, executor)
        }
        CompletableFuture.allOf(*workers.toTypedArray()).thenRun {
            if (!stopped.get()) onComplete()
        }
    }

    fun stop() {
        stopped.set(true)
        pending.clear()
    }
}
