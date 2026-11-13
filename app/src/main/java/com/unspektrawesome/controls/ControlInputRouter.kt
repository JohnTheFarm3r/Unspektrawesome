package com.unspektrawesome.controls

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/** An input seen by the app, with the accessory it came from. */
data class CapturedInput(val trigger: ControlTrigger, val deviceName: String?, val external: Boolean,
    val time: Long = SystemClock.uptimeMillis())

/** Shares inputs between the camera and the button settings. */
object ControlInputHub {
    /** While set, the next input is delivered here instead of running an action. */
    @Volatile var capture: ((CapturedInput) -> Unit)? = null
    /** True while the button settings are open, so trying a button does not also change the volume. */
    @Volatile var monitoring = false
    /** The most recent input, used to show which button was pressed. */
    val lastInput = MutableStateFlow<CapturedInput?>(null)
}

/**
 * Turns key, dial and stick events into camera actions. A press runs at once, unless the same input also has
 * a hold action; then a short press runs when released and a hold runs after [HOLD_MS]. Steppers repeat while held.
 */
class ControlInputRouter(
    private val bindings: () -> List<ControlBinding>,
    private val handler: () -> ((ControlAction, Int) -> Unit)?,
    /** Gives a short vibration when an accessory runs an action; true for a dial or repeated step. */
    private val feedback: (step: Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    private class Held(val consumed: Boolean, val tap: ControlBinding? = null, var task: Runnable? = null,
        val released: ControlAction? = null, val external: Boolean = false)

    /** One input on one device, for example a stick pushed left. */
    private data class InputKey(val device: String, val kind: ControlInputKind, val code: Int, val sign: Int = 0)

    /** Where an input came from: its binding key, the name to show, and whether it is an accessory. */
    private data class Source(val key: String, val name: String?, val external: Boolean)

    private fun source(device: InputDevice?) =
        Source(deviceKey(device), device?.takeIf { it.isExternal }?.name, device?.isExternal == true)

    private val held = mutableMapOf<InputKey, Held>()
    private val axisStates = mutableMapOf<InputKey, Int>()
    private val scrollRemainders = mutableMapOf<InputKey, Float>()

    fun onKey(event: KeyEvent): Boolean {
        if (com.unspektrawesome.BuildConfig.DEBUG) android.util.Log.d("ControlInput",
            "key action=${event.action} code=${event.keyCode} scan=${event.scanCode} repeat=${event.repeatCount} device=${event.device?.name}")
        if (event.keyCode in SYSTEM_KEYS) return false
        val code = if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) event.keyCode else -event.scanCode
        if (code == 0) return false
        val id = InputKey("${event.deviceId}", ControlInputKind.KEY, code)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) press(id, ControlInputKind.KEY, code, 0, source(event.device))
                else held[id]?.consumed == true
            KeyEvent.ACTION_UP -> release(id)
            else -> false
        }
    }

    fun onMotion(event: MotionEvent): Boolean = when {
        com.unspektrawesome.BuildConfig.DEBUG && android.util.Log.d("ControlInput",
            "motion action=${event.action} source=${event.source} v=${event.getAxisValue(MotionEvent.AXIS_VSCROLL)} " +
                "h=${event.getAxisValue(MotionEvent.AXIS_HSCROLL)} device=${event.device?.name}") < 0 -> false
        event.action == MotionEvent.ACTION_SCROLL -> scroll(event)
        event.action == MotionEvent.ACTION_MOVE && (event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)) -> axes(event)
        else -> false
    }

    /** A button on an accessory read directly over USB. */
    fun onDirectButton(deviceKey: String, name: String, usage: Int, pressed: Boolean) {
        val id = InputKey(deviceKey, ControlInputKind.HID, usage)
        if (pressed) press(id, ControlInputKind.HID, usage, 0, Source(deviceKey, name, true)) else release(id)
    }

    /** A dial or wheel on an accessory read directly over USB. */
    fun onDirectWheel(deviceKey: String, name: String, usage: Int, steps: Int) {
        if (steps != 0) tick(ControlInputKind.SCROLL, usage, steps, Source(deviceKey, name, true))
    }

    fun reset() {
        held.values.forEach { state ->
            state.task?.let(main::removeCallbacks)
            state.released?.let { handler()?.invoke(it, 0) }
        }
        held.clear()
        axisStates.clear()
        scrollRemainders.clear()
    }

    private fun scroll(event: MotionEvent): Boolean {
        var consumed = false
        for (axis in SCROLL_AXES) {
            val value = event.getAxisValue(axis)
            if (value == 0f || !value.isFinite()) continue
            val key = InputKey("${event.deviceId}", ControlInputKind.SCROLL, axis)
            // High-resolution wheels report fractions of a step.
            val total = (scrollRemainders[key] ?: 0f).let { if (it.sign != value.sign) 0f else it } + value
            val steps = if (abs(value) >= 1f) value.sign.toInt() * maxOf(1, abs(truncate(value)).toInt()) else truncate(total).toInt()
            scrollRemainders[key] = if (abs(value) >= 1f) 0f else total - steps
            if (steps == 0) { consumed = true; continue }
            consumed = tick(ControlInputKind.SCROLL, axis, steps, source(event.device)) || consumed
        }
        return consumed
    }

    private fun tick(kind: ControlInputKind, code: Int, steps: Int, device: Source): Boolean {
        val input = input(kind, code, 0, device)
        ControlInputHub.lastInput.value = input
        ControlInputHub.capture?.let { it(input); return true }
        val matches = match(kind, code, 0, device)
        if (matches.isEmpty()) return false
        val run = handler() ?: return ControlInputHub.monitoring
        val binding = matches.first()
        if (binding.action.stepper) run(binding.action, steps.sign * binding.direction * minOf(abs(steps), MAX_STEPS_PER_EVENT))
        else run(binding.action, 1)
        if (device.external) feedback(true)
        return true
    }

    private fun axes(event: MotionEvent): Boolean {
        var consumed = false
        for (axis in STICK_AXES) {
            val value = event.getAxisValue(axis)
            val key = InputKey("${event.deviceId}", ControlInputKind.AXIS, axis)
            val previous = axisStates[key] ?: 0
            // Hysteresis keeps a resting stick from flickering between pressed and released.
            val current = when {
                abs(value) >= PRESS_THRESHOLD -> value.sign.toInt()
                abs(value) <= RELEASE_THRESHOLD -> 0
                else -> previous
            }
            if (current == previous) continue
            axisStates[key] = current
            if (previous != 0) consumed = release(key.copy(sign = previous)) || consumed
            if (current != 0) consumed = press(key.copy(sign = current), ControlInputKind.AXIS, axis, current, source(event.device)) || consumed
        }
        return consumed
    }

    private fun press(id: InputKey, kind: ControlInputKind, code: Int, sign: Int, device: Source): Boolean {
        held.remove(id)?.task?.let(main::removeCallbacks)
        val input = input(kind, code, sign, device)
        ControlInputHub.lastInput.value = input
        ControlInputHub.capture?.let {
            it(input)
            held[id] = Held(true)
            return true
        }
        val matches = match(kind, code, sign, device)
        if (matches.isEmpty()) return false
        val run = handler()
        if (run == null) {
            // Outside the viewfinder, keys keep their usual meaning, except camera keys that would open another camera app.
            val consumed = ControlInputHub.monitoring ||
                kind == ControlInputKind.KEY && (code == KeyEvent.KEYCODE_CAMERA || code == KeyEvent.KEYCODE_FOCUS)
            held[id] = Held(consumed)
            return consumed
        }
        val stepper = matches.firstOrNull { it.action.stepper }
        if (stepper != null) {
            run(stepper.action, stepper.direction)
            if (device.external) feedback(true)
            val state = Held(true)
            if (stepper.action.repeats) {
                val repeat = object : Runnable {
                    override fun run() {
                        handler()?.invoke(stepper.action, stepper.direction) ?: return
                        if (device.external) feedback(true)
                        main.postDelayed(this, REPEAT_MS)
                    }
                }
                state.task = repeat
                main.postDelayed(repeat, REPEAT_DELAY_MS)
            }
            held[id] = state
            return true
        }
        val tap = matches.firstOrNull { it.gesture == ControlGesture.TAP }
        val hold = matches.firstOrNull { it.gesture == ControlGesture.HOLD }
        if (hold == null) {
            tap?.let {
                run(it.action, 1)
                if (device.external) feedback(false)
            }
            held[id] = Held(true, released = tap?.action?.takeIf { it.tellsRelease })
            return true
        }
        val state = Held(true, tap, external = device.external)
        val task = Runnable {
            if (held[id] === state) {
                held[id] = Held(true)
                handler()?.invoke(hold.action, 1) ?: return@Runnable
                if (device.external) feedback(false)
            }
        }
        state.task = task
        held[id] = state
        main.postDelayed(task, HOLD_MS)
        return true
    }

    private fun release(id: InputKey): Boolean {
        val state = held.remove(id) ?: return false
        state.task?.let(main::removeCallbacks)
        // A hold action that has not run yet means this was a short press.
        state.tap?.takeIf { state.task != null }?.let { tap ->
            handler()?.invoke(tap.action, 1) ?: return@let
            if (state.external) feedback(false)
        }
        state.released?.let { handler()?.invoke(it, 0) }
        return state.consumed
    }

    private fun match(kind: ControlInputKind, code: Int, sign: Int, device: Source): List<ControlBinding> =
        ControlBindings.resolve(bindings(), kind, code, sign, listOf(device.key,
            if (device.external) ControlTrigger.ACCESSORY else ControlTrigger.PHONE, ControlTrigger.ANY))

    private fun input(kind: ControlInputKind, code: Int, sign: Int, device: Source) =
        CapturedInput(ControlTrigger(kind, code, sign, device.key), device.name, device.external)

    companion object {
        const val HOLD_MS = 500L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 110L
        private const val MAX_STEPS_PER_EVENT = 5
        private const val PRESS_THRESHOLD = .6f
        private const val RELEASE_THRESHOLD = .3f
        private val SYSTEM_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST)
        private val SCROLL_AXES = intArrayOf(MotionEvent.AXIS_VSCROLL, MotionEvent.AXIS_HSCROLL, MotionEvent.AXIS_SCROLL)
        private val STICK_AXES = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE)

        /** Accessories are told apart by maker, model and name, so a binding survives reconnecting. */
        fun deviceKey(device: InputDevice?): String =
            if (device == null || !device.isExternal) ControlTrigger.PHONE
            else "${device.vendorId}:${device.productId}:${device.name}"
    }
}
