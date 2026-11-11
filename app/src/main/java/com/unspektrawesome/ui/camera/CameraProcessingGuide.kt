package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

internal const val CAMERA_PROCESSING_GUIDE_STEPS = 4

@Composable
internal fun CameraProcessingGuide(step: Int, targetBounds: Rect?, onSkip: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier) {
    val titles = listOf("Process now or later", "Save for later in Gallery", "Turn deferred processing off", "Export when you are ready")
    val descriptions = listOf(
        "Process now saves JPEGs while you shoot. Later in Gallery saves RAWs for a session you start afterward, giving the camera more processing room. Open the gear, then General to choose.",
        "Choose Later in Gallery to save an original DNG with each shot's crop, look and optional location. Gallery applies that saved crop before spektrafilm when exporting, even if you change the camera settings afterward. The guide leaves your choice unchanged.",
        "Choose Process now to turn deferred processing off and save new shots as JPEGs while shooting. Existing waiting photos stay in Gallery with their saved settings. In Process now, tapping the camera thumbnail opens your last photo; hold it for 0.5 seconds to open Gallery. In Later in Gallery, a tap opens Gallery directly.",
        "Continue to Gallery for the next tour, covering your photos, processing queue and export controls. Develop waiting RAWs entirely on your phone. Capture dates are preserved. Originals are kept by default; Gallery settings offers optional RAW deletion after verified exports, with a warning before enabling it.",
    )
    GuideOverlay("PROCESSING GUIDE", step, CAMERA_PROCESSING_GUIDE_STEPS, titles[step], descriptions[step],
        targetBounds, onSkip, onNext, modifier, nextLabel = when (step) {
            0 -> "Show settings"
            2 -> "Back to camera"
            3 -> "Continue to Gallery"
            else -> "Next"
        }, tag = "Camera processing guide")
}
