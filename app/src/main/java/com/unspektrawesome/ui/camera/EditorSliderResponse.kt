package com.unspektrawesome.ui.camera

internal fun editorSliderValue(position: Float, startPosition: Float, startValue: Float, sensitivity: Float): Float {
    val x = position.coerceIn(0f, 1f)
    if (x == 0f || x == 1f || sensitivity >= 1f) return x
    val start = startPosition.coerceIn(0f, 1f)
    val value = if (start == 0f || start == 1f) start else startValue.coerceIn(0f, 1f)
    if (x == start) return value

    // Join two cubic curves at the drag start, with the same slope and fixed endpoints.
    val leftSlope = if (start > 0f) value / start else Float.POSITIVE_INFINITY
    val rightSlope = if (start < 1f) (1f - value) / (1f - start) else Float.POSITIVE_INFINITY
    val slope = minOf(sensitivity.coerceAtLeast(0f), leftSlope, rightSlope)
    val end = if (x < start) 0f else 1f
    val distance = end - start
    val t = (x - start) / distance
    val linear = slope * distance
    return (value + linear * t + (end - value - linear) * t * t * t).coerceIn(0f, 1f)
}
