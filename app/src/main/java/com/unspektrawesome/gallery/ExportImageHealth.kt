package com.unspektrawesome.gallery

internal object ExportImageHealth {
    fun minimumBytes(width: Int, height: Int): Long = maxOf(4096L, width.toLong() * height / 16)

    fun concern(pixels: IntArray): String? {
        if (pixels.isEmpty()) return "JPEG has no readable pixels"
        val values = IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            maxOf((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
        }.apply { sort() }
        val dark = values.count { it <= 8 }
        if (dark.toDouble() / values.size >= .98 || values[values.size * 99 / 100] <= 16)
            return "JPEG appears almost black"
        if (values[values.size * 95 / 100] - values[values.size * 5 / 100] < 8)
            return "JPEG has unusually little image detail"
        return null
    }
}
