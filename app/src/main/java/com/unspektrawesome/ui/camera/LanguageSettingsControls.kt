package com.unspektrawesome.ui.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.BuiltInLanguage
import com.unspektrawesome.language.LanguagePickerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A notice with its English template and the values filled into it. */
private data class LanguageNotice(val text: String, val arguments: Map<String, Any?>? = null, val failure: Boolean = false)

@Composable
internal fun LanguageSettingsControls() {
    val context = LocalContext.current
    val language by AppLanguage.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LanguageNotice?>(null) }
    var picker by remember { mutableStateOf(false) }
    if (picker) LanguagePickerDialog { picker = false }
    val exporter = rememberProfileDocumentExport("application/json", "Could not export the language file. Please try another location.")
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val pack = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(AppLanguage::import)
                        ?: error("Could not open this language file.")
                }
                notice = if (AppLanguage.state.value.enabled) LanguageNotice("Using custom language: {name}", mapOf("name" to pack.name))
                    else LanguageNotice("Imported {name}. Select Custom to use it.", mapOf("name" to pack.name))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = LanguageNotice(if (error is IllegalArgumentException) error.message.orEmpty()
                    else "Could not import this language file. Check that it is valid JSON. Your current language is unchanged.",
                    failure = true)
            } finally { busy = false }
        }
    }
    val custom = language.custom
    val idle = !busy && !exporter.busy
    // Numbers match the callouts of the drawing shown above for the current state.
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (custom == null) DiagramNumber(1)
        BuiltInLanguage.all.firstOrNull { it.code == language.selectedCode }?.let { selected ->
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                Text(selected.flag, translate = false, fontSize = 18.sp, lineHeight = 22.sp, modifier = Modifier.clearAndSetSemantics { })
            }
            Text(selected.nativeName, translate = false, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        } ?: Spacer(Modifier.weight(1f))
        DiagramButton("Choose language", Modifier.widthIn(max = 200.dp).testTag("Choose built-in language"),
            symbol = SettingsSymbol.Languages) { picker = true }
    }
    SetupChoice(null, listOf("English", "Custom"), if (language.enabled) 1 else if (language.selectedCode == "en") 0 else -1,
        number = if (custom == null) 2 else 4, enabled = { it == 0 || (custom != null && idle) },
        choiceModifier = { Modifier.testTag(if (it == 0) "Language English" else "Language Custom") }) { index ->
        AppLanguage.useCustom(index == 1)
    }
    custom?.let { pack ->
        Column(Modifier.fillMaxWidth().diagramHairline().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Imported language: {name}", arguments = mapOf("name" to pack.name), color = CameraColors.Text, fontSize = 13.sp,
                lineHeight = 18.sp)
            Text("{size} of {size_2} entries available. Missing entries use English.",
                arguments = mapOf("size" to pack.strings.size, "size_2" to language.english.size),
                color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
    SetupActionRow("Export English template", SettingsSymbol.Export, Modifier.testTag("Export English language"),
        detail = "Unspektrawesome-English-${BuildConfig.VERSION_NAME}.json", number = if (custom != null) 1 else null, enabled = idle) {
        val json = AppLanguage.exportEnglish()
        exporter.write("Unspektrawesome-English-${BuildConfig.VERSION_NAME}.json", "English translation template exported.") {
            it.writer(Charsets.UTF_8).use { writer -> writer.write(json) }
        }
    }
    SetupActionRow("Import language", SettingsSymbol.Import, Modifier.testTag("Import language"),
        number = if (custom != null) 2 else null, enabled = idle) {
        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }
    if (custom != null) SetupActionRow("Export updated custom language", SettingsSymbol.Export,
        detail = "Unspektrawesome-Custom-${BuildConfig.VERSION_NAME}.json", number = 3, enabled = idle) {
        val json = AppLanguage.exportCustom()
        exporter.write("Unspektrawesome-Custom-${BuildConfig.VERSION_NAME}.json", "Custom language exported with the latest English entries.") {
            it.writer(Charsets.UTF_8).use { writer -> writer.write(json) }
        }
    }
    notice?.let { DiagramHint(it.text, Modifier.testTag("Language import result"), warning = it.failure, arguments = it.arguments) }
}
