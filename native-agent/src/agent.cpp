// agent.cpp — JVM Observatory native agent: core implementation
// ─────────────────────────────────────────────────────────────────────────────
//
// This file contains:
//   1. Global variable DEFINITIONS (g_jvmti, g_event_buffer)
//   2. now_ns() — high-resolution nanosecond timestamp
//   3. DllMain  — Windows DLL entry point
//   4. Agent_OnLoad  — called by JVM when -agentpath DLL is first loaded
//   5. Agent_OnUnload — called by JVM during orderly shutdown
//   6. Java_com_jvmobservatory_agent_NativeEventBridge_drainEvents
//                  — JNI function: drains ring buffer into a Java byte[]
//
// LOADING SEQUENCE (for orientation):
//   java -agentpath:jvm_observatory_agent.dll -jar myapp.jar
//     │
//     ├─► Windows LoadLibrary("jvm_observatory_agent.dll")
//     │     └─► DllMain(DLL_PROCESS_ATTACH)         ← step 3
//     │
//     ├─► JVM calls Agent_OnLoad(javaVm, ...)        ← step 4
//     │     ├─► GetEnv → jvmtiEnv*
//     │     ├─► AddCapabilities (request event types)
//     │     ├─► SetEventCallbacks (register C++ functions)
//     │     └─► SetEventNotificationMode (enable events)
//     │
//     ├─► JVM runs myapp.jar
//     │     ├─► JVMTI callbacks fire → g_event_buffer.push(ev)
//     │     └─► Java drain thread → drainEvents() JNI → g_event_buffer.pop()
//     │
//     └─► JVM shuts down → Agent_OnUnload(javaVm)   ← step 5
// ─────────────────────────────────────────────────────────────────────────────

#include "agent.h"

#include <cstdio>    // printf, fprintf (startup diagnostics)

// ─── Global variable DEFINITIONS ──────────────────────────────────────────────
//
// These are the ONE TRUE definitions. agent.h uses "extern" to declare them,
// meaning "they exist somewhere." Here is where the memory is actually
// allocated (in the .bss/.data segment of the DLL).
//
// JAVA ANALOGY:
//   agent.h declares:    extern jvmtiEnv* g_jvmti;         ≈ "static jvmtiEnv g_jvmti;"
//   agent.cpp defines:          jvmtiEnv* g_jvmti = nullptr; ← actual allocation
//
// WHY GLOBAL (not passed as parameters)?
//   JVMTI callbacks have fixed signatures specified by the JVMTI spec.
//   We cannot add extra parameters to them. The only way for callbacks
//   (defined in jvmti_events.cpp) to access the ring buffer and JVMTI
//   environment is via globals. This is a JVMTI idiom, not general advice.
jvmtiEnv*                                   g_jvmti       = nullptr;
RingBuffer<RawEvent, EVENT_BUFFER_CAPACITY> g_event_buffer;

// ─── now_ns() ─────────────────────────────────────────────────────────────────
//
// Returns a monotonically increasing timestamp in nanoseconds.
// "Monotonically increasing" means: every call returns a value >= the previous
// call. Timestamps never go backward (unlike wall-clock time during DST changes).
//
// WHY NOT std::chrono::steady_clock?
//   std::chrono::steady_clock is theoretically correct, but on older MSVC/Windows
//   configurations it has been observed with ~15ms resolution (the system timer
//   tick interval). For a profiler that measures GC pauses, 15ms granularity
//   makes sub-millisecond events invisible.
//   QueryPerformanceCounter reads the hardware performance counter register
//   directly and has ~100ns precision on all modern Windows 10/11 systems.
//   On Linux, clock_gettime(CLOCK_MONOTONIC) reliably delivers ~1ns precision.
//
// FREQUENCY CACHING:
//   QueryPerformanceFrequency() is an OS call (slower than a register read).
//   We call it exactly once via a static local initialized at first call.
//   C++11 guarantees static local initialization is thread-safe: a hidden
//   "guard" flag ensures only one thread runs the initializer, even if
//   multiple threads call now_ns() simultaneously.
//
// OVERFLOW AVOIDANCE:
//   With a typical QPC frequency of ~10 MHz, a uint64_t counter overflows
//   after ~58,000 years — not a concern. However, multiplying the counter by
//   1,000,000,000 BEFORE dividing by frequency would overflow in ~9 seconds.
//   We use double conversion to avoid this: we lose nanosecond precision beyond
//   ~100ns, which is fine for our use case (GC pause measurement).
uint64_t now_ns() noexcept {
#ifdef _WIN32
    static const LARGE_INTEGER s_freq = []() -> LARGE_INTEGER {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f;
    }();

    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);

    return static_cast<uint64_t>(
        static_cast<double>(counter.QuadPart)
        / static_cast<double>(s_freq.QuadPart)
        * 1.0e9
    );
