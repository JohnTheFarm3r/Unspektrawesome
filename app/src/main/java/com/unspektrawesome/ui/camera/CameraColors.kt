package com.unspektrawesome.ui.camera

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.compositionLocalOf
import com.unspektrawesome.settings.DEFAULT_CONTROL_OPACITY

internal val LocalCameraControlBackground = compositionLocalOf { CameraColors.PreviewControlBackground }

internal object CameraColors {
    const val OverlayDimAmount = .5f
    val OverlayScrim = Color.Black.copy(alpha = OverlayDimAmount)
    val Accent = Color(0xFFDDFE52)
    val ExposurePriority = Color(0xFF8CCEFF)
    val FocusFailed = Color(0xFFFF756B)
    val SlowShutter = FocusFailed
    val Warning = Color(0xFFFFD54F)
    val Background = Color.Black
    val Glass = Color(0xE6161616)
    val Tray = Color(0xF0111111)
    val Muted = Color(0xFF9A9A9A)
    val Surface = Color(0xFF1A1A1A)
    val DialogBackground = Color(0xFF161616)
    val Text = Color.White
    val Border = Color(0xFF2A2A2A)
    private val menuGradient = listOf(Background.copy(alpha = .8f), Background.copy(alpha = .9f), Background)
    val MenuBackground = Brush.verticalGradient(List(2) { Background })
    val QuickMenuBackground = Brush.verticalGradient(List(2) { Glass.copy(alpha = .9f) })
    fun viewfinderDockBackground(extension: Float): Brush = Brush.verticalGradient(menuGradient.map {
        it.copy(alpha = it.alpha * (1f - extension.coerceIn(0f, 1f)))
    })
    fun viewfinderPanelBackground(overlap: Float, opacity: Float): Brush = Brush.verticalGradient(menuGradient.map {
        it.copy(alpha = it.alpha + (opacity.coerceIn(0f, 1f) - it.alpha) * overlap.coerceIn(0f, 1f))
    })
    val MenuCard = Color(0xFF111111)
    val MenuSelectedCard = MenuCard
    val AmbientCard = MenuCard.copy(alpha = .55f)
    val MenuBorder = Border
    val PreviewControlBackground = Background.copy(alpha = DEFAULT_CONTROL_OPACITY)
    val PreviewControlBorder = Border
    fun rulerBackdrop(background: Color, top: Float, bottom: Float): Brush = Brush.verticalGradient(
        0f to background.copy(alpha = 0f),
        .15f to background,
        .95f to background,
        1f to background.copy(alpha = 0f),
        startY = top, endY = bottom,
    )
    val RecordingOverlay = Background.copy(alpha = .76f)
    val RailSelection = MenuSelectedCard.copy(alpha = .38f)
    val RailHighlight = Text.copy(alpha = .07f)
    val RailRim = Text.copy(alpha = .14f)
    val HistogramRed = Color(0xFFFF7974)
    val HistogramGreen = Color(0xFF8DDB7B)
    val HistogramBlue = Color(0xFF7FA7FF)
    val WhiteBalanceCool = Color(0xFF82B5FF)
    val WhiteBalanceNeutral = Color(0xFFF2EBDD)
    val WhiteBalanceWarm = Color(0xFFFF9974)

    fun whiteBalanceColor(fraction: Float): Color {
        val amount = fraction.coerceIn(0f, 1f)
        return if (amount < .5f) androidx.compose.ui.graphics.lerp(WhiteBalanceCool, WhiteBalanceNeutral, amount * 2f)
        else androidx.compose.ui.graphics.lerp(WhiteBalanceNeutral, WhiteBalanceWarm, (amount - .5f) * 2f)
    }
}
