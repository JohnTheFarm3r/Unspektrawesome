package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.RawOutput
import com.unspektrawesome.camera.exposure.AeEngine
import com.unspektrawesome.camera.exposure.ExposureBounds
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.*
import java.util.Locale
import kotlin.math.ln

/**
 * Section heading of the lens profile editor in the [DiagramSection] style, with a small number that
 * matches the overview drawing.
 */
@Composable
internal fun LensSectionHeading(number: Int, title: String, modifier: Modifier = Modifier, tag: String? = null) {
    val renderer = LocalAppLanguage.current.renderer
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp)
        .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        StageNumber(number)
        Text(renderer.text(title).uppercase(), translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        tag?.let {
            Text(renderer.text(it).uppercase(), translate = false, color = Color(0xFFCFCFCF), fontSize = 9.sp,
                lineHeight = 12.sp, letterSpacing = .9.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.border(1.dp, Diagram.Border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** Small lime marker used inside dense stage drawings. */
@Composable
internal fun StageNumber(number: Int, modifier: Modifier = Modifier, active: Boolean = true) {
    Box(modifier.size(15.dp).background(if (active) CameraColors.Accent else Diagram.Line, CircleShape),
        contentAlignment = Alignment.Center) {
        Text("$number", translate = false, color = Color.Black, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Outlined box of a stage flow with free content. */
@Composable
internal fun StageBox(modifier: Modifier, width: Dp, height: Dp, active: Boolean = false, dashed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val border = when { active -> CameraColors.Accent; dashed -> Diagram.Line; else -> Diagram.Ink }
    Column(modifier.width(width).heightIn(min = height)
        .drawBehind {
            drawRoundRect(Color(0xFF050505), cornerRadius = CornerRadius(7.dp.toPx()))
            drawRoundRect(border, cornerRadius = CornerRadius(7.dp.toPx()), style = Stroke(1.5.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
        }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically), content = content)
}

@Composable
internal fun StageTitle(text: String, active: Boolean = false, translate: Boolean = true) {
    Text(text, translate = translate, color = if (active) CameraColors.Accent else CameraColors.Text, fontSize = 10.5.sp,
        lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun StageMono(text: String, active: Boolean = false, translate: Boolean = false, arguments: Map<String, Any?>? = null,
    color: Color = if (active) CameraColors.Accent else Color(0xFFCFCFCF), mono: Boolean = true) {
    Text(text, translate = translate, arguments = arguments, color = color, fontSize = 9.sp, lineHeight = 11.sp,
        fontFamily = if (mono) Diagram.Mono else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Stage caption in the small uppercase style. */
@Composable
internal fun StageCaption(text: String, modifier: Modifier = Modifier, maxWidth: Dp = 160.dp, color: Color = Diagram.Hint) {
    val renderer = LocalAppLanguage.current.renderer
    Text(renderer.text(text).uppercase(), translate = false, color = color, fontSize = 8.5.sp, lineHeight = 11.sp,
        letterSpacing = .9.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = maxWidth))
}

/** Stage whose drawing is described once instead of exposing every label to screen readers. */
@Composable
internal fun LensStage(height: Dp, description: String, content: @Composable BoxScope.() -> Unit) {
    DiagramStage(height, description = description) {
        Box(Modifier.matchParentSize().clearAndSetSemantics {}, content = content)
    }
}

private fun Float.format(digits: Int) = String.format(Locale.ROOT, "%.${digits}f", this)

/** Overview of a lens profile from the sensor to the viewfinder and saved photo. */
@Composable
internal fun LensProfileOverviewStage(profile: LensProfile, sensor: PhysicalSensorProfile, camera: CameraDescriptor,
    stream: RawStreamSelection) {
    val renderer = LocalAppLanguage.current.renderer
    val decoded = profile.rawLayoutOverride.stream(stream)
    val preview = ViewfinderResolutions.size(decoded,
        ViewfinderResolutions.selectedEdge(decoded, profile.previewShortEdge, profile.previewQuality))
    val adjusted = profile.rawEdgeCrop.isActive || profile.rawLayoutOverride.enabled ||
        profile.cfaOverride != CfaOverride() || profile.vendorOverrides.enabled
    val pathIndex = sensor.paths.indexOfFirst { it.route.routeId == camera.route.routeId }.coerceAtLeast(0) + 1
    // Rows: captions at 30, the input boxes at 54, notes under them from 106, the processing boxes at 186 and the
    // outputs at 252. Each note has room for a second line before the next row starts.
    LensStage(320.dp, "Overview of this lens profile from the sensor to the saved photo. Numbers match the sections below.") {
        DiagramDrawing {
            val lime = CameraColors.Accent
            box(8.dp, 54.dp, 52.dp, 46.dp, radius = 7.dp, fill = Color(0xFF050505))
            circle(34.dp, 77.dp, 11.dp)
            circle(34.dp, 77.dp, 5.dp, color = Diagram.Line)
            arrow(60.dp, 77.dp, 73.dp, 77.dp, lime)
            arrow(196.dp, 77.dp, 209.dp, 77.dp, lime)
            arrow(222.dp, 100.dp, 222.dp, 185.dp, lime)
            arrow(213.dp, 203.dp, 203.dp, 203.dp, lime)
            line(144.dp, 220.dp, 144.dp, 236.dp, lime)
            line(78.dp, 236.dp, 251.dp, 236.dp, lime)
            arrow(78.dp, 236.dp, 78.dp, 251.dp, lime)
            arrow(251.dp, 236.dp, 251.dp, 251.dp, lime)
            polyline(listOf(110.dp to 186.dp, 110.dp to 176.dp, 34.dp to 176.dp), lime, dashed = true)
            arrow(34.dp, 176.dp, 34.dp, 103.dp, lime, dashed = true)
        }
        StageCaption("Sensor to photo · numbers match sections", Modifier.at(12.dp, 8.dp), maxWidth = 312.dp)
        StageCaption("Sensor", Modifier.at(8.dp, 30.dp), maxWidth = 64.dp)
        StageCaption("Camera ID", Modifier.at(76.dp, 30.dp), maxWidth = 108.dp)
        StageCaption("RAW stream", Modifier.at(212.dp, 30.dp), maxWidth = 100.dp)
        StageBox(Modifier.at(76.dp, 54.dp), 120.dp, 46.dp, active = true) {
            cameraIdLabel(camera).split(" · ").forEach { StageMono(it, active = true) }
        }
        StageBox(Modifier.at(212.dp, 54.dp), 114.dp, 46.dp) {
            StageMono("${stream.width} x ${stream.height}")
            StageMono("${stream.format.name} · ${(stream.width.toFloat() / stream.height).format(2)}")
        }
        StageNumber(1, Modifier.at(189.dp, 47.dp))
        StageNumber(1, Modifier.at(319.dp, 47.dp))
        Column(Modifier.at(76.dp, 106.dp).width(120.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            StageMono("Lens ID {id}", arguments = mapOf("id" to profile.routeId), color = Diagram.Hint)
            DiagramText("{index} of {count} paths to this sensor".let { renderer.format(it, mapOf("index" to pathIndex,
                "count" to sensor.paths.size)) }, size = 9f, translate = false, maxWidth = 120.dp)
        }
        Column(Modifier.at(230.dp, 106.dp).width(96.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StageNumber(3)
                Column(Modifier.weight(1f)) {
                    StageMono("Frame rate", translate = true, color = Diagram.Hint, mono = false)
                    val rate = profile.capture.frameRate
                    if (rate == null) StageMono("Lens default", active = true, translate = true)
                    else StageMono("{value1} fps", active = true, arguments = mapOf("value1" to rate))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StageNumber(4)
                Column(Modifier.weight(1f)) {
                    StageMono("Frame averaging", translate = true, color = Diagram.Hint, mono = false)
                    StageMono("${profile.capture.averageFrames} / ${profile.capture.frameInterval}", active = true)
                }
            }
        }
        StageNumber(8, Modifier.at(27.dp, 132.dp))
        Column(Modifier.at(8.dp, 186.dp).width(72.dp)) {
            StageCaption("Auto exposure", maxWidth = 72.dp)
            StageMono(if (profile.aeEngine == AeEngine.ANDROID) "Android AE" else "Unspektra AE", active = true, translate = true)
        }
        StageBox(Modifier.at(214.dp, 186.dp), 112.dp, 34.dp, dashed = !adjusted) {
            StageTitle("RAW adjustments")
            StageMono("crop · layout · CFA", translate = true, color = Diagram.Hint, mono = false)
        }
        StageBox(Modifier.at(86.dp, 186.dp), 116.dp, 34.dp) {
            StageTitle("Color calibration")
            StageMono("levels · shading · DCP", translate = true, color = Diagram.Hint, mono = false)
        }
        StageNumber(9, Modifier.at(319.dp, 179.dp))
        StageNumber(10, Modifier.at(195.dp, 179.dp))
        StageBox(Modifier.at(8.dp, 252.dp), 140.dp, 50.dp) {
            StageTitle("Viewfinder")
            StageMono("${preview.width} × ${preview.height}")
        }
        Box(Modifier.at(117.dp, 264.dp).size(22.dp).border(1.5.dp, Diagram.Ink, CircleShape), contentAlignment = Alignment.Center) {
            Text(renderer.text(profile.focalLength.label(camera)), translate = false, color = CameraColors.Text, fontSize = 7.5.sp,
                lineHeight = 9.sp, fontFamily = Diagram.Mono, maxLines = 1)
        }
        StageBox(Modifier.at(176.dp, 252.dp), 150.dp, 50.dp) {
            StageTitle("Capture")
            if (profile.mode == LensProfileMode.PHOTO) {
                StageMono("Photo OIS · {state}", translate = true, mono = false,
                    arguments = mapOf("state" to if (profile.photoOisEnabled) "On" else "Off"))
                StageMono("Multi-frame merge · {state}", translate = true, mono = false,
                    arguments = mapOf("state" to if (profile.capture.mergeEnabled) "On" else "Off"))
            } else {
                val video = profile.videoStabilization
                if (video.liveStabilization) StageMono("Live gyro stabilization · {state}", translate = true, mono = false,
                    arguments = mapOf("state" to "On"))
                else {
                    if (!video.captureGyro) StageMono("OIS · {state}", translate = true, mono = false,
                        arguments = mapOf("state" to if (video.ois) "On" else "Off"))
                    StageMono("Capture gyro data · {state}", translate = true, mono = false,
                        arguments = mapOf("state" to if (video.captureGyro) "On" else "Off"))
                }
            }
        }
        StageNumber(7, Modifier.at(141.dp, 245.dp))
        StageNumber(6, Modifier.at(108.dp, 286.dp))
        StageNumber(2, Modifier.at(319.dp, 245.dp))
        StageNumber(5, Modifier.at(319.dp, 295.dp))
    }
}

internal fun cameraIdLabel(camera: CameraDescriptor): String = when (camera.route.kind) {
    com.unspektrawesome.camera.CameraRoute.Kind.LOGICAL, com.unspektrawesome.camera.CameraRoute.Kind.HIDDEN_LOGICAL ->
        "Logical ID ${camera.route.openCameraId}"
    com.unspektrawesome.camera.CameraRoute.Kind.PHYSICAL ->
        "Logical ID ${camera.route.openCameraId} · Physical ID ${camera.route.physicalCameraId}"
    com.unspektrawesome.camera.CameraRoute.Kind.DIRECT, com.unspektrawesome.camera.CameraRoute.Kind.HIDDEN_DIRECT ->
        "Physical ID ${camera.route.openCameraId}"
}

/** Short explanation of how a camera ID reaches the lens, shown under each Camera ID choice. */
internal fun cameraIdDetail(camera: CameraDescriptor): String = when (camera.route.kind) {
    com.unspektrawesome.camera.CameraRoute.Kind.LOGICAL, com.unspektrawesome.camera.CameraRoute.Kind.HIDDEN_LOGICAL -> "Logical camera"
    com.unspektrawesome.camera.CameraRoute.Kind.PHYSICAL -> "This lens, through a logical camera"
    com.unspektrawesome.camera.CameraRoute.Kind.DIRECT, com.unspektrawesome.camera.CameraRoute.Kind.HIDDEN_DIRECT -> "This lens, opened directly"
}

/** Bytes of one RAW frame, from the stored bits per pixel. */
internal fun rawFrameBytes(width: Int, height: Int, format: com.unspektrawesome.camera.RawFormat): Double =
    width.toDouble() * height * format.storageBitsPerPixel / 8.0

/** Data per frame of each RAW stream the lens offers, with the saved stream in lime. */
@Composable
internal fun RawStreamDataStage(outputs: List<RawOutput>, stream: RawStreamSelection, supported: (RawOutput) -> Boolean) {
    val rows = outputs.take(6)
    val largest = rows.maxOfOrNull { rawFrameBytes(it.size.width, it.size.height, it.format) }?.coerceAtLeast(1.0) ?: 1.0
    // The title may wrap to a second line above the stream boxes, and the column captions above the rows.
    LensStage(104.dp + 16.dp * rows.size + 8.dp, "Data per frame of each RAW stream. The saved stream is highlighted.") {
        DiagramDrawing {
            val lime = CameraColors.Accent
            arrow(80.dp, 47.dp, 95.dp, 47.dp, lime)
            line(222.dp, 47.dp, 234.dp, 47.dp, lime)
            line(234.dp, 37.dp, 234.dp, 59.dp, lime)
            arrow(234.dp, 37.dp, 245.dp, 37.dp, lime)
            arrow(234.dp, 59.dp, 245.dp, 59.dp, lime)
            line(8.dp, 76.dp, 326.dp, 76.dp, Diagram.StageBorder, stroke = 1.dp)
        }
        StageCaption("RAW stream · data per frame", Modifier.at(12.dp, 8.dp), maxWidth = 200.dp)
        StageBox(Modifier.at(8.dp, 36.dp), 72.dp, 22.dp) { StageTitle("Camera ID") }
        StageBox(Modifier.at(98.dp, 34.dp), 124.dp, 26.dp, active = true) {
            StageMono("${stream.width} x ${stream.height} · ${stream.format.name}", active = true)
        }
        StageBox(Modifier.at(248.dp, 28.dp), 78.dp, 18.dp) { StageMono("Viewfinder", translate = true, mono = false) }
        StageBox(Modifier.at(248.dp, 50.dp), 78.dp, 18.dp) { StageMono("Capture", translate = true, mono = false) }
        StageCaption("Stream", Modifier.at(8.dp, 80.dp), maxWidth = 126.dp)
        StageCaption("About this much data per frame", Modifier.at(140.dp, 80.dp), maxWidth = 188.dp)
        rows.forEachIndexed { index, output ->
            val y = 104.dp + 16.dp * index
            val selected = stream.matches(output)
            val available = supported(output)
            val color = when { selected -> CameraColors.Accent; available -> Color(0xFFCFCFCF); else -> Color(0xFF8C8C8C) }
            Box(Modifier.at(8.dp, y).width(128.dp)) {
                Text("${output.size.width} x ${output.size.height} ${output.format.name}", translate = false, color = color,
                    fontSize = 8.5.sp, lineHeight = 11.sp, fontFamily = Diagram.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val bytes = rawFrameBytes(output.size.width, output.size.height, output.format)
            // The bar, size and state share the row up to the stage edge; the bar is short enough to leave the words room.
            Row(Modifier.at(140.dp, y).width(188.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val width = (80.0 * bytes / largest).coerceAtLeast(3.0).dp
                Box(Modifier.size(width, 7.dp).then(
                    if (available || selected) Modifier.background(if (selected) CameraColors.Accent else Diagram.Line, RoundedCornerShape(2.dp))
                    else Modifier.border(1.dp, Color(0xFF6A6A6A), RoundedCornerShape(2.dp))))
                Text("{value1} MB", arguments = mapOf("value1" to (bytes / 1_000_000.0).toFloat().format(1)), color = color,
                    fontSize = 8.5.sp, lineHeight = 11.sp, fontFamily = Diagram.Mono, maxLines = 1)
                if (selected) Text("selected", color = color, fontSize = 8.5.sp, lineHeight = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                else if (!available) Text("unavailable", color = color, fontSize = 8.5.sp, lineHeight = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** What the photo and video profiles steady. The other capture mode is dimmed. */
@Composable
internal fun CaptureStabilizationStage(profile: LensProfile, oisSupported: Boolean) {
    val photo = profile.mode == LensProfileMode.PHOTO
    val video = profile.videoStabilization
    val photoOis = photo && profile.photoOisEnabled && oisSupported
    LensStage(184.dp, "What this profile steadies. Photo profiles use optical stabilization, video profiles can also use gyro stabilization.") {
        DiagramDrawing { line(167.dp, 20.dp, 167.dp, 174.dp, Diagram.Border, stroke = 1.dp, dashed = true) }
        Box(Modifier.matchParentSize().alpha(if (photo) 1f else .35f)) {
            DiagramDrawing {
                val ink = if (photoOis) CameraColors.Accent else Diagram.Line
                box(22.dp, 46.dp, 118.dp, 74.dp, radius = 12.dp, fill = Color(0xFF050505))
                circle(81.dp, 83.dp, 28.dp)
                circle(81.dp, 83.dp, 15.dp, color = Diagram.Line)
                if (photoOis) circle(86.dp, 80.dp, 15.dp, color = CameraColors.Accent, dashed = true)
                arrow(46.dp, 83.dp, 33.dp, 83.dp, ink)
                arrow(116.dp, 83.dp, 129.dp, 83.dp, ink)
                arrow(81.dp, 52.dp, 81.dp, 44.dp, ink)
                arrow(81.dp, 114.dp, 81.dp, 122.dp, ink)
            }
            StageCaption("Photo profile", Modifier.at(12.dp, 20.dp), maxWidth = 126.dp)
            Column(Modifier.at(22.dp, 124.dp).width(140.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (photo) DiagramLabel("Photo OIS", when { !oisSupported -> "Unavailable"; profile.photoOisEnabled -> "On"; else -> "Off" },
                    active = photoOis, maxWidth = 140.dp)
                else DiagramLabel("Photo OIS", null, maxWidth = 140.dp)
                DiagramText("Optical, in the camera module", size = 9f, maxWidth = 140.dp)
            }
        }
        Box(Modifier.matchParentSize().alpha(if (photo) .35f else 1f)) {
            val live = !photo && video.liveStabilization
            val crop = video.settings.cropRatio.coerceIn(1f, 1.5f)
            DiagramDrawing {
                box(182.dp, 46.dp, 140.dp, 84.dp, radius = 3.dp, fill = Color(0xFF050505))
                val w = 140.dp / crop; val h = 84.dp / crop
                box(182.dp + (140.dp - w) / 2, 46.dp + (84.dp - h) / 2, w, h, radius = 0.dp,
                    color = if (live) CameraColors.Accent else Diagram.Line, dashed = true)
                polyline(listOf(186, 196, 206, 216, 226, 236, 246, 256, 266, 276, 286, 296, 306, 316)
                    .zip(listOf(100, 92, 104, 90, 100, 91, 104, 93, 101, 91, 100, 94, 99, 97)).map { (x, y) -> x.dp to y.dp },
                    color = Color(0xFF6A6A6A), stroke = 1.2.dp)
                if (live) polyline(listOf(186.dp to 97.dp, 222.dp to 93.dp, 252.dp to 99.dp, 284.dp to 97.dp, 316.dp to 95.dp),
                    color = CameraColors.Accent, stroke = 1.8.dp)
            }
            StageCaption("Video profile", Modifier.at(180.dp, 20.dp), maxWidth = 126.dp)
            if (live) Box(Modifier.at(258.dp, 56.dp)) { StageMono("{value1}x", active = true, arguments = mapOf("value1" to crop.format(2))) }
            if (photo) DiagramLabel("Live gyro stabilization", null, Modifier.at(182.dp, 134.dp), maxWidth = 150.dp)
            else if (live) Column(Modifier.at(182.dp, 134.dp).widthIn(max = 150.dp)) {
                StageCaption("Live gyro stabilization", maxWidth = 150.dp)
                StageMono("Crop {crop}x · {smoothing} s · {strength}%", active = true, arguments = mapOf(
                    "crop" to crop.format(2), "smoothing" to video.settings.smoothingSeconds.format(2),
                    "strength" to kotlin.math.round(video.settings.strength * 100).toInt()))
            } else DiagramLabel(if (video.captureGyro) "Capture gyro data" else "OIS",
                if (video.captureGyro || (video.ois && oisSupported)) "On" else "Off", Modifier.at(182.dp, 134.dp), maxWidth = 150.dp)
        }
    }
}

/** Device exposure range with the Unspektra AE limits in lime, on a logarithmic scale. */
@Composable
internal fun AeLimitsStage(device: ExposureBounds, selected: ExposureBounds) {
    val left = 64f; val right = 318f
    fun position(value: Double, min: Double, max: Double): Float =
        if (max <= min) left else (left + (right - left) * (ln(value / min) / ln(max / min))).toFloat()
    val isoLow = position(selected.minIso.toDouble(), device.minIso.toDouble(), device.maxIso.toDouble())
    val isoHigh = position(selected.maxIso.toDouble(), device.minIso.toDouble(), device.maxIso.toDouble())
    val fast = position(selected.minShutterNs.toDouble(), device.minShutterNs.toDouble(), device.maxShutterNs.toDouble())
    val slow = position(selected.maxShutterNs.toDouble(), device.minShutterNs.toDouble(), device.maxShutterNs.toDouble())
    LensStage(118.dp, "Device exposure range with the Unspektra AE limits highlighted.") {
        DiagramDrawing {
            listOf(44.dp, 94.dp).forEach { y ->
                line(left.dp, y, right.dp, y, Diagram.Line)
                line(left.dp, y - 4.dp, left.dp, y + 4.dp, Diagram.Hint, stroke = 1.dp)
                line(right.dp, y - 4.dp, right.dp, y + 4.dp, Diagram.Hint, stroke = 1.dp)
            }
            line(isoLow.dp, 44.dp, isoHigh.dp, 44.dp, CameraColors.Accent, stroke = 3.dp)
            line(fast.dp, 94.dp, slow.dp, 94.dp, CameraColors.Accent, stroke = 3.dp)
            circle(slow.dp, 94.dp, 5.dp, color = CameraColors.Accent)
        }
        StageCaption("Unspektra AE limits", Modifier.at(8.dp, 8.dp), maxWidth = 260.dp)
        DiagramText("ISO", Modifier.at(8.dp, 38.dp), size = 9f, maxWidth = 52.dp)
        DiagramText("Shutter", Modifier.at(8.dp, 88.dp), size = 9f, maxWidth = 52.dp)
        @Composable
        fun value(text: String, x: Float, y: Dp, active: Boolean, end: Boolean) {
            Box(Modifier.at(if (end) (x - 70f).dp else x.dp, y).width(70.dp),
                contentAlignment = if (end) Alignment.TopEnd else Alignment.TopStart) {
                StageMono(text, active = active, color = if (active) CameraColors.Accent else Diagram.Hint)
            }
        }
        // Limits close together share one label, so their values never run into each other.
        @Composable
        fun limits(low: String, high: String, from: Float, to: Float, y: Dp) {
            if (to - from >= 130f) {
                value(low, from, y, true, false)
                value(high, to, y, true, true)
            } else {
                val width = 170f
                val x = ((from + to) / 2 - width / 2).coerceIn(left - 4f, right + 4f - width)
                Box(Modifier.at(x.dp, y).width(width.dp), contentAlignment = Alignment.TopCenter) {
                    StageMono(if (low == high) low else "$low → $high", active = true, color = CameraColors.Accent)
                }
            }
        }
        limits("${selected.minIso}", "${selected.maxIso}", isoLow, isoHigh, 28.dp)
        value("${device.minIso}", left, 50.dp, false, false)
        value("${device.maxIso}", right, 50.dp, false, true)
        // Room above the lime thumb, so the values never touch it.
        limits("${formatShutter(selected.minShutterNs)} s", "${formatShutter(selected.maxShutterNs)} s", fast, slow, 72.dp)
        value("${formatShutter(device.minShutterNs)} s", left, 102.dp, false, false)
        value("${formatShutter(device.maxShutterNs)} s", right, 102.dp, false, true)
    }
}

/** Color filter mosaic for the chosen remosaic layout and color order. */
@Composable
internal fun CfaGridStage(value: CfaOverride) {
    val block = value.layout.blockSize
    val cells = maxOf(4, block * 2)
    val cell = 96f / cells
    val sensorOrder = value.order == CfaOrder.SENSOR
    val pattern = (if (sensorOrder) CfaOrder.RGGB else value.order).name
    LensStage(124.dp, "Sensor pixel grid for the chosen CFA layout and color order.") {
        DiagramDrawing {
            for (row in 0 until cells) for (column in 0 until cells) {
                box((12 + column * cell).dp, (20 + row * cell).dp, cell.dp, cell.dp, radius = 0.dp, color = Diagram.Line, stroke = 1.dp)
            }
            box(12.dp, 20.dp, (cell * block).dp, (cell * block).dp, radius = 0.dp, color = CameraColors.Accent, stroke = 1.5.dp)
            box(12.dp, 20.dp, (cell * block * 2).dp, (cell * block * 2).dp, radius = 0.dp, color = Diagram.Ink, stroke = 1.5.dp)
        }
        StageCaption("Sensor pixel grid", Modifier.at(12.dp, 6.dp), maxWidth = 120.dp)
        if (cell >= 11f) for (row in 0 until cells) for (column in 0 until cells) {
            val letter = pattern[((row / block) % 2) * 2 + (column / block) % 2].toString()
            Box(Modifier.at((12 + column * cell).dp, (20 + row * cell).dp).size(cell.dp), contentAlignment = Alignment.Center) {
                Text(letter, translate = false, color = if (sensorOrder) Diagram.Line else Color(0xFFBDBDBD),
                    fontSize = 7.5.sp, lineHeight = 9.sp, fontFamily = Diagram.Mono)
            }
        }
        Column(Modifier.at(124.dp, 24.dp).widthIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DiagramLabel("CFA / remosaic", value.layout.label, maxWidth = 176.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DiagramLabel("CFA color order", value.order.label, active = !sensorOrder, maxWidth = 176.dp)
            }
            DiagramText(if (block == 1) "Each pixel has its own color" else "Same-color pixel blocks are remosaicked",
                size = 9f, maxWidth = 196.dp)
        }
    }
}

/** What Auto Lens Discovery does, before it runs. */
@Composable
internal fun DiscoveryIntroStage() {
    LensStage(222.dp, "Discovery tests one lens at a time. RAW10 is preferred, with RAW_SENSOR as a fallback, and one profile is made per lens.") {
        DiagramDrawing {
            box(14.dp, 34.dp, 64.dp, 64.dp, radius = 16.dp)
            circle(32.dp, 52.dp, 11.dp, color = CameraColors.Accent)
            circle(60.dp, 52.dp, 11.dp, color = Diagram.Hint, stroke = 1.dp)
            circle(32.dp, 80.dp, 11.dp, color = Diagram.Hint, stroke = 1.dp)
            circle(60.dp, 80.dp, 11.dp, color = Diagram.Hint, stroke = 1.dp)
            val lime = CameraColors.Accent
            arrow(80.dp, 53.dp, 97.dp, 53.dp, lime)
            arrow(160.dp, 53.dp, 175.dp, 53.dp, lime)
            arrow(228.dp, 53.dp, 239.dp, 53.dp, lime)
            arrow(130.dp, 64.dp, 130.dp, 83.dp, Color(0xFF8C8C8C), stroke = 1.dp, dashed = true)
            line(184.dp, 97.dp, 203.dp, 97.dp, Color(0xFF8C8C8C), stroke = 1.dp, dashed = true)
            arrow(203.dp, 97.dp, 203.dp, 67.dp, Color(0xFF8C8C8C), stroke = 1.dp, dashed = true)
            line(100.dp, 151.dp, 217.dp, 151.dp, Color(0xFF8C8C8C), stroke = 1.dp)
        }
        StageCaption("What discovery does", Modifier.at(12.dp, 8.dp), maxWidth = 160.dp)
        DiagramLabel("Cameras", "one lens at a time", Modifier.at(14.dp, 104.dp), active = false, maxWidth = 84.dp)
        StageCaption("Preferred", Modifier.at(100.dp, 29.dp), maxWidth = 74.dp)
        StageBox(Modifier.at(100.dp, 42.dp), 60.dp, 22.dp, active = true) { StageMono("RAW10", active = true) }
        DiagramText("not verified", Modifier.at(136.dp, 66.dp), size = 8.5f, maxWidth = 64.dp)
        StageBox(Modifier.at(100.dp, 86.dp), 84.dp, 22.dp, dashed = true) { StageMono("RAW_SENSOR") }
        StageCaption("Fallback", Modifier.at(100.dp, 111.dp), maxWidth = 80.dp)
        StageBox(Modifier.at(178.dp, 42.dp), 50.dp, 22.dp) { StageMono("Verify", translate = true, mono = false) }
        StageBox(Modifier.at(242.dp, 42.dp), 80.dp, 22.dp, active = true) { StageMono("Lens profile", active = true, translate = true, mono = false) }
        DiagramText("Stops testing a lens once a stream is verified", Modifier.at(178.dp, 6.dp), size = 9.5f,
            color = Color(0xFFCFCFCF), maxWidth = 150.dp)
        DiagramLabel("Profile", "one per lens", Modifier.at(244.dp, 68.dp), active = false, maxWidth = 86.dp)
        StageBox(Modifier.at(100.dp, 140.dp), 117.dp, 22.dp, dashed = true) {
            StageMono("Maximum resolution", translate = true, mono = false, color = Color(0xFF8C8C8C))
        }
        DiagramLabel("Automatic profiles use", "normal or binned resolutions", Modifier.at(226.dp, 130.dp), active = false,
            maxWidth = 104.dp)
        DiagramText("Manual profiles are kept.", Modifier.at(14.dp, 194.dp), size = 10f, color = Color(0xFFCFCFCF), maxWidth = 140.dp)
        DiagramText("You can cancel and continue later.", Modifier.at(160.dp, 194.dp), size = 10f, color = Color(0xFFCFCFCF),
            maxWidth = 166.dp)
    }
}

internal val DiscoveryPhases = listOf(
    "Discovering cameras…", "Opening camera…", "Creating RAW and viewfinder session…",
    "Waiting for valid RAW viewfinder frames…", "Verifying RAW capture…", "Checking viewfinder after capture…", "Closing camera…",
)
internal const val DiscoveryRecoverPhase = "Waiting for the camera service to recover…"
internal const val DiscoveryCancelPhase = "Cancelling and releasing camera…"

/** The test steps of one RAW stream as a timeline. The current step is lime, finished steps are white. */
@Composable
internal fun DiscoveryPhaseFlow(phase: String) {
    val current = DiscoveryPhases.indexOf(phase)
    Column(Modifier.fillMaxWidth().border(1.dp, Diagram.StageBorder, RoundedCornerShape(14.dp))
        .background(Color(0x800A0A0A), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StageCaption("Phases", maxWidth = 200.dp)
            StageCaption("current", color = CameraColors.Accent, maxWidth = 200.dp)
        }
        Column(Modifier.fillMaxWidth().drawBehind {
            // Loop back for the next stream on the right edge.
            val x = size.width - 6.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            val color = Color(0xFF8C8C8C)
            drawLine(color, androidx.compose.ui.geometry.Offset(x, 10.dp.toPx()),
                androidx.compose.ui.geometry.Offset(x, size.height - 10.dp.toPx()), 1.dp.toPx(), pathEffect = dash)
        }.padding(end = 18.dp)) {
            DiscoveryPhases.forEachIndexed { index, step ->
                val state = when { current < 0 -> 0; index < current -> 1; index == current -> 2; else -> 0 }
                PhaseRow(step, state, first = index == 0, last = index == DiscoveryPhases.lastIndex)
            }
        }
        DiagramText("next stream", Modifier.align(Alignment.End), size = 8.5f, maxWidth = 160.dp)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PhaseSide("When needed", DiscoveryRecoverPhase, phase == DiscoveryRecoverPhase, Modifier.weight(1f))
            PhaseSide("After Cancel", DiscoveryCancelPhase, phase == DiscoveryCancelPhase, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhaseRow(text: String, state: Int, first: Boolean, last: Boolean) {
    val ink = when (state) { 2 -> CameraColors.Accent; 1 -> Color(0xFFCFCFCF); else -> Diagram.Hint }
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp).drawBehind {
        val x = 5.5.dp.toPx()
        val top = if (first) size.height / 2 else 0f
        val bottom = if (last) size.height / 2 else size.height
        drawLine(if (state == 1) Color(0xFFCFCFCF) else Color(0xFF3A3A3A), androidx.compose.ui.geometry.Offset(x, top),
            androidx.compose.ui.geometry.Offset(x, bottom), 1.dp.toPx())
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(11.dp).background(when (state) { 2 -> CameraColors.Accent; 1 -> Color(0xFFCFCFCF); else -> Color(0xFF0A0A0A) },
            CircleShape).border(1.5.dp, when (state) { 2 -> CameraColors.Accent; 1 -> Color(0xFFCFCFCF); else -> Diagram.Line }, CircleShape))
        Text(text, color = ink, fontSize = 10.5.sp, lineHeight = 14.sp,
            fontWeight = if (state == 2) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(vertical = 3.dp))
    }
}

@Composable
private fun PhaseSide(caption: String, text: String, active: Boolean, modifier: Modifier) {
    Column(modifier.widthIn(min = 120.dp).drawBehind {
        drawRoundRect(if (active) CameraColors.Accent else Diagram.Line, cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    }.padding(horizontal = 7.dp, vertical = 5.dp)) {
        StageCaption(caption, maxWidth = 240.dp)
        Text(text, color = if (active) CameraColors.Accent else Color(0xFFCFCFCF), fontSize = 9.5.sp, lineHeight = 12.5.sp)
    }
}

/** Lens switcher of the camera screen for the listed profiles, numbered in list order. */
@Composable
internal fun LensSwitcherStage(profiles: List<LensSwitcherEntry>) {
    val shown = profiles.take(5)
    val more = profiles.size - shown.size
    val step = if (shown.size + (if (more > 0) 1 else 0) <= 4) 42f else 34f
    val chip = if (step == 42f) 36f else 30f
    val current = shown.indexOfFirst { it.current }
    val unavailable = shown.indexOfFirst { !it.available }
    val unticked = shown.indexOfFirst { !it.enabled && it.available }
    LensStage(136.dp, "Lens switcher on the camera screen. Profiles appear left to right in list order.") {
        DiagramDrawing {
            box(20.dp, (-30).dp, 180.dp, 162.dp, radius = 24.dp)
            box(28.dp, (-20).dp, 164.dp, 64.dp, radius = 3.dp, color = Color(0xFF3A3A3A), stroke = 1.dp, dashed = true)
            circle(110.dp, 108.dp, 14.dp)
            fun chipCenter(index: Int) = (29f + step * index + chip / 2f)
            if (current >= 0) {
                val x = chipCenter(current)
                callout(x.dp, 54.dp, x.dp, 16.dp); line(x.dp, 16.dp, 208.dp, 16.dp, CameraColors.Accent.copy(alpha = .7f), stroke = 1.dp)
            }
            if (unavailable >= 0) {
                val x = chipCenter(unavailable)
                callout(x.dp, 54.dp, x.dp, 48.dp); line(x.dp, 48.dp, 208.dp, 48.dp, CameraColors.Accent.copy(alpha = .7f), stroke = 1.dp)
            }
            if (unticked >= 0) {
                val x = chipCenter(unticked)
                callout(x.dp, 76.dp, x.dp, 104.dp); line(x.dp, 104.dp, 208.dp, 104.dp, CameraColors.Accent.copy(alpha = .7f), stroke = 1.dp)
            }
        }
        shown.forEachIndexed { index, entry ->
            val x = (29f + step * index).dp
            val off = !entry.enabled || !entry.available
            Box(Modifier.at(x, 54.dp).size(chip.dp, 22.dp)
                .then(if (entry.current) Modifier.background(CameraColors.Accent.copy(alpha = .08f), RoundedCornerShape(11.dp)) else Modifier)
                .drawBehind {
                    drawRoundRect(when { entry.current -> CameraColors.Accent; off -> Diagram.Line; else -> Color(0xFFCFCFCF) },
                        cornerRadius = CornerRadius(11.dp.toPx()), style = Stroke(1.dp.toPx(),
                            pathEffect = if (off) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null))
                }, contentAlignment = Alignment.Center) {
                Text(entry.label, translate = false, color = when { entry.current -> CameraColors.Accent; off -> Color(0xFF8C8C8C)
                    else -> Diagram.Ink }, fontSize = 8.5.sp, lineHeight = 10.sp, fontFamily = Diagram.Mono, maxLines = 1,
                    textDecoration = if (!entry.enabled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            }
        }
        if (more > 0) Box(Modifier.at((29f + step * shown.size).dp, 54.dp).size(chip.dp, 22.dp), contentAlignment = Alignment.Center) {
            Text("+$more", translate = false, color = Diagram.Hint, fontSize = 8.5.sp, fontFamily = Diagram.Mono)
        }
        DiagramText("Left to right = list order", Modifier.at(30.dp, 94.dp), size = 9f, maxWidth = 64.dp)
        @Composable
        fun note(entry: LensSwitcherEntry, index: Int, y: Dp, value: String, active: Boolean) {
            Column(Modifier.at(214.dp, y).width(116.dp)) {
                Text("{number} · {name}", arguments = mapOf("number" to index + 1, "name" to entry.name), color = Diagram.Hint,
                    fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, color = if (active) CameraColors.Accent else Color(0xFFCFCFCF), fontSize = 10.5.sp, lineHeight = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (current >= 0) note(shown[current], current, 4.dp, if (shown[current].startup) "Current · startup" else "Current", true)
        if (unavailable >= 0) note(shown[unavailable], unavailable, 42.dp, "Lens unavailable", false)
        if (unticked >= 0) note(shown[unticked], unticked, 92.dp, "Box unticked, not in switcher", false)
        if (shown.isEmpty()) DiagramText("No lens profiles", Modifier.at(214.dp, 50.dp), size = 10f, maxWidth = 116.dp)
    }
}

internal data class LensSwitcherEntry(val name: String, val label: String, val enabled: Boolean, val available: Boolean,
    val current: Boolean, val startup: Boolean)
