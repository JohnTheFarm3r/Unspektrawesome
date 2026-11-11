package com.unspektrawesome.camera.session;

import android.hardware.HardwareBuffer;
import android.media.Image;

import com.unspektrawesome.camera.RawOutput;

import java.nio.ByteBuffer;
import java.util.Objects;

/** A callback frame can be retained until asynchronous GPU consumption finishes. */
public final class RawHardwareFrame implements AutoCloseable {
    public final HardwareBuffer hardwareBuffer;
    public final ByteBuffer packedRawBytes;
    public final RawCaptureMetadata metadata;
    public final RawOutput output;
    public final int rowStrideBytes;
    public final android.hardware.camera2.CaptureResult captureResult;
    private final Image image;
    private final Runnable onReleased;
    private int references = 1;
    private boolean ownerClosed;

    RawHardwareFrame(HardwareBuffer hardwareBuffer, ByteBuffer packedRawBytes,
                     RawCaptureMetadata metadata, RawOutput output, int rowStrideBytes) {
        this(hardwareBuffer, packedRawBytes, metadata, output, rowStrideBytes, null);
    }

    RawHardwareFrame(HardwareBuffer hardwareBuffer, ByteBuffer packedRawBytes,
                     RawCaptureMetadata metadata, RawOutput output, int rowStrideBytes,
                     android.hardware.camera2.CaptureResult captureResult) {
        this(hardwareBuffer, packedRawBytes, metadata, output, rowStrideBytes, captureResult, null, null);
    }

    RawHardwareFrame(HardwareBuffer hardwareBuffer, ByteBuffer packedRawBytes,
                     RawCaptureMetadata metadata, RawOutput output, int rowStrideBytes,
                     android.hardware.camera2.CaptureResult captureResult, Image image, Runnable onReleased) {
        this.hardwareBuffer = Objects.requireNonNull(hardwareBuffer);
        this.packedRawBytes = Objects.requireNonNull(packedRawBytes);
        this.metadata = Objects.requireNonNull(metadata);
        this.output = Objects.requireNonNull(output);
        if (rowStrideBytes <= 0) throw new IllegalArgumentException("RAW row stride must be positive");
        this.rowStrideBytes = rowStrideBytes;
        this.captureResult = captureResult;
        this.image = image;
        this.onReleased = onReleased;
    }

    public synchronized Lease retain() {
        if (image == null || references == 0) return null;
        references++;
        return new Lease(this);
    }

    private synchronized void release() {
        if (--references == 0 && image != null) {
            try { hardwareBuffer.close(); }
            finally {
                try { image.close(); }
                finally { if (onReleased != null) onReleased.run(); }
            }
        }
    }

    @Override public synchronized void close() {
        if (ownerClosed) return;
        ownerClosed = true;
        release();
    }

    public static final class Lease implements AutoCloseable {
        public final RawHardwareFrame frame;
        private boolean closed;
        private Lease(RawHardwareFrame frame) { this.frame = frame; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            frame.release();
        }
    }
}
