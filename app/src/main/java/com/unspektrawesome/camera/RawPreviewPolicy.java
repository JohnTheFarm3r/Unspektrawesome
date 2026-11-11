package com.unspektrawesome.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.EnumSet;

/** Selects a RAW acquisition stream and independent even-sized GPU processing dimensions. */
public final class RawPreviewPolicy {
    public static final int TARGET_FRAMES_PER_SECOND = 30;
    private static final double FPS_TOLERANCE = 0.25;

    public enum Quality {
        LOW(0.25f), MEDIUM(0.50f), HIGH(0.75f), FULL(1.0f);
        final float scale;
        Quality(float scale) { this.scale = scale; }
    }

    public static final class Plan {
        public final RawOutput acquisitionOutput;
        public final Size2d processingSize;
        public final int framesPerSecond;
        public final IntRange aeTargetRange;
        public final com.unspektrawesome.camera.session.RawSessionConfiguration sessionConfiguration;

        Plan(RawOutput acquisitionOutput, Size2d processingSize, int framesPerSecond,
             IntRange aeTargetRange) {
            this(acquisitionOutput, processingSize, framesPerSecond, aeTargetRange, null);
        }

        private Plan(RawOutput acquisitionOutput, Size2d processingSize, int framesPerSecond,
                     IntRange aeTargetRange, com.unspektrawesome.camera.session.RawSessionConfiguration configuration) {
            this.acquisitionOutput = acquisitionOutput;
            this.processingSize = processingSize;
            this.framesPerSecond = framesPerSecond;
            this.aeTargetRange = aeTargetRange;
            this.sessionConfiguration = configuration;
        }

        public boolean meetsRealtimeTarget() { return framesPerSecond >= 30; }

        public Plan withProcessingSize(Size2d size) {
            if (size.width > acquisitionOutput.size.width || size.height > acquisitionOutput.size.height) {
                throw new IllegalArgumentException("Processing dimensions exceed the RAW stream");
            }
            return new Plan(acquisitionOutput, size, framesPerSecond, aeTargetRange, sessionConfiguration);
        }

        public Plan withSessionConfiguration(com.unspektrawesome.camera.session.RawSessionConfiguration configuration) {
            return new Plan(acquisitionOutput, processingSize, configuration.fpsRange.upper,
                    configuration.fpsRange, configuration);
        }

        public Plan withFrameRate(int fps, IntRange range) {
            if (fps <= 0) throw new IllegalArgumentException("Frame rate must be positive");
            com.unspektrawesome.camera.session.RawSessionConfiguration configuration = sessionConfiguration;
            if (configuration != null) configuration = configuration.withFrameRate(range);
            return new Plan(acquisitionOutput, processingSize, fps, range, configuration);
        }
    }

    private RawPreviewPolicy() {}

    public static Plan select(List<RawOutput> outputs, List<IntRange> aeRanges,
                              Quality quality, int preferredFps) {
        return select(outputs, aeRanges, quality, preferredFps, EnumSet.allOf(RawFormat.class));
    }

    /** importableFormats should come from the active GPU external-memory capability query. */
    public static Plan select(List<RawOutput> outputs, List<IntRange> aeRanges,
                              Quality quality, int preferredFps,
                              Set<RawFormat> importableFormats) {
        return select(outputs, aeRanges, quality, preferredFps, importableFormats, false);
    }

