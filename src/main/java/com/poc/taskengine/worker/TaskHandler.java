package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.TaskExecutionException;
import com.poc.taskengine.model.Task;

/**
 * Strategy interface for processing tasks of a specific {@link TaskType}.
 *
 * <p>Concrete implementations of this interface are automatically collected
 * and registered in the {@link TaskHandlerRegistry}.
 */
public interface TaskHandler {

    /**
     * @return the task type supported by this handler
     */
    TaskType getSupportedType();

    /**
     * Executes the business logic for the given task.
     *
     * @param task the task to process
     * @return a {@link TaskResult} indicating successful execution
     * @throws TaskExecutionException if processing fails (causing a retry or FAILED transition)
     */
    TaskResult execute(Task task) throws TaskExecutionException;
}
