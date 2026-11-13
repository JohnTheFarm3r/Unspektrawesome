package com.unspektrawesome.settings

import android.content.Context
import android.view.KeyEvent
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.exposure.AeMeter
import com.unspektrawesome.camera.RawFormat
import com.unspektrawesome.camera.FlashMode
import com.unspektrawesome.camera.RawOutput
import org.json.JSONArray
import org.json.JSONObject
import com.unspektrawesome.capture.PhotoCrop
import com.unspektrawesome.capture.CropRatio
import com.unspektrawesome.capture.SavedPhotoCrop
import com.unspektrawesome.video.landscapeVideoCrop

enum class CompositionGrid(val label: String) {
    OFF("Off"), THIRDS("Thirds"), FINE("Fine"), GOLDEN("Golden ratio"),
    DIAGONALS("Diagonals"), INTERSECTIONS("Intersections"),
}

enum class HistogramSize(val label: String, val width: Int, val height: Int) {
    SMALL("Small", 112, 68), MEDIUM("Medium", 160, 92), LARGE("Large", 208, 116);

    fun next(): HistogramSize = entries[(ordinal + 1) % entries.size]
}

enum class FocusPeakingStrength(val label: String, val shaderValue: Float) {
    LOW("Low", 1f), MEDIUM("Medium", 2f), HIGH("High", 3f),
}

/** How focusing is assisted: edge highlights over the whole viewfinder, or a magnified RAW loupe. */
/** How the microphone track is stored in recorded videos. */
enum class VideoAudioFormat(val label: String) { AAC("AAC"), PCM("PCM") }

enum class FocusAssist(val label: String) { PEAKING("Peaking"), LOUPE("Loupe") }

/** Loupe magnification as screen pixels per RAW pixel. */
enum class LoupeZoom(val label: String, val screenPixelsPerRawPixel: Float) {
    ACTUAL("100%", 1f), DOUBLE("200%", 2f), QUADRUPLE("400%", 4f);

    fun next(): LoupeZoom = entries[(ordinal + 1) % entries.size]
}

/** Loupe window centre placed by the photographer, normalized to the viewfinder. */
data class LoupePlacement(val x: Float, val y: Float) {
    val valid: Boolean get() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
}

enum class PreviewSlider { EXPOSURE, AE_BALANCE, SHADOWS, HIGHLIGHTS }

enum class RawPreviewQuality(
    val label: String,
    val scale: Float,
) {
    LOW("Low", 0.25f),
    MEDIUM("Medium", 0.5f),
    HIGH("High", 0.75f),
    FULL("Full", 1f),
}

/** Camera stream that feeds the photo-mode viewfinder. Photos and video always use RAW. */
enum class ViewfinderSource(val label: String) {
    RAW("RAW sensor"),
    ISP("Camera ISP"),
}

/** How on-screen text and buttons turn. Photos and videos always follow how the phone is held. */
enum class UiOrientation(val label: String, val detail: String) {
    AUTOMATIC("Automatic", "Text and buttons turn with the phone, even when the system rotation lock is on."),
    FOLLOW_SYSTEM("Follow system", "Text and buttons turn with the phone only while system auto-rotate is on."),
    PORTRAIT("Portrait", "Text and buttons always stay in portrait."),
    LANDSCAPE("Landscape", "Text and buttons always stay in landscape."),
}

const val DEFAULT_CONTROL_OPACITY = .8f

private fun normalizeControlOpacity(value: Float): Float =
    if (value == .806f || !value.isFinite()) DEFAULT_CONTROL_OPACITY else value.coerceIn(0f, 1f)

