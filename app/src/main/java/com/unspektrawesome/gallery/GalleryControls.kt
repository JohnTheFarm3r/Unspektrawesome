package com.unspektrawesome.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.Diagram
import com.unspektrawesome.ui.camera.DiagramNumber
import com.unspektrawesome.ui.camera.diagramHairline

internal enum class GallerySymbol { BACK, MORE, DOWN, SHARE, DETAILS, STACK, PLAY, PAUSE, EXPAND, EXPORT }

@Composable
internal fun GalleryTitleBadge() {
    Box(Modifier.size(30.dp).border(1.dp, Diagram.Border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        com.unspektrawesome.ui.camera.CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_aperture, Modifier.size(16.dp), CameraColors.Accent)
    }
}

@Composable
internal fun GalleryCameraAction(onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clip(Diagram.ControlShape).clickable(role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text("Camera") }, contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 30.dp).clip(Diagram.ControlShape).background(Diagram.Glass)
            .border(1.dp, Diagram.Border, Diagram.ControlShape).padding(horizontal = 11.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            GalleryIcon(3, CameraColors.Text, Modifier.size(13.dp))
            Text("Camera", color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun GallerySettingsDisclosure(title: String, number: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val action = AppLanguage.format(if (expanded) "Collapse {title}" else "Expand {title}", mapOf("title" to AppLanguage.text(title)))
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
            .diagramHairline().padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            number?.let { DiagramNumber(it) }
            Text(title, color = CameraColors.Text, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            GallerySymbolIcon(GallerySymbol.DOWN, if (expanded) CameraColors.Accent else Diagram.Hint,
                Modifier.size(16.dp).rotate(if (expanded) 180f else 0f))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun GalleryQueueRow(selected: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().diagramHairline()
        .then(if (selected) Modifier.padding(vertical = 2.dp).clip(Diagram.ControlShape)
            .background(CameraColors.Accent.copy(alpha = .05f)).border(1.dp, CameraColors.Accent, Diagram.ControlShape)
            .padding(horizontal = 6.dp) else Modifier)
        .padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
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
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(Diagram.ControlShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(label) }
        .alpha(if (enabled) 1f else .4f), contentAlignment = Alignment.Center) { icon() }
}
