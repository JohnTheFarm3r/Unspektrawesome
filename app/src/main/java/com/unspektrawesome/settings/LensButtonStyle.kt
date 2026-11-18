package com.unspektrawesome.settings

import com.unspektrawesome.camera.CameraDescriptor
import org.json.JSONObject

enum class LensButtonShape(val label: String) { CIRCLE("Circle"), ROUNDED("Rounded"), SQUARE("Square"), PILL("Pill") }

/**
 * How a lens profile's button looks on the camera screen, and whether the profile gets a button of its own
 * next to the button of its lens. A blank [label] shows the focal length label.
 * [spacing] is the gap to the neighbouring buttons in dp; negative values overlap them, null spaces automatically.
 */
data class LensButtonStyle(
    val ownButton: Boolean = false,
    val label: String = "",
    val textSize: Float = DEFAULT_TEXT_SIZE,
    val size: Float = DEFAULT_SIZE,
    val shape: LensButtonShape = LensButtonShape.CIRCLE,
    val spacing: Float? = null,
) {
    val customLabel: String? get() = label.trim().takeIf { it.isNotEmpty() }

    /** Whether anything besides the separate button differs from the standard look. */
    val customLook: Boolean get() = copy(ownButton = false) != LensButtonStyle()

    fun toJson(): JSONObject = JSONObject().put("ownButton", ownButton).put("label", label).put("textSize", textSize.toDouble())
        .put("size", size.toDouble()).put("shape", shape.name).put("spacing", spacing?.toDouble() ?: JSONObject.NULL)

    companion object {
        const val DEFAULT_TEXT_SIZE = 12f
        const val MIN_TEXT_SIZE = 8f
        const val MAX_TEXT_SIZE = 24f
        const val DEFAULT_SIZE = 44f
        const val MIN_SIZE = 32f
        const val MAX_SIZE = 72f
        const val MIN_SPACING = -16f
        const val MAX_SPACING = 24f
        const val MAX_LABEL_LENGTH = 16

        fun cleanLabel(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(MAX_LABEL_LENGTH)

        fun fromJson(json: JSONObject?): LensButtonStyle {
            if (json == null) return LensButtonStyle()
            fun number(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
                json.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() && it in range } ?: default
            return LensButtonStyle(
                ownButton = json.optBoolean("ownButton", false),
                label = cleanLabel(json.optString("label", "")),
                textSize = number("textSize", DEFAULT_TEXT_SIZE, MIN_TEXT_SIZE..MAX_TEXT_SIZE),
                size = number("size", DEFAULT_SIZE, MIN_SIZE..MAX_SIZE),
                shape = LensButtonShape.entries.firstOrNull { it.name == json.optString("shape") } ?: LensButtonShape.CIRCLE,
                spacing = if (json.isNull("spacing") || !json.has("spacing")) null
                    else json.optDouble("spacing").toFloat().takeIf { it.isFinite() }?.coerceIn(MIN_SPACING, MAX_SPACING))
        }
    }
}

/**
 * One button of the lens switcher. A lens button offers the profiles of its lens that have no button of their own
 * and shows the one in use or last used. A separate button always selects [pinned]. Holding either button lists
 * every profile of the lens in [group].
 */
data class LensRailSlot(val key: String, val group: List<LensProfile>, val pinned: LensProfile?) {
    /** Profiles a tap on this button can show. */
    val choices: List<LensProfile> get() = pinned?.let(::listOf) ?: group.filterNot { it.button.ownButton }

    fun contains(id: String?): Boolean = id != null && choices.any { it.id == id }
}

object LensRail {
    /**
     * The lens switcher's buttons for [profiles], which are in profile list order. Lenses are ordered by facing,
     * then focal length. A lens's buttons follow the profile list order, with the lens button at the place of its
     * first profile that has no button of its own.
     */
    fun slots(profiles: List<LensProfile>, cameras: List<CameraDescriptor>): List<LensRailSlot> {
        val byRoute = cameras.associateBy { it.route.routeId }
        val usable = profiles.filter { it.routeId in byRoute }
        val routeOrder = LensButtonMapping.opticalOrder(cameras).mapIndexed { index, camera -> camera.route.routeId to index }.toMap()
        val groups = usable.groupBy { it.focalGroup }.toList().sortedWith(compareBy(
            { (_, choices) -> byRoute.getValue(choices.first().routeId).facing.ordinal },
            { (_, choices) -> choices.minOf { it.focalLength.equivalent(byRoute.getValue(it.routeId)) ?: Float.MAX_VALUE } },
            { (_, choices) -> choices.minOf { routeOrder.getValue(it.routeId) } }))
        return groups.flatMap { (group, choices) ->
            val main = choices.firstOrNull { !it.button.ownButton }
            choices.mapNotNull { profile ->
                when {
                    profile.button.ownButton -> LensRailSlot("profile:${profile.id}", choices, profile)
                    profile === main -> LensRailSlot(group, choices, null)
                    else -> null
                }
            }
        }
    }
}
