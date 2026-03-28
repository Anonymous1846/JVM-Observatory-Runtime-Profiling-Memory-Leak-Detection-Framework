// agent.h — Central header for the JVM Observatory native agent
// ─────────────────────────────────────────────────────────────────────────────
//
// Included by: agent.cpp, jvmti_events.cpp
//
// Defines:
//   1. Windows compatibility macros (#ifndef-guarded to prevent C4005 errors)
//   2. EventType enum  — identifies which JVMTI event triggered a record
//   3. RawEvent struct — the fixed binary layout shared between C++ and Java
//   4. Global declarations — g_jvmti, g_event_buffer (defined in agent.cpp)
//   5. now_ns()        — high-resolution timestamp (implemented in agent.cpp)
//   6. JVMTI callback forward declarations (implemented in jvmti_events.cpp)
//
// DESIGN NOTE FOR JAVA DEVELOPERS:
//   In C++, global variables must be DECLARED in headers (so every .cpp that
//   includes the header knows the variable exists) but DEFINED in exactly one
//   .cpp file (where the memory is actually allocated). "extern" in a header
//   is the declaration; the definition in agent.cpp omits "extern". This is
//   the C++ equivalent of a public static field: declared in many places,
//   allocated in one.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

// ─── Windows compatibility macros ─────────────────────────────────────────────
//
// These MUST be defined before including <windows.h>.
//
// WHY #ifndef GUARDS?
//   CMakeLists.txt passes WIN32_LEAN_AND_MEAN and NOMINMAX as /D compiler
//   flags (equivalent to -D on Linux). If this header also has plain #define
//   for the same macros, MSVC emits warning C4005 ("macro redefinition").
//   With /WX (warnings as errors), C4005 becomes a build-stopping error.
//   The #ifndef guard says "only define this if it has not already been
//   defined" — by the compiler's /D flag or by another header included first.
//
// WIN32_LEAN_AND_MEAN:
//   windows.h normally includes dozens of sub-headers. This macro excludes
//   rarely-used ones (Cryptography, DDE, RPC, Shell, Winsock...), which:
//     - Speeds up compilation
//     - Avoids macro name collisions (e.g., windows.h defines ERROR and BOOL)
//
// NOMINMAX:
//   windows.h defines min() and max() as macros unless NOMINMAX is set.
//   These macros silently override std::min and std::max, causing cryptic
//   template errors deep in STL headers. Always define NOMINMAX.
#ifdef _WIN32
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  include <windows.h>  // LARGE_INTEGER, QueryPerformanceCounter, GetCurrentThreadId
#endif

// ─── JNI and JVMTI headers ────────────────────────────────────────────────────
//
// These headers are inside the JDK installation, not in the system include path.
// CMakeLists.txt uses find_package(JNI REQUIRED) to locate them and adds the
// paths via target_include_directories, so the compiler can find them by the
// short names below.
//
// <jvmti.h>  — JVMTI C API:
//               jvmtiEnv*          — the agent's handle to all JVMTI functions
//               jvmtiCapabilities  — opt-in event permissions
//               jvmtiEventCallbacks — struct of callback function pointers
//               jvmtiEvent         — enum of event type codes
//               JVMTI_VERSION_11   — API version constant
//               JVMTI_ERROR_NONE   — success code
//
// <jni.h>    — JNI C API (jvmti.h already includes this, but being explicit
//               documents that we use JNI types like JNIEnv*, jbyteArray, etc.)
#include <jvmti.h>
#include <jni.h>

// ─── Standard library headers ─────────────────────────────────────────────────
#include <cstdint>   // uint8_t, uint32_t, uint64_t — exact-width integer types
#include <cstddef>   // std::size_t

// ─── Our ring buffer ───────────────────────────────────────────────────────────
// Included here (rather than only in agent.cpp) because this header declares
// g_event_buffer using the RingBuffer<> template — both agent.cpp and
// jvmti_events.cpp need to know the full type to call push()/pop() on it.
#include "ring_buffer.h"

// ─── EventType ────────────────────────────────────────────────────────────────
//
// Identifies which JVMTI event produced a RawEvent record.
// Using uint8_t as the underlying type so the enum fits in the first byte of
// RawEvent (offset 0) with no wasted space.
//
// "enum class" (scoped enum): unlike plain "enum", you must write
// EventType::ALLOC instead of just ALLOC. This prevents name collisions and
// makes the code more readable. Java developers: think of it like a Java enum.
//
// Java reads this value with:
//   byte typeCode = buf.get(eventOffset + 0);
//   if (typeCode == 1) { /* ALLOC event */ }
enum class EventType : uint8_t {
    ALLOC         = 1,  // VMObjectAlloc: object created via JNI or Reflection
    GC_START      = 2,  // GarbageCollectionStart: GC cycle has begun
    GC_FINISH     = 3,  // GarbageCollectionFinish: GC cycle has ended
    THREAD_START  = 4,  // ThreadStart: new Java thread has become runnable
};

