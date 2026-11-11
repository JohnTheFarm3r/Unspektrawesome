package com.unspektrawesome.language

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraStyle
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.pageCardBackground
import com.unspektrawesome.ui.camera.CameraDialogAction
import com.unspektrawesome.ui.camera.CameraWindowBackdrop
import com.unspektrawesome.ui.camera.LocalCameraWindowRotation
import com.unspektrawesome.ui.camera.RotatingCameraPanel
import com.unspektrawesome.ui.camera.SettingsIcon
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.ui.onboarding.OnboardingHeader
import com.unspektrawesome.ui.onboarding.OnboardingNavigation

@Composable
internal fun LanguagePicker(firstLaunch: Boolean, onDone: () -> Unit) {
    LanguagePickerContent(firstLaunch, !firstLaunch, Modifier.fillMaxSize(), onDone)
}

@Composable
private fun LanguagePickerContent(firstLaunch: Boolean, compact: Boolean, modifier: Modifier, onDone: () -> Unit) {
    val language by AppLanguage.state.collectAsState()
    AmbientScreen(modifier) {
    Column(Modifier.fillMaxSize()
        .then(if (firstLaunch) Modifier.safeDrawingPadding() else Modifier)
        .padding(horizontal = if (compact) 12.dp else 20.dp).testTag("Language picker")) {
        if (firstLaunch) OnboardingHeader() else if (!compact) Spacer(Modifier.height(20.dp))
        LanguageText("Choose your language", color = CameraColors.Text, fontSize = if (compact) 16.sp else 28.sp,
            lineHeight = if (compact) 22.sp else 34.sp, letterSpacing = if (compact) 0.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = if (compact) 8.dp else 24.dp))
        LazyColumn(Modifier.weight(1f).selectableGroup().testTag("Language list"),
            contentPadding = PaddingValues(bottom = if (compact) 6.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
            items(BuiltInLanguage.all, key = { it.code }) { item ->
                val selected = item.code == language.selectedCode
                val shape = if (compact) CameraStyle.ControlShape else RoundedCornerShape(28.dp)
                Row(Modifier.fillMaxWidth().clip(shape)
                    .pageCardBackground()
                    .border(1.dp, if (selected) CameraColors.Accent else CameraColors.MenuBorder, shape)
                    .selectable(selected, role = Role.RadioButton) { AppLanguage.select(item.code, confirm = !firstLaunch) }
                    .testTag("Language option ${item.code}")
                    .heightIn(min = if (compact) 44.dp else 56.dp)
                    .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                            LanguageText(item.flag, translate = false, fontSize = 22.sp, lineHeight = 26.sp, modifier = Modifier.width(32.dp))
                        }
                    } else LanguageText(item.flag, translate = false, fontSize = 28.sp, modifier = Modifier.width(48.dp))
                    LanguageText(item.nativeName, translate = false, color = if (selected) CameraColors.Accent else CameraColors.Text,
                        fontSize = if (compact) 14.sp else 16.sp, lineHeight = if (compact) 20.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                        modifier = Modifier.weight(1f))
                    if (selected) {
                        if (compact) SettingsIcon(SettingsSymbol.Check, Modifier.size(18.dp), color = CameraColors.Accent)
                        else SettingsIcon(SettingsSymbol.Check, color = CameraColors.Accent)
                    }
                }
            }
            item {
                LanguageText("For more languages, export the English template in Settings, translate it into your language, then import it again.",
                    color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = if (compact) 6.dp else 16.dp)
                        .border(1.dp, CameraColors.MenuBorder, if (compact) CameraStyle.ControlShape else RoundedCornerShape(18.dp))
                        .pageCardBackground(if (compact) CameraStyle.ControlShape else RoundedCornerShape(18.dp))
                        .padding(if (compact) 8.dp else 16.dp)
                        .testTag("More languages information"))
            }
        }
        if (compact) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            CameraDialogAction("Done", true) {
                if (language.selectedCode != "custom") AppLanguage.select(language.selectedCode)
                onDone()
            }
        } else OnboardingNavigation(primaryLabel = if (firstLaunch) "Continue" else "Done",
            primaryModifier = Modifier.testTag("Confirm language"),
            sizingLabels = listOf("Continue", "Done"), onPrimary = {
                if (language.selectedCode != "custom") AppLanguage.select(language.selectedCode)
                onDone()
            })
    }
    }
}

@Composable
internal fun LanguagePickerDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CameraWindowBackdrop()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)
                .testTag("Language dialog content area"), contentAlignment = Alignment.Center) {
                RotatingCameraPanel(LocalCameraWindowRotation.current, maxWidth = 360.dp) {
                    BoxWithConstraints(Modifier.fillMaxWidth().testTag("Language picker dialog")) {
                        LanguagePickerContent(false, true, Modifier.fillMaxWidth().height(minOf(480.dp, maxHeight * .9f))
                            .clip(CameraStyle.PanelShape).border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }, onDismiss)
                    }
                }
            }
        }
    }
}
