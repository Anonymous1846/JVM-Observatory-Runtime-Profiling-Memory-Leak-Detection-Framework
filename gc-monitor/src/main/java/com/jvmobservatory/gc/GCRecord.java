package com.jvmobservatory.gc;

import java.time.Instant;

/**
 * Immutable snapshot of a single garbage collection event.
 *
 * WHY a record: GC events are pure data — no behavior, no mutation.
 * Java records give us equals/hashCode/toString for free and clearly
 * communicate the "data carrier" intent to readers.
 *
 * @param gcName         Name of the GC collector (e.g., "G1 Young Generation", "ZGC")
 * @param gcCause        What triggered the GC (e.g., "Allocation Failure", "System.gc()")
 * @param gcAction        GC action type (e.g., "end of minor GC", "end of major GC")
 * @param durationMs     How long the GC pause lasted in milliseconds
 * @param heapUsedBefore Total heap used across all memory pools BEFORE GC (bytes)
 * @param heapUsedAfter  Total heap used across all memory pools AFTER GC (bytes)
 * @param timestamp      When this GC event occurred
 */
public record GCRecord(
        String gcName,
        String gcCause,
        String gcAction,
        long durationMs,
        long heapUsedBefore,
        long heapUsedAfter,
        Instant timestamp
) {
    /**
     * Convenience: how many bytes were reclaimed by this GC cycle.
     * A negative value means heap grew (e.g., promotion from young to old gen).
     */
    public long bytesReclaimed() {
        return heapUsedBefore - heapUsedAfter;
    }
}
