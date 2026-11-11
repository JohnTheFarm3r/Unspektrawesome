package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.BuildConfig

@Composable
internal fun AboutScreen(onCheckForUpdates: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    SettingsPage {
        SettingsCard("Unspektrawesome", symbol = SettingsSymbol.Info) {
            Text("Version {value1}", arguments = mapOf("value1" to (BuildConfig.VERSION_NAME.removeSuffix("-testing"))), color = CameraColors.Text, fontSize = 14.sp)
            SettingsDivider()
            Text("Developer: JohnTheFarm3r", color = CameraColors.Muted, fontSize = 13.sp)
            if (onCheckForUpdates != null) {
                SettingsButton("Check for updates", Modifier.fillMaxWidth(), symbol = SettingsSymbol.Update, onClick = onCheckForUpdates)
            }
        }
        DiagnosticLogCard()
        SettingsCard {
            Text("Unofficial partial port of spektrafilm. Not affiliated with the original spektrafilm project. The spektrafilm name and original work belong to its original author, not this app's developer.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        SettingsCard("ORIGINAL PROJECT", symbol = SettingsSymbol.Book) {
            AboutLink("spektrafilm OFX") { uriHandler.openUri("https://spektrafilm.114c.de/") }
            SettingsDivider()
            AboutLink("chaert-s / source") { uriHandler.openUri("https://github.com/chaert-s/spektrafilm-ofx") }
            SettingsDivider()
            Text("Tri-X profile by Andrea Volpato. Adapted for Vulkan by JohnTheFarm3r. Licensed under CC BY-SA 4.0.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            AboutLink("Andrea Volpato / spektrafilm") { uriHandler.openUri("https://github.com/andreavolpato/spektrafilm") }
            SettingsDivider()
            AboutLink("CC BY-SA 4.0") { uriHandler.openUri("https://creativecommons.org/licenses/by-sa/4.0/") }
        }
    }
}

@Composable
private fun AboutLink(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = CameraColors.Text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        SettingsIcon(SettingsSymbol.ExternalLink, Modifier.size(17.dp))
    }
}
