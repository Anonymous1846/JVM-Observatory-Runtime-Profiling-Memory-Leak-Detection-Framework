package com.jvmobservatory.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jvmobservatory.gc.GCRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/**
 * Kafka consumer that reads from all 3 telemetry topics and routes events
 * to the {@link MetricsAggregator} for in-memory storage.
 *
 * <p>Runs on a dedicated daemon thread so it doesn't prevent JVM shutdown.
 * Uses a short poll timeout (100ms) to stay responsive to interrupts.</p>
 */
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final KafkaConsumer<String, String> consumer;
    private final MetricsAggregator aggregator;
    private final ObjectMapper mapper;
    private volatile Thread consumerThread;

    /**
     * Creates a new telemetry consumer.
     *
     * @param bootstrapServers Kafka broker addresses
     * @param groupId          consumer group ID — multiple instances with the same groupId
     *                         will load-balance partitions among themselves
     * @param aggregator       the in-memory aggregator to route parsed events into
     */
    public TelemetryConsumer(String bootstrapServers, String groupId, MetricsAggregator aggregator) {
        this.aggregator = aggregator;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // ISSUE 9 FIX: Use "latest" so a new consumer group doesn't replay the entire topic
        // history on first start. For monitoring data, historical events are stale — we only
        // care about live data flowing in after the consumer starts.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        // Poll up to 500 records at a time — balances throughput with processing latency.
        // Too high and we might hold the poll loop for too long; too low and we waste
        // network round-trips.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(List.of("jvm.allocations", "jvm.gc", "jvm.alerts"));

        // Jackson ObjectMapper with JavaTimeModule for parsing ISO-8601 timestamps
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        log.info("[jvmobs] TelemetryConsumer initialized, group={}, brokers={}", groupId, bootstrapServers);
    }

    /**
     * Starts the consumer loop on a daemon thread.
     *
     * <p>The thread is marked as daemon so it won't prevent JVM shutdown if the
     * application exits. The poll loop runs with a 100ms timeout, which means the
     * thread checks for interrupts every 100ms — responsive enough for clean shutdown.</p>
     */
    public void start() {
        consumerThread = new Thread(() -> {
            log.info("[jvmobs] Telemetry consumer thread started");
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            route(record.topic(), record.key(), record.value());
                        } catch (Exception e) {
                            // Log and continue — one bad record shouldn't stop the consumer.
                            // Common causes: malformed JSON, unexpected schema changes.
                            log.error("[jvmobs] Error processing record from {}", record.topic(), e);
                        }
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                // Expected when stop() is called — clean shutdown signal
                log.info("[jvmobs] Consumer wakeup received, shutting down");
            } finally {
                consumer.close();
                log.info("[jvmobs] Telemetry consumer thread stopped");
            }
        }, "jvmobs-telemetry-consumer");

        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /**
     * Routes a consumed record to the appropriate aggregator method based on topic.
     */
    private void route(String topic, String appId, String json) throws JsonProcessingException {
        JsonNode node = mapper.readTree(json);

        switch (topic) {
            case "jvm.allocations" -> {
                String className = node.get("className").asText();
                long count = node.get("count").asLong();
                long totalBytes = node.get("totalBytes").asLong();
                aggregator.recordAllocation(appId, className, count, totalBytes);
            }

            case "jvm.gc" -> {
                // Reconstruct GCRecord from the JSON fields
                GCRecord gcRecord = new GCRecord(
                        node.get("gcName").asText(),
                        node.get("gcCause").asText(),
                        node.has("gcAction") ? node.get("gcAction").asText() : "end of minor GC",
                        node.get("durationMs").asLong(),
                        node.get("heapUsedBefore").asLong(),
                        node.get("heapUsedAfter").asLong(),
                        Instant.parse(node.get("timestamp").asText())
                );
                aggregator.recordGCEvent(appId, gcRecord);

                // Also record a heap sample from the post-GC state.
                // We estimate maxBytes from context if available, otherwise use a sentinel.
                long heapUsedAfter = node.get("heapUsedAfter").asLong();
                long maxHeap = node.has("maxHeap") ? node.get("maxHeap").asLong() : heapUsedAfter * 2;
                aggregator.recordHeapSample(appId, heapUsedAfter, maxHeap);
            }

            case "jvm.alerts" -> {
                AlertEvent alert = new AlertEvent(
                        appId,
                        Instant.parse(node.get("timestamp").asText()),
                        node.get("severity").asText(),
                        node.get("alertType").asText(),
                        node.has("className") ? node.get("className").asText() : "",
                        node.has("leakProbability") ? node.get("leakProbability").asDouble() : 0.0,
                        node.has("growthRatio") ? node.get("growthRatio").asDouble() : 0.0,
                        node.has("message") ? node.get("message").asText() : ""
                );
                aggregator.recordAlert(appId, alert);
            }

            default -> log.warn("[jvmobs] Unknown topic: {}", topic);
        }
    }

    /**
     * Signals the consumer to stop. Uses {@link KafkaConsumer#wakeup()} which is
     * the only thread-safe method on KafkaConsumer — it causes the next poll() call
     * to throw WakeupException, breaking out of the loop cleanly.
     */
    public void stop() {
        log.info("[jvmobs] Stopping telemetry consumer");
        consumer.wakeup();
    }
}
