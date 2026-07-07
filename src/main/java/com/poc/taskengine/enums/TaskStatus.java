package com.poc.taskengine.enums;

/**
 * Represents the lifecycle state of a Task.
 *
 * Normal execution path:
 *   PENDING → IN_PROGRESS → COMPLETED
 *                         → FAILED
 *                         → TIMED_OUT
 *
 * Cancellation path (only valid from PENDING):
 *   PENDING → CANCELLED
 *
 * Transition rules are NOT enforced here — this enum only declares the valid
 * states. Enforcement is added in Phase 4 via an explicit state-machine guard
 * on the service layer, so that the rules live in one place and can be tested
 * independently of the enum definition.
 */
public enum TaskStatus {

    /**
     * Task has been accepted and is waiting in the queue.
     * The only state from which CANCELLED is a legal transition.
     */
    PENDING,

    /**
     * A worker thread has picked up the task and is actively executing it.
     */
    IN_PROGRESS,

    /**
     * Task finished successfully. Terminal state — no further transitions allowed.
     */
    COMPLETED,

    /**
     * Task execution threw an unrecoverable error (or exhausted all retries).
     * Terminal state.
     */
    FAILED,

    /**
     * Caller requested cancellation while the task was still PENDING.
     * Terminal state — cannot cancel a task already IN_PROGRESS.
     */
    CANCELLED,

    /**
     * Task exceeded its maximum allowed execution duration.
     * Terminal state — distinct from FAILED so monitoring can alert on
     * latency regressions separately from logic errors.
     */
    TIMED_OUT
}
