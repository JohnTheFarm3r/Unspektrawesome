package com.unspektrawesome.preview

import com.unspektrawesome.capture.CaptureMetadata
import com.unspektrawesome.vulkan.params.GeometryShaderParameters
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

internal data class PersistedCapture(
    val width: Int,
    val height: Int,
    val revision: Long,
    val capturedAtMillis: Long,
    val timeSeconds: Double,
    val metadata: CaptureMetadata,
    val raw: ByteBuffer,
    val color: ByteBuffer,
    val geometry: ByteBuffer,
    val film: ByteBuffer,
    val shading: ByteBuffer?,
    val saveOriginalDng: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 6)
        put("saveOriginalDng", saveOriginalDng)
        put("width", width); put("height", height)
        put("revision", revision); put("capturedAt", capturedAtMillis); put("time", timeSeconds)
        put("metadata", metadata.toJson())
        put("raw", encode(raw)); put("color", encode(color))
        put("geometry", encode(geometry)); put("film", encode(film))
        shading?.let { put("shading", encode(it)) }
    }

    fun store(directory: File, pixels: ByteBuffer): File {
        val id = "${capturedAtMillis}-${UUID.randomUUID()}"
        val temporary = File(directory, "$id.tmp")
        val destination = File(directory, "$id.shot")
        val header = toJson().toString().toByteArray(Charsets.UTF_8)
        synchronized(claimLock) { writingFiles.add(temporary) }
        try {
            temporary.outputStream().use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(header.size)
                output.write(header)
                val bytes = pixels.duplicate().apply { clear() }
                output.writeInt(bytes.remaining())
                while (bytes.hasRemaining()) stream.channel.write(bytes)
                stream.fd.sync()
            }
            check(temporary.renameTo(destination)) { "Unable to persist capture" }
            return destination
        } finally {
            synchronized(claimLock) {
                temporary.delete()
                writingFiles.remove(temporary)
            }
        }
    }

    companion object {
        // Serializes exports across controllers while an activity is being recreated.
        val exportLock = Any()
        val claimLock = Any()
        private val writingFiles = mutableSetOf<File>()

        fun removeInterruptedWrites(directory: File) = synchronized(claimLock) {
            directory.listFiles { file -> file.extension == "tmp" }?.forEach { file ->
                if (file !in writingFiles) file.delete()
            }
        }

        @Volatile var exportingFile: File? = null

        fun read(file: File): Pair<PersistedCapture, ByteBuffer> = file.inputStream().use { stream ->
            val input = DataInputStream(stream)
            val headerSize = input.readInt()
            require(headerSize in 1..32_000_000) { "Invalid capture header" }
            val header = ByteArray(headerSize).also { input.readFully(it) }
            val value = JSONObject(String(header, Charsets.UTF_8))
            require(value.getInt("version") in 1..6) { "Unsupported capture version" }
            val length = input.readInt()
            require(length > 0 && length.toLong() == file.length() - headerSize - 8) { "Incomplete RAW capture" }
            val pixels = stream.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,
                headerSize.toLong() + 8, length.toLong()).order(ByteOrder.nativeOrder())
            fromJson(value) to pixels
        }

        fun fromJson(value: JSONObject): PersistedCapture {
            require(value.getInt("version") in 1..6) { "Unsupported capture version" }
            val raw = com.unspektrawesome.vulkan.params.RawShaderParameters.upgrade(decode(value.getString("raw")))
            return PersistedCapture(
                value.getInt("width"), value.getInt("height"), value.getLong("revision"),
                value.getLong("capturedAt"), value.getDouble("time"),
                CaptureMetadata.fromJson(if (value.getInt("version") == 1) value else value.getJSONObject("metadata")),
                raw, decode(value.getString("color")),
                GeometryShaderParameters.upgrade(decode(value.getString("geometry")), raw.getInt(0), raw.getInt(4)), decode(value.getString("film")),
                if (value.has("shading")) decode(value.getString("shading")) else null,
                saveOriginalDng = value.optBoolean("saveOriginalDng", false),
            )
        }

        private fun encode(buffer: ByteBuffer): String {
            val bytes = buffer.duplicate().apply { position(0) }
            return Base64.getEncoder().encodeToString(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }

        private fun decode(encoded: String): ByteBuffer {
            val bytes = Base64.getDecoder().decode(encoded)
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes); flip()
            }
        }
    }
}