#else
    // Linux: clock_gettime directly delivers seconds + nanoseconds.
    // CLOCK_MONOTONIC: not affected by NTP adjustments or system clock changes.
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec)  * 1'000'000'000ULL
         + static_cast<uint64_t>(ts.tv_nsec);
#endif
}

// ─── DllMain (Windows only) ───────────────────────────────────────────────────
//
// DllMain is the entry point for a Windows DLL. Windows calls it automatically
// when the DLL is loaded into a process, unloaded, or when a thread attaches
// or detaches.
//
// WHY DO WE NEED THIS AT ALL?
//   Without a custom DllMain, the CRT provides a stub that does nothing.
//   We need a custom one to call DisableThreadLibraryCalls (see below).
//
// WHY DisableThreadLibraryCalls?
//   By default, Windows calls DllMain with DLL_THREAD_ATTACH every time a
//   new thread is created in the process, and DLL_THREAD_DETACH when a thread
//   exits. The JVM creates hundreds of internal threads:
//     - GC threads (G1 parallel GC workers, ZGC thread pool)
//     - JIT compiler threads (C1, C2)
//     - VM periodic task thread
//     - Finalizer thread
//     - Reference handler thread
//     - ... and more
//   Each one would trigger DllMain unnecessarily. DisableThreadLibraryCalls
//   suppresses the THREAD_ATTACH / THREAD_DETACH notifications entirely.
//   This improves thread startup performance and eliminates the risk of
//   accidentally holding a lock inside DllMain (which causes loader-lock
//   deadlocks — a notoriously hard-to-debug Windows bug).
//
// WHY #ifdef _WIN32?
//   DllMain is Windows-only. On Linux, shared libraries use
//   __attribute__((constructor)) and __attribute__((destructor)) for equivalent
//   functionality, but we don't need either here (Agent_OnLoad is enough).
#ifdef _WIN32
BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID /*lpReserved*/) {
    if (ul_reason_for_call == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hModule);
    }
    // DLL_PROCESS_DETACH: Agent_OnUnload handles cleanup.
    // DLL_THREAD_ATTACH / DLL_THREAD_DETACH: suppressed by the call above.
    return TRUE;
}
#endif

