package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraState
import com.unspektrawesome.spektra.SpektraStateRepository
import java.util.Locale
import kotlin.math.roundToInt

private val TrayAccent = CameraColors.Accent
private val TrayMuted = CameraColors.Muted

private enum class SpektraPage(val label: String) {
    FILM("Film"), PRINT("Print"), CHROMA_DENOISE("Chroma Denoise"), GRAIN("Grain"), HALATION("Halation"),
    DIFFUSION("Diffusion"), SCANNER("Scanner"), OPTICS("Optics"), OUTPUT("Output"), ADVANCED("Couplers"),
}

private val processLabels = listOf("Print simulation", "Scan negative", "Process negative")
private val renderOutputLabels = listOf(
    "Final preview", "Film density CMY", "Film density CMY + grain",
    "Film log raw", "Print log raw", "Print density CMY",
)
private val rgbToRawLabels = listOf("Hanatos 2025", "Mallett 2019", "Hanatos 2026")
private val autoExposureLabels = listOf("Center weighted", "Median")
private val pushPullLabels = listOf("Standard", "Experimental")
private val printTimingLabels = listOf("Filtered enlarger", "APD printer density")
private val grainModelLabels = listOf("Preview", "Production", "Grain Synthesis")
private val filmFormatLabels = listOf(
    "Standard 8", "Super 8", "Standard 16", "Super 16",
    "Standard 35", "Super 35", "Standard 65", "IMAX 70",
)
private val diffusionFamilyLabels = listOf(
    "Glimmerglass", "Black Pro-Mist", "Pro-Mist", "CineBloom",
)

private val SPEKTRA_COLOR_SPACE_LABELS = listOf(
    "ARRI LogC4",
    "ARRI LogC3 EI800",
    "BMDFilm WideGamut Gen5",
    "DaVinci Intermediate WideGamut",
    "RED Log3G10 REDWideGamutRGB",
    "Sony S-Log3 S-Gamut3",
    "Sony S-Log3 S-Gamut3.Cine",
    "Canon Log2 CinemaGamut D55",
    "Canon Log3 CinemaGamut D55",
    "Panasonic V-Log V-Gamut",
    "ACES2065-1",
    "ACEScg",
    "ACEScct",
    "ACEScc",
    "Linear Rec.2020",
    "Linear Rec.709",
    "Linear P3-D65",
    "sRGB",
    "Display P3",
    "ProPhoto RGB",
    "Adobe RGB (1998)",
    "DCI-P3",
    "P3-D65 Gamma 2.2",
    "P3-D65 Gamma 2.6",
    "Rec.709 Gamma 2.2",
    "Rec.709 Gamma 2.4"
)
private val SPEKTRA_OUTPUT_ROLE_LABELS = listOf("Display Out SDR", "Display Out HDR", "Scene Handoff")
private val SPEKTRA_HDR_PRESET_LABELS = listOf("PQ 1000", "PQ 4000", "HLG 1000", "Custom")
private val SPEKTRA_HDR_TRANSFER_LABELS = listOf("Rec.2100 ST2084 (PQ)", "Rec.2100 HLG")
private val SPEKTRA_HDR_TONE_MAPPING_LABELS = listOf("Soft Rolloff", "Hard Clip")

