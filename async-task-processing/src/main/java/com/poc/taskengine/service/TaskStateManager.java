package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.exception.InvalidTaskStateException;
import com.poc.taskengine.exception.TaskNotFoundException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enforces atomic, auditable task state transitions using per-task ReentrantLocks.
 *
 * ── WHY THIS CLASS EXISTS ────────────────────────────────────────────────────
 * Without it, two threads can race on the same task:
 *   Thread A (worker):  findById → status=PENDING → update to IN_PROGRESS
 *   Thread B (cancel):  findById → status=PENDING → update to CANCELLED
 * Both reads see PENDING; both writes succeed; last writer wins; one transition is lost.
 *
 * ── WHY ONLY transitionStatus (no saveWithTransition) ────────────────────────
 * An earlier design had saveWithTransition(task, expectedStatus) which accepted a
 * pre-mutated task object. This is fundamentally broken with reference-based
 * repositories: InMemoryTaskRepository stores object references, not copies.
 * When the caller mutates task.setStatus(NEW) before passing it in, findById()
 * inside the lock returns the SAME already-mutated object. The check
 * "currentStatus == expectedStatus" then compares NEW to OLD and fails — every
 * transition is rejected, leaving tasks permanently stuck in the new status.
 *
 * The correct design is:
 *   1. Caller does NOT mutate task.status before the lock call.
 *   2. transitionStatus acquires the lock, reads the stored status (still OLD),
 *      verifies it equals expectedStatus, calls updateStatus(id, newStatus).
 *   3. updateStatus sets task.status = newStatus on the stored reference.
 *   4. Caller may then freely mutate other fields (startedAt, result, etc.)
 *      because the status race is already resolved.
 *
 * ── WHY ReentrantLock OVER synchronized ──────────────────────────────────────
 * 1. tryLock(timeout, unit) — a future timeout watchdog can attempt acquisition
 *    without blocking forever if a worker thread stalls.
 * 2. lockInterruptibly() — supports graceful shutdown coordination.
 * 3. Thread dumps name the lock holder; anonymous monitor locks do not.
 *
 * ── WHY ONE LOCK PER TASK-ID ─────────────────────────────────────────────────
 * A global lock serialises every transition across all tasks. Per-ID locks mean
 * threads on different tasks NEVER contend — they only contend when two threads
 * race the SAME task, which is exactly the scenario we must prevent.
 *
 * ── WHY ConcurrentHashMap FOR THE LOCK REGISTRY ──────────────────────────────
 * computeIfAbsent is atomic at the bin level: two threads racing to create the
 * lock for the same taskId will both get the same ReentrantLock instance.
 */
@Slf4j
@Component
public class TaskStateManager {

    private final TaskRepository taskRepository;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Terminal statuses — once reached, no further transitions are permitted. */
    private static final Set<TaskStatus> TERMINAL_STATUSES = Set.of(
            TaskStatus.COMPLETED,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED,
            TaskStatus.TIMED_OUT
    );

    public TaskStateManager(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * Atomically verify a task is in {@code expectedStatus} and transition it to
     * {@code newStatus}, or throw if the expectation is not met.
     *
     * <p><strong>IMPORTANT — do NOT mutate task.status before calling this method.</strong>
     * The repository stores a reference to the Task object. If the caller has already
     * set task.setStatus(newStatus), then findById() inside the lock returns the
     * already-mutated object, making the expectedStatus check always fail.
     * Mutate non-status fields (startedAt, result, errorMessage) AFTER this call returns.
     *
     * <p>The lock for {@code taskId} is acquired before the repository read so no
     * other thread can observe or change status between the check and the update.
     *
     * @param taskId         the task to transition
     * @param expectedStatus the status the task must currently be in
     * @param newStatus      the status to transition to
     * @throws TaskNotFoundException     if no task with that ID exists
     * @throws InvalidTaskStateException if the current status ≠ expectedStatus,
     *                                   or if the task is already in a terminal state
     */
    public void transitionStatus(String taskId, TaskStatus expectedStatus, TaskStatus newStatus) {
        ReentrantLock lock = locks.computeIfAbsent(taskId, id -> new ReentrantLock());

        lock.lock();
        try {
            Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new TaskNotFoundException(taskId));

            TaskStatus currentStatus = task.getStatus();

            // Guard 1: hard stop on terminal states.
            if (TERMINAL_STATUSES.contains(currentStatus)) {
                log.warn("Transition rejected for task [{}]: already terminal {} — refused {}",
                        taskId, currentStatus, newStatus);
                throw new InvalidTaskStateException(taskId, currentStatus,
                        "transition to " + newStatus + " (task is already terminal)");
            }

            // Guard 2: expected-status check.
            if (currentStatus != expectedStatus) {
                log.warn("Transition rejected for task [{}]: expected {} but found {} — refused {}",
                        taskId, expectedStatus, currentStatus, newStatus);
                throw new InvalidTaskStateException(taskId, currentStatus,
                        "transition to " + newStatus + " (expected status was " + expectedStatus + ")");
            }

            // All guards passed — safe to update.
            taskRepository.updateStatus(taskId, newStatus);
            log.debug("Task [{}] transitioned {} → {} (under lock)", taskId, currentStatus, newStatus);

        } finally {
            lock.unlock();
        }
    }
}
