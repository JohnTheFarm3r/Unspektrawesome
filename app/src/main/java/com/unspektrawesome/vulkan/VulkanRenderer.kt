package com.unspektrawesome.vulkan

import android.hardware.HardwareBuffer
import android.view.Surface
import com.unspektrawesome.vulkan.params.IspShaderParameters
import java.nio.ByteBuffer

/** Lifecycle-safe owner of one native Vulkan renderer instance. */
class VulkanRenderer(cachePath: String = "") : AutoCloseable {
    private var handle: Long = nativeCreate(cachePath).also {
        check(it != 0L) { "Native Vulkan renderer creation failed" }
    }
    internal val rawUploads = RawUploadPool(nativeRawUploads(handle))

    @Synchronized
    fun setSurface(surface: Surface?): Boolean = withHandle { nativeSetSurface(it, surface) }

    @Synchronized
    internal fun setGpuPerformanceActive(active: Boolean) = withHandle { nativeSetGpuPerformanceActive(it, active) }

    @Synchronized
    fun setVideoSurface(surface: Surface?, width: Int = 0, height: Int = 0, fullRange: Boolean = false): Boolean = withHandle {
        nativeSetVideoSurface(it, surface, width, height, fullRange)
    }

    @Synchronized
    fun videoFrames(): Long = withHandle { nativeVideoFrames(it) }

    @Synchronized
    fun presentedFrames(): Long = withHandle { nativePresentedFrames(it) }

    /** Focus loupe request for the following frames, encoded by FocusLoupeRequest. */
    fun setFocusLoupe(values: FloatArray) = withHandle { nativeSetFocusLoupe(it, values) }

    /** Latest focus loupe result, decoded by FocusLoupeResult. */
    fun focusLoupeResult(): FloatArray? = withHandle { nativeFocusLoupeResult(it) }

    @Synchronized
    fun warmUp(preview: Boolean, step: Int, part: Int = -1): Boolean = withHandle {
        require(step in 0 until warmupStepCount(preview))
        require(part == -1 || part in 0 until warmupPartCount(preview, step))
        nativeWarmUp(it, preview, step, part)
    }

    internal data class PipelineCreationStats(
        val feedbackEnabled: Boolean,
        val created: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
        val unreported: Long,
    )

    @Synchronized
    internal fun pipelineCreationStats(): PipelineCreationStats = withHandle {
        val values = nativePipelineCreationStats(it)
        PipelineCreationStats(values[0] != 0L, values[1], values[2], values[3], values[4])
    }

    @Synchronized
    internal fun preparationIdentity(): String = withHandle { nativePreparationIdentity(it) }

    @Synchronized
    internal fun loadedPipelineCache(): Boolean = withHandle { nativeLoadedPipelineCache(it) }

    @Synchronized
    fun beginLensSwitchBlur(): Boolean = withHandle { nativeBeginLensSwitchBlur(it) }

    @Synchronized
    fun histogramPixels(cropAspect: Float): IntArray? = withHandle { nativeHistogramPixels(it, cropAspect) }

    @Synchronized
    fun histogramBins(cropAspect: Float, generation: Long = 0): IntArray? =
        withHandle { nativeHistogramBins(it, cropAspect, generation) }

    @Synchronized
    internal fun previewSnapshot(bitmap: android.graphics.Bitmap): Long = withHandle { nativePreviewSnapshot(it, bitmap) }

    @Synchronized
    fun pollExposureMeter(meter: ExposureMeterBuffer): Boolean = withHandle { nativePollExposureMeter(it, meter.bytes) }

