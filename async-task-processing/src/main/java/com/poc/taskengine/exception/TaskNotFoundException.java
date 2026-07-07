package com.poc.taskengine.exception;

/**
 * Thrown when a task ID is requested but does not exist in the repository.
 * Maps to HTTP 404 Not Found via GlobalExceptionHandler.
 */
public class TaskNotFoundException extends RuntimeException {

    private final String taskId;

    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
