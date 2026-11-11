package com.unspektrawesome.ui.camera

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import java.math.BigDecimal
import java.math.RoundingMode

internal class ExposureRulerScale(val stops: List<Long>) {
    init { require(stops.isNotEmpty() && stops.first() > 0 && stops.zipWithNext().all { (a, b) -> a < b }) }

    fun position(value: Long): Float {
        val bounded = value.coerceIn(stops.first(), stops.last())
        val found = stops.binarySearch(bounded)
        if (found >= 0) return found.toFloat()
        val upper = -found - 1
        val lower = upper - 1
        return lower + (ln(bounded.toDouble() / stops[lower]) / ln(stops[upper].toDouble() / stops[lower])).toFloat()
    }

    fun value(position: Float): Long {
        val bounded = position.coerceIn(0f, stops.lastIndex.toFloat())
        val lower = bounded.toInt()
        if (lower == stops.lastIndex || bounded == lower.toFloat()) return stops[lower]
        return (stops[lower] * exp(ln(stops[lower + 1].toDouble() / stops[lower]) * (bounded - lower)))
            .roundToLong().coerceIn(stops.first(), stops.last())
    }

    fun shutterValue(position: Float): Long {
        val nanoseconds = value(position)
        if (nanoseconds == stops.first() || nanoseconds == stops.last()) return nanoseconds
        val rounded = if (nanoseconds < 350_000_000L) {
            1_000_000_000L / (1_000_000_000.0 / nanoseconds).roundToLong()
        } else {
            (nanoseconds / 100_000_000.0).roundToLong() * 100_000_000L
        }
        return rounded.coerceIn(stops.first(), stops.last())
    }
}

internal fun formatFineShutter(nanoseconds: Long): String {
    if (nanoseconds <= 0) return "-"
    fun number(value: Double, decimals: Int) = BigDecimal.valueOf(value)
        .setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    if (nanoseconds < 350_000_000L) {
        return "1/${(1_000_000_000.0 / nanoseconds).roundToLong()}"
    }
    return number(nanoseconds / 1_000_000_000.0, 1)
}
