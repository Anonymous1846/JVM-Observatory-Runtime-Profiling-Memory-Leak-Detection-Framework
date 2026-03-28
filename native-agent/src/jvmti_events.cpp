// jvmti_events.cpp — JVMTI event callback implementations
// ─────────────────────────────────────────────────────────────────────────────
//
// ══ READ THIS BEFORE MODIFYING ════════════════════════════════════════════════
//
// JVMTI callbacks fire on JVM-internal threads at points the JVM chooses.
// Some callbacks fire at JVM "safepoints" — moments where the JVM has
// paused all threads to do GC bookkeeping. The rules below are NOT optional.
//
// ── INSIDE ANY JVMTI CALLBACK: ALLOWED ────────────────────────────────────
//   ✓  Stack allocation:             RawEvent ev{};
//   ✓  Atomic ring buffer push:      g_event_buffer.push(ev);
//   ✓  Read a timestamp:             now_ns();
//   ✓  OS thread ID:                 GetCurrentThreadId() / gettid()
//   ✓  JVMTI: GetClassSignature()    (in VMObjectAlloc and ThreadStart ONLY)
//   ✓  JVMTI: Deallocate()          (to free strings from GetClassSignature)
//
// ── INSIDE ANY JVMTI CALLBACK: FORBIDDEN ──────────────────────────────────
//   ✗  Heap allocation:              new, malloc, std::string, std::vector
//      WHY: The JVM's memory allocator may hold a lock mid-GC.
//           Calling malloc from the GC thread deadlocks instantly.
//
//   ✗  Locks:                        std::mutex, CRITICAL_SECTION, lock_guard
//      WHY: If the same lock is held anywhere on the call stack that the
//           JVM paused, you will deadlock. The ring buffer uses lock-free
//           atomics precisely to avoid this.
//
//   ✗  JNI calls in GC callbacks:    env->NewObject(), env->CallMethod()...
//      WHY: JNIEnv* is not available (or valid) during GC callbacks.
//           The JVMTI spec signals this by not providing JNIEnv* in the
//           GarbageCollectionStart/Finish callback signatures.
//
//   ✗  C++ exceptions:               throw, try/catch that lets it propagate
//      WHY: Exceptions crossing JVMTI callback boundaries = undefined behavior.
//
//   ✗  Blocking I/O:                 file write, socket recv, printf to disk
//      WHY: Blocks the JVM thread, which may be holding GC locks.
//           printf to a terminal is buffered and usually safe for debugging
//           but remove it from production callbacks.
//
// ══ PERFORMANCE TARGET ════════════════════════════════════════════════════════
//
// Each callback should complete in < 100ns. Our ring buffer push is ~20ns
// (three atomic operations). The class signature lookup (GetClassSignature)
// is ~200-500ns but only runs in VMObjectAlloc, which fires relatively rarely
// compared to bytecode-level allocations.
// ─────────────────────────────────────────────────────────────────────────────

#include "agent.h"

// OS-specific headers for current_thread_id()
#ifdef _WIN32
#  include <windows.h>    // GetCurrentThreadId()
#else
#  include <unistd.h>     // syscall, SYS_gettid
#  include <sys/syscall.h>
#endif

// ─── Helper: current OS thread ID ─────────────────────────────────────────────
//
// We use the OS thread ID rather than JVMTI's jthread handle because:
//   1. Requires no JVMTI capability — works in every callback including GC.
//   2. Pure OS call — safe to call mid-GC (no JVM state accessed).
//   3. Java developers can correlate with Thread.currentThread().threadId()
//      or with OS-level profiling tools (Process Monitor, perf, etc.).
//
// On HotSpot JVM (OpenJDK), Java thread IDs do map to OS thread IDs, so
// a Java-side map of (os_thread_id → thread_name) built from ThreadStart
// events can decode the thread_id in every other event.
//
// static inline: this function is local to this translation unit and the
// compiler should inline it at every call site (it's only 1-2 instructions).
static inline uint32_t current_thread_id() noexcept {
#ifdef _WIN32
    return static_cast<uint32_t>(GetCurrentThreadId());
#else
    // gettid() is Linux-specific. On older glibc (<2.30), it is not directly
    // exposed as a function, so we use the syscall directly.
    return static_cast<uint32_t>(syscall(SYS_gettid));
#endif
}

