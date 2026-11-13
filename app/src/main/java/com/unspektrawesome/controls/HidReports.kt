package com.unspektrawesome.controls

/**
 * Reads the controls a USB input accessory describes about itself, and turns its input reports into button presses,
 * releases and wheel steps. Usages combine the usage page and id as `page shl 16 or id`.
 */
class HidReportLayout private constructor(private val fields: List<Field>, private val usesReportIds: Boolean) {
    private class Field(
        val reportId: Int,
        val bitOffset: Int,
        val size: Int,
        val count: Int,
        val usages: List<Int>,
        val usageMinimum: Int?,
        val logicalMinimum: Int,
        val logicalMaximum: Int,
        val variable: Boolean,
        val relative: Boolean,
    ) {
        fun usage(index: Int): Int? = usages.getOrNull(index) ?: usages.lastOrNull()?.takeIf { usageMinimum == null }

        fun arrayUsage(value: Int): Int? {
            if (value < logicalMinimum || value > logicalMaximum) return null
            val index = value - logicalMinimum
            val usage = usageMinimum?.plus(index) ?: usages.getOrNull(index) ?: return null
            return usage.takeIf { it and 0xFFFF != 0 }
        }
    }

    sealed interface Event {
        data class Button(val usage: Int, val pressed: Boolean) : Event
        data class Wheel(val usage: Int, val steps: Int) : Event
    }

    /** Usages held down by the last reports, per report id. */
    private val pressed = mutableMapOf<Int, Set<Int>>()

    val hasControls: Boolean get() = fields.isNotEmpty()

    /** Decodes one input report. Wheel steps come first, so a dial that also reports a button is recognized as a dial. */
    fun decode(report: ByteArray, length: Int = report.size): List<Event> {
        if (length <= 0) return emptyList()
        val reportId = if (usesReportIds) report[0].toInt() and 0xFF else 0
        val dataStart = if (usesReportIds) 8 else 0
        val wheels = mutableListOf<Event>()
        val down = mutableSetOf<Int>()
        var matched = false
        for (field in fields) {
            if (field.reportId != reportId) continue
            matched = true
            for (index in 0 until field.count) {
                val start = dataStart + field.bitOffset + index * field.size
                if (start + field.size > length * 8) break
                val raw = bits(report, start, field.size)
                val value = if (field.logicalMinimum < 0) signExtend(raw, field.size) else raw
                if (!field.variable) {
                    field.arrayUsage(value)?.let(down::add)
                    continue
                }
                val usage = field.usage(index) ?: continue
                when {
                    field.relative -> if (value != 0 && usage in WHEELS) wheels += Event.Wheel(usage, value)
                    // Some accessories report a held button as any non-zero number, such as a key code.
                    field.logicalMinimum == 0 && field.logicalMaximum == 1 || usage ushr 16 in BUTTON_PAGES ->
                        if (value != 0) down += usage
                }
            }
        }
        if (!matched) return emptyList()
        val previous = pressed[reportId].orEmpty()
        pressed[reportId] = down
        return wheels + (down - previous).map { Event.Button(it, true) } + (previous - down).map { Event.Button(it, false) }
    }

    /** Releases everything still held, for example when the accessory disconnects. */
    fun releaseAll(): List<Event> = pressed.values.flatten().distinct().map { Event.Button(it, false) }.also { pressed.clear() }

