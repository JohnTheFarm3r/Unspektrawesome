package com.unspektrawesome.settings

import android.content.Context
import android.content.SharedPreferences
import com.unspektrawesome.camera.exposure.AeBalance
import com.unspektrawesome.spektra.LensTone
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraLensCorrectionHooks
import com.unspektrawesome.spektra.SpektraLensCorrections
import com.unspektrawesome.spektra.SpektraStateRepository
import org.json.JSONObject

/** Identifies the lens whose shadows and highlight compression are being edited. */
fun CameraPreferences.lensToneKey(selectedRouteId: String?): String? =
    lensProfiles.firstOrNull { it.id == activeLensProfileId }?.let { "lens:${it.id}" }
        ?: selectedRouteId?.takeIf { lensProfiles.isEmpty() }?.let { "route:$it" }

interface LensToneStorage {
    /** The lens whose values are currently held by the film parameters. */
    var activeKey: String?
    fun load(key: String): LensTone?
    fun save(key: String, tone: LensTone)
}

class SharedPreferencesLensToneStorage(private val preferences: SharedPreferences) : LensToneStorage {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("lens_tone", Context.MODE_PRIVATE),
    )

    override var activeKey: String?
        get() = preferences.getString(ACTIVE, null)
        set(value) { preferences.edit().putString(ACTIVE, value).apply() }

    override fun load(key: String): LensTone? = preferences.getString(PREFIX + key, null)?.let { json ->
        runCatching { LensTone.fromJson(JSONObject(json)) }.getOrNull()
    }

    override fun save(key: String, tone: LensTone) {
        preferences.edit().putString(PREFIX + key, tone.normalized().toJson().toString()).apply()
    }

    private companion object {
        const val ACTIVE = "active_lens"
        const val PREFIX = "tone:"
    }
}

interface LensExposureStorage {
    fun load(routeId: String): Int
    fun save(routeId: String, compensation: Int)
}

/**
 * Keeps shadows and highlight compression separate for each lens, and saves or restores
 * EV, AE balance, shadows and highlight compression of every lens with custom profiles.
 */
class LensCorrectionCoordinator(
    private val tones: LensToneStorage,
    private val exposure: LensExposureStorage,
    private val preferences: () -> CameraPreferences,
    private val selectedRouteId: () -> String?,
    private val cameraRouteIds: () -> List<String>,
    private val savePreferences: (CameraPreferences) -> Unit,
    private val setActiveExposureCompensation: (Int) -> Unit,
) : SpektraLensCorrectionHooks {

    /** Stores the previous lens's values and loads the values of [key] when the lens changes. */
    fun selectLens(key: String?, repository: SpektraStateRepository) {
        val previous = tones.activeKey
        if (key == null || key == previous) return
        val current = LensTone.of(repository.state.value.params)
        tones.activeKey = key
        // On the first run, or for a lens without saved values, the current values carry over.
        if (previous != null) tones.save(previous, current)
        tones.load(key)?.takeIf { it != current }?.let(repository::switchLensTone)
    }

    override fun capture(params: SpektraFilmParams): SpektraLensCorrections {
        val prefs = preferences()
        val active = tones.activeKey
        val current = LensTone.of(params)
        val toneKeys = prefs.lensProfiles.map { "lens:${it.id}" }
            .ifEmpty { cameraRouteIds().map { "route:$it" } } + listOfNotNull(active)
        val routes = prefs.lensProfiles.map { it.routeId }.ifEmpty { cameraRouteIds() } +
            listOfNotNull(selectedRouteId())
        return SpektraLensCorrections(
            exposureCompensation = routes.distinct().associateWith(exposure::load),
            aeBalance = prefs.lensProfiles.associate { it.id to AeBalance.normalize(it.aeBalance) },
            tone = toneKeys.distinct().associateWith { key ->
                if (key == active) current else tones.load(key) ?: current
            },
        )
    }

    override fun apply(corrections: SpektraLensCorrections, params: SpektraFilmParams): SpektraFilmParams {
        val active = tones.activeKey
        corrections.tone.forEach { (key, tone) -> if (key != active) tones.save(key, tone) }

        corrections.exposureCompensation.forEach { (route, value) -> exposure.save(route, value) }
        selectedRouteId()?.let(corrections.exposureCompensation::get)?.let(setActiveExposureCompensation)

        val prefs = preferences()
        val balanced = prefs.copy(lensProfiles = prefs.lensProfiles.map { lens ->
            corrections.aeBalance[lens.id]?.let { lens.copy(aeBalance = AeBalance.normalize(it)) } ?: lens
        })
        if (balanced != prefs) savePreferences(balanced)

        return active?.let(corrections.tone::get)?.applyTo(params) ?: params
    }
}
