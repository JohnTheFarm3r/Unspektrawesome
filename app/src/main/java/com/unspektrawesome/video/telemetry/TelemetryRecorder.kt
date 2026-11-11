package com.unspektrawesome.video.telemetry

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.unspektrawesome.camera.session.RawCaptureMetadata
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.telemetry.proto.Gyroflow as G
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class TelemetryRecorder(context: Context, private val camera: TelemetryCamera) : AutoCloseable {
    private sealed interface Event
    private data class Motion(val time: Long, val x: Float, val y: Float, val z: Float, val accuracy: Int) : Event
    private data class Capture(val pts: Long, val imageTime: Long, val data: RawCaptureMetadata,
        val correction: com.unspektrawesome.video.GyroStabilizer.Correction?) : Event
    private data class Encoded(val inputPts: Long, val pts: Long) : Event
    private data class Start(val time: Long) : Event
    private val queue = ArrayBlockingQueue<Event>(4096)
    private val overflow = AtomicLong()
    private val droppedMotion = AtomicLong()
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val callbacks = HandlerThread("VideoGyroscope")
    private lateinit var handler: Handler
    private val drained = CountDownLatch(1)
    private val armedAt = SystemClock.elapsedRealtimeNanos()
    private val source = camera.header()
    private val path = File.createTempFile("video-motion-", ".sqlite", context.cacheDir)
    private val database: SQLiteDatabase
    @Volatile private var finishing = false
    @Volatile private var closed = false
    @Volatile private var error: Throwable? = null
    private var registered = false
    private var registrationFailure: String? = null
    private var originNs = 0L
    private var duplicate = 0L
    private var outOfOrder = 0L
    private var invalid = 0L
    private var saturated = 0L
    private var previousNs = 0L
    private var count = 0L
    private var firstNs = 0L
    private var lastNs = 0L
    private val captureIssues = linkedSetOf<String>()
    private val oisStates = linkedSetOf<String>()
    private var firstPose: FloatArray? = null
    private var coverageStartUs = Double.POSITIVE_INFINITY
    private var coverageEndUs = Double.NEGATIVE_INFINITY
    var issues: Set<String> = emptySet()
        private set
    var measuredRate = 0.0
        private set
    val readyToStart get() = SystemClock.elapsedRealtimeNanos() - armedAt >= 500_000_000L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            offer(Motion(event.timestamp, event.values[0], event.values[1], event.values[2], event.accuracy))
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        database = try { SQLiteDatabase.openOrCreateDatabase(path, null) }
        catch (failure: Throwable) { path.delete(); throw failure }
        try {
        callbacks.start()
        handler = Handler(callbacks.looper)
        database.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
        database.execSQL("PRAGMA synchronous=OFF")
        database.execSQL("PRAGMA cache_size=-2048")
        database.execSQL("CREATE TABLE gyro(id INTEGER PRIMARY KEY,t INTEGER,dt INTEGER,packet BLOB,json TEXT)")
        database.execSQL("CREATE INDEX gyro_interval ON gyro(dt)")
        database.execSQL("CREATE TABLE captures(input INTEGER PRIMARY KEY,json TEXT,packet BLOB,sensorTime INTEGER,exposureStart REAL,exposureEnd REAL)")
        database.execSQL("CREATE TABLE encoded(sequence INTEGER PRIMARY KEY,input INTEGER UNIQUE,pts INTEGER UNIQUE,containerTicks INTEGER)")
        registered = try { sensor != null && manager.registerListener(listener, sensor, 2500, 0, handler) }
        catch (failure: RuntimeException) { registrationFailure = failure.javaClass.simpleName; false }
        source.put("sensor", sensor?.let { JSONObject().put("name", it.name).put("vendor", it.vendor)
            .put("type", it.type).put("calibration", "Android bias-compensated")
            .put("resolution", it.resolution).put("maximumRange", it.maximumRange).put("minDelayUs", it.minDelay)
            .put("requestedPeriodUs", 2500).put("maxReportLatencyUs", 0).put("registered", registered)
            .put("registrationFailure", registrationFailure ?: JSONObject.NULL) } ?: JSONObject.NULL)
        Thread({ writeEvents() }, "VideoTelemetryWriter").start()
        } catch (failure: Throwable) {
            manager.unregisterListener(listener)
            callbacks.quitSafely()
            database.close()
            path.delete()
            throw failure
        }
    }

    private fun offer(event: Event) {
        if (!closed && !queue.offer(event)) {
            overflow.incrementAndGet()
            if (event is Motion) droppedMotion.incrementAndGet()
        }
    }

    fun start() = offer(Start(SystemClock.elapsedRealtimeNanos()))
    fun submitted(inputTimestampNs: Long, imageTimestampNs: Long, capture: RawCaptureMetadata,
        correction: com.unspektrawesome.video.GyroStabilizer.Correction? = null) =
        offer(Capture(inputTimestampNs / 1000, imageTimestampNs, capture, correction))
    fun encoded(inputPtsUs: Long, presentationUs: Long) = offer(Encoded(inputPtsUs, presentationUs))

    private fun writeEvents() {
        try {
            database.compileStatement("INSERT INTO gyro(t,dt,packet,json) VALUES(?,?,?,?)").use { gyro ->
                val preRoll = ArrayDeque<Motion>()
                var recording = false
                fun save(e: Motion) {
                    if (count == 0L) { if (originNs == 0L) originNs = e.time; firstNs = e.time }
                    val delta = if (count == 0L) 0 else e.time - previousNs
                    if (count > 0) { if (delta == 0L) duplicate++; if (delta < 0) outOfOrder++ }
                    if (!e.x.isFinite() || !e.y.isFinite() || !e.z.isFinite()) invalid++
                    val limit = sensor?.maximumRange ?: Float.MAX_VALUE
                    if (maxOf(kotlin.math.abs(e.x), kotlin.math.abs(e.y), kotlin.math.abs(e.z)) >= limit * .995f) saturated++
                    gyro.bindLong(1, e.time); gyro.bindLong(2, delta)
                    // Baked stabilization keeps raw measurements only in the provenance track.
                    gyro.bindBlob(3, if (camera.liveStabilization) ByteArray(0) else
                        TelemetryPackets.motion((e.time - originNs) / 1000.0, e.x, e.y, e.z))
                    gyro.bindString(4, "[${e.time},${e.x.toRawBits()},${e.y.toRawBits()},${e.z.toRawBits()},${e.accuracy}]")
                    gyro.executeInsert()
                    previousNs = e.time; lastNs = e.time; count++
                }
                fun trim(time: Long) {
                    while (preRoll.size > 1 && preRoll[1].time < time - 500_000_000L) preRoll.removeFirst()
                    while (preRoll.size > 2048) { preRoll.removeFirst(); droppedMotion.incrementAndGet() }
                }
                database.compileStatement("INSERT INTO captures VALUES(?,?,?,?,?,?)").use { capture ->
                    database.compileStatement("INSERT INTO encoded(input,pts) VALUES(?,?)").use { encoded ->
                        while (!finishing || queue.isNotEmpty()) {
                            val first = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                            database.beginTransaction()
                            try {
                                var event: Event? = first
                                var batch = 0
                                while (event != null) {
                                    when (val e = checkNotNull(event)) {
                                        is Motion -> {
                                            if (recording) save(e) else { preRoll.addLast(e); trim(e.time) }
                                        }
                                        is Start -> {
                                            trim(e.time)
                                            preRoll.forEach(::save)
                                            preRoll.clear()
                                            if (originNs == 0L) originNs = e.time
                                            recording = true
                                            source.put("recordingStartedRealtimeNs", e.time)
                                        }
                                        is Capture -> {
                                            val frame = camera.frame(e.data, e.imageTime)
                                            e.correction?.let { correction ->
                                                val applied = JSONObject()
                                                    .put("sourceFromOutputSensorUvRows", JSONArray(correction.rows.toList()))
                                                    .put("rowStart", correction.rowStart).put("rowSpan", correction.rowSpan)
                                                    .put("correctionScale", correction.scale)
                                                correction.distortion?.let { lens ->
                                                    applied.put("distortionModel", "android-brown-conrady")
                                                        .put("sensorUvIntrinsics", JSONArray(lens.intrinsics.toList()))
                                                        .put("androidDistortion", JSONArray(lens.coefficients.toList()))
                                                }
                                                frame.put("liveStabilization", applied)
                                            }
                                            val packet = frameMessage(frame, 0).build()
                                            capture.bindLong(1, e.pts); capture.bindString(2, frame.toString())
                                            capture.bindBlob(3, packet.toByteArray()); capture.bindLong(4, e.data.sensorTimestampNs)
                                            if (packet.hasExposureTimeUs()) {
                                                capture.bindDouble(5, packet.startTimestampUs - packet.exposureTimeUs * .5)
                                                capture.bindDouble(6, packet.endTimestampUs - packet.exposureTimeUs * .5)
                                            } else { capture.bindNull(5); capture.bindNull(6) }
                                            capture.executeInsert()
                                        }
                                        is Encoded -> {
                                            encoded.bindLong(1, e.inputPts); encoded.bindLong(2, e.pts); encoded.executeInsert()
                                            database.rawQuery("SELECT json,exposureStart,exposureEnd FROM captures WHERE input=?",
                                                arrayOf(e.inputPts.toString())).use { frame ->
                                                if (frame.moveToFirst()) {
                                                    validateCapture(JSONObject(frame.getString(0)))
                                                    if (!frame.isNull(1)) {
                                                        coverageStartUs = minOf(coverageStartUs, frame.getDouble(1))
                                                        coverageEndUs = maxOf(coverageEndUs, frame.getDouble(2))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    event = if (++batch < 512) queue.poll() else null
                                }
                                database.setTransactionSuccessful()
                            } finally { database.endTransaction() }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            error = failure
            InternalLogRecorder.error("VideoTelemetry", "Telemetry spool failed", failure)
        } finally { drained.countDown() }
    }

    private fun validateCapture(frame: JSONObject) {
        val reasons = frame.getJSONArray("issues")
        for (j in 0 until reasons.length()) captureIssues += reasons.getString(j)
        oisStates += frame.getString("ois")
        val pose = frame.optJSONArray("lensPoseXyzw")?.let { FloatArray(4) { index -> it.getDouble(index).toFloat() } }
        val previous = firstPose
        if (previous == null) firstPose = pose
        else if (pose == null || !TelemetryCalibration.sameRotation(previous, pose))
            captureIssues += "camera-imu-calibration-changed"
    }

    private fun stopSensors(postRoll: Boolean) {
        if (finishing) return
        val stopped = CountDownLatch(1)
        handler.postDelayed({
            manager.unregisterListener(listener)
            finishing = true
            callbacks.quitSafely()
            stopped.countDown()
        }, if (postRoll) 500 else 0)
        check(stopped.await(3, TimeUnit.SECONDS))
        check(drained.await(15, TimeUnit.SECONDS))
    }

    private fun scalar(sql: String): Long = database.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    private fun diagnostics(): JSONObject {
        val intervals = scalar("SELECT COUNT(*) FROM gyro WHERE dt>0")
        fun percentile(f: Double): Long? = if (intervals == 0L) null else scalar(
            "SELECT dt FROM gyro WHERE dt>0 ORDER BY dt LIMIT 1 OFFSET ${(f * (intervals - 1)).toLong()}")
        val mean = if (intervals == 0L) 0.0 else scalar("SELECT SUM(dt) FROM gyro WHERE dt>0").toDouble() / intervals
        measuredRate = if (mean > 0) 1e9 / mean else 0.0
        return JSONObject().put("sampleCount", count).put("firstTimestampNs", firstNs).put("lastTimestampNs", lastNs)
            .put("meanIntervalNs", mean).put("medianIntervalNs", percentile(.5) ?: JSONObject.NULL)
            .put("p95IntervalNs", percentile(.95) ?: JSONObject.NULL).put("p99IntervalNs", percentile(.99) ?: JSONObject.NULL)
            .put("largestGapNs", percentile(1.0) ?: JSONObject.NULL).put("deliveredRateHz", measuredRate)
            .put("duplicateEvents", duplicate).put("outOfOrderEvents", outOfOrder).put("queueOverflow", overflow.get())
            .put("droppedSamples", droppedMotion.get()).put("invalidMeasurements", invalid).put("suspectedSaturation", saturated)
            .put("submittedCaptures", scalar("SELECT COUNT(*) FROM captures"))
            .put("encodedFrames", scalar("SELECT COUNT(*) FROM encoded"))
    }

    fun embed(input: FileChannel, output: FileChannel, durationUs: Long) {
        var stageMs = SystemClock.elapsedRealtime()
        val timings = linkedMapOf<String, Long>()
        fun completed(stage: String) {
            val now = SystemClock.elapsedRealtime()
            timings[stage] = now - stageMs
            stageMs = now
        }
        stopSensors(true)
        completed("drain")
        error?.let { throw it }
        require(scalar("SELECT COUNT(*) FROM encoded WHERE input NOT IN (SELECT input FROM captures)") == 0L)
        val timeline = TelemetryMp4.videoTimeline(input)
        require(scalar("SELECT COUNT(*) FROM encoded") == timeline.presentationTicks.size.toLong())
        database.beginTransaction()
        try {
            database.execSQL("UPDATE encoded SET pts=NULL")
            database.compileStatement("UPDATE encoded SET pts=?,containerTicks=? WHERE sequence=?").use { update ->
                for ((index, ticks) in timeline.presentationTicks.withIndex()) {
                    update.bindLong(1, timeline.microseconds(ticks)); update.bindLong(2, ticks)
                    update.bindLong(3, index + 1L); check(update.executeUpdateDelete() == 1)
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        source.put("videoTimeBase", "1/${timeline.timescale}")
        completed("timeline")
        val diagnostics = diagnostics()
        completed("diagnostics")
        val allIssues = LinkedHashSet(captureIssues)
        if (camera.liveStabilization) allIssues += "live-stabilization-already-applied"
        if (!registered) allIssues += "gyro-unavailable"
        if (count < 2 || overflow.get() > 0 || droppedMotion.get() > 0 || invalid > 0 || duplicate > 0 || outOfOrder > 0) allIssues += "gyro-stream-incomplete"
        if (diagnostics.optLong("largestGapNs") > 100_000_000) allIssues += "gyro-gap-over-100ms"
        if (saturated > 0) allIssues += "gyro-saturation"
        fun incompleteCoverage(startUs: Double, endUs: Double) = count < 2 ||
            startUs < (firstNs - originNs) / 1000.0 || endUs > (lastNs - originNs) / 1000.0
        if (coverageStartUs.isFinite() && incompleteCoverage(coverageStartUs, coverageEndUs))
            allIssues += "gyro-coverage-incomplete"
        source.put("originNs", originNs).put("diagnostics", diagnostics).put("issues", JSONArray(allIssues.toList()))
            .put("oisStates", JSONArray(oisStates.toList())).put("automaticStabilizationEligible", allIssues.isEmpty())
        completed("validation")
        val frames = database.rawQuery("SELECT e.pts,c.json,e.containerTicks,e.input,c.packet,c.sensorTime,c.exposureStart,c.exposureEnd " +
            "FROM encoded e JOIN captures c ON c.input=e.input ORDER BY e.pts", null)
        val motions = database.rawQuery("SELECT t,packet,json FROM gyro ORDER BY id", null)
        frames.use { f -> motions.use { g ->
            var hasGyro = g.moveToFirst()
            val total = f.count
            var index = 0
            val samples = sequence {
                var hasFrame = f.moveToFirst()
                while (hasFrame) {
                    val pts = f.getLong(0)
                    var frame = f.getString(1)
                    val ticks = f.getLong(2)
                    val inputPts = f.getLong(3)
                    val packet = f.getBlob(4)
                    if (!f.isNull(6) && incompleteCoverage(f.getDouble(6), f.getDouble(7))) {
                        frame = JSONObject(frame).apply { getJSONArray("issues").put("gyro-coverage-incomplete") }.toString()
                    }
                    hasFrame = f.moveToNext()
                    val nextTime = if (hasFrame) f.getLong(5) else Long.MAX_VALUE
                    val imu = ByteArrayOutputStream()
                    val original = StringBuilder()
                    var samples = 0
                    while (hasGyro && g.getLong(0) < nextTime) {
                        require(samples < 100_000) { "Gyro interval is too large" }
                        if (samples++ > 0) original.append(',')
                        original.append(g.getString(2))
                        imu.write(g.getBlob(1))
                        hasGyro = g.moveToNext()
                    }
                    index++
                    val main = TelemetryPackets.main(packet, index, imu.toByteArray(),
                        if (index == 1) header(durationUs, JSONObject(frame)) else null)
                    val extension = TelemetryPackets.extension(frame, pts, ticks, inputPts, original,
                        header = if (index == 1) source.toString() else null,
                        diagnostics = if (index == total) diagnostics.toString() else null,
                        issues = if (index == total) JSONArray(allIssues.toList()).toString() else null)
                    yield(TelemetryMp4.Sample(pts, main, extension))
                }
            }
            TelemetryMp4.append(input, output, samples, total, durationUs)
        } }
        completed("packets")
        issues = allIssues
        InternalLogRecorder.event("VideoTelemetry", "rate=$measuredRate samples=$count issues=${issues.joinToString()} stagesMs=$timings")
    }

    private fun frameMessage(frame: JSONObject, number: Int): G.FrameMetadata.Builder {
        val result = G.FrameMetadata.newBuilder().setFrameNumber(number)
        if (!frame.isNull("exposureNs")) result.exposureTimeUs = frame.getLong("exposureNs") / 1000.0
        if (!frame.isNull("iso")) result.iso = frame.getInt("iso")
        val geometry = camera.geometry
        if (geometry != null && frame.optLong("exposureNs") > 0 && !frame.isNull("skewNs") && frame.getLong("skewNs") >= 0) {
            val times = geometry.readoutUs(frame.getLong("sensorTimestampNs"), originNs, frame.getLong("exposureNs"), frame.getLong("skewNs"))
            result.startTimestampUs = times[0]; result.endTimestampUs = times[1]
        }
        if (geometry != null) {
            val crop = geometry.sensorCrop()
            result.cropX = crop[0].toFloat(); result.cropY = crop[1].toFloat()
            result.cropWidth = crop[2].toFloat(); result.cropHeight = crop[3].toFloat()
        }
        frame.optJSONArray("outputIntrinsics")?.let { k ->
            val lens = G.LensData.newBuilder().addAllCameraIntrinsicMatrix((0 until k.length()).map { k.getDouble(it).toFloat() })
            frame.optJSONArray("androidDistortion")?.takeIf { it.length() == 5 }?.let { d ->
                lens.setOpencvStandard(G.OpenCVStandard.newBuilder().addAllCoefficients(listOf(0, 1, 3, 4, 2).map { d.getDouble(it).toFloat() }))
            }
            if (!frame.isNull("focalLengthMm")) lens.focalLengthMm = frame.getDouble("focalLengthMm").toFloat()
            if (!frame.isNull("focusDiopters") && frame.getDouble("focusDiopters") > 0)
                lens.focusDistanceMm = (1000 / frame.getDouble("focusDiopters")).toFloat()
            result.addLens(lens)
        }
        return result
    }

    private fun header(durationUs: Long, frame: JSONObject): G.Header {
        val cameraMetadata = G.Header.CameraMetadata.newBuilder().setCameraBrand(android.os.Build.MANUFACTURER)
            .setCameraModel(android.os.Build.MODEL).setImuOrientation("XYZ")
            .setAdditionalData(source.toString())
        val pose = frame.optJSONArray("lensPoseXyzw")
        if (!camera.liveStabilization && camera.poseReference in listOf(0, 1) && pose?.length() == 4) cameraMetadata.setImuRotation(
            G.Quaternion.newBuilder().setX(pose.getDouble(0).toFloat()).setY(pose.getDouble(1).toFloat())
                .setZ(pose.getDouble(2).toFloat()).setW(pose.getDouble(3).toFloat()))
        val geometry = camera.geometry
        if (geometry != null) {
            cameraMetadata.sensorPixelWidth = geometry.sensorWidth; cameraMetadata.sensorPixelHeight = geometry.sensorHeight
            if (!source.isNull("physicalWidthMm")) cameraMetadata.pixelPitchXNm = (source.getDouble("physicalWidthMm") * 1e6 / geometry.sensorWidth).toInt()
            if (!source.isNull("physicalHeightMm")) cameraMetadata.pixelPitchYNm = (source.getDouble("physicalHeightMm") * 1e6 / geometry.sensorHeight).toInt()
        }
        val clip = G.Header.ClipMetadata.newBuilder().setFrameWidth(geometry?.width ?: 0).setFrameHeight(geometry?.height ?: 0)
            .setDurationUs(durationUs.toDouble()).setPixelAspectRatio(1f).setRotationDegrees(camera.rotation)
            .setImuSampleRate(if (camera.liveStabilization) 0 else measuredRate.toInt()).setFrameReadoutDirectionValue(geometry?.direction ?: 0)
        val firstFrame = frameMessage(frame, 1)
        clip.frameReadoutTimeUs = firstFrame.endTimestampUs - firstFrame.startTimestampUs
        if (frame.optLong("durationNs") > 0) clip.sensorFrameRate = (1e9 / frame.getLong("durationNs")).toFloat()
        if (durationUs > 0) clip.fileFrameRate = (scalar("SELECT COUNT(*) FROM encoded") * 1e6 / durationUs).toFloat()
        return G.Header.newBuilder().setCamera(cameraMetadata).setClip(clip).build()
    }

    override fun close() {
        if (closed) return
        try { stopSensors(false) }
        finally {
            closed = true
            manager.unregisterListener(listener)
            finishing = true
            callbacks.quitSafely()
            database.close()
            path.delete()
        }
    }
}
