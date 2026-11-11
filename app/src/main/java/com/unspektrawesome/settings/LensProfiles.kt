package com.unspektrawesome.settings

import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.RawFormat
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.exposure.AeLimits
import com.unspektrawesome.camera.exposure.AeBalance
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.discovery.LensVerification
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

data class LensProfile(
    val id: String,
    val sensorId: String,
    val name: String,
    val routeId: String,
    val rawStream: RawStreamSelection,
    val enabled: Boolean = true,
    val previewQuality: RawPreviewQuality = RawPreviewQuality.LOW,
    val rawLevels: RawLevelCalibration = RawLevelCalibration(),
    val previewShortEdge: Int? = 480,
    val color: LensColorSettings = LensColorSettings(),
    val verification: LensVerification? = null,
    val autoManaged: Boolean = false,
    val aeLimits: AeLimits = AeLimits(),
    val aeBalance: Float = 0f,
    val vendorOverrides: VendorOverrides = VendorOverrides(),
    val aeEngine: AeEngine = AeEngine.SPEKTRA,
    val rawEdgeCrop: RawEdgeCrop = RawEdgeCrop(),
    val rawLayoutOverride: RawLayoutOverride = RawLayoutOverride(),
    val cfaOverride: CfaOverride = CfaOverride(),
    val capture: LensCaptureSettings = LensCaptureSettings(),
    val focalLength: LensFocalLengthSettings = LensFocalLengthSettings(),
    val photoOisEnabled: Boolean = true,
    val rawSession: com.unspektrawesome.camera.session.RawSessionConfiguration? = null,
) {
    val focalGroup: String get() = "$sensorId:${focalLength.factor}"
    val managedByDiscovery: Boolean get() = autoManaged && verification != null
    val displayName: String get() = verification?.configuration?.let {
        if (it.isAutomaticName(name)) it.name else name
    } ?: name
}

enum class LensProfileImportMode { KEEP_EXISTING, REPLACE_ALL }

object LensProfiles {
    fun reorder(preferences: CameraPreferences, ids: List<String>): CameraPreferences {
        val profiles = preferences.lensProfiles.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(profiles::get)
        return preferences.copy(lensProfiles = ordered + preferences.lensProfiles.filterNot { it.id in ids })
    }

