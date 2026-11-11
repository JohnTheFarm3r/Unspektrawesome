package com.unspektrawesome.gallery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

internal data class DngQueueRecord(val id: String, val owner: String, val state: ExportState, val hidden: Boolean = false)

/** Two checksummed slots after the TIFF data keep queue changes separate from image bytes. */
internal object DngQueueJournal {
    private val magic = byteArrayOf(85, 78, 83, 80, 69, 75, 84, 82, 65, 81, 85, 69, 85, 69, 1, 0)
    internal const val SLOT_BYTES = 1024
    private const val HASH_BYTES = 32
    private data class Slot(val base: Long, val sequence: Long, val index: Int, val record: DngQueueRecord)

    fun read(input: FileChannel): DngQueueRecord? = latest(input)?.record

    fun write(input: FileChannel, output: FileChannel, record: DngQueueRecord): Long {
        require(record.id.length in 1..256 && record.owner.length in 1..256)
        val previous = latest(input)
        if (previous?.record == record) return input.size()
        val base = previous?.base ?: input.size()
        val index = previous?.let { 1 - it.index } ?: 0
        val sequence = (previous?.sequence ?: 0) + 1
        require(base >= 8 && sequence > 0)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeUTF(record.id); it.writeUTF(record.owner)
                it.writeUTF(record.state.name); it.writeBoolean(record.hidden)
            }
        }.toByteArray()
        require(payload.size <= SLOT_BYTES - HASH_BYTES - 40)
        val slot = ByteBuffer.allocate(SLOT_BYTES)
        slot.put(magic).putLong(base).putLong(sequence).putInt(index).putInt(payload.size).put(payload)
        val hash = MessageDigest.getInstance("SHA-256").digest(slot.array().copyOf(SLOT_BYTES - HASH_BYTES))
        slot.position(SLOT_BYTES - HASH_BYTES); slot.put(hash); slot.flip()
        val at = base + index * SLOT_BYTES
        while (slot.hasRemaining()) check(output.write(slot, at + slot.position()) > 0)
        output.force(true)
        return output.size()
    }

    private fun latest(input: FileChannel): Slot? {
        val size = input.size()
        val start = (size - SLOT_BYTES * 3).coerceAtLeast(0)
        val tail = ByteBuffer.allocate((size - start).toInt())
        while (tail.hasRemaining()) check(input.read(tail, start + tail.position()) > 0)
        val bytes = tail.array()
        var latest: Slot? = null
        for (offset in 0..bytes.size - SLOT_BYTES) {
            if (!magic.indices.all { bytes[offset + it] == magic[it] }) continue
            val value = runCatching {
                val slot = ByteBuffer.wrap(bytes, offset, SLOT_BYTES).slice()
                val base = slot.getLong(16)
                val sequence = slot.getLong(24)
                val index = slot.getInt(32)
                val length = slot.getInt(36)
                require(base >= 8 && sequence > 0 && index in 0..1 && base + index * SLOT_BYTES == start + offset)
                require(size <= base + SLOT_BYTES * 2 && length in 1..SLOT_BYTES - HASH_BYTES - 40)
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(offset, offset + SLOT_BYTES - HASH_BYTES))
                require(MessageDigest.isEqual(actual, bytes.copyOfRange(offset + SLOT_BYTES - HASH_BYTES, offset + SLOT_BYTES)))
                val record = DataInputStream(ByteArrayInputStream(bytes, offset + 40, length)).use {
                    DngQueueRecord(it.readUTF(), it.readUTF(), ExportState.valueOf(it.readUTF()), it.readBoolean())
                }
                require(record.id.length in 1..256 && record.owner.length in 1..256)
                Slot(base, sequence, index, record)
            }.getOrNull() ?: continue
            if (latest == null || value.sequence > latest.sequence) latest = value
        }
        return latest
    }
}
