package com.unspektrawesome.camera.session;

import android.hardware.camera2.CameraMetadata;

final class AutofocusFeedback {
    enum State { SCANNING, FOCUSED, FAILED, TIMED_OUT }

    private long generation;
    private long triggerFrame = Long.MAX_VALUE;
    private long lastFrame = -1;
    private State state = State.SCANNING;

    void start(long value) {
        generation = value;
        triggerFrame = Long.MAX_VALUE;
        lastFrame = -1;
        state = State.SCANNING;
    }

    State update(long requestGeneration, long frameNumber, Integer trigger, Integer afState) {
        if (requestGeneration != generation || trigger == null ||
                trigger == CameraMetadata.CONTROL_AF_TRIGGER_CANCEL) return state;
        if (state == State.FOCUSED || state == State.FAILED) return state;
        if (trigger == CameraMetadata.CONTROL_AF_TRIGGER_START) triggerFrame = Math.min(triggerFrame, frameNumber);
        if (frameNumber < triggerFrame || frameNumber < lastFrame || afState == null) return state;
        lastFrame = frameNumber;
        if (afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED) state = State.FOCUSED;
        else if (afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) state = State.FAILED;
        else if (state != State.TIMED_OUT) state = State.SCANNING;
        return state;
    }

    State timeout(long requestGeneration) {
        if (requestGeneration == generation && state == State.SCANNING) state = State.TIMED_OUT;
        return state;
    }

    State fail(long requestGeneration) {
        if (requestGeneration == generation) state = State.FAILED;
        return state;
    }
}
