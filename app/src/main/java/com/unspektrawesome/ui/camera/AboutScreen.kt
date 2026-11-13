package com.unspektrawesome.ui.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.language.LocalAppLanguage

@Composable
internal fun AboutScreen(onCheckForUpdates: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    DiagramPage {
        DiagnosticLogStage()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Unspektrawesome", translate = false, color = CameraColors.Text, fontSize = 14.sp, lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("Version {value1}", arguments = mapOf("value1" to (BuildConfig.VERSION_NAME.removeSuffix("-testing"))),
                    color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 15.sp, fontFamily = Diagram.Mono)
            }
            if (onCheckForUpdates != null) {
                DiagramButton("Check for updates", Modifier.widthIn(max = 200.dp), symbol = SettingsSymbol.Update,
                    onClick = onCheckForUpdates)
            }
        }
        Text("Developer: JohnTheFarm3r", color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        DiagnosticLogCard()
        AboutCreditsStage()
        DiagramSection("Disclaimer")
        AboutTextRow(4, "Unofficial partial port of spektrafilm. Not affiliated with the original spektrafilm project. The spektrafilm name and original work belong to its original author, not this app's developer.")
        DiagramSection("ORIGINAL PROJECT")
        AboutLink("spektrafilm OFX", 1) { uriHandler.openUri("https://spektrafilm.114c.de/") }
        AboutLink("chaert-s / source") { uriHandler.openUri("https://github.com/chaert-s/spektrafilm-ofx") }
        AboutTextRow(2, "Tri-X profile by Andrea Volpato. Adapted for Vulkan by JohnTheFarm3r. Licensed under CC BY-SA 4.0.")
        AboutLink("Andrea Volpato / spektrafilm") { uriHandler.openUri("https://github.com/andreavolpato/spektrafilm") }
        AboutLink("CC BY-SA 4.0", 3) { uriHandler.openUri("https://creativecommons.org/licenses/by-sa/4.0/") }
    }
}

