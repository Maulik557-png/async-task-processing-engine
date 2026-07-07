package com.poc.taskengine.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for retry-related beans.
 *
 * ── WHY A SEPARATE ScheduledExecutorService FOR RETRIES ─────────────────────
 *
 * Option A: Use the main taskExecutor for retry delays.
 *   Problem: The main taskExecutor is a ThreadPoolExecutor — it cannot schedule
 *   tasks to fire after a delay. ThreadPoolExecutor.execute() runs immediately;
 *   there is no execute(runnable, delay, unit) signature. Wrapping a sleep() inside
 *   the Runnable would work but blocks a main pool thread for the entire delay
 *   duration, consuming a thread that should be running real tasks.
 *
 * Option B: Use @Scheduled for retries.
 *   Problem: @Scheduled fires on a fixed calendar cadence (e.g., every 5 seconds).
 *   Retries need per-task variable delays based on retryCount:
 *     attempt 1: ~1047ms, attempt 2: ~2088ms, attempt 3: ~4031ms
 *   A fixed-cadence scheduler would need to store all pending retries, check each
 *   one on every tick, and fire only those whose delay has elapsed — essentially
 *   re-implementing ScheduledExecutorService manually.
 *
 * Option C (chosen): Dedicated ScheduledExecutorService.
 *   ScheduledExecutorService.schedule(runnable, delay, unit) does exactly what we need:
 *   "run this task once, after exactly this delay." The thread is not consumed during
 *   the wait — the delay is implemented via a platform timer, not a sleeping thread.
 *   When the timer fires, a pool thread picks up the retry Runnable and calls
 *   taskService.retryTask(), which completes in microseconds.
 *
 * ── WHY 2 THREADS ────────────────────────────────────────────────────────────
 * Each timer callback runs taskService.retryTask() — a fast, non-blocking operation
 * (~microseconds: acquire permit, update task, submit to pool). Two threads ensure
 * that two simultaneous retries don't serialize behind each other. More than 2
 * would be wasteful — retry callbacks are not I/O-bound and don't block.
 *
 * ── WHY NOT newSingleThreadScheduledExecutor() ───────────────────────────────
 * Single-thread pools are a common choice for background schedulers, but they create
 * a head-of-line blocking problem: if one retry callback stalls (e.g., awaiting a
 * Semaphore permit), the entire retry queue backs up. 2 threads eliminates that risk.
 */
@Slf4j
@Configuration
public class RetryConfig {

    @Value("${task.retry.base-delay-ms:1000}")
    private long baseDelayMs;

    @Value("${task.retry.max-jitter-ms:100}")
    private long maxJitterMs;

    @Value("${task.retry.permit-timeout-seconds:5}")
    private long permitTimeoutSeconds;

    @Bean(name = "retrySchedulerExecutor")
    public ScheduledExecutorService retrySchedulerExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "retry-scheduler-" + counter.incrementAndGet());
            t.setDaemon(true); // daemon: OK — retries should not prevent JVM shutdown
            return t;
        };
        return Executors.newScheduledThreadPool(2, factory);
    }

    /** Base delay (ms) for first retry. Doubles per retry attempt. */
    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    /** Maximum random jitter added to each retry delay (ms). */
    public long getMaxJitterMs() {
        return maxJitterMs;
    }

    /** Max seconds to block waiting for a Semaphore permit during retry re-submission. */
    public long getPermitTimeoutSeconds() {
        return permitTimeoutSeconds;
    }
}
