package com.unspektrawesome.ui.camera

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.controls.CapturedInput
import com.unspektrawesome.controls.ControlAction
import com.unspektrawesome.controls.ControlBinding
import com.unspektrawesome.controls.ControlBindings
import com.unspektrawesome.controls.ControlGesture
import com.unspektrawesome.controls.ControlGroup
import com.unspektrawesome.controls.ControlInputHub
import com.unspektrawesome.controls.ControlInputKind
import com.unspektrawesome.controls.ControlTrigger
import com.unspektrawesome.controls.DirectUsbControls
import com.unspektrawesome.controls.DirectUsbStatus
import com.unspektrawesome.controls.HidUsage
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics

/** Readable name of a button, dial or stick direction. */
internal fun controlInputName(trigger: ControlTrigger): String = when (trigger.kind) {
    ControlInputKind.KEY -> if (trigger.code < 0) AppLanguage.format("Button {value}", mapOf("value" to -trigger.code))
        else controlKeyName(trigger.code)
    ControlInputKind.SCROLL -> AppLanguage.text(if (trigger.code == MotionEvent.AXIS_HSCROLL || trigger.code == HidUsage.PAN) "Side wheel" else "Dial")
    ControlInputKind.HID -> when (trigger.code) {
        HidUsage.CAMERA_FOCUS -> AppLanguage.text("Half press")
        HidUsage.CAMERA_SHUTTER -> AppLanguage.text("Camera button")
        HidUsage.RECORD -> AppLanguage.text("Record button")
        HidUsage.ZOOM_IN -> AppLanguage.text("Zoom in")
        HidUsage.ZOOM_OUT -> AppLanguage.text("Zoom out")
        HidUsage.VOLUME_UP -> AppLanguage.text("Volume up")
        HidUsage.VOLUME_DOWN -> AppLanguage.text("Volume down")
        HidUsage.PLAY_PAUSE -> AppLanguage.text("Play or pause")
        HidUsage.NEXT_TRACK -> AppLanguage.text("Next track")
        HidUsage.PREVIOUS_TRACK -> AppLanguage.text("Previous track")
        HidUsage.KEYBOARD_ENTER -> AppLanguage.text("Enter")
        HidUsage.KEYBOARD_SPACE -> AppLanguage.text("Space")
        // Other controls are numbered by their usage, which stays the same every time the accessory connects.
        else -> AppLanguage.format("Button {value}", mapOf("value" to
            (trigger.code and 0xFFFF).toString(16).uppercase().padStart(2, '0')))
    }
    ControlInputKind.AXIS -> AppLanguage.text(when (trigger.code) {
        MotionEvent.AXIS_X -> if (trigger.sign < 0) "Left stick left" else "Left stick right"
        MotionEvent.AXIS_Y -> if (trigger.sign < 0) "Left stick up" else "Left stick down"
        MotionEvent.AXIS_Z -> if (trigger.sign < 0) "Right stick left" else "Right stick right"
        MotionEvent.AXIS_RZ -> if (trigger.sign < 0) "Right stick up" else "Right stick down"
        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE -> "Left trigger"
        else -> "Right trigger"
    })
}

private fun controlKeyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_VOLUME_UP -> AppLanguage.text("Volume up")
    KeyEvent.KEYCODE_VOLUME_DOWN -> AppLanguage.text("Volume down")
    KeyEvent.KEYCODE_CAMERA -> AppLanguage.text("Camera button")
    KeyEvent.KEYCODE_FOCUS -> AppLanguage.text("Half press")
    KeyEvent.KEYCODE_ZOOM_IN -> AppLanguage.text("Zoom in")
    KeyEvent.KEYCODE_ZOOM_OUT -> AppLanguage.text("Zoom out")
    KeyEvent.KEYCODE_MEDIA_RECORD -> AppLanguage.text("Record button")
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> AppLanguage.text("Enter")
    KeyEvent.KEYCODE_SPACE -> AppLanguage.text("Space")
    KeyEvent.KEYCODE_DPAD_UP -> AppLanguage.text("Up")
    KeyEvent.KEYCODE_DPAD_DOWN -> AppLanguage.text("Down")
    KeyEvent.KEYCODE_DPAD_LEFT -> AppLanguage.text("Left")
    KeyEvent.KEYCODE_DPAD_RIGHT -> AppLanguage.text("Right")
    KeyEvent.KEYCODE_DPAD_CENTER -> AppLanguage.text("Center")
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> AppLanguage.text("Play or pause")
    KeyEvent.KEYCODE_MEDIA_NEXT -> AppLanguage.text("Next track")
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> AppLanguage.text("Previous track")
    KeyEvent.KEYCODE_HEADSETHOOK -> AppLanguage.text("Headset button")
    KeyEvent.KEYCODE_PAGE_UP -> AppLanguage.text("Page up")
    KeyEvent.KEYCODE_PAGE_DOWN -> AppLanguage.text("Page down")
    KeyEvent.KEYCODE_BUTTON_THUMBL -> AppLanguage.text("Left stick press")
    KeyEvent.KEYCODE_BUTTON_THUMBR -> AppLanguage.text("Right stick press")
    // Letters, digits and controller buttons keep their printed names.
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_").split('_')
        .joinToString(" ") { part -> if (part.length <= 2) part else part.lowercase().replaceFirstChar(Char::uppercase) }
}

