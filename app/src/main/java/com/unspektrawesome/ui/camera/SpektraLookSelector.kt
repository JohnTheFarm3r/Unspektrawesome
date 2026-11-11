package com.unspektrawesome.ui.camera

import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.language.AppLanguage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.spektra.SpektraFilmParams
import com.unspektrawesome.spektra.SpektraCardOrders
import com.unspektrawesome.spektra.SpektraLook
import com.unspektrawesome.spektra.SpektraState
import com.unspektrawesome.spektra.SpektraStateRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class LookSection(val label: String) {
    Film("FILM"), Print("PRINT"), Format("FORMAT"), Effects("EFFECTS"),
    Processing("PROCESSING"), Exposure("EXPOSURE"), Custom("CUSTOM"),
}
private val exposureCards = listOf("Center weighted", "Median")

private val filmCards = SpektraFilmParams.FILM_LABELS.withIndex().filterNot {
    it.value in setOf("Fujifilm Provia 100F", "Fujifilm Velvia 100", "Kodak Kodachrome 64", "Kodak Ektachrome 100")
}
private val filmCardLabels = filmCards.map { it.value }

@Composable
internal fun SpektraLookSelector(
    state: SpektraState,
    repository: SpektraStateRepository,
    onClose: () -> Unit,
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
    customSelectionRequest: Int = 0,
    onCreateBounds: (Rect) -> Unit = {},
    onCustomSectionChanged: (Boolean) -> Unit = {},
    onReorderBounds: (Rect) -> Unit = {},
    onReorderFinished: () -> Unit = {},
    sensorAeActive: Boolean = false,
    videoState: com.unspektrawesome.video.VideoRecordingState = com.unspektrawesome.video.VideoRecordingState(),
    onVideoChromaDenoiseChanged: (Boolean) -> Unit = {},
    onVideoChromaDenoiseStrengthChanged: (Float) -> Unit = {},
    onVideoHotPixelRemovalChanged: (Boolean) -> Unit = {},
    processingSelectionRequest: Int = 0,
) {
    var sectionOrder by remember(repository.cardOrders) {
        mutableStateOf(repository.cardOrders.orderedKeys("tabs", LookSection.entries.map { it.name }))
    }
    var savedSection by rememberSaveable { mutableStateOf(LookSection.valueOf(sectionOrder.first())) }
    val sections = LookSection.entries.filterNot { sensorAeActive && it == LookSection.Exposure ||
        state.params.isMonochromeFilm && it == LookSection.Print ||
        videoState.enabled && it == LookSection.Format }
    val visibleOrder = sectionOrder.filter { LookSection.valueOf(it) in sections }
    val section = savedSection.takeIf { it in sections } ?: LookSection.valueOf(visibleOrder.first())
    val params = state.params
    val profiles by repository.customProfiles.profiles.collectAsState()
    val selectedProfileId by repository.selectedProfileId.collectAsState()
    val transfer = rememberProfileTransfer(repository.customProfiles, repository.cardOrders)
    var renameProfile by remember { mutableStateOf<com.unspektrawesome.spektra.SpektraProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<com.unspektrawesome.spektra.SpektraProfile?>(null) }
    LaunchedEffect(customSelectionRequest) {
        if (customSelectionRequest > 0) savedSection = LookSection.Custom
    }
    LaunchedEffect(processingSelectionRequest) {
        if (processingSelectionRequest > 0) savedSection = LookSection.Processing
    }
    LaunchedEffect(section) { onCustomSectionChanged(section == LookSection.Custom) }
    BackHandler(onBack = onClose)
    CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides CameraPressIndication) {
    Column(modifier) {
        key(sections) {
        LookSectionSelector(section, repository.cardOrders, visibleOrder,
            onOrderChanged = { sectionOrder = repository.cardOrders.saveVisible("tabs", it, LookSection.entries.map { page -> page.name }) },
            onSelect = { savedSection = it })
        CameraPageTransition(section, Modifier.fillMaxWidth().wrapContentHeight().clipToBounds(), order = { sectionOrder.indexOf(it.name) }) { displayedSection ->
        Column(Modifier.fillMaxWidth()) {
            when (displayedSection) {
                LookSection.Processing -> ProcessingLookCards(params, repository, rotationDegrees,
                    videoState, onVideoChromaDenoiseChanged, onVideoChromaDenoiseStrengthChanged,
                    onVideoHotPixelRemovalChanged,
                    onReorderBounds, onReorderFinished)
                LookSection.Film -> ChoiceCards(filmCardLabels, filmCards.indexOfFirst { it.index == params.film }, "film", rotationDegrees,
                    cardOrders = repository.cardOrders, cardIds = filmCards.map { it.index.toString() },
                    onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished) {
                    repository.updateParams { p -> SpektraLook.stock(p, filmCards[it].index) }
                }
                LookSection.Print -> ChoiceCards(SpektraFilmParams.PAPER_LABELS, params.paper, "paper", rotationDegrees,
                    cardOrders = repository.cardOrders,
                    onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished) {
                    repository.updateParams { p -> SpektraLook.paper(p, it) }
                }
                LookSection.Format -> ChoiceCards(SpektraLook.formats,
                    if (params.grainEnabled) params.filmFormat else -1, "format", rotationDegrees,
                    cardOrders = repository.cardOrders,
                    onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished,
                    onDisable = { repository.updateParams { it.copy(grainEnabled = false) } },
                    amounts = SpektraLook.formats.indices.map { SpektraLook.formatGrainAmount(params, it) },
                    amountLabel = "Grain",
                    defaultAmount = SpektraFilmParams().grainAmount,
                    onAmountChange = { index, amount ->
                        repository.updateParams { SpektraLook.formatGrain(it, index, amount).copy(grainEnabled = true) }
                    },
                ) {
                    repository.updateParams { p -> SpektraLook.format(p, it).copy(grainEnabled = true) }
                }
                LookSection.Custom -> {
                    val actions: @Composable () -> Unit = {
                        ProfileAction(if (profiles.isEmpty()) "Create profile" else "New",
                            accent = profiles.isEmpty(), onClick = { repository.clearProfileSelection(); onAdvanced() },
                            modifier = Modifier.onGloballyPositioned { onCreateBounds(it.boundsInRoot()) })
                        ProfileAction("Import", enabled = !transfer.busy, onClick = transfer.importProfile)
                        ProfileAction("Export", enabled = !transfer.busy && profiles.isNotEmpty(), onClick = transfer.exportProfiles)
                        profiles.firstOrNull { it.id == selectedProfileId }?.let { profile ->
                            ProfileAction("Edit", accent = true, onClick = onAdvanced)
                            ProfileAction("Rename", onClick = { renameProfile = profile })
                            ProfileAction("Delete", onClick = { deleteProfile = profile })
                        }
                    }
                    val selectedIndex = profiles.indexOfFirst { it.id == selectedProfileId }
                    ChoiceCards(profiles.map { it.name }, selectedIndex.takeIf {
                        it >= 0 && profiles[it].params == params
                    } ?: -1, "custom", rotationDegrees,
                        cardOrders = repository.cardOrders, cardIds = profiles.map { it.id },
                        emptyContent = {
                            OrientedLookPanel(abs(rotationDegrees) == 90f, rotationDegrees, Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().testTag("Empty custom profiles"), contentAlignment = Alignment.Center) {
                                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
                                }
                            }
                        },
                        onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished) { repository.applyProfile(profiles[it]) }
                    if (profiles.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                }
                LookSection.Exposure -> ChoiceCards(exposureCards,
                    if (params.autoExposure) params.autoExposureMethod else -1, "exposure", rotationDegrees,
                    cardOrders = repository.cardOrders,
                    onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished,
                    onDisable = { repository.updateParams { it.copy(autoExposure = false) } },
                    amountLabel = "Auto exposure",
                ) { method ->
                    repository.updateParams {
                        it.copy(autoExposure = true, autoExposureMethod = method)
                    }
                }
                LookSection.Effects -> Column {
                    ChoiceCards(SpektraLook.diffusionFamilies,
                        if (params.cameraDiffusionEnabled) params.cameraDiffusionFamily else -1, "diffusion", rotationDegrees,
                        cardOrders = repository.cardOrders,
                        onReorderBounds = onReorderBounds, onReorderFinished = onReorderFinished,
                        onDisable = { repository.updateParams { it.copy(cameraDiffusionEnabled = false) } },
                        amounts = SpektraLook.diffusionFamilies.indices.map { SpektraLook.diffusionAmount(params, it) / 2f },
                        amountLabel = "Diffusion",
                        maximumAmount = 1f,
                        defaultAmount = SpektraFilmParams().cameraDiffusionStrength / 2f,
                        dragDistanceMultiplier = 4f,
                        onAmountChange = { family, amount ->
                            repository.updateParams { SpektraLook.diffusion(it, family, amount * 2f) }
                        },
                    ) { family ->
                        repository.updateParams { SpektraLook.diffusionFamily(it, family) }
                    }
                    if (params.printDiffusionEnabled) {
                        Text("Print diffusion · Advanced", color = CameraColors.Accent, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                .clickable(role = Role.Button, onClick = onAdvanced).padding(4.dp))
                    }
                }
            }
        }
        }
        }
    }
    renameProfile?.let { profile ->
        SpektraProfileDialog(repository, profile, { renameProfile = null }, { renameProfile = null }, renameOnly = true)
    }
    deleteProfile?.let { profile ->
        CameraAlertDialog(
            onDismissRequest = { deleteProfile = null }, containerColor = CameraColors.Background,
            titleContentColor = CameraColors.Text, textContentColor = CameraColors.Muted,
            title = { CameraWindowBackdrop(); Text("Delete {name}?", arguments = mapOf("name" to (profile.name))) },
            text = { Text("This removes the saved card. Your current camera settings stay as they are.") },
            confirmButton = { TextButton(onClick = { repository.deleteProfile(profile.id); deleteProfile = null }) {
                Text("Delete", color = CameraColors.Accent)
            } },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("Cancel", color = CameraColors.Muted) } },
        )
    }
}
}

