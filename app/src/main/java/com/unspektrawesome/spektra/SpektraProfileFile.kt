package com.unspektrawesome.spektra

import com.unspektrawesome.io.readUpTo
import com.unspektrawesome.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal object SpektraProfileFile {
    private const val FORMAT = "unspektrawesome-custom-profile"
    private const val COLLECTION_FORMAT = "unspektrawesome-custom-profiles"
    private const val VERSION = 1
    private const val MAX_BYTES = 256 * 1024
    const val MAX_PROFILES = 512
    const val MAX_COLLECTION_BYTES = 16 * 1024 * 1024

    private const val LENS_CORRECTIONS = "lensCorrections"
    private val sourceKeys = setOf("inputHighlights", "inputHighlightThreshold", "inputShadows",
        "exportHighlightRecovery", "exportChromaDenoise", "chromaDenoiseStrength", "exportHotPixelRemoval",
        "formatGrainAmounts", "diffusionAmounts", "gpuRenderTiling")

    fun encode(profile: SpektraProfile): String {
        val all = profile.params.toJsonObject()
        return JSONObject().apply {
            put("format", "spektra-recipe")
            put("version", VERSION)
            put("source_app", "unspektrawesome")
            put("name", profile.name)
            put("spektra_params", JSONObject().apply {
                all.keys().forEach { if (it !in sourceKeys) put(it, all.get(it)) }
            })
            put("source_app_params", JSONObject().apply {
                sourceKeys.forEach { put(it, all.get(it)) }
                profile.corrections?.let { put(LENS_CORRECTIONS, it.toJson()) }
            })
        }.toString(2)
    }

    fun encode(profiles: List<SpektraProfile>): String {
        require(profiles.size in 1..MAX_PROFILES) { "Select between 1 and $MAX_PROFILES custom profiles." }
        if (profiles.size == 1) return encode(profiles.single())
        val json = JSONObject().apply {
            put("format", "spektra-recipes")
            put("source_app", "unspektrawesome")
            put("version", VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("presets", JSONArray().apply { profiles.forEach { put(JSONObject(encode(it))) } })
        }.toString(2)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_COLLECTION_BYTES) { "The selected custom profiles are too large." }
        return json
    }

    fun readProfiles(input: InputStream): List<SpektraProfile> {
        val stream = input.buffered()
        stream.mark(2)
        val jpeg = stream.read() == 0xff && stream.read() == 0xd8
        stream.reset()
        if (jpeg) return listOf(SpektraProfileJpeg.read(stream))
        val bytes = stream.readUpTo(MAX_COLLECTION_BYTES + 1)
        require(bytes.size <= MAX_COLLECTION_BYTES) { "This custom-profile backup is too large." }
        val json = bytes.toString(Charsets.UTF_8)
        val value = try { JSONObject(json) } catch (_: Exception) {
            throw IllegalArgumentException("Choose an exported Unspektrawesome custom-profile file.")
        }
        if (value.opt("format") in setOf(FORMAT, "spektra-recipe")) return listOf(decode(json))
        require(value.opt("format") in setOf(COLLECTION_FORMAT, "spektra-recipes", "com.unmcrawesome.edit-presets")) { "Choose an exported Unspektrawesome custom-profile file." }
        require(value.opt("version") == VERSION) { "This backup version is not supported. Try updating the app." }
        val array = value.optJSONArray(if (value.opt("format") == COLLECTION_FORMAT) "profiles" else "presets") ?: throw IllegalArgumentException("This backup is missing its profiles.")
        require(array.length() in 1..MAX_PROFILES) { "This backup has no custom profiles or contains too many." }
        return (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            if (value.opt("format") == "com.unmcrawesome.edit-presets") {
                val params = when (val raw = entry.opt("params")) {
                    is JSONObject -> raw
                    is String -> JSONObject(raw)
                    else -> throw IllegalArgumentException("This profile file is missing its settings.")
                }
                decode(JSONObject().put("format", "spektra-recipe").put("version", 1)
                    .put("name", entry.getString("name")).put("source_app", "unmcrawesome")
                    .put("spektra_params", params.getJSONObject("spektra")).toString())
            } else decode(entry.toString())
        }
    }

    fun read(input: InputStream): SpektraProfile {
        val bytes = input.readUpTo(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "This profile file is too large." }
        return decode(bytes.toString(Charsets.UTF_8))
    }

    fun decode(json: String): SpektraProfile {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This profile file is too large." }
        val value = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw IllegalArgumentException("Choose an exported Unspektrawesome profile file.")
        }
        require(value.opt("format") in setOf(FORMAT, "spektra-recipe")) { "Choose an exported Unspektrawesome profile file." }
        require(value.opt("version") == VERSION) { "This profile file version is not supported. Try updating the app." }
        val name = (value.opt("name") as? String)?.trim().orEmpty()
        require(name.isNotEmpty() && name.length <= 40) { "The profile name must contain 1 to 40 characters." }
        if (value.opt("format") == "spektra-recipe") {
            require((value.opt("source_app") as? String)?.isNotBlank() == true) {
                "This profile contains invalid or unsupported settings."
            }
            val shared = value.optJSONObject("spektra_params")
                ?: throw IllegalArgumentException("This profile file is missing its settings.")
            val params = JSONObject().apply {
                shared.keys().forEach { if (it !in sourceKeys) put(it, shared.get(it)) }
            }
            validateParams(params, SpektraFilmParams().toJsonObject())
            var corrections: SpektraLensCorrections? = null
            if (value.getString("source_app") == "unspektrawesome") {
                val native = value.optJSONObject("source_app_params")
                    ?: throw IllegalArgumentException("This profile file is missing its settings.")
                sourceKeys.forEach { if (native.has(it)) params.put(it, native.get(it)) }
                validateParams(params, SpektraFilmParams().toJsonObject())
                corrections = runCatching { SpektraLensCorrections.fromJson(native.optJSONObject(LENS_CORRECTIONS)) }.getOrNull()
            }
            return SpektraProfile("", name, SpektraFilmParams.fromJson(params), corrections)
        }
        val state = value.optJSONObject("state")
            ?: throw IllegalArgumentException("This profile file is missing its settings.")
        val params = state.optJSONObject("params")
            ?: throw IllegalArgumentException("This profile file is missing its settings.")
        validateParams(params, SpektraFilmParams().toJsonObject())
        val decoded = SpektraStateJson.decode(state.toString())
            ?: throw IllegalArgumentException("These profile settings are not supported. Try updating the app.")
        return SpektraProfile("", name, decoded.params)
    }

    private fun validateParams(value: JSONObject, template: JSONObject) {
        require(value.keys().asSequence().any(template::has)) {
            "This profile file is missing some settings."
        }
        for (key in value.keys()) {
            if (!template.has(key)) continue
            val actual = value.get(key)
            val expected = template.opt(key)
            val valid = when (expected) {
                is Boolean -> actual is Boolean
                is Int -> actual is Number && actual.toDouble() == actual.toInt().toDouble()
                is Number -> actual is Number && actual.toFloat().isFinite()
                is JSONObject -> actual is JSONObject && actual.keys().asSequence().all {
                    val amount = actual.get(it)
                    val indices = if (key == "formatGrainAmounts") SpektraLook.formats.indices else SpektraLook.diffusionFamilies.indices
                    val maximum = if (key == "formatGrainAmounts") 3f else 2f
                    it.toIntOrNull() in indices && amount is Number && amount.toFloat().isFinite() && amount.toFloat() in 0f..maximum
                }
                else -> false
            }
            require(valid) { "This profile contains invalid or unsupported settings." }
        }
    }
}
