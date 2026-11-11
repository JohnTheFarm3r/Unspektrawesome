package com.unspektrawesome.ui.camera

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.Size2d
import com.unspektrawesome.capture.CropRatio
import com.unspektrawesome.capture.PhotoCrop
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.video.VideoCropPresets
import com.unspektrawesome.video.VideoDownscale
import com.unspektrawesome.video.VideoPixelCrop
import com.unspektrawesome.video.VideoRecordingState
import com.unspektrawesome.video.landscapeVideoCrop

@Composable
internal fun VideoFramingDialog(visible: Boolean, preferences: CameraPreferences, state: VideoRecordingState,
    rotation: Float, topInset: Dp, sideInset: Dp, bottomInset: Dp, onChange: (CameraPreferences) -> Unit,
    onDownscale: (VideoDownscale) -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(visible, onClose)
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.matchParentSize().background(Color.Transparent)
                .clickable(interactionSource = null, indication = null, onClick = onClose))
            CameraCropPanel(rotation, topInset, sideInset, bottomInset) {
                Column(Modifier.testTag("Video crop dialog")) {
                    VideoFramingControls(preferences, state, onChange, onDownscale, onClose)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoFramingControls(preferences: CameraPreferences, state: VideoRecordingState,
    onChange: (CameraPreferences) -> Unit, onDownscale: (VideoDownscale) -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    var pixelTab by rememberSaveable { mutableStateOf(preferences.videoPixelCrop != null) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var width by rememberSaveable { mutableStateOf("16") }
    var height by rememberSaveable { mutableStateOf("9") }
    val crop = preferences.videoCrop.landscapeVideoCrop()
    val pixel = preferences.videoPixelCrop
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    fun choose(value: PhotoCrop, pixels: VideoPixelCrop? = null) {
        if (!state.busy) onChange(preferences.copy(videoCrop = value.landscapeVideoCrop(),
            videoPixelCrop = pixels, selectedVideoCropId = null))
    }
    val presets = VideoCropPresets.aspects.let { entries ->
        if (pixel == null && crop !in entries) entries + crop else entries
    }
    Column(modifier.animateContentSize(tween(240)).testTag("Video framing controls")) {
        CameraCropHeader("VIDEO CROP", onClose, Modifier.testTag("Close video framing"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VideoSegments(listOf(
            VideoSegment("Aspect", !pixelTab, Modifier.testTag("Video aspect tab")) { pixelTab = false; custom = false },
            VideoSegment("Pixel crop", pixelTab, Modifier.testTag("Video pixel tab")) { pixelTab = true; custom = false },
        ))
        AnimatedContent(pixelTab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
            label = "video_crop_type") { showPixels ->
            val list = rememberLazyListState()
            val fling = rememberPanelFlingBehavior(horizontal = true)
            LaunchedEffect(showPixels) {
                val selected = if (showPixels) VideoPixelCrop.entries.indexOf(pixel) else presets.indexOf(crop)
                list.scrollToItem(selected.coerceAtLeast(0))
            }
            Box(Modifier.fillMaxWidth()) {
                val cardWidth = 72.dp * kotlin.math.sqrt(fontScale)
                LazyRow(state = list, flingBehavior = fling, overscrollEffect = null,
                    modifier = Modifier.fillMaxWidth().trackPanelFling(fling).testTag("Video crop presets").selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showPixels) items(VideoPixelCrop.entries, key = { it.name }) { preset ->
                        VideoCropCard(AppLanguage.format("{width} × {height}", mapOf("width" to preset.width, "height" to preset.height)),
                            preset.aspectLabel, preset.width.toFloat() / preset.height, pixel == preset,
                            !state.busy && preset in state.availablePixelCrops, "video_pixel_${preset.name}", cardWidth) {
                            choose(preset.aspect(), preset)
                        }
                    } else {
                        items(presets, key = { it.toJson().toString() }) { preset ->
                            val raw = state.rawSize.takeIf { preset.ratio == CropRatio.NATIVE } ?: Size2d(4, 3)
                            val aspect = preset.sourceAspect(Size2d(maxOf(raw.width, raw.height), minOf(raw.width, raw.height))).toFloat()
                            VideoCropCard(AppLanguage.text(preset.label()), null, aspect, pixel == null && crop == preset,
                                !state.busy, "video_aspect_${preset.label()}", cardWidth) { custom = false; choose(preset) }
                        }
                        item {
                            VideoCropCard(AppLanguage.text("Custom"), null, 1f, custom, !state.busy, "video_custom_crop", cardWidth) {
                                val ratio = crop.ratio
                                width = PhotoCrop.number(if (ratio == CropRatio.CUSTOM) crop.customWidth else ratio.width.takeIf { it > 0 } ?: 16.0)
                                height = PhotoCrop.number(if (ratio == CropRatio.CUSTOM) crop.customHeight else ratio.height.takeIf { it > 0 } ?: 9.0)
                                custom = !custom
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(custom, enter = fadeIn(tween(180)) + expandVertically(tween(240)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(240))) {
            val w = width.replace(',', '.').toDoubleOrNull()
            val h = height.replace(',', '.').toDoubleOrNull()
            val valid = w != null && h != null && PhotoCrop.validCustom(w, h)
            fun applyCustom() { if (valid) { choose(PhotoCrop(CropRatio.CUSTOM, w, h)); custom = false } }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CropNumberField("Crop width", width, { width = it }, Modifier.weight(1f), ImeAction.Next) {}
                    Text(":", color = CameraColors.Muted)
                    CropNumberField("Crop height", height, { height = it }, Modifier.weight(1f), ImeAction.Done, ::applyCustom)
                }
                TextButton(onClick = ::applyCustom, enabled = valid && !state.busy) { Text("Apply", color = CameraColors.Accent) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Downscale", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            VideoSegments(VideoDownscale.entries.map { factor ->
                VideoSegment(AppLanguage.format("{value1}x", mapOf("value1" to factor.factor.toString())),
                    state.downscale == factor, Modifier.testTag("Video downscale ${factor.name}"), !state.busy && factor in state.available) { onDownscale(factor) }
            })
        }
        HorizontalDivider(color = CameraColors.MenuBorder)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Recording size", color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(end = 12.dp))
            val size = state.displayOutputSize
            Text(if (size != null) "{width} × {height}" else "Unavailable",
                arguments = if (size != null) mapOf("width" to size.width, "height" to size.height) else emptyMap(),
                modifier = Modifier.testTag("Video framing output"), color = CameraColors.Accent,
                fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        state.outputIssue?.let { Text(it, color = CameraColors.Warning, fontSize = 11.sp, lineHeight = 15.sp) }
        }
    }
}

private data class VideoSegment(val label: String, val selected: Boolean, val modifier: Modifier,
    val enabled: Boolean = true, val onClick: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoSegments(choices: List<VideoSegment>) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
    val minimumWidth = with(density) {
        choices.maxOf { measurer.measure(AppLanguage.text(it.label), style).size.width }.toDp() + 24.dp
    }
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val shape = RoundedCornerShape(22.dp)
        val availableWidth = maxWidth
        if (minimumWidth * choices.size <= maxWidth) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape)
                .border(1.dp, CameraColors.MenuBorder, shape)) {
                choices.forEachIndexed { index, choice ->
                    if (index > 0) Box(Modifier.padding(vertical = 6.dp).width(1.dp).fillMaxHeight().background(CameraColors.MenuBorder))
                    VideoSegmentButton(choice, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { VideoSegmentButton(it, Modifier.width(minimumWidth.coerceAtMost(availableWidth))
                .border(1.dp, CameraColors.MenuBorder, shape)) }
        }
    }
}

@Composable
private fun VideoSegmentButton(choice: VideoSegment, modifier: Modifier) {
    val background by animateColorAsState(if (choice.selected) CameraColors.MenuSelectedCard else Color.Transparent,
        tween(180), label = "Video segment background")
    val ink by animateColorAsState(if (!choice.enabled) CameraColors.Muted.copy(alpha = .4f)
        else if (choice.selected) CameraColors.Accent else CameraColors.Muted, tween(180), label = "Video segment ink")
    val border by animateColorAsState(if (choice.selected) CameraColors.Accent else Color.Transparent,
        tween(180), label = "Video segment border")
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.heightIn(min = 34.dp).clip(shape).background(background).border(1.dp, border, shape)
        .selectable(choice.selected, choice.enabled, Role.RadioButton, onClick = choice.onClick).then(choice.modifier)
        .padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(choice.label, modifier = Modifier.fillMaxWidth(), color = ink, fontSize = 11.sp, lineHeight = 15.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun VideoCropCard(label: String, subtitle: String?, aspect: Float, selected: Boolean, enabled: Boolean,
    tag: String, width: Dp, description: String? = null, onEdit: (() -> Unit)? = null,
    minimumHeight: Dp = if (subtitle == null) 60.dp else 68.dp,
    drawing: (DrawScope.(Color) -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val ink by animateColorAsState(if (!enabled) CameraColors.Muted.copy(alpha = .4f)
        else if (selected) CameraColors.Accent else CameraColors.Text, tween(180), label = "Video crop ink")
    val border by animateColorAsState(if (selected) CameraColors.Accent else CameraColors.MenuBorder,
        tween(180), label = "Video crop border")
    val background by animateColorAsState(if (selected) CameraColors.MenuSelectedCard else CameraColors.MenuCard,
        tween(180), label = "Video crop background")
    Column(Modifier.width(width).heightIn(min = minimumHeight).clip(shape)
        .border(1.dp, border, shape)
        .combinedClickable(enabled = enabled, role = Role.RadioButton, onClick = onClick, onLongClick = onEdit,
            onLongClickLabel = if (onEdit != null) AppLanguage.text("Edit saved crop") else null)
        .testTag(tag).languageSemantics { language ->
            this.selected = selected
            contentDescription = description?.let(language::text) ?: language.format("Crop {value1}", mapOf("value1" to label))
        }
        .padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Canvas(Modifier.width(36.dp).height(22.dp)) { if (drawing != null) drawing(ink) else drawCropCard(aspect, ink) }
        Text(label, modifier = Modifier.fillMaxWidth(), translate = false, color = ink, fontSize = if (subtitle == null) 12.sp else 10.sp,
            lineHeight = if (subtitle == null) 16.sp else 12.sp, textAlign = TextAlign.Center)
        subtitle?.let { Text(it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            translate = false, color = ink, fontSize = 10.sp, lineHeight = 12.sp) }
    }
}

@Composable
internal fun VideoOutputIndicator(state: VideoRecordingState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (!state.enabled || state.busy) return
    val size = state.displayOutputSize
    Text(if (size != null) "{width} × {height}" else "Unavailable",
        arguments = if (size != null) mapOf("width" to size.width, "height" to size.height) else emptyMap(),
        modifier = modifier.testTag("Video output indicator").clip(RoundedCornerShape(8.dp))
            .background(CameraColors.RecordingOverlay).clickable(enabled = !state.busy, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp).languageSemantics { language ->
                contentDescription = if (size != null) language.format("Recording size: {width} × {height}",
                    mapOf("width" to size.width, "height" to size.height)) else language.text(state.outputIssue ?: "Video is unavailable for this lens")
            },
        color = if (state.outputSupported) CameraColors.Text else CameraColors.Warning,
        fontSize = 11.sp, lineHeight = 14.sp, fontFamily = com.unspektrawesome.ui.camera.CameraTypography.Readout)
}
