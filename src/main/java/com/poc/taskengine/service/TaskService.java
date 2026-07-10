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
import com.poc.taskengine.worker.TaskHandlerRegistry;
import com.poc.taskengine.worker.TaskWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Business logic for task lifecycle management.
 *
 * PHASE 4 ADDITIONS:
 * - Semaphore(50, true) rate limiting (tryAcquire before persist).
 * - TaskStateManager for atomic state transitions.
 * - PriorityTaskWrapper for priority queue ordering.
 *
 * PHASE 5 ADDITIONS:
 *
 * ── retryTask() ──────────────────────────────────────────────────────────────
 * Called by RetryScheduler after a backoff delay. The task is currently FAILED.
 * This method:
 *   1. Increments retryCount (atomically, before re-submission).
 *   2. Clears startedAt, completedAt, errorMessage (fresh state for next attempt).
 *   3. Calls stateManager.retryTransition(taskId) → FAILED→PENDING (under lock).
 *   4. Acquires a Semaphore permit (blocking with timeout — retry thread, not HTTP thread).
 *   5. Re-wraps in TaskWorker → rateLimitedWorker → PriorityTaskWrapper, submits.
 *
 * WHY blocking acquire() in retryTask() but non-blocking tryAcquire() in submitTask()?
 *   submitTask() is called from a Tomcat HTTP request thread. Blocking it under load
 *   ties up the web server — we exist to prevent that. So tryAcquire() + 503 is correct.
 *   retryTask() is called from a daemon retry-scheduler thread that has no HTTP client
 *   waiting. It can afford to block briefly (up to permitTimeoutSeconds) until a permit
 *   frees. If it times out, the task is marked FAILED permanently.
 *
 * ── cancelTask() shutdown guard ──────────────────────────────────────────────
 * If the executor is shut down, cancelTask is still available (it doesn't enqueue anything).
 * No change needed; the method only updates the repository.
 */
@Slf4j
@Service
public class TaskService {

    /** Maximum number of tasks in-flight (PENDING + IN_PROGRESS) at any time. */
    private static final int MAX_IN_FLIGHT = 50;

    private final TaskRepository taskRepository;
    private final Executor taskExecutor;
    private final Executor criticalTaskExecutor;
    private final Executor bulkTaskExecutor;
    private final TaskStateManager stateManager;
    private final RetryScheduler retryScheduler;
    private final CircuitBreakerRegistry circuitBreaker;
    private final TaskHandlerRegistry registry;
    private final MetricsRegistry metricsRegistry;
    private final org.springframework.core.env.Environment env;

    @Value("${task.retry.permit-timeout-seconds:5}")
    private long permitTimeoutSeconds;

    /**
     * Fair Semaphore acting as the in-flight task capacity gate.
     * Phase 4: tryAcquire() (non-blocking) on submit, release() in worker finally-block.
     * Phase 5: acquire(timeout) (blocking) on retry resubmission — retry threads can wait.
     */
    private final Semaphore rateLimiter = new Semaphore(MAX_IN_FLIGHT, true);

    public TaskService(TaskRepository taskRepository,
                       @Qualifier("taskExecutor") Executor taskExecutor,
                       @Qualifier("criticalTaskExecutor") Executor criticalTaskExecutor,
                       @Qualifier("bulkTaskExecutor") Executor bulkTaskExecutor,
                       TaskStateManager stateManager,
                       RetryScheduler retryScheduler,
                       CircuitBreakerRegistry circuitBreaker,
                       TaskHandlerRegistry registry,
                       MetricsRegistry metricsRegistry,
                       org.springframework.core.env.Environment env) {
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
        this.criticalTaskExecutor = criticalTaskExecutor;
        this.bulkTaskExecutor = bulkTaskExecutor;
        this.stateManager = stateManager;
        this.retryScheduler = retryScheduler;
        this.circuitBreaker = circuitBreaker;
        this.registry = registry;
        this.metricsRegistry = metricsRegistry;
        this.env = env;
    }

    /**
     * Accept a task, persist it in PENDING state, and hand it off to the thread pool.
     * Returns the task ID immediately — the caller does not wait for execution.
     *
     * @throws TaskQueueFullException if the in-flight task limit (50) has been reached
     */
    public String submitTask(TaskType type, TaskPriority priority,
                             String payload, String submittedBy, int maxRetries) {
        return submitTask(type, priority, payload, submittedBy, maxRetries,
                UUID.randomUUID().toString(), null);
    }

    public String submitTask(TaskType type, TaskPriority priority,
                             String payload, String submittedBy, int maxRetries,
                             String taskId) {
        return submitTask(type, priority, payload, submittedBy, maxRetries,
                taskId, null);
    }

    /**
     * Complete submission workflow with idempotency support and duplicate race protection.
     */
    public String submitTask(TaskType type, TaskPriority priority,
                             String payload, String submittedBy, int maxRetries,
                             String taskId, String idempotencyKey) {

        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            Optional<Task> existing = taskRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent submission matched existing task [{}]. Returning ID.", existing.get().getTaskId());
                return existing.get().getTaskId();
            }
        }

        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit reached ({} in-flight). Rejecting submission by {}", MAX_IN_FLIGHT, submittedBy);
            throw new TaskQueueFullException();
        }

        Task task = Task.builder()
                .taskId(taskId)
                .type(type)
                .priority(priority)
                .status(TaskStatus.PENDING)
                .payload(payload)
                .submittedBy(submittedBy)
                .createdAt(Instant.now())
                .retryCount(0)
                .maxRetries(maxRetries)
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            taskRepository.save(task);
        } catch (Exception e) {
            if (idempotencyKey != null && isUniqueConstraintViolation(e)) {
                rateLimiter.release();
                Task concurrentTask = taskRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new RuntimeException("Concurrent insert failed but task could not be retrieved", e));
                log.info("Concurrent insert caught. Returning ID of existing task [{}].", concurrentTask.getTaskId());
                return concurrentTask.getTaskId();
            }
            rateLimiter.release();
            throw e;
        }

        metricsRegistry.recordSubmission();
        log.info("Task [{}] submitted: type={}, priority={}, submittedBy={}, maxRetries={}, idempotencyKey={}",
                task.getTaskId(), type, priority, submittedBy, maxRetries, idempotencyKey);

        enqueueWorker(task);
        return task.getTaskId();
    }

    private boolean isUniqueConstraintViolation(Throwable t) {
        while (t != null) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (t instanceof DataIntegrityViolationException) {
                return true;
            }
            if (t instanceof java.sql.SQLException) {
                String sqlState = ((java.sql.SQLException) t).getSQLState();
                if ("23505".equals(sqlState)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Re-enqueue a FAILED task for retry after backoff.
     *
     * <p>Called by {@link RetryScheduler} after the backoff delay expires.
     * The task is currently in FAILED state. This method:
     * <ol>
     *   <li>Increments {@code retryCount} to reflect the upcoming attempt number.</li>
     *   <li>Resets transient fields for a clean attempt.</li>
     *   <li>Atomically transitions FAILED → PENDING via {@code retryTransition}.</li>
     *   <li>Acquires a Semaphore permit (blocking with timeout — safe on scheduler threads).</li>
     *   <li>Submits a new TaskWorker to the pool.</li>
     * </ol>
     *
     * @param taskId the ID of the FAILED task to retry
     */
    public void retryTask(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        int nextRetryCount = task.getRetryCount() + 1;

        // Atomically: FAILED → PENDING (the only path out of FAILED).
        try {
            stateManager.retryTransition(taskId, nextRetryCount, "Retry attempt #" + nextRetryCount);
        } catch (InvalidTaskStateException e) {
            log.warn("Retry aborted for task [{}] — unexpected state: {}", taskId, e.getMessage());
            return;
        }

        // Sync local object fields
        task.setRetryCount(nextRetryCount);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setErrorMessage(null);
        task.setResult(null);

        // Acquire Semaphore permit. Blocking is acceptable here (retry scheduler thread).
        try {
            if (!rateLimiter.tryAcquire(permitTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Retry permit acquisition timed out for task [{}] after {}s — marking permanently FAILED",
                        taskId, permitTimeoutSeconds);
                task.setErrorMessage("Retry abandoned: system at capacity for " + permitTimeoutSeconds + "s");
                stateManager.transitionStatus(taskId, TaskStatus.PENDING, TaskStatus.FAILED);
                circuitBreaker.recordFailure(task.getType());
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Retry permit acquisition interrupted for task [{}]", taskId);
            return;
        }

        log.info("Task [{}] re-enqueued for retry #{}/{} (FAILED→PENDING complete)",
                taskId, task.getRetryCount(), task.getMaxRetries());

        enqueueWorker(task);
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
     * @throws TaskNotFoundException      if no task with that ID exists
     * @throws InvalidTaskStateException  if the task is not in PENDING status
     */
    public void cancelTask(String taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> {
                    log.warn("Cancel requested for unknown task: id={}", taskId);
                    return new TaskNotFoundException(taskId);
                });

        stateManager.transitionStatus(taskId, TaskStatus.PENDING, TaskStatus.CANCELLED);
        log.info("Task [{}] cancelled (was PENDING)", taskId);
    }

    /** Expose available semaphore permits — used in tests to verify rate limiting. */
    public int availablePermits() {
        return rateLimiter.availablePermits();
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Helper to select the correct executor pool based on task type.
     */
    private Executor selectExecutor(TaskType type) {
        return switch (type) {
            case INVOICE_PROCESSING -> criticalTaskExecutor;
            case EMAIL_NOTIFICATION -> bulkTaskExecutor;
            default -> taskExecutor;
        };
    }

    /**
     * Wrap a TaskWorker in the rate-limiting outer Runnable and PriorityTaskWrapper,
     * then submit to the executor. Used by both submitTask() and retryTask().
     */
    private void enqueueWorker(Task task) {
        Executor executor = selectExecutor(task.getType());
        TaskWorker worker = new TaskWorker(
                task, stateManager, retryScheduler, circuitBreaker, registry, metricsRegistry);

        Runnable rateLimitedWorker = () -> {
            try {
                worker.run();
            } finally {
                rateLimiter.release();
                log.debug("Semaphore permit released for task [{}]. Available: {}/{}",
                        task.getTaskId(), rateLimiter.availablePermits(), MAX_IN_FLIGHT);
            }
        };

        executor.execute(new PriorityTaskWrapper(rateLimitedWorker, task.getPriority(), task.getTaskId()));
    }

    @PostConstruct
    public void recoverTasks() {
        boolean recoveryEnabled = env.getProperty("task.recovery.enabled", Boolean.class, true);
        if (!recoveryEnabled || java.util.Arrays.asList(env.getActiveProfiles()).contains("test") || isRunningTest()) {
            log.info("Task recovery is disabled for test environment.");
            return;
        }

        Thread recoveryThread = new Thread(() -> {
            log.info("Starting recovery of crashed/pending tasks...");
            
            // 1. Reset all IN_PROGRESS tasks to PENDING
            try {
                List<Task> inProgress = taskRepository.findByStatus(TaskStatus.IN_PROGRESS);
                for (Task task : inProgress) {
                    log.info("Resetting crashed task [{}] from IN_PROGRESS to PENDING", task.getTaskId());
                    try {
                        taskRepository.updateStatusForRetry(task.getTaskId(), TaskStatus.PENDING, task.getRetryCount(), "Reset to PENDING on application restart");
                        task.setStatus(TaskStatus.PENDING);
                        task.setStartedAt(null);
                        task.setCompletedAt(null);
                        task.setErrorMessage("Reset to PENDING on application restart");
                        task.setResult(null);
                    } catch (Exception e) {
                        log.error("Failed to reset crashed task [{}]: {}", task.getTaskId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to fetch IN_PROGRESS tasks for recovery: {}", e.getMessage());
            }

            // 2. Query all PENDING tasks and re-submit them
            try {
                List<Task> pending = taskRepository.findByStatus(TaskStatus.PENDING);
                log.info("Found {} pending tasks to recover.", pending.size());
                for (Task task : pending) {
                    log.info("Recovering task [{}] (type={})", task.getTaskId(), task.getType());
                    recoverAndSubmit(task);
                }
            } catch (Exception e) {
                log.error("Failed to recover pending tasks: {}", e.getMessage());
            }
            log.info("Recovery of crashed/pending tasks completed.");
        }, "task-recovery-thread");
        recoveryThread.start();
    }

    private boolean isRunningTest() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getClassName().startsWith("org.junit.") || element.getClassName().contains("Test")) {
                return true;
            }
        }
        return false;
    }

    private void recoverAndSubmit(Task task) {
        try {
            rateLimiter.acquire(); // block until a permit is available
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Recovery interrupted for task [{}]", task.getTaskId());
            return;
        }
        enqueueWorker(task);
    }
}
