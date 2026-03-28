package com.jvmobservatory.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated GC statistics for a time window.
 *
 * <p>Contains individual GC events plus computed summary metrics. The dashboard
 * uses {@code avgPauseMs} for the headline metric and {@code events} for the
 * detailed GC event timeline chart.</p>
 *
 * @param events              individual GC events within the time window
 * @param avgPauseMs          average GC pause duration in milliseconds
 * @param totalReclaimedBytes total heap bytes reclaimed across all GC events
 */
public record GCStatsDTO(
        List<GCEventDTO> events,
        double avgPauseMs,
        long totalReclaimedBytes
) {

    /**
     * A single GC event for the timeline view.
     *
     * @param gcName          name of the garbage collector (e.g. "G1 Young Generation")
     * @param gcCause         what triggered the GC (e.g. "G1 Evacuation Pause")
     * @param durationMs      pause duration in milliseconds
     * @param heapUsedBefore  heap bytes used before collection
     * @param heapUsedAfter   heap bytes used after collection
     * @param timestamp       when the GC event occurred
     */
    public record GCEventDTO(
            String gcName,
            String gcCause,
            long durationMs,
            long heapUsedBefore,
            long heapUsedAfter,
            Instant timestamp
    ) {}
}
