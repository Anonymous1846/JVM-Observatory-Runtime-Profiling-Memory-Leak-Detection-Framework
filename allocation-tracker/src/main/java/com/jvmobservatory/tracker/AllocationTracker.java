package com.jvmobservatory.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Lock-free, high-throughput allocation counting engine.
 *
 * <h2>Design Rationale</h2>
 *
 * <p><b>WHY Singleton:</b> There is exactly one JVM per process, so there is exactly one
 * allocation tracker. The singleton is accessed from ByteBuddy-injected advice on every
 * {@code new} bytecode — it must be fast to look up. We use the initialization-on-demand
 * holder idiom (Bill Pugh pattern) because it is lazy, thread-safe, and requires no
 * synchronization on the fast path (the JVM guarantees class initialization is serial).</p>
 *
 * <p><b>WHY LongAdder (not AtomicLong):</b> Under high contention (many threads recording
 * allocations simultaneously), AtomicLong becomes a bottleneck because every CAS retry
 * invalidates the cache line on all other cores. LongAdder uses <em>striped counters</em> —
 * each thread writes to its own cell, and {@code sum()} aggregates lazily. Benchmarks show
 * 10x higher throughput under 8+ threads compared to AtomicLong.</p>
 *
 * <p><b>WHY ConcurrentHashMap (not synchronized HashMap):</b> ConcurrentHashMap uses
 * lock striping — only the hash bucket is locked during writes, not the entire map.
 * This means threads recording allocations for different classes never contend with
 * each other. A synchronized HashMap would serialize ALL recordings.</p>
 */
public final class AllocationTracker {

    private static final Logger logger = LoggerFactory.getLogger(AllocationTracker.class);

    // ── Initialization-on-demand holder (Bill Pugh singleton) ──────────────
    // WHY this pattern: the inner class is not loaded until getInstance() is called,
    // so the singleton is lazy. The JVM spec guarantees that class initialization
    // is thread-safe, so no explicit synchronization is needed.
    private static final class Holder {
        private static final AllocationTracker INSTANCE = new AllocationTracker();
    }

    /**
     * Returns the singleton tracker instance.
     */
    public static AllocationTracker getInstance() {
        return Holder.INSTANCE;
    }

    // ── Per-class allocation counts ────────────────────────────────────────
    // Key: fully qualified class name (e.g., "java.util.ArrayList")
    // Value: LongAdder for lock-free concurrent incrementing
    private final ConcurrentHashMap<String, LongAdder> allocationCounts = new ConcurrentHashMap<>();

    // ── Per-class allocation bytes (populated by native JVMTI callbacks) ───
    private final ConcurrentHashMap<String, LongAdder> allocationBytes = new ConcurrentHashMap<>();

    // ── Hot allocation site tracking ───────────────────────────────────────
    // Key: "className::methodName(fileName:lineNumber)"
    // Value: AllocationSite with count and timing info
    private final ConcurrentHashMap<String, AllocationSite> allocationSites = new ConcurrentHashMap<>();

    /**
     * WHY MAX_TRACKED_SITES: Without a cap, a pathological workload (e.g., generated
     * class names or dynamic proxies) could create millions of unique site keys,
     * consuming unbounded memory. 100K sites is generous for real applications and
     * keeps the map under ~50MB. When the cap is reached, new sites are silently
     * dropped — existing sites continue to be tracked accurately.
     *
     * See: GitHub Issue #7 — "Prevent unbounded growth in allocation site tracking"
     */
    static final int MAX_TRACKED_SITES = 100_000;

    // ── Rolling rate window ────────────────────────────────────────────────
    // WHY 60 buckets: one per second, giving a 1-minute rolling window for
    // computing allocations/sec. The timer thread calls advanceBucket() every
    // second to rotate to the next bucket and zero it out.
    private final long[] rateBuckets = new long[60];
    private final AtomicInteger currentBucket = new AtomicInteger(0);

