package com.unspektrawesome.capture

import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.Rect2d
import com.unspektrawesome.camera.SensorMetadata
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.settings.RawEdgeCrop
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite()) {
            "Normalized rectangle values must be finite"
        }
        require(left >= 0f && top >= 0f && width > 0f && height > 0f) {
            "Normalized rectangle must have a positive size and non-negative origin"
        }
        require(left + width <= 1.000001f && top + height <= 1.000001f) {
            "Normalized rectangle must remain inside the source image"
        }
    }
}

data class PixelCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(left >= 0 && top >= 0 && width >= 2 && height >= 2) {
            "Pixel crop must have a valid origin and dimensions"
        }
    }
}

data class NormalizedPoint(val x: Float, val y: Float)

data class FrameGeometrySnapshot(
    val sourceSize: Size2d,
    val activeArray: NormalizedRect,
    val sourceCrop: NormalizedRect,
    val cropPixels: PixelCrop,
    val transform: CaptureTransform,
    val targetViewport: Size2d,
    val outputSize: Size2d,
    val calibrationArray: NormalizedRect = activeArray,
    val rawBounds: Rect2d = Rect2d(0, 0, sourceSize.width, sourceSize.height),
) {
    val targetAspect: Float = targetViewport.width.toFloat() / targetViewport.height.toFloat()

    val cropAspect: Float get() = transform.outputSize(Size2d(cropPixels.width, cropPixels.height)).let {
        it.width.toFloat() / it.height
    }

    val activeAspect: Float get() {
        val aspect = activeArray.width * sourceSize.width / (activeArray.height * sourceSize.height)
        return if (transform.swapsAxes) 1f / aspect else aspect
    }

    fun atSize(size: Size2d, cropToPixels: Boolean = false): FrameGeometrySnapshot {
        require(size.width in 2..cropPixels.width && size.height in 2..cropPixels.height)
        val crop = if (cropToPixels) {
            val left = (activeArray.left * sourceSize.width).roundToInt()
            val top = (activeArray.top * sourceSize.height).roundToInt()
            val width = (activeArray.width * sourceSize.width).roundToInt()
            val height = (activeArray.height * sourceSize.height).roundToInt()
            // Align once against the RAW bounds so successive crops do not shift the center.
            PixelCrop(left + evenFloor((width - size.width) / 2.0),
                top + evenFloor((height - size.height) / 2.0), size.width, size.height)
        } else cropPixels
        val retained = crop.normalized(sourceSize)
        return copy(sourceCrop = retained, cropPixels = crop, activeArray = if (cropToPixels) retained else activeArray,
            targetViewport = transform.outputSize(size), outputSize = transform.outputSize(size))
    }

    fun limitedTo(processingLimit: Size2d): FrameGeometrySnapshot =
        copy(outputSize = transform.outputSize(fitWithin(cropPixels, processingLimit)))

    fun covering(other: FrameGeometrySnapshot, processingLimit: Size2d): FrameGeometrySnapshot {
        require(sourceSize == other.sourceSize && activeArray == other.activeArray && transform == other.transform &&
            rawBounds == other.rawBounds && calibrationArray == other.calibrationArray)
        val left = minOf(cropPixels.left, other.cropPixels.left)
        val top = minOf(cropPixels.top, other.cropPixels.top)
        val right = maxOf(cropPixels.left + cropPixels.width, other.cropPixels.left + other.cropPixels.width)
        val bottom = maxOf(cropPixels.top + cropPixels.height, other.cropPixels.top + other.cropPixels.height)
        val crop = PixelCrop(left, top, right - left, bottom - top)
        return copy(sourceCrop = crop.normalized(sourceSize), cropPixels = crop,
            targetViewport = transform.outputSize(Size2d(crop.width, crop.height)),
            outputSize = transform.outputSize(fitWithin(crop, processingLimit)))
    }

    init {
        require(cropPixels.left + cropPixels.width <= sourceSize.width &&
            cropPixels.top + cropPixels.height <= sourceSize.height
        ) {
            "Pixel crop must remain inside the RAW source"
        }
        require(outputSize.width > 0 && outputSize.height > 0) {
            "Geometry output dimensions must be positive"
        }
    }

    fun sourceUv(outputX: Int, outputY: Int): NormalizedPoint {
        require(outputX in 0 until outputSize.width && outputY in 0 until outputSize.height) {
            "Output position must remain inside the geometry output"
        }
        return sourceUv(
            (outputX + 0.5f) / outputSize.width,
            (outputY + 0.5f) / outputSize.height,
        )
    }

    fun sourceUv(normalizedOutputX: Float, normalizedOutputY: Float): NormalizedPoint {
        require(normalizedOutputX.isFinite() && normalizedOutputY.isFinite() &&
            normalizedOutputX in 0f..1f && normalizedOutputY in 0f..1f
        ) {
            "Normalized output position must remain inside the geometry output"
        }
        var u = normalizedOutputX
        val v = normalizedOutputY
        if (transform.mirrorHorizontal) u = 1f - u
        val sourceU: Float
        val sourceV: Float
        when (transform.orientationDegrees) {
            90 -> {
                sourceU = v
                sourceV = 1f - u
            }
            180 -> {
                sourceU = 1f - u
                sourceV = 1f - v
            }
            270 -> {
                sourceU = 1f - v
                sourceV = u
            }
            else -> {
                sourceU = u
                sourceV = v
            }
        }
        return NormalizedPoint(
            sourceCrop.left + sourceU * sourceCrop.width,
            sourceCrop.top + sourceV * sourceCrop.height,
        )
    }

    companion object {
        fun createForPhotoCrop(
            sensor: SensorMetadata,
            output: RawOutput,
            transform: CaptureTransform,
            crop: PhotoCrop,
            processingLimit: Size2d? = null,
            rawEdgeCrop: RawEdgeCrop = RawEdgeCrop(),
        ): FrameGeometrySnapshot {
            val bounds = rawEdgeCrop.bounds(output.size)
            val size = Size2d(bounds.width(), bounds.height())
            return create(sensor, output, transform, crop.viewport(size, transform), processingLimit, rawEdgeCrop,
                keepNative = rawEdgeCrop.isActive && crop.ratio == CropRatio.NATIVE, sourceAspect = crop.sourceAspect(size))
        }

        fun createForRawRaster(
            sensor: SensorMetadata,
            output: RawOutput,
            transform: CaptureTransform,
            processingLimit: Size2d? = null,
            rawEdgeCrop: RawEdgeCrop = RawEdgeCrop(),
        ): FrameGeometrySnapshot = create(
            sensor = sensor,
            output = output,
            transform = transform,
            targetViewport = rawRasterViewport(output, transform),
            processingLimit = processingLimit,
            rawEdgeCrop = rawEdgeCrop,
            keepNative = rawEdgeCrop.isActive,
        )

        fun rawRasterViewport(output: RawOutput, transform: CaptureTransform): Size2d =
            transform.outputSize(output.size)

        fun create(
            sensor: SensorMetadata,
            output: RawOutput,
            transform: CaptureTransform,
            targetViewport: Size2d,
            processingLimit: Size2d? = null,
            rawEdgeCrop: RawEdgeCrop = RawEdgeCrop(),
            keepNative: Boolean = false,
            sourceAspect: Double? = null,
        ): FrameGeometrySnapshot {
            require(targetViewport.width > 0 && targetViewport.height > 0) {
                "Target viewport dimensions must be positive"
            }
            val pixelArray = requireNotNull(sensor.pixelArray(output.maximumResolutionMode)) {
                "Sensor pixel array is unavailable"
            }
            val activeArray = requireNotNull(sensor.rawActiveArray(output.maximumResolutionMode)) {
                "Sensor active array is unavailable"
            }
            validateActiveArray(activeArray, pixelArray)

            val calibration = mapActiveArray(activeArray, pixelArray, output.size)
            val bounds = rawEdgeCrop.bounds(output.size)
            val left = maxOf(calibration.left, bounds.left)
            val top = maxOf(calibration.top, bounds.top)
            val right = minOf(calibration.left + calibration.width, bounds.right)
            val bottom = minOf(calibration.top + calibration.height, bounds.bottom)
            val mappedActive = PixelCrop(
                if (right - left >= 2) left else bounds.left,
                if (bottom - top >= 2) top else bounds.top,
                if (right - left >= 2) right - left else bounds.width(),
                if (bottom - top >= 2) bottom - top else bounds.height(),
            )
            val desiredSourceAspect = sourceAspect ?: if (transform.swapsAxes) {
                targetViewport.height.toDouble() / targetViewport.width.toDouble()
            } else {
                targetViewport.width.toDouble() / targetViewport.height.toDouble()
            }
            val crop = if (keepNative) mappedActive else centerCrop(mappedActive, desiredSourceAspect)
            val normalizedActive = mappedActive.normalized(output.size)
            val normalizedCrop = crop.normalized(output.size)
            val rawOutputSize = processingLimit?.let { limit -> fitWithin(crop, limit) }
                ?: Size2d(crop.width, crop.height)
            val orientedOutput = transform.outputSize(rawOutputSize)
            return FrameGeometrySnapshot(
                sourceSize = output.size,
                activeArray = normalizedActive,
                sourceCrop = normalizedCrop,
                cropPixels = crop,
                transform = transform,
                targetViewport = if (keepNative) transform.outputSize(Size2d(crop.width, crop.height)) else targetViewport,
                outputSize = orientedOutput,
                calibrationArray = calibration.normalized(output.size),
                rawBounds = bounds,
            )
        }

        private fun validateActiveArray(active: Rect2d, pixelArray: Size2d) {
            require(active.left >= 0 && active.top >= 0 &&
                active.right <= pixelArray.width && active.bottom <= pixelArray.height
            ) {
                "Sensor active array must remain inside the pixel array"
            }
        }

        private fun mapActiveArray(
            active: Rect2d,
            pixelArray: Size2d,
            rawSize: Size2d,
        ): PixelCrop {
            val left = evenCeil(active.left.toDouble() * rawSize.width / pixelArray.width)
            val top = evenCeil(active.top.toDouble() * rawSize.height / pixelArray.height)
            val right = evenFloor(active.right.toDouble() * rawSize.width / pixelArray.width)
            val bottom = evenFloor(active.bottom.toDouble() * rawSize.height / pixelArray.height)
            require(right - left >= 2 && bottom - top >= 2) {
                "Mapped sensor active array is too small"
            }
            return PixelCrop(left, top, right - left, bottom - top)
        }

        private fun centerCrop(active: PixelCrop, desiredAspect: Double): PixelCrop {
            require(desiredAspect.isFinite() && desiredAspect > 0.0) {
                "Target aspect ratio must be finite and positive"
            }
            val activeAspect = active.width.toDouble() / active.height.toDouble()
            val width: Int
            val height: Int
            if (activeAspect > desiredAspect) {
                height = active.height
                width = evenFloor(height * desiredAspect).coerceAtLeast(2)
            } else {
                width = active.width
                height = evenFloor(width / desiredAspect).coerceAtLeast(2)
            }
            val left = active.left + evenFloor((active.width - width) / 2.0)
            val top = active.top + evenFloor((active.height - height) / 2.0)
            return PixelCrop(left, top, width, height)
        }

        private fun fitWithin(crop: PixelCrop, limit: Size2d): Size2d {
            require(limit.width >= 2 && limit.height >= 2) {
                "Processing limit must be at least 2 by 2"
            }
            val scale = min(
                1.0,
                min(limit.width.toDouble() / crop.width, limit.height.toDouble() / crop.height),
            )
            return Size2d(
                evenFloor(crop.width * scale).coerceAtLeast(2),
                evenFloor(crop.height * scale).coerceAtLeast(2),
            )
        }

        private fun PixelCrop.normalized(size: Size2d) = NormalizedRect(
            left = left.toFloat() / size.width,
            top = top.toFloat() / size.height,
            width = width.toFloat() / size.width,
            height = height.toFloat() / size.height,
        )

        private fun evenFloor(value: Double): Int = floor(value).toInt() and -2

        private fun evenCeil(value: Double): Int {
            val integer = kotlin.math.ceil(value).toInt()
            return if (integer and 1 == 0) integer else integer + 1
        }
    }
}
