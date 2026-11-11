package com.unspektrawesome.camera.discovery

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.camera.CameraDescriptor
import java.io.File

class LensDiscoveryStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "auto-lens-discovery.json"))

    fun load(environment: String): LensDiscoveryReport? = runCatching {
        LensDiscoveryReport.decode(file.openRead().bufferedReader().use { it.readText() }, environment)
    }.getOrNull()

    fun save(report: LensDiscoveryReport) {
        val stream = file.startWrite()
        try {
            stream.write(report.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object {
        fun environment(context: Context, cameras: List<CameraDescriptor>): String {
            val inventory = cameras.sortedBy { it.route.routeId }.joinToString("\n") { camera ->
                listOf(camera.route.routeId, camera.route.kind, camera.route.openCameraId,
                    camera.route.characteristicsCameraId, camera.route.physicalCameraId,
                    camera.facing, camera.focalLengthMm, camera.equivalentFocalLengthMm,
                    camera.sensorWidthMm, camera.sensorHeightMm, camera.issues.sortedBy { it.name },
                    camera.rawOutputs.map { "${it.format}:${it.size}:${it.maximumResolutionMode}:${it.minimumFrameDurationNs}" }.sorted(),
                    camera.aeFpsRanges.map { "${it.lower}:${it.upper}" }.sorted(),
                    camera.sensorMetadata?.let { sensor ->
                        val calibration = sensor.colorCalibration
                        listOf(sensor.toString(), sensor.orientationDegrees, sensor.pixelArray, sensor.maximumResolutionPixelArray,
                            listOf(sensor.activeArray, sensor.rawActiveArray, sensor.maximumResolutionActiveArray,
                                sensor.maximumResolutionRawActiveArray).map { rect -> rect?.let { listOf(it.left, it.top, it.right, it.bottom) } },
                            calibration.illuminant1, calibration.illuminant2,
                            listOf(calibration.colorMatrix1(), calibration.colorMatrix2(), calibration.forwardMatrix1(),
                                calibration.forwardMatrix2(), calibration.calibrationMatrix1(), calibration.calibrationMatrix2())
                                .map { it?.toList() })
                    }).joinToString("|")
            }
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            return discoveryHash(listOf(LensDiscoveryReport.SCHEMA, BuildConfig.LENS_DISCOVERY_BUILD,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, context.packageName, installed.lastUpdateTime,
                Build.FINGERPRINT, Build.VERSION.INCREMENTAL, Build.VERSION.SECURITY_PATCH,
                Build.VERSION.SDK_INT, inventory).joinToString("\n"))
        }
    }
}
