package com.unspektrawesome.video

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.unspektrawesome.BuildConfig
import com.unspektrawesome.diagnostics.InternalLogRecorder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val VIDEO_FILE_EXTENSION = ".mp4"
private const val QUICKTIME_FILE_EXTENSION = ".mov"
private const val PCM_FILE_EXTENSION = ".pcm"
private const val PCM_DIRECTORY = "recording-audio"
private const val VIDEO_DIRECTORY = "/Unspektrawesome"
private const val AUDIO_SAMPLE_RATE = 48_000
private const val AUDIO_BYTES_PER_FRAME = 2
private const val AUDIO_BUFFER_BYTES = 2048
private const val MAX_PENDING_BYTES = 32 * 1024 * 1024

enum class VideoPhase { IDLE, STARTING, RECORDING, SAVING, ERROR }

data class VideoRecordingState(
    val enabled: Boolean = false,
    val downscale: VideoDownscale = VideoDownscale.FULL,
    val available: List<VideoDownscale> = emptyList(),
    val bitrate: Int = 45_000_000,
    val defaultBitrate: Int = 45_000_000,
    val bitrateRange: VideoBitrateRange? = null,
    val phase: VideoPhase = VideoPhase.IDLE,
    /** Exposure, focus and white balance are held for the current recording. */
    val controlsLocked: Boolean = false,
    val startedAtMs: Long = 0,
    val droppedFrames: Long = 0,
    val lastUri: Uri? = null,
    val message: String? = null,
    val outputSize: com.unspektrawesome.camera.Size2d? = null,
    val portraitCrop: Boolean = false,
    val rawSize: com.unspektrawesome.camera.Size2d? = null,
    val availablePixelCrops: List<VideoPixelCrop> = emptyList(),
    val outputIssue: String? = null,
    val frameRate: Double = 30.0,
    val frameRateOverride: Int? = null,
    val availableFrameRates: List<Int> = emptyList(),
    val captureGyro: Boolean = false,
    val clipCaptureGyro: Boolean? = null,
    val oisEnabled: Boolean = true,
    val photoOisEnabled: Boolean = true,
    val oisSupported: Boolean = false,
    val oisLive: Boolean = true,
    val oisReported: Int? = null,
    val liveStabilization: Boolean = false,
    val chromaDenoise: Boolean = false,
    val chromaDenoiseStrength: Float = 1f,
    val hotPixelRemoval: Boolean = false,
    val stabilizationSettings: LiveStabilizationSettings = LiveStabilizationSettings(),
    val stabilizationLimited: Boolean = false,
) {
    val busy: Boolean get() = phase in setOf(VideoPhase.STARTING, VideoPhase.RECORDING, VideoPhase.SAVING)
    val oisBlockedByGyro: Boolean get() = liveStabilization || captureGyro || (busy && clipCaptureGyro == true)
    val requestedOis: Boolean get() = if (enabled) oisEnabled && !oisBlockedByGyro else photoOisEnabled
    val outputSupported: Boolean get() = downscale in available && outputIssue == null && outputSize != null
    val displayOutputSize: com.unspektrawesome.camera.Size2d? get() = outputSize?.let {
        if (portraitCrop) com.unspektrawesome.camera.Size2d(it.height, it.width) else it
    }
}

