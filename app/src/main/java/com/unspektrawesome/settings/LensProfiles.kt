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

/** Capture mode a lens profile belongs to. Photo and video keep separate profile lists. */
enum class LensProfileMode {
    PHOTO, VIDEO;

    val other: LensProfileMode get() = if (this == PHOTO) VIDEO else PHOTO
}

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
    val mode: LensProfileMode = LensProfileMode.PHOTO,
    val videoStabilization: VideoStabilization = VideoStabilization(),
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

    /** Imports into the profile list of [target]; the other capture mode keeps its profiles. */
    fun importProfiles(preferences: CameraPreferences, entries: List<ImportedLensProfile>,
        mode: LensProfileImportMode = LensProfileImportMode.KEEP_EXISTING,
        target: LensProfileMode = preferences.lensProfileMode): CameraPreferences {
        require(entries.isNotEmpty()) { "Select at least one lens profile." }
        fun matches(existing: LensProfile, incoming: LensProfile) = existing.id == incoming.id ||
            existing.name.trim().equals(incoming.name.trim(), ignoreCase = true)
        val (existing, others) = preferences.lensProfiles.partition { it.mode == target }
        val kept = if (mode == LensProfileImportMode.KEEP_EXISTING) existing else emptyList()
        val names = kept.map { it.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
        val imported = entries.map { entry ->
            val original = entry.profile
            var name = original.name
            var suffix = 2
            while (!names.add(name.lowercase(java.util.Locale.ROOT))) name = "${original.name} (${suffix++})"
            original.copy(id = UUID.randomUUID().toString(), name = name, mode = target)
        }
        val startup = entries.indexOfFirst { it.startup }.takeIf { it >= 0 }?.let(imported::get)
        fun retained(id: String?): LensProfile? = kept.firstOrNull { it.id == id } ?:
            existing.firstOrNull { it.id == id }?.let { previous ->
                entries.indexOfFirst { matches(previous, it.profile) }.takeIf { it >= 0 }?.let(imported::get)
            }
        val default = startup ?: retained(preferences.defaultLensProfileId(target))
        val active = if (target != preferences.lensProfileMode) preferences.activeLensProfileId else
            (retained(preferences.activeLensProfileId) ?: startup ?: imported.firstOrNull { it.enabled } ?: kept.firstOrNull { it.enabled })?.id
        val merged = kept + imported
        return preferences.copy(lensProfiles = if (target == LensProfileMode.PHOTO) merged + others else others + merged,
            autoLensDiscoveryConfigured = preferences.autoLensDiscoveryConfigured || mode == LensProfileImportMode.REPLACE_ALL,
            activeLensProfileId = active).withDefault(target, default,
                preferences.defaultLensId.takeIf { mode == LensProfileImportMode.KEEP_EXISTING })
    }

    fun initialize(cameras: List<CameraDescriptor>, preferences: CameraPreferences): CameraPreferences {
        if (preferences.autoLensDiscoveryConfigured) return normalize(preferences, cameras)
        if (cameras.none { it.isUsable }) return preferences
        val legacy = SensorProfileCatalog.initializeConfiguration(cameras, preferences)
        val profiles = legacy.lensProfiles.toMutableList()
        SensorProfileCatalog.resolve(cameras, legacy).forEach { configured ->
            val sensor = configured.profile
            // Photo and video each start with a standard profile for every lens.
            LensProfileMode.entries.filter { mode -> profiles.none { it.sensorId == sensor.id.value && it.mode == mode } }.forEach { mode ->
                val camera = configured.selectedPath
                val raw = configured.selectedRegularRawOutput ?: SensorProfileCatalog.defaultRegularRawOutput(
                    camera.rawOutputs) ?: return@forEach
                val focal = camera.equivalentFocalLengthMm?.roundToInt()?.let { "${it}mm" } ?: camera.lensRole.label
                profiles += LensProfile(UUID.randomUUID().toString(), sensor.id.value, "$focal Default",
                    camera.route.routeId, RawStreamSelection.from(raw), configured.enabled, legacy.previewQuality,
                    previewShortEdge = legacy.previewShortEdge,
                    color = LensColorSettings(matrixEnabled = camera.sensorMetadata.colorCalibration.hasUsableColorTransform()),
                    mode = mode)
            }
        }
        val active = legacy.activeLensProfileId ?: profiles.firstOrNull {
            it.mode == legacy.lensProfileMode && it.routeId == legacy.defaultLensId && it.enabled
        }?.id
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
        val regrouped = preferences.copy(lensProfiles = profiles)
        val mode = preferences.lensProfileMode
        val available = visible(regrouped, cameras, mode)
        val photoAvailable = if (mode == LensProfileMode.PHOTO) available else visible(regrouped, cameras, LensProfileMode.PHOTO)
        val photo = photoAvailable.firstOrNull { it.id == preferences.defaultLensProfileId }
            ?: photoAvailable.firstOrNull { preferences.defaultLensProfileId == null &&
                it.id == preferences.activeLensProfileId &&
                (preferences.defaultLensId == null || it.routeId == preferences.defaultLensId) }
            ?: photoAvailable.firstOrNull { it.routeId == preferences.defaultLensId }
            ?: photoAvailable.firstOrNull()
        val videoAvailable = if (mode == LensProfileMode.VIDEO) available else visible(regrouped, cameras, LensProfileMode.VIDEO)
        // Without a saved choice, video starts on the lens that photo starts on.
        val video = videoAvailable.firstOrNull { it.id == preferences.defaultVideoLensProfileId }
            ?: sameLens(videoAvailable, photo ?: profiles.firstOrNull { it.id == preferences.defaultLensProfileId })
            ?: videoAvailable.firstOrNull()
        val startup = if (mode == LensProfileMode.VIDEO) video else photo
        val active = available.firstOrNull { it.id == preferences.activeLensProfileId } ?: startup
        val streams = (available + listOfNotNull(active)).associate { profile ->
            profile.routeId to profile.rawStream
        }
        // The capture mode that is not in use keeps its saved startup profile while none of its profiles can be shown.
        val keepPhoto = photo == null && mode != LensProfileMode.PHOTO
        val keepVideo = video == null && mode != LensProfileMode.VIDEO
        return regrouped.copy(activeLensProfileId = active?.id,
            aeEngine = active?.aeEngine ?: preferences.aeEngine,
            enabledLensIds = available.map { it.routeId }.toSet(), lensSelectionConfigured = true,
            lensButtonRouteIds = available.map { it.routeId }.distinct(), lensButtonMappingConfigured = true,
            defaultLensProfileId = if (keepPhoto) preferences.defaultLensProfileId else photo?.id,
            defaultLensId = if (keepPhoto) preferences.defaultLensId else photo?.routeId,
            defaultVideoLensProfileId = if (keepVideo) preferences.defaultVideoLensProfileId else video?.id,
            rawPreviewStreams = streams)
    }

    fun startup(cameras: List<CameraDescriptor>, preferences: CameraPreferences): CameraPreferences {
        if (cameras.none { it.isUsable }) return preferences
        val initialized = initialize(cameras, preferences)
        return normalize(initialized.copy(
            activeLensProfileId = initialized.defaultLensProfileId(initialized.lensProfileMode)), cameras)
    }

    /** Makes [id] the profile its capture mode starts with. */
    fun setDefault(preferences: CameraPreferences, id: String): CameraPreferences {
        val profile = preferences.lensProfiles.firstOrNull { it.id == id } ?: return preferences
        return preferences.withDefault(profile.mode, profile)
    }

    /**
     * Shows the profiles of [mode] and stays on the lens in use. The profile last used in that mode is preferred,
     * then the mode's startup profile. A lens without a profile in that mode falls back to the startup profile.
     */
    fun switchMode(preferences: CameraPreferences, mode: LensProfileMode, cameras: List<CameraDescriptor>): CameraPreferences {
        if (preferences.lensProfileMode == mode) return preferences
        val current = preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId }
        val available = visible(preferences, cameras, mode)
        val preferred = listOfNotNull(available.firstOrNull { it.id == preferences.idleLensProfileId },
            available.firstOrNull { it.id == preferences.defaultLensProfileId(mode) }) + available
        val next = sameLens(preferred, current) ?: available.firstOrNull { it.id == preferences.defaultLensProfileId(mode) }
        return normalize(preferences.copy(lensProfileMode = mode, activeLensProfileId = next?.id,
            idleLensProfileId = current?.id), cameras)
    }

    /**
     * Gives a capture mode without any profile a copy of the other mode's profiles, so both start with the same lenses.
     * Returns the updated preferences and the id of each copy by the id of its original.
     */
    fun fillModes(preferences: CameraPreferences): Pair<CameraPreferences, Map<String, String>> {
        val profiles = preferences.lensProfiles
        val empty = LensProfileMode.entries.singleOrNull { mode -> profiles.none { it.mode == mode } }
        if (profiles.isEmpty() || empty == null) return preferences to emptyMap()
        val copies = profiles.map { it.copy(id = UUID.randomUUID().toString(), mode = empty) }
        val ids = profiles.map { it.id }.zip(copies.map { it.id }).toMap()
        val default = copies.firstOrNull { it.id == ids[preferences.defaultLensProfileId(empty.other)] }
        return preferences.copy(lensProfiles = profiles + copies).withDefault(empty, default, preferences.defaultLensId) to ids
    }

    private fun sameLens(profiles: List<LensProfile>, lens: LensProfile?): LensProfile? = lens?.let {
        profiles.firstOrNull { it.focalGroup == lens.focalGroup } ?: profiles.firstOrNull { it.sensorId == lens.sensorId }
    }

    private fun CameraPreferences.withDefault(mode: LensProfileMode, profile: LensProfile?,
        fallbackRouteId: String? = null): CameraPreferences =
        if (mode == LensProfileMode.VIDEO) copy(defaultVideoLensProfileId = profile?.id)
        else copy(defaultLensProfileId = profile?.id, defaultLensId = profile?.routeId ?: fallbackRouteId)

    fun effective(preferences: CameraPreferences): CameraPreferences {
        val active = preferences.lensProfiles.firstOrNull { it.id == preferences.activeLensProfileId && it.enabled } ?: return preferences
        val stream = active.rawStream
        return preferences.copy(aeEngine = active.aeEngine, previewQuality = active.previewQuality,
            previewShortEdge = ViewfinderResolutions.selectedEdge(active.rawLayoutOverride.stream(stream), active.previewShortEdge, active.previewQuality),
            rawPreviewStreams = preferences.rawPreviewStreams + (active.routeId to stream))
    }

    fun duplicate(preferences: CameraPreferences, id: String): CameraPreferences {
        val original = preferences.lensProfiles.first { it.id == id }
        val names = preferences.lensProfiles.filter { it.mode == original.mode }.map { it.name }.toSet()
        var name = "${original.name} copy"
        var index = 2
        while (name in names) name = "${original.name} copy ${index++}"
        val copy = original.copy(id = UUID.randomUUID().toString(), name = name, verification = null, autoManaged = false)
        val profiles = preferences.lensProfiles.toMutableList()
        profiles.add(profiles.indexOf(original) + 1, copy)
        return preferences.copy(lensProfiles = profiles)
    }

    fun add(preferences: CameraPreferences, sensor: PhysicalSensorProfile,
        mode: LensProfileMode = preferences.lensProfileMode): CameraPreferences {
        val camera = sensor.primary
        val raw = SensorProfileCatalog.defaultRegularRawOutput(camera.rawOutputs)
            ?: return preferences
        val name = camera.equivalentFocalLengthMm?.roundToInt()?.let { "${it}mm" } ?: camera.lensRole.label
        val profile = LensProfile(UUID.randomUUID().toString(), sensor.id.value, name, camera.route.routeId,
            RawStreamSelection.from(raw), color = LensColorSettings(matrixEnabled = camera.sensorMetadata.colorCalibration.hasUsableColorTransform()),
            mode = mode)
        return preferences.copy(lensProfiles = preferences.lensProfiles + profile)
    }

    /** Every lens keeps at least one profile in each capture mode. */
    fun canDelete(preferences: CameraPreferences, id: String): Boolean {
        val profile = preferences.lensProfiles.firstOrNull { it.id == id } ?: return false
        return preferences.lensProfiles.count { it.sensorId == profile.sensorId && it.mode == profile.mode } > 1
    }

    fun delete(preferences: CameraPreferences, id: String): CameraPreferences {
        if (!canDelete(preferences, id)) return preferences
        val removed = preferences.lensProfiles.first { it.id == id }
        var remaining = preferences.lensProfiles.filterNot { it.id == id }
        val replacement = remaining.first { it.sensorId == removed.sensorId && it.mode == removed.mode }
        val default = preferences.defaultLensProfileId(removed.mode) == id
        if (preferences.activeLensProfileId == id || default) {
            remaining = remaining.map { if (it.id == replacement.id) it.copy(enabled = true) else it }
        }
        val updated = preferences.copy(lensProfiles = remaining,
            activeLensProfileId = if (preferences.activeLensProfileId == id) replacement.id else preferences.activeLensProfileId)
        return if (default) updated.withDefault(removed.mode, replacement) else updated
    }

    fun encode(profiles: List<LensProfile>): String = JSONArray().apply {
        profiles.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("sensor", p.sensorId); put("name", p.name); put("route", p.routeId)
            put("mode", p.mode.name)
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
            put("videoStabilization", p.videoStabilization.toJson())
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
                videoStabilization = VideoStabilization.fromJson(p.optJSONObject("videoStabilization")),
                rawSession = RawSessionSettings.decode(p.optJSONObject("rawSession")),
                mode = LensProfileMode.entries.firstOrNull { it.name == p.optString("mode") } ?: LensProfileMode.PHOTO)
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

    /** Profiles the lens switcher offers in [mode], by default the capture mode in use. */
    fun visible(preferences: CameraPreferences, cameras: List<CameraDescriptor>,
        mode: LensProfileMode = preferences.lensProfileMode): List<LensProfile> =
        preferences.lensProfiles.filter { it.mode == mode && it.enabled && available(it, cameras) }
}
