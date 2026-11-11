package com.unspektrawesome.ui.camera

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.unspektrawesome.language.LanguageText as Text

internal enum class EditorSliderSensitivity(val label: String, val scale: Float) {
    LOW("Low", .15f), MEDIUM("Mid", .4f), HIGH("High", 1f);
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal val LocalEditorSliderSensitivity = staticCompositionLocalOf { EditorSliderSensitivity.MEDIUM }
internal val LocalCycleEditorSliderSensitivity = staticCompositionLocalOf<() -> Unit> { {} }

internal fun formatEditorValue(value: Float, integer: Boolean, displayedValue: String? = null): String {
    if (integer) return value.toInt().toString()
    val displayed = displayedValue?.removePrefix("+")?.takeIf { it.toFloatOrNull()?.isFinite() == true }
    val formatted = displayed ?: if (value != 0f && kotlin.math.abs(value) < .001f)
        java.math.BigDecimal(value.toString()).round(java.math.MathContext(3)).toPlainString()
        else String.format(java.util.Locale.ROOT, "%.3f", value)
    val trimmed = if ('.' in formatted) formatted.trimEnd('0').trimEnd('.') else formatted
    return if ('.' in trimmed || displayed != null && '.' !in displayed) trimmed else "$trimmed.0"
}

internal fun parseEditorValue(text: String, range: ClosedFloatingPointRange<Float>, integer: Boolean): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf {
        it.isFinite() && it in range && (!integer || it.toDouble() == kotlin.math.floor(it.toDouble()))
    }

@Composable
internal fun EditorValueDialog(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    integer: Boolean, onDismiss: () -> Unit, onApply: (Float) -> Unit,
    displayedValue: String? = null) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val previous = activity?.window?.attributes?.softInputMode
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose { if (previous != null) activity.window.setSoftInputMode(previous) }
    }
    fun number(amount: Float) = formatEditorValue(amount, integer)
    val initial = remember { formatEditorValue(value, integer, displayedValue) }
    var input by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val parsed = parseEditorValue(input.text, range, integer)
    val focus = remember { FocusRequester() }
    fun apply() { parsed?.let { onApply(it); onDismiss() } }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = generateSequence(view as View?) { it.parent as? View }
                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
            onDispose {}
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp),
            contentAlignment = Alignment.BottomCenter) {
            RotatingCameraPanel(LocalCameraWindowRotation.current, maxWidth = 360.dp) {
                CameraDialogSurface(modifier = Modifier.testTag("Editor value dialog"), header = {
                    Text(label, color = CameraColors.Text, fontSize = 14.sp)
                }, footer = {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = CameraColors.Muted) }
                        OutlinedButton(onClick = ::apply, enabled = parsed != null,
                            shape = CameraStyle.ActionShape,
                            border = androidx.compose.foundation.BorderStroke(1.dp,
                                if (parsed != null) CameraColors.Accent else CameraColors.MenuBorder)) {
                            Text("Apply", color = if (parsed != null) CameraColors.Accent else CameraColors.Muted)
                        }
                    }
                }) {
                    Text("Range: {minimum} to {maximum}",
                        arguments = mapOf("minimum" to number(range.start), "maximum" to number(range.endInclusive)),
                        color = if (parsed == null) CameraColors.FocusFailed else CameraColors.Muted,
                        fontSize = 11.sp, lineHeight = 16.sp)
                    OutlinedTextField(input, { input = it.copy(text = it.text.take(64)) }, singleLine = true,
                        isError = parsed == null, modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("Editor value input"),
                        shape = CameraStyle.ControlShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { apply() }),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = CameraColors.Text,
                            unfocusedTextColor = CameraColors.Text, focusedBorderColor = CameraColors.Accent,
                            unfocusedBorderColor = CameraColors.MenuBorder, cursorColor = CameraColors.Accent))
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