    fun importProfiles(preferences: CameraPreferences, entries: List<ImportedLensProfile>,
        mode: LensProfileImportMode = LensProfileImportMode.KEEP_EXISTING): CameraPreferences {
        require(entries.isNotEmpty()) { "Select at least one lens profile." }
        fun matches(existing: LensProfile, incoming: LensProfile) = existing.id == incoming.id ||
            existing.name.trim().equals(incoming.name.trim(), ignoreCase = true)
        val kept = if (mode == LensProfileImportMode.KEEP_EXISTING) preferences.lensProfiles else emptyList()
        val names = kept.map { it.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
        val imported = entries.map { entry ->
            val original = entry.profile
            var name = original.name
            var suffix = 2
            while (!names.add(name.lowercase(java.util.Locale.ROOT))) name = "${original.name} (${suffix++})"
            original.copy(id = UUID.randomUUID().toString(), name = name)
        }
        val startup = entries.indexOfFirst { it.startup }.takeIf { it >= 0 }?.let(imported::get)
        fun retained(id: String?): LensProfile? = kept.firstOrNull { it.id == id } ?:
            preferences.lensProfiles.firstOrNull { it.id == id }?.let { previous ->
                entries.indexOfFirst { matches(previous, it.profile) }.takeIf { it >= 0 }?.let(imported::get)
            }
        val default = startup ?: retained(preferences.defaultLensProfileId)
        val active = retained(preferences.activeLensProfileId) ?: startup ?: imported.firstOrNull { it.enabled } ?: kept.firstOrNull { it.enabled }
        return preferences.copy(lensProfiles = kept + imported,
            autoLensDiscoveryConfigured = preferences.autoLensDiscoveryConfigured || mode == LensProfileImportMode.REPLACE_ALL,
            defaultLensProfileId = default?.id,
            defaultLensId = default?.routeId ?: preferences.defaultLensId.takeIf { mode == LensProfileImportMode.KEEP_EXISTING },
            activeLensProfileId = active?.id)
    }

    fun initialize(cameras: List<CameraDescriptor>, preferences: CameraPreferences): CameraPreferences {
        if (preferences.autoLensDiscoveryConfigured) return normalize(preferences, cameras)
        if (cameras.none { it.isUsable }) return preferences
        val legacy = SensorProfileCatalog.initializeConfiguration(cameras, preferences)
        val profiles = legacy.lensProfiles.toMutableList()
        SensorProfileCatalog.resolve(cameras, legacy).forEach { configured ->
            val sensor = configured.profile
            if (profiles.none { it.sensorId == sensor.id.value }) {
                val camera = configured.selectedPath
                val raw = configured.selectedRegularRawOutput ?: SensorProfileCatalog.defaultRegularRawOutput(
                    camera.rawOutputs) ?: return@forEach
                val focal = camera.equivalentFocalLengthMm?.roundToInt()?.let { "${it}mm" } ?: camera.lensRole.label
                profiles += LensProfile(UUID.randomUUID().toString(), sensor.id.value, "$focal Default",
                    camera.route.routeId, RawStreamSelection.from(raw), configured.enabled, legacy.previewQuality,
                    previewShortEdge = legacy.previewShortEdge,
                    color = LensColorSettings(matrixEnabled = camera.sensorMetadata.colorCalibration.hasUsableColorTransform()))
            }
        }
        val active = legacy.activeLensProfileId ?: profiles.firstOrNull { it.routeId == legacy.defaultLensId && it.enabled }?.id
        return normalize(legacy.copy(lensProfiles = profiles, activeLensProfileId = active), cameras)
    }

    fun normalize(preferences: CameraPreferences, cameras: List<CameraDescriptor>): CameraPreferences {
        if (preferences.lensProfiles.isEmpty()) return preferences
        val groups = SensorProfileCatalog.group(cameras)
        val sensors = groups.associateBy { it.id.value }
        val byRoute = groups.flatMap { sensor -> sensor.routeIds.map { it to sensor } }.toMap()
        val profiles = preferences.lensProfiles.map { profile ->
            val sensor = byRoute[profile.routeId] ?: sensors[profile.sensorId] ?: return@map profile
            val regrouped = profile.copy(sensorId = sensor.id.value)
            if (profile.verification != null) return@map regrouped
            val camera = sensor.paths.firstOrNull { it.route.routeId == profile.routeId } ?: sensor.primary
            regrouped.copy(routeId = camera.route.routeId)
        }
        val available = visible(preferences.copy(lensProfiles = profiles), cameras)
        val startup = available.firstOrNull { it.id == preferences.defaultLensProfileId }
            ?: available.firstOrNull { preferences.defaultLensProfileId == null &&
                it.id == preferences.activeLensProfileId &&
                (preferences.defaultLensId == null || it.routeId == preferences.defaultLensId) }
            ?: available.firstOrNull { it.routeId == preferences.defaultLensId }
            ?: available.firstOrNull()
        val active = available.firstOrNull { it.id == preferences.activeLensProfileId } ?: startup
        val streams = (available + listOfNotNull(active)).associate { profile ->
            profile.routeId to profile.rawStream
        }
        return preferences.copy(lensProfiles = profiles, activeLensProfileId = active?.id,
            aeEngine = active?.aeEngine ?: preferences.aeEngine,
            enabledLensIds = available.map { it.routeId }.toSet(), lensSelectionConfigured = true,
            lensButtonRouteIds = available.map { it.routeId }.distinct(), lensButtonMappingConfigured = true,
            defaultLensProfileId = startup?.id, defaultLensId = startup?.routeId, rawPreviewStreams = streams)
    }

    fun startup(cameras: List<CameraDescriptor>, preferences: CameraPreferences): CameraPreferences {
        if (cameras.none { it.isUsable }) return preferences
        val initialized = initialize(cameras, preferences)
        return normalize(initialized.copy(activeLensProfileId = initialized.defaultLensProfileId), cameras)
    }

    fun effective(preferences: CameraPreferences): CameraPreferences {
        val active = preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId && it.enabled } ?: return preferences
        val stream = active.rawStream
        return preferences.copy(aeEngine = active.aeEngine, previewQuality = active.previewQuality,
            previewShortEdge = ViewfinderResolutions.selectedEdge(active.rawLayoutOverride.stream(stream), active.previewShortEdge, active.previewQuality),
            rawPreviewStreams = preferences.rawPreviewStreams + (active.routeId to stream))
    }

