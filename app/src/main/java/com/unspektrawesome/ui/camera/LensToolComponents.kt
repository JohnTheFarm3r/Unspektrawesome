package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.LanguageText as Text

private val LensToolShape = RoundedCornerShape(12.dp)

/** Hairline that separates the header, body and footer of a lens tool window. */
@Composable
private fun LensToolDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Diagram.StageBorder))
}

@Composable
internal fun LensToolDialog(
    title: String,
    symbol: SettingsSymbol,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnClickOutside,
            decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalDensity provides density, com.unspektrawesome.language.LocalOwnTextWidth provides true) {
            CameraWindowBackdrop()
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(10.dp), contentAlignment = Alignment.Center) {
              if (dismissOnClickOutside) Box(Modifier.matchParentSize()
                  .clickable(interactionSource = null, indication = null, onClick = onDismiss))
              RotatingCameraPanel(LocalCameraWindowRotation.current, maxWidth = 592.dp) {
                BoxWithConstraints(modifier.fillMaxWidth().fillMaxHeight(.94f).clip(LensToolShape)
                    .background(CameraColors.Background).background(Color(0xF00C0C0C))
                    .border(1.dp, Diagram.Border, LensToolShape)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
                    val maximumFooterHeight = maxHeight * .45f
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LensToolBadge(symbol)
                            Text(title, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                        }
                        LensToolDivider()
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), content = content)
                        LensToolDivider()
                        Column(Modifier.fillMaxWidth().heightIn(max = maximumFooterHeight).verticalScroll(rememberScrollState())
                            .testTag("Lens tool footer").padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
                    }
                }
              }
            }
        }
    }
}

/** Small outlined square holding a lime icon, used beside window and card titles. */
@Composable
internal fun LensToolBadge(symbol: SettingsSymbol) {
    Box(Modifier.size(24.dp).border(1.dp, Diagram.Border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        SettingsIcon(symbol, Modifier.size(14.dp), CameraColors.Accent)
    }
}

/** Card of a lens tool window: glass fill, hairline border and 8 dp corners. */
@Composable
internal fun LensToolCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Diagram.ControlShape).background(Diagram.Glass)
        .border(1.dp, Diagram.Border, Diagram.ControlShape).padding(10.dp), content = content)
}

@Composable
internal fun LensToolAction(
    label: String,
    modifier: Modifier = Modifier,
    symbol: SettingsSymbol? = null,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink = if (!enabled) CameraColors.Muted else if (accent) CameraColors.Accent else CameraColors.Text
    Row(modifier.heightIn(min = 48.dp).clip(Diagram.ControlShape)
        .background(if (accent && enabled) CameraColors.Accent.copy(alpha = .06f) else Diagram.Glass)
        .border(1.dp, if (accent && enabled) CameraColors.Accent else Diagram.Border, Diagram.ControlShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp).alpha(if (enabled) 1f else .4f),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        symbol?.let { SettingsIcon(it, Modifier.size(15.dp), ink) }
        Text(label, color = ink, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun LensToolActionPair(
    firstLabel: String,
    secondLabel: String,
    firstClick: () -> Unit,
    secondClick: () -> Unit,
    modifier: Modifier = Modifier,
    firstSymbol: SettingsSymbol? = null,
    secondSymbol: SettingsSymbol? = null,
    secondAccent: Boolean = false,
    firstEnabled: Boolean = true,
    secondEnabled: Boolean = true,
) {
    val language = LocalAppLanguage.current.renderer
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labels = listOf(firstLabel to firstSymbol, secondLabel to secondSymbol)
        val widest = with(density) { labels.maxOf { (label, symbol) ->
            measurer.measure(language.text(label), style).size.width + if (symbol != null) 23.dp.roundToPx() else 0
        }.toDp() } + 28.dp
        val paired = widest <= (maxWidth - 8.dp) / 2
        val buttonWidth = if (paired) (maxWidth - 8.dp) / 2 else maxWidth
        val buttonHeight = with(density) { labels.maxOf { (label, symbol) ->
            measurer.measure(language.text(label), style, constraints = Constraints(maxWidth =
                (buttonWidth - 28.dp - if (symbol != null) 23.dp else 0.dp).roundToPx().coerceAtLeast(1))).size.height
        }.toDp() }.plus(24.dp).coerceAtLeast(48.dp)
        val actions: @Composable () -> Unit = {
            LensToolAction(firstLabel, Modifier.width(buttonWidth).height(buttonHeight), firstSymbol,
                enabled = firstEnabled, onClick = firstClick)
            LensToolAction(secondLabel, Modifier.width(buttonWidth).height(buttonHeight), secondSymbol,
                accent = secondAccent, enabled = secondEnabled, onClick = secondClick)
        }
        if (paired) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** Dashed notice with an info mark, for explanations and messages inside lens tool windows. */
@Composable
internal fun LensToolNotice(text: String) {
    Row(Modifier.fillMaxWidth().clip(Diagram.ControlShape).background(Diagram.Glass)
        .drawBehind {
            drawRoundRect(Color(0xFF3A3A3A), cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsIcon(SettingsSymbol.Info, Modifier.padding(top = 1.dp).size(13.dp), Diagram.Hint)
        Text(text, color = Diagram.Ink, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
}

/** Check mark box. [filled] draws the lime selected box used for selectable profiles. */
@Composable
internal fun LensToolCheck(checked: Boolean, filled: Boolean = false) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(18.dp).background(if (checked && filled) CameraColors.Accent else Color.Transparent, shape)
        .border(1.5.dp, if (checked) CameraColors.Accent else Color(0xFF6A6A6A), shape), contentAlignment = Alignment.Center) {
        if (checked) SettingsIcon(SettingsSymbol.Check, Modifier.size(12.dp), if (filled) Color.Black else CameraColors.Accent)
    }
}
