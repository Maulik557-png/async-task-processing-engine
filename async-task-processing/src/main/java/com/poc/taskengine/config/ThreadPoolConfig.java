package com.poc.taskengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pool configuration for asynchronous task execution.
 *
 * WHY ThreadPoolTaskExecutor over a raw ExecutorService:
 *   ThreadPoolTaskExecutor is Spring's wrapper around ThreadPoolExecutor.
 *   It integrates with Spring's lifecycle (start/stop/shutdown hooks),
 *   supports @Async, and exposes queue-depth metrics to Actuator without
 *   extra plumbing. We get all of that for free while keeping the underlying
 *   JDK semantics identical.
 *
 * The pool is intentionally named "taskExecutor" (a distinct Spring-recognised
 * name) so it doesn't collide with Spring's default SimpleAsyncTaskExecutor.
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // corePoolSize = 5
        // The number of threads kept alive in the pool even when idle.
        // Production implication: 5 concurrent tasks can always run without queuing.
        // Set this to match the expected steady-state concurrency — too low wastes
        // burst capacity; too high wastes OS thread resources at idle.
        executor.setCorePoolSize(5);

        // maxPoolSize = 10
        // The pool will only spawn threads beyond corePoolSize when the queue is FULL.
        // Production implication: this cap prevents runaway thread creation under
        // sudden burst load. A thread costs ~512 KB of stack space; unbounded pools
        // exhaust memory before they exhaust CPU.
        executor.setMaxPoolSize(10);

        // queueCapacity = 100
        // A LinkedBlockingQueue of this depth sits between the core threads and the
        // max threads.
        // Execution order under load:
        //   1. Task arrives. If idle core threads exist → run immediately.
        //   2. No idle core threads. Core count < corePoolSize → spawn a new core thread.
        //   3. All core threads busy. Queue not full → enqueue.
        //   4. Queue is full AND thread count < maxPoolSize → spawn an extra thread.
        //   5. Queue full AND thread count == maxPoolSize → REJECTED → RejectedExecutionHandler.
        // Production implication: 100-deep queue gives us a substantial burst buffer
        // before we need to spin up extra threads (step 4) or reject (step 5).
        executor.setQueueCapacity(100);

        // threadNamePrefix = "task-worker-"
        // Every thread in this pool is named "task-worker-N" (N = sequential int).
        // Production implication: named threads are instantly identifiable in thread
        // dumps, JVisualVM, and log lines — critical for diagnosing deadlocks or stalls.
        executor.setThreadNamePrefix("task-worker-");

        // CallerRunsPolicy: when both the queue (100) and the pool (10) are saturated,
        // the CALLING thread (i.e., the HTTP request thread) executes the task itself
        // instead of the rejection being thrown as an exception.
        //
        // WHY CallerRunsPolicy over AbortPolicy (the default):
        //   AbortPolicy throws RejectedExecutionException, which we'd have to catch and
        //   convert into a 503. CallerRunsPolicy instead provides natural backpressure:
        //   the calling thread is occupied running the task, so it cannot accept new
        //   requests until it finishes. This slows the producer at the source rather
        //   than failing it — the system degrades gracefully rather than shedding load.
        //
        // WHY NOT DiscardPolicy or DiscardOldestPolicy:
        //   Both silently drop tasks with no record — a direct violation of the engine's
        //   rule that "nothing fails silently". CallerRunsPolicy at least guarantees
        //   the task eventually runs.
        //
        // Concrete scenario: 10 threads running + 100 queued = 110 tasks in flight.
        // Task 111 arrives → CallerRunsPolicy → the HTTP thread that called submit()
        // runs task 111 inline. Until task 111 finishes, that HTTP thread cannot
        // accept another request — the caller experiences this as slow response,
        // not as an error. The pool clears, and normal operation resumes.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Graceful shutdown: wait for in-flight tasks before the JVM exits.
        // Production implication: without this, SIGTERM during deployment kills
        // tasks mid-execution, leaving orphaned IN_PROGRESS records in the store.
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // awaitTerminationSeconds = 30
        // Maximum wait during shutdown before forcing termination.
        // Production implication: gives workers time to finish their current task
        // (simulated work in Phase 2 sleeps up to ~3 s; real work in Phase 6 may
        // take longer — revisit this value when real durations are known).
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}
