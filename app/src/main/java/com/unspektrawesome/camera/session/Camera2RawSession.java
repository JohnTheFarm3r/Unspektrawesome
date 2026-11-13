package com.unspektrawesome.camera.session;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.TonemapCurve;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.util.Size;
import android.util.Range;
import android.util.Rational;
import android.util.Log;
import android.hardware.camera2.params.MeteringRectangle;

import com.unspektrawesome.camera.CameraRoute;
import com.unspektrawesome.camera.FlashMode;
import com.unspektrawesome.diagnostics.InternalLogRecorder;
import com.unspektrawesome.camera.ColorCalibration;
import com.unspektrawesome.camera.IntRange;
import com.unspektrawesome.camera.RawColorCalibration;
import com.unspektrawesome.vulkan.params.WhiteBalanceGains;
import com.unspektrawesome.camera.RawFormat;
import com.unspektrawesome.camera.RawOutput;
import com.unspektrawesome.camera.SensorMetadata;
import com.unspektrawesome.camera.Size2d;
import com.unspektrawesome.camera.WhiteBalanceTemperature;
import com.unspektrawesome.camera.exposure.AeLimits;
import com.unspektrawesome.camera.exposure.ExposureBounds;
import com.unspektrawesome.camera.exposure.SensorExposure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.EnumSet;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Single-route RAW Camera2 session with borrowed GPU and packed plane buffers. */
public final class Camera2RawSession implements AutoCloseable {
    private static final String TAG = "UnspekRawSession";
    private final Object rawReaderLock = new Object();
    private final java.util.IdentityHashMap<ImageReader, Integer> rawReaderLeases = new java.util.IdentityHashMap<>();
    private final java.util.Set<ImageReader> retiredRawReaders = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final int METADATA_CAPACITY = 24;
    private static final long FOCUS_TIMEOUT_MS = 8_000L;
    private static final long SLOW_PREVIEW_UPDATE_NS = 100_000_000L;
    private static final float TAP_REGION_SIZE = 0.12f;
    private static final int MIN_WHITE_BALANCE_KELVIN = 2_000;
    private static final int MAX_WHITE_BALANCE_KELVIN = 10_000;

    public enum State {
        IDLE,
        OPENING,
        CONFIGURING,
        STREAMING,
        STILL_CONFIGURING,
        STILL_CAPTURING,
        STILL_CAPTURED,
        STILL_FAILED,
        PREVIEW_RECONFIGURING,
        CLOSING,
        CLOSED,
        UNSUPPORTED,
        ERROR
    }

    public enum ErrorCode {
        API_UNSUPPORTED,
        CAMERA_ACCESS,
        CAMERA_DISCONNECTED,
        CAMERA_DEVICE,
        SESSION_CONFIGURATION,
        REQUEST,
        CAPTURE_FAILURE,
        BUFFER,
        CONSUMER
    }

    public enum MeteringState {
        SCANNING,
        SUCCESS,
        FAILED,
        TIMED_OUT,
        RESET,
        UNSUPPORTED
    }

    public static final class ControlState {
        public final boolean flashAvailable;
        public final FlashMode flashMode;
        public final int exposureCompensationMin;
        public final int exposureCompensationMax;
        public final float exposureCompensationStepEv;
        public final int exposureCompensation;
        public final boolean manualExposureSupported;
        public final int isoMin;
        public final int isoMax;
        public final long exposureTimeMinNs;
        public final long exposureTimeMaxNs;
        public final boolean autoExposure;
        public final boolean externalAutoExposure;
        public final int iso;
        public final long exposureTimeNs;
        public final Long pendingExposureTimeNs;
        public final boolean manualFocusSupported;
        public final float minimumFocusDistance;
        public final float hyperfocalDistance;
        public final boolean autoFocus;
        public final float focusDistance;
        public final boolean manualWhiteBalanceSupported;
        public final boolean autoWhiteBalance;
        public final Integer whiteBalanceTemperatureKelvin;
        public final Float whiteBalanceRedGain;
        public final Float whiteBalanceGreenGain;
        public final Float whiteBalanceBlueGain;

        ControlState(int exposureCompensationMin, int exposureCompensationMax,
                     float exposureCompensationStepEv, int exposureCompensation,
                     boolean manualExposureSupported, int isoMin, int isoMax,
                     long exposureTimeMinNs, long exposureTimeMaxNs, boolean autoExposure,
                     int iso, long exposureTimeNs, Long pendingExposureTimeNs, boolean manualFocusSupported,
                     float minimumFocusDistance, float hyperfocalDistance,
                     boolean autoFocus, float focusDistance,
                     boolean manualWhiteBalanceSupported, boolean autoWhiteBalance,
                     Integer whiteBalanceTemperatureKelvin,
                     Float whiteBalanceRedGain, Float whiteBalanceGreenGain,
                     Float whiteBalanceBlueGain, boolean flashAvailable, FlashMode flashMode,
                     boolean externalAutoExposure) {
            this.externalAutoExposure = externalAutoExposure;
            this.flashAvailable = flashAvailable;
            this.flashMode = flashMode;
            this.exposureCompensationMin = exposureCompensationMin;
            this.exposureCompensationMax = exposureCompensationMax;
            this.exposureCompensationStepEv = exposureCompensationStepEv;
            this.exposureCompensation = exposureCompensation;
            this.manualExposureSupported = manualExposureSupported;
            this.isoMin = isoMin;
            this.isoMax = isoMax;
            this.exposureTimeMinNs = exposureTimeMinNs;
            this.exposureTimeMaxNs = exposureTimeMaxNs;
            this.autoExposure = autoExposure;
            this.iso = iso;
            this.exposureTimeNs = exposureTimeNs;
            this.pendingExposureTimeNs = pendingExposureTimeNs;
            this.manualFocusSupported = manualFocusSupported;
            this.minimumFocusDistance = minimumFocusDistance;
            this.hyperfocalDistance = hyperfocalDistance;
            this.autoFocus = autoFocus;
            this.focusDistance = focusDistance;
            this.manualWhiteBalanceSupported = manualWhiteBalanceSupported;
            this.autoWhiteBalance = autoWhiteBalance;
            this.whiteBalanceTemperatureKelvin = whiteBalanceTemperatureKelvin;
            this.whiteBalanceRedGain = whiteBalanceRedGain;
            this.whiteBalanceGreenGain = whiteBalanceGreenGain;
            this.whiteBalanceBlueGain = whiteBalanceBlueGain;
        }
    }

    public static final class SessionError {
        public final ErrorCode code;
        public final String message;
        public final boolean fatal;
        public final Throwable cause;
        SessionError(ErrorCode code, String message, boolean fatal, Throwable cause) {
            this.code = Objects.requireNonNull(code);
            this.message = Objects.requireNonNull(message);
            this.fatal = fatal;
            this.cause = cause;
        }
    }

    private final CameraManager cameraManager;
    private RawSessionConfiguration exactConfiguration;
    private ImageReader auxiliaryJpegReader;
    private int companionMode;
    private int requestBatchSize = 1;

    public synchronized void setRequestBatchSize(int size) {
        if (size < 1 || size > 32) throw new IllegalArgumentException("Invalid RAW request batch size");
        if (state != State.IDLE) throw new IllegalStateException("Camera already started");
        requestBatchSize = size;
    }

    public synchronized void setCompanionMode(int mode) {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("Invalid companion mode");
        if (state != State.IDLE) throw new IllegalStateException("Camera already started");
        companionMode = mode;
    }

    private static final int ISP_VIEWFINDER_IMAGES = 4;
    private Size2d ispViewfinderSize;
    private volatile boolean ispViewfinderStream;
    private final TimestampMetadataMatcher<RawCaptureMetadata> ispMetadata =
            new TimestampMetadataMatcher<>(METADATA_CAPACITY);
    private final TimestampMetadataMatcher<CaptureResult> ispResults =
            new TimestampMetadataMatcher<>(METADATA_CAPACITY);
    private final PendingFrameQueue<Image> pendingIspImage = new PendingFrameQueue<>(2, Camera2RawSession::closeImage);
    private TonemapCurve ispTonemapCurve;
    private boolean ispEdgeOffSupported;
    private Integer ispNoiseReductionMode;
    private boolean ispColorCorrectionSupported;

    /** Deliver the ISP companion stream as a viewfinder source sized for this processing output. */
    public synchronized void setIspViewfinder(Size2d processingSize) {
        if (state != State.IDLE) throw new IllegalStateException("Camera already started");
        ispViewfinderSize = processingSize;
    }

    /** Whether the capture requests of this session produce ISP viewfinder frames. */
    public boolean ispViewfinderStreaming() {
        return ispViewfinderStream && (exactConfiguration == null || exactConfiguration.targetCompanion);
    }
    private com.unspektrawesome.settings.RawLayoutOverride rawLayoutOverride = com.unspektrawesome.settings.RawLayoutOverride.NONE;
    private VendorRequestOverrides vendorOverrides = new VendorRequestOverrides(
            com.unspektrawesome.settings.VendorOverrides.NONE);
    private volatile boolean closeRequested;
    private final CountDownLatch resourcesReleased = new CountDownLatch(1);
    private volatile long companionFrames;
    private volatile HandlerThread retiringThread;

    public long companionFrames() { return companionFrames; }

    public synchronized void setExactConfiguration(RawSessionConfiguration configuration) {
        if (state != State.IDLE) throw new IllegalStateException("Session has already been started");
        exactConfiguration = configuration;
    }

    public synchronized void setVendorOverrides(com.unspektrawesome.settings.VendorOverrides overrides) {
        if (state != State.IDLE) throw new IllegalStateException("Session has already been started");
        vendorOverrides = new VendorRequestOverrides(overrides);
    }

    public synchronized void setRawLayoutOverride(com.unspektrawesome.settings.RawLayoutOverride override) {
        if (state != State.IDLE) throw new IllegalStateException("Session has already been started");
        rawLayoutOverride = override.active();
    }

