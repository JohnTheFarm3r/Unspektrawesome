package com.unspektrawesome.camera.session;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CaptureResult;
import android.media.Image;

import com.unspektrawesome.camera.RawOutput;

import java.util.Objects;

/** A processed camera frame from the ISP viewfinder stream, retained until GPU consumption finishes. */
public final class IspHardwareFrame implements AutoCloseable {
    public final HardwareBuffer hardwareBuffer;
    public final RawCaptureMetadata metadata;
    public final CaptureResult captureResult;
    public final int width;
    public final int height;
    public final int format;
    /** RAW stream layout of the same capture, which defines the viewfinder geometry. */
    public final RawOutput rawOutput;
    /** Decoded RAW row stride, or 0 before the session has seen a RAW frame. */
    public final int rawRowStrideBytes;
    private final Image image;
    private int references = 1;
    private boolean ownerClosed;

    IspHardwareFrame(Image image, HardwareBuffer hardwareBuffer, RawCaptureMetadata metadata,
                     CaptureResult captureResult, RawOutput rawOutput, int rawRowStrideBytes) {
        this.image = Objects.requireNonNull(image);
        this.hardwareBuffer = hardwareBuffer;
        this.metadata = Objects.requireNonNull(metadata);
        this.captureResult = captureResult;
        this.rawOutput = Objects.requireNonNull(rawOutput);
        this.rawRowStrideBytes = Math.max(rawRowStrideBytes, 0);
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.format = image.getFormat();
    }

    /** CPU-readable planes, available only for YUV_420_888 frames while a lease is held. */
    public synchronized Image.Plane[] planes() {
        if (references == 0 || format != ImageFormat.YUV_420_888) return null;
        try { return image.getPlanes(); } catch (RuntimeException ignored) { return null; }
    }

    public synchronized Lease retain() {
        if (references == 0) return null;
        references++;
        return new Lease(this);
    }

    private synchronized void release() {
        if (--references == 0) {
            try { if (hardwareBuffer != null) hardwareBuffer.close(); }
            finally { try { image.close(); } catch (RuntimeException ignored) { } }
        }
    }

    @Override public synchronized void close() {
        if (ownerClosed) return;
        ownerClosed = true;
        release();
    }

    public static final class Lease implements AutoCloseable {
        public final IspHardwareFrame frame;
        private boolean closed;
        private Lease(IspHardwareFrame frame) { this.frame = frame; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            frame.release();
        }
    }
}
