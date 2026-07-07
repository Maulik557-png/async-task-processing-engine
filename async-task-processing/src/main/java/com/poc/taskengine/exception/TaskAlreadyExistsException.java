package com.poc.taskengine.exception;

/**
 * Thrown when a caller attempts to submit a task with a taskId that already
 * exists in the repository.
 *
 * In the current in-memory implementation this shouldn't normally occur because
 * UUID generation guarantees uniqueness. It is included to satisfy the Phase 3
 * spec and to guard future import / replay paths where a caller might supply
 * their own ID.
 *
 * Maps to HTTP 409 Conflict via GlobalExceptionHandler.
 */
public class TaskAlreadyExistsException extends RuntimeException {

    private final String taskId;

    public TaskAlreadyExistsException(String taskId) {
        super("Task already exists: " + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