/** Where a binding applies: the phone, one accessory, or any device. */
internal fun controlDeviceName(trigger: ControlTrigger, deviceName: String?): String = when (trigger.device) {
    ControlTrigger.PHONE -> AppLanguage.text("Phone")
    ControlTrigger.ACCESSORY -> AppLanguage.text("Any accessory")
    ControlTrigger.ANY -> AppLanguage.text("Any device")
    else -> deviceName ?: AppLanguage.text("Accessory")
}

private fun ControlBinding.matches(input: CapturedInput): Boolean =
    trigger.kind == input.trigger.kind && trigger.code == input.trigger.code && trigger.sign == input.trigger.sign &&
        when (trigger.device) {
            ControlTrigger.ANY -> true
            ControlTrigger.ACCESSORY -> input.external
            else -> trigger.device == input.trigger.device
        }

/** Names of the connected accessories that can send buttons, dials or sticks. */
@Composable
private fun rememberControlDevices(): List<String> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(InputManager::class.java) }
    var names by remember { mutableStateOf(emptyList<String>()) }
    DisposableEffect(manager) {
        fun refresh() {
            names = manager.inputDeviceIds.toList().mapNotNull(manager::getInputDevice).filter { device ->
                device.isExternal && !device.isVirtual && (device.supportsSource(InputDevice.SOURCE_KEYBOARD) ||
                    device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                    device.supportsSource(InputDevice.SOURCE_ROTARY_ENCODER) || device.supportsSource(InputDevice.SOURCE_MOUSE))
            }.map { it.name }.distinct().sorted()
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        refresh()
        manager.registerInputDeviceListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    return names
}

@Composable
internal fun ButtonSettings(isActive: Boolean, onAssign: (ControlAction) -> Unit) {
    DisposableEffect(isActive) {
        ControlInputHub.monitoring = isActive
        onDispose { ControlInputHub.monitoring = false }
    }
    val bindings by ControlBindings.state.collectAsState()
    val lastInput by ControlInputHub.lastInput.collectAsState()
    // Inputs from before this page opened are not shown as live feedback.
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val live = lastInput?.takeIf { it.time >= openedAt }
    var resetConfirmation by remember { mutableStateOf(false) }
    val devices = rememberControlDevices()
    SettingsPage {
        SettingsCard("Try your buttons", symbol = SettingsSymbol.Gamepad) {
            LiveInputStrip(live, bindings)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DeviceChip(AppLanguage.text("Phone"))
                devices.forEach { DeviceChip(it) }
            }
            DirectUsbSettings()
        }
        ControlGroup.entries.forEach { group ->
            SettingsSection(group.label)
            SettingsCard {
                val actions = ControlAction.entries.filter { it.group == group }
                actions.forEachIndexed { index, action ->
                    if (index > 0) SettingsDivider()
                    ControlActionRow(action, bindings.filter { it.action == action }, live, onAssign = { onAssign(action) })
                }
            }
        }
        if (resetConfirmation) {
            SettingsCard {
                SettingsLabel("Reset all buttons?")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton("Cancel", Modifier.weight(1f)) { resetConfirmation = false }
                    SettingsButton("Reset", Modifier.weight(1f), symbol = SettingsSymbol.Reset, accent = true) {
                        ControlBindings.reset()
                        resetConfirmation = false
                    }
                }
            }
        } else SettingsButton("Reset to defaults", Modifier.fillMaxWidth(), symbol = SettingsSymbol.Reset) { resetConfirmation = true }
    }
}