@Composable
internal fun SpektraAdjustmentTray(
    state: SpektraState,
    repository: SpektraStateRepository,
    onClose: () -> Unit,
    landscape: Boolean,
    menuRotationDegrees: Float,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = onClose,
    onSaveBounds: (Rect) -> Unit = {},
    sensorAeActive: Boolean = false,
    onContentHeightChanged: (Dp) -> Unit = {},
    videoMode: Boolean = false,
    manualExposureOnly: Boolean = false,
) {
    var savedPage by rememberSaveable { mutableStateOf(SpektraPage.FILM) }
    val pages = SpektraPage.entries.filterNot {
        state.params.isMonochromeFilm && it == SpektraPage.PRINT || videoMode &&
        (it in setOf(SpektraPage.CHROMA_DENOISE, SpektraPage.GRAIN) ||
            state.params.isMonochromeFilm && it == SpektraPage.SCANNER) }
    val selectedPage = savedPage.takeIf { it in pages } ?: SpektraPage.FILM
    val categoryScroll = rememberScrollState()
    LaunchedEffect(selectedPage, pages) {
        if (savedPage != selectedPage) categoryScroll.scrollTo(0)
    }
    var selectedKey by rememberSaveable(selectedPage) { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    val editorPreferences = LocalContext.current.getSharedPreferences("editor-controls", android.content.Context.MODE_PRIVATE)
    var sensitivity by rememberSaveable {
        mutableStateOf(runCatching {
            EditorSliderSensitivity.valueOf(editorPreferences.getString("slider_sensitivity", "MEDIUM")!!)
        }.getOrDefault(EditorSliderSensitivity.MEDIUM))
    }
    val profiles by repository.customProfiles.profiles.collectAsState()
    val selectedId by repository.selectedProfileId.collectAsState()
    val profile = profiles.firstOrNull { it.id == selectedId }
    val controls = AdjustmentBuilder().apply {
        val update: (SpektraFilmParams) -> Unit = { next -> repository.updateParams { next } }
        when (selectedPage) {
            SpektraPage.FILM -> FilmPage(state.params, update, sensorAeActive, videoMode, manualExposureOnly)
            SpektraPage.PRINT -> PrintPage(state.params, update)
            SpektraPage.CHROMA_DENOISE -> ChromaDenoisePage(state.params, update)
            SpektraPage.GRAIN -> GrainPage(state.params, update)
            SpektraPage.HALATION, SpektraPage.DIFFUSION, SpektraPage.SCANNER -> LookPage(state.params, update, videoMode)
            SpektraPage.OPTICS -> OpticsPage(state.params, update)
            SpektraPage.OUTPUT -> OutputPage(state.params, update)
            SpektraPage.ADVANCED -> AdvancedPage(state.params, update, videoMode)
        }
    }.controls.filter {
        when (selectedPage) {
            SpektraPage.HALATION -> it.section == "HALATION"
            SpektraPage.DIFFUSION -> it.section == "DIFFUSION"
            SpektraPage.SCANNER -> it.section == "SCANNER"
            else -> true
        }
    }
    val active = controls.firstOrNull { it.key == selectedKey } ?: controls.first()
    val density = LocalDensity.current
    val preferredHeight = 268.dp * density.fontScale.coerceIn(1f, 1.4f)
    LaunchedEffect(preferredHeight) { onContentHeightChanged(preferredHeight) }
    CompositionLocalProvider(LocalEditorSliderSensitivity provides sensitivity,
        LocalCycleEditorSliderSensitivity provides {
            sensitivity = sensitivity.next()
            editorPreferences.edit().putString("slider_sensitivity", sensitivity.name).apply()
        }) {
        Surface(modifier.testTag("Advanced editor"), color = Color.Transparent, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(start = 12.dp, top = 8.dp, end = 12.dp)) {
                    val heading: @Composable () -> Unit = {
                        Column {
                            Text("PROFILE EDITOR", color = TrayMuted, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = .8.sp)
                            Text(profile?.let { it.name + if (it.params != state.params) " / " + AppLanguage.text("Edited") else "" } ?: AppLanguage.text("New custom look"), translate = false,
                                color = CameraColors.Text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val save: @Composable () -> Unit = {
                        EditorFooterAction("Save profile", false, modifier = Modifier.onGloballyPositioned { onSaveBounds(it.boundsInRoot()) }) { saving = true }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth < 300.dp * LocalDensity.current.fontScale) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                heading()
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    save()
                                    EditorFooterAction("Apply now", true, onClick = onClose)
                                }
                            }
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { heading() }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { save() }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                EditorFooterAction("Apply now", true, onClick = onClose)
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(categoryScroll).testTag("Advanced categories"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pages.forEach { page ->
                            EditorChip(page.label, selectedPage == page, category = true) { savedPage = page }
                        }
                    }
                    key(selectedPage, videoMode) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()).testTag("Advanced settings"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            controls.forEach { control ->
                                EditorChip(control.label, active.key == control.key) { selectedKey = control.key }
                            }
                        }
                    }
                    key(active.key) {
                        Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            if (active.section == "RECIPE COLOR SETTINGS") {
                                Text(active.section, color = TrayAccent, fontSize = 10.sp)
                                Text("These color and HDR settings travel with exported recipes. Camera input stays linear Rec.709; the viewfinder and JPEG stay sRGB SDR.", color = TrayMuted, fontSize = 11.sp)
                            }
                            active.content()
                        }
                    }
                    HorizontalDivider(color = CameraColors.MenuBorder, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("Editor actions"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditorFooterAction(AppLanguage.text("spektrafilm") + " " + AppLanguage.text(if (state.params.enabled) "ON" else "OFF"), state.params.enabled,
                            icon = com.unspektrawesome.R.drawable.ic_sleek_power) { repository.updateParams { it.copy(enabled = !it.enabled) } }
                        EditorFooterAction("Undo", false, enabled = repository.canUndo(), icon = com.unspektrawesome.R.drawable.ic_sleek_undo_2) { repository.undo() }
                        EditorFooterAction("Reset all", false, icon = com.unspektrawesome.R.drawable.ic_sleek_rotate_ccw) { repository.reset() }

                    }
                    }
                }
            }
        }
    }
    if (saving) SpektraProfileDialog(repository, profile, onDismiss = { saving = false }, onSaved = {
        saving = false
        onSaved()
    })
}

