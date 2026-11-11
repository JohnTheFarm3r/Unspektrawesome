package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.AppLanguage
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal class ProfileDocumentExport(val busy: Boolean, val write: (String, String, (OutputStream) -> Unit) -> Unit)

@Composable
internal fun rememberProfileDocumentExport(mimeType: String,
    failureMessage: String = "Could not export the profiles. Please try another location."): ProfileDocumentExport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var successMessage by rememberSaveable { mutableStateOf("") }
    var writing by remember { mutableStateOf(false) }
    val directory = remember(context) { File(context.cacheDir, "profile-transfers") }
    fun message(text: String) = Toast.makeText(context, AppLanguage.text(text), Toast.LENGTH_LONG).show()
    fun stagedFile(name: String): File {
        require(name.matches(Regex("[a-f0-9-]+\\.backup")))
        return File(directory, name)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        val name = pending
        if (name != null) scope.launch(Dispatchers.Main.immediate) {
            writing = true
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            stagedFile(name).inputStream().use { it.copyTo(output) }
                        } ?: error("Could not open the destination.")
                    }
                    message(successMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message(failureMessage)
            } finally {
                stagedFile(name).delete()
                pending = null
                writing = false
            }
        }
    }
    return ProfileDocumentExport(writing || pending != null) { fileName, doneMessage, write ->
        if (!writing && pending == null) scope.launch(Dispatchers.Main.immediate) {
            writing = true
            val name = "${UUID.randomUUID()}.backup"
            try {
                withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "Could not prepare the backup." }
                    stagedFile(name).outputStream().use(write)
                }
                pending = name
                successMessage = doneMessage
                launcher.launch(fileName)
            } catch (e: CancellationException) {
                stagedFile(name).delete()
                pending = null
                throw e
            } catch (e: Exception) {
                stagedFile(name).delete()
                pending = null
                message(e.message ?: failureMessage)
            } finally { writing = false }
        }
    }
}