@Composable
private fun DeviceChip(name: String) {
    Row(Modifier.clip(CircleShape).border(1.dp, CameraColors.MenuBorder, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(CameraColors.Accent, CircleShape))
        Text(name, color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp, translate = false, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveInputStrip(input: CapturedInput?, bindings: List<ControlBinding>) {
    val shape = RoundedCornerShape(14.dp)
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(input?.time) {
        if (input != null) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(900, easing = LinearOutSlowInEasing))
        }
    }
    val bound = input?.let { value -> bindings.filter { it.matches(value) } }.orEmpty()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).pageCardBackground(shape)
        .border(1.dp, lerpColor(CameraColors.MenuBorder, CameraColors.Accent, pulse.value), shape)
        .semantics { liveRegion = LiveRegionMode.Polite }
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).background(if (input != null) CameraColors.Accent else CameraColors.Muted, CircleShape))
        if (input == null) {
            Text("Press any button, turn a dial or move a stick", color = CameraColors.Muted, fontSize = 13.sp,
                lineHeight = 18.sp, modifier = Modifier.weight(1f))
        } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("{input} · {device}", arguments = mapOf("input" to controlInputName(input.trigger),
                "device" to (input.deviceName ?: AppLanguage.text("Phone"))),
                color = CameraColors.Text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(if (bound.isEmpty()) AppLanguage.text("Not assigned")
                else bound.joinToString("  ·  ") { binding -> bindingSummary(binding) },
                translate = false, color = if (bound.isEmpty()) CameraColors.Muted else CameraColors.Accent,
                fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

private fun lerpColor(from: Color, to: Color, amount: Float) = androidx.compose.ui.graphics.lerp(from, to, amount.coerceIn(0f, 1f))

/** Action and direction or gesture, for example "ISO: Decrease" or "Shutter: Hold". */
private fun bindingSummary(binding: ControlBinding): String {
    val action = AppLanguage.text(binding.action.label)
    val detail = when {
        binding.action.stepper && binding.trigger.kind == ControlInputKind.SCROLL -> null
        binding.action.stepper -> AppLanguage.text(if (binding.direction > 0) binding.action.increaseLabel else binding.action.decreaseLabel)
        binding.gesture == ControlGesture.HOLD -> AppLanguage.text("Hold")
        else -> null
    }
    return if (detail == null) action else AppLanguage.format("{action}: {detail}", mapOf("action" to action, "detail" to detail))
}

@Composable
private fun ControlActionRow(action: ControlAction, bindings: List<ControlBinding>, live: CapturedInput?, onAssign: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onAssign)
        .languageSemantics { language -> contentDescription = language.format("Assign a button to {action}",
            mapOf("action" to language.text(action.label))) }
        .padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                SettingsLabel(action.label)
                if (bindings.isEmpty()) Text("Not assigned", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Box(Modifier.size(32.dp).border(1.dp, CameraColors.MenuBorder, CircleShape), contentAlignment = Alignment.Center) {
                SettingsIcon(SettingsSymbol.Add, Modifier.size(16.dp), CameraColors.Accent)
            }
        }
        if (bindings.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bindings.sortedWith(compareBy({ -it.direction }, { it.gesture.ordinal })).forEach { binding ->
                BindingChip(binding, live?.let(binding::matches) == true && live.time > 0) { ControlBindings.remove(binding) }
            }
        }
    }
}