@Composable
private fun EditorChip(label: String, selected: Boolean, category: Boolean = false, onClick: () -> Unit) {
    val shape = CameraStyle.ActionShape
    Box(Modifier.heightIn(min = if (category) 36.dp else 28.dp)
        .drawWithContent {
            drawContent()
            if (category && selected) drawRect(TrayAccent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
        }
        .then(if (category) Modifier else Modifier.border(1.dp, if (selected) TrayAccent else CameraColors.MenuBorder, shape))
        .selectable(selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = if (category) 0.dp else 10.dp, vertical = if (category) 8.dp else 4.dp),
        contentAlignment = Alignment.Center) {
        Text(if (category) AppLanguage.text(label).uppercase(Locale.ROOT) else AppLanguage.text(label), translate = false,
            color = if (selected) TrayAccent else TrayMuted, fontSize = 12.sp, lineHeight = 16.sp,
            letterSpacing = if (category) .6.sp else 0.sp)
    }
}

@Composable
private fun EditorFooterAction(label: String, accent: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: Int? = null, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 32.dp).clip(CameraStyle.ActionShape)
        .border(1.dp, if (accent) TrayAccent else CameraColors.MenuBorder, CameraStyle.ActionShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = if (!enabled) TrayMuted.copy(alpha = .4f) else if (accent) TrayAccent else CameraColors.Text
        icon?.let { CameraIcon(it, Modifier.size(12.dp), color) }
        Text(label, color = color, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

private data class Adjustment(val section: String, val label: String, val content: @Composable () -> Unit) {
    val key get() = "$section/$label"
}

private class AdjustmentBuilder {
    val controls = mutableListOf<Adjustment>()
    private var section = ""
    fun Section(title: String) { section = title }
    fun Hint(text: String) {
        val previous = controls.removeAt(controls.lastIndex)
        controls += previous.copy(content = {
            previous.content()
            Text(text, color = TrayMuted, fontSize = 11.sp)
        })
    }
    fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        controls += Adjustment(section, label) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = CameraColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                CameraOverlayToggle(checked, label, onChange)
            }
        }
    }
    fun OptionRow(label: String, value: Int, labels: List<String>, selectableLabels: List<String> = labels, onChange: (Int) -> Unit) {
        controls += Adjustment(section, label) {
            Text("{label} : {value2}", arguments = mapOf("label" to (label), "value2" to (labels.getOrNull(value).orEmpty())), color = CameraColors.Text, fontSize = 12.sp)
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("Advanced options")) {
                val density = LocalDensity.current
                val columns = if (maxWidth < 280.dp * density.fontScale) 1 else 2
                val availableWidth = maxWidth
                val width = with(density) { ((availableWidth.roundToPx() - kotlin.math.ceil(6.dp.toPx()).toInt() * (columns - 1)) / columns).toDp() }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectableLabels.forEachIndexed { index, option ->
                        Box(Modifier.width(if (index == selectableLabels.lastIndex && selectableLabels.size % columns == 1) availableWidth else width)
                            .heightIn(min = 36.dp).clip(CameraStyle.ControlShape)
                            .border(1.dp, if (index == value) TrayAccent else CameraColors.MenuBorder, CameraStyle.ControlShape)
                            .selectable(index == value, role = Role.RadioButton) { onChange(index) }.padding(8.dp), contentAlignment = Alignment.Center) {
                            Text(option, color = if (index == value) TrayAccent else CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
    fun FloatRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, default: Float = 0f,
        formatter: (Float) -> String = ::formatThree, enabled: Boolean = true, integer: Boolean = false, onChange: (Float) -> Unit) {
        controls += Adjustment(section, label) { FloatControl(label, value, range, default, formatter, enabled, integer, onChange) }
    }
    fun IntRow(label: String, value: Int, range: IntRange, default: Int, onChange: (Int) -> Unit) {
        FloatRow(label, value.toFloat(), range.first.toFloat()..range.last.toFloat(), default.toFloat(), ::formatZero, integer = true) {
            onChange(it.roundToInt().coerceIn(range))
        }
    }
}

@Composable
private fun FloatControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, default: Float,
    formatter: (Float) -> String, enabled: Boolean, integer: Boolean, onChange: (Float) -> Unit) {
    var enteringValue by rememberSaveable { mutableStateOf(false) }
    Column {
        val entry: @Composable () -> Unit = {
            EditorSensitivityButton(enabled)
            OutlinedButton(onClick = { enteringValue = true }, enabled = enabled,
                shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, CameraColors.MenuBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.languageSemantics { language -> contentDescription = language.format("Enter {label}", mapOf("label" to label)) }) {
                Text(formatter(value), translate = false, color = if (enabled) TrayAccent else TrayMuted, fontSize = 13.sp)
            }
            TextButton(onClick = { onChange(default.coerceIn(range)) }, enabled = enabled,
                modifier = Modifier.languageSemantics { language -> contentDescription = language.format("Reset {label}", mapOf("label" to (label))) }) {
                Text("Reset", color = if (enabled) TrayMuted else TrayMuted.copy(alpha = .4f), fontSize = 11.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp * LocalDensity.current.fontScale) {
                Column {
                    Text(label, color = CameraColors.Text, fontSize = 12.sp)
                    FlowRow(verticalArrangement = Arrangement.Center) { entry() }
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = CameraColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                entry()
            }
        }
        CameraCompactSlider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled,
            minimumHeight = 32.dp, thumbSize = 16.dp, valueStep = if (integer) 1f else null,
            modifier = Modifier.fillMaxWidth().languageSemantics { language -> contentDescription = language.text(label) },
            sensitivity = LocalEditorSliderSensitivity.current.scale, requireThumb = false,
            onReset = { onChange(default.coerceIn(range)) })
    }
    if (enteringValue && enabled) EditorValueDialog(label, value, range, integer,
        onDismiss = { enteringValue = false }, onApply = onChange, displayedValue = formatter(value))
}

@Composable
private fun EditorSensitivityButton(enabled: Boolean) {
    val sensitivity = LocalEditorSliderSensitivity.current
    val cycle = LocalCycleEditorSliderSensitivity.current
    val ink = if (enabled) TrayAccent else TrayMuted
    Box(Modifier.size(40.dp).testTag("Slider sensitivity")
        .languageSemantics { language ->
            contentDescription = language.format("Slider sensitivity: {value}", mapOf("value" to sensitivity.label))
            stateDescription = language.text(sensitivity.label)
        }.clickable(enabled = enabled, role = Role.Button,
            onClickLabel = AppLanguage.format("Switch to {label}", mapOf("label" to sensitivity.next().label)), onClick = cycle),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val radius = size.width * .42f
            val pivot = Offset(center.x, size.height * .74f)
            val stroke = 1.8.dp.toPx()
            drawArc(ink, 180f, 180f, false, Offset(pivot.x - radius, pivot.y - radius),
                Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            listOf(180.0, 225.0, 270.0, 315.0, 360.0).forEach { degrees ->
                val angle = Math.toRadians(degrees)
                val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                drawLine(ink, pivot + direction * (radius * .8f), pivot + direction * radius, stroke, StrokeCap.Round)
            }
            val angle = Math.toRadians(210.0 + sensitivity.ordinal * 60.0)
            val tip = pivot + Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat()) * (radius * .76f)
            drawLine(ink, pivot, tip, stroke * 1.3f, StrokeCap.Round)
            drawCircle(ink, stroke, pivot)
        }
    }
}

private fun AdjustmentBuilder.ChromaDenoisePage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit) {
    Section("CHROMA DENOISE")
    SwitchRow("Enabled", p.exportChromaDenoise) { update(p.copy(exportChromaDenoise = it)) }
    Hint("Reduce color noise in saved photos before spektrafilm. Saved with each custom profile.")
    FloatRow("Strength", p.chromaDenoiseStrength, 0f..1f, SpektraFilmParams().chromaDenoiseStrength,
        formatter = { "${(it * 100).roundToInt()}%" }, enabled = p.exportChromaDenoise) {
        update(p.copy(chromaDenoiseStrength = it))
    }
    Hint(if (p.exportChromaDenoise) "Smooth colored speckles while keeping brightness detail."
        else "Enable Chroma Denoise to adjust strength. Your saved strength is kept while off.")
}

private fun AdjustmentBuilder.FilmPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit,
    sensorAeActive: Boolean, videoMode: Boolean, manualExposureOnly: Boolean) {
    Section("FILM")
    OptionRow("RGB to Raw", p.rgbToRawMethod, rgbToRawLabels) { update(p.copy(rgbToRawMethod = it)) }
    OptionRow("Stock", p.film, SpektraFilmParams.FILM_LABELS) { update(p.copy(film = it)) }
    OptionRow("Film Format", p.filmFormat, filmFormatLabels) { update(p.copy(filmFormat = it)) }
    FloatRow("Exposure EV", p.filmExposureEv, -8f..8f, 0f, ::formatEv) { update(p.copy(filmExposureEv = it)) }
    if (!manualExposureOnly) {
        SwitchRow("Auto Exposure", p.autoExposure) { update(p.copy(autoExposure = it)) }
        if (sensorAeActive) Hint("Saved in the recipe. The camera currently uses sensor auto exposure.")
        OptionRow("Auto Exposure Meter", p.autoExposureMethod, autoExposureLabels) { update(p.copy(autoExposureMethod = it)) }
        if (sensorAeActive) Hint("Saved in the recipe. The camera currently uses sensor auto exposure.")
    }
    OptionRow("Push / Pull Mode", p.filmPushPullMode, pushPullLabels) { update(p.copy(filmPushPullMode = it)) }
    FloatRow("Film Push / Pull Stops", p.filmPushPullStops, -2f..2f, 0f, ::formatEv) { update(p.copy(filmPushPullStops = it)) }
    if (!p.isMonochromeFilm) {
        FloatRow("Negative Bleach Bypass", p.negativeBleachBypassAmount, 0f..1f) { update(p.copy(negativeBleachBypassAmount = it)) }
        FloatRow("Leuco-Cyan Coupling", p.negativeLeucoCyanCoupling, 0f..2f, 1f) { update(p.copy(negativeLeucoCyanCoupling = it)) }
    }
    FloatRow("Gamma", p.filmGamma, 0.1f..2f, 1f) { update(p.copy(filmGamma = it)) }
}

private fun AdjustmentBuilder.PrintPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit) {
    Section("PRINT")
    OptionRow("Paper", p.paper, SpektraFilmParams.PAPER_LABELS) { update(p.copy(paper = it)) }
    OptionRow("Print Timing", p.printTiming, printTimingLabels) { update(p.copy(printTiming = it)) }
    FloatRow("Exposure EV", p.printExposureEv, -5f..5f, 0f, ::formatEv) { update(p.copy(printExposureEv = it)) }
    FloatRow("Print Push / Pull Stops", p.printPushPullStops, -2f..2f, 0f, ::formatEv) { update(p.copy(printPushPullStops = it)) }
    FloatRow("Print Bleach Bypass", p.printBleachBypassAmount, 0f..1f) { update(p.copy(printBleachBypassAmount = it)) }
    FloatRow("Gamma", p.printGamma, 0.1f..2f, 1f) { update(p.copy(printGamma = it)) }
    FloatRow("Shadow Shape", p.printShadowShape, -1f..1f) { update(p.copy(printShadowShape = it)) }
    FloatRow("Highlight Shape", p.printHighlightShape, -1f..1f) { update(p.copy(printHighlightShape = it)) }
    FloatRow("C Filter", p.filterC, 0f..120f, formatter = ::formatOne) { update(p.copy(filterC = it)) }
    FloatRow("M Filter Shift", p.filterMShift, -60f..60f, formatter = ::formatOne) { update(p.copy(filterMShift = it)) }
    FloatRow("Y Filter Shift", p.filterYShift, -60f..60f, formatter = ::formatOne) { update(p.copy(filterYShift = it)) }
    FloatRow("Preflash Exposure", p.preflashExposure, 0f..1f) { update(p.copy(preflashExposure = it)) }
    FloatRow("Preflash M Filter Shift", p.preflashMFilterShift, -60f..60f, formatter = ::formatOne) { update(p.copy(preflashMFilterShift = it)) }
    FloatRow("Preflash Y Filter Shift", p.preflashYFilterShift, -60f..60f, formatter = ::formatOne) { update(p.copy(preflashYFilterShift = it)) }
    SwitchRow("Gang Printer Points", p.printerLightsGang) { update(p.copy(printerLightsGang = it)) }
    FloatRow("Printer Point R", p.printerLightsR, -24f..24f, formatter = ::formatOne) { update(p.copy(printerLightsR = it)) }
    FloatRow("Printer Point G", p.printerLightsG, -24f..24f, formatter = ::formatOne) { update(p.copy(printerLightsG = it)) }
    FloatRow("Printer Point B", p.printerLightsB, -24f..24f, formatter = ::formatOne) { update(p.copy(printerLightsB = it)) }
    SwitchRow("Printer Point Calibration", p.printerLightCalibration) { update(p.copy(printerLightCalibration = it)) }
}

