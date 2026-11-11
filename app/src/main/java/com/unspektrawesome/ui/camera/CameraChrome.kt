package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.preview.CameraControlState
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraLook
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

internal enum class CameraGlyph { Settings, Manual, Gallery, Flip, Grid, Timer, Chevron }
internal val LocalCameraRotation = compositionLocalOf { 0f }

@Composable
internal fun CameraGlyphIcon(glyph: CameraGlyph, modifier: Modifier = Modifier, color: Color = Color.White) {
    val drawable = when (glyph) {
        CameraGlyph.Settings -> com.unspektrawesome.R.drawable.ic_sleek_settings
        CameraGlyph.Manual -> com.unspektrawesome.R.drawable.ic_sleek_sliders_horizontal
        CameraGlyph.Gallery -> com.unspektrawesome.R.drawable.ic_sleek_image
        CameraGlyph.Flip -> com.unspektrawesome.R.drawable.ic_sleek_refresh_cw
        CameraGlyph.Grid -> com.unspektrawesome.R.drawable.ic_sleek_grid_3x3
        CameraGlyph.Timer -> com.unspektrawesome.R.drawable.ic_sleek_timer
        CameraGlyph.Chevron -> com.unspektrawesome.R.drawable.ic_sleek_chevron_right
    }
    CameraIcon(drawable, modifier.size(20.dp), color)
}

@Composable
internal fun CameraRoundAction(description: String, glyph: CameraGlyph, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Box(modifier.size(48.dp).previewControlSurface(CircleShape, selected = active)
        .clickable(enabled, role = Role.Button, onClick = onClick).languageSemantics { language -> contentDescription = language.text(description) }, contentAlignment = Alignment.Center) {
        CameraGlyphIcon(glyph, Modifier.animatedCameraRotation(LocalCameraRotation.current), color = if (!enabled) CameraColors.Muted else if (active) CameraColors.Accent else Color.White)
    }
}