    /** Wait off the UI and camera threads, including the camera's onClosed acknowledgement. */
    public boolean awaitReleased(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        if (!resourcesReleased.await(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        HandlerThread thread = retiringThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.join(Math.max(1, deadline - SystemClock.elapsedRealtime()));
            if (thread.isAlive()) return false;
        }
        return controlRequestExecutor.awaitTermination(Math.max(1, deadline - SystemClock.elapsedRealtime()),
                TimeUnit.MILLISECONDS);
    }

    /** Also handles cancellation while an asynchronous open has not delivered its device. */
    public void requestClose() {
        closeRequested = true;
        Handler handler = cameraHandler;
        if (handler == null || !handler.post(this::close)) close();
    }
    private final SensorMetadata sensorMetadata;
    private final TimestampMetadataMatcher<RawCaptureMetadata> metadata =
            new TimestampMetadataMatcher<>(METADATA_CAPACITY);
    private final TimestampMetadataMatcher<CaptureResult> previewDngMetadata =
            new TimestampMetadataMatcher<>(METADATA_CAPACITY);
    // Capture results of preview frames, so buffered viewfinder frames can be saved as DNG.
    private final TimestampMetadataMatcher<CaptureResult> previewResults =
            new TimestampMetadataMatcher<>(METADATA_CAPACITY);
    private final PendingFrameQueue<Image> pendingImage =
            new PendingFrameQueue<>(RawImageBuffering.DEFAULT_PENDING_IMAGES, Camera2RawSession::closeImage);
    private final PendingFrameQueue<Image> pendingStillImage = new PendingFrameQueue<>(1, Camera2RawSession::closeImage);
    private boolean captureInPreviewSession;
    private volatile boolean captureNextPreviewFrame;
    private long inSessionStillTimestamp = -1L;

    private volatile State state = State.IDLE;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final ExecutorService controlRequestExecutor = Executors.newSingleThreadExecutor(
            action -> new Thread(action, "UnspektrawesomeCameraRequests"));
    private final LatestCameraRequestQueue controlRequests = new LatestCameraRequestQueue(controlRequestExecutor);
    private final RecordingRequestPump recordingRequests = new RecordingRequestPump();
    private final RecordingExposureRamp recordingExposure = new RecordingExposureRamp();
    private int recordingPipelineDepth;
    private boolean recording;
    private boolean recordingRequestsRejected;
    private int recordingFocusSteps;
    private boolean recordingFocusRestart;
    private ImageReader imageReader;
    private ImageReader meteringReader;
    private CameraDevice cameraDevice;
    private CameraDevice closingCameraDevice;
    private boolean cameraOpening;
    private boolean yuvCompanionRequired;
    private boolean rawOnlyRequired;
    private final Set<RawOutput> rejectedGpuBufferOutputs = new java.util.HashSet<>();
    private final Map<String, CameraCharacteristics> sessionCharacteristics = new java.util.HashMap<>();
    private boolean gpuBufferUsage;
    private boolean rawInputStarted;
    private Runnable gpuBufferTimeout;
    private boolean reopenForStreamRetry;
    private boolean reopenForExposureChange;
    private CameraCaptureSession captureSession;
    private CameraCaptureSession closingSession;
    private Runnable afterSessionClosed;
    private CameraRoute route;
    private RawOutput previewOutput;
    private RawOutput output;
    private IntRange aeFpsRange;
    private final RawStreamDiagnostics streamDiagnostics = new RawStreamDiagnostics();
    private RawFrameConsumer frameConsumer;
    private RawSessionListener listener;
    private String physicalCameraId;
    private boolean missingWhiteBalanceLogged;
    private boolean rawLayoutLogged;
    private boolean requestLensShadingMap;
    private Integer opticalStabilizationMode;
    private boolean opticalStabilizationEnabled = true;
    private volatile Integer reportedOpticalStabilizationMode;
    private volatile boolean opticalStabilizationSupported;
    private volatile boolean opticalStabilizationLive = true;
    private long opticalStabilizationChangedNs;
    private RawStillCaptureCallback stillCaptureCallback;
    private boolean stillFrameDelivered;
    private Runnable stillCaptureTimeout;
    private CaptureRequest.Builder repeatingRequestBuilder;
    private int maxAfRegions;
    private int maxAeRegions;
    private int[] availableAfModes;
    private boolean meteringPointSet;
    private float meteringPointX;
    private float meteringPointY;
    private final AutofocusFeedback autofocusFeedback = new AutofocusFeedback();
    private MeteringState focusReportedState;
    private long focusClientGeneration;
    private boolean exposurePointSet;
    private float exposurePointX;
    private float exposurePointY;
    private long exposureRequestGeneration;
    private MeteringState exposureReportedState;
    private boolean aeLockAvailable;
    private long meteringRequestGeneration;
    private Runnable focusTimeout;
    private int exposureCompensationMin;
    private int exposureCompensationMax;
    private float exposureCompensationStepEv = 1f;
    private int exposureCompensation;
    private boolean manualExposureSupported;
    private int isoMin = 100;
    private int isoMax = 100;
    private long exposureTimeMinNs = 1_000_000L;
    private long exposureTimeMaxNs = 10_000_000L;
    private long maximumFrameDurationNs = 1_000_000_000L;
    private boolean autoExposure = true;
    private ControlState initialControls;
    private SensorExposure initialAutoExposure;
    private AeLimits initialAeLimits;
    private boolean externalAutoExposure;
    private boolean externalExposureAutomatic = true;
    private Float meteredAperture;
    private long exposureControlGeneration;
    private int iso = 100;
    private long exposureTimeNs = 10_000_000L;
    private final LongShutterSelection longShutter = new LongShutterSelection();
    private long captureRequestGeneration;
    private boolean repeatingPreviewActive;
    private boolean longPreviewInFlight;
    private boolean captureSessionRestartPending;
    private boolean stillRequestAwaitingRestart;
    private boolean longPreviewImageDelivered;
    private boolean singleCaptureIdle = true;
    private boolean singleCaptureUsed;
    private int longPreviewFocusTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE;
    private final Runnable longPreviewRequest = this::captureLongPreview;
    private Runnable longPreviewTimeout;
    private boolean manualFocusSupported;
    private float minimumFocusDistance;
    private float hyperfocalDistance;
    private boolean autoFocus = true;
    private float focusDistance;
    private int[] availableAwbModes;
    private boolean manualWhiteBalanceSupported;
    private boolean autoWhiteBalance = true;
    private Integer whiteBalanceTemperatureKelvin;
    private Float whiteBalanceRedGain;
    private Float whiteBalanceGreenGain;
    private Float whiteBalanceBlueGain;
    private float[] lastAutoWhiteBalanceNeutral;
    private float[] lockedWhiteBalanceNeutral;
    private boolean captureWhiteBalanceLock;
    private boolean awbLockAvailable;
    private long lastControlReportMs;
    private RequestMetadata stillRequestMetadata;
    private StillExposure stillExposure;
    private FlashMode flashMode = FlashMode.OFF;
    private boolean flashAvailable;
    private int[] availableAeModes;
    private FlashMode stillFlashMode = FlashMode.OFF;
    private FlashMode stillFlashRequestMode = FlashMode.OFF;
    private boolean flashMetered;
    private boolean stillFlashRequired;
    private boolean flashPreviewPaused;
    private FlashMeteringSequence flashMetering;
    private Runnable flashMeteringTimeout;

    static final class RequestMetadata {
        final long meteringGeneration;
        final long captureGeneration;
        final long exposureGeneration;
        final long exposureControlGeneration;
        long recordingToken;
        boolean recordingStarted;
        private final float[] manualNeutral;

        RequestMetadata(long meteringGeneration, float[] manualNeutral) {
            this(meteringGeneration, manualNeutral, 0L);
        }

        RequestMetadata(long meteringGeneration, float[] manualNeutral, long captureGeneration) {
            this(meteringGeneration, manualNeutral, captureGeneration, 0L);
        }

        RequestMetadata(long meteringGeneration, float[] manualNeutral, long captureGeneration, long exposureGeneration) {
            this(meteringGeneration, manualNeutral, captureGeneration, exposureGeneration, 0L);
        }

        RequestMetadata(long meteringGeneration, float[] manualNeutral, long captureGeneration,
                        long exposureGeneration, long exposureControlGeneration) {
            this.exposureControlGeneration = exposureControlGeneration;
            this.meteringGeneration = meteringGeneration;
            this.captureGeneration = captureGeneration;
            this.exposureGeneration = exposureGeneration;
            this.manualNeutral = manualNeutral == null ? null : manualNeutral.clone();
        }

        boolean autoWhiteBalance() { return manualNeutral == null; }

        float[] manualNeutral() {
            return manualNeutral == null ? null : manualNeutral.clone();
        }
    }

    public Camera2RawSession(CameraManager cameraManager) {
        this(cameraManager, null);
    }

    public Camera2RawSession(CameraManager cameraManager, SensorMetadata sensorMetadata) {
        this(cameraManager, sensorMetadata, 0);
    }

    public Camera2RawSession(CameraManager cameraManager, SensorMetadata sensorMetadata,
                             int initialExposureCompensation) {
        this.cameraManager = Objects.requireNonNull(cameraManager);
        this.sensorMetadata = sensorMetadata;
        this.exposureCompensation = initialExposureCompensation;
    }

    public State state() { return state; }
    public long droppedPendingFrames() { return pendingImage.dropped(); }
    public long metadataEvictions() { return metadata.evictions(); }

    public boolean opticalStabilizationSupported() { return opticalStabilizationSupported; }
    public boolean opticalStabilizationLive() { return opticalStabilizationLive; }
    public Integer reportedOpticalStabilizationMode() { return reportedOpticalStabilizationMode; }

    public synchronized boolean setOpticalStabilization(boolean enabled) {
        if (state == State.IDLE) {
            opticalStabilizationEnabled = enabled;
            return true;
        }
        if (!opticalStabilizationSupported || !opticalStabilizationLive) return false;
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            opticalStabilizationEnabled = enabled;
            opticalStabilizationMode = enabled ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    : CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF;
            opticalStabilizationChangedNs = SystemClock.elapsedRealtimeNanos();
            applyControlUpdate(false);
        });
    }

    public synchronized boolean setFlashMode(FlashMode mode) {
        Objects.requireNonNull(mode);
        if (state == State.IDLE) {
            flashMode = mode;
            return true;
        }
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            flashMode = mode;
            applyControlUpdate();
        });
    }

    public synchronized boolean setMeteringPoint(float normalizedX, float normalizedY) {
        return setFocusPoint(normalizedX, normalizedY, 0L);
    }

    public synchronized boolean setFocusPoint(float normalizedX, float normalizedY, long generation) {
        if (!Float.isFinite(normalizedX) || !Float.isFinite(normalizedY)
                || normalizedX < 0f || normalizedX > 1f
                || normalizedY < 0f || normalizedY > 1f
                || state != State.STREAMING) {
            return false;
        }
        Handler handler = cameraHandler;
        return handler != null && handler.post(
                () -> {
                    focusClientGeneration = generation;
                    focusReportedState = null;
                    applyTapMetering(normalizedX, normalizedY);
                });
    }

    public synchronized boolean setExposurePoint(float normalizedX, float normalizedY, long generation) {
        if (!Float.isFinite(normalizedX) || !Float.isFinite(normalizedY) ||
                normalizedX < 0f || normalizedX > 1f || normalizedY < 0f || normalizedY > 1f ||
                state != State.STREAMING) return false;
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            exposureRequestGeneration = generation;
            exposureReportedState = null;
            if (!tapAutoExposureEnabled()) {
                notifyExposureState(MeteringState.UNSUPPORTED);
                return;
            }
            exposurePointSet = true;
            exposurePointX = normalizedX;
            exposurePointY = normalizedY;
            notifyExposureState(MeteringState.SCANNING);
            applyControlUpdate();
        });
    }

    public synchronized boolean clearExposurePoint() {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            exposurePointSet = false;
            notifyExposureState(MeteringState.RESET);
            if (!externalAutoExposure) applyControlUpdate();
        });
    }

    public synchronized boolean setExposureCompensation(int value) {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            exposureCompensation = clamp(value, exposureCompensationMin, exposureCompensationMax);
            if (externalAutoExposure) {
                notifyControlsChanged(true);
                return;
            }
            boolean interrupt = hasSlowManualPreview();
            longShutter.clear();
            autoExposure = true;
            applyExposureUpdate(interrupt);
        });
    }

    public synchronized boolean setAutoExposure() {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            boolean interrupt = hasSlowManualPreview();
            longShutter.clear();
            autoExposure = !externalAutoExposure;
            externalExposureAutomatic = true;
            applyExposureUpdate(interrupt);
        });
    }

    public synchronized boolean setExternalAutoExposure(boolean enabled) {
        if (state == State.IDLE) {
            externalAutoExposure = enabled;
            return true;
        }
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            boolean interrupt = hasSlowManualPreview();
            externalAutoExposure = enabled && manualExposureSupported;
            autoExposure = !externalAutoExposure;
            externalExposureAutomatic = true;
            longShutter.clear();
            exposureControlGeneration++;
            applyExposureUpdate(interrupt);
        });
    }

    public synchronized boolean setSensorExposure(int sensitivity, long durationNs, long generation) {
        return setSensorExposure(sensitivity, durationNs, generation, true);
    }

    public synchronized boolean setSensorExposure(int sensitivity, long durationNs, long generation, boolean automatic) {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            if (!externalAutoExposure || !manualExposureSupported || state != State.STREAMING) return;
            int nextIso = clamp(sensitivity, isoMin, isoMax);
            long nextTimeNs = clamp(durationNs, exposureTimeMinNs, exposureTimeMaxNs);
            if (!autoExposure && iso == nextIso && exposureTimeNs == nextTimeNs &&
                    exposureControlGeneration == generation && externalExposureAutomatic == automatic) return;
            boolean interrupt = hasSlowManualPreview() && nextTimeNs <= exposureTimeNs / 2;
            if (generation != exposureControlGeneration && recordingRequests.active())
                recordingExposure.reset(nextIso, nextTimeNs);
            autoExposure = false;
            externalExposureAutomatic = automatic;
            longShutter.clear();
            iso = nextIso;
            exposureTimeNs = nextTimeNs;
            exposureControlGeneration = generation;
            if (interrupt) applyExposureUpdate(true);
            else applyControlUpdate(false);
        });
    }

    public synchronized void setRecording(boolean enabled) {
        setRecordingState(enabled, null);
    }

    public synchronized void setRecording(boolean enabled, boolean automaticExposure) {
        setRecordingState(enabled, automaticExposure);
    }

    private void setRecordingState(boolean enabled, Boolean automaticExposure) {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.post(() -> {
            boolean automatic = automaticExposure == null ? externalExposureAutomatic : automaticExposure;
            if (recording == enabled && externalExposureAutomatic == automatic) return;
            recording = enabled;
            externalExposureAutomatic = automatic;
            recordingRequestsRejected = false;
            applyControlUpdate(false);
        });
    }

    public synchronized boolean setIso(int value) {
        Handler handler = cameraHandler;
        return manualExposureSupported && handler != null && handler.post(() -> {
            boolean interrupt = hasSlowManualPreview() && iso != clamp(value, isoMin, isoMax);
            autoExposure = false;
            externalExposureAutomatic = false;
            iso = clamp(value, isoMin, isoMax);
            applyExposureUpdate(interrupt);
        });
    }

    public synchronized boolean setExposureTimeNs(long value) {
        Handler handler = cameraHandler;
        return manualExposureSupported && handler != null && handler.post(() -> {
            long previous = exposureTimeNs;
            boolean wasAuto = autoExposure;
            boolean wasExternalAuto = externalExposureAutomatic;
            autoExposure = false;
            externalExposureAutomatic = false;
            exposureTimeNs = longShutter.select(previous, clamp(value, exposureTimeMinNs, exposureTimeMaxNs));
            if (wasAuto || wasExternalAuto || exposureTimeNs != previous) {
                applyExposureUpdate(!wasAuto && previous >= SLOW_PREVIEW_UPDATE_NS && exposureTimeNs != previous);
            } else notifyControlsChanged(true);
        });
    }

    public synchronized boolean applyLongShutter() {
        Handler handler = cameraHandler;
        return manualExposureSupported && handler != null && handler.post(() -> {
            if (state != State.STREAMING || longShutter.pendingExposureTimeNs() == null) return;
            boolean interrupt = exposureTimeNs > LongShutterSelection.LIMIT_NS;
            autoExposure = false;
            exposureTimeNs = longShutter.apply(exposureTimeNs);
            applyExposureUpdate(interrupt);
        });
    }

    public synchronized boolean cancelLongShutter() {
        Handler handler = cameraHandler;
        return manualExposureSupported && handler != null && handler.post(() -> {
            boolean interrupt = exposureTimeNs > LongShutterSelection.LIMIT_NS ||
                    stillExposure != null && stillExposure.exposureTimeNs > LongShutterSelection.LIMIT_NS;
            autoExposure = false;
            exposureTimeNs = longShutter.cancel(exposureTimeMaxNs);
            longPreviewFocusTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE;
            applyExposureUpdate(interrupt, true);
        });
    }

    private void discardInFlightCaptures() throws CameraAccessException {
        discardInFlightCaptures(false);
    }

    private void discardInFlightCaptures(boolean reopenDevice) throws CameraAccessException {
        recordingRequests.stop();
        InternalLogRecorder.event("LongShutter", "discard generation=" + captureRequestGeneration
                + " repeating=" + repeatingPreviewActive + " preview=" + longPreviewInFlight);
        captureRequestGeneration++;
        repeatingPreviewActive = false;
        longPreviewInFlight = false;
        longPreviewImageDelivered = false;
        if (cameraHandler != null) cameraHandler.removeCallbacks(longPreviewRequest);
        cancelLongPreviewTimeout();
        metadata.clear();
        previewDngMetadata.clear();
        previewResults.clear();
        pendingImage.clear();
        pendingStillImage.clear();
        if (reopenDevice && cameraDevice != null) {
            controlRequests.cancelAndWait();
            captureSessionRestartPending = true;
            reopenForExposureChange = true;
            afterSessionClosed = null;
            closingSession = null;
            captureSession = null;
            repeatingRequestBuilder = null;
            // Some drivers retain the old sensor timing after replacing only the streams.
            closeCameraDevice(cameraDevice);
            return;
        }
        if (captureSession != null && !captureSessionRestartPending) {
            captureSessionRestartPending = true;
            // Recreate the streams so interrupted sensor requests cannot stall their replacements.
            replaceSession(() -> {
                replaceReader(output, this::onPreviewImageAvailable);
                if (!terminalState()) configureSession(SessionMode.PREVIEW);
            });
        }
    }

    private void applyExposureUpdate(boolean interrupt) {
        applyExposureUpdate(interrupt, false);
    }

    private void applyExposureUpdate(boolean interrupt, boolean cancelPhoto) {
        RawStillCaptureCallback canceled = null;
        boolean acquiringPhoto = captureInPreviewSession && !stillFrameDelivered &&
                (state == State.STILL_CAPTURING || state == State.STILL_CONFIGURING);
        if (interrupt && cameraDevice != null && (!acquiringPhoto || cancelPhoto)) {
            try {
                discardInFlightCaptures(true);
            } catch (CameraAccessException | IllegalStateException error) {
                report(new SessionError(ErrorCode.REQUEST, "Unable to interrupt the long exposure", false, error));
            }
            if (acquiringPhoto) {
                canceled = stillCaptureCallback;
                stillCaptureCallback = null;
                stillRequestAwaitingRestart = false;
                cancelStillCaptureTimeout();
                cancelFlashMetering();
                flashPreviewPaused = false;
                captureInPreviewSession = false;
                captureNextPreviewFrame = false;
                inSessionStillTimestamp = -1L;
                stillFrameDelivered = true;
                stillDngResult = null;
                stillExposure = null;
                transition(State.STREAMING);
            }
        }
        applyControlUpdate();
        if (canceled != null) canceled.onStillCaptureCanceled();
    }

    public synchronized boolean setAutoFocus() {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            autoFocus = true;
            resetMetering();
            applyControlUpdate();
        });
    }

    public synchronized boolean restartPassiveFocus() {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            if (!autoFocus || state != State.STREAMING || captureSession == null ||
                    repeatingRequestBuilder == null || captureSessionRestartPending || usesLongPreview() ||
                    !supportsAfMode(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) return;
            resetMetering();
            CaptureRequest.Builder request = repeatingRequestBuilder;
            if (recordingRequests.active()) {
                recordingFocusSteps = 2;
                recordingFocusRestart = true;
                return;
            }
            try {
                applyCommonRequestControls(request, true);
                applyMeteringControls(request);
                request.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                captureSession.capture(buildRequest(request), captureCallback, cameraHandler);
                applyMeteringControls(request);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                repeatRequest(captureSession, buildRequest(request), captureCallback, cameraHandler);
                notifyControlsChanged(true);
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException error) {
                applyMeteringControls(request);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
        });
    }

    public synchronized boolean setFocusDistance(float value) {
        Handler handler = cameraHandler;
        return manualFocusSupported && Float.isFinite(value) && handler != null && handler.post(() -> {
            autoFocus = false;
            resetMetering();
            focusDistance = Math.max(0f, Math.min(value, minimumFocusDistance));
            applyControlUpdate();
        });
    }

    public synchronized boolean setAutoWhiteBalance() {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            autoWhiteBalance = true;
            lockedWhiteBalanceNeutral = null;
            captureWhiteBalanceLock = false;
            whiteBalanceTemperatureKelvin = null;
            whiteBalanceRedGain = null;
            whiteBalanceGreenGain = null;
            whiteBalanceBlueGain = null;
            applyControlUpdate();
        });
    }

    public synchronized boolean setWhiteBalanceTemperatureKelvin(int value) {
        int temperature = clamp(value, MIN_WHITE_BALANCE_KELVIN, MAX_WHITE_BALANCE_KELVIN);
        if (!manualWhiteBalanceSupported || sensorMetadata == null
                || WhiteBalanceTemperature.cameraNeutralForTemperature(
                        temperature, sensorMetadata.colorCalibration) == null) return false;
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            autoWhiteBalance = false;
            lockedWhiteBalanceNeutral = null;
            captureWhiteBalanceLock = false;
            whiteBalanceTemperatureKelvin = temperature;
            whiteBalanceRedGain = null;
            whiteBalanceGreenGain = null;
            whiteBalanceBlueGain = null;
            applyControlUpdate();
        });
    }

    /** Holds the current exposure, focus and white balance fixed, as used by panorama. */
    public synchronized boolean freezeCaptureControls(int sensitivity, long durationNs) {
        return freezeCaptureControls(sensitivity, durationNs, true, true, true);
    }

    /** Holds the chosen controls at their current values, as used by locked video recording. The others keep running. */
    public synchronized boolean freezeCaptureControls(int sensitivity, long durationNs,
                                                      boolean exposure, boolean focus, boolean whiteBalance) {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            if (exposure) {
                iso = clamp(sensitivity, isoMin, isoMax);
                exposureTimeNs = clamp(durationNs, exposureTimeMinNs, exposureTimeMaxNs);
                autoExposure = false;
                externalExposureAutomatic = false;
                longShutter.clear();
            }
            if (focus) {
                if (manualFocusSupported) autoFocus = false;
                resetMetering();
            }
            if (whiteBalance && autoWhiteBalance) {
                if (manualWhiteBalanceSupported && lastAutoWhiteBalanceNeutral != null) {
                    lockedWhiteBalanceNeutral = lastAutoWhiteBalanceNeutral.clone();
                    autoWhiteBalance = false;
                } else captureWhiteBalanceLock = true;
            }
            applyControlUpdate();
        });
    }

    private boolean panoramaControlsApplied(CaptureResult sensorResult, TotalCaptureResult result) {
        if (result == null) return false;
        CaptureRequest request = result.getRequest();
        if (!Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_OFF).equals(request.get(CaptureRequest.CONTROL_AE_MODE))) return false;
        if (manualFocusSupported && !Integer.valueOf(CameraMetadata.CONTROL_AF_MODE_OFF).equals(request.get(CaptureRequest.CONTROL_AF_MODE))) return false;
        if (manualFocusSupported && Integer.valueOf(CameraMetadata.LENS_STATE_MOVING).equals(
                captureValue(sensorResult, result, CaptureResult.LENS_STATE))) return false;
        if (manualWhiteBalanceSupported && !captureWhiteBalanceLock && !Integer.valueOf(CameraMetadata.CONTROL_AWB_MODE_OFF).equals(request.get(CaptureRequest.CONTROL_AWB_MODE))) return false;
        return !captureWhiteBalanceLock || !awbLockAvailable || Boolean.TRUE.equals(request.get(CaptureRequest.CONTROL_AWB_LOCK));
    }

    /** Flash and long exposures use a fresh photo request on the active stream. */
    public synchronized void capturePreviewFrame(RawStillCaptureCallback callback) {
        if (state != State.STREAMING) throw new IllegalStateException("RAW preview is not ready for capture");
        if (longShutter.pendingExposureTimeNs() != null) throw new IllegalStateException("Apply the long shutter before taking a photo");
        stillCaptureCallback = Objects.requireNonNull(callback);
        stillFrameDelivered = false;
        captureInPreviewSession = true;
        previewDngMetadata.clear();
        previewResults.clear();
        captureNextPreviewFrame = false;
        inSessionStillTimestamp = -1L;
        transition(State.STILL_CAPTURING);
        Handler handler = cameraHandler;
        if (handler == null || !handler.post(() -> {
            if (stillCaptureCallback != callback || state != State.STILL_CAPTURING) return;
            if (!validatePendingShutter()) return;
            prepareFlashCapture();
            stillExposure = null;
            if (stillFlashMode.usesCaptureFlash()
                    || manualExposureSupported && !autoExposure && exposureTimeNs >= LongShutterSelection.LIMIT_NS) {
                stillRequestMetadata = snapshotRequestMetadata();
                if (!autoExposure && manualExposureSupported) stillExposure = new StillExposure(iso, exposureTimeNs, exposureTimeNs);
                issueStillCapture(captureSession);
            } else {
                captureNextPreviewFrame = true;
                scheduleStillCaptureTimeout();
            }
        })) {
            fail(new SessionError(ErrorCode.REQUEST, "Camera thread closed before RAW capture", true, null));
        }
    }

    public synchronized boolean stepExposureCompensation(int direction, java.util.function.IntConsumer onChanged) {
        Handler handler = cameraHandler;
        return handler != null && handler.post(() -> {
            if ((!autoExposure && !externalAutoExposure) || exposureCompensationMin >= exposureCompensationMax) return;
            exposureCompensation = clamp(exposureCompensation + clamp(direction, -1, 1),
                    exposureCompensationMin, exposureCompensationMax);
            if (externalAutoExposure) notifyControlsChanged(true);
            else applyControlUpdate();
            onChanged.accept(exposureCompensation);
        });
    }

    public void captureStill(List<RawOutput> availableOutputs,
                             RawStillCaptureCallback callback) {
        captureStill(availableOutputs, EnumSet.allOf(RawFormat.class), callback);
    }

    public synchronized void captureStill(List<RawOutput> availableOutputs,
                                          Set<RawFormat> importableFormats,
                                          RawStillCaptureCallback callback) {
        if (longShutter.pendingExposureTimeNs() != null) throw new IllegalStateException("Apply the long shutter before taking a photo");
        RawOutput selected = StillCapturePlanner.select(
                state, previewOutput, availableOutputs, importableFormats);
        Objects.requireNonNull(callback);
        if (selected.maximumResolutionMode && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw new IllegalArgumentException(
                    "Maximum-resolution still capture requires Android 12 (API 31) or newer");
        }
        stillCaptureCallback = callback;
        stillFrameDelivered = false;
        captureNextPreviewFrame = false;
        previewDngMetadata.clear();
        previewResults.clear();
        captureInPreviewSession = StillCapturePlanner.canReusePreview(previewOutput, selected);
        if (Log.isLoggable("SpektraPerf", Log.DEBUG)) {
            Log.d("SpektraPerf", "still session_reused=" + captureInPreviewSession
                    + " preview=" + previewOutput.size + " capture=" + selected.size);
        }
        inSessionStillTimestamp = -1L;
        transition(State.STILL_CONFIGURING);
        Handler handler = cameraHandler;
        if (handler == null || !handler.post(() -> {
            if (stillCaptureCallback != callback || state != State.STILL_CONFIGURING || !validatePendingShutter()) return;
            prepareFlashCapture();
            stillRequestMetadata = snapshotRequestMetadata();
            stillExposure = null;
            if (manualExposureSupported && !autoExposure) {
                stillExposure = new StillExposure(iso, exposureTimeNs,
                        1_000_000_000L / Math.max(1, aeFpsRange.upper));
            }
            if (captureInPreviewSession && (captureSession != null || captureSessionRestartPending)) issueStillCapture(captureSession);
            else beginStillReconfiguration(selected);
        })) {
            fail(new SessionError(ErrorCode.REQUEST,
                    "Camera thread closed before still capture could start", true, null));
        }
    }

    public synchronized void resumePreview() {
        if (!StillCapturePlanner.canResume(state)) {
            throw new IllegalStateException("Preview can resume only after still capture completion");
        }
        if (captureInPreviewSession) {
            captureInPreviewSession = false;
            captureNextPreviewFrame = false;
            previewDngMetadata.clear();
            previewResults.clear();
            inSessionStillTimestamp = -1L;
            pendingStillImage.clear();
            transition(State.STREAMING);
            Handler handler = cameraHandler;
            if (handler != null) handler.post(this::applyControlUpdate);
            RawStillCaptureCallback callback = stillCaptureCallback;
            stillCaptureCallback = null;
            if (callback != null) callback.onPreviewResumed();
            return;
        }
        transition(State.PREVIEW_RECONFIGURING);
        Handler handler = cameraHandler;
        if (handler == null || !handler.post(this::beginPreviewReconfiguration)) {
            fail(new SessionError(ErrorCode.REQUEST,
                    "Camera thread closed before preview could resume", true, null));
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void start(CameraRoute route, RawOutput output, IntRange aeFpsRange,
                                   RawFrameConsumer consumer, RawSessionListener listener) {
        if (state != State.IDLE) throw new IllegalStateException("Session has already been started");
        this.output = Objects.requireNonNull(output);
        this.previewOutput = output;
        this.route = Objects.requireNonNull(route);
        this.aeFpsRange = Objects.requireNonNull(aeFpsRange);
        if (exactConfiguration != null) this.aeFpsRange = exactConfiguration.fpsRange;
        this.frameConsumer = Objects.requireNonNull(consumer);
        this.listener = Objects.requireNonNull(listener);
        this.physicalCameraId = route.physicalCameraId;
        this.missingWhiteBalanceLogged = false;
        this.rawLayoutLogged = false;

        if (closeRequested) {
            close();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            unsupported("GPU-usage ImageReader requires Android 10 (API 29) or newer");
            return;
        }
        if (output.maximumResolutionMode && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            unsupported("Maximum-resolution sensor mode requires Android 12 (API 31) or newer");
            return;
        }
        requestLensShadingMap = supportsLensShadingMap(route);
        readMeteringCapabilities(route);
        readControlCapabilities(route);
        restoreInitialControls();
        notifyControlsChanged(true);

        cameraThread = new HandlerThread("UnspektrawesomeRawCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            imageReader = createRawReader(output, true);
            imageReader.setOnImageAvailableListener(this::onPreviewImageAvailable, cameraHandler);
            if (exactConfiguration != null && exactConfiguration.auxiliaryJpegSize != null) {
                Size2d size = exactConfiguration.auxiliaryJpegSize;
                auxiliaryJpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG,
                        2, exactConfiguration.jpegReaderUsage);
            }
            replaceMeteringReader();
            transition(State.OPENING);
            openCamera();
        } catch (CameraAccessException | SecurityException | IllegalArgumentException error) {
            fail(new SessionError(ErrorCode.CAMERA_ACCESS,
                    "Unable to open RAW camera route " + route.routeId, true, error));
        } catch (RuntimeException error) {
            fail(new SessionError(ErrorCode.BUFFER,
                    "Unable to allocate the GPU RAW ImageReader", true, error));
        }
    }

    private boolean validatePendingShutter() {
        if (longShutter.pendingExposureTimeNs() == null) return true;
        stillFailure(new SessionError(ErrorCode.REQUEST,
                "Apply the long shutter before taking a photo", false, null));
        return false;
    }

    private void replaceMeteringReader() throws CameraAccessException {
        closeMeteringReader();
        if (companionMode == 1) return;
        if (ispViewfinderSize != null && replaceIspViewfinderReader()) return;
        if (companionMode == 0 && exactConfiguration != null) {
            if (exactConfiguration.companionFormat == 0) return;
            Size2d size = exactConfiguration.companionSize;
            meteringReader = exactConfiguration.companionFormat == ImageFormat.PRIVATE
                    ? ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE,
                            2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                    : ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2);
            meteringReader.setOnImageAvailableListener(this::discardMeteringImage, cameraHandler);
            return;
        }
        if (companionMode == 0 && rawOnlyRequired) return;
        CameraCharacteristics selected = requiredCharacteristics(
                route.characteristicsCameraId);
        StreamConfigurationMap map = selected.get(output.maximumResolutionMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                : CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int format = companionMode == 3 || companionMode == 0 && yuvCompanionRequired
                ? ImageFormat.YUV_420_888 : ImageFormat.PRIVATE;
        Size2d size = companionSize(map == null ? null : map.getOutputSizes(format));
        if (companionMode == 0 && size == null && format == ImageFormat.PRIVATE) {
            format = ImageFormat.YUV_420_888;
            size = companionSize(map == null ? null : map.getOutputSizes(format));
        }
        if (size == null) {
            if (companionMode != 0) throw new IllegalArgumentException("Selected ISP surface is unavailable");
            return;
        }
        yuvCompanionRequired = format == ImageFormat.YUV_420_888;
        // Some HALs accept RAW-only output but leave AWB, AF and RAW calibration inactive.
        try {
            meteringReader = format == ImageFormat.PRIVATE
                    ? ImageReader.newInstance(size.width, size.height, format,
                            2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                    : ImageReader.newInstance(size.width, size.height, format, 2);
            meteringReader.setOnImageAvailableListener(this::discardMeteringImage, cameraHandler);
        } catch (RuntimeException error) {
            closeMeteringReader();
            if (companionMode != 0) throw error;
            InternalLogRecorder.warning("CameraCompanion", "route=" + route.routeId
                    + " rejected_format=" + format + " size=" + size + " reason=" + error);
            if (!yuvCompanionRequired) {
                yuvCompanionRequired = true;
                replaceMeteringReader();
            } else {
                rawOnlyRequired = true;
            }
            return;
        }
        InternalLogRecorder.event("CameraCompanion", "route=" + route.routeId
                + " format=" + format + " size=" + size);
    }

    private boolean replaceIspViewfinderReader() throws CameraAccessException {
        if (rawOnlyRequired) return false;
        int format;
        Size2d size = null;
        if (exactConfiguration != null) {
            // A RAW-only exact session keeps its validated stream layout.
            if (exactConfiguration.companionFormat == 0) return false;
            format = exactConfiguration.companionFormat;
            if (coversProcessingSize(exactConfiguration.companionSize, ispViewfinderSize)) size = exactConfiguration.companionSize;
        } else {
            format = companionMode == 2 && !yuvCompanionRequired ? ImageFormat.PRIVATE : ImageFormat.YUV_420_888;
        }
        if (size == null) {
            CameraCharacteristics selected = requiredCharacteristics(route.characteristicsCameraId);
            StreamConfigurationMap map = selected.get(output.maximumResolutionMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                    : CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size2d> candidates = new ArrayList<>();
            Size[] sizes = map == null ? null : map.getOutputSizes(format);
            if (sizes != null) for (Size candidate : sizes) candidates.add(new Size2d(candidate.getWidth(), candidate.getHeight()));
            size = selectIspViewfinderSize(candidates, output.size, ispViewfinderSize);
        }
        if (size == null) {
            InternalLogRecorder.warning("IspViewfinder", "route=" + route.routeId + " no_stream format=" + format);
            return false;
        }
        try {
            meteringReader = format == ImageFormat.PRIVATE
                    ? ImageReader.newInstance(size.width, size.height, format, ISP_VIEWFINDER_IMAGES,
                            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                    : ImageReader.newInstance(size.width, size.height, format, ISP_VIEWFINDER_IMAGES,
                            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_CPU_READ_OFTEN);
            meteringReader.setOnImageAvailableListener(this::onIspImageAvailable, cameraHandler);
        } catch (RuntimeException error) {
            closeMeteringReader();
            InternalLogRecorder.warning("IspViewfinder", "route=" + route.routeId
                    + " rejected_format=" + format + " size=" + size + " reason=" + error);
            return false;
        }
        pendingIspImage.clear();
        ispMetadata.clear();
        ispResults.clear();
        ispViewfinderStream = true;
        yuvCompanionRequired = format == ImageFormat.YUV_420_888;
        InternalLogRecorder.event("IspViewfinder", "route=" + route.routeId + " format=" + format
                + " size=" + size + " processing=" + ispViewfinderSize);
        return true;
    }

    private static boolean coversProcessingSize(Size2d size, Size2d processing) {
        return Math.max(size.width, size.height) >= Math.max(processing.width, processing.height)
                && Math.min(size.width, size.height) >= Math.min(processing.width, processing.height);
    }

    /** The smallest stream matching the RAW aspect ratio that still covers the processing size. */
    public static Size2d selectIspViewfinderSize(List<Size2d> sizes, Size2d raw, Size2d processing) {
        Comparator<Size2d> order = Comparator.comparingLong(Size2d::area)
                .thenComparingInt(size -> size.width).thenComparingInt(size -> size.height);
        double rawAspect = (double) raw.width / raw.height;
        Size2d covering = null;
        Size2d largest = null;
        for (Size2d size : sizes) {
            if (size.width <= 0 || size.height <= 0) continue;
            if (Math.abs((double) size.width / size.height - rawAspect) > rawAspect * 0.01) continue;
            if (largest == null || order.compare(size, largest) > 0) largest = size;
            if (coversProcessingSize(size, processing) && (covering == null || order.compare(size, covering) < 0)) covering = size;
        }
        if (covering != null) return covering;
        if (largest != null) return largest;
        return selectCompanionSize(sizes, raw);
    }

    private void onIspImageAvailable(ImageReader reader) {
        if (reader != meteringReader) return;
        long startedNs = SystemClock.elapsedRealtimeNanos();
        Image image;
        try {
            image = reader.acquireNextImage();
        } catch (IllegalStateException error) {
            if (reader == meteringReader) {
                fail(new SessionError(ErrorCode.BUFFER, "Unable to read the preview stream", true, error));
            }
            return;
        }
        if (image == null) return;
        companionFrames++;
        streamDiagnostics.companionDrained(SystemClock.elapsedRealtimeNanos() - startedNs);
        if (!acceptsPreviewFrames()) {
            closeImage(image);
            return;
        }
        long timestamp = image.getTimestamp();
        pendingIspImage.offer(timestamp, image);
        RawCaptureMetadata captured = ispMetadata.take(timestamp);
        if (captured == null) return;
        PendingFrameQueue.Entry<Image> entry = pendingIspImage.takeIfTimestamp(timestamp);
        if (entry != null) deliverIsp(entry.value, captured);
        else ispResults.take(timestamp);
    }

    private volatile int rawRowStrideBytes;

    /** Remembers the RAW layout while the viewfinder is fed by the ISP and RAW frames are skipped. */
    private void recordRawRowStride(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length != 1) return;
            int stride = rawLayoutOverride.stride(planes[0].getRowStride(), output.format, output.size);
            if (stride > 0) rawRowStrideBytes = stride;
        } catch (RuntimeException ignored) {
        }
    }

    private void deliverIsp(Image image, RawCaptureMetadata captured) {
        IspHardwareFrame delivered = null;
        try {
            if (!frameConsumer.shouldProcessIspFrame(captured.sensorTimestampNs)) {
                ispResults.take(captured.sensorTimestampNs);
                return;
            }
            RawOutput rawOutput = output;
            if (rawOutput == null) {
                ispResults.take(captured.sensorTimestampNs);
                return;
            }
            HardwareBuffer hardwareBuffer = null;
            try { hardwareBuffer = image.getHardwareBuffer(); } catch (RuntimeException ignored) { }
            delivered = new IspHardwareFrame(image, hardwareBuffer, captured, ispResults.take(captured.sensorTimestampNs),
                    rawLayoutOverride.output(rawOutput), rawRowStrideBytes);
            frameConsumer.onIspFrame(delivered);
        } catch (RuntimeException error) {
            // The RAW stream keeps running; the consumer falls back to the RAW viewfinder on its own failures.
            InternalLogRecorder.warning("IspViewfinder", "delivery_failed " + error);
        } finally {
            if (delivered != null) delivered.close();
            else closeImage(image);
        }
    }

    private void discardMeteringImage(ImageReader reader) {
        if (reader != meteringReader) return;
        long startedNs = SystemClock.elapsedRealtimeNanos();
        boolean acquired = false;
        try (Image ignored = reader.acquireLatestImage()) {
            acquired = ignored != null;
            if (acquired) companionFrames++;
        } catch (IllegalStateException error) {
            if (reader == meteringReader) {
                fail(new SessionError(ErrorCode.BUFFER,
                        "Unable to read the preview stream", true, error));
            }
        } finally {
            if (acquired) streamDiagnostics.companionDrained(SystemClock.elapsedRealtimeNanos() - startedNs);
        }
    }

    private Size2d companionSize(Size[] sizes) {
        List<Size2d> candidates = new ArrayList<>();
        if (sizes != null) {
            for (Size size : sizes) candidates.add(new Size2d(size.getWidth(), size.getHeight()));
        }
        return selectCompanionSize(candidates, output.size);
    }

    public static Size2d selectCompanionSize(List<Size2d> sizes, Size2d raw) {
        Size2d smallest = null;
        Size2d matching = null;
        Comparator<Size2d> order = Comparator.comparingLong(Size2d::area)
                .thenComparingInt(size -> size.width).thenComparingInt(size -> size.height);
        double rawAspect = (double) raw.width / raw.height;
        for (Size2d size : sizes) {
            if (size.width <= 0 || size.height <= 0) continue;
            if (smallest == null || order.compare(size, smallest) < 0) smallest = size;
            // Minor RAW aspect differences should not force a full-resolution companion.
            if (Math.abs((double) size.width / size.height - rawAspect) <= rawAspect * 0.01
                    && (matching == null || order.compare(size, matching) < 0)) matching = size;
        }
        return matching == null ? smallest : matching;
    }

    private void closeMeteringReader() {
        ImageReader reader = meteringReader;
        meteringReader = null;
        ispViewfinderStream = false;
        pendingIspImage.clear();
        if (reader == null) return;
        try { reader.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) { }
        try { reader.close(); } catch (RuntimeException ignored) { }
    }

    private static void closeImage(Image image) {
        try { image.close(); } catch (RuntimeException ignored) { }
    }

    private Runnable retainRawReader(ImageReader reader) {
        synchronized (rawReaderLock) {
            rawReaderLeases.put(reader, rawReaderLeases.getOrDefault(reader, 0) + 1);
        }
        return () -> {
            synchronized (rawReaderLock) {
                int remaining = rawReaderLeases.get(reader) - 1;
                if (remaining == 0) {
                    rawReaderLeases.remove(reader);
                    if (retiredRawReaders.remove(reader)) reader.close();
                } else rawReaderLeases.put(reader, remaining);
            }
        };
    }

    private void retireRawReader(ImageReader reader) {
        synchronized (rawReaderLock) {
            if (rawReaderLeases.containsKey(reader)) retiredRawReaders.add(reader);
            else reader.close();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() throws CameraAccessException {
        cameraOpening = true;
        try {
            cameraManager.openCamera(route.openCameraId, handlerExecutor(), deviceCallback());
        } catch (CameraAccessException | RuntimeException error) {
            cameraOpening = false;
            throw error;
        }
    }

    private void closeCameraDevice(CameraDevice device) {
        recordingRequests.stop();
        controlRequests.cancelAndWait();
        if (cameraDevice == device) cameraDevice = null;
        closingCameraDevice = device;
        try { device.close(); }
        catch (RuntimeException error) {
            InternalLogRecorder.error("Camera2", "Camera did not close", error);
        }
    }

    private boolean terminalState() {
        return closeRequested || state == State.CLOSING || state == State.CLOSED || state == State.ERROR;
    }

    private CameraDevice.StateCallback deviceCallback() {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                cameraOpening = false;
                if (terminalState()) {
                    closeCameraDevice(device);
                    return;
                }
                cameraDevice = device;
                configureSession(SessionMode.PREVIEW);
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                cameraOpening = false;
                if (device == closingCameraDevice) return;
                closeCameraDevice(device);
                fail(new SessionError(ErrorCode.CAMERA_DISCONNECTED,
                        "Camera disconnected while streaming RAW", true, null));
            }

            @Override
            public void onError(CameraDevice device, int error) {
                cameraOpening = false;
                if (device == closingCameraDevice) return;
                if (retryGpuBufferConfiguration() || retryCompanionConfiguration()) return;
                closeCameraDevice(device);
                fail(new SessionError(ErrorCode.CAMERA_DEVICE,
                        "Camera device error " + error, true, null));
            }

            @Override
            public void onClosed(CameraDevice device) {
                if (device != closingCameraDevice) return;
                closingCameraDevice = null;
                if ((reopenForStreamRetry || reopenForExposureChange) && !terminalState()) {
                    boolean exposureRestart = reopenForExposureChange;
                    reopenForStreamRetry = false;
                    reopenForExposureChange = false;
                    pendingImage.clear();
                    metadata.clear();
                    replaceReader(exposureRestart ? output : previewOutput,
                            Camera2RawSession.this::onPreviewImageAvailable);
                    if (terminalState()) return;
                    if (!exposureRestart && state != State.PREVIEW_RECONFIGURING) transition(State.OPENING);
                    try {
                        openCamera();
                    } catch (CameraAccessException | RuntimeException error) {
                        fail(new SessionError(ErrorCode.CAMERA_ACCESS,
                                "Unable to reopen RAW camera", true, error));
                    }
                } else if (terminalState()) {
                    stopCameraThread();
                    resourcesReleased.countDown();
                }
            }
        };
    }

    private boolean retryGpuBufferConfiguration() {
        if (terminalState() || !gpuBufferUsage || rawInputStarted || cameraDevice == null
                || state != State.CONFIGURING && state != State.STREAMING && state != State.PREVIEW_RECONFIGURING) return false;
        rejectedGpuBufferOutputs.add(output);
        gpuBufferUsage = false;
        cancelGpuBufferTimeout();
        reopenForStreamRetry = true;
        InternalLogRecorder.warning("CameraRawBuffer", "route=" + route.routeId
                + " format=" + output.format + " retry=CPU");
        closeCameraDevice(cameraDevice);
        return true;
    }

    private boolean retryCompanionConfiguration() {
        if (companionMode != 0 || exactConfiguration != null || closeRequested) return false;
        if (state != State.CONFIGURING || meteringReader == null
                || rawOnlyRequired || cameraDevice == null) return false;
        boolean yuvAvailable = false;
        if (!yuvCompanionRequired) {
            try {
                CameraCharacteristics selected = requiredCharacteristics(route.characteristicsCameraId);
                StreamConfigurationMap map = selected.get(output.maximumResolutionMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                        : CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                yuvAvailable = map != null
                        && companionSize(map.getOutputSizes(ImageFormat.YUV_420_888)) != null;
            } catch (CameraAccessException | RuntimeException error) {
                InternalLogRecorder.warning("CameraCompanion", "route=" + route.routeId
                        + " yuv_sizes_unavailable=" + error);
            }
        }
        yuvCompanionRequired = yuvAvailable;
        rawOnlyRequired = !yuvAvailable;
        reopenForStreamRetry = true;
        InternalLogRecorder.warning("CameraCompanion", "route=" + route.routeId
                + " retry=" + (yuvAvailable ? "YUV" : "RAW-only"));
        closeCameraDevice(cameraDevice);
        return true;
    }

    private void configureSession(SessionMode mode) {
        if (mode == SessionMode.PREVIEW && state == State.OPENING) transition(State.CONFIGURING);
        try {
            OutputConfiguration configuration = new OutputConfiguration(imageReader.getSurface());
            configureSensorPixelMode(configuration);
            if (exactConfiguration != null && exactConfiguration.rawStreamUseCase != 0L
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                configuration.setStreamUseCase(exactConfiguration.rawStreamUseCase);
            }
            if (route.physicalCameraId != null) {
                configuration.setPhysicalCameraId(route.physicalCameraId);
            }
            List<OutputConfiguration> configurations = new ArrayList<>();
            configurations.add(configuration);
            if (auxiliaryJpegReader != null) {
                // Configured for HAL geometry, never targeted by capture requests.
                OutputConfiguration jpeg = new OutputConfiguration(auxiliaryJpegReader.getSurface());
                configureSensorPixelMode(jpeg);
                if (route.physicalCameraId != null) jpeg.setPhysicalCameraId(route.physicalCameraId);
                configurations.add(jpeg);
            }
            if (meteringReader != null) {
                OutputConfiguration metering = new OutputConfiguration(meteringReader.getSurface());
                configureSensorPixelMode(metering);
                if (route.physicalCameraId != null && (exactConfiguration == null
                        || exactConfiguration.physicalCompanion)) metering.setPhysicalCameraId(route.physicalCameraId);
                configurations.add(metering);
            }
            SessionConfiguration sessionConfiguration = new SessionConfiguration(
                    vendorOverrides.getSessionMode(),
                    configurations, handlerExecutor(), sessionCallback(mode));
            CaptureRequest.Builder parameterBuilder = createCaptureRequest(
                    mode == SessionMode.PREVIEW ? CameraDevice.TEMPLATE_PREVIEW : CameraDevice.TEMPLATE_STILL_CAPTURE,
                    mode == SessionMode.PREVIEW);
            vendorOverrides.applySession(parameterBuilder);
            CaptureRequest parameters = parameterBuilder.build();
            sessionConfiguration.setSessionParameters(parameters);
            logStreamConfiguration(parameters);
            cameraDevice.createCaptureSession(sessionConfiguration);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            handleConfigurationFailure(mode, new SessionError(ErrorCode.SESSION_CONFIGURATION,
                    "Unable to configure the RAW capture session: " + error.getMessage(), mode == SessionMode.PREVIEW,
                    error));
        }
    }

    private void configureSensorPixelMode(OutputConfiguration configuration) {
        if (output.maximumResolutionMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            configuration.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
    }

    private CameraCaptureSession.StateCallback sessionCallback(SessionMode mode) {
        CameraDevice configuredDevice = cameraDevice;
        return new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                if (terminalState() || configuredDevice != cameraDevice) {
                    session.close();
                    return;
                }
                captureSession = session;
                if (exactConfiguration != null && exactConfiguration.startupDelayMs > 0) {
                    cameraHandler.postDelayed(() -> {
                        if (!terminalState() && configuredDevice == cameraDevice && session == captureSession) {
                            startConfiguredSession(session, mode);
                        }
                    }, exactConfiguration.startupDelayMs);
                } else startConfiguredSession(session, mode);
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                session.close();
                if (terminalState() || configuredDevice != cameraDevice) return;
                handleConfigurationFailure(mode,
                        new SessionError(ErrorCode.SESSION_CONFIGURATION,
                                "Camera rejected the RAW output configuration",
                                mode == SessionMode.PREVIEW, null));
            }

            @Override
            public void onReady(CameraCaptureSession session) {
                if (session != captureSession || captureSessionRestartPending) return;
                singleCaptureIdle = true;
                if (recordingRequests.active()) {
                    submitRecordingBurst(false);
                    return;
                }
                if (longPreviewImageDelivered) finishLongPreview(0L);
                else if (state == State.STREAMING && usesLongPreview()) queueLongPreview(0L);
            }

            @Override
            public void onClosed(CameraCaptureSession session) {
                onSessionClosed(session);
            }
        };
    }

    private void startConfiguredSession(CameraCaptureSession session, SessionMode mode) {
        singleCaptureIdle = true;
        singleCaptureUsed = false;
        if (captureSessionRestartPending) {
            captureSessionRestartPending = false;
            boolean submitStill = stillRequestAwaitingRestart && state == State.STILL_CAPTURING && !stillFrameDelivered;
            stillRequestAwaitingRestart = false;
            if (submitStill) {
                try {
                    repeatingRequestBuilder = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, true);
                    submitStillCapture(session);
                } catch (CameraAccessException error) {
                    stillFailure(new SessionError(ErrorCode.REQUEST, "Unable to restart the long RAW photo", false, error));
                }
            } else startRepeating(session);
            return;
        }
        if (mode == SessionMode.PREVIEW) {
            startRepeating(session);
        } else {
            issueStillCapture(session);
        }
    }

    private CaptureRequest.Builder createCaptureRequest(int template, boolean preview) throws CameraAccessException {
        if (template == CameraDevice.TEMPLATE_PREVIEW && exactConfiguration != null) {
            template = exactConfiguration.previewRequestTemplate;
        }
        CaptureRequest.Builder request = cameraDevice.createCaptureRequest(template);
        request.addTarget(imageReader.getSurface());
        applyCommonRequestControls(request, preview);
        applyMeteringControls(request);
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        vendorOverrides.applyRequest(request);
        return request;
    }

    private CaptureRequest buildRequest(CaptureRequest.Builder builder) {
        vendorOverrides.applyRequest(builder);
        return builder.build();
    }

    private void startRepeating(CameraCaptureSession session) {
        recordingRequests.stop();
        controlRequests.cancelAndWait();
        try {
            captureRequestGeneration++;
            cancelLongPreviewTimeout();
            cameraHandler.removeCallbacks(longPreviewRequest);
            longPreviewInFlight = false;
            longPreviewImageDelivered = false;
            captureSessionRestartPending = false;
            stillRequestAwaitingRestart = false;
            CaptureRequest.Builder request = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, true);
            repeatingRequestBuilder = request;
            streamDiagnostics.reset();
            repeatingPreviewActive = !usesLongPreview();
            if (repeatingPreviewActive) repeatRequest(session, buildRequest(request), captureCallback, cameraHandler);
            boolean resumed = state == State.PREVIEW_RECONFIGURING;
            transition(State.STREAMING);
            scheduleGpuBufferTimeout();
            if (usesLongPreview()) queueLongPreview(0L);
            else if (recording) applyControlUpdate(false);
            if (resumed) {
                RawStillCaptureCallback callback = stillCaptureCallback;
                stillCaptureCallback = null;
                if (callback != null) {
                    try { callback.onPreviewResumed(); }
                    catch (RuntimeException error) {
                        report(new SessionError(ErrorCode.CONSUMER,
                                "Preview-resumed callback failed", false, error));
                    }
                }
            }
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            if (retryGpuBufferConfiguration() || retryCompanionConfiguration()) return;
            fail(new SessionError(ErrorCode.REQUEST,
                    "Unable to start the RAW repeating request", true, error));
        }
    }

    private void issueStillCapture(CameraCaptureSession session) {
        try {
            if (captureInPreviewSession && stillExposure != null &&
                    stillExposure.exposureTimeNs >= LongShutterSelection.LIMIT_NS) {
                discardInFlightCaptures();
                stillRequestMetadata = snapshotRequestMetadata();
                if (captureSessionRestartPending) {
                    stillRequestAwaitingRestart = true;
                    transition(State.STILL_CAPTURING);
                    scheduleStillCaptureTimeout();
                    return;
                }
            }
            submitStillCapture(session);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            stillFailure(new SessionError(ErrorCode.REQUEST,
                    "Unable to interrupt the preview for a RAW photo", false, error));
        }
    }

    private void prepareFlashCapture() {
        cancelFlashMetering();
        stillFlashMode = flashAvailable ? flashMode : FlashMode.OFF;
        stillFlashRequestMode = stillFlashMode;
        flashMetered = externalAutoExposure;
        stillFlashRequired = stillFlashMode == FlashMode.SINGLE || externalAutoExposure &&
                stillFlashMode == FlashMode.AUTO && FlashMeteringSequence.isDark(exposureTimeNs, iso, meteredAperture);
        flashPreviewPaused = false;
    }

    private void applyFlashControls(CaptureRequest.Builder request, boolean preview, boolean requestAutoExposure) {
        FlashMode mode = flashAvailable ? (preview ? flashMode : stillFlashRequestMode) : FlashMode.OFF;
        request.set(CaptureRequest.CONTROL_AE_MODE, requestAutoExposure
                ? preview ? CameraMetadata.CONTROL_AE_MODE_ON : FlashMeteringSequence.aeMode(mode, availableAeModes)
                : CameraMetadata.CONTROL_AE_MODE_OFF);
        request.set(CaptureRequest.FLASH_MODE, mode == FlashMode.TORCH ? CameraMetadata.FLASH_MODE_TORCH
                : !preview && mode.usesCaptureFlash() && stillFlashRequired ? CameraMetadata.FLASH_MODE_SINGLE
                : CameraMetadata.FLASH_MODE_OFF);
        if (!preview && stillFlashMode.usesCaptureFlash()) {
            request.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        }
    }

    private void beginFlashMetering(CameraCaptureSession session) {
        int aeMode = FlashMeteringSequence.aeMode(stillFlashRequestMode, availableAeModes);
        Log.i("UnspekFlash", "meter route=" + route.routeId + " format=" + output.format
                + " mode=" + stillFlashMode + " metering=" + stillFlashRequestMode + " ae=" + aeMode + " autoExposure=" + autoExposure
                + " iso=" + iso + " exposure=" + exposureTimeNs);
        FlashMeteringSequence sequence = new FlashMeteringSequence(stillFlashRequestMode, aeMode);
        flashMetering = sequence;
        flashPreviewPaused = captureInPreviewSession;
        transition(State.STILL_CAPTURING);
        try {
            CaptureRequest.Builder request = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, true);
            request.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            request.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
            if (aeLockAvailable) request.set(CaptureRequest.CONTROL_AE_LOCK, false);
            CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession source, CaptureRequest request, TotalCaptureResult result) {
                    if (flashMetering != sequence || captureSession != source || !isCurrentRequest(request)) return;
                    CaptureResult sensorResult = sensorCaptureResult(result, physicalCameraId);
                    boolean ready = sequence.update(Integer.valueOf(CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                                    .equals(request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)), result.getFrameNumber(),
                            result.get(CaptureResult.CONTROL_AE_STATE), result.get(CaptureResult.FLASH_STATE),
                            FlashMeteringSequence.isDark(captureValue(sensorResult, result, CaptureResult.SENSOR_EXPOSURE_TIME),
                                    captureValue(sensorResult, result, CaptureResult.SENSOR_SENSITIVITY),
                                    captureValue(sensorResult, result, CaptureResult.LENS_APERTURE)));
                    if (ready) finishFlashMetering(source, sequence);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession source, CaptureRequest request, CaptureFailure failure) {
                    if (flashMetering == sequence) stillFailure(new SessionError(ErrorCode.CAPTURE_FAILURE,
                            "Unable to meter the photo flash", false, null));
                }
            };
            repeatRequest(session, buildRequest(request), callback, cameraHandler);
            repeatingPreviewActive = false;
            request.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            session.capture(buildRequest(request), callback, cameraHandler);
            flashMeteringTimeout = () -> {
                if (flashMetering != sequence) return;
                if (sequence.receivedResult()) finishFlashMetering(session, sequence);
                else stillFailure(new SessionError(ErrorCode.CAPTURE_FAILURE,
                        "The camera did not return flash metering. Try the photo again.", false, null));
            };
            cameraHandler.postDelayed(flashMeteringTimeout, 3_000L);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            stillFailure(new SessionError(ErrorCode.REQUEST, "Unable to prepare the photo flash", false, error));
        }
    }

    private void finishFlashMetering(CameraCaptureSession session, FlashMeteringSequence sequence) {
        if (flashMetering != sequence || state != State.STILL_CAPTURING) return;
        stillFlashRequired = sequence.needsFlash();
        Log.i("UnspekFlash", "metered route=" + route.routeId + " mode=" + stillFlashMode
                + " needsFlash=" + stillFlashRequired);
        cancelFlashMetering();
        if (sequence.needsForcedFlashMetering(autoExposure)) {
            // Some RAW streams report FLASH_REQUIRED but never meter or fire in native Auto.
            stillFlashRequestMode = FlashMode.SINGLE;
            beginFlashMetering(session);
            return;
        }
        if (stillFlashMode == FlashMode.AUTO && !stillFlashRequired) stillFlashRequestMode = FlashMode.OFF;
        flashMetered = true;
        pendingImage.clear();
        submitStillCapture(session);
    }

    private void cancelFlashMetering() {
        if (flashMetering != null && captureSession != null) {
            controlRequests.cancelAndWait();
            try { captureSession.stopRepeating(); }
            catch (CameraAccessException | IllegalStateException ignored) { }
        }
        flashMetering = null;
        if (flashMeteringTimeout != null && cameraHandler != null) cameraHandler.removeCallbacks(flashMeteringTimeout);
        flashMeteringTimeout = null;
    }

    private void restoreFlashPreview() {
        if (!flashPreviewPaused || captureSession == null || repeatingRequestBuilder == null) return;
        controlRequests.cancelAndWait();
        flashPreviewPaused = false;
        if (usesLongPreview()) return;
        try {
            applyCommonRequestControls(repeatingRequestBuilder, true);
            applyMeteringControls(repeatingRequestBuilder);
            repeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            captureSession.capture(buildRequest(repeatingRequestBuilder), captureCallback, cameraHandler);
            repeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            repeatRequest(captureSession, buildRequest(repeatingRequestBuilder), captureCallback, cameraHandler);
            repeatingPreviewActive = true;
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            report(new SessionError(ErrorCode.REQUEST, "Unable to restore preview after flash", false, error));
        }
    }

    private void submitStillCapture(CameraCaptureSession session) {
        recordingRequests.stop();
        controlRequests.cancelAndWait();
        if (stillFlashMode.usesCaptureFlash() && !flashMetered) {
            beginFlashMetering(session);
            return;
        }
        try {
            CaptureRequest.Builder request = createCaptureRequest(
                    captureInPreviewSession ? CameraDevice.TEMPLATE_PREVIEW : CameraDevice.TEMPLATE_STILL_CAPTURE, false);
            if (captureInPreviewSession && repeatingRequestBuilder != null) {
                CameraCharacteristics characteristics = requiredCharacteristics(route.characteristicsCameraId);
                List<CaptureRequest.Key<?>> sessionKeys = characteristics.getAvailableSessionKeys();
                if (sessionKeys != null) {
                    for (CaptureRequest.Key<?> key : sessionKeys) copyRequestValue(repeatingRequestBuilder, request, key);
                }
            }
            // Flash must retain its exposure mode even when it is also a session key.
            applyFlashControls(request, false, autoExposure && stillExposure == null);
            // Lock the metered exposure without switching AE off and restarting its scan.
            if (autoExposure && !stillFlashMode.usesCaptureFlash() && aeLockAvailable && Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_ON).equals(
                    request.get(CaptureRequest.CONTROL_AE_MODE))) {
                request.set(CaptureRequest.CONTROL_AE_LOCK, true);
            }
            transition(State.STILL_CAPTURING);
            singleCaptureIdle = false;
            singleCaptureUsed = true;
            session.capture(buildRequest(request), stillCaptureResultCallback, cameraHandler);
            RawStillCaptureCallback callback = stillCaptureCallback;
            if (callback != null) callback.onStillCaptureRequested(stillExposure == null
                    ? exposureTimeNs : stillExposure.exposureTimeNs);
            scheduleStillCaptureTimeout();
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            stillFailure(new SessionError(ErrorCode.REQUEST,
                    "Unable to issue the full-resolution RAW still request", false, error));
        }
    }

    private static <T> void copyRequestValue(CaptureRequest.Builder source, CaptureRequest.Builder target,
                                             CaptureRequest.Key<T> key) {
        T value = source.get(key);
        if (value != null) target.set(key, value);
    }

    private void beginStillReconfiguration(RawOutput captureOutput) {
        replaceSession(() -> {
            replaceReader(captureOutput, this::onStillImageAvailable, false);
            if (state == State.STILL_CONFIGURING) configureSession(SessionMode.STILL);
        });
    }

    private void beginPreviewReconfiguration() {
        replaceSession(() -> {
            replaceReader(previewOutput, this::onPreviewImageAvailable);
            if (state == State.PREVIEW_RECONFIGURING) configureSession(SessionMode.PREVIEW);
        });
    }

    private void replaceSession(Runnable afterClose) {
        recordingRequests.stop();
        controlRequests.cancelAndWait();
        previewDngMetadata.clear();
        previewResults.clear();
        pendingImage.clear();
        pendingStillImage.clear();
        metadata.clear();
        CameraCaptureSession session = captureSession;
        if (session == null) {
            afterClose.run();
            return;
        }
        afterSessionClosed = afterClose;
        closingSession = session;
        try { session.stopRepeating(); }
        catch (CameraAccessException | IllegalStateException ignored) { }
        try { session.abortCaptures(); }
        catch (CameraAccessException | IllegalStateException ignored) { }
        session.close();
    }

    private void onSessionClosed(CameraCaptureSession session) {
        if (captureSession == session) captureSession = null;
        if (closingSession != session) return;
        closingSession = null;
        Runnable continuation = afterSessionClosed;
        afterSessionClosed = null;
        if (continuation != null && state != State.CLOSING && state != State.CLOSED
                && state != State.ERROR) {
            continuation.run();
        }
    }

    private void replaceReader(RawOutput nextOutput,
                               ImageReader.OnImageAvailableListener imageListener) {
        replaceReader(nextOutput, imageListener, true);
    }

    private void replaceReader(RawOutput nextOutput,
                               ImageReader.OnImageAvailableListener imageListener, boolean preview) {
        ImageReader previous = imageReader;
        imageReader = null;
        if (previous != null) {
            previous.setOnImageAvailableListener(null, null);
            retireRawReader(previous);
        }
        try {
            output = nextOutput;
            imageReader = createRawReader(nextOutput, preview);
            imageReader.setOnImageAvailableListener(imageListener, cameraHandler);
            replaceMeteringReader();
        } catch (CameraAccessException | RuntimeException error) {
            if (state == State.STILL_CONFIGURING) {
                stillFailure(new SessionError(ErrorCode.BUFFER,
                        "Unable to allocate the full-resolution GPU RAW ImageReader",
                        false, error));
            } else {
                fail(new SessionError(ErrorCode.BUFFER,
                        "Unable to restore the preview GPU RAW ImageReader", true, error));
            }
        }
    }

    private void handleConfigurationFailure(SessionMode mode, SessionError error) {
        if (mode == SessionMode.PREVIEW && (retryGpuBufferConfiguration() || retryCompanionConfiguration())) return;
        if (mode == SessionMode.STILL) stillFailure(error); else fail(error);
    }

    private void stillFailure(SessionError error) {
        State current = state;
        if (current == State.CLOSING || current == State.CLOSED || current == State.ERROR) return;
        cancelStillCaptureTimeout();
        cancelFlashMetering();
        restoreFlashPreview();
        report(error);
        transition(State.STILL_FAILED);
        RawStillCaptureCallback callback = stillCaptureCallback;
        if (callback != null) {
            try { callback.onStillCaptureError(error); }
            catch (RuntimeException callbackError) {
                report(new SessionError(ErrorCode.CONSUMER,
                        "Still-capture error callback failed", false, callbackError));
            }
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                             long timestamp, long frameNumber) {
                    if (!acceptsPreviewFrames() || !isCurrentRequest(request)) return;
                    if (recording && com.unspektrawesome.BuildConfig.DEBUG && Log.isLoggable("VideoFrame", Log.VERBOSE))
                        InternalLogRecorder.event("VideoFrame", "capture frame=" + frameNumber + " sensor_ns=" + timestamp
                                + " " + recordingRequests.diagnosticState());
                    recordingFrameStarted(request);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    if (!acceptsPreviewFrames() || !isCurrentRequest(request)) return;
                    recordingFrameStarted(request);
                    updateMeteringState(request, result);
                    updateAutoControlValues(
                            sensorCaptureResult(result, physicalCameraId), result);
                    RawCaptureMetadata captured = captureMetadata(result, physicalCameraId);
                    if (captured == null) return;
                    logCaptureCadence(request, result, captured);
                    metadata.put(captured.sensorTimestampNs, captured);
                    CaptureResult sensorResult = sensorCaptureResult(result, physicalCameraId);
                    previewResults.put(captured.sensorTimestampNs, sensorResult);
                    if (captureNextPreviewFrame) previewDngMetadata.put(captured.sensorTimestampNs, sensorResult);
                    if (ispViewfinderStream) {
                        ispMetadata.put(captured.sensorTimestampNs, captured);
                        ispResults.put(captured.sensorTimestampNs, sensorResult);
                        PendingFrameQueue.Entry<Image> ispImage = pendingIspImage.takeIfTimestamp(captured.sensorTimestampNs);
                        if (ispImage != null) {
                            RawCaptureMetadata exact = ispMetadata.take(captured.sensorTimestampNs);
                            if (exact != null) deliverIsp(ispImage.value, exact);
                            else closeImage(ispImage.value);
                        }
                    }
                    if (preservesRecordingFrames()) {
                        deliverPendingRecordingFrames();
                        return;
                    }
                    PendingFrameQueue.Entry<Image> image =
                            captureInPreviewSession ? pendingImage.takeExact(captured.sensorTimestampNs)
                                    : pendingImage.takeIfTimestamp(captured.sensorTimestampNs);
                    if (image != null) {
                        RawCaptureMetadata exact = metadata.take(captured.sensorTimestampNs);
                        deliverPreview(image.value, exact);
                    }
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                            CaptureFailure failure) {
                    if (!acceptsPreviewFrames() || !isCurrentRequest(request)) return;
                    recordingFrameStarted(request);
                    report(new SessionError(ErrorCode.CAPTURE_FAILURE,
                            "RAW capture failed for frame " + failure.getFrameNumber() + " reason=" + failure.getReason(), false, null));
                    finishLongPreview(100L);
                }

                @Override
                public void onCaptureBufferLost(CameraCaptureSession session, CaptureRequest request,
                                                android.view.Surface target, long frameNumber) {
                    if (!acceptsPreviewFrames() || !isCurrentRequest(request)) return;
                    InternalLogRecorder.warning("CameraBuffer", "route=" + route.routeId
                            + " lost_frame=" + frameNumber + " stream="
                            + (imageReader != null && target.equals(imageReader.getSurface()) ? "RAW" : "companion"));
                    if (imageReader != null && target.equals(imageReader.getSurface())) {
                        if (retryGpuBufferConfiguration()) return;
                        finishLongPreview(100L);
                    }
                }
            };

    private final CameraCaptureSession.CaptureCallback stillCaptureResultCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                             long timestamp, long frameNumber) {
                    if (state != State.STILL_CAPTURING || stillFrameDelivered || !isCurrentRequest(request)) return;
                    if (captureInPreviewSession) identifyStillImage(timestamp);
                    scheduleStillCaptureTimeout(true);
                    RawStillCaptureCallback callback = stillCaptureCallback;
                    if (callback != null) callback.onStillCaptureStarted(stillExposure == null
                            ? exposureTimeNs : stillExposure.exposureTimeNs);
                }
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    if (state != State.STILL_CAPTURING || stillFrameDelivered || !isCurrentRequest(request)) return;
                    restoreFlashPreview();
                    RawCaptureMetadata captured = captureMetadata(result, physicalCameraId);
                    if (captured == null) {
                        stillFailure(new SessionError(ErrorCode.CAPTURE_FAILURE,
                                "RAW still result has no sensor timestamp", false, null));
                        return;
                    }
                    if (captureInPreviewSession) identifyStillImage(captured.sensorTimestampNs);
                    stillDngResult = sensorCaptureResult(result, physicalCameraId);
                    metadata.put(captured.sensorTimestampNs, captured);
                    PendingFrameQueue.Entry<Image> image =
                            (captureInPreviewSession ? pendingStillImage : pendingImage).takeIfTimestamp(captured.sensorTimestampNs);
                    if (image != null) {
                        RawCaptureMetadata exact = metadata.take(captured.sensorTimestampNs);
                        deliverStill(image.value, exact);
                    }
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                            CaptureFailure failure) {
                    if (state != State.STILL_CAPTURING || stillFrameDelivered || !isCurrentRequest(request)) return;
                    stillFailure(new SessionError(ErrorCode.CAPTURE_FAILURE,
                            "Full-resolution RAW capture failed for frame "
                                    + failure.getFrameNumber() + " reason=" + failure.getReason(), false, null));
                }
            };

    private boolean isCurrentRequest(CaptureRequest request) {
        Object tag = request.getTag();
        return tag instanceof RequestMetadata &&
                ((RequestMetadata) tag).captureGeneration == captureRequestGeneration;
    }

    private boolean acceptsPreviewFrames() {
        return state == State.STREAMING || captureInPreviewSession
                && (state == State.STILL_CONFIGURING || state == State.STILL_CAPTURING
                    || state == State.STILL_CAPTURED);
    }

    private void identifyStillImage(long timestamp) {
        inSessionStillTimestamp = timestamp;
        PendingFrameQueue.Entry<Image> earlyImage = pendingImage.takeExact(timestamp);
        if (earlyImage != null) pendingStillImage.offer(timestamp, earlyImage.value);
    }

    private void onPreviewImageAvailable(ImageReader reader) {
        onImageAvailable(reader, false);
    }

    private void onStillImageAvailable(ImageReader reader) {
        onImageAvailable(reader, true);
    }

    private void onImageAvailable(ImageReader reader, boolean stillImage) {
        if (reader != imageReader) return;
        Image image;
        long acquireStartedNs = SystemClock.elapsedRealtimeNanos();
        try {
            image = captureInPreviewSession || (!stillImage && frameConsumer != null && frameConsumer.preserveFrameOrder())
                    ? reader.acquireNextImage() : reader.acquireLatestImage();
        } catch (IllegalStateException error) {
            if (!stillImage && retryGpuBufferConfiguration()) return;
            fail(new SessionError(ErrorCode.BUFFER,
                    "RAW ImageReader could not acquire the newest image", true, error));
            return;
        }
        if (image == null) return;
        long acquiredNs = SystemClock.elapsedRealtimeNanos();
        if (captureInPreviewSession && image.getTimestamp() == inSessionStillTimestamp) stillImage = true;
        if ((!stillImage && !acceptsPreviewFrames())
                || (stillImage && state != State.STILL_CAPTURING)) {
            image.close();
            return;
        }
        if (!stillImage) {
            rawInputStarted = true;
            cancelGpuBufferTimeout();
            streamDiagnostics.rawAcquired(acquiredNs - acquireStartedNs);
        }
        long timestamp = image.getTimestamp();
        PendingFrameQueue<Image> slot = stillImage && captureInPreviewSession ? pendingStillImage : pendingImage;
        slot.offer(timestamp, image);
        if (!stillImage && preservesRecordingFrames()) {
            deliverPendingRecordingFrames();
            return;
        }
        RawCaptureMetadata exact = metadata.take(timestamp);
        if (exact != null) {
            PendingFrameQueue.Entry<Image> matched = captureInPreviewSession
                    ? slot.takeExact(timestamp) : slot.takeIfTimestamp(timestamp);
            if (matched != null) {
                if (stillImage) deliverStill(matched.value, exact);
                else deliverPreview(matched.value, exact);
            }
        }
    }

    private boolean preservesRecordingFrames() {
        return !captureInPreviewSession && frameConsumer != null && frameConsumer.preserveFrameOrder();
    }

    private void deliverPendingRecordingFrames() {
        PendingFrameQueue.MatchedEntry<Image, RawCaptureMetadata> matched;
        while ((matched = pendingImage.takeOldestMatched(metadata)) != null) {
            deliverPreview(matched.value, matched.metadata);
        }
    }

    private void deliverPreview(Image image, RawCaptureMetadata captured) {
        if (captureNextPreviewFrame) {
            CaptureResult result = previewDngMetadata.take(captured.sensorTimestampNs);
            if (result != null) {
                captureNextPreviewFrame = false;
                stillDngResult = result;
                deliverStill(image, captured);
                return;
            }
        }
        long deliveryStartedNs = SystemClock.elapsedRealtimeNanos();
        boolean consumerSkipped = false;
        RawHardwareFrame delivered = null;
        HardwareBuffer borrowedBuffer = null;
        try {
            Image ownedImage = image;
            if (!matchesOutput(ownedImage)) {
                fail(new SessionError(ErrorCode.BUFFER, "RAW image does not match the requested stream", true, null));
                return;
            }
            if (!frameConsumer.shouldProcessFrame(captured.sensorTimestampNs)) {
                consumerSkipped = true;
                if (rawRowStrideBytes == 0) recordRawRowStride(ownedImage);
                return;
            }
            HardwareBuffer hardwareBuffer = ownedImage.getHardwareBuffer();
            if (hardwareBuffer == null) {
                fail(new SessionError(ErrorCode.BUFFER,
                        "RAW image has no HardwareBuffer", true, null));
                return;
            }
            borrowedBuffer = hardwareBuffer;
            {
                Image.Plane[] planes = ownedImage.getPlanes();
                if (planes.length != 1 || rawLayoutOverride.stride(planes[0].getRowStride(), output.format, output.size) <= 0) {
                    fail(new SessionError(ErrorCode.BUFFER,
                            "RAW image has no valid row-stride metadata", true, null));
                    return;
                }
                logRawLayout(ownedImage, borrowedBuffer, planes[0]);
                RawOutput decodedOutput = rawLayoutOverride.output(output);
                int decodedStride = rawLayoutOverride.stride(planes[0].getRowStride(), output.format, output.size);
                rawRowStrideBytes = decodedStride;
                ByteBuffer packedRawBytes = PackedRawPlaneBuffer.borrow(
                        planes[0].getBuffer(), decodedStride,
                        decodedOutput.size.width, decodedOutput.size.height, output.format, planes[0].getPixelStride());
                delivered = new RawHardwareFrame(borrowedBuffer, packedRawBytes, captured,
                        decodedOutput, decodedStride, previewResults.take(captured.sensorTimestampNs),
                        ownedImage, retainRawReader(imageReader));
                frameConsumer.onRawFrame(delivered);
            }
        } catch (RuntimeException error) {
            fail(new SessionError(ErrorCode.CONSUMER,
                    "RAW frame consumer failed during synchronous buffer access", true, error));
        } finally {
            if (delivered != null) delivered.close();
            else {
                if (borrowedBuffer != null) borrowedBuffer.close();
                image.close();
            }
            streamDiagnostics.previewReleased(SystemClock.elapsedRealtimeNanos() - deliveryStartedNs, consumerSkipped);
            finishLongPreview(0L);
        }
    }

    private void logStreamConfiguration(CaptureRequest parameters) {
        try {
            CameraCharacteristics selected = requiredCharacteristics(route.characteristicsCameraId);
            CameraCharacteristics opened = route.openCameraId.equals(route.characteristicsCameraId) ? selected
                    : requiredCharacteristics(route.openCameraId);
            StreamConfigurationMap map = selected.get(output.maximumResolutionMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                    : CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<CaptureRequest.Key<?>> sessionKeys = opened.getAvailableSessionKeys();
            InternalLogRecorder.event("CameraStreams", "route=" + route.routeId + " open=" + route.openCameraId
                    + " physical=" + route.physicalCameraId + " characteristics=" + route.characteristicsCameraId
                    + " raw=" + output.format + ":" + output.size + " reader_capacity=" + imageReader.getMaxImages()
                    + " raw_usage=0x" + Long.toHexString(exactConfiguration == null ? 3L : exactConfiguration.rawReaderUsage)
                    + " auxiliary_jpeg=" + (auxiliaryJpegReader == null ? "none"
                    : auxiliaryJpegReader.getWidth() + "x" + auxiliaryJpegReader.getHeight())
                    + " companion=" + (meteringReader == null ? "none" : meteringReader.getImageFormat()
                    + ":" + meteringReader.getWidth() + "x" + meteringReader.getHeight())
                    + " companion_min_ns=" + (map == null || meteringReader == null ? "unknown"
                    : map.getOutputMinFrameDuration(meteringReader.getImageFormat(),
                    new Size(meteringReader.getWidth(), meteringReader.getHeight())))
                    + " planned_fps=" + aeFpsRange + " request_batch=" + requestBatchSize
                    + " initial_session_fps=" + parameters.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                    + " initial_session_ae=" + parameters.get(CaptureRequest.CONTROL_AE_MODE)
                    + " initial_session_ois=" + parameters.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                    + " selected_fps_ranges=" + Arrays.toString(selected.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                    + " open_fps_ranges=" + Arrays.toString(opened.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                    + " fps_session_key=" + (sessionKeys != null && sessionKeys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)));
        } catch (CameraAccessException | RuntimeException error) {
            InternalLogRecorder.warning("CameraStreams", "route=" + route.routeId + " diagnostics_unavailable=" + error);
        }
    }

    private void logCaptureCadence(CaptureRequest request, TotalCaptureResult result, RawCaptureMetadata captured) {
        String cadence = streamDiagnostics.capture(captured.frameNumber, captured.sensorTimestampNs,
                SystemClock.elapsedRealtimeNanos());
        if (cadence == null) return;
        CaptureResult physical = sensorCaptureResult(result, physicalCameraId);
        InternalLogRecorder.event("CameraCadence", "route=" + route.routeId + " " + cadence
                + " frame=" + captured.frameNumber + " sensor_ns=" + captured.sensorTimestampNs
                + " request_fps=" + request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                + " request_ae=" + request.get(CaptureRequest.CONTROL_AE_MODE)
                + " request_iso=" + request.get(CaptureRequest.SENSOR_SENSITIVITY)
                + " request_exposure_ns=" + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                + " request_duration_ns=" + request.get(CaptureRequest.SENSOR_FRAME_DURATION)
                + " thermal=" + InternalLogRecorder.thermalStatus()
                + " result_fps_range=" + result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                + " physical_fps_range=" + physical.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                + " physical_ae=" + physical.get(CaptureResult.CONTROL_AE_MODE)
                + " exposure_ns=" + captured.exposureTimeNs + " duration_ns=" + captured.frameDurationNs
                + " iso=" + captured.sensitivityIso + " pipeline_depth=" + result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)
                + " active_physical=" + result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                + " intent=" + request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
                + " noise_reduction=" + request.get(CaptureRequest.NOISE_REDUCTION_MODE)
                + " edge=" + request.get(CaptureRequest.EDGE_MODE)
                + " pending_drops=" + pendingImage.dropped());
    }

    private CaptureResult stillDngResult;

    private void deliverStill(Image image, RawCaptureMetadata captured) {
        if (stillFrameDelivered) {
            image.close();
            return;
        }
        stillFrameDelivered = true;
        cancelStillCaptureTimeout();
        try (Image ownedImage = image) {
            if (!matchesOutput(ownedImage)) {
                stillFailure(new SessionError(ErrorCode.BUFFER, "RAW capture does not match the requested stream", false, null));
                return;
            }
            HardwareBuffer hardwareBuffer = ownedImage.getHardwareBuffer();
            if (hardwareBuffer == null) {
                stillFailure(new SessionError(ErrorCode.BUFFER,
                        "Full-resolution RAW image has no HardwareBuffer", false, null));
                return;
            }
            try (HardwareBuffer borrowedBuffer = hardwareBuffer) {
                Image.Plane[] planes = ownedImage.getPlanes();
                if (planes.length != 1 || rawLayoutOverride.stride(planes[0].getRowStride(), output.format, output.size) <= 0) {
                    stillFailure(new SessionError(ErrorCode.BUFFER,
                            "Full-resolution RAW image has no valid row-stride metadata",
                            false, null));
                    return;
                }
                logRawLayout(ownedImage, borrowedBuffer, planes[0]);
                RawStillCaptureCallback callback = stillCaptureCallback;
                if (callback == null) {
                    stillFailure(new SessionError(ErrorCode.CONSUMER,
                            "Still-capture callback is unavailable", false, null));
                    return;
                }
                RawOutput decodedOutput = rawLayoutOverride.output(output);
                int decodedStride = rawLayoutOverride.stride(planes[0].getRowStride(), output.format, output.size);
                ByteBuffer packedRawBytes = PackedRawPlaneBuffer.borrow(
                        planes[0].getBuffer(), decodedStride,
                        decodedOutput.size.width, decodedOutput.size.height, output.format, planes[0].getPixelStride());
                boolean previewFrame = captureInPreviewSession;
                transition(State.STILL_CAPTURED);
                CaptureResult dngResult = stillDngResult;
                stillDngResult = null;
                RawHardwareFrame frame = new RawHardwareFrame(
                        borrowedBuffer, packedRawBytes, captured, decodedOutput, decodedStride, dngResult);
                callback.onStillFrame(frame);
                if (previewFrame && acceptsPreviewFrames()) {
                    if (frameConsumer.shouldProcessFrame(captured.sensorTimestampNs)) frameConsumer.onRawFrame(frame);
                }
            }
        } catch (RuntimeException error) {
            SessionError callbackError = new SessionError(ErrorCode.CONSUMER,
                    "Still-capture consumer failed during synchronous buffer access",
                    false, error);
            if (state == State.PREVIEW_RECONFIGURING || state == State.STREAMING) {
                report(callbackError);
            } else {
                stillFailure(callbackError);
            }
        }
    }

    private RawCaptureMetadata captureMetadata(TotalCaptureResult result,
                                               String physicalCameraId) {
        CaptureResult sensorResult = sensorCaptureResult(result, physicalCameraId);
        Long timestamp = captureValue(sensorResult, result, CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp == null || timestamp <= 0) return null;
        CaptureRequest request = result.getRequest();
        Object tag = request.getTag();
        RequestMetadata requestMetadata = tag instanceof RequestMetadata
                ? (RequestMetadata) tag : new RequestMetadata(0L, null);
        Integer requestedIso = Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_OFF).equals(
                request.get(CaptureRequest.CONTROL_AE_MODE))
                ? request.get(CaptureRequest.SENSOR_SENSITIVITY) : null;
        float[] neutralValues = requestMetadata.manualNeutral();
        if (requestMetadata.autoWhiteBalance()) {
            Rational[] neutral = captureValue(
                    sensorResult, result, CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (neutral != null && neutral.length == 3) {
                float[] candidate = new float[3];
                boolean valid = true;
                for (int index = 0; index < 3; index++) {
                    candidate[index] = neutral[index].getDenominator() == 0 ? 0f
                            : neutral[index].getNumerator() / (float) neutral[index].getDenominator();
                    valid &= Float.isFinite(candidate[index]) && candidate[index] > 0f;
                }
                if (valid) neutralValues = candidate;
            }
            if (neutralValues == null) {
                RggbChannelVector gains = captureValue(
                        sensorResult, result, CaptureResult.COLOR_CORRECTION_GAINS);
                if (gains != null) {
                    float green = (gains.getGreenEven() + gains.getGreenOdd()) * 0.5f;
                    if (gains.getRed() > 0f && green > 0f && gains.getBlue() > 0f) {
                        neutralValues = new float[]{
                                1f / gains.getRed(), 1f / green, 1f / gains.getBlue()};
                    }
                }
            }
        }
        if (neutralValues == null && !missingWhiteBalanceLogged) {
            missingWhiteBalanceLogged = true;
            Log.w(TAG, "RAW white balance missing for route " + route.routeId
                    + "; logical keys=" + whiteBalanceKeyNames(result)
                    + "; physical keys=" + whiteBalanceKeyNames(sensorResult)
                    + "; AWB state=" + captureValue(sensorResult, result,
                    CaptureResult.CONTROL_AWB_STATE));
        }
        if (requestMetadata.autoWhiteBalance() && neutralValues != null) lastAutoWhiteBalanceNeutral = neutralValues.clone();
        LensShadingData lensShading = lensShadingData(
                captureValue(sensorResult, result,
                        CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP));
        android.util.Pair<Double, Double>[] sensorNoise = captureValue(
                sensorResult, result, CaptureResult.SENSOR_NOISE_PROFILE);
        float[] noiseProfile = null;
        if (sensorNoise != null) {
            noiseProfile = new float[sensorNoise.length * 2];
            for (int i = 0; i < sensorNoise.length; i++) {
                noiseProfile[i * 2] = sensorNoise[i].first.floatValue();
                noiseProfile[i * 2 + 1] = sensorNoise[i].second.floatValue();
            }
        }
        reportedOpticalStabilizationMode = sensorResult.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        if (opticalStabilizationChangedNs != 0L) {
            if (Objects.equals(opticalStabilizationMode, reportedOpticalStabilizationMode))
                opticalStabilizationChangedNs = 0L;
            else if (SystemClock.elapsedRealtimeNanos() - opticalStabilizationChangedNs > 2_000_000_000L)
                opticalStabilizationLive = false;
        }
        return new RawCaptureMetadata(timestamp, result.getFrameNumber(),
                captureValue(sensorResult, result, CaptureResult.SENSOR_EXPOSURE_TIME),
                captureValue(sensorResult, result, CaptureResult.SENSOR_FRAME_DURATION),
                captureValue(sensorResult, result, CaptureResult.SENSOR_SENSITIVITY),
                captureValue(sensorResult, result, CaptureResult.LENS_FOCUS_DISTANCE),
                captureValue(sensorResult, result, CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                captureValue(sensorResult, result, CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL),
                neutralValues,
                lensShading == null ? 0 : lensShading.width,
                lensShading == null ? 0 : lensShading.height,
                lensShading == null ? null : lensShading.gains,
                captureValue(sensorResult, result, CaptureResult.LENS_APERTURE),
                captureValue(sensorResult, result, CaptureResult.LENS_FOCAL_LENGTH), noiseProfile,
                requestMetadata.exposureControlGeneration, requestedIso,
                Build.VERSION.SDK_INT >= 31 ? captureValue(sensorResult, result,
                        CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED) : null,
                new StabilizationCapture(sensorResult, sensorResult != result ? physicalCameraId
                        : result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                        sensorResult != result ? physicalCameraId : route.openCameraId), panoramaControlsApplied(sensorResult, result));
    }

    @SuppressWarnings("deprecation")
    private CaptureResult sensorCaptureResult(TotalCaptureResult result,
                                              String physicalCameraId) {
        CaptureResult sensorResult = result;
        if (physicalCameraId != null) {
            CaptureResult physical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? Api31.physicalCameraResults(result).get(physicalCameraId)
                    : result.getPhysicalCameraResults().get(physicalCameraId);
            if (physical != null) sensorResult = physical;
        }
        return sensorResult;
    }

    private boolean matchesOutput(Image image) {
        return image.getWidth() == output.size.width && image.getHeight() == output.size.height
                && image.getFormat() == output.format.imageFormat;
    }

    private void logRawLayout(Image image, HardwareBuffer buffer, Image.Plane plane) {
        if (rawLayoutLogged) return;
        rawLayoutLogged = true;
        Log.i(TAG, "RAW layout route=" + route.routeId
                + " output=" + output.format + " " + output.size.width + "x" + output.size.height
                + " image=" + image.getWidth() + "x" + image.getHeight()
                + " rowStride=" + plane.getRowStride()
                + " pixelStride=" + plane.getPixelStride()
                + " buffer=" + buffer.getWidth() + "x" + buffer.getHeight()
                + " format=" + buffer.getFormat()
                + " layers=" + buffer.getLayers()
                + " usage=0x" + Long.toHexString(buffer.getUsage()));
    }

    private static <T> T captureValue(CaptureResult preferred,
                                      TotalCaptureResult fallback,
                                      CaptureResult.Key<T> key) {
        T value = preferred.get(key);
        return value != null ? value : fallback.get(key);
    }

    private static String whiteBalanceKeyNames(CaptureResult result) {
        StringBuilder names = new StringBuilder("[");
        for (CaptureResult.Key<?> key : result.getKeys()) {
            String name = key.getName();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("awb") && !lower.contains("neutral")
                    && !lower.contains("colorcorrection") && !lower.contains("gain")) {
                continue;
            }
            if (names.length() > 1) names.append(", ");
            names.append(name);
        }
        return names.append(']').toString();
    }

    private static LensShadingData lensShadingData(LensShadingMap map) {
        if (map == null) return null;
        int width = map.getColumnCount();
        int height = map.getRowCount();
        long expected = (long) width * height * 4;
        if (width <= 0 || height <= 0 || expected != map.getGainFactorCount()
                || expected > Integer.MAX_VALUE) return null;
        float[] gains = new float[(int) expected];
        try { map.copyGainFactors(gains, 0); }
        catch (RuntimeException ignored) { return null; }
        for (float gain : gains) {
            if (!Float.isFinite(gain) || gain <= 0f) return null;
        }
        return new LensShadingData(width, height, gains);
    }

    private boolean supportsLensShadingMap(CameraRoute route) {
        int[] routeModes = lensShadingMapModes(characteristics(route.characteristicsCameraId));
        int[] openCameraModes = route.characteristicsCameraId.equals(route.openCameraId)
                ? null
                : lensShadingMapModes(characteristics(route.openCameraId));
        return shouldRequestLensShadingMap(routeModes, openCameraModes);
    }

    private void readMeteringCapabilities(CameraRoute route) {
        CameraCharacteristics selected = characteristics(route.characteristicsCameraId);
        CameraCharacteristics opened = route.characteristicsCameraId.equals(route.openCameraId)
                ? null : characteristics(route.openCameraId);
        maxAfRegions = characteristicInt(selected,
                CameraCharacteristics.CONTROL_MAX_REGIONS_AF,
                characteristicInt(opened, CameraCharacteristics.CONTROL_MAX_REGIONS_AF, 0));
        maxAeRegions = characteristicInt(selected,
                CameraCharacteristics.CONTROL_MAX_REGIONS_AE,
                characteristicInt(opened, CameraCharacteristics.CONTROL_MAX_REGIONS_AE, 0));
        availableAfModes = selected == null ? null : selected.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (availableAfModes == null && opened != null) {
            availableAfModes = opened.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        }
        aeLockAvailable = characteristicBoolean(selected,
                CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE,
                characteristicBoolean(opened,
                        CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE, false));
    }

    private void readControlCapabilities(CameraRoute route) {
        CameraCharacteristics selected = characteristics(route.characteristicsCameraId);
        CameraCharacteristics opened = route.characteristicsCameraId.equals(route.openCameraId)
                ? null : characteristics(route.openCameraId);
        opticalStabilizationMode = preferredOpticalStabilizationMode(
                characteristic(selected, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, null),
                characteristic(opened, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, null));
        opticalStabilizationSupported = Integer.valueOf(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                .equals(opticalStabilizationMode);
        if (opticalStabilizationSupported && !opticalStabilizationEnabled)
            opticalStabilizationMode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF;
        opticalStabilizationLive = true;
        opticalStabilizationChangedNs = 0L;
        reportedOpticalStabilizationMode = null;
        flashAvailable = characteristicBoolean(opened, CameraCharacteristics.FLASH_INFO_AVAILABLE,
                characteristicBoolean(selected, CameraCharacteristics.FLASH_INFO_AVAILABLE, false));
        availableAeModes = characteristic(opened, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                characteristic(selected, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, null));
        Range<Integer> compensation = characteristic(selected,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE,
                characteristic(opened, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, null));
        Rational step = characteristic(selected, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                characteristic(opened, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, null));
        Range<Integer> sensitivity = characteristic(selected,
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                characteristic(opened, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, null));
        SensorExposureLimits exposureLimits = SensorExposureLimits.read(selected, opened);
        recordingPipelineDepth = Math.max(
                characteristic(opened, CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH, (byte) 0),
                characteristic(selected, CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH, (byte) 0));

        exposureCompensationMin = compensation == null ? 0 : compensation.getLower();
        exposureCompensationMax = compensation == null ? 0 : compensation.getUpper();
        exposureCompensation = clamp(exposureCompensation, exposureCompensationMin, exposureCompensationMax);
        exposureCompensationStepEv = step == null ? 1f : step.floatValue();
        manualExposureSupported = (hasCapability(selected,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                || hasCapability(opened,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
                && sensitivity != null && exposureLimits != null;
        externalAutoExposure = externalAutoExposure && manualExposureSupported;
        if (externalAutoExposure) autoExposure = false;
        if (sensitivity != null) {
            isoMin = sensitivity.getLower();
            isoMax = sensitivity.getUpper();
        }
        if (exposureLimits != null) {
            exposureTimeMinNs = exposureLimits.minimumNs;
            exposureTimeMaxNs = exposureLimits.maximumNs;
            maximumFrameDurationNs = exposureLimits.maximumFrameDurationNs;
        }
        minimumFocusDistance = characteristic(selected,
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                characteristic(opened, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, 0f));
        hyperfocalDistance = characteristic(selected,
                CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE,
                characteristic(opened, CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE, 0f));
        manualFocusSupported = minimumFocusDistance > 0f
                && supportsAfMode(CameraMetadata.CONTROL_AF_MODE_OFF);
        availableAwbModes = characteristic(selected,
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
                characteristic(opened, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, null));
        awbLockAvailable = characteristic(selected, CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE,
                characteristic(opened, CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE, false));
        manualWhiteBalanceSupported = supportsMode(
                availableAwbModes, CameraMetadata.CONTROL_AWB_MODE_OFF)
                && sensorMetadata != null
                && WhiteBalanceTemperature.cameraNeutralForTemperature(
                        5000, sensorMetadata.colorCalibration) != null;
        iso = clamp(iso, isoMin, isoMax);
        exposureTimeNs = clamp(exposureTimeNs, exposureTimeMinNs, exposureTimeMaxNs);
        readIspViewfinderCapabilities(selected, opened);
    }

    public synchronized void setInitialControls(ControlState controls) {
        if (state != State.IDLE) throw new IllegalStateException("Camera has already started");
        initialControls = controls;
    }

    public synchronized void setInitialAutoExposure(SensorExposure exposure, AeLimits limits) {
        if (state != State.IDLE) throw new IllegalStateException("Camera has already started");
        initialAutoExposure = Objects.requireNonNull(exposure);
        initialAeLimits = Objects.requireNonNull(limits);
    }

    private void restoreInitialControls() {
        if (externalAutoExposure && initialAutoExposure != null) {
            ExposureBounds bounds = initialAeLimits.resolve(
                    new ExposureBounds(isoMin, isoMax, exposureTimeMinNs, exposureTimeMaxNs));
            SensorExposure exposure = bounds.clamp(initialAutoExposure);
            iso = exposure.getIso();
            exposureTimeNs = exposure.getTimeNs();
        }
        initialAutoExposure = null;
        initialAeLimits = null;
        ControlState saved = initialControls;
        initialControls = null;
        if (saved == null) return;
        if (manualExposureSupported && saved.externalAutoExposure == externalAutoExposure) {
            autoExposure = saved.autoExposure;
            iso = clamp(saved.iso, isoMin, isoMax);
            exposureTimeNs = clamp(saved.exposureTimeNs, exposureTimeMinNs, exposureTimeMaxNs);
            if (saved.pendingExposureTimeNs != null) {
                longShutter.select(exposureTimeNs,
                        clamp(saved.pendingExposureTimeNs, exposureTimeMinNs, exposureTimeMaxNs));
            }
        }
        if (manualFocusSupported && !saved.autoFocus) {
            autoFocus = false;
            focusDistance = Math.max(0f, Math.min(saved.focusDistance, minimumFocusDistance));
        }
        if (manualWhiteBalanceSupported && !saved.autoWhiteBalance && saved.whiteBalanceTemperatureKelvin != null) {
            autoWhiteBalance = false;
            whiteBalanceTemperatureKelvin = clamp(saved.whiteBalanceTemperatureKelvin,
                    MIN_WHITE_BALANCE_KELVIN, MAX_WHITE_BALANCE_KELVIN);
        }
    }

    private static <T> T characteristic(CameraCharacteristics characteristics,
                                        CameraCharacteristics.Key<T> key, T fallback) {
        if (characteristics == null) return fallback;
        T value = characteristics.get(key);
        return value == null ? fallback : value;
    }

    private static boolean hasCapability(CameraCharacteristics characteristics, int expected) {
        if (characteristics == null) return false;
        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) return false;
        for (int capability : capabilities) if (capability == expected) return true;
        return false;
    }

    private static int characteristicInt(CameraCharacteristics characteristics,
                                         CameraCharacteristics.Key<Integer> key,
                                         int fallback) {
        if (characteristics == null) return fallback;
        Integer value = characteristics.get(key);
        return value == null ? fallback : value;
    }

    private static boolean characteristicBoolean(CameraCharacteristics characteristics,
                                                 CameraCharacteristics.Key<Boolean> key,
                                                 boolean fallback) {
        if (characteristics == null) return fallback;
        Boolean value = characteristics.get(key);
        return value == null ? fallback : value;
    }

    private void applyCommonRequestControls(CaptureRequest.Builder request, boolean preview) {
        applyCommonRequestControls(request, preview, 0L);
    }

    private void applyCommonRequestControls(CaptureRequest.Builder request, boolean preview, long recordingToken) {
        if (meteringReader != null && (exactConfiguration == null || exactConfiguration.targetCompanion)) {
            request.addTarget(meteringReader.getSurface());
        }
        RequestMetadata requestMetadata = preview ? snapshotRequestMetadata()
                : Objects.requireNonNull(stillRequestMetadata);
        requestMetadata.recordingToken = recordingToken;
        request.setTag(requestMetadata);
        request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (opticalStabilizationMode != null) {
            // Some devices leave OIS off in their default capture requests.
            request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, opticalStabilizationMode);
        }
        StillExposure exposure = preview ? null : stillExposure;
        boolean requestAutoExposure = autoExposure && exposure == null;
        request.set(CaptureRequest.CONTROL_AE_MODE, requestAutoExposure
                ? CameraMetadata.CONTROL_AE_MODE_ON : CameraMetadata.CONTROL_AE_MODE_OFF);
        applyFlashControls(request, preview, requestAutoExposure);
        if (aeLockAvailable) request.set(CaptureRequest.CONTROL_AE_LOCK, false);
        request.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        request.set(CaptureRequest.CONTROL_AWB_MODE,
                requestMetadata.autoWhiteBalance() || !manualWhiteBalanceSupported
                        ? CameraMetadata.CONTROL_AWB_MODE_AUTO
                        : CameraMetadata.CONTROL_AWB_MODE_OFF);
        if (awbLockAvailable) request.set(CaptureRequest.CONTROL_AWB_LOCK, captureWhiteBalanceLock);
        if (preview && ispViewfinderStream) applyIspViewfinderControls(request, requestMetadata);
        request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);
        if (!requestAutoExposure) {
            request.set(CaptureRequest.SENSOR_SENSITIVITY, exposure == null ? iso : exposure.iso);
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                    exposure == null ? exposureTimeNs : exposure.exposureTimeNs);
            long frameDuration = exposure == null ? Math.max(exposureTimeNs,
                    1_000_000_000L / Math.max(1, aeFpsRange.upper)) : exposure.frameDurationNs;
            request.set(CaptureRequest.SENSOR_FRAME_DURATION,
                    Math.min(frameDuration, maximumFrameDurationNs));
        }
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new Range<>(aeFpsRange.lower, aeFpsRange.upper));
        if (requestLensShadingMap) {
            request.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        }
        if (output.maximumResolutionMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            request.set(CaptureRequest.SENSOR_PIXEL_MODE,
                    CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
    }

    /** Keep the ISP viewfinder stream close to the sensor: known tone curve, no sharpening, manual color when selected. */
    private void applyIspViewfinderControls(CaptureRequest.Builder request, RequestMetadata requestMetadata) {
        if (ispTonemapCurve != null) {
            request.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE);
            request.set(CaptureRequest.TONEMAP_CURVE, ispTonemapCurve);
        }
        if (ispEdgeOffSupported) request.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
        if (ispNoiseReductionMode != null) request.set(CaptureRequest.NOISE_REDUCTION_MODE, ispNoiseReductionMode);
        float[] neutral = requestMetadata.manualNeutral();
        if (neutral == null || !ispColorCorrectionSupported || !manualWhiteBalanceSupported || sensorMetadata == null) return;
        float red = neutral[1] / neutral[0];
        float blue = neutral[1] / neutral[2];
        if (!Float.isFinite(red) || !Float.isFinite(blue) || red <= 0f || blue <= 0f) return;
        float[] matrix = RawColorCalibration.INSTANCE.afterWhiteBalance(
                sensorMetadata.colorCalibration, new WhiteBalanceGains(red, 1f, blue));
        Rational[] elements = new Rational[9];
        for (int index = 0; index < 9; index++) {
            elements[index] = new Rational(Math.round(matrix[index] * 10000f), 10000);
        }
        request.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
        request.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(red, 1f, 1f, blue));
        request.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform(elements));
    }

    private void readIspViewfinderCapabilities(CameraCharacteristics selected, CameraCharacteristics opened) {
        boolean manualPostProcessing = hasCapability(selected,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                || hasCapability(opened, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING);
        int[] tonemapModes = characteristic(selected, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES,
                characteristic(opened, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, null));
        Integer maxPoints = characteristic(selected, CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS,
                characteristic(opened, CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS, null));
        ispTonemapCurve = manualPostProcessing && supportsMode(tonemapModes, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                && maxPoints != null && maxPoints >= 2 ? srgbTonemapCurve(Math.min(maxPoints, 64)) : null;
        int[] edgeModes = characteristic(selected, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                characteristic(opened, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, null));
        ispEdgeOffSupported = supportsMode(edgeModes, CameraMetadata.EDGE_MODE_OFF);
        int[] noiseModes = characteristic(selected, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                characteristic(opened, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, null));
        ispNoiseReductionMode = supportsMode(noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                ? Integer.valueOf(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                : supportsMode(noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                ? Integer.valueOf(CameraMetadata.NOISE_REDUCTION_MODE_OFF) : null;
        ispColorCorrectionSupported = manualPostProcessing;
    }

    /** An sRGB transfer curve sampled evenly in output space so shadows keep their precision. */
    static TonemapCurve srgbTonemapCurve(int points) {
        float[] curve = new float[points * 2];
        for (int index = 0; index < points; index++) {
            float out = (float) index / (points - 1);
            float in = out <= 0.04045f ? out / 12.92f : (float) Math.pow((out + 0.055) / 1.055, 2.4);
            curve[2 * index] = index == 0 ? 0f : index == points - 1 ? 1f : in;
            curve[2 * index + 1] = out;
        }
        return new TonemapCurve(curve, curve, curve);
    }

    private boolean tapAutofocusEnabled() {
        return autoFocus && supportsAfMode(CameraMetadata.CONTROL_AF_MODE_AUTO) && maxAfRegions > 0;
    }

    private boolean tapAutoExposureEnabled() {
        return autoExposure && maxAeRegions > 0;
    }

    private void applyMeteringControls(CaptureRequest.Builder request) {
        MeteringRectangle region = exposurePointSet
                ? meteringRectangle(exposurePointX, exposurePointY) : null;
        request.set(CaptureRequest.CONTROL_AE_REGIONS, region != null && tapAutoExposureEnabled()
                ? new MeteringRectangle[]{region} : defaultMeteringRegions(maxAeRegions));
        if (!autoFocus) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
            request.set(CaptureRequest.CONTROL_AF_REGIONS, defaultMeteringRegions(maxAfRegions));
            return;
        }
        boolean autofocus = tapAutofocusEnabled();
        if (!meteringPointSet) {
            int mode = supportsAfMode(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    : autofocus ? CameraMetadata.CONTROL_AF_MODE_AUTO
                    : CameraMetadata.CONTROL_AF_MODE_OFF;
            request.set(CaptureRequest.CONTROL_AF_MODE, mode);
            request.set(CaptureRequest.CONTROL_AF_REGIONS, defaultMeteringRegions(maxAfRegions));
            return;
        }
        if (autofocus) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AF_REGIONS,
                    new MeteringRectangle[]{meteringRectangle(meteringPointX, meteringPointY)});
        } else {
            request.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            request.set(CaptureRequest.CONTROL_AF_REGIONS, defaultMeteringRegions(maxAfRegions));
        }
    }

    private MeteringRectangle[] defaultMeteringRegions(int maximumRegions) {
        return maximumRegions > 0 ? new MeteringRectangle[]{new MeteringRectangle(activeArrayForOutput(), 0)} : null;
    }

    private void updateAutoControlValues(CaptureResult sensorResult,
                                          TotalCaptureResult result) {
        meteredAperture = captureValue(sensorResult, result, CaptureResult.LENS_APERTURE);
        if (autoExposure) {
            Integer resultIso = captureValue(sensorResult, result, CaptureResult.SENSOR_SENSITIVITY);
            Long resultExposure = captureValue(sensorResult, result, CaptureResult.SENSOR_EXPOSURE_TIME);
            if (resultIso != null) iso = clamp(resultIso, isoMin, isoMax);
            if (resultExposure != null) {
                exposureTimeNs = clamp(resultExposure, exposureTimeMinNs, exposureTimeMaxNs);
            }
        }
        if (autoFocus) {
            Float resultFocus = captureValue(sensorResult, result, CaptureResult.LENS_FOCUS_DISTANCE);
            if (resultFocus != null && Float.isFinite(resultFocus)) focusDistance = resultFocus;
        }
        if (autoWhiteBalance) {
            RggbChannelVector gains = captureValue(
                    sensorResult, result, CaptureResult.COLOR_CORRECTION_GAINS);
            whiteBalanceRedGain = null;
            whiteBalanceGreenGain = null;
            whiteBalanceBlueGain = null;
            whiteBalanceTemperatureKelvin = null;
            if (gains != null) {
                float green = (gains.getGreenEven() + gains.getGreenOdd()) * 0.5f;
                if (Float.isFinite(gains.getRed()) && gains.getRed() > 0f
                        && Float.isFinite(green) && green > 0f
                        && Float.isFinite(gains.getBlue()) && gains.getBlue() > 0f) {
                    whiteBalanceRedGain = gains.getRed();
                    whiteBalanceGreenGain = green;
                    whiteBalanceBlueGain = gains.getBlue();
                    ColorCalibration calibration = sensorMetadata == null
                            ? null : sensorMetadata.colorCalibration;
                    whiteBalanceTemperatureKelvin = WhiteBalanceTemperature.fromCamera2Gains(
                            gains.getRed(), green, gains.getBlue(), calibration);
                }
            }
        }
        notifyControlsChanged(false);
    }

    private void applyControlUpdate() {
        applyControlUpdate(true);
    }

    private void applyControlUpdate(boolean interruptLongPreview) {
        if (state == State.STREAMING && captureSession != null && repeatingRequestBuilder != null && !captureSessionRestartPending) {
            try {
                if (recording && externalAutoExposure && externalExposureAutomatic && !autoExposure &&
                        !usesLongPreview() && !recordingRequestsRejected) {
                    if (!recordingRequests.active()) {
                        controlRequests.cancelAndWait();
                        recordingExposure.reset(iso, exposureTimeNs);
                        recordingRequests.start(aeFpsRange.upper, recordingPipelineDepth,
                                requestBatchSize > 1 ? requestBatchSize : RecordingRequestPump.BURST_FRAMES);
                        submitRecordingBurst(true);
                    }
                    notifyControlsChanged(false);
                    return;
                }
                if (recordingRequests.active()) {
                    recordingRequests.stop();
                    recordingFocusSteps = 0;
                    controlRequests.cancelAndWait();
                }
                if (usesLongPreview()) {
                    if (repeatingPreviewActive || longPreviewInFlight && interruptLongPreview) discardInFlightCaptures();
                    queueLongPreview(0L);
                } else {
                    if (longPreviewInFlight) discardInFlightCaptures();
                    if (captureSessionRestartPending) {
                        notifyControlsChanged(true);
                        return;
                    }
                    cameraHandler.removeCallbacks(longPreviewRequest);
                    longPreviewFocusTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE;
                    applyCommonRequestControls(repeatingRequestBuilder, true);
                    applyMeteringControls(repeatingRequestBuilder);
                    repeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                    submitControlRequest(buildRequest(repeatingRequestBuilder));
                    repeatingPreviewActive = true;
                }
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
                if (opticalStabilizationChangedNs != 0L) {
                    opticalStabilizationLive = false;
                    opticalStabilizationMode = reportedOpticalStabilizationMode;
                    opticalStabilizationChangedNs = 0L;
                    Log.w(TAG, "Camera rejected a live OIS change", error);
                } else {
                    report(new SessionError(ErrorCode.REQUEST,
                            "Unable to update camera controls", false, error));
                }
            }
        }
        notifyControlsChanged(true);
    }

    private int repeatRequest(CameraCaptureSession target, CaptureRequest request,
                              CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws CameraAccessException {
        if (requestBatchSize == 1) return target.setRepeatingRequest(request, callback, handler);
        List<CaptureRequest> requests = new ArrayList<>(requestBatchSize);
        for (int i = 0; i < requestBatchSize; i++) requests.add(request);
        return target.setRepeatingBurst(requests, callback, handler);
    }

    private void submitControlRequest(CaptureRequest request) {
        CameraCaptureSession target = captureSession;
        Handler handler = cameraHandler;
        long generation = captureRequestGeneration;
        controlRequests.submit(() -> {
            long startedNs = SystemClock.elapsedRealtimeNanos();
            SessionError failure = null;
            try {
                repeatRequest(target, request, captureCallback, handler);
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
                failure = new SessionError(ErrorCode.REQUEST, "Unable to update camera controls", false, error);
            }
            long elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs;
            SessionError result = failure;
            handler.post(() -> {
                if (captureSession != target || generation != captureRequestGeneration || terminalState()) return;
                streamDiagnostics.requestUpdated(elapsedNs);
                if (result != null) report(result);
            });
        });
    }

    private void recordingFrameStarted(CaptureRequest request) {
        Object tag = request.getTag();
        if (!(tag instanceof RequestMetadata)) return;
        RequestMetadata metadata = (RequestMetadata) tag;
        if (metadata.recordingToken == 0 || metadata.recordingStarted) return;
        metadata.recordingStarted = true;
        if (recordingRequests.frameStarted(metadata.recordingToken)) submitRecordingBurst(false);
    }

    private void submitRecordingBurst(boolean stopRepeating) {
        if (state != State.STREAMING || captureSession == null || captureSessionRestartPending || terminalState()) return;
        long token = recordingRequests.beginSubmission();
        if (token == 0) return;
        CameraCaptureSession target = captureSession;
        Handler handler = cameraHandler;
        int batchFrames = recordingRequests.batchFrames();
        List<CaptureRequest> requests = new ArrayList<>(batchFrames);
        int targetIso = iso;
        long targetTimeNs = exposureTimeNs;
        try {
            for (int i = 0; i < batchFrames; i++) {
                SensorExposure next = recordingExposure.next(targetIso, targetTimeNs,
                        1_000_000_000L / Math.max(1, aeFpsRange.upper), batchFrames - i);
                iso = clamp(next.getIso(), isoMin, isoMax);
                exposureTimeNs = clamp(next.getTimeNs(), exposureTimeMinNs, exposureTimeMaxNs);
                applyCommonRequestControls(repeatingRequestBuilder, true, token);
                applyMeteringControls(repeatingRequestBuilder);
                repeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                if (recordingFocusSteps > 0) {
                    repeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, recordingFocusSteps == 2
                            ? CameraMetadata.CONTROL_AF_TRIGGER_CANCEL : recordingFocusRestart
                            ? CameraMetadata.CONTROL_AF_TRIGGER_IDLE : CameraMetadata.CONTROL_AF_TRIGGER_START);
                    if (recordingFocusRestart && recordingFocusSteps == 2)
                        repeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    recordingFocusSteps--;
                }
                requests.add(buildRequest(repeatingRequestBuilder));
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            rejectRecordingRequests(error);
            return;
        } finally {
            iso = targetIso;
            exposureTimeNs = targetTimeNs;
        }
        controlRequests.submit(() -> {
            long startedNs = SystemClock.elapsedRealtimeNanos();
            Exception failure = null;
            try {
                if (stopRepeating) target.stopRepeating();
                target.captureBurst(requests, captureCallback, handler);
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
                failure = error;
            }
            long elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs;
            Exception result = failure;
            handler.post(() -> {
                if (target != captureSession || !recordingRequests.submitted(token) || terminalState()) return;
                streamDiagnostics.requestUpdated(elapsedNs);
                if (result != null) {
                    rejectRecordingRequests(result);
                } else submitRecordingBurst(false);
            });
        });
        repeatingPreviewActive = true;
    }

    private void rejectRecordingRequests(Exception error) {
        recordingRequests.stop();
        recordingRequestsRejected = true;
        InternalLogRecorder.error("Camera2", "Camera rejected queued recording requests", error);
        cameraHandler.post(() -> applyControlUpdate(false));
    }

    private boolean usesLongPreview() {
        return !autoExposure && exposureTimeNs >= LongShutterSelection.LIMIT_NS;
    }

    private boolean hasSlowManualPreview() {
        return !autoExposure && exposureTimeNs >= SLOW_PREVIEW_UPDATE_NS;
    }

    private void queueLongPreview(long delayMs) {
        if (cameraHandler == null || state != State.STREAMING || !usesLongPreview()) return;
        cameraHandler.removeCallbacks(longPreviewRequest);
        cameraHandler.postDelayed(longPreviewRequest, delayMs);
    }

    private void captureLongPreview() {
        if (state != State.STREAMING || captureSession == null || repeatingRequestBuilder == null ||
                !usesLongPreview() || longPreviewInFlight || captureSessionRestartPending || !singleCaptureIdle) return;
        controlRequests.cancelAndWait();
        try {
            if (singleCaptureUsed) {
                discardInFlightCaptures();
                return;
            }
            applyCommonRequestControls(repeatingRequestBuilder, true);
            applyMeteringControls(repeatingRequestBuilder);
            repeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, longPreviewFocusTrigger);
            InternalLogRecorder.event("LongShutter", "preview generation=" + captureRequestGeneration
                    + " exposure_ns=" + exposureTimeNs + " iso=" + iso);
            singleCaptureIdle = false;
            singleCaptureUsed = true;
            longPreviewImageDelivered = false;
            captureSession.capture(buildRequest(repeatingRequestBuilder), captureCallback, cameraHandler);
            longPreviewFocusTrigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE;
            longPreviewInFlight = true;
            Runnable timeout = () -> {
                if (!longPreviewInFlight || state != State.STREAMING) return;
                applyControlUpdate();
            };
            longPreviewTimeout = timeout;
            cameraHandler.postDelayed(timeout, StillExposure.timeoutMs(exposureTimeNs, false));
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            report(new SessionError(ErrorCode.REQUEST, "Unable to request the long RAW preview", false, error));
        }
    }

    private void finishLongPreview(long delayMs) {
        if (!longPreviewInFlight) return;
        longPreviewImageDelivered = true;
        cancelLongPreviewTimeout();
        if (singleCaptureIdle) {
            longPreviewInFlight = false;
            longPreviewImageDelivered = false;
            queueLongPreview(delayMs);
        }
    }

    private void cancelLongPreviewTimeout() {
        if (longPreviewTimeout != null && cameraHandler != null) cameraHandler.removeCallbacks(longPreviewTimeout);
        longPreviewTimeout = null;
    }

    private void notifyControlsChanged(boolean immediate) {
        long now = SystemClock.elapsedRealtime();
        if (!immediate && now - lastControlReportMs < 100L) return;
        lastControlReportMs = now;
        RawSessionListener current = listener;
        if (current == null) return;
        try {
            current.onControlsChanged(new ControlState(
                    exposureCompensationMin, exposureCompensationMax, exposureCompensationStepEv,
                    exposureCompensation, manualExposureSupported, isoMin, isoMax,
                    exposureTimeMinNs, exposureTimeMaxNs, autoExposure, iso, exposureTimeNs, longShutter.pendingExposureTimeNs(),
                    manualFocusSupported, minimumFocusDistance, hyperfocalDistance,
                    autoFocus, focusDistance, manualWhiteBalanceSupported,
                    autoWhiteBalance, whiteBalanceTemperatureKelvin,
                    whiteBalanceRedGain, whiteBalanceGreenGain, whiteBalanceBlueGain, flashAvailable, flashMode,
                    externalAutoExposure));
        } catch (RuntimeException ignored) { }
    }

    private float[] manualWhiteBalanceNeutral() {
        if (lockedWhiteBalanceNeutral != null) return lockedWhiteBalanceNeutral.clone();
        Integer temperature = whiteBalanceTemperatureKelvin;
        if (temperature == null) {
            throw new IllegalStateException("Manual white balance temperature is unavailable");
        }
        return Objects.requireNonNull(WhiteBalanceTemperature.cameraNeutralForTemperature(
                temperature, Objects.requireNonNull(sensorMetadata).colorCalibration),
                "Sensor calibration cannot represent the selected white balance");
    }

    private RequestMetadata snapshotRequestMetadata() {
        return new RequestMetadata(meteringRequestGeneration,
                autoWhiteBalance ? null : manualWhiteBalanceNeutral(), captureRequestGeneration, exposureRequestGeneration,
                exposureControlGeneration);
    }

    private static boolean supportsMode(int[] modes, int expected) {
        if (modes == null) return false;
        for (int mode : modes) if (mode == expected) return true;
        return false;
    }

    private void applyTapMetering(float normalizedX, float normalizedY) {
        if (state != State.STREAMING || captureSession == null || repeatingRequestBuilder == null) return;
        if (!tapAutofocusEnabled()) {
            notifyMeteringState(MeteringState.UNSUPPORTED);
            return;
        }
        cancelFocusTimeout();
        meteringPointSet = true;
        meteringPointX = normalizedX;
        meteringPointY = normalizedY;
        meteringRequestGeneration++;
        autofocusFeedback.start(meteringRequestGeneration);
        focusReportedState = null;
        notifyMeteringState(MeteringState.SCANNING);
        CaptureRequest.Builder request = repeatingRequestBuilder;
        if (usesLongPreview()) {
            longPreviewFocusTrigger = CameraMetadata.CONTROL_AF_TRIGGER_START;
            applyControlUpdate(false);
            scheduleFocusTimeout();
            return;
        }
        if (recordingRequests.active()) {
            recordingFocusSteps = 2;
            recordingFocusRestart = false;
            scheduleFocusTimeout();
            return;
        }
        request.setTag(snapshotRequestMetadata());
        applyMeteringControls(request);
        controlRequests.cancelAndWait();
        try {
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(buildRequest(request), captureCallback, cameraHandler);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(buildRequest(request), captureCallback, cameraHandler);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            repeatRequest(captureSession, buildRequest(request), captureCallback, cameraHandler);
            scheduleFocusTimeout();
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            notifyMeteringState(focusState(autofocusFeedback.fail(meteringRequestGeneration)));
            report(new SessionError(ErrorCode.REQUEST, "Unable to apply focus region", false, error));
        } finally {
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        }
    }

    private void updateMeteringState(CaptureRequest request, TotalCaptureResult result) {
        Object tag = request.getTag();
        if (!(tag instanceof RequestMetadata)) return;
        RequestMetadata metadata = (RequestMetadata) tag;
        CaptureResult sensorResult = sensorCaptureResult(result, physicalCameraId);
        if (meteringPointSet && tapAutofocusEnabled() && metadata.meteringGeneration == meteringRequestGeneration) {
            AutofocusFeedback.State feedback = autofocusFeedback.update(metadata.meteringGeneration,
                    result.getFrameNumber(), request.get(CaptureRequest.CONTROL_AF_TRIGGER),
                    captureValue(sensorResult, result, CaptureResult.CONTROL_AF_STATE));
            notifyMeteringState(focusState(feedback));
        }
        if (exposurePointSet && metadata.exposureGeneration == exposureRequestGeneration) {
            Integer aeState = captureValue(sensorResult, result, CaptureResult.CONTROL_AE_STATE);
            if (!tapAutoExposureEnabled()) notifyExposureState(MeteringState.RESET);
            else if (aeState != null) notifyExposureState(
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                            ? MeteringState.SUCCESS : MeteringState.SCANNING);
        }
    }

    private static MeteringState focusState(AutofocusFeedback.State state) {
        switch (state) {
            case FOCUSED: return MeteringState.SUCCESS;
            case FAILED: return MeteringState.FAILED;
            case TIMED_OUT: return MeteringState.TIMED_OUT;
            default: return MeteringState.SCANNING;
        }
    }

    private MeteringRectangle meteringRectangle(float normalizedX, float normalizedY) {
        Rect activeArray = activeArrayForOutput();
        int regionWidth = Math.max(1, Math.round(activeArray.width() * TAP_REGION_SIZE));
        int regionHeight = Math.max(1, Math.round(activeArray.height() * TAP_REGION_SIZE));
        int centerX = activeArray.left + Math.round(normalizedX * activeArray.width());
        int centerY = activeArray.top + Math.round(normalizedY * activeArray.height());
        int left = clamp(centerX - regionWidth / 2,
                activeArray.left, activeArray.right - regionWidth);
        int top = clamp(centerY - regionHeight / 2,
                activeArray.top, activeArray.bottom - regionHeight);
        return new MeteringRectangle(
                new Rect(left, top, left + regionWidth, top + regionHeight),
                MeteringRectangle.METERING_WEIGHT_MAX);
    }

    private Rect activeArrayForOutput() {
        CameraCharacteristics selected = characteristics(route.characteristicsCameraId);
        Rect active = null;
        if (selected != null && output.maximumResolutionMode
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            active = Api31.maximumResolutionActiveArray(selected);
        }
        if (active == null && selected != null) {
            active = selected.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        }
        if (active == null) {
            active = new Rect(0, 0, output.size.width, output.size.height);
        }
        return active;
    }

    private boolean supportsAfMode(int mode) {
        if (availableAfModes == null) return false;
        for (int available : availableAfModes) if (available == mode) return true;
        return false;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(value, Math.max(lower, upper)));
    }

    private static long clamp(long value, long lower, long upper) {
        return Math.max(lower, Math.min(value, Math.max(lower, upper)));
    }

    private void scheduleFocusTimeout() {
        long generation = meteringRequestGeneration;
        focusTimeout = () -> {
            focusTimeout = null;
            if (meteringPointSet && generation == meteringRequestGeneration)
                notifyMeteringState(focusState(autofocusFeedback.timeout(generation)));
        };
        cameraHandler.postDelayed(focusTimeout, usesLongPreview()
                ? Math.max(FOCUS_TIMEOUT_MS, exposureTimeNs / 1_000_000L * 3L) : FOCUS_TIMEOUT_MS);
    }

    private void cancelFocusTimeout() {
        Runnable reset = focusTimeout;
        focusTimeout = null;
        if (reset != null && cameraHandler != null) cameraHandler.removeCallbacks(reset);
    }

    private void resetMetering() {
        cancelFocusTimeout();
        meteringPointSet = false;
        meteringRequestGeneration++;
        notifyMeteringState(MeteringState.RESET);
    }

    private void notifyMeteringState(MeteringState state) {
        if (focusReportedState == state) return;
        focusReportedState = state;
        RawSessionListener current = listener;
        if (current == null) return;
        try { current.onFocusStateChanged(focusClientGeneration, state); }
        catch (RuntimeException ignored) { }
    }

    private void notifyExposureState(MeteringState state) {
        if (exposureReportedState == state) return;
        exposureReportedState = state;
        RawSessionListener current = listener;
        if (current == null) return;
        try { current.onExposureStateChanged(exposureRequestGeneration, state); }
        catch (RuntimeException ignored) { }
    }

    static Integer preferredOpticalStabilizationMode(int[] routeModes, int[] openCameraModes) {
        // A physical lens's advertised modes take precedence over its logical camera.
        int[] modes = routeModes != null ? routeModes : openCameraModes;
        if (supportsMode(modes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            return CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
        }
        return supportsMode(modes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF : null;
    }

    static boolean shouldRequestLensShadingMap(int[] routeModes, int[] openCameraModes) {
        return supportsLensShadingMap(routeModes) || supportsLensShadingMap(openCameraModes);
    }

    private static int[] lensShadingMapModes(CameraCharacteristics characteristics) {
        return characteristics == null ? null : characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES);
    }

    private static boolean supportsLensShadingMap(int[] modes) {
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) return true;
        }
        return false;
    }

    private CameraCharacteristics requiredCharacteristics(String cameraId) throws CameraAccessException {
        CameraCharacteristics value = sessionCharacteristics.get(cameraId);
        if (value == null) {
            value = cameraManager.getCameraCharacteristics(cameraId);
            sessionCharacteristics.put(cameraId, value);
        }
        return value;
    }

    private CameraCharacteristics characteristics(String cameraId) {
        try { return requiredCharacteristics(cameraId); }
        catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private Executor handlerExecutor() {
        return command -> {
            Handler handler = cameraHandler;
            if (handler == null || !handler.post(command)) {
                command.run();
            }
        };
    }

    private void scheduleStillCaptureTimeout() {
        scheduleStillCaptureTimeout(false);
    }

    private void scheduleStillCaptureTimeout(boolean started) {
        cancelStillCaptureTimeout();
        Runnable timeout = () -> {
            if (state == State.STILL_CAPTURING && !stillFrameDelivered) {
                stillFailure(new SessionError(ErrorCode.CAPTURE_FAILURE,
                        "Timed out waiting for the full-resolution RAW image and metadata",
                        false, null));
            }
        };
        stillCaptureTimeout = timeout;
        cameraHandler.postDelayed(timeout, StillExposure.timeoutMs(stillExposure == null
                ? exposureTimeNs : stillExposure.exposureTimeNs, started));
    }

    private void cancelStillCaptureTimeout() {
        Runnable timeout = stillCaptureTimeout;
        stillCaptureTimeout = null;
        Handler handler = cameraHandler;
        if (timeout != null && handler != null) handler.removeCallbacks(timeout);
    }

    private void unsupported(String message) {
        transition(State.UNSUPPORTED);
        report(new SessionError(ErrorCode.API_UNSUPPORTED, message, true, null));
    }

    private void fail(SessionError error) {
        State current = state;
        if (current == State.CLOSING || current == State.CLOSED || current == State.ERROR) return;
        report(error);
        transition(State.ERROR);
        cleanup(State.ERROR);
    }

    private void report(SessionError error) {
        InternalLogRecorder.error("Camera2", "route=" + (route == null ? "none" : route.routeId)
                + " state=" + state + " code=" + error.code + " fatal=" + error.fatal
                + " message=" + error.message, error.cause);
        RawSessionListener current = listener;
        if (current == null) return;
        try { current.onError(error); }
        catch (RuntimeException ignored) { }
    }

    private void transition(State next) {
        InternalLogRecorder.event("Camera2", "route=" + (route == null ? "none" : route.routeId)
                + " state=" + state + "->" + next + " output=" + (output == null ? "none" : output.format + " " + output.size)
                + " companion=" + (meteringReader == null ? "none" : meteringReader.getImageFormat())
                + " dropped=" + pendingImage.dropped());
        state = next;
        RawSessionListener current = listener;
        if (current == null) return;
        try { current.onStateChanged(next); }
        catch (RuntimeException ignored) { }
    }

    @Override
    public synchronized void close() {
        closeRequested = true;
        if (state == State.CLOSED || state == State.CLOSING) return;
        if (state == State.IDLE || state == State.UNSUPPORTED) {
            controlRequests.close();
            controlRequestExecutor.shutdown();
            transition(State.CLOSED);
            resourcesReleased.countDown();
            return;
        }
        transition(State.CLOSING);
        Handler handler = cameraHandler;
        if (handler != null && Looper.myLooper() != handler.getLooper() && handler.post(
                () -> cleanup(State.CLOSED))) {
            return;
        }
        cleanup(State.CLOSED);
    }

    private void cleanup(State terminalState) {
        recordingRequests.stop();
        controlRequests.close();
        controlRequestExecutor.shutdown();
        cancelGpuBufferTimeout();
        cancelLongPreviewTimeout();
        if (cameraHandler != null) cameraHandler.removeCallbacks(longPreviewRequest);
        longPreviewInFlight = false;
        repeatingPreviewActive = false;
        captureSessionRestartPending = false;
        stillRequestAwaitingRestart = false;
        longPreviewImageDelivered = false;
        singleCaptureIdle = true;
        singleCaptureUsed = false;
        captureNextPreviewFrame = false;
        previewDngMetadata.clear();
        previewResults.clear();
        reopenForStreamRetry = false;
        reopenForExposureChange = false;
        cancelStillCaptureTimeout();
        cancelFlashMetering();
        flashPreviewPaused = false;
        cancelFocusTimeout();
        repeatingRequestBuilder = null;
        afterSessionClosed = null;
        closingSession = null;
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            try { session.stopRepeating(); }
            catch (CameraAccessException | RuntimeException ignored) { }
            try { session.close(); } catch (RuntimeException ignored) { }
        }
        CameraDevice device = cameraDevice;
        if (device != null) {
            try { closeCameraDevice(device); } catch (RuntimeException ignored) { }
        }
        pendingImage.clear();
        pendingStillImage.clear();
        pendingIspImage.clear();
        metadata.clear();
        ispMetadata.clear();
        ispResults.clear();
        ImageReader reader = imageReader;
        ImageReader jpeg = auxiliaryJpegReader;
        auxiliaryJpegReader = null;
        if (jpeg != null) {
            try { jpeg.close(); } catch (RuntimeException ignored) { }
        }
        try { closeMeteringReader(); } catch (RuntimeException ignored) { }
        imageReader = null;
        if (reader != null) {
            try { reader.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) { }
            try { retireRawReader(reader); } catch (RuntimeException ignored) { }
        }
        stillCaptureCallback = null;
        transition(terminalState);
        // Late device callbacks use the executor's fallback and still close their device.
        stopCameraThread();
        if (!cameraOpening && closingCameraDevice == null) resourcesReleased.countDown();
    }

    private void stopCameraThread() {
        HandlerThread thread = cameraThread;
        cameraThread = null;
        cameraHandler = null;
        if (thread != null) {
            retiringThread = thread;
            thread.quitSafely();
        }
    }

    private ImageReader createRawReader(RawOutput selected, boolean preview) {
        cancelGpuBufferTimeout();
        rawInputStarted = false;
        int pendingCapacity = RawImageBuffering.pendingImages(preview ? aeFpsRange.upper : 30,
                selected.size.width, selected.size.height);
        pendingImage.setCapacity(pendingCapacity);
        // Metadata matching, queued frames, one active frame and two acquireLatestImage slots.
        int maxImages = pendingCapacity + RawFrameConsumer.MAX_QUEUED_FRAMES + 3;
        long usage = exactConfiguration == null ? HardwareBuffer.USAGE_CPU_READ_OFTEN : exactConfiguration.rawReaderUsage;
        gpuBufferUsage = usage == HardwareBuffer.USAGE_CPU_READ_OFTEN
                && preview && selected.format != RawFormat.RAW_SENSOR
                && !rejectedGpuBufferOutputs.contains(selected);
        if (gpuBufferUsage) {
            try {
                long gpuUsage = usage | HardwareBuffer.USAGE_GPU_DATA_BUFFER;
                if (HardwareBuffer.isSupported(selected.size.width, selected.size.height,
                        selected.format.imageFormat, 1, gpuUsage)) {
                    return Api29.createGpuRawReader(selected, gpuUsage, maxImages);
                }
                rejectedGpuBufferOutputs.add(selected);
                gpuBufferUsage = false;
                InternalLogRecorder.event("CameraRawBuffer", "route=" + route.routeId
                        + " format=" + selected.format + " size=" + selected.size + " unsupported_gpu_usage=CPU");
            } catch (RuntimeException error) {
                rejectedGpuBufferOutputs.add(selected);
                gpuBufferUsage = false;
                InternalLogRecorder.warning("CameraRawBuffer", "route=" + route.routeId
                        + " format=" + selected.format + " allocation_retry=CPU reason=" + error);
            }
        }
        return Api29.createGpuRawReader(selected, usage, maxImages);
    }

    private void scheduleGpuBufferTimeout() {
        cancelGpuBufferTimeout();
        if (!gpuBufferUsage || rawInputStarted) return;
        ImageReader reader = imageReader;
        gpuBufferTimeout = () -> {
            gpuBufferTimeout = null;
            if (reader == imageReader) retryGpuBufferConfiguration();
        };
        cameraHandler.postDelayed(gpuBufferTimeout, Math.max(3_000L, exposureTimeNs / 1_000_000L + 1_000L));
    }

    private void cancelGpuBufferTimeout() {
        if (gpuBufferTimeout != null && cameraHandler != null) cameraHandler.removeCallbacks(gpuBufferTimeout);
        gpuBufferTimeout = null;
    }

    private static final class Api29 {
        @TargetApi(Build.VERSION_CODES.Q)
        static ImageReader createGpuRawReader(RawOutput output, long usage, int maxImages) {
            return ImageReader.newInstance(output.size.width, output.size.height,
                    output.format.imageFormat, maxImages, usage);
        }
    }

    private static final class Api31 {
        @TargetApi(Build.VERSION_CODES.S)
        static Map<String, TotalCaptureResult> physicalCameraResults(
                TotalCaptureResult result) {
            return result.getPhysicalCameraTotalResults();
        }

        @TargetApi(Build.VERSION_CODES.S)
        static Rect maximumResolutionActiveArray(CameraCharacteristics characteristics) {
            return characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
        }
    }


    private static final class LensShadingData {
        final int width;
        final int height;
        final float[] gains;

        LensShadingData(int width, int height, float[] gains) {
            this.width = width;
            this.height = height;
            this.gains = gains;
        }
    }

    private enum SessionMode { PREVIEW, STILL }
}
