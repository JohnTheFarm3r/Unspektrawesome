package com.unspektrawesome.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.languageSemantics

internal val LocalCompactSettings = staticCompositionLocalOf { false }

@Composable
internal fun SettingsPage(scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll)
            .padding(horizontal = SettingsStyle.PagePadding).padding(top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun SettingsSection(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp, modifier = Modifier.semantics { heading() })
    }
}

@Composable
internal fun SettingsCard(
    title: String? = null,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    symbol: SettingsSymbol? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = Color.Transparent, shape = SettingsStyle.PanelShape,
        border = BorderStroke(1.dp, CameraColors.MenuBorder), modifier = Modifier.fillMaxWidth().pageCardBackground(SettingsStyle.PanelShape)) {
        Column(Modifier.padding(SettingsStyle.PanelPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = trailing != null && maxWidth < 300.dp * LocalDensity.current.fontScale
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            symbol?.let { SettingsIconBadge(it) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, color = CameraColors.Text, fontSize = 16.sp, lineHeight = 22.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                                detail?.let { SettingsHint(it) }
                            }
                            if (!stacked) trailing?.invoke()
                        }
                        if (stacked) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { trailing?.invoke() }
                    }
                }
            }
            content()
        }
    }
}

@Composable
internal fun SettingsLabel(label: String) {
    Text(label, color = CameraColors.Text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun SettingsHint(detail: String) {
    Text(detail, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(color = CameraColors.MenuBorder, thickness = 1.dp)
}

@Composable
internal fun SettingsFieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp * LocalDensity.current.fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SettingsChoices(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    choiceModifier: (Int) -> Modifier = { Modifier },
    onSelect: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val language = LocalAppLanguage.current.renderer
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val requiredWidth = with(density) {
            val widest = labels.maxOfOrNull { label -> measurer.measure(language.text(label), style).size.width } ?: 0
            (widest + 28.dp.toPx()) * labels.size + (labels.size - 1).coerceAtLeast(0) * 8.dp.toPx()
        }
        val fits = requiredWidth <= with(density) { maxWidth.toPx() }
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                SettingsChoice(label, selected == index,
                    choiceModifier(index).then(if (fits) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max))) { onSelect(index) }
            }
        }
    }
}

@Composable
internal fun SettingsDropdownField(
    label: String,
    value: String,
    detail: String?,
    expanded: Boolean,
    enabled: Boolean = true,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focus = LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val shape = SettingsStyle.FieldShape
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { focus.clearFocus(); onExpand() }
            .languageSemantics { language -> contentDescription = language.text(label); stateDescription = language.text(value) }
            .padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = CameraColors.Muted.copy(alpha = if (enabled) 1f else .45f),
                fontSize = 12.sp, lineHeight = 18.sp)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                .then(if (expanded) Modifier.pageCardBackground(shape) else Modifier)
                .border(1.dp, if (expanded) CameraColors.Accent else CameraColors.MenuBorder, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(value, color = if (enabled) CameraColors.Text else CameraColors.Muted.copy(alpha = .45f),
                    fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                SettingsIcon(SettingsSymbol.Dropdown, Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
                    if (expanded) CameraColors.Accent else CameraColors.Muted.copy(alpha = if (enabled) 1f else .45f))
            }
            detail?.let { SettingsHint(it) }
        }
        CompactCameraMenu(expanded && enabled, onDismiss,
            modifier = Modifier.width(maxWidth.coerceAtMost(320.dp))) {
            content()
        }
    }
}

@Composable
internal fun SettingsMenuChoice(
    label: String,
    selected: Boolean = false,
    detail: String? = null,
    description: String = label,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    symbol: SettingsSymbol? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp).fillMaxWidth().heightIn(min = 48.dp).clip(shape)
        .then(if (selected) Modifier.pageCardBackground(shape) else Modifier)
        .border(1.dp, if (selected) CameraColors.Accent else Color.Transparent, shape)
        .clickable(enabled = enabled, role = role, onClick = onClick)
        .languageSemantics { language ->
            contentDescription = language.text(description)
            if (role == Role.RadioButton) this.selected = selected
        }
        .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        symbol?.let { SettingsIcon(it, Modifier.size(18.dp),
            if (!enabled) CameraColors.Muted.copy(alpha = .45f) else if (selected) CameraColors.Accent else CameraColors.Muted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = if (!enabled) CameraColors.Muted.copy(alpha = .45f)
                else if (selected) CameraColors.Accent else CameraColors.Text,
                fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            detail?.let { SettingsHint(it) }
        }
        if (selected) SettingsIcon(SettingsSymbol.Check, Modifier.size(18.dp), CameraColors.Accent)
    }
}

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String? = null,
    error: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalCompactSettings.current
    val shape = if (compact) CameraStyle.ControlShape else SettingsStyle.FieldShape
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(CameraColors.Accent, CameraColors.Accent.copy(alpha = .3f))) {
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4, keyboardOptions = keyboardOptions,
            interactionSource = interaction, cursorBrush = SolidColor(CameraColors.Accent),
            textStyle = TextStyle(color = if (enabled) CameraColors.Text else CameraColors.Muted, fontSize = 14.sp, lineHeight = 20.sp),
            modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 64.dp else 72.dp)
                .languageSemantics { language -> if (error != null) this.error(language.text(error)) },
            decorationBox = { field ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = if (focused) CameraColors.Accent else CameraColors.Muted,
                        fontSize = 12.sp, lineHeight = 18.sp)
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                        .then(if (focused) Modifier.pageCardBackground(shape) else Modifier)
                        .border(1.dp, when { error != null -> CameraColors.FocusFailed; focused -> CameraColors.Accent; else -> CameraColors.MenuBorder }, shape)
                        .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 13.dp),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, color = CameraColors.Muted,
                            fontSize = 14.sp, lineHeight = 20.sp)
                        field()
                    }
                    error?.let { Text(it, color = CameraColors.FocusFailed, fontSize = 12.sp, lineHeight = 18.sp) }
                }
            })
    }
}
