package com.unspektrawesome.language

import android.content.Context
import android.util.AtomicFile
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.io.readUpTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class LanguageState(val custom: LanguagePack? = null, val enabled: Boolean = false,
    val english: Map<String, String> = emptyMap(), val selectedCode: String = "en",
    val bundled: LanguagePack? = null, val choiceMade: Boolean = false) {
    val renderer = LanguageRenderer(english, if (enabled) custom else bundled)
    val rtl get() = enabled && custom?.direction == "rtl"
}

internal object AppLanguage {
    private val mutableState = MutableStateFlow(LanguageState())
    val state = mutableState.asStateFlow()
    private var displayState by mutableStateOf(mutableState.value)
    private var context: Context? = null
    private val lock = Any()

    fun initialize(application: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = application.applicationContext
        val json = app.assets.open("languages/english.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val entries = json.getJSONObject("strings")
        val english = entries.keys().asSequence().associateWith { entries.getString(it) }
        val file = AtomicFile(File(app.filesDir, "custom-language.json"))
        val custom = runCatching { file.openRead().use { LanguagePack.parse(read(it), english) } }.getOrNull()
        val preferences = app.getSharedPreferences("language", Context.MODE_PRIVATE)
        val enabled = custom != null && preferences.getBoolean("custom_enabled", false)
        val code = preferences.getString("selected_code", "en").takeIf { code -> BuiltInLanguage.all.any { it.code == code } } ?: "en"
        val bundled = loadBundled(app, code, english)
        context = app
        update(LanguageState(custom, enabled, english, if (enabled) "custom" else code, bundled,
            preferences.getBoolean("choice_made", enabled)))
    }

    fun text(source: String): String = displayState.renderer.text(source)
    fun format(source: String, arguments: Map<String, Any?>): String = displayState.renderer.format(source, arguments)

    private fun update(value: LanguageState) {
        displayState = value
        mutableState.value = value
        context?.let { com.unspektrawesome.installCameraShortcuts(it) }
    }

    fun read(input: InputStream): String {
        val bytes = input.readUpTo(LanguagePack.MAX_BYTES + 1)
        require(bytes.size <= LanguagePack.MAX_BYTES) { "This language file is too large." }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun import(input: InputStream): LanguagePack = synchronized(lock) {
        val previous = state.value
        val pack = LanguagePack.parse(read(input), previous.english)
        val file = AtomicFile(File(requireNotNull(context).filesDir, "custom-language.json"))
        val output = file.startWrite()
        try {
            output.write(pack.toJson(pack.strings, BuildConfig.VERSION_NAME).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        update(previous.copy(custom = pack))
        pack
    }

    fun useCustom(enabled: Boolean) = synchronized(lock) {
        require(!enabled || state.value.custom != null) { "Import a language file first." }
        requireNotNull(context).getSharedPreferences("language", Context.MODE_PRIVATE).edit()
            .putBoolean("custom_enabled", enabled).putString("selected_code", if (enabled) "custom" else "en")
            .putBoolean("choice_made", true).apply()
        update(state.value.copy(enabled = enabled, selectedCode = if (enabled) "custom" else "en", bundled = null, choiceMade = true))
    }

    fun select(code: String, confirm: Boolean = true) = synchronized(lock) {
        require(BuiltInLanguage.all.any { it.code == code })
        val previous = state.value
        val app = requireNotNull(context)
        val pack = loadBundled(app, code, previous.english)
        val confirmed = previous.choiceMade || confirm
        app.getSharedPreferences("language", Context.MODE_PRIVATE).edit()
            .putString("selected_code", code).putBoolean("custom_enabled", false)
            .putBoolean("choice_made", confirmed).apply()
        update(previous.copy(enabled = false, selectedCode = code, bundled = pack, choiceMade = confirmed))
    }

    private fun loadBundled(app: Context, code: String, english: Map<String, String>): LanguagePack? =
        if (code == "en") null else app.assets.open("languages/$code.json").use { LanguagePack.parse(read(it), english) }

    fun exportEnglish(): String = LanguagePack("English", "ltr", emptyMap()).toJson(state.value.english, BuildConfig.VERSION_NAME)
    fun exportCustom(): String = requireNotNull(state.value.custom).toJson(state.value.english, BuildConfig.VERSION_NAME)
}
