package com.unspektrawesome.settings

import android.content.Context
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.calibration.DcpParser
import com.unspektrawesome.io.readUpTo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The parts of a full backup that can be restored separately. */
enum class BackupPart { SETTINGS, LENS_PROFILES }

/** A validated full backup, ready to restore. */
class SettingsBackupContents internal constructor(
    val source: LensProfileDevice,
    val appVersion: String,
    internal val settings: Map<String, Map<String, Any>>?,
    internal val customLanguage: String?,
    internal val lensFiles: Map<String, Map<String, Any>>?,
    internal val dcps: Map<String, ByteArray>,
    val photoProfiles: Int,
    val videoProfiles: Int,
    val sameDevice: Boolean,
) {
    val hasSettings get() = settings != null
    val canRestoreLensProfiles get() = lensFiles != null && sameDevice
}

/**
 * Saves and restores everything a person set up in the app: settings, button assignments,
 * custom film profiles and the language choice, and separately the lens profiles with their DCP files.
 */
object SettingsBackup {
    private const val FORMAT = "unspektrawesome-backup"
    private const val VERSION = 1
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024

    private const val CAMERA_PREFERENCES = "camera_preferences"

    /*
     * Every preference file is backed up unless it is listed here, so settings added later are included without
     * changing this class. Files starting with "lens_" belong to the lens profiles. Excluded files hold device
     * measurements, caches, receipts, crash guards and guide progress, which are not settings a person made.
     */
    private val EXCLUDED_FILES = setOf("raw_frame_rates", "lens_startup_guard", "capture-export-receipts",
        "gallery-export-receipts", "onboarding")

    /** Lens profile values that live beside the general settings in the camera preferences. */
    private val LENS_KEYS = setOf(
        "lens_profiles", "active_lens_profile", "default_lens_profile", "lens_profile_mode", "default_video_lens_profile",
        "idle_lens_profile", "enabled_lens_ids", "lens_selection_configured", "default_lens_id", "lens_button_route_ids",
        "lens_button_mapping_configured", "raw_preview_streams", "auto_lens_discovery_configured",
    )

    /** Values that must be granted again on each phone instead of being copied. */
    private val EXCLUDED_KEYS = mapOf("gallery" to setOf("raw_cleanup_authorization"))

    private val FILE_NAME = Regex("[A-Za-z0-9_.-]{1,64}")

    private fun lensFile(file: String) = file.startsWith("lens_")

    private fun lensKey(key: String) = key in LENS_KEYS || key.startsWith("lens_profile")

    private fun includes(part: BackupPart, file: String, key: String): Boolean = when {
        file in EXCLUDED_FILES || !file.matches(FILE_NAME) || key in EXCLUDED_KEYS[file].orEmpty() -> false
        file == CAMERA_PREFERENCES -> lensKey(key) == (part == BackupPart.LENS_PROFILES)
        else -> lensFile(file) == (part == BackupPart.LENS_PROFILES)
    }

