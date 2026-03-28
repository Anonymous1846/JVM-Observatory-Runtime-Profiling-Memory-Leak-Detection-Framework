package com.jvmobservatory.api.service;

import com.jvmobservatory.api.dto.GCStatsDTO;
import com.jvmobservatory.api.dto.GCStatsDTO.GCEventDTO;
import com.jvmobservatory.api.dto.HeapSampleDTO;
import com.jvmobservatory.gc.GCRecord;
import com.jvmobservatory.telemetry.MetricsAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for GC and heap-related metrics.
 *
 * <p>Reads raw GC events and heap samples from the {@link MetricsAggregator},
 * applies time-window filtering, and computes summary statistics.</p>
 */
@Service
public class GCStatsService {

    private static final Logger log = LoggerFactory.getLogger(GCStatsService.class);

    private final MetricsAggregator aggregator;

    public GCStatsService(MetricsAggregator aggregator) {
        this.aggregator = aggregator;
    }

    /**
     * Returns aggregated GC statistics for the given time window.
     *
     * @param appId the monitored JVM instance identifier
     * @param hours number of hours to look back (e.g., 1 = last hour)
     * @return GC stats including individual events, average pause, and total reclaimed bytes
     */
    public GCStatsDTO getStats(String appId, int hours) {
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);

        List<GCRecord> allEvents = aggregator.getRecentGCEvents(appId);

        // Filter to events within the requested time window
        List<GCRecord> filtered = allEvents.stream()
                .filter(r -> r.timestamp().isAfter(cutoff))
                .collect(Collectors.toList());

        // Convert to DTOs for the REST response
        List<GCEventDTO> eventDTOs = filtered.stream()
                .map(r -> new GCEventDTO(
                        r.gcName(),
                        r.gcCause(),
                        r.durationMs(),
                        r.heapUsedBefore(),
                        r.heapUsedAfter(),
                        r.timestamp()
                ))
                .collect(Collectors.toList());

        // Compute average pause duration across all GC events in the window.
        // This is the headline metric shown on the dashboard.
        double avgPauseMs = filtered.stream()
                .mapToLong(GCRecord::durationMs)
                .average()
                .orElse(0.0);

        // Compute total bytes reclaimed = sum of (heapUsedBefore - heapUsedAfter).
        // Negative values (heap growth during GC) are clamped to 0.
        long totalReclaimedBytes = filtered.stream()
                .mapToLong(r -> Math.max(0, r.heapUsedBefore() - r.heapUsedAfter()))
                .sum();

        return new GCStatsDTO(eventDTOs, avgPauseMs, totalReclaimedBytes);
    }

    /**
     * Returns the heap usage timeline for the time-series chart.
     *
     * <p>Converts each {@link MetricsAggregator.HeapSampleData} into a
     * {@link HeapSampleDTO} with an ISO-8601 timestamp string for direct
     * consumption by the React frontend.</p>
     *
     * @param appId the monitored JVM instance identifier
     * @return chronologically ordered list of heap samples
     */
    public List<HeapSampleDTO> getHeapTrend(String appId) {
        return aggregator.getHeapTimeline(appId).stream()
                .map(sample -> new HeapSampleDTO(
                        sample.timestamp().toString(),
                        sample.usedBytes(),
                        sample.maxBytes()
                ))
                .collect(Collectors.toList());
    }
}