private fun AdjustmentBuilder.GrainPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit) {
    Section("GRAIN")
    SwitchRow("Enabled", p.grainEnabled) { update(p.copy(grainEnabled = it)) }
    OptionRow("Model", p.grainModel, grainModelLabels, selectableLabels = grainModelLabels.take(2)) { update(p.copy(grainModel = it)) }
    if (p.isMonochromeFilm) {
        if (p.grainModel == 2) {
            FloatRow("Amount", p.grainSynthesisAmount, 0f..2f, 1f) { update(p.copy(grainSynthesisAmount = it)) }
            FloatRow("Size", p.grainSynthesisSize, 0.1f..5f, 1f) { update(p.copy(grainSynthesisSize = it)) }
        } else {
            FloatRow("Amount", p.grainAmount, 0f..2f, 1f) { update(p.copy(grainAmount = it)) }
            FloatRow("Size", p.grainParticleAreaUm2 / 0.1f, 0.1f..5f, 1f) { update(p.copy(grainParticleAreaUm2 = it * 0.1f)) }
        }
        return
    }
    FloatRow("Amount", p.grainAmount, 0f..2f, 1f) { update(p.copy(grainAmount = it)) }
    FloatRow("Saturation", p.grainSaturation, 0f..1f, 1f) { update(p.copy(grainSaturation = it)) }
    FloatRow("Particle Area um2", p.grainParticleAreaUm2, 0.01f..5f, 0.1f) { update(p.copy(grainParticleAreaUm2 = it)) }
    SwitchRow("Sublayers", p.grainSublayersEnabled) { update(p.copy(grainSublayersEnabled = it)) }
    IntRow("Sub Layer Count", p.grainSubLayerCount, 1..8, 1) { update(p.copy(grainSubLayerCount = it)) }
    FloatRow("Particle Scale R", p.grainParticleScaleR, 0f..8f, 1.2f) { update(p.copy(grainParticleScaleR = it)) }
    FloatRow("Particle Scale G", p.grainParticleScaleG, 0f..8f, 1f) { update(p.copy(grainParticleScaleG = it)) }
    FloatRow("Particle Scale B", p.grainParticleScaleB, 0f..8f, 2.5f) { update(p.copy(grainParticleScaleB = it)) }
    FloatRow("Layer Scale 0", p.grainParticleScaleLayer0, 0f..8f, 6f) { update(p.copy(grainParticleScaleLayer0 = it)) }
    FloatRow("Layer Scale 1", p.grainParticleScaleLayer1, 0f..8f, 1f) { update(p.copy(grainParticleScaleLayer1 = it)) }
    FloatRow("Layer Scale 2", p.grainParticleScaleLayer2, 0f..8f, 0.4f) { update(p.copy(grainParticleScaleLayer2 = it)) }
    FloatRow("Density Min R", p.grainDensityMinR, 0f..1f, 0.04f) { update(p.copy(grainDensityMinR = it)) }
    FloatRow("Density Min G", p.grainDensityMinG, 0f..1f, 0.05f) { update(p.copy(grainDensityMinG = it)) }
    FloatRow("Density Min B", p.grainDensityMinB, 0f..1f, 0.06f) { update(p.copy(grainDensityMinB = it)) }
    FloatRow("Uniformity R", p.grainUniformityR, 0f..1f, 0.99f) { update(p.copy(grainUniformityR = it)) }
    FloatRow("Uniformity G", p.grainUniformityG, 0f..1f, 0.97f) { update(p.copy(grainUniformityG = it)) }
    FloatRow("Uniformity B", p.grainUniformityB, 0f..1f, 0.98f) { update(p.copy(grainUniformityB = it)) }
    FloatRow("Final Grain Blur", p.grainFinalBlurUm, 0f..25f, 11.8f, ::formatTwo) { update(p.copy(grainFinalBlurUm = it)) }
    FloatRow("Dye Cloud Blur um", p.grainBlurDyeCloudsUm, 0f..10f, 1f) { update(p.copy(grainBlurDyeCloudsUm = it)) }
    FloatRow("Micro Structure Scale", p.grainMicroStructureScale, 0f..100f, 0.2f) { update(p.copy(grainMicroStructureScale = it)) }
    FloatRow("Micro Structure Sigma nm", p.grainMicroStructureSigmaNm, 0f..100f, 30f, ::formatOne) { update(p.copy(grainMicroStructureSigmaNm = it)) }
    IntRow("Seed", p.grainSeed, 0..1_000_000, 1) { update(p.copy(grainSeed = it)) }
    SwitchRow("Animate", p.grainAnimate) { update(p.copy(grainAnimate = it)) }
    FloatRow("Synthesis Size", p.grainSynthesisSize, 0.25f..4f, 1f) { update(p.copy(grainSynthesisSize = it)) }
    FloatRow("Synthesis Amount", p.grainSynthesisAmount, 0f..3f, 1f) { update(p.copy(grainSynthesisAmount = it)) }
    FloatRow("Synthesis Sharpness", p.grainSynthesisSharpness, 0.25f..4f, 1f) { update(p.copy(grainSynthesisSharpness = it)) }
    FloatRow("Synthesis Quality", p.grainSynthesisQuality, 0.25f..4f, 1f) { update(p.copy(grainSynthesisQuality = it)) }
    Section("GRAIN SYNTHESIS")
    IntRow("Samples", p.grainSynthesisSamples, 1..1024, 128) { update(p.copy(grainSynthesisSamples = it)) }
    Hint("Effective samples = Samples × Quality, limited to 1024.")
    FloatRow("Mean Radius um", p.grainSynthesisMeanRadiusUm, 0.05f..10f, 0.25f) { update(p.copy(grainSynthesisMeanRadiusUm = it)) }
    FloatRow("Radius StdDev Ratio", p.grainSynthesisRadiusStdDevRatio, 0f..1f) { update(p.copy(grainSynthesisRadiusStdDevRatio = it)) }
    FloatRow("Observation Aperture Sigma um", p.grainSynthesisObservationSigmaUm, 0f..20f, 1f) { update(p.copy(grainSynthesisObservationSigmaUm = it)) }
    FloatRow("Cell Size Ratio", p.grainSynthesisCellSizeRatio, 0.25f..2f, 1f) { update(p.copy(grainSynthesisCellSizeRatio = it)) }
    FloatRow("Max Radius Quantile", p.grainSynthesisMaxRadiusQuantile, 0.95f..0.9999f, 0.999f, ::formatFour) { update(p.copy(grainSynthesisMaxRadiusQuantile = it)) }
    FloatRow("Coverage Epsilon", p.grainSynthesisCoverageEpsilon, 0.000001f..0.01f, 0.0001f, ::formatSix) { update(p.copy(grainSynthesisCoverageEpsilon = it)) }
    IntRow("Max Grains Per Cell", p.grainSynthesisMaxGrainsPerCell, 1..128, 32) { update(p.copy(grainSynthesisMaxGrainsPerCell = it)) }
    FloatRow("Radius Scale R", p.grainSynthesisRadiusScaleR, 0f..8f, 1.2f) { update(p.copy(grainSynthesisRadiusScaleR = it)) }
    FloatRow("Radius Scale G", p.grainSynthesisRadiusScaleG, 0f..8f, 1f) { update(p.copy(grainSynthesisRadiusScaleG = it)) }
    FloatRow("Radius Scale B", p.grainSynthesisRadiusScaleB, 0f..8f, 2.5f) { update(p.copy(grainSynthesisRadiusScaleB = it)) }
    FloatRow("Layer Scale 0", p.grainSynthesisLayerScale0, 0f..8f, 6f) { update(p.copy(grainSynthesisLayerScale0 = it)) }
    FloatRow("Layer Scale 1", p.grainSynthesisLayerScale1, 0f..8f, 1f) { update(p.copy(grainSynthesisLayerScale1 = it)) }
    FloatRow("Layer Scale 2", p.grainSynthesisLayerScale2, 0f..8f, 0.4f) { update(p.copy(grainSynthesisLayerScale2 = it)) }
    SwitchRow("Layered", p.grainSynthesisLayered) { update(p.copy(grainSynthesisLayered = it)) }
}

