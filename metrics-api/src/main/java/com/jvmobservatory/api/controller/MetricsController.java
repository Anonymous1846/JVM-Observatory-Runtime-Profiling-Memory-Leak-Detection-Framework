package com.jvmobservatory.api.controller;

import com.jvmobservatory.api.dto.*;
import com.jvmobservatory.api.service.AllocationService;
import com.jvmobservatory.api.service.GCStatsService;
import com.jvmobservatory.api.service.LeakSuspectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller exposing JVM telemetry metrics for the React dashboard.
 *
 * <p>All endpoints are GET (read-only) and return JSON. The dashboard polls
 * these endpoints on a configurable interval (typically 2-5 seconds).</p>
 *
 * <p>{@code @CrossOrigin("*")} allows the React dev server (typically on port 3000)
 * to call this API (on port 8080) without CORS errors. In production, this should
 * be restricted to the actual dashboard origin.</p>
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin("*")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final AllocationService allocationService;
    private final GCStatsService gcStatsService;
    private final LeakSuspectService leakSuspectService;

    public MetricsController(AllocationService allocationService,
                             GCStatsService gcStatsService,
                             LeakSuspectService leakSuspectService) {
        this.allocationService = allocationService;
        this.gcStatsService = gcStatsService;
        this.leakSuspectService = leakSuspectService;
    }

    /**
     * Returns the top N allocating classes for the given application.
     *
     * @param appId the monitored JVM instance identifier (required)
     * @param topN  maximum number of classes to return (default: 20)
     * @return list of allocation DTOs sorted by count descending
     */
    @GetMapping("/allocations")
    public List<ClassAllocationDTO> getAllocations(
            @RequestParam String appId,
            @RequestParam(defaultValue = "20") int topN) {
        return allocationService.getTopAllocators(appId, topN);
    }

    /**
     * Returns current memory leak suspects for the given application.
     *
     * @param appId the monitored JVM instance identifier (required)
     * @return list of leak suspect DTOs sorted by probability descending
     */
    @GetMapping("/leak-suspects")
    public List<LeakSuspectDTO> getLeakSuspects(@RequestParam String appId) {
        return leakSuspectService.getSuspects(appId);
    }

    /**
     * Returns aggregated GC statistics for the given time window.
     *
     * @param appId the monitored JVM instance identifier (required)
     * @param hours number of hours to look back (default: 1)
     * @return GC stats including events, average pause, and total reclaimed bytes
     */
    @GetMapping("/gc-stats")
    public GCStatsDTO getGCStats(
            @RequestParam String appId,
            @RequestParam(defaultValue = "1") int hours) {
        return gcStatsService.getStats(appId, hours);
    }

    /**
     * Returns the heap usage timeline for the time-series chart.
     *
     * @param appId the monitored JVM instance identifier (required)
     * @return chronologically ordered list of heap samples
     */
    @GetMapping("/heap-trend")
    public List<HeapSampleDTO> getHeapTrend(@RequestParam String appId) {
        return gcStatsService.getHeapTrend(appId);
    }

    /**
     * Returns a D3-compatible flamegraph tree of allocation hotspots.
     *
     * <p>The tree is built by splitting class names into package segments.
     * The React dashboard renders this using D3's partition layout.</p>
     *
     * @param appId the monitored JVM instance identifier (required)
     * @return root node of the flamegraph hierarchy
     */
    @GetMapping("/allocation-flamegraph")
    public FlamegraphNode getAllocationFlamegraph(@RequestParam String appId) {
        return allocationService.buildFlamegraph(appId);
    }
}
