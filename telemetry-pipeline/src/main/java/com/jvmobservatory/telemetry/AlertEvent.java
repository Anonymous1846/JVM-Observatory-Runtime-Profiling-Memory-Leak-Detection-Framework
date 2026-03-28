package com.jvmobservatory.telemetry;

import java.time.Instant;

/**
 * Represents an alert raised by the heap analyzer or GC monitor.
 *
 * <p>Alert types:</p>
 * <ul>
 *   <li>{@code LEAK_SUSPECT} — a class shows statistically significant monotonic growth</li>
 *   <li>{@code OOM_RISK} — heap usage is approaching the configured maximum</li>
 *   <li>{@code GC_ANOMALY} — GC pause times or frequency exceed thresholds</li>
 * </ul>
 *
 * @param appId            monitored JVM instance identifier
 * @param timestamp        when the alert was generated (UTC)
 * @param severity         "HIGH", "MEDIUM", or "LOW"
 * @param alertType        one of "LEAK_SUSPECT", "OOM_RISK", "GC_ANOMALY"
 * @param className        the class involved (may be empty for GC_ANOMALY)
 * @param leakProbability  0.0–1.0 confidence that a leak exists (meaningful for LEAK_SUSPECT)
 * @param growthRatio      ratio of current instance count to baseline (>1.0 means growth)
 * @param message          human-readable description of the alert
 */
public record AlertEvent(
        String appId,
        Instant timestamp,
        String severity,
        String alertType,
        String className,
        double leakProbability,
        double growthRatio,
        String message
) {}
