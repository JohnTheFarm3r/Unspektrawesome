package com.unspektrawesome.video

import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.SensorMetadata
import com.unspektrawesome.capture.CaptureTransform
import com.unspektrawesome.capture.CropRatio
import com.unspektrawesome.capture.PhotoCrop
import com.unspektrawesome.capture.PixelCrop
import com.unspektrawesome.settings.RawEdgeCrop
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.capture.NormalizedRect

internal object VideoFraming {
    fun source(sensor: SensorMetadata, output: RawOutput, transform: CaptureTransform,
        crop: PhotoCrop, pixels: VideoPixelCrop? = null, edges: RawEdgeCrop = RawEdgeCrop()): FrameGeometrySnapshot? {
        val native = FrameGeometrySnapshot.create(sensor, output, transform, transform.outputSize(output.size),
            rawEdgeCrop = edges, keepNative = true)
        if (pixels != null) {
            val size = pixels.sourceSize(output.size, crop.swapped)
            if (size.width > native.cropPixels.width || size.height > native.cropPixels.height) return null
            return cropped(native, size)
        }
        return if (crop.ratio == CropRatio.NATIVE) native else
            FrameGeometrySnapshot.createForPhotoCrop(sensor, output, transform, crop, rawEdgeCrop = edges)
    }

    fun retainedSize(geometry: FrameGeometrySnapshot): Size2d = geometry.cropPixels.let {
        Size2d(maxOf(it.width, it.height), minOf(it.width, it.height))
    }

    fun outputSize(retained: Size2d, downscale: VideoDownscale,
        widthAlignment: Int = 2, heightAlignment: Int = 2): Size2d? {
        require(widthAlignment > 0 && heightAlignment > 0)
        val width = (retained.width / downscale.factor / widthAlignment).toInt() * widthAlignment
        val height = (retained.height / downscale.factor / heightAlignment).toInt() * heightAlignment
        return if (width >= 2 && height >= 2) Size2d(width, height) else null
    }

    fun sourceSize(geometry: FrameGeometrySnapshot, encodedSize: Size2d): Size2d =
        if (geometry.cropPixels.width < geometry.cropPixels.height) Size2d(encodedSize.height, encodedSize.width)
        else encodedSize

    fun fits(geometry: FrameGeometrySnapshot, encodedSize: Size2d): Boolean {
        val size = sourceSize(geometry, encodedSize)
        return size.width <= geometry.cropPixels.width && size.height <= geometry.cropPixels.height
    }

    fun recording(geometry: FrameGeometrySnapshot, encodedSize: Size2d,
        downscale: VideoDownscale = VideoDownscale.FULL): FrameGeometrySnapshot {
        val size = sourceSize(geometry, encodedSize)
        require(fits(geometry, encodedSize))
        return if (downscale == VideoDownscale.FULL) cropped(geometry, size) else geometry.atSize(size)
    }

    private fun cropped(frame: FrameGeometrySnapshot, size: Size2d): FrameGeometrySnapshot {
        val before = frame.cropPixels
        require(size.width <= before.width && size.height <= before.height)
        val pixels = PixelCrop(before.left + ((before.width - size.width) / 2 and -2),
            before.top + ((before.height - size.height) / 2 and -2), size.width, size.height)
        val crop = NormalizedRect(pixels.left.toFloat() / frame.sourceSize.width,
            pixels.top.toFloat() / frame.sourceSize.height, pixels.width.toFloat() / frame.sourceSize.width,
            pixels.height.toFloat() / frame.sourceSize.height)
        val active = frame.activeArray
        val scale = maxOf(crop.width / active.width, crop.height / active.height).coerceAtMost(1f)
        val width = active.width * scale
        val height = active.height * scale
        val left = (crop.left + (crop.width - width) / 2f)
            .coerceIn(active.left, active.left + (active.width - width))
        val top = (crop.top + (crop.height - height) / 2f)
            .coerceIn(active.top, active.top + (active.height - height))
        return frame.copy(sourceCrop = crop, cropPixels = pixels,
            activeArray = NormalizedRect(left, top, width, height),
            targetViewport = frame.transform.outputSize(size), outputSize = frame.transform.outputSize(size))
    }

    fun stabilized(frame: FrameGeometrySnapshot, ratio: Float): FrameGeometrySnapshot {
        require(ratio.isFinite() && ratio in 1f..1.5f)
        val crop = frame.cropPixels
        val scale = ratio.toString().toDouble()
        val width = (crop.width / scale).toInt().coerceAtLeast(2) and -2
        val height = (crop.height / scale).toInt().coerceAtLeast(2) and -2
        return cropped(frame, Size2d(width, height))
    }
}
