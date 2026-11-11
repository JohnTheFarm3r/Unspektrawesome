package com.unspektrawesome.diagnostics

import java.io.File
import java.io.FileOutputStream

internal class RollingLogStore(
    private val directory: File,
    private val fileBytes: Int = 1024 * 1024,
    private val fileCount: Int = 4,
) {
    init {
        require(fileBytes > 0 && fileCount > 0)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create log directory" }
    }

    private fun file(index: Int) = File(directory, "events-$index.log")

    fun append(lines: List<String>) {
        var output: FileOutputStream? = null
        try {
            var size = file(0).length()
            for (line in lines) {
                val bytes = (line.take(fileBytes / 4) + "\n").toByteArray(Charsets.UTF_8)
                if (size + bytes.size > fileBytes) {
                    output?.close()
                    output = null
                    check(!file(fileCount - 1).exists() || file(fileCount - 1).delete()) { "Unable to rotate logs" }
                    for (index in fileCount - 2 downTo 0) {
                        if (file(index).exists()) check(file(index).renameTo(file(index + 1))) { "Unable to rotate logs" }
                    }
                    size = 0
                }
                if (output == null) output = FileOutputStream(file(0), true)
                output.write(bytes)
                size += bytes.size
            }
        } finally {
            output?.close()
        }
    }

    fun snapshot(): List<Pair<String, ByteArray>> = (fileCount - 1 downTo 0).mapNotNull { index ->
        file(index).takeIf { it.isFile }?.let { it.name to it.readBytes() }
    }

    fun sizeBytes(): Long = (0 until fileCount).sumOf { file(it).length() }

    fun clear() {
        (0 until fileCount).forEach { index ->
            check(!file(index).exists() || file(index).delete()) { "Unable to clear logs" }
        }
    }
}
