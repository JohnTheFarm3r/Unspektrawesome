package com.unspektrawesome.calibration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

data class ImportedDcp(val id: String, val fileName: String)

class DcpRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "color-profiles")
    private val cache = object : LinkedHashMap<String, DcpProfile>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DcpProfile>?) = size > 8
    }

    @Synchronized fun import(uri: Uri): ImportedDcp {
        val fileName = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.take(200)?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.take(200) ?: "Custom DCP.dcp"
        val bytes = resolver.openInputStream(uri)?.use(DcpParser::readBytes) ?: error("DCP file could not be opened")
        return import(bytes, fileName)
    }

    @Synchronized fun import(bytes: ByteArray, fileName: String): ImportedDcp {
        val profile = DcpParser.parse(bytes)
        val id = digest(bytes)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create DCP storage" }
        val file = file(id)
        if (!file.exists() || runCatching { digest(file.inputStream().use(DcpParser::readBytes)) }.getOrNull() != id) {
            val temporary = File.createTempFile("import-", ".tmp", directory)
            try {
                temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
                check(temporary.renameTo(file)) { "Unable to save imported DCP" }
            } finally { temporary.delete() }
        }
        cache[id] = profile
        return ImportedDcp(id, fileName)
    }

    @Synchronized fun readOriginal(id: String): ByteArray {
        val bytes = file(id).inputStream().use(DcpParser::readBytes)
        check(digest(bytes) == id) { "Imported DCP is damaged. Import the profile again." }
        DcpParser.parse(bytes)
        return bytes
    }

    @Synchronized fun load(id: String): DcpProfile {
        cache[id]?.let { return it }
        val bytes = file(id).inputStream().use(DcpParser::readBytes)
        check(digest(bytes) == id) { "Imported DCP is damaged. Import the profile again." }
        return DcpParser.parse(bytes).also { cache[id] = it }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid imported DCP identifier" }
        return File(directory, "$id.dcp")
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
