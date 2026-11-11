package com.unspektrawesome.camera.session;

import android.hardware.camera2.CameraMetadata;
import com.unspektrawesome.camera.FlashMode;

final class FlashMeteringSequence {
    private final FlashMode mode;
    private final boolean nativeAutoFlash;
    private long triggerFrame = Long.MAX_VALUE;
    private boolean receivedResult;
    private boolean needsFlash;
    private boolean preflashFired;

    FlashMeteringSequence(FlashMode mode, int aeMode) {
        this.mode = mode;
        nativeAutoFlash = aeMode == CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
        needsFlash = mode == FlashMode.SINGLE;
    }

    boolean update(boolean trigger, long frame, Integer aeState, Integer flashState, boolean dark) {
        if (trigger) triggerFrame = frame;
        if (frame < triggerFrame) return false;
        receivedResult = true;
        preflashFired |= Integer.valueOf(CameraMetadata.FLASH_STATE_FIRED).equals(flashState)
                || Integer.valueOf(CameraMetadata.FLASH_STATE_PARTIAL).equals(flashState);
        needsFlash |= Integer.valueOf(CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED).equals(aeState)
                || preflashFired
                || !nativeAutoFlash && dark;
        return aeState == null || aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED
                || aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                || aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED;
    }

    boolean receivedResult() { return receivedResult; }
    boolean needsFlash() { return needsFlash; }

    boolean needsForcedFlashMetering(boolean autoExposure) {
        return autoExposure && mode == FlashMode.AUTO && needsFlash && !preflashFired;
    }

    static int aeMode(FlashMode mode, int[] availableModes) {
        int desired = mode == FlashMode.AUTO ? CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                : mode == FlashMode.SINGLE ? CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                : CameraMetadata.CONTROL_AE_MODE_ON;
        if (availableModes != null) for (int available : availableModes) if (available == desired) return desired;
        return CameraMetadata.CONTROL_AE_MODE_ON;
    }

    static boolean isDark(Long exposureNs, Integer iso, Float aperture) {
        if (exposureNs == null || exposureNs <= 0 || iso == null || iso <= 0) return false;
        double fNumber = aperture == null || !Float.isFinite(aperture) || aperture <= 0 ? 2.0 : aperture;
        double ev100 = Math.log(fNumber * fNumber / (exposureNs / 1_000_000_000.0) * 100.0 / iso) / Math.log(2.0);
        return ev100 < 6.0;
    }
}
