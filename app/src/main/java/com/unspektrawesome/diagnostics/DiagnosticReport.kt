package com.unspektrawesome.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.settings.CameraPreferencesStore
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticReport {
    fun deviceInfo(context: Context, includeCameraCapabilities: Boolean = true): String = buildString {
        appendLine("Unspektrawesome diagnostic report")
        appendLine("Created: ${Instant.now()}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${BuildConfig.BUILD_TYPE}")
        appendLine("Package: ${context.packageName}")
        appendLine("Preview diagnostics: ${if (BuildConfig.DEBUG) InternalLogRecorder.PREVIEW_DIAGNOSTICS_VERSION else 0}; native events: direct recorder")
        appendLine("Vulkan validation requested: ${BuildConfig.VULKAN_VALIDATION}; activation and callback checks are recorded in events")
        section("Installation") {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("Updated: ${Instant.ofEpochMilli(info.lastUpdateTime)}")
            appendLine("Debuggable: ${context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        }
        appendLine("Device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}")
        appendLine("Product: ${Build.PRODUCT} / ${Build.DEVICE}; board=${Build.BOARD}; hardware=${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= 31) appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE}; SDK=${Build.VERSION.SDK_INT}; patch=${Build.VERSION.SECURITY_PATCH}")
        appendLine("OS build: ${Build.DISPLAY}; fingerprint=${Build.FINGERPRINT}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Uptime: ${SystemClock.elapsedRealtime()} ms")
        section("Resources") { appendLine(resources(context)) }
        section("Camera settings") {
            val preferences = CameraPreferencesStore(context).load()
            appendLine("Deferred=${preferences.processPhotosLater}; previewEdge=${preferences.previewShortEdge}; " +
                "aeEngine=${preferences.aeEngine}; aeMeter=${preferences.aeMeter}")
            preferences.lensProfiles.forEach { profile ->
                appendLine("route=${profile.routeId}; active=${profile.id == preferences.activeLensProfileId}; " +
                    "enabled=${profile.enabled}; stream=${profile.rawStream}; previewEdge=${profile.previewShortEdge}; aeEngine=${profile.aeEngine}")
            }
            val gallery = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
            appendLine("Export priority=${gallery.getString("priority", null)}; pauseWhenHot=${gallery.getBoolean("pause_when_hot", true)}")
        }
        if (includeCameraCapabilities) section("Camera capabilities") {
            val manager = context.getSystemService(CameraManager::class.java)
            for (id in manager.cameraIdList.take(24)) section("Camera $id") {
                val c = manager.getCameraCharacteristics(id)
                appendLine("hardwareLevel=${c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}; " +
                    "facing=${c.get(CameraCharacteristics.LENS_FACING)}; physicalIds=${c.physicalCameraIds}")
                appendLine("capabilities=${c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contentToString()}")
                appendLine("pixelArray=${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}; " +
                    "activeArray=${c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}; " +
                    "cfa=${c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)}")
                appendLine("white=${c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)}; " +
                    "black=${c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)}; " +
                    "orientation=${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
                appendLine("fps=${c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.contentToString()}")
                val streams = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                for (format in listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12)) {
                    appendLine("format=$format sizes=${streams?.getOutputSizes(format)?.joinToString()}")
                }
            }
        }
        appendLine("Reports contain app logs and numerical image samples, without photos or RAW pixel buffers.")
    }

    fun resources(context: Context): String {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        return "ramAvailable=${memory.availMem}; ramTotal=${memory.totalMem}; lowMemory=${memory.lowMemory}; " +
            "heapUsed=${runtime.totalMemory() - runtime.freeMemory()}; heapMax=${runtime.maxMemory()}; " +
            "diskFree=${context.filesDir.usableSpace}; thermal=${context.getSystemService(PowerManager::class.java).currentThermalStatus}"
    }

    fun writeExits(zip: ZipOutputStream, context: Context, since: Long) {
        val summary = buildString {
            section("Previous app process exits") {
                val exits = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp >= since }
                if (exits.isEmpty()) appendLine("No retained exits since logs were cleared.")
                exits.forEachIndexed { index, exit ->
                    appendLine("${Instant.ofEpochMilli(exit.timestamp)} pid=${exit.pid} reason=${exit.reason} (${exitReasonName(exit.reason)}) " +
                        "status=${exit.status} importance=${exit.importance} pssKb=${exit.pss} rssKb=${exit.rss} " +
                        "description=${exit.description}")
                    if (exit.reason == ApplicationExitInfo.REASON_ANR || exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        section("Trace $index") {
                            val bytes = exit.traceInputStream?.use { readTrace(it) }
                            when {
                                bytes == null -> appendLine("Trace unavailable")
                                bytes.size > MAX_TRACE_BYTES -> appendLine("Trace exceeds the report size limit")
                                else -> {
                                    val extension = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) "pb" else "txt"
                                    val name = "exits/${exit.timestamp}-$index.$extension"
                                    zip.entry(name, bytes)
                                    appendLine("Trace: $name")
                                }
                            }
                        }
                    }
                }
            }
        }
        zip.entry("process-exits.txt", summary.toByteArray())
    }

    fun writeFile(zip: ZipOutputStream, name: String, file: File) {
        if (file.isFile) zip.entry(name, file.readBytes())
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        else -> "UNKNOWN"
    }

    private fun readTrace(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_TRACE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_TRACE_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private inline fun StringBuilder.section(name: String, body: StringBuilder.() -> Unit) {
        appendLine("\n[$name]")
        try { body() } catch (error: Exception) { appendLine("Unavailable: ${error.javaClass.simpleName}: ${error.message}") }
    }

    private const val MAX_TRACE_BYTES = 2 * 1024 * 1024
}
