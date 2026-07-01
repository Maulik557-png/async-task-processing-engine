package com.poc.taskengine.model;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Core domain object representing a unit of asynchronous work.
 *
 * Design decisions:
 * - @Builder chosen over plain @Data constructor so callers can build instances
 *   fluently without positional argument ordering errors, especially important
 *   as fields accumulate across phases.
 * - @Data gives us getters, setters, equals/hashCode, and toString for free.
 *   equals/hashCode are keyed on all fields by default — if we later need
 *   identity-based equality (only by taskId), we'll override those two methods.
 * - All timestamps use Instant (UTC, nanosecond precision) rather than
 *   LocalDateTime so there is no ambiguity about timezone when tasks are
 *   submitted from different regions or when logs are correlated across systems.
 * - payload and result are plain String to stay flexible — callers may send
 *   JSON, base64, or plain text. Parsing is the worker's responsibility.
 *
 * IMPORTANT: This class must never be serialised directly to the HTTP response.
 *   All API shapes go through DTOs in com.poc.taskengine.dto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    /**
     * Globally unique identifier for this task.
     * Generated as a UUID string at submission time (Phase 3).
     * String rather than java.util.UUID so it serialises naturally to JSON
     * and is easy to embed in log messages without .toString() calls.
     */
    private String taskId;

    /**
     * The category of work — drives thread-pool routing and worker selection.
     */
    private TaskType type;

    /**
     * Comparator weight for queue ordering.
     * CRITICAL (1) is dequeued before LOW (4).
     */
    private TaskPriority priority;

    /**
     * Current lifecycle state.
     * Transitions are enforced atomically by the service layer (Phase 4).
     * Starting value is always PENDING.
     */
    private TaskStatus status;

    /**
     * Caller-supplied input, typically JSON-encoded.
     * Parsed and interpreted by the worker; the engine treats it as opaque bytes.
     */
    private String payload;

    /**
     * Identity of the submitter — user ID, service name, or API key.
     * Used for audit logging and rate-limiting (later phases).
     */
    private String submittedBy;

    /**
     * When the task was accepted and persisted. Set once, never mutated.
     */
    private Instant createdAt;

    /**
     * When a worker thread picked up the task (transitioned to IN_PROGRESS).
     * Null until the task is dequeued.
     */
    private Instant startedAt;

    /**
     * When the task reached a terminal state (COMPLETED, FAILED, TIMED_OUT, CANCELLED).
     * Null until terminal.
     */
    private Instant completedAt;

    /**
     * How many times this task has been retried after a failure.
     * Zero on first attempt.
     */
    private int retryCount;

    /**
     * Maximum number of retry attempts permitted for this task.
     * When retryCount >= maxRetries the task transitions to FAILED permanently.
     * Retry logic is wired in Phase 5.
     */
    private int maxRetries;

    /**
     * Human-readable description of the last error encountered.
     * Null on success. Set before transitioning to FAILED or TIMED_OUT
     * so post-mortem analysis doesn't require log scraping.
     */
    private String errorMessage;

    /**
     * Worker-produced output, typically JSON-encoded.
     * Null until the task reaches COMPLETED.
     * Stored here so the polling endpoint (Phase 3) can return it immediately
     * without going back to the worker.
     */
    private String result;
}
