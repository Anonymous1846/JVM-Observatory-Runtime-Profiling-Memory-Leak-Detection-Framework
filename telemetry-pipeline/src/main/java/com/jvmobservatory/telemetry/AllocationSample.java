package com.jvmobservatory.telemetry;

/**
 * Immutable snapshot of an allocation sampling event captured by the JVMTI agent.
 *
 * <p>Each sample represents a batch of allocations for a single class on a single thread,
 * collected at the configured sample rate. The {@code sampleRate} field allows the consumer
 * to extrapolate real allocation volume: actual ≈ count × sampleRate.</p>
 *
 * @param appId      unique identifier of the monitored JVM instance
 * @param className  fully-qualified class name being allocated (e.g. "java.lang.String")
 * @param count      number of allocations observed in this sample window
 * @param totalBytes cumulative bytes allocated across all {@code count} allocations
 * @param threadName name of the thread that performed the allocations
 * @param sampleRate 1-in-N sampling ratio (e.g. 100 means every 100th allocation is recorded)
 */
public record AllocationSample(
        String appId,
        String className,
        long count,
        long totalBytes,
        String threadName,
        int sampleRate
) {}
