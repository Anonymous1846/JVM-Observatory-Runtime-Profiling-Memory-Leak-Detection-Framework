package com.jvmobservatory.api.dto;

/**
 * Data transfer object for a single class's allocation statistics.
 *
 * <p>Used by the REST API to return top-allocating classes. The {@code percentage}
 * field shows this class's share of total allocations, making it easy for the
 * dashboard to render proportional bar charts.</p>
 *
 * @param className  fully-qualified Java class name
 * @param count      total number of allocations observed
 * @param totalBytes cumulative bytes allocated
 * @param percentage this class's share of total allocation count (0.0–100.0)
 */
public record ClassAllocationDTO(
        String className,
        long count,
        long totalBytes,
        double percentage
) {}
