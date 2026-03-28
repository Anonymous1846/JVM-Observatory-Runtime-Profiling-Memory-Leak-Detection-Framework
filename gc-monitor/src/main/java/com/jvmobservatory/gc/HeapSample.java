package com.jvmobservatory.gc;

import java.time.Instant;

/**
 * A point-in-time snapshot of JVM heap usage.
 *
 * Recorded once per second by the heap sampler thread in {@link GCEventMonitor}.
 * These samples build a time series that the analysis service uses to detect
 * memory leak trends (monotonically increasing heap after full GC = likely leak).
 *
 * @param timestamp When this sample was taken
 * @param usedBytes Current heap usage in bytes
 * @param maxBytes  Maximum heap size configured for the JVM (-Xmx)
 */
public record HeapSample(
        Instant timestamp,
        long usedBytes,
        long maxBytes
) {
    /**
     * Heap utilization as a ratio between 0.0 and 1.0.
     * Values approaching 1.0 indicate the JVM is running out of heap space.
     */
    public double utilizationRatio() {
        if (maxBytes <= 0) return 0.0;
        return (double) usedBytes / maxBytes;
    }
}
