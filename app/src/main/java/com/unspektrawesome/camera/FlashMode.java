package com.unspektrawesome.camera;

public enum FlashMode {
    OFF("Off", null),
    AUTO("Auto", "A"),
    TORCH("Always on", "ON"),
    SINGLE("On capture", "SHOT");

    public final String label;
    public final String badge;

    FlashMode(String label, String badge) {
        this.label = label;
        this.badge = badge;
    }

    public FlashMode next() {
        FlashMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    public boolean usesCaptureFlash() {
        return this == AUTO || this == SINGLE;
    }
}
