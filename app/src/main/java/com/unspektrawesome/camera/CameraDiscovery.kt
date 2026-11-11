package com.unspektrawesome.camera

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.unspektrawesome.diagnostics.InternalLogRecorder
import java.io.File

class CameraDiscovery(private val context: Context) {
    fun discover(): List<CameraDescriptor> {
        val started = System.nanoTime()
        AtomicFile(File(context.cacheDir, "camera-catalog-v1.json")).delete()
        val discovered = CameraCatalog(context).discover()
        discovered.forEach { camera ->
            InternalLogRecorder.event("RawStreamDiscovery", "route=${camera.route.routeId} usable=${camera.isUsable}: " + camera.rawOutputs.joinToString {
                "${it.format} ${it.size.width}x${it.size.height} maximumMode=${it.maximumResolutionMode}"
            })
        }
        Log.i("CameraStartup", "Full discovery: ${discovered.size} routes, ${(System.nanoTime() - started) / 1_000_000} ms")
        return discovered
    }
}
