package com.unspektrawesome.ui.camera

import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.LensButtonMapping
import com.unspektrawesome.settings.LensProfiles
import com.unspektrawesome.settings.LensRail

/**
 * The lens button a button step moves to, in the lens rail's order, as the id to select and its label.
 * Returns null at either end of the rail.
 */
internal fun lensStepTarget(cameras: List<CameraDescriptor>, preferences: CameraPreferences,
    selectedRouteId: String?, step: Int): Pair<String, String>? {
    if (step == 0 || cameras.isEmpty()) return null
    val ordered = LensButtonMapping.opticalOrder(cameras)
    val byRoute = cameras.associateBy { it.route.routeId }
    val profiles = LensProfiles.visible(preferences, cameras).filter { it.routeId in byRoute }
    if (profiles.isEmpty()) {
        val index = ordered.indexOfFirst { it.route.routeId == selectedRouteId }
        val target = ordered.getOrNull(if (index < 0) 0 else index + step.coerceIn(-1, 1)) ?: return null
        return target.route.routeId.takeIf { it != selectedRouteId }?.let { it to LensButtonMapping.zoomLabel(target) }
    }
    val slots = LensRail.slots(profiles, cameras)
    val index = slots.indexOfFirst { it.contains(preferences.activeLensProfileId) }
    val target = slots.getOrNull(if (index < 0) 0 else index + step.coerceIn(-1, 1)) ?: return null
    if (index >= 0 && target === slots[index]) return null
    val choices = target.choices
    val profile = choices.firstOrNull { it.id == preferences.defaultLensProfileId(preferences.lensProfileMode) } ?: choices.first()
    return profile.id to (profile.button.customLabel ?: profile.focalLength.label(byRoute.getValue(profile.routeId)))
}
