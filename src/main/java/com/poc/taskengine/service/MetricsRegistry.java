package com.poc.taskengine.service;

import com.poc.taskengine.dto.MetricsResponse;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe registry that captures engine-wide performance and queue metrics.
 *
 * <p>Uses atomic structures for mutation logic to prevent concurrency drifts across
 * concurrent workers. Fetches queue depth and worker count directly from the live
 * ThreadPoolExecutor instances.
 */
@Service
public class MetricsRegistry {

    private final TaskRepository taskRepository;
    private final ThreadPoolExecutor defaultExecutor;
    private final ThreadPoolExecutor criticalExecutor;
    private final ThreadPoolExecutor bulkExecutor;

    private final AtomicLong totalSubmitted = new AtomicLong(0);
    private final AtomicLong totalCompleted = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalTimedOut = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeMs = new AtomicLong(0);

    public MetricsRegistry(
            TaskRepository taskRepository,
            @Qualifier("taskExecutor") Executor defaultExecutor,
            @Qualifier("criticalTaskExecutor") Executor criticalExecutor,
            @Qualifier("bulkTaskExecutor") Executor bulkExecutor) {
        this.taskRepository = taskRepository;
        this.defaultExecutor = (ThreadPoolExecutor) defaultExecutor;
        this.criticalExecutor = (ThreadPoolExecutor) criticalExecutor;
        this.bulkExecutor = (ThreadPoolExecutor) bulkExecutor;
    }

    public void recordSubmission() {
        totalSubmitted.incrementAndGet();
    }

    public void recordCompletion(long durationMs) {
        totalCompleted.incrementAndGet();
        totalExecutionTimeMs.addAndGet(durationMs);
    }

    public void recordFailure() {
        totalFailed.incrementAndGet();
    }

    public void recordTimeout() {
        totalTimedOut.incrementAndGet();
    }

    public MetricsResponse getMetricsSummary() {
        long activeWorkerCount = defaultExecutor.getActiveCount()
                + criticalExecutor.getActiveCount()
                + bulkExecutor.getActiveCount();

        long queueDepth = defaultExecutor.getQueue().size()
                + criticalExecutor.getQueue().size()
                + bulkExecutor.getQueue().size();

        long completed = totalCompleted.get();
        double averageExecutionTimeMs = completed == 0 ? 0.0 : (double) totalExecutionTimeMs.get() / completed;

        List<Task> allTasks = taskRepository.findAll();

        Map<String, Long> tasksByStatus = new ConcurrentHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            tasksByStatus.put(status.name(), 0L);
        }
        allTasks.forEach(t -> {
            if (t.getStatus() != null) {
                tasksByStatus.merge(t.getStatus().name(), 1L, (a, b) -> a + b);
            }
        });

        Map<String, Long> tasksByType = new ConcurrentHashMap<>();
        for (TaskType type : TaskType.values()) {
            tasksByType.put(type.name(), 0L);
        }
        allTasks.forEach(t -> {
            if (t.getType() != null) {
                tasksByType.merge(t.getType().name(), 1L, (a, b) -> a + b);
            }
        });

        return new MetricsResponse(
                activeWorkerCount,
                queueDepth,
                totalSubmitted.get(),
                completed,
                totalFailed.get(),
                totalTimedOut.get(),
                averageExecutionTimeMs,
                tasksByStatus,
                tasksByType
        );
    }
}
