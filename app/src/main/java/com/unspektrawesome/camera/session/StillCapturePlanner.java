package com.unspektrawesome.camera.session;

import com.unspektrawesome.camera.RawFormat;
import com.unspektrawesome.camera.RawOutput;

import java.util.List;
import java.util.Set;

/** Validates capture state and preserves the active RAW stream. */
final class StillCapturePlanner {
    private StillCapturePlanner() {}

    static boolean canReusePreview(RawOutput preview, RawOutput capture) {
        return preview.format == capture.format && preview.size.equals(capture.size)
                && preview.maximumResolutionMode == capture.maximumResolutionMode;
    }

    static RawOutput select(Camera2RawSession.State state, RawOutput preview, List<RawOutput> outputs,
                            Set<RawFormat> importableFormats) {
        if (state != Camera2RawSession.State.STREAMING) {
            throw new IllegalStateException("Still capture requires an active RAW preview");
        }
        if (!importableFormats.contains(preview.format)
                || outputs.stream().noneMatch(output -> canReusePreview(preview, output))) {
            throw new IllegalArgumentException("The active RAW stream is unavailable for capture");
        }
        return preview;
    }

    static boolean canResume(Camera2RawSession.State state) {
        return state == Camera2RawSession.State.STILL_CAPTURED
                || state == Camera2RawSession.State.STILL_FAILED;
    }
}
