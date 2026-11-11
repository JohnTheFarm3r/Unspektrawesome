package com.unspektrawesome.capture

import com.unspektrawesome.camera.Size2d
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.roundToInt

enum class CropRatio(val label: String, val width: Double, val height: Double) {
    NATIVE("Native", 0.0, 0.0),
    FOUR_THREE("4:3", 4.0, 3.0),
    THREE_TWO("3:2", 3.0, 2.0),
    SQUARE("1:1", 1.0, 1.0),
    SIXTEEN_NINE("16:9", 16.0, 9.0),
    FIVE_FOUR("5:4", 5.0, 4.0),
    SEVEN_FIVE("7:5", 7.0, 5.0),
    TWO_ONE("2:1", 2.0, 1.0),
    CINEMA("2.39:1", 2.39, 1.0),
    THREE_ONE("3:1", 3.0, 1.0),
    CUSTOM("Custom", 3.0, 2.0),
}

data class PhotoCrop(
    val ratio: CropRatio = CropRatio.NATIVE,
    val customWidth: Double = 3.0,
    val customHeight: Double = 2.0,
    val swapped: Boolean = false,
) {
    init { require(validCustom(customWidth, customHeight)) { "Crop proportions must be positive and between 1:10 and 10:1" } }

    private val proportions: Pair<Double, Double> get() =
        if (ratio == CropRatio.CUSTOM) customWidth to customHeight else ratio.width to ratio.height

    fun sourceAspect(source: Size2d): Double {
        if (ratio == CropRatio.NATIVE) return source.width.toDouble() / source.height
        val (width, height) = proportions
        var aspect = width / height
        if (source.width < source.height) aspect = 1.0 / aspect
        if (swapped) aspect = 1.0 / aspect
        return aspect
    }

    fun viewport(source: Size2d, transform: CaptureTransform): Size2d {
        if (ratio == CropRatio.NATIVE) return transform.outputSize(source)
        return transform.outputSize(Size2d((sourceAspect(source) * 10_000).roundToInt(), 10_000))
    }

    fun label(portrait: Boolean = false): String {
        if (ratio == CropRatio.NATIVE) return "Native"
        val (width, height) = proportions
        val reverse = portrait != swapped
        return if (reverse) "${number(height)}:${number(width)}" else "${number(width)}:${number(height)}"
    }

    fun toJson() = JSONObject().put("ratio", ratio.name).put("width", customWidth)
        .put("height", customHeight).put("swapped", swapped)

    companion object {
        fun validCustom(width: Double, height: Double) = width.isFinite() && height.isFinite() &&
            width in .001..9999.0 && height in .001..9999.0 && width / height in .1..10.0

        fun fromJson(value: JSONObject?): PhotoCrop = runCatching {
            if (value == null) return PhotoCrop()
            PhotoCrop(CropRatio.valueOf(value.getString("ratio")), value.optDouble("width", 3.0),
                value.optDouble("height", 2.0), value.optBoolean("swapped", false))
        }.getOrDefault(PhotoCrop())

        fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
