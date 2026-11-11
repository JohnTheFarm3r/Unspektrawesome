package com.unspektrawesome.camera.session;

import java.util.ArrayDeque;

/** Retains recent frames while their timestamp-matched capture results are in flight. */
final class PendingFrameQueue<T> {
    interface Releaser<T> { void release(T value); }

    static final class Entry<T> {
        final long timestampNs;
        final T value;

        Entry(long timestampNs, T value) {
            this.timestampNs = timestampNs;
            this.value = value;
        }
    }

    static final class MatchedEntry<T, M> {
        final T value;
        final M metadata;

        MatchedEntry(T value, M metadata) {
            this.value = value;
            this.metadata = metadata;
        }
    }

    private int capacity;
    private final Releaser<T> releaser;
    private final ArrayDeque<Entry<T>> frames = new ArrayDeque<>();
    private long newestTimestampNs = Long.MIN_VALUE;
    private long dropped;

    PendingFrameQueue(int capacity, Releaser<T> releaser) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.releaser = releaser;
    }

    synchronized void setCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        while (frames.size() > capacity) discardFirst();
    }

    synchronized void offer(long timestampNs, T value) {
        if (value == null) throw new IllegalArgumentException("Frame is null");
        if (timestampNs <= newestTimestampNs) {
            releaser.release(value);
            dropped++;
            return;
        }
        newestTimestampNs = timestampNs;
        if (frames.size() == capacity) discardFirst();
        frames.addLast(new Entry<>(timestampNs, value));
    }

    synchronized Entry<T> takeIfTimestamp(long timestampNs) {
        boolean found = false;
        for (Entry<T> frame : frames) {
            if (frame.timestampNs == timestampNs) {
                found = true;
                break;
            }
        }
        if (!found) return null;
        // Once a newer frame is ready, older unmatched frames must never render afterward.
        while (frames.getFirst().timestampNs < timestampNs) discardFirst();
        return frames.removeFirst();
    }

    synchronized Entry<T> takeExact(long timestampNs) {
        java.util.Iterator<Entry<T>> iterator = frames.iterator();
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (entry.timestampNs == timestampNs) {
                iterator.remove();
                return entry;
            }
        }
        return null;
    }

    synchronized <M> MatchedEntry<T, M> takeOldestMatched(TimestampMetadataMatcher<M> metadata) {
        Entry<T> oldest = frames.peekFirst();
        if (oldest == null) return null;
        M match = metadata.take(oldest.timestampNs);
        if (match == null) return null;
        frames.removeFirst();
        return new MatchedEntry<>(oldest.value, match);
    }

    private void discardFirst() {
        releaser.release(frames.removeFirst().value);
        dropped++;
    }

    synchronized void clear() {
        while (!frames.isEmpty()) releaser.release(frames.removeFirst().value);
        newestTimestampNs = Long.MIN_VALUE;
    }

    synchronized long dropped() { return dropped; }
}
