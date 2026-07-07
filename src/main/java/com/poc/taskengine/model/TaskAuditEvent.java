package com.poc.taskengine.model;

import com.poc.taskengine.enums.TaskStatus;

import java.time.Instant;

/**
 * Immutable audit log entry capturing a single status transition in a task's lifecycle.
 *
 * @param fromStatus  the status before the transition
 * @param toStatus    the status after the transition
 * @param timestamp   the exact instant when the transition was logged
 * @param threadName  the name of the thread that performed the transition
 * @param message     optional description or error message associated with the transition
 */
public record TaskAuditEvent(
        TaskStatus fromStatus,
        TaskStatus toStatus,
        Instant timestamp,
        String threadName,
        String message
) {}
