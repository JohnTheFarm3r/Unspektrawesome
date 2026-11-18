package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.RawFrameRateReport
import com.unspektrawesome.camera.RawFrameRateStore
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal typealias LensFrameRateCheck = suspend (CameraDescriptor, LensProfile) -> RawFrameRateReport

@Composable
internal fun LensCaptureControls(camera: CameraDescriptor, profile: LensProfile, rail: LensRailPreview,
    onCheck: LensFrameRateCheck?, isActive: Boolean = true, onChange: (LensProfile) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { RawFrameRateStore(context) }
    var reportRevision by remember { mutableIntStateOf(0) }
    val report = remember(camera, profile, reportRevision) { store.load(camera, profile) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var showResults by remember(profile.id, camera.route.routeId) { mutableStateOf(false) }
    val active by rememberUpdatedState(isActive)
    val scope = rememberCoroutineScope()
    LaunchedEffect(isActive) {
        if (!isActive) {
            showResults = false
            failed = false
            scope.coroutineContext.cancelChildren()
        }
    }
    val capture = profile.capture
    var fpsExpanded by remember { mutableStateOf(false) }
    var ispExpanded by remember { mutableStateOf(false) }
    var labelExpanded by remember { mutableStateOf(false) }
    val renderer = LocalAppLanguage.current.renderer
    val readings = report?.readings?.takeIf { showResults }.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        LensSectionHeading(3, "Frame rate")
        FrameRateStage(capture, readings)
        DiagramDropdownField("ISP COMPANION SURFACE", capture.ispSurface.label, null, ispExpanded, enabled = !checking,
            number = 1, onExpand = { if (!checking) ispExpanded = true }, onDismiss = { ispExpanded = false }) {
            IspPreviewSurface.entries.forEach { mode ->
                DiagramMenuItem(mode.label, selected = mode == capture.ispSurface) {
                    onChange(profile.copy(capture = capture.copy(ispSurface = mode))); ispExpanded = false
                }
            }
        }
        DiagramDropdownField("RAW FRAME RATE",
            capture.frameRate?.let { fps -> renderer.format("{fps} fps", mapOf("fps" to fps)) } ?: "Lens default", null,
            fpsExpanded, enabled = !checking, translateValue = capture.frameRate == null, number = 2,
            onExpand = { if (!checking) fpsExpanded = true }, onDismiss = { fpsExpanded = false }) {
            DiagramMenuItem("Lens default", selected = capture.frameRate == null) {
                onChange(profile.copy(capture = capture.copy(frameRate = null))); fpsExpanded = false
            }
            (LensFrameRates.advertised(camera) + report?.supported.orEmpty()).distinct().sorted().forEach { rate ->
                DiagramMenuItem(renderer.format("{fps} fps", mapOf("fps" to rate)), selected = capture.frameRate == rate,
                    translate = false) {
                    onChange(profile.copy(capture = capture.copy(frameRate = rate))); fpsExpanded = false
                }
            }
        }
        SettingsFieldPair(
            first = { modifier ->
                ProfileNumberField("Custom requested FPS", capture.frameRate?.toString().orEmpty(), !checking, modifier,
                    valid = { it.toIntOrNull() in 1..1000 }) { value ->
                    onChange(profile.copy(capture = capture.copy(frameRate = value.toInt())))
                }
            },
            second = { modifier ->
                ProfileNumberField("RAW request batch size", capture.requestBatchSize.toString(), !checking, modifier,
                    number = 3, valid = { it.toIntOrNull() in 1..32 }) { value ->
                    onChange(profile.copy(capture = capture.copy(requestBatchSize = value.toInt())))
                }
            },
        )
        DiagramHint("Keep 1 unless the camera mode requires grouped RAW requests.")
        if (onCheck != null) DiagramButtonRow {
            DiagramButton(if (checking) "Checking RAW frame rates…" else "Check frame rates",
                Modifier.testTag("Check frame rates"), enabled = isActive && !checking, symbol = SettingsSymbol.Update,
                accent = true, onClick = {
                    scope.launch {
                        checking = true
                        failed = false
                        showResults = false
                        try {
                            onCheck(camera, profile)
                            reportRevision++
                            showResults = active
                        }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { failed = true }
                        finally { checking = false }
                    }
                })
        }
        if (failed) DiagramHint("Frame rate check failed. Close other camera apps and try again.", warning = true)
        if (readings.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            readings.forEach { reading ->
                val measured = reading.measured
                if (measured != null) Text("Requested {requested} fps: measured {measured} fps",
                    arguments = mapOf("requested" to reading.requested, "measured" to String.format(Locale.ROOT, "%.1f", measured)),
                    color = if (reading.supported) CameraColors.Accent else Diagram.Hint, fontSize = 11.sp, lineHeight = 16.sp,
                    fontFamily = Diagram.Mono)
                else Text("Requested {requested} fps: unavailable", arguments = mapOf("requested" to reading.requested),
                    color = Diagram.Hint, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = Diagram.Mono)
            }
        }

        LensSectionHeading(4, "Frame averaging")
        FrameAveragingStage(capture)
        SettingsFieldPair(
            first = { modifier ->
                ProfileNumberField("Source frames per output", capture.frameInterval.toString(), !checking, modifier,
                    number = 1, valid = { it.toIntOrNull() in 1..120 }) { value ->
                    val interval = value.toInt()
                    onChange(profile.copy(capture = capture.copy(frameInterval = interval, averageFrames = minOf(capture.averageFrames, interval))))
                }
            },
            second = { modifier ->
                ProfileNumberField("Frames to average", capture.averageFrames.toString(), !checking, modifier,
                    number = 2, valid = { it.toIntOrNull() in 1..capture.frameInterval }) { value ->
                    onChange(profile.copy(capture = capture.copy(averageFrames = value.toInt())))
                }
            },
        )
        DiagramHint("Frames to average goes up to the first value. Lowering the first value lowers it too.")

        LensSectionHeading(5, "Multi-frame merge")
        MergeStage(capture)
        DiagramSwitchRow("Multi-frame merge", capture.mergeEnabled, enabled = !checking) { enabled ->
            onChange(profile.copy(capture = capture.copy(mergeEnabled = enabled)))
        }
        val mergeEnabled = !checking && capture.mergeEnabled
        MergeSliderSetting("Frames to merge", "{frames} frames", mapOf("frames" to capture.mergeFrames),
            capture.mergeFrames.toFloat(), mergeEnabled, number = 1,
            valueRange = LensCaptureSettings.MERGE_FRAMES_MIN.toFloat()..LensCaptureSettings.MERGE_FRAMES_MAX.toFloat(), valueStep = 1f,
            endpoints = "${LensCaptureSettings.MERGE_FRAMES_MIN}" to "${LensCaptureSettings.MERGE_FRAMES_MAX}", translateEndpoints = false,
            hint = "More frames lower noise further but take longer to merge.") { value ->
            onChange(profile.copy(capture = capture.copy(mergeFrames = value.roundToInt()
                .coerceIn(LensCaptureSettings.MERGE_FRAMES_MIN, LensCaptureSettings.MERGE_FRAMES_MAX))))
        }
        MergeSliderSetting("Motion sensitivity", "{value1}%", mapOf("value1" to (capture.mergeMotion * 100).roundToInt()),
            capture.mergeMotion, mergeEnabled, number = 2, endpoints = "Lower noise" to "Less ghosting",
            hint = "Low values blend more frames for less noise, but moving subjects can ghost.") { value ->
            onChange(profile.copy(capture = capture.copy(mergeMotion = value.coerceIn(0f, 1f))))
        }

        // The look controls sit right under the drawing, one at a time, so the drawing stays in view while they change.
        var lookTab by rememberSaveable(profile.id) { mutableIntStateOf(0) }
        val search = LocalSettingsSearch.current
        LaunchedEffect(search?.pending?.title) {
            LENS_BUTTON_LOOK_TERMS.indexOf(search?.pending?.title).takeIf { it >= 0 }?.let { lookTab = it }
        }
        LensSectionHeading(6, "Lens button")
        // Search brings the drawing and the controls into view together.
        Column(LENS_BUTTON_LOOK_TERMS.fold(Modifier.fillMaxWidth() as Modifier) { modifier, term ->
            modifier.then(settingsSearchTarget(term))
        }) {
            LensButtonStage(profile, camera, rail, focus = LENS_BUTTON_LOOK_NUMBERS[lookTab])
            LensButtonLookPanel(profile, camera, lookTab, { lookTab = it }, onChange)
        }
        DiagramSwitchRow("Separate lens button", profile.button.ownButton, number = 3,
            detail = "Adds a button for this profile next to its lens. Holding either button still lists every profile of the lens.") {
            onChange(profile.copy(button = profile.button.copy(ownButton = it)))
        }
        ProfileNumberField("Focal length factor", profile.focalLength.factor.toString(), !checking, Modifier.fillMaxWidth(),
            number = 1, valid = { it.toFloatOrNull()?.let { factor -> factor.isFinite() && factor in .1f..20f } == true }) { value ->
            onChange(profile.copy(focalLength = profile.focalLength.copy(factor = value.toFloat())))
        }
        DiagramDropdownField("LENS BUTTON LABEL", profile.focalLength.label.label, null, labelExpanded, number = 2,
            onExpand = { labelExpanded = true }, onDismiss = { labelExpanded = false }) {
            LensFocalLengthLabel.entries.forEach { label ->
                DiagramMenuItem(label.label, selected = label == profile.focalLength.label) {
                    onChange(profile.copy(focalLength = profile.focalLength.copy(label = label))); labelExpanded = false
                }
            }
        }
        DiagramButtonRow {
            DiagramButton("Reset button look", enabled = profile.button.customLook, symbol = SettingsSymbol.Reset) {
                onChange(profile.copy(button = LensButtonStyle(ownButton = profile.button.ownButton)))
            }
        }
    }
}

