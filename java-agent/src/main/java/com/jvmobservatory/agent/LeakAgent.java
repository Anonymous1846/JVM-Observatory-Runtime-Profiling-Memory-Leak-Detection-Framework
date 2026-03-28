package com.jvmobservatory.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point — the JVM calls premain() before the application's main().
 *
 * This is the equivalent of Agent_OnLoad in C++: the JVM gives us an Instrumentation
 * handle that lets us rewrite class bytecode at load time. We use ByteBuddy to inject
 * allocation tracking into every constructor.
 *
 * WHY premain (not agentmain):
 * premain runs BEFORE the application starts, so we can intercept classes as they
 * load for the first time. agentmain attaches to an already-running JVM and requires
 * retransformation, which is more fragile and can't instrument classes already loaded
 * by the bootstrap classloader.
 */
public class LeakAgent {

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[java-agent] LeakAgent starting via premain...");

        // Store the Instrumentation for later use (e.g., object sizing, class enumeration)
        InstrumentationHolder.set(inst);

        // Start the native event drain thread (polls the C++ ring buffer every 10ms)
        NativeEventBridge.startDrainThread();

        // Install ByteBuddy instrumentation: intercept all constructors
        new AgentBuilder.Default()
                // Ignore JDK internals and ByteBuddy's own classes to avoid infinite loops
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .ignore(ElementMatchers.nameStartsWith("jdk.internal."))
                .ignore(ElementMatchers.nameStartsWith("sun."))
                // Match all types (the sampler inside ConstructorInterceptor decides what to track)
                .type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ConstructorInterceptor.class)
                                .on(ElementMatchers.isConstructor()))
                )
                // Use retransformation strategy so we can re-instrument classes later if needed
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .installOn(inst);

        System.out.println("[java-agent] ByteBuddy instrumentation installed.");
    }
}
