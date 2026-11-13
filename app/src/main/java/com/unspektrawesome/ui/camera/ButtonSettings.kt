package com.unspektrawesome.ui.camera

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.settings.CameraPreferences

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

/** The part of the grip drawing, or of the phone's side keys, that sends [trigger]. */
private fun gripPartFor(trigger: ControlTrigger, external: Boolean): GripPart? {
    if (!external) return if (trigger.kind != ControlInputKind.KEY) null else when (trigger.code) {
        KeyEvent.KEYCODE_VOLUME_UP -> GripPart.VolumeUp
        KeyEvent.KEYCODE_VOLUME_DOWN -> GripPart.VolumeDown
        KeyEvent.KEYCODE_CAMERA -> GripPart.CameraKey
        else -> null
    }
    return when (trigger.kind) {
        ControlInputKind.KEY -> when (trigger.code) {
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS -> GripPart.Shutter
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT -> GripPart.Zoom
            else -> null
        }
        ControlInputKind.HID -> when (trigger.code) {
            HidUsage.CAMERA_FOCUS, HidUsage.CAMERA_SHUTTER -> GripPart.Shutter
            HidUsage.ZOOM_IN, HidUsage.ZOOM_OUT -> GripPart.Zoom
            else -> null
        }
        ControlInputKind.SCROLL -> if (trigger.code == MotionEvent.AXIS_HSCROLL || trigger.code == HidUsage.PAN) null else GripPart.Dial
        ControlInputKind.AXIS -> null
    }
}

