package com.unspektrawesome.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.vulkan.VulkanRenderer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class VideoBitrateRange(val minimum: Int, val maximum: Int) {
    init { require(minimum >= 10_000_000 && maximum >= minimum) }

    fun clamp(bitrate: Int): Int = bitrate.coerceIn(minimum, maximum)

    fun roundedDefault(bitrate: Double): Int {
        val unit = 1_000_000L
        val lower = (minimum.toLong() + unit - 1) / unit
        val upper = maximum / unit
        return if (lower <= upper) ((bitrate / unit).roundToLong().coerceIn(lower, upper) * unit).toInt()
        else clamp(bitrate.roundToInt())
    }

    companion object {
        fun fromReported(minimum: Int, maximum: Int): VideoBitrateRange? {
            val lower = maxOf(10_000_000, minimum)
            return if (maximum >= lower) VideoBitrateRange(lower, maximum) else null
        }
    }
}

data class VideoEncoding(
    val codecName: String,
    val level: Int,
    val bitrates: VideoBitrateRange,
    val bitrateMode: Int,
    val size: Size2d,
    val frameRate: Double = 30.0,
    val operatingRate: Double = frameRate,
    val colorRange: Int = MediaFormat.COLOR_RANGE_FULL,
) {
    val defaultBitrate: Int get() {
        val pixels = size.width.toDouble() * size.height
        val base = when {
            pixels <= 1280 * 720 -> 10_000_000.0
            pixels <= 1920 * 1080 -> 10_000_000.0 + (pixels - 1280 * 720) / (1920 * 1080 - 1280 * 720) * 6_000_000
            pixels <= 2560 * 1440 -> 16_000_000.0 + (pixels - 1920 * 1080) / (2560 * 1440 - 1920 * 1080) * 12_000_000
            pixels <= 3840 * 2160 -> 28_000_000.0 + (pixels - 2560 * 1440) / (3840 * 2160 - 2560 * 1440) * 17_000_000
            else -> 45_000_000.0 * pixels / (3840 * 2160)
        }
        return bitrates.roundedDefault(base * frameRate / 30.0)
    }

    fun format(bitrate: Int, range: Int = colorRange): MediaFormat {
        require(bitrate == bitrates.clamp(bitrate))
        require(range == MediaFormat.COLOR_RANGE_FULL || range == MediaFormat.COLOR_RANGE_LIMITED)
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, range)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, level)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, operatingRate.toFloat())
            if (frameRate == frameRate.toInt().toDouble()) setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.toInt())
            else setFloat(MediaFormat.KEY_FRAME_RATE, frameRate.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
    }

    data class ConfiguredEncoder(val codec: MediaCodec, val surface: Surface, val colorRange: Int, val bt709Surface: Boolean)

    fun configureCodec(bitrate: Int): ConfiguredEncoder {
        var failure: Exception? = null
        for (range in listOf(colorRange, MediaFormat.COLOR_RANGE_LIMITED).distinct()) {
            val codec = MediaCodec.createByCodecName(codecName)
            var surface: Surface? = null
            try {
                codec.configure(format(bitrate, range), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                val configuredRange = codec.inputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, range)
                check(configuredRange == MediaFormat.COLOR_RANGE_FULL || configuredRange == MediaFormat.COLOR_RANGE_LIMITED)
                val fullRange = configuredRange == MediaFormat.COLOR_RANGE_FULL
                val bt709Surface = VulkanRenderer.setVideoColorSpace(surface, fullRange)
                check(!fullRange || bt709Surface)
                return ConfiguredEncoder(codec, surface, configuredRange, bt709Surface)
            } catch (error: Exception) {
                runCatching { codec.release() }
                surface?.release()
                failure?.let(error::addSuppressed)
                failure = error
            }
        }
        throw checkNotNull(failure)
    }

    companion object {
        private data class Key(val retained: Size2d, val downscale: VideoDownscale, val frameRate: Double)
        private data class Result(val encoding: VideoEncoding?)
        private val byConfiguration = android.util.LruCache<Key, Result>(96)
        private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.isHardwareAccelerated && !it.isAlias }
        }

        @Synchronized
        fun supported(retained: Size2d, downscale: VideoDownscale = VideoDownscale.FULL,
            frameRate: Double = 30.0): VideoEncoding? {
            require(retained.width >= retained.height && retained.height >= 2)
            require(frameRate.isFinite() && frameRate > 0 && frameRate <= 1000)
            val key = Key(retained, downscale, frameRate)
            return (byConfiguration[key] ?: Result(findSupported(retained, downscale, frameRate))
                .also { byConfiguration.put(key, it) }).encoding
        }

        private fun findSupported(retained: Size2d, downscale: VideoDownscale, frameRate: Double): VideoEncoding? {
            val candidates = codecs.mapNotNull { codec -> runCatching {
                val type = codec.supportedTypes.firstOrNull { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
                    ?: return@runCatching null
                val capabilities = codec.getCapabilitiesForType(type)
                val video = capabilities.videoCapabilities ?: return@runCatching null
                val size = VideoFraming.outputSize(retained, downscale,
                    maxOf(2, video.widthAlignment), maxOf(2, video.heightAlignment)) ?: return@runCatching null
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats ||
                    !video.areSizeAndRateSupported(size.width, size.height, frameRate)) return@runCatching null
                // Reserve processing headroom to drain brief input backlogs at the selected frame rate.
                val operatingRate = minOf(frameRate * 2, video.getSupportedFrameRatesFor(size.width, size.height).upper)
                val level = capabilities.profileLevels
                    .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
                    .maxOfOrNull { it.level } ?: return@runCatching null
                val profile = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(type,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, level) ?: return@runCatching null
                val profileVideo = profile.videoCapabilities ?: return@runCatching null
                val range = VideoBitrateRange.fromReported(video.bitrateRange.lower,
                    minOf(video.bitrateRange.upper, profileVideo.bitrateRange.upper)) ?: return@runCatching null
                val encoder = capabilities.encoderCapabilities ?: return@runCatching null
                listOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED).asSequence()
                    .flatMap { colorRange -> listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR).asSequence()
                        .filter { encoder.isBitrateModeSupported(it) }
                        .map { VideoEncoding(codec.name, level, range, it, size, frameRate, operatingRate, colorRange) } }
                    .firstOrNull { runCatching { capabilities.isFormatSupported(it.format(range.minimum)) &&
                        capabilities.isFormatSupported(it.format(range.maximum)) }.getOrDefault(false) }
            }.getOrNull() }
            return candidates.maxWithOrNull(compareBy<VideoEncoding> { it.size.width.toLong() * it.size.height }
                .thenBy { it.colorRange == MediaFormat.COLOR_RANGE_FULL }
                .thenBy { it.bitrates.maximum })
        }
    }
}
