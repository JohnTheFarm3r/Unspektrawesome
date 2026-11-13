package com.unspektrawesome.controls

import android.content.Context
import android.view.KeyEvent
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.VolumeButtonAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ControlGroup(val label: String) {
    CAPTURE("Capture"),
    LENS_MODE("Lenses and modes"),
    EXPOSURE("Exposure"),
    IMAGE("Focus and image"),
    SHORTCUTS("Shortcuts"),
}

/**
 * Camera functions that buttons, dials and sticks can trigger. Steppers change a value up or down
 * and take a direction; the other actions run once per press.
 */
enum class ControlAction(
    val label: String,
    val group: ControlGroup,
    val stepper: Boolean = false,
    val repeats: Boolean = stepper,
    val increaseLabel: String = "Increase",
    val decreaseLabel: String = "Decrease",
    /** Also told when the button is released, with a step of 0. */
    val tellsRelease: Boolean = false,
) {
    SHUTTER("Shutter release", ControlGroup.CAPTURE),
    TAKE_PHOTO("Take photo", ControlGroup.CAPTURE),
    RECORD_VIDEO("Record video", ControlGroup.CAPTURE),
    HALF_PRESS("Half press focus", ControlGroup.CAPTURE, tellsRelease = true),
    LENS("Switch lens", ControlGroup.LENS_MODE, stepper = true, repeats = false,
        increaseLabel = "Next lens", decreaseLabel = "Previous lens"),
    CAPTURE_MODE("Capture mode", ControlGroup.LENS_MODE, stepper = true, repeats = false,
        increaseLabel = "Next mode", decreaseLabel = "Previous mode"),
    EV("Exposure compensation", ControlGroup.EXPOSURE, stepper = true),
    ISO("ISO", ControlGroup.EXPOSURE, stepper = true),
    SHUTTER_SPEED("Shutter speed", ControlGroup.EXPOSURE, stepper = true),
    AE_BALANCE("AE balance", ControlGroup.EXPOSURE, stepper = true),
    SHADOWS("Shadows", ControlGroup.IMAGE, stepper = true),
    HIGHLIGHTS("Highlight compression", ControlGroup.IMAGE, stepper = true),
    FOCUS("Focus distance", ControlGroup.IMAGE, stepper = true),
    WHITE_BALANCE("White balance", ControlGroup.IMAGE, stepper = true),
    AUTO_EXPOSURE("Auto exposure", ControlGroup.SHORTCUTS),
    AUTO_FOCUS("Autofocus", ControlGroup.SHORTCUTS),
    SPEKTRA_AE("spektrafilm AE", ControlGroup.SHORTCUTS, stepper = true, repeats = false,
        increaseLabel = "Next", decreaseLabel = "Previous"),
    GALLERY("Open gallery", ControlGroup.SHORTCUTS),
}

enum class ControlGesture(val label: String) { TAP("Press"), HOLD("Hold") }

/** HID inputs come from accessories read directly over USB; their code is the usage page and id. */
enum class ControlInputKind { KEY, SCROLL, AXIS, HID }

/**
 * One physical input. Keys without an Android key code are identified by their scan code, stored as a
 * negative [code]. Axis inputs carry the direction the stick or trigger was pushed in [sign].
 */
data class ControlTrigger(val kind: ControlInputKind, val code: Int, val sign: Int = 0, val device: String) {
    fun sameInput(other: ControlTrigger) = kind == other.kind && code == other.code && sign == other.sign && device == other.device

    companion object {
        /** Buttons built into the phone. */
        const val PHONE = "phone"
        /** Any connected accessory. */
        const val ACCESSORY = "accessory"
        /** Any device, built in or connected. */
        const val ANY = "any"
    }
}

