package com.unspektrawesome.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal enum class UpdatePhase { CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }

internal data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.CHECKING,
    val release: UpdateRelease? = null,
    val progress: Float = 0f,
    val message: String? = null,
)

internal class UpdateController(context: Context, private val scope: CoroutineScope) {
    private val updater = AppUpdater(context.applicationContext)
    private var job: Job? = null
    private var downloadedFile: File? = null
    var visible by mutableStateOf(false)
        private set
    var state by mutableStateOf(UpdateState())
        private set

    fun check(manual: Boolean) {
        if (job?.isCompleted == false || downloadedFile != null || state.phase == UpdatePhase.AVAILABLE) {
            if (manual) visible = true
            return
        }
        visible = manual
        state = UpdateState()
        job = scope.launch {
            try {
                val release = updater.check()
                state = UpdateState(if (release == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, release)
                if (release != null) visible = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = UpdateState(UpdatePhase.ERROR, message = errorMessage(error))
            }
        }
    }

    fun download() {
        if (job?.isCompleted == false) return
        val release = state.release ?: return
        state = state.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, message = null)
        job = scope.launch {
            try {
                downloadedFile = updater.download(release) { state = state.copy(progress = it) }
                state = state.copy(phase = UpdatePhase.READY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(phase = UpdatePhase.AVAILABLE, message = errorMessage(error))
            }
        }
    }

    fun install() {
        val file = downloadedFile ?: return
        if (!file.isFile) {
            downloadedFile = null
            state = state.copy(phase = UpdatePhase.AVAILABLE, message = "Download the update again to install it.")
            return
        }
        try {
            updater.install(file)
        } catch (_: Exception) {
            showMessage("Android could not open the installer. Try again.")
        }
    }

    fun showMessage(message: String) {
        state = state.copy(message = message)
    }

    fun dismiss() {
        if (state.phase != UpdatePhase.DOWNLOADING) visible = false
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is UnknownHostException -> "Cannot reach GitHub. Check your internet connection and try again."
        is SocketTimeoutException -> "The connection timed out. Try again."
        else -> error.message ?: "The update could not be completed. Try again."
    }
}
