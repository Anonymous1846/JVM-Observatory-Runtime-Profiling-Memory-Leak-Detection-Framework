package com.jvmobservatory.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jvmobservatory.gc.GCRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Async Kafka producer that streams profiling events to Kafka topics.
 *
 * <p>Design decisions:</p>
 * <ul>
 *   <li><b>batch.size=64KB</b>: amortizes network overhead. Small events (~200 bytes each) are
 *       batched into one TCP packet, reducing per-message network cost by ~300x.</li>
 *   <li><b>compression.type=lz4</b>: fastest compression codec available in Kafka. Achieves ~3x
 *       compression ratio at negligible CPU cost. JSON telemetry events compress extremely well
 *       because of repeated field names and string patterns.</li>
 *   <li><b>linger.ms=5</b>: wait up to 5ms to accumulate more records into a batch before sending.
 *       Trades 5ms of latency for significantly higher throughput. For monitoring data, 5ms of
 *       added latency is imperceptible.</li>
 *   <li><b>acks=1</b>: leader acknowledgment only. For monitoring data, we can tolerate rare
 *       message loss (e.g., during leader failover) in exchange for lower latency. Using acks=all
 *       would add ~5-15ms per send waiting for ISR replication.</li>
 *   <li><b>Async send</b>: synchronous send blocks the caller thread until Kafka acknowledges.
 *       In our case, the caller is often the JVM's allocation tracking thread — blocking it would
 *       slow down the monitored application. Async send with a callback lets us log errors without
 *       impacting the profiled JVM.</li>
 * </ul>
 */
public class TelemetryProducer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProducer.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final String appId;

    /**
     * Creates a new telemetry producer targeting the given Kafka cluster.
     *
     * @param bootstrapServers comma-separated list of Kafka broker addresses (e.g. "broker1:9092,broker2:9092")
     * @param appId            unique identifier for this monitored JVM — used as Kafka message key
     *                         so all events from one app land on the same partition (preserving order)
     */
    public TelemetryProducer(String bootstrapServers, String appId) {
        this.appId = appId;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // WHY batch.size=64KB: amortizes network overhead. Small events (~200 bytes each)
        // are batched into one TCP packet, reducing per-message overhead dramatically.
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);

        // WHY linger.ms=5: wait up to 5ms to accumulate more records into a batch before
        // sending. Trades 5ms latency for higher throughput — acceptable for monitoring data.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // WHY lz4: fastest compression codec. ~3x compression ratio at negligible CPU cost.
        // JSON events with repeated field names compress extremely well.
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // 32MB total buffer memory — allows the producer to buffer events if Kafka is
        // temporarily slow, preventing back-pressure on the profiled application.
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);

        // WHY acks=1: leader acknowledgment only. For monitoring data, we can tolerate
        // rare message loss in exchange for lower latency.
        props.put(ProducerConfig.ACKS_CONFIG, "1");

        // Retry up to 3 times on transient failures (network blips, leader elections).
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        this.producer = new KafkaProducer<>(props);

        // ISSUE 6 FIX: Without JavaTimeModule, Jackson cannot serialize Instant/LocalDateTime.
        // It would throw "no serializer found for java.time.Instant" at runtime.
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        log.info("[jvmobs] TelemetryProducer initialized for appId={}, brokers={}", appId, bootstrapServers);
    }

    /**
     * Sends an allocation sample event to the "jvm.allocations" topic.
     */
    public void sendAllocation(AllocationSample sample) {
        String json = buildAllocationJson(sample);
        send("jvm.allocations", json);
    }

    /**
     * Sends a GC event to the "jvm.gc" topic.
     */
    public void sendGCEvent(GCRecord record) {
        String json = buildGCJson(record);
        send("jvm.gc", json);
    }

    /**
     * Sends an alert event to the "jvm.alerts" topic.
     */
    public void sendAlert(AlertEvent alert) {
        String json = buildAlertJson(alert);
        send("jvm.alerts", json);
    }

    /**
     * Sends a JSON message to the given Kafka topic asynchronously.
     *
     * <p>WHY async: synchronous send blocks the caller thread until Kafka acknowledges.
     * In our case, the caller is the JVM's allocation tracking thread — blocking it would
     * slow down the monitored application. We use an async callback to log failures without
     * impacting the profiled JVM.</p>
     */
    private void send(String topic, String json) {
        // Use appId as the message key so all events from one application land on the same
        // Kafka partition, preserving event ordering per app.
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, appId, json);

        // ISSUE 5 FIX: Always use async send with callback — never block the caller thread.
        producer.send(record, (metadata, ex) -> {
            if (ex != null) {
                log.error("[jvmobs] Kafka send to {} failed", topic, ex);
            }
        });
    }

    /**
     * Builds JSON for an allocation sample event.
     */
    private String buildAllocationJson(AllocationSample sample) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "ALLOCATION");
            event.put("appId", sample.appId());
            event.put("timestamp", Instant.now().toString());
            event.put("className", sample.className());
            event.put("count", sample.count());
            event.put("totalBytes", sample.totalBytes());
            event.put("threadName", sample.threadName());
            event.put("sampleRate", sample.sampleRate());
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[jvmobs] Failed to serialize allocation sample", e);
            return "{}";
        }
    }

    /**
     * Builds JSON for a GC event.
     */
    private String buildGCJson(GCRecord record) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "GC");
            event.put("appId", appId);
            event.put("timestamp", record.timestamp().toString());
            event.put("gcName", record.gcName());
            event.put("gcCause", record.gcCause());
            event.put("gcAction", record.gcAction());
            event.put("durationMs", record.durationMs());
            event.put("heapUsedBefore", record.heapUsedBefore());
            event.put("heapUsedAfter", record.heapUsedAfter());
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[jvmobs] Failed to serialize GC event", e);
            return "{}";
        }
    }

    /**
     * Builds JSON for an alert event.
     */
    private String buildAlertJson(AlertEvent alert) {
        try {
            return mapper.writeValueAsString(alert);
        } catch (JsonProcessingException e) {
            log.error("[jvmobs] Failed to serialize alert event", e);
            return "{}";
        }
    }

    /**
     * Flushes any pending messages and closes the Kafka producer.
     * Should be called during application shutdown.
     */
    public void close() {
        log.info("[jvmobs] Closing TelemetryProducer for appId={}", appId);
        producer.close();
    }
}
