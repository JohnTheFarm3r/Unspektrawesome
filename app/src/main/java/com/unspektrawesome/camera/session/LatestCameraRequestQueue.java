package com.unspektrawesome.camera.session;

import java.util.concurrent.Executor;

final class LatestCameraRequestQueue implements AutoCloseable {
    private final Executor executor;
    private final Object lock = new Object();
    private final Object submitting = new Object();
    private Runnable pending;
    private boolean scheduled;
    private boolean closed;

    LatestCameraRequestQueue(Executor executor) {
        this.executor = executor;
    }

    boolean submit(Runnable request) {
        synchronized (lock) {
            if (closed) return false;
            pending = request;
            if (!scheduled) scheduleLocked();
            return true;
        }
    }

    private void scheduleLocked() {
        scheduled = true;
        executor.execute(this::drain);
    }

    private void drain() {
        try {
            synchronized (submitting) {
                Runnable request;
                synchronized (lock) {
                    request = pending;
                    pending = null;
                }
                if (request != null) request.run();
            }
        } finally {
            synchronized (lock) {
                scheduled = false;
                if (pending != null && !closed) scheduleLocked();
            }
        }
    }

    // Finish the in-flight submission before a still capture, stream change, or close.
    void cancelAndWait() {
        synchronized (lock) { pending = null; }
        synchronized (submitting) { }
    }

    @Override
    public void close() {
        synchronized (lock) { closed = true; }
        cancelAndWait();
    }
}
