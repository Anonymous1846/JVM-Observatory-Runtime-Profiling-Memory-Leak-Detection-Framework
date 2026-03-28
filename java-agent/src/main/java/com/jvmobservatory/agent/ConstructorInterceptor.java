package com.jvmobservatory.agent;

import com.jvmobservatory.tracker.AllocationTracker;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} class for intercepting constructors.
 *
 * WHY @Advice vs @Delegation:
 * ByteBuddy offers two instrumentation strategies:
 *   - @Delegation: creates a proxy method that delegates to an interceptor object.
 *     This adds method dispatch overhead (virtual call + argument boxing).
 *   - @Advice: inlines the interceptor code directly into the target method's bytecode.
 *     No method call overhead — the advice code becomes part of the constructor itself.
 *
 * For allocation tracking, we instrument EVERY constructor in the application.
 * Even a tiny per-call overhead (like a virtual dispatch) multiplied by millions
 * of constructor calls per second becomes significant. @Advice eliminates this
 * by producing bytecode identical to what you'd write by hand.
 *
 * WHY static fields:
 * ByteBuddy @Advice methods are inlined into the target class at the bytecode level.
 * They execute in the context of the target class, NOT in the context of this class.
 * This means they cannot reference instance fields or non-static methods of
 * ConstructorInterceptor. Static fields are the only way to pass data between
 * the advice code and the outside world — they live on the class itself and are
 * accessible from any context.
 */
public class ConstructorInterceptor {

    private static final AllocationSampler sampler = new AllocationSampler();

    /**
     * Called BEFORE the constructor body executes.
     *
     * WHY we capture startNs in onEnter:
     * The sampling decision and timer start happen atomically before the constructor
     * runs. This ensures we measure the complete constructor duration and that the
     * sampling decision is made before any side effects occur. If we sampled in
     * onExit, we'd have already paid the cost of capturing the stack trace for
     * non-sampled allocations.
     *
     * @return the start time in nanoseconds, or -1 if this allocation should not be tracked
     */
    @Advice.OnMethodEnter
    public static long onEnter() {
        // Return -1 to signal "don't track this allocation" in onExit.
        // This avoids capturing expensive stack traces for non-sampled allocations.
        if (!sampler.shouldSample()) {
            return -1L;
        }
        return System.nanoTime();
    }

    /**
     * Called AFTER the constructor body completes (whether normally or via exception).
     *
     * @param startNs   the value returned by onEnter (-1 means "skip")
     * @param allocated the newly constructed object (available via @Advice.This)
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter long startNs,
            @Advice.This Object allocated) {
        if (startNs < 0) return; // was not sampled

        // Record the allocation with the tracker.
        // getClass().getName() gives us the concrete class (e.g., "java.util.ArrayList"),
        // not the declared type. This is critical for tracking which specific
        // implementation classes are being allocated.
        AllocationTracker.getInstance().record(
                allocated.getClass().getName(),
                Thread.currentThread().getStackTrace(),
                System.nanoTime() - startNs
        );
    }

    /**
     * Expose the sampler so that the agent can read/modify the sample rate at runtime.
     *
     * @return the allocation sampler used by this interceptor
     */
    public static AllocationSampler getSampler() {
        return sampler;
    }
}
