package com.unspektrawesome.calibration

import com.unspektrawesome.camera.ColorCalibration
import com.unspektrawesome.camera.WhiteBalanceTemperature
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object DcpParser {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    const val MAX_TABLE_ENTRIES = 131_072

    fun readBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            require(output.size() + read <= MAX_FILE_BYTES) { "DCP file exceeds 16 MB" }
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }

    fun parse(bytes: ByteArray): DcpProfile {
        require(bytes.size in 8..MAX_FILE_BYTES) { "Invalid DCP file size" }
        val order = when (bytes.take(2)) {
            listOf(0x49.toByte(), 0x49.toByte()) -> ByteOrder.LITTLE_ENDIAN
            listOf(0x4d.toByte(), 0x4d.toByte()) -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("Select a DCP camera profile")
        }
        val buffer = ByteBuffer.wrap(bytes).order(order)
        fun u16(offset: Int) = buffer.getShort(offset).toInt() and 0xffff
        fun u32(offset: Int) = buffer.getInt(offset).toLong() and 0xffffffffL
        require(u16(2) in setOf(42, 0x4352)) { "Unsupported DCP header" }
        val directory = u32(4)
        require(directory in 8..(bytes.size - 2).toLong()) { "DCP directory is outside the file" }
        val count = u16(directory.toInt())
        require(count in 1..4096 && directory + 2 + count * 12L + 4 <= bytes.size) { "DCP directory is truncated" }
        val fields = mutableMapOf<Int, Field>()
        repeat(count) { index ->
            val at = directory.toInt() + 2 + index * 12
            val tag = u16(at)
            val type = u16(at + 2)
            val typeSize = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8
                else -> throw IllegalArgumentException("Unsupported DCP field type $type") }
            val values = u32(at + 4)
            val length = values * typeSize
            val offset = if (length <= 4) (at + 8).toLong() else u32(at + 8)
            require(values <= Int.MAX_VALUE && offset <= bytes.size && length <= bytes.size - offset) { "DCP field $tag is truncated" }
            require(fields.put(tag, Field(buffer, type, values.toInt(), offset.toInt())) == null) { "DCP contains a duplicate field $tag" }
        }
        require(listOf(52529, 52531, 52532, 52537).none(fields::containsKey)) { "Three-illuminant DCP profiles are not supported" }
        fields[52551]?.let {
            require(it.type == 7 && it.count == 8 && u16(it.offset) == 1 && u16(it.offset + 2) == 0) {
                "HDR DCP profiles are not supported"
            }
        }
        fun matrix(tag: Int): FloatArray? = fields[tag]?.let { field ->
            require(field.count == 9 && field.type in setOf(5, 10, 11, 12)) { "DCP matrices must contain nine numbers" }
            field.floats().also { m ->
                val determinant = m[0].toDouble() * (m[4].toDouble() * m[8] - m[5].toDouble() * m[7]) -
                    m[1].toDouble() * (m[3].toDouble() * m[8] - m[5].toDouble() * m[6]) +
                    m[2].toDouble() * (m[3].toDouble() * m[7] - m[4].toDouble() * m[6])
                require(determinant.isFinite() && abs(determinant) > 1e-10) { "DCP contains a singular color matrix" }
                if (tag == 50964 || tag == 50965) require((0..2).all { row -> (0..2).sumOf { m[row * 3 + it].toDouble() } > 0.0 }) {
                    "DCP forward matrix has an invalid white point"
                }
            }
        }
        val color1 = matrix(50721)
        val color2 = matrix(50722)
        val forward1 = matrix(50964)
        val forward2 = matrix(50965)
        require(color1 != null || forward1 != null) { "DCP has no usable color calibration" }
        val illuminant1 = fields[50778]?.integer()
        val illuminant2 = fields[50779]?.integer()
        val dual = color2 != null || forward2 != null
        require(!dual || color1 != null && color2 != null &&
            WhiteBalanceTemperature.illuminantTemperature(illuminant1) != null &&
            WhiteBalanceTemperature.illuminantTemperature(illuminant2) != null && illuminant1 != illuminant2) {
            "Dual-illuminant DCP needs two color matrices and supported, distinct illuminants"
        }
        require((forward1 == null) == (forward2 == null) || !dual) { "Dual-illuminant DCP has incomplete forward matrices" }
        val dimensions = fields[50937]?.let {
            require(it.type == 4 && it.count == 3) { "Invalid DCP hue/saturation dimensions" }
            it.floats().map { value ->
                require(value >= 1f && value <= MAX_TABLE_ENTRIES && value == value.toInt().toFloat()) { "Invalid DCP table dimensions" }
                value.toInt()
            }
        }
        val entries = dimensions?.let { it[0].toLong() * it[1] * it[2] } ?: 0L
        require(dimensions == null || dimensions[1] >= 2 && entries in 1..MAX_TABLE_ENTRIES.toLong()) { "DCP hue/saturation table is too large or invalid" }
        fun table(tag: Int): FloatArray? = fields[tag]?.let {
            require(dimensions != null && it.type == 11 && it.count.toLong() == entries * 3) { "DCP hue/saturation table is incomplete" }
            it.floats().also { values ->
                values.forEachIndexed { index, value ->
                    require(if (index % 3 == 0) abs(value) <= 360f else value >= 0f) { "DCP hue/saturation table has invalid adjustments" }
                    if (index % 3 == 2 && index / 3 % dimensions[1] == 0) require(abs(value - 1f) < 1e-5f) {
                        "DCP hue/saturation table changes neutral brightness"
                    }
                }
            }
        }
        val table1 = table(50938)
        val table2 = table(50939)
        require((dimensions == null) == (table1 == null) && (table2 == null || dual)) { "DCP hue/saturation calibration is incomplete" }
        val encoding = fields[51107]?.integer() ?: 0
        require(encoding in 0..1) { "Unsupported DCP hue/saturation encoding" }
        return DcpProfile(fields[50936]?.text().orEmpty().ifBlank { "Custom DCP" }, fields[50708]?.text(), fields[50942]?.text(),
            ColorCalibration(color1, color2, forward1, forward2, null, null, illuminant1, illuminant2), dual,
            dimensions?.get(0) ?: 0, dimensions?.get(1) ?: 0, dimensions?.get(2) ?: 0, encoding,
            table1, table2, fields.containsKey(50982), fields.containsKey(50940))
    }

    private class Field(val buffer: ByteBuffer, val type: Int, val count: Int, val offset: Int) {
        fun integer(): Int {
            require(count == 1 && type in setOf(3, 4)) { "Invalid DCP integer field" }
            val value = if (type == 3) (buffer.getShort(offset).toInt() and 0xffff).toLong() else buffer.getInt(offset).toLong() and 0xffffffffL
            require(value <= Int.MAX_VALUE) { "DCP integer is out of range" }
            return value.toInt()
        }
        fun text(): String {
            require(type == 1 || type == 2 || type == 7) { "Invalid DCP text field" }
            require(count <= 4096) { "DCP text is too long" }
            val bytes = ByteArray(count)
            buffer.duplicate().apply { position(offset); get(bytes) }
            return bytes.toString(Charsets.UTF_8).substringBefore('\u0000').trim()
        }
        fun floats(): FloatArray = FloatArray(count) { index ->
            val value = when (type) {
                3 -> (buffer.getShort(offset + index * 2).toInt() and 0xffff).toDouble()
                4 -> (buffer.getInt(offset + index * 4).toLong() and 0xffffffffL).toDouble()
                5, 10 -> {
                    val n = buffer.getInt(offset + index * 8)
                    val d = buffer.getInt(offset + index * 8 + 4)
                    val numerator = if (type == 5) (n.toLong() and 0xffffffffL).toDouble() else n.toDouble()
                    val denominator = if (type == 5) (d.toLong() and 0xffffffffL).toDouble() else d.toDouble()
                    require(denominator != 0.0) { "DCP contains a zero denominator" }
                    numerator / denominator
                }
                11 -> buffer.getFloat(offset + index * 4).toDouble()
                12 -> buffer.getDouble(offset + index * 8)
                else -> throw IllegalArgumentException("Unsupported DCP numeric field")
            }
            require(value.isFinite() && value.toFloat().isFinite()) { "DCP contains a non-finite value" }
            value.toFloat()
        }
    }
}
