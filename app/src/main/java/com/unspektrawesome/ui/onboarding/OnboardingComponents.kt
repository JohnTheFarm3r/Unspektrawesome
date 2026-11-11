package com.unspektrawesome.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.pageCardBackground
import com.unspektrawesome.ui.camera.SettingsIcon
import com.unspektrawesome.ui.camera.SettingsSymbol

@Composable
internal fun OnboardingHeader(
    page: Int? = null,
    reserveSkip: Boolean = false,
    onSkip: (() -> Unit)? = null,
    cameraAccess: Boolean = false,
) {
    val language = LocalAppLanguage.current.renderer
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val brandStyle = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val actionStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp)) {
        val brandWidth = with(density) { measurer.measure("UNSPEKTRAWESOME", brandStyle).size.width.toDp() } + 38.dp
        val naturalSkipWidth = with(density) { measurer.measure(language.text("Skip introduction"), actionStyle).size.width.toDp() } + 32.dp
        val skipWidth = naturalSkipWidth.coerceAtMost(maxWidth)
        val skipHeight = with(density) {
            measurer.measure(language.text("Skip introduction"), actionStyle,
                constraints = Constraints(maxWidth = (skipWidth - 32.dp).roundToPx().coerceAtLeast(1))).size.height.toDp()
        }.plus(28.dp).coerceAtLeast(56.dp)
        val inline = brandWidth + skipWidth + 12.dp <= maxWidth
        val glyph: @Composable () -> Unit = {
            Box(Modifier.size(28.dp).border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                SettingsIcon(SettingsSymbol.Aperture, Modifier.size(16.dp), CameraColors.Text)
            }
        }
        val name: @Composable () -> Unit = {
            Text("UNSPEKTRAWESOME", translate = false, color = CameraColors.Muted, style = brandStyle)
        }
        val brand: @Composable () -> Unit = {
            if (brandWidth > maxWidth) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { glyph(); name() }
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { glyph(); name() }
        }
        val skip: @Composable () -> Unit = {
            if (onSkip != null) {
                OnboardingAction("Skip introduction", Modifier.width(skipWidth).height(skipHeight), primary = false, onClick = onSkip)
            } else Spacer(Modifier.size(skipWidth, skipHeight))
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (reserveSkip && inline) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) { brand(); skip() }
            } else {
                brand()
                if (reserveSkip) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { skip() }
            }
            if (page != null) {
                val counter = "${page + 1} / $ONBOARDING_PAGES"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(ONBOARDING_PAGES) { index ->
                            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (index <= page) CameraColors.Accent else CameraColors.MenuBorder))
                        }
                    }
                    Text(counter, translate = false,
                        color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            } else if (cameraAccess) {
                Text("CAMERA ACCESS", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
    OnboardingDivider()
}

@Composable
internal fun OnboardingDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CameraColors.MenuBorder))
}

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
    val style = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    OnboardingDivider()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
        val naturalWidth = with(density) { sizingLabels.maxOf {
            measurer.measure(language.text(it), style).size.width + if (it == "Back") 24.dp.roundToPx() else 0
        }.toDp() } + 32.dp
        val paired = secondaryLabel != null && naturalWidth <= (maxWidth - 12.dp) / 2
        val buttonWidth = if (paired) (maxWidth - 12.dp) / 2 else maxWidth
        val buttonHeight = with(density) {
            sizingLabels.maxOf {
                val iconWidth = if (it == "Back") 24.dp else 0.dp
                measurer.measure(language.text(it), style,
                    constraints = Constraints(maxWidth = (buttonWidth - 32.dp - iconWidth).roundToPx().coerceAtLeast(1))).size.height
            }.toDp()
        }.plus(28.dp).coerceAtLeast(56.dp)
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
        if (paired) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { secondary(); primary() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { secondary(); primary() }
    }
}

@Composable
internal fun OnboardingPanel(
    @DrawableRes symbol: Int,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).pageCardBackground()
        .border(1.dp, CameraColors.MenuBorder, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (title != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OnboardingGlyph(symbol)
                Text(title, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            content()
        } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OnboardingGlyph(symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
internal fun OnboardingGlyph(@DrawableRes symbol: Int, active: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(26.dp).border(1.dp, if (active) CameraColors.Accent else CameraColors.MenuBorder,
        RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(symbol), contentDescription = null, modifier = Modifier.size(15.dp),
            tint = if (active) CameraColors.Accent else CameraColors.Muted)
    }
}

@Composable
internal fun OnboardingAction(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    symbol: SettingsSymbol? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val ink = when {
        !enabled -> CameraColors.Muted.copy(alpha = .5f)
        primary -> CameraColors.Accent
        else -> CameraColors.Text
    }
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
        .pageCardBackground()
        .border(1.dp, if (primary && enabled) CameraColors.Accent else CameraColors.MenuBorder, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        symbol?.let { SettingsIcon(it, Modifier.size(16.dp), ink) }
        Text(label, color = ink, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