// ─── Agent_OnLoad ──────────────────────────────────────────────────────────────
//
// The JVM calls this function immediately after loading the agent DLL, before
// the application's main() or main class is loaded. This is our "constructor"
// for the agent.
//
// Parameters (defined by the JVMTI specification):
//   vm       — a pointer to the JavaVM. Use vm->GetEnv() to obtain JNIEnv* or
//              jvmtiEnv*. Do not store it long-term (use g_jvmti instead).
//   options  — the string after '=' in -agentpath:foo.dll=OPTIONS. Null if
//              no options string was provided. Currently unused.
//   reserved — always null; reserved for future JVMTI use.
//
// Return values:
//   JNI_OK  (0)  — agent initialized successfully; JVM continues loading.
//   JNI_ERR (-1) — initialization failed; JVM will print an error and abort.
//
// WHY extern "C"?
//   The JVM searches for "Agent_OnLoad" by its exact string name in the DLL's
//   export table. C++ name mangling would rename it to something like
//   "_Z11Agent_OnLoadP6JavaVMPcPv", which the JVM cannot find.
//   extern "C" disables mangling, preserving the plain name "Agent_OnLoad".
//
// WHY JNIEXPORT?
//   JNIEXPORT expands to __declspec(dllexport) on Windows. This ensures the
//   symbol is placed in the DLL's export table. Without it, the symbol is
//   private to the DLL and invisible to the JVM's LoadLibrary/GetProcAddress.
//   The .def file provides a second guarantee (belt-and-suspenders approach).
extern "C" JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM* vm, char* /*options*/, void* /*reserved*/) {

    // ─── Step 1: Obtain the JVMTI environment ────────────────────────────────
    //
    // GetEnv asks the JVM for an interface pointer to the JVMTI API.
    // Think of jvmtiEnv* like a Java interface: it gives us access to all
    // JVMTI functions (GetClassSignature, AddCapabilities, SetEventCallbacks...).
    //
    // JVMTI_VERSION_11: Request the JVMTI 11.0 interface. The JVM will return
    // the requested version or a compatible newer version. JVMTI 11 is fully
    // supported on JDK 11, 17, 21, and 22.
    //
    // reinterpret_cast<void**>: GetEnv takes a void** because it predates
    // generics/templates. We cast our jvmtiEnv** to void** for the call,
    // then use the filled pointer as jvmtiEnv*. This is safe — the JVM
    // knows it's writing a jvmtiEnv* into our pointer variable.
    jvmtiEnv* jvmti = nullptr;
    jint rc = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_11);
    if (rc != JNI_OK || jvmti == nullptr) {
        fprintf(stderr,
            "[jvmobs] FATAL: GetEnv(JVMTI_VERSION_11) failed (rc=%d). "
            "Is the JDK version >= 11?\n", static_cast<int>(rc));
        return JNI_ERR;
    }
    g_jvmti = jvmti;  // store globally so JVMTI callbacks can use it

    // ─── Step 2: Add capabilities ────────────────────────────────────────────
    //
    // JVMTI is opt-in. A freshly obtained jvmtiEnv* cannot generate ANY events
    // by default. We must explicitly request permission for the event types
    // we want to receive. Unused capabilities waste JVM overhead, so we
    // request only what we need.
    //
    // jvmtiCapabilities{} — zero-initializes all capability flags to 0 (off).
    // In C++, {} on a struct means "set all fields to zero." We then set only
    // the two we need to 1.
    //
    // CRITICAL — DO NOT ADD these capabilities (JDK 21+ removed them):
    //   caps.can_get_thread_info       — was removed in JDK 21+; always available now.
    //   caps.can_get_source_file_name  — same. Adding either causes:
    //     error C2039: 'can_get_thread_info': is not a member of 'jvmtiCapabilities'
    //
    // GetClassSignature requires NO capability — it works out of the box.
    jvmtiCapabilities caps{};
    caps.can_generate_vm_object_alloc_events    = 1;  // enables VMObjectAlloc event
    caps.can_generate_garbage_collection_events = 1;  // enables GC start/finish events

    rc = jvmti->AddCapabilities(&caps);
    if (rc != JVMTI_ERROR_NONE) {
        fprintf(stderr,
            "[jvmobs] FATAL: AddCapabilities failed (jvmtiError=%d). "
            "Another agent may have claimed exclusive capabilities.\n",
            static_cast<int>(rc));
        return JNI_ERR;
    }

    // ─── Step 3: Register callback functions ─────────────────────────────────
    //
    // jvmtiEventCallbacks is a C struct where each field is a function pointer
    // for one event type. We zero-initialize it (all callbacks = nullptr), then
    // set only the four events we care about.
    //
    // The callback functions are defined in jvmti_events.cpp.
    // They are declared in agent.h with extern "C" so they have C linkage,
    // making their function-pointer types compatible with the JVMTI C API.
    jvmtiEventCallbacks callbacks{};
    callbacks.VMObjectAlloc           = &OnVMObjectAlloc;
    callbacks.GarbageCollectionStart  = &OnGarbageCollectionStart;
    callbacks.GarbageCollectionFinish = &OnGarbageCollectionFinish;
    callbacks.ThreadStart             = &OnThreadStart;

    // sizeof(callbacks): passing the struct size allows newer JVMs to handle
    // older agents that don't know about newer callback fields.
    rc = jvmti->SetEventCallbacks(&callbacks, static_cast<jint>(sizeof(callbacks)));
    if (rc != JVMTI_ERROR_NONE) {
        fprintf(stderr,
            "[jvmobs] FATAL: SetEventCallbacks failed (jvmtiError=%d).\n",
            static_cast<int>(rc));
        return JNI_ERR;
    }

    // ─── Step 4: Enable events ────────────────────────────────────────────────
    //
    // Registering callbacks alone is NOT enough — we must also ENABLE each
    // event type. The second parameter (nullptr) means "enable globally for
    // all threads." Passing a jthread handle would scope the event to a
    // single thread, which we don't need here.
    //
    // We use a lambda to reduce repetition. On C++17, lambdas are fully
    // supported and compile to tight inline code — no overhead.
    auto enable_event = [&](jvmtiEvent event_type, const char* event_name) {
        jvmtiError err = jvmti->SetEventNotificationMode(
            JVMTI_ENABLE,   // enable (use JVMTI_DISABLE to turn off)
            event_type,     // which event
            nullptr         // scope: nullptr = all threads
        );
        if (err != JVMTI_ERROR_NONE) {
            // Non-fatal: log a warning but continue. The agent still works
            // for the events that were successfully enabled.
            fprintf(stderr,
                "[jvmobs] WARNING: Could not enable %s event (jvmtiError=%d). "
                "Events of this type will not be captured.\n",
                event_name, static_cast<int>(err));
        }
    };

    enable_event(JVMTI_EVENT_VM_OBJECT_ALLOC,           "VMObjectAlloc");
    enable_event(JVMTI_EVENT_GARBAGE_COLLECTION_START,  "GarbageCollectionStart");
    enable_event(JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, "GarbageCollectionFinish");
    enable_event(JVMTI_EVENT_THREAD_START,              "ThreadStart");

    // ─── Startup banner ───────────────────────────────────────────────────────
    // This message appears on the console when running:
    //   java -agentpath:jvm_observatory_agent.dll -version
    // Verify with: java -agentpath:build\Release\jvm_observatory_agent.dll -version
    // Expected: [jvmobs] Agent loaded on Windows. Buffer: 4096 slots.
    printf("[jvmobs] Agent loaded on %s. Buffer: %zu slots.\n",
#ifdef _WIN32
           "Windows",
#else
           "Linux",
#endif
           EVENT_BUFFER_CAPACITY);

    return JNI_OK;
}

