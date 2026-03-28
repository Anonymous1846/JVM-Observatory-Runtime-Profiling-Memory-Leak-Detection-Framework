// ring_buffer.cpp — Why is this file almost empty?
// ─────────────────────────────────────────────────────────────────────────────
//
// ══ THE C++ TEMPLATE RULE ════════════════════════════════════════════════════
//
// RingBuffer<T, Capacity> is a class template. Templates are different from
// regular classes in one fundamental way:
//
//   Regular class (Foo):
//     foo.h   → class declaration (method signatures only)
//     foo.cpp → method definitions (actual code)
//     Compiler sees foo.h everywhere, foo.cpp compiled once.
//
//   Template class (RingBuffer<T, Capacity>):
//     ring_buffer.h → BOTH declaration AND definition (full method bodies)
//     ring_buffer.cpp → (almost nothing)
//
// WHY? The compiler generates actual machine code for a template only when
// it sees the specific type substitution — the "instantiation":
//
//   RingBuffer<RawEvent, 4096>  ← instantiation with T=RawEvent, Capacity=4096
//
// To generate machine code for push() and pop() with T=RawEvent, the compiler
// needs to see the FULL source of push() and pop() in the same translation unit
// that performs the instantiation. The instantiation happens in agent.cpp and
// jvmti_events.cpp (both include agent.h which includes ring_buffer.h).
//
// If we put push() and pop() in ring_buffer.cpp instead, then when compiling
// agent.cpp the compiler would see only the DECLARATIONS (from ring_buffer.h)
// but not the DEFINITIONS. It would generate a placeholder "call to push()"
// and hope the linker finds the code in another .obj file. But ring_buffer.cpp
// would not contain instantiated code for T=RawEvent either — it would only
// contain an uninstantiated template that the linker cannot use.
// Result: linker error "unresolved external symbol RingBuffer<RawEvent,4096>::push"
//
// ══ THE CANONICAL SOLUTION ════════════════════════════════════════════════════
//
// Put EVERYTHING (declaration + definition) in the header file.
// Every .cpp that includes ring_buffer.h gets a complete copy of the template
// source. Each instantiation produces its own .obj-file code. The linker
// deduplicates them (via COMDAT folding on Windows, weak symbols on Linux).
//
// ══ THE ALTERNATIVE (explicit instantiation) ══════════════════════════════════
//
// There IS a way to split templates across .h and .cpp:
//   1. Put method declarations in ring_buffer.h (as now)
//   2. Put method DEFINITIONS in ring_buffer.cpp
//   3. Add this line at the bottom of ring_buffer.cpp:
//        template class RingBuffer<RawEvent, 4096>;   ← explicit instantiation
//   4. Add this line in ring_buffer.h (outside the class):
//        extern template class RingBuffer<RawEvent, 4096>;  ← suppress other instantiations
//
// This works but creates a tight coupling: ring_buffer.h would need to know
// about RawEvent (creating a circular dependency). The header-only approach
// is simpler for this project.
//
// ══ WHY DOES THIS FILE EXIST AT ALL? ═════════════════════════════════════════
//
// CMakeLists.txt lists ring_buffer.cpp as a source file. This serves two
// purposes:
//   1. Makes the project structure explicit ("ring_buffer has a .cpp file
//      like all other modules") — easier for newcomers to navigate.
//   2. The static_assert below gives the compiler one extra opportunity to
//      validate the template instantiation in isolation.
//
// This translation unit contributes no object code to jvm_observatory_agent.dll.
// ─────────────────────────────────────────────────────────────────────────────

#include "agent.h"  // transitively includes ring_buffer.h, defines RawEvent

// Validate that the template instantiates cleanly with our specific types.
// This runs the static_asserts inside RingBuffer<> (power-of-2 check, etc.)
// and catches any template-substitution failures at compile time, isolated
// from agent.cpp and jvmti_events.cpp. A build error here points directly
// to the ring buffer, not to a call site in a callback function.
static_assert(
    RingBuffer<RawEvent, EVENT_BUFFER_CAPACITY>::CAPACITY == EVENT_BUFFER_CAPACITY,
    "Ring buffer capacity constant is inconsistent — check EVENT_BUFFER_CAPACITY "
    "in agent.h and the RingBuffer template in ring_buffer.h"
);
