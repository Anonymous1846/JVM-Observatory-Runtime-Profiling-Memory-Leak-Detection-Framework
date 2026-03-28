// ring_buffer.h — Lock-free Single-Producer / Single-Consumer ring buffer
// ─────────────────────────────────────────────────────────────────────────────
//
// ══ WHAT THIS IS ═════════════════════════════════════════════════════════════
//
// A ring buffer is a fixed-size circular array. Think of it like a conveyor
// belt with a fixed number of slots:
//
//   [ slot 0 | slot 1 | slot 2 | ... | slot N-1 ]
//              ↑ tail (consumer reads here)
//                        ↑ head (producer writes here)
//
// The producer (writer) advances head_ forward.
// The consumer (reader) advances tail_ forward.
// When either reaches the end of the array, it wraps back to slot 0.
//
// ══ WHY LOCK-FREE? ═══════════════════════════════════════════════════════════
//
// "Lock-free" means: no mutex, no condition variable, no spin-wait.
// Synchronization is done purely with C++ atomic load/store.
//
// This is REQUIRED for JVMTI callbacks. You CANNOT take a mutex inside a
// JVMTI callback: if the JVM is mid-GC, your mutex might already be held
// by another JVM thread, causing an instant deadlock. With lock-free atomics,
// the callback always makes forward progress in ~20ns.
//
// ══ WHY SPSC (Single-Producer / Single-Consumer)? ════════════════════════════
//
// Our usage pattern is exactly:
//   - ONE producer:  JVMTI callbacks write events (multiple JVM threads
//                    funnel through this one buffer, BUT the ring buffer
//                    is written from one logical stream — see NOTE below)
//   - ONE consumer:  Java drain thread reads events via drainEvents() JNI
//
// SPSC buffers need only TWO atomic variables (head_, tail_). MPSC or MPMC
// buffers require CAS loops and are 5–10x more complex. We use SPSC here for
// correctness of the data structure itself; event ordering across threads is
// handled at the Java analysis layer, not here.
//
// NOTE ON "SINGLE PRODUCER":
//   Strictly, JVMTI callbacks can fire on different JVM threads simultaneously.
//   A true SPSC buffer assumes only one thread calls push() at a time.
//   If your JVM workload is heavily multi-threaded, you may see occasional
//   lost events due to race conditions on head_. For a monitoring tool (not
//   a correctness tool), occasional dropped events are acceptable.
//   A future upgrade path: promote to MPSC by changing push() to use a
//   compare-and-swap (CAS) loop on head_.
//
// ══ WHY A TEMPLATE? ══════════════════════════════════════════════════════════
//
// The ring buffer logic is identical regardless of what you store in it.
// Making it a template lets the compiler generate optimized code for each
// specific type (T=RawEvent, Capacity=4096) at compile time.
//
// C++ TEMPLATE RULE: Because the compiler needs the full method bodies to
// generate code for each instantiation, template implementations MUST live
// in the header file (not in a .cpp file). This is why all push()/pop()
// code is here, not in ring_buffer.cpp. ring_buffer.cpp exists in CMakeLists
// but contributes no object code — see that file for a full explanation.
//
// ══ MEMORY ORDERING PRIMER (for Java developers) ═════════════════════════════
//
// Java's volatile keyword guarantees that writes are visible to other threads.
// C++ atomics are similar but give you fine-grained control:
//
//   memory_order_relaxed — No ordering guarantee. Just "do the atomic op."
//                          Fastest. Only safe when ordering doesn't matter.
//
//   memory_order_acquire — "I am about to READ shared data. Make sure I see
//                          all writes that happened before the matching
//                          release on the other side."
//
//   memory_order_release — "I have finished WRITING shared data. Signal to
//                          anyone doing an acquire-load that all my previous
//                          writes are now visible."
//
// The acquire/release pair forms a "happens-before" relationship:
//   producer: write buf_[head]           → head_.store(next, release)
//   consumer: tail == head_.load(acquire) → read buf_[tail]
//   Guarantee: the consumer sees buf_[head] fully written.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <atomic>    // std::atomic — our only synchronization primitive
#include <array>     // std::array  — fixed-size, no heap allocation
#include <cstddef>   // std::size_t

// ─────────────────────────────────────────────────────────────────────────────
// RingBuffer<T, Capacity>
//
// T        — element type. Must be trivially copyable (raw structs are fine).
// Capacity — number of slots. MUST be a power of 2 (enforced at compile time).
//
// Usage:
//   RingBuffer<RawEvent, 4096> buf;
//   buf.push(ev);   // producer side (JVMTI callback)
//   buf.pop(ev);    // consumer side (Java drain thread)
// ─────────────────────────────────────────────────────────────────────────────
template<typename T, std::size_t Capacity>
class RingBuffer {

    // ── Compile-time constraints ──────────────────────────────────────────────
    //
    // Power-of-2 capacity is REQUIRED. It lets us replace the slow modulo
    // operator (%) with a fast bitwise AND (&):
    //
    //   (index + 1) % Capacity       ← requires a division instruction (~20 cycles)
    //   (index + 1) & (Capacity - 1) ← single AND instruction (~1 cycle)
    //
    // This matters because push() and pop() are called millions of times per
    // second in a production JVM.
    static_assert(
        (Capacity & (Capacity - 1)) == 0,
        "RingBuffer Capacity must be a power of 2 (e.g. 256, 1024, 4096)"
    );
    static_assert(Capacity >= 2, "RingBuffer Capacity must be at least 2");

