package com.unspektrawesome.ui.camera

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.unspektrawesome.R
import androidx.compose.ui.unit.sp

internal object CameraTypography {
    val Body = androidx.compose.material3.Typography().let {
        it.copy(
            displayLarge = it.displayLarge.copy(letterSpacing = 0.sp),
            headlineLarge = it.headlineLarge.copy(letterSpacing = 0.sp),
            headlineMedium = it.headlineMedium.copy(letterSpacing = 0.sp),
            titleLarge = it.titleLarge.copy(letterSpacing = 0.sp),
            titleMedium = it.titleMedium.copy(letterSpacing = 0.sp),
            titleSmall = it.titleSmall.copy(letterSpacing = 0.sp),
            bodyLarge = it.bodyLarge.copy(letterSpacing = 0.sp),
            bodyMedium = it.bodyMedium.copy(letterSpacing = 0.sp),
            bodySmall = it.bodySmall.copy(letterSpacing = 0.sp),
            labelLarge = it.labelLarge.copy(letterSpacing = 0.sp),
            labelMedium = it.labelMedium.copy(letterSpacing = 0.sp),
            labelSmall = it.labelSmall.copy(letterSpacing = 0.sp),
        )
    }
    internal var nativeReadout: android.graphics.Typeface? = null
        private set

    fun loadNativeReadout(context: android.content.Context): android.graphics.Typeface {
        return nativeReadout ?: (androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
            ?: android.graphics.Typeface.MONOSPACE).also { nativeReadout = it }
    }

    val Readout = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    )
}
