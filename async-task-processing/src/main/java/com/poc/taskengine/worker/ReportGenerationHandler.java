package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.TaskExecutionException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.service.TaskStateManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Executes REPORT_GENERATION tasks.
 *
 * <p>Delegates to {@link ReportGenerationPipeline} to execute a multi-stage
 * CompletableFuture pipeline for data fetching, processing, formatting, and storing.
 */
@Component
public class ReportGenerationHandler implements TaskHandler {

    private final TaskStateManager stateManager;
    private final Executor taskExecutor;

    public ReportGenerationHandler(TaskStateManager stateManager,
                                   @Qualifier("taskExecutor") Executor taskExecutor) {
        this.stateManager = stateManager;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public TaskType getSupportedType() {
        return TaskType.REPORT_GENERATION;
    }

    @Override
    public TaskResult execute(Task task) throws TaskExecutionException {
        try {
            // Instantiate and execute the concrete multi-stage pipeline.
            ReportGenerationPipeline pipeline = new ReportGenerationPipeline(task, stateManager, taskExecutor);
            pipeline.execute();

            // The pipeline's last stage transitions the status to COMPLETED (or FAILED).
            // Check if the pipeline failed.
            if (task.getStatus() == com.poc.taskengine.enums.TaskStatus.FAILED) {
                throw new TaskExecutionException(task.getErrorMessage());
            }

            return new TaskResult(task.getResult());

        } catch (Exception e) {
            if (e instanceof TaskExecutionException) {
                throw (TaskExecutionException) e;
            }
            throw new TaskExecutionException("Report generation pipeline failed: " + e.getMessage(), e);
        }
    }
}