// ─── Agent_OnUnload ────────────────────────────────────────────────────────────
//
// Called by the JVM during orderly shutdown, after all Java threads have
// terminated and all pending finalizers have run.
//
// Use this to release resources acquired in Agent_OnLoad.
// DO NOT call JNI functions here — the JNIEnv* is no longer valid.
// The JVMTI environment itself is being torn down; calling jvmti->anything()
// here has undefined behavior.
extern "C" JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM* /*vm*/) {
    // The ring buffer lives in static storage — no manual free() needed.
    // Null out g_jvmti to prevent any late-firing callback from calling into
    // a torn-down JVMTI environment. (Late callbacks should not happen after
    // Agent_OnUnload, but defensive nulling costs nothing.)
    g_jvmti = nullptr;
    printf("[jvmobs] Agent unloaded.\n");
}

// ─── drainEvents JNI function ──────────────────────────────────────────────────
//
// Called from Java every 10ms via ScheduledExecutorService:
//
//   // Java side (com.jvmobservatory.agent.NativeEventBridge)
//   private static native byte[] drainEvents(int maxEvents);
//
//   // Java caller
//   byte[] raw = NativeEventBridge.drainEvents(256);
//   if (raw != null) {
//       ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
//       // process events at offsets 0, 88, 176, ... (one RawEvent per 88 bytes)
//   }
//
// This function pops up to `maxEvents` RawEvent structs from the ring buffer,
// packs them into a contiguous byte[], and returns it to Java.
//
// ── JNI NAMING RULE ──────────────────────────────────────────────────────────
// The function name is derived mechanically from the Java declaration:
//   Java_                          ← prefix
//   com_jvmobservatory_agent       ← package name (dots → underscores)
//   _NativeEventBridge             ← class name
//   _drainEvents                   ← method name
//   Result: Java_com_jvmobservatory_agent_NativeEventBridge_drainEvents
//
// This exact string MUST appear in:
//   1. The C++ function name (here)
//   2. The .def file EXPORTS section
//   3. dumpbin /exports output after building
//
// ── PARAMETERS ───────────────────────────────────────────────────────────────
//   env       — JNIEnv* for the calling Java thread. Valid here (not in GC).
//   cls       — the NativeEventBridge Class object (static native method).
//               Unused — we don't need to call back into the Java class.
//   maxEvents — maximum number of events to drain. Java passes 256.
//
// ── RETURN VALUE ─────────────────────────────────────────────────────────────
//   byte[] of length (count × 88), or null if no events are available.
//   Java checks for null before processing, so null is the "nothing to do" signal.
//
// ── HEAP ALLOCATION NOTE ──────────────────────────────────────────────────────
//   env->NewByteArray() allocates in the Java heap — this is fine because we
//   are called from a normal Java thread, NOT from a JVMTI callback.
//   (JVMTI callbacks may not do heap allocation; drainEvents may.)
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jvmobservatory_agent_NativeEventBridge_drainEvents(
        JNIEnv* env, jclass /*cls*/, jint maxEvents) {

    // Clamp to a safe range: must be positive and bounded.
    // Java always passes 256; the upper bound of 1024 handles backlog.
    if (maxEvents <= 0 || maxEvents > 1024) {
        maxEvents = 256;
    }

    // Stack-allocate a temporary buffer for the drained events.
    //
    // WHY STACK and not std::vector?
    //   Stack allocation is instantaneous (just move the stack pointer).
    //   std::vector would call malloc(), which acquires the heap lock and
    //   takes ~100ns. For a function called every 10ms, that overhead is
    //   irrelevant, but stack allocation is simpler and avoids the question
    //   of "what if malloc fails in the middle of a drain?"
    //
    // SIZE: 1024 × 88 bytes = 88 KB.
    //   Default Java thread stack: 512 KB – 1 MB on Windows x64.
    //   88 KB is safely within budget.
    //
    // WHY NOT maxEvents × sizeof(RawEvent) (a VLA)?
    //   Variable-length arrays (VLAs) are not standard C++ (they are in C99
    //   but not C++11/14/17). MSVC does not support them. Fixed size of 1024
    //   matches the clamped maxEvents upper bound.
    RawEvent events[1024];
    int count = 0;

    // Pop events from the ring buffer until we hit maxEvents or it's empty.
    // g_event_buffer.pop() is the consumer side of our SPSC ring buffer:
    //   - returns true and fills `ev` if an event is available
    //   - returns false immediately if the buffer is empty (no blocking)
    RawEvent ev{};
    while (count < maxEvents && g_event_buffer.pop(ev)) {
        events[count++] = ev;
    }

    if (count == 0) {
        return nullptr;  // Java checks for null and skips processing
    }

    // Allocate the Java byte array (count × 88 bytes) in the Java heap.
    const jsize total_bytes = static_cast<jsize>(count)
                            * static_cast<jsize>(sizeof(RawEvent));
    jbyteArray result = env->NewByteArray(total_bytes);
    if (result == nullptr) {
        // env->NewByteArray() returns null and sets OutOfMemoryError in the JVM.
        // Returning null here propagates the pending exception to Java.
        return nullptr;
    }

    // Copy our C++ struct bytes into the Java byte array.
    //
    // SetByteArrayRegion(array, start_offset, length, source_buffer)
    //   array         — the jbyteArray we just allocated
    //   0             — start writing at byte offset 0
    //   total_bytes   — write this many bytes
    //   source_buffer — pointer to our C++ data (reinterpreted as jbyte*)
    //
    // jbyte is "signed char" (a Java byte is signed, -128..127). Our struct
    // bytes are the same bit patterns as Java bytes when read at the right
    // offsets via ByteBuffer — the signedness only matters if you interpret
    // individual bytes as numbers, which Java's ByteBuffer.getInt() etc. handle
    // correctly by reading multiple bytes and combining them.
    env->SetByteArrayRegion(
        result,
        0,
        total_bytes,
        reinterpret_cast<const jbyte*>(events)
    );

    return result;
}
