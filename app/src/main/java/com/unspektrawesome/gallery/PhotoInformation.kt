package com.unspektrawesome.gallery

import androidx.exifinterface.media.ExifInterface
import com.unspektrawesome.capture.CaptureMetadata
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class PhotoInfoField(val label: String, val value: String, val detail: String? = null, val translateValue: Boolean = true)
internal data class PhotoInfoSection(val title: String, val fields: List<PhotoInfoField>)

internal object PhotoInformation {
    private val captureFields = listOf("Shutter", "ISO", "Aperture", "Focal length", "35 mm equivalent")
    private val lookFields = listOf("Spektra", "FILM", "FORMAT", "PRINT", "HALATION", "CAMERA DIFFUSION", "PRINT DIFFUSION")
    private val cameraFields = listOf("Device", "Lens", "Lens profile", "RAW stream", "RAW crop", "Color calibration", "Manufacturer", "Model", "Codename", "App")
    private val mergeFields = listOf("Frames to merge", "Motion sensitivity", "Frames merged", "Rejected for motion")
    private val locationFields = listOf("Coordinates", "Altitude")
    private val labels = captureFields + lookFields + cameraFields + mergeFields + locationFields
    // EXIF readers return question marks for line breaks in older descriptions.
    private val fieldStart = Regex("(?:^|[;?\\r\\n]+)\\s*(" + labels.joinToString("|") { Regex.escape(it) } + "):\\s*", RegexOption.IGNORE_CASE)

    fun fromCapture(metadata: CaptureMetadata): List<PhotoInfoSection> = fromDescription(metadata.description(), buildMap {
        put("Lens", metadata.lensModel)
        metadata.identity?.let {
            put("App", it.software)
            put("Manufacturer", it.manufacturer)
            put("Model", it.model)
        }
        metadata.exposureTimeNs?.takeIf { it > 0 }?.let { put("Shutter", "${number(it / 1_000_000_000.0)} s") }
        metadata.sensitivityIso?.takeIf { it > 0 }?.let { put("ISO", it.toString()) }
        metadata.aperture?.takeIf { it.isFinite() && it > 0 }?.let { put("Aperture", "f/${BigDecimal.valueOf(it.toDouble()).setScale(1, RoundingMode.HALF_UP).toPlainString()}") }
        metadata.focalLengthMm?.takeIf { it.isFinite() && it > 0 }?.let { put("Focal length", "${rounded(it.toDouble(), 2)} mm") }
        metadata.equivalentFocalLengthMm?.times(metadata.lensProfile?.cropFactor ?: 1f)?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.let {
            put("35 mm equivalent", "${rounded(it, 0)} mm")
        }
        metadata.location?.let {
            put("Coordinates", coordinates(it.latitude, it.longitude))
            it.altitudeMeters?.let { altitude -> put("Altitude", "${number(altitude)} m") }
        }
    })

    fun fromExif(exif: ExifInterface): List<PhotoInfoSection> = fromDescription(
        exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty(),
        buildMap {
            fun text(label: String, tag: String) {
                exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { put(label, it) }
            }
            fun measurement(label: String, tag: String, prefix: String = "", suffix: String = "") {
                exif.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() && it > 0 }?.let {
                    put(label, "$prefix${number(it)}$suffix")
                }
            }
            text("App", ExifInterface.TAG_SOFTWARE)
            text("Manufacturer", ExifInterface.TAG_MAKE)
            text("Model", ExifInterface.TAG_MODEL)
            text("Lens", ExifInterface.TAG_LENS_MODEL)
            exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.let { comment ->
                runCatching { org.json.JSONObject(comment).optJSONObject("lensProfile")?.getString("name") }
                    .getOrNull()?.let { put("Lens profile", it) }
            }
            measurement("ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            measurement("Shutter", ExifInterface.TAG_EXPOSURE_TIME, suffix = " s")
            measurement("Aperture", ExifInterface.TAG_F_NUMBER, prefix = "f/")
            measurement("Focal length", ExifInterface.TAG_FOCAL_LENGTH, suffix = " mm")
            measurement("35 mm equivalent", ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, suffix = " mm")
            exif.latLong?.let {
                put("Coordinates", coordinates(it[0], it[1]))
                exif.getAltitude(Double.NaN).takeIf(Double::isFinite)?.let { altitude ->
                    put("Altitude", "${number(altitude)} m")
                }
            }
        },
    )

    fun fromDescription(description: String, fallback: Map<String, String> = emptyMap()): List<PhotoInfoSection> {
        val starts = fieldStart.findAll(description).toList()
        val fields = mutableMapOf<String, String>()
        starts.forEachIndexed { index, match ->
            val label = labels.first { it.equals(match.groupValues[1], ignoreCase = true) }
            val value = description.substring(match.range.last + 1, starts.getOrNull(index + 1)?.range?.first ?: description.length).trim()
            if (value.isNotEmpty()) fields[label] = value
        }
        fields.putAll(fallback.filterValues { it.isNotBlank() })
        fun section(title: String, keys: List<String>): PhotoInfoSection? {
            val rows = keys.mapNotNull { key -> fields[key]?.let { value ->
                val label = when (key) {
                    "Spektra" -> "Processing"
                    "FILM" -> "Film"
                    "FORMAT" -> "Format"
                    "PRINT" -> "Print"
                    "HALATION" -> "Halation"
                    "CAMERA DIFFUSION" -> "Camera diffusion"
                    "PRINT DIFFUSION" -> "Print diffusion"
                    else -> key
                }
                if (key in listOf("HALATION", "CAMERA DIFFUSION", "PRINT DIFFUSION")) {
                    val parts = value.split(';').map(String::trim)
                    PhotoInfoField(label, parts.first(), parts.drop(1).joinToString(" · ").takeIf { it.isNotEmpty() })
                } else PhotoInfoField(label, when (key) {
                    "Shutter" -> shutter(value)
                    "Frames merged" -> framesMerged(value)
                    else -> value
                },
                    translateValue = key !in cameraFields && key !in locationFields)
            } }
            return rows.takeIf { it.isNotEmpty() }?.let { PhotoInfoSection(title, it) }
        }
        return buildList {
            section("Capture", captureFields)?.let(::add)
            section("spektrafilm", if (fields["Spektra"].equals("Disabled", ignoreCase = true)) listOf("Spektra") else lookFields)?.let(::add)
            section("Multi-frame merge", mergeFields)?.let(::add)
            section("Camera", cameraFields)?.let(::add)
            section("Location", locationFields)?.let(::add)
            if (starts.isEmpty() && description.isNotBlank()) {
                add(PhotoInfoSection("Description", listOf(PhotoInfoField("", description.trim(), translateValue = false))))
            }
        }
    }

    private fun shutter(value: String): String {
        val seconds = value.removeSuffix("s").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return value
        val denominator = (1.0 / seconds).roundToLong()
        // Keep slow exposures in seconds when a rounded reciprocal would change the duration by more than 2%.
        val formatted = if (denominator >= 2 && abs(seconds * denominator - 1.0) <= 0.02) {
            "1/$denominator"
        } else {
            BigDecimal.valueOf(seconds).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
        return formatted + " s"
    }

    // Rebuilds the stored "6 of 8" text from a template the language files can translate.
    private fun framesMerged(value: String): String {
        val (merged, total) = Regex("(\\d+) of (\\d+)").matchEntire(value.trim())?.destructured ?: return value
        return "$merged of $total"
    }

    private fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    private fun rounded(value: Double, decimals: Int) =
        BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun coordinates(latitude: Double, longitude: Double) = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
}
