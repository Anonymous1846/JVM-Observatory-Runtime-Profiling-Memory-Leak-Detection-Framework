package com.jvmobservatory.gc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple in-process pub/sub for GC events.
 *
 * This is intentionally NOT a full event bus framework (no Guava EventBus, no Spring events).
 * We need exactly one thing: fan-out GC events to multiple consumers with zero allocation
 * on the publish path. A simple list of consumers does the job.
 *
 * WHY CopyOnWriteArrayList:
 * Reads (publish) vastly outnumber writes (subscribe). COWAL is optimized for this pattern —
 * reads are completely lock-free, iterating over a stable snapshot of the array.
 * The overhead of copying on write is acceptable since subscribe() is called once at startup,
 * while publish() is called on every GC event (potentially hundreds of times per second
 * under allocation pressure).
 */
public class GCEventBus {

    private static final Logger log = LoggerFactory.getLogger(GCEventBus.class);

    private final List<Consumer<GCRecord>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener to receive GC events.
     * Typically called once at startup — not on the hot path.
     *
     * @param listener callback that receives each GC event
     */
    public void subscribe(Consumer<GCRecord> listener) {
        listeners.add(listener);
        log.debug("[jvmobs] GC event listener registered: {}", listener);
    }

    /**
     * Fan out a GC event to all registered listeners.
     *
     * Each listener is invoked in a try/catch so that one misbehaving listener
     * cannot prevent other listeners from receiving the event. This is critical
     * because GC events are ephemeral — if a listener misses one, that data is
     * lost forever.
     *
     * @param event the GC event to broadcast
     */
    public void publish(GCRecord event) {
        for (Consumer<GCRecord> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // WHY catch per listener: a Kafka send failure should not prevent
                // the OOM risk detector from seeing the event. Each consumer is
                // independent and must not affect others.
                log.error("[jvmobs] GC event listener threw exception", e);
            }
        }
    }
}
