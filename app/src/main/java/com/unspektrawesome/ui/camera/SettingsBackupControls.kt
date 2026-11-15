package com.unspektrawesome.ui.camera

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.calibration.DcpRepository
import com.unspektrawesome.gallery.GalleryProcessing
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.settings.BackupPart
import com.unspektrawesome.settings.SettingsBackup
import com.unspektrawesome.settings.SettingsBackupContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export and import of a full backup: settings, custom film profiles and lens profiles. */
@Composable
internal fun SettingsBackupControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dcps = remember(context) { DcpRepository(context) }
    val exporter = rememberProfileDocumentExport("application/zip", "Could not export the backup. Please try another location.")
    var loading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<SettingsBackupContents?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.Main.immediate) {
            loading = true
            try {
                importing = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { SettingsBackup.read(it) }
                        ?: error("Could not open the backup.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? IllegalArgumentException)?.message ?: "Could not read this backup. Choose a backup exported from Unspektrawesome settings."
            } finally { loading = false }
        }
    }
    val busy = loading || exporter.busy
    SetupSection("Backup")
    SetupActionRow("Export backup", SettingsSymbol.Export, Modifier.testTag("Export backup"), enabled = !busy) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        exporter.write("Unspektrawesome-backup-$date.zip", "Backup exported.") { output ->
            SettingsBackup.write(context, output, dcps::readOriginal)
        }
    }
    SetupActionRow("Import backup", SettingsSymbol.Import, Modifier.testTag("Import backup"), enabled = !busy) {
        if (GalleryProcessing.state.value.running) error = "Photos are being processed. Import the backup once processing has finished or is paused."
        else try { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        catch (_: Exception) { Toast.makeText(context, AppLanguage.text("Could not open the file picker."), Toast.LENGTH_LONG).show() }
    }
    if (busy) CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), color = CameraColors.Accent, strokeWidth = 2.dp)
    DiagramHint("A backup holds your settings, button assignments, custom film profiles and lens profiles with their DCP files. Lens profiles can only be imported on the same phone model.")

    importing?.let { backup ->
        BackupImportDialog(backup, onDismiss = { importing = null }) { parts ->
            importing = null
            scope.launch(Dispatchers.Main.immediate) {
                loading = true
                try {
                    if (GalleryProcessing.state.value.running)
                        error("Photos are being processed. Import the backup once processing has finished or is paused.")
                    withContext(Dispatchers.IO) {
                        SettingsBackup.restore(context, backup, parts, importDcp = { bytes, name -> dcps.import(bytes, name).id },
                            importLanguage = { AppLanguage.import(it.byteInputStream(Charsets.UTF_8)) })
                    }
                    restartApp(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Could not restore the backup."
                    loading = false
                }
            }
        }
    }
    error?.let { message ->
        CameraDialogWindow({ error = null }, header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsIcon(SettingsSymbol.Info, Modifier.size(24.dp))
                Text("Cannot import backup", color = CameraColors.Text, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
            }
        }, footer = {
            LensToolAction("Close", Modifier.fillMaxWidth(), SettingsSymbol.Close, accent = true) { error = null }
        }) {
            Text(message, color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

/** Lets the person choose which parts of a backup to restore. Both are chosen when available. */
@Composable
private fun BackupImportDialog(backup: SettingsBackupContents, onDismiss: () -> Unit, onConfirm: (Set<BackupPart>) -> Unit) {
    val available = buildSet {
        if (backup.hasSettings) add(BackupPart.SETTINGS)
        if (backup.canRestoreLensProfiles) add(BackupPart.LENS_PROFILES)
    }
    var selected by remember(backup) { mutableStateOf(available) }
    CameraDialogWindow(onDismiss, header = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(SettingsSymbol.Import, Modifier.size(24.dp), CameraColors.Accent)
            Text("Import backup", color = CameraColors.Text, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
        }
    }, footer = {
        LensToolActionPair("Cancel", "Import", onDismiss, { onConfirm(selected) }, Modifier.testTag("Backup import footer"),
            firstSymbol = SettingsSymbol.Close, secondSymbol = SettingsSymbol.Import, secondAccent = true,
            secondEnabled = selected.isNotEmpty())
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose what to import. The chosen parts replace what is on this phone, and the app restarts to apply them.",
                color = CameraColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Text("From {device} · version {version}", arguments = mapOf("device" to backup.source.label, "version" to backup.appVersion),
                color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono)
            BackupPartRow("Settings", "App settings, button assignments, custom film profiles and language",
                BackupPart.SETTINGS, BackupPart.SETTINGS in selected, BackupPart.SETTINGS in available) {
                selected = if (it) selected + BackupPart.SETTINGS else selected - BackupPart.SETTINGS
            }
            val lensDetail = when {
                !backup.sameDevice -> AppLanguage.format("Made on {device}. Lens profiles can only be imported on the same phone model.",
                    mapOf("device" to backup.source.label))
                backup.photoProfiles + backup.videoProfiles == 0 -> AppLanguage.text("This backup has no lens profiles.")
                else -> AppLanguage.format("{photo} photo and {video} video profiles with their DCP files",
                    mapOf("photo" to backup.photoProfiles, "video" to backup.videoProfiles))
            }
            BackupPartRow("Lens profiles", lensDetail, BackupPart.LENS_PROFILES,
                BackupPart.LENS_PROFILES in selected, BackupPart.LENS_PROFILES in available, translateDetail = false) {
                selected = if (it) selected + BackupPart.LENS_PROFILES else selected - BackupPart.LENS_PROFILES
            }
        }
    }
}

@Composable
private fun BackupPartRow(title: String, detail: String, part: BackupPart, checked: Boolean, enabled: Boolean,
    translateDetail: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Diagram.ControlShape)
        .background(if (checked) Color(0x99202020) else Diagram.Glass)
        .border(1.dp, if (checked) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .testTag("Backup part ${part.name}").padding(horizontal = 10.dp, vertical = 9.dp).alpha(if (enabled) 1f else .5f),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LensToolCheck(checked, filled = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (checked) CameraColors.Accent else CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold)
            Text(detail, translate = translateDetail, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** Starts the app again so every screen and the camera load the restored values. */
private fun restartApp(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch))
    Runtime.getRuntime().exit(0)
}