@Composable
internal fun LookSummary(params: SpektraFilmParams, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rotation = LocalCameraRotation.current
    val height = (48f + (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f) * 16f).dp
    val stock = SpektraFilmParams.FILM_LABELS.getOrNull(params.film).orEmpty().removePrefix("Kodak ").removePrefix("Fujifilm ")
    val format = SpektraLook.formats.getOrNull(params.filmFormat).orEmpty().replace("Standard ", "").replace("Super ", "S") + "mm"
    val paper = if (params.isMonochromeFilm) "" else
        SpektraFilmParams.PAPER_LABELS.getOrNull(params.paper).orEmpty().removePrefix("Kodak ").removePrefix("Fujifilm ")
    Row(modifier.height(height).previewControlSurface(CircleShape)
        .clickable(role = Role.Button, onClick = onClick).languageSemantics { language -> contentDescription = language.text("Choose film stock"); stateDescription = language.text(if (expanded) "Expanded" else "Collapsed") }
        .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (abs(rotation) == 90f) {
            listOf(if (params.enabled) stock else "spektrafilm OFF", format, paper).filter { it.isNotEmpty() }.forEach { label ->
                OrientedLookPanel(true, rotation, Modifier.weight(1f).height(height - 4.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(label, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else Text((if (params.enabled) "$stock  ·  $format  ·  $paper" else "spektrafilm OFF  ·  $stock").trimEnd(' ', '·'), modifier = Modifier.weight(1f), color = Color.White,
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        CameraGlyphIcon(CameraGlyph.Chevron, Modifier.size(16.dp).animatedCameraRotation(rotation))
    }
}

@Composable
internal fun SensorExposureRail(controls: CameraControlState, onChange: (Int) -> Boolean,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val minimum = controls.exposureCompensationMin
    val maximum = controls.exposureCompensationMax
    CameraAdjustmentRail(
        title = if (controls.autoExposure) "EV" else "M",
        description = "Sensor exposure compensation",
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        value = controls.exposureCompensation,
        minimum = minimum,
        maximum = maximum,
        supported = minimum < maximum && controls.autoExposure,
        label = { if (it == 0) "0.0" else String.format(Locale.ROOT, "%+.1f", it * controls.exposureCompensationStepEv) },
        onChange = onChange,
        modifier = modifier,
    )
}

@Composable
internal fun ShadowsRail(value: Float, onChange: (Float) -> Unit,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    CameraAdjustmentRail(
        title = "SH",
        description = "Shadows",
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        value = (value * 20f).roundToInt(),
        minimum = 0,
        maximum = 20,
        supported = true,
        label = { String.format(Locale.ROOT, "%.2f", it / 20f) },
        onChange = { onChange(it / 20f); true },
        modifier = modifier,
    )
}

@Composable
internal fun HighlightCompressionRail(value: Float, onChange: (Float) -> Unit,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    CameraAdjustmentRail(
        title = "HC",
        description = "Highlight Compression",
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        value = (value * 20f).roundToInt(),
        minimum = -20,
        maximum = 20,
        supported = true,
        label = { if (it == 0) "0.00" else String.format(Locale.ROOT, "%+.2f", it / 20f) },
        onChange = { onChange(it / 20f); true },
        modifier = modifier,
    )
}

@Composable
internal fun AeBalanceRail(value: Float, supported: Boolean, onChange: (Float) -> Unit,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    CameraAdjustmentRail(
        title = "AE BAL",
        description = "AE balance",
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        value = (com.unspektrawesome.camera.exposure.AeBalance.normalize(value) * 10).roundToInt(),
        minimum = -30,
        maximum = 30,
        supported = supported,
        label = { if (it == 0) "0.0" else String.format(Locale.ROOT, "%+.1f", it / 10f) },
        valueDescription = {
            when {
                it == 0 -> "Balanced"
                it < 0 -> "Lower ISO ${abs(it) / 10f} stops"
                else -> "Faster shutter ${it / 10f} stops"
            }
        },
        onChange = { onChange(it / 10f); true },
        modifier = modifier.testTag("AE balance rail"),
    )
}

@Composable
private fun CameraAdjustmentRail(
    title: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    value: Int,
    minimum: Int,
    maximum: Int,
    supported: Boolean,
    label: (Int) -> String,
    onChange: (Int) -> Boolean,
    modifier: Modifier,
    valueDescription: (Int) -> String = label,
) {
    val haptics = LocalHapticFeedback.current
    val rotation = rememberAnimatedCameraRotation(LocalCameraRotation.current)
    val landscapeFraction = abs(sin(Math.toRadians(rotation.toDouble()))).toFloat()
    CollapsibleCameraRail(title, description, label(value), valueDescription(value), supported,
        expanded, onExpandedChange, modifier) { onInteraction ->
        val changeValue: (Int) -> Boolean = {
            onInteraction()
            onChange(it)
        }
        Column(
            Modifier.width(42.dp).railGlass().padding(top = 7.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RotatingCameraPanel(LocalCameraRotation.current, Modifier.heightIn(max = 42.dp)) {
                CameraRailReadings(title)
            }
            Spacer(Modifier.height(3.6.dp))
            if (minimum >= maximum) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("…", color = CameraColors.Muted, fontSize = 20.sp)
                }
            } else key(minimum, maximum) {
                val listState = rememberLazyListState(maximum - value.coerceIn(minimum, maximum))
                val fling = rememberPanelFlingBehavior(default = rememberSnapFlingBehavior(listState))
                val scope = rememberCoroutineScope()
                val currentOnChange by rememberUpdatedState(changeValue)
                val currentSupported by rememberUpdatedState(supported)
                val currentValue by rememberUpdatedState(value)
                fun centeredIndex(): Int {
                    val layout = listState.layoutInfo
                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                    return layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
                        ?: (maximum - currentValue)
                }
                LaunchedEffect(value, supported) {
                    if (!listState.isScrollInProgress) {
                        listState.requestScrollToItem(maximum - value.coerceIn(minimum, maximum))
                    }
                }
                LaunchedEffect(listState) {
                    var wasScrolling = false
                    var lastRequested: Int? = null
                    snapshotFlow { listState.isScrollInProgress to centeredIndex() }.collect { (scrolling, index) ->
                        if (scrolling && !wasScrolling) lastRequested = null
                        if ((scrolling || wasScrolling) && currentSupported) {
                            val value = maximum - index
                            if (value != lastRequested) {
                                val changed = value != currentValue
                                if (!changed || currentOnChange(value)) {
                                    if (changed) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    lastRequested = value
                                } else if (!scrolling) {
                                    listState.requestScrollToItem(maximum - currentValue.coerceIn(minimum, maximum))
                                }
                            }
                        }
                        wasScrolling = scrolling
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)
                    .languageSemantics { language ->
                        contentDescription = language.text(description)
                        stateDescription = language.text(valueDescription(currentValue))
                        progressBarRangeInfo = ProgressBarRangeInfo(currentValue.toFloat(),
                            minimum.toFloat()..maximum.toFloat(), (maximum - minimum - 1).coerceAtLeast(0))
                        if (!supported) disabled()
                        setProgress { supported && changeValue(it.roundToInt().coerceIn(minimum, maximum)) }
                    }) {
                    val rowHeight = minOf(27.6.dp + 14.4.dp * landscapeFraction, maxHeight)
                    LaunchedEffect(rowHeight) {
                        if (!listState.isScrollInProgress) {
                            listState.requestScrollToItem(maximum - currentValue.coerceIn(minimum, maximum))
                        }
                    }
                    val padding = ((maxHeight - rowHeight) / 2).coerceAtLeast(0.dp)
                    Box(Modifier.align(Alignment.Center).fillMaxWidth().height(minOf(22.8.dp, rowHeight))
                        .rotate(rotation).testTag("$description selection").railSelection(supported))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().trackPanelFling(fling).graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.drawWithCache {
                            val fade = Brush.verticalGradient(
                                0f to Color.Transparent, .08f to Color.Black,
                                .92f to Color.Black, 1f to Color.Transparent)
                            onDrawWithContent {
                                drawContent()
                                drawRect(fade, blendMode = BlendMode.DstIn)
                            }
                        },
                        contentPadding = PaddingValues(vertical = padding),
                        flingBehavior = fling,
                        userScrollEnabled = supported,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(maximum - minimum + 1, key = { it }) { index ->
                            val selected = centeredIndex() == index
                            val valueLabel = label(maximum - index)
                            Box(Modifier.fillMaxWidth().height(rowHeight)
                                .drawWithCache {
                                    onDrawBehind {
                                        if (index < maximum - minimum) drawLine(
                                            CameraColors.Muted.copy(alpha = .46f),
                                            Offset(size.width / 2 - 2.4.dp.toPx(), size.height),
                                            Offset(size.width / 2 + 2.4.dp.toPx(), size.height),
                                            .6.dp.toPx(), StrokeCap.Round)
                                    }
                                }
                                .combinedClickable(enabled = supported,
                                    onClick = { scope.launch { listState.animateScrollToItem(index) } },
                                    onDoubleClick = {
                                        if (0 in minimum..maximum && currentOnChange(0)) {
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            listState.requestScrollToItem(maximum)
                                        }
                                    }),
                                contentAlignment = Alignment.Center) {
                                Text(valueLabel, Modifier.rotate(rotation),
                                    color = if (selected && supported) CameraColors.Accent
                                        else CameraColors.Muted.copy(alpha = if (supported) 1f else .4f),
                                    fontSize = if (selected) 9.6.sp else 7.2.sp,
                                    lineHeight = if (selected) 12.sp else 10.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CaptureActions(viewfinderSpektraEnabled: Boolean, busy: Boolean, lastPhoto: Uri?, onGallery: () -> Unit, onViewfinderSpektra: () -> Unit, onShutter: () -> Unit, onFlip: () -> Unit, canFlip: Boolean, onSettings: () -> Unit, modifier: Modifier = Modifier, vertical: Boolean = false,
    shutterPulse: () -> Float = { 0f },
    exposureTimeNs: Long = 0L,
    exposureStartedAtMs: Long? = null,
    showBusyProgress: Boolean = true,
    onGalleryHold: () -> Unit = onGallery,
    overlayFraction: Float = 0f,
    thumbnailFeedback: CaptureFeedback? = null,
    mode: CaptureMode = CaptureMode.PHOTO,
    recording: Boolean = false,
    panoramaAuto: (@Composable (Modifier) -> Unit)? = null,
    onShutterLongPress: (() -> Unit)? = null,
    onGuideTarget: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> }) {
    val videoMode = mode == CaptureMode.VIDEO
    val burstMode = mode == CaptureMode.BURST
    val panoramaMode = mode == CaptureMode.PANORAMA || mode == CaptureMode.GRID
    val gridMode = mode == CaptureMode.GRID
    val context = LocalContext.current
    val resolver = context.contentResolver
    val haptics = LocalHapticFeedback.current
    val actionFontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val controlBackground = LocalCameraControlBackground.current
    val thumbnail by produceState<Bitmap?>(null, lastPhoto) {
        value = lastPhoto?.let { uri -> withContext(Dispatchers.IO) {
            runCatching {
                if (com.unspektrawesome.burst.BurstMedia.isBurst(context, uri))
                    return@runCatching com.unspektrawesome.burst.BurstMedia.thumbnail(context, uri)
                if (android.os.Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(128, 128), null)
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val scale = 128f / maxOf(info.size.width, info.size.height)
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
        } }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val shutterSize = if (vertical) 76.dp else (maxWidth / 5).coerceIn(58.dp, 80.dp)
        val smallActionSize = minOf((48f + (actionFontScale - 1f) * 16f).dp, ((maxWidth - 16.dp) / 5 - 4.dp).coerceAtLeast(48.dp))
        val gallery: @Composable () -> Unit = {
            GalleryThumbnailButton(onGallery, onGalleryHold, Modifier.size(48.dp)
                .onGloballyPositioned { onGuideTarget("gallery", it.boundsInRoot()) }) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(controlBackground.copy(alpha = controlBackground.alpha * overlayFraction))
                    .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(12.dp))
                    .padding(1.dp * overlayFraction).clip(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().animatedCameraRotation(LocalCameraRotation.current), contentScale = ContentScale.Crop) } ?: CameraGlyphIcon(CameraGlyph.Gallery, Modifier.animatedCameraRotation(LocalCameraRotation.current))
                }
                androidx.compose.animation.AnimatedVisibility(thumbnailFeedback != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)),
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) {
                    var lastFeedback by remember { mutableStateOf(thumbnailFeedback) }
                    SideEffect { if (thumbnailFeedback != null) lastFeedback = thumbnailFeedback }
                    (thumbnailFeedback ?: lastFeedback)?.let { feedback ->
                        Box(Modifier.size(18.dp).clip(CircleShape).background(CameraColors.Glass)
                            .border(1.dp, CameraColors.MenuBorder, CircleShape).testTag("Capture thumbnail status")
                            .languageSemantics { language -> contentDescription = language.text(feedback.label); liveRegion = LiveRegionMode.Polite },
                            contentAlignment = Alignment.Center) {
                            CaptureStatusIcon(feedback.kind, Modifier.size(12.dp).animatedCameraRotation(LocalCameraRotation.current))
                        }
                    }
                }
            }
        }
        val viewfinderToggle: @Composable () -> Unit = {
            val toggleWidth = (if (vertical) maxWidth - 16.dp else (maxWidth - 16.dp) / 5 - 4.dp)
                .coerceAtMost(if (actionFontScale > 1.3f) 84.dp else 64.dp).coerceAtLeast(smallActionSize)
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val toggleStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center)
            val toggleHeight = with(density) {
                listOf("{label} ON", "{label} OFF").maxOf { pattern ->
                    measurer.measure(AppLanguage.format(pattern, mapOf("label" to AppLanguage.text("VF-S"))), toggleStyle,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = (toggleWidth - 8.dp).roundToPx().coerceAtLeast(1))).size.height
                }.toDp()
            }.plus(8.dp).coerceAtLeast(smallActionSize)
            RotatingCameraPanel(LocalCameraRotation.current, maxWidth = toggleWidth, unboundedHeight = true) {
                Box(Modifier.size(toggleWidth, toggleHeight).clip(CircleShape)
                    .background(controlBackground.copy(alpha = controlBackground.alpha * overlayFraction))
                    .border(1.dp, if (viewfinderSpektraEnabled) CameraColors.Accent else CameraColors.PreviewControlBorder, CircleShape)
                    .clickable(role = Role.Button, onClick = onViewfinderSpektra)
                    .languageSemantics { language -> contentDescription = language.text("Viewfinder spektrafilm"); stateDescription = language.text(if (viewfinderSpektraEnabled) "On" else "Off") },
                    contentAlignment = Alignment.Center) {
                    Text(if (viewfinderSpektraEnabled) "{label} ON" else "{label} OFF", Modifier.padding(horizontal = 4.dp),
                        arguments = mapOf("label" to AppLanguage.text("VF-S")),
                        color = if (viewfinderSpektraEnabled) CameraColors.Accent else CameraColors.Muted, style = toggleStyle)
                }
            }
        }
        val shutter: @Composable () -> Unit = {
            val panoramaRecording by animateFloatAsState(if (panoramaMode && recording) 1f else 0f,
                tween(260, easing = FastOutSlowInEasing), label = "Panorama shutter shape")
            val stopShape = if (panoramaMode || panoramaRecording > 0f) panoramaRecording else if (recording) 1f else 0f
            val shutterColor by animateColorAsState(
                if (busy) CameraColors.Surface else if (videoMode || panoramaMode) CameraColors.Accent else Color.White,
                tween(180), label = "Shutter color")
            val shutterMode = mode
            val lockedRecording = onShutterLongPress.takeIf { videoMode && !recording && !busy }
            val holdSource = remember { MutableInteractionSource() }
            val holding by holdSource.collectIsPressedAsState()
            val holdProgress = remember { Animatable(0f) }
            LaunchedEffect(holding, lockedRecording != null) {
                holdProgress.snapTo(0f)
                if (holding && lockedRecording != null)
                    holdProgress.animateTo(1f, tween(SHUTTER_LOCK_HOLD_MS.toInt(), easing = LinearEasing))
            }
            CameraShutterButton(enabled = !busy, onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onShutter()
                },
                onLongClick = lockedRecording?.let { start -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    start()
                } },
                onLongClickLabel = lockedRecording?.let { AppLanguage.text("Record with exposure, focus and white balance locked") },
                interactionSource = holdSource,
                modifier = Modifier.size(shutterSize).languageSemantics { language ->
                    contentDescription = language.text(if (gridMode) { if (recording) "Stop 3×3 capture" else "Start 3×3 capture" } else if (panoramaMode) { if (recording) "Stop panorama" else "Start panorama" } else if (recording) "Stop recording" else if (videoMode) "Start recording" else if (burstMode) "Capture BURST" else "Take photo")
                    stateDescription = language.text(shutterMode.label)
                }.clip(CircleShape)
                    .background(controlBackground.copy(alpha = controlBackground.alpha * overlayFraction))) {
                Box(Modifier.fillMaxSize().padding(8.dp).graphicsLayer {
                    val scale = 1f - .18f * shutterPulse()
                    scaleX = scale
                    scaleY = scale
                }.padding(10.dp * stopShape)
                    .clip(RoundedCornerShape(shutterSize / 2 * (1f - stopShape) + 6.dp * stopShape))
                    .background(shutterColor),
                    contentAlignment = Alignment.Center) {
                    AnimatedVisibility(!panoramaMode || !recording, enter = fadeIn(tween(180, delayMillis = 80)), exit = fadeOut(tween(100))) {
                        Crossfade(shutterMode, Modifier.fillMaxSize(), tween(180), label = "shutter_mode") { mode ->
                            ShutterModeLabel(mode, if (busy) CameraColors.Text else CameraColors.Background,
                                Modifier.fillMaxSize().padding(if (recording) 2.dp else 8.dp).animatedCameraRotation(LocalCameraRotation.current))
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val strokeWidth = 2.dp.toPx()
                    drawCircle(if (busy) CameraColors.Accent.copy(alpha = .2f) else if (videoMode) CameraColors.Accent else Color.White,
                        radius = (size.minDimension - strokeWidth) / 2, style = Stroke(strokeWidth))
                    drawCircle(CameraColors.Accent.copy(alpha = shutterPulse()),
                        radius = (size.minDimension - strokeWidth) / 2, style = Stroke(strokeWidth))
                    // The lock hold fills a lime ring once a press lasts longer than a tap.
                    val progress = holdProgress.value
                    if (progress > .2f) {
                        val holdStroke = 3.dp.toPx()
                        drawArc(CameraColors.Accent, -90f, 360f * progress, false,
                            topLeft = Offset(holdStroke / 2, holdStroke / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - holdStroke, size.height - holdStroke),
                            style = Stroke(holdStroke, cap = StrokeCap.Round))
                    }
                }
                if (busy && exposureTimeNs >= 1_000_000_000L && exposureStartedAtMs != null) {
                    ShutterExposureRing(exposureStartedAtMs, exposureTimeNs, Modifier.fillMaxSize())
                } else if (busy && showBusyProgress) {
                    val transition = rememberInfiniteTransition(label = "Capture progress")
                    val angle by transition.animateFloat(0f, 360f,
                        infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "Capture ring rotation")
                    Canvas(Modifier.fillMaxSize()) {
                        val strokeWidth = 2.dp.toPx()
                        drawArc(CameraColors.Accent, angle - 90f, 100f, false,
                            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
                            style = Stroke(strokeWidth, cap = StrokeCap.Round))
                    }
                }
            }
        }
        val flip: @Composable () -> Unit = {
            if (panoramaMode) Box(Modifier.size(smallActionSize), contentAlignment = Alignment.Center) {
                panoramaAuto?.invoke(Modifier.fillMaxSize())
            } else Box(Modifier.size(smallActionSize).previewControlSurface(CircleShape)
                .clickable(enabled = canFlip, role = Role.Button, onClick = onFlip)
                .languageSemantics { language -> contentDescription = language.text("Switch front and rear camera") }, contentAlignment = Alignment.Center) {
                CameraGlyphIcon(CameraGlyph.Flip, Modifier.animatedCameraRotation(LocalCameraRotation.current), color = if (canFlip) Color.White else CameraColors.Muted)
            }
        }
        val settings: @Composable () -> Unit = { CameraRoundAction("Camera settings", CameraGlyph.Settings, onSettings,
            Modifier.onGloballyPositioned { onGuideTarget("settings", it.boundsInRoot()) }) }
        if (vertical) {
            Column(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                gallery(); viewfinderToggle(); shutter(); flip(); settings()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { gallery() }
                Box(Modifier.weight(1f).offset(x = (48.dp - shutterSize) / 4), contentAlignment = Alignment.Center) { viewfinderToggle() }
                Box(Modifier.weight(1f).height(shutterSize), contentAlignment = Alignment.TopCenter) {
                    shutter()
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { flip() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { settings() }
            }
        }
    }
}
