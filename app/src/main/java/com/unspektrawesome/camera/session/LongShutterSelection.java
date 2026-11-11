package com.unspektrawesome.camera.session;

public final class LongShutterSelection {
    public static final long LIMIT_NS = 1_000_000_000L;

    private Long pendingExposureTimeNs;

    public Long pendingExposureTimeNs() { return pendingExposureTimeNs; }

    long select(long appliedNs, long selectedNs) {
        if (selectedNs > LIMIT_NS) {
            pendingExposureTimeNs = selectedNs == appliedNs ? null : selectedNs;
            return Math.max(LIMIT_NS, appliedNs);
        }
        clear();
        return selectedNs;
    }

    long apply(long appliedNs) {
        long result = pendingExposureTimeNs == null ? appliedNs : pendingExposureTimeNs;
        clear();
        return result;
    }

    long cancel(long maximumNs) {
        clear();
        return Math.min(LIMIT_NS, maximumNs);
    }

    void clear() { pendingExposureTimeNs = null; }
}
