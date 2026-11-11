package com.unspektrawesome.vulkan.params

import com.unspektrawesome.camera.SensorMetadata
import com.unspektrawesome.camera.RawColorCalibration
import com.unspektrawesome.calibration.ResolvedLensColor
import com.unspektrawesome.calibration.RawCalibrationTables
import com.unspektrawesome.camera.session.RawCaptureMetadata
import com.unspektrawesome.spektra.SpektraCaptureSnapshot
import com.unspektrawesome.spektra.SpektraState
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WhiteBalanceGains(val red: Float, val green: Float, val blue: Float) {
    init {
        require(red.isFinite() && red > 0f) { "Red white-balance gain must be finite and positive" }
        require(green.isFinite() && green > 0f) { "Green white-balance gain must be finite and positive" }
        require(blue.isFinite() && blue > 0f) { "Blue white-balance gain must be finite and positive" }
    }

    companion object {
        fun fromRawCaptureMetadata(metadata: RawCaptureMetadata): WhiteBalanceGains {
            val neutral = requireNotNull(metadata.neutralColorPoint()) {
                "Capture metadata has no neutral color point"
            }
            require(neutral.all { it.isFinite() && it > 0f }) {
                "Neutral color point must contain three finite positive values"
            }
            return WhiteBalanceGains(
                red = neutral[1] / neutral[0],
                green = 1f,
                blue = neutral[1] / neutral[2],
            )
        }
    }
}

