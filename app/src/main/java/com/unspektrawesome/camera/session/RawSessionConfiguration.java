package com.unspektrawesome.camera.session;

import com.unspektrawesome.camera.IntRange;
import com.unspektrawesome.camera.Size2d;
import java.util.Objects;

/** An exact stream combination, without automatic companion fallbacks. */
public final class RawSessionConfiguration {
    public final int companionFormat;
    public final Size2d companionSize;
    public final boolean physicalCompanion;
    public final IntRange fpsRange;
    public final boolean allowUnadvertisedRaw;
    public final long rawReaderUsage;
    public final Size2d auxiliaryJpegSize;
    public final long jpegReaderUsage;
    public final int startupDelayMs;
    public final boolean targetCompanion;
    public final int previewRequestTemplate;
    public final long rawStreamUseCase;

    public RawSessionConfiguration(int companionFormat, Size2d companionSize,
                                   boolean physicalCompanion, IntRange fpsRange) {
        this(companionFormat, companionSize, physicalCompanion, fpsRange,
                false, 3L, null, 3L, 0);
    }

    public RawSessionConfiguration(int companionFormat, Size2d companionSize,
                                   boolean physicalCompanion, IntRange fpsRange,
                                   boolean allowUnadvertisedRaw, long rawReaderUsage,
                                   Size2d auxiliaryJpegSize, long jpegReaderUsage, int startupDelayMs) {
        this(companionFormat, companionSize, physicalCompanion, fpsRange, allowUnadvertisedRaw,
                rawReaderUsage, auxiliaryJpegSize, jpegReaderUsage, startupDelayMs, true, 1);
    }

    public RawSessionConfiguration(int companionFormat, Size2d companionSize,
                                   boolean physicalCompanion, IntRange fpsRange,
                                   boolean allowUnadvertisedRaw, long rawReaderUsage,
                                   Size2d auxiliaryJpegSize, long jpegReaderUsage, int startupDelayMs,
                                   boolean targetCompanion, int previewRequestTemplate) {
        this(companionFormat, companionSize, physicalCompanion, fpsRange, allowUnadvertisedRaw,
                rawReaderUsage, auxiliaryJpegSize, jpegReaderUsage, startupDelayMs,
                targetCompanion, previewRequestTemplate, 0L);
    }

    public RawSessionConfiguration(int companionFormat, Size2d companionSize,
                                   boolean physicalCompanion, IntRange fpsRange,
                                   boolean allowUnadvertisedRaw, long rawReaderUsage,
                                   Size2d auxiliaryJpegSize, long jpegReaderUsage, int startupDelayMs,
                                   boolean targetCompanion, int previewRequestTemplate, long rawStreamUseCase) {
        if (companionFormat != 0 && companionFormat != 34 && companionFormat != 35) {
            throw new IllegalArgumentException("Unsupported companion format");
        }
        if ((companionFormat == 0) != (companionSize == null)) {
            throw new IllegalArgumentException("Companion size and format must agree");
        }
        if (rawReaderUsage < 0 || (rawReaderUsage & 0xfL) != 3L
                || jpegReaderUsage < 0 || startupDelayMs < 0 || startupDelayMs > 5_000
                || (previewRequestTemplate != 1 && previewRequestTemplate != 2)
                || rawStreamUseCase < 0) {
            throw new IllegalArgumentException("Invalid RAW session options");
        }
        if (auxiliaryJpegSize != null && (auxiliaryJpegSize.width > 65_536
                || auxiliaryJpegSize.height > 65_536 || auxiliaryJpegSize.area() > Integer.MAX_VALUE / 2)) {
            throw new IllegalArgumentException("Auxiliary output is too large");
        }
        this.companionFormat = companionFormat;
        this.companionSize = companionSize;
        this.physicalCompanion = physicalCompanion;
        this.fpsRange = Objects.requireNonNull(fpsRange);
        this.allowUnadvertisedRaw = allowUnadvertisedRaw;
        this.rawReaderUsage = rawReaderUsage;
        this.auxiliaryJpegSize = auxiliaryJpegSize;
        this.jpegReaderUsage = jpegReaderUsage;
        this.startupDelayMs = startupDelayMs;
        this.targetCompanion = targetCompanion;
        this.previewRequestTemplate = previewRequestTemplate;
        this.rawStreamUseCase = rawStreamUseCase;
    }

    public RawSessionConfiguration withFrameRate(IntRange range) {
        return new RawSessionConfiguration(companionFormat, companionSize, physicalCompanion, range,
                allowUnadvertisedRaw, rawReaderUsage, auxiliaryJpegSize, jpegReaderUsage, startupDelayMs,
                targetCompanion, previewRequestTemplate, rawStreamUseCase);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof RawSessionConfiguration)) return false;
        RawSessionConfiguration value = (RawSessionConfiguration) other;
        return companionFormat == value.companionFormat
                && Objects.equals(companionSize, value.companionSize)
                && physicalCompanion == value.physicalCompanion && fpsRange.equals(value.fpsRange)
                && allowUnadvertisedRaw == value.allowUnadvertisedRaw && rawReaderUsage == value.rawReaderUsage
                && Objects.equals(auxiliaryJpegSize, value.auxiliaryJpegSize)
                && jpegReaderUsage == value.jpegReaderUsage && startupDelayMs == value.startupDelayMs
                && targetCompanion == value.targetCompanion && previewRequestTemplate == value.previewRequestTemplate
                && rawStreamUseCase == value.rawStreamUseCase;
    }

    @Override public int hashCode() {
        return Objects.hash(companionFormat, companionSize, physicalCompanion, fpsRange,
                allowUnadvertisedRaw, rawReaderUsage, auxiliaryJpegSize, jpegReaderUsage, startupDelayMs,
                targetCompanion, previewRequestTemplate, rawStreamUseCase);
    }
}
