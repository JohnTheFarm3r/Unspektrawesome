package com.unspektrawesome.camera.session;

import com.unspektrawesome.camera.exposure.SensorExposure;

final class RecordingExposureRamp {
    private double isoEv;
    private double timeEv;

    void reset(int iso, long timeNs) {
        isoEv = log2(iso);
        timeEv = log2(timeNs);
    }

    SensorExposure next(int targetIso, long targetTimeNs, long frameDurationNs, int framesRemaining) {
        double isoDelta = log2(targetIso) - isoEv;
        double timeDelta = log2(targetTimeNs) - timeEv;
        // AE already limits its response. Spread its latest target across this burst
        // without adding a slower ramp that leaves the sensor behind the controls.
        double maxStep = Math.min(.20, frameDurationNs * 6e-9);
        double scale = Math.max(framesRemaining, Math.max(Math.abs(isoDelta + timeDelta),
                Math.max(Math.abs(isoDelta), Math.abs(timeDelta))) / maxStep);
        isoEv += isoDelta / scale;
        timeEv += timeDelta / scale;
        return new SensorExposure((int) Math.round(Math.pow(2.0, isoEv)),
                Math.round(Math.pow(2.0, timeEv)));
    }

    private static double log2(double value) { return Math.log(value) / Math.log(2.0); }
}
