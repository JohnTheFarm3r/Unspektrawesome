package com.unspektrawesome.vulkan.params

internal object ChromaNoiseProfile {
    fun resolveNoiseProfilePair(noiseProfile: FloatArray?, isoValue: Int): FloatArray {
        if (noiseProfile != null && noiseProfile.size >= 2) {
            var sumS = 0.0f
            var sumO = 0.0f
            var pairs = 0
            var i = 0
            while (i + 1 < noiseProfile.size) {
                val s = noiseProfile[i]
                val o = noiseProfile[i + 1]
                if (s.isFinite() && o.isFinite() && s > 0f && o > 0f) {
                    sumS += s
                    sumO += o
                    pairs++
                }
                i += 2
            }
            if (pairs > 0) {
                return floatArrayOf(sumS / pairs.toFloat(), sumO / pairs.toFloat())
            }
        }
        return resolveNoiseProfilePair(0f, 0f, isoValue)
    }

    fun resolveNoiseProfilePair(profileS: Float, profileO: Float, isoValue: Int): FloatArray {
        if (profileS.isFinite() && profileO.isFinite() && profileS > 0f && profileO > 0f) {
            return floatArrayOf(profileS, profileO)
        }
        val fallbackGain = (isoValue.coerceAtLeast(100).toFloat() / 100.0f).coerceAtLeast(1f)
        return floatArrayOf(
            1.0e-4f * fallbackGain,
            4.5e-7f * kotlin.math.sqrt(fallbackGain)
        )
    }
}
