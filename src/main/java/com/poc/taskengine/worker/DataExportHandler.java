package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.TaskExecutionException;
import com.poc.taskengine.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Executes DATA_EXPORT tasks.
 *
 * <p>Simulates data export processing by sleeping for ~1000ms.
 */
@Slf4j
@Component
public class DataExportHandler implements TaskHandler {

    @Override
    public TaskType getSupportedType() {
        return TaskType.DATA_EXPORT;
    }

    @Override
    public TaskResult execute(Task task) throws TaskExecutionException {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] Initiating data export task [{}]...", threadName, task.getTaskId());

        try {
            // Simulate data export work (~1000ms)
            Thread.sleep(1000);

            log.info("[{}] Data export task [{}] completed", threadName, task.getTaskId());
            return new TaskResult("data_export_complete.csv");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException("Data export processing interrupted", e);
        }
    }
}
