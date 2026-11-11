package com.unspektrawesome.ui.camera

import com.unspektrawesome.preview.RawCapturePhase
import com.unspektrawesome.preview.RawCaptureStatus

enum class CaptureFeedbackKind { Processing, Paused, Saved, Canceled, Error }

data class CaptureFeedback(
    val kind: CaptureFeedbackKind,
    val label: String,
    val action: String? = null,
    val requiresAttention: Boolean = kind != CaptureFeedbackKind.Processing && kind != CaptureFeedbackKind.Saved,
) {
    companion object {
        fun from(status: RawCaptureStatus): CaptureFeedback? {
            val count = status.outstandingShots
            return when {
                status.phase == RawCapturePhase.ERROR -> if (count > 0) {
                    CaptureFeedback(CaptureFeedbackKind.Paused, "Photo queue paused", "Manage")
                } else CaptureFeedback(CaptureFeedbackKind.Error, "Photo not saved", "Details")
                status.savingRawShots > 0 -> CaptureFeedback(CaptureFeedbackKind.Processing,
                    if (status.rawBufferFull) "RAW buffer full"
                    else "Saving photos", "Gallery", requiresAttention = status.rawBufferFull)
                status.processingDeferred && status.phase == RawCapturePhase.CAPTURING ->
                    CaptureFeedback(CaptureFeedbackKind.Processing, "Saving photos", "Gallery")
                count >= RawCaptureStatus.CAPACITY ->
                    CaptureFeedback(CaptureFeedbackKind.Processing, "Photo queue full", "Queue", requiresAttention = true)
                status.phase == RawCapturePhase.CAPTURING ->
                    CaptureFeedback(CaptureFeedbackKind.Processing, "Capturing photo", if (count > 0) "Queue" else null)
                count > 0 -> CaptureFeedback(CaptureFeedbackKind.Processing,
                    "Saving photos: $count", "Queue")
                status.phase == RawCapturePhase.SAVING ->
                    CaptureFeedback(CaptureFeedbackKind.Processing, "Saving photo")
                status.phase == RawCapturePhase.SUCCEEDED && status.processingDeferred && status.deferredShots > 0 ->
                    CaptureFeedback(CaptureFeedbackKind.Saved, "${status.deferredShots} waiting in Gallery", "Gallery")
                status.phase == RawCapturePhase.SUCCEEDED ->
                    CaptureFeedback(CaptureFeedbackKind.Saved, "Photo saved")
                status.phase == RawCapturePhase.CANCELED ->
                    CaptureFeedback(CaptureFeedbackKind.Canceled,
                        if (status.canceledCapture) "Long exposure canceled" else "Processing queue canceled")
                else -> null
            }
        }
    }
}
