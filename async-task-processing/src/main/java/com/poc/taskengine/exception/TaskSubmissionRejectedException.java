package com.poc.taskengine.exception;

/**
 * Thrown when the ThreadPoolExecutor rejects a submitted Runnable via the
 * {@link com.poc.taskengine.worker.LoggingRejectedExecutionHandler}.
 *
 * Two distinct rejection scenarios trigger this:
 *
 * 1. POOL SHUTDOWN — {@link java.util.concurrent.ThreadPoolExecutor#execute} is called
 *    after {@link java.util.concurrent.ThreadPoolExecutor#shutdown()} has been invoked
 *    (e.g., during graceful shutdown). Any POST /api/v1/tasks during the drain window
 *    should get a clear 503, not a 500.
 *
 * 2. QUEUE OVERFLOW — In theory, with a Semaphore(50) limiting in-flight tasks and an
 *    unbounded PriorityBlockingQueue, the queue never fills. This exception is the
 *    secondary safety net for bugs or test paths that bypass the Semaphore.
 *
 * Maps to HTTP 503 via {@link com.poc.taskengine.exception.GlobalExceptionHandler}.
 * Distinct from {@link TaskQueueFullException} (which the Semaphore throws before
 * the task even reaches the executor).
 */
public class TaskSubmissionRejectedException extends RuntimeException {

    public TaskSubmissionRejectedException(String message) {
        super(message);
    }
}