data class ControlBinding(
    val trigger: ControlTrigger,
    val action: ControlAction,
    val gesture: ControlGesture = ControlGesture.TAP,
    /** Step direction for steppers. For dials, 1 keeps the dial's own direction and -1 reverses it. */
    val direction: Int = 1,
    /** Accessory name shown in settings. */
    val deviceName: String? = null,
) {
    /** True when [other] cannot share an input with this binding. */
    fun conflictsWith(other: ControlBinding): Boolean {
        if (!trigger.sameInput(other.trigger)) return false
        if (trigger.kind == ControlInputKind.SCROLL) return true
        return action.stepper || other.action.stepper || gesture == other.gesture
    }
}

object ControlBindings {
    private const val FILE = "control_bindings"
    private const val KEY = "bindings"
    private const val KEY_REVISION = "defaults_revision"
    private const val DEFAULTS_REVISION = 2
    private val mutableState = MutableStateFlow<List<ControlBinding>>(emptyList())
    val state: StateFlow<List<ControlBinding>> = mutableState.asStateFlow()
    private var context: Context? = null

    /** Loads saved bindings. The first start turns the former volume button choices into bindings. */
    fun initialize(context: Context, legacy: CameraPreferences) = synchronized(this) {
        if (this.context != null) return@synchronized
        val app = context.applicationContext
        this.context = app
        val preferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val saved = preferences.getString(KEY, null)
        var bindings = if (saved == null) defaults(legacy) else decode(saved)
        // Defaults added in later versions join saved bindings without replacing the user's choices.
        if (saved != null && preferences.getInt(KEY_REVISION, 1) < 2)
            directDefaults().filter { added -> bindings.none { it.conflictsWith(added) } }.let { bindings = bindings + it }
        mutableState.value = bindings
        save(bindings)
    }

    fun set(bindings: List<ControlBinding>) {
        mutableState.value = bindings
        save(bindings)
    }

    /** Adds [binding], removing whatever it replaces. */
    fun assign(binding: ControlBinding) = set(state.value.filterNot { it.conflictsWith(binding) } + binding)

    fun remove(binding: ControlBinding) = set(state.value - binding)

    fun reset() = set(defaults(CameraPreferences()))

    /**
     * Bindings that apply to an input, given the device keys from most to least specific. A binding for this exact
     * accessory wins over one for every accessory, which wins over one for any device.
     */
    fun resolve(bindings: List<ControlBinding>, kind: ControlInputKind, code: Int, sign: Int,
        deviceKeys: List<String>): List<ControlBinding> {
        val candidates = bindings.filter { it.trigger.kind == kind && it.trigger.code == code && it.trigger.sign == sign }
        val result = mutableListOf<ControlBinding>()
        deviceKeys.distinct().forEach { key ->
            candidates.filter { it.trigger.device == key }.forEach { candidate ->
                if (result.none { it.copy(trigger = candidate.trigger).conflictsWith(candidate) }) result += candidate
            }
        }
        return result
    }

