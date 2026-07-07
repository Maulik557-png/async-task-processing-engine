package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.exception.InvalidTaskStateException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodic background job that finds tasks stuck in IN_PROGRESS and marks them TIMED_OUT.
 *
 * ── WHY TIMED_OUT EXISTS AS A SEPARATE STATUS (not FAILED) ───────────────────
 * FAILED means the worker ran and returned an error — there is an errorMessage.
 * TIMED_OUT means the worker ran but never returned — we don't know why it hung.
 * These are distinct operational conditions:
 *   - FAILED: retry if retryCount < maxRetries.
 *   - TIMED_OUT: DO NOT auto-retry (a retried task would likely hang again for the same
 *     underlying reason — an infinite loop, a deadlocked downstream, a held lock).
 * Keeping them separate lets ops teams filter for timed-out tasks specifically
 * (GET /api/v1/tasks?status=TIMED_OUT) and investigate the hung worker behaviour.
 *
 * ── HOW A HUNG THREAD IS HANDLED ─────────────────────────────────────────────
 * The watchdog marks the task TIMED_OUT in the repository, but it does NOT
 * interrupt the worker thread. The thread may still be running a long operation.
 * Forcible thread interruption (Thread.interrupt()) is possible but dangerous:
 *   1. It may interrupt I/O mid-stream, leaving external systems in inconsistent state.
 *   2. Code that catches InterruptedException without re-setting the flag can silently
 *      absorb the interruption and keep running.
 * The safe approach: let the hung thread eventually finish or be reclaimed by JVM
 * shutdown. The task record is already TIMED_OUT, so even if the thread completes
 * later and tries to transition IN_PROGRESS→COMPLETED, the TaskStateManager guard will
 * reject the transition (task is TIMED_OUT, a terminal state) and log a warning.
 * The task remains TIMED_OUT — the late completion is safely ignored.
 *
 * ── WHY @Scheduled OVER A MANUAL ScheduledExecutorService HERE ───────────────
 * The watchdog has no per-task delay computation — it just runs on a fixed cadence
 * (every N milliseconds). @Scheduled(fixedDelay) is exactly this: "run once, wait
 * N ms after completion, run again." It's simpler than a manual ScheduledExecutorService
 * for this use case. The retry scheduler (RetryConfig) uses ScheduledExecutorService
 * because it needs per-task variable delays — a different requirement.
 *
 * fixedDelay (not fixedRate): the N ms starts AFTER the scan completes, not at the
 * start of each scan. Under heavy load, if the scan takes 5s and interval is 30s,
 * the next scan starts 35s after the previous one began — avoiding scan pile-up.
 *
 * ── WHY USE transitionStatus (not updateStatus directly) ─────────────────────
 * The ReentrantLock guard in TaskStateManager prevents a race where:
 *   1. The watchdog reads the task as IN_PROGRESS.
 *   2. The worker completes the task (IN_PROGRESS → COMPLETED).
 *   3. The watchdog calls updateStatus(TIMED_OUT), overwriting COMPLETED.
 * With the lock: the watchdog tries transitionStatus(IN_PROGRESS, TIMED_OUT).
 * If the worker already wrote COMPLETED, the guard sees "expected IN_PROGRESS but
 * found COMPLETED" → throws InvalidTaskStateException → watchdog catches it, logs
 * a debug message, and moves on. The task stays COMPLETED. Correct.
 */
@Slf4j
@Component
public class TaskTimeoutWatchdog {

    private final TaskRepository taskRepository;
    private final TaskStateManager stateManager;
    private final MetricsRegistry metricsRegistry;

    @Value("${task.timeout.seconds:60}")
    private long taskTimeoutSeconds;

    public TaskTimeoutWatchdog(TaskRepository taskRepository, TaskStateManager stateManager, MetricsRegistry metricsRegistry) {
        this.taskRepository = taskRepository;
        this.stateManager = stateManager;
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * Scan all IN_PROGRESS tasks and mark those past the timeout threshold as TIMED_OUT.
     *
     * <p>fixedDelayString reads the interval from application.properties so it can be
     * set to a small value (e.g., 3000ms) in tests without recompiling.
     */
    @Scheduled(fixedDelayString = "${task.watchdog.interval-ms:30000}")
    public void checkForTimedOutTasks() {
        Instant cutoff = Instant.now().minusSeconds(taskTimeoutSeconds);
        List<Task> inProgressTasks = taskRepository.findByStatus(TaskStatus.IN_PROGRESS);

        int timedOutCount = 0;
        for (Task task : inProgressTasks) {
            if (task.getStartedAt() != null && task.getStartedAt().isBefore(cutoff)) {
                try {
                    // Mark non-status fields before the lock (see TaskStateManager Javadoc).
                    task.setCompletedAt(Instant.now());
                    task.setErrorMessage(
                            "Task exceeded timeout of " + taskTimeoutSeconds + "s — marked TIMED_OUT by watchdog");

                    // Atomic transition: IN_PROGRESS → TIMED_OUT.
                    stateManager.transitionStatus(
                            task.getTaskId(), TaskStatus.IN_PROGRESS, TaskStatus.TIMED_OUT,
                            "Timed out by watchdog: task exceeded timeout of " + taskTimeoutSeconds + "s");

                    metricsRegistry.recordTimeout();

                    timedOutCount++;
                    log.warn("Watchdog timed out task [{}] — type={}, startedAt={}, cutoff={}",
                            task.getTaskId(), task.getType(), task.getStartedAt(), cutoff);

                } catch (InvalidTaskStateException e) {
                    // The task was completed/cancelled/failed between our findByStatus
                    // and transitionStatus calls. This is expected and benign — the worker
                    // finished the task while the watchdog was scanning.
                    log.debug("Watchdog: task [{}] already transitioned out of IN_PROGRESS: {}",
                            task.getTaskId(), e.getMessage());
                }
            }
        }

        if (timedOutCount > 0) {
            log.info("Watchdog scan complete — {} task(s) marked TIMED_OUT", timedOutCount);
        } else {
            log.debug("Watchdog scan complete — no timed-out tasks found (checked {} IN_PROGRESS tasks)",
                    inProgressTasks.size());
        }
    }
}
