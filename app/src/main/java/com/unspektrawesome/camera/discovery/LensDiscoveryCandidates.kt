package com.unspektrawesome.camera.discovery

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.unspektrawesome.camera.*
import com.unspektrawesome.camera.IntRange
import com.unspektrawesome.camera.session.Camera2RawSession
import com.unspektrawesome.camera.session.RawSessionConfiguration
import com.unspektrawesome.settings.RawStreamSelection
import com.unspektrawesome.settings.SensorProfileCatalog
import kotlin.math.abs

object LensDiscoveryCandidates {
    fun enumerate(manager: CameraManager, cameras: List<CameraDescriptor>): List<DiscoveredLensConfiguration> =
        enumerate(cameras, manager.cameraIdList.toSet()) { camera ->
            val map = runCatching {
                manager.getCameraCharacteristics(camera.route.characteristicsCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }.getOrNull()
            listOf(ImageFormat.PRIVATE, ImageFormat.YUV_420_888).associateWith { format ->
                runCatching {
                    map?.getOutputSizes(format)?.filter { it.width > 0 && it.height > 0 }
                        ?.map { Size2d(it.width, it.height) }.orEmpty()
                }.getOrDefault(emptyList())
            }
        }

    internal fun enumerate(
        cameras: List<CameraDescriptor>,
        publicIds: Set<String>,
        previewSizes: (CameraDescriptor) -> Map<Int, List<Size2d>>,
    ): List<DiscoveredLensConfiguration> {
        val sensors = SensorProfileCatalog.group(cameras).flatMap { sensor ->
            sensor.paths.map { it.route.routeId to sensor.id.value }
        }.toMap()
        val ordered = cameras.groupBy { sensors[it.route.routeId] ?: it.route.routeId }.values
            .sortedWith(compareBy({ it.first().facing.ordinal },
                { group -> group.minOf { abs((it.equivalentFocalLengthMm ?: 1000f) - 26f) } },
                { group -> group.minOf { it.route.routeId } }))
            .flatMap { group -> group.sortedWith(compareBy<CameraDescriptor>(
                { when {
                    it.route.kind == CameraRoute.Kind.DIRECT -> 0
                    it.route.physicalCameraId != null && it.route.openCameraId in publicIds -> 1
                    it.route.kind == CameraRoute.Kind.HIDDEN_DIRECT -> 2
                    it.route.kind == CameraRoute.Kind.LOGICAL -> 3
                    else -> 4
                } }, { it.route.routeId })) }
        return ordered.flatMap { camera ->
            val sizes = previewSizes(camera)
            val outputs = camera.rawOutputs.sortedWith(compareBy<RawOutput>(
                { it.maximumResolutionMode }, { if (it.maximumFps() >= 24) 0 else 1 },
                { when (it.format) { RawFormat.RAW10 -> 0; RawFormat.RAW_SENSOR -> 1; else -> 2 } },
                { if (it.size.area() <= 20_000_000) 0 else 1 }, { -it.size.area() }, { it.size.width }, { it.size.height }))
            outputs.distinctBy(RawStreamSelection::from).map { output ->
                val preview = listOf(ImageFormat.PRIVATE, ImageFormat.YUV_420_888).firstNotNullOfOrNull { format ->
                    Camera2RawSession.selectCompanionSize(sizes[format].orEmpty(), output.size)?.let { format to it }
                }
                val targetFps = RawPreviewPolicy.TARGET_FRAMES_PER_SECOND
                DiscoveredLensConfiguration(camera.route.routeId, camera.route.openCameraId,
                    camera.route.characteristicsCameraId,
                    camera.route.openCameraId.takeIf { camera.route.physicalCameraId != null ||
                        camera.route.kind in listOf(CameraRoute.Kind.LOGICAL, CameraRoute.Kind.HIDDEN_LOGICAL) },
                    camera.route.physicalCameraId, sensors[camera.route.routeId] ?: "${camera.facing}:${camera.route.characteristicsCameraId}",
                    camera.facing, camera.focalLengthMm, camera.equivalentFocalLengthMm,
                    camera.sensorWidthMm, camera.sensorHeightMm, RawStreamSelection.from(output),
                    RawSessionConfiguration(preview?.first ?: 0, preview?.second,
                        preview != null && camera.route.physicalCameraId != null, IntRange(targetFps, targetFps)))
            }
        }.distinctBy { it.key }.let { candidates ->
            val groups = candidates.groupBy { it.sensorId }.values.toList()
            (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }
        }
    }
}
