package com.unspektrawesome.spektra

import org.json.JSONObject

/** Shadows and highlight compression remembered for one lens. */
data class LensTone(val shadows: Float = 0f, val highlights: Float = 0f) {
    fun normalized() = LensTone(
        shadows.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
        highlights.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
    )

    fun applyTo(params: SpektraFilmParams): SpektraFilmParams =
        normalized().let { params.copy(inputShadows = it.shadows, inputHighlights = it.highlights) }

    fun toJson(): JSONObject = JSONObject().put("shadows", shadows.toDouble()).put("highlights", highlights.toDouble())

    companion object {
        fun of(params: SpektraFilmParams) = LensTone(params.inputShadows, params.inputHighlights)

        fun fromJson(json: JSONObject): LensTone =
            LensTone(json.optDouble("shadows", 0.0).toFloat(), json.optDouble("highlights", 0.0).toFloat()).normalized()
    }
}

/**
 * Per-lens exposure and tone corrections stored with a custom profile.
 * EV steps are keyed by camera route, AE balance by lens profile and tone by lens key.
 */
data class SpektraLensCorrections(
    val exposureCompensation: Map<String, Int> = emptyMap(),
    val aeBalance: Map<String, Float> = emptyMap(),
    val tone: Map<String, LensTone> = emptyMap(),
) {
    val isEmpty: Boolean get() = exposureCompensation.isEmpty() && aeBalance.isEmpty() && tone.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("exposureCompensation", JSONObject().apply { exposureCompensation.forEach { (k, v) -> put(k, v) } })
        put("aeBalance", JSONObject().apply { aeBalance.forEach { (k, v) -> put(k, v.toDouble()) } })
        put("tone", JSONObject().apply { tone.forEach { (k, v) -> put(k, v.toJson()) } })
    }

    companion object {
        private const val MAX_ENTRIES = 256

        fun fromJson(json: JSONObject?): SpektraLensCorrections? {
            json ?: return null
            fun <T> read(name: String, value: (JSONObject, String) -> T?): Map<String, T> {
                val entries = json.optJSONObject(name) ?: return emptyMap()
                return entries.keys().asSequence().filter { it.isNotBlank() }.take(MAX_ENTRIES)
                    .mapNotNull { key -> value(entries, key)?.let { key to it } }.toMap()
            }
            val corrections = SpektraLensCorrections(
                exposureCompensation = read("exposureCompensation") { o, k ->
                    (o.opt(k) as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()
                },
                aeBalance = read("aeBalance") { o, k ->
                    (o.opt(k) as? Number)?.toFloat()?.takeIf { it.isFinite() }
                        ?.let(com.unspektrawesome.camera.exposure.AeBalance::normalize)
                },
                tone = read("tone") { o, k -> o.optJSONObject(k)?.let(LensTone::fromJson) },
            )
            return corrections.takeUnless { it.isEmpty }
        }
    }
}

/** Connects custom profiles with the per-lens corrections kept outside the film parameters. */
interface SpektraLensCorrectionHooks {
    /** Returns the corrections of every known lens, using [params] for the active lens. */
    fun capture(params: SpektraFilmParams): SpektraLensCorrections? = null

    /** Stores [corrections] for every lens and returns [params] adjusted for the active lens. */
    fun apply(corrections: SpektraLensCorrections, params: SpektraFilmParams): SpektraFilmParams = params

    object None : SpektraLensCorrectionHooks
}
