package com.jvmobservatory.agent;

import java.lang.instrument.Instrumentation;

/**
 * Static holder for the {@link Instrumentation} reference provided by the JVM.
 *
 * WHY a static holder:
 * The JVM passes the Instrumentation instance to premain() exactly once. We need
 * it later (e.g., to retransform classes, calculate object sizes, or enumerate
 * loaded classes). Since premain() is a static method and the Instrumentation
 * reference must be accessible from multiple components, a static holder is the
 * simplest and most idiomatic approach.
 *
 * WHY volatile:
 * The Instrumentation is set by the JVM's agent initialization thread and read
 * by application threads. Volatile guarantees visibility across threads without
 * requiring synchronization — the write happens once at startup, and all
 * subsequent reads see the correct value.
 */
public class InstrumentationHolder {

    private static volatile Instrumentation instrumentation;

    /**
     * Store the Instrumentation reference. Called once from premain().
     *
     * @param inst the Instrumentation instance provided by the JVM
     */
    public static void set(Instrumentation inst) {
        instrumentation = inst;
    }

    /**
     * Retrieve the Instrumentation reference.
     *
     * @return the stored Instrumentation, or null if the agent was not loaded
     */
    public static Instrumentation get() {
        return instrumentation;
    }
}
