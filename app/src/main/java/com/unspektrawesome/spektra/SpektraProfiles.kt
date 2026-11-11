package com.unspektrawesome.spektra

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpektraProfile(
    val id: String,
    val name: String,
    val params: SpektraFilmParams,
    val corrections: SpektraLensCorrections? = null,
)

interface SpektraProfilePersistence {
    fun load(): List<SpektraProfile>
    fun save(profiles: List<SpektraProfile>)
}

class SharedPreferencesSpektraProfilePersistence(private val preferences: SharedPreferences) : SpektraProfilePersistence {
    override fun load() = SpektraProfileJson.decode(preferences.getString("custom_profiles", null))
    override fun save(profiles: List<SpektraProfile>) {
        preferences.edit().putString("custom_profiles", SpektraProfileJson.encode(profiles)).apply()
    }
}

internal object SpektraProfileJson {
    fun encode(profiles: List<SpektraProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("state", JSONObject(SpektraStateJson.encode(SpektraState(params = profile.params))))
                profile.corrections?.let { put("lensCorrections", it.toJson()) }
            })
        }
    }.toString()

    fun decode(json: String?): List<SpektraProfile> = runCatching {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                val state = SpektraStateJson.decode(entry.getJSONObject("state").toString()) ?: return@runCatching null
                val id = entry.getString("id").takeIf { it.isNotBlank() } ?: return@runCatching null
                val name = entry.getString("name").trim().takeIf { it.isNotEmpty() } ?: return@runCatching null
                SpektraProfile(id, name, state.params,
                    runCatching { SpektraLensCorrections.fromJson(entry.optJSONObject("lensCorrections")) }.getOrNull())
            }.getOrNull()
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}

class SpektraProfiles(private val persistence: SpektraProfilePersistence = object : SpektraProfilePersistence {
    override fun load() = emptyList<SpektraProfile>()
    override fun save(profiles: List<SpektraProfile>) = Unit
}) {
    private val mutableProfiles = MutableStateFlow(persistence.load())
    val profiles = mutableProfiles.asStateFlow()

    fun save(
        name: String,
        params: SpektraFilmParams,
        replaceId: String? = null,
        corrections: SpektraLensCorrections? = null,
    ): SpektraProfile {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 40) { "Use a name between 1 and 40 characters." }
        require(mutableProfiles.value.none { it.id != replaceId && it.name.equals(cleanName, ignoreCase = true) }) {
            "A profile with this name already exists."
        }
        require(replaceId == null || mutableProfiles.value.any { it.id == replaceId }) { "Profile no longer exists." }
        val profile = SpektraProfile(replaceId ?: UUID.randomUUID().toString(), cleanName, params, corrections)
        publish(if (replaceId == null) mutableProfiles.value + profile else mutableProfiles.value.map {
            if (it.id == replaceId) profile else it
        })
        return profile
    }

    fun delete(id: String) = publish(mutableProfiles.value.filterNot { it.id == id })

    fun importProfile(profile: SpektraProfile): SpektraProfile = importProfiles(listOf(profile)).single()

    fun importProfiles(incoming: List<SpektraProfile>): List<SpektraProfile> {
        require(incoming.isNotEmpty()) { "Select at least one custom profile." }
        val names = mutableProfiles.value.map { it.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
        val imported = incoming.map { profile ->
            val baseName = profile.name.trim()
            require(baseName.isNotEmpty() && baseName.length <= 40) { "Use a name between 1 and 40 characters." }
            var name = baseName
            var copy = 2
            while (!names.add(name.lowercase(java.util.Locale.ROOT))) {
                val suffix = " (${copy++})"
                name = baseName.take(40 - suffix.length).trimEnd() + suffix
            }
            profile.copy(id = UUID.randomUUID().toString(), name = name)
        }
        publish(mutableProfiles.value + imported)
        return imported
    }

    private fun publish(profiles: List<SpektraProfile>) {
        persistence.save(profiles)
        mutableProfiles.value = profiles
    }
}
