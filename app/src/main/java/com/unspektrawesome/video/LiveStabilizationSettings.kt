package com.unspektrawesome.video

data class LiveStabilizationSettings(
    val cropRatio: Float = 1.2f,
    val smoothingSeconds: Float = .2f,
    val strength: Float = .8f,
) {
    init {
        require(cropRatio.isFinite() && cropRatio in 1f..1.5f)
        require(smoothingSeconds.isFinite() && smoothingSeconds in .05f..1f)
        require(strength.isFinite() && strength in 0f..1f)
    }
}
