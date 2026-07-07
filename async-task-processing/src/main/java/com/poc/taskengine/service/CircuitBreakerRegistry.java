package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks consecutive failures per TaskType and emits a warning when a type
 * reaches the circuit-breaker threshold.
 *
 * ── WHAT THIS IS (AND WHAT IT IS NOT) ────────────────────────────────────────
 * This is a LOG-ONLY circuit breaker. It does not actually stop task submissions.
 * Its purpose is to demonstrate the concept and provide an observable signal:
 * "Something is consistently wrong with EMAIL_NOTIFICATION tasks — investigate."
 *
 * A production circuit breaker (e.g., Resilience4j) would additionally:
 *   - Enter an OPEN state that rejects new submissions for a cooldown period.
 *   - Transition to HALF-OPEN to probe recovery with limited traffic.
 *   - Transition to CLOSED (normal) once the probe succeeds.
 *
 * ── WHY PER-TYPE COUNTERS (not per-taskId) ───────────────────────────────────
 * A circuit breaker exists to detect systemic failure in a downstream dependency,
 * not task-specific failure. If the email service is down, ALL EMAIL_NOTIFICATION
 * tasks will fail — the pattern is visible only at the type level.
 * Individual task failures (e.g., malformed payload) are handled by retry logic;
 * the circuit breaker fires when retries consistently don't help either.
 *
 * ── WHY "CONSECUTIVE" FAILURES, NOT TOTAL ────────────────────────────────────
 * A window of 10 consecutive failures means the last 10 completed tasks of this
 * type all failed. A success in between resets the counter, indicating recovery.
 * Counting total failures would never reset — a type that was flaky 100 tasks ago
 * would still look broken, even if it has been healthy for the last 50 tasks.
 *
 * ── WHY AtomicInteger (not a synchronized int) ───────────────────────────────
 * recordSuccess and recordFailure are called from worker threads, potentially
 * concurrently for the same TaskType. AtomicInteger.incrementAndGet() is a
 * single atomic CAS instruction — no lock, no contention, correct under concurrent
 * access. A plain int field would require synchronization; AtomicInteger gives us
 * the same guarantee without the lock overhead.
 *
 * ── WHY ConcurrentHashMap FOR THE COUNTER REGISTRY ──────────────────────────
 * computeIfAbsent is atomic at the bin level: two threads racing to insert the
 * counter for the same TaskType both get the same AtomicInteger instance.
 */
@Slf4j
@Component
public class CircuitBreakerRegistry {

    private static final int FAILURE_THRESHOLD = 10;

    private final ConcurrentHashMap<TaskType, AtomicInteger> consecutiveFailures =
            new ConcurrentHashMap<>();

    /**
     * Record a successful task completion for the given type.
     * Resets the consecutive failure counter for that type to zero.
     *
     * @param type the type of the task that succeeded
     */
    public void recordSuccess(TaskType type) {
        AtomicInteger counter = consecutiveFailures.computeIfAbsent(type, t -> new AtomicInteger(0));
        int previous = counter.getAndSet(0);
        if (previous > 0) {
            log.debug("Circuit breaker for {} reset to 0 (was {} consecutive failures)", type, previous);
        }
    }

    /**
     * Record a permanent task failure (all retries exhausted) for the given type.
     * Increments the consecutive failure counter and emits a WARNING if the
     * threshold is reached or exceeded.
     *
     * @param type the type of the task that permanently failed
     */
    public void recordFailure(TaskType type) {
        int count = consecutiveFailures.computeIfAbsent(type, t -> new AtomicInteger(0))
                .incrementAndGet();

        if (count >= FAILURE_THRESHOLD) {
            // Emit at WARN level so monitoring alert rules can trigger on this pattern.
            log.warn(
                    "CIRCUIT BREAKER THRESHOLD REACHED — {} consecutive permanent failures " +
                    "for {}. Consider pausing submissions of this type.",
                    count, type
            );
        } else {
            log.debug("Consecutive permanent failure count for {}: {}/{}",
                    type, count, FAILURE_THRESHOLD);
        }
    }

    /**
     * Returns the current consecutive failure count for a task type.
     * Used in tests to assert circuit-breaker state without log parsing.
     */
    public int getConsecutiveFailures(TaskType type) {
        return consecutiveFailures.getOrDefault(type, new AtomicInteger(0)).get();
    }
}
