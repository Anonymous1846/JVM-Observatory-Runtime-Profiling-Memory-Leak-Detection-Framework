package com.jvmobservatory.api.dto;

/**
 * A single heap usage data point for the time-series chart.
 *
 * <p>The {@code timestamp} is an ISO-8601 string (not {@link java.time.Instant})
 * because the React frontend parses it directly with {@code new Date(timestamp)}.
 * Sending a pre-formatted string avoids timezone confusion on the client side.</p>
 *
 * @param timestamp ISO-8601 formatted timestamp (e.g. "2024-01-15T10:30:00Z")
 * @param usedBytes heap bytes currently in use
 * @param maxBytes  maximum configured heap size
 */
public record HeapSampleDTO(
        String timestamp,
        long usedBytes,
        long maxBytes
) {}