    @Synchronized
    fun render(
        hardwareBuffer: HardwareBuffer?,
        packedRawBytes: ByteBuffer?,
        rawParameters: ByteBuffer,
        rawColorParameters: ByteBuffer,
        geometryParameters: ByteBuffer,
        spektraFilmParameters: ByteBuffer,
        lensShadingMap: ByteBuffer?,
        processingWidth: Int,
        processingHeight: Int,
        performanceOutput: ByteBuffer? = null,
        exposureMeter: ExposureMeterBuffer? = null,
        videoTimestampNs: Long = 0L,
        rawUpload: RawUpload? = null,
        averageGroup: Long = 0L,
        averageIndex: Int = 0,
        averageCount: Int = 1,
        averageRevision: Long = 0L,
        previewTimestampNs: Long = 0L,
        ispHardwareBuffer: HardwareBuffer? = null,
        ispPlaneBytes: ByteBuffer? = null,
        ispParameters: ByteBuffer? = null,
    ): Boolean = withHandle { current ->
        require(rawParameters.isDirect) { "RAW parameters must be direct" }
        require(packedRawBytes == null || packedRawBytes.isDirect) {
            "Packed RAW bytes must be direct"
        }
        require(ispParameters == null || ispParameters.isDirect && ispParameters.capacity() >= IspShaderParameters.SIZE_BYTES &&
            (ispPlaneBytes == null || ispPlaneBytes.isDirect) && (ispHardwareBuffer != null || ispPlaneBytes != null)) {
            "ISP viewfinder frame is invalid"
        }
        require(rawColorParameters.isDirect) { "RAW color parameters must be direct" }
        require(geometryParameters.isDirect) { "Geometry parameters must be direct" }
        require(spektraFilmParameters.isDirect) { "SpektraFilm parameters must be direct" }
        require(lensShadingMap == null || lensShadingMap.isDirect) {
            "Lens-shading parameters must be direct"
        }
        require(performanceOutput == null || performanceOutput.isDirect && performanceOutput.capacity() >= 8) {
            "Preview timing output must be a direct ByteBuffer of at least 8 bytes"
        }
        require(averageCount in 1..120 && averageIndex in 0 until averageCount)
        fun submit(upload: Long) = nativeRender(
            current,
            hardwareBuffer,
            packedRawBytes,
            rawParameters,
            rawColorParameters,
            geometryParameters,
            spektraFilmParameters,
            lensShadingMap,
            processingWidth,
            processingHeight,
            performanceOutput,
            exposureMeter?.bytes,
            videoTimestampNs,
            upload, averageGroup, averageIndex, averageCount, averageRevision,
            previewTimestampNs,
            ispHardwareBuffer, ispPlaneBytes, ispParameters,
        )
        rawUpload?.render(::submit) ?: submit(0L)
    }

    @Synchronized
    fun captureAmaze(
        hardwareBuffer: HardwareBuffer?,
        packedRawBytes: ByteBuffer?,
        rawParameters: ByteBuffer,
        rawColorParameters: ByteBuffer,
        geometryParameters: ByteBuffer,
        spektraFilmParameters: ByteBuffer,
        lensShadingMap: ByteBuffer?,
        rgbaOutput: ByteBuffer,
        spektraTimeSeconds: Double,
        halfFloatOutput: Boolean = false,
        cooperativeScheduling: Boolean = true,
    ): Boolean = withHandle { current ->
        require(rawParameters.isDirect) { "RAW parameters must be direct" }
        require(packedRawBytes == null || packedRawBytes.isDirect) {
            "Packed RAW bytes must be direct"
        }
        require(rawColorParameters.isDirect) { "RAW color parameters must be direct" }
        require(geometryParameters.isDirect) { "Geometry parameters must be direct" }
        require(spektraFilmParameters.isDirect) { "SpektraFilm parameters must be direct" }
        require(lensShadingMap == null || lensShadingMap.isDirect) {
            "Lens-shading parameters must be direct"
        }
        require(rgbaOutput.isDirect) { "RGBA capture output must be direct" }
        nativeCaptureAmaze(
            current,
            hardwareBuffer,
            packedRawBytes,
            rawParameters,
            rawColorParameters,
            geometryParameters,
            spektraFilmParameters,
            lensShadingMap,
            rgbaOutput,
            spektraTimeSeconds,
            halfFloatOutput,
            cooperativeScheduling,
            false,
            0,
        )
    }

    @Synchronized
    fun captureAmazeToBitmap(
        packedRawBytes: ByteBuffer,
        rawParameters: ByteBuffer,
        rawColorParameters: ByteBuffer,
        geometryParameters: ByteBuffer,
        spektraFilmParameters: ByteBuffer,
        lensShadingMap: ByteBuffer?,
        bitmap: android.graphics.Bitmap,
        spektraTimeSeconds: Double,
        gpuPauseMillis: Int = 0,
    ): Boolean = withHandle { current ->
        require(bitmap.isMutable && bitmap.config == android.graphics.Bitmap.Config.ARGB_8888)
        nativeCaptureAmaze(current, null, packedRawBytes, rawParameters, rawColorParameters,
            geometryParameters, spektraFilmParameters, lensShadingMap, bitmap, spektraTimeSeconds,
            false, true, true, gpuPauseMillis.coerceIn(0, 20))
    }

