package com.unspektrawesome.camera.session;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;

import java.util.Locale;

public final class OpticalStabilizationSupport {
    private OpticalStabilizationSupport() { }

    /**
     * Some camera systems leave OIS out of a rear lens's advertised modes, yet stabilize when
     * OIS is requested. On these makers, rear lenses request OIS and the capture results decide
     * whether it stays available.
     */
    public static boolean requestsUnadvertisedOis(String manufacturer, Integer lensFacing) {
        if (manufacturer == null || lensFacing == null || lensFacing != CameraMetadata.LENS_FACING_BACK) return false;
        String maker = manufacturer.trim().toLowerCase(Locale.ROOT);
        return maker.equals("motorola") || maker.equals("honor");
    }

    static Integer preferredMode(int[] routeModes, int[] openCameraModes, boolean requestUnadvertised) {
        // A physical lens's advertised modes take precedence over its logical camera.
        int[] modes = routeModes != null ? routeModes : openCameraModes;
        if (supports(modes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) || requestUnadvertised) {
            return CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
        }
        return supports(modes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF : null;
    }

    static boolean advertised(int[] routeModes, int[] openCameraModes) {
        int[] modes = routeModes != null ? routeModes : openCameraModes;
        return supports(modes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
    }

    /** Whether the app requests OIS for a lens, from its characteristics and the device maker. */
    public static boolean available(CameraCharacteristics selected, CameraCharacteristics opened, String manufacturer) {
        int[] routeModes = modes(selected);
        int[] openCameraModes = opened == selected ? null : modes(opened);
        Integer facing = facing(selected);
        if (facing == null) facing = facing(opened);
        return Integer.valueOf(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON).equals(
                preferredMode(routeModes, openCameraModes, requestsUnadvertisedOis(manufacturer, facing)));
    }

    static int[] modes(CameraCharacteristics characteristics) {
        return characteristics == null ? null
                : characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
    }

    static Integer facing(CameraCharacteristics characteristics) {
        return characteristics == null ? null : characteristics.get(CameraCharacteristics.LENS_FACING);
    }

    private static boolean supports(int[] modes, int mode) {
        if (modes == null) return false;
        for (int value : modes) {
            if (value == mode) return true;
        }
        return false;
    }
}