/** RAW-to-linear working RGB calibration consumed by the demosaic shaders. */
class RawColorShaderParameters private constructor(
    val revisionId: Long,
    private val values: FloatArray,
) {
    fun toByteBuffer(): ByteBuffer = writeTo(
        ByteBuffer.allocateDirect(SIZE_BYTES).order(ByteOrder.nativeOrder()),
    )

    fun writeTo(target: ByteBuffer): ByteBuffer {
        require(target.isDirect && target.capacity() >= SIZE_BYTES) {
            "Spektra parameter target must be a direct buffer of at least $SIZE_BYTES bytes"
        }
        return target.order(ByteOrder.nativeOrder()).apply {
            values.forEachIndexed { index, value -> putFloat(index * Float.SIZE_BYTES, value) }
            position(0)
            limit(SIZE_BYTES)
        }
    }

    companion object {
        const val WHITE_BALANCE_EXPOSURE_OFFSET = 0
        const val HOT_PIXEL_REMOVAL_OFFSET = WHITE_BALANCE_EXPOSURE_OFFSET + 12
        const val COLOR_MATRIX_0_OFFSET = 16
        const val COLOR_MATRIX_1_OFFSET = 32
        const val COLOR_MATRIX_2_OFFSET = 48
        const val TONE_OFFSET = 64
        const val FOCUS_PEAKING_OFFSET = TONE_OFFSET + 4
        const val INPUT_HIGHLIGHTS_OFFSET = TONE_OFFSET + 8
        const val EXPORT_HIGHLIGHT_RECOVERY_OFFSET = TONE_OFFSET + 12
        const val LEVELS_OFFSET = 80
        const val COLOR_CONTROLS_OFFSET = 96
        const val DCP_TABLE_OFFSET = COLOR_CONTROLS_OFFSET
        const val DCP_ILLUMINANT_WEIGHT_OFFSET = COLOR_CONTROLS_OFFSET + 4
        const val EXPOSURE_ZEBRA_OFFSET = COLOR_CONTROLS_OFFSET + 8
        const val INPUT_SHADOWS_OFFSET = COLOR_CONTROLS_OFFSET + 12
        const val TEXTURE_CONTROLS_OFFSET = 112
        const val EXPORT_CHROMA_DENOISE_OFFSET = TEXTURE_CONTROLS_OFFSET
        const val NOISE_PROFILE_S_OFFSET = TEXTURE_CONTROLS_OFFSET + 4
        const val NOISE_PROFILE_O_OFFSET = TEXTURE_CONTROLS_OFFSET + 8
        const val HIGHLIGHT_THRESHOLD_OFFSET = TEXTURE_CONTROLS_OFFSET + 12
        const val GEOMETRY_CONTROLS_OFFSET = 128
        const val PREVIEW_HIGHLIGHT_RECOVERY_OFFSET = GEOMETRY_CONTROLS_OFFSET
        const val VIDEO_CHROMA_DENOISE_OFFSET = GEOMETRY_CONTROLS_OFFSET + 4
        const val SIZE_BYTES = 144
        const val STD140_ALIGNMENT_BYTES = 16
        const val VEC4_SIZE_BYTES = 16
        const val LENS_SHADING_STRENGTH_OFFSET = LEVELS_OFFSET + 12
        const val LENS_SHADING_WIDTH_OFFSET = GEOMETRY_CONTROLS_OFFSET + 8
        const val LENS_SHADING_HEIGHT_OFFSET = GEOMETRY_CONTROLS_OFFSET + 12

        fun fromState(
            state: SpektraState,
            sensor: SensorMetadata,
            whiteBalance: WhiteBalanceGains,
            lensShadingMap: LensShadingMapParameters?,
            color: ResolvedLensColor? = null,
            focusPeakingStrength: Float = 0f,
            captureMetadata: RawCaptureMetadata? = null,
            exposureZebraEnabled: Boolean = false,
            previewHighlightRecovery: Boolean = false,
            videoMode: Boolean = false,
            videoChromaDenoise: Boolean = false,
            videoChromaDenoiseStrength: Float = 1f,
            videoHotPixelRemoval: Boolean = false,
        ): RawColorShaderParameters = create(
            state = state,
            sensor = sensor,
            whiteBalance = whiteBalance,
            lensShadingMap = lensShadingMap,
            color = color,
            focusPeakingStrength = focusPeakingStrength,
            captureMetadata = captureMetadata,
            exposureZebraEnabled = exposureZebraEnabled,
            previewHighlightRecovery = previewHighlightRecovery && !videoMode,
            videoMode = videoMode,
            videoChromaDenoise = videoMode && videoChromaDenoise,
            videoChromaDenoiseStrength = videoChromaDenoiseStrength,
            videoHotPixelRemoval = videoMode && videoHotPixelRemoval,
        )

        fun fromState(
            state: SpektraState,
            sensor: SensorMetadata,
            captureMetadata: RawCaptureMetadata,
        ): RawColorShaderParameters = fromState(
            state,
            sensor,
            WhiteBalanceGains.fromRawCaptureMetadata(captureMetadata),
            LensShadingMapParameters.fromRawCaptureMetadataOrNull(captureMetadata),
        )

        fun fromCaptureSnapshot(
            snapshot: SpektraCaptureSnapshot,
            sensor: SensorMetadata,
            whiteBalance: WhiteBalanceGains,
            lensShadingMap: LensShadingMapParameters?,
            color: ResolvedLensColor? = null,
            captureMetadata: RawCaptureMetadata? = null,
        ): RawColorShaderParameters = create(
            state = snapshot.state,
            capture = true,
            captureMetadata = captureMetadata,
            sensor = sensor,
            whiteBalance = whiteBalance,
            lensShadingMap = lensShadingMap,
            color = color,
        ).also {
            check(it.revisionId == snapshot.revisionId)
        }

        fun fromCaptureSnapshot(
            snapshot: VulkanCaptureSnapshot,
            sensor: SensorMetadata,
            whiteBalance: WhiteBalanceGains,
            lensShadingMap: LensShadingMapParameters?,
            color: ResolvedLensColor? = null,
            captureMetadata: RawCaptureMetadata? = null,
        ): RawColorShaderParameters = fromCaptureSnapshot(
            snapshot.state, sensor, whiteBalance, lensShadingMap, color, captureMetadata,
        )

        fun fromCaptureSnapshot(
            snapshot: VulkanCaptureSnapshot,
            sensor: SensorMetadata,
            captureMetadata: RawCaptureMetadata,
        ): RawColorShaderParameters = fromCaptureSnapshot(
            snapshot.state,
            sensor,
            WhiteBalanceGains.fromRawCaptureMetadata(captureMetadata),
            LensShadingMapParameters.fromRawCaptureMetadataOrNull(captureMetadata),
            captureMetadata = captureMetadata,
        )

        private fun create(
            state: SpektraState,
            capture: Boolean = false,
            focusPeakingStrength: Float = 0f,
            exposureZebraEnabled: Boolean = false,
            captureMetadata: RawCaptureMetadata? = null,
            sensor: SensorMetadata,
            whiteBalance: WhiteBalanceGains,
            lensShadingMap: LensShadingMapParameters?,
            color: ResolvedLensColor? = null,
            previewHighlightRecovery: Boolean = false,
            videoMode: Boolean = false,
            videoChromaDenoise: Boolean = false,
            videoChromaDenoiseStrength: Float = 1f,
            videoHotPixelRemoval: Boolean = false,
        ): RawColorShaderParameters {
            require(videoChromaDenoiseStrength.isFinite() && videoChromaDenoiseStrength in 0f..1f)
            val matrix = color?.matrix(whiteBalance) ?: RawColorCalibration.afterWhiteBalance(sensor.colorCalibration, whiteBalance)
            val denoise = capture && state.params.exportChromaDenoise
            val hotPixels = if (capture) state.params.exportHotPixelRemoval else videoHotPixelRemoval
            val photoPeaking = focusPeakingStrength > 0f && !videoMode
            val noise = if (denoise || videoChromaDenoise || hotPixels || focusPeakingStrength > 0f) ChromaNoiseProfile.resolveNoiseProfilePair(
                captureMetadata?.noiseProfile(), captureMetadata?.sensitivityIso ?: 100,
            ) else floatArrayOf(0f, 0f)
            return RawColorShaderParameters(
                revisionId = state.revisionId,
                values = floatArrayOf(
                    whiteBalance.red, whiteBalance.green, whiteBalance.blue, if (hotPixels) 1f else 0f,
                    matrix[0], matrix[3], matrix[6], 0f,
                    matrix[1], matrix[4], matrix[7], 0f,
                    matrix[2], matrix[5], matrix[8], 0f,
                    if (state.params.enabled || photoPeaking) 0f else 1f, if (capture) 0f else focusPeakingStrength, state.params.inputHighlights,
                    if (capture && state.params.exportHighlightRecovery) 1f else 0f,
                    if (videoMode) 1f else 0f, 0f, 1f, if (color?.settings?.lensShadingEnabled != false) 1f else 0f,
                    RawCalibrationTables.dcpOffset(lensShadingMap, color?.activeDcp).toFloat(),
                    color?.illuminantWeight(whiteBalance) ?: 1f, if (!capture && exposureZebraEnabled) 1f else 0f,
                    if (photoPeaking) 0f else state.params.inputShadows,
                    if (denoise) state.params.chromaDenoiseStrength else 0f, noise[0], noise[1],
                    1f + state.params.inputHighlightThreshold,
                    if (!capture && focusPeakingStrength <= 0f && previewHighlightRecovery) 1f else 0f,
                    if (videoChromaDenoise) videoChromaDenoiseStrength else 0f,
                    lensShadingMap?.width?.toFloat() ?: 0f,
                    lensShadingMap?.height?.toFloat() ?: 0f,
                ),
            )
        }


    }
}

data class VulkanCaptureSnapshot(
    val state: SpektraCaptureSnapshot,
    val frameSeed: FrameSeedSnapshot,
) {
    val revisionId: Long get() = state.revisionId

    companion object {
        fun from(
            state: SpektraCaptureSnapshot,
            metadata: RawCaptureMetadata,
        ): VulkanCaptureSnapshot = VulkanCaptureSnapshot(
            state,
            FrameSeedSnapshot.fromFrameNumber(metadata.frameNumber),
        )
    }
}