data class CameraPreferences(
    val aeEngine: AeEngine = AeEngine.SPEKTRA,
    val aeMeter: AeMeter = AeMeter.MEDIAN,
    val aeSwitchOverlay: Boolean = false,
    val photoCrop: PhotoCrop = PhotoCrop(),
    val videoCrop: PhotoCrop = PhotoCrop(CropRatio.SIXTEEN_NINE),
    val videoPixelCrop: com.unspektrawesome.video.VideoPixelCrop? = null,
    val savedPhotoCrops: List<SavedPhotoCrop> = emptyList(),
    val selectedPhotoCropId: String? = null,
    val selectedVideoCropId: String? = null,
    val compositionGrid: CompositionGrid = CompositionGrid.OFF,
    val horizonEnabled: Boolean = true,
    val flashMode: FlashMode = FlashMode.OFF,
    val saveLocation: Boolean = false,
    val shutterTimerSeconds: Int = 0,
    val passiveFocusIntervalSeconds: Int = 0,
    val modeSwipeEnabled: Boolean = true,
    val uiOrientation: UiOrientation = UiOrientation.AUTOMATIC,
    val maxBrightness: Boolean = false,
    val extendTallCropPreview: Boolean = true,
    val controlOpacity: Float = DEFAULT_CONTROL_OPACITY,
    val expandedPreviewSliders: Set<PreviewSlider> = emptySet(),
    val previewQuality: RawPreviewQuality = RawPreviewQuality.LOW,
    val previewShortEdge: Int? = 480,
    val viewfinderSource: ViewfinderSource = ViewfinderSource.RAW,
    val performanceOverlay: Boolean = false,
    val recordingBufferMb: Int = 512,
    val videoAudioFormat: VideoAudioFormat = VideoAudioFormat.AAC,
    /** What holding the shutter in video mode locks for the recording it starts. */
    val recordingLock: com.unspektrawesome.video.RecordingLock = com.unspektrawesome.video.RecordingLock(),
    val burstPreShutterSeconds: Int = 1,
    val histogramEnabled: Boolean = false,
    val histogramSize: HistogramSize = HistogramSize.SMALL,
    val exposureZebraEnabled: Boolean = false,
    val previewHighlightRecovery: Boolean = false,
    val focusPeakingStrength: FocusPeakingStrength = FocusPeakingStrength.MEDIUM,
    val focusPeakingEnabled: Boolean = true,
    val autofocusPeakingEnabled: Boolean = false,
    val autofocusPeakingStrength: FocusPeakingStrength = FocusPeakingStrength.MEDIUM,
    val focusAssist: FocusAssist = FocusAssist.LOUPE,
    val loupeZoom: LoupeZoom = LoupeZoom.DOUBLE,
    val loupeManualFocus: Boolean = true,
    val loupeOnTap: Boolean = true,
    val loupePeaking: Boolean = true,
    /** Where the photographer moved the loupe, or null to place it automatically. */
    val loupePlacement: LoupePlacement? = null,
    val processPhotosLater: Boolean = false,
    val saveOriginalDng: Boolean = true,
    val volumeButtonsEnabled: Boolean = true,
    val volumeUpAction: VolumeButtonAction = VolumeButtonAction.SHUTTER,
    val volumeDownAction: VolumeButtonAction = VolumeButtonAction.SYSTEM_VOLUME,
    val enabledLensIds: Set<String> = emptySet(),
    val lensSelectionConfigured: Boolean = false,
    val defaultLensId: String? = null,
    val lensButtonRouteIds: List<String> = emptyList(),
    val lensButtonMappingConfigured: Boolean = false,
    val rawPreviewStreams: Map<String, RawStreamSelection> = emptyMap(),
    val lensProfiles: List<LensProfile> = emptyList(),
    val activeLensProfileId: String? = null,
    val defaultLensProfileId: String? = null,
    val autoLensDiscoveryConfigured: Boolean = false,
    /** Capture mode whose lens profiles the viewfinder offers. */
    val lensProfileMode: LensProfileMode = LensProfileMode.PHOTO,
    val defaultVideoLensProfileId: String? = null,
    /** Profile last used in the capture mode that is not in use. */
    val idleLensProfileId: String? = null,
) {
    /** Profile that [mode] starts with. */
    fun defaultLensProfileId(mode: LensProfileMode): String? =
        if (mode == LensProfileMode.VIDEO) defaultVideoLensProfileId else defaultLensProfileId

    /** Lens profiles of [mode] in their saved order. */
    fun lensProfiles(mode: LensProfileMode): List<LensProfile> = lensProfiles.filter { it.mode == mode }

    /** Viewfinder peaking; the loupe replaces it when selected. */
    fun peakingStrengthFor(autoFocus: Boolean): FocusPeakingStrength? = when {
        focusAssist == FocusAssist.LOUPE -> null
        autoFocus && autofocusPeakingEnabled -> autofocusPeakingStrength
        !autoFocus && focusPeakingEnabled -> focusPeakingStrength
        else -> null
    }

    fun volumeActionFor(keyCode: Int): VolumeButtonAction = when {
        !volumeButtonsEnabled -> VolumeButtonAction.SYSTEM_VOLUME
        keyCode == KeyEvent.KEYCODE_VOLUME_UP -> volumeUpAction
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownAction
        else -> VolumeButtonAction.SYSTEM_VOLUME
    }
}

