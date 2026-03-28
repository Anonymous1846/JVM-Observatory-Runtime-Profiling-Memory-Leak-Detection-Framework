package com.jvmobservatory.tracker;

import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks allocation statistics for a specific call site (class + method + line number).
 *
 * <p>A "call site" is the exact location in application code where an object was allocated.
 * By aggregating counts and timing per call site, we can answer questions like:</p>
 * <ul>
 *   <li>"Which line of code allocates the most objects?" (count)</li>
 *   <li>"Which allocations are the slowest?" (avgNs — indicates expensive constructors)</li>
 * </ul>
 *
 * <p><b>WHY LongAdder for both count and totalNs:</b> Multiple threads may record
 * allocations at the same call site concurrently (e.g., a HashMap.put() call in a
 * shared service). LongAdder's striped counters avoid CAS contention, keeping the
 * recording overhead under 10ns per call even at 8+ threads.</p>
 *
 * <p><b>WHY mutable class (not a record):</b> Records are immutable, but we need to
 * atomically accumulate counts and durations over time. LongAdder is inherently mutable.</p>
 */
public class AllocationSite {

    private final String className;
    private final String site;

    // WHY separate count and totalNs (not a single "average" field):
    // Computing a running average atomically requires either a lock or a CAS loop
    // on two values simultaneously. Keeping them separate and dividing at read time
    // is both simpler and lock-free.
    private final LongAdder count = new LongAdder();
    private final LongAdder totalNs = new LongAdder();

    public AllocationSite(String className, String site) {
        this.className = className;
        this.site = site;
    }

    /**
     * Record a single allocation at this site with the given constructor duration.
     *
     * @param ns nanoseconds spent in the constructor (0 if timing is unavailable)
     */
    public void record(long ns) {
        count.increment();
        totalNs.add(ns);
    }

    /**
     * Total number of allocations recorded at this site.
     */
    public long totalCount() {
        return count.sum();
    }

    /**
     * Average constructor duration in nanoseconds.
     *
     * <p>WHY this is approximate: count and totalNs are read non-atomically, so a
     * concurrent recording between the two reads could slightly skew the result.
     * This is acceptable for monitoring — we trade perfect accuracy for zero locking.</p>
     */
    public long avgNs() {
        long c = count.sum();
        return c > 0 ? totalNs.sum() / c : 0;
    }

    public String getClassName() {
        return className;
    }

    public String getSite() {
        return site;
    }

    @Override
    public String toString() {
        return className + "::" + site + " [count=" + totalCount() + ", avgNs=" + avgNs() + "]";
    }
}
