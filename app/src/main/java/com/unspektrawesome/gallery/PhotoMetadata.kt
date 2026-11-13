package com.unspektrawesome.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramIconButton
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.ui.camera.diagramHairline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoMetadata(photo: GalleryPhoto, shot: GalleryShot?, modifier: Modifier, showDate: Boolean = false,
    videoPosition: Long = 0, onClose: () -> Unit = {}) {
    key(photo.uri, photo.generation, shot?.id) {
        val context = LocalContext.current
        val sections by produceState<List<PhotoInfoSection>?>(null) {
            value = withContext(Dispatchers.IO) {
                val capture = shot?.let { runCatching { GalleryStore.get(context).recipe(it.id).metadata }.getOrNull() }
                runCatching {
                    if (photo.isVideo) VideoInformation.read(context, photo)
                    else if (capture != null) PhotoInformation.fromCapture(capture)
                    else context.contentResolver.openInputStream(photo.uri)?.use { PhotoInformation.fromExif(ExifInterface(it)) }.orEmpty()
                }.getOrDefault(emptyList())
            }
        }
        Column(modifier.clip(Diagram.WindowShape).background(Color(0xE60C0C0C))
            .border(1.dp, Diagram.Border, Diagram.WindowShape)
            .testTag("Photo information")) {
            Row(Modifier.fillMaxWidth().diagramHairline().padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(if (photo.isVideo) "Video info" else "Photo info", color = CameraColors.Text,
                        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    if (photo.isVideo) Text("What is inside this file", color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp)
                }
                DiagramIconButton(SettingsSymbol.Close, "Close", onClick = onClose)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxWidth < 250.dp || LocalDensity.current.fontScale >= 1.4f
                val wide = maxWidth >= 300.dp && !compact
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                    Column(Modifier.fillMaxWidth().diagramHairline().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(photo.name, translate = false, color = CameraColors.Text, fontSize = 11.sp, lineHeight = 15.sp,
                            fontFamily = Diagram.Mono)
                        if (!photo.isVideo) Text("{width} × {height} · {value3}", arguments = mapOf("width" to (photo.width),
                            "height" to (photo.height), "value3" to (bytes(photo.bytes))), color = Diagram.Hint, fontSize = 10.5.sp,
                            lineHeight = 14.sp, fontFamily = Diagram.Mono)
                        if (showDate) Text(date(photo.capturedAt, "d MMMM yyyy · HH:mm:ss"), color = Diagram.Hint, fontSize = 10.5.sp,
                            lineHeight = 14.sp, fontFamily = Diagram.Mono)
                        if (shot != null && !shot.hasRaw) Text("Original RAW deleted after export", color = MediaArt.SoftInk,
                            fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                    val loaded = sections
                    when {
                        loaded == null -> Text("Loading capture information", color = Diagram.Hint, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp))
                        loaded.isEmpty() -> if (!photo.isVideo) Text("No capture details available", color = Diagram.Hint,
                            fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        photo.isVideo -> VideoInformationBody(photo, loaded, compact, wide, videoPosition)
                        else -> PhotoInformationBody(photo, loaded, compact, wide)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoInformationBody(photo: GalleryPhoto, sections: List<PhotoInfoSection>, compact: Boolean, wide: Boolean) {
    sections.forEach { section ->
        when (section.title) {
            "Capture" -> {
                InfoHeading(section.title)
                InfoGrid(section.fields, if (compact) 1 else if (wide) 3 else 2)
            }
            "spektrafilm" -> SpektrafilmChain(photo, section, compact)
            "Camera" -> CameraStage(section, compact)
            else -> CommonSection(section, compact)
        }
    }
}

/** The look as the film process it imitates: sensor, camera filter, negative, print and the saved file. */
@Composable
private fun SpektrafilmChain(photo: GalleryPhoto, section: PhotoInfoSection, compact: Boolean) {
    val fields = section.fields.associateBy { it.label }
    val processing = fields["Processing"]
    InfoHeading(section.title, trailing = processing?.let { field -> @Composable {
        InfoPill("Processing", field.value, field.translateValue, active = !field.value.equals("Disabled", ignoreCase = true))
    } })
    fun enabled(label: String) = fields[label]?.value?.equals("Disabled", ignoreCase = true) == false
    val camera = listOfNotNull(fields["Camera diffusion"])
    val film = listOfNotNull(fields["Film"], fields["Format"], fields["Halation"])
    val print = listOfNotNull(fields["Print"], fields["Print diffusion"])
    val known = setOf("Processing", "Camera diffusion", "Film", "Format", "Halation", "Print", "Print diffusion")
    val other = section.fields.filter { it.label !in known }
    if (camera.isEmpty() && film.isEmpty() && print.isEmpty()) {
        if (other.isNotEmpty()) InfoStage(other, compact)
        return
    }
    InfoEndpoint("RAW", "scene from the sensor", accent = false)
    if (camera.isNotEmpty()) {
        InfoLink("through the lens filter")
        InfoStage(camera, compact, caption = "Camera") { cameraDiffusionArt(enabled("Camera diffusion")) }
    }
    if (film.isNotEmpty()) {
        InfoLink("exposes the negative")
        InfoStage(film, compact, caption = "Film", accent = { it.label == "Film" }) { filmArt(enabled("Halation")) }
    }
    if (print.isNotEmpty()) {
        InfoLink("printed on paper")
        InfoStage(print, compact, caption = "Print", accent = { it.label == "Print" }) { printArt() }
    }
    if (other.isNotEmpty()) InfoStage(other, compact, Modifier.padding(top = 4.dp))
    InfoLink("saved")
    InfoEndpoint(if (photo.mimeType == "image/png") "PNG" else "JPG", "this file · {width} × {height}", accent = true,
        arguments = mapOf("width" to photo.width, "height" to photo.height))
}

@Composable
private fun CameraStage(section: PhotoInfoSection, compact: Boolean) {
    InfoHeading(section.title)
    InfoStage(section.fields, compact, caption = "Sensor", drawingHeight = 104.dp, accent = { it.label == "Lens profile" }) { sensorArt() }
}

@Composable
private fun CommonSection(section: PhotoInfoSection, compact: Boolean) {
    when (section.title) {
        "Multi-frame merge" -> {
            InfoHeading(section.title)
            val fields = section.fields.associateBy { it.label }
            val numbers = Regex("(\\d+)\\D+(\\d+)").find(fields["Frames merged"]?.value.orEmpty())?.destructured
            val total = numbers?.component2()?.toIntOrNull()
                ?: Regex("\\d+").find(fields["Frames to merge"]?.value.orEmpty())?.value?.toIntOrNull() ?: 8
            val merged = numbers?.component1()?.toIntOrNull() ?: total
            InfoStage(section.fields, compact, caption = "Merge", drawingHeight = 50.dp, accent = { it.label == "Frames merged" }) {
                mergeArt(merged, total)
            }
        }
        "Location" -> {
            InfoHeading(section.title)
            InfoStage(section.fields, compact, drawingHeight = 34.dp) { locationArt() }
        }
        "Description" -> {
            InfoHeading(section.title)
            section.fields.forEach {
                Text(it.value, translate = it.translateValue, color = MediaArt.SoftInk, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        else -> {
            InfoHeading(section.title)
            InfoStage(section.fields, compact)
        }
    }
}

@Composable
private fun VideoInformationBody(photo: GalleryPhoto, sections: List<PhotoInfoSection>, compact: Boolean, wide: Boolean, position: Long) {
    val hasAudio = sections.any { it.title == "Audio" }
    val looks = sections.filter { it.title == "spektrafilm" }
    sections.forEach { section ->
        val fields = section.fields.associateBy { it.label }
        when (section.title) {
            "Video" -> {
                InfoHeading(section.title, accent = true) {
                    InfoNote(if (hasAudio) "one file, two tracks" else "one file, one track")
                }
                VideoStreamMap(photo, fields["File size"]?.value?.takeIf { it != "Not specified" }, hasAudio, position, photo.durationMs)
                val summaryLabels = listOf("Duration", "File size", "Overall bitrate")
                InfoGrid(summaryLabels.mapNotNull { fields[it] }, if (compact) 1 else 3, Modifier.padding(top = 6.dp))
                val gyro = section.fields.filter { it.label == "Live gyro stabilization" || it.label.startsWith("Stabilization ") }
                val track = section.fields.filter { it.label !in summaryLabels && it !in gyro }
                InfoStage(track, compact, Modifier.padding(top = 8.dp), caption = "Video track", drawingHeight = 48.dp) { videoTrackArt() }
                val stabilized = fields["Live gyro stabilization"]?.value == "On"
                val crop = fields["Stabilization crop"]?.value?.removeSuffix("x")?.toDoubleOrNull()
                InfoStage(gyro, compact, Modifier.padding(top = 8.dp), caption = "Gyro", drawingHeight = 48.dp,
                    accent = { it.label == "Live gyro stabilization" && stabilized }) { gyroArt(stabilized, crop) }
            }
            "Color" -> {
                InfoHeading(section.title, accent = true) { InfoNote("how a player reads the pixels") }
                val chain = listOf("Color range", "Matrix coefficients", "Transfer", "Color primaries").mapNotNull { fields[it] }
                InfoChain(chain)
                InfoGrid(section.fields.filter { it !in chain }, if (compact) 1 else 2, Modifier.padding(top = 6.dp))
            }
            "Audio" -> {
                InfoHeading(section.title, accent = true) { AudioWaveMark() }
                InfoGrid(section.fields, if (compact) 1 else if (wide) 4 else 2)
            }
            "Camera" -> CameraStage(section, compact)
            "spektrafilm" -> {
                val index = looks.indexOf(section)
                val time = fields["Time"]
                if (index == 0) {
                    InfoHeading(section.title, accent = true) { InfoNote("one block per settings change") }
                    SettingsChangeTimeline(looks.mapNotNull { look ->
                        val values = look.fields.associateBy { it.label }
                        values["Time"]?.value?.removeSuffix(" s")?.toDoubleOrNull()?.let { it to values["Film"]?.value }
                    }, photo.durationMs / 1000.0)
                } else InfoHeading(section.title, accent = true) {
                    time?.let { Text(it.value, translate = false, color = CameraColors.Text, fontSize = 10.sp, lineHeight = 13.sp,
                        fontFamily = Diagram.Mono) }
                }
                InfoGrid(section.fields, if (compact) 1 else 2, Modifier.padding(top = 4.dp), accent = { it.label == "Time" })
            }
            else -> CommonSection(section, compact)
        }
    }
}
