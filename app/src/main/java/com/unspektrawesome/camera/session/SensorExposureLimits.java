package com.unspektrawesome.camera.session;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;

public final class SensorExposureLimits {
    private static final long MAXIMUM_SHUTTER_NS = 30_000_000_000L;

    public final long minimumNs;
    public final long maximumNs;
    public final long maximumFrameDurationNs;

    SensorExposureLimits(long minimumNs, long maximumNs, long maximumFrameDurationNs) {
        this.minimumNs = minimumNs;
        // Driver timing limits can exceed the usable long-exposure range.
        this.maximumNs = Math.min(maximumNs, MAXIMUM_SHUTTER_NS);
        this.maximumFrameDurationNs = Math.max(this.maximumNs, maximumFrameDurationNs);
    }

    public static SensorExposureLimits read(CameraCharacteristics selected, CameraCharacteristics opened) {
        Range<Long> standard = get(selected, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (standard == null) standard = get(opened, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (standard == null) return null;
        Long frameDuration = get(selected, CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        if (frameDuration == null) frameDuration = get(opened, CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        SensorExposureLimits limits = new SensorExposureLimits(standard.getLower(), standard.getUpper(),
                frameDuration == null ? standard.getUpper() : frameDuration);
        CameraCharacteristics sensor = selected == null ? opened : selected;
        limits = limits.extend(vendorRange(sensor, "org.codeaurora.qcamera3.iso_exp_priority.exposure_time_range"), null);
        return limits.extend(vendorRange(sensor, "xiaomi.sensor.info.exposureTimeRange"),
                vendorDuration(sensor, "xiaomi.sensor.info.maxFrameDuration"));
    }

    SensorExposureLimits extend(long[] range, Long frameDurationNs) {
        if (range == null || range.length != 2 || range[0] <= 0 || range[1] < range[0]
                || range[1] < maximumNs) return this;
        long duration = Math.max(maximumFrameDurationNs, range[1]);
        if (frameDurationNs != null && frameDurationNs >= range[1]) duration = Math.max(duration, frameDurationNs);
        return new SensorExposureLimits(minimumNs, range[1], duration);
    }

    private static long[] vendorRange(CameraCharacteristics characteristics, String name) {
        if (characteristics == null) return null;
        try {
            long[] range = characteristics.get(new CameraCharacteristics.Key<>(name, long[].class));
            if (range != null) return range;
        } catch (IllegalArgumentException ignored) { }
        Object value = vendorValue(characteristics, name);
        return value instanceof long[] ? (long[]) value : null;
    }

    private static Long vendorDuration(CameraCharacteristics characteristics, String name) {
        if (characteristics == null) return null;
        try {
            Long duration = characteristics.get(new CameraCharacteristics.Key<>(name, Long.class));
            if (duration != null) return duration;
        } catch (IllegalArgumentException ignored) { }
        Object value = vendorValue(characteristics, name);
        if (value instanceof Long) return (Long) value;
        return value instanceof long[] && ((long[]) value).length == 1 ? ((long[]) value)[0] : null;
    }

    private static Object vendorValue(CameraCharacteristics characteristics, String name) {
        if (characteristics == null) return null;
        try {
            for (CameraCharacteristics.Key<?> key : characteristics.getKeys()) {
                if (name.equals(key.getName())) return characteristics.get(key);
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private static <T> T get(CameraCharacteristics characteristics, CameraCharacteristics.Key<T> key) {
        return characteristics == null ? null : characteristics.get(key);
    }
}
