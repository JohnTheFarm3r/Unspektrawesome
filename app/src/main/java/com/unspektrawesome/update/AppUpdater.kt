package com.unspektrawesome.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.FileProvider
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.io.readUpTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal class AppUpdater(private val context: Context) {
    suspend fun check(): UpdateRelease? = withContext(Dispatchers.IO) {
        val results = UpdateRelease.REPOSITORIES.map { repository ->
            currentCoroutineContext().ensureActive()
            runCatching { checkRepository(repository) }
        }
        currentCoroutineContext().ensureActive()
        UpdateRelease.newestOf(results)
    }

    private fun checkRepository(repository: String): UpdateRelease? {
        val connection = connect(UpdateRelease.latestUrl(repository), "application/vnd.github+json")
        return try {
            when (val status = connection.responseCode) {
                404 -> null
                200 -> {
                    val json = connection.inputStream.use { input ->
                        val bytes = input.readUpTo(1_048_577)
                        require(bytes.size <= 1_048_576) { "The update response is too large." }
                        bytes.toString(Charsets.UTF_8)
                    }
                    UpdateRelease.parse(json, BuildConfig.VERSION_NAME.removeSuffix("-testing"), repository)
                }
                403, 429 -> throw IOException("GitHub is limiting update checks. Try again later.")
                else -> throw IOException("The update server returned $status. Try again later.")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(release: UpdateRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "update.part")
        val apk = File(directory, "update.apk")
        apk.delete()
        val connection = connect(release.downloadUrl, "application/octet-stream")
        try {
            check(connection.responseCode == 200) { "The APK could not be downloaded. Try again." }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        received += count
                        require(received <= release.size) { "The downloaded APK has an unexpected size." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val percent = (received * 100 / release.size).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(received.toFloat() / release.size) }
                        }
                    }
                }
            }
            require(received == release.size) { "The download was interrupted. Try again." }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(release.sha256 == null || checksum == release.sha256) { "The APK checksum did not match. Try again." }
            validate(partial, release)
            check(partial.renameTo(apk)) { "The downloaded APK could not be saved." }
            apk
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    fun validate(file: File, release: UpdateRelease) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("The downloaded file is not a valid APK.")
        val installed = manager.getPackageInfo(context.packageName, flags)
        identity(archive).validate(identity(installed), release.version, Build.VERSION.SDK_INT)
    }

    private fun identity(info: PackageInfo): UpdateApkIdentity {
        val application = requireNotNull(info.applicationInfo)
        return UpdateApkIdentity(info.packageName, info.longVersionCode, info.versionName,
            application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            application.metaData?.getString("com.unspektrawesome.DISTRIBUTION_CHANNEL"), application.minSdkVersion,
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty())
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun connect(address: String, accept: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Unspektrawesome/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
}
