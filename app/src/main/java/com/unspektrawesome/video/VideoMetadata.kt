package com.unspektrawesome.video

import com.unspektrawesome.capture.CaptureIdentity
import com.unspektrawesome.capture.CaptureMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class VideoMetadata(
    private val configuration: JSONObject,
    identity: () -> CaptureIdentity = { CaptureIdentity.current },
) {
    private class Change(val timeUs: Long, capture: () -> CaptureMetadata) {
        val capture by lazy(capture)
    }

    private val identity by lazy(identity)
    private val changes = mutableListOf<Change>()
    private var lastRevision: Long? = null

    private fun visibleChanges(timeOffsetUs: Long) = changes.filterIndexed { index, _ ->
        index == changes.lastIndex || changes[index + 1].timeUs > timeOffsetUs
    }

    @Synchronized
    fun frame(timeUs: Long, revision: Long, metadata: () -> CaptureMetadata) {
        if (lastRevision == revision) return
        // Keep the frame's immutable settings and build metadata when saving the finished recording.
        changes.add(Change(timeUs.coerceAtLeast(0), metadata))
        lastRevision = revision
    }

    @Synchronized
    fun complete(droppedFrames: Long) {
        require(droppedFrames >= 0)
        configuration.put("droppedFrames", droppedFrames)
    }

    @Synchronized
    fun json(timeOffsetUs: Long = 0): String = JSONObject().put("schema", 1).put("identity", identity.toJson())
        .put("recording", configuration).put("settingsTimeline", JSONArray().apply {
            visibleChanges(timeOffsetUs).forEach { change ->
                put(JSONObject().put("timeUs", (change.timeUs - timeOffsetUs).coerceAtLeast(0))
                    .put("capture", change.capture.toJson()))
            }
        }).toString()

    @Synchronized
    fun description(timeOffsetUs: Long = 0): String = visibleChanges(timeOffsetUs).let { visible ->
        buildList {
            add(identity.software)
            visible.firstOrNull()?.capture?.description()?.let(::add)
            if (configuration.has("droppedFrames")) add("Dropped frames: ${configuration.getLong("droppedFrames")}")
            configuration.optJSONObject("liveStabilization")?.let { stabilization ->
                val enabled = stabilization.optBoolean("enabled")
                add("Live gyro stabilization: ${if (enabled) "On" else "Off"}")
                if (enabled) {
                    fun amount(key: String, label: String, unit: String, scale: Double = 1.0) {
                        stabilization.optDouble(key).takeIf { it.isFinite() }?.let {
                            val value = String.format(Locale.ROOT, "%.2f", it * scale).trimEnd('0').trimEnd('.')
                            add("$label: $value$unit")
                        }
                    }
                    amount("cropRatio", "Stabilization crop", "x")
                    amount("strength", "Stabilization strength", "%", 100.0)
                    amount("smoothingSeconds", "Stabilization smoothing", " s")
                }
            }
            add("Settings revisions: ${visible.size}")
        }.joinToString("; ")
    }
}
