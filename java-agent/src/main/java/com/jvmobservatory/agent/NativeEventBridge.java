package com.jvmobservatory.agent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the C++ native agent DLL and drains events from the lock-free ring buffer via JNI.
 *
 * The native agent (loaded via -agentpath:) writes events into a shared memory ring buffer
 * using JVMTI callbacks. This class periodically drains that buffer from a Java thread,
 * deserializes the raw bytes into typed event objects, and routes them to the appropriate
 * handlers (allocation tracker, GC monitor, etc.).
 *
 * WHY drain from Java instead of pushing from C++:
 * JNI calls from C++ into Java are expensive and require careful thread management
 * (attaching/detaching threads). By having Java pull events on a timer, we:
 *   1. Avoid JNI overhead on the hot allocation path in C++
 *   2. Batch multiple events per drain cycle (amortizing JNI cost)
 *   3. Keep the C++ side simple — just write to a buffer
 *   4. Control the drain rate from Java (easy to tune without recompiling C++)
 */
public class NativeEventBridge {

    private static final Logger log = LoggerFactory.getLogger(NativeEventBridge.class);

    /**
     * Size of a single RawEvent in bytes. Must match sizeof(RawEvent) in the C++ code.
     * If the C++ struct layout changes, this MUST be updated in lockstep.
     *
     * C++ struct layout (88 bytes total):
     *   offset  0: type         (uint8_t,  1 byte)
     *   offset  1: _pad[3]      (3 bytes padding for alignment)
     *   offset  4: thread_id    (uint32_t, 4 bytes)
     *   offset  8: timestamp_ns (uint64_t, 8 bytes)
     *   offset 16: size_bytes   (uint64_t, 8 bytes)
     *   offset 24: class_sig    (char[64], 64 bytes)
     *   Total: 88 bytes
     */
    private static final int RAW_EVENT_SIZE = 88;

    private static ScheduledExecutorService drainExecutor;

    /**
     * JNI native method -- implemented in C++ as
     * Java_com_jvmobservatory_agent_NativeEventBridge_drainEvents
     *
     * Drains up to maxEvents from the lock-free ring buffer and returns them
     * as a flat byte array (maxEvents * RAW_EVENT_SIZE bytes maximum).
     * Returns null or empty array if no events are pending.
     *
     * @param maxEvents maximum number of events to drain in one call
     * @return raw bytes containing serialized RawEvent structs
     */
    private static native byte[] drainEvents(int maxEvents);

