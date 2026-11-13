package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.AppLanguage
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.unspektrawesome.calibration.DcpRepository
import com.unspektrawesome.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exports and imports the lens profiles of one capture [mode]. */
@Composable
internal fun rememberLensProfileTransfer(preferences: CameraPreferences, mode: LensProfileMode,
    onChange: (CameraPreferences) -> Unit): ProfileTransferActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dcps = remember(context) { DcpRepository(context) }
    val currentPreferences by rememberUpdatedState(preferences)
    val currentMode by rememberUpdatedState(mode)
    val change by rememberUpdatedState(onChange)
    val exporter = rememberProfileDocumentExport("application/zip")
    var loading by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<CameraPreferences?>(null) }
    var importing by remember { mutableStateOf<LensProfileBackup?>(null) }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var importMode by rememberSaveable { mutableStateOf(LensProfileImportMode.KEEP_EXISTING) }
    fun message(text: String) = Toast.makeText(context, AppLanguage.text(text), Toast.LENGTH_LONG).show()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch(Dispatchers.Main.immediate) {
            loading = true
            importing = null
            importMode = LensProfileImportMode.KEEP_EXISTING
            try {
                importing = withContext(Dispatchers.IO) {
                    require(uris.size <= LensProfileArchive.MAX_PROFILES) { "Choose fewer backup files at once." }
                    val incoming = mutableListOf<ImportedLensProfile>()
                    val files = linkedMapOf<String, ByteArray>()
                    var total = 0L
                    uris.forEach { uri ->
                        val backup = context.contentResolver.openInputStream(uri)?.use { LensProfileArchive.read(it) }
                            ?: error("Could not open a lens-profile backup.")
                        require(incoming.size + backup.profiles.size <= LensProfileArchive.MAX_PROFILES) {
                            "Import up to ${LensProfileArchive.MAX_PROFILES} lens profiles at once."
                        }
                        incoming += backup.profiles
                        backup.dcps.forEach { (id, bytes) ->
                            if (id !in files) {
                                total += bytes.size
                                require(total <= LensProfileArchive.MAX_BYTES) { "The selected DCP files exceed 128 MB. Import fewer backups together." }
                                files[id] = bytes
                            }
                        }
                    }
                    LensProfileBackup(incoming, files)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importError = e.message ?: "Could not import the lens profiles."
            } finally { loading = false }
        }
    }
    exporting?.let { saved ->
        val profiles = saved.lensProfiles.filter { it.mode == mode }
        val startupId = saved.defaultLensProfileId(mode)
        ProfileSelectionDialog("Export lens profiles",
            "Every setting and DCP file is included. Backups can only be imported on the same device model.", "Export",
            profiles.map { TransferProfileChoice(it.id, it.displayName, lensProfileTransferDetail(it, it.id == startupId)) },
            onDismiss = { exporting = null }) { ids ->
            val selected = profiles.filter { it.id in ids }
            exporting = null
            exporter.write("Lens profiles.unspektrawesome.zip", "Exported ${selected.size} lens profiles with their DCP files.") { output ->
                LensProfileArchive.write(output, selected, startupId, readDcp = dcps::readOriginal)
            }
        }
    }
    importing?.let { backup ->
        ProfileSelectionDialog("Import lens profiles",
            "Choose profiles to import with their DCP files. The included startup selection is restored.", "Import",
            backup.profiles.mapIndexed { index, entry -> TransferProfileChoice(index.toString(), entry.profile.displayName,
                lensProfileTransferDetail(entry.profile, entry.startup)) }, options = {
                LensProfileImportChoices(importMode, mode) { importMode = it }
            }, onDismiss = { importing = null }) { ids ->
            val selected = backup.profiles.filterIndexed { index, _ -> index.toString() in ids }
            val selectedMode = importMode
            val target = mode
            importing = null
            scope.launch(Dispatchers.Main.immediate) {
                loading = true
                try {
                    withContext(Dispatchers.IO) {
                        selected.mapNotNull { it.profile.color.dcpId }.distinct().forEach { id ->
                            val name = selected.first { it.profile.color.dcpId == id }.profile.color.dcpName ?: "Custom DCP.dcp"
                            dcps.import(backup.dcps.getValue(id), name)
                        }
                    }
                    change(LensProfiles.importProfiles(currentPreferences, selected, selectedMode, target))
                    message("Imported ${selected.size} lens profiles with their DCP files.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    importError = e.message ?: "Could not restore the lens profiles."
                } finally { loading = false }
            }
        }
    }
    importError?.let { LensProfileImportErrorDialog(it) { importError = null } }
    return ProfileTransferActions(exporter.busy || loading || exporting != null || importing != null || importError != null,
        importProfile = {
            try { importer.launch(arrayOf("*/*")) }
            catch (_: Exception) { message("Could not open the file picker.") }
        }, exportProfiles = { if (currentPreferences.lensProfiles.any { it.mode == currentMode }) exporting = currentPreferences })
}

@Composable
internal fun LensProfileImportChoices(mode: LensProfileImportMode, target: LensProfileMode,
    onChange: (LensProfileImportMode) -> Unit) {
    SettingsCard {
        SettingsChoices(listOf("Keep existing", "Replace all"), mode.ordinal) {
            onChange(LensProfileImportMode.entries[it])
        }
        SettingsHint(when {
            mode == LensProfileImportMode.KEEP_EXISTING -> "Existing profiles are kept. Matching names become copies."
            target == LensProfileMode.VIDEO -> "All existing video lens profiles are removed and replaced by the selected imports."
            else -> "All existing photo lens profiles are removed and replaced by the selected imports."
        })
    }
}

private fun lensProfileTransferDetail(profile: LensProfile, startup: Boolean): String = listOfNotNull(
    "Lens ID ${profile.routeId}", profile.rawStream.format.name, "${profile.rawStream.width} × ${profile.rawStream.height}",
    profile.color.dcpName?.let { "DCP: $it" }, if (startup) "Startup" else null,
).joinToString("\n")