    @Synchronized
    fun diagnostics(): String = if (handle == 0L) {
        "Renderer is released"
    } else {
        nativeGetDiagnosticString(handle)
    }

    @Synchronized
    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        rawUploads.close()
        nativeRelease(current)
    }

    private inline fun <T> withHandle(block: (Long) -> T): T {
        check(handle != 0L) { "Renderer is released" }
        return block(handle)
    }

    private external fun nativeCreate(cachePath: String): Long
    private external fun nativeRawUploads(handle: Long): Long
    private external fun nativeWarmUp(handle: Long, preview: Boolean, step: Int, part: Int): Boolean
    private external fun nativePipelineCreationStats(handle: Long): LongArray
    private external fun nativePreparationIdentity(handle: Long): String
    private external fun nativeLoadedPipelineCache(handle: Long): Boolean
    private external fun nativeSetSurface(handle: Long, surface: Surface?): Boolean
    private external fun nativeSetGpuPerformanceActive(handle: Long, active: Boolean)
    private external fun nativeSetVideoSurface(handle: Long, surface: Surface?, width: Int, height: Int, fullRange: Boolean): Boolean
    private external fun nativeVideoFrames(handle: Long): Long
    private external fun nativePresentedFrames(handle: Long): Long
    private external fun nativeSetFocusLoupe(handle: Long, values: FloatArray)
    private external fun nativeFocusLoupeResult(handle: Long): FloatArray?
    private external fun nativeBeginLensSwitchBlur(handle: Long): Boolean
    private external fun nativeHistogramPixels(handle: Long, cropAspect: Float): IntArray?
    private external fun nativeHistogramBins(handle: Long, cropAspect: Float, generation: Long): IntArray?
    private external fun nativePreviewSnapshot(handle: Long, bitmap: android.graphics.Bitmap): Long
    private external fun nativePollExposureMeter(handle: Long, meter: ByteBuffer): Boolean
    private external fun nativeRender(
        handle: Long,
        hardwareBuffer: HardwareBuffer?,
        packedRawBytes: ByteBuffer?,
        rawParameters: ByteBuffer,
        rawColorParameters: ByteBuffer,
        geometryParameters: ByteBuffer,
        spektraFilmParameters: ByteBuffer,
        lensShadingMap: ByteBuffer?,
        processingWidth: Int,
        processingHeight: Int,
        performanceOutput: ByteBuffer?,
        exposureMeter: ByteBuffer?,
        videoTimestampNs: Long,
        rawUpload: Long,
        averageGroup: Long,
        averageIndex: Int,
        averageCount: Int,
        averageRevision: Long,
        previewTimestampNs: Long,
        ispHardwareBuffer: HardwareBuffer?,
        ispPlaneBytes: ByteBuffer?,
        ispParameters: ByteBuffer?,
    ): Boolean
    private external fun nativeGetDiagnosticString(handle: Long): String
    private external fun nativeCaptureAmaze(
        handle: Long,
        hardwareBuffer: HardwareBuffer?,
        packedRawBytes: ByteBuffer?,
        rawParameters: ByteBuffer,
        rawColorParameters: ByteBuffer,
        geometryParameters: ByteBuffer,
        spektraFilmParameters: ByteBuffer,
        lensShadingMap: ByteBuffer?,
        rgbaOutput: Any,
        spektraTimeSeconds: Double,
        halfFloatOutput: Boolean,
        cooperativeScheduling: Boolean,
        bitmapOutput: Boolean,
        gpuPauseMillis: Int,
    ): Boolean
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("unspektrawesome_vulkan")
        }

        fun warmupStepCount(preview: Boolean): Int = nativeWarmupStepCount(preview)
        fun warmupPartCount(preview: Boolean, step: Int): Int = nativeWarmupPartCount(preview, step)
        internal fun setVideoColorSpace(surface: Surface, fullRange: Boolean): Boolean = nativeSetVideoColorSpace(surface, fullRange)
        @JvmStatic private external fun nativeSetVideoColorSpace(surface: Surface, fullRange: Boolean): Boolean
        @JvmStatic private external fun nativeWarmupPartCount(preview: Boolean, step: Int): Int
        @JvmStatic private external fun nativeWarmupStepCount(preview: Boolean): Int
    }
}
