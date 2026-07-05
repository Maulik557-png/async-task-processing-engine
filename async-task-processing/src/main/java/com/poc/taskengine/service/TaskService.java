package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.InvalidTaskStateException;
import com.poc.taskengine.exception.TaskNotFoundException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.worker.TaskWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for task lifecycle management.
 *
 * Depends on the TaskRepository interface only — never on InMemoryTaskRepository.
 * This guarantees that swapping to the JPA implementation in Phase 8 requires
 * zero changes here.
 *
 * The executor is injected by the "taskExecutor" qualifier to avoid Spring
 * accidentally wiring the wrong bean if future phases add a second pool.
 */
@Slf4j
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ThreadPoolTaskExecutor taskExecutor;

    public TaskService(TaskRepository taskRepository,
                       @Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor) {
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Accept a task, persist it in PENDING state, and hand it off to the thread pool.
     * Returns the task ID immediately — the caller does not wait for execution.
     *
     * @param type        category of work to execute
     * @param priority    queue ordering weight
     * @param payload     opaque caller-supplied input (JSON string)
     * @param submittedBy identity of the submitter for audit purposes
     * @param maxRetries  maximum number of automatic retry attempts allowed
     * @return the generated task ID
     */
    public String submitTask(TaskType type, TaskPriority priority,
                             String payload, String submittedBy, int maxRetries) {
        // Build the Task in PENDING state — every field that the worker or polling
        // endpoint will need is set here, at submission time.
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

        // Persist before submitting to the pool — if the app crashes between
        // save() and execute(), we at least have a PENDING record to retry.
        taskRepository.save(task);

        log.info("Task [{}] submitted: type={}, priority={}, submittedBy={}, maxRetries={}",
                task.getTaskId(), type, priority, submittedBy, maxRetries);

        // Hand off to the pool. execute() is non-blocking — it enqueues the
        // Runnable and returns immediately. The calling thread is free to accept
        // the next request right away, decoupling submission from execution.
        taskExecutor.execute(new TaskWorker(task, taskRepository));

        return task.getTaskId();
    }

    /**
     * Retrieve a single task by ID.
     *
     * @param taskId the UUID string of the task
     * @return the Task domain model
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
     * @return a snapshot list of matching tasks
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
     * Only PENDING tasks can be cancelled — an IN_PROGRESS task is already
     * running on a worker thread and cannot be interrupted safely without
     * cooperative cancellation logic (added in a later phase). A COMPLETED or
     * FAILED task is in a terminal state and cannot be rewound.
     *
     * @param taskId the UUID string of the task to cancel
     * @throws TaskNotFoundException      if no task with that ID exists
     * @throws InvalidTaskStateException  if the task is not in PENDING status
     */
    public void cancelTask(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> {
                    log.warn("Cancel requested for unknown task: id={}", taskId);
                    return new TaskNotFoundException(taskId);
                });

        if (task.getStatus() != TaskStatus.PENDING) {
            log.warn("Cancel rejected for task [{}]: status is {}, not PENDING",
                    taskId, task.getStatus());
            throw new InvalidTaskStateException(taskId, task.getStatus(), "cancel");
        }

        // WHY updateStatus instead of save()?
        // updateStatus uses ConcurrentHashMap.compute() — atomic at the bin level.
        // If a worker thread picks up this task between our findById() and here,
        // both threads will contend on the same bin lock; the second write wins.
        // Phase 4 will add a proper state-machine guard to prevent that race.
        taskRepository.updateStatus(taskId, TaskStatus.CANCELLED);
        log.info("Task [{}] cancelled (was PENDING)", taskId);
    }
}