private fun CapturedInput.gripPart() = gripPartFor(trigger, external)
private fun ControlBinding.gripPart() = gripPartFor(trigger, trigger.device != ControlTrigger.PHONE)

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
    val usbDevices by DirectUsbControls.devices.collectAsState()
    val usbEnabled by DirectUsbControls.enabled.collectAsState()
    val usbStatus by DirectUsbControls.status.collectAsState()
    val usbConnected = usbDevices.any { it.key in usbEnabled && usbStatus[it.key] == DirectUsbStatus.CONNECTED }
    DiagramPage {
        TryButtonsStage(live, bindings, devices, usbDevices.isNotEmpty(), usbDevices.any { it.key in usbEnabled }, usbConnected)
        NumberedSection(1, "Try your buttons") { DiagramValue("live", translate = true, color = Diagram.Hint) }
        LiveInputStrip(live, bindings)
        NumberedSection(2, "Devices") {
            DiagramValue("{count} connected", arguments = mapOf("count" to devices.size + 1), color = Diagram.Hint)
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagramChip(AppLanguage.text("Phone"), leadingDot = true)
            devices.forEach { DiagramChip(it, leadingDot = true) }
        }
        DirectUsbSettings()
        usbDevices.firstOrNull { it.key in usbEnabled }?.let { device ->
            DirectConnectionStage(device.name, usbStatus[device.key] == DirectUsbStatus.CONNECTED)
        }
        // Each stage explains the groups listed below it, numbered like their rows.
        listOf(listOf(ControlGroup.CAPTURE, ControlGroup.LENS_MODE), listOf(ControlGroup.EXPOSURE, ControlGroup.IMAGE),
            listOf(ControlGroup.SHORTCUTS)).forEach { groups ->
            val actions = ControlAction.entries.filter { it.group in groups }
            when (groups.first()) {
                ControlGroup.CAPTURE -> InputFlowStage(actions, bindings, live)
                ControlGroup.EXPOSURE -> StepperStage(actions, bindings, live)
                else -> ShortcutStage(actions, bindings)
            }
            groups.forEach { group ->
                val groupActions = actions.filter { it.group == group }
                DiagramSection(group.label) {
                    DiagramValue("{assigned} of {total} assigned", arguments = mapOf(
                        "assigned" to groupActions.count { action -> bindings.any { it.action == action } },
                        "total" to groupActions.size), color = Diagram.Hint)
                }
                groupActions.forEach { action ->
                    ControlActionRow(action, actions.indexOf(action) + 1, bindings.filter { it.action == action }, live,
                        onAssign = { onAssign(action) })
                }
            }
        }
        if (resetConfirmation) {
            ResetPreviewStage(bindings)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(Diagram.ControlShape).background(Diagram.Glass)
                .border(1.dp, Diagram.Border, Diagram.ControlShape).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("Reset all buttons?", color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                DiagramButtonRow {
                    DiagramButton("Cancel") { resetConfirmation = false }
                    DiagramButton("Reset", symbol = SettingsSymbol.Reset, accent = true) {
                        ControlBindings.reset()
                        resetConfirmation = false
                    }
                }
            }
        } else {
            DiagramButtonRow(Modifier.padding(top = 8.dp)) {
                DiagramButton("Reset to defaults", Modifier.fillMaxWidth(), symbol = SettingsSymbol.Reset) { resetConfirmation = true }
            }
            Text("Asks to confirm before anything changes.", color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Section label with the number its stage uses, and a muted value on the right. */
@Composable
private fun NumberedSection(number: Int, label: String, trailing: @Composable () -> Unit) {
    val renderer = LocalAppLanguage.current.renderer
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiagramNumber(number)
        Text(renderer.text(label).uppercase(), translate = false, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 14.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** Small uppercase caption over a value, for stage labels whose text is partly user data. */
@Composable
private fun StageLabel(caption: String?, value: String?, modifier: Modifier = Modifier, active: Boolean = true,
    captionTranslate: Boolean = true, valueTranslate: Boolean = true, valueColor: Color? = null, maxWidth: Dp = 150.dp,
    detail: String? = null, captionLines: Int = 2) {
    val renderer = LocalAppLanguage.current.renderer
    Column(modifier.widthIn(max = maxWidth)) {
        caption?.let {
            Text((if (captionTranslate) renderer.text(it) else it).uppercase(), translate = false, color = Diagram.Hint,
                fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = .6.sp, maxLines = captionLines, overflow = TextOverflow.Ellipsis)
        }
        value?.let {
            Text(it, translate = valueTranslate, color = valueColor ?: if (active) CameraColors.Accent else Color(0xFF8C8C8C),
                fontSize = 12.sp, lineHeight = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        detail?.let { Text(it, color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** The grip and the phone's own keys, lit where the last input came from. */
@Composable
private fun TryButtonsStage(live: CapturedInput?, bindings: List<ControlBinding>, devices: List<String>,
    directAvailable: Boolean, directOn: Boolean, directConnected: Boolean) {
    val gx = 37.dp; val gy = 81.dp; val scale = .78f
    val part = live?.gripPart()
    val lit = buildSet { part?.let(::add); if (directConnected) add(GripPart.Usb) }
    val bound = live?.let { value -> bindings.filter { it.matches(value) } }.orEmpty()
    DiagramStage(282.dp, description = "Try your buttons") {
        DiagramDrawing {
            photographyGrip(gx, gy, scale, lit)
            val (sx, sy) = gripPoint(part ?: GripPart.Shutter, gx, gy, scale)
            if (live != null) callout(sx, sy, sx, 62.dp, active = true)
            // Bracket over the phone's side keys, leading up to their list.
            val (ux, uy) = gripPoint(GripPart.VolumeUp, gx, gy, scale)
            val (cx, _) = gripPoint(GripPart.CameraKey, gx, gy, scale)
            val faint = Color.White.copy(alpha = .35f)
            line(ux, uy - 1.dp, ux, uy - 5.dp, faint, 1.dp)
            line(ux, uy - 5.dp, cx, uy - 5.dp, faint, 1.dp)
            line(cx, uy - 5.dp, cx, uy - 1.dp, faint, 1.dp)
            line((ux + cx) / 2, uy - 5.dp, (ux + cx) / 2, 56.dp, faint, 1.dp)
            // From the bottom of the grip to the accessory label.
            line(80.dp, 214.dp, 80.dp, 236.dp, faint, 1.dp)
            line(80.dp, 236.dp, 86.dp, 236.dp, faint, 1.dp)
            val (px, py) = gripPoint(GripPart.Usb, gx, gy, scale)
            callout(px, py, 206.dp, py, active = directConnected)
            line(206.dp, py, 206.dp, 236.dp, if (directConnected) CameraColors.Accent.copy(alpha = .7f) else faint, 1.dp)
            line(206.dp, 236.dp, 211.dp, 236.dp, if (directConnected) CameraColors.Accent.copy(alpha = .7f) else faint, 1.dp)
        }
        StageLabel(if (live != null) "After your last press" else "Before a press", null, Modifier.at(12.dp, 10.dp), maxWidth = 164.dp)
        if (live != null) {
            StageLabel(AppLanguage.format("{input} · {device}", mapOf("input" to controlInputName(live.trigger),
                "device" to (live.deviceName ?: AppLanguage.text("Phone")))),
                if (bound.isEmpty()) AppLanguage.text("Not assigned") else bound.joinToString(", ") { bindingSummary(it) },
                Modifier.at(12.dp, 26.dp), active = bound.isNotEmpty(), captionTranslate = false, valueTranslate = false,
                maxWidth = 160.dp)
            val (sx, sy) = gripPoint(part ?: GripPart.Shutter, gx, gy, scale)
            DiagramNumber(1, Modifier.at(sx - 9.dp, 63.dp))
        } else {
            DiagramText("Press any button, turn a dial or move a stick", Modifier.at(12.dp, 28.dp), size = 11f, maxWidth = 160.dp)
            DiagramNumber(1, Modifier.at(73.dp, 63.dp))
        }
        PhoneKeysLabel(bindings, live, Modifier.at(182.dp, 8.dp))
        DiagramNumber(2, Modifier.at(71.dp, 151.dp))
        StageLabel(if (devices.isEmpty()) "Accessories" else devices.joinToString(", "),
            AppLanguage.text(if (devices.isEmpty()) "Not connected" else "Connected"), Modifier.at(90.dp, 224.dp),
            captionTranslate = devices.isEmpty(), valueTranslate = false,
            valueColor = if (devices.isEmpty()) Color(0xFF8C8C8C) else CameraColors.Text, maxWidth = 110.dp)
        if (directAvailable) DiagramNumber(3, Modifier.at(156.dp, 156.dp))
        StageLabel("USB-C", if (directAvailable) AppLanguage.text("Direct connection") else null, Modifier.at(214.dp, 224.dp),
            captionTranslate = false, valueTranslate = false, valueColor = CameraColors.Text, maxWidth = 116.dp,
            detail = if (!directAvailable) null else if (directConnected) "Connected" else if (directOn) "On" else "Off")
    }
}

/** What each of the phone's own keys does, from the phone's real bindings. */
@Composable
private fun PhoneKeysLabel(bindings: List<ControlBinding>, live: CapturedInput?, modifier: Modifier) {
    // Each key's action sits under its name, so both get the full width; the list ends above the phone's side keys.
    Column(modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        StageLabel("Phone keys", null, maxWidth = 150.dp)
        listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA).forEach { code ->
            val actions = ControlBindings.resolve(bindings, ControlInputKind.KEY, code, 0, listOf(ControlTrigger.PHONE, ControlTrigger.ANY))
            val hot = live != null && !live.external && live.trigger.kind == ControlInputKind.KEY && live.trigger.code == code
            Column {
                Text(controlKeyName(code), translate = false, color = if (hot) CameraColors.Accent else Diagram.Hint, fontSize = 10.sp,
                    lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (actions.isEmpty()) AppLanguage.text("Not assigned") else actions.joinToString(", ") { bindingSummary(it) },
                    translate = false, color = if (hot) CameraColors.Accent else if (actions.isEmpty()) Color(0xFF8C8C8C) else CameraColors.Text,
                    fontSize = 10.5.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LiveInputStrip(input: CapturedInput?, bindings: List<ControlBinding>) {
    val shape = RoundedCornerShape(10.dp)
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(input?.time) {
        if (input != null) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(900, easing = LinearOutSlowInEasing))
        }
    }
    val bound = input?.let { value -> bindings.filter { it.matches(value) } }.orEmpty()
    val border = if (input == null) Diagram.Border else lerpColor(CameraColors.Accent.copy(alpha = .55f), CameraColors.Accent, pulse.value)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 54.dp).clip(shape).background(Diagram.Glass)
        .border(1.dp, border, shape)
        .semantics { liveRegion = LiveRegionMode.Polite }
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(9.dp).background(if (input != null) CameraColors.Accent else Diagram.Hint, CircleShape))
        if (input == null) {
            Text("Press any button, turn a dial or move a stick", color = Diagram.Hint, fontSize = 12.sp,
                lineHeight = 17.sp, modifier = Modifier.weight(1f))
        } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("{input} · {device}", arguments = mapOf("input" to controlInputName(input.trigger),
                "device" to (input.deviceName ?: AppLanguage.text("Phone"))),
                color = CameraColors.Text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
            Text(if (bound.isEmpty()) AppLanguage.text("Not assigned")
                else bound.joinToString("  ·  ") { binding -> bindingSummary(binding) },
                translate = false, color = if (bound.isEmpty()) Color(0xFF8C8C8C) else CameraColors.Accent,
                fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (input?.external == true) GripIcon(Modifier.size(18.dp))
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

/** Input name with its direction or gesture, as shown on chips: "+ Zoom in", "⇅ Dial" or "Hold · Camera button". */
private fun bindingInputName(binding: ControlBinding): String {
    val prefix = when {
        binding.action.stepper && binding.trigger.kind == ControlInputKind.SCROLL -> if (binding.direction < 0) "⇅ " else ""
        binding.action.stepper -> if (binding.direction > 0) "+ " else "− "
        binding.gesture == ControlGesture.HOLD -> AppLanguage.text("Hold") + " · "
        else -> ""
    }
    return prefix + controlInputName(binding.trigger)
}

/** Order of devices in lists: the phone, single accessories, then every accessory and any device. */
private fun deviceOrder(trigger: ControlTrigger) = when (trigger.device) {
    ControlTrigger.PHONE -> 0
    ControlTrigger.ACCESSORY -> 2
    ControlTrigger.ANY -> 3
    else -> 1
}

private data class FlowInput(val device: String, val name: String, val actions: Set<Int>, val hot: Boolean)

/** Inputs on the left joined to the capture, lens and mode actions they run. */
@Composable
private fun InputFlowStage(actions: List<ControlAction>, bindings: List<ControlBinding>, live: CapturedInput?) {
    val maximumRows = 9
    val inputs = bindings.filter { it.action in actions }.sortedWith(compareBy({ deviceOrder(it.trigger) }, { it.trigger.device }))
        .groupBy { controlDeviceName(it.trigger, it.deviceName) to bindingInputName(it) }
        .map { (key, group) -> FlowInput(key.first, key.second, group.map { actions.indexOf(it.action) }.toSet(),
            live != null && group.any { it.matches(live) }) }
    val shown = inputs.take(maximumRows)
    val rows = buildList {
        var device: String? = null
        shown.forEach { input ->
            if (input.device != device) { device = input.device; add(input.device to null) }
            add(input.device to input)
        }
    }
    var y = 26f
    val positions = rows.map { (_, input) -> y.also { y += if (input == null) 14f else 22f } }
    val more = inputs.size - shown.size
    val actionTop = 30f
    val actionStep = 26f
    val height = maxOf(y + if (more > 0) 22f else 6f, actionTop + actionStep * actions.size + 4f)
    val hotActions = inputs.filter { it.hot }.flatMap { it.actions }.toSet()
    val assigned = actions.map { action -> bindings.any { it.action == action } }
    DiagramStage(height.dp, description = "Capture") {
        DiagramDrawing {
            rows.forEachIndexed { index, (_, input) ->
                input ?: return@forEachIndexed
                input.actions.forEach { target ->
                    curve(122f, positions[index] + 10f, 206f, actionTop + actionStep * target + 9f,
                        if (input.hot) CameraColors.Accent else Diagram.Ink, if (input.hot) 1.8f else 1.2f)
                }
            }
            actions.forEachIndexed { index, _ ->
                val ay = (actionTop + actionStep * index + 9f).dp
                if (assigned[index]) dot(206.dp, ay, if (index in hotActions) 3.dp else 2.5.dp,
                    if (index in hotActions) CameraColors.Accent else Diagram.Ink)
                else {
                    circle(174.dp, ay, 4.dp, Diagram.Line, 1.dp, dashed = true)
                    line(178.dp, ay, 206.dp, ay, Diagram.Line, 1.dp, dashed = true)
                }
            }
        }
        StageLabel("Input", null, Modifier.at(12.dp, 8.dp))
        StageLabel("Action", null, Modifier.at(212.dp, 8.dp), maxWidth = 118.dp)
        rows.forEachIndexed { index, (device, input) ->
            val top = positions[index].dp
            if (input == null) {
                Text(device.uppercase(), translate = false, color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp,
                    letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.at(12.dp, top).widthIn(max = 108.dp))
            } else Box(Modifier.at(12.dp, top).width(108.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.height(20.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0B0B0B))
                    .border(1.dp, if (input.hot) CameraColors.Accent else Diagram.Ink, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text(input.name, translate = false, color = if (input.hot) CameraColors.Accent else CameraColors.Text,
                        fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (more > 0) Text("+{count} more", arguments = mapOf("count" to more), color = Diagram.Hint, fontSize = 9.5.sp,
            lineHeight = 12.sp, modifier = Modifier.at(12.dp, (y + 4f).dp))
        actions.forEachIndexed { index, action ->
            Row(Modifier.at(212.dp, (actionTop + actionStep * index).dp).widthIn(max = 120.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagramNumber(index + 1, active = assigned[index])
                Text(action.label, color = when {
                    index in hotActions -> CameraColors.Accent
                    assigned[index] -> CameraColors.Text
                    else -> Diagram.Hint
                }, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Smooth connector between two artboard points, in dp. */
private fun DiagramPen.curve(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) = with(scope) {
    val mid = (x1 + x2) / 2f
    val path = Path().apply {
        moveTo(x1.dp.toPx(), y1.dp.toPx())
        cubicTo(mid.dp.toPx(), y1.dp.toPx(), mid.dp.toPx(), y2.dp.toPx(), x2.dp.toPx(), y2.dp.toPx())
    }
    drawPath(path, color, style = Stroke(width.dp.toPx()))
}

/** The grip's dial and keys for the exposure, focus and image steppers. */
@Composable
private fun StepperStage(actions: List<ControlAction>, bindings: List<ControlBinding>, live: CapturedInput?) {
    val gx = (-5).dp; val gy = 10.7.dp; val scale = .95f
    val first = actions.firstOrNull { action -> bindings.any { it.action == action } }
    val binding = first?.let { action -> bindings.filter { it.action == action }
        .sortedWith(compareBy({ -it.direction }, { it.gesture.ordinal })).first() }
    val part = binding?.gripPart()
    val lit = setOfNotNull(live?.gripPart())
    val unassigned = actions.withIndex().filter { (_, action) -> bindings.none { it.action == action } }
    DiagramStage(if (unassigned.size > 6) 204.dp else 182.dp, description = "Exposure") {
        DiagramDrawing {
            photographyGrip(gx, gy, scale, lit, clipPhoneAt = 202f)
            if (part != null) {
                val (px, py) = gripPoint(part, gx, gy, scale)
                callout(px, py + 2.dp, px, 16.dp)
                line(px, 16.dp, 120.dp, 16.dp, CameraColors.Accent.copy(alpha = .7f), 1.dp)
            }
        }
        if (first != null && binding != null) {
            Row(Modifier.at(124.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagramNumber(actions.indexOf(first) + 1)
                StageLabel(AppLanguage.format("{input} · {device}", mapOf("input" to bindingInputName(binding),
                    "device" to controlDeviceName(binding.trigger, binding.deviceName))), AppLanguage.text(first.label),
                    captionTranslate = false, valueTranslate = false, maxWidth = 180.dp,
                    detail = if (binding.trigger.kind == ControlInputKind.SCROLL) "Both directions" else null)
            }
        }
        Column(Modifier.at(204.dp, 66.dp).widthIn(max = 128.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            StageLabel("Steps", null)
            listOf("+" to "Button for Increase", "−" to "Button for Decrease", "" to "Dial turns both ways", "⇅" to "Dial reversed")
                .forEach { (sign, text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (sign.isNotEmpty()) Text(sign, translate = false, color = CameraColors.Accent, fontSize = 10.sp,
                            lineHeight = 14.sp, fontFamily = Diagram.Mono)
                        Text(text, color = Color(0xFFCFCFCF), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
        }
        if (unassigned.isNotEmpty()) {
            // Numbers wrap onto a second row so every unassigned action stays visible.
            Column(Modifier.at(204.dp, 140.dp).width(128.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    unassigned.forEach { (index, _) -> DiagramNumber(index + 1, active = false) }
                }
                DiagramText("Not assigned", maxWidth = 128.dp)
            }
        }
    }
}

/** What each shortcut does, with the button that runs it. */
@Composable
private fun ShortcutStage(actions: List<ControlAction>, bindings: List<ControlBinding>) {
    fun first(action: ControlAction) = bindings.filter { it.action == action }.sortedWith(compareBy({ -it.direction }, { it.gesture.ordinal }))
        .firstOrNull()
    fun status(action: ControlAction) = first(action)?.let { binding -> AppLanguage.format("{input} · {device}",
        mapOf("input" to bindingInputName(binding), "device" to controlDeviceName(binding.trigger, binding.deviceName))) }
    val gallery = first(ControlAction.GALLERY)
    val galleryKey = gallery?.takeIf { it.trigger.device == ControlTrigger.PHONE || it.trigger.device == ControlTrigger.ANY }
        ?.trigger?.code
    val number = { action: ControlAction -> actions.indexOf(action) + 1 }
    DiagramStage(340.dp, description = "Shortcuts") {
        DiagramDrawing {
            line(170.dp, 32.dp, 170.dp, 170.dp, Diagram.StageBorder, 1.dp)
            line(12.dp, 180.dp, 324.dp, 180.dp, Diagram.StageBorder, 1.dp)
            line(178.dp, 103.dp, 324.dp, 103.dp, Diagram.StageBorder, 1.dp)
            // Phone seen from the front, its side keys and the gallery it opens.
            box(22.dp, 40.dp, 50.dp, 96.dp, 10.dp)
            box(28.dp, 48.dp, 38.dp, 80.dp, 5.dp, Color(0xFF3A3A3A), 1.dp)
            listOf(KeyEvent.KEYCODE_VOLUME_UP to 52, KeyEvent.KEYCODE_VOLUME_DOWN to 70, KeyEvent.KEYCODE_CAMERA to 96).forEach { (code, top) ->
                val on = code == galleryKey
                box(72.dp, top.dp, 4.dp, if (code == KeyEvent.KEYCODE_CAMERA) 10.dp else 14.dp, 1.5.dp,
                    if (on) CameraColors.Accent else Diagram.Ink, 1.2.dp, fill = if (on) CameraColors.Accent else null)
            }
            val linked = gallery != null
            arrow(80.dp, 84.dp, 97.dp, 84.dp, if (linked) CameraColors.Accent else Diagram.Line, dashed = !linked)
            box(101.dp, 48.dp, 58.dp, 84.dp, 6.dp, if (linked) CameraColors.Accent else Diagram.Ink)
            for (row in 0 until 4) for (column in 0 until 3) {
                if (row == 3 && column == 2) continue
                box((106 + column * 17).dp, (56 + row * 17).dp, 14.dp, 14.dp, 2.dp,
                    when (row) { 0, 1 -> Diagram.Ink; 2 -> Diagram.Line; else -> Color(0xFF3A3A3A) }, 1.dp)
            }
            // spektrafilm AE steps through its choices and wraps around.
            arrow(76.dp, 224.dp, 106.dp, 224.dp, Diagram.Ink, 1.dp)
            arrow(220.dp, 224.dp, 248.dp, 224.dp, Diagram.Ink, 1.dp)
            polyline(listOf(280.dp to 237.dp, 280.dp to 259.dp, 42.dp to 259.dp), Diagram.Line, 1.dp, dashed = true)
            arrow(42.dp, 259.dp, 42.dp, 241.dp, Diagram.Line, 1.dp, dashed = true)
        }
        StageLabel("What each shortcut does", null, Modifier.at(12.dp, 10.dp), maxWidth = 312.dp)
        Row(Modifier.at(12.dp, 140.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagramNumber(number(ControlAction.GALLERY), active = gallery != null)
            StageLabel(status(ControlAction.GALLERY) ?: AppLanguage.text("Not assigned"), AppLanguage.text(ControlAction.GALLERY.label),
                captionTranslate = false, valueTranslate = false, active = gallery != null, maxWidth = 130.dp)
        }
        ShortcutBlock(number(ControlAction.AUTO_EXPOSURE), ControlAction.AUTO_EXPOSURE, status(ControlAction.AUTO_EXPOSURE),
            Modifier.at(180.dp, 30.dp)) {
            listOf("ISO", "Shutter").forEach { ShortcutValue(it) }
            ShortcutArrow()
            ShortcutValue("Auto", target = true)
        }
        ShortcutBlock(number(ControlAction.AUTO_FOCUS), ControlAction.AUTO_FOCUS, status(ControlAction.AUTO_FOCUS),
            Modifier.at(180.dp, 108.dp)) {
            ShortcutValue("MF")
            ShortcutArrow()
            ShortcutValue("AF", target = true)
        }
        val spektra = ControlAction.SPEKTRA_AE
        Row(Modifier.at(12.dp, 186.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DiagramNumber(number(spektra), active = first(spektra) != null)
            StageLabel(AppLanguage.format("{action} · {next} / {previous}", mapOf("action" to AppLanguage.text(spektra.label),
                "next" to AppLanguage.text(spektra.increaseLabel), "previous" to AppLanguage.text(spektra.decreaseLabel))),
                null, captionTranslate = false, maxWidth = 296.dp, captionLines = 1)
        }
        DiagramNode("Off", Modifier.at(24.dp, 212.dp), width = 50.dp)
        DiagramNode("Center weighted", Modifier.at(108.dp, 212.dp), width = 110.dp)
        DiagramNode("Median", Modifier.at(252.dp, 212.dp), width = 72.dp)
        listOf(74.dp, 218.dp).forEach { x ->
            Text("+ {next}", arguments = mapOf("next" to AppLanguage.text(spektra.increaseLabel)), color = Diagram.Hint,
                fontSize = 9.sp, lineHeight = 11.sp, fontFamily = Diagram.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.at(x, 200.dp).widthIn(max = 100.dp))
        }
        Column(Modifier.at(12.dp, 263.dp).width(312.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DiagramText("+ Next wraps to Off · − Previous steps back", Modifier.padding(start = 42.dp), maxWidth = 270.dp)
            DiagramText(status(spektra) ?: AppLanguage.text("Not assigned"),
                color = if (first(spektra) != null) CameraColors.Accent else Diagram.Hint, translate = false, maxWidth = 312.dp)
            DiagramText("Does nothing while Unspektra AE or panorama is active.", maxWidth = 312.dp)
        }
    }
}

@Composable
private fun ShortcutBlock(number: Int, action: ControlAction, status: String?, modifier: Modifier,
    values: @Composable RowScope.() -> Unit) {
    Column(modifier.widthIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DiagramNumber(number, active = status != null)
            StageLabel(action.label, null)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = values)
        Text("Back to auto", color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status ?: AppLanguage.text("Not assigned"), translate = false, color = if (status != null) CameraColors.Accent else Diagram.Hint,
            fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShortcutArrow() {
    Text("→", translate = false, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 13.sp)
}

@Composable
private fun ShortcutValue(label: String, target: Boolean = false) {
    Box(Modifier.height(18.dp).border(1.dp, if (target) CameraColors.Accent else Diagram.Line, RoundedCornerShape(4.dp))
        .padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (target) CameraColors.Accent else CameraColors.Text, fontSize = 9.5.sp, lineHeight = 11.sp,
            fontFamily = Diagram.Mono, maxLines = 1)
    }
}

/** While a direct connection is on: the dial reads both ways and buttons reach only this app. */
@Composable
private fun DirectConnectionStage(deviceName: String, connected: Boolean) {
    val gx = 6.dp; val gy = 24.2.dp; val scale = .7f
    // Read as one description so the drawing's app name does not repeat the About page's title.
    val description = LocalAppLanguage.current.renderer
        .text("Reads dials in both directions. Buttons then work only in this app while it is open.")
    DiagramStage(232.dp, Modifier.clearAndSetSemantics { contentDescription = description }) {
        DiagramDrawing {
            photographyGrip(gx, gy, scale, if (connected) setOf(GripPart.Usb) else emptySet())
            val (dx, dy) = gripPoint(GripPart.Dial, gx, gy, scale)
            callout(dx, dy + 6.dp, dx, 33.dp)
            line(dx, 33.dp, 230.dp, 33.dp, CameraColors.Accent.copy(alpha = .7f), 1.dp)
            val (ux, uy) = gripPoint(GripPart.Usb, gx, gy, scale)
            val flow = if (connected) CameraColors.Accent else Diagram.Line
            polyline(listOf(ux to uy, 140.dp to uy, 140.dp to 170.dp), flow, 1.5.dp, dashed = !connected)
            polyline(listOf(140.dp to 154.dp, 275.dp to 154.dp, 275.dp to 170.dp), Diagram.Line, 1.dp, dashed = true)
            line(271.dp, 158.dp, 279.dp, 166.dp, Diagram.Hint, 1.5.dp)
            line(279.dp, 158.dp, 271.dp, 166.dp, Diagram.Hint, 1.5.dp)
            box(104.dp, 170.dp, 112.dp, 44.dp, 6.dp, flow, 1.5.dp, dashed = !connected)
            box(226.dp, 170.dp, 100.dp, 44.dp, 6.dp, Diagram.Line, 1.dp, dashed = true)
        }
        Column(Modifier.at(12.dp, 6.dp)) {
            StageLabel("Direct connection", null, maxWidth = 210.dp, captionLines = 1)
            DiagramText(deviceName, translate = false, color = CameraColors.Text, size = 10f, maxWidth = 210.dp, maxLines = 1)
        }
        StageLabel(AppLanguage.text("Dial"), AppLanguage.text("Both directions"), Modifier.at(234.dp, 22.dp),
            captionTranslate = false, valueTranslate = false, maxWidth = 98.dp)
        Text("USB-C", translate = false, color = if (connected) CameraColors.Accent else Diagram.Hint, fontSize = 9.5.sp,
            lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp, modifier = Modifier.at(104.dp, 84.dp))
        Column(Modifier.at(114.dp, 176.dp).width(96.dp)) {
            Text("Unspektrawesome", translate = false, color = if (connected) CameraColors.Accent else CameraColors.Text,
                fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("While open", color = Color(0xFFCFCFCF), fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        DiagramText("Other apps", Modifier.at(236.dp, 180.dp), maxWidth = 84.dp)
        Column(Modifier.at(12.dp, 150.dp).width(86.dp)) {
            StageLabel("Buttons", null)
            Text("Work only in this app while it is open", color = Color(0xFFCFCFCF), fontSize = 10.sp, lineHeight = 13.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ControlActionRow(action: ControlAction, number: Int, bindings: List<ControlBinding>, live: CapturedInput?,
    onAssign: () -> Unit) {
    Column(Modifier.fillMaxWidth().diagramHairline().clickable(role = Role.Button, onClick = onAssign)
        .languageSemantics { language -> contentDescription = language.format("Assign a button to {action}",
            mapOf("action" to language.text(action.label))) }
        .padding(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiagramNumber(number, active = bindings.isNotEmpty())
            Text(action.label, color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            if (bindings.isEmpty()) Text("Not assigned", color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp)
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).background(Diagram.Glass, CircleShape).border(1.dp, Diagram.Border, CircleShape),
                    contentAlignment = Alignment.Center) {
                    SettingsIcon(SettingsSymbol.Add, Modifier.size(14.dp), CameraColors.Accent)
                }
            }
        }
        if (bindings.isNotEmpty()) FlowRow(Modifier.padding(start = 28.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bindings.sortedWith(compareBy({ -it.direction }, { it.gesture.ordinal })).forEach { binding ->
                val name = bindingInputName(binding)
                DiagramChip(name, detail = controlDeviceName(binding.trigger, binding.deviceName),
                    active = live?.let(binding::matches) == true && live.time > 0,
                    removeLabel = AppLanguage.format("Remove {input}", mapOf("input" to name))) { ControlBindings.remove(binding) }
            }
        }
    }
}

private data class ResetRow(val device: String, val inputs: String, val action: String)

/** What Reset would change, worked out from the current bindings and the defaults. */
@Composable
private fun ResetPreviewStage(bindings: List<ControlBinding>) {
    val defaults = remember { ControlBindings.defaults(CameraPreferences()) }
    fun ControlBinding.key() = copy(deviceName = null)
    val defaultKeys = defaults.map { it.key() }.toSet()
    val currentKeys = bindings.map { it.key() }.toSet()
    fun rows(list: List<ControlBinding>) = list.sortedWith(compareBy({ deviceOrder(it.trigger) }, { it.trigger.device }))
        .groupBy { controlDeviceName(it.trigger, it.deviceName) to it.action }
        .map { (key, group) -> ResetRow(key.first, group.joinToString(", ") { bindingInputName(it) }, AppLanguage.text(key.second.label)) }
    val groups = listOf(
        Triple("Stays", bindings.filter { it.key() in defaultKeys }, 0),
        Triple("Removed", bindings.filter { it.key() !in defaultKeys }, 1),
        Triple("Restored defaults", defaults.filter { it.key() !in currentKeys }, 2),
    ).filter { it.second.isNotEmpty() }.map { (label, list, kind) -> Triple(label, rows(list), kind) to list.size }
    var height = 30f
    groups.forEach { (group, _) ->
        height += 20f + group.second.size * 18f + group.second.map { it.device }.distinct().size * 16f
    }
    if (groups.none { it.first.third != 0 }) height += 18f
    DiagramStage((height + 6f).dp, description = "What Reset would change") {
        Column(Modifier.at(12.dp, 8.dp).width(312.dp)) {
            StageLabel("What Reset would change", null, maxWidth = 312.dp)
            Spacer(Modifier.height(8.dp))
            if (groups.none { it.first.third != 0 }) Text("Nothing would change.", color = Diagram.Hint, fontSize = 11.sp,
                lineHeight = 18.sp)
            groups.forEach { (group, count) ->
                val (label, rows, kind) = group
                Row(Modifier.fillMaxWidth().height(20.dp).drawBehind {
                    drawLine(Diagram.Hairline, Offset(0f, size.height - .5.dp.toPx()), Offset(size.width, size.height - .5.dp.toPx()),
                        1.dp.toPx())
                }, verticalAlignment = Alignment.CenterVertically) {
                    StageLabel(label, null, Modifier.weight(1f))
                    DiagramValue("$count", color = if (kind == 2) CameraColors.Accent else Diagram.Hint)
                }
                var device: String? = null
                rows.forEach { row ->
                    if (row.device != device) {
                        device = row.device
                        Text(row.device, translate = false, color = Diagram.Hint, fontSize = 9.5.sp, lineHeight = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(16.dp))
                    }
                    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(9.dp), contentAlignment = Alignment.Center) {
                            when (kind) {
                                1 -> SettingsIcon(SettingsSymbol.Close, Modifier.size(8.dp), Color(0xFF8C8C8C))
                                2 -> Box(Modifier.size(6.dp).background(CameraColors.Accent, CircleShape))
                                else -> Box(Modifier.size(6.dp).border(1.dp, Diagram.Hint, CircleShape))
                            }
                        }
                        val ink = when (kind) { 1 -> Color(0xFF8C8C8C); 2 -> CameraColors.Text; else -> Color(0xFFCFCFCF) }
                        Text(row.inputs, translate = false, color = ink, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textDecoration = if (kind == 1) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f, fill = false))
                        Text("→", translate = false, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 14.sp)
                        Text(row.action, translate = false, color = if (kind == 2) CameraColors.Accent else ink, fontSize = 11.sp,
                            lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
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
    Box(Modifier.fillMaxSize().background(Diagram.Dim)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        .safeDrawingPadding().padding(14.dp).testTag("Button assignment"), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 400.dp).fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            DiagramWindow(title = action.label, eyebrow = action.group.label, buttons = {
                when {
                    step == AssignStep.Review -> {
                        DiagramButton("Try again") {
                            first = null; second = null; gesture = ControlGesture.TAP; reversed = false
                            step = AssignStep.Listening(false)
                        }
                        DiagramButton("Save", Modifier.testTag("Save button assignment"), symbol = SettingsSymbol.Check, accent = true) {
                            proposed.forEach(ControlBindings::assign)
                            onClose()
                        }
                    }
                    listening?.second == true -> {
                        DiagramButton("Cancel", onClick = onClose)
                        DiagramButton("Skip") { step = AssignStep.Review }
                    }
                    else -> DiagramButton("Cancel", onClick = onClose)
                }
            }) {
                AnimatedContent(step, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }) { current ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (current) {
                            is AssignStep.Listening -> {
                                ListeningStage(action, current.second, first)
                                if (action.stepper) StepIndicator(action, current.second)
                                first?.takeIf { current.second }?.let {
                                    CapturedInputChip(AppLanguage.text(action.increaseLabel), it)
                                }
                                Text(when {
                                    !action.stepper -> "Press a button"
                                    current.second -> AppLanguage.format("Now press the button for {direction}",
                                        mapOf("direction" to AppLanguage.text(action.decreaseLabel)))
                                    else -> AppLanguage.format("Turn a dial, or press the button for {direction}",
                                        mapOf("direction" to AppLanguage.text(action.increaseLabel)))
                                }, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                    translate = !action.stepper, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                if (!current.second) Text("Phone keys and connected accessories are heard here.",
                                    color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp)
                            }
                            AssignStep.Review -> {
                                val dial = first?.trigger?.kind == ControlInputKind.SCROLL
                                ReviewStage(action, first, proposed.firstOrNull(), replaced,
                                    gesture.takeIf { !action.stepper && !dial })
                                first?.let { input ->
                                    CapturedInputChip(if (action.stepper && !dial) AppLanguage.text(action.increaseLabel) else null, input)
                                }
                                second?.let { CapturedInputChip(AppLanguage.text(action.decreaseLabel), it) }
                                if (!action.stepper && !dial) {
                                    val gestures = ControlGesture.entries
                                    DiagramSegmented(gestures.map { it.label }, gestures.indexOf(gesture),
                                        Modifier.testTag("Button gesture choices")) { gesture = gestures[it] }
                                }
                                if (action.stepper && dial) {
                                    DialDirections(action, first, reversed)
                                    DiagramSwitchRow("Reverse direction", reversed, divider = false) { reversed = it }
                                }
                                if (replaced.isNotEmpty()) Text("Replaces {actions}", arguments = mapOf("actions" to
                                    replaced.joinToString(", ") { AppLanguage.text(it.label) }),
                                    color = CameraColors.FocusFailed, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
            DiagramValue(if (step == AssignStep.Review) "review" else "listening", translate = true,
                color = if (step == AssignStep.Review) Diagram.Hint else CameraColors.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp))
        }
    }
}

/** The two steps of a stepper on buttons: one button for each direction. */
@Composable
private fun StepIndicator(action: ControlAction, second: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepChip(1, action.increaseLabel, active = !second, done = second)
        Box(Modifier.weight(1f).height(10.dp).drawBehind {
            val y = size.height / 2
            drawLine(Diagram.Line, Offset(0f, y), Offset(size.width - 4.dp.toPx(), y), 1.dp.toPx())
            drawPath(Path().apply {
                moveTo(size.width, y); lineTo(size.width - 5.dp.toPx(), y - 3.5.dp.toPx()); lineTo(size.width - 5.dp.toPx(), y + 3.5.dp.toPx()); close()
            }, Diagram.Line)
        })
        StepChip(2, action.decreaseLabel, active = second, done = false)
    }
}

@Composable
private fun StepChip(number: Int, label: String, active: Boolean, done: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.heightIn(min = 26.dp).border(1.dp, if (active) CameraColors.Accent else Diagram.Border, shape)
        .padding(start = 5.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(16.dp).background(if (done) CameraColors.Accent else Color.Transparent, CircleShape)
            .border(1.dp, if (active || done) CameraColors.Accent else Diagram.Line, CircleShape), contentAlignment = Alignment.Center) {
            if (done) SettingsIcon(SettingsSymbol.Check, Modifier.size(9.dp), Color.Black)
            else Text("$number", translate = false, color = if (active) CameraColors.Accent else Diagram.Hint, fontSize = 9.5.sp,
                lineHeight = 11.sp, fontFamily = Diagram.Mono)
        }
        Text(label, color = when { active -> CameraColors.Accent; done -> CameraColors.Text; else -> Diagram.Hint },
            fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

/** The grip and phone keys with a pulsing ring while the window waits for an input. */
@Composable
private fun ListeningStage(action: ControlAction, second: Boolean, first: CapturedInput?) {
    val transition = rememberInfiniteTransition(label = "Listening ring")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "Listening ring progress")
    val gx = 4.dp; val gy = 60.dp; val scale = .62f
    val captured = first?.takeIf { second }?.gripPart()
    DiagramStage(198.dp, description = "Listening") {
        DiagramDrawing {
            photographyGrip(gx, gy, scale, setOfNotNull(captured))
            val (kx, ky) = gripPoint(GripPart.VolumeDown, gx, gy, scale)
            val (sx, sy) = gripPoint(GripPart.Shutter, gx, gy, scale)
            val (dx, dy) = gripPoint(GripPart.Dial, gx, gy, scale)
            val (zx, zy) = gripPoint(GripPart.Zoom, gx, gy, scale)
            line(kx, ky - 2.dp, kx, 56.dp, Color.White.copy(alpha = .35f), 1.dp)
            line(zx - 8.dp, zy - 1.dp, zx - 8.dp, 36.dp, Color.White.copy(alpha = .35f), 1.dp)
            line(dx + 4.dp, dy - 4.dp, dx + 4.dp, 10.dp, Color.White.copy(alpha = .35f), 1.dp)
            line(sx + 2.dp, sy + 4.dp, 248.dp, 96.dp, Diagram.Line, 1.dp, dashed = true)
            for (offset in listOf(0f, .5f)) {
                val wave = (progress + offset) % 1f
                circle(272.dp, 96.dp, (21f + wave * 31f).dp, CameraColors.Accent.copy(alpha = (1f - wave) * .5f), 1.2.dp)
            }
            circle(272.dp, 96.dp, 21.dp, CameraColors.Accent, 1.6.dp, fill = Color(0xFF0A0A0A))
        }
        Box(Modifier.at(260.dp, 84.dp).size(24.dp), contentAlignment = Alignment.Center) {
            GripIcon(Modifier.size(20.dp))
        }
        Text(AppLanguage.text("Listening").uppercase(), translate = false, color = CameraColors.Accent, fontSize = 9.sp,
            lineHeight = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.at(222.dp, 30.dp).width(100.dp))
        DiagramText("Zoom lever", Modifier.at(12.dp, 8.dp), maxWidth = 46.dp)
        StageLabel(null, AppLanguage.text("Dial"), Modifier.at(66.dp, 4.dp), active = false, valueTranslate = false,
            valueColor = CameraColors.Text, detail = if (action.stepper && !second) "Sets both directions" else null, maxWidth = 140.dp)
        DiagramText("Phone keys", Modifier.at(114.dp, 56.dp), maxWidth = 100.dp)
        if (second && first != null) {
            DiagramText("Step 1 kept", Modifier.at(12.dp, 172.dp), maxWidth = 150.dp)
            Text("Waiting for {direction}", arguments = mapOf("direction" to AppLanguage.text(action.decreaseLabel)),
                color = CameraColors.Accent, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.at(196.dp, 170.dp).width(128.dp))
        }
    }
}

/** The captured input and what it does once saved, with the press and hold timing when that choice applies. */
@Composable
private fun ReviewStage(action: ControlAction, first: CapturedInput?, proposed: ControlBinding?, replaced: List<ControlAction>,
    gesture: ControlGesture?) {
    first ?: return
    val gx = 4.dp; val gy = 18.dp; val scale = .5f
    val part = first.gripPart()
    val height = if (gesture != null) 152.dp else 112.dp
    DiagramStage(height, description = "Review") {
        DiagramDrawing {
            photographyGrip(gx, gy, scale, setOfNotNull(part))
            part?.let {
                val (px, py) = gripPoint(it, gx, gy, scale)
                callout(px, py, px, 8.dp)
                line(px, 8.dp, 168.dp, 8.dp, CameraColors.Accent.copy(alpha = .7f), 1.dp)
            }
            if (gesture != null) {
                line(112.dp, 120.dp, 324.dp, 120.dp, Color(0xFF3A3A3A), 1.dp)
                val press = gesture == ControlGesture.TAP
                polyline(listOf(114.dp to 146.dp, 124.dp to 146.dp, 124.dp to 132.dp, 134.dp to 132.dp, 134.dp to 146.dp,
                    184.dp to 146.dp), if (press) CameraColors.Accent else Diagram.Hint, if (press) 1.5.dp else 1.3.dp)
                polyline(listOf(184.dp to 146.dp, 194.dp to 146.dp, 194.dp to 132.dp, 262.dp to 132.dp, 262.dp to 146.dp,
                    324.dp to 146.dp), if (!press) CameraColors.Accent else Diagram.Hint, if (!press) 1.5.dp else 1.3.dp)
            }
        }
        Column(Modifier.at(176.dp, 4.dp).width(152.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            StageLabel(AppLanguage.format("{input} · {device}", mapOf("input" to controlInputName(first.trigger),
                "device" to (first.deviceName ?: AppLanguage.text("Phone")))), null, captionTranslate = false, maxWidth = 152.dp)
            if (replaced.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("before", color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono,
                    modifier = Modifier.width(40.dp))
                Text(replaced.joinToString(", ") { AppLanguage.text(it.label) }, translate = false, color = Color(0xFF8C8C8C),
                    fontSize = 12.sp, lineHeight = 15.sp, textDecoration = TextDecoration.LineThrough, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("after", color = Diagram.Hint, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Diagram.Mono,
                    modifier = Modifier.width(40.dp))
                Text(proposed?.let(::bindingSummary) ?: AppLanguage.text(action.label), translate = false, color = CameraColors.Accent,
                    fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (gesture != null) {
            Text(ControlGesture.TAP.label, color = if (gesture == ControlGesture.TAP) CameraColors.Accent else Diagram.Hint,
                fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.at(118.dp, 102.dp).widthIn(max = 80.dp))
            Text(ControlGesture.HOLD.label, color = if (gesture == ControlGesture.HOLD) CameraColors.Accent else Diagram.Hint,
                fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.at(204.dp, 102.dp).widthIn(max = 120.dp))
            DiagramText("Finger down", Modifier.at(12.dp, 126.dp), size = 9.5f, maxWidth = 96.dp)
        }
    }
}

/** Which way the captured dial turns each direction of a stepper, swapped by Reverse direction. */
@Composable
private fun DialDirections(action: ControlAction, input: CapturedInput?, reversed: Boolean) {
    input ?: return
    Row(Modifier.fillMaxWidth().drawBehind {
        drawRoundRect(Diagram.Line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(56.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension * .3f
            drawCircle(Diagram.Ink, radius, center, style = Stroke(1.5.dp.toPx()))
            drawCircle(Diagram.Line, radius * .4f, center, style = Stroke(1.dp.toPx()))
            for (tick in 0 until 12) {
                val angle = Math.toRadians(tick * 30.0)
                val inner = radius + 2.dp.toPx(); val outer = radius + 5.dp.toPx()
                drawLine(Diagram.Hint, Offset(center.x + inner * kotlin.math.cos(angle).toFloat(), center.y + inner * kotlin.math.sin(angle).toFloat()),
                    Offset(center.x + outer * kotlin.math.cos(angle).toFloat(), center.y + outer * kotlin.math.sin(angle).toFloat()), 1.dp.toPx())
            }
            val arc = size.minDimension * .46f
            val topLeft = Offset(center.x - arc, center.y - arc)
            val arcSize = androidx.compose.ui.geometry.Size(arc * 2, arc * 2)
            drawArc(if (reversed) Diagram.Hint else CameraColors.Accent, 200f, 70f, false, topLeft, arcSize, style = Stroke(1.3.dp.toPx()))
            drawArc(if (reversed) CameraColors.Accent else Diagram.Hint, 20f, 70f, false, topLeft, arcSize, style = Stroke(1.3.dp.toPx()))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("One way: {direction}", arguments = mapOf("direction" to AppLanguage.text(
                if (reversed) action.decreaseLabel else action.increaseLabel)), color = CameraColors.Accent, fontSize = 11.sp, lineHeight = 15.sp)
            Text("Other way: {direction}", arguments = mapOf("direction" to AppLanguage.text(
                if (reversed) action.increaseLabel else action.decreaseLabel)), color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
            Text("{input} · {device}", arguments = mapOf("input" to (if (reversed) "⇅ " else "") + controlInputName(input.trigger),
                "device" to (input.deviceName ?: AppLanguage.text("Phone"))), color = CameraColors.Text, fontSize = 11.5.sp,
                lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CapturedInputChip(role: String?, input: CapturedInput) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(shape).background(CameraColors.Accent.copy(alpha = .06f))
        .border(1.dp, CameraColors.Accent, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (input.external) GripIcon(Modifier.size(18.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(controlInputName(input.trigger), color = CameraColors.Accent, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, translate = false)
            Text(input.deviceName ?: AppLanguage.text("Phone"), color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 16.sp,
                translate = false, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        role?.let { Text(it, color = CameraColors.Text, fontSize = 12.sp, lineHeight = 17.sp, translate = false) }
    }
}

/** Lets the app read a USB accessory directly, for controls Android leaves out, such as which way a dial turns. */
@Composable
private fun DirectUsbSettings() {
    val devices by DirectUsbControls.devices.collectAsState()
    val enabled by DirectUsbControls.enabled.collectAsState()
    val status by DirectUsbControls.status.collectAsState()
    devices.forEach { device ->
        val on = device.key in enabled
        val state = status[device.key]
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DiagramNumber(3)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Direct connection", color = CameraColors.Text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Text(device.name, color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp, translate = false,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    !on -> "Reads dials in both directions. Buttons then work only in this app while it is open."
                    state == DirectUsbStatus.CONNECTED -> "Connected. Assign its buttons again here."
                    state == DirectUsbStatus.WAITING_FOR_PERMISSION -> "Allow access when Android asks."
                    state == DirectUsbStatus.FAILED -> "Could not connect. Reconnect the accessory and try again."
                    else -> "Connecting"
                }, color = if (on && state == DirectUsbStatus.FAILED) CameraColors.FocusFailed
                    else if (on && state == DirectUsbStatus.CONNECTED) CameraColors.Accent else Diagram.Hint,
                    fontSize = 11.sp, lineHeight = 15.sp)
            }
            DiagramSwitch(on, "Direct connection") { DirectUsbControls.setEnabled(device.key, it) }
        }
    }
}
