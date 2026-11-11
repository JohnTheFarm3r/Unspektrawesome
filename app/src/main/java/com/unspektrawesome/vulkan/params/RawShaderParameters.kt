package com.unspektrawesome.vulkan.params

import com.unspektrawesome.camera.CfaPattern
import com.unspektrawesome.camera.RawFormat
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.RawPreviewPolicy
import com.unspektrawesome.camera.SensorMetadata
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.camera.session.RawCaptureMetadata
import com.unspektrawesome.settings.CfaOverride
import com.unspektrawesome.settings.RawLevelCalibration
import com.unspektrawesome.settings.RawLevelMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Immutable frame seed captured together with the processing state. */
@JvmInline
value class FrameSeedSnapshot(val value: Int) {
    companion object {
        fun fromFrameNumber(frameNumber: Long): FrameSeedSnapshot =
            FrameSeedSnapshot((frameNumber xor (frameNumber ushr 32)).toInt())
    }
}

/** Host representation of RawParameters in raw_input.glsl. */
class RawShaderParameters private constructor(
    val rawWidth: Int,
    val rawHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val rowStrideBytes: Int,
    val rawFormat: Int,
    val cfaPattern: Int,
    val frameSeed: FrameSeedSnapshot,
    val cfaBlockSize: Int,
    val blackLevelR: Float,
    val blackLevelG1: Float,
    val blackLevelG2: Float,
    val blackLevelB: Float,
    val whiteLevelR: Float,
    val whiteLevelG1: Float,
    val whiteLevelG2: Float,
    val whiteLevelB: Float,
) {
    fun toByteBuffer(): ByteBuffer = writeTo(
        ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder()),
    )

    fun writeTo(target: ByteBuffer): ByteBuffer {
        require(target.isDirect && target.capacity() >= SIZE_BYTES) {
            "RAW parameter target must be a direct buffer of at least $SIZE_BYTES bytes"
        }
        return target.order(ByteOrder.nativeOrder()).apply {
            putInt(DIMENSIONS_OFFSET, rawWidth)
            putInt(DIMENSIONS_OFFSET + 4, rawHeight)
            putInt(DIMENSIONS_OFFSET + 8, outputWidth)
            putInt(DIMENSIONS_OFFSET + 12, outputHeight)
            putInt(LAYOUT_INFO_OFFSET, rowStrideBytes)
            putInt(LAYOUT_INFO_OFFSET + 4, rawFormat)
            putInt(LAYOUT_INFO_OFFSET + 8, cfaPattern)
            putInt(LAYOUT_INFO_OFFSET + 12, frameSeed.value)
            putFloat(BLACK_LEVEL_OFFSET, blackLevelR)
            putFloat(BLACK_LEVEL_OFFSET + 4, blackLevelG1)
            putFloat(BLACK_LEVEL_OFFSET + 8, blackLevelG2)
            putFloat(BLACK_LEVEL_OFFSET + 12, blackLevelB)
            putFloat(WHITE_LEVEL_OFFSET, whiteLevelR)
            putFloat(WHITE_LEVEL_OFFSET + 4, whiteLevelG1)
            putFloat(WHITE_LEVEL_OFFSET + 8, whiteLevelG2)
            putFloat(WHITE_LEVEL_OFFSET + 12, whiteLevelB)
            putInt(REMOSAIC_OFFSET, cfaBlockSize)
            putInt(REMOSAIC_OFFSET + 4, cfaPattern)
            putInt(REMOSAIC_OFFSET + 8, 0)
            putInt(REMOSAIC_OFFSET + 12, 0)
            position(0)
            limit(SIZE_BYTES)
        }
    }

    companion object {
        const val DIMENSIONS_OFFSET = 0
        const val LAYOUT_INFO_OFFSET = 16
        const val BLACK_LEVEL_OFFSET = 32
        const val WHITE_LEVEL_OFFSET = 48
        const val REMOSAIC_OFFSET = 64
        const val SIZE_BYTES = 80

        fun upgrade(source: ByteBuffer): ByteBuffer {
            if (source.remaining() != 64) return source
            return ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
                put(source.duplicate())
                putInt(1); putInt(source.getInt(LAYOUT_INFO_OFFSET + 8)); putInt(0); putInt(0)
                flip()
            }
        }
        const val STD140_ALIGNMENT_BYTES = 16
        const val VEC4_SIZE_BYTES = 16

        const val RAW_FORMAT_SENSOR = 0
        const val RAW_FORMAT_10 = 1
        const val RAW_FORMAT_12 = 2

        const val CFA_RGGB = 0
        const val CFA_GRBG = 1
        const val CFA_GBRG = 2
        const val CFA_BGGR = 3

        fun forPreview(
            output: RawOutput,
            sensor: SensorMetadata,
            rowStrideBytes: Int,
            outputDimensions: Size2d,
            frameSeed: FrameSeedSnapshot,
        ): RawShaderParameters = create(
            output, sensor, rowStrideBytes, outputDimensions, frameSeed,
        )

        fun forPreview(
            output: RawOutput,
            sensor: SensorMetadata,
            metadata: RawCaptureMetadata,
            rowStrideBytes: Int,
            outputDimensions: Size2d,
            frameSeed: FrameSeedSnapshot,
            calibration: RawLevelCalibration = RawLevelCalibration(),
            cfaOverride: CfaOverride = CfaOverride(),
        ): RawShaderParameters = create(
            output, sensor, rowStrideBytes, outputDimensions, frameSeed, metadata, calibration, cfaOverride,
        )

        fun forPreview(
            output: RawOutput,
            sensor: SensorMetadata,
            rowStrideBytes: Int,
            quality: RawPreviewPolicy.Quality,
            frameSeed: FrameSeedSnapshot,
        ): RawShaderParameters = create(
            output,
            sensor,
            rowStrideBytes,
            PreviewQualityDimensions.from(output.size, quality),
            frameSeed,
        )

        fun forCapture(
            output: RawOutput,
            sensor: SensorMetadata,
            rowStrideBytes: Int,
            frameSeed: FrameSeedSnapshot,
        ): RawShaderParameters = create(output, sensor, rowStrideBytes, output.size, frameSeed)

        fun forCapture(
            output: RawOutput,
            sensor: SensorMetadata,
            rowStrideBytes: Int,
            snapshot: VulkanCaptureSnapshot,
        ): RawShaderParameters = forCapture(
            output, sensor, rowStrideBytes, snapshot.frameSeed,
        )

        fun forCapture(
            output: RawOutput,
            sensor: SensorMetadata,
            metadata: RawCaptureMetadata,
            rowStrideBytes: Int,
            snapshot: VulkanCaptureSnapshot,
            calibration: RawLevelCalibration = RawLevelCalibration(),
            cfaOverride: CfaOverride = CfaOverride(),
        ): RawShaderParameters = create(
            output,
            sensor,
            rowStrideBytes,
            output.size,
            snapshot.frameSeed,
            metadata,
            calibration,
            cfaOverride,
        )

        private fun create(
            output: RawOutput,
            sensor: SensorMetadata,
            rowStrideBytes: Int,
            outputDimensions: Size2d,
            frameSeed: FrameSeedSnapshot,
            captureMetadata: RawCaptureMetadata? = null,
            calibration: RawLevelCalibration = RawLevelCalibration(),
            cfaOverride: CfaOverride = CfaOverride(),
        ): RawShaderParameters {
            if (calibration.mode == RawLevelMode.DYNAMIC) require(sensor.hasValidLevels()) { "Sensor black and white levels are invalid" }
            require(calibration.isValid()) { "Black and white calibration is invalid" }
            require(minOf(outputDimensions.width, outputDimensions.height) <= minOf(output.size.width, output.size.height) &&
                maxOf(outputDimensions.width, outputDimensions.height) <= maxOf(output.size.width, output.size.height)) {
                "Output dimensions must not exceed RAW dimensions"
            }
            val minimumStride = when (output.format) {
                RawFormat.RAW_SENSOR -> output.size.width * 2L
                RawFormat.RAW10 -> ((output.size.width + 3L) / 4L) * 5L
                RawFormat.RAW12 -> ((output.size.width + 1L) / 2L) * 3L
            }
            require(rowStrideBytes >= minimumStride && rowStrideBytes > 0) {
                "Row stride $rowStrideBytes is smaller than the packed RAW row $minimumStride"
            }

            val staticBlack = sensor.blackLevel()
            val dynamicWhite = captureMetadata?.dynamicWhiteLevel
            val dynamicBlack = captureMetadata?.dynamicBlackLevel()
            fun validPair(black: FloatArray?, white: Int?): Boolean =
                white != null && white in 1..RawLevelCalibration.MAX_WHITE_LEVEL &&
                    black != null && black.size == 4 &&
                    black.all { it.isFinite() && it >= 0f && it < white }
            val (spatial, whiteLevel) = when {
                calibration.mode != RawLevelMode.DYNAMIC -> requireNotNull(calibration.overrideBlack()) to requireNotNull(calibration.overrideWhite)
                validPair(dynamicBlack, dynamicWhite) -> requireNotNull(dynamicBlack) to requireNotNull(dynamicWhite)
                validPair(staticBlack, dynamicWhite) -> staticBlack to requireNotNull(dynamicWhite)
                validPair(dynamicBlack, sensor.whiteLevel) -> requireNotNull(dynamicBlack) to sensor.whiteLevel
                validPair(staticBlack, sensor.whiteLevel) -> staticBlack to sensor.whiteLevel
                else -> throw IllegalArgumentException(
                    "RAW black and white metadata is invalid; select manual levels",
                )
            }
            val pattern = cfaOverride.pattern(sensor)
            val canonical = when (pattern) {
                CfaPattern.RGGB -> floatArrayOf(spatial[0], spatial[1], spatial[2], spatial[3])
                CfaPattern.GRBG -> floatArrayOf(spatial[1], spatial[0], spatial[3], spatial[2])
                CfaPattern.GBRG -> floatArrayOf(spatial[2], spatial[0], spatial[3], spatial[1])
                CfaPattern.BGGR -> floatArrayOf(spatial[3], spatial[1], spatial[2], spatial[0])
                else -> throw IllegalArgumentException("Only Bayer CFA patterns are supported")
            }
            val white = whiteLevel.toFloat()
            return RawShaderParameters(
                rawWidth = output.size.width,
                rawHeight = output.size.height,
                outputWidth = outputDimensions.width,
                outputHeight = outputDimensions.height,
                rowStrideBytes = rowStrideBytes,
                rawFormat = when (output.format) {
                    RawFormat.RAW_SENSOR -> RAW_FORMAT_SENSOR
                    RawFormat.RAW10 -> RAW_FORMAT_10
                    RawFormat.RAW12 -> RAW_FORMAT_12
                },
                cfaPattern = when (pattern) {
                    CfaPattern.RGGB -> CFA_RGGB
                    CfaPattern.GRBG -> CFA_GRBG
                    CfaPattern.GBRG -> CFA_GBRG
                    CfaPattern.BGGR -> CFA_BGGR
                },
                frameSeed = frameSeed,
                cfaBlockSize = cfaOverride.layout.blockSize,
                blackLevelR = canonical[0],
                blackLevelG1 = canonical[1],
                blackLevelG2 = canonical[2],
                blackLevelB = canonical[3],
                whiteLevelR = white,
                whiteLevelG1 = white,
                whiteLevelG2 = white,
                whiteLevelB = white,
            )
        }
    }
}

object PreviewQualityDimensions {
    fun from(source: Size2d, quality: RawPreviewPolicy.Quality): Size2d {
        val scale = when (quality) {
            RawPreviewPolicy.Quality.LOW -> 0.25f
            RawPreviewPolicy.Quality.MEDIUM -> 0.5f
            RawPreviewPolicy.Quality.HIGH -> 0.75f
            RawPreviewPolicy.Quality.FULL -> 1f
        }
        val width = maxOf(2, (kotlin.math.floor(source.width * scale).toInt() and -2))
        val height = maxOf(2, (kotlin.math.floor(source.height * scale).toInt() and -2))
        return Size2d(width, height)
    }
}
