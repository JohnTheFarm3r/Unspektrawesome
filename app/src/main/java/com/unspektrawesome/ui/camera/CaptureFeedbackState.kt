package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.unspektrawesome.preview.RawCaptureStatus
import kotlinx.coroutines.delay

internal data class CaptureFeedbackState(
    val visible: CaptureFeedback?,
    val dismiss: () -> Unit,
)

@Composable
internal fun rememberCaptureFeedbackState(status: RawCaptureStatus): CaptureFeedbackState {
    val feedback = CaptureFeedback.from(status)
    var dismissed by remember(status.feedbackId) { mutableStateOf(false) }
    val transient = feedback?.kind == CaptureFeedbackKind.Saved || feedback?.kind == CaptureFeedbackKind.Canceled
    LaunchedEffect(status.feedbackId, transient) {
        if (transient) {
            delay(2000L)
            dismissed = true
        }
    }
    return CaptureFeedbackState(if (dismissed) null else feedback) { dismissed = true }
}
