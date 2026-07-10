package com.poc.taskengine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Data Transfer Object representing a snapshot of engine-wide operational metrics.
 */
@Schema(description = "Snapshot summary of all engine metrics and performance statistics")
public record MetricsResponse(
        @Schema(description = "Count of worker threads currently executing tasks", example = "2")
        long activeWorkerCount,

        @Schema(description = "Number of pending tasks waiting in the execution queues", example = "5")
        long queueDepth,

        @Schema(description = "Total number of tasks submitted to the engine since startup", example = "100")
        long totalSubmitted,

        @Schema(description = "Total number of successfully completed tasks", example = "85")
        long totalCompleted,

        @Schema(description = "Total number of tasks that permanently failed", example = "10")
        long totalFailed,

        @Schema(description = "Total number of tasks evicted due to execution timeouts", example = "5")
        long totalTimedOut,

        @Schema(description = "Average processing duration for completed tasks (ms)", example = "1420.5")
        double averageExecutionTimeMs,

        @Schema(description = "Live count of tasks grouped by their status", example = "{\"COMPLETED\": 85, \"IN_PROGRESS\": 2}")
        Map<String, Long> tasksByStatus,

        @Schema(description = "Live count of tasks grouped by their type", example = "{\"EMAIL_NOTIFICATION\": 60, \"DATA_EXPORT\": 40}")
        Map<String, Long> tasksByType
) {}