// ─── RawEvent struct ──────────────────────────────────────────────────────────
//
// ══ BINARY CONTRACT WITH JAVA ════════════════════════════════════════════════
//
// This struct has a FIXED, EXACT memory layout. Java reads it byte-by-byte
// using ByteBuffer at hard-coded offsets. DO NOT:
//   - Reorder fields (changes offsets)
//   - Add fields between existing ones (shifts offsets)
//   - Remove _pad (implicit compiler padding is unpredictable across platforms)
//   - Change field types (changes sizes and therefore offsets)
//
// If you must add new fields, append them at the END and update the Java side.
//
// ══ MEMORY LAYOUT (88 bytes) ══════════════════════════════════════════════════
//
//   Offset  0 │ type         │ uint8_t    │  1 byte  │ EventType code
//   Offset  1 │ _pad[3]      │ uint8_t[3] │  3 bytes │ explicit alignment padding
//   Offset  4 │ thread_id    │ uint32_t   │  4 bytes │ OS thread ID
//   Offset  8 │ timestamp_ns │ uint64_t   │  8 bytes │ nanoseconds since boot
//   Offset 16 │ size_bytes   │ uint64_t   │  8 bytes │ bytes allocated (ALLOC only)
//   Offset 24 │ class_sig    │ char[64]   │ 64 bytes │ JNI class signature
//             └──────────────┴────────────┴──────────┘
//   Total: 1 + 3 + 4 + 8 + 8 + 64 = 88 bytes
//
// ══ WHY EXPLICIT PADDING? ════════════════════════════════════════════════════
//
//   Without _pad[3], the compiler would insert 3 bytes of "implicit padding"
//   between type and thread_id to align thread_id (uint32_t) to a 4-byte
//   boundary. The struct would still be 88 bytes, BUT:
//     - The padding is invisible in code — a maintenance trap
//     - Another developer might insert a field "before thread_id" and be
//       confused why sizeof(RawEvent) didn't change
//     - The Java documentation for "offset 1: 3 bytes of padding" would
//       look like a mistake
//   Explicit padding self-documents the layout and prevents accidents.
//
// ══ WHY NO #pragma pack? ══════════════════════════════════════════════════════
//
//   #pragma pack(1) removes ALL implicit padding. We don't need it here
//   because our explicit _pad[3] ensures every field falls on its natural
//   alignment boundary already. The static_assert below is our safety net:
//   if the layout ever changes, the build fails immediately.
//
// ══ JAVA READING EXAMPLE ══════════════════════════════════════════════════════
//
//   byte[] raw = NativeEventBridge.drainEvents(256);
//   ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
//   int offset = 0; // position of first event
//
//   byte     type      = buf.get(offset + 0);          // EventType code
//   // skip _pad[3] at offset 1..3
//   int      threadId  = buf.getInt(offset + 4);       // OS thread ID
//   long     tsNs      = buf.getLong(offset + 8);      // timestamp
//   long     sizeBytes = buf.getLong(offset + 16);     // alloc size
//   String   classSig  = new String(raw, offset + 24, 64, StandardCharsets.UTF_8)
//                           .trim();                   // e.g. "Ljava/util/ArrayList;"
//   offset += 88; // advance to next event
//
struct RawEvent {
    uint8_t  type;           // offset  0 — EventType value (cast from EventType enum)
    uint8_t  _pad[3];        // offset  1 — explicit padding to align thread_id to 4 bytes
    uint32_t thread_id;      // offset  4 — OS thread ID (GetCurrentThreadId on Windows)
    uint64_t timestamp_ns;   // offset  8 — nanoseconds from now_ns()
    uint64_t size_bytes;     // offset 16 — allocated bytes (ALLOC events only; 0 otherwise)
    char     class_sig[64];  // offset 24 — null-terminated JNI class signature
                             //             e.g. "Ljava/util/ArrayList;"
                             //             e.g. "[B" (byte array)
                             //             empty string for GC and THREAD events
};

