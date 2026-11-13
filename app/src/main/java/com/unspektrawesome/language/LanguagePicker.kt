package com.unspektrawesome.language

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.ui.camera.AmbientScreen
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraWindowBackdrop
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramButton
import com.unspektrawesome.ui.camera.DiagramFlow
import com.unspektrawesome.ui.camera.DiagramNodeState
import com.unspektrawesome.ui.camera.DiagramStep
import com.unspektrawesome.ui.camera.LocalCameraWindowRotation
import com.unspektrawesome.ui.camera.RotatingCameraPanel
import com.unspektrawesome.ui.camera.SettingsIcon
import com.unspektrawesome.ui.camera.SettingsSymbol
import com.unspektrawesome.ui.camera.diagramHairline
import com.unspektrawesome.ui.onboarding.OnboardingHeader
import com.unspektrawesome.ui.onboarding.OnboardingNavigation

@Composable
internal fun LanguagePicker(firstLaunch: Boolean, onDone: () -> Unit) {
    AmbientScreen(Modifier.fillMaxSize()) {
        LanguagePickerContent(firstLaunch, !firstLaunch, Modifier.fillMaxSize(), onDone)
    }
}

/** First launch shows the list full screen; settings shows the same list in a compact window. */
@Composable
private fun LanguagePickerContent(firstLaunch: Boolean, compact: Boolean, modifier: Modifier, onDone: () -> Unit) {
    val language by AppLanguage.state.collectAsState()
    fun confirm() {
        if (language.selectedCode != "custom") AppLanguage.select(language.selectedCode)
        onDone()
    }
    Column(modifier
        .then(if (firstLaunch) Modifier.safeDrawingPadding() else Modifier)
        .padding(horizontal = if (compact) 14.dp else 20.dp).padding(top = if (compact) 14.dp else 0.dp).testTag("Language picker")) {
        if (firstLaunch) OnboardingHeader() else if (!compact) Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = if (compact) 0.dp else 20.dp, bottom = if (compact) 8.dp else 16.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageText("Choose your language", color = CameraColors.Text, fontSize = if (compact) 16.sp else 28.sp,
                lineHeight = if (compact) 21.sp else 34.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() })
            LanguageText("${BuiltInLanguage.all.size}", translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp,
                fontFamily = Diagram.Mono, modifier = Modifier.padding(bottom = 3.dp).clearAndSetSemantics { })
        }
        LazyColumn(Modifier.weight(1f).selectableGroup().testTag("Language list"),
            contentPadding = PaddingValues(bottom = if (compact) 6.dp else 24.dp)) {
            items(BuiltInLanguage.all, key = { it.code }) { item ->
                LanguageRow(item, item.code == language.selectedCode, compact) {
                    AppLanguage.select(item.code, confirm = !firstLaunch)
                }
            }
            item { MoreLanguagesNote(compact) }
        }
        if (compact) Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            DiagramButton("Done", accent = true, onClick = ::confirm)
        } else OnboardingNavigation(primaryLabel = if (firstLaunch) "Continue" else "Done",
            primaryModifier = Modifier.testTag("Confirm language"),
            sizingLabels = listOf("Continue", "Done"), onPrimary = ::confirm)
    }
}

@Composable
private fun LanguageRow(item: BuiltInLanguage, selected: Boolean, compact: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .testTag("Language option ${item.code}")
        .heightIn(min = if (compact) 44.dp else 54.dp)
        .diagramHairline()
        .drawBehind { if (selected) drawLine(CameraColors.Accent, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx()) }
        .background(if (selected) CameraColors.Accent.copy(alpha = .04f) else Color.Transparent)
        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
            LanguageText(item.flag, translate = false, fontSize = if (compact) 18.sp else 24.sp, lineHeight = if (compact) 22.sp else 28.sp,
                modifier = Modifier.width(if (compact) 26.dp else 34.dp))
        }
        LanguageText(item.nativeName, translate = false, color = if (selected) CameraColors.Accent else CameraColors.Text,
            fontSize = if (compact) 13.5.sp else 16.sp, lineHeight = if (compact) 18.sp else 21.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) SettingsIcon(SettingsSymbol.Check, Modifier.size(14.dp), CameraColors.Accent)
        LanguageText(item.code.uppercase(), translate = false, color = Color(0xFF8C8C8C), fontSize = 10.sp, lineHeight = 14.sp,
            fontFamily = Diagram.Mono, textAlign = TextAlign.End, modifier = Modifier.width(22.dp).clearAndSetSemantics { })
    }
}

@Composable
private fun MoreLanguagesNote(compact: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 10.dp else 18.dp).clip(Diagram.ControlShape)
        .background(Diagram.Glass).border(1.dp, Diagram.Border, Diagram.ControlShape).padding(10.dp)
        .testTag("More languages information"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        LanguageText("For more languages, export the English template in Settings, translate it into your language, then import it again.",
            color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 16.sp)
        DiagramFlow(listOf(DiagramStep("Export template"), DiagramStep("Translate"), DiagramStep("Import", state = DiagramNodeState.Active)),
            Modifier.fillMaxWidth().clearAndSetSemantics { })
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
                        LanguagePickerContent(false, true, Modifier.fillMaxWidth().height(minOf(520.dp, maxHeight * .9f))
                            .clip(Diagram.WindowShape).background(Color(0xF20E0E0E)).border(1.dp, Diagram.Border, Diagram.WindowShape)
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }, onDismiss)
                    }
                }
            }
        }
    }
}
