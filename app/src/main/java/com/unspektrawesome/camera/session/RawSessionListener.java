package com.unspektrawesome.camera.session;

public interface RawSessionListener {
    void onStateChanged(Camera2RawSession.State state);
    void onError(Camera2RawSession.SessionError error);

    default void onMeteringStateChanged(Camera2RawSession.MeteringState state) { }
    default void onFocusStateChanged(long generation, Camera2RawSession.MeteringState state) {
        onMeteringStateChanged(state);
    }
    default void onExposureStateChanged(long generation, Camera2RawSession.MeteringState state) { }
    default void onControlsChanged(Camera2RawSession.ControlState state) { }
}
