package com.unspektrawesome.camera.session;

public interface RawStillCaptureCallback {
    default void onStillCaptureRequested(long exposureTimeNs) { }

    default void onStillCaptureStarted(long exposureTimeNs) { }

    default void onStillCaptureCanceled() { }

    /** Called once with the full-resolution buffer still owned by its Image. */
    void onStillFrame(RawHardwareFrame frame);

    /** Called after resumePreview has restored the RAW repeating request. */
    void onPreviewResumed();

    /** Reports a recoverable still-capture failure. Preview can still be resumed. */
    void onStillCaptureError(Camera2RawSession.SessionError error);
}