data class RawStreamSelection(
    val format: RawFormat,
    val width: Int,
    val height: Int,
    val maximumResolutionMode: Boolean = false,
) {
    init {
        require(width > 0 && height > 0) { "RAW raster dimensions must be positive" }
    }

    fun matches(output: RawOutput): Boolean = format == output.format &&
        width == output.size.width && height == output.size.height &&
        maximumResolutionMode == output.maximumResolutionMode

    companion object {
        fun from(output: RawOutput) = RawStreamSelection(
            output.format,
            output.size.width,
            output.size.height,
            output.maximumResolutionMode,
        )
    }
}

class CameraPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): CameraPreferences {
        if (preferences.all.isEmpty()) return CameraPreferences()
        val previewQuality = runCatching {
            RawPreviewQuality.valueOf(
                preferences.getString(KEY_PREVIEW_QUALITY, null) ?: RawPreviewQuality.LOW.name,
            )
        }.getOrDefault(RawPreviewQuality.LOW)
        val viewfinderSource = runCatching {
            ViewfinderSource.valueOf(preferences.getString(KEY_VIEWFINDER_SOURCE, null) ?: ViewfinderSource.RAW.name)
        }.getOrDefault(ViewfinderSource.RAW)
        val photoCrop = PhotoCrop.fromJson(runCatching { JSONObject(preferences.getString("photo_crop", "{}")!!) }.getOrNull())
        val videoCrop = preferences.getString("video_crop", null)?.let {
            PhotoCrop.fromJson(runCatching { JSONObject(it) }.getOrNull()).landscapeVideoCrop()
        } ?: PhotoCrop(CropRatio.SIXTEEN_NINE)
        val savedCrops = if (!preferences.contains("saved_photo_crops") && photoCrop.ratio == CropRatio.CUSTOM)
            listOf(SavedPhotoCrop("previous-custom", "", photoCrop))
        else SavedPhotoCrop.decode(preferences.getString("saved_photo_crops", null))
        val selectedCropId = preferences.getString("selected_photo_crop", null)?.takeIf { id ->
            savedCrops.any { it.id == id && it.crop == photoCrop }
        } ?: savedCrops.firstOrNull { it.crop == photoCrop }?.id
        val legacyAeEngine = runCatching {
            AeEngine.valueOf(preferences.getString("ae_engine", AeEngine.SPEKTRA.name)!!)
        }.getOrDefault(AeEngine.SPEKTRA)
        val value = CameraPreferences(
            aeEngine = legacyAeEngine,
            aeMeter = runCatching { AeMeter.valueOf(preferences.getString("ae_meter", AeMeter.MEDIAN.name)!!) }.getOrDefault(AeMeter.MEDIAN),
            aeSwitchOverlay = preferences.getBoolean("ae_switch_overlay", false),
            autoLensDiscoveryConfigured = preferences.getBoolean("auto_lens_discovery_configured", false),
            photoCrop = photoCrop,
            videoCrop = videoCrop,
            videoPixelCrop = preferences.getString("video_pixel_crop", null)?.let { name ->
                com.unspektrawesome.video.VideoPixelCrop.entries.firstOrNull { it.name == name }
            },
            savedPhotoCrops = savedCrops,
            selectedPhotoCropId = selectedCropId,
            selectedVideoCropId = preferences.getString("selected_video_crop", null)?.takeIf { id ->
                savedCrops.any { it.id == id && it.crop == videoCrop }
            },
            compositionGrid = runCatching { CompositionGrid.valueOf(preferences.getString("composition_grid", "OFF")!!) }.getOrDefault(CompositionGrid.OFF),
            horizonEnabled = preferences.getBoolean("horizon_enabled", true),
            flashMode = runCatching { FlashMode.valueOf(preferences.getString("flash_mode", "OFF")!!) }.getOrDefault(FlashMode.OFF),
            saveLocation = preferences.getBoolean("save_location", false),
            shutterTimerSeconds = preferences.getInt("shutter_timer_seconds", 0).coerceIn(0, 30),
            passiveFocusIntervalSeconds = preferences.getInt("passive_focus_interval_seconds", 0).coerceIn(0, 60),
            modeSwipeEnabled = preferences.getBoolean("mode_swipe_enabled", true),
            uiOrientation = UiOrientation.entries.firstOrNull {
                it.name == preferences.getString("ui_orientation", null)
            } ?: UiOrientation.AUTOMATIC,
            maxBrightness = preferences.getBoolean("max_brightness", false),
            extendTallCropPreview = preferences.getBoolean("extend_tall_crop_preview", true),
            controlOpacity = normalizeControlOpacity(preferences.getFloat("control_opacity", DEFAULT_CONTROL_OPACITY)),
            expandedPreviewSliders = preferences.getStringSet("expanded_preview_sliders", emptySet()).orEmpty()
                .mapNotNull { name -> PreviewSlider.entries.firstOrNull { it.name == name } }.toSet(),
            previewQuality = previewQuality,
            viewfinderSource = viewfinderSource,
            processPhotosLater = preferences.getBoolean("process_photos_later", false),
            saveOriginalDng = preferences.getBoolean("save_original_dng", true),
            volumeButtonsEnabled = preferences.getBoolean(KEY_VOLUME_SHUTTER_ENABLED, true),
            volumeUpAction = loadVolumeAction(KEY_VOLUME_UP_ACTION, up = true),
            volumeDownAction = loadVolumeAction(KEY_VOLUME_DOWN_ACTION, up = false),
            performanceOverlay = preferences.getBoolean("performance_overlay", false),
            recordingBufferMb = preferences.getInt("recording_buffer_mb", 512).coerceIn(512, 4096),
            videoAudioFormat = VideoAudioFormat.entries.firstOrNull {
                it.name == preferences.getString("video_audio_format", null)
            } ?: VideoAudioFormat.AAC,
            recordingLock = com.unspektrawesome.video.RecordingLock(
                shutter = preferences.getBoolean("recording_lock_shutter", true),
                iso = preferences.getBoolean("recording_lock_iso", true),
                focus = preferences.getBoolean("recording_lock_focus", true),
                whiteBalance = preferences.getBoolean("recording_lock_white_balance", true)),
            burstPreShutterSeconds = preferences.getInt("burst_pre_shutter_seconds", 1).coerceIn(1, 5),
            histogramEnabled = preferences.getBoolean("histogram_enabled", false),
            exposureZebraEnabled = preferences.getBoolean("exposure_zebra_enabled", false),
            previewHighlightRecovery = preferences.getBoolean("preview_highlight_recovery", false),
            histogramSize = runCatching {
                HistogramSize.valueOf(preferences.getString("histogram_size", "SMALL")!!)
            }.getOrDefault(HistogramSize.SMALL),
            focusPeakingStrength = runCatching {
                FocusPeakingStrength.valueOf(preferences.getString("focus_peaking_strength", "MEDIUM")!!)
            }.getOrDefault(FocusPeakingStrength.MEDIUM),
            focusPeakingEnabled = preferences.getBoolean("focus_peaking_enabled", true),
            autofocusPeakingEnabled = preferences.getBoolean("autofocus_peaking_enabled", false),
            autofocusPeakingStrength = runCatching {
                FocusPeakingStrength.valueOf(preferences.getString("autofocus_peaking_strength", "MEDIUM")!!)
            }.getOrDefault(FocusPeakingStrength.MEDIUM),
            focusAssist = runCatching { FocusAssist.valueOf(preferences.getString("focus_assist", FocusAssist.LOUPE.name)!!) }
                .getOrDefault(FocusAssist.LOUPE),
            loupeZoom = runCatching { LoupeZoom.valueOf(preferences.getString("loupe_zoom", LoupeZoom.DOUBLE.name)!!) }
                .getOrDefault(LoupeZoom.DOUBLE),
            loupeManualFocus = preferences.getBoolean("loupe_manual_focus", true),
            loupeOnTap = preferences.getBoolean("loupe_on_tap", true),
            loupePeaking = preferences.getBoolean("loupe_peaking", true),
            loupePlacement = if (preferences.contains("loupe_x") && preferences.contains("loupe_y"))
                LoupePlacement(preferences.getFloat("loupe_x", .5f), preferences.getFloat("loupe_y", .5f)).takeIf { it.valid }
            else null,
            previewShortEdge = if (preferences.contains("preview_short_edge")) preferences.getInt("preview_short_edge", 480).coerceAtLeast(0) else null,
            lensProfiles = LensProfiles.decode(preferences.getString("lens_profiles", null), defaultAeEngine = legacyAeEngine),
            activeLensProfileId = preferences.getString("active_lens_profile", null),
            defaultLensProfileId = preferences.getString("default_lens_profile", null),
            lensProfileMode = LensProfileMode.entries.firstOrNull {
                it.name == preferences.getString("lens_profile_mode", null)
            } ?: LensProfileMode.PHOTO,
            defaultVideoLensProfileId = preferences.getString("default_video_lens_profile", null),
            idleLensProfileId = preferences.getString("idle_lens_profile", null),
            enabledLensIds = preferences.getStringSet(KEY_ENABLED_LENSES, emptySet()).orEmpty().toSet(),
            lensSelectionConfigured = preferences.getBoolean(KEY_LENS_SELECTION_CONFIGURED, false),
            defaultLensId = preferences.getString(KEY_DEFAULT_LENS, null),
            lensButtonRouteIds = decodeLensButtons(
                preferences.getString(KEY_LENS_BUTTON_ROUTES, null),
            ),
            lensButtonMappingConfigured = preferences.getBoolean(
                KEY_LENS_BUTTON_MAPPING_CONFIGURED,
                false,
            ),
            rawPreviewStreams = decodeRawStreams(
                preferences.getString(KEY_RAW_PREVIEW_STREAMS, null),
            ),
        )
        val resolved = value.copy(aeEngine = value.selectedAeEngine())
        if (preferences.getBoolean(KEY_MEDIAN_AE_DEFAULT_APPLIED, false)) return resolved
        val migrated = if (resolved.aeEngine == AeEngine.SPEKTRA && resolved.aeMeter == AeMeter.CENTER_WEIGHTED)
            resolved.copy(aeMeter = AeMeter.MEDIAN) else resolved
        preferences.edit()
            .putString("ae_meter", migrated.aeMeter.name)
            .putBoolean(KEY_MEDIAN_AE_DEFAULT_APPLIED, true)
            .apply()
        return migrated
    }

    fun save(value: CameraPreferences) {
        preferences.edit().apply {
            if (value.previewShortEdge == null) remove("preview_short_edge") else putInt("preview_short_edge", value.previewShortEdge)
        }.apply()
        preferences.edit()
            .putString("ae_engine", value.selectedAeEngine().name)
            .putBoolean("ae_switch_overlay", value.aeSwitchOverlay)
            .putString("ae_meter", value.aeMeter.name)
            .putBoolean(KEY_MEDIAN_AE_DEFAULT_APPLIED, true)
            .putBoolean("auto_lens_discovery_configured", value.autoLensDiscoveryConfigured)
            .putString("photo_crop", value.photoCrop.toJson().toString())
            .putString("video_crop", value.videoCrop.toJson().toString())
            .putString("video_pixel_crop", value.videoPixelCrop?.name)
            .putString("saved_photo_crops", SavedPhotoCrop.encode(value.savedPhotoCrops))
            .putString("selected_photo_crop", value.selectedPhotoCropId)
            .putString("selected_video_crop", value.selectedVideoCropId)
            .putString("composition_grid", value.compositionGrid.name)
            .putBoolean("horizon_enabled", value.horizonEnabled)
            .putString("flash_mode", value.flashMode.name)
            .putBoolean("save_location", value.saveLocation)
            .putInt("shutter_timer_seconds", value.shutterTimerSeconds.coerceIn(0, 30))
            .putInt("passive_focus_interval_seconds", value.passiveFocusIntervalSeconds.coerceIn(0, 60))
            .putBoolean("mode_swipe_enabled", value.modeSwipeEnabled)
            .putString("ui_orientation", value.uiOrientation.name)
            .putBoolean("max_brightness", value.maxBrightness)
            .putBoolean("extend_tall_crop_preview", value.extendTallCropPreview)
            .putFloat("control_opacity", normalizeControlOpacity(value.controlOpacity))
            .putStringSet("expanded_preview_sliders", value.expandedPreviewSliders.map { it.name }.toSet())
            .putString(KEY_PREVIEW_QUALITY, value.previewQuality.name)
            .putString(KEY_VIEWFINDER_SOURCE, value.viewfinderSource.name)
            .remove("viewfinder_fps")
            .putBoolean("performance_overlay", value.performanceOverlay)
            .putInt("recording_buffer_mb", value.recordingBufferMb.coerceIn(512, 4096))
            .putString("video_audio_format", value.videoAudioFormat.name)
            .putBoolean("recording_lock_shutter", value.recordingLock.shutter)
            .putBoolean("recording_lock_iso", value.recordingLock.iso)
            .putBoolean("recording_lock_focus", value.recordingLock.focus)
            .putBoolean("recording_lock_white_balance", value.recordingLock.whiteBalance)
            .putInt("burst_pre_shutter_seconds", value.burstPreShutterSeconds.coerceIn(1, 5))
            .putBoolean("histogram_enabled", value.histogramEnabled)
            .putBoolean("exposure_zebra_enabled", value.exposureZebraEnabled)
            .putBoolean("preview_highlight_recovery", value.previewHighlightRecovery)
            .putString("histogram_size", value.histogramSize.name)
            .putString("focus_peaking_strength", value.focusPeakingStrength.name)
            .putBoolean("focus_peaking_enabled", value.focusPeakingEnabled)
            .putBoolean("autofocus_peaking_enabled", value.autofocusPeakingEnabled)
            .putString("autofocus_peaking_strength", value.autofocusPeakingStrength.name)
            .putString("focus_assist", value.focusAssist.name)
            .putString("loupe_zoom", value.loupeZoom.name)
            .putBoolean("loupe_manual_focus", value.loupeManualFocus)
            .putBoolean("loupe_on_tap", value.loupeOnTap)
            .putBoolean("loupe_peaking", value.loupePeaking)
            .also { editor ->
                val placement = value.loupePlacement?.takeIf { it.valid }
                if (placement != null) editor.putFloat("loupe_x", placement.x).putFloat("loupe_y", placement.y)
                else editor.remove("loupe_x").remove("loupe_y")
            }
            .putBoolean("process_photos_later", value.processPhotosLater)
            .putBoolean("save_original_dng", value.saveOriginalDng)
            .putBoolean(KEY_VOLUME_SHUTTER_ENABLED, value.volumeButtonsEnabled)
            .putString(KEY_VOLUME_UP_ACTION, value.volumeUpAction.name)
            .putString(KEY_VOLUME_DOWN_ACTION, value.volumeDownAction.name)
            .putString("lens_profiles", LensProfiles.encode(value.lensProfiles))
            .putString("active_lens_profile", value.activeLensProfileId)
            .putString("default_lens_profile", value.defaultLensProfileId)
            .putString("lens_profile_mode", value.lensProfileMode.name)
            .putString("default_video_lens_profile", value.defaultVideoLensProfileId)
            .putString("idle_lens_profile", value.idleLensProfileId)
            .putStringSet(KEY_ENABLED_LENSES, value.enabledLensIds)
            .putBoolean(KEY_LENS_SELECTION_CONFIGURED, value.lensSelectionConfigured)
            .putString(KEY_DEFAULT_LENS, value.defaultLensId)
            .putString(KEY_LENS_BUTTON_ROUTES, encodeLensButtons(value.lensButtonRouteIds))
            .putBoolean(KEY_LENS_BUTTON_MAPPING_CONFIGURED, value.lensButtonMappingConfigured)
            .putString(KEY_RAW_PREVIEW_STREAMS, encodeRawStreams(value.rawPreviewStreams))
            .apply()
    }

    private fun loadVolumeAction(key: String, up: Boolean): VolumeButtonAction {
        val saved = preferences.getString(key, null)
        VolumeButtonAction.entries.firstOrNull { it.name == saved }?.let { return it }
        return when (preferences.getString("volume_button_action", null)) {
            "DEVICE_EV" -> if (up) VolumeButtonAction.DEVICE_EV_UP else VolumeButtonAction.DEVICE_EV_DOWN
            "SPEKTRA_AE" -> if (up) VolumeButtonAction.SPEKTRA_AE_NEXT else VolumeButtonAction.SPEKTRA_AE_PREVIOUS
            else -> {
                val shutterEnabled = when (preferences.getString(KEY_VOLUME_SHUTTER_BUTTON, null)) {
                    "BOTH" -> true
                    "DOWN" -> !up
                    else -> up
                }
                if (shutterEnabled) VolumeButtonAction.SHUTTER else VolumeButtonAction.SYSTEM_VOLUME
            }
        }
    }

    private fun encodeLensButtons(routeIds: List<String>): String = JSONArray().apply {
        routeIds.forEach(::put)
    }.toString()

    private fun decodeLensButtons(value: String?): List<String> = runCatching {
        val array = JSONArray(value ?: return emptyList())
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeRawStreams(streams: Map<String, RawStreamSelection>): String =
        JSONObject().apply {
            streams.forEach { (routeId, selection) ->
                put(routeId, JSONObject().apply {
                    put(JSON_FORMAT, selection.format.name)
                    put(JSON_WIDTH, selection.width)
                    put(JSON_HEIGHT, selection.height)
                    put(JSON_MAXIMUM_MODE, selection.maximumResolutionMode)
                })
            }
        }.toString()

    private fun decodeRawStreams(value: String?): Map<String, RawStreamSelection> = runCatching {
        val root = JSONObject(value ?: return emptyMap())
        buildMap {
            root.keys().forEach { routeId ->
                val stream = root.optJSONObject(routeId) ?: return@forEach
                val format = runCatching {
                    RawFormat.valueOf(stream.getString(JSON_FORMAT))
                }.getOrNull() ?: return@forEach
                val width = stream.optInt(JSON_WIDTH)
                val height = stream.optInt(JSON_HEIGHT)
                if (routeId.isNotBlank() && width > 0 && height > 0) {
                    put(
                        routeId,
                        RawStreamSelection(
                            format,
                            width,
                            height,
                            stream.optBoolean(JSON_MAXIMUM_MODE, false),
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val FILE_NAME = "camera_preferences"
        const val KEY_MEDIAN_AE_DEFAULT_APPLIED = "median_ae_default_applied"
        const val KEY_PREVIEW_QUALITY = "raw_preview_quality"
        const val KEY_VIEWFINDER_SOURCE = "viewfinder_source"
        const val KEY_VOLUME_SHUTTER_ENABLED = "volume_shutter_enabled"
        const val KEY_VOLUME_SHUTTER_BUTTON = "volume_shutter_button"
        const val KEY_VOLUME_UP_ACTION = "volume_up_action"
        const val KEY_VOLUME_DOWN_ACTION = "volume_down_action"
        const val KEY_ENABLED_LENSES = "enabled_lens_ids"
        const val KEY_LENS_SELECTION_CONFIGURED = "lens_selection_configured"
        const val KEY_DEFAULT_LENS = "default_lens_id"
        const val KEY_LENS_BUTTON_ROUTES = "lens_button_route_ids"
        const val KEY_LENS_BUTTON_MAPPING_CONFIGURED = "lens_button_mapping_configured"
        const val KEY_RAW_PREVIEW_STREAMS = "raw_preview_streams"
        const val JSON_FORMAT = "format"
        const val JSON_WIDTH = "width"
        const val JSON_HEIGHT = "height"
        const val JSON_MAXIMUM_MODE = "maximumResolutionMode"
    }
}
