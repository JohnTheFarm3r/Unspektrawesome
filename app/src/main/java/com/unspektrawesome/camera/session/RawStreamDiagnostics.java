package com.unspektrawesome.camera.session;

import java.util.Locale;

final class RawStreamDiagnostics {
    private static final long INTERVAL_NS = 5_000_000_000L;
    private long startNs;
    private long startFrame;
    private long startSensorNs;
    private boolean started;
    private long results;
    private long rawImages;
    private long companionImages;
    private long deliveries;
    private long skipped;
    private long acquireNs;
    private long acquireMaxNs;
    private long drainNs;
    private long drainMaxNs;
    private long deliveryNs;
    private long deliveryMaxNs;
    private long requestUpdates;
    private long requestUpdateNs;
    private long requestUpdateMaxNs;

    void reset() {
        started = false;
        clearCounts();
    }

    void rawAcquired(long elapsedNs) {
        rawImages++;
        acquireNs += elapsedNs;
        acquireMaxNs = Math.max(acquireMaxNs, elapsedNs);
    }

    void companionDrained(long elapsedNs) {
        companionImages++;
        drainNs += elapsedNs;
        drainMaxNs = Math.max(drainMaxNs, elapsedNs);
    }

    void previewReleased(long elapsedNs, boolean consumerSkipped) {
        deliveries++;
        if (consumerSkipped) skipped++;
        deliveryNs += elapsedNs;
        deliveryMaxNs = Math.max(deliveryMaxNs, elapsedNs);
    }

    void requestUpdated(long elapsedNs) {
        requestUpdates++;
        requestUpdateNs += elapsedNs;
        requestUpdateMaxNs = Math.max(requestUpdateMaxNs, elapsedNs);
    }

    String capture(long frameNumber, long sensorNs, long nowNs) {
        if (!started || frameNumber < startFrame || sensorNs <= startSensorNs) {
            begin(frameNumber, sensorNs, nowNs);
            return "warmup=true";
        }
        results++;
        long elapsedNs = nowNs - startNs;
        if (elapsedNs < INTERVAL_NS) return null;
        String detail = String.format(Locale.ROOT,
                "window_ms=%.1f capture_fps=%.2f result_fps=%.2f raw_fps=%.2f companion_fps=%.2f "
                        + "results=%d raw_images=%d companion_images=%d deliveries=%d consumer_skips=%d "
                        + "acquire_ms=%.3f/%.3f drain_ms=%.3f/%.3f delivery_ms=%.3f/%.3f "
                        + "request_updates=%d request_update_ms=%.3f/%.3f",
                elapsedNs / 1e6, (frameNumber - startFrame) * 1e9 / (sensorNs - startSensorNs),
                results * 1e9 / elapsedNs, rawImages * 1e9 / elapsedNs, companionImages * 1e9 / elapsedNs,
                results, rawImages, companionImages, deliveries, skipped,
                meanMs(acquireNs, rawImages), acquireMaxNs / 1e6,
                meanMs(drainNs, companionImages), drainMaxNs / 1e6,
                meanMs(deliveryNs, deliveries), deliveryMaxNs / 1e6,
                requestUpdates, meanMs(requestUpdateNs, requestUpdates), requestUpdateMaxNs / 1e6);
        begin(frameNumber, sensorNs, nowNs);
        return detail;
    }

    private void begin(long frameNumber, long sensorNs, long nowNs) {
        startNs = nowNs;
        startFrame = frameNumber;
        startSensorNs = sensorNs;
        started = true;
        clearCounts();
    }

    private void clearCounts() {
        results = rawImages = companionImages = deliveries = skipped = 0;
        acquireNs = acquireMaxNs = drainNs = drainMaxNs = deliveryNs = deliveryMaxNs = 0;
        requestUpdates = requestUpdateNs = requestUpdateMaxNs = 0;
    }

    private static double meanMs(long durationNs, long count) {
        return count == 0 ? 0 : durationNs / (count * 1e6);
    }
}
