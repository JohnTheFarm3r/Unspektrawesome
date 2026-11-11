package com.unspektrawesome.settings

enum class RawLevelMode(val label: String, val white: Int? = null, val black: Float? = null) {
    DYNAMIC("Dynamic (per frame from sensor)"),
    CUSTOM("Custom (static)"),
    BIT_10("10-bit · White 1023 / Black 64", 1023, 64f),
    BIT_12("12-bit · White 4095 / Black 256", 4095, 256f),
    BIT_14("14-bit · White 16383 / Black 1024", 16383, 1024f),
    BIT_16("16-bit · White 65535 / Black 4096", 65535, 4096f),
    BIT_12_LOW_BLACK("12-bit · White 4095 / Black 64", 4095, 64f),
    BIT_14_LOW_BLACK("14-bit · White 16383 / Black 64", 16383, 64f),
    BIT_14_ZERO("14-bit · White 16383 / Black 0", 16383, 0f),
}

data class RawLevelCalibration(
    val mode: RawLevelMode = RawLevelMode.DYNAMIC,
    val white: Int = 16383,
    val black00: Float = 1024f,
    val black01: Float = 1024f,
    val black10: Float = 1024f,
    val black11: Float = 1024f,
) {
    val overrideWhite: Int? get() = if (mode == RawLevelMode.CUSTOM) white else mode.white
    fun overrideBlack(): FloatArray? = when (mode) {
        RawLevelMode.DYNAMIC -> null
        RawLevelMode.CUSTOM -> floatArrayOf(black00, black01, black10, black11)
        else -> FloatArray(4) { requireNotNull(mode.black) }
    }

    fun isValid(): Boolean {
        val level = overrideWhite ?: return mode == RawLevelMode.DYNAMIC
        return level in 1..MAX_WHITE_LEVEL && overrideBlack()?.all { it.isFinite() && it >= 0f && it < level } == true
    }

    companion object {
        const val MAX_WHITE_LEVEL = 65535
    }
}

fun CameraPreferences.rawLevelCalibration(): RawLevelCalibration =
    lensProfiles.firstOrNull { it.id == activeLensProfileId }?.rawLevels ?: RawLevelCalibration()
