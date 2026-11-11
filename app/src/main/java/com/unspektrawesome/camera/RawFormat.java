package com.unspektrawesome.camera;

import android.graphics.ImageFormat;

public enum RawFormat {
    RAW_SENSOR(ImageFormat.RAW_SENSOR, 16),
    RAW10(ImageFormat.RAW10, 10),
    RAW12(ImageFormat.RAW12, 12);

    public final int imageFormat;
    public final int storageBitsPerPixel;

    RawFormat(int imageFormat, int storageBitsPerPixel) {
        this.imageFormat = imageFormat;
        this.storageBitsPerPixel = storageBitsPerPixel;
    }

    public int maximumSampleValue() { return (1 << storageBitsPerPixel) - 1; }

    public static RawFormat fromImageFormat(int imageFormat) {
        for (RawFormat value : values()) {
            if (value.imageFormat == imageFormat) return value;
        }
        return null;
    }
}
