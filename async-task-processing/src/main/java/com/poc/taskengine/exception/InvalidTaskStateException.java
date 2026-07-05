package com.poc.taskengine.exception;

import com.poc.taskengine.enums.TaskStatus;

/**
 * Thrown when a state transition is attempted but is illegal given the task's
 * current status.
 *
 * Examples:
 *   - Cancelling a task that is already COMPLETED.
 *   - Cancelling a task that is IN_PROGRESS (not supported in Phase 3; a running
 *     task cannot be safely interrupted without cooperative cancellation logic
 *     added in a later phase).
 *
 * Maps to HTTP 400 Bad Request via GlobalExceptionHandler, because the problem
 * is in the client's request (wrong state), not a server-side fault.
 */
public class InvalidTaskStateException extends RuntimeException {

    private final String taskId;
    private final TaskStatus currentStatus;

    public InvalidTaskStateException(String taskId, TaskStatus currentStatus, String action) {
        super(String.format(
                "Cannot %s task [%s]: current status is %s", action, taskId, currentStatus));
        this.taskId = taskId;
        this.currentStatus = currentStatus;
    }

    public String getTaskId() {
        return taskId;
    }

    public TaskStatus getCurrentStatus() {
        return currentStatus;
    }
}
