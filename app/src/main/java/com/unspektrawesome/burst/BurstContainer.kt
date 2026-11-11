package com.unspektrawesome.burst

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.unspektrawesome.preview.PersistedCapture
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32

internal object BurstContainer {
    const val MIME_TYPE = "application/x-unspektra"
    const val DIRECTORY = "DCIM/Unspektrawesome/BURST/"
    private const val MAGIC = 0x5350454b54524133L
    private const val VERSION = 1
    private const val MAX_RECORD = 300 * 1024 * 1024

    class Writer(private val context: Context, val capturedAt: Long) : AutoCloseable {
        val uri: Uri = BurstMedia.create(context, "Unspektrawesome-$capturedAt.spektra")
        private val stream = try {
            ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw")))
        } catch (error: Exception) { context.contentResolver.delete(uri, null, null); throw error }
        private val channel = stream.channel
        private val frames = JSONArray()
        private var complete = false
        private var lastTimestamp = Long.MIN_VALUE

        init { write(channel, ByteBuffer.allocate(20).putLong(MAGIC).putInt(VERSION).putLong(0).flip() as ByteBuffer) }

        fun append(frame: BurstFrame) = frame.withEncoded { append(frame.capture, it) }

        fun append(capture: PersistedCapture, handle: Long) {
            val timestamp = capture.metadata.sensorTimestampNs
            check(!complete && frames.length() < BurstMemoryPlan.MAX_FRAMES && timestamp > lastTimestamp)
            val json = capture.toJson().toString().toByteArray(Charsets.UTF_8)
            val recipe = record(ByteBuffer.wrap(RawFrameCompression.compressMetadata(json)))
            val raw = record(RawFrameCompression.payload(handle, false))
            frames.put(JSONObject().put("recipe", recipe).put("raw", raw)
                .put("timestamp", timestamp).put("previewChecksum", CRC32().apply {
                    update(RawFrameCompression.payload(handle, true))
                }.value))
            lastTimestamp = timestamp
        }

        fun finish(thumbnail: ByteArray, width: Int, height: Int): Uri {
            check(!complete && frames.length() > 0)
            val thumbnailOffset = record(ByteBuffer.wrap(thumbnail))
            val index = JSONObject().put("compression", "blosc-zstd-bitshuffle").put("frames", frames).put("capturedAt", capturedAt)
                .put("width", width).put("height", height).put("thumbnail", thumbnailOffset)
            val offset = record(ByteBuffer.wrap(index.toString().toByteArray(Charsets.UTF_8)))
            channel.position(12)
            write(channel, ByteBuffer.allocate(8).putLong(offset).flip() as ByteBuffer)
            channel.force(true)
            check(context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1)
            complete = true
            return uri
        }

        private fun record(input: ByteBuffer): Long {
            val bytes = input.duplicate()
            require(bytes.remaining() in 1..MAX_RECORD)
            val offset = channel.position()
            val checksum = CRC32().apply { update(bytes.duplicate()) }
            write(channel, ByteBuffer.allocate(12).putInt(bytes.remaining()).putLong(checksum.value).flip() as ByteBuffer)
            write(channel, bytes)
            return offset
        }

        override fun close() {
            try { stream.close() } finally { if (!complete) context.contentResolver.delete(uri, null, null) }
        }
    }

    class Reader(context: Context, uri: Uri) : AutoCloseable {
        private val stream = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(context.contentResolver.openFileDescriptor(BurstMedia.canonicalUri(uri), "r")))
        private val channel = stream.channel
        private val limit: Long
        val index: JSONObject
        val frames: JSONArray
        val count: Int get() = frames.length()

