package com.poc.taskengine.dto;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.TaskAuditEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO returned by every task-related endpoint.
 *
 * Architectural rule: this is what the client sees — the Task domain model is
 * never exposed directly. If we add internal fields to Task (e.g., retry metadata,
 * internal flags), they stay hidden here unless explicitly included.
 *
 * All timestamp fields use Instant, which Jackson serialises to ISO-8601 UTC
 * strings (e.g., "2026-07-01T15:04:05.123456789Z") by default. This avoids
 * timezone ambiguity when clients live in different regions.
 *
 * Fields map 1-to-1 to the Phase 3 spec:
 *   taskId, status, type, priority, createdAt, startedAt, completedAt,
 *   result, errorMessage, retryCount
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TaskResponse {

    private String taskId;
    private TaskStatus status;
    private TaskType type;
    private TaskPriority priority;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String result;
    private String errorMessage;
    private int retryCount;
    private List<TaskAuditEvent> auditTrail;
}