// ─── Helper: fill class signature into a RawEvent ────────────────────────────
//
// Calls jvmti->GetClassSignature() to get the JNI-format class name and
// copies it into the 64-byte dest buffer.
//
// JNI CLASS SIGNATURE FORMAT (for Java developers):
//   Java type          JNI signature
//   ─────────────────  ─────────────────────────────
//   int                "I"
//   long               "J"
//   boolean            "Z"
//   double             "D"
//   java.util.ArrayList  "Ljava/util/ArrayList;"   ← L prefix, ; suffix, / not .
//   int[]              "[I"
//   String[][]         "[[Ljava/lang/String;"
//
// JVMTI MEMORY MANAGEMENT:
//   GetClassSignature() allocates the signature string using JVMTI's own
//   internal allocator. We MUST call jvmti->Deallocate() on it afterward.
//   Failing to deallocate is a memory leak in the JVM process. Unlike Java,
//   C++ does not have garbage collection — every JVMTI-allocated string
//   must be manually freed.
//
// WHY A SEPARATE HELPER FUNCTION?
//   Both VMObjectAlloc and (potentially) ThreadStart might want the class
//   name. Factoring it out avoids code duplication and makes the callbacks
//   shorter and easier to read.
//
// SAFETY: This function must NOT be called from GC callbacks (it calls JVMTI
// which has JNI involvement). Only call from VMObjectAlloc and ThreadStart.
static void fill_class_sig(
        jvmtiEnv*   jvmti,
        jclass      klass,
        char*       dest,
        std::size_t dest_len) noexcept
{
    char* sig    = nullptr;
    // generic_ptr is a JVMTI "generic signature" (for generic types like
    // List<String>). We pass nullptr because we only want the erased signature.
    char* generic_ptr = nullptr;

    jvmtiError err = jvmti->GetClassSignature(klass, &sig, &generic_ptr);

    // Free the generic signature immediately — we never use it.
    if (generic_ptr != nullptr) {
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic_ptr));
    }

    if (err == JVMTI_ERROR_NONE && sig != nullptr) {
#ifdef _WIN32
        // strncpy_s is MSVC's "safe" version of strncpy.
        // _TRUNCATE: if sig is longer than dest_len-1 chars, truncate silently.
        // The function always null-terminates dest.
        strncpy_s(dest, dest_len, sig, _TRUNCATE);
#else
        // strncpy does NOT guarantee null termination if src is longer than n.
        // We write the null byte explicitly at the last position.
        strncpy(dest, sig, dest_len - 1);
        dest[dest_len - 1] = '\0';
#endif
        // IMPORTANT: free the JVMTI-allocated string. Not optional.
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
    }
    // If GetClassSignature failed (e.g., klass is null, or a JVM internal
    // type that has no signature), dest remains '\0'-filled from RawEvent{}.
    // Java will see an empty string, which it can handle gracefully.
}

// ─── VMObjectAlloc callback ───────────────────────────────────────────────────
//
// Fires when a Java object is created via:
//   - JNI:        env->NewObject(), env->AllocObject()
//   - Reflection: Class.newInstance(), Constructor.newInstance()
//   - java.lang.reflect.Array.newInstance()
//
// Does NOT fire for ordinary "new Foo()" bytecodes. For bytecode-level
// allocation sampling, you would need JVMTI heap sampling (JDK 11+) or
// bytecode instrumentation. VMObjectAlloc is intentionally limited to
// JNI/reflection to reduce overhead.
//
// JVMTI SPEC SIGNATURE (must match exactly):
//   void JNICALL (*VMObjectAlloc)(jvmtiEnv *jvmti_env,
//                                 JNIEnv* jni_env,
//                                 jthread thread,
//                                 jobject object,
//                                 jclass object_klass,
//                                 jlong size);
//
// Parameters we USE:
//   jvmti        — to call GetClassSignature
//   object_klass — the class of the newly allocated object
//   size         — bytes allocated by the JVM for this object
//
// Parameters we IGNORE (suppressed by /wd4100 in CMakeLists.txt):
//   jni          — JNIEnv* (valid here, but we don't need to call JNI)
//   thread       — jthread handle (we use OS thread ID instead, no capability needed)
//   object       — the allocated object (barely initialized; unsafe to inspect)
extern "C" void JNICALL
OnVMObjectAlloc(jvmtiEnv* jvmti, JNIEnv* /*jni*/,
                jthread   /*thread*/, jobject /*object*/,
                jclass    object_klass, jlong size)
{
    // Stack-allocate the event record. {} means "zero-initialize all fields."
    // In JVMTI callbacks: NEVER use new, malloc, or std::string. Stack only.
    RawEvent ev{};
    ev.type         = static_cast<uint8_t>(EventType::ALLOC);
    ev.thread_id    = current_thread_id();
    ev.timestamp_ns = now_ns();
    ev.size_bytes   = static_cast<uint64_t>(size);

    // GetClassSignature is safe here: we are NOT in a GC callback.
    // JNIEnv is valid, JVMTI calls are permitted.
    fill_class_sig(jvmti, object_klass, ev.class_sig, sizeof(ev.class_sig));

    // Push to the lock-free ring buffer. ~20ns. Returns false if full (drop).
    g_event_buffer.push(ev);
}

