package com.unspektrawesome.vulkan

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.unspektrawesome.diagnostics.InternalLogRecorder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ShaderWarmup(context: Context) {
    private val cachePath = File(context.cacheDir, "vulkan-pipelines").apply { mkdirs() }
        .resolve("rendering").absolutePath
    private val completedRecord = AtomicFile(File(context.cacheDir, "vulkan-pipelines/prepared"))
    private val progress = ShaderPreparationProgress(
        total = 0,
        readCompletedIdentity = { runCatching { completedRecord.readFully().toString(Charsets.UTF_8) }.getOrNull() },
        saveCompletedIdentity = { identity ->
            val output = completedRecord.startWrite()
            try {
                output.write(identity.toByteArray(Charsets.UTF_8))
                completedRecord.finishWrite(output)
            } catch (error: Throwable) {
                completedRecord.failWrite(output)
                throw error
            }
        },
    )
    val state = progress.state

    fun hideProgress() = progress.hide()
    private val workerCount = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 6)
    private val executor = Executors.newFixedThreadPool(workerCount) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            task.run()
        }, "ShaderWarmup")
    }
    private val started = AtomicBoolean()
    private val interrupted = AtomicBoolean()
    private val completionLogged = AtomicBoolean()
    private var compilationStarted = 0L
    private var compilationCpuStarted = 0L
    private val stepCount = VulkanRenderer.warmupStepCount(true) + VulkanRenderer.warmupStepCount(false)

    private fun compileStep(renderer: VulkanRenderer, preview: Boolean, step: Int, part: Int = -1, hydrate: Boolean = false) {
        val started = SystemClock.elapsedRealtime()
        check(renderer.warmUp(preview, step, part)) { renderer.diagnostics() }
        progress.stepCompleted()
        InternalLogRecorder.event("ShaderWarmup",
            "${if (hydrate) "hydrate " else ""}${if (preview) "preview" else "export"} step=$step elapsedMs=${SystemClock.elapsedRealtime() - started} part=$part")
        if (progress.state.value.finished && !interrupted.get() && completionLogged.compareAndSet(false, true)) {
            InternalLogRecorder.event("ShaderWarmup",
                "compilation_finished elapsedMs=${SystemClock.elapsedRealtime() - compilationStarted} " +
                    "cpuMs=${Process.getElapsedCpuTime() - compilationCpuStarted}")
        }
    }

    private fun preparation(preview: Boolean) = RendererPreparation(
        executor = executor,
        create = { VulkanRenderer(cachePath) },
        steps = {
            List(VulkanRenderer.warmupStepCount(preview)) { step ->
                { renderer: VulkanRenderer ->
                    compileStep(renderer, preview, step, hydrate = true)
                }
            }
        },
        onFailure = ::preparationFailed,
    )
    private val preview = preparation(preview = true)
    private val export = preparation(preview = false)
    private val parallelSteps = listOf(true, false).flatMap { isPreview ->
        (0 until VulkanRenderer.warmupStepCount(isPreview)).filterNot {
            isPreview && (it == 0 || it == 3)
        }.flatMap { step ->
            val parts = VulkanRenderer.warmupPartCount(isPreview, step)
            List(parts) { part ->
                { renderer: VulkanRenderer -> compileStep(renderer, isPreview, step, if (parts == 1) -1 else part) }
            }
        }
    }
    private val parallel = ParallelShaderPreparation(
        executor = executor,
        workerCount = workerCount,
        create = { VulkanRenderer(cachePath) },
        prepare = { renderer ->
            check(renderer.warmUp(true, 0)) { renderer.diagnostics() }
            check(renderer.warmUp(true, 3)) { renderer.diagnostics() }
        },
        steps = parallelSteps,
        onFailure = ::preparationFailed,
        onComplete = {
            if (!interrupted.get()) {
                preview.start()
                export.start()
            }
        },
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            if (interrupted.get()) return@execute
            try {
                compilationStarted = SystemClock.elapsedRealtime()
                compilationCpuStarted = Process.getElapsedCpuTime()
                InternalLogRecorder.event("ShaderWarmup", "compilation_start workers=$workerCount")
                progress.setTotal(2 + parallelSteps.size + stepCount)
                VulkanRenderer(cachePath).use { renderer ->
                    val started = SystemClock.elapsedRealtime()
                    check(renderer.warmUp(true, 0)) { renderer.diagnostics() }
                    progress.checkCache(
                        renderer.preparationIdentity(),
                        renderer.loadedPipelineCache(),
                    )
                    if (progress.state.value.required) {
                        progress.stepCompleted()
                        InternalLogRecorder.event("ShaderWarmup", "preview step=0 elapsedMs=${SystemClock.elapsedRealtime() - started}")
                        compileStep(renderer, true, 3)
                    } else progress.setTotal(stepCount)
                }
                if (!interrupted.get()) {
                    if (progress.state.value.required) parallel.start()
                    else { preview.start(); export.start() }
                }
            } catch (error: Throwable) {
                preparationFailed(error)
            }
        }
    }

    fun takeRenderer(forExport: Boolean): VulkanRenderer {
        if (forExport) {
            interrupted.set(true)
            parallel.stop()
            progress.interrupt()
            preview.finishEarly()
            export.finishEarly()
        }
        return if (forExport) export.take(finishPreparation = false)
        else preview.take(waitUntilReady = progress.state.value.finished && !interrupted.get())
    }

    private fun preparationFailed(error: Throwable) {
        InternalLogRecorder.error("ShaderWarmup", "Pipeline preparation failed", error)
        interrupted.set(true)
        parallel.stop()
        progress.fail()
        preview.finishEarly()
        export.finishEarly()
    }
}