private fun AdjustmentBuilder.LookPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit, videoMode: Boolean) {
    Section("HALATION")
    SwitchRow("Enabled", p.halationEnabled) { update(p.copy(halationEnabled = it)) }
    FloatRow("Scatter Amount", p.scatterAmount, 0f..2f, 1f) { update(p.copy(scatterAmount = it)) }
    FloatRow("Scatter Scale", p.scatterScale, 0f..4f, 1f) { update(p.copy(scatterScale = it)) }
    FloatRow("Amount", p.halationAmount, 0f..4f, 1f) { update(p.copy(halationAmount = it)) }
    FloatRow("Scale", p.halationScale, 0f..4f, 1f) { update(p.copy(halationScale = it)) }
    FloatRow("Boost EV", p.halationBoostEv, 0f..20f, formatter = ::formatEv) { update(p.copy(halationBoostEv = it)) }
    FloatRow("Boost Range", p.halationBoostRange, 0f..1f, 0.3f) { update(p.copy(halationBoostRange = it)) }
    FloatRow("Protect EV", p.halationProtectEv, 0f..10f, 4f, ::formatEv) { update(p.copy(halationProtectEv = it)) }
    FloatRow("Strength R", p.halationStrengthR, 0f..1f, 0.05f) { update(p.copy(halationStrengthR = it)) }
    FloatRow("Strength G", p.halationStrengthG, 0f..1f, 0.015f) { update(p.copy(halationStrengthG = it)) }
    FloatRow("Strength B", p.halationStrengthB, 0f..1f) { update(p.copy(halationStrengthB = it)) }
    FloatRow("First Sigma R um", p.halationFirstSigmaUmR, 0f..200f, 65f, ::formatOne) { update(p.copy(halationFirstSigmaUmR = it)) }
    FloatRow("First Sigma G um", p.halationFirstSigmaUmG, 0f..200f, 65f, ::formatOne) { update(p.copy(halationFirstSigmaUmG = it)) }
    FloatRow("First Sigma B um", p.halationFirstSigmaUmB, 0f..200f, 65f, ::formatOne) { update(p.copy(halationFirstSigmaUmB = it)) }
    Hint("At 65 um, each channel uses the film stock's halation radius. Adjust the channels or Scale to change it.")
    Section("DIFFUSION")
    SwitchRow("Camera Enabled", p.cameraDiffusionEnabled) { update(p.copy(cameraDiffusionEnabled = it)) }
    OptionRow("Camera Family", p.cameraDiffusionFamily, diffusionFamilyLabels) { update(p.copy(cameraDiffusionFamily = it)) }
    FloatRow("Camera Strength", p.cameraDiffusionStrength, 0f..2f, 0.5f) { update(p.copy(cameraDiffusionStrength = it)) }
    FloatRow("Camera Spatial Scale", p.cameraDiffusionSpatialScale, 0f..4f, 1f) { update(p.copy(cameraDiffusionSpatialScale = it)) }
    FloatRow("Camera Halo Warmth", p.cameraDiffusionHaloWarmth, -1.5f..1.5f) { update(p.copy(cameraDiffusionHaloWarmth = it)) }
    FloatRow("Camera Core Intensity", p.cameraDiffusionCoreIntensity, 0f..4f, 1f) { update(p.copy(cameraDiffusionCoreIntensity = it)) }
    FloatRow("Camera Core Size", p.cameraDiffusionCoreSize, 0.1f..4f, 1f) { update(p.copy(cameraDiffusionCoreSize = it)) }
    FloatRow("Camera Halo Intensity", p.cameraDiffusionHaloIntensity, 0f..4f, 1f) { update(p.copy(cameraDiffusionHaloIntensity = it)) }
    FloatRow("Camera Halo Size", p.cameraDiffusionHaloSize, 0.1f..4f, 1f) { update(p.copy(cameraDiffusionHaloSize = it)) }
    FloatRow("Camera Bloom Intensity", p.cameraDiffusionBloomIntensity, 0f..4f, 1f) { update(p.copy(cameraDiffusionBloomIntensity = it)) }
    FloatRow("Camera Bloom Size", p.cameraDiffusionBloomSize, 0.1f..4f, 1f) { update(p.copy(cameraDiffusionBloomSize = it)) }
    if (!p.isMonochromeFilm) {
        SwitchRow("Print Enabled", p.printDiffusionEnabled) { update(p.copy(printDiffusionEnabled = it)) }
        OptionRow("Print Family", p.printDiffusionFamily, diffusionFamilyLabels) { update(p.copy(printDiffusionFamily = it)) }
        FloatRow("Print Strength", p.printDiffusionStrength, 0f..2f, 0.5f) { update(p.copy(printDiffusionStrength = it)) }
        FloatRow("Print Spatial Scale", p.printDiffusionSpatialScale, 0f..4f, 1f) { update(p.copy(printDiffusionSpatialScale = it)) }
        FloatRow("Print Halo Warmth", p.printDiffusionHaloWarmth, -1.5f..1.5f) { update(p.copy(printDiffusionHaloWarmth = it)) }
        FloatRow("Print Core Intensity", p.printDiffusionCoreIntensity, 0f..4f, 1f) { update(p.copy(printDiffusionCoreIntensity = it)) }
        FloatRow("Print Core Size", p.printDiffusionCoreSize, 0.1f..4f, 1f) { update(p.copy(printDiffusionCoreSize = it)) }
        FloatRow("Print Halo Intensity", p.printDiffusionHaloIntensity, 0f..4f, 1f) { update(p.copy(printDiffusionHaloIntensity = it)) }
        FloatRow("Print Halo Size", p.printDiffusionHaloSize, 0.1f..4f, 1f) { update(p.copy(printDiffusionHaloSize = it)) }
        FloatRow("Print Bloom Intensity", p.printDiffusionBloomIntensity, 0f..4f, 1f) { update(p.copy(printDiffusionBloomIntensity = it)) }
        FloatRow("Print Bloom Size", p.printDiffusionBloomSize, 0.1f..4f, 1f) { update(p.copy(printDiffusionBloomSize = it)) }
    }
    Section("SCANNER")
    SwitchRow("Enabled", p.scannerEnabled) { update(p.copy(scannerEnabled = it)) }
    if (!p.isMonochromeFilm) {
        SwitchRow("White Correction", p.scannerWhiteCorrection) { update(p.copy(scannerWhiteCorrection = it)) }
        SwitchRow("Black Correction", p.scannerBlackCorrection) { update(p.copy(scannerBlackCorrection = it)) }
        FloatRow("White Level", p.scannerWhiteLevel, 0f..1f, 0.98f) { update(p.copy(scannerWhiteLevel = it)) }
        FloatRow("Black Level", p.scannerBlackLevel, 0f..1f, 0.01f) { update(p.copy(scannerBlackLevel = it)) }
    }
    if (!p.isMonochromeFilm) {
        FloatRow("Glare Percent", p.glarePercent, 0f..0.2f, 0.03f) { update(p.copy(glarePercent = it)) }
        if (!videoMode) {
            FloatRow("Glare Roughness", p.glareRoughness, 0f..4f, 0.7f) { update(p.copy(glareRoughness = it)) }
            FloatRow("Glare Blur", p.glareBlur, 0f..32f, 0.5f) { update(p.copy(glareBlur = it)) }
        }
    }
    if (videoMode) return
    FloatRow("MTF50 lp/mm", p.scannerMtf50LpMm, 0f..300f, 60f, ::formatOne) { update(p.copy(scannerMtf50LpMm = it)) }
    FloatRow("Unsharp Radius um", p.scannerUnsharpRadiusUm, 0f..100f, 5f, ::formatOne) { update(p.copy(scannerUnsharpRadiusUm = it)) }
    FloatRow("Unsharp Amount", p.scannerUnsharpAmount, 0f..4f, 0.7f) { update(p.copy(scannerUnsharpAmount = it)) }
}

