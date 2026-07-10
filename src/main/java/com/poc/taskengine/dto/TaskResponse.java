package com.poc.taskengine.dto;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.TaskAuditEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO returned by every task-related endpoint.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "Details and status of a processed or pending task")
public class TaskResponse {

    @Schema(description = "UUID identifying the task", example = "e04df2e9-ef12-421b-873b-fde5bc632a4e")
    private String taskId;

    @Schema(description = "Current lifecycle status of the task", example = "COMPLETED")
    private TaskStatus status;

    @Schema(description = "Type/Category of task logic to run", example = "DATA_EXPORT")
    private TaskType type;

    @Schema(description = "Queue execution priority level", example = "NORMAL")
    private TaskPriority priority;

    @Schema(description = "Timestamp when the task was initially submitted", example = "2026-07-09T14:25:19.123Z")
    private Instant createdAt;

    @Schema(description = "Timestamp when task execution actually started", example = "2026-07-09T14:25:20.123Z")
    private Instant startedAt;

    @Schema(description = "Timestamp when task execution ended in terminal state", example = "2026-07-09T14:25:22.123Z")
    private Instant completedAt;

    @Schema(description = "Task output payload returned by the task handler (JSON string)", example = "{\"exportedRows\": 150}")
    private String result;

    @Schema(description = "Human-readable error details if execution failed", example = "Connection timeout")
    private String errorMessage;

    @Schema(description = "Number of automatic retries performed so far", example = "1")
    private int retryCount;

    @Schema(description = "Detailed list of every state transition of this task")
    private List<TaskAuditEvent> auditTrail;
}
