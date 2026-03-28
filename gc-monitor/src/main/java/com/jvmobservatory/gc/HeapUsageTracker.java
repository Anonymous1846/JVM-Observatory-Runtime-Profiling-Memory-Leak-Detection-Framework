package com.jvmobservatory.gc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Maintains a sliding window of {@link HeapSample} records.
 *
 * WHY a sliding window instead of unbounded history:
 * At 1 sample/second, unbounded storage would accumulate 86,400 samples/day.
 * For leak detection, we only need the last hour (3,600 samples) — that's enough
 * to identify a monotonically increasing heap trend. Older data is available in
 * Kafka for long-term analysis.
 *
 * WHY ArrayDeque over LinkedList:
 * ArrayDeque is backed by a resizable array — better cache locality, lower memory
 * overhead (no Node objects), and O(1) amortized for addLast/pollFirst. LinkedList
 * allocates a Node object per element, which is exactly the kind of allocation
 * pressure we're trying to monitor, not create.
 */
public class HeapUsageTracker {

    /**
     * Maximum number of samples to retain.
     * 3600 samples = 1 hour at 1 sample/second.
     */
    static final int MAX_SAMPLES = 3600;

    private final Deque<HeapSample> samples = new ArrayDeque<>();

    /**
     * Record a new heap sample. If the window is full, the oldest sample is evicted.
     *
     * @param sample the heap usage snapshot to record
     */
    public synchronized void record(HeapSample sample) {
        samples.addLast(sample);
        if (samples.size() > MAX_SAMPLES) {
            samples.pollFirst();
        }
    }

    /**
     * Return the most recent N samples, ordered oldest-first.
     *
     * @param count maximum number of samples to return
     * @return up to {@code count} most recent samples (may be fewer if not enough data)
     */
    public synchronized List<HeapSample> getRecent(int count) {
        List<HeapSample> all = new ArrayList<>(samples);
        int size = all.size();
        if (count >= size) {
            return Collections.unmodifiableList(all);
        }
        return Collections.unmodifiableList(all.subList(size - count, size));
    }

    /**
     * Return all samples currently in the sliding window as an unmodifiable list.
     *
     * @return all retained samples, ordered oldest-first
     */
    public synchronized List<HeapSample> getSamples() {
        return Collections.unmodifiableList(new ArrayList<>(samples));
    }
}
