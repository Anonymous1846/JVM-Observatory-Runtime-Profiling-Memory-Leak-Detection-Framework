package com.jvmobservatory.api.service;

import com.jvmobservatory.api.dto.LeakSuspectDTO;
import com.jvmobservatory.telemetry.AlertEvent;
import com.jvmobservatory.telemetry.MetricsAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for memory leak detection results.
 *
 * <p>Filters the active alerts for LEAK_SUSPECT type and maps them to DTOs
 * for the dashboard's leak suspects table. Only alerts with type "LEAK_SUSPECT"
 * are relevant here — OOM_RISK and GC_ANOMALY alerts are handled elsewhere.</p>
 */
@Service
public class LeakSuspectService {

    private static final Logger log = LoggerFactory.getLogger(LeakSuspectService.class);

    private final MetricsAggregator aggregator;

    public LeakSuspectService(MetricsAggregator aggregator) {
        this.aggregator = aggregator;
    }

    /**
     * Returns all current leak suspects for the given application.
     *
     * @param appId the monitored JVM instance identifier
     * @return list of leak suspect DTOs, sorted by leak probability descending
     */
    public List<LeakSuspectDTO> getSuspects(String appId) {
        List<AlertEvent> alerts = aggregator.getActiveAlerts(appId);

        return alerts.stream()
                // Only include LEAK_SUSPECT alerts — other alert types (OOM_RISK, GC_ANOMALY)
                // have different semantics and are displayed in separate dashboard panels.
                .filter(alert -> "LEAK_SUSPECT".equals(alert.alertType()))
                .map(alert -> new LeakSuspectDTO(
                        alert.className(),
                        alert.leakProbability(),
                        alert.growthRatio(),
                        alert.severity(),
                        alert.message()
                ))
                // Sort by probability descending so the most likely leaks appear first
                .sorted((a, b) -> Double.compare(b.leakProbability(), a.leakProbability()))
                .collect(Collectors.toList());
    }
}
