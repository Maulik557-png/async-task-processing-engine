package com.poc.taskengine.model;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Core domain object representing a unit of asynchronous work.
 *
 * Mapped as a JPA Entity matching the Flyway tasks table schema exactly.
 */
@Entity
@Table(name = "tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    /**
     * Globally unique identifier for this task.
     * Generated as a UUID string at submission time (Phase 3).
     */
    @Id
    @Column(name = "task_id", length = 36)
    private String taskId;

    /**
     * The category of work — drives thread-pool routing and worker selection.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private TaskType type;

    /**
     * Comparator weight for queue ordering.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 50)
    private TaskPriority priority;

    /**
     * Current lifecycle state.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TaskStatus status;

    /**
     * Caller-supplied input, typically JSON-encoded.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /**
     * Identity of the submitter — user ID, service name, or API key.
     */
    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    /**
     * When the task was accepted and persisted. Set once, never mutated.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When a worker thread picked up the task (transitioned to IN_PROGRESS).
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * When the task reached a terminal state.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * How many times this task has been retried after a failure.
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * Maximum number of retry attempts permitted for this task.
     */
    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    /**
     * Human-readable description of the last error encountered.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Worker-produced output, typically JSON-encoded.
     */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /**
     * Optional idempotency key to prevent double submissions.
     */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    /**
     * History of all status transitions that this task has undergone.
     */
    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<TaskAuditEvent> auditTrail = new ArrayList<>();
}
