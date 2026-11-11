package com.unspektrawesome.ui.camera

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraStateRepository
import com.unspektrawesome.video.VideoRecordingState

internal enum class ProcessingScope { SHARED, PHOTO, VIDEO }

internal val processingCardCopy = mapOf(
    "spektra" to ("Film processing" to "spektrafilm"),
    "highlight-threshold" to ("Highlight Compression" to "Start brightness"),
    "chroma-denoise" to ("" to "Chroma Denoise"),
    "video-hot-pixels" to ("Video recording" to "Hot Pixel Removal"),
    "photo-hot-pixels" to ("Photo export" to "Hot Pixel Removal"),
)

internal data class ProcessingLookControl(
    val key: String,
    val label: String,
    val active: Boolean,
    val scope: ProcessingScope = ProcessingScope.SHARED,
    val available: Boolean = true,
    val toggleable: Boolean = true,
    val amount: Float? = null,
    val defaultAmount: Float = 1f,
    val amountLabel: String = "Strength",
    val onToggle: () -> Unit = {},
    val onAmountChange: (Float) -> Unit = {},
) {
    val heading: String get() = processingCardCopy.getValue(key).first
    val title: String get() = processingCardCopy.getValue(key).second
}

@Composable
internal fun ProcessingLookCards(
    params: SpektraFilmParams,
    repository: SpektraStateRepository,
    rotationDegrees: Float,
    videoState: VideoRecordingState,
    onVideoChromaDenoiseChanged: (Boolean) -> Unit,
    onVideoChromaDenoiseStrengthChanged: (Float) -> Unit,
    onVideoHotPixelRemovalChanged: (Boolean) -> Unit,
    onReorderBounds: (Rect) -> Unit,
    onReorderFinished: () -> Unit,
) {
    val defaults = remember { SpektraFilmParams() }
    val controls = listOf(
        ProcessingLookControl("spektra", "spektrafilm processing", params.enabled,
            onToggle = { repository.updateParams { it.copy(enabled = !it.enabled) } }),
        ProcessingLookControl("highlight-threshold", "Highlight compression start brightness",
            active = true, toggleable = false, amount = params.inputHighlightThreshold,
            defaultAmount = defaults.inputHighlightThreshold, amountLabel = "Start brightness",
            onAmountChange = { value -> repository.updateParams { it.copy(inputHighlightThreshold = value) } }),
        ProcessingLookControl("chroma-denoise", "Chroma Denoise",
            active = if (videoState.enabled) videoState.chromaDenoise else params.exportChromaDenoise,
            available = !videoState.enabled || !videoState.busy,
            amount = if (videoState.enabled) videoState.chromaDenoiseStrength else params.chromaDenoiseStrength,
            defaultAmount = if (videoState.enabled) 1f else defaults.chromaDenoiseStrength,
            onToggle = {
                if (videoState.enabled) onVideoChromaDenoiseChanged(!videoState.chromaDenoise)
                else repository.updateParams { it.copy(exportChromaDenoise = !it.exportChromaDenoise) }
            },
            onAmountChange = { value ->
                if (videoState.enabled) onVideoChromaDenoiseStrengthChanged(value)
                else repository.updateParams { it.copy(chromaDenoiseStrength = value) }
            }),
        ProcessingLookControl("video-hot-pixels", "Video hot pixel removal",
            scope = ProcessingScope.VIDEO,
            active = videoState.hotPixelRemoval, available = !videoState.busy,
            onToggle = { onVideoHotPixelRemovalChanged(!videoState.hotPixelRemoval) }),
        ProcessingLookControl("photo-hot-pixels", "Photo hot pixel removal",
            scope = ProcessingScope.PHOTO,
            active = params.exportHotPixelRemoval,
            onToggle = { repository.updateParams { it.copy(exportHotPixelRemoval = !it.exportHotPixelRemoval) } }),
    )
    val mode = if (videoState.enabled) ProcessingScope.VIDEO else ProcessingScope.PHOTO
    val visible = controls.filter { it.scope == ProcessingScope.SHARED || it.scope == mode }
    ChoiceCards(visible.map { it.label }, -1, "processing", rotationDegrees, repository.cardOrders,
        cardIds = visible.map { it.key }, controls = visible, allCardIds = controls.map { it.key },
        onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished)
}
