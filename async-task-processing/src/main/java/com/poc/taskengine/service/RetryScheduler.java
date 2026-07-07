package com.poc.taskengine.service;

import com.poc.taskengine.config.RetryConfig;
import com.poc.taskengine.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Schedules retry attempts for failed tasks using exponential backoff with jitter.
 *
 * ── RETRY TIMING FORMULA ─────────────────────────────────────────────────────
 *
 *   waitTime = baseDelay * (2 ^ retryCount) + random(0, maxJitter)
 *
 * With baseDelay=1000ms, maxJitter=100ms:
 *   Attempt 0 failed → retry 1 in: 1000 + [0..100] ms  ≈ 1050ms
 *   Attempt 1 failed → retry 2 in: 2000 + [0..100] ms  ≈ 2060ms
 *   Attempt 2 failed → retry 3 in: 4000 + [0..100] ms  ≈ 4025ms
 *
 * ── WHY JITTER IS NOT OPTIONAL ──────────────────────────────────────────────
 * Scenario: 50 tasks all fail simultaneously at t=0 because a downstream
 * service returned 500. Without jitter:
 *   t=1000ms: all 50 tasks retry simultaneously → 50 requests hit the
 *             recovering service at once → the service is re-overloaded.
 *   t=2000ms: all 50 tasks retry again → repeat.
 *
 * This is called a "retry storm" or "thundering herd after recovery" — a real
 * production incident pattern. The system that just recovered is immediately
 * re-overloaded by synchronized retries.
 *
 * With jitter [0, 100ms]:
 *   t≈1000ms: task A retries
 *   t≈1047ms: task B retries  (47ms later)
 *   t≈1083ms: task C retries  (83ms later)
 * The 50 retries are spread across a 100ms window, giving the recovering
 * system a gentle ramp instead of a cliff.
 *
 * ── WHY @Lazy ON TaskService ─────────────────────────────────────────────────
 * RetryScheduler injects TaskService (to call retryTask()).
 * TaskService injects RetryScheduler (to pass it to TaskWorker).
 * This is a circular dependency. Spring resolves it by creating a proxy for
 * whichever bean is annotated @Lazy — the proxy is injected immediately, but
 * the real bean is only fetched on first method call, by which time the context
 * is fully initialised and the cycle is resolved.
 *
 * We put @Lazy here (not on TaskService) so TaskService is always fully
 * constructed first — it is the more central component.
 */
@Slf4j
@Component
public class RetryScheduler {

    private final ScheduledExecutorService retrySchedulerExecutor;
    private final RetryConfig retryConfig;

    // @Lazy breaks the circular dependency: TaskService → RetryScheduler → TaskService.
    @Lazy
    @Autowired
    private TaskService taskService;

    public RetryScheduler(ScheduledExecutorService retrySchedulerExecutor,
                          RetryConfig retryConfig) {
        this.retrySchedulerExecutor = retrySchedulerExecutor;
        this.retryConfig = retryConfig;
    }

    /**
     * Schedule a single retry of the given task after an exponentially-increasing,
     * jittered delay.
     *
     * <p>The task's {@code retryCount} at the time of this call is used as the
     * exponent. The actual increment happens inside {@link TaskService#retryTask}
     * so the retry count is committed atomically with the FAILED→PENDING transition.
     *
     * @param task the task that just permanently failed one attempt (is now FAILED)
     */
    public void scheduleRetry(Task task) {
        long jitter = ThreadLocalRandom.current().nextLong(0, retryConfig.getMaxJitterMs() + 1);
        long delay = retryConfig.getBaseDelayMs() * (1L << task.getRetryCount()) + jitter;

        log.info("Task [{}] scheduled for retry #{} in {}ms (base={}ms * 2^{} + {}ms jitter)",
                task.getTaskId(),
                task.getRetryCount() + 1,
                delay,
                retryConfig.getBaseDelayMs(),
                task.getRetryCount(),
                jitter);

        String taskId = task.getTaskId();
        retrySchedulerExecutor.schedule(
                () -> {
                    try {
                        taskService.retryTask(taskId);
                    } catch (Exception e) {
                        log.error("Retry re-submission failed for task [{}]: {}", taskId, e.getMessage(), e);
                    }
                },
                delay,
                TimeUnit.MILLISECONDS
        );
    }
}