    // Prefixes to skip when extracting the "interesting" call site from a stack trace.
    // WHY skip these: JDK internals and our own instrumentation frames are noise —
    // the developer wants to see THEIR code that triggered the allocation.
    private static final String[] SKIP_PREFIXES = {
            "java.", "javax.", "sun.", "jdk.", "com.jvmobservatory.", "net.bytebuddy."
    };

    private AllocationTracker() {
        logger.info("AllocationTracker initialized (max tracked sites: {})", MAX_TRACKED_SITES);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Recording methods — called on every allocation, MUST be fast
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Record an allocation detected by ByteBuddy instrumentation.
     *
     * <p>Called from injected advice on every {@code new} bytecode in instrumented classes.
     * This is the hottest path in the entire system, so every nanosecond matters:</p>
     * <ul>
     *   <li>LongAdder.increment() is a single CAS on the thread's stripe — no retries</li>
     *   <li>computeIfAbsent() only allocates on the first occurrence of a class name</li>
     *   <li>Stack walking is O(depth) but we short-circuit at the first non-JDK frame</li>
     * </ul>
     *
     * @param className  fully qualified class name of the allocated object
     * @param stack      stack trace at the allocation point (may be null if unavailable)
     * @param durationNs time spent in the constructor (for timing analysis)
     */
    public void record(String className, StackTraceElement[] stack, long durationNs) {
        // Count the allocation for this class
        allocationCounts.computeIfAbsent(className, k -> new LongAdder()).increment();

        // Extract the first "interesting" call site — the developer's code that triggered
        // the allocation, skipping JDK frames and our own instrumentation.
        if (stack != null && stack.length > 0) {
            StackTraceElement site = extractCallSite(stack);
            if (site != null) {
                String siteKey = className + "::" + formatSite(site);

                // WHY size check before computeIfAbsent: ConcurrentHashMap.size() is O(1)
                // (it maintains a counter). This avoids the cost of creating the lambda
                // and AllocationSite object when we're at capacity.
                if (allocationSites.size() < MAX_TRACKED_SITES) {
                    allocationSites.computeIfAbsent(siteKey,
                            k -> new AllocationSite(className, formatSite(site))).record(durationNs);
                }
            }
        }

        // Bump the current rate bucket (not atomic with the bucket read, but that's fine —
        // rate estimation is approximate by nature)
        rateBuckets[currentBucket.get() % 60]++;
    }

    /**
     * Record an allocation detected by the native JVMTI agent via VMObjectAlloc callback.
     *
     * <p>The native agent passes JNI-style type signatures (e.g., "Ljava/util/ArrayList;")
     * because that is what JVMTI provides. We convert to human-readable names here rather
     * than in native code to keep the C agent simple and avoid JNI string manipulation.</p>
     *
     * @param classSig    JNI type signature (e.g., "Ljava/util/ArrayList;", "[B", "[Ljava/lang/String;")
     * @param bytes       size of the allocated object in bytes
     * @param timestampNs monotonic timestamp from the native agent (for ordering)
     */
    public void recordNative(String classSig, long bytes, long timestampNs) {
        String name = convertJniSignature(classSig);

        allocationCounts.computeIfAbsent(name, k -> new LongAdder()).increment();
        allocationBytes.computeIfAbsent(name, k -> new LongAdder()).add(bytes);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Query methods — called by the metrics API, not on the hot path
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the top N classes by allocation count, sorted descending.
     *
     * <p>WHY stream + sort (not a priority queue): this method is called infrequently
     * (e.g., every 10 seconds by the metrics endpoint). The number of tracked classes
     * is typically < 10K, so sorting is fast and the code is more readable than
     * maintaining a concurrent priority queue on the hot path.</p>
     */
    public List<ClassStats> getTopAllocators(int topN) {
        long totalAllocations = getTotalAllocations();

        return allocationCounts.entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<String, LongAdder>>comparingLong(
                        e -> e.getValue().sum()).reversed())
                .limit(topN)
                .map(e -> {
                    String className = e.getKey();
                    long count = e.getValue().sum();
                    long bytes = allocationBytes.containsKey(className)
                            ? allocationBytes.get(className).sum() : 0L;
                    double percentage = totalAllocations > 0
                            ? (count * 100.0) / totalAllocations : 0.0;
                    return new ClassStats(className, count, bytes, percentage);
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns the top N allocation sites by count, sorted descending.
     */
    public List<AllocationSite> getTopAllocationSites(int topN) {
        return allocationSites.values().stream()
                .sorted(Comparator.comparingLong(AllocationSite::totalCount).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    /**
     * Returns the total number of allocations recorded across all classes.
     */
    public long getTotalAllocations() {
        return allocationCounts.values().stream()
                .mapToLong(LongAdder::sum)
                .sum();
    }

    /**
     * Advance the rolling rate window by one second.
     *
     * <p>Called by a timer thread every 1 second. Increments the bucket index and zeroes
     * the new bucket so it starts counting fresh. The previous 59 buckets retain their
     * counts, giving a 60-second sliding window for rate calculation.</p>
     */
    public void advanceBucket() {
        int next = currentBucket.incrementAndGet() % 60;
        rateBuckets[next] = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Walk the stack trace and return the first frame that is NOT from the JDK
     * or our own instrumentation. This is the "interesting" call site — the
     * developer's code that triggered the allocation.
     */
    private StackTraceElement extractCallSite(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String frameClass = frame.getClassName();
            if (!shouldSkipFrame(frameClass)) {
                return frame;
            }
        }
        // All frames are JDK/instrumentation — return the deepest frame as a fallback
        return stack.length > 0 ? stack[stack.length - 1] : null;
    }

    private boolean shouldSkipFrame(String className) {
        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String formatSite(StackTraceElement element) {
        return element.getMethodName() + "(" + element.getFileName() + ":" + element.getLineNumber() + ")";
    }

    /**
     * Convert a JNI type signature to a human-readable Java class name.
     *
     * <p>JNI signatures use a compact encoding:</p>
     * <ul>
     *   <li>{@code Ljava/util/ArrayList;} → {@code java.util.ArrayList}</li>
     *   <li>{@code [B} → {@code byte[]}</li>
     *   <li>{@code [Ljava/lang/String;} → {@code java.lang.String[]}</li>
     *   <li>{@code [[I} → {@code int[][]}</li>
     * </ul>
     *
     * <p>WHY we do this conversion in Java (not C): JNI string manipulation is verbose
     * and error-prone. The native agent just passes the raw signature, and we handle
     * the formatting here where String operations are trivial.</p>
     */
    static String convertJniSignature(String sig) {
        if (sig == null || sig.isEmpty()) {
            return "unknown";
        }

        // Count array dimensions
        int arrayDepth = 0;
        int idx = 0;
        while (idx < sig.length() && sig.charAt(idx) == '[') {
            arrayDepth++;
            idx++;
        }

        // Parse the base type
        String baseName;
        if (idx < sig.length()) {
            char typeChar = sig.charAt(idx);
            baseName = switch (typeChar) {
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'D' -> "double";
                case 'F' -> "float";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'S' -> "short";
                case 'Z' -> "boolean";
                case 'L' -> {
                    // Object type: strip leading 'L' and trailing ';', replace '/' with '.'
                    int semiColon = sig.indexOf(';', idx);
                    if (semiColon > idx + 1) {
                        yield sig.substring(idx + 1, semiColon).replace('/', '.');
                    }
                    yield sig.substring(idx + 1).replace('/', '.');
                }
                default -> sig; // Unknown — return as-is
            };
        } else {
            baseName = sig;
        }

        // Append array brackets
        if (arrayDepth > 0) {
            StringBuilder sb = new StringBuilder(baseName);
            for (int i = 0; i < arrayDepth; i++) {
                sb.append("[]");
            }
            return sb.toString();
        }

        return baseName;
    }
}
