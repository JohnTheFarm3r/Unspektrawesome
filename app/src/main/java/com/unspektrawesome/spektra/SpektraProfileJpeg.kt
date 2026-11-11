package com.unspektrawesome.spektra

import com.unspektrawesome.BuildConfig
import com.unspektrawesome.capture.SpektraShotMetadata
import com.unspektrawesome.io.EmbeddedSettings
import com.unspektrawesome.io.JpegProfilePayload
import org.json.JSONObject
import java.io.InputStream
import java.nio.channels.FileChannel

internal object SpektraProfileJpeg {
    private const val MAX_BYTES = 256 * 1024

    fun write(input: FileChannel, output: FileChannel, shot: SpektraShotMetadata) {
        val profile = SpektraProfile("", "${shot.film} ${shot.format}".trim().take(40),
            SpektraFilmParams.fromJson(JSONObject(shot.settingsJson)))
        val recipe = JSONObject(SpektraProfileFile.encode(profile)).apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
        }.toString()
        val payload = EmbeddedSettings.encode(recipe, MAX_BYTES)
        require(payload.size <= 65000) { "This profile is too large to embed in a JPEG." }
        JpegProfilePayload.write(input, output, payload)
    }

    fun read(input: InputStream): SpektraProfile {
        val stream = input.buffered()
        stream.mark(JpegProfilePayload.MAX_HEADER_BYTES)
        val payload = try { JpegProfilePayload.read(stream) }
            catch (_: Exception) { throw IllegalArgumentException("The embedded JPEG profile is invalid or unsupported.") }
        if (payload != null) {
            val json = try { EmbeddedSettings.decode(payload, MAX_BYTES) }
                catch (_: Exception) { throw IllegalArgumentException("The embedded JPEG profile is invalid or unsupported.") }
            return SpektraProfileFile.decode(json)
        }
        stream.reset()
        return SpektraProfileXmp.read(stream)
    }
}
