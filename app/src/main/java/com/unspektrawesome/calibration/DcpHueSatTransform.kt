package com.unspektrawesome.calibration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.pow

/** CPU calibration for embedded DNG previews and processing-queue thumbnails. */
class DcpHueSatTransform private constructor(
    private val data: ByteBuffer, private val base: Int, private val weight: Float,
) {
    private fun number(index: Int) = data.getFloat(base * 16 + index * 4)
    private fun index(at: Int, maximum: Int): Int = number(at).let {
        require(it.isFinite() && it in 0f..maximum.toFloat() && it == it.toInt().toFloat()) { "Invalid DCP table" }
        it.toInt()
    }
    private val hue = index(0, DcpParser.MAX_TABLE_ENTRIES)
    private val saturation = index(1, DcpParser.MAX_TABLE_ENTRIES)
    private val value = index(2, DcpParser.MAX_TABLE_ENTRIES)
    private val encoded = index(3, 1) == 1 && value > 1
    private val first = index(4, 262146)
    private val second = index(5, 262146)

    init {
        val entries = hue.toLong() * saturation * value
        val count = index(6, 262146)
        require(hue >= 1 && saturation >= 2 && value >= 1 && entries <= DcpParser.MAX_TABLE_ENTRIES && first == 2 &&
            (second == 2 || second.toLong() == 2 + entries) && count.toLong() == second + entries &&
            (base.toLong() + count) * 16 <= data.limit()) { "Incomplete DCP table" }
    }

    private val table = FloatArray(hue * saturation * value * 3) { index ->
        val entry = index / 3 * 4 + index % 3
        val a = number(first * 4 + entry)
        if (first == second) a else a * weight + number(second * 4 + entry) * (1f - weight)
    }

    fun apply(rgb: FloatArray): FloatArray = FloatArray(3).also { apply(rgb, it) }

    fun apply(rgb: FloatArray, output: FloatArray) {
        fun toProPhoto(row: Int) = (TO_PROPHOTO[row * 3] * rgb[0] + TO_PROPHOTO[row * 3 + 1] * rgb[1] +
            TO_PROPHOTO[row * 3 + 2] * rgb[2]).toFloat().coerceAtLeast(0f)
        val red = toProPhoto(0)
        val green = toProPhoto(1)
        val blue = toProPhoto(2)
        val v = maxOf(red, green, blue)
        val delta = v - minOf(red, green, blue)
        var h = if (delta < 1e-7f) 0f else when (v) {
            red -> (green - blue) / delta
            green -> 2f + (blue - red) / delta
            else -> 4f + (red - green) / delta
        } / 6f
        h -= floor(h)
        val s = if (v > 0f) delta / v else 0f
        var brightness = if (encoded) { if (v <= .0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - .055f } else v
        val hp = h * hue
        val sp = s.coerceIn(0f, 1f) * (saturation - 1)
        val vp = brightness.coerceIn(0f, 1f) * (value - 1)
        val hl = hp.toInt(); val sl = sp.toInt(); val vl = vp.toInt()
        val hf = hp - hl; val sf = sp - sl; val vf = vp - vl
        var hueShift = 0f
        var saturationScale = 0f
        var valueScale = 0f
        for (corner in 0..7) {
            val upperH = corner and 1 != 0
            val upperS = corner and 2 != 0
            val upperV = corner and 4 != 0
            val contribution = (if (upperH) hf else 1f - hf) *
                (if (upperS) sf else 1f - sf) * (if (upperV) vf else 1f - vf)
            val hc = if (upperH) (hl + 1) % hue else hl
            val sc = if (upperS) minOf(sl + 1, saturation - 1) else sl
            val vc = if (upperV) minOf(vl + 1, value - 1) else vl
            val entry = ((vc * hue + hc) * saturation + sc) * 3
            hueShift += table[entry] * contribution
            saturationScale += table[entry + 1] * contribution
            valueScale += table[entry + 2] * contribution
        }
        h = h + hueShift / 360f
        h -= floor(h)
        val sat = (s * saturationScale).coerceIn(0f, 1f)
        brightness *= valueScale
        if (encoded) brightness = if (brightness <= .04045f) brightness / 12.92f else ((brightness + .055f) / 1.055f).pow(2.4f)
        val chroma = brightness * sat
        val section = h * 6f
        val x = chroma * (1f - kotlin.math.abs(section % 2f - 1f))
        val offset = brightness - chroma
        val r: Float
        val g: Float
        val b: Float
        when (section.toInt()) {
            0 -> { r = chroma; g = x; b = 0f }
            1 -> { r = x; g = chroma; b = 0f }
            2 -> { r = 0f; g = chroma; b = x }
            3 -> { r = 0f; g = x; b = chroma }
            4 -> { r = x; g = 0f; b = chroma }
            else -> { r = chroma; g = 0f; b = x }
        }
        repeat(3) { row -> output[row] = (FROM_PROPHOTO[row * 3] * (r + offset) +
            FROM_PROPHOTO[row * 3 + 1] * (g + offset) + FROM_PROPHOTO[row * 3 + 2] * (b + offset)).toFloat() }
    }

    companion object {
        fun from(color: ByteBuffer, tables: ByteBuffer?): DcpHueSatTransform? {
            val offset = color.order(ByteOrder.nativeOrder()).getFloat(96)
            if (offset == 0f) return null
            val weight = color.getFloat(100)
            require(offset.isFinite() && offset in 1f..1048576f && offset == offset.toInt().toFloat() &&
                weight.isFinite() && weight in 0f..1f && tables != null && (offset.toLong() + 2) * 16 <= tables.limit()) { "Missing DCP table" }
            return DcpHueSatTransform(tables.duplicate().order(ByteOrder.nativeOrder()), offset.toInt(), weight)
        }
        private val TO_PROPHOTO = doubleArrayOf(.529337205, .330188454, .140581572, .098361944, .873425969, .028168661, .016873187, .117644399, .865354738)
        private val FROM_PROPHOTO = doubleArrayOf(2.034149267, -.727666256, -.306771900, -.228802244, 1.231806991, -.002927093, -.008557507, -.153274881, 1.161975004)
    }
}
