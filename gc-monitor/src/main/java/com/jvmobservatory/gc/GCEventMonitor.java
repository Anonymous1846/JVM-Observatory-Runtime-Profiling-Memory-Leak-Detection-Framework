package com.jvmobservatory.gc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to JVM GC notifications via JMX.
 *
 * WHY JMX over polling:
 * JMX GC notifications are event-driven — the JVM pushes a notification to us
 * when a GC cycle completes. This means ZERO CPU overhead during normal execution.
 * Polling-based approaches (e.g., checking GC counts on a timer) would burn CPU
 * continuously and still miss short-lived GC events between poll intervals.
 *
 * The JMX notification mechanism is built into HotSpot and has been stable since
 * Java 7. It's the same mechanism that tools like VisualVM and JMC use internally.
 */
public class GCEventMonitor {

    private static final Logger log = LoggerFactory.getLogger(GCEventMonitor.class);

    private final GCEventBus eventBus;
    private final HeapUsageTracker heapTracker;
    private ScheduledExecutorService heapSampler;

    /**
     * Number of consecutive GC events where post-GC heap usage exceeded the threshold.
     * A single high-usage GC is normal (e.g., a burst of allocations). Multiple
     * consecutive high-usage GCs suggest the application cannot free enough memory
     * and is heading toward OutOfMemoryError.
     */
    private int consecutiveHighUsage = 0;

    /**
     * If post-GC heap usage is above this ratio of max heap, we consider it "high".
     * 85% is chosen because most GC algorithms become pathologically slow above this
     * threshold — they spend more time collecting than the application spends running.
     */
    static final double OOM_RISK_THRESHOLD = 0.85;

    /**
     * How many consecutive high-usage GCs before we emit a warning.
     * 3 consecutive events filters out transient spikes while still catching
     * genuine memory pressure early enough to be actionable.
     */
    static final int OOM_RISK_CONSECUTIVE = 3;

    public GCEventMonitor(GCEventBus eventBus, HeapUsageTracker heapTracker) {
        this.eventBus = eventBus;
        this.heapTracker = heapTracker;
    }

    /**
     * Start listening for GC events and sampling heap usage.
     *
     * This method registers JMX notification listeners on every GC bean and
     * starts a background thread that samples heap usage once per second.
     */
    public void start() {
        // --- GC Notification Listeners ---
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // WHY casting to NotificationEmitter:
            // GarbageCollectorMXBean extends NotificationEmitter on HotSpot/OpenJDK,
            // but Java's type system doesn't express this at compile time. The MXBean
            // interface hierarchy (GarbageCollectorMXBean -> MemoryManagerMXBean ->
            // PlatformManagedObject) doesn't include NotificationEmitter. At runtime,
            // the concrete implementation (e.g., com.sun.management.internal.GarbageCollectorExtImpl)
            // DOES implement NotificationEmitter. This cast is safe on all mainstream JVMs.
            if (!(gcBean instanceof NotificationEmitter)) {
                log.warn("[jvmobs] GC bean {} does not support notifications (not a NotificationEmitter). " +
                         "This is unusual — are you running on a non-HotSpot JVM?", gcBean.getName());
                continue;
            }

            NotificationEmitter emitter = (NotificationEmitter) gcBean;
            NotificationListener listener = this::handleGCNotification;
            emitter.addNotificationListener(listener, null, null);
            log.info("[jvmobs] Registered GC notification listener for: {}", gcBean.getName());
        }

        // --- Heap Usage Sampling Thread ---
        // WHY a separate sampling thread instead of relying on GC events alone:
        // GC events tell us heap usage at GC time, but we also need to see heap usage
        // BETWEEN GCs. A leak that grows slowly may not trigger GC for minutes. The
        // 1-second sampling gives us a continuous time series for trend analysis.
        heapSampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jvmobs-heap-sampler");
            t.setDaemon(true); // WHY daemon: must not prevent JVM shutdown
            return t;
        });

        heapSampler.scheduleAtFixedRate(() -> {
            try {
                MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                heapTracker.record(new HeapSample(
                        Instant.now(),
                        usage.getUsed(),
                        usage.getMax()
                ));
            } catch (Exception e) {
                log.error("[jvmobs] Error sampling heap usage", e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        log.info("[jvmobs] Heap usage sampling started (1-second interval)");
    }

    /**
     * Handle a JMX GC notification.
     *
     * This callback is invoked by the JVM on one of its internal threads whenever
     * a GC cycle completes. We extract the GC details, build a GCRecord, and
     * publish it to the event bus.
     */
    private void handleGCNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                .equals(notification.getType())) {
            return;
        }

        // Extract GC info from the notification's user data.
        // The JMX spec encodes this as CompositeData — a generic key-value structure
        // that GarbageCollectionNotificationInfo knows how to parse.
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        GcInfo gcInfo = info.getGcInfo();

        // WHY summing memory pools:
        // GC operates on individual memory pools (Eden, Survivor0, Survivor1, Old Gen,
        // and possibly Metaspace). There is no single "heap used" field in the GC
        // notification — we must sum across all pools to get the total. This matches
        // what you see in VisualVM's "Heap" chart.
        long heapUsedBefore = gcInfo.getMemoryUsageBeforeGc().values().stream()
                .mapToLong(MemoryUsage::getUsed)
                .sum();
        long heapUsedAfter = gcInfo.getMemoryUsageAfterGc().values().stream()
                .mapToLong(MemoryUsage::getUsed)
                .sum();

        GCRecord record = new GCRecord(
                info.getGcName(),
                info.getGcCause(),
                info.getGcAction(),
                gcInfo.getDuration(),
                heapUsedBefore,
                heapUsedAfter,
                Instant.now()
        );

        eventBus.publish(record);
        checkOOMRisk(record);
    }

    /**
     * Check whether the JVM is at risk of running out of memory.
     *
     * We look at post-GC heap usage because that represents the "live set" —
     * objects that survived garbage collection and cannot be freed. If the live
     * set consistently exceeds 85% of max heap, the JVM is in trouble:
     *   - GC will run more frequently (higher CPU overhead)
     *   - GC pauses will get longer (more live objects to scan)
     *   - Eventually, GC overhead will exceed the limit and trigger OOM
     *
     * We require multiple consecutive high-usage GCs to avoid false alarms
     * from transient allocation bursts.
     */
    private void checkOOMRisk(GCRecord record) {
        long maxHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        if (maxHeap <= 0) return; // max heap unknown (some JVM configs)

        double ratio = (double) record.heapUsedAfter() / maxHeap;

        if (ratio > OOM_RISK_THRESHOLD) {
            consecutiveHighUsage++;
        } else {
            consecutiveHighUsage = 0;
        }

        if (consecutiveHighUsage >= OOM_RISK_CONSECUTIVE) {
            log.warn("[jvmobs] OOM RISK: heap usage above 85% for {} consecutive GCs. " +
                     "Post-GC heap: {}MB / {}MB ({}%)",
                    consecutiveHighUsage,
                    record.heapUsedAfter() / (1024 * 1024),
                    maxHeap / (1024 * 1024),
                    (int) (ratio * 100));
        }
    }

    /**
     * Stop the heap sampling thread. GC notification listeners are automatically
     * cleaned up when the JVM shuts down.
     */
    public void stop() {
        if (heapSampler != null) {
            heapSampler.shutdownNow();
            log.info("[jvmobs] Heap sampler stopped");
        }
    }
}
