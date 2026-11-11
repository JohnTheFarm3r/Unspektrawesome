package com.unspektrawesome.camera.session;

/** Manual sensor exposure selected for a still request. */
final class StillExposure {
    final int iso;
    final long exposureTimeNs;
    final long frameDurationNs;

    StillExposure(int iso, long exposureTimeNs, long frameDurationNs) {
        this.iso = iso;
        this.exposureTimeNs = exposureTimeNs;
        this.frameDurationNs = Math.max(exposureTimeNs, frameDurationNs);
    }

    static long timeoutMs(long exposureTimeNs, boolean started) {
        long exposureMs = Math.max(0L, exposureTimeNs) / 1_000_000L + 1L;
        return 15_000L + exposureMs * (started ? 1L : 2L);
    }
}