    fun duplicate(preferences: CameraPreferences, id: String): CameraPreferences {
        val original = preferences.lensProfiles.first { it.id == id }
        val names = preferences.lensProfiles.map { it.name }.toSet()
        var name = "${original.name} copy"
        var index = 2
        while (name in names) name = "${original.name} copy ${index++}"
        val copy = original.copy(id = UUID.randomUUID().toString(), name = name, verification = null, autoManaged = false)
        val profiles = preferences.lensProfiles.toMutableList()
        profiles.add(profiles.indexOf(original) + 1, copy)
        return preferences.copy(lensProfiles = profiles)
    }

    fun add(preferences: CameraPreferences, sensor: PhysicalSensorProfile): CameraPreferences {
        val camera = sensor.primary
        val raw = SensorProfileCatalog.defaultRegularRawOutput(camera.rawOutputs)
            ?: return preferences
        val name = camera.equivalentFocalLengthMm?.roundToInt()?.let { "${it}mm" } ?: camera.lensRole.label
        val profile = LensProfile(UUID.randomUUID().toString(), sensor.id.value, name, camera.route.routeId,
            RawStreamSelection.from(raw), color = LensColorSettings(matrixEnabled = camera.sensorMetadata.colorCalibration.hasUsableColorTransform()))
        return preferences.copy(lensProfiles = preferences.lensProfiles + profile)
    }

    fun canDelete(preferences: CameraPreferences, id: String): Boolean {
        val profile = preferences.lensProfiles.firstOrNull { it.id == id } ?: return false
        return preferences.lensProfiles.count { it.sensorId == profile.sensorId } > 1
    }

    fun delete(preferences: CameraPreferences, id: String): CameraPreferences {
        if (!canDelete(preferences, id)) return preferences
        val removed = preferences.lensProfiles.first { it.id == id }
        var remaining = preferences.lensProfiles.filterNot { it.id == id }
        val replacement = remaining.first { it.sensorId == removed.sensorId }
        if (preferences.activeLensProfileId == id || preferences.defaultLensProfileId == id) {
            remaining = remaining.map { if (it.id == replacement.id) it.copy(enabled = true) else it }
        }
        return preferences.copy(lensProfiles = remaining,
            defaultLensProfileId = if (preferences.defaultLensProfileId == id) replacement.id else preferences.defaultLensProfileId,
            defaultLensId = if (preferences.defaultLensProfileId == id) replacement.routeId else preferences.defaultLensId,
            activeLensProfileId = if (preferences.activeLensProfileId == id) replacement.id else preferences.activeLensProfileId)
    }

