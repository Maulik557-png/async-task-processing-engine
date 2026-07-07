package com.poc.taskengine.exception;

/**
 * Exception thrown when a task handler fails to process a task.
 *
 * <p>Thrown by {@link com.poc.taskengine.worker.TaskHandler#execute(com.poc.taskengine.model.Task)}
 * and caught by {@link com.poc.taskengine.worker.TaskWorker} to transition the task
 * to the FAILED state.
 */
public class TaskExecutionException extends Exception {

    public TaskExecutionException(String message) {
        super(message);
    }

    public TaskExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