// ─── GarbageCollectionStart callback ─────────────────────────────────────────
//
// Fires at the BEGINNING of a GC cycle, before any objects have been moved
// or collected. The JVM is at a safepoint — all Java threads are paused.
//
// ══ CRITICAL: HEAVILY RESTRICTED CALLBACK ════════════════════════════════════
//
// Notice the callback signature: only jvmtiEnv* — NO JNIEnv*.
// This is the JVMTI spec's way of saying: "JNI is not available here."
// The JVM is mid-GC; the JNI bridge is not operational. Calling any JNI
// function (even something innocuous-looking like env->FindClass) will crash
// or corrupt the JVM.
//
// Allowed: stack allocation, now_ns(), current_thread_id(), ring buffer push.
// Forbidden: JNI, heap allocation, locks, blocking I/O.
//
// JAVA USAGE:
// Java can compute GC pause duration by pairing GC_START and GC_FINISH events
// with the same thread_id and taking the timestamp difference:
//   gcPauseNs = gcFinishTimestamp - gcStartTimestamp
extern "C" void JNICALL
OnGarbageCollectionStart(jvmtiEnv* /*jvmti*/)
{
    RawEvent ev{};
    ev.type         = static_cast<uint8_t>(EventType::GC_START);
    ev.thread_id    = current_thread_id();
    ev.timestamp_ns = now_ns();
    // ev.size_bytes  = 0 — not applicable for GC events
    // ev.class_sig   = "" — not applicable for GC events

    g_event_buffer.push(ev);
}

// ─── GarbageCollectionFinish callback ────────────────────────────────────────
//
// Fires at the END of a GC cycle, after all objects have been moved or
// collected and the heap is in a consistent state.
//
// Same restrictions as GarbageCollectionStart: no JNI, no heap allocation.
// The JVMTI spec intentionally omits JNIEnv* from the signature.
//
// PAIRING WITH GC_START:
// Match GC_START and GC_FINISH events using thread_id. On concurrent
// collectors (G1, ZGC, Shenandoah), multiple GC phases may overlap — but
// within a single GC thread, start and finish are always sequential.
extern "C" void JNICALL
OnGarbageCollectionFinish(jvmtiEnv* /*jvmti*/)
{
    RawEvent ev{};
    ev.type         = static_cast<uint8_t>(EventType::GC_FINISH);
    ev.thread_id    = current_thread_id();
    ev.timestamp_ns = now_ns();

    g_event_buffer.push(ev);
}

// ─── ThreadStart callback ─────────────────────────────────────────────────────
//
// Fires when a new Java thread is about to execute its first instruction —
// after Thread.start() completes JVM thread setup, but before the thread's
// run() method body begins.
//
// Not a GC callback, so JNIEnv* is valid and JVMTI calls are permitted.
// We choose NOT to call GetThreadInfo() to avoid:
//   - Setting the can_get_thread_info capability (removed in JDK 21+)
//   - The complexity of managing the JVMTI-allocated thread info struct
// Instead, we capture the OS thread ID. Java can correlate it with
// Thread.currentThread().threadId() if needed.
//
// TYPICAL JAVA USAGE:
// Build a map of os_thread_id → thread_name using this event stream,
// then look up thread names when processing ALLOC and GC events.
extern "C" void JNICALL
OnThreadStart(jvmtiEnv* /*jvmti*/, JNIEnv* /*jni*/, jthread /*thread*/)
{
    RawEvent ev{};
    ev.type         = static_cast<uint8_t>(EventType::THREAD_START);
    ev.thread_id    = current_thread_id();
    ev.timestamp_ns = now_ns();
    // ev.size_bytes  = 0 — not applicable
    // ev.class_sig   = "" — thread name would go here if we had it;
    //                        add GetThreadInfo() + can_get_thread_info capability
    //                        if you need thread names (requires JDK < 21 or
    //                        checking the capability before setting it)

    g_event_buffer.push(ev);
}
