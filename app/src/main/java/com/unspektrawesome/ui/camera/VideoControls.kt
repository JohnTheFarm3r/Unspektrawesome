package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.video.VideoRecordingState
import com.unspektrawesome.video.LiveStabilizationSettings
import java.util.Locale
import java.math.BigDecimal
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoMenuControls(state: VideoRecordingState,
    onBitrate: (Int) -> Unit, onFrameRate: (Int?) -> Unit = {}, onCaptureGyro: (Boolean) -> Unit = {},
    onOis: (Boolean) -> Unit = {}, onLiveStabilization: (Boolean) -> Unit = {},
    onStabilizationSettings: (LiveStabilizationSettings) -> Unit = {}) {
    if (!state.enabled) return
    Column(Modifier.fillMaxWidth().testTag("Video menu settings"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        VideoMenuCard {
            Row(Modifier.fillMaxWidth().padding(CameraStyle.QuickMenuRowPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Frame rate", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.width(64.dp))
                BoxWithConstraints(Modifier.weight(1f)) {
                    val density = LocalDensity.current
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    val style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    val labels = listOf(AppLanguage.text("Auto")) + state.availableFrameRates.map(Int::toString)
                    val choicePadding = if (maxWidth < 168.dp) 3.dp else 4.dp
                    val minimumWidth = with(density) { labels.maxOf { measurer.measure(it, style, softWrap = false).size.width }.toDp() } + choicePadding * 2 + 2.dp
                    val columns = ((maxWidth + 4.dp) / (minimumWidth + 4.dp)).toInt().coerceIn(1, 4)
                    val width = with(density) { ((maxWidth.roundToPx() - kotlin.math.ceil(4.dp.toPx()).toInt() * (columns - 1)) / columns).toDp() }
                    FlowRow(Modifier.fillMaxWidth().selectableGroup().testTag("Video frame rate choices"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        (listOf<Int?>(null) + state.availableFrameRates).forEach { rate ->
                            VideoMenuChoice(rate?.toString() ?: "Auto", state.frameRateOverride == rate,
                                Modifier.width(width).testTag("Video frame rate ${rate ?: "profile"}"), enabled = !state.busy, horizontalPadding = choicePadding,
                                description = rate?.let { AppLanguage.format("{value1} fps", mapOf("value1" to it)) } ?: "Auto") { onFrameRate(rate) }
                        }
                    }
                }
            }
            val output = state.displayOutputSize
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Output", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                Text(AppLanguage.format("{value1} fps", mapOf("value1" to String.format(Locale.ROOT, "%.2f", state.frameRate).trimEnd('0').trimEnd('.'))) +
                    (output?.let { " · ${it.width} × ${it.height}" } ?: ""), translate = false,
                    color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = CameraTypography.Readout,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            VideoMenuDivider()
        }
        VideoMenuCard {
            VideoSliderRow(VideoMenuGlyph.BITRATE, "Bitrate", "{value1} Mbps", bitrateNumber(state.bitrate)) {
                val range = state.bitrateRange
                if (range != null && range.maximum > range.minimum && state.available.isNotEmpty()) {
                    val minimum = (range.minimum / 1_000_000.0).toFloat()
                    val maximum = (range.maximum / 1_000_000.0).toFloat()
                    CameraCompactSlider((state.bitrate / 1_000_000.0).toFloat(), { value ->
                        onBitrate(when {
                            value >= maximum -> range.maximum
                            value <= minimum -> range.minimum
                            else -> range.clamp((value.roundToInt() * 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        })
                    }, enabled = !state.busy, valueRange = minimum..maximum, requireThumb = false,
                        minimumHeight = 36.dp, thumbSize = 16.dp,
                        onReset = { onBitrate(range.clamp(state.defaultBitrate)) },
                        modifier = Modifier.fillMaxWidth().testTag("Video bitrate slider")
                            .languageSemantics { language -> contentDescription = language.text("Video bitrate") })
                }
            }
        }
        VideoMenuCard {
            AnimatedVisibility(!state.liveStabilization,
                enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(240))) {
                Column {
                    AnimatedVisibility(!state.oisBlockedByGyro,
                        enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(240))) {
                        Column {
                            val oisStatus = when {
                                !state.oisSupported -> "OIS is unavailable for this lens"
                                !state.oisLive -> "Live OIS changes are unavailable"
                                state.oisReported == null -> "OIS state is unverified"
                                state.oisReported != (if (state.oisEnabled) 1 else 0) -> "Waiting for OIS confirmation"
                                state.oisReported == 1 -> "OIS on"
                                else -> "OIS off"
                            }
                            VideoToggle(VideoMenuGlyph.OIS, "OIS", state.oisEnabled && state.oisSupported,
                                !state.liveStabilization && state.oisSupported && state.oisLive, onOis, oisStatus)
                            VideoMenuDivider()
                        }
                    }
                    VideoToggle(VideoMenuGlyph.GYRO_DATA, "Capture gyro data", state.captureGyro,
                        !state.liveStabilization, onCaptureGyro, "Save gyro data with video")
                    if (state.busy && state.clipCaptureGyro != null && state.clipCaptureGyro != state.captureGyro)
                        Text("Applies to the next recording", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    VideoMenuDivider()
                }
            }
            VideoToggle(VideoMenuGlyph.STABILIZATION, "Live gyro stabilization", state.liveStabilization,
                !state.busy, onLiveStabilization)
            AnimatedVisibility(state.liveStabilization,
                enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(240))) {
                Column {
                    val settings = state.stabilizationSettings
                    val enabled = !state.busy && state.liveStabilization
                    StabilizationSlider(VideoMenuGlyph.CROP, "Crop", "Stabilization crop", "{value1}x",
                        String.format(Locale.ROOT, "%.2f", settings.cropRatio), settings.cropRatio, 1f..1.5f, enabled,
                        { onStabilizationSettings(settings.copy(cropRatio = it)) },
                        { onStabilizationSettings(settings.copy(cropRatio = 1.2f)) })
                    VideoMenuDivider()
                    StabilizationSlider(VideoMenuGlyph.SMOOTHING, "Smoothing", "Stabilization smoothing", "{value1} s",
                        String.format(Locale.ROOT, "%.2f", settings.smoothingSeconds), settings.smoothingSeconds, .05f..1f, enabled,
                        { onStabilizationSettings(settings.copy(smoothingSeconds = it)) },
                        { onStabilizationSettings(settings.copy(smoothingSeconds = .2f)) })
                    VideoMenuDivider()
                    StabilizationSlider(VideoMenuGlyph.STRENGTH, "Strength", "Stabilization strength", "{value1}%",
                        (settings.strength * 100).roundToInt().toString(), settings.strength, 0f..1f, enabled,
                        { onStabilizationSettings(settings.copy(strength = it)) },
                        { onStabilizationSettings(settings.copy(strength = .8f)) })
                }
            }
        }
        if (state.available.isEmpty()) Text("Video is unavailable for this lens",
            modifier = Modifier.padding(horizontal = 8.dp), color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
internal fun VideoMenuCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun VideoMenuDivider() {
    HorizontalDivider(color = CameraColors.MenuBorder, modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
private fun VideoSliderRow(icon: VideoMenuGlyph, label: String, format: String, number: String,
    slider: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(CameraStyle.QuickMenuRowPadding)) {
        val stacked = LocalDensity.current.fontScale > 1.3f
        val readout: @Composable (Modifier) -> Unit = { modifier ->
            Text(format, modifier = modifier, arguments = mapOf("value1" to number),
                color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp,
                fontFamily = CameraTypography.Readout, textAlign = TextAlign.End)
        }
        if (stacked) Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                readout(Modifier.weight(1f))
            }
            slider()
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.width(64.dp), color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Box(Modifier.weight(1f)) { slider() }
            readout(Modifier.width(64.dp))
        }
    }
}

@Composable
private fun StabilizationSlider(icon: VideoMenuGlyph, label: String, description: String, format: String,
    number: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onChange: (Float) -> Unit, onReset: () -> Unit) {
    VideoSliderRow(icon, label, format, number) {
        CameraCompactSlider(value, onChange, enabled, valueRange = range, valueStep = .01f,
            minimumHeight = 36.dp, thumbSize = 16.dp, requireThumb = false, onReset = onReset,
            modifier = Modifier.fillMaxWidth().testTag(description)
                .languageSemantics { language -> contentDescription = language.text(description) })
    }
}

@Composable
private fun VideoToggle(icon: VideoMenuGlyph, label: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag("Video toggle $label"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(CameraStyle.ControlShape)
            .border(1.dp, if (checked) CameraColors.Accent else CameraColors.MenuBorder, CameraStyle.ControlShape)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .languageSemantics { language -> contentDescription = language.text(label); toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, modifier = Modifier.fillMaxWidth(), color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
                subtitle?.let { Text(it, modifier = Modifier.fillMaxWidth(), color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 13.sp) }
            }
            Box(Modifier.size(36.dp, 20.dp).border(1.dp, if (checked) CameraColors.Accent else CameraColors.MenuBorder, RoundedCornerShape(50))) {
                Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 3.dp)
                    .size(14.dp).background(if (checked) CameraColors.Accent else CameraColors.Muted, RoundedCornerShape(50)))
            }
        }
    }
}

private fun bitrateNumber(bitrate: Int): String = BigDecimal.valueOf(bitrate.toLong(), 6).stripTrailingZeros().toPlainString()
