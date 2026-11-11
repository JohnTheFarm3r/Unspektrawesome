package com.unspektrawesome.preview

class RgbHistogram private constructor(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val sampleCount: Int,
) {
    val peak: Int = maxOf(red.max(), green.max(), blue.max()).coerceAtLeast(1)

    companion object {
        fun fromBins(bins: IntArray): RgbHistogram {
            require(bins.size == 768 && bins.all { it >= 0 })
            val red = bins.copyOfRange(0, 256)
            val green = bins.copyOfRange(256, 512)
            val blue = bins.copyOfRange(512, 768)
            val count = red.sumOf { it.toLong() }
            require(count <= Int.MAX_VALUE && green.sumOf { it.toLong() } == count && blue.sumOf { it.toLong() } == count)
            return RgbHistogram(red, green, blue, count.toInt())
        }

        fun fromArgb(pixels: IntArray): RgbHistogram {
            val red = IntArray(256)
            val green = IntArray(256)
            val blue = IntArray(256)
            var count = 0
            for (pixel in pixels) {
                if (pixel ushr 24 == 0) continue
                red[pixel ushr 16 and 255]++
                green[pixel ushr 8 and 255]++
                blue[pixel and 255]++
                count++
            }
            return RgbHistogram(red, green, blue, count)
        }
    }
}
