package com.jvmobservatory.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Demo application that generates allocation, GC, and thread activity
 * so the JVMTI agent and Java agent have real events to capture.
 *
 * Run with both agents:
 *   java -agentpath:native-agent/build/Release/jvm_observatory_agent.dll \
 *        -javaagent:java-agent/target/java-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        -jar demo-app/target/demo-app-1.0.0-SNAPSHOT.jar
 */
public class DemoApp {

    // Held in a static field so the GC can't collect them — simulates a leak
    private static final List<byte[]> leakyList = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[demo] JVM Observatory Demo App started.");
        System.out.println("[demo] Generating allocations, GC pressure, and threads...");
        System.out.println("[demo] Press Ctrl+C to stop.\n");

        int cycle = 0;

        while (true) {
            cycle++;

            // ── Allocation churn: create short-lived objects ──
            allocateChurn();

            // ── Simulated leak: accumulate ~50KB per cycle ──
            leakyList.add(new byte[50 * 1024]);

            // ── Spawn a worker thread every 10 cycles ──
            if (cycle % 10 == 0) {
                spawnWorker(cycle);
            }

            // ── Force a GC every 20 cycles ──
            if (cycle % 20 == 0) {
                System.out.printf("[demo] Cycle %d — forcing GC. Leak list size: %d entries (~%d KB)%n",
                        cycle, leakyList.size(), leakyList.size() * 50);
                System.gc();
            }

            Thread.sleep(200); // ~5 cycles/sec
        }
    }

    /**
     * Creates short-lived objects to generate allocation events.
     * The GC will reclaim these quickly — they represent normal churn.
     */
    private static void allocateChurn() {
        Map<String, List<Integer>> temp = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            String key = "key-" + ThreadLocalRandom.current().nextInt(50);
            temp.computeIfAbsent(key, k -> new ArrayList<>())
                .add(ThreadLocalRandom.current().nextInt());
        }
    }

    /**
     * Spawns a short-lived thread to generate ThreadStart JVMTI events.
     */
    private static void spawnWorker(int cycle) {
        Thread worker = new Thread(() -> {
            // Simulate some work
            double sum = 0;
            for (int i = 0; i < 10_000; i++) {
                sum += Math.sqrt(i);
            }
        }, "demo-worker-" + cycle);
        worker.setDaemon(true);
        worker.start();
    }
}
