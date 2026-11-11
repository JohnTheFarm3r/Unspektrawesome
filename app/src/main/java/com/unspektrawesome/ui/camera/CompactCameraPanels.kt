package com.unspektrawesome.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.unspektrawesome.language.languageSemantics

internal object CameraStyle {
    val PanelShape = RoundedCornerShape(18.dp)
    val ControlShape = RoundedCornerShape(18.dp)
    val ActionShape = RoundedCornerShape(50)
    val PopupShape = RoundedCornerShape(14.dp)
    val PanelPadding = 12.dp
    val QuickMenuWidth = 272.dp
    val QuickMenuLabelWidth = 56.dp
    val QuickMenuRowPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
}

internal val LocalCameraWindowRotation = staticCompositionLocalOf { 0f }

@Composable
internal fun CameraQuickMenuHeader(title: String, @androidx.annotation.DrawableRes icon: Int, onClose: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).border(1.dp, CameraColors.Muted.copy(alpha = .6f), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center) { CameraIcon(icon, Modifier.size(12.dp), CameraColors.Muted) }
            com.unspektrawesome.language.LanguageText(title, color = CameraColors.Text, fontSize = 12.sp,
                lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(CameraStyle.ControlShape).border(1.dp, CameraColors.MenuBorder, CameraStyle.ControlShape)
                .clickable(onClick = onClose).languageSemantics { language ->
                    contentDescription = language.text("Close")
                }, contentAlignment = Alignment.Center) {
                CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_x, Modifier.size(16.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = CameraColors.MenuBorder)
    }
}

@Composable
internal fun CameraDialogWindow(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    rotation: Float = LocalCameraWindowRotation.current,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismiss, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        CameraWindowBackdrop()
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            if (properties.dismissOnClickOutside) Box(Modifier.matchParentSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss))
            RotatingCameraPanel(rotation, maxWidth = 360.dp) {
                CameraDialogSurface(modifier, header, footer, content)
            }
        }
    }
}

@Composable
internal fun CameraDialogSurface(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(max = 480.dp)
        .clip(CameraStyle.PanelShape).background(CameraColors.DialogBackground)
        .border(1.dp, CameraColors.MenuBorder, CameraStyle.PanelShape)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }.padding(CameraStyle.PanelPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold)) { header() }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        footer()
    }
}

@Composable
internal fun CameraAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = CameraStyle.PanelShape,
    containerColor: Color = CameraColors.Background,
    titleContentColor: Color = CameraColors.Text,
    textContentColor: Color = CameraColors.Muted,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        CameraWindowBackdrop()
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            if (properties.dismissOnClickOutside) Box(Modifier.matchParentSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismissRequest))
            RotatingCameraPanel(LocalCameraWindowRotation.current, maxWidth = 360.dp) {
                CameraDialogSurface(modifier.clip(shape).background(containerColor), header = {
                    CompositionLocalProvider(LocalContentColor provides titleContentColor) { title?.invoke() }
                }, footer = {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dismissButton?.let {
                            Box(Modifier.border(1.dp, CameraColors.MenuBorder, CameraStyle.ActionShape)) { it() }
                        }
                        Box(Modifier.border(1.dp, CameraColors.Accent, CameraStyle.ActionShape)) { confirmButton() }
                    }
                }) {
                    CompositionLocalProvider(LocalContentColor provides textContentColor,
                        LocalCompactSettings provides true) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp)) {
                            text?.invoke()
                        }
                    }
                }
            }
        }
    }
}

internal class CenteredCameraMenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maximumY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        val y = if (anchorBounds.bottom + popupContentSize.height + margin <= windowSize.height)
            anchorBounds.bottom else anchorBounds.top - popupContentSize.height
        return IntOffset((windowSize.width - popupContentSize.width) / 2, y.coerceIn(margin, maximumY))
    }
}

@Composable
internal fun CompactCameraMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    properties: PopupProperties = CameraPopupProperties, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.roundToPx() }
    val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceIn(96.dp, 420.dp)
    Popup(popupPositionProvider = CenteredCameraMenuPosition(margin), onDismissRequest = onDismiss, properties = properties) {
        CameraWindowBackdrop()
        RotatingCameraPanel(LocalCameraWindowRotation.current, maxWidth = 320.dp) {
        Column(modifier.widthIn(min = 184.dp, max = 320.dp).heightIn(max = maximumHeight)
            .width(IntrinsicSize.Max)
            .clip(CameraStyle.PopupShape).background(CameraColors.DialogBackground)
            .border(BorderStroke(1.dp, CameraColors.MenuBorder), CameraStyle.PopupShape)
            .verticalScroll(rememberScrollState()).padding(4.dp), content = content)
        }
    }
}