@Composable
private fun BindingChip(binding: ControlBinding, active: Boolean, onRemove: () -> Unit) {
    val shape = CircleShape
    val highlight by animateColorAsState(if (active) CameraColors.Accent else CameraColors.MenuBorder, tween(240),
        label = "Binding highlight")
    val prefix = when {
        binding.action.stepper && binding.trigger.kind == ControlInputKind.SCROLL -> if (binding.direction < 0) "⇅ " else ""
        binding.action.stepper -> if (binding.direction > 0) "+ " else "− "
        binding.gesture == ControlGesture.HOLD -> AppLanguage.text("Hold") + " · "
        else -> ""
    }
    val name = prefix + controlInputName(binding.trigger)
    val device = controlDeviceName(binding.trigger, binding.deviceName)
    Row(Modifier.heightIn(min = 36.dp).clip(shape).pageCardBackground(shape).border(1.dp, highlight, shape)
        .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (active) CameraColors.Accent else CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, translate = false, maxLines = 1)
        Text(device, color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 16.sp, translate = false, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 140.dp))
        Box(Modifier.size(36.dp).clip(shape).clickable(role = Role.Button, onClick = onRemove)
            .languageSemantics { language -> contentDescription = language.format("Remove {input}", mapOf("input" to name)) },
            contentAlignment = Alignment.Center) {
            SettingsIcon(SettingsSymbol.Close, Modifier.size(12.dp), CameraColors.Muted)
        }
    }
}

private sealed interface AssignStep {
    /** Waiting for an input. [second] is the decrease direction of a stepper. */
    data class Listening(val second: Boolean) : AssignStep
    data object Review : AssignStep
}

