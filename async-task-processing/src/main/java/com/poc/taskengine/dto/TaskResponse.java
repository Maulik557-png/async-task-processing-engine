package com.poc.taskengine.dto;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;

import java.time.Instant;

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

    // ── Private constructor — callers must go through TaskMapper ─────────────
    // This prevents ad-hoc construction that might forget to copy a field.
    private TaskResponse() {}

    // ── Static builder ────────────────────────────────────────────────────────
    // Mirrors the Task.builder() pattern so mappers can set fields fluently
    // without positional argument ordering mistakes.

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TaskResponse response = new TaskResponse();

        public Builder taskId(String v)           { response.taskId = v;        return this; }
        public Builder status(TaskStatus v)        { response.status = v;        return this; }
        public Builder type(TaskType v)            { response.type = v;          return this; }
        public Builder priority(TaskPriority v)    { response.priority = v;      return this; }
        public Builder createdAt(Instant v)        { response.createdAt = v;     return this; }
        public Builder startedAt(Instant v)        { response.startedAt = v;     return this; }
        public Builder completedAt(Instant v)      { response.completedAt = v;   return this; }
        public Builder result(String v)            { response.result = v;        return this; }
        public Builder errorMessage(String v)      { response.errorMessage = v;  return this; }
        public Builder retryCount(int v)           { response.retryCount = v;    return this; }

        public TaskResponse build() { return response; }
    }

    // ── Getters only — response is immutable once built ───────────────────────

    public String getTaskId()           { return taskId; }
    public TaskStatus getStatus()       { return status; }
    public TaskType getType()           { return type; }
    public TaskPriority getPriority()   { return priority; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getStartedAt()       { return startedAt; }
    public Instant getCompletedAt()     { return completedAt; }
    public String getResult()           { return result; }
    public String getErrorMessage()     { return errorMessage; }
    public int getRetryCount()          { return retryCount; }
}
