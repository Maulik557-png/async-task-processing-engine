package com.poc.taskengine.model;

import com.poc.taskengine.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Audit log entry capturing a single status transition in a task's lifecycle.
 */
@Entity
@Table(name = "task_audit_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 50)
    private TaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 50)
    private TaskStatus toStatus;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "thread_name", nullable = false)
    private String threadName;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /**
     * Custom constructor to retain backwards compatibility with existing log instantiation logic.
     */
    public TaskAuditEvent(TaskStatus fromStatus, TaskStatus toStatus, Instant timestamp, String threadName, String message) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.timestamp = timestamp;
        this.threadName = threadName;
        this.message = message;
    }
}