        init {
            try {
                require(channel.size() >= 32)
                val header = bytes(0, 20)
                require(header.long == MAGIC)
                require(header.int == VERSION)
                limit = header.long
                require(limit in 20..channel.size() - 12)
                index = JSONObject(record(limit, 1024 * 1024, channel.size()).toString(Charsets.UTF_8))
                require(limit + 12 + bytes(limit, 4).int == channel.size())
                require(index.getString("compression") == "blosc-zstd-bitshuffle")
                frames = index.getJSONArray("frames")
                require(count in 1..BurstMemoryPlan.MAX_FRAMES)
                var next = 20L
                var timestamp = Long.MIN_VALUE
                repeat(count) { frame ->
                    val entry = frames.getJSONObject(frame)
                    require(entry.getLong("timestamp") > timestamp)
                    timestamp = entry.getLong("timestamp")
                    for (key in listOf("recipe", "raw")) {
                        val offset = entry.getLong(key)
                        require(offset == next)
                        val size = bytes(offset, 4).int
                        require(size in 1..MAX_RECORD && offset <= limit - 12L - size)
                        next = offset + 12L + size
                    }
                }
                require(index.getLong("thumbnail") == next && next < limit)
            } catch (error: Throwable) { stream.close(); throw error }
        }

        fun capture(frame: Int): PersistedCapture {
            require(frame in 0 until count)
            val bytes = record(frames.getJSONObject(frame).getLong("recipe"), 32 * 1024 * 1024)
            val json = RawFrameCompression.decompressMetadata(bytes, 32 * 1024 * 1024).toString(Charsets.UTF_8)
            return PersistedCapture.fromJson(JSONObject(json)).also {
                require(it.metadata.sensorTimestampNs == frames.getJSONObject(frame).getLong("timestamp"))
                require(it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 128 * 1024 * 1024)
            }
        }

        fun pixels(frame: Int, preview: Boolean): ByteArray {
            require(frame in 0 until count)
            val entry = frames.getJSONObject(frame)
            val offset = entry.getLong("raw")
            if (!preview) return record(offset, MAX_RECORD)
            val size = bytes(offset, 4).int
            require(size in 40..MAX_RECORD && offset <= limit - 12L - size)
            val header = bytes(offset + 12, 40).order(ByteOrder.LITTLE_ENDIAN)
            val fullSize = header.getInt(28)
            val previewSize = header.getInt(32)
            require(fullSize >= 16 && previewSize >= 0 && 40L + fullSize + previewSize == size.toLong())
            val result = if (previewSize == 0) record(offset, 2 * 1024 * 1024) else {
                require(previewSize <= 2 * 1024 * 1024)
                bytes(offset + 12 + 40 + fullSize, previewSize).array()
            }
            dimensions(result)
            require(CRC32().apply { update(result) }.value == entry.getLong("previewChecksum"))
            return result
        }

        fun thumbnail(): ByteArray = record(index.getLong("thumbnail"), 4 * 1024 * 1024)

        private fun record(offset: Long, maximum: Int, end: Long = limit): ByteArray {
            require(offset >= 20 && offset <= end - 12)
            val header = bytes(offset, 12)
            val size = header.int
            val checksum = header.long
            require(size in 1..maximum && size.toLong() <= end - offset - 12)
            val data = bytes(offset + 12, size).array()
            require(CRC32().apply { update(data) }.value == checksum)
            return data
        }

        private fun bytes(offset: Long, count: Int): ByteBuffer {
            require(offset >= 0 && offset <= channel.size() - count)
            return ByteBuffer.allocate(count).apply {
                while (hasRemaining()) check(channel.read(this, offset + position()) > 0)
                flip()
            }
        }

        override fun close() = stream.close()
    }

    private fun write(channel: FileChannel, bytes: ByteBuffer) {
        while (bytes.hasRemaining()) check(channel.write(bytes) > 0)
    }

    fun dimensions(encoded: ByteArray): Pair<Int, Int> {
        require(encoded.size >= 56)
        val bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        require(encoded.copyOfRange(0, 8).contentEquals(byteArrayOf(83, 80, 82, 65, 87, 48, 48, 49)))
        val width = bytes.getInt(8); val height = bytes.getInt(12)
        require(width in 2..16384 && height in 2..16384 && width % 2 == 0 && height % 2 == 0 &&
            width.toLong() * height <= 128 * 1024 * 1024)
        require(bytes.getInt(28) >= 16 && bytes.getInt(32) >= 0 &&
            40L + bytes.getInt(28) + bytes.getInt(32) == encoded.size.toLong())
        return width to height
    }
}
