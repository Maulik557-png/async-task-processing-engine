package com.poc.taskengine.worker;

import com.poc.taskengine.exception.TaskSubmissionRejectedException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Custom RejectedExecutionHandler that logs a structured ERROR and throws
 * a named exception instead of silently dropping tasks or running them on the caller.
 *
 * ── STANDARD REJECTION POLICIES — COMPARISON ─────────────────────────────────
 *
 * Java's ThreadPoolExecutor ships with four built-in policies. Understanding each
 * is critical for production systems:
 *
 * ┌─────────────────────┬──────────────────────────────────────────────────────────────────┐
 * │ Policy              │ Behaviour                                                        │
 * ├─────────────────────┼──────────────────────────────────────────────────────────────────┤
 * │ AbortPolicy         │ Throws RejectedExecutionException. The caller knows the task     │
 * │ (default)           │ was rejected and can handle it. Correct when rejection is        │
 * │                     │ exceptional and the caller MUST know. Downside: the exception    │
 * │                     │ name is generic — "why was it rejected?" requires log inspection.│
 * ├─────────────────────┼──────────────────────────────────────────────────────────────────┤
 * │ DiscardPolicy       │ Silently drops the task. The caller receives no signal.          │
 * │                     │ Acceptable ONLY when task loss is explicitly tolerable           │
 * │                     │ (e.g., periodic metrics sampling, fire-and-forget pings).        │
 * │                     │ NEVER acceptable for our async task engine — a submitted task    │
 * │                     │ is a user commitment; silent loss is a data integrity violation. │
 * ├─────────────────────┼──────────────────────────────────────────────────────────────────┤
 * │ DiscardOldestPolicy │ Drops the oldest queued (not yet started) task, then retries     │
 * │                     │ submission of the new one. Useful when newer data supersedes     │
 * │                     │ older data (e.g., real-time sensor feeds, UI refresh events).   │
 * │                     │ Wrong for us: task ordering is by priority, not by age;          │
 * │                     │ discarding a CRITICAL task because a LOW task came in later      │
 * │                     │ would be a correctness violation.                                │
 * ├─────────────────────┼──────────────────────────────────────────────────────────────────┤
 * │ CallerRunsPolicy    │ The thread that called executor.execute() runs the task itself.  │
 * │                     │ This provides natural backpressure: if all pool threads are busy │
 * │                     │ and the queue is full, the submitting thread slows down because  │
 * │                     │ it is now doing work instead of submitting more. No data loss,   │
 * │                     │ no exception needed. Used in Phase 2 as a secondary safety net. │
 * │                     │                                                                  │
 * │                     │ Limitation for our use case: when the submitting thread is a    │
 * │                     │ Tomcat HTTP thread, CallerRunsPolicy means an HTTP request       │
 * │                     │ thread silently executes a long-running task — this ties up     │
 * │                     │ the web server under overload, which is exactly what we exist   │
 * │                     │ to prevent. The 503 path via Semaphore handles overload; the     │
 * │                     │ executor rejection handler is a last-resort guard that should   │
 * │                     │ be explicit, not silent.                                         │
 * └─────────────────────┴──────────────────────────────────────────────────────────────────┘
 *
 * ── WHY THIS CUSTOM HANDLER ───────────────────────────────────────────────────
 *
 * AbortPolicy is closest to what we want (throw an exception) but it:
 *   1. Throws a generic RejectedExecutionException with no context.
 *   2. Logs nothing — the rejection is invisible unless the caller logs the exception.
 *
 * This handler:
 *   1. Logs a structured ERROR with pool diagnostics (active threads, queue depth,
 *      shutdown state) so monitoring systems detect overload without log parsing.
 *   2. Throws TaskSubmissionRejectedException — a domain-named exception that maps
 *      cleanly to HTTP 503 in GlobalExceptionHandler.
 *   3. Makes the rejection reason explicit in the exception message.
 */
@Slf4j
public class LoggingRejectedExecutionHandler implements RejectedExecutionHandler {

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        // Structured log: include pool health for fast operational diagnosis.
        log.error(
                "EXECUTOR REJECTED TASK — pool active={}/{}, queue={} pending, shutdown={}",
                executor.getActiveCount(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size(),
                executor.isShutdown()
        );

        String reason = executor.isShutdown()
                ? "The task executor is shutting down — no new tasks accepted during drain window"
                : "The task executor queue is at capacity";

        throw new TaskSubmissionRejectedException(reason);
    }
}
