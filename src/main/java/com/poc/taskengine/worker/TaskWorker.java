package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.service.CircuitBreakerRegistry;
import com.poc.taskengine.service.MetricsRegistry;
import com.poc.taskengine.service.RetryScheduler;
import com.poc.taskengine.service.TaskStateManager;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable that executes a single Task on whichever thread the pool assigns it to.
 *
 * PHASE 4 KEY DESIGN: mutation order with TaskStateManager
 * ─────────────────────────────────────────────────────────
 * Rule: NEVER mutate task.status before calling stateManager.transitionStatus().
 * The repository stores object references. Pre-mutation defeats the lock check.
 * See TaskStateManager Javadoc for full explanation.
 *
 * PHASE 5 ADDITIONS:
 * ──────────────────
 * 1. RETRY LOGIC: markFailed() checks retryCount < maxRetries. If retries remain,
 *    it transitions IN_PROGRESS→FAILED and then calls RetryScheduler.scheduleRetry().
 *    RetryScheduler fires after an exponential-backoff+jitter delay, calling
 *    TaskService.retryTask() which transitions FAILED→PENDING and re-enqueues.
 *
 * 2. CIRCUIT BREAKER: on COMPLETED, recordSuccess(type) resets the counter.
 *    On permanent FAILED (retries exhausted), recordFailure(type) increments it.
 *    At FAILURE_THRESHOLD consecutive failures for a type, a WARNING is logged.
 *
 * 3. FORCE-FAIL MAP: For integration testing, tasks whose ID is in forceFailCounts
 *    are made to fail exactly that many times before succeeding. This lets tests
 *    verify retry behaviour without modifying the payload or adding test-only branches
 *    in the business logic path. See RetryIntegrationTest for usage.
 */
@Slf4j
public class TaskWorker implements Runnable {

    /**
     * Test hook: maps taskId → remaining forced failures.
     *
     * Before each forced failure, the counter is decremented atomically.
     * When the count reaches 0, the task executes normally and succeeds.
     *
     * Example (RetryIntegrationTest):
     *   TaskWorker.forceFailCounts.put(taskId, new AtomicInteger(2));
     *   → first 2 attempts throw, 3rd attempt succeeds.
     *
     * WHY a static map on the worker class (not a Spring bean)?
     *   TaskWorker is not a Spring bean — it is created per-task via new.
     *   A static field on the class is the simplest way to share state across
     *   all TaskWorker instances without introducing test-only injection points.
     *   The field is cleared after each test to avoid cross-test contamination.
     */
    public static final ConcurrentHashMap<String, AtomicInteger> forceFailCounts =
            new ConcurrentHashMap<>();

    private final Task task;
    private final TaskStateManager stateManager;
    private final RetryScheduler retryScheduler;
    private final CircuitBreakerRegistry circuitBreaker;
    private final TaskHandlerRegistry registry;
    private final MetricsRegistry metricsRegistry;

