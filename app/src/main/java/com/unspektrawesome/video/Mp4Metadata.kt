package com.unspektrawesome.video

import com.unspektrawesome.io.EmbeddedSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal object Mp4Metadata {
    private data class Atom(val offset: Long, val size: Long, val header: Int, val type: String)
    private val fourCc = Charsets.ISO_8859_1
    private const val MAX_SETTINGS_BYTES = 16 * 1024 * 1024
    private val settingsId = java.util.UUID.fromString("fde798e0-d8cd-4bca-b1aa-c766f72685a7").let {
        ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
    }

    private fun isSettings(input: FileChannel, atom: Atom): Boolean = atom.type == "uuid" &&
        atom.size >= atom.header + settingsId.size && read(input, atom.offset + atom.header, settingsId.size).contentEquals(settingsId)

    fun settings(input: FileChannel, offset: Long = 0, length: Long = input.size() - offset): String? {
        val movie = atoms(input, offset, offset + length).firstOrNull { it.type == "moov" } ?: return null
        val userData = atoms(input, movie.offset + movie.header, movie.offset + movie.size)
            .firstOrNull { it.type == "udta" } ?: return null
        val entries = atoms(input, userData.offset + userData.header, userData.offset + userData.size)
        val embedded = entries.filter { isSettings(input, it) }
        require(embedded.size <= 1)
        embedded.singleOrNull()?.let {
            val count = it.size - it.header - settingsId.size
            require(count in 10..MAX_SETTINGS_BYTES.toLong())
            return EmbeddedSettings.decode(read(input, it.offset + it.header + settingsId.size, count.toInt()), MAX_SETTINGS_BYTES)
        }
        val meta = entries.firstOrNull { it.type == "meta" } ?: return null
        val items = atoms(input, meta.offset + meta.header + 4, meta.offset + meta.size)
            .firstOrNull { it.type == "ilst" } ?: return null
        for (item in atoms(input, items.offset + items.header, items.offset + items.size).filter { it.type == "----" }) {
            if (item.size > 16 * 1024 * 1024) continue
            val fields = atoms(input, item.offset + item.header, item.offset + item.size)
            fun value(type: String, prefix: Int): String? = fields.firstOrNull { it.type == type }?.let {
                require(it.size >= it.header + prefix)
                read(input, it.offset + it.header + prefix, (it.size - it.header - prefix).toInt()).toString(Charsets.UTF_8)
            }
            if (value("mean", 4) == "com.unspektrawesome" && value("name", 4) == "capture") return value("data", 8)
        }
        return null
    }

    fun write(input: FileChannel, output: FileChannel, software: String, description: String, settings: String) {
        val originalSize = input.size()
        val atoms = atoms(input, 0, originalSize)
        val movie = atoms.single { it.type == "moov" }
        require(movie.size <= 256 * 1024 * 1024) { "Movie index is too large" }
        val children = atoms(input, movie.offset + movie.header, movie.offset + movie.size)
        val userData = children.singleOrNull { it.type == "udta" }
        val entries = userData?.let { atoms(input, it.offset + it.header, it.offset + it.size) }.orEmpty()
        val meta = entries.singleOrNull { it.type == "meta" }
        val metaChildren = meta?.let { atoms(input, it.offset + it.header + 4, it.offset + it.size) }.orEmpty()
        val itemList = metaChildren.singleOrNull { it.type == "ilst" }
        val existingItems = itemList?.let { atoms(input, it.offset + it.header, it.offset + it.size) }.orEmpty()
        val tags = atom("ilst", join(existingItems.filter { it.type !in setOf("©too", "©cmt", "----") }.map { read(input, it) }) +
            textTag("©too", software) + textTag("©cmt", description))
        val handler = metaChildren.firstOrNull { it.type == "hdlr" }?.let { read(input, it) }
            ?: atom("hdlr", ByteArray(8) + "mdir".toByteArray(fourCc) + ByteArray(13))
        val metadata = atom("meta", ByteArray(4) + handler +
            join(metaChildren.filter { it.type !in setOf("hdlr", "ilst") }.map { read(input, it) }) + tags)
        val payload = EmbeddedSettings.encode(settings, MAX_SETTINGS_BYTES)
        require(payload.size <= MAX_SETTINGS_BYTES)
        val updatedUserData = atom("udta", join(entries.filter { it.type != "meta" && !isSettings(input, it) }.map { read(input, it) }) +
            metadata + atom("uuid", settingsId + payload))
        val updatedMovie = atom("moov", join(children.filter { it.type != "udta" }.map { read(input, it) }) + updatedUserData)
        try {
            // Appending the index leaves every media chunk offset unchanged.
            write(output, originalSize, updatedMovie)
            output.force(true)
            write(output, movie.offset + 4, "free".toByteArray(fourCc))
            output.force(true)
        } catch (error: Exception) {
            runCatching {
                write(output, movie.offset + 4, "moov".toByteArray(fourCc))
                output.truncate(originalSize)
                output.force(true)
            }
            throw error
        }
    }

    private fun atoms(input: FileChannel, start: Long, end: Long): List<Atom> = buildList {
        var offset = start
        while (offset < end) {
            require(end - offset >= 8) { "Truncated MP4 atom" }
            val header = ByteBuffer.wrap(read(input, offset, 8)).order(ByteOrder.BIG_ENDIAN)
            val shortSize = header.int.toLong() and 0xffffffffL
            val type = ByteArray(4).also { header.get(it) }.toString(fourCc)
            val headerSize = if (shortSize == 1L) 16 else 8
            val size = if (shortSize == 1L) ByteBuffer.wrap(read(input, offset + 8, 8)).long else shortSize
            require(size >= headerSize && size <= end - offset) { "Invalid MP4 atom size" }
            add(Atom(offset, size, headerSize, type))
            offset += size
        }
    }

    private fun read(input: FileChannel, atom: Atom): ByteArray = read(input, atom.offset, atom.size.toInt())
    private fun read(input: FileChannel, offset: Long, count: Int): ByteArray {
        val bytes = ByteBuffer.allocate(count)
        while (bytes.hasRemaining()) check(input.read(bytes, offset + bytes.position()) > 0) { "Truncated MP4" }
        return bytes.array()
    }

    private fun write(output: FileChannel, offset: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) check(output.write(buffer, offset + buffer.position()) > 0) { "Unable to write MP4 metadata" }
    }

    private fun atom(type: String, bytes: ByteArray): ByteArray = ByteBuffer.allocate(8 + bytes.size)
        .putInt(8 + bytes.size).put(type.toByteArray(fourCc)).put(bytes).array()
    private fun data(text: String): ByteArray = atom("data", ByteBuffer.allocate(8).putInt(1).putInt(0).array() + text.toByteArray())
    private fun textTag(type: String, text: String): ByteArray = atom(type, data(text))
    private fun join(values: List<ByteArray>): ByteArray = ByteBuffer.allocate(values.sumOf { it.size }).apply {
        values.forEach { put(it) }
    }.array()
}