/** Settings search terms of the look choices, in their order, which lead search to the matching choice. */
private val LENS_BUTTON_LOOK_TERMS = listOf("Button label", "Text size", "Button size", "Shape", "Spacing")

/** Number of each look choice in the lens button drawing. */
private val LENS_BUTTON_LOOK_NUMBERS = listOf(4, 5, 6, 7, 8)

/**
 * Label, text size, button size, shape and spacing of the lens button. A row of choices picks the one control
 * shown, which keeps the panel short enough to use with the drawing above it in view.
 */
@Composable
private fun LensButtonLookPanel(profile: LensProfile, camera: CameraDescriptor, tab: Int, onTab: (Int) -> Unit,
    onChange: (LensProfile) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    val style = profile.button
    fun change(next: LensButtonStyle) { if (next != style) onChange(profile.copy(button = next)) }
    DiagramSegmented(listOf("Label", "Text", "Size", "Shape", "Spacing"), tab, Modifier.testTag("Lens button look"),
        onSelect = onTab)
    Column(Modifier.fillMaxWidth().heightIn(min = 112.dp)) {
        when (tab) {
            0 -> {
                var label by remember(profile.id) { mutableStateOf(style.label) }
                LaunchedEffect(style.label) { if (label.trim() != style.label.trim()) label = style.label }
                SensorArtField(label, { value ->
                    label = LensButtonStyle.cleanLabel(value)
                    change(style.copy(label = label))
                }, "Button label", Modifier.testTag("Lens button label"), number = 4, mono = false,
                    placeholder = renderer.text(profile.focalLength.label(camera)))
                DiagramHint("Replaces the whole label. Leave it empty to show the focal length.")
            }
            1 -> {
                DiagramSliderRow("Text size", style.textSize, "${style.textSize.roundToInt()}", { value ->
                    change(style.copy(textSize = value.roundToInt().toFloat()
                        .coerceIn(LensButtonStyle.MIN_TEXT_SIZE, LensButtonStyle.MAX_TEXT_SIZE)))
                }, range = LensButtonStyle.MIN_TEXT_SIZE..LensButtonStyle.MAX_TEXT_SIZE, step = 1f, number = 5,
                    onReset = { change(style.copy(textSize = LensButtonStyle.DEFAULT_TEXT_SIZE)) })
                DiagramHint("Long labels shrink to fit the button.")
            }
            2 -> DiagramSliderRow("Button size", style.size, "${style.size.roundToInt()}", { value ->
                change(style.copy(size = ((value / 2f).roundToInt() * 2f)
                    .coerceIn(LensButtonStyle.MIN_SIZE, LensButtonStyle.MAX_SIZE)))
            }, range = LensButtonStyle.MIN_SIZE..LensButtonStyle.MAX_SIZE, step = 2f, number = 6,
                onReset = { change(style.copy(size = LensButtonStyle.DEFAULT_SIZE)) })
            3 -> {
                DiagramSegmented(LensButtonShape.entries.map { it.label }, style.shape.ordinal) {
                    change(style.copy(shape = LensButtonShape.entries[it]))
                }
                DiagramHint("A pill grows wider to fit its label.")
            }
            else -> {
                DiagramSwitchRow("Automatic spacing", style.spacing == null, number = 8,
                    detail = "Buttons overlap slightly and move closer when the row is full.") { automatic ->
                    change(style.copy(spacing = if (automatic) null else 0f))
                }
                style.spacing?.let { spacing ->
                    DiagramSliderRow("Spacing", spacing, spacingText(spacing), { value ->
                        change(style.copy(spacing = value.roundToInt().toFloat()
                            .coerceIn(LensButtonStyle.MIN_SPACING, LensButtonStyle.MAX_SPACING)))
                    }, range = LensButtonStyle.MIN_SPACING..LensButtonStyle.MAX_SPACING, step = 1f,
                        startLabel = "Overlap", endLabel = "Apart", onReset = { change(style.copy(spacing = 0f)) })
                    DiagramHint("Two neighbouring buttons use the larger of their spacings.")
                }
            }
        }
    }
}

