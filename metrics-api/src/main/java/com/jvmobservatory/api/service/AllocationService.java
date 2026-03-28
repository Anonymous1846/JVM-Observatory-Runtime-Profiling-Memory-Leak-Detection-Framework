package com.jvmobservatory.api.service;

import com.jvmobservatory.api.dto.ClassAllocationDTO;
import com.jvmobservatory.api.dto.FlamegraphNode;
import com.jvmobservatory.telemetry.MetricsAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for allocation-related metrics.
 *
 * <p>Reads raw allocation data from the {@link MetricsAggregator} and transforms
 * it into DTOs suitable for the REST API and React dashboard.</p>
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    private final MetricsAggregator aggregator;

    public AllocationService(MetricsAggregator aggregator) {
        this.aggregator = aggregator;
    }

    /**
     * Returns the top N allocating classes for the given app.
     *
     * <p>Computes each class's percentage of total allocations so the dashboard
     * can render proportional bar charts without a second API call.</p>
     *
     * @param appId the monitored JVM instance identifier
     * @param topN  maximum number of classes to return
     * @return list of allocation DTOs sorted by count descending
     */
    public List<ClassAllocationDTO> getTopAllocators(String appId, int topN) {
        var allocations = aggregator.getTopAllocators(appId, topN);

        // Compute total count across all returned classes for percentage calculation
        long totalCount = allocations.stream()
                .mapToLong(MetricsAggregator.AllocationData::count)
                .sum();

        return allocations.stream()
                .map(a -> new ClassAllocationDTO(
                        a.className(),
                        a.count(),
                        a.totalBytes(),
                        totalCount > 0 ? (a.count() * 100.0) / totalCount : 0.0
                ))
                .collect(Collectors.toList());
    }

    /**
     * Builds a D3-compatible flamegraph tree from allocation data.
     *
     * <p>Splits fully-qualified class names by '.' to create a hierarchical tree:
     * {@code "com.company.service.MyClass"} becomes
     * {@code root → com → company → service → MyClass}.
     * Each leaf node's value is the allocation count for that class.</p>
     *
     * <p>This tree structure is directly consumable by D3's hierarchy/partition
     * layout, which the React dashboard uses for the flamegraph visualization.</p>
     *
     * @param appId the monitored JVM instance identifier
     * @return root node of the flamegraph tree
     */
    public FlamegraphNode buildFlamegraph(String appId) {
        var allocations = aggregator.getTopAllocators(appId, 100);

        // Root node represents all allocations
        long totalCount = allocations.stream()
                .mapToLong(MetricsAggregator.AllocationData::count)
                .sum();
        FlamegraphNode root = new FlamegraphNode("all", totalCount);

        for (var alloc : allocations) {
            // Split "com.company.service.MyClass" into ["com", "company", "service", "MyClass"]
            String[] parts = alloc.className().split("\\.");
            FlamegraphNode current = root;

            for (String part : parts) {
                // Find existing child with this name, or create a new one
                FlamegraphNode child = current.getChildren().stream()
                        .filter(c -> c.getName().equals(part))
                        .findFirst()
                        .orElse(null);

                if (child == null) {
                    child = new FlamegraphNode(part, 0);
                    current.addChild(child);
                }

                current = child;
            }

            // The leaf node gets the allocation count as its value
            current.setValue(alloc.count());
        }

        return root;
    }
}
