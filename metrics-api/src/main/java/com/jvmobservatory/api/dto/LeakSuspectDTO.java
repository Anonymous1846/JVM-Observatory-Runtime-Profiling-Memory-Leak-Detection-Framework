package com.jvmobservatory.api.dto;

/**
 * Data transfer object for a memory leak suspect.
 *
 * <p>Represents a class flagged by the heap analyzer as potentially leaking memory.
 * The dashboard uses {@code leakProbability} and {@code severity} to color-code
 * and sort the leak suspects table.</p>
 *
 * @param className       fully-qualified class name of the suspect
 * @param leakProbability 0.0–1.0 confidence score from statistical analysis
 * @param growthRatio     ratio of current to baseline instance count (>1.0 = growing)
 * @param severity        "HIGH", "MEDIUM", or "LOW" based on probability thresholds
 * @param message         human-readable explanation of why this class is suspect
 */
public record LeakSuspectDTO(
        String className,
        double leakProbability,
        double growthRatio,
        String severity,
        String message
) {}
