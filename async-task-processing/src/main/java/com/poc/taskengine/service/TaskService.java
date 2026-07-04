package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.worker.TaskWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
     * @param type      category of work to execute
     * @param priority  queue ordering weight
     * @param payload   opaque caller-supplied input (JSON string)
     * @param submittedBy identity of the submitter for audit purposes
     * @return the generated task ID
     */
    public String submitTask(TaskType type, TaskPriority priority,
                             String payload, String submittedBy) {
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
                .maxRetries(3)   // Default; configurable per-task in later phases.
                .build();

        // Persist before submitting to the pool — if the app crashes between
        // save() and execute(), we at least have a PENDING record to retry.
        taskRepository.save(task);

        log.info("Task [{}] submitted: type={}, priority={}, submittedBy={}",
                task.getTaskId(), type, priority, submittedBy);

        // Hand off to the pool. execute() is non-blocking — it enqueues the
        // Runnable and returns immediately. The calling thread is free to accept
        // the next request right away, decoupling submission from execution.
        taskExecutor.execute(new TaskWorker(task, taskRepository));

        return task.getTaskId();
    }
}
