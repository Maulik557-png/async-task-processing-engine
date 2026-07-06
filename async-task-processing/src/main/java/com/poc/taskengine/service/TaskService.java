package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.InvalidTaskStateException;
import com.poc.taskengine.exception.TaskNotFoundException;
import com.poc.taskengine.exception.TaskQueueFullException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.worker.PriorityTaskWrapper;
import com.poc.taskengine.worker.TaskWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Business logic for task lifecycle management.
 *
 * PHASE 4 ADDITIONS:
 *
 * ── SEMAPHORE RATE LIMITING ──────────────────────────────────────────────────
 * A Semaphore(50, true) caps the number of in-flight tasks (PENDING or IN_PROGRESS)
 * to 50. submitTask() calls tryAcquire() before persisting the task; if no permit
 * is available, TaskQueueFullException is thrown → HTTP 503.
 *
 * WHY Semaphore OVER an AtomicInteger counter?
 *   AtomicInteger requires manual compare-and-set logic that is easy to get wrong.
 *   Semaphore encapsulates the acquire/release contract in a well-tested, named
 *   concurrency primitive that clearly communicates "limited resource" semantics.
 *
 * WHY fairness=true (second constructor argument)?
 *   Without fairness, permits are granted by racing on a CAS instruction. Under
 *   sustained load (many threads calling tryAcquire() in tight loops), a thread
 *   that happens to call tryAcquire() at the exact nanosecond a permit is released
 *   wins the CAS, regardless of how long it has been waiting. A thread that is
 *   slightly unlucky in its timing could be starved indefinitely while newer requests
 *   keep winning. With fairness=true, the Semaphore maintains an internal FIFO
 *   queue of waiters and grants permits in arrival order — no thread waits longer
 *   than proportional to its wait time relative to others.
 *
 * WHY tryAcquire() (non-blocking) rather than acquire() (blocking)?
 *   acquire() would block the HTTP request thread until a permit becomes available.
 *   Blocking the Tomcat thread under load ties up the HTTP server's thread pool,
 *   which is exactly the failure mode this engine exists to prevent. tryAcquire()
 *   returns false immediately, lets us throw 503, and releases the HTTP thread.
 *
 * ── EXECUTOR INJECTION TYPE ───────────────────────────────────────────────────
 * The field type is now Executor (the interface), not ThreadPoolTaskExecutor.
 * This makes the service layer independent of the pool implementation — Phase 9
 * can swap in virtual threads with zero changes here. The @Qualifier still works
 * because Spring resolves it by bean name before checking type.
 *
 * ── TASK STATE MANAGER ────────────────────────────────────────────────────────
 * cancelTask() now delegates to TaskStateManager.transitionStatus() which acquires
 * the per-task ReentrantLock, closing the TOCTOU race in the Phase 3 implementation.
 */
@Slf4j
@Service
public class TaskService {

    /** Maximum number of tasks in-flight (PENDING + IN_PROGRESS) at any time. */
    private static final int MAX_IN_FLIGHT = 50;

    private static final Set<TaskStatus> TERMINAL_STATUSES = Set.of(
            TaskStatus.COMPLETED, TaskStatus.FAILED,
            TaskStatus.CANCELLED, TaskStatus.TIMED_OUT
    );

    private final TaskRepository taskRepository;
    private final Executor taskExecutor;
    private final TaskStateManager stateManager;

    /**
     * Fair Semaphore acting as the in-flight task capacity gate.
     *
     * WHY fair=true: see class-level Javadoc above.
     * WHY initialPermits=50: matches production capacity target.
     */
    private final Semaphore rateLimiter = new Semaphore(MAX_IN_FLIGHT, true);

    public TaskService(TaskRepository taskRepository,
                       @Qualifier("taskExecutor") Executor taskExecutor,
                       TaskStateManager stateManager) {
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
        this.stateManager = stateManager;
    }