/** Numbered paragraph row, matching a number on the credits drawing. */
@Composable
private fun AboutTextRow(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DiagramNumber(number)
        Text(text, color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AboutLink(label: String, number: Int? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).diagramHairline()
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (number != null) DiagramNumber(number) else Spacer(Modifier.width(18.dp))
        Text(label, color = CameraColors.Accent, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        SettingsIcon(SettingsSymbol.ExternalLink, Modifier.size(15.dp), CameraColors.Accent)
    }
}

/** Where the film work comes from and how it reaches this app. */
@Composable
private fun AboutCreditsStage() {
    // The links are the rows below; the drawing is read as one description.
    val description = LocalAppLanguage.current.renderer
        .text("Tri-X profile by Andrea Volpato. Adapted for Vulkan by JohnTheFarm3r. Licensed under CC BY-SA 4.0.")
    DiagramStage(270.dp, Modifier.clearAndSetSemantics { contentDescription = description }) {
        DiagramDrawing {
            box(118.dp, 12.dp, 98.dp, 46.dp)
            line(104.dp, 32.dp, 118.dp, 32.dp, Color(0xFF6A6A6A), 1.dp)
            line(216.dp, 32.dp, 230.dp, 32.dp, Color(0xFF6A6A6A), 1.dp)
            box(8.dp, 19.dp, 96.dp, 26.dp, 13.dp, CameraColors.Accent, 1.2.dp)
            box(230.dp, 19.dp, 96.dp, 26.dp, 13.dp, CameraColors.Accent, 1.2.dp)
            polyline(listOf(150.dp to 58.dp, 150.dp to 72.dp, 82.dp to 72.dp, 82.dp to 88.dp), CameraColors.Accent)
            arrow(82.dp, 86.dp, 82.dp, 90.dp, CameraColors.Accent)
            box(14.dp, 90.dp, 136.dp, 46.dp)
            arrow(82.dp, 136.dp, 82.dp, 172.dp, CameraColors.Accent)
            line(82.dp, 154.dp, 94.dp, 154.dp, Color(0xFF6A6A6A), 1.dp)
            box(94.dp, 143.dp, 80.dp, 22.dp, 11.dp, CameraColors.Accent, 1.2.dp, fill = CameraColors.Accent.copy(alpha = .08f))
            box(14.dp, 176.dp, 136.dp, 46.dp)
            arrow(150.dp, 199.dp, 188.dp, 199.dp, CameraColors.Accent)
            box(192.dp, 170.dp, 132.dp, 54.dp, 8.dp, CameraColors.Accent, fill = CameraColors.Accent.copy(alpha = .05f))
            polyline(listOf(184.dp to 58.dp, 184.dp to 72.dp, 240.dp to 72.dp, 240.dp to 170.dp), Diagram.Hint, 1.dp, dashed = true)
            line(16.dp, 251.dp, 30.dp, 251.dp, CameraColors.Accent, 1.5.dp)
            line(150.dp, 251.dp, 164.dp, 251.dp, Diagram.Hint, 1.dp, dashed = true)
        }
        CreditsText("spektrafilm", 118.dp, 18.dp, 98.dp, CameraColors.Text, 11f, FontWeight.SemiBold, TextAlign.Center, translate = false)
        CreditsText("original author's work", 118.dp, 34.dp, 98.dp, Diagram.Hint, 8f, align = TextAlign.Center)
        CreditsText("spektrafilm OFX", 16.dp, 25.dp, 80.dp, CameraColors.Accent, 9f, FontWeight.Medium, translate = false)
        CreditsText("chaert-s / source", 238.dp, 25.dp, 80.dp, CameraColors.Accent, 9f, FontWeight.Medium, translate = false)
        // Title and author flow in one column so a two-line title pushes the author down inside the box.
        Column(Modifier.at(24.dp, 94.dp).width(120.dp)) {
            CreditsText("Tri-X profile", 0.dp, 0.dp, 120.dp, CameraColors.Text, 10.5f, FontWeight.SemiBold)
            Text("by {name}", arguments = mapOf("name" to "Andrea Volpato"), color = Diagram.Hint, fontSize = 8.5.sp,
                lineHeight = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        CreditsText("CC BY-SA 4.0", 94.dp, 148.dp, 80.dp, CameraColors.Accent, 9f, align = TextAlign.Center, translate = false,
            mono = true)
        Column(Modifier.at(24.dp, 180.dp).width(120.dp)) {
            CreditsText("Adapted for Vulkan", 0.dp, 0.dp, 120.dp, CameraColors.Text, 10.5f, FontWeight.SemiBold)
            Text("by {name}", arguments = mapOf("name" to "JohnTheFarm3r"), color = Diagram.Hint, fontSize = 8.5.sp,
                lineHeight = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        CreditsText("Unspektrawesome", 202.dp, 176.dp, 116.dp, CameraColors.Text, 11f, FontWeight.SemiBold, translate = false)
        CreditsText("Unofficial partial port of spektrafilm", 202.dp, 192.dp, 116.dp, Color(0xFFCFCFCF), 8.5f)
        DiagramNumber(1, Modifier.at(109.dp, 4.dp))
        DiagramNumber(2, Modifier.at(5.dp, 82.dp))
        DiagramNumber(3, Modifier.at(165.dp, 136.dp))
        DiagramNumber(4, Modifier.at(231.dp, 112.dp))
        Column(Modifier.at(252.dp, 92.dp).width(78.dp)) {
            DiagramLabel("Not affiliated", null)
            Text("Name and work belong to its author", color = Color(0xFF8C8C8C), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        CreditsText("Credited work in this app", 36.dp, 245.dp, 108.dp, Diagram.Hint, 9f)
        CreditsText("Not affiliated", 170.dp, 245.dp, 150.dp, Diagram.Hint, 9f)
    }
}

@Composable
private fun CreditsText(text: String, x: Dp, y: Dp, width: Dp, color: Color, size: Float, weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start, translate: Boolean = true, mono: Boolean = false) {
    Text(text, translate = translate, color = color, fontSize = size.sp, lineHeight = (size + 3).sp, fontWeight = weight,
        textAlign = align, fontFamily = if (mono) Diagram.Mono else null, maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.at(x, y).width(width))
}
