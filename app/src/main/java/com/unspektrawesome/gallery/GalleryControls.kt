package com.unspektrawesome.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.CameraStyle
import com.unspektrawesome.ui.camera.CompactCameraMenu
import com.unspektrawesome.ui.camera.pageCardBackground

internal enum class GallerySymbol { BACK, MORE, DOWN, SHARE, DETAILS, STACK, PLAY, PAUSE, EXPAND, EXPORT }

@Composable
internal fun GalleryTitleBadge() {
    Box(Modifier.size(28.dp).border(1.dp, CameraColors.Muted.copy(alpha = .6f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        com.unspektrawesome.ui.camera.CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_aperture, Modifier.size(16.dp))
    }
}

@Composable
internal fun GalleryCameraAction(onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50))
        .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text("Camera") }
        .padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        GalleryIcon(3, CameraColors.Text, Modifier.size(14.dp))
        Text("Camera", color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
internal fun GallerySettingsSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
}

@Composable
internal fun GallerySettingsDisclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val action = AppLanguage.format(if (expanded) "Collapse {title}" else "Expand {title}", mapOf("title" to AppLanguage.text(title)))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            GallerySymbolIcon(GallerySymbol.DOWN, CameraColors.Muted, Modifier.size(16.dp).rotate(if (expanded) 180f else 0f))
        }
        if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun GalleryQueueRow(selected: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().then(if (selected) Modifier.border(1.dp, CameraColors.Accent, CameraStyle.ControlShape) else Modifier)
        .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
}

@Composable
internal fun GallerySettingToggle(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Switch) { onChange(!checked) }
        .languageSemantics { toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        com.unspektrawesome.ui.camera.SettingsSwitch(checked, label, onChange = onChange)
    }
}

@Composable
internal fun GallerySymbolIcon(symbol: GallerySymbol, color: Color, modifier: Modifier = Modifier) {
    com.unspektrawesome.ui.camera.CameraIcon(when (symbol) {
        GallerySymbol.BACK -> com.unspektrawesome.R.drawable.ic_sleek_arrow_left
        GallerySymbol.MORE -> com.unspektrawesome.R.drawable.ic_sleek_ellipsis
        GallerySymbol.DOWN -> com.unspektrawesome.R.drawable.ic_sleek_chevron_down
        GallerySymbol.DETAILS -> com.unspektrawesome.R.drawable.ic_sleek_info
        GallerySymbol.SHARE -> com.unspektrawesome.R.drawable.ic_sleek_share_2
        GallerySymbol.STACK -> com.unspektrawesome.R.drawable.ic_sleek_layers
        GallerySymbol.PLAY -> com.unspektrawesome.R.drawable.ic_sleek_play
        GallerySymbol.PAUSE -> com.unspektrawesome.R.drawable.ic_sleek_pause
        GallerySymbol.EXPAND -> com.unspektrawesome.R.drawable.ic_sleek_scan
        GallerySymbol.EXPORT -> com.unspektrawesome.R.drawable.ic_sleek_download
    }, modifier, color)
}

@Composable
internal fun GalleryIconButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(24.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label) }, contentAlignment = Alignment.Center) { icon() }
}

@Composable
internal fun GalleryMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    CompactCameraMenu(expanded, onDismiss, content = content)
}

@Composable
internal fun GalleryMenuItem(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { selected = active }.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (!enabled) CameraColors.Muted.copy(alpha = .4f) else if (active) CameraColors.Accent else CameraColors.Text,
            fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        if (active) com.unspektrawesome.ui.camera.CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_check, Modifier.size(16.dp), CameraColors.Accent)
    }
}

@Composable
internal fun GalleryDropdown(label: String, options: List<String>, selectedIndex: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(22.dp))
            .border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(22.dp))
            .clickable(enabled, role = Role.Button) { expanded = true }
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            GallerySymbolIcon(GallerySymbol.DOWN, CameraColors.Muted, Modifier.size(16.dp))
        }
        GalleryMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, option -> GalleryMenuItem(option, selectedIndex == index) { expanded = false; onSelect(index) } }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GalleryViewerActions(detailsVisible: Boolean, onShare: () -> Unit, onDetails: () -> Unit,
    modifier: Modifier = Modifier, onMoreChanged: (Boolean) -> Unit = {}, more: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val required = with(density) { listOf("Share", "Details", "More").maxOf {
        measurer.measure(AnnotatedString(AppLanguage.text(it)), LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 19.sp)).size.width
    }.toDp() + 52.dp }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val columns = ((maxWidth + 6.dp) / (required.coerceAtLeast(80.dp) + 6.dp)).toInt().coerceIn(1, 3)
        val slot = (maxWidth - 6.dp * (columns - 1)) / columns
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GalleryViewerAction("Share", GallerySymbol.SHARE, Modifier.width(slot), onClick = onShare)
            GalleryViewerAction("Details", GallerySymbol.DETAILS, Modifier.width(slot), active = detailsVisible, onClick = onDetails)
            Box(Modifier.width(slot)) {
                GalleryViewerAction("More", GallerySymbol.MORE, Modifier.fillMaxWidth(), onClick = { expanded = true; onMoreChanged(true) })
                GalleryMenu(expanded, { expanded = false; onMoreChanged(false) }) { more { expanded = false; onMoreChanged(false) } }
            }
        }
    }
}

@Composable
internal fun GalleryViewerAction(label: String, icon: GallerySymbol, modifier: Modifier, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (!enabled) CameraColors.Muted.copy(alpha = .4f) else if (active) CameraColors.Accent else CameraColors.Text
    val shape = RoundedCornerShape(22.dp)
    Row(modifier.heightIn(min = 44.dp).clip(shape)
        .border(1.dp, if (active) CameraColors.Accent else CameraColors.MenuBorder, shape)
        .then(if (active) Modifier.pageCardBackground(shape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label); selected = active }
        .padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        GallerySymbolIcon(icon, color, Modifier.size(18.dp))
        Text(label, color = color, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f))
    }
}
