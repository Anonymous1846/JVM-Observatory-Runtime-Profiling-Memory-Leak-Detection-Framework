package com.jvmobservatory.api;

import com.jvmobservatory.telemetry.MetricsAggregator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the JVM Observatory Metrics API.
 *
 * <p>This Spring Boot application serves REST endpoints that expose JVM telemetry
 * data (allocations, GC stats, heap trends, leak suspects) consumed from Kafka.
 * The React dashboard polls these endpoints to render live visualizations.</p>
 */
@SpringBootApplication
public class MetricsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetricsApiApplication.class, args);
    }

    /**
     * Singleton MetricsAggregator bean shared between the Kafka event consumer
     * (which writes data) and the REST services (which read data).
     *
     * <p>Registered as a Spring bean so both layers get the same instance via
     * dependency injection. The aggregator uses ConcurrentHashMap internally,
     * so concurrent read/write access from different threads is safe.</p>
     */
    @Bean
    public MetricsAggregator metricsAggregator() {
        return new MetricsAggregator();
    }
}
