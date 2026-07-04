package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runnable that executes a single Task on whichever thread the pool assigns it to.
 *
 * Design decisions:
 * - Implements Runnable (not Callable) because we don't need a return value via Future.
 *   The result is persisted to the repository and the caller polls for it.
 * - Stateless except for the task + repository references injected at construction time.
 *   This makes it safe to hand off to any thread without shared mutable state.
 * - Uses ThreadLocalRandom for sleep duration (not Math.random, which uses a shared
 *   seed protected by a synchronized block — ThreadLocalRandom avoids that contention
 *   when many workers call it simultaneously).
 *
 * Phase 6 replaces the Thread.sleep() stub with real business logic per TaskType.
 */
@Slf4j
public class TaskWorker implements Runnable {

    private final Task task;
    private final TaskRepository taskRepository;

    public TaskWorker(Task task, TaskRepository taskRepository) {
        this.task = task;
        this.taskRepository = taskRepository;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        String taskId = task.getTaskId();

        // ── Transition: PENDING → IN_PROGRESS ──────────────────────────────────
        // Record startedAt before updating status so the audit trail is consistent
        // (startedAt always corresponds to the moment IN_PROGRESS was set).
        task.setStartedAt(Instant.now());
        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.save(task);

        log.info("[{}] Task [{}] (type={}, priority={}) → IN_PROGRESS",
                threadName, taskId, task.getType(), task.getPriority());

        try {
            // Simulate variable-length work: 500 ms – 2500 ms.
            // Using ThreadLocalRandom: each thread has its own PRNG seed, so
            // concurrent calls here carry zero contention (unlike Math.random()).
            long sleepMs = ThreadLocalRandom.current().nextLong(500, 2500);
            log.debug("[{}] Task [{}] simulating work for {} ms", threadName, taskId, sleepMs);
            Thread.sleep(sleepMs);

            // ── Transition: IN_PROGRESS → COMPLETED ────────────────────────────
            task.setCompletedAt(Instant.now());
            task.setStatus(TaskStatus.COMPLETED);
            task.setResult("Simulated result for task " + taskId);
            taskRepository.save(task);

            log.info("[{}] Task [{}] (type={}, priority={}) → COMPLETED (took {} ms)",
                    threadName, taskId, task.getType(), task.getPriority(), sleepMs);

        } catch (InterruptedException e) {
            // Pool is shutting down (awaitTerminationSeconds exceeded or JVM kill).
            // Re-interrupt the thread so the pool's shutdown logic can observe the flag.
            Thread.currentThread().interrupt();

            // ── Transition: IN_PROGRESS → FAILED ───────────────────────────────
            task.setCompletedAt(Instant.now());
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Worker thread interrupted during execution");
            taskRepository.save(task);

            log.warn("[{}] Task [{}] interrupted — marked FAILED", threadName, taskId);

        } catch (Exception e) {
            // Unexpected runtime error — still must not fail silently.
            task.setCompletedAt(Instant.now());
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);

            log.error("[{}] Task [{}] threw unexpected exception → FAILED: {}",
                    threadName, taskId, e.getMessage(), e);
        }
    }
}