@Composable
private fun ProfileAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    accent: Boolean = false, onClick: () -> Unit) {
    val ink = if (!enabled) CameraColors.Muted.copy(alpha = .4f) else if (accent) CameraColors.Accent else CameraColors.Text
    Box(modifier.heightIn(min = 44.dp).clip(CameraStyle.ActionShape)
        .background(if (accent) CameraColors.MenuSelectedCard else CameraColors.MenuCard)
        .border(1.dp, if (accent && enabled) CameraColors.Accent else CameraColors.MenuBorder, CameraStyle.ActionShape)
        .clickable(enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = ink, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun LookSectionSelector(selected: LookSection, cardOrders: SpektraCardOrders, order: List<String>,
    onOrderChanged: (List<String>) -> Unit, onSelect: (LookSection) -> Unit) {
    val density = LocalDensity.current
    val tabScroll = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val changeOrder by rememberUpdatedState(onOrderChanged)
    val textMeasurer = rememberTextMeasurer()
    val language = com.unspektrawesome.language.LocalAppLanguage.current
    val textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .6.sp)
    val tabHeight = maxOf(36.dp,
        with(density) { textMeasurer.measure(AppLanguage.text("EXPOSURE"), textStyle, softWrap = false).size.height.toDp() } + 12.dp)
    val minimumWidths = remember(textStyle, density, language) {
        LookSection.entries.associate { section ->
            section.name to maxOf(48.dp, with(density) {
                textMeasurer.measure(AppLanguage.text(section.label), textStyle, softWrap = false).size.width.toDp()
            } + 20.dp)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val spareWidth = (maxWidth - order.fold(0.dp) { total, name -> total + minimumWidths.getValue(name) }).coerceAtLeast(0.dp) / order.size
        val tabWidths = order.associateWith { minimumWidths.getValue(it) + spareWidth }
        val widths by rememberUpdatedState(tabWidths.mapValues { with(density) { it.value.roundToPx() } })
        val reorder = remember(cardOrders, tabScroll) {
            LookCardReorderState(order, 0, tabScroll,
                saveOrder = { changeOrder(it) },
                onMove = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                onFinished = {}, itemWidth = { widths.getValue(it) })
        }
        AnimateReorderingCards(reorder)
        LaunchedEffect(selected, tabWidths) {
            if (reorder.draggedKey == null) {
                val index = reorder.order.indexOf(selected.name)
                val item = tabScroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (item == null || item.offset < 0 || item.offset + item.size > tabScroll.layoutInfo.viewportEndOffset)
                    tabScroll.animateScrollToItem(index)
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
        LazyRow(state = tabScroll, userScrollEnabled = reorder.draggedKey == null,
            modifier = Modifier.fillMaxWidth().height(tabHeight).testTag("Look tabs")
                .reorderLookCards(reorder, 0f, holdMillis = LOOK_TAB_HOLD_MILLIS) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) {
            itemsIndexed(reorder.order, key = { _, key -> key }) { _, key ->
                val section = LookSection.valueOf(key)
                val active = section == selected
                val moving = reorder.draggedKey == key
                val lifted = moving || reorder.settlingKey == key
                val textColor by animateColorAsState(if (active) CameraColors.Accent else CameraColors.Muted,
                    tween(180), label = "Look tab color")
                Box(
                    Modifier.width(tabWidths.getValue(key)).height(tabHeight)
                        .animateItem(placementSpec = if (lifted) null else tween(180))
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationX = reorder.translation(key) }
                        .background(if (moving) CameraColors.MenuSelectedCard else Color.Transparent, RoundedCornerShape(8.dp))
                        .border(1.dp, if (moving) CameraColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .selectable(active, role = Role.Tab) { onSelect(section) }
                        .semantics { customActions = reorder.accessibilityActions(key) },
                    contentAlignment = Alignment.Center,
                ) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(section.label, color = textColor,
                                style = textStyle, maxLines = 1, softWrap = false,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    if (active) Box(Modifier.align(Alignment.BottomCenter).width(44.dp).height(2.dp).background(CameraColors.Accent))
                }
            }
        }
        if (reorder.draggedKey != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Move {draggedKey}", arguments = mapOf("draggedKey" to (reorder.draggedKey)), color = CameraColors.Accent, fontSize = 11.sp,
                modifier = Modifier.padding(end = 8.dp))
            CardReorderIndicator()
        }
        }
        }
    }
}

private data class CardCopy(val brand: String, val title: String, val speed: String, val medium: String, val footer: String)
private data class CardLayout(val width: Dp, val height: Dp, val titleStyles: List<TextStyle>, val rotatedTitleStyles: List<TextStyle>)
private data class CardContent(val copy: CardCopy, val kind: String, val processing: Boolean = false)

private val sharedCardContents by lazy {
    listOf("film" to filmCardLabels, "paper" to SpektraFilmParams.PAPER_LABELS,
        "format" to SpektraLook.formats, "diffusion" to SpektraLook.diffusionFamilies,
        "exposure" to exposureCards).flatMap { (kind, labels) ->
        labels.mapIndexed { index, label -> CardContent(cardCopy(label, index, kind), kind) }
    } + listOf("Grain", "Diffusion", "Auto exposure").map {
        CardContent(CardCopy(it.uppercase(Locale.ROOT), "DISABLED", "", "", ""), "disabled")
    } + processingCardCopy.values.map { (heading, title) ->
        CardContent(CardCopy(heading, title, "", "", ""), "processing", processing = true)
    }
}

private fun cardCopy(label: String, index: Int, kind: String): CardCopy {
    if (kind == "film" && label == "Kodak Tri-X 7266")
        return CardCopy("KODAK", "Tri-X 7266", "", "B&W REVERSAL", "")
    if (kind == "exposure") return CardCopy("AUTO EXPOSURE", label.uppercase(Locale.ROOT).replace("CENTER ", "CENTER\n"),
        "", "", "")
    if (kind == "custom") return CardCopy("CUSTOM", label, "", "", "")
    if (kind == "diffusion") return CardCopy("DIFFUSION", label.uppercase(Locale.ROOT).replace("BLACK ", "BLACK\n"),
        "", "", "")
    if (kind == "format") {
        val name = label.substringBeforeLast(' ')
        val number = label.substringAfterLast(' ')
        return CardCopy("FILM FORMAT", name.uppercase(Locale.ROOT), number, "", "")
    }
    val brand = label.substringBefore(' ').uppercase(Locale.ROOT)
    val name = label.substringAfter(' ')
    if (kind == "paper") {
        if (index >= 6) return CardCopy(brand, name, "", "MOTION PICTURE", "PRINT STOCK")
        if (index == 5) return CardCopy(brand, "CRYSTAL\nARCHIVE", "TYPE II", "", "PAPER")
        return CardCopy(brand, name.substringBeforeLast(' ').uppercase(Locale.ROOT), name.substringAfterLast(' ').uppercase(Locale.ROOT), "", "PAPER")
    }
    val parts = Regex("^(.*) (\\d+[A-Z]?)(.*)$").matchEntire(name)
    val title = parts?.groupValues?.get(1) ?: name
    val speed = parts?.groupValues?.let { it[2] + it[3] }.orEmpty()
    val iso = Regex("\\d+").find(parts?.groupValues?.get(2) ?: name)?.value.orEmpty()
    return CardCopy(brand, listOf(title, speed).filter { it.isNotBlank() }.joinToString(" "), "", if (index >= 16) "COLOR REVERSAL" else "Color negative", "ISO $iso")
}

private fun stockStripe(index: Int, kind: String, label: String): List<Color> = when {
    kind == "film" && label == "Kodak Tri-X 7266" -> listOf(CameraColors.Muted, CameraColors.Text)
    kind == "custom" -> listOf(CameraColors.Accent, CameraColors.Accent)
    kind == "format" || kind == "diffusion" || kind == "exposure" -> listOf(CameraColors.Muted.copy(alpha = .35f), CameraColors.Muted)
    kind == "paper" -> listOf(Color(0xFF646365), Color(0xFFE2D9C5))
    index in 1..5 -> listOf(Color(0xFF922EB0), Color(0xFFE29891), Color(0xFFFFD444))
    index == 0 -> listOf(Color(0xFFB92642), Color(0xFFF35C31), Color(0xFFFFC83D))
    index in 13..15 || index >= 18 -> listOf(Color(0xFF207B67), Color(0xFF7BAB55), Color(0xFFD4D27A))
    else -> listOf(Color(0xFFAE7A23), Color(0xFFDEAE40), Color(0xFFF5D563))
}

@Composable
internal fun ChoiceCards(
    labels: List<String>,
    selected: Int,
    kind: String,
    rotationDegrees: Float,
    cardOrders: SpektraCardOrders,
    cardIds: List<String>? = null,
    controls: List<ProcessingLookControl>? = null,
    allCardIds: List<String>? = null,
    onReorderBounds: (Rect) -> Unit = {},
    onReorderFinished: () -> Unit = {},
    onDisable: (() -> Unit)? = null,
    amounts: List<Float>? = null,
    amountLabel: String = "Strength",
    maximumAmount: Float = 2f,
    defaultAmount: Float = 1f,
    dragDistanceMultiplier: Float = 1f,
    onAmountChange: (Int, Float) -> Unit = { _, _ -> },
    emptyContent: @Composable () -> Unit = {},
    onSelect: (Int) -> Unit = {},
) {
    val choiceOffset = if (onDisable != null) 1 else 0
    val cardLabels = remember(labels, choiceOffset) { if (choiceOffset == 1) listOf("Disabled") + labels else labels }
    val keys = remember(labels, cardIds, choiceOffset) {
        (if (choiceOffset == 1) listOf("disabled") else emptyList()) + (cardIds ?: labels.indices.map { it.toString() })
    }
    val initialOrder = remember(kind, keys, cardOrders) {
        keys.take(choiceOffset) + cardOrders.orderedKeys(kind, keys.drop(choiceOffset))
    }
    val selectedCardIndex = selected + choiceOffset
    val selectedKey = keys.getOrNull(selectedCardIndex)
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = (initialOrder.indexOf(selectedKey) - 1).coerceAtLeast(0))
    val haptics = LocalHapticFeedback.current
    val finishReordering by rememberUpdatedState(onReorderFinished)
    val reorder = remember(kind, keys, allCardIds, scroll, cardOrders) {
        LookCardReorderState(initialOrder, choiceOffset, scroll,
            saveOrder = { if (allCardIds == null) cardOrders.save(kind, it)
                else cardOrders.saveVisible(kind, it, allCardIds) },
            onMove = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            onFinished = { finishReordering() })
    }
    AnimateReorderingCards(reorder)
    val activeIndex = reorder.order.indexOf(selectedKey).coerceAtLeast(0)
    val centeredIndex by remember(scroll) {
        derivedStateOf {
            val layout = scroll.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: 0
        }
    }
    var scrollRequest by remember(kind) { mutableStateOf<Pair<String, Int>?>(null) }
    var scrollRequestId by remember(kind) { mutableIntStateOf(0) }
    val selectedVisible by remember(scroll, selectedKey) {
        derivedStateOf {
            val layout = scroll.layoutInfo
            layout.visibleItemsInfo.any { it.key == selectedKey && it.offset >= layout.viewportStartOffset &&
                it.offset + it.size <= layout.viewportEndOffset }
        }
    }
    val navigationIndex = scrollRequest?.first?.let { reorder.order.indexOf(it).takeIf { index -> index >= 0 } }
        ?: if (controls == null && selectedVisible) activeIndex else centeredIndex
    var gestureHintPending by remember(kind) { mutableStateOf(true) }
    fun centerCard(displayIndex: Int) {
        val key = reorder.order.getOrNull(displayIndex) ?: return
        scrollRequest = key to ++scrollRequestId
    }
    fun selectCard(displayIndex: Int) {
        val key = reorder.order.getOrNull(displayIndex) ?: return
        val index = keys.indexOf(key)
        gestureHintPending = false
        centerCard(displayIndex)
        if (controls == null) {
            if (choiceOffset == 1 && index == 0) onDisable?.invoke() else onSelect(index - choiceOffset)
        }
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val textMeasurer = rememberTextMeasurer()
    val language = com.unspektrawesome.language.LocalAppLanguage.current
    val textStyle = LocalTextStyle.current
    val landscape = abs(rotationDegrees) == 90f
    val copies = remember(cardLabels, kind, choiceOffset) {
        cardLabels.mapIndexed { index, label ->
            if (controls != null) controls[index].let { CardCopy(it.heading, it.title, "", "", "OFF") }
            else if (choiceOffset == 1 && index == 0) CardCopy(amountLabel.uppercase(Locale.ROOT), "DISABLED", "", "", "")
            else cardCopy(label, index - choiceOffset, kind)
        }
    }
    fun cardTitle(copy: CardCopy): String = listOf(
        if (kind == "film" || kind == "custom") copy.title else AppLanguage.text(copy.title),
        copy.speed,
    ).filter { it.isNotEmpty() }.joinToString(" ")
    fun cardSubtitle(copy: CardCopy): String = listOf(copy.medium, if (controls == null) copy.footer else "")
        .filter { it.isNotEmpty() }.joinToString(" · ") { AppLanguage.text(it) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val cardLayout = remember(copies, maxWidth, density, textStyle, landscape, language) {
            var width = (96.dp * fontScale).coerceAtMost(maxWidth - 32.dp)
            val titleStyle = textStyle.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            val brandStyle = textStyle.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = .6.sp)
            val detailStyle = textStyle.copy(fontSize = 10.sp, lineHeight = 12.sp)
            fun requiredHeight(content: CardContent, textWidth: Dp): Dp {
                val copy = content.copy
                val processing = content.processing
                val constraints = Constraints(maxWidth = with(density) { textWidth.roundToPx().coerceAtLeast(1) })
                val titleText = listOf(if (content.kind in setOf("film", "custom")) copy.title else AppLanguage.text(copy.title), copy.speed)
                    .filter { it.isNotEmpty() }.joinToString(" ")
                val title = textMeasurer.measure(titleText, titleStyle, constraints = constraints,
                    maxLines = if (content.kind == "custom") 2 else Int.MAX_VALUE).size.height
                val brand = if (copy.brand.isEmpty()) 0 else textMeasurer.measure(AppLanguage.text(copy.brand), brandStyle, constraints = constraints).size.height
                val detailText = listOf(copy.medium, copy.footer).filter { it.isNotEmpty() }.joinToString(" · ") { AppLanguage.text(it) }
                val detail = if (!processing) {
                    if (detailText.isEmpty()) 0 else textMeasurer.measure(detailText, detailStyle, constraints = constraints).size.height
                } else listOf(AppLanguage.text("OFF"), AppLanguage.text("ON"), "100%").maxOf {
                        textMeasurer.measure(it, titleStyle, constraints = constraints).size.height
                    }
                return with(density) { (brand + title + detail).toDp() } + if (!processing) 25.dp else 18.dp
            }
            fun contentHeight(textWidth: Dp): Dp {
                val common = sharedCardContents.maxOf { requiredHeight(it, textWidth) }
                return if (kind == "custom") maxOf(common, copies.maxOfOrNull {
                    requiredHeight(CardContent(it, kind), textWidth)
                } ?: common) else common
            }
            val height = maxOf(80.dp, contentHeight(width - 16.dp))
            if (landscape) width = maxOf(width, contentHeight(height - 16.dp))
            CardLayout(width, height, List(copies.size) { titleStyle }, List(copies.size) { titleStyle })
        }
        val cardWidth by animateDpAsState(cardLayout.width, tween(300, easing = FastOutSlowInEasing), label = "Card width")
        val cardHeight by animateDpAsState(cardLayout.height, tween(300, easing = FastOutSlowInEasing), label = "Card height")
        val inset = 16.dp
        val precedingCards = if (cardLayout.width * 2 + 8.dp + inset * 2 <= maxWidth) 1 else 0
        LaunchedEffect(kind, selectedKey, cardLayout.width, maxWidth, landscape) {
            if (controls == null && selectedKey != null && reorder.draggedKey == null) {
                centerCard(activeIndex)
            }
        }
        LaunchedEffect(scrollRequest) {
            val request = scrollRequest ?: return@LaunchedEffect
            try {
                val index = reorder.order.indexOf(request.first)
                if (index >= 0 && reorder.draggedKey == null) scroll.animateScrollToItem((index - precedingCards).coerceAtLeast(0))
            } finally {
                if (scrollRequest == request) scrollRequest = null
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            Box(Modifier.fillMaxWidth().height(cardHeight + 2.dp), contentAlignment = Alignment.CenterStart) {
                if (cardLabels.isEmpty()) emptyContent() else {
                LazyRow(
                    state = scroll,
                    contentPadding = PaddingValues(start = inset, end = inset),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    userScrollEnabled = reorder.draggedKey == null,
                    modifier = Modifier.fillMaxSize().testTag("Look cards $kind")
                        .reorderLookCards(reorder, rotationDegrees) {
                            gestureHintPending = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                ) {
                    itemsIndexed(reorder.order, key = { _, key -> key }) { displayIndex, key ->
                        val index = keys.indexOf(key)
                        val label = cardLabels[index]
                        val control = controls?.get(index)
                        val active = control?.active ?: (index == selectedCardIndex)
                        val moving = reorder.draggedKey == key
                        val lifted = moving || reorder.settlingKey == key
                        val amount = control?.amount ?: amounts?.getOrNull(index - choiceOffset)
                        val copy = copies[index]
                        val shape = RoundedCornerShape(14.dp)
                        AdjustableLookCard(
                            amount = amount,
                            amountLabel = control?.amountLabel ?: amountLabel,
                            maximumAmount = if (control != null) 1f else maximumAmount,
                            defaultAmount = control?.defaultAmount ?: defaultAmount,
                            dragDistanceMultiplier = if (control != null) 2f else dragDistanceMultiplier,
                            showGestureHint = gestureHintPending && active && amount != null && !moving &&
                                (control == null || displayIndex == navigationIndex),
                            onGestureHintFinished = { gestureHintPending = false },
                            active = active,
                            enabled = control?.available != false,
                            toggleable = control?.toggleable == true,
                            reordering = moving,
                            accessibilityActions = reorder.accessibilityActions(key),
                            rotationDegrees = rotationDegrees,
                            onSelect = {
                                if (control == null) selectCard(displayIndex) else {
                                    gestureHintPending = false
                                    control.onToggle()
                                }
                            },
                            onAmountChange = {
                                gestureHintPending = false
                                if (control != null) control.onAmountChange((it * 100).roundToInt() / 100f)
                                else onAmountChange(index - choiceOffset, it)
                            },
                            modifier = Modifier.width(cardWidth).height(cardHeight)
                                .animateItem(placementSpec = if (lifted) null else tween(180))
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer { translationX = reorder.translation(key) }
                                .onGloballyPositioned {
                                    if (displayIndex == maxOf(choiceOffset, navigationIndex)) onReorderBounds(it.boundsInRoot())
                                }
                                .clip(shape)
                                .then(if (lifted) Modifier.background(CameraColors.Background) else Modifier)
                                .background(if (active) CameraColors.MenuSelectedCard else CameraColors.MenuCard)
                                .border(1.dp, if (active || lifted) CameraColors.Accent else CameraColors.MenuBorder, shape)
                                .languageSemantics(mergeDescendants = true) { language ->
                                    contentDescription = if (kind == "custom") label else language.text(label)
                                    if (amount == null && control == null) stateDescription = language.text(if (active) "Selected" else "Tap to apply")
                                },
                        ) {
                            OrientedLookPanel(landscape, rotationDegrees, Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().padding(8.dp)) {
                                    if (copy.brand.isNotEmpty()) Text(copy.brand, modifier = Modifier.fillMaxWidth(),
                                        color = if (active) CameraColors.Accent else CameraColors.Muted,
                                        fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = .6.sp)
                                    Text(cardTitle(copy), translate = false,
                                        color = if (control != null && !active) CameraColors.Muted else CameraColors.Text,
                                        style = cardLayout.titleStyles[index], modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                        maxLines = if (kind == "custom") 2 else Int.MAX_VALUE,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Spacer(Modifier.weight(1f))
                                    if (control == null) {
                                        if (cardSubtitle(copy).isNotEmpty()) Text(cardSubtitle(copy), translate = false, modifier = Modifier.fillMaxWidth(),
                                            color = CameraColors.Muted, fontSize = 10.sp, lineHeight = 12.sp)
                                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape)
                                            .background(Brush.horizontalGradient(stockStripe(index - choiceOffset, kind, label))))
                                    } else Text(if (amount != null && active) "{percentage}%" else if (active) "ON" else "OFF",
                                        arguments = mapOf("percentage" to ((amount ?: 0f) * 100).roundToInt()), modifier = Modifier.fillMaxWidth(),
                                        color = if (active) CameraColors.Accent else CameraColors.Muted,
                                        fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                if (reorder.draggedKey == null) {
                    CarouselArrow(false, navigationIndex > 0, Modifier.align(Alignment.CenterStart)) { selectCard(navigationIndex - 1) }
                    CarouselArrow(true, navigationIndex < cardLabels.lastIndex, Modifier.align(Alignment.CenterEnd)) { selectCard(navigationIndex + 1) }
                }
                }
            }
            val count = minOf(5, cardLabels.size)
            val start = (navigationIndex - count / 2).coerceIn(0, cardLabels.size - count)
            val customActionsBelow = kind == "custom" && cardLabels.isNotEmpty()
            Row(Modifier.fillMaxWidth().padding(top = if (customActionsBelow) 2.dp else 6.dp, bottom = if (customActionsBelow) 0.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                if (count == 0) Spacer(Modifier.size(6.dp))
                repeat(count) { dot ->
                    Box(Modifier.size(6.dp).background(if (start + dot == navigationIndex) CameraColors.Accent else Color.White.copy(alpha = .25f), CircleShape))
                }
            }
        }
        }
    }
}

@Composable
private fun CarouselArrow(next: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .languageSemantics { language -> contentDescription = language.text(if (next) "Next choice" else "Previous choice") }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(32.dp).background(CameraColors.Background.copy(alpha = .85f), CircleShape).border(1.dp, CameraColors.MenuBorder, CircleShape)) {
            val color = Color.White.copy(alpha = if (enabled) .95f else .25f)
            val direction = if (next) 1f else -1f
            val center = size.width * .5f
            val tip = Offset(center + direction * 3.dp.toPx(), center)
            drawLine(color, Offset(center - direction * 2.dp.toPx(), center - 6.dp.toPx()), tip, 1.7.dp.toPx(), StrokeCap.Round)
            drawLine(color, tip, Offset(center - direction * 2.dp.toPx(), center + 6.dp.toPx()), 1.7.dp.toPx(), StrokeCap.Round)
        }
    }
}
