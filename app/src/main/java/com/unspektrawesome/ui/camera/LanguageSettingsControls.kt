package com.unspektrawesome.ui.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

@Composable
internal fun LanguageSettingsControls() {
    val context = LocalContext.current
    val language by AppLanguage.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
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
                notice = if (AppLanguage.state.value.enabled) "Using custom language: ${pack.name}"
                    else "Imported ${pack.name}. Select Custom to use it."
                failure = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = if (error is IllegalArgumentException) error.message
                    else "Could not import this language file. Check that it is valid JSON. Your current language is unchanged."
                failure = true
            } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BuiltInLanguage.all.firstOrNull { it.code == language.selectedCode }?.let { selected ->
            Text("${selected.flag}  ${selected.nativeName}", translate = false, color = CameraColors.Text, fontSize = 16.sp)
        }
        SettingsButton("Choose language", Modifier.fillMaxWidth().testTag("Choose built-in language"), symbol = SettingsSymbol.Languages) { picker = true }
        SettingsDivider()
        SettingsFieldPair(
            first = { modifier -> SettingsChoice("English", language.selectedCode == "en", modifier.testTag("Language English")) {
                AppLanguage.useCustom(false)
            } },
            second = { modifier -> SettingsChoice("Custom", language.enabled, modifier.testTag("Language Custom"),
                enabled = language.custom != null && !busy && !exporter.busy) { AppLanguage.useCustom(true) } },
        )
        language.custom?.let { pack ->
            Text("Imported language: {name}", arguments = mapOf("name" to (pack.name)), color = CameraColors.Text, fontSize = 12.sp)
            Text("{size} of {size_2} entries available. Missing entries use English.", arguments = mapOf("size" to (pack.strings.size), "size_2" to (language.english.size)),
                color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        SettingsButton("Export English template", Modifier.fillMaxWidth().testTag("Export English language"), enabled = !busy && !exporter.busy,
            symbol = SettingsSymbol.Export) {
            val json = AppLanguage.exportEnglish()
            exporter.write("Unspektrawesome-English-${BuildConfig.VERSION_NAME}.json", "English translation template exported.") {
                it.writer(Charsets.UTF_8).use { writer -> writer.write(json) }
            }
        }
        SettingsButton("Import language", Modifier.fillMaxWidth().testTag("Import language"), enabled = !busy && !exporter.busy,
            symbol = SettingsSymbol.Import) {
            importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        if (language.custom != null) SettingsButton("Export updated custom language", Modifier.fillMaxWidth(), enabled = !busy && !exporter.busy,
            symbol = SettingsSymbol.Export) {
            val json = AppLanguage.exportCustom()
            exporter.write("Unspektrawesome-Custom-${BuildConfig.VERSION_NAME}.json", "Custom language exported with the latest English entries.") {
                it.writer(Charsets.UTF_8).use { writer -> writer.write(json) }
            }
        }
        notice?.let { Text(it, color = if (failure) CameraColors.FocusFailed else CameraColors.Muted,
            fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.testTag("Language import result")) }
    }
}