    fun encode(profiles: List<LensProfile>): String = JSONArray().apply {
        profiles.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("sensor", p.sensorId); put("name", p.name); put("route", p.routeId)
            put("enabled", p.enabled); put("quality", p.previewQuality.name)
            put("previewShortEdge", p.previewShortEdge ?: JSONObject.NULL)
            put("color", p.color.toJson())
            put("verification", p.verification?.toJson())
            put("autoManaged", p.autoManaged)
            put("aeLimits", p.aeLimits.toJson())
            put("aeEngine", p.aeEngine.name)
            put("rawEdgeCrop", p.rawEdgeCrop.toJson())
            put("rawLayoutOverride", p.rawLayoutOverride.toJson())
            put("cfaOverride", p.cfaOverride.toJson())
            put("capture", p.capture.toJson())
            put("focalLength", p.focalLength.toJson())
            put("photoOisEnabled", p.photoOisEnabled)
            put("aeBalance", AeBalance.normalize(p.aeBalance))
            put("vendorOverrides", p.vendorOverrides.toJson())
            put("rawSession", p.rawSession?.let(RawSessionSettings::encode))
            put("format", p.rawStream.format.name); put("width", p.rawStream.width); put("height", p.rawStream.height)
            put("maximum", p.rawStream.maximumResolutionMode)
            put("levels", JSONObject().apply {
                put("mode", p.rawLevels.mode.name); put("white", p.rawLevels.white)
                put("black", JSONArray(listOf(p.rawLevels.black00, p.rawLevels.black01, p.rawLevels.black10, p.rawLevels.black11)))
            })
        }) }
    }.toString()

    fun decode(json: String?, migrateNames: Boolean = true, defaultAeEngine: AeEngine = AeEngine.SPEKTRA): List<LensProfile> = runCatching {
        val array = JSONArray(json ?: return emptyList())
        (0 until array.length()).mapNotNull { index -> runCatching {
            val p = array.getJSONObject(index)
            val levels = p.optJSONObject("levels")
            val black = levels?.optJSONArray("black")
            val calibration = RawLevelCalibration(
                mode = runCatching { RawLevelMode.valueOf(levels?.optString("mode") ?: "DYNAMIC") }.getOrDefault(RawLevelMode.DYNAMIC),
                white = levels?.optInt("white", 16383) ?: 16383,
                black00 = black?.optDouble(0, 1024.0)?.toFloat() ?: 1024f,
                black01 = black?.optDouble(1, 1024.0)?.toFloat() ?: 1024f,
                black10 = black?.optDouble(2, 1024.0)?.toFloat() ?: 1024f,
                black11 = black?.optDouble(3, 1024.0)?.toFloat() ?: 1024f,
            )
            val profile = LensProfile(p.getString("id"), p.getString("sensor"), p.getString("name"), p.getString("route"),
                RawStreamSelection(RawFormat.valueOf(p.getString("format")), p.getInt("width"), p.getInt("height"), p.optBoolean("maximum")),
                p.optBoolean("enabled", true), RawPreviewQuality.valueOf(p.optString("quality", "LOW")), calibration,
                if (p.isNull("previewShortEdge")) null else p.getInt("previewShortEdge").coerceAtLeast(0),
                LensColorSettings.fromJson(p.optJSONObject("color")),
                p.optJSONObject("verification")?.let(LensVerification::fromJson))
            val uneditedAutomatic = profile.verification?.configuration?.isAutomaticName(profile.name) == true &&
                profile.previewQuality == RawPreviewQuality.LOW && profile.previewShortEdge == 480 &&
                profile.rawLevels == RawLevelCalibration() && profile.color == LensColorSettings()
            profile.copy(name = if (migrateNames) profile.displayName else profile.name, autoManaged = p.optBoolean("autoManaged", uneditedAutomatic),
                aeEngine = runCatching { AeEngine.valueOf(p.optString("aeEngine", defaultAeEngine.name)) }.getOrDefault(defaultAeEngine),
                rawEdgeCrop = RawEdgeCrop.fromJson(p.optJSONObject("rawEdgeCrop")),
                rawLayoutOverride = RawLayoutOverride.fromJson(p.optJSONObject("rawLayoutOverride")),
                cfaOverride = CfaOverride.fromJson(p.optJSONObject("cfaOverride")),
                aeLimits = aeLimitsFromJson(p.optJSONObject("aeLimits")),
                aeBalance = AeBalance.normalize(p.optDouble("aeBalance", 0.0).toFloat()),
                vendorOverrides = VendorOverrides.fromJson(p.optJSONObject("vendorOverrides")),
                capture = LensCaptureSettings.fromJson(p.optJSONObject("capture")),
                focalLength = LensFocalLengthSettings.fromJson(p.optJSONObject("focalLength")),
                photoOisEnabled = p.optBoolean("photoOisEnabled", true),
                rawSession = RawSessionSettings.decode(p.optJSONObject("rawSession")))
        }.getOrNull() }.filter { it.id.isNotBlank() && it.sensorId.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun available(profile: LensProfile, cameras: List<CameraDescriptor>): Boolean {
        val camera = cameras.firstOrNull { it.route.routeId == profile.routeId && it.isUsable } ?: return false
        val proof = profile.verification ?: return true
        return proof.verified && proof.configuration.raw == profile.rawStream &&
            proof.configuration.matchesRoute(camera)
    }

    fun resolveRawOutput(stream: RawStreamSelection, camera: CameraDescriptor,
        configuration: com.unspektrawesome.camera.session.RawSessionConfiguration? = null): RawOutput? =
        camera.rawOutputs.firstOrNull(stream::matches) ?: if (configuration?.allowUnadvertisedRaw == true &&
            !stream.maximumResolutionMode && stream.width in 2..65_536 && stream.height in 2..65_536 &&
            stream.width % (if (stream.format == RawFormat.RAW10) 4 else 2) == 0 && stream.height % 2 == 0 &&
            stream.width.toLong() * stream.height * 2 <= Int.MAX_VALUE)
            RawOutput(stream.format, com.unspektrawesome.camera.Size2d(stream.width, stream.height), 0, 0, false)
        else null

    fun visible(preferences: CameraPreferences, cameras: List<CameraDescriptor>): List<LensProfile> =
        preferences.lensProfiles.filter { it.enabled && available(it, cameras) }
}
