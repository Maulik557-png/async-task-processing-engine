package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.exception.InvalidTaskStateException;
import com.poc.taskengine.exception.TaskNotFoundException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import com.poc.taskengine.model.TaskAuditEvent;
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
@RequiredArgsConstructor
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
     * <p>TERMINAL STATE GUARD: once a task reaches COMPLETED, FAILED, CANCELLED, or TIMED_OUT,
     * this method refuses ALL transitions. The only exception is FAILED→PENDING for retries,
     * which must go through {@link #retryTransition(String)} instead.
     *
     * @param taskId         the task to transition
     * @param expectedStatus the status the task must currently be in
     * @param newStatus      the status to transition to
     * @throws TaskNotFoundException     if no task with that ID exists
     * @throws InvalidTaskStateException if the current status ≠ expectedStatus,
     *                                   or if the task is already in a terminal state
     */
    public void transitionStatus(String taskId, TaskStatus expectedStatus, TaskStatus newStatus) {
        transitionStatus(taskId, expectedStatus, newStatus, "Transitioned from " + expectedStatus + " to " + newStatus);
    }

    /**
     * Overloaded transitionStatus that accepts a custom description message for the audit event.
     */
    public void transitionStatus(String taskId, TaskStatus expectedStatus, TaskStatus newStatus, String message) {
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

            // Record transition in audit trail
            TaskAuditEvent event = new TaskAuditEvent(
                    currentStatus,
                    newStatus,
                    Instant.now(),
                    Thread.currentThread().getName(),
                    message
            );
            event.setTask(task);
            task.getAuditTrail().add(event);

            log.debug("Task [{}] transitioned {} → {} (under lock)", taskId, currentStatus, newStatus);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Special transition for retry logic: FAILED → PENDING.
     *
     * <p>This is the ONLY path that transitions a task out of the FAILED terminal state.
     * The standard {@link #transitionStatus} rejects all transitions from terminal states
     * to prevent unintended state recovery. This dedicated method allows ONLY the specific
     * FAILED→PENDING transition required when a retry is granted.
     *
     * <p>WHY NOT just relax the terminal guard in transitionStatus()?
     * Because that would allow ANY caller to "un-terminal" any task. We want only the
     * retry scheduler to ever move a task out of FAILED. This method is the single,
     * audited, intentional gate for that operation.
     *
     * @param taskId the task to re-enqueue for retry
     * @throws TaskNotFoundException     if no task with that ID exists
     * @throws InvalidTaskStateException if the task is not currently FAILED
     */
    public void retryTransition(String taskId) {
        ReentrantLock lock = locks.computeIfAbsent(taskId, id -> new ReentrantLock());

        lock.lock();
        try {
            Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new TaskNotFoundException(taskId));

            TaskStatus currentStatus = task.getStatus();

            if (currentStatus != TaskStatus.FAILED) {
                log.warn("Retry transition rejected for task [{}]: expected FAILED but found {}",
                        taskId, currentStatus);
                throw new InvalidTaskStateException(taskId, currentStatus,
                        "retry transition (expected FAILED, was " + currentStatus + ")");
            }

            taskRepository.updateStatus(taskId, TaskStatus.PENDING);

            // Record retry transition in audit trail
            TaskAuditEvent event = new TaskAuditEvent(
                    currentStatus,
                    TaskStatus.PENDING,
                    Instant.now(),
                    Thread.currentThread().getName(),
                    "Retry granted: transitioned FAILED to PENDING"
            );
            event.setTask(task);
            task.getAuditTrail().add(event);

            log.info("Task [{}] FAILED → PENDING (retry granted, under lock)", taskId);

        } finally {
            lock.unlock();
        }
    }
}