/** The camera, its grouped RAW requests and the requested stream, plus the measured rates after a check. */
@Composable
private fun FrameRateStage(capture: LensCaptureSettings, readings: List<com.unspektrawesome.camera.RawFrameRateReading>) {
    val renderer = LocalAppLanguage.current.renderer
    val requests = capture.requestBatchSize.coerceIn(1, 4)
    val requestEnd = 86 + requests * 18
    val ispOn = capture.ispSurface != IspPreviewSurface.OFF
    // Centre line of the RAW request row, top of the ISP branch, and the first check result row.
    val streamY = 74
    val ispY = 112
    val branchY = ispY + 35
    val resultsTop = 216
    val height = if (readings.isEmpty()) 182 else resultsTop + readings.size * 12 + 8
    val widest = readings.maxOfOrNull { it.requested }?.coerceAtLeast(1) ?: 1
    SensorArtStage(height.dp, "Frame rate · what the camera is asked for") {
        DiagramDrawing {
            arrow(72.dp, streamY.dp, 83.dp, streamY.dp, CameraColors.Accent)
            repeat(requests) { index ->
                val x = (86 + index * 18).dp
                box(x, (streamY - 7).dp, 14.dp, 14.dp, 2.dp, Diagram.Ink, 1.2.dp, fill = SensorArt.Fill)
                dot(x + 7.dp, streamY.dp, 2.dp)
            }
            arrow(requestEnd.dp, streamY.dp, 157.dp, streamY.dp, CameraColors.Accent)
            box(160.dp, (streamY - 16).dp, 168.dp, 32.dp, 7.dp, CameraColors.Accent, fill = SensorArt.Fill)
            repeat(9) { index -> rect((168 + index * 9).dp, (streamY - 7).dp, 3.dp, 14.dp, CameraColors.Accent.copy(alpha = .85f)) }
            line(40.dp, (streamY + 12).dp, 40.dp, branchY.dp, if (ispOn) SensorArt.Dim else Diagram.Line, dashed = true)
            arrow(40.dp, branchY.dp, 88.dp, branchY.dp, if (ispOn) SensorArt.Dim else Diagram.Line, dashed = true)
            if (readings.isNotEmpty()) {
                line(8.dp, 186.dp, 328.dp, 186.dp, Diagram.StageBorder, 1.dp)
                readings.forEachIndexed { index, reading ->
                    val y = (resultsTop + 2 + index * 12).dp
                    val width = (210f * reading.requested / widest).coerceAtLeast(16f).dp
                    val measured = reading.measured
                    box(52.dp, y, width, 8.dp, 2.dp, Diagram.Line, 1.dp, dashed = measured == null)
                    if (measured != null) rect(52.dp, y, width * (measured / reading.requested).toFloat().coerceIn(0f, 1f), 8.dp,
                        if (reading.supported) CameraColors.Accent else Diagram.Hint)
                }
            }
        }
        DiagramLabel("Frame rate · what the camera is asked for", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 300.dp)
        DiagramNode("Camera", Modifier.at(8.dp, (streamY - 12).dp), width = 64.dp, state = DiagramNodeState.Done)
        DiagramText(renderer.format("Batch {count}", mapOf("count" to capture.requestBatchSize)), Modifier.at(86.dp, 36.dp),
            translate = false, size = 8.5f, maxWidth = 70.dp)
        Row(Modifier.at(86.dp, (streamY + 10).dp).widthIn(max = 72.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DiagramNumber(3)
            DiagramText("requests", size = 8.5f, maxWidth = 50.dp)
        }
        DiagramText("RAW stream", Modifier.at(160.dp, 36.dp), size = 8.5f, maxWidth = 150.dp)
        Box(Modifier.at(246.dp, (streamY - 16).dp).size(76.dp, 32.dp), contentAlignment = Alignment.CenterEnd) {
            DiagramText(capture.frameRate?.let { renderer.format("{fps} fps", mapOf("fps" to it)) } ?: renderer.text("Lens default"),
                color = CameraColors.Accent, translate = false, mono = true, size = 10f, maxWidth = 76.dp, align = TextAlign.End)
        }
        SensorArtMarker(2, 316.dp, (streamY - 24).dp)
        Row(Modifier.at(94.dp, ispY.dp).width(234.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DiagramText("ISP companion surface", Modifier.weight(1f), size = 8.5f, maxWidth = 210.dp)
            DiagramNumber(1)
        }
        FlowRow(Modifier.at(92.dp, (ispY + 26).dp).width(236.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IspPreviewSurface.entries.forEach { mode ->
                SensorArtTag(mode.label, active = mode == capture.ispSurface, dashed = mode == IspPreviewSurface.OFF)
            }
        }
        DiagramText("ISP output beside RAW", Modifier.at(8.dp, (branchY + 5).dp), size = 8.5f, maxWidth = 76.dp,
            color = if (ispOn) Diagram.Hint else SensorArt.Detail)
        if (readings.isNotEmpty()) {
            DiagramLabel("Check results · requested vs measured", modifier = Modifier.at(8.dp, 190.dp), maxWidth = 320.dp)
            readings.forEachIndexed { index, reading ->
                val y = (resultsTop + index * 12).dp
                val width = (210f * reading.requested / widest).coerceAtLeast(16f)
                val measured = reading.measured
                DiagramText(renderer.format("{fps} fps", mapOf("fps" to reading.requested)), Modifier.at(8.dp, y), translate = false,
                    mono = true, size = 8.5f, maxWidth = 44.dp, maxLines = 1)
                DiagramText(if (measured != null) renderer.format("{fps} fps", mapOf("fps" to String.format(Locale.ROOT, "%.1f", measured)))
                    else renderer.text("unavailable"), Modifier.at((52 + width + 6).coerceAtMost(266f).dp, y), translate = false, mono = true,
                    size = 8.5f, maxWidth = 62.dp, maxLines = 1, color = if (reading.supported) CameraColors.Accent else Diagram.Hint)
            }
        }
    }
}

/** Groups of source frames: the first frames of each group are averaged into one output frame. */
@Composable
private fun FrameAveragingStage(capture: LensCaptureSettings) {
    val renderer = LocalAppLanguage.current.renderer
    val shown = capture.frameInterval.coerceAtMost(6)
    val averaged = capture.averageFrames.coerceIn(1, shown)
    val hidden = capture.frameInterval - shown
    // The two groups stay between x 8 and 200, left of the labels, and far enough apart for both output boxes.
    val outputWidth = 44f
    val gapBase = if (hidden > 0) 30f else 12f
    val extra = if (hidden > 0) 30f else 0f
    fun stepFor(start: Float) = minOf(23f, (200f - start - gapBase - extra + 6f) / (2 * shown))
    val firstCenter = 8f + outputWidth / 2
    var step = stepFor(8f)
    val start = maxOf(8f, firstCenter - (averaged * step - 3f) / 2)
    step = stepFor(start)
    val boxWidth = step - 3f
    val groupWidth = shown * step - 3f
    val gap = maxOf(gapBase, outputWidth + 6f - groupWidth)
    val groups = listOf(start, start + groupWidth + gap)
    SensorArtStage(120.dp, "Frames in · frames out") {
        DiagramDrawing {
            groups.forEach { groupStart ->
                repeat(shown) { index ->
                    val use = index < averaged
                    box((groupStart + index * step).dp, 40.dp, boxWidth.dp, 16.dp, 2.dp, if (use) Diagram.Ink else Diagram.Line, 1.2.dp,
                        dashed = !use, fill = SensorArt.Fill)
                }
                val end = groupStart + averaged * step - 3f
                polyline(listOf(groupStart.dp to 61.dp, groupStart.dp to 65.dp, end.dp to 65.dp, end.dp to 61.dp), CameraColors.Accent)
                val center = (groupStart + end) / 2
                arrow(center.dp, 65.dp, center.dp, 77.dp, CameraColors.Accent)
                box((center - outputWidth / 2).dp, 80.dp, outputWidth.dp, 18.dp, 2.dp, CameraColors.Accent)
            }
        }
        DiagramLabel("Frames in · frames out", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 190.dp)
        DiagramText("time →", Modifier.at(8.dp, 103.dp), size = 8.5f, maxWidth = 90.dp, maxLines = 1)
        groups.forEach { groupStart ->
            val center = groupStart + (averaged * step - 3f) / 2
            DiagramText("output", Modifier.at((center - outputWidth / 2).dp, 83.dp).width(outputWidth.dp), color = CameraColors.Accent,
                mono = true, size = 8.5f, align = TextAlign.Center, maxWidth = outputWidth.dp, maxLines = 1)
            if (hidden > 0) DiagramText(renderer.format("+{count}", mapOf("count" to hidden)), Modifier.at((groupStart + groupWidth + 3).dp, 42.dp),
                translate = false, mono = true, size = 8.5f, maxWidth = 26.dp, maxLines = 1)
        }
        Column(Modifier.at(208.dp, 26.dp).width(120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagramNumber(1)
                DiagramLabel("Source frames per output", "${capture.frameInterval}", maxWidth = 96.dp, valueTranslate = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagramNumber(2)
                DiagramLabel("Frames to average", "${capture.averageFrames}", maxWidth = 96.dp, valueTranslate = false)
            }
        }
    }
}

/** Recent frames merged into one photo, and where motion sensitivity sits between noise and ghosting. */
@Composable
private fun MergeStage(capture: LensCaptureSettings) {
    val renderer = LocalAppLanguage.current.renderer
    val on = capture.mergeEnabled
    val ink = if (on) CameraColors.Accent else Diagram.Line
    val frames = capture.mergeFrames.coerceAtMost(8)
    SensorArtStage(118.dp, "Multi-frame merge · photos") {
        DiagramDrawing {
            repeat(frames) { index ->
                box((12 + index * 3).dp, (36 + index * 3).dp, 40.dp, 28.dp, 2.dp, if (on) Diagram.Ink else Diagram.Line, 1.2.dp,
                    dashed = !on, fill = SensorArt.Fill)
            }
            val right = 12f + (frames - 1) * 3 + 40
            val middle = 36f + (frames - 1) * 3 / 2f + 14
            arrow((right + 4).dp, middle.dp, 99.dp, middle.dp, ink, dashed = !on)
            circle(114.dp, middle.dp, 13.dp, ink, dashed = !on)
            arrow(128.dp, middle.dp, 143.dp, middle.dp, ink, dashed = !on)
            box(146.dp, (middle - 16).dp, 44.dp, 32.dp, 3.dp, ink, dashed = !on)
            polyline(listOf(152.dp to (middle + 10).dp, 160.dp to middle.dp, 166.dp to (middle + 6).dp, 172.dp to (middle - 2).dp,
                184.dp to (middle + 10).dp), ink, 1.2.dp)
            line(206.dp, 70.dp, 324.dp, 70.dp, Diagram.Line)
            dot((206 + 118 * capture.mergeMotion).dp, 70.dp, 5.dp, if (on) CameraColors.Accent else Diagram.Hint)
        }
        DiagramLabel("Multi-frame merge · photos", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 190.dp)
        Row(Modifier.at(12.dp, 92.dp).widthIn(max = 130.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DiagramNumber(1, active = on)
            DiagramText(renderer.format("{frames} frames", mapOf("frames" to capture.mergeFrames)),
                color = if (on) CameraColors.Accent else Diagram.Hint, translate = false, mono = true, size = 9f, maxWidth = 100.dp)
        }
        DiagramText("1 photo", Modifier.at(146.dp, 92.dp), size = 9f, maxWidth = 56.dp)
        Row(Modifier.at(206.dp, 36.dp).width(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DiagramText(renderer.format("Motion sensitivity {value1}%",
                mapOf("value1" to (capture.mergeMotion * 100).roundToInt())), Modifier.weight(1f), translate = false,
                size = 9f, maxWidth = 98.dp, color = if (on) Diagram.Ink else Diagram.Hint)
            DiagramNumber(2, active = on)
        }
        DiagramText("Lower noise", Modifier.at(206.dp, 80.dp), size = 8.5f, maxWidth = 58.dp)
        DiagramText("Less ghosting", Modifier.at(266.dp, 80.dp).width(58.dp), size = 8.5f, maxWidth = 58.dp, align = TextAlign.End)
    }
}

@Composable
private fun MergeSliderSetting(label: String, valueText: String, valueArguments: Map<String, Any>, value: Float, enabled: Boolean,
    number: Int, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, valueStep: Float? = null,
    endpoints: Pair<String, String>? = null, translateEndpoints: Boolean = true, hint: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiagramNumber(number, active = enabled)
            Text(label, color = if (enabled) CameraColors.Text else Diagram.Hint, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.weight(1f))
            DiagramValue(valueText, arguments = valueArguments, translate = true,
                color = if (enabled) CameraColors.Accent else SensorArt.Detail)
        }
        CameraCompactSlider(value, onChange, enabled = enabled, thumbSize = 14.dp, requireThumb = false,
            valueRange = valueRange, valueStep = valueStep, minimumHeight = 40.dp,
            modifier = Modifier.fillMaxWidth().languageSemantics { language -> contentDescription = language.text(label) })
        if (endpoints != null) Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .7f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(endpoints.first, translate = translateEndpoints, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp,
                modifier = Modifier.weight(1f))
            Text(endpoints.second, translate = translateEndpoints, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Text(hint, color = SensorArt.Detail, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ProfileNumberField(label: String, value: String, enabled: Boolean, modifier: Modifier, number: Int? = null,
    valid: (String) -> Boolean, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (!valid(text) || text.toDoubleOrNull() != value.toDoubleOrNull()) text = value
    }
    SensorArtField(text, {
        text = it
        if (valid(it)) onChange(it)
    }, label, modifier.testTag(label), enabled = enabled, number = number,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
