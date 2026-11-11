package com.unspektrawesome.camera.session;

final class RawImageBuffering {
    static final int DEFAULT_PENDING_IMAGES = 3;
    private static final int MAX_PENDING_IMAGES = 16;
    private static final long ADDITIONAL_BYTES = 64L * 1024 * 1024;

    private RawImageBuffering() {}

    static int pendingImages(int framesPerSecond, int width, int height) {
        if (framesPerSecond < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException();
        }
        // Allow 100 ms for capture results, with bounded extra storage for faster streams.
        long byFrameRate = (framesPerSecond + 9L) / 10;
        long frameBytes = ((width * 2L + 255) / 256 * 256) * height;
        long byMemory = DEFAULT_PENDING_IMAGES + ADDITIONAL_BYTES / frameBytes;
        return (int) Math.max(DEFAULT_PENDING_IMAGES, Math.min(MAX_PENDING_IMAGES, Math.min(byFrameRate, byMemory)));
    }
}
