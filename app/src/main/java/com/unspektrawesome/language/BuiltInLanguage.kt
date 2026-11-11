package com.unspektrawesome.language

internal data class BuiltInLanguage(val code: String, val nativeName: String, val flag: String) {
    companion object {
        val all = listOf(
            BuiltInLanguage("en", "English", "🇬🇧"),
            BuiltInLanguage("de", "Deutsch", "🇩🇪"),
            BuiltInLanguage("fr", "Français", "🇫🇷"),
            BuiltInLanguage("it", "Italiano", "🇮🇹"),
            BuiltInLanguage("ru", "Русский", "🇷🇺"),
            BuiltInLanguage("es", "Español", "🇪🇸"),
            BuiltInLanguage("pt", "Português", "🇵🇹"),
            BuiltInLanguage("uk", "Українська", "🇺🇦"),
            BuiltInLanguage("pl", "Polski", "🇵🇱"),
            BuiltInLanguage("zh", "简体中文", "🇨🇳"),
            BuiltInLanguage("ja", "日本語", "🇯🇵"),
            BuiltInLanguage("sr", "Srpski (latinica)", "🇷🇸"),
            BuiltInLanguage("hu", "Magyar", "🇭🇺"),
        )
    }
}
