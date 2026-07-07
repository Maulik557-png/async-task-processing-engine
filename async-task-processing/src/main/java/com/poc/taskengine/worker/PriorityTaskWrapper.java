package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskPriority;

/**
 * Wraps a Runnable with a TaskPriority so that PriorityBlockingQueue can order
 * pending tasks by urgency before a worker thread dequeues them.
 *
 * WHY a wrapper instead of making TaskWorker implement Comparable directly?
 *   TaskWorker already has a clear single responsibility (execute one task).
 *   Mixing ordering logic into it would violate SRP and couple the priority-queue
 *   concern to the execution concern.  The wrapper is a pure value object: it holds
 *   a priority and delegates run() to the inner Runnable.
 *
 * WHY Comparable<PriorityTaskWrapper> instead of a separate Comparator?
 *   PriorityBlockingQueue<E> uses the element's natural ordering when no Comparator
 *   is supplied to its constructor.  Implementing Comparable<PriorityTaskWrapper>
 *   gives us that natural ordering without an extra object.  We could supply a
 *   Comparator to the constructor instead; both work, but the Comparable approach
 *   keeps all ordering logic in this one class.
 *
 * HOW the ordering works:
 *   PriorityBlockingQueue is a MIN-HEAP — it dequeues the element with the SMALLEST
 *   compareTo result first.  TaskPriority assigns lower int values to higher urgency:
 *     CRITICAL=1, HIGH=2, NORMAL=3, LOW=4
 *   So compareTo returns (this.value - other.value), which is negative when this task
 *   is more urgent, causing it to float to the top of the heap.  No special-casing needed.
 *
 * Example:
 *   LOW(4) vs CRITICAL(1): 4 - 1 = +3 → CRITICAL comes first (smaller result).
 */
public class PriorityTaskWrapper implements Runnable, Comparable<PriorityTaskWrapper> {

    private final Runnable delegate;
    private final int priorityValue;
    private final String taskId; // for logging only — not used in compareTo

    /**
     * @param delegate      the actual task to run when dequeued
     * @param priority      the priority used for queue ordering
     * @param taskId        the task ID, used in log messages only
     */
    public PriorityTaskWrapper(Runnable delegate, TaskPriority priority, String taskId) {
        this.delegate = delegate;
        this.priorityValue = priority.getValue();
        this.taskId = taskId;
    }

    @Override
    public void run() {
        delegate.run();
    }

    /**
     * Natural ordering: ascending by priorityValue (lower value = higher urgency).
     * PriorityBlockingQueue's min-heap dequeues the lowest value first, so this
     * ordering makes CRITICAL(1) always come before LOW(4).
     */
    @Override
    public int compareTo(PriorityTaskWrapper other) {
        // Integer.compare is preferred over subtraction to avoid overflow with extreme values,
        // though TaskPriority's range (1-4) makes overflow impossible here.
        return Integer.compare(this.priorityValue, other.priorityValue);
    }

    public String getTaskId() {
        return taskId;
    }

    public int getPriorityValue() {
        return priorityValue;
    }
}