private fun AdjustmentBuilder.OpticsPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit) {
    Section("FILTERING")
    SwitchRow("Filter UV", p.cameraUvFilterEnabled) { update(p.copy(cameraUvFilterEnabled = it)) }
    FloatRow("UV Cut nm", p.cameraUvCutNm, 380f..450f, 410f, ::formatZero) { update(p.copy(cameraUvCutNm = it)) }
    SwitchRow("Filter IR", p.cameraIrFilterEnabled) { update(p.copy(cameraIrFilterEnabled = it)) }
    FloatRow("IR Cut nm", p.cameraIrCutNm, 600f..780f, 675f, ::formatZero) { update(p.copy(cameraIrCutNm = it)) }
    Section("FILM PLANE")
    FloatRow("Scale", p.enlargerScale, 1f..32f, 1f) { update(p.copy(enlargerScale = it)) }
    FloatRow("Offset X %", p.enlargerOffsetXPercent, -100f..100f, formatter = ::formatOne) { update(p.copy(enlargerOffsetXPercent = it)) }
    FloatRow("Offset Y %", p.enlargerOffsetYPercent, -100f..100f, formatter = ::formatOne) { update(p.copy(enlargerOffsetYPercent = it)) }
}

private fun AdjustmentBuilder.OutputPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit) {
    Section("COLOR")
    OptionRow("Mode", p.process, processLabels) { update(p.copy(process = it)) }
    if (p.process == 1) SwitchRow("Invert Negative", p.scanNegativeInvert) { update(p.copy(scanNegativeInvert = it)) }
    OptionRow("Render Output", p.renderOutput, renderOutputLabels) { update(p.copy(renderOutput = it)) }
    Section("RECIPE COLOR SETTINGS")
    OptionRow("Input Color Space", p.inputColorSpace, SPEKTRA_COLOR_SPACE_LABELS) { update(p.copy(inputColorSpace = it)) }
    OptionRow("Output Color Space", p.outputColorSpace, SPEKTRA_COLOR_SPACE_LABELS) { update(p.copy(outputColorSpace = it)) }
    OptionRow("Output Role", p.outputRole, SPEKTRA_OUTPUT_ROLE_LABELS) { update(p.copy(outputRole = it)) }
    OptionRow("HDR Preset", p.hdrPreset, SPEKTRA_HDR_PRESET_LABELS) { update(p.copy(hdrPreset = it)) }
    OptionRow("HDR Transfer", p.hdrTransfer, SPEKTRA_HDR_TRANSFER_LABELS) { update(p.copy(hdrTransfer = it)) }
    OptionRow("HDR Tone Mapping", p.hdrToneMapping, SPEKTRA_HDR_TONE_MAPPING_LABELS) { update(p.copy(hdrToneMapping = it)) }
    FloatRow("Reference White Nits", p.hdrReferenceWhiteNits, 48f..1000f, 203f, ::formatZero) { update(p.copy(hdrReferenceWhiteNits = it)) }
    FloatRow("Peak Nits", p.hdrPeakNits, 100f..10000f, 1000f, ::formatZero) { update(p.copy(hdrPeakNits = it)) }
    FloatRow("HDR Exposure EV", p.hdrExposureEv, -8f..8f, formatter = ::formatEv) { update(p.copy(hdrExposureEv = it)) }
    Section("COLOR")
    SwitchRow("Color Adaptation", p.colorAdaptation) { update(p.copy(colorAdaptation = it)) }
    if (p.colorAdaptation) {
        SwitchRow("Input Compression", p.colorAdaptationInputCompression) { update(p.copy(colorAdaptationInputCompression = it)) }
        SwitchRow("Curve Smoothing", p.colorAdaptationCurveSmoothing) { update(p.copy(colorAdaptationCurveSmoothing = it)) }
        SwitchRow("Output Lightness Compression", p.colorAdaptationOutputLightnessCompression) { update(p.copy(colorAdaptationOutputLightnessCompression = it)) }
        SwitchRow("Output Chroma Compression", p.colorAdaptationOutputChromaCompression) { update(p.copy(colorAdaptationOutputChromaCompression = it)) }
    }
}

