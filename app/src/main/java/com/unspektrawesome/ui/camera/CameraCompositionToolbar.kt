package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.camera.FlashMode
import com.unspektrawesome.capture.CropRatio
import com.unspektrawesome.capture.CaptureTransform
import com.unspektrawesome.capture.PhotoCrop
import com.unspektrawesome.capture.SavedPhotoCrop
import com.unspektrawesome.settings.CameraPreferences
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class CompositionPanel { CROP, CUSTOM }
internal enum class CompositionGlyph { CROP, ZEBRA, HISTOGRAM, PEAKING, FLASH, FLASH_OFF, SUN }

@Composable
private fun topToolSize(): Dp = 36.dp * LocalDensity.current.fontScale.coerceIn(1f, 4f / 3f)

@Composable
internal fun BoxScope.CameraCompositionToolbar(
    preferences: CameraPreferences,
    onChange: (CameraPreferences) -> Unit,
    moreOpen: Boolean,
    onMore: () -> Unit,
    onCloseMore: () -> Unit,
    rotation: Float,
    panelMaxHeight: Dp,
    cropOutputSize: (PhotoCrop) -> Size2d?,
    onCropPreview: (PhotoCrop?) -> Unit,
    onPanelChanged: (Boolean) -> Unit,
    onMenuBounds: (Rect) -> Unit,
    onToolsBounds: (Rect) -> Unit,
    flashAvailable: Boolean = false,
    captureBusy: Boolean = false,
    videoMode: Boolean = false,
    cropEnabled: Boolean = true,
    onVideoCrop: (() -> Unit)? = null,
    panelTop: Dp = 64.dp,
    panelSides: Dp = 24.dp,
    panelBottom: Dp = 16.dp,
) {
    var panel by rememberSaveable { mutableStateOf<CompositionPanel?>(null) }
    var width by rememberSaveable { mutableStateOf("3") }
    var height by rememberSaveable { mutableStateOf("2") }
    var name by rememberSaveable { mutableStateOf("") }
    var editingCropId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastPanel by rememberSaveable { mutableStateOf(CompositionPanel.CROP) }
    val visibility = remember { MutableTransitionState(false) }
    val transition = rememberTransition(visibility, label = "Composition panel")
    val windowBackdrop = (LocalCameraBackdropWindows.current?.count ?: 0) > 0
    val cropCardsState = rememberLazyListState()
    var tooltip by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val currentPreview by rememberUpdatedState(onCropPreview)
    val currentPanelChanged by rememberUpdatedState(onPanelChanged)
    val customWidth = width.replace(',', '.').toDoubleOrNull()
    val customHeight = height.replace(',', '.').toDoubleOrNull()
    val custom = if (customWidth != null && customHeight != null && PhotoCrop.validCustom(customWidth, customHeight))
        PhotoCrop(CropRatio.CUSTOM, customWidth, customHeight) else null
    fun close() { keyboard?.hide(); currentPreview(null); panel = null }
    fun open(value: CompositionPanel) { onCloseMore(); panel = if (panel == value) null else value }
    fun editCustom(saved: SavedPhotoCrop? = null) {
        val crop = saved?.crop ?: preferences.photoCrop
        width = PhotoCrop.number(if (crop.swapped) crop.customHeight else crop.customWidth)
        height = PhotoCrop.number(if (crop.swapped) crop.customWidth else crop.customHeight)
        name = saved?.name.orEmpty()
        editingCropId = saved?.id
        panel = CompositionPanel.CUSTOM
    }
    fun saveCustom() {
        if (panel != CompositionPanel.CUSTOM) return
        custom?.let { crop ->
            val saved = editingCropId?.let { id -> SavedPhotoCrop(id, name.trim(), crop) } ?: SavedPhotoCrop.create(name, crop)
            val crops = if (preferences.savedPhotoCrops.any { it.id == saved.id })
                preferences.savedPhotoCrops.map { if (it.id == saved.id) saved else it }
            else preferences.savedPhotoCrops + saved
            onChange(preferences.copy(photoCrop = crop, savedPhotoCrops = crops, selectedPhotoCropId = saved.id))
            close()
        }
    }
    SideEffect { visibility.targetState = panel != null; panel?.let { lastPanel = it } }
    val panelShowing = visibility.currentState || visibility.targetState
    LaunchedEffect(moreOpen) { if (moreOpen) close() }
    LaunchedEffect(cropEnabled, videoMode) { if (!cropEnabled || videoMode) close() }
    LaunchedEffect(tooltip) { if (tooltip != null) { delay(1_800); tooltip = null } }
    DisposableEffect(panelShowing) {
        currentPanelChanged(panelShowing)
        onDispose { currentPanelChanged(false) }
    }
    DisposableEffect(Unit) { onDispose { currentPreview(null) } }
    LaunchedEffect(panel, custom) {
        if (panel == CompositionPanel.CUSTOM && custom != null) { delay(120); currentPreview(custom) }
        else if (panel != CompositionPanel.CUSTOM) currentPreview(null)
    }
    LaunchedEffect(panel) {
        if (panel == CompositionPanel.CROP) {
            val presetCount = CropRatio.entries.size - 1
            val selected = preferences.savedPhotoCrops.indexOfFirst { it.id == preferences.selectedPhotoCropId }
            cropCardsState.scrollToItem(if (selected >= 0) presetCount + selected else
                CropRatio.entries.indexOf(preferences.photoCrop.ratio).coerceAtMost(presetCount - 1))
        }
    }
    BackHandler(panel != null) { close() }
    transition.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(240)), exit = fadeOut(tween(240)),
        modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Transparent)
            .clickable(interactionSource = null, indication = null) { close() }
            .languageSemantics { language -> contentDescription = language.text("Close composition controls") })
        CameraCropPanel(rotation, panelTop, panelSides, panelBottom) {
        Column(Modifier.fillMaxWidth()
            .testTag("Composition picker")) {
            when (panel ?: lastPanel) {
                CompositionPanel.CROP -> {
                    CameraCropHeader("Aspect ratio", ::close, Modifier.testTag("Close photo framing"), done = true,
                        subtitle = { CropResolution(preferences.photoCrop, cropOutputSize) })
                    fun aspect(crop: PhotoCrop): Float {
                        val size = cropOutputSize(crop) ?: crop.viewport(Size2d(4000, 3000),
                            CaptureTransform(if (abs(rotation) == 90f) 0 else 90, false))
                        return size.width.toFloat() / size.height
                    }
                    val cards = CropRatio.entries.filter { it != CropRatio.CUSTOM }.map { ratio ->
                        val crop = preferences.photoCrop.copy(ratio = ratio)
                        CompositionCard(ratio.name, ratio.label, "Crop ${ratio.label}", preferences.photoCrop.ratio == ratio,
                            onClick = { onChange(preferences.copy(photoCrop = crop, selectedPhotoCropId = null)); close() },
                            drawing = { ink -> drawCropCard(aspect(crop), ink) })
                    } + preferences.savedPhotoCrops.map { saved ->
                        val name = saved.name.ifBlank { saved.crop.label() }
                        CompositionCard(saved.id, name, "Saved crop $name",
                            preferences.selectedPhotoCropId == saved.id && preferences.photoCrop == saved.crop,
                            subtitle = if (saved.name.isBlank()) "Custom" else saved.crop.label(),
                            translateLabel = saved.name.isBlank(),
                            onClick = { onChange(preferences.copy(photoCrop = saved.crop, selectedPhotoCropId = saved.id)); close() },
                            onEdit = { editCustom(saved) }, drawing = { ink -> drawCropCard(aspect(saved.crop), ink) })
                    } + CompositionCard("create-custom", "Custom", "Create custom crop", false,
                        onClick = { editCustom() }, drawing = { ink -> drawCustomCropCard(ink) })
                    CompositionCards(cards, cropCardsState, "Photo crop ratios")

                }
                CompositionPanel.CUSTOM -> {
                    CameraQuickMenuHeader("CUSTOM CROP", com.unspektrawesome.R.drawable.ic_sleek_square_dashed, ::close)
                    val fling = rememberPanelFlingBehavior()
                    Column(Modifier.weight(1f, fill = false).trackPanelFling(fling)
                        .verticalScroll(rememberScrollState(), flingBehavior = fling, overscrollEffect = null)
                        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Name", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.width(48.dp))
                            BasicTextField(name, { name = it.take(32) }, singleLine = true,
                                textStyle = TextStyle(color = CameraColors.Text, fontSize = 14.sp),
                                cursorBrush = SolidColor(CameraColors.Accent),
                                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Next),
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(22.dp))
                                    .background(CameraColors.Background).border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(22.dp))
                                    .padding(12.dp).languageSemantics { language -> contentDescription = language.text("Crop name") },
                                decorationBox = { field -> Box { if (name.isEmpty()) Text("Name (optional)", color = CameraColors.Muted, fontSize = 14.sp); field() } })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ratio", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.width(48.dp))
                            CropNumberField("Crop width", width, { width = it }, Modifier.weight(1f), ImeAction.Next) {}
                            Text(":", color = CameraColors.Muted, fontSize = 16.sp)
                            CropNumberField("Crop height", height, { height = it }, Modifier.weight(1f), ImeAction.Done, ::saveCustom)
                        }
                        if (custom != null) CropResolution(custom, cropOutputSize, Modifier.padding(start = 56.dp))
                        else Text("Enter positive proportions from 1:10 to 10:1.", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
                    FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        editingCropId?.let { id ->
                            CameraDialogAction("Delete", false, onClick = {
                                onChange(preferences.copy(savedPhotoCrops = preferences.savedPhotoCrops.filterNot { it.id == id },
                                    selectedPhotoCropId = preferences.selectedPhotoCropId?.takeUnless { it == id }))
                                close()
                            })
                        }
                        CameraDialogAction("Cancel", false, ::close)
                        androidx.compose.material3.OutlinedButton(onClick = ::saveCustom, enabled = custom != null && panel == CompositionPanel.CUSTOM,
                            shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, CameraColors.Accent)) {
                            Text("Save", color = if (custom != null) CameraColors.Accent else CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
        }
        }
    }
    Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 4.dp, end = 4.dp)
        .onGloballyPositioned { onToolsBounds(it.boundsInRoot()) }
        .widthIn(max = 392.dp).fillMaxWidth().height(48.dp).testTag("Camera top tools"),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val cropBadge = preferences.photoCrop.takeIf { it.ratio != CropRatio.NATIVE }?.let { crop ->
            val pixels = preferences.videoPixelCrop.takeIf { videoMode }
            if (pixels != null) {
                if (crop.swapped) pixels.aspectLabel.split(":").reversed().joinToString(":") else pixels.aspectLabel
            } else crop.label(!videoMode && abs(rotation) != 90f)
        }
        CompositionIconButton(if (videoMode) "Video crop" else "Photo crop", CompositionGlyph.CROP, preferences.photoCrop.ratio != CropRatio.NATIVE,
            { if (videoMode && onVideoCrop != null) { close(); onVideoCrop() } else open(CompositionPanel.CROP) },
            badge = cropBadge,
            onLongClick = { tooltip = if (videoMode) "Video crop" else "Photo crop" },
            enabled = cropEnabled, modifier = Modifier.weight(1f))
        CompositionIconButton("Exposure zebra", CompositionGlyph.ZEBRA, preferences.exposureZebraEnabled,
            { onChange(preferences.copy(exposureZebraEnabled = !preferences.exposureZebraEnabled)) },
            checked = preferences.exposureZebraEnabled, modifier = Modifier.weight(1f),
            onLongClick = { tooltip = "Zebra follows VF-S: blue shadows, red highlights" })
        CompositionIconButton("Histogram overlay", CompositionGlyph.HISTOGRAM, preferences.histogramEnabled,
            { onChange(preferences.copy(histogramEnabled = !preferences.histogramEnabled)) },
            checked = preferences.histogramEnabled, onLongClick = { tooltip = "RGB histogram" }, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1f).height(48.dp).onGloballyPositioned { onMenuBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); close(); onMore() }
            .languageSemantics { language -> contentDescription = language.text("More look controls"); stateDescription = language.text(if (moreOpen) "Expanded" else "Collapsed") },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(topToolSize()).previewControlSurface(androidx.compose.foundation.shape.CircleShape, selected = moreOpen),
                contentAlignment = Alignment.Center) {
                CameraGlyphIcon(CameraGlyph.Manual, Modifier.size(18.dp).animatedCameraRotation(rotation),
                    color = if (moreOpen) CameraColors.Accent else CameraColors.Muted)
            }
        }
        val flash = if (flashAvailable) preferences.flashMode else FlashMode.OFF
        CompositionIconButton("Flash", if (flash == FlashMode.OFF) CompositionGlyph.FLASH_OFF else CompositionGlyph.FLASH,
            flash != FlashMode.OFF, {
                val next = preferences.flashMode.next()
                onChange(preferences.copy(flashMode = next))
                tooltip = "Flash: ${next.label}"
            }, badge = flash.badge, state = if (flashAvailable) flash.label else "Unavailable on this camera",
            enabled = flashAvailable && !captureBusy, onLongClick = { tooltip = "Flash: ${flash.label}" }, modifier = Modifier.weight(1f))
        // With the loupe selected, the autofocus assist opens the loupe after tap to focus.
        if (preferences.focusAssist == com.unspektrawesome.settings.FocusAssist.LOUPE)
            CompositionIconButton("Loupe on tap to focus", CompositionGlyph.PEAKING, preferences.loupeOnTap,
                { onChange(preferences.copy(loupeOnTap = !preferences.loupeOnTap)) },
                checked = preferences.loupeOnTap, modifier = Modifier.weight(1f),
                onLongClick = { tooltip = "Loupe on tap to focus" })
        else CompositionIconButton("Autofocus peaking", CompositionGlyph.PEAKING, preferences.autofocusPeakingEnabled,
            { onChange(preferences.copy(autofocusPeakingEnabled = !preferences.autofocusPeakingEnabled)) },
            checked = preferences.autofocusPeakingEnabled, modifier = Modifier.weight(1f),
            onLongClick = { tooltip = "Focus peaking during autofocus" })
        CompositionIconButton("Maximum screen brightness", CompositionGlyph.SUN, preferences.maxBrightness,
            { onChange(preferences.copy(maxBrightness = !preferences.maxBrightness)) },
            state = if (preferences.maxBrightness) "Maximum" else "Follow device", onLongClick = { tooltip = "Maximum screen brightness" },
            modifier = Modifier.weight(1f))
    }
    tooltip?.let {
        RotatingCameraPanel(rotation, Modifier.align(Alignment.TopCenter).padding(top = 66.dp, start = 12.dp, end = 12.dp)
            .heightIn(max = panelMaxHeight.coerceAtLeast(64.dp))) {
            Text(it, color = CameraColors.Text, fontSize = 12.sp,
                modifier = Modifier.background(CameraColors.Glass, RoundedCornerShape(10.dp)).padding(10.dp))
        }
    }
}