// ── Safety net ────────────────────────────────────────────────────────────────
// If this assertion fails, the struct layout has drifted from the Java reader.
// Fix the struct — do not remove this assertion.
// The message appears in the compiler error output if the assert fails.
static_assert(sizeof(RawEvent) == 88,
    "RawEvent must be exactly 88 bytes. Java reads it at fixed byte offsets. "
    "A size mismatch means the layout has changed — update the Java ByteBuffer "
    "reader to match, then adjust this assertion.");

// ─── Ring buffer configuration ─────────────────────────────────────────────────
//
// 4096 slots × 88 bytes/slot = 352 KB of static memory.
//
// WHY 4096?
//   At 10ms drain intervals, a busy JVM might generate:
//     - Thousands of small allocations per second via JNI/Reflection
//     - 1–5 GC cycles per second (each = 2 events)
//     - Dozens of new threads per second
//   4096 slots provides ample headroom for a 10ms burst without dropping events.
//   If the ring buffer fills up, events are silently dropped — which is
//   acceptable for a monitoring tool. Increase to 8192 or 16384 if needed.
//
// WHY A POWER OF 2?
//   Required by RingBuffer<> (see ring_buffer.h) for fast index wrapping
//   using bitwise AND instead of expensive modulo.
static constexpr std::size_t EVENT_BUFFER_CAPACITY = 4096;

// ─── Global variable declarations ─────────────────────────────────────────────
//
// "extern" = "this variable EXISTS somewhere else — here is its type and name."
// The DEFINITION (actual memory allocation) is in agent.cpp.
//
// Both agent.cpp and jvmti_events.cpp include this header, so both can access
// these globals. The linker connects them at link time.
//
// In Java terms: think of these as "static fields of a class" — declared once,
// shared across all code in the module.
extern jvmtiEnv*                                    g_jvmti;         // JVMTI API handle, set in Agent_OnLoad
extern RingBuffer<RawEvent, EVENT_BUFFER_CAPACITY>  g_event_buffer;  // shared lock-free event queue

// ─── Utility function declaration ─────────────────────────────────────────────
//
// Returns the current time in nanoseconds (monotonically increasing).
// Implemented in agent.cpp.
//   Windows: QueryPerformanceCounter (~100ns resolution)
//   Linux:   clock_gettime(CLOCK_MONOTONIC) (~1ns resolution)
//
// noexcept: safe to call from JVMTI callbacks (no exceptions).
uint64_t now_ns() noexcept;

// ─── JVMTI callback forward declarations ──────────────────────────────────────
//
// These functions are IMPLEMENTED in jvmti_events.cpp.
// They are DECLARED here so agent.cpp can reference them when filling in
// the jvmtiEventCallbacks struct during Agent_OnLoad.
//
// WHY extern "C"?
//   C++ compilers "mangle" function names to encode their parameter types
//   into the symbol name (e.g., OnVMObjectAlloc becomes something like
//   "_Z14OnVMObjectAllocP8JvmtiEnvP7JNIEnv_..."). This is how C++ handles
//   function overloading.
//
//   extern "C" disables name mangling and uses the plain C name instead.
//   This is required because:
//     1. We assign these functions to function-pointer fields in a C struct
//        (jvmtiEventCallbacks), which expects plain C naming.
//     2. It makes the symbols readable in debugging tools (nm, dumpbin).
//
// WHY JNICALL?
//   JNICALL specifies the calling convention:
//     - On 64-bit Windows: __cdecl (same as default — effectively a no-op)
//     - On 32-bit Windows: __stdcall (arguments passed right-to-left on stack)
//   Always include JNICALL for portability, even on x64 where it's redundant.
extern "C" {

    // Fires when an object is allocated via JNI or Reflection.
    // JNIEnv* jni is VALID here — can call GetClassSignature, etc.
    void JNICALL OnVMObjectAlloc(
        jvmtiEnv* jvmti,
        JNIEnv*   jni,
        jthread   thread,
        jobject   object,
        jclass    object_klass,
        jlong     size
    );

    // Fires at the START of a GC cycle. JNIEnv* is NOT available.
    // Can only perform: stack allocation, atomic ring buffer push, now_ns().
    void JNICALL OnGarbageCollectionStart(jvmtiEnv* jvmti);

    // Fires at the END of a GC cycle. Same restrictions as GCStart.
    void JNICALL OnGarbageCollectionFinish(jvmtiEnv* jvmti);

    // Fires when a new Java thread becomes runnable (after Thread.start()).
    // JNIEnv* jni is VALID here.
    void JNICALL OnThreadStart(
        jvmtiEnv* jvmti,
        JNIEnv*   jni,
        jthread   thread
    );

} // extern "C"
