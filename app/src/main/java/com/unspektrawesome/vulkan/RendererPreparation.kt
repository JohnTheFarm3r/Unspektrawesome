package com.unspektrawesome.vulkan

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class RendererPreparation<T : AutoCloseable>(
    private val executor: Executor,
    private val create: () -> T,
    private val steps: () -> List<(T) -> Unit>,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private class Preparation<T> {
        val result = CompletableFuture<T?>()
        var renderer: T? = null
        var running = false
        var stop = false
    }

    private val lock = Any()
    private var preparation: Preparation<T>? = null
    private var claimed = false
    private var closed = false

    fun start() = synchronized(lock) {
        if (closed || claimed || preparation != null) return@synchronized
        val pending = Preparation<T>().also { preparation = it }
        var work: List<(T) -> Unit>? = null
        var nextStep = 0
        fun finish() {
            val release = synchronized(lock) {
                if (pending.result.isDone) return@synchronized null
                val abandoned = closed && !claimed
                pending.result.complete(if (abandoned) null else pending.renderer)
                if (abandoned) pending.renderer.also { pending.renderer = null } else null
            }
            release?.close()
        }
        fun advance() {
            val proceed = synchronized(lock) {
                if (pending.result.isDone || pending.stop || (closed && !claimed)) false
                else { pending.running = true; true }
            }
            if (!proceed) {
                finish()
                return
            }
            try {
                val prepared = pending.renderer ?: create().also {
                    synchronized(lock) { pending.renderer = it }
                }
                val remaining = work ?: steps().also { work = it }
                if (nextStep < remaining.size) {
                    try {
                        remaining[nextStep++](prepared)
                    } catch (error: Throwable) {
                        onFailure(error)
                    }
                }
            } catch (error: Throwable) {
                onFailure(error)
            }
            val more = synchronized(lock) {
                pending.running = false
                nextStep < (work?.size ?: 0) && !pending.stop && (!closed || claimed)
            }
            if (more) executor.execute(::advance) else finish()
        }
        executor.execute(::advance)
    }

    fun finishEarly() = synchronized(lock) {
        preparation?.let { pending ->
            pending.stop = true
            if (!pending.running) pending.result.complete(pending.renderer)
        }
    }

    fun take(waitUntilReady: Boolean = true, finishPreparation: Boolean = true): T {
        val pending = synchronized(lock) {
            check(!closed) { "Renderer preparation is already released" }
            val current = preparation
            if (claimed || current == null || (!waitUntilReady && !current.result.isDone)) null else {
                claimed = true
                if (!finishPreparation) finishEarly()
                current.result
            }
        }
        return pending?.join() ?: create()
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        val current = preparation
        if (!claimed && current != null && !current.running) {
            current.result.complete(null)
            current.renderer?.let { renderer -> executor.execute { renderer.close() } }
            current.renderer = null
        }
    }
}
