package com.jvmobservatory.tracker;

/**
 * Immutable snapshot of allocation statistics for a single class.
 *
 * <p><b>WHY a record:</b> ClassStats is a pure data carrier — it holds a point-in-time
 * snapshot of allocation metrics for display in the metrics API or telemetry pipeline.
 * Records give us immutability, {@code equals()}, {@code hashCode()}, and {@code toString()}
 * for free, with no boilerplate.</p>
 *
 * <p><b>WHY percentage is pre-computed:</b> The total allocation count changes between
 * queries, so each snapshot captures the percentage at the time of creation. This avoids
 * confusing situations where percentages don't sum to 100% because the denominator changed.</p>
 *
 * @param className  fully qualified class name (e.g., "java.util.ArrayList")
 * @param count      total number of allocations of this class
 * @param totalBytes total bytes allocated (from native JVMTI tracking; 0 if only ByteBuddy-tracked)
 * @param percentage this class's share of total allocations (0.0–100.0)
 */
public record ClassStats(
        String className,
        long count,
        long totalBytes,
        double percentage
) {
    @Override
    public String toString() {
        return String.format("%s: %,d allocations (%,.0f bytes, %.2f%%)",
                className, count, (double) totalBytes, percentage);
    }
}
