package com.unspektrawesome.capture

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PhotoFileNames {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun baseName(capturedAtMillis: Long, utcOffsetSeconds: Int?): String {
        val instant = Instant.ofEpochMilli(capturedAtMillis)
        val offset = utcOffsetSeconds?.let(ZoneOffset::ofTotalSeconds)
            ?: ZoneId.systemDefault().rules.getOffset(instant)
        return "Unspektrawesome_${dateFormat.format(instant.atOffset(offset))}"
    }
}
