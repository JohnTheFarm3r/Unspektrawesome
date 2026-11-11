package com.unspektrawesome.preview

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.RawPreviewPolicy

/**
 * Remembers which lens configuration is starting so that a process crash during startup
 * is reported as a failed lens session on the next launch instead of reopening the same lens.
 */
internal class LensStartupGuard(
    private val preferences: SharedPreferences,
    private val currentPid: Int = Process.myPid(),
    private val exitReason: (Int) -> Int?,
) {
    private var pending: String? = null

    /** The configuration that was starting when the previous process crashed, if any. */
    val crashedConfiguration: String? = run {
        val configuration = preferences.getString(CONFIGURATION_KEY, null)
        val pid = preferences.getInt(PID_KEY, 0)
        if (configuration != null) preferences.edit().remove(CONFIGURATION_KEY).remove(PID_KEY).commit()
        configuration?.takeIf { pid > 0 && pid != currentPid && exitReason(pid) in CRASH_REASONS }
    }

    // Committed synchronously because a native crash leaves no chance to flush later.
    fun starting(configuration: String) {
        if (pending == configuration) return
        pending = configuration
        preferences.edit().putString(CONFIGURATION_KEY, configuration).putInt(PID_KEY, currentPid).commit()
    }

    fun finished() {
        if (pending == null) return
        pending = null
        preferences.edit().remove(CONFIGURATION_KEY).remove(PID_KEY).apply()
    }

    companion object {
        private const val CONFIGURATION_KEY = "starting_configuration"
        private const val PID_KEY = "starting_pid"
        private val CRASH_REASONS = setOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH)

        fun create(context: Context): LensStartupGuard {
            val application = context.applicationContext
            val activityManager = application.getSystemService(ActivityManager::class.java)
            return LensStartupGuard(application.getSharedPreferences("lens_startup_guard", Context.MODE_PRIVATE)) { pid ->
                runCatching {
                    activityManager?.getHistoricalProcessExitReasons(application.packageName, pid, 1)
                        ?.firstOrNull()?.reason
                }.getOrNull()
            }
        }

        fun configuration(camera: CameraDescriptor, plan: RawPreviewPolicy.Plan): String =
            "${camera.route.routeId}|${plan.acquisitionOutput.format}|${plan.acquisitionOutput.size}"
    }
}
