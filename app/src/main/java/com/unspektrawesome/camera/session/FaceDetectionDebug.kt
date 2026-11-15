package com.unspektrawesome.camera.session

import android.graphics.Rect
import android.hardware.camera2.params.Face
import android.util.Log
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.capture.FaceGeometry
import com.unspektrawesome.capture.FrameGeometrySnapshot
import com.unspektrawesome.capture.NormalizedPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * Debug-only view of the camera's own face detection.
 * Enable with `adb shell setprop debug.unspektra.faces 1`, then restart the app.
 */
object FaceDetectionDebug {
    private const val TAG = "FaceDebug"

    /** Face position as fractions of the sensor active array. */
    data class DetectedFace(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val id: Int,
        val score: Int,
        val landmarks: List<Pair<Float, Float>>,
    )

    data class Frame(val mode: Int, val faces: List<DetectedFace>)

    @JvmStatic
    val enabled: Boolean by lazy {
        BuildConfig.DEBUG && readProperty("debug.unspektra.faces") == "1"
    }

    private val mutableFrame = MutableStateFlow(Frame(0, emptyList()))
    val frame: StateFlow<Frame> = mutableFrame

    /** Geometry of the displayed viewfinder, used to place sensor coordinates on screen. */
    @Volatile
    var geometry: FrameGeometrySnapshot? = null

    private var lastLogMs = 0L

    @JvmStatic
    fun publish(mode: Int, faces: Array<Face>?, activeArray: Rect?) {
        if (activeArray == null || activeArray.width() <= 0 || activeArray.height() <= 0) return
        val width = activeArray.width().toFloat()
        val height = activeArray.height().toFloat()
        fun x(value: Int) = (value - activeArray.left) / width
        fun y(value: Int) = (value - activeArray.top) / height
        val detected = faces.orEmpty().map { face ->
            val bounds = face.bounds
            DetectedFace(
                x(bounds.left), y(bounds.top), x(bounds.right), y(bounds.bottom),
                face.id, face.score,
                listOfNotNull(face.leftEyePosition, face.rightEyePosition, face.mouthPosition)
                    .map { x(it.x) to y(it.y) },
            )
        }
        mutableFrame.value = Frame(mode, detected)
        val now = System.currentTimeMillis()
        if (now - lastLogMs >= 1000L) {
            lastLogMs = now
            Log.i(TAG, "mode=$mode faces=${detected.size} " + detected.joinToString(" ") {
                "[id=${it.id} score=${it.score} landmarks=${it.landmarks.size}]"
            })
        }
    }

    @JvmStatic
    fun clear() {
        mutableFrame.value = Frame(0, emptyList())
    }

    fun toSurface(geometry: FrameGeometrySnapshot, sensorX: Float, sensorY: Float): NormalizedPoint =
        FaceGeometry.toSurface(geometry, sensorX, sensorY)

    private fun readProperty(name: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name).start()
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else process.inputStream.bufferedReader().use { it.readText().trim() }
        } finally {
            process.destroy()
        }
    }.getOrNull()
}
