package com.unspektrawesome.ui.camera

import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.settings.CameraPreferences
import com.unspektrawesome.settings.LensButtonMapping
import com.unspektrawesome.settings.LensProfiles

/**
 * The lens a button step moves to, in the lens rail's order, as the id to select and its label.
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
    val routeOrder = ordered.mapIndexed { index, camera -> camera.route.routeId to index }.toMap()
    val groups = profiles.groupBy { it.focalGroup }.values.sortedWith(compareBy(
        { choices -> byRoute.getValue(choices.first().routeId).facing.ordinal },
        { choices -> choices.minOf { it.focalLength.equivalent(byRoute.getValue(it.routeId)) ?: Float.MAX_VALUE } },
        { choices -> choices.minOf { routeOrder.getValue(it.routeId) } }))
    val active = profiles.firstOrNull { it.id == preferences.activeLensProfileId }
    val index = groups.indexOfFirst { group -> group.any { it.id == active?.id } }
    val target = groups.getOrNull(if (index < 0) 0 else index + step.coerceIn(-1, 1)) ?: return null
    if (index >= 0 && target === groups[index]) return null
    val profile = target.firstOrNull { it.id == preferences.defaultLensProfileId(preferences.lensProfileMode) } ?: target.first()
    return profile.id to profile.focalLength.label(byRoute.getValue(profile.routeId))
}