    companion object {
        const val WHEEL = 0x00010038
        const val PAN = 0x000C0238
        const val DIAL = 0x00010037
        private val WHEELS = setOf(WHEEL, PAN, DIAL)
        /** Keyboard, button, consumer, telephony and camera control pages. Battery and pointer values are not buttons. */
        private val BUTTON_PAGES = setOf(0x07, 0x09, 0x0B, 0x0C, 0x90)

        fun parse(descriptor: ByteArray): HidReportLayout {
            val fields = mutableListOf<Field>()
            val offsets = mutableMapOf<Int, Int>()
            var usagePage = 0
            var logicalMinimum = 0
            var logicalMaximum = 0
            var reportSize = 0
            var reportCount = 0
            var reportId = 0
            var usesIds = false
            val stack = ArrayDeque<IntArray>()
            var usages = mutableListOf<Int>()
            var usageMinimum: Int? = null
            var usageMaximum: Int? = null
            var position = 0
            while (position < descriptor.size) {
                val prefix = descriptor[position].toInt() and 0xFF
                if (prefix == 0xFE) {
                    // Long items carry no input layout.
                    val length = descriptor.getOrNull(position + 1)?.toInt()?.and(0xFF) ?: break
                    position += 3 + length
                    continue
                }
                val size = intArrayOf(0, 1, 2, 4)[prefix and 3]
                if (position + size >= descriptor.size) break
                val unsigned = (0 until size).fold(0) { value, byte ->
                    value or ((descriptor[position + 1 + byte].toInt() and 0xFF) shl (8 * byte))
                }
                val signed = if (size in 1..3) signExtend(unsigned, size * 8) else unsigned
                position += 1 + size
                val type = (prefix shr 2) and 3
                val tag = prefix shr 4
                fun fullUsage(value: Int) = if (size == 4) value else (usagePage shl 16) or (value and 0xFFFF)
                when (type) {
                    0 -> {
                        if (tag == 8) {
                            val flags = unsigned
                            val offset = offsets[reportId] ?: 0
                            val variable = flags and 2 != 0
                            if (flags and 1 == 0 && reportSize in 1..32 && reportCount > 0) {
                                val minimum = usageMinimum
                                val maximum = usageMaximum
                                // A usage range on a variable field names each element, such as a row of buttons.
                                val rangeUsages = if (variable && minimum != null && maximum != null && maximum >= minimum)
                                    (minimum..maximum).take(reportCount) else null
                                fields += Field(reportId, offset, reportSize, reportCount, rangeUsages ?: usages.toList(),
                                    if (rangeUsages == null && maximum != null) minimum else null,
                                    logicalMinimum, logicalMaximum, variable, relative = flags and 4 != 0)
                            }
                            offsets[reportId] = offset + reportSize * reportCount
                        }
                        if (tag in setOf(8, 9, 0xA, 0xB)) {
                            usages = mutableListOf()
                            usageMinimum = null
                            usageMaximum = null
                        }
                    }
                    1 -> when (tag) {
                        0 -> usagePage = unsigned
                        1 -> logicalMinimum = signed
                        2 -> logicalMaximum = if (logicalMinimum >= 0) unsigned else signed
                        7 -> reportSize = unsigned
                        8 -> { reportId = unsigned; usesIds = true }
                        9 -> reportCount = unsigned
                        0xA -> stack.addLast(intArrayOf(usagePage, logicalMinimum, logicalMaximum, reportSize, reportCount, reportId))
                        0xB -> stack.removeLastOrNull()?.let {
                            usagePage = it[0]; logicalMinimum = it[1]; logicalMaximum = it[2]
                            reportSize = it[3]; reportCount = it[4]; reportId = it[5]
                        }
                    }
                    2 -> when (tag) {
                        0 -> usages += fullUsage(unsigned)
                        1 -> usageMinimum = fullUsage(unsigned)
                        2 -> usageMaximum = fullUsage(unsigned)
                    }
                }
            }
            return HidReportLayout(fields, usesIds)
        }

        private fun bits(data: ByteArray, start: Int, size: Int): Int {
            var value = 0L
            for (bit in 0 until size) {
                val index = start + bit
                if ((data[index / 8].toInt() shr (index % 8)) and 1 != 0) value = value or (1L shl bit)
            }
            return value.toInt()
        }

        private fun signExtend(value: Int, bits: Int): Int =
            if (bits >= 32) value else (value shl (32 - bits)) shr (32 - bits)
    }
}