    static {
        try {
            // WHY System.loadLibrary (not System.load):
            // loadLibrary searches java.library.path, which the user sets via
            // -Djava.library.path=... This is more portable than hardcoding a path.
            // The library name "jvm_observatory_agent" maps to:
            //   - Windows: jvm_observatory_agent.dll
            //   - Linux:   libjvm_observatory_agent.so
            //   - macOS:   libjvm_observatory_agent.dylib
            System.loadLibrary("jvm_observatory_agent");
            log.info("[jvmobs] Native agent DLL loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            // This is expected when running without the native agent.
            // The Java-side instrumentation (ByteBuddy) still works — we just
            // won't get JVMTI-level events (exact object sizes, native GC phases).
            log.warn("[jvmobs] Native agent DLL not found. Native events disabled. " +
                     "Load with -agentpath: to enable.", e);
        }
    }

    // Callback interfaces for routing events to the appropriate subsystem
    private static Consumer<NativeAllocationEvent> allocationHandler;
    private static Consumer<NativeGCEvent> gcHandler;

    public static void setAllocationHandler(Consumer<NativeAllocationEvent> handler) {
        allocationHandler = handler;
    }

    public static void setGCHandler(Consumer<NativeGCEvent> handler) {
        gcHandler = handler;
    }

    /**
     * Start the background thread that periodically drains the native ring buffer.
     *
     * WHY 10ms interval:
     * - Fast enough to avoid ring buffer overflow under moderate allocation rates
     * - Slow enough to amortize JNI overhead (batch ~25-250 events per drain)
     * - Matches typical GC young-gen pause times, so we don't miss GC event pairs
     */
    public static void startDrainThread() {
        drainExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jvmobs-native-drain");
            t.setDaemon(true); // WHY daemon: must not prevent JVM shutdown
            return t;
        });
        drainExecutor.scheduleAtFixedRate(NativeEventBridge::drainCycle,
                10, 10, TimeUnit.MILLISECONDS);
        log.info("[jvmobs] Native drain thread started (10ms interval)");
    }

    /**
     * Single drain cycle: pull events from the native buffer and route them.
     *
     * This method is called every 10ms by the scheduled executor. It drains
     * up to 256 events per cycle, deserializes each one, and dispatches it
     * to the registered handler.
     */
    private static void drainCycle() {
        try {
            byte[] raw = drainEvents(256);
            if (raw == null || raw.length == 0) return;

            // ISSUE 4 FIX: Always use native byte order.
            // The C++ code writes structs in the CPU's native byte order (little-endian
            // on x86/x64, big-endian on SPARC/PowerPC). ByteBuffer defaults to BIG_ENDIAN,
            // which would misinterpret fields on little-endian systems (99% of modern hardware).
            // Using nativeOrder() ensures we read the bytes in the same order C++ wrote them.
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());

            int eventCount = raw.length / RAW_EVENT_SIZE;
            for (int i = 0; i < eventCount; i++) {
                int base = i * RAW_EVENT_SIZE;
                buf.position(base);

                // Read fields at EXACT byte offsets matching C++ RawEvent struct.
                // These offsets are determined by the C++ struct layout and alignment rules.
                // If the C++ struct changes, these offsets MUST be updated.
                byte typeCode = buf.get(base + 0);
                int threadId = buf.getInt(base + 4);
                long tsNs = buf.getLong(base + 8);
                long sizeBytes = buf.getLong(base + 16);

                // ISSUE 3 FIX: Read 64 bytes for class_sig and strip null bytes.
                // C++ writes a null-terminated string into a fixed 64-byte buffer.
                // Java strings don't use null terminators, so we must strip the
                // trailing \0 bytes to get a clean class name.
                byte[] sigBytes = new byte[64];
                buf.position(base + 24);
                buf.get(sigBytes);
                String classSig = new String(sigBytes, StandardCharsets.UTF_8)
                        .replace("\0", "").trim();

                routeEvent(typeCode, threadId, tsNs, sizeBytes, classSig);
            }
        } catch (Exception e) {
            log.error("[jvmobs] Error draining native events", e);
        }
    }

    /**
     * Route a deserialized event to the appropriate handler based on its type code.
     *
     * Type codes (defined in the C++ enum EventType):
     *   1 = ALLOC        (object allocation with class signature and size)
     *   2 = GC_START     (garbage collection cycle beginning)
     *   3 = GC_FINISH    (garbage collection cycle completed)
     *   4 = THREAD_START (new thread created)
     */
    private static void routeEvent(byte type, int threadId, long tsNs, long sizeBytes, String classSig) {
        switch (type) {
            case 1 -> { // ALLOC
                if (allocationHandler != null) {
                    allocationHandler.accept(new NativeAllocationEvent(classSig, sizeBytes, tsNs, threadId));
                }
            }
            case 2, 3 -> { // GC_START, GC_FINISH
                if (gcHandler != null) {
                    gcHandler.accept(new NativeGCEvent(type == 2, tsNs, threadId));
                }
            }
            case 4 -> { // THREAD_START
                log.debug("[jvmobs] Thread started: OS threadId={}", threadId);
            }
        }
    }

    /**
     * Stop the drain thread. Called during agent shutdown.
     */
    public static void stop() {
        if (drainExecutor != null) {
            drainExecutor.shutdownNow();
        }
    }

    // --- Inner DTOs for routing typed events ---

    /**
     * A native-level allocation event from the JVMTI agent.
     * Contains the exact object size (which ByteBuddy cannot provide).
     */
    public record NativeAllocationEvent(String classSig, long bytes, long timestampNs, int threadId) {}

    /**
     * A native-level GC phase event from the JVMTI agent.
     * Provides GC start/finish at a lower level than JMX notifications.
     */
    public record NativeGCEvent(boolean isStart, long timestampNs, int threadId) {}
}
