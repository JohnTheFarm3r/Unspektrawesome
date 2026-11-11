package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LanguageText as Text

@Composable
internal fun LensToolDialog(
    title: String,
    symbol: SettingsSymbol,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnClickOutside,
            decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            CameraWindowBackdrop()
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
              if (dismissOnClickOutside) Box(Modifier.matchParentSize()
                  .clickable(interactionSource = null, indication = null, onClick = onDismiss))
              RotatingCameraPanel(LocalCameraWindowRotation.current, maxWidth = 592.dp) {
                BoxWithConstraints(modifier.fillMaxWidth().fillMaxHeight(.92f).clip(CameraStyle.PanelShape)
                    .background(CameraColors.Background).background(CameraColors.MenuBackground)
                    .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
                    val maximumFooterHeight = maxHeight * .45f
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsIcon(symbol, Modifier.size(24.dp))
                            Text(title, color = CameraColors.Text, fontSize = 16.sp, lineHeight = 22.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                        }
                        SettingsDivider()
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), content = content)
                        SettingsDivider()
                        Column(Modifier.fillMaxWidth().heightIn(max = maximumFooterHeight).verticalScroll(rememberScrollState())
                            .testTag("Lens tool footer").padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
                    }
                }
              }
            }
        }
    }
}

@Composable
internal fun LensToolAction(
    label: String,
    modifier: Modifier = Modifier,
    symbol: SettingsSymbol? = null,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink = if (!enabled) CameraColors.Muted else if (accent) CameraColors.Accent else CameraColors.Text
    Row(modifier.heightIn(min = 48.dp).clip(SettingsStyle.ChoiceShape)
        .pageCardBackground(SettingsStyle.ChoiceShape)
        .border(1.dp, if (accent && enabled) CameraColors.Accent else CameraColors.MenuBorder, SettingsStyle.ChoiceShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp).alpha(if (enabled) 1f else .4f),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        symbol?.let { SettingsIcon(it, Modifier.size(16.dp), ink) }
        Text(label, color = ink, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun LensToolActionPair(
    firstLabel: String,
    secondLabel: String,
    firstClick: () -> Unit,
    secondClick: () -> Unit,
    modifier: Modifier = Modifier,
    firstSymbol: SettingsSymbol? = null,
    secondSymbol: SettingsSymbol? = null,
    secondAccent: Boolean = false,
    firstEnabled: Boolean = true,
    secondEnabled: Boolean = true,
) {
    val language = LocalAppLanguage.current.renderer
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labels = listOf(firstLabel to firstSymbol, secondLabel to secondSymbol)
        val widest = with(density) { labels.maxOf { (label, symbol) ->
            measurer.measure(language.text(label), style).size.width + if (symbol != null) 24.dp.roundToPx() else 0
        }.toDp() } + 28.dp
        val paired = widest <= (maxWidth - 12.dp) / 2
        val buttonWidth = if (paired) (maxWidth - 12.dp) / 2 else maxWidth
        val buttonHeight = with(density) { labels.maxOf { (label, symbol) ->
            measurer.measure(language.text(label), style, constraints = Constraints(maxWidth =
                (buttonWidth - 28.dp - if (symbol != null) 24.dp else 0.dp).roundToPx().coerceAtLeast(1))).size.height
        }.toDp() }.plus(24.dp).coerceAtLeast(48.dp)
        val actions: @Composable () -> Unit = {
            LensToolAction(firstLabel, Modifier.width(buttonWidth).height(buttonHeight), firstSymbol,
                enabled = firstEnabled, onClick = firstClick)
            LensToolAction(secondLabel, Modifier.width(buttonWidth).height(buttonHeight), secondSymbol,
                accent = secondAccent, enabled = secondEnabled, onClick = secondClick)
        }
        if (paired) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
internal fun LensToolNotice(text: String) {
    SettingsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsIcon(SettingsSymbol.Info, Modifier.size(16.dp))
            Text(text, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun LensToolCheck(checked: Boolean) {
    Box(Modifier.size(22.dp).border(1.dp, if (checked) CameraColors.Accent else CameraColors.MenuBorder,
        RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (checked) SettingsIcon(SettingsSymbol.Check, Modifier.size(14.dp), CameraColors.Accent)
    }
}
