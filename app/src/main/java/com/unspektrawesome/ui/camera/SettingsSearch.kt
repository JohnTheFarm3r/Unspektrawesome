package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics
import java.text.Normalizer
import java.util.Locale

/**
 * Settings rows that can be found by searching. Rows add themselves while they are composed,
 * so new settings become searchable without a separate list.
 */
internal class SettingsSearchIndex {
    internal class Target(val title: String, val page: Int, val translate: Boolean, val heading: Boolean,
        val requester: BringIntoViewRequester) {
        var top = 0f
    }

    internal data class Jump(val page: Int, val title: String, val openProfileEditor: Boolean = false)

    val targets = mutableStateListOf<Target>()
    var highlighted by mutableStateOf<Target?>(null)
    var pending by mutableStateOf<Jump?>(null)
    /** Increases when a search result asks the lens profile page to open the profile in use. */
    var profileEditorRequest by mutableIntStateOf(0)
}

internal val LocalSettingsSearch = staticCompositionLocalOf<SettingsSearchIndex?> { null }
internal val LocalSettingsSearchPage = compositionLocalOf { -1 }

/** Makes a settings row searchable and lets search scroll to it and briefly outline it. */
@Composable
internal fun settingsSearchTarget(title: String?, translate: Boolean = true, heading: Boolean = false): Modifier {
    val index = LocalSettingsSearch.current
    val page = LocalSettingsSearchPage.current
    if (index == null || page < 0 || title.isNullOrBlank()) return Modifier
    val requester = remember { BringIntoViewRequester() }
    val target = remember(index, page, title, translate, heading) {
        SettingsSearchIndex.Target(title, page, translate, heading, requester)
    }
    DisposableEffect(target) {
        index.targets.add(target)
        onDispose { index.targets.remove(target) }
    }
    val active = index.highlighted === target
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 160 else 900), label = "Settings search highlight")
    LaunchedEffect(active) {
        if (active) {
            kotlinx.coroutines.delay(1400)
            if (index.highlighted === target) index.highlighted = null
        }
    }
    return Modifier.bringIntoViewRequester(requester)
        .onGloballyPositioned { target.top = it.positionInRoot().y }
        .drawBehind {
            if (glow > 0f) {
                val radius = CornerRadius(8.dp.toPx())
                drawRoundRect(CameraColors.Accent.copy(alpha = .08f * glow), cornerRadius = radius)
                drawRoundRect(CameraColors.Accent.copy(alpha = glow), cornerRadius = radius, style = Stroke(1.dp.toPx()))
            }
        }
}

@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val renderer = LocalAppLanguage.current.renderer
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(CameraColors.Accent, CameraColors.Accent.copy(alpha = .3f))) {
        BasicTextField(query, onQueryChange, singleLine = true, interactionSource = interaction,
            cursorBrush = SolidColor(CameraColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            textStyle = TextStyle(color = CameraColors.Text, fontSize = 14.sp, lineHeight = 20.sp),
            modifier = modifier.fillMaxWidth().testTag("Settings search")
                .languageSemantics { language -> contentDescription = language.text("Search settings") },
            decorationBox = { field ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Diagram.ControlShape).background(CameraColors.MenuCard)
                    .border(1.dp, if (focused) CameraColors.Accent else CameraColors.MenuBorder, Diagram.ControlShape)
                    .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsIcon(SettingsSymbol.Search, Modifier.size(16.dp), if (focused) CameraColors.Accent else Diagram.Hint)
                    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        if (query.isEmpty()) Text(renderer.text("Search settings"), translate = false, color = Diagram.Hint,
                            fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
                        field()
                    }
                    if (query.isNotEmpty()) Box(Modifier.size(40.dp).clip(Diagram.ControlShape)
                        .clickable(role = Role.Button) { onQueryChange("") }
                        .languageSemantics { language -> contentDescription = language.text("Clear search") },
                        contentAlignment = Alignment.Center) {
                        SettingsIcon(SettingsSymbol.Close, Modifier.size(14.dp), CameraColors.Text)
                    }
                }
            })
    }
}

/** A setting that is only shown after opening a lens profile, so it cannot add itself while the list is shown. */
internal class SettingsSearchExtra(val title: String, val page: Int)

private class SettingsSearchResult(val title: String, val path: String, val jump: SettingsSearchIndex.Jump, val rank: Int,
    val order: Float)

private fun searchable(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

@Composable
internal fun SettingsSearchResults(query: String, index: SettingsSearchIndex, tabs: List<String>, extras: List<SettingsSearchExtra>,
    modifier: Modifier = Modifier, onPick: (SettingsSearchIndex.Jump) -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    val words = searchable(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val results = run {
        fun shown(title: String, translate: Boolean) = if (translate) renderer.text(title) else title
        fun rank(title: String, english: String, section: String): Int? {
            val name = searchable(title)
            val source = searchable(english)
            val all = "$name $source ${searchable(section)}"
            return when {
                words.isEmpty() || words.any { it !in all } -> null
                words.all { word -> name.split(' ').any { it.startsWith(word) } } -> 0
                words.all { it in name || it in source } -> 1
                else -> 2
            }
        }
        val headings = index.targets.filter { it.heading }.groupBy { it.page }
        val found = index.targets.mapNotNull { target ->
            val title = shown(target.title, target.translate)
            val section = if (target.heading) "" else headings[target.page].orEmpty()
                .filter { it.top <= target.top + 1f }.maxByOrNull { it.top }?.let { shown(it.title, it.translate) }.orEmpty()
            rank(title, target.title, section)?.let { rank ->
                SettingsSearchResult(title, listOf(tabs.getOrElse(target.page) { "" }, section).filter { it.isNotBlank() }.joinToString(" · "),
                    SettingsSearchIndex.Jump(target.page, target.title), rank, target.page * 1e6f + target.top)
            }
        } + extras.mapNotNull { extra ->
            val title = renderer.text(extra.title)
            val section = renderer.text("Lens profile in use")
            rank(title, extra.title, section)?.let { rank ->
                SettingsSearchResult(title, "${tabs.getOrElse(extra.page) { "" }} · $section",
                    SettingsSearchIndex.Jump(extra.page, extra.title, openProfileEditor = true), rank, extra.page * 1e6f + 9e5f)
            }
        }
        found.distinctBy { "${it.jump.page}|${searchable(it.title)}|${it.path}" }
            .distinctBy { "${it.jump.page}|${searchable(it.title)}" }
            .sortedWith(compareBy<SettingsSearchResult> { it.rank }.thenBy { it.order }).take(80)
    }
    val fling = rememberPanelFlingBehavior()
    LazyColumn(modifier.fillMaxSize().background(Color.Black).testTag("Settings search results").trackPanelFling(fling),
        flingBehavior = fling, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp)) {
        if (results.isEmpty()) item(key = "empty") {
            DiagramNote("Try another word, such as focus, video or language.", title = "No matching settings")
        }
        items(results, key = { "${it.jump.page}|${it.jump.title}|${it.jump.openProfileEditor}" }) { result ->
            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick(result.jump) }
                .diagramHairline().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(result.title, translate = false, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
                Text(result.path, translate = false, color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp,
                    fontFamily = Diagram.Mono)
            }
        }
    }
}
