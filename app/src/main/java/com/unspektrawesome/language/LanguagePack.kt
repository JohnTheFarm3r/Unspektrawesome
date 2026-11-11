package com.unspektrawesome.language

import org.json.JSONObject
import java.util.Locale

internal data class LanguagePack(val name: String, val direction: String, val strings: Map<String, String>) {
    fun toJson(english: Map<String, String>, version: String): String = JSONObject()
        .put("format", FORMAT).put("version", 1).put("appVersion", version)
        .put("language", name).put("direction", direction)
        .put("instructions", "Set language to your language's name and direction to ltr or rtl. Translate the values in strings, keeping the English keys and {placeholders} unchanged. Missing entries use English. Reset controls always remain in English.")
        .put("strings", JSONObject(english.mapValues { (key, fallback) -> strings[key] ?: fallback })).toString(2)

    companion object {
        const val FORMAT = "unspektrawesome.language"
        const val MAX_BYTES = 2 * 1024 * 1024
        private val placeholder = Regex("\\{([A-Za-z][A-Za-z0-9_]*|[0-9]+)\\}")
        fun placeholders(text: String): Set<String> = placeholder.findAll(text).map { it.groupValues[1] }.toSet()

        fun parse(json: String, english: Map<String, String>): LanguagePack {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This language file is too large." }
            var quoted = false
            var escaped = false
            var depth = 0
            json.forEach { character ->
                if (escaped) escaped = false
                else if (quoted && character == '\\') escaped = true
                else if (character == '"') quoted = !quoted
                else if (!quoted) when (character) {
                    '{', '[' -> {
                        depth++
                        require(depth <= 8) { "This language file has an invalid structure." }
                    }
                    '}', ']' -> depth--
                }
            }
            val root = JSONObject(json.removePrefix("\uFEFF"))
            require(root.opt("format") == FORMAT && root.opt("version") == 1) {
                "Choose an Unspektrawesome language JSON file exported from Language settings."
            }
            val name = (root.opt("language") as? String)?.trim().orEmpty()
            require(name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() }) { "Enter a language name between 1 and 80 characters." }
            val direction = if (root.has("direction")) root.opt("direction") else "ltr"
            require(direction in setOf("ltr", "rtl")) { "Language direction must be ltr or rtl." }
            val entries = root.getJSONObject("strings")
            require(entries.length() <= 20_000) { "This language file has too many entries." }
            val translations = linkedMapOf<String, String>()
            for (key in entries.keys()) {
                val value = entries.get(key)
                require(value is String && value.isNotBlank() && value.length <= 32_768 && '\u0000' !in value) {
                    "A translation is empty, too long, or is not text: ${key.take(100)}"
                }
                if (key !in english) continue
                require(placeholders(value) == placeholders(english.getValue(key))) {
                    "Keep every {placeholder} in this translation: ${key.take(100)}"
                }
                translations[key] = value
            }
            require(translations.isNotEmpty()) { "This file has no text matching the installed app's English template." }
            return LanguagePack(name, direction as String, translations)
        }
    }
}

internal class LanguageRenderer(english: Map<String, String>, private val pack: LanguagePack?) {
    private data class Template(val translated: String, val literals: List<String>, val arguments: List<String>) {
        fun match(source: String): Map<String, String>? {
            if (!source.startsWith(literals.first()) || !source.endsWith(literals.last())) return null
            var offset = literals.first().length
            val values = linkedMapOf<String, String>()
            arguments.forEachIndexed { index, name ->
                val next = literals[index + 1]
                val end = if (index == arguments.lastIndex) source.length - next.length
                    else if (next.isEmpty()) return null else source.indexOf(next, offset)
                if (end < offset) return null
                val value = source.substring(offset, end)
                if (name in values && values[name] != value) return null
                values[name] = value
                offset = end + next.length
            }
            return values.takeIf { offset == source.length }
        }
    }
    private val slots = Regex("\\{([A-Za-z][A-Za-z0-9_]*|[0-9]+)\\}")
    private val literalArguments = Regex("name|file|path|uri|key|id|version")
    private val keysBySource = english.entries.associate { it.value to it.key }
    private val translated = pack?.strings.orEmpty().mapNotNull { (key, value) ->
        english[key]?.takeIf { it != value }?.let { it to value }
    }.toMap()
    private val exact = translated.filterKeys { !slots.containsMatchIn(it) }
    private val whitespace = Regex("\\s+")
    private fun fold(text: String) = whitespace.replace(text, " ").trim().lowercase(Locale.ROOT)
    private val folded = exact.entries.associate { fold(it.key) to it.value }
    private val templates = if (translated.isEmpty()) emptyList() else english.map { (key, source) ->
        source to (pack?.strings?.get(key) ?: source)
    }.filter { slots.containsMatchIn(it.first) }
        .mapNotNull { (source, translated) ->
            val matches = slots.findAll(source).toList()
            if (!slots.replace(source, "").any(Char::isLetter)) return@mapNotNull null
            var offset = 0
            val literals = buildList {
                matches.forEach { match ->
                    add(source.substring(offset, match.range.first))
                    offset = match.range.last + 1
                }
                add(source.substring(offset))
            }
            Template(translated, literals, matches.map { it.groupValues[1] })
        }.sortedByDescending { it.literals.sumOf(String::length) }
    private val cache = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }

    fun text(source: String): String {
        if (pack == null || source.isEmpty()) return source
        return synchronized(cache) { cache[source] ?: render(source, 0).also { cache[source] = it } }
    }

    fun format(source: String, arguments: Map<String, Any?>): String {
        val template = pack?.strings?.get(keysBySource[source]) ?: source
        return slots.replace(template) { match ->
            val name = match.groupValues[1]
            val value = if (name in arguments) arguments[name]?.toString().orEmpty() else return@replace match.value
            if (literalArguments.containsMatchIn(name.lowercase(Locale.ROOT))) value else text(value)
        }
    }

    private fun render(source: String, depth: Int): String {
        exact[source]?.let { return it }
        if (depth > 3 || source.length > 32_768) return source
        folded[fold(source)]?.let { return if (source == source.uppercase(Locale.ROOT)) it.uppercase(Locale.ROOT) else it }
        for (template in templates) {
            val values = template.match(source) ?: continue
            return slots.replace(template.translated) {
                val name = it.groupValues[1]
                val value = values[name].orEmpty()
                if (literalArguments.containsMatchIn(name.lowercase(Locale.ROOT))) value
                else render(value, depth + 1)
            }
        }
        val trimmed = source.trim()
        if (trimmed != source) return source.substring(0, source.indexOf(trimmed)) + render(trimmed, depth + 1) + source.substring(source.indexOf(trimmed) + trimmed.length)
        val split = Regex("(\\n+|  ·  | · |: | / )").findAll(source).toList()
        if (split.isNotEmpty()) {
            var offset = 0
            return buildString {
                split.forEach { separator ->
                    append(render(source.substring(offset, separator.range.first), depth + 1)).append(separator.value)
                    offset = separator.range.last + 1
                }
                append(render(source.substring(offset), depth + 1))
            }
        }
        return source
    }
}
