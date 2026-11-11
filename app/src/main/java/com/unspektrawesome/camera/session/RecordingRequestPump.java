package com.unspektrawesome.camera.session;

final class RecordingRequestPump {
    static final int BURST_FRAMES = 4;
    private int refillFrames;
    private int batchFrames = BURST_FRAMES;
    private long generation;
    private boolean active;
    private boolean submitting;
    private int queuedFrames;

    void start(int frameRate) {
        start(frameRate, 0);
    }

    void start(int frameRate, int pipelineDepth) {
        start(frameRate, pipelineDepth, BURST_FRAMES);
    }

    void start(int frameRate, int pipelineDepth, int batchFrames) {
        if (batchFrames < 1 || batchFrames > 32) throw new IllegalArgumentException("Invalid RAW request batch size");
        stop();
        this.batchFrames = batchFrames;
        // Cover the camera pipeline and at least 125 ms of request submission time.
        refillFrames = Math.max(Math.max(batchFrames, Math.min(8, pipelineDepth)), (frameRate + 7) / 8);
        active = true;
    }

    void stop() {
        generation++;
        active = submitting = false;
        queuedFrames = 0;
    }

    boolean active() { return active; }
    int batchFrames() { return batchFrames; }

    String diagnosticState() {
        return "request_queued=" + queuedFrames + " request_submitting=" + submitting + " request_pump=" + active;
    }

    boolean frameStarted(long token) {
        if (!active || token != generation || queuedFrames == 0) return false;
        queuedFrames--;
        return true;
    }

    long beginSubmission() {
        if (!active || queuedFrames > refillFrames || submitting) return 0;
        queuedFrames += batchFrames;
        submitting = true;
        return generation;
    }

    boolean submitted(long token) {
        if (!active || token != generation) return false;
        submitting = false;
        return true;
    }
}
