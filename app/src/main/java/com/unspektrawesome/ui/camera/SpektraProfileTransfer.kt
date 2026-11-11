package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.AppLanguage
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.unspektrawesome.ui.camera.CameraAlertDialog as AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.spektra.SpektraCardOrders
import com.unspektrawesome.spektra.SpektraProfile
import com.unspektrawesome.spektra.SpektraProfileFile
import com.unspektrawesome.spektra.SpektraProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProfileTransferActions(val busy: Boolean, val importProfile: () -> Unit, val exportProfiles: () -> Unit)

@Composable
internal fun rememberProfileTransfer(profiles: SpektraProfiles, cardOrders: SpektraCardOrders): ProfileTransferActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberProfileDocumentExport("application/json")
    var loading by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<List<SpektraProfile>?>(null) }
    var importing by remember { mutableStateOf<List<SpektraProfile>?>(null) }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    fun message(text: String) = Toast.makeText(context, AppLanguage.text(text), Toast.LENGTH_LONG).show()
    fun export(selected: List<SpektraProfile>) {
        val fileName = if (selected.size == 1) selected.single().name
            .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_") + ".unspektrawesome.json"
            else "Custom profiles.unspektrawesome.json"
        exporter.write(fileName, "Exported ${selected.size} custom ${if (selected.size == 1) "profile" else "profiles"}.") {
            it.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(SpektraProfileFile.encode(selected)) }
        }
    }
    fun import(selected: List<SpektraProfile>) {
        val saved = profiles.importProfiles(selected)
        message(if (saved.size == 1) "Imported ${saved.single().name}. Tap its card to apply it."
            else "Imported ${saved.size} custom profiles. Tap a card to apply it.")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch(Dispatchers.Main.immediate) {
            loading = true
            try {
                val incoming = withContext(Dispatchers.IO) {
                    require(uris.size <= SpektraProfileFile.MAX_PROFILES) { "Choose fewer profile files at once." }
                    buildList {
                        uris.forEach { uri ->
                            val batch = context.contentResolver.openInputStream(uri)?.use(SpektraProfileFile::readProfiles)
                                ?: error("Could not open a custom-profile file.")
                            require(size + batch.size <= SpektraProfileFile.MAX_PROFILES) { "Import up to ${SpektraProfileFile.MAX_PROFILES} profiles at once." }
                            addAll(batch)
                        }
                    }
                }
                if (incoming.size == 1) import(incoming) else importing = incoming
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importError = e.message ?: "Could not import the profiles. Please try another file."
            } finally { loading = false }
        }
    }
    exporting?.let { available ->
        ProfileSelectionDialog("Export custom profiles", "Select the cards to save together, in their current order.", "Export",
            available.map { TransferProfileChoice(it.id, it.name) }, onDismiss = { exporting = null }) { ids ->
            exporting = null
            export(available.filter { it.id in ids })
        }
    }
    importing?.let { available ->
        ProfileSelectionDialog("Import custom profiles", "Choose the cards to add. Matching names become separate copies.", "Import",
            available.mapIndexed { index, profile -> TransferProfileChoice(index.toString(), profile.name) },
            onDismiss = { importing = null }) { ids ->
            import(available.filterIndexed { index, _ -> index.toString() in ids })
            importing = null
        }
    }
    importError?.let { error ->
        CameraDialogWindow(onDismiss = { importError = null }, header = {
            Text("Import custom profiles", color = CameraColors.Text, fontSize = 16.sp, lineHeight = 22.sp)
        }, footer = {
            CameraDialogAction("Close", true) { importError = null }
        }) {
            Text(error, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
    return ProfileTransferActions(exporter.busy || loading || exporting != null || importing != null || importError != null,
        importProfile = {
            try { importer.launch(arrayOf("*/*")) }
            catch (_: Exception) { message("Could not open the file picker.") }
        },
        exportProfiles = {
            val available = profiles.profiles.value.associateBy { it.id }
            val ordered = cardOrders.orderedKeys("custom", available.keys.toList()).mapNotNull(available::get)
            if (ordered.size == 1) export(ordered) else if (ordered.isNotEmpty()) exporting = ordered
        })
}
