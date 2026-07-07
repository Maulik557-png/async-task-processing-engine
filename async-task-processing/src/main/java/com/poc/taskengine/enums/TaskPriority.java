package com.poc.taskengine.enums;

/**
 * Priority level of a task, used to order tasks in the priority queue.
 *
 * The {@code value} field is the comparator weight: lower number = higher urgency.
 * This is intentional — it mirrors natural ordering in a min-heap
 * (PriorityBlockingQueue with a Comparator<Task> keyed on priority.getValue())
 * so CRITICAL (1) is dequeued before LOW (4) without any special-casing.
 *
 * Phase 2 will wire this field into the queue comparator.
 */
public enum TaskPriority {

    /** Highest urgency — SLA-critical work that must not wait behind anything. */
    CRITICAL(1),

    /** Above-normal urgency — important work, not life-or-death. */
    HIGH(2),

    /** Default priority for most tasks when the caller doesn't specify. */
    NORMAL(3),

    /** Best-effort work — may be deferred if the system is under load. */
    LOW(4);

    /**
     * Comparator weight. Used as the sort key when ordering tasks in the
     * PriorityBlockingQueue. Lower value = dequeued first.
     */
    private final int value;

    TaskPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
