package com.unspektrawesome.settings

import android.os.Build
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.calibration.DcpParser
import com.unspektrawesome.io.readUpTo
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportedLensProfile(val profile: LensProfile, val startup: Boolean = false)

data class LensProfileBackup(val profiles: List<ImportedLensProfile>, val dcps: Map<String, ByteArray>)

data class LensProfileDevice(val manufacturer: String, val model: String, val device: String) {
    private val identity get() = listOf(manufacturer, model, device).map { it.trim().lowercase(Locale.ROOT) }
    val label get() = "${manufacturer.trim()} ${model.trim()} (${device.trim()})"
    internal val isKnown get() = identity.all { it.isNotBlank() && it.length <= 128 && it != "unknown" }
    internal fun matches(other: LensProfileDevice) = isKnown && other.isKnown && identity == other.identity
    internal fun toJson() = JSONObject().put("manufacturer", manufacturer.trim()).put("model", model.trim()).put("device", device.trim())

    companion object {
        fun current() = LensProfileDevice(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.DEVICE.orEmpty())
        internal fun fromJson(json: JSONObject?) = LensProfileDevice(
            json?.opt("manufacturer") as? String ?: "", json?.opt("model") as? String ?: "", json?.opt("device") as? String ?: "")
    }
}

object LensProfileArchive {
    const val MAX_PROFILES = 512
    const val MAX_BYTES = 128 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val FORMAT = "unspektrawesome-lens-profiles"
    private const val VERSION = 2

    fun write(output: OutputStream, profiles: List<LensProfile>, startupId: String?,
        device: LensProfileDevice = LensProfileDevice.current(), readDcp: (String) -> ByteArray) {
        require(device.isKnown) { "Could not identify this device. Lens profiles cannot be exported." }
        require(profiles.size in 1..MAX_PROFILES) { "Select between 1 and $MAX_PROFILES lens profiles." }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Lens profiles contain duplicate identifiers." }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("sourceDevice", device.toJson())
            put("startupProfile", startupId?.takeIf { id -> profiles.any { it.id == id } })
            put("profiles", JSONArray(LensProfiles.encode(profiles)))
        }.toString(2).toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "The selected lens profiles are too large." }
        var total = manifest.size.toLong()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("manifest.json", manifest)
            profiles.mapNotNull { it.color.dcpId }.distinct().forEach { id ->
                val bytes = readDcp(id)
                require(digest(bytes) == id) { "A DCP file does not match its lens profile." }
                DcpParser.parse(bytes)
                total += bytes.size
                require(total <= MAX_BYTES) { "The selected DCP files exceed 128 MB. Export fewer profiles together." }
                entry("dcps/$id.dcp", bytes)
            }
        }
    }

    fun read(input: InputStream, device: LensProfileDevice = LensProfileDevice.current()): LensProfileBackup {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && (name == "manifest.json" || name.matches(Regex("dcps/[a-f0-9]{64}\\.dcp")))) {
                    "Choose an exported Unspektrawesome lens-profile backup."
                }
                require(name !in entries && entries.size <= MAX_PROFILES) { "This backup contains duplicate or excessive files." }
                val limit = if (name == "manifest.json") MAX_MANIFEST_BYTES else DcpParser.MAX_FILE_BYTES
                val bytes = zip.readUpTo(minOf(limit.toLong(), MAX_BYTES - total).toInt() + 1)
                total += bytes.size
                require(bytes.size <= limit && total <= MAX_BYTES) { "This lens-profile backup is too large." }
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        val manifest = entries.remove("manifest.json") ?: error("This file is missing its lens profiles.")
        val json = JSONObject(manifest.toString(Charsets.UTF_8))
        require(json.opt("format") == FORMAT) { "Choose an exported Unspektrawesome lens-profile backup." }
        require(json.opt("version") != 1) {
            "This older backup does not identify its source device. Update the app on the original phone and export the profiles again."
        }
        require(json.opt("version") == VERSION) { "This backup version is not supported. Try updating the app." }
        val source = LensProfileDevice.fromJson(json.optJSONObject("sourceDevice"))
        require(source.isKnown) { "This backup has no valid source-device information. Export the profiles again from the original phone." }
        require(device.isKnown) { "Could not identify this device. Lens profiles cannot be imported." }
        require(source.matches(device)) {
            "These profiles are for ${source.label}. This phone is ${device.label}. Lens profiles can only be imported on the same device model."
        }
        val array = json.getJSONArray("profiles")
        require(array.length() in 1..MAX_PROFILES) { "This backup has no lens profiles or contains too many." }
        val profiles = LensProfiles.decode(array.toString(), migrateNames = false)
        require(profiles.size == array.length()) { "This backup contains incomplete or invalid lens profiles." }
        profiles.forEach {
            require(it.rawLayoutOverride.validationError(it.rawStream.format,
                com.unspektrawesome.camera.Size2d(it.rawStream.width, it.rawStream.height)) == null) {
                "This backup contains an invalid RAW layout override."
            }
            require(it.rawLevels.isValid() && (!it.color.dcpEnabled || it.color.dcpId != null)) {
                "This backup contains invalid calibration settings."
            }
        }
        val required = profiles.mapNotNull { it.color.dcpId }.toSet()
        require(entries.keys == required.map { "dcps/$it.dcp" }.toSet()) { "This backup is missing a DCP or contains unexpected files." }
        val dcps = required.associateWith { id ->
            entries.getValue("dcps/$id.dcp").also {
                require(digest(it) == id) { "A DCP file in this backup is damaged." }
                DcpParser.parse(it)
            }
        }
        val startup = json.optString("startupProfile").takeIf { it.isNotBlank() }
        require(startup == null || profiles.any { it.id == startup }) { "The startup lens profile is missing from this backup." }
        return LensProfileBackup(profiles.map { ImportedLensProfile(it, it.id == startup) }, dcps)
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