    // MASK = Capacity - 1 in binary is all 1s below the capacity bit.
    // Example for Capacity=4096 (0x1000): MASK = 4095 (0x0FFF).
    // x & MASK == x % Capacity when Capacity is a power of 2.
    static constexpr std::size_t MASK = Capacity - 1;

public:
    static constexpr std::size_t CAPACITY = Capacity;

    // ── push ──────────────────────────────────────────────────────────────────
    // Called by the PRODUCER (JVMTI callback thread).
    //
    // Thread safety: safe for ONE producer thread at a time (SPSC).
    // Returns false if the buffer is full — the event is silently dropped.
    // Dropping is INTENTIONAL: blocking or throwing inside a JVMTI callback
    // would either deadlock the JVM or violate the callback contract.
    // A monitoring tool losing 0.1% of events is fine; a deadlock is not.
    //
    // noexcept: no exceptions can propagate out of a JVMTI callback.
    bool push(const T& item) noexcept {
        // We are the only writer to head_, so reading it with relaxed is safe.
        // "relaxed" here means: just load the value, don't add any fence.
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t next = (head + 1) & MASK;

        // Check if the buffer is full.
        // Full condition: next == tail_ (the slot head_ would write to is
        // currently occupied by the consumer).
        //
        // acquire: we must see the consumer's most recent tail_ write before
        // deciding whether the buffer is full. Without acquire here, we might
        // read a stale tail_ value and incorrectly think the buffer is full.
        if (next == tail_.load(std::memory_order_acquire)) {
            return false;  // buffer full — caller should not block, just drop
        }

        buf_[head] = item;  // write the element into the slot

        // release: signal to the consumer that buf_[head] is fully written.
        // Any consumer that loads head_ with acquire is guaranteed to see
        // the complete element we just wrote to buf_[head].
        // This is the "publish" step: head_ advancing = "slot head is ready."
        head_.store(next, std::memory_order_release);
        return true;
    }

    // ── pop ───────────────────────────────────────────────────────────────────
    // Called by the CONSUMER (Java drain thread via JNI).
    //
    // Thread safety: safe for ONE consumer thread at a time (SPSC).
    // Returns false if the buffer is empty (nothing to drain).
    //
    // noexcept: this can be called from JNI — no C++ exceptions across JNI.
    bool pop(T& item) noexcept {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);

        // Check if the buffer is empty.
        // Empty condition: tail == head_ (no unread elements).
        //
        // acquire: we must see the producer's release-store on head_ AND
        // the element write that happened before it. Without acquire, we
        // might read buf_[tail] before the producer has finished writing it.
        if (tail == head_.load(std::memory_order_acquire)) {
            return false;  // buffer empty
        }

        item = buf_[tail];  // read the element from the slot

        // release: signal to the producer that we have consumed this slot.
        // The producer reads tail_ with acquire in push() to check if full.
        tail_.store((tail + 1) & MASK, std::memory_order_release);
        return true;
    }

    // ── size_approx ───────────────────────────────────────────────────────────
    // Returns the APPROXIMATE number of elements currently in the buffer.
    // "Approximate" because in a concurrent system, this count can change
    // between the time you read it and the time you act on it.
    // Useful for diagnostics and logging — do not use for control flow.
    std::size_t size_approx() const noexcept {
        const std::size_t head = head_.load(std::memory_order_acquire);
        const std::size_t tail = tail_.load(std::memory_order_acquire);
        // Unsigned subtraction wraps correctly for ring buffer indices.
        // Example: head=2, tail=4094, Capacity=4096
        //   (2 - 4094) in size_t = 2^64 - 4092  (unsigned wrap, defined in C++)
        //   (2^64 - 4092) & 4095 = 4  ← correct! 4 elements in buffer.
        return (head - tail) & MASK;
    }

private:
    // ── Cache-line isolation: preventing false sharing ────────────────────────
    //
    // Modern CPUs transfer memory between L1 cache and main memory in chunks
    // called "cache lines" — typically 64 bytes on x86-64.
    //
    // FALSE SHARING: if head_ and tail_ were on the SAME cache line:
    //   1. Producer (on CPU core A) writes head_  → marks the cache line dirty
    //   2. Consumer (on CPU core B) reads tail_   → must fetch the cache line
    //      from core A first (cross-core cache coherence protocol)
    //   3. Repeat every push/pop → 100–200ns penalty instead of ~1ns
    //
    // FIX: alignas(64) forces each variable to START at a 64-byte boundary.
    // The compiler inserts padding to achieve this. MSVC warns about this
    // with C4324 ("structure was padded due to alignment specifier"), which
    // is why CMakeLists.txt adds /wd4324 to suppress it — the padding is
    // intentional and correct.
    //
    // After alignment:
    //   head_ occupies cache line N     (written only by the producer)
    //   tail_ occupies cache line N+1   (written only by the consumer)
    //   Each core only invalidates its own cache line.
    alignas(64) std::atomic<std::size_t> head_{0};  // next slot to WRITE (producer)
    alignas(64) std::atomic<std::size_t> tail_{0};  // next slot to READ  (consumer)

    // The storage array. std::array is a fixed-size array with no heap
    // allocation. Because RingBuffer<RawEvent, 4096> is declared as a global
    // in agent.cpp, this array lives in the .bss segment — zero-initialized
    // static storage allocated once when the DLL loads, no malloc() needed.
    //
    // Size: 4096 × sizeof(RawEvent) = 4096 × 88 bytes = 352 KB
    std::array<T, Capacity> buf_{};
};