    public TaskWorker(Task task,
                      TaskStateManager stateManager,
                      RetryScheduler retryScheduler, CircuitBreakerRegistry circuitBreaker,
                      TaskHandlerRegistry registry, MetricsRegistry metricsRegistry) {
        this.task = task;
        this.stateManager = stateManager;
        this.retryScheduler = retryScheduler;
        this.circuitBreaker = circuitBreaker;
        this.registry = registry;
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        String taskId = task.getTaskId();

        // Put MDC context variables for thread-local logging traceability
        MDC.put("taskId", taskId);
        MDC.put("taskType", task.getType().name());

        try {
            // ── Transition: PENDING → IN_PROGRESS ──────────────────────────────────
            // CRITICAL: Do NOT call task.setStatus(IN_PROGRESS) before this call.
            // See class Javadoc for the reference-aliasing explanation.
            try {
                stateManager.transitionStatus(taskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS);
            } catch (Exception e) {
                // Task was cancelled between enqueue and dequeue — skip silently.
                log.warn("[{}] Task [{}] skipping — could not transition PENDING→IN_PROGRESS: {}",
                        threadName, taskId, e.getMessage());
                return;
            }

            // Set startedAt AFTER the transition succeeds — we own this task now.
            task.setStartedAt(Instant.now());

            log.info("[{}] Task [{}] (type={}, priority={}, attempt={}/{}) → IN_PROGRESS",
                    threadName, taskId, task.getType(), task.getPriority(),
                    task.getRetryCount() + 1, task.getMaxRetries() + 1);

            try {
                // Check force-fail test hook.
                AtomicInteger failRemaining = forceFailCounts.get(taskId);
                if (failRemaining != null && failRemaining.get() > 0) {
                    failRemaining.decrementAndGet();
                    throw new RuntimeException(
                            "Forced failure for retry test (failures remaining after this: "
                            + failRemaining.get() + ")");
                }

                // Resolve and execute the handler from TaskHandlerRegistry
                TaskHandler handler = registry.getHandler(task.getType());
                TaskResult result = handler.execute(task);

                // If the handler did not already transition the status (e.g. ReportGenerationPipeline transitions internally),
                // we transition to COMPLETED here.
                if (task.getStatus() == TaskStatus.IN_PROGRESS) {
                    task.setCompletedAt(Instant.now());
                    task.setResult(result.getResult());
                    stateManager.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);

                    // Circuit breaker: reset consecutive failure counter on success.
                    circuitBreaker.recordSuccess(task.getType());

                    log.info("[{}] Task [{}] (type={}, priority={}) → COMPLETED",
                            threadName, taskId, task.getType(), task.getPriority());
                }

                // Record completion metrics
                if (task.getStatus() == TaskStatus.COMPLETED) {
                    long durationMs = java.time.Duration.between(task.getStartedAt(), task.getCompletedAt()).toMillis();
                    metricsRegistry.recordCompletion(durationMs);
                }

            } catch (Exception e) {
                log.error("[{}] Task [{}] threw exception → will retry or fail: {}",
                        threadName, taskId, e.getMessage());
                markFailed(taskId, threadName, e.getMessage());
            }
        } finally {
            // Guarantee MDC context variables are cleared to prevent memory leaks or incorrect diagnostic output on pooled threads
            MDC.clear();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Transition IN_PROGRESS → FAILED, then check whether a retry should be scheduled.
     *
     * <p>Sequence when retries remain:
     *   1. IN_PROGRESS → FAILED (atomically via stateManager)
     *   2. retryScheduler.scheduleRetry(task)   ← schedules FAILED→PENDING after delay
     *   The task will re-enter PENDING→IN_PROGRESS→... on the next attempt.
     *
     * <p>Sequence when retries exhausted:
     *   1. IN_PROGRESS → FAILED (permanent)
     *   2. circuitBreaker.recordFailure(type)
     */
    private void markFailed(String taskId, String threadName, String errorMessage) {
        task.setCompletedAt(Instant.now());
        task.setErrorMessage(errorMessage);
        // Transition IN_PROGRESS → FAILED (if not already transitioned to FAILED, e.g. by pipeline error handler).
        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            try {
                stateManager.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.FAILED, errorMessage);
            } catch (Exception ex) {
                log.warn("[{}] Task [{}] could not be marked FAILED: {}", threadName, taskId, ex.getMessage());
                return; // Cannot proceed with retry if state transition failed.
            }
        }

        // Check retries.
        if (task.getRetryCount() < task.getMaxRetries()) {
            log.info("[{}] Task [{}] failed (attempt {}/{}) — scheduling retry",
                    threadName, taskId,
                    task.getRetryCount() + 1, task.getMaxRetries() + 1);
            retryScheduler.scheduleRetry(task);
            // Do NOT record circuit-breaker failure here — this is a transient failure.
        } else {
            log.warn("[{}] Task [{}] exhausted all {} retries — permanently FAILED",
                    threadName, taskId, task.getMaxRetries());
            // Only record permanent failure in the circuit breaker.
            circuitBreaker.recordFailure(task.getType());
            // Record failure metric
            metricsRegistry.recordFailure();
        }
    }
}
