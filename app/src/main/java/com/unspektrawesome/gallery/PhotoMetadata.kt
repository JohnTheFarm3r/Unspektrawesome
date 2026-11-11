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
import com.unspektrawesome.ui.camera.CameraStyle
import com.unspektrawesome.ui.camera.CameraTypography
import com.unspektrawesome.ui.camera.SettingsIcon
import com.unspektrawesome.ui.camera.SettingsSymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoMetadata(photo: GalleryPhoto, shot: GalleryShot?, modifier: Modifier, showDate: Boolean = false,
    onClose: () -> Unit = {}) {
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
        Column(modifier.clip(CameraStyle.PanelShape).background(CameraColors.Glass)
            .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
            .testTag("Photo information")) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (photo.isVideo) "Video info" else "Photo info", color = CameraColors.Text,
                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                GalleryIconButton("Close", onClick = onClose) {
                    SettingsIcon(SettingsSymbol.Close, Modifier.size(18.dp), CameraColors.Text)
                }
            }
            androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(photo.name, translate = false, color = CameraColors.Text, fontSize = 12.sp, fontFamily = CameraTypography.Readout, lineHeight = 16.sp)
                if (!photo.isVideo) Text("{width} × {height} · {value3}", arguments = mapOf("width" to (photo.width), "height" to (photo.height), "value3" to (bytes(photo.bytes))), color = CameraColors.Muted, fontSize = 11.sp, fontFamily = CameraTypography.Readout)
                if (showDate) Text(date(photo.capturedAt, "d MMMM yyyy · HH:mm:ss"), color = CameraColors.Muted, fontSize = 11.sp)
                if (shot != null && !shot.hasRaw) Text("Original RAW deleted after export", color = CameraColors.Muted, fontSize = 11.sp)
            }
            val loaded = sections
            when {
                loaded == null -> Text("Loading capture information", color = CameraColors.Muted, fontSize = 11.sp)
                loaded.isEmpty() -> if (!photo.isVideo) Text("No capture details available", color = CameraColors.Muted, fontSize = 11.sp)
                else -> loaded.forEach { section -> PhotoInformationSection(section) }
            }
            }
        }
    }
}

@Composable
private fun PhotoInformationSection(section: PhotoInfoSection) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val singleColumn = maxWidth < 250.dp || LocalDensity.current.fontScale >= 1.4f
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
            Text(section.title.uppercase(), color = CameraColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = .7.sp, modifier = Modifier.semantics { heading() })
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (section.title == "Capture") {
                    section.fields.chunked(if (singleColumn) 1 else 2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { field ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(field.label, color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 14.sp)
                                    Text(field.value, translate = field.translateValue, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 17.sp, fontFamily = CameraTypography.Readout)
                                }
                            }
                            if (!singleColumn && row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    section.fields.forEach { field ->
                        if (singleColumn) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (field.label.isNotEmpty()) Text(field.label, modifier = Modifier.fillMaxWidth(), color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 15.sp)
                                PhotoInformationValue(field)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                if (field.label.isNotEmpty()) Text(field.label, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 17.sp,
                                    modifier = Modifier.weight(.36f))
                                PhotoInformationValue(field, Modifier.weight(.64f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoInformationValue(field: PhotoInfoField, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(field.value, modifier = Modifier.fillMaxWidth(), translate = field.translateValue, color = CameraColors.Text, fontSize = 11.sp, lineHeight = 17.sp)
        field.detail?.let { Text(it, color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 15.sp) }
    }
}
