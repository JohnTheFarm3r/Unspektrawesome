package com.unspektrawesome.camera.session

import com.unspektrawesome.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Debug-build override that makes cameras advertise a lower maximum ISO than they really support,
 * to reproduce cameras that apply more gain than their advertised range.
 *
 * Enable with `adb shell setprop debug.unspektra.iso_report_max 800`, then restart the app.
 * Clear it with `adb shell setprop debug.unspektra.iso_report_max 0`.
 */
object IsoRangeDebug {
    private val limit: Int by lazy {
        if (!BuildConfig.DEBUG) 0 else readProperty("debug.unspektra.iso_report_max")?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }

    @JvmStatic
    fun advertisedMax(real: Int): Int = if (limit > 0) minOf(real, limit) else real

    private fun readProperty(name: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name).start()
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else process.inputStream.bufferedReader().use { it.readText().trim() }
        } finally {
            process.destroy()
        }
    }.getOrNull()
}