    /**
     * Accept a task, persist it in PENDING state, and hand it off to the thread pool.
     * Returns the task ID immediately — the caller does not wait for execution.
     *
     * @throws TaskQueueFullException if the in-flight task limit (50) has been reached
     */
    public String submitTask(TaskType type, TaskPriority priority,
                             String payload, String submittedBy, int maxRetries) {

        // ── Semaphore gate: acquire before persisting ─────────────────────────
        // Acquire BEFORE saving. If we saved first and then failed to acquire, we'd
        // have a PENDING task with no Runnable in the pool — permanently stuck.
        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit reached ({} in-flight). Rejecting submission by {}",
                    MAX_IN_FLIGHT, submittedBy);
            throw new TaskQueueFullException();
        }

        Task task = Task.builder()
                .taskId(UUID.randomUUID().toString())
                .type(type)
                .priority(priority)
                .status(TaskStatus.PENDING)
                .payload(payload)
                .submittedBy(submittedBy)
                .createdAt(Instant.now())
                .retryCount(0)
                .maxRetries(maxRetries)
                .build();

        // Persist before submitting to the pool — PENDING record exists even if
        // the app crashes between save() and execute().
        taskRepository.save(task);

        log.info("Task [{}] submitted: type={}, priority={}, submittedBy={}, maxRetries={}",
                task.getTaskId(), type, priority, submittedBy, maxRetries);

        // ── Wrap TaskWorker in a permit-releasing outer Runnable ──────────────
        // The permit is released in a finally block so it is always returned,
        // even if the worker throws an uncaught exception.
        //
        // WHY a wrapper Runnable rather than releasing inside TaskWorker directly?
        //   TaskWorker should not know about the rate limiter. The wrapper keeps
        //   the rate-limiting concern in the service layer where it belongs.
        TaskWorker worker = new TaskWorker(task, taskRepository, stateManager, taskExecutor);
        Runnable rateLimitedWorker = () -> {
            try {
                worker.run();
            } finally {
                rateLimiter.release();
                log.debug("Semaphore permit released for task [{}]. Available: {}/{}",
                        task.getTaskId(), rateLimiter.availablePermits(), MAX_IN_FLIGHT);
            }
        };

        // ── Wrap in PriorityTaskWrapper for priority queue ordering ───────────
        // PriorityBlockingQueue uses PriorityTaskWrapper.compareTo() to determine
        // dequeue order. Without this wrapper the queue would try to cast the
        // Runnable to Comparable and throw ClassCastException.
        taskExecutor.execute(new PriorityTaskWrapper(rateLimitedWorker, priority, task.getTaskId()));

        return task.getTaskId();
    }

    /**
     * Retrieve a single task by ID.
     *
     * @throws TaskNotFoundException if no task with that ID exists
     */
    public Task getTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> {
                    log.warn("Task not found: id={}", taskId);
                    return new TaskNotFoundException(taskId);
                });
    }

    /**
     * Return all tasks, or filter by status when provided.
     *
     * @param status optional status filter; if null, all tasks are returned
     */
    public List<Task> getAllTasks(TaskStatus status) {
        if (status != null) {
            log.debug("Listing tasks filtered by status={}", status);
            return taskRepository.findByStatus(status);
        }
        log.debug("Listing all tasks");
        return taskRepository.findAll();
    }

    /**
     * Cancel a task that is still PENDING.
     *
     * PHASE 4: Delegates to TaskStateManager.transitionStatus() which acquires
     * the per-task ReentrantLock before verifying and updating status. This closes
     * the TOCTOU race in the Phase 3 implementation where a worker could move the
     * task to IN_PROGRESS between findById() and updateStatus().
     *
     * @throws TaskNotFoundException      if no task with that ID exists
     * @throws InvalidTaskStateException  if the task is not in PENDING status
     */
    public void cancelTask(String taskId) {
        // Verify the task exists first for a clear 404 message.
        taskRepository.findById(taskId)
                .orElseThrow(() -> {
                    log.warn("Cancel requested for unknown task: id={}", taskId);
                    return new TaskNotFoundException(taskId);
                });

        // Atomic lock+verify+update. Throws InvalidTaskStateException if not PENDING.
        stateManager.transitionStatus(taskId, TaskStatus.PENDING, TaskStatus.CANCELLED);
        log.info("Task [{}] cancelled (was PENDING)", taskId);
    }

    /** Expose available semaphore permits — used in tests to verify rate limiting. */
    public int availablePermits() {
        return rateLimiter.availablePermits();
    }
}