    /** Preference files on this phone that hold values of [part]. */
    private fun files(context: Context, part: BackupPart): List<String> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .map { it.name }.filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }
            .filter { file -> file !in EXCLUDED_FILES && file.matches(FILE_NAME) &&
                (file == CAMERA_PREFERENCES || lensFile(file) == (part == BackupPart.LENS_PROFILES)) }
            .sorted()

    fun write(context: Context, output: OutputStream, readDcp: (String) -> ByteArray,
        device: LensProfileDevice = LensProfileDevice.current()) {
        val settings = JSONObject().put("files", snapshot(context, BackupPart.SETTINGS))
        customLanguageFile(context).takeIf { it.isFile }?.let { settings.put("customLanguage", it.readText(Charsets.UTF_8)) }
        val lensFiles = snapshot(context, BackupPart.LENS_PROFILES)
        val profiles = LensProfiles.decode(lensFiles.optJSONObject(CAMERA_PREFERENCES)
            ?.optJSONObject("lens_profiles")?.optString("value"), migrateNames = false)
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("sourceDevice", device.toJson())
        }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
            entry("settings.json", settings.toString().toByteArray(Charsets.UTF_8))
            entry("lens-profiles.json", JSONObject().put("files", lensFiles).toString().toByteArray(Charsets.UTF_8))
            var total = 0L
            profiles.mapNotNull { it.color.dcpId }.distinct().forEach { id ->
                val bytes = readDcp(id)
                require(digest(bytes) == id) { "A DCP file does not match its lens profile." }
                total += bytes.size
                require(total <= LensProfileArchive.MAX_BYTES) { "The DCP files of your lens profiles exceed 128 MB." }
                entry("dcps/$id.dcp", bytes)
            }
        }
    }

    fun read(input: InputStream, device: LensProfileDevice = LensProfileDevice.current()): SettingsBackupContents {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val json = name == "manifest.json" || name == "settings.json" || name == "lens-profiles.json"
                require(!entry.isDirectory && (json || name.matches(Regex("dcps/[a-f0-9]{64}\\.dcp")))) {
                    "Choose a backup exported from Unspektrawesome settings."
                }
                require(name !in entries && entries.size <= LensProfileArchive.MAX_PROFILES) { "This backup contains duplicate or excessive files." }
                val limit = if (json) MAX_JSON_BYTES else DcpParser.MAX_FILE_BYTES
                val bytes = zip.readUpTo(minOf(limit.toLong(), LensProfileArchive.MAX_BYTES - total).toInt() + 1)
                total += bytes.size
                require(bytes.size <= limit && total <= LensProfileArchive.MAX_BYTES) { "This backup is too large." }
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        fun json(name: String) = entries.remove(name)?.let { JSONObject(it.toString(Charsets.UTF_8)) }
        val manifest = json("manifest.json") ?: error("Choose a backup exported from Unspektrawesome settings.")
        require(manifest.opt("format") == FORMAT) { "Choose a backup exported from Unspektrawesome settings." }
        require(manifest.opt("version") == VERSION) { "This backup version is not supported. Try updating the app." }
        val source = LensProfileDevice.fromJson(manifest.optJSONObject("sourceDevice"))
        val settingsJson = json("settings.json")
        val settings = settingsJson?.let { parseFiles(it.optJSONObject("files"), BackupPart.SETTINGS) }
        val customLanguage = settingsJson?.opt("customLanguage") as? String
        val lensFiles = json("lens-profiles.json")?.let { parseFiles(it.optJSONObject("files"), BackupPart.LENS_PROFILES) }
        require(settings != null || lensFiles != null) { "This backup is empty." }
        val profiles = lensFiles?.get(CAMERA_PREFERENCES)?.get("lens_profiles")?.let { encoded ->
            require(encoded is String) { "This backup contains incomplete or invalid lens profiles." }
            val count = JSONArray(encoded).length()
            LensProfiles.decode(encoded, migrateNames = false).also {
                require(it.size == count) { "This backup contains incomplete or invalid lens profiles." }
            }
        }.orEmpty()
        val required = profiles.mapNotNull { it.color.dcpId }.toSet()
        require(entries.keys == required.map { "dcps/$it.dcp" }.toSet()) { "This backup is missing a DCP or contains unexpected files." }
        val dcps = required.associateWith { id ->
            entries.getValue("dcps/$id.dcp").also {
                require(digest(it) == id) { "A DCP file in this backup is damaged." }
                DcpParser.parse(it)
            }
        }
        return SettingsBackupContents(source, manifest.optString("appVersion"), settings, customLanguage, lensFiles, dcps,
            photoProfiles = profiles.count { it.mode == LensProfileMode.PHOTO },
            videoProfiles = profiles.count { it.mode == LensProfileMode.VIDEO },
            sameDevice = source.matches(device))
    }

    /**
     * Replaces the chosen [parts] with the backup's values. DCP files are stored first, so the lens profiles that use
     * them are complete once saved. The app must restart afterward, because running screens keep their loaded values.
     */
    fun restore(context: Context, backup: SettingsBackupContents, parts: Set<BackupPart>, importDcp: (ByteArray, String) -> String,
        importLanguage: (String) -> Unit) {
        require(parts.isNotEmpty())
        val lensProfiles = BackupPart.LENS_PROFILES in parts
        require(!lensProfiles || backup.canRestoreLensProfiles) {
            "These lens profiles are for ${backup.source.label}. Lens profiles can only be imported on the same device model."
        }
        require(BackupPart.SETTINGS !in parts || backup.settings != null) { "This backup has no settings." }
        if (lensProfiles) {
            val names = LensProfiles.decode(backup.lensFiles?.get(CAMERA_PREFERENCES)?.get("lens_profiles") as? String,
                migrateNames = false).mapNotNull { profile -> profile.color.dcpId?.let { it to (profile.color.dcpName ?: "Custom DCP.dcp") } }.toMap()
            backup.dcps.forEach { (id, bytes) -> check(importDcp(bytes, names[id] ?: "Custom DCP.dcp") == id) }
        }
        if (BackupPart.SETTINGS in parts) backup.customLanguage?.let(importLanguage)
        parts.forEach { part ->
            val values = if (part == BackupPart.SETTINGS) backup.settings!! else backup.lensFiles!!
            // The chosen part is replaced as a whole: values that are not in the backup return to their defaults.
            (files(context, part) + values.keys).distinct().forEach { file ->
                val preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)
                val editor = preferences.edit()
                preferences.all.keys.filter { includes(part, file, it) }.forEach(editor::remove)
                values[file].orEmpty().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value as Set<String>)
                    }
                }
                check(editor.commit()) { "Could not save the restored settings." }
            }
        }
    }

    private fun customLanguageFile(context: Context) = File(context.filesDir, "custom-language.json")

    private fun snapshot(context: Context, part: BackupPart): JSONObject = JSONObject().apply {
        files(context, part).forEach { file ->
            val values = JSONObject()
            context.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (!includes(part, file, key)) return@forEach
                val (type, encoded) = when (value) {
                    is String -> "string" to value
                    is Boolean -> "boolean" to value
                    is Int -> "int" to value
                    is Long -> "long" to value
                    is Float -> "float" to value.toDouble()
                    is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>())
                    else -> return@forEach
                }
                values.put(key, JSONObject().put("type", type).put("value", encoded))
            }
            put(file, values)
        }
    }

    private fun parseFiles(json: JSONObject?, part: BackupPart): Map<String, Map<String, Any>> {
        requireNotNull(json) { "This backup is damaged." }
        return json.keys().asSequence().associateWith { file ->
            require(file.matches(FILE_NAME) && file !in EXCLUDED_FILES) { "This backup is damaged." }
            val values = json.getJSONObject(file)
            values.keys().asSequence().associateWith { key ->
                require(includes(part, file, key)) { "This backup is damaged." }
                val item = values.getJSONObject(key)
                when (item.getString("type")) {
                    "string" -> item.getString("value")
                    "boolean" -> item.getBoolean("value")
                    "int" -> item.getInt("value")
                    "long" -> item.getLong("value")
                    "float" -> item.getDouble("value").toFloat()
                    "set" -> item.getJSONArray("value").let { array -> (0 until array.length()).map(array::getString).toSet() }
                    else -> error("This backup is damaged.")
                }
            }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
