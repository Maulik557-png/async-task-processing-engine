package com.poc.taskengine.dto;

import java.util.Map;

/**
 * Data Transfer Object representing a snapshot of engine-wide operational metrics.
 */
public record MetricsResponse(
        long activeWorkerCount,
        long queueDepth,
        long totalSubmitted,
        long totalCompleted,
        long totalFailed,
        long totalTimedOut,
        double averageExecutionTimeMs,
        Map<String, Long> tasksByStatus,
        Map<String, Long> tasksByType
) {}
