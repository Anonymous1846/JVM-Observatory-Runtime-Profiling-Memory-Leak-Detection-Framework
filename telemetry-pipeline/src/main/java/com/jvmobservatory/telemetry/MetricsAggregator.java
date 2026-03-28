package com.jvmobservatory.telemetry;

import com.jvmobservatory.gc.GCRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * In-memory aggregation of metrics data consumed from Kafka.
 *
 * <p>This class acts as the central data store for the metrics API. All data is kept in memory
 * using concurrent collections because:</p>
 * <ul>
 *   <li>Monitoring data is ephemeral — we only need recent history, not persistence</li>
 *   <li>ConcurrentHashMap provides thread-safe reads without locking for the API layer</li>
 *   <li>ConcurrentLinkedDeque allows O(1) append and efficient trimming from the head</li>
 *   <li>Bounded collections (MAX_*) prevent unbounded memory growth</li>
 * </ul>
 */
public class MetricsAggregator {

    private static final Logger log = LoggerFactory.getLogger(MetricsAggregator.class);

    // WHY these limits: balance between useful history and memory consumption.
    // 1000 GC events ≈ several hours of GC activity (typical apps do 1-10 GCs/min).
    // 100 alerts keeps the dashboard focused on actionable items.
    // 3600 heap samples = 1 hour at 1 sample/second, enough for trend analysis.
    static final int MAX_GC_EVENTS = 1000;
    static final int MAX_ALERTS = 100;
    static final int MAX_HEAP_SAMPLES = 3600;
    private static final int MAX_TOP_ALLOCATORS = 100;

    /**
     * Per-app allocation tracking: className -> aggregated counts.
     * We store raw allocation data and let the API module format DTOs.
     */
    private final Map<String, Map<String, AllocationData>> topAllocators = new ConcurrentHashMap<>();

    /** Per-app recent GC events, bounded to MAX_GC_EVENTS. */
    private final Map<String, ConcurrentLinkedDeque<GCRecord>> recentGCEvents = new ConcurrentHashMap<>();

    /** Per-app active alerts, bounded to MAX_ALERTS. */
    private final Map<String, ConcurrentLinkedDeque<AlertEvent>> activeAlerts = new ConcurrentHashMap<>();

    /** Per-app heap usage timeline, bounded to MAX_HEAP_SAMPLES. */
    private final Map<String, ConcurrentLinkedDeque<HeapSampleData>> heapTimeline = new ConcurrentHashMap<>();

    /**
     * Simple data holder for aggregated allocation counts per class.
     */
    public record AllocationData(String className, long count, long totalBytes) {}

    /**
     * A single heap usage data point for time-series charting.
     *
     * @param timestamp when the sample was taken
     * @param usedBytes heap bytes in use at that moment
     * @param maxBytes  maximum heap size configured for the JVM
     */
    public record HeapSampleData(Instant timestamp, long usedBytes, long maxBytes) {}

    /**
     * Records an allocation event, updating the running totals for the given class.
     *
     * <p>If the number of tracked classes exceeds {@link #MAX_TOP_ALLOCATORS}, the class
     * with the lowest allocation count is evicted. This ensures we always track the most
     * significant allocators without unbounded memory growth.</p>
     *
     * @param appId      monitored JVM instance identifier
     * @param className  fully-qualified class name
     * @param count      number of allocations in this sample
     * @param totalBytes total bytes allocated in this sample
     */
    public void recordAllocation(String appId, String className, long count, long totalBytes) {
        Map<String, AllocationData> classMap = topAllocators.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());

        classMap.merge(className,
                new AllocationData(className, count, totalBytes),
                (existing, incoming) -> new AllocationData(
                        className,
                        existing.count() + incoming.count(),
                        existing.totalBytes() + incoming.totalBytes()
                ));

        // Trim to top MAX_TOP_ALLOCATORS by count if we've exceeded the limit
        if (classMap.size() > MAX_TOP_ALLOCATORS) {
            // Find the class with the lowest count and remove it
            classMap.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().count()))
                    .ifPresent(min -> classMap.remove(min.getKey()));
        }
    }

    /**
     * Records a GC event, appending to the per-app deque and trimming old entries.
     */
    public void recordGCEvent(String appId, GCRecord record) {
        ConcurrentLinkedDeque<GCRecord> deque = recentGCEvents.computeIfAbsent(appId, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(record);

        // Trim from the head (oldest events) to stay within bounds
        while (deque.size() > MAX_GC_EVENTS) {
            deque.pollFirst();
        }
    }

    /**
     * Records an alert, appending to the per-app deque and trimming old entries.
     */
    public void recordAlert(String appId, AlertEvent alert) {
        ConcurrentLinkedDeque<AlertEvent> deque = activeAlerts.computeIfAbsent(appId, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(alert);

        // Trim from the head (oldest alerts) to stay within bounds
        while (deque.size() > MAX_ALERTS) {
            deque.pollFirst();
        }
    }

    /**
     * Records a heap usage sample for time-series display.
     */
    public void recordHeapSample(String appId, long usedBytes, long maxBytes) {
        ConcurrentLinkedDeque<HeapSampleData> deque = heapTimeline.computeIfAbsent(appId, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new HeapSampleData(Instant.now(), usedBytes, maxBytes));

        // Trim from the head (oldest samples) to stay within bounds
        while (deque.size() > MAX_HEAP_SAMPLES) {
            deque.pollFirst();
        }
    }

    /**
     * Returns the top N allocating classes for the given app, sorted by allocation count descending.
     *
     * @param appId the monitored JVM instance
     * @param n     maximum number of results to return
     * @return list of allocation data, sorted by count descending
     */
    public List<AllocationData> getTopAllocators(String appId, int n) {
        Map<String, AllocationData> classMap = topAllocators.get(appId);
        if (classMap == null) {
            return Collections.emptyList();
        }

        return classMap.values().stream()
                .sorted(Comparator.comparingLong(AllocationData::count).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Returns recent GC events for the given app, in chronological order.
     */
    public List<GCRecord> getRecentGCEvents(String appId) {
        ConcurrentLinkedDeque<GCRecord> deque = recentGCEvents.get(appId);
        if (deque == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(deque);
    }

    /**
     * Returns active alerts for the given app, in chronological order.
     */
    public List<AlertEvent> getActiveAlerts(String appId) {
        ConcurrentLinkedDeque<AlertEvent> deque = activeAlerts.get(appId);
        if (deque == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(deque);
    }

    /**
     * Returns the heap usage timeline for the given app, in chronological order.
     */
    public List<HeapSampleData> getHeapTimeline(String appId) {
        ConcurrentLinkedDeque<HeapSampleData> deque = heapTimeline.get(appId);
        if (deque == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(deque);
    }
}
