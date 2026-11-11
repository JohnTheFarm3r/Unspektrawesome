package com.unspektrawesome.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

internal const val VIDEO_GUIDE_REVISION = 12
internal val VIDEO_GUIDE_STEPS = VideoGuideStep.entries.size

internal enum class VideoGuideStep(val title: String, val description: String) {
    RECORD("Video mode",
        "Swipe left or right on the viewfinder in portrait, or up or down in landscape, to cycle through BURST, VIDEO, PHOTO, 3×3 and PANORAMA. Swipe the other way to go back. In 3×3, tap the shutter and aim at each highlighted circle. Hold your aim for 1.5 seconds to capture each of the nine panels automatically. The shutter stops capture early. Export later in Gallery is the default.\n\nPanorama and 3×3 adjust exposure, white balance and focus automatically until capture starts, then lock their current values. AUTO restores these settings after manual adjustments. Captured 3×3 frames stay in place in the viewfinder.\n\nYou can also choose Photo Mode or Video Mode from the launcher shortcut menu. Choose a lens before recording. Tap the record button to start, then tap again to stop. Allow microphone access to record audio.\n\nThis video tour has its own progress. Switch back to photo mode to resume the photo guide, or use Skip guide at any time.\n\nStarting a recording closes the bottom panel. In portrait, swipe up to reopen it or down to close it while recording."),
    FRAMING("Frame your video",
        "Tap the crop button or the recording-size indicator. Choose an aspect ratio or an exact pixel crop, then a separate downscale factor. Pixel crops include 4K, 2K, FHD, 720p and cinema formats.\n\nRotate the phone before recording to choose portrait or landscape. The size indicator includes stabilization crop. Framing locks and the indicator and lens buttons hide during recording."),
    CAPTURE("Video capture settings",
        "Open the arrow menu to choose a supported frame rate and bitrate. Higher bitrate makes larger files.\n\nOIS starts on for supported lenses. Capture gyro data starts off; turn it on to embed motion data for later stabilization in compatible software. Gyro capture turns OIS off. Live stabilization hides both controls and also turns OIS off. Your OIS choice returns when those features are off."),
    STABILIZATION("Stabilize while recording",
        "Enable live gyro stabilization in the video menu. Crop reserves room to correct shake and narrows the view. Smoothing steadies motion; Strength controls how much correction is applied. Set these before recording.\n\nPans follow your movement while other shake is reduced. Telephoto lenses reach the margin sooner. Crop limit means stronger movement must follow the camera to avoid black borders. Stabilization is already applied to the saved video."),
    PROCESSING("Video processing",
        "Swipe up and open PROCESSING. Cards and profile editors show only controls for the current mode. Video settings are separate from photo export. VF-S controls spektrafilm in both the preview and saved video.\n\nChroma Denoise and Hot Pixel Removal both start off for video. Chroma Denoise reduces colored noise; swipe its card for strength, or double-tap to reset to 100%. Higher strength uses more processing time. Hot Pixel Removal removes isolated bright sensor pixels. Set video processing before recording."),
    EXPOSURE("Exposure and focus in video",
        "ISO, SHUTTER, WB and FOCUS work in video mode. With Unspektra AE, hold ISO or SHUTTER to lock one while the other stays automatic. The Priority label marks that lock.\n\nThe shutter label turns red when exposure is too long for the selected frame rate. Choose a faster shutter to avoid slow frames. Briefly hold FOCUS with its slider closed to return to Auto.\n\nAfter a manual adjustment, hold ISO or SHUTTER to keep that value fixed and return the other to Auto. Manual WB shows the selected temperature color. Briefly hold WB with its slider closed to restore Auto.\n\nUse the top peaking button for autofocus, or open the manual FOCUS slider for manual peaking. Peaking affects only the preview; the recorded video keeps its selected look and processing.\n\nTap the shutter to record with your current settings. Hold it for 1 second, until the ring fills, to record with exposure, focus and white balance locked. A lock with AE/AF/WB appears under the recording timer, and your previous settings return when recording stops."),
    REVIEW("Review your recording",
        "The timer shows recording progress. Tap stop and wait for saving to finish. Open Gallery to play the clip. Videos fill portrait and landscape views; tap to show or hide playback controls, which fade after three seconds.\n\nReplay this tour from Camera settings > General > Guides > Show video guide. Photo and video guide progress are remembered separately."),
}

@Composable
internal fun VideoGuide(step: VideoGuideStep, targetBounds: Rect?, onSkip: () -> Unit, onNext: () -> Unit) {
    GuideOverlay("VIDEO GUIDE", step.ordinal, VIDEO_GUIDE_STEPS, step.title, step.description,
        targetBounds, onSkip, onNext, tag = "Video guide")
}