/** Encodes the shared preview surface and microphone into one pending MediaStore item. */
class VideoRecording(private val context: Context) : AutoCloseable {
    internal val traceFrames = BuildConfig.DEBUG && Log.isLoggable("VideoFrame", Log.VERBOSE)
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var microphone: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var pcm = false
    private var pcmFile: java.io.File? = null
    private var pcmOutput: java.nio.channels.FileChannel? = null
    private var pcmFrames = 0L
    private var pcmOriginNs: Long? = null
    var audioWritten = true
        private set
    private var descriptor: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null
    private var worker: Thread? = null
    private val finished = CountDownLatch(1)
    private val clock = VideoFrameClock()
    private val timeline = VideoSampleTimeline()
    private var frameCounter = VideoFrameCounter()
    private var encoderPressure = VideoEncoderPressure(30.0)
    @Volatile private var encoderInputCapacity: Int? = null
    @Volatile private var fileSizeBytes: Long? = null
    val droppedFrames: Long get() = frameCounter.droppedFrames
    fun samplePerformance(nowMs: Long, queuedFrames: Int) = VideoPerformance(
        queuedFrames = queuedFrames,
        encoderQueuedFrames = encoderPressure.queuedFrames.toLong(),
        encoderPressurePercent = encoderPressure.sample(System.nanoTime()),
        encoderInputCapacity = encoderInputCapacity,
        encodedFramesPerSecond = frameCounter.sampleFramesPerSecond(nowMs),
        droppedFrames = frameCounter.droppedFrames,
        fileSizeBytes = fileSizeBytes,
    )
    val started: Boolean get() = worker != null
    private var startNs = 0L
    private var stopTimestampNs = 0L
    private var requestedBitrate = 0
    @Volatile private var stopping = false
    @Volatile private var audioStopping = false
    @Volatile private var aborting = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var finalized = false
    private var metadata = VideoMetadata(org.json.JSONObject())
    private var telemetry: com.unspektrawesome.video.telemetry.TelemetryRecorder? = null
    private var captureGyroRequested = false
    @Volatile internal var stabilization: LiveStabilization? = null
        private set
    internal fun stabilize(camera: com.unspektrawesome.video.telemetry.TelemetryCamera,
        geometry: com.unspektrawesome.capture.FrameGeometrySnapshot,
        bounds: com.unspektrawesome.capture.NormalizedRect, settings: LiveStabilizationSettings, historyNs: Long) {
        stabilization = LiveStabilization(context, camera, geometry, bounds, settings, historyNs)
    }
    private var durationUs = 0L
    var telemetryWritten = false
        private set
    val telemetryIssues get() = telemetry?.issues.orEmpty()
    val telemetryReady get() = telemetry?.readyToStart != false
    internal fun captureGyro(camera: com.unspektrawesome.video.telemetry.TelemetryCamera) {
        captureGyroRequested = true
        telemetry = runCatching { com.unspektrawesome.video.telemetry.TelemetryRecorder(context, camera) }
            .onFailure { InternalLogRecorder.error("VideoTelemetry", "Gyroscope could not start", it) }.getOrNull()
    }
    fun submittedFrame(timestampNs: Long, capture: com.unspektrawesome.camera.session.RawCaptureMetadata) {
        telemetry?.submitted(timestampNs, capture.sensorTimestampNs, capture, stabilization?.latest)
    }
    fun encoderInputWait(waitNs: Long, capacity: Int) {
        if (capacity > 0) encoderInputCapacity = capacity
        encoderPressure.observe(waitNs, System.nanoTime(), capacity)
    }
    fun encoderFrameSubmitted(timestampNs: Long) = encoderPressure.submitted(timestampNs / 1_000, System.nanoTime())
    var metadataWritten = false
        private set
    internal val saveTimingsMs = linkedMapOf<String, Long>()

    fun metadataFrame(timestampNs: Long, revision: Long, capture: () -> com.unspektrawesome.capture.CaptureMetadata) {
        metadata.frame((timestampNs - startNs) / 1000, revision, capture)
    }
    private var onError: () -> Unit = {}
    var surface: Surface? = null
        private set
    var fullRange: Boolean = true
        private set

