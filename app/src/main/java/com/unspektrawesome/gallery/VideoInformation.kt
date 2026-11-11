package com.unspektrawesome.gallery

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.unspektrawesome.capture.CaptureMetadata
import com.unspektrawesome.video.Mp4Metadata
import org.json.JSONObject
import java.util.Locale

internal object VideoInformation {
    fun read(context: Context, photo: GalleryPhoto): List<PhotoInfoSection> {
        val formats = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, photo.uri, null)
                List(extractor.trackCount) { extractor.getTrackFormat(it) }
            } finally { extractor.release() }
        }.getOrDefault(emptyList())
        val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audio = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val metadata = runCatching {
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(context, photo.uri)
                listOf(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadataRetriever.METADATA_KEY_BITRATE,
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    .associateWith { reader.extractMetadata(it) }
            }
        }.getOrDefault(emptyMap())
        val color = runCatching {
            context.contentResolver.openAssetFileDescriptor(photo.uri, "r")?.use { descriptor ->
                descriptor.createInputStream().use { stream ->
                    Mp4ColorInfo.read(stream.channel, descriptor.startOffset,
                        descriptor.declaredLength.takeIf { it >= 0 } ?: (stream.channel.size() - descriptor.startOffset))
                }
            }
        }.getOrNull()
        val size = photo.bytes.takeIf { it > 0 } ?: runCatching {
            context.contentResolver.openAssetFileDescriptor(photo.uri, "r")?.use { it.length }
        }.getOrNull()
        val captureSettings = runCatching {
            context.contentResolver.openAssetFileDescriptor(photo.uri, "r")?.use { descriptor ->
                descriptor.createInputStream().use { stream ->
                    Mp4Metadata.settings(stream.channel, descriptor.startOffset,
                        descriptor.declaredLength.takeIf { it >= 0 } ?: (stream.channel.size() - descriptor.startOffset))
                        ?.let(::JSONObject)
                }
            }
        }.getOrNull()
        val duration = metadata[MediaMetadataRetriever.METADATA_KEY_DURATION]?.toLongOrNull()
            ?: video?.number(MediaFormat.KEY_DURATION)?.toLong()?.div(1000) ?: photo.durationMs
        val totalBitrate = metadata[MediaMetadataRetriever.METADATA_KEY_BITRATE]?.toDoubleOrNull()
            ?: size?.takeIf { it > 0 && duration > 0 }?.let { it * 8_000.0 / duration }
        fun field(label: String, value: String?) = PhotoInfoField(label, value ?: "Not specified")
        return buildList {
            add(PhotoInfoSection("Video", buildList {
                add(field("Codec", video?.getString(MediaFormat.KEY_MIME)?.let(::codec)))
                val width = video?.number(MediaFormat.KEY_WIDTH)?.toInt() ?: photo.width
                val height = video?.number(MediaFormat.KEY_HEIGHT)?.toInt() ?: photo.height
                add(field("Resolution", if (width > 0 && height > 0) "$width × $height" else null))
                add(field("Duration", duration.takeIf { it > 0 }?.let(::videoDuration)))
                add(field("File size", size?.takeIf { it > 0 }?.let(::bytes)))
                add(field("Overall bitrate", totalBitrate?.let(::bitrate)))
                video?.number(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { add(field("Video bitrate", bitrate(it))) }
                val fps = video?.number(MediaFormat.KEY_FRAME_RATE)
                    ?: metadata[MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE]?.toDoubleOrNull()
                add(field("Frame rate", fps?.takeIf { it > 0 }?.let { "${number(it)} fps" }))
                val rotation = metadata[MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION]?.toIntOrNull()
                    ?: video?.number(MediaFormat.KEY_ROTATION)?.toInt()
                rotation?.let { add(field("Rotation", "$it°")) }
                addAll(recordingFields(captureSettings?.optJSONObject("recording")))
            }))
            add(PhotoInfoSection("Color", listOf(
                field("Color space", video?.number(MediaFormat.KEY_COLOR_STANDARD)?.toInt()?.let(::colorSpace)
                    ?: color?.primaries?.let(::primaries)),
                field("Color primaries", color?.primaries?.let(::primaries)).copy(detail = "Container color tags"),
                field("Transfer", color?.transfer?.let(::transfer)
                    ?: video?.number(MediaFormat.KEY_COLOR_TRANSFER)?.toInt()?.let(::androidTransfer)),
                field("Matrix coefficients", color?.matrix?.let(::matrix)),
                PhotoInfoField("Color range", when (color?.fullRange) {
                    true -> "Full range"
                    false -> "Limited range"
                    null -> when (video?.number(MediaFormat.KEY_COLOR_RANGE)?.toInt()) {
                        MediaFormat.COLOR_RANGE_FULL -> "Full range"
                        MediaFormat.COLOR_RANGE_LIMITED -> "Limited range"
                        else -> "Not specified"
                    }
                }),
            )))
            if (audio != null) add(PhotoInfoSection("Audio", buildList {
                add(field("Codec", audio.getString(MediaFormat.KEY_MIME)?.let(::codec)))
                add(field("Sample rate", audio.number(MediaFormat.KEY_SAMPLE_RATE)?.let { "${number(it / 1000)} kHz" }))
                add(field("Channels", audio.number(MediaFormat.KEY_CHANNEL_COUNT)?.toInt()?.toString()))
                audio.number(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { add(field("Audio bitrate", bitrate(it))) }
            }))
            captureSettings?.optJSONArray("settingsTimeline")?.let { changes ->
                for (index in 0 until changes.length()) {
                    val change = changes.optJSONObject(index) ?: continue
                    val capture = runCatching { CaptureMetadata.fromJson(change.getJSONObject("capture")) }.getOrNull() ?: continue
                    val sections = PhotoInformation.fromCapture(capture)
                    if (index == 0) addAll(sections.filter { it.title == "Camera" })
                    val time = PhotoInfoField("Time", String.format(Locale.ROOT, "%.3f s", change.optLong("timeUs") / 1e6))
                    addAll(sections.filter { it.title == "spektrafilm" }.map { it.copy(fields = listOf(time) + it.fields) })
                }
            }
        }
    }

    internal fun recordingFields(recording: JSONObject?): List<PhotoInfoField> = buildList {
        val droppedFrames = recording?.optLong("droppedFrames", -1)?.takeIf { it >= 0 }
        add(PhotoInfoField("Dropped frames", droppedFrames?.toString() ?: "Not specified"))
        val stabilization = recording?.optJSONObject("liveStabilization")
        val enabled = stabilization?.opt("enabled") as? Boolean
        add(PhotoInfoField("Live gyro stabilization", when (enabled) {
            true -> "On"
            false -> "Off"
            null -> "Not specified"
        }))
        if (enabled == true) {
            fun amount(key: String, range: ClosedFloatingPointRange<Double>): Double? =
                stabilization.optDouble(key).takeIf { it.isFinite() && it in range }
            add(PhotoInfoField("Stabilization crop", amount("cropRatio", 1.0..1.5)?.let { "${number(it)}x" } ?: "Not specified"))
            add(PhotoInfoField("Stabilization strength", amount("strength", 0.0..1.0)?.let { "${number(it * 100)}%" } ?: "Not specified"))
            add(PhotoInfoField("Stabilization smoothing", amount("smoothingSeconds", .05..1.0)?.let { "${number(it)} s" } ?: "Not specified"))
        }
    }

    private fun MediaFormat.number(key: String): Double? = runCatching { getNumber(key)?.toDouble() }.getOrNull()
    private fun number(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun bitrate(value: Double): String = if (value >= 1_000_000) "${number(value / 1_000_000)} Mbps" else "${number(value / 1000)} kbps"
    private fun codec(mime: String): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265 / HEVC"
        MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264 / AVC"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
        MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
        MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
        MediaFormat.MIMETYPE_AUDIO_RAW -> "PCM"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
        else -> mime
    }
    private fun colorSpace(value: Int): String? = when (value) {
        MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
        MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601 PAL"
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601 NTSC"
        MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
        else -> null
    }
    private fun androidTransfer(value: Int): String? = when (value) {
        MediaFormat.COLOR_TRANSFER_LINEAR -> "Linear"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ / SMPTE ST 2084"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG / ARIB STD-B67"
        else -> null
    }
    private fun primaries(value: Int): String? = when (value) {
        1 -> "BT.709"
        2 -> null
        4 -> "BT.470 M"
        5 -> "BT.470 BG"
        6 -> "SMPTE 170M"
        7 -> "SMPTE 240M"
        9 -> "BT.2020"
        10 -> "SMPTE ST 428"
        11 -> "DCI-P3"
        12 -> "Display P3"
        else -> "CICP $value"
    }
    private fun transfer(value: Int): String? = when (value) {
        1 -> "BT.709"
        2 -> null
        4 -> "Gamma 2.2"
        5 -> "Gamma 2.8"
        6 -> "SMPTE 170M"
        7 -> "SMPTE 240M"
        8 -> "Linear"
        13 -> "sRGB / IEC 61966-2-1"
        14 -> "BT.2020 (10-bit)"
        15 -> "BT.2020 (12-bit)"
        16 -> "PQ / SMPTE ST 2084"
        17 -> "SMPTE ST 428"
        18 -> "HLG / ARIB STD-B67"
        else -> "CICP $value"
    }
    private fun matrix(value: Int): String? = when (value) {
        0 -> "Identity / RGB"
        1 -> "BT.709"
        2 -> null
        4 -> "FCC"
        5, 6 -> "BT.601"
        7 -> "SMPTE 240M"
        8 -> "YCgCo"
        9 -> "BT.2020 NCL"
        10 -> "BT.2020 CL"
        14 -> "ICtCp"
        else -> "CICP $value"
    }
}
