package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.settings.FocusPeakingStrength

@Composable
internal fun FocusPeakingOverlay(strength: FocusPeakingStrength, onChange: (FocusPeakingStrength) -> Unit,
    interactive: Boolean = true) {
    Row(Modifier.width(IntrinsicSize.Max).testTag("Focus peaking strength")
        .railGlass().languageSemantics { language -> stateDescription = language.text(strength.label) }
        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Peaking", color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 13.sp)
        Box(Modifier.width(1.dp).height(24.dp).background(CameraColors.RailRim))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocusPeakingStrength.entries.forEach { choice ->
                CameraAssistAction(choice.label, null, "Focus peaking ${choice.label}", enabled = true,
                    primary = choice == strength, interactive = interactive, onClick = { onChange(choice) })
            }
        }
    }
}