    @SuppressLint("MissingPermission")
    fun prepare(encoding: VideoEncoding, bitrate: Int, rotation: Int, configuration: org.json.JSONObject = org.json.JSONObject(),
        audioFormat: com.unspektrawesome.settings.VideoAudioFormat = com.unspektrawesome.settings.VideoAudioFormat.AAC,
        onError: () -> Unit) {
        check(video == null && worker == null)
        this.onError = onError
        pcm = audioFormat == com.unspektrawesome.settings.VideoAudioFormat.PCM
        requestedBitrate = bitrate
        frameCounter = VideoFrameCounter(encoding.frameRate)
        encoderPressure = VideoEncoderPressure(encoding.frameRate)
        encoderInputCapacity = null
        metadata = VideoMetadata(configuration.put("width", encoding.size.width).put("height", encoding.size.height)
            .put("frameRate", encoding.frameRate).put("bitrate", bitrate).put("rotation", rotation)
            .put("audioFormat", audioFormat.name))
        try {
            val name = com.unspektrawesome.capture.PhotoFileNames.baseName(System.currentTimeMillis(), null) +
                if (pcm) QUICKTIME_FILE_EXTENSION else VIDEO_FILE_EXTENSION
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, if (pcm) "video/quicktime" else "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + VIDEO_DIRECTORY)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
            pendingUri = uri
            descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
            muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(rotation)
            }
            val configured = encoding.configureCodec(bitrate)
            video = configured.codec
            fullRange = configured.colorRange == MediaFormat.COLOR_RANGE_FULL
            surface = configured.surface
            if (pcm) {
                val directory = java.io.File(context.cacheDir, PCM_DIRECTORY).apply { mkdirs() }
                // Leftovers from an interrupted recording; an active recording writes every few milliseconds.
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60_000 }
                    ?.forEach { it.delete() }
                val file = java.io.File.createTempFile("audio-", PCM_FILE_EXTENSION, directory)
                pcmFile = file
                pcmOutput = java.io.RandomAccessFile(file, "rw").channel
            } else {
                val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFFER_BYTES)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audio!!.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val minimumBuffer = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(minimumBuffer > 0)
            microphone = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimumBuffer * 2, AUDIO_BUFFER_BYTES * 8)).build()
            check(microphone!!.state == AudioRecord.STATE_INITIALIZED)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun start() {
        check(worker == null)
        checkNotNull(video).start()
        if (!pcm) checkNotNull(audio).start()
        startNs = System.nanoTime()
        encoderPressure.start(startNs)
        telemetry?.start()
        checkNotNull(microphone).startRecording()
        check(microphone!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        worker = Thread({ encode() }, "VideoEncoding").also { it.start() }
    }

    fun timestamp(sensorTimestampNs: Long): Long = clock.timestamp(sensorTimestampNs)
    fun frameReceived(sensorTimestampNs: Long) = clock.anchor(sensorTimestampNs)

    @Synchronized
    fun stopAudio(stoppedAtNs: Long) {
        if (!audioStopping) {
            stopTimestampNs = stoppedAtNs
            audioStopping = true
        }
    }

    @Synchronized
    fun stop(stoppedAtNs: Long = System.nanoTime()) {
        if (!stopping) {
            stopAudio(stoppedAtNs)
            stopping = true
        }
    }

    fun finish(stoppedAtNs: Long = System.nanoTime()): Uri {
        check(worker != null)
        var stageNs = System.nanoTime()
        fun completed(stage: String) {
            val now = System.nanoTime()
            saveTimingsMs[stage] = (now - stageNs) / 1_000_000
            stageNs = now
        }
        try {
            stop(stoppedAtNs)
            check(finished.await(15, TimeUnit.SECONDS))
            failure?.let { throw it }
            check(finalized)
            metadata.complete(frameCounter.droppedFrames)
            completed("encoder")
            val uri = checkNotNull(pendingUri)
            if (pcm) {
                audioWritten = runCatching {
                    checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).use { file ->
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.fileDescriptor)).use { input ->
                            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(file.fileDescriptor)).use { output ->
                                writePcmTrack(input.channel, output.channel)
                            }
                        }
                    }
                }.onFailure { InternalLogRecorder.error("VideoRecording", "Uncompressed audio could not be saved", it) }.isSuccess
                completed("audio")
            }
            if (captureGyroRequested) telemetryWritten = runCatching {
                val recorder = checkNotNull(telemetry)
                checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).use { file ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { input ->
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { output ->
                            recorder.embed(input.channel, output.channel, durationUs)
                        }
                    }
                }
            }.onFailure { InternalLogRecorder.error("VideoTelemetry", "Telemetry could not be saved", it) }.isSuccess
            completed("telemetry")
            metadataWritten = runCatching {
                checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).use { file ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { input ->
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { output ->
                            val timeOffsetUs = checkNotNull(timeline.originUs) - startNs / 1000
                            Mp4Metadata.write(input.channel, output.channel, com.unspektrawesome.capture.CaptureIdentity.current.software,
                                metadata.description(timeOffsetUs), metadata.json(timeOffsetUs))
                        }
                    }
                }
            }.onFailure { InternalLogRecorder.error("VideoRecording", "Recording metadata could not be saved", it) }.isSuccess
            completed("settings")
            val updated = context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            check(updated == 1)
            completed("publish")
            pendingUri = null
            return uri
        } finally {
            close()
            completed("close")
            InternalLogRecorder.event("VideoRecording", "save durationUs=$durationUs stagesMs=$saveTimingsMs")
        }
    }

    /** Aligns the captured PCM with the first video frame and trims it to the video length. */
    private fun writePcmTrack(input: java.nio.channels.FileChannel, output: java.nio.channels.FileChannel) {
        val file = checkNotNull(pcmFile)
        java.io.RandomAccessFile(file, "r").channel.use { source ->
            val captured = minOf(pcmFrames, source.size() / AUDIO_BYTES_PER_FRAME)
            check(captured > 0)
            val offsetUs = checkNotNull(pcmOriginNs) / 1000 - checkNotNull(timeline.originUs)
            val offsetFrames = Math.floorDiv(offsetUs * AUDIO_SAMPLE_RATE + 500_000, 1_000_000L)
            val skip = (-offsetFrames).coerceIn(0, captured)
            val pad = offsetFrames.coerceAtLeast(0)
            val limit = (durationUs * AUDIO_SAMPLE_RATE + 500_000) / 1_000_000
            val padded = minOf(pad, limit)
            val frames = minOf(captured - skip, (limit - padded).coerceAtLeast(0))
            check(frames + padded > 0)
            PcmAudioTrack.append(input, output, source, skip * AUDIO_BYTES_PER_FRAME, padded, frames, AUDIO_SAMPLE_RATE, 1)
            InternalLogRecorder.event("VideoRecording", "pcm audio offsetUs=$offsetUs skipped=$skip padded=$padded frames=$frames")
        }
    }

    private data class Sample(val video: Boolean, val bytes: ByteBuffer, val info: MediaCodec.BufferInfo)

    private fun encode() {
        val videoCodec = checkNotNull(video)
        val audioCodec = if (pcm) null else checkNotNull(audio)
        val pcmChannel = if (pcm) checkNotNull(pcmOutput) else null
        val pcmBuffer = if (pcm) ByteBuffer.allocateDirect(AUDIO_BUFFER_BYTES) else null
        val trackCount = if (pcm) 1 else 2
        val input = checkNotNull(microphone)
        val output = checkNotNull(muxer)
        var muxing = false
        val tracks = intArrayOf(-1, -1)
        val samples = longArrayOf(0, 0)
        val buffers = longArrayOf(0, 0)
        val configBuffers = longArrayOf(0, 0)
        val writtenBytes = longArrayOf(0, 0)
        val previousPts = longArrayOf(-1, -1)
        val ended = booleanArrayOf(false, false)
        val pending = ArrayList<Sample>()
        var pendingBytes = 0
        val info = MediaCodec.BufferInfo()
        var audioInput = -1
        var audioFrames = 0L
        var audioOriginNs: Long? = null
        var audioTimestampAvailable = false
        val audioTimestamp = AudioTimestamp()
        var previousAudioInputUs = -1L
        var audioEos = false
        var audioCaptureStopped = false
        var stopNs = 0L
        var lastSizeSampleNs = 0L
        var lastVideoOutputNs = 0L

        fun write(track: Int, bytes: ByteBuffer, metadata: MediaCodec.BufferInfo) {
            val inputPts = metadata.presentationTimeUs
            metadata.presentationTimeUs = timeline.presentationTimeUs(inputPts) ?: return
            val writeStartedNs = if (traceFrames && track == 0) System.nanoTime() else 0L
            output.writeSampleData(tracks[track], bytes, metadata)
            if (traceFrames && track == 0) InternalLogRecorder.event("VideoFrame",
                "muxed pts_us=$inputPts bytes=${metadata.size} write_us=${(System.nanoTime() - writeStartedNs) / 1_000}")
            if (track == 0) {
                frameCounter.encoded(metadata.presentationTimeUs)
                telemetry?.encoded(inputPts, metadata.presentationTimeUs)
            }
            previousPts[track] = maxOf(previousPts[track], metadata.presentationTimeUs)
            samples[track]++
            writtenBytes[track] += metadata.size
        }

        fun flushPending() {
            if (!muxing || timeline.originUs == null) return
            for (sample in pending) write(if (sample.video) 0 else 1, sample.bytes, sample.info)
            pending.clear()
            pendingBytes = 0
        }

        fun drain(codec: MediaCodec, track: Int): Boolean {
            if (ended[track]) return false
            val traceOutput = traceFrames && track == 0
            val dequeueStartedNs = if (traceOutput) System.nanoTime() else 0L
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(tracks[track] < 0)
                val format = codec.outputFormat
                if (track == 0) InternalLogRecorder.event("VideoRecording",
                    "encoder output requested=$requestedBitrate configured=${format.getInteger(MediaFormat.KEY_BIT_RATE, 0)} " +
                        "mode=${format.getInteger(MediaFormat.KEY_BITRATE_MODE, -1)}")
                if (track == 0 && format.containsKey(MediaFormat.KEY_PROFILE))
                    check(format.getInteger(MediaFormat.KEY_PROFILE) == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                tracks[track] = output.addTrack(format)
                if ((0 until trackCount).all { tracks[it] >= 0 }) {
                    output.start()
                    muxing = true
                    flushPending()
                }
                return true
            }
            if (index < 0) return false
            val outputReadyNs = if (traceOutput) System.nanoTime() else 0L
            val outputPtsUs = info.presentationTimeUs
            if (traceOutput) {
                InternalLogRecorder.event("VideoFrame", "encoded pts_us=${info.presentationTimeUs} bytes=${info.size} " +
                    "flags=${info.flags} dequeue_us=${(outputReadyNs - dequeueStartedNs) / 1_000} " +
                    "output_gap_us=${if (lastVideoOutputNs == 0L) 0 else (outputReadyNs - lastVideoOutputNs) / 1_000} " +
                    "latency_us=${if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && outputPtsUs > 0)
                        outputReadyNs / 1_000 - outputPtsUs else -1} " +
                    "encoder_pending=${encoderPressure.queuedFrames}")
                lastVideoOutputNs = outputReadyNs
            }
            try {
                buffers[track]++
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) configBuffers[track]++
                if (buffers[track] <= 3 || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                    InternalLogRecorder.event("VideoRecording", "output track=$track buffer=${buffers[track]} " +
                        "size=${info.size} ptsUs=${info.presentationTimeUs} flags=${info.flags}")
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = checkNotNull(codec.getOutputBuffer(index))
                    if (track == 0) {
                        encoderPressure.encoded(info.presentationTimeUs, System.nanoTime())
                        timeline.video(info.presentationTimeUs)
                    }
                    flushPending()
                    if (muxing && timeline.originUs != null) write(track, bytes, info) else {
                        check(pendingBytes + info.size <= MAX_PENDING_BYTES)
                        bytes.position(info.offset).limit(info.offset + info.size)
                        val copy = ByteBuffer.allocateDirect(info.size).put(bytes).apply { flip() }
                        val metadata = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        pending.add(Sample(track == 0, copy, metadata))
                        pendingBytes += info.size
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended[track] = true
            } finally {
                val releaseStartedNs = if (traceOutput) System.nanoTime() else 0L
                codec.releaseOutputBuffer(index, false)
                if (traceOutput) InternalLogRecorder.event("VideoFrame",
                    "released pts_us=$outputPtsUs held_us=${(releaseStartedNs - outputReadyNs) / 1_000} " +
                        "release_us=${(System.nanoTime() - releaseStartedNs) / 1_000}")
            }
            return true
        }

        try {
            while (!aborting && !ended.all { it }) {
                val nowNs = System.nanoTime()
                if (nowNs - lastSizeSampleNs >= TimeUnit.SECONDS.toNanos(1)) {
                    fileSizeBytes = runCatching { descriptor?.statSize?.takeIf { it >= 0 } }.getOrNull()
                    lastSizeSampleNs = nowNs
                }
                var progressed = false
                if (stopping && stopNs == 0L) {
                    stopNs = System.nanoTime()
                    videoCodec.signalEndOfInputStream()
                }
                if (stopNs != 0L) check(System.nanoTime() - stopNs < TimeUnit.SECONDS.toNanos(10))
                if (audioStopping && !audioCaptureStopped) {
                    input.stop()
                    audioCaptureStopped = true
                }
                if (!audioEos && pcmChannel != null) {
                    val buffer = checkNotNull(pcmBuffer)
                    val count = if (audioCaptureStopped) 0
                        else input.read(buffer.apply { clear() }, AUDIO_BUFFER_BYTES, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0)
                    if (count > 0) {
                        if (!audioTimestampAvailable && input.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            pcmOriginNs = audioTimestamp.nanoTime - audioTimestamp.framePosition * 1_000_000_000L / AUDIO_SAMPLE_RATE
                            audioTimestampAvailable = true
                        }
                        if (pcmOriginNs == null) pcmOriginNs = System.nanoTime() -
                            (pcmFrames + count / AUDIO_BYTES_PER_FRAME) * 1_000_000_000L / AUDIO_SAMPLE_RATE
                        buffer.position(0).limit(count - count % AUDIO_BYTES_PER_FRAME)
                        while (buffer.hasRemaining()) check(pcmChannel.write(buffer) > 0)
                        pcmFrames += count / AUDIO_BYTES_PER_FRAME
                        progressed = true
                    } else if (audioCaptureStopped) {
                        audioEos = true
                        ended[1] = true
                        progressed = true
                    }
                }
                if (!audioEos && audioCodec != null) {
                    if (audioInput < 0) audioInput = audioCodec.dequeueInputBuffer(0)
                    if (audioInput >= 0) {
                        val buffer = checkNotNull(audioCodec.getInputBuffer(audioInput)).apply { clear() }
                        if (audioStopping) {
                            val endUs = maxOf(previousAudioInputUs + 1,
                                ((audioOriginNs ?: startNs) + audioFrames * 1_000_000_000L / AUDIO_SAMPLE_RATE) / 1000)
                            audioCodec.queueInputBuffer(audioInput, 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioEos = true
                            progressed = true
                        } else {
                            val count = input.read(buffer, minOf(buffer.capacity(), AUDIO_BUFFER_BYTES), AudioRecord.READ_NON_BLOCKING)
                            check(count >= 0)
                            if (count > 0) {
                                if (!audioTimestampAvailable && input.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                    audioOriginNs = audioTimestamp.nanoTime - audioTimestamp.framePosition * 1_000_000_000L / AUDIO_SAMPLE_RATE
                                    audioTimestampAvailable = true
                                }
                                if (audioOriginNs == null) audioOriginNs = System.nanoTime() -
                                    (audioFrames + count / AUDIO_BYTES_PER_FRAME) * 1_000_000_000L / AUDIO_SAMPLE_RATE
                                val ptsUs = maxOf(previousAudioInputUs + 1,
                                    (audioOriginNs!! + audioFrames * 1_000_000_000L / AUDIO_SAMPLE_RATE) / 1000)
                                audioCodec.queueInputBuffer(audioInput, 0, count, ptsUs, 0)
                                previousAudioInputUs = ptsUs
                                audioFrames += count / AUDIO_BYTES_PER_FRAME
                                audioInput = -1
                                progressed = true
                            }
                        }
                    }
                }
                progressed = drain(videoCodec, 0) || progressed
                if (audioCodec != null) progressed = drain(audioCodec, 1) || progressed
                if (!progressed) Thread.sleep(2)
            }
            check(!aborting && muxing && samples[0] > 0 && (if (pcm) pcmFrames > 0 else samples[1] > 0)) {
                "Incomplete recording: aborting=$aborting muxing=$muxing samples=${samples.contentToString()} " +
                    "buffers=${buffers.contentToString()} config=${configBuffers.contentToString()} " +
                    "ended=${ended.contentToString()} originUs=${timeline.originUs} pendingBytes=$pendingBytes pcmFrames=$pcmFrames"
            }
            // Hold the final video frame until recording stops, including slow GPU frames.
            val end = MediaCodec.BufferInfo().apply {
                set(0, 0, maxOf(previousPts[0] + 1, stopTimestampNs / 1000 - checkNotNull(timeline.originUs)), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            output.writeSampleData(tracks[0], ByteBuffer.allocateDirect(0), end)
            durationUs = end.presentationTimeUs
            output.stop()
            InternalLogRecorder.event("VideoRecording",
                "finished video bitrate=${writtenBytes[0] * 8_000_000L / end.presentationTimeUs.coerceAtLeast(1)} " +
                    "requested=$requestedBitrate frames=${samples[0]}")
            finalized = true
        } catch (error: Throwable) {
            failure = error
            InternalLogRecorder.error("VideoRecording", "encoding failed", error)
        } finally {
            releaseResources()
            finished.countDown()
            if (failure != null && !aborting) runCatching { onError() }
        }
    }

    private fun releaseResources() {
        runCatching { microphone?.stop() }
        runCatching { microphone?.release() }
        microphone = null
        runCatching { video?.release() }
        video = null
        runCatching { audio?.release() }
        audio = null
        runCatching { surface?.release() }
        surface = null
        runCatching { muxer?.release() }
        muxer = null
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { pcmOutput?.close() }
        pcmOutput = null
    }

    override fun close() {
        stabilization?.close()
        aborting = true
        if (worker == null) releaseResources() else finished.await(15, TimeUnit.SECONDS)
        pendingUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        pendingUri = null
        pcmFile?.let { file -> runCatching { file.delete() } }
        pcmFile = null
        runCatching { telemetry?.close() }
            .onFailure { InternalLogRecorder.error("VideoTelemetry", "Telemetry cleanup failed", it) }
    }
}
