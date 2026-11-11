package com.unspektrawesome.video

import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.capture.CropRatio
import com.unspektrawesome.capture.PhotoCrop

enum class VideoDownscale(val factor: Double) {
    FULL(1.0), ONE_QUARTER(1.25), ONE_HALF(1.5), DOUBLE(2.0), TRIPLE(3.0);
}

enum class VideoPixelCrop(val width: Int, val height: Int, val aspectLabel: String) {
    UHD(3840, 2160, "16:9"),
    QHD(2560, 1440, "16:9"),
    FULL_HD(1920, 1080, "16:9"),
    HD(1280, 720, "16:9"),
    UHD_FLAT(3840, 2076, "1.85:1"),
    UHD_TWO_ONE(3840, 1920, "2:1"),
    UHD_SCOPE(3840, 1608, "2.39:1"),
    DCI(4096, 2160, "1.90:1"),
    DCI_SCOPE(4096, 1716, "2.39:1");

    fun aspect(swapped: Boolean = false) = PhotoCrop(CropRatio.CUSTOM,
        width.toDouble(), height.toDouble(), swapped)

    fun sourceSize(raw: Size2d, swapped: Boolean): Size2d =
        if ((raw.width < raw.height) != swapped) Size2d(height, width) else Size2d(width, height)

    fun fits(raw: Size2d, swapped: Boolean, available: Size2d = raw): Boolean = sourceSize(raw, swapped).let {
        it.width <= available.width && it.height <= available.height
    }
}

internal object VideoCropPresets {
    val aspects = listOf(
        PhotoCrop(CropRatio.NATIVE),
        PhotoCrop(CropRatio.SIXTEEN_NINE),
        PhotoCrop(CropRatio.CUSTOM, 1.85, 1.0),
        PhotoCrop(CropRatio.TWO_ONE),
        PhotoCrop(CropRatio.CINEMA),
    )
}

internal fun PhotoCrop.landscapeVideoCrop(): PhotoCrop = copy(
    swapped = false,
    customWidth = maxOf(customWidth, customHeight),
    customHeight = minOf(customWidth, customHeight),
)
