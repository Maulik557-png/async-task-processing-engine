package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.TaskStateManager;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runnable that executes a single Task on whichever thread the pool assigns it to.
 *
 * PHASE 4 KEY DESIGN: mutation order with TaskStateManager
 * ─────────────────────────────────────────────────────────
 * Rule: NEVER mutate task.status before calling stateManager.transitionStatus().
 *
 * WHY: InMemoryTaskRepository stores object references, not copies. Calling
 * task.setStatus(X) before transitionStatus() means findById() inside the lock
 * returns the already-mutated object. The guard check "currentStatus == expected"
 * then sees the NEW status, not the OLD one — the guard always rejects the transition,
 * leaving the task permanently stuck in the pre-mutated status.
 *
 * CORRECT PATTERN:
 *   1. Call transitionStatus(taskId, OLD, NEW).  Do NOT touch task.status before this.
 *   2. On success, the repository's task object now has status=NEW.
 *      (Because updateStatus(taskId, NEW) sets task.status on the stored reference.)
 *   3. Freely mutate other fields (startedAt, result, errorMessage) — the race is resolved.
 *
 * Other Phase 4 additions:
 * - REPORT_GENERATION tasks are routed to ReportGenerationPipeline (CompletableFuture chain).
 * - All other types use the simulated sleep stub (Phase 6 adds real business logic).
 */
@Slf4j
public class TaskWorker implements Runnable {

    private final Task task;
    private final TaskRepository taskRepository;
    private final TaskStateManager stateManager;
    private final Executor executor;   // passed to ReportGenerationPipeline

    public TaskWorker(Task task, TaskRepository taskRepository,
                      TaskStateManager stateManager, Executor executor) {
        this.task = task;
        this.taskRepository = taskRepository;
        this.stateManager = stateManager;
        this.executor = executor;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        String taskId = task.getTaskId();

        // ── Transition: PENDING → IN_PROGRESS ──────────────────────────────────
        //
        // CRITICAL: Do NOT call task.setStatus(IN_PROGRESS) before this call.
        // transitionStatus reads the task's CURRENT status from the repository.
        // If we pre-mutate, the repository (which holds the same reference) would
        // already show IN_PROGRESS, making the PENDING check fail immediately.
        //
        // After this call succeeds, the repository has updated task.status = IN_PROGRESS
        // (via updateStatus on the stored reference). We can then safely set startedAt.
        try {
            stateManager.transitionStatus(taskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS);
        } catch (Exception e) {
            // Task was already moved from PENDING (e.g., cancelled between enqueue and dequeue).
            log.warn("[{}] Task [{}] skipping execution — could not transition PENDING→IN_PROGRESS: {}",
                    threadName, taskId, e.getMessage());
            return;
        }

        // Set startedAt AFTER the successful transition — we own this task now.
        task.setStartedAt(Instant.now());

        log.info("[{}] Task [{}] (type={}, priority={}) → IN_PROGRESS",
                threadName, taskId, task.getType(), task.getPriority());

        // ── Branch: REPORT_GENERATION → CompletableFuture pipeline ──────────────
        if (task.getType() == TaskType.REPORT_GENERATION) {
            runReportGenerationPipeline(threadName, taskId);
            return;
        }

        // ── Default path: simulated sleep stub ────────────────────────────────
        // Phase 6 replaces this with real per-type business logic.
        try {
            long sleepMs = ThreadLocalRandom.current().nextLong(500, 2500);
            log.debug("[{}] Task [{}] simulating work for {} ms", threadName, taskId, sleepMs);
            Thread.sleep(sleepMs);

            // ── Transition: IN_PROGRESS → COMPLETED ────────────────────────────
            // Mutate non-status fields BEFORE transitionStatus (safe — we own the task).
            // task.status must still be IN_PROGRESS (set by the successful transition above).
            // transitionStatus reads task.status from the stored reference, sees IN_PROGRESS, succeeds.
            task.setCompletedAt(Instant.now());
            task.setResult("Simulated result for task " + taskId);
            stateManager.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);

            log.info("[{}] Task [{}] (type={}, priority={}) → COMPLETED (took {} ms)",
                    threadName, taskId, task.getType(), task.getPriority(), sleepMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(taskId, threadName, "Worker thread interrupted during execution");
            log.warn("[{}] Task [{}] interrupted — marked FAILED", threadName, taskId);

        } catch (Exception e) {
            markFailed(taskId, threadName, e.getMessage());
            log.error("[{}] Task [{}] threw unexpected exception → FAILED: {}",
                    threadName, taskId, e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void runReportGenerationPipeline(String threadName, String taskId) {
        try {
            new ReportGenerationPipeline(task, stateManager, executor).execute();
        } catch (Exception e) {
            log.error("[{}] Task [{}] pipeline threw uncaught exception: {}",
                    threadName, taskId, e.getMessage(), e);
            markFailed(taskId, threadName, "Pipeline uncaught exception: " + e.getMessage());
        }
    }

    private void markFailed(String taskId, String threadName, String errorMessage) {
        // Mutate fields first (safe — we own the task and status is IN_PROGRESS).
        task.setCompletedAt(Instant.now());
        task.setErrorMessage(errorMessage);
        // Don't set task.status before transitionStatus — see class-level doc.
        try {
            stateManager.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.FAILED);
        } catch (Exception ex) {
            log.warn("[{}] Task [{}] could not be marked FAILED: {}", threadName, taskId, ex.getMessage());
        }
    }
}