    public static Plan select(List<RawOutput> outputs, List<IntRange> aeRanges,
                              Quality quality, int preferredFps,
                              Set<RawFormat> importableFormats, boolean preferAdvertisedFpsRange) {
        Objects.requireNonNull(quality);
        Objects.requireNonNull(importableFormats);
        if (preferredFps <= 0) throw new IllegalArgumentException("Preferred FPS must be positive");
        List<RawOutput> regular = new ArrayList<>();
        for (RawOutput output : outputs) {
            if (!output.maximumResolutionMode && importableFormats.contains(output.format)) regular.add(output);
        }
        if (regular.isEmpty()) {
            for (RawOutput output : outputs) {
                if (importableFormats.contains(output.format)) regular.add(output);
            }
        }
        if (regular.isEmpty()) throw new IllegalArgumentException("No RAW preview output");

        RawOutput reference = regular.stream().max(Comparator.comparingLong(value -> value.size.area()))
                .orElseThrow(AssertionError::new);
        Size2d processing = scaledEven(reference.size, quality.scale);

        List<RawOutput> realtime = new ArrayList<>();
        for (RawOutput output : regular) {
            if (output.maximumFps() + FPS_TOLERANCE >= preferredFps) realtime.add(output);
        }
        List<RawOutput> candidates = realtime.isEmpty() ? regular : realtime;
        RawOutput acquisition = chooseAcquisition(candidates, processing);
        FpsChoice fps = chooseFps(acquisition, aeRanges, preferredFps, preferAdvertisedFpsRange);
        return new Plan(acquisition, fitWithin(processing, acquisition.size), fps.fps, fps.range);
    }

    static Size2d scaledEven(Size2d source, float scale) {
        if (!(scale > 0f && scale <= 1f)) throw new IllegalArgumentException("Scale must be in (0, 1]");
        int width = Math.max(2, ((int) Math.floor(source.width * scale)) & ~1);
        int height = Math.max(2, ((int) Math.floor(source.height * scale)) & ~1);
        return new Size2d(width, height);
    }

    private static RawOutput chooseAcquisition(List<RawOutput> outputs, Size2d processing) {
        Comparator<RawOutput> order = Comparator
                .comparing((RawOutput value) -> value.size.area() < processing.area())
                .thenComparingLong(value -> value.size.area() >= processing.area()
                        ? value.size.area() : -value.size.area())
                .thenComparingInt(value -> previewFormatRank(value.format));
        return outputs.stream().min(order).orElseThrow(AssertionError::new);
    }

    private static Size2d fitWithin(Size2d requested, Size2d source) {
        if (requested.width <= source.width && requested.height <= source.height) return requested;
        float scale = Math.min((float) source.width / requested.width, (float) source.height / requested.height);
        return scaledEven(requested, Math.min(1f, scale));
    }

    private static FpsChoice chooseFps(RawOutput output, List<IntRange> ranges, int preferred,
                                       boolean preferAdvertisedFpsRange) {
        int ceiling = output.minimumFrameDurationNs == 0 ? preferred
                : Math.max(1, (int) Math.floor(output.maximumFps() + FPS_TOLERANCE));
        // Some MediaTek RAW durations understate the sensor's supported repeating rate.
        int limit = preferAdvertisedFpsRange && !ranges.isEmpty() ? preferred : Math.min(preferred, ceiling);
        IntRange bestRange = null;
        int bestFps = 0;
        for (IntRange range : ranges) {
            int fps = Math.min(limit, range.upper);
            if (fps >= range.lower && (fps > bestFps
                    || fps == bestFps && (bestRange == null || range.lower > bestRange.lower))) {
                bestFps = fps;
                bestRange = range;
            }
        }
        if (bestRange == null && output.maximumResolutionMode && !ranges.isEmpty()) {
            IntRange range = ranges.stream().min(Comparator.comparingInt(value -> value.lower))
                    .orElseThrow(AssertionError::new);
            return new FpsChoice(Math.min(limit, range.upper), range);
        }
        return bestRange == null ? new FpsChoice(limit, new IntRange(limit, limit))
                : new FpsChoice(bestFps, bestRange);
    }

    private static int previewFormatRank(RawFormat format) {
        switch (format) {
            case RAW10: return 0;
            case RAW12: return 1;
            default: return 2;
        }
    }

    private static final class FpsChoice {
        final int fps;
        final IntRange range;
        FpsChoice(int fps, IntRange range) { this.fps = fps; this.range = range; }
    }
}
