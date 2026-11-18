package com.unspektrawesome.settings

import android.content.Context
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Size of the app's interface, independent of the phone's display size setting.
 * At 100% the narrow side of the screen is [STANDARD_WIDTH_DP] wide on every phone, so layouts look the same everywhere.
 * Large screens, such as tablets and unfolded foldables, start from their own display size instead, because stretching
 * a phone width across them makes every control oversized. Auto follows the device's display size on every screen.
 * Text still follows the device's font size in all cases.
 */
object AppDisplaySize {
    data class State(val auto: Boolean = false, val percent: Int = 100)

    const val STANDARD_WIDTH_DP = 411f

    /** Smallest screen width at which Android treats a screen as a tablet or an unfolded foldable. */
    const val LARGE_SCREEN_SMALLEST_WIDTH_DP = 600

    fun isLargeScreen(configuration: android.content.res.Configuration): Boolean =
        configuration.smallestScreenWidthDp >= LARGE_SCREEN_SMALLEST_WIDTH_DP
    val STEPS = listOf(80, 90, 100, 110, 120)

    private const val FILE = "app_display_size"
    private const val KEY_AUTO = "auto"
    private const val KEY_PERCENT = "percent"
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        mutableState.value = State(preferences.getBoolean(KEY_AUTO, false), nearestStep(preferences.getInt(KEY_PERCENT, 100)))
    }

    fun update(context: Context, next: State) {
        val value = next.copy(percent = nearestStep(next.percent))
        mutableState.value = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, value.auto).putInt(KEY_PERCENT, value.percent).apply()
    }

    fun nearestStep(percent: Int) = STEPS.minBy { kotlin.math.abs(it - percent) }

    /** Pixels per layout unit at 100%, or null when the screen size is unavailable. */
    fun standardDensity(context: Context): Float? = runCatching {
        if (isLargeScreen(context.resources.configuration)) return@runCatching context.resources.displayMetrics.density
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        min(bounds.width(), bounds.height()).takeIf { it > 0 }?.let { it / STANDARD_WIDTH_DP }
    }.getOrNull()

    /** The phone's own display size expressed on this app's scale. */
    fun systemPercent(context: Context, systemDensity: Float): Int =
        standardDensity(context)?.let { (systemDensity / it * 100f).roundToInt() } ?: 100
}
