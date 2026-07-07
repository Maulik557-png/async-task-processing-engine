package com.poc.taskengine.exception;

/**
 * Thrown when the rate-limiting Semaphore in TaskService has no available permits —
 * meaning 50 tasks are already in-flight (PENDING or IN_PROGRESS) and the engine
 * is at capacity.
 *
 * Maps to HTTP 503 Service Unavailable via GlobalExceptionHandler.
 *
 * WHY 503 and not 429 Too Many Requests?
 *   429 implies the CLIENT is sending too fast and should slow down its rate.
 *   503 implies the SERVER is temporarily unavailable and the client should retry later.
 *   Our limit is on in-flight capacity, not per-client rate — the server is saturated,
 *   not the client misbehaving. 503 is the correct code here.
 */
public class TaskQueueFullException extends RuntimeException {

    public TaskQueueFullException() {
        super("Task queue is at capacity (50 in-flight tasks). Please retry after existing tasks complete.");
    }
}
