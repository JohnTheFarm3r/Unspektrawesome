package com.unspektrawesome.spektra

import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.capture.SpektraShotMetadata
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringWriter

internal object SpektraProfileXmp {
    private const val NAMESPACE = "https://unspektrawesome.app/ns/recipe/1.0/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMP = "adobe:ns:meta/"
    private const val RDF_TAG = "RDF"
    private const val RECIPE_KEY = "Recipe"
    private const val DOCTYPE_PREFIX = "<!DOCTYPE"
    private const val MAX_BYTES = 60 * 1024
    private const val INVALID = "The embedded JPEG profile is invalid or unsupported."
    private const val MISSING = "This JPEG has no embedded profile. JPEGs exported before JPEG profile sharing was added cannot create a profile. Use an original JPEG from a version that supports this feature, with its metadata intact."

    fun encode(shot: SpektraShotMetadata): String = encode(SpektraProfile(
        "", "${shot.film} ${shot.format}".trim().take(40),
        SpektraFilmParams.fromJson(JSONObject(shot.settingsJson)),
    ))

    fun encode(profile: SpektraProfile): String {
        val recipe = JSONObject(SpektraProfileFile.encode(profile)).apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
        }.toString()
        val output = StringWriter()
        Xml.newSerializer().apply {
            setOutput(output)
            setPrefix("x", XMP)
            startTag(XMP, "xmpmeta")
            setPrefix("rdf", RDF)
            startTag(RDF, RDF_TAG)
            setPrefix("unspektra", NAMESPACE)
            startTag(RDF, "Description")
            attribute(RDF, "about", "")
            startTag(NAMESPACE, RECIPE_KEY)
            text(recipe)
            endTag(NAMESPACE, RECIPE_KEY)
            endTag(RDF, "Description")
            endTag(RDF, RDF_TAG)
            endTag(XMP, "xmpmeta")
            endDocument()
        }
        return output.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This profile is too large to embed in a JPEG." }
        }
    }

    fun read(input: InputStream): SpektraProfile {
        val bytes = try { ExifInterface(input).getAttributeBytes(ExifInterface.TAG_XMP) }
            catch (_: Exception) { throw IllegalArgumentException(INVALID) }
        require(bytes != null) { MISSING }
        require(bytes.size <= MAX_BYTES) { INVALID }
        return decode(bytes.toString(Charsets.UTF_8))
    }

    fun decode(xmp: String): SpektraProfile {
        require(xmp.toByteArray(Charsets.UTF_8).size <= MAX_BYTES &&
            !xmp.contains(DOCTYPE_PREFIX, ignoreCase = true)) { INVALID }
        val recipe = try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(xmp.reader())
            }
            var found: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val value = if (parser.namespace == NAMESPACE && parser.name == RECIPE_KEY) parser.nextText()
                    else parser.getAttributeValue(NAMESPACE, RECIPE_KEY)
                if (value != null) {
                    require(found == null) { INVALID }
                    found = value
                }
            }
            found
        } catch (_: Exception) { throw IllegalArgumentException(INVALID) }
        require(recipe != null) { MISSING }
        return SpektraProfileFile.decode(recipe)
    }
}
