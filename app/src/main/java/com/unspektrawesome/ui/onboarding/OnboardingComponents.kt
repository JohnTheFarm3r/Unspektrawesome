package com.unspektrawesome.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.SettingsIcon
import com.unspektrawesome.ui.camera.SettingsSymbol

/** App name, an optional small Skip link and the page progress, in the settings page style. */
@Composable
internal fun OnboardingHeader(
    page: Int? = null,
    onSkip: (() -> Unit)? = null,
    cameraAccess: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(26.dp).border(1.dp, Diagram.Border, Diagram.ControlShape), contentAlignment = Alignment.Center) {
                SettingsIcon(SettingsSymbol.Aperture, Modifier.size(14.dp), CameraColors.Text)
            }
            Text("UNSPEKTRAWESOME", translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp,
                letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (onSkip != null) OnboardingSkip(onSkip)
        }
        if (page != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(ONBOARDING_PAGES) { index ->
                        Box(Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp))
                            .background(if (index <= page) CameraColors.Accent else Diagram.Border))
                    }
                }
                val counter = "${page + 1} / $ONBOARDING_PAGES"
                Text(counter, translate = false, color = Diagram.Hint, fontSize = 10.sp,
                    lineHeight = 14.sp, fontFamily = Diagram.Mono)
            }
        } else if (cameraAccess) {
            Text("CAMERA ACCESS", color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp)
        }
    }
    OnboardingDivider()
}

/** Small text link that jumps to the permissions page. */
@Composable
private fun OnboardingSkip(onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).clip(Diagram.ControlShape).clickable(role = Role.Button, onClick = onClick)
        .testTag("Skip introduction").padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Skip introduction", color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 15.sp,
            textDecoration = TextDecoration.Underline, modifier = Modifier.widthIn(max = 160.dp))
    }
}

@Composable
internal fun OnboardingDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Diagram.Hairline))
}

/** Back and Continue at the bottom of the page. They share a row when the translated labels fit, and stack otherwise. */
@Composable
internal fun OnboardingNavigation(
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryModifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    secondaryModifier: Modifier = Modifier,
    secondaryEnabled: Boolean = true,
    sizingLabels: List<String> = listOfNotNull(primaryLabel, secondaryLabel),
) {
    val language = LocalAppLanguage.current.renderer
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth().drawBehind {
        drawLine(Diagram.Hairline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
    }.padding(top = 10.dp, bottom = 12.dp)) {
        val icon = 22.dp
        val naturalWidth = with(density) { sizingLabels.maxOf {
            measurer.measure(language.text(it), style).size.width + if (it == "Back") icon.roundToPx() else 0
        }.toDp() } + 28.dp
        val paired = secondaryLabel != null && naturalWidth <= (maxWidth - 8.dp) / 2
        val buttonWidth = if (paired) (maxWidth - 8.dp) / 2 else maxWidth
        val buttonHeight = with(density) {
            sizingLabels.maxOf {
                val iconWidth = if (it == "Back") icon else 0.dp
                measurer.measure(language.text(it), style,
                    constraints = Constraints(maxWidth = (buttonWidth - 28.dp - iconWidth).roundToPx().coerceAtLeast(1))).size.height
            }.toDp()
        }.plus(20.dp).coerceAtLeast(48.dp)
        val secondary: @Composable () -> Unit = {
            secondaryLabel?.let {
                OnboardingAction(it, secondaryModifier.width(buttonWidth).height(buttonHeight), primary = false,
                    symbol = SettingsSymbol.Back.takeIf { secondaryLabel == "Back" }, enabled = secondaryEnabled, onClick = onSecondary)
            }
        }
        val primary: @Composable () -> Unit = {
            OnboardingAction(primaryLabel, primaryModifier.width(buttonWidth).height(buttonHeight),
                enabled = primaryEnabled, onClick = onPrimary)
        }
        if (paired) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { secondary(); primary() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { secondary(); primary() }
    }
}

/** Outlined button with the settings corners: lime outline for the main action, grey for the other. */
@Composable
internal fun OnboardingAction(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    symbol: SettingsSymbol? = null,
    onClick: () -> Unit,
) {
    val ink = when {
        !enabled -> CameraColors.Muted.copy(alpha = .5f)
        primary -> CameraColors.Accent
        else -> CameraColors.Text
    }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Diagram.ControlShape)
        .background(if (primary && enabled) CameraColors.Accent.copy(alpha = .06f) else Diagram.Glass)
        .border(1.dp, if (primary && enabled) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
        symbol?.let { SettingsIcon(it, Modifier.size(15.dp), ink) }
        Text(label, color = ink, modifier = Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
