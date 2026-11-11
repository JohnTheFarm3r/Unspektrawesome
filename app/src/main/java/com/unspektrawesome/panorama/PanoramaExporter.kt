package com.unspektrawesome.panorama

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.unspektrawesome.burst.BurstRendering
import com.unspektrawesome.capture.JpegMediaStoreWriter
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

internal class PanoramaExportPaused : Exception()

internal class PanoramaExporter(private val context: Context) {
    fun export(uri: Uri, writer: JpegMediaStoreWriter, checkpoint: () -> Unit, stage: (Int, Int) -> Unit): Uri {
        val project = PanoramaProject.read(context, uri)
        require(project.frames.size >= 2)
        val bounds = project.bounds
        val transforms = project.outputTransforms
        val bytes = bounds.width.toLong() * bounds.height * 4
        require(bytes in 1..8_000_000_000L)
        val canvas = File(project.directory, "export.rgba")
        val output = File(project.directory, "export.png")
        canvas.delete(); output.delete()
        require(StatFs(context.filesDir.absolutePath).availableBytes > bytes * 3 + 256L * 1024 * 1024) {
            "Not enough storage to export this panorama. RAWs are kept."
        }
        try {
            RandomAccessFile(canvas, "rw").use { it.setLength(0); it.setLength(bytes) }
            BurstRendering(context).use { rendering ->
                var previous = PanoramaTransform()
                project.frames.forEachIndexed { index, frame ->
                    checkpoint(); stage(index + 1, project.frames.size)
                    val file = File(project.directory, frame.file)
                    require(file.length() in 16..300L * 1024 * 1024)
                    val bitmap = rendering.render(frame.capture, file.readBytes(), false)
                    try {
                        com.unspektrawesome.diagnostics.ImageDiagnostics.output(project.id, bitmap)
                        val transform = transforms[index]
                        val (dx, dy) = if (transform.plane) {
                            val currentCenter = transform.point(project.analysisWidth * .5, project.analysisHeight * .5)
                            val previousCenter = previous.point(project.analysisWidth * .5, project.analysisHeight * .5)
                            currentCenter.first - previousCenter.first to currentCenter.second - previousCenter.second
                        } else transform.x - previous.x to transform.y - previous.y
                        val direction = if (index == 0) 0 else if (abs(dx) >= abs(dy)) { if (dx >= 0) 1 else -1 } else { if (dy >= 0) 2 else -2 }
                        PanoramaNative.compose(bitmap, canvas.absolutePath, bounds.width, bounds.height, project.focal * project.scale,
                            transform.scaled(project.scale).values(), bounds.left, bounds.top, direction)
                        previous = transform
                    } finally { bitmap.recycle() }
                }
            }
            checkpoint()
            val crop = PanoramaNative.crop(canvas.absolutePath, bounds.width, bounds.height)
            PanoramaPng.write(canvas, bounds.width, crop, output, checkpoint)
            checkpoint()
            return writer.writePng(output, project.capture.metadata, project.capture.capturedAtMillis, project.id)
        } finally { canvas.delete(); output.delete() }
    }
}
