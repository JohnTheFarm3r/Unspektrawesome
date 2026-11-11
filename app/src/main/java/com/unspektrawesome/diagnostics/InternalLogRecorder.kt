package com.unspektrawesome.diagnostics

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.PowerManager
import android.util.Log
import androidx.core.content.FileProvider
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.diagnostics.DiagnosticReport.entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object InternalLogRecorder {
    const val PREVIEW_DIAGNOSTICS_VERSION = 15
    data class State(
        val recording: Boolean = false,
        val nativeLogs: Boolean = false,
        val bytes: Long = 0,
        val droppedLines: Long = 0,
        val storageError: String? = null,
        val readerError: String? = null,
        val automaticLogError: String? = null,
        val automaticReportError: String? = null,
    )

    private sealed interface Work {
        data class Line(val text: String) : Work
        data class Action(val run: () -> Unit) : Work
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val started = AtomicBoolean()
    private val dropped = AtomicLong()
    private val queue = ArrayBlockingQueue<Work>(2048)
    private val reportLock = Mutex()
    private lateinit var application: Application
    private lateinit var directory: File
    private var store: RollingLogStore? = null
    private lateinit var publicLogs: PublicDiagnosticLogs
    private var publicRetryAt = 0L
    @Volatile private var clearedAt = 0L
    @Volatile private var latestThermalStatus: Int? = null

    fun start(app: Application) {
        if (!started.compareAndSet(false, true)) return
        application = app
        directory = File(app.noBackupFilesDir, "diagnostics")
        publicLogs = PublicDiagnosticLogs(app)
        clearedAt = runCatching { File(directory, "cleared-at").readText().toLong() }.getOrDefault(0L)
        val sessionStarted = System.currentTimeMillis()
        installCrashHandler()
        thread(name = "DiagnosticWriter", isDaemon = true) { writeLoop() }
        event("App", "session_start pid=${Process.myPid()} version=${BuildConfig.VERSION_NAME} " +
            "code=${BuildConfig.VERSION_CODE} build=${BuildConfig.BUILD_TYPE} " +
            "previewDiagnostics=${if (BuildConfig.DEBUG) PREVIEW_DIAGNOSTICS_VERSION else 0}")
        runCatching {
            val power = app.getSystemService(PowerManager::class.java)
            latestThermalStatus = power.currentThermalStatus
            power.addThermalStatusListener(app.mainExecutor) { status ->
                latestThermalStatus = status
                event("Thermal", "status=$status")
            }
        }
        thread(name = "DiagnosticLogcat", isDaemon = true) { readLogcat(sessionStarted) }
        thread(name = "DiagnosticRecovery", isDaemon = true) { saveAutomaticReport() }
    }

    fun automaticLogPath(): String = PublicDiagnosticLogs.relativePath(application)

    @JvmStatic fun thermalStatus(): Int? = latestThermalStatus

    @JvmStatic fun event(component: String, message: String) = record("I", component, message, null)
    @JvmStatic fun warning(component: String, message: String) = record("W", component, message, null)
    @JvmStatic fun error(component: String, message: String, error: Throwable? = null) = record("E", component, message, error)

    @JvmStatic fun recordNative(priority: Int, component: String, message: String) {
        val level = when {
            priority >= Log.ERROR -> "E"
            priority >= Log.WARN -> "W"
            else -> "I"
        }
        append("${Instant.now()} $level [$component] tid=${Process.myTid()} native=true ${message.take(12_000)}")
    }

    fun resources(stage: String) {
        if (started.get()) runCatching { event("Resources", "$stage ${DiagnosticReport.resources(application)}") }
    }

    private fun record(level: String, component: String, message: String, error: Throwable?) {
        val detail = buildString {
            append(message.take(12_000))
            if (error != null) append("\n${Log.getStackTraceString(error).take(12_000)}")
        }
        append("${Instant.now()} $level [$component] tid=${Process.myTid()} $detail")
        Log.println(when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO }, "UnspekTrace", "[$component] $detail")
    }

    private fun append(line: String) {
        if (started.get() && !queue.offer(Work.Line(line.take(24_000)))) dropped.incrementAndGet()
    }

    private fun writeLoop() {
        while (true) {
            val first = queue.poll(1, TimeUnit.SECONDS)
            val lines = mutableListOf<String>()
            if (first is Work.Line) {
                lines.add(first.text)
                while (lines.size < 128 && queue.peek() is Work.Line) lines.add((queue.poll() as Work.Line).text)
            }
            try {
                val current = store ?: RollingLogStore(File(directory, "events")).also { store = it }
                if (first is Work.Action) first.run()
                else if (lines.isNotEmpty()) current.append(lines)
                mutableState.update { it.copy(recording = true, bytes = current.sizeBytes(), droppedLines = dropped.get(), storageError = null) }
            } catch (error: Exception) {
                mutableState.update { it.copy(recording = false, storageError = "Log storage unavailable: ${error.message}") }
            }
            if (lines.isNotEmpty() && android.os.SystemClock.elapsedRealtime() >= publicRetryAt) {
                try {
                    publicLogs.append(lines)
                    mutableState.update { it.copy(automaticLogError = null) }
                } catch (error: Exception) {
                    publicRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000
                    mutableState.update { it.copy(automaticLogError = "Automatic logs unavailable: ${error.message}") }
                }
            }
        }
    }

    private fun saveAutomaticReport() = runBlocking {
        for (attempt in 0..2) {
            try {
                reportLock.withLock {
                    publicLogs.removeIncompleteReports()
                    val report = createReport(includeCameraCapabilities = false)
                    publicLogs.publishReport(report)
                }
                mutableState.update { it.copy(automaticReportError = null) }
                event("Recorder", "automatic_report_saved path=${automaticLogPath()}")
                return@runBlocking
            } catch (error: Exception) {
                mutableState.update { it.copy(automaticReportError = "Automatic logs unavailable: ${error.message}") }
                if (attempt < 2) kotlinx.coroutines.delay(if (attempt == 0) 1000 else 5000)
            }
        }
    }

    private fun readLogcat(sessionStarted: Long) {
        var cursor = sessionStarted
        var delayMs = 1000L
        val pattern = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+):\s?(.*)$""")
        while (true) {
            var process: java.lang.Process? = null
            try {
                val since = maxOf(cursor, clearedAt)
                process = ProcessBuilder("logcat", "-b", "main,system,crash", "--pid=${Process.myPid()}",
                    "-v", "epoch", "-T", "${since / 1000}.${(since % 1000).toString().padStart(3, '0')}", "*:I")
                    .redirectErrorStream(true).start()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val match = pattern.matchEntire(line)
                        if (match != null) {
                            val timestamp = (match.groupValues[1].toDouble() * 1000).toLong()
                            cursor = maxOf(cursor, timestamp)
                            if (!mutableState.value.nativeLogs) mutableState.update { it.copy(nativeLogs = true, readerError = null) }
                            delayMs = 1000
                            val tag = match.groupValues[3].trim()
                            val level = match.groupValues[2]
                            if (timestamp >= clearedAt && tag != "UnspekTrace" &&
                                (level in listOf("W", "E", "F") || tag !in NOISY_TAGS && !tag.startsWith("VRI["))) {
                                append("logcat $line")
                            }
                        } else if (!line.startsWith("---------")) append("logcat $line")
                    }
                }
                throw IllegalStateException("logcat exited (${process.waitFor()})")
            } catch (error: Exception) {
                mutableState.update { it.copy(nativeLogs = false, readerError = "Native log capture interrupted: ${error.message}") }
                append("${Instant.now()} W [Recorder] ${error.message}; retryMs=$delayMs")
            } finally { process?.destroy() }
            Thread.sleep(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun <T> onWriter(action: () -> T): T = withContext(Dispatchers.IO) {
        check(started.get()) { "Log recorder has not started" }
        val result = CompletableFuture<T>()
        check(queue.offer(Work.Action {
            try { result.complete(action()) } catch (error: Exception) { result.completeExceptionally(error) }
        }, 5, TimeUnit.SECONDS)) { "Log recorder is busy" }
        result.get(10, TimeUnit.SECONDS)
    }

    suspend fun saveReport(): File = withContext(Dispatchers.IO) {
        reportLock.withLock {
            event("Recorder", "report_requested")
            createReport(includeCameraCapabilities = true)
        }
    }

    private suspend fun createReport(includeCameraCapabilities: Boolean): File {
        val logs = onWriter { requireNotNull(store).snapshot() }
        val reports = File(application.cacheDir, "diagnostic-reports").apply { check(isDirectory || mkdirs()) }
        val output = File(reports, "Unspektrawesome-log-${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.entry("device-and-app.txt", (DiagnosticReport.deviceInfo(application, includeCameraCapabilities) + "\nRecorder: ${state.value}\n").toByteArray())
                logs.forEach { (name, bytes) -> zip.entry("logs/$name", bytes) }
                DiagnosticReport.writeFile(zip, "last-jvm-crash.txt", File(directory, "last-jvm-crash.txt"))
                DiagnosticReport.writeExits(zip, application, clearedAt)
            }
            reports.listFiles()?.filter { it.extension == "zip" && it != output }?.sortedByDescending { it.lastModified() }
                ?.drop(2)?.forEach { it.delete() }
            return output
        } catch (error: Exception) { output.delete(); throw error }
    }

    suspend fun exportReport(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val output = application.contentResolver.openOutputStream(uri, "wt") ?: error("Unable to open the selected file")
        output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
    }

    fun shareIntent(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", report)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Unspektrawesome diagnostic report")
            clipData = ClipData.newRawUri("Diagnostic report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, com.unspektrawesome.language.AppLanguage.text("Share diagnostic report"))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        reportLock.withLock {
            onWriter {
                val now = System.currentTimeMillis()
                File(directory, "cleared-at").writeText(now.toString())
                clearedAt = now
                requireNotNull(store).clear()
                val crash = File(directory, "last-jvm-crash.txt")
                check(!crash.exists() || crash.delete()) { "Unable to clear the crash log" }
                File(application.cacheDir, "diagnostic-reports").listFiles()?.filter { it.extension == "zip" }
                    ?.forEach { check(it.delete()) { "Unable to clear a saved report" } }
                publicLogs.clear()
                publicRetryAt = 0
                mutableState.update { it.copy(automaticLogError = null, automaticReportError = null) }
                dropped.set(0)
            }
            event("Recorder", "logs_cleared")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = "${Instant.now()} version=${BuildConfig.VERSION_NAME} pid=${Process.myPid()} " +
                    "thread=${thread.name}\n${Log.getStackTraceString(error).take(64_000)}"
                runCatching {
                    directory.mkdirs()
                    FileOutputStream(File(directory, "last-jvm-crash.txt")).use { it.write(text.toByteArray()); it.fd.sync() }
                }
                publicLogs.appendCrash(text)
            } catch (_: Throwable) {
                // Crash recording must always return control to Android's handler.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
    }

    private val NOISY_TAGS = setOf("Choreographer", "CompatChangeReporter", "InsetsController", "InsetsSourceConsumer",
        "ViewRootImpl", "HWUI", "OpenGLRenderer", "ProfileInstaller", "MIUIInput", "ImeTracker")
}
