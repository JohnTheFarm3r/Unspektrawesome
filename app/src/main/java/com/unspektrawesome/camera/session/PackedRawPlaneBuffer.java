package com.unspektrawesome.camera.session;

import com.unspektrawesome.camera.RawFormat;

import java.nio.ByteBuffer;
import java.util.Objects;

final class PackedRawPlaneBuffer {
    private PackedRawPlaneBuffer() {}

    static ByteBuffer borrow(ByteBuffer plane, int rowStrideBytes, int width, int height,
                             RawFormat format, int pixelStrideBytes) {
        int expected = format == RawFormat.RAW_SENSOR ? 2 : 0;
        if (pixelStrideBytes != expected) {
            throw new IllegalArgumentException("Unsupported " + format + " pixel stride " + pixelStrideBytes);
        }
        return borrow(plane, rowStrideBytes, width, height, format);
    }

    static ByteBuffer borrow(ByteBuffer plane, int rowStrideBytes, int width, int height,
                             RawFormat format) {
        Objects.requireNonNull(plane, "Packed RAW plane is null");
        Objects.requireNonNull(format, "RAW format is null");
        if (!plane.isDirect()) {
            throw new IllegalArgumentException("Packed RAW plane must be a direct ByteBuffer");
        }
        if (rowStrideBytes <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Packed RAW plane dimensions must be positive");
        }
        long finalRowBytes;
        switch (format) {
            case RAW_SENSOR:
                finalRowBytes = (long) width * 2L;
                break;
            case RAW10:
                finalRowBytes = ((long) width + 3L) / 4L * 5L;
                break;
            case RAW12:
                finalRowBytes = ((long) width + 1L) / 2L * 3L;
                break;
            default:
                throw new IllegalArgumentException("Unsupported RAW format");
        }
        if (rowStrideBytes < finalRowBytes) {
            throw new IllegalArgumentException("RAW row stride is smaller than the final packed row");
        }
        long minimum = (long) rowStrideBytes * (height - 1L) + finalRowBytes;
        long fullRows = (long) rowStrideBytes * height;
        if (fullRows > Integer.MAX_VALUE || plane.remaining() < minimum) {
            throw new IllegalArgumentException(
                    "RAW plane is smaller than its row-stride layout");
        }
        ByteBuffer view = plane.slice();
        view.limit((int) Math.min(fullRows, plane.remaining()));
        return view.slice().order(plane.order());
    }
}
