package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.CircuitBreakerRegistry;
import com.poc.taskengine.service.RetryScheduler;
import com.poc.taskengine.service.TaskStateManager;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
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
    private final TaskRepository taskRepository;
    private final TaskStateManager stateManager;
    private final Executor executor;
    private final RetryScheduler retryScheduler;
    private final CircuitBreakerRegistry circuitBreaker;

    public TaskWorker(Task task, TaskRepository taskRepository,
                      TaskStateManager stateManager, Executor executor,
                      RetryScheduler retryScheduler, CircuitBreakerRegistry circuitBreaker) {
        this.task = task;
        this.taskRepository = taskRepository;
        this.stateManager = stateManager;
        this.executor = executor;
        this.retryScheduler = retryScheduler;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        String taskId = task.getTaskId();

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

        // ── Branch: REPORT_GENERATION → CompletableFuture pipeline ──────────────
        if (task.getType() == TaskType.REPORT_GENERATION) {
            runReportGenerationPipeline(threadName, taskId);
            return;
        }

        // ── Default path: simulated sleep stub with optional force-fail ─────────
        try {
            // Check force-fail test hook.
            AtomicInteger failRemaining = forceFailCounts.get(taskId);
            if (failRemaining != null && failRemaining.get() > 0) {
                failRemaining.decrementAndGet();
                throw new RuntimeException(
                        "Forced failure for retry test (failures remaining after this: "
                        + failRemaining.get() + ")");
            }

            long sleepMs = ThreadLocalRandom.current().nextLong(500, 2500);
            log.debug("[{}] Task [{}] simulating work for {}ms", threadName, taskId, sleepMs);
            Thread.sleep(sleepMs);

            // ── Transition: IN_PROGRESS → COMPLETED ────────────────────────────
            // Mutate non-status fields BEFORE transitionStatus (safe — we own the task).
            task.setCompletedAt(Instant.now());
            task.setResult("Simulated result for task " + taskId);
            stateManager.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);

            // Circuit breaker: reset consecutive failure counter on success.
            circuitBreaker.recordSuccess(task.getType());

            log.info("[{}] Task [{}] (type={}, priority={}) → COMPLETED (took {}ms)",
                    threadName, taskId, task.getType(), task.getPriority(), sleepMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] Task [{}] interrupted — marking FAILED", threadName, taskId);
            markFailed(taskId, threadName, "Worker thread interrupted during execution");

        } catch (Exception e) {
            log.error("[{}] Task [{}] threw exception → will retry or fail: {}",
                    threadName, taskId, e.getMessage());
            markFailed(taskId, threadName, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void runReportGenerationPipeline(String threadName, String taskId) {
        try {
            new ReportGenerationPipeline(task, stateManager, executor).execute();
            // Pipeline's storeResult() already called transitionStatus(IN_PROGRESS→COMPLETED).
            // Record success for circuit breaker.
            if (task.getStatus() == TaskStatus.COMPLETED) {
                circuitBreaker.recordSuccess(task.getType());
            }
        } catch (Exception e) {
            log.error("[{}] Task [{}] pipeline threw uncaught exception: {}",
                    threadName, taskId, e.getMessage(), e);
            markFailed(taskId, threadName, "Pipeline uncaught exception: " + e.getMessage());
        }
    }

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
     *
     * <p>WHY transition to FAILED first (not straight to PENDING for retry)?
     *   If we skipped FAILED and went directly IN_PROGRESS→PENDING, the failure
     *   would be invisible in the audit trail. A task that is retrying has genuinely
     *   failed — that should be observable via GET /api/v1/tasks/{id} between the
     *   failure and the retry. The FAILED→PENDING transition is the explicit "retry
     *   granted" step in retryTask(), clearly logged and auditable.
     */
    private void markFailed(String taskId, String threadName, String errorMessage) {
        task.setCompletedAt(Instant.now());
        task.setErrorMessage(errorMessage);
        // Transition IN_PROGRESS → FAILED (always happens, regardless of retry).
        try {
            stateManager.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.FAILED);
        } catch (Exception ex) {
            log.warn("[{}] Task [{}] could not be marked FAILED: {}", threadName, taskId, ex.getMessage());
            return; // Cannot proceed with retry if state transition failed.
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
        }
    }
}