private fun AdjustmentBuilder.AdvancedPage(p: SpektraFilmParams, update: (SpektraFilmParams) -> Unit, videoMode: Boolean) {
    Section("DIR COUPLERS")
    FloatRow("Amount", p.dirCouplersAmount, 0f..2f) { update(p.copy(dirCouplersAmount = it)) }
    if (!videoMode) {
        FloatRow("Diffusion um", p.dirCouplersDiffusionUm, 0f..100f, 20f, ::formatOne) { update(p.copy(dirCouplersDiffusionUm = it)) }
        FloatRow("Tail um", p.dirCouplersDiffusionTailUm, 0f..1000f, 200f, ::formatOne) { update(p.copy(dirCouplersDiffusionTailUm = it)) }
        FloatRow("Tail Weight", p.dirCouplersDiffusionTailWeight, 0f..1f, 0.06f) { update(p.copy(dirCouplersDiffusionTailWeight = it)) }
    }
    FloatRow("Same-Layer Inhibition", p.dirCouplersInhibitionSameLayer, 0f..2f, 1f) { update(p.copy(dirCouplersInhibitionSameLayer = it)) }
    if (p.isMonochromeFilm) return
    FloatRow("Interlayer Inhibition", p.dirCouplersInhibitionInterlayer, 0f..2f, 1f) { update(p.copy(dirCouplersInhibitionInterlayer = it)) }
    FloatRow("Same-Layer Gamma R", p.dirCouplersGammaSameLayerR, 0f..1f, 0.336f) { update(p.copy(dirCouplersGammaSameLayerR = it)) }
    FloatRow("Same-Layer Gamma G", p.dirCouplersGammaSameLayerG, 0f..1f, 0.319f) { update(p.copy(dirCouplersGammaSameLayerG = it)) }
    FloatRow("Same-Layer Gamma B", p.dirCouplersGammaSameLayerB, 0f..1f, 0.273f) { update(p.copy(dirCouplersGammaSameLayerB = it)) }
    FloatRow("R -> G Gamma", p.dirCouplersGammaRToG, 0f..1f, 0.353f) { update(p.copy(dirCouplersGammaRToG = it)) }
    FloatRow("R -> B Gamma", p.dirCouplersGammaRToB, 0f..1f, 0.302f) { update(p.copy(dirCouplersGammaRToB = it)) }
    FloatRow("G -> R Gamma", p.dirCouplersGammaGToR, 0f..1f, 0.154f) { update(p.copy(dirCouplersGammaGToR = it)) }
    FloatRow("G -> B Gamma", p.dirCouplersGammaGToB, 0f..1f, 0.353f) { update(p.copy(dirCouplersGammaGToB = it)) }
    FloatRow("B -> R Gamma", p.dirCouplersGammaBToR, 0f..1f, 0.168f) { update(p.copy(dirCouplersGammaBToR = it)) }
    FloatRow("B -> G Gamma", p.dirCouplersGammaBToG, 0f..1f, 0.226f) { update(p.copy(dirCouplersGammaBToG = it)) }
}

private fun formatEv(value: Float) = String.format(Locale.ROOT, if (value >= 0f) "+%.2f" else "%.2f", value)
private fun formatZero(value: Float) = String.format(Locale.ROOT, "%.0f", value)
private fun formatOne(value: Float) = String.format(Locale.ROOT, "%.1f", value)
private fun formatTwo(value: Float) = String.format(Locale.ROOT, "%.2f", value)
private fun formatThree(value: Float) = String.format(Locale.ROOT, "%.3f", value)
private fun formatFour(value: Float) = String.format(Locale.ROOT, "%.4f", value)
private fun formatSix(value: Float) = String.format(Locale.ROOT, "%.6f", value)
