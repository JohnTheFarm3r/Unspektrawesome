package com.unspektrawesome.panorama

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import com.unspektrawesome.preview.PersistedCapture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class PanoramaFrame(val file: String, val capture: PersistedCapture, val transform: PanoramaTransform)

internal class PanoramaProject private constructor(val directory: File, val id: String, val analysisWidth: Int,
    val analysisHeight: Int, val focal: Double, val frames: MutableList<PanoramaFrame>, var complete: Boolean, val grid: Boolean = false,
    val projection: PanoramaProjection = PanoramaProjection.CYLINDRICAL) {
    val manifest get() = File(directory, "panorama.json")
    val thumbnail get() = File(directory, PREVIEW_FILE)
    val bytes get() = directory.listFiles()?.filter { it.extension == "raw" || it.name in setOf("panorama.json", "preview.png", PREVIEW_FILE, "queue.json") }?.sumOf { it.length() } ?: 0L
    val capture get() = frames.first().capture
    val scale get() = capture.width.toDouble() / analysisWidth
    val outputTransforms get() = if (projection == PanoramaProjection.PLANE)
        panoramaPlaneLayout(frames.map { it.transform }, analysisWidth, analysisHeight, focal).also { require(it.fits) }.transforms
        else frames.map { it.transform }
    val bounds get() = outputTransforms.map { PanoramaBounds.frame(capture.width, capture.height, it.scaled(scale), focal * scale) }
        .reduce(PanoramaBounds::union)

    fun checkpoint() {
        val json = JSONObject().put("schema", 7).put("id", id).put("width", analysisWidth).put("height", analysisHeight).put("grid", grid)
            .put("projection", projection.name)
            .put("focal", focal).put("complete", complete).put("capture", capture.toJson()).put("frames", JSONArray(frames.map { frame ->
                val raw = ByteArray(frame.capture.raw.remaining()).also { frame.capture.raw.duplicate().get(it) }
                JSONObject().put("file", frame.file).put("raw", java.util.Base64.getEncoder().encodeToString(raw)).put("transform", frame.transform.json())
            }))
        val atomic = AtomicFile(manifest)
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    fun preview(bitmap: Bitmap) {
        val atomic = AtomicFile(thumbnail)
        val stream = atomic.startWrite()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    fun ensurePreview(context: Context) {
        if (thumbnail.isFile) return
        com.unspektrawesome.burst.BurstRendering(context).use { rendering ->
            PanoramaMosaic(directory, focal).use { mosaic ->
                var previous = PanoramaTransform()
                frames.forEachIndexed { index, frame ->
                    val encoded = com.unspektrawesome.burst.RawFrameCompression.preview(File(directory, frame.file).readBytes())
                    val rendered = rendering.render(frame.capture, encoded, true)
                    try {
                        val bitmap = Bitmap.createBitmap(analysisWidth, analysisHeight, Bitmap.Config.ARGB_8888)
                        val dx = frame.transform.x - previous.x; val dy = frame.transform.y - previous.y
                        val direction = if (index == 0) 0 else if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) { if (dx >= 0) 1 else -1 } else if (dy >= 0) 2 else -2
                        try {
                            PanoramaNative.resize(rendered, bitmap)
                            if (grid) { preview(bitmap); return }
                            val combined = mosaic.append(bitmap, frame.transform, direction)
                            try { if (index == frames.lastIndex) preview(combined) } finally { combined.recycle() }
                        } finally { bitmap.recycle() }
                        previous = frame.transform
                    } finally { rendered.recycle() }
                }
            }
        }
    }

    companion object {
        const val PREVIEW_FILE = "preview-v13.png"
        fun root(context: Context) = File(context.filesDir, "panoramas").apply { mkdirs() }
        fun create(context: Context, width: Int, height: Int, focal: Double, grid: Boolean = false,
            projection: PanoramaProjection = PanoramaProjection.CYLINDRICAL): PanoramaProject {
            require(!grid || projection == PanoramaProjection.CYLINDRICAL)
            val id = "panorama-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            return PanoramaProject(File(root(context), id).apply { check(mkdir()) }, id, width, height, focal, mutableListOf(), false, grid, projection)
        }
        fun read(context: Context, uri: Uri): PanoramaProject {
            require(uri.scheme == "file")
            val manifest = File(requireNotNull(uri.path)).canonicalFile
            require(manifest.name == "panorama.json" && manifest.parentFile?.parentFile == root(context).canonicalFile)
            require(manifest.length() in 1..64_000_000)
            val json = JSONObject(AtomicFile(manifest).readFully().toString(Charsets.UTF_8))
            val schema = json.getInt("schema")
            require(schema == 7)
            val projection = PanoramaProjection.valueOf(json.getString("projection"))
            val grid = json.optBoolean("grid")
            require(!grid || projection == PanoramaProjection.CYLINDRICAL)
            val width = json.getInt("width"); val height = json.getInt("height"); val focal = json.getDouble("focal")
            require(width in 1..1536 && height in 1..1536 && focal.isFinite() && focal > 1)
            val list = json.getJSONArray("frames")
            require(list.length() in 1..160)
            val shared = PersistedCapture.fromJson(json.getJSONObject("capture"))
            val frames = MutableList(list.length()) { index ->
                val value = list.getJSONObject(index)
                val name = value.getString("file")
                require(name == "$index.raw")
                val capture = run {
                    val bytes = java.util.Base64.getDecoder().decode(value.getString("raw"))
                    require(bytes.size == shared.raw.remaining())
                    shared.copy(raw = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder()).apply { put(bytes); flip() })
                }
                val transform = PanoramaTransform.read(value.getJSONArray("transform"))
                require(transform.plane == (projection == PanoramaProjection.PLANE))
                PanoramaFrame(name, capture, transform)
            }
            if (projection == PanoramaProjection.PLANE) require(panoramaPlaneLayout(frames.map { it.transform }, width, height, focal).fits)
            return PanoramaProject(manifest.parentFile!!, json.getString("id"), width, height, focal, frames, json.optBoolean("complete"), grid, projection)
        }
    }
}
