package com.jvmobservatory.heap;

/**
 * Immutable snapshot of a potential memory leak suspect.
 *
 * <p><b>WHY a record:</b> LeakSuspect is a pure data carrier returned by {@link LeakAnalyzer}.
 * It captures a point-in-time ranking of objects by retained size. Records give us
 * immutability and automatic {@code equals()}/{@code hashCode()}/{@code toString()}.</p>
 *
 * <p><b>WHY retainedPercentage is included:</b> The absolute retained size in bytes is
 * hard to interpret without context — "50MB retained" means different things on a 512MB
 * heap vs. a 16GB heap. The percentage immediately conveys severity: "this one object
 * is responsible for 40% of the heap" is an obvious leak.</p>
 *
 * @param objectId          unique ID of the suspect object in the heap dump
 * @param className         fully qualified class name (e.g., "java.util.HashMap")
 * @param retainedBytes     total bytes retained by this object (its subtree in the dominator tree)
 * @param retainedPercentage percentage of the total heap retained by this object (0.0–100.0)
 */
public record LeakSuspect(
        long objectId,
        String className,
        long retainedBytes,
        double retainedPercentage
) {
    @Override
    public String toString() {
        return String.format("LeakSuspect{id=%d, class=%s, retained=%,d bytes (%.2f%%)}",
                objectId, className, retainedBytes, retainedPercentage);
    }
}
