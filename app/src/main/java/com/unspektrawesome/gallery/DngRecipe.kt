package com.unspektrawesome.gallery

import com.unspektrawesome.preview.PersistedCapture
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID

internal data class DngRecipe(val id: String, val owner: String?, val capture: PersistedCapture, val record: DngQueueRecord?) {
    companion object {
        fun read(input: FileChannel): DngRecipe? {
            val description = description(input) ?: return null
            if (!description.trimStart().startsWith("{")) return null
            val json = JSONObject(description)
            if (!json.has("unspektrawesomeRecipe")) return null
            require(json.getInt("unspektrawesomeRecipe") == 1)
            val value = json.getJSONObject("capture")
            val capture = PersistedCapture.fromJson(value)
            GalleryProcessingService.validate(capture)
            require(capture.raw.getInt(0) > 0 && capture.raw.getInt(4) > 0)
            require(capture.raw.getInt(0).toLong() * capture.raw.getInt(4) * 2 <= Int.MAX_VALUE)
            val record = DngQueueJournal.read(input)
            val savedId = json.optString("captureId").takeIf { it.isNotBlank() }
            require(savedId == null || record == null || savedId == record.id)
            val id = savedId ?: record?.id ?: "recovered-${UUID.nameUUIDFromBytes(value.toString().toByteArray(Charsets.UTF_8))}"
            require(id.matches(Regex("[A-Za-z0-9_-]{1,256}")))
            return DngRecipe(id, json.optString("packageName").takeIf { it.isNotBlank() } ?: record?.owner, capture, record)
        }

        /** Read only bounded metadata, including thumbnail-first and SubIFD DNGs. */
        internal fun description(input: FileChannel): String? {
            val size = input.size()
            fun read(at: Long, length: Int, order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteBuffer {
                require(at >= 0 && length >= 0 && at <= size - length)
                return ByteBuffer.allocate(length).order(order).apply {
                    while (hasRemaining()) check(input.read(this, at + position()) > 0)
                    flip()
                }
            }
            val header = read(0, 8)
            val order = when (header.getShort(0).toInt() and 65535) {
                0x4949 -> ByteOrder.LITTLE_ENDIAN
                0x4d4d -> ByteOrder.BIG_ENDIAN
                else -> return null
            }
            header.order(order)
            require(header.getShort(2).toInt() == 42)
            fun uint(value: Int) = value.toLong() and 0xffffffffL
            val pending = ArrayDeque<Long>().apply { add(uint(header.getInt(4))) }
            val visited = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val at = pending.removeFirst()
                require(at >= 8 && visited.size < 32 && visited.add(at))
                val count = read(at, 2, order).short.toInt() and 65535
                require(count in 1..1024)
                val directory = read(at + 2, count * 12 + 4, order)
                repeat(count) { index ->
                    val entry = index * 12
                    val tag = directory.getShort(entry).toInt() and 65535
                    val type = directory.getShort(entry + 2).toInt() and 65535
                    val length = uint(directory.getInt(entry + 4))
                    val offset = uint(directory.getInt(entry + 8))
                    if (tag == 270) {
                        require(type == 2 && length in 1..32_000_000)
                        val bytes = read(if (length <= 4) at + 2 + entry + 8 else offset, length.toInt()).array()
                        val text = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                        if (text.contains("unspektrawesomeRecipe")) return text
                    } else if (tag == 330) {
                        require(type == 4 && length in 1..32)
                        val children = if (length == 1L) listOf(offset) else {
                            val values = read(offset, length.toInt() * 4, order)
                            List(length.toInt()) { uint(values.int) }
                        }
                        children.filter { it > 0 }.forEach(pending::add)
                    }
                }
                uint(directory.getInt(count * 12)).takeIf { it > 0 }?.let(pending::add)
            }
            return null
        }
    }
}
