package com.unspektrawesome.capture

import android.os.Build
import com.unspektrawesome.BuildConfig
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CaptureIdentity(
    val appName: String,
    val appVersion: String,
    val appVersionCode: Int,
    val manufacturer: String,
    val model: String,
    val codename: String,
    val marketingName: String,
) {
    val software: String get() = "$appName $appVersion ($appVersionCode)"

    fun toJson(): JSONObject = JSONObject().apply {
        put("appName", appName)
        put("appVersion", appVersion)
        put("appVersionCode", appVersionCode)
        put("manufacturer", manufacturer)
        put("model", model)
        put("codename", codename)
        put("marketingName", marketingName)
    }

    companion object {
        val current: CaptureIdentity by lazy {
            CaptureIdentity("Unspektrawesome", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
                sequenceOf("ro.product.marketname", "ro.product.vendor.marketname", "ro.config.marketing_name")
                    .mapNotNull(::readProperty).firstOrNull() ?: Build.MODEL)
        }

        fun fromJson(value: JSONObject) = CaptureIdentity(
            value.getString("appName"), value.getString("appVersion"), value.getInt("appVersionCode"),
            value.getString("manufacturer"), value.getString("model"), value.getString("codename"),
            value.getString("marketingName"),
        )

        private fun readProperty(name: String): String? = runCatching {
            val process = ProcessBuilder("/system/bin/getprop", name).start()
            try {
                if (!process.waitFor(200, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
                else process.inputStream.bufferedReader().use { it.readText().trim() }
                    .takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
            } finally {
                process.destroy()
            }
        }.getOrNull()
    }
}