/** Full-screen panel that waits for a button and assigns it to [action]. */
@Composable
internal fun ButtonAssignOverlay(action: ControlAction, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var step by remember { mutableStateOf<AssignStep>(AssignStep.Listening(false)) }
    var first by remember { mutableStateOf<CapturedInput?>(null) }
    var second by remember { mutableStateOf<CapturedInput?>(null) }
    var gesture by remember { mutableStateOf(ControlGesture.TAP) }
    var reversed by remember { mutableStateOf(false) }
    val listening = step as? AssignStep.Listening
    DisposableEffect(listening) {
        // One control can send several signals at once, such as a dial that also reports a button. Keep the first.
        var taken = false
        if (listening != null) ControlInputHub.capture = capture@{ input ->
            if (taken) return@capture
            if (!listening.second) {
                taken = true
                first = input
                // A dial covers both directions; a stepper on a button also needs one for going down.
                step = if (action.stepper && input.trigger.kind != ControlInputKind.SCROLL) AssignStep.Listening(true) else AssignStep.Review
            } else {
                if (first?.trigger == input.trigger) return@capture
                taken = true
                second = input
                step = AssignStep.Review
            }
        }
        onDispose { ControlInputHub.capture = null }
    }
    val proposed = remember(first, second, gesture, reversed) {
        listOfNotNull(
            first?.let { ControlBinding(it.trigger, action, if (action.stepper) ControlGesture.TAP else gesture,
                if (it.trigger.kind == ControlInputKind.SCROLL && reversed) -1 else 1, it.deviceName) },
            second?.let { ControlBinding(it.trigger, action, direction = -1, deviceName = it.deviceName) },
        )
    }
    val existing by ControlBindings.state.collectAsState()
    val replaced = existing.filter { old -> old.action != action && proposed.any { it.conflictsWith(old) } }
        .map { it.action }.distinct()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .78f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        .safeDrawingPadding().padding(20.dp).testTag("Button assignment"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().clip(SettingsStyle.PanelShape)
            .background(CameraColors.DialogBackground).border(1.dp, CameraColors.MenuBorder, SettingsStyle.PanelShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(action.label, color = CameraColors.Text, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            AnimatedContent(step, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }) { current ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (current) {
                        is AssignStep.Listening -> {
                            ListeningRing()
                            Text(when {
                                !action.stepper -> "Press a button"
                                current.second -> AppLanguage.format("Now press the button for {direction}",
                                    mapOf("direction" to AppLanguage.text(action.decreaseLabel)))
                                else -> AppLanguage.format("Turn a dial, or press the button for {direction}",
                                    mapOf("direction" to AppLanguage.text(action.increaseLabel)))
                            }, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                                translate = !action.stepper, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            first?.takeIf { current.second }?.let {
                                CapturedInputChip(AppLanguage.text(action.increaseLabel), it)
                            }
                        }
                        AssignStep.Review -> {
                            first?.let { input ->
                                CapturedInputChip(if (action.stepper && input.trigger.kind != ControlInputKind.SCROLL)
                                    AppLanguage.text(action.increaseLabel) else null, input)
                            }
                            second?.let { CapturedInputChip(AppLanguage.text(action.decreaseLabel), it) }
                            if (!action.stepper && first?.trigger?.kind != ControlInputKind.SCROLL) {
                                val gestures = ControlGesture.entries
                                SettingsChoices(gestures.map { it.label }, gestures.indexOf(gesture),
                                    Modifier.testTag("Button gesture choices")) { gesture = gestures[it] }
                            }
                            if (action.stepper && first?.trigger?.kind == ControlInputKind.SCROLL) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { SettingsLabel("Reverse direction") }
                                    CameraOverlayToggle(reversed, "Reverse direction") { reversed = it }
                                }
                            }
                            if (replaced.isNotEmpty()) Text("Replaces {actions}", arguments = mapOf("actions" to
                                replaced.joinToString(", ") { AppLanguage.text(it.label) }),
                                color = CameraColors.Warning, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    step == AssignStep.Review -> {
                        SettingsButton("Try again", Modifier.weight(1f)) {
                            first = null; second = null; gesture = ControlGesture.TAP; reversed = false
                            step = AssignStep.Listening(false)
                        }
                        SettingsButton("Save", Modifier.weight(1f).testTag("Save button assignment"),
                            symbol = SettingsSymbol.Check, accent = true) {
                            proposed.forEach(ControlBindings::assign)
                            onClose()
                        }
                    }
                    listening?.second == true -> {
                        SettingsButton("Cancel", Modifier.weight(1f), onClick = onClose)
                        SettingsButton("Skip", Modifier.weight(1f)) { step = AssignStep.Review }
                    }
                    else -> SettingsButton("Cancel", Modifier.weight(1f), onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun CapturedInputChip(role: String?, input: CapturedInput) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).pageCardBackground(shape).border(1.dp, CameraColors.Accent, shape)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsIcon(SettingsSymbol.Gamepad, Modifier.size(18.dp), CameraColors.Accent)
        Column(Modifier.weight(1f)) {
            Text(controlInputName(input.trigger), color = CameraColors.Accent, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold, translate = false)
            Text(input.deviceName ?: AppLanguage.text("Phone"), color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 17.sp,
                translate = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        role?.let { Text(it, color = CameraColors.Text, fontSize = 12.sp, lineHeight = 17.sp, translate = false) }
    }
}

@Composable
private fun ListeningRing() {
    val transition = rememberInfiniteTransition(label = "Listening ring")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "Listening ring progress")
    Box(Modifier.size(112.dp).drawBehind {
        for (offset in listOf(0f, .5f)) {
            val wave = (progress + offset) % 1f
            drawCircle(CameraColors.Accent.copy(alpha = (1f - wave) * .55f), radius = size.minDimension / 2 * (.5f + wave * .5f),
                style = Stroke(2.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(56.dp).background(CameraColors.MenuCard, CircleShape).border(1.dp, CameraColors.Accent, CircleShape),
            contentAlignment = Alignment.Center) {
            SettingsIcon(SettingsSymbol.Gamepad, Modifier.size(24.dp), CameraColors.Accent)
        }
    }
}

/** Lets the app read a USB accessory directly, for controls Android leaves out, such as which way a dial turns. */
@Composable
private fun DirectUsbSettings() {
    val devices by DirectUsbControls.devices.collectAsState()
    val enabled by DirectUsbControls.enabled.collectAsState()
    val status by DirectUsbControls.status.collectAsState()
    devices.forEach { device ->
        SettingsDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Direct connection", color = CameraColors.Text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Text(device.name, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, translate = false,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val on = device.key in enabled
                val state = status[device.key]
                Text(when {
                    !on -> "Reads dials in both directions. Buttons then work only in this app while it is open."
                    state == DirectUsbStatus.CONNECTED -> "Connected. Assign its buttons again here."
                    state == DirectUsbStatus.WAITING_FOR_PERMISSION -> "Allow access when Android asks."
                    state == DirectUsbStatus.FAILED -> "Could not connect. Reconnect the accessory and try again."
                    else -> "Connecting"
                }, color = if (on && state == DirectUsbStatus.FAILED) CameraColors.FocusFailed
                    else if (on && state == DirectUsbStatus.CONNECTED) CameraColors.Accent else CameraColors.Muted,
                    fontSize = 12.sp, lineHeight = 17.sp)
            }
            CameraOverlayToggle(device.key in enabled, "Direct connection") { DirectUsbControls.setEnabled(device.key, it) }
        }
    }
}
