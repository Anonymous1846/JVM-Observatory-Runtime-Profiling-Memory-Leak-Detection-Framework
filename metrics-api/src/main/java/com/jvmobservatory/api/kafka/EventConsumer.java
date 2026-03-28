package com.jvmobservatory.api.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jvmobservatory.gc.GCRecord;
import com.jvmobservatory.telemetry.AlertEvent;
import com.jvmobservatory.telemetry.MetricsAggregator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Spring-managed Kafka event consumer that listens to all JVM telemetry topics.
 *
 * <p>Uses Spring Kafka's {@code @KafkaListener} for declarative topic subscription.
 * Each listener method parses the JSON payload and delegates to the
 * {@link MetricsAggregator} for in-memory storage.</p>
 *
 * <p>Why separate from {@link com.jvmobservatory.telemetry.TelemetryConsumer}?
 * TelemetryConsumer is a standalone Kafka consumer for non-Spring deployments.
 * This class leverages Spring's consumer group management, error handling, and
 * lifecycle management (auto-start/stop with the application context).</p>
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final MetricsAggregator aggregator;
    private final ObjectMapper mapper;

    public EventConsumer(MetricsAggregator aggregator) {
        this.aggregator = aggregator;

        // ISSUE 6 FIX: Register JavaTimeModule so Jackson can parse ISO-8601 timestamps
        // back into java.time.Instant when deserializing event payloads.
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Consumes allocation events from the "jvm.allocations" topic.
     *
     * <p>Expected JSON schema:
     * {@code {"eventType":"ALLOCATION","appId":"...","className":"...","count":N,"totalBytes":N,...}}</p>
     */
    @KafkaListener(topics = "jvm.allocations", groupId = "${spring.kafka.consumer.group-id}")
    public void onAllocation(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = mapper.readTree(record.value());
            String className = node.get("className").asText();
            long count = node.get("count").asLong();
            long totalBytes = node.get("totalBytes").asLong();

            aggregator.recordAllocation(record.key(), className, count, totalBytes);
        } catch (Exception e) {
            log.error("[jvmobs] Failed to process allocation event: {}", record.value(), e);
        }
    }

    /**
     * Consumes GC events from the "jvm.gc" topic.
     *
     * <p>Also extracts heap usage data to feed the heap trend timeline.
     * We record a heap sample from every GC event because GC events provide
     * the most accurate heap usage snapshots (measured after collection).</p>
     */
    @KafkaListener(topics = "jvm.gc", groupId = "${spring.kafka.consumer.group-id}")
    public void onGCEvent(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = mapper.readTree(record.value());

            GCRecord gcRecord = new GCRecord(
                    node.get("gcName").asText(),
                    node.get("gcCause").asText(),
                    node.has("gcAction") ? node.get("gcAction").asText() : "end of minor GC",
                    node.get("durationMs").asLong(),
                    node.get("heapUsedBefore").asLong(),
                    node.get("heapUsedAfter").asLong(),
                    Instant.parse(node.get("timestamp").asText())
            );
            aggregator.recordGCEvent(record.key(), gcRecord);

            // Record a heap sample from the post-GC state for the heap trend chart.
            // Estimate maxBytes from the JSON if available, otherwise use a heuristic.
            long heapUsedAfter = node.get("heapUsedAfter").asLong();
            long maxBytes = node.has("maxHeap") ? node.get("maxHeap").asLong() : heapUsedAfter * 2;
            aggregator.recordHeapSample(record.key(), heapUsedAfter, maxBytes);

        } catch (Exception e) {
            log.error("[jvmobs] Failed to process GC event: {}", record.value(), e);
        }
    }

    /**
     * Consumes alert events from the "jvm.alerts" topic.
     */
    @KafkaListener(topics = "jvm.alerts", groupId = "${spring.kafka.consumer.group-id}")
    public void onAlert(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = mapper.readTree(record.value());

            AlertEvent alert = new AlertEvent(
                    record.key(),
                    Instant.parse(node.get("timestamp").asText()),
                    node.get("severity").asText(),
                    node.get("alertType").asText(),
                    node.has("className") ? node.get("className").asText() : "",
                    node.has("leakProbability") ? node.get("leakProbability").asDouble() : 0.0,
                    node.has("growthRatio") ? node.get("growthRatio").asDouble() : 0.0,
                    node.has("message") ? node.get("message").asText() : ""
            );
            aggregator.recordAlert(record.key(), alert);

        } catch (Exception e) {
            log.error("[jvmobs] Failed to process alert event: {}", record.value(), e);
        }
    }
}
