package com.jvmobservatory.agent;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1-in-N sampling to keep allocation tracking overhead low.
 *
 * Not every constructor invocation needs to be fully tracked. In a typical Java
 * application, millions of objects are created per second (HashMap entries, iterators,
 * lambda captures, etc.). Tracking every single one would add unacceptable overhead.
 *
 * With 1-in-100 sampling (the default), we capture a statistically representative
 * picture of allocation patterns while adding < 1% overhead. The sample rate can
 * be adjusted at runtime via JMX or configuration for debugging specific issues.
 *
 * WHY sampling instead of filtering by class:
 * We don't know which classes are "interesting" ahead of time. A memory leak could
 * come from any class. Uniform random sampling ensures we catch leaks regardless
 * of which class is leaking.
 */
public class AllocationSampler {

    /**
     * WHY AtomicInteger:
     * The sample rate can be changed at runtime (e.g., via a management API) while
     * shouldSample() is being called from multiple application threads. AtomicInteger
     * gives us safe reads and writes without locking.
     */
    private final AtomicInteger sampleRate = new AtomicInteger(100);

    /**
     * Decide whether the current allocation should be tracked.
     *
     * WHY ThreadLocalRandom:
     * java.util.Random uses a single CAS (compare-and-swap) internally, which
     * contends at high thread counts — exactly the scenario we're in, since
     * shouldSample() is called from every application thread hitting an instrumented
     * constructor. ThreadLocalRandom maintains a separate random state per thread,
     * eliminating all contention. Zero shared state = zero synchronization overhead.
     *
     * @return true if this allocation should be sampled (1 in N chance)
     */
    public boolean shouldSample() {
        return ThreadLocalRandom.current().nextInt(sampleRate.get()) == 0;
    }

    /**
     * @return the current sample rate (1 in N allocations are tracked)
     */
    public int getSampleRate() {
        return sampleRate.get();
    }

    /**
     * Change the sample rate at runtime.
     *
     * Lower values = more samples = higher overhead but more detail.
     * Higher values = fewer samples = lower overhead but less detail.
     *
     * Typical values:
     *   - 1: track every allocation (debugging only, high overhead)
     *   - 100: default, good balance for production
     *   - 1000: ultra-low overhead for high-throughput systems
     *
     * @param rate the new sample rate (must be >= 1)
     * @throws IllegalArgumentException if rate < 1
     */
    public void setSampleRate(int rate) {
        if (rate < 1) {
            throw new IllegalArgumentException("Sample rate must be >= 1, got: " + rate);
        }
        sampleRate.set(rate);
    }
}
