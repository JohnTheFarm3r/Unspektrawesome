package com.unspektrawesome.ui.camera

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.unspektrawesome.R
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics

internal val LocalSettingsDesign = staticCompositionLocalOf { false }

internal object SettingsStyle {
    val PanelShape = RoundedCornerShape(18.dp)
    val FieldShape = RoundedCornerShape(22.dp)
    val ChoiceShape = RoundedCornerShape(22.dp)
    val PagePadding = 20.dp
    val PanelPadding = 16.dp
}

internal enum class SettingsSymbol(@param:DrawableRes val drawable: Int) {
    Aperture(R.drawable.ic_settings_aperture),
    Save(R.drawable.ic_settings_save),
    Layers(R.drawable.ic_settings_layers),
    Video(R.drawable.ic_settings_video),
    Lock(R.drawable.ic_settings_lock),
    Focus(R.drawable.ic_settings_focus),
    Volume(R.drawable.ic_settings_volume_2),
    Languages(R.drawable.ic_settings_languages),
    Book(R.drawable.ic_settings_book_open),
    Monitor(R.drawable.ic_settings_monitor),
    Phone(R.drawable.ic_settings_smartphone),
    Sliders(R.drawable.ic_settings_sliders_horizontal),
    Overlays(R.drawable.ic_settings_scan_line),
    Close(R.drawable.ic_settings_x),
    Discover(R.drawable.ic_settings_scan_search),
    Resume(R.drawable.ic_settings_play),
    Collapse(R.drawable.ic_settings_chevron_up),
    Export(R.drawable.ic_settings_download),
    Import(R.drawable.ic_settings_upload),
    Grip(R.drawable.ic_settings_grip_vertical),
    More(R.drawable.ic_settings_more_vertical),
    Check(R.drawable.ic_settings_check),
    Edit(R.drawable.ic_settings_pencil),
    Rename(R.drawable.ic_settings_type),
    Duplicate(R.drawable.ic_settings_copy),
    Startup(R.drawable.ic_settings_star),
    Delete(R.drawable.ic_settings_trash_2),
    Back(R.drawable.ic_settings_arrow_left),
    Saved(R.drawable.ic_settings_cloud_check),
    Dropdown(R.drawable.ic_settings_chevron_down),
    Reset(R.drawable.ic_settings_rotate_ccw),
    RotateClockwise(R.drawable.ic_settings_rotate_cw),
    Info(R.drawable.ic_settings_info),
    Update(R.drawable.ic_settings_refresh_cw),
    Logs(R.drawable.ic_settings_file_text),
    ExternalLink(R.drawable.ic_settings_external_link),
    DcpImport(R.drawable.ic_settings_file_plus),
    Erase(R.drawable.ic_settings_eraser),
    Gamepad(R.drawable.ic_settings_gamepad_2),
    Add(R.drawable.ic_settings_plus),
}

@Composable
internal fun SettingsIcon(symbol: SettingsSymbol, modifier: Modifier = Modifier, color: Color = CameraColors.Muted) {
    Icon(painterResource(symbol.drawable), contentDescription = null, modifier = modifier.size(20.dp), tint = color)
}

@Composable
internal fun SettingsIconBadge(symbol: SettingsSymbol) {
    Box(Modifier.size(26.dp).border(1.dp, CameraColors.MenuBorder, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center) {
        SettingsIcon(symbol, Modifier.size(16.dp))
    }
}

@Composable
internal fun SettingsSwitch(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val position by animateDpAsState(if (checked) 24.dp else 4.dp, label = "Settings switch position")
    Box(Modifier.size(56.dp, 48.dp).clip(CircleShape)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .languageSemantics { language -> contentDescription = language.text(label) }
        .alpha(if (enabled) 1f else .4f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 28.dp).clip(CircleShape).pageCardBackground(CircleShape)
            .border(1.dp, if (checked) CameraColors.Accent else CameraColors.MenuBorder, CircleShape)) {
            Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(position.roundToPx(), 0) }.size(20.dp)
                .background(if (checked) CameraColors.Accent else CameraColors.Muted, CircleShape))
        }
    }
}

@Composable
internal fun SettingsButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    symbol: SettingsSymbol? = null,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (accent) CameraColors.Accent else CameraColors.Text
    Row(modifier.heightIn(min = 48.dp).clip(SettingsStyle.ChoiceShape)
        .then(if (accent) Modifier.pageCardBackground(SettingsStyle.ChoiceShape) else Modifier)
        .border(1.dp, if (accent) CameraColors.Accent else CameraColors.MenuBorder, SettingsStyle.ChoiceShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp).alpha(if (enabled) 1f else .4f),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        symbol?.let { SettingsIcon(it, Modifier.size(17.dp), ink) }
        Text(label, color = ink, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
    }
}
