package com.unspektrawesome.diagnostics

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import com.unspektrawesome.BuildConfig
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PublicDiagnosticLogs(
    private val context: Context,
    private val relativePath: String = relativePath(context),
    private val maxFileBytes: Int = 1024 * 1024,
    private val liveFileCount: Int = 4,
    private val reportCount: Int = 3,
) : Closeable {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val lock = ReentrantLock()
    private val session = "${System.currentTimeMillis()}-${Process.myPid()}-${UUID.randomUUID().toString().take(8)}"
    private var segment = 0
    private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var bytesWritten = 0

    fun append(lines: List<String>) = lock.withLock {
        try {
            for (line in lines) {
                val bytes = (line + "\n").toByteArray()
                if (output == null || bytesWritten + bytes.size > maxFileBytes) openSession()
                requireNotNull(output).write(bytes)
                bytesWritten += bytes.size
            }
        } catch (error: Exception) {
            closeOutput()
            throw error
        }
    }

    fun appendCrash(text: String) {
        if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) return
        try {
            output?.let { stream ->
                stream.write(("\n[Uncaught exception]\n$text\n").toByteArray())
                stream.fd.sync()
            }
        } finally { lock.unlock() }
    }

    fun publishReport(report: File): Uri {
        val uri = create("${REPORT_PREFIX}$session.zip", "application/zip", pending = true)
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: error("Unable to open automatic report")
            stream.use { destination -> report.inputStream().use { it.copyTo(destination) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            trim(REPORT_PREFIX, reportCount, uri)
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun removeIncompleteReports() {
        ownedFiles().filter { it.pending && it.name.startsWith(REPORT_PREFIX) }
            .forEach { resolver.delete(it.uri, null, null) }
    }

    fun clear() = lock.withLock {
        closeOutput()
        ownedFiles().forEach { check(resolver.delete(it.uri, null, null) == 1) }
    }

    override fun close() = lock.withLock { closeOutput() }

    private fun openSession() {
        closeOutput()
        val uri = create("${SESSION_PREFIX}$session-${segment++}.txt", "text/plain", pending = false)
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: error("Unable to open automatic log")
            output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            val header = ("Unspektrawesome session log\n" +
                "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${BuildConfig.BUILD_TYPE}\n" +
                "Package: ${context.packageName}\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android: ${Build.VERSION.RELEASE}; SDK=${Build.VERSION.SDK_INT}\n" +
                "OS build: ${Build.FINGERPRINT}\n\n").toByteArray()
            requireNotNull(output).write(header)
            bytesWritten = header.size
            trim(SESSION_PREFIX, liveFileCount, uri)
        } catch (error: Exception) {
            closeOutput()
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun closeOutput() {
        runCatching { output?.close() }
        output = null
        bytesWritten = 0
    }

    private fun create(name: String, mimeType: String, pending: Boolean): Uri =
        resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }) ?: error("Unable to create automatic log")

    private fun trim(prefix: String, keep: Int, current: Uri) {
        ownedFiles().filter { it.name.startsWith(prefix) && !it.pending && it.uri != current }
            .drop((keep - 1).coerceAtLeast(0)).forEach { resolver.delete(it.uri, null, null) }
    }

    private data class StoredFile(val uri: Uri, val name: String, val pending: Boolean)

    private fun ownedFiles(): List<StoredFile> {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath, context.packageName))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} DESC")
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return buildList {
            resolver.query(collection, columns, query, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name.startsWith(SESSION_PREFIX) && (name.endsWith(".txt") || name.endsWith(".log")) ||
                        name.startsWith(REPORT_PREFIX) && name.endsWith(".zip")) {
                        add(StoredFile(ContentUris.withAppendedId(collection, cursor.getLong(0)), name, cursor.getInt(2) != 0))
                    }
                }
            }
        }
    }

    companion object {
        private const val SESSION_PREFIX = "Unspektrawesome-session-"
        private const val REPORT_PREFIX = "Unspektrawesome-startup-"

        fun relativePath(context: Context): String =
            "${Environment.DIRECTORY_DOWNLOADS}/Unspektrawesome/logs/" +
                if (context.packageName.endsWith(".testing")) "testing/" else ""
    }
}