    private fun save(bindings: List<ControlBinding>) {
        context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, encode(bindings))
            ?.putInt(KEY_REVISION, DEFAULTS_REVISION)?.apply()
    }

    /** Standard camera, zoom and record controls of accessories read directly over USB. */
    private fun directDefaults(): List<ControlBinding> = listOf(
        HidUsage.CAMERA_FOCUS to (ControlAction.HALF_PRESS to 1),
        HidUsage.CAMERA_SHUTTER to (ControlAction.SHUTTER to 1),
        HidUsage.RECORD to (ControlAction.RECORD_VIDEO to 1),
        HidUsage.ZOOM_IN to (ControlAction.LENS to 1),
        HidUsage.ZOOM_OUT to (ControlAction.LENS to -1),
    ).map { (usage, target) ->
        ControlBinding(ControlTrigger(ControlInputKind.HID, usage, device = ControlTrigger.ANY), target.first, direction = target.second)
    }

    fun defaults(legacy: CameraPreferences): List<ControlBinding> = buildList {
        fun key(code: Int, device: String, action: ControlAction, direction: Int = 1) =
            add(ControlBinding(ControlTrigger(ControlInputKind.KEY, code, device = device), action, direction = direction))
        if (legacy.volumeButtonsEnabled) {
            legacyBinding(KeyEvent.KEYCODE_VOLUME_UP, legacy.volumeUpAction)?.let(::add)
            legacyBinding(KeyEvent.KEYCODE_VOLUME_DOWN, legacy.volumeDownAction)?.let(::add)
        }
        key(KeyEvent.KEYCODE_CAMERA, ControlTrigger.ANY, ControlAction.SHUTTER)
        key(KeyEvent.KEYCODE_FOCUS, ControlTrigger.ANY, ControlAction.HALF_PRESS)
        key(KeyEvent.KEYCODE_MEDIA_RECORD, ControlTrigger.ANY, ControlAction.RECORD_VIDEO)
        key(KeyEvent.KEYCODE_ZOOM_IN, ControlTrigger.ANY, ControlAction.LENS, 1)
        key(KeyEvent.KEYCODE_ZOOM_OUT, ControlTrigger.ANY, ControlAction.LENS, -1)
        // Remote shutters usually send one of these keys.
        listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE).forEach {
            key(it, ControlTrigger.ACCESSORY, ControlAction.SHUTTER)
        }
        addAll(directDefaults())
    }

    private fun legacyBinding(code: Int, action: VolumeButtonAction): ControlBinding? {
        val (target, direction) = when (action) {
            VolumeButtonAction.SHUTTER -> ControlAction.SHUTTER to 1
            VolumeButtonAction.SYSTEM_VOLUME -> return null
            VolumeButtonAction.DEVICE_EV_UP -> ControlAction.EV to 1
            VolumeButtonAction.DEVICE_EV_DOWN -> ControlAction.EV to -1
            VolumeButtonAction.SPEKTRA_AE_NEXT -> ControlAction.SPEKTRA_AE to 1
            VolumeButtonAction.SPEKTRA_AE_PREVIOUS -> ControlAction.SPEKTRA_AE to -1
        }
        return ControlBinding(ControlTrigger(ControlInputKind.KEY, code, device = ControlTrigger.PHONE), target, direction = direction)
    }

    internal fun encode(bindings: List<ControlBinding>): String = JSONObject().put("version", 1).put("bindings", JSONArray().apply {
        bindings.forEach { binding ->
            put(JSONObject()
                .put("kind", binding.trigger.kind.name)
                .put("code", binding.trigger.code)
                .put("sign", binding.trigger.sign)
                .put("device", binding.trigger.device)
                .put("deviceName", binding.deviceName ?: JSONObject.NULL)
                .put("action", binding.action.name)
                .put("gesture", binding.gesture.name)
                .put("direction", binding.direction))
        }
    }).toString()

    internal fun decode(value: String): List<ControlBinding> = runCatching {
        val array = JSONObject(value).getJSONArray("bindings")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                ControlBinding(
                    ControlTrigger(ControlInputKind.valueOf(item.getString("kind")), item.getInt("code"),
                        item.optInt("sign", 0).coerceIn(-1, 1), item.getString("device")),
                    ControlAction.valueOf(item.getString("action")),
                    ControlGesture.entries.firstOrNull { it.name == item.optString("gesture") } ?: ControlGesture.TAP,
                    if (item.optInt("direction", 1) < 0) -1 else 1,
                    item.optString("deviceName").takeIf { !item.isNull("deviceName") && it.isNotBlank() },
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/** HID usages, as usage page shl 16 or usage id. */
object HidUsage {
    const val CAMERA_FOCUS = 0x00900020
    const val CAMERA_SHUTTER = 0x00900021
    const val RECORD = 0x000C00B2
    const val PLAY_PAUSE = 0x000C00CD
    const val NEXT_TRACK = 0x000C00B5
    const val PREVIOUS_TRACK = 0x000C00B6
    const val VOLUME_UP = 0x000C00E9
    const val VOLUME_DOWN = 0x000C00EA
    const val ZOOM_IN = 0x000C022D
    const val ZOOM_OUT = 0x000C022E
    const val KEYBOARD_ENTER = 0x00070028
    const val KEYBOARD_SPACE = 0x0007002C
    const val WHEEL = HidReportLayout.WHEEL
    const val PAN = HidReportLayout.PAN
}