@Composable
private fun CropResolution(crop: PhotoCrop, size: (PhotoCrop) -> Size2d?, modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start) {
    val output = size(crop)
    Text(output?.let { "${it.width} × ${it.height} · ${String.format(java.util.Locale.ROOT, "%.1f", it.width.toDouble() * it.height / 1_000_000)} MP" }
        ?: "Crop before spektrafilm", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = CameraTypography.Readout,
        textAlign = textAlign, modifier = modifier.testTag("Crop resolution"))
}

@Composable
internal fun CropNumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, ime: ImeAction, onDone: () -> Unit) {
    BasicTextField(value, { if (it.length <= 9) onChange(it) }, singleLine = true,
        textStyle = TextStyle(color = CameraColors.Text, fontSize = 14.sp, fontFamily = CameraTypography.Readout, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(CameraColors.Accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(22.dp)).background(CameraColors.Background)
            .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(22.dp)).padding(10.dp)
            .languageSemantics { language -> contentDescription = language.text(label) })
}

@Composable
internal fun CompositionIconButton(label: String, glyph: CompositionGlyph, active: Boolean, onClick: () -> Unit,
    badge: String? = null, state: String = if (active) "On" else "Off", enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null, checked: Boolean? = null, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
        .combinedClickable(enabled = enabled, role = if (checked == null) Role.Button else Role.Switch, onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }, onLongClick = onLongClick)
        .languageSemantics { language ->
            contentDescription = language.text(label)
            stateDescription = language.text(state)
            if (checked != null) toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
        }, contentAlignment = Alignment.Center) {
    Column(Modifier.size(topToolSize()).previewControlSurface(androidx.compose.foundation.shape.CircleShape, selected = active && enabled),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val ink = if (active && enabled) CameraColors.Accent else CameraColors.Text.copy(alpha = if (enabled) 1f else .4f)
        Column(Modifier.animatedCameraRotation(LocalCameraRotation.current), horizontalAlignment = Alignment.CenterHorizontally) {
        CameraIcon(when (glyph) {
            CompositionGlyph.CROP -> com.unspektrawesome.R.drawable.ic_sleek_crop
            CompositionGlyph.ZEBRA -> com.unspektrawesome.R.drawable.ic_sleek_contrast
            CompositionGlyph.HISTOGRAM -> com.unspektrawesome.R.drawable.ic_sleek_bar_chart_3
            CompositionGlyph.PEAKING -> com.unspektrawesome.R.drawable.ic_sleek_focus
            CompositionGlyph.FLASH -> com.unspektrawesome.R.drawable.ic_sleek_zap
            CompositionGlyph.FLASH_OFF -> com.unspektrawesome.R.drawable.ic_sleek_zap_off
            CompositionGlyph.SUN -> com.unspektrawesome.R.drawable.ic_sleek_sun
        }, Modifier.size(if (badge == null) 18.dp else 16.dp), ink)
        if (badge != null) Text(badge, color = ink, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, fontWeight = FontWeight.Medium)
        }
    }
}
}
