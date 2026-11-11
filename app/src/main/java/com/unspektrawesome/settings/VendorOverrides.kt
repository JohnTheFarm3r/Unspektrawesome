package com.unspektrawesome.settings

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

enum class VendorTagTarget(val label: String) { REQUEST("Request"), SESSION("Session") }

enum class VendorValueType(val label: String) {
    I32("i32"), I64("i64"), FLOAT("float"), U8("u8"),
    I32_ARRAY("i32[]"), I64_ARRAY("i64[]"), FLOAT_ARRAY("float[]"), U8_ARRAY("u8[]");

    fun parse(text: String): Any {
        val input = text.trim()
        require(input.isNotEmpty()) { "Enter a value." }
        require(input.length <= 16_384) { "The value is too long." }
        fun integer(value: String, bits: Int): Long {
            val number = parseVendorInteger(value)
            val maximum = BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
            val minimum = BigInteger.ONE.shiftLeft(bits - 1).negate()
            require(number in minimum..maximum) { "Use a signed $bits-bit integer." }
            return number.toLong()
        }
        fun byte(value: String): Byte {
            val number = parseVendorInteger(value)
            require(number in BigInteger.ZERO..BigInteger.valueOf(255)) { "Use a byte from 0 to 255." }
            return number.toByte()
        }
        fun float(value: String): Float = requireNotNull(value.toFloatOrNull()?.takeIf { it.isFinite() }) {
            "Use a finite floating-point number."
        }
        val array = name.endsWith("_ARRAY")
        val values = if (array) {
            require(input.startsWith('[') == input.endsWith(']')) { "Close the array brackets." }
            val body = input.removeSurrounding("[", "]").trim()
            require(body.isNotEmpty()) { "Enter at least one array value." }
            val parts = body.split(',')
            require(parts.none { it.isBlank() }) { "Enter a value between each comma." }
            parts.flatMap { it.trim().split(Regex("\\s+")) }.also {
                require(it.size <= 1024) { "Use at most 1024 array values." }
            }
        } else listOf(input)
        return when (this) {
            I32 -> integer(input, 32).toInt()
            I64 -> integer(input, 64)
            FLOAT -> float(input)
            U8 -> byte(input)
            I32_ARRAY -> values.map { integer(it, 32).toInt() }.toIntArray()
            I64_ARRAY -> values.map { integer(it, 64) }.toLongArray()
            FLOAT_ARRAY -> values.map(::float).toFloatArray()
            U8_ARRAY -> values.map(::byte).toByteArray()
        }
    }
}

private fun parseVendorInteger(text: String): BigInteger {
    val input = text.trim()
    require(input.matches(Regex("[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)"))) {
        "Use a decimal integer or hexadecimal value such as 0x1234."
    }
    val unsigned = input.removePrefix("-").removePrefix("+")
    val number = if (unsigned.startsWith("0x", true)) BigInteger(unsigned.substring(2), 16) else BigInteger(unsigned)
    return if (input.startsWith('-')) number.negate() else number
}

data class VendorTagOverride(
    val key: String = "",
    val target: VendorTagTarget = VendorTagTarget.REQUEST,
    val type: VendorValueType? = null,
    val value: String = "",
) {
    fun validationError(): String? = runCatching {
        require(key.trim().matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) {
            "Enter the full vendor key name."
        }
        requireNotNull(type) { "Select a value type." }.parse(value)
    }.exceptionOrNull()?.message

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("target", target.name); put("type", type?.name ?: JSONObject.NULL); put("value", value)
    }
}

data class VendorOverrides(
    val enabled: Boolean = false,
    val opMode: String = "0",
    val tags: List<VendorTagOverride> = emptyList(),
) {
    fun sessionMode(): Int {
        require(opMode.length <= 32) { "The opmode is too long." }
        val value = parseVendorInteger(opMode)
        require(value in BigInteger.ZERO..BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
            "Use an opmode from 0 to 2147483647."
        }
        require(value != BigInteger.ONE && value != BigInteger.valueOf(2)) {
            "Modes 1 and 2 are reserved by Android. Use 0 or a vendor opmode."
        }
        return value.toInt()
    }

    fun opModeError(): String? = runCatching { sessionMode() }.exceptionOrNull()?.message

    fun validationError(): String? {
        if (!enabled) return null
        opModeError()?.let { return it }
        if (tags.size > MAX_TAGS) return "Use at most $MAX_TAGS vendor tags."
        tags.forEachIndexed { index, tag -> tag.validationError()?.let { return "Tag ${index + 1}: $it" } }
        if (tags.distinctBy { it.target to it.key.trim() }.size != tags.size) return "Each key can appear only once per target."
        return null
    }

    fun active(): VendorOverrides = if (!enabled || (sessionModeOrNull() == 0 && tags.isEmpty())) NONE else this

    private fun sessionModeOrNull() = runCatching { sessionMode() }.getOrNull()

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("opMode", opMode)
        put("tags", JSONArray().apply { tags.forEach { put(it.toJson()) } })
    }

    companion object {
        const val MAX_TAGS = 64
        @JvmField val NONE = VendorOverrides()

        fun fromJson(json: JSONObject?): VendorOverrides {
            if (json == null) return NONE
            val array = json.optJSONArray("tags") ?: JSONArray()
            return VendorOverrides(json.optBoolean("enabled"), json.optString("opMode", "0"),
                (0 until array.length()).map { index ->
                    val tag = array.getJSONObject(index)
                    VendorTagOverride(tag.optString("key"),
                        VendorTagTarget.valueOf(tag.getString("target")),
                        if (tag.isNull("type")) null else VendorValueType.valueOf(tag.getString("type")),
                        tag.optString("value"))
                })
        }
    }
}

fun CameraPreferences.vendorOverrides(): VendorOverrides = lensProfiles.firstOrNull {
    it.id == activeLensProfileId && it.enabled
}?.vendorOverrides?.active() ?: VendorOverrides.NONE
