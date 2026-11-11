package com.unspektrawesome.settings

import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.camera.LensFacing
import com.unspektrawesome.camera.ShootingLensPolicy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object LensButtonMapping {
    fun enabledCameras(
        cameras: List<CameraDescriptor>,
        preferences: CameraPreferences,
    ): List<CameraDescriptor> {
        val usable = cameras.filter(CameraDescriptor::isUsable)
        if (preferences.lensProfiles.isNotEmpty()) {
            val visible = LensProfiles.visible(preferences, cameras)
            return opticalOrder(visible.distinctBy { it.routeId }.mapNotNull { profile -> usable.firstOrNull { it.route.routeId == profile.routeId }?.let { camera ->
                val reference = usable.filter { it.facing == camera.facing && it.equivalentFocalLengthMm != null }
                    .minWithOrNull(compareBy({ abs(it.equivalentFocalLengthMm - if (it.facing == LensFacing.BACK) 26f else 24f) },
                        { it.route.routeId }))?.equivalentFocalLengthMm
                val zoom = camera.relativeZoom ?: camera.equivalentFocalLengthMm?.let { focal ->
                    reference?.takeIf { it > 0f }?.let { (focal / it * 10).roundToInt() / 10f }
                }
                camera.withLensIdentity(profile.verification?.configuration?.role ?: camera.lensRole, zoom)
            } }.distinctBy { it.route.routeId })
        }
        if (!preferences.lensSelectionConfigured) {
            return ShootingLensPolicy.canonicalCameras(cameras)
        }
        return usable.filter { it.route.routeId in preferences.enabledLensIds }
    }

    fun selectableCameras(
        cameras: List<CameraDescriptor>,
        preferences: CameraPreferences,
    ): List<CameraDescriptor> = if (preferences.lensSelectionConfigured) {
        enabledCameras(cameras, preferences)
    } else {
        cameras.filter(CameraDescriptor::isUsable)
    }

    fun resolvedRouteIds(
        cameras: List<CameraDescriptor>,
        preferences: CameraPreferences,
    ): List<String> {
        val enabled = enabledCameras(cameras, preferences)
        val defaults = enabled.map { it.route.routeId }
        if (preferences.lensProfiles.isNotEmpty()) return defaults
        if (!preferences.lensButtonMappingConfigured) return defaults

        val usableById = selectableCameras(cameras, preferences)
            .associateBy { it.route.routeId }
        val targetCount = defaults.size
        if (targetCount == 0) return emptyList()
        val resolved = LinkedHashSet<String>(targetCount)
        preferences.lensButtonRouteIds.forEach { routeId ->
            if (routeId in usableById && resolved.size < targetCount) resolved += routeId
        }
        defaults.forEach { routeId ->
            if (resolved.size < targetCount) resolved += routeId
        }
        return resolved.take(targetCount)
    }

    fun opticalOrder(cameras: List<CameraDescriptor>): List<CameraDescriptor> = cameras.sortedWith(compareBy(
        { it.facing.ordinal }, { it.equivalentFocalLengthMm ?: Float.MAX_VALUE },
        { it.relativeZoom ?: Float.MAX_VALUE }, { it.route.routeId }))

    fun zoomLabel(camera: CameraDescriptor): String = camera.relativeZoom?.takeIf { it.isFinite() && it > 0f }?.let {
        if (it == it.roundToInt().toFloat()) "${it.roundToInt()}×" else String.format(Locale.ROOT, "%.1f×", it)
    } ?: "Lens"

    fun resolvedCameras(
        cameras: List<CameraDescriptor>,
        preferences: CameraPreferences,
    ): List<CameraDescriptor> {
        val byId = enabledCameras(cameras, preferences).associateBy { it.route.routeId }
        return resolvedRouteIds(cameras, preferences).mapNotNull(byId::get)
    }

    fun assign(
        cameras: List<CameraDescriptor>,
        preferences: CameraPreferences,
        buttonIndex: Int,
        routeId: String,
    ): CameraPreferences {
        val usableIds = selectableCameras(cameras, preferences)
            .map { it.route.routeId }
            .toSet()
        require(routeId in usableIds) { "Lens button route is not a usable discovered camera" }
        val routes = resolvedRouteIds(cameras, preferences).toMutableList()
        require(buttonIndex in routes.indices) { "Lens button index is out of range" }
        val previousIndex = routes.indexOf(routeId)
        if (previousIndex >= 0 && previousIndex != buttonIndex) {
            routes[previousIndex] = routes[buttonIndex]
        }
        routes[buttonIndex] = routeId
        return preferences.copy(
            lensButtonRouteIds = routes,
            lensButtonMappingConfigured = true,
        )
    }
}
