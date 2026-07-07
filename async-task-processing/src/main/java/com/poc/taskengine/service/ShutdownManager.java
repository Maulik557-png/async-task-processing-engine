package com.poc.taskengine.service;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.repository.TaskRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates orderly shutdown of the task execution infrastructure.
 *
 * ── WHY @PreDestroy OVER A JVM SHUTDOWN HOOK ────────────────────────────────
 *
 * Phase 2/3 used a JVM shutdown hook inside ThreadPoolConfig:
 *   Runtime.getRuntime().addShutdownHook(new Thread(() -> executor.shutdown()));
 *
 * Problems with JVM shutdown hooks:
 *   1. They run in a bare Thread with NO Spring context. You cannot call
 *      @Autowired Spring beans (e.g., taskRepository.findByStatus()) because
 *      the Spring context may already be partially destroyed by the time the
 *      hook fires.
 *   2. Multiple hooks run in parallel with undefined ordering — the hook might
 *      race with Spring's own context destruction lifecycle.
 *   3. The hook does not integrate with Spring's bean lifecycle events, so it
 *      cannot be unit-tested via the ApplicationContext.
 *
 * @PreDestroy fires INSIDE the Spring context lifecycle:
 *   1. Spring calls @PreDestroy methods before destroying any beans, so all
 *      injected beans (taskRepository, executor) are still fully operational.
 *   2. It runs on the application thread, not a separate hook thread —
 *      no concurrency with the Spring context destruction.
 *   3. It is testable: you can call applicationContext.close() in a test and
 *      verify the shutdown log output.
 *
 * ── SHUTDOWN SEQUENCE ────────────────────────────────────────────────────────
 *
 *   1. pool.shutdown()          → stop accepting new tasks; in-flight tasks continue.
 *   2. pool.awaitTermination()  → wait up to 30s for in-flight tasks to finish.
 *   3a. If all finish in time:  log "clean shutdown", return.
 *   3b. If timeout expires:     pool.shutdownNow() → sends interrupt to all workers,
 *                               then logs remaining in-progress tasks (which are
 *                               now stuck until the JVM exits).
 *   4. retryScheduler.shutdown()→ prevent new retries from firing post-shutdown.
 *
 * ── WHY 30 SECONDS ───────────────────────────────────────────────────────────
 * Our tasks sleep for 500–2500ms in Phase 2-5. 30s is ~12x the longest task.
 * In a real system, choose a value slightly above the 99.9th-percentile task duration.
 * This should be configurable (e.g., task.shutdown.timeout-seconds=30) in Phase 6+.
 *
 * ── HOW NEW SUBMISSIONS ARE REJECTED DURING SHUTDOWN ────────────────────────
 * Once pool.shutdown() is called, executor.execute() invokes the registered
 * RejectedExecutionHandler (our LoggingRejectedExecutionHandler). It logs an ERROR
 * and throws TaskSubmissionRejectedException → HTTP 503 with a clear message:
 * "The task executor is shutting down — no new tasks accepted during drain window."
 * The HTTP layer remains alive during the drain window (Spring shuts down Tomcat
 * after @PreDestroy returns), so callers can still get 503 responses.
 */
@Slf4j
@Component
public class ShutdownManager {

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final Executor taskExecutor;
    private final Executor criticalTaskExecutor;
    private final Executor bulkTaskExecutor;
    private final ScheduledExecutorService retrySchedulerExecutor;
    private final TaskRepository taskRepository;

    public ShutdownManager(
            @Qualifier("taskExecutor") Executor taskExecutor,
            @Qualifier("criticalTaskExecutor") Executor criticalTaskExecutor,
            @Qualifier("bulkTaskExecutor") Executor bulkTaskExecutor,
            @Qualifier("retrySchedulerExecutor") ScheduledExecutorService retrySchedulerExecutor,
            TaskRepository taskRepository) {
        this.taskExecutor = taskExecutor;
        this.criticalTaskExecutor = criticalTaskExecutor;
        this.bulkTaskExecutor = bulkTaskExecutor;
        this.retrySchedulerExecutor = retrySchedulerExecutor;
        this.taskRepository = taskRepository;
    }

    @PreDestroy
    public void shutdown() {
        ThreadPoolExecutor defaultPool = (ThreadPoolExecutor) taskExecutor;
        ThreadPoolExecutor criticalPool = (ThreadPoolExecutor) criticalTaskExecutor;
        ThreadPoolExecutor bulkPool = (ThreadPoolExecutor) bulkTaskExecutor;

        int inProgressCount = taskRepository.findByStatus(TaskStatus.IN_PROGRESS).size();
        int queuedCount = defaultPool.getQueue().size() + criticalPool.getQueue().size() + bulkPool.getQueue().size();

        log.info("=======================================================");
        log.info("=== GRACEFUL SHUTDOWN INITIATED                      ===");
        log.info("=======================================================");
        log.info("Tasks IN_PROGRESS: {}", inProgressCount);
        log.info("Tasks in executor queues (PENDING): {}", queuedCount);
        log.info("Waiting up to {}s for in-flight tasks to complete...", SHUTDOWN_TIMEOUT_SECONDS);

        // Stop accepting new submissions on all pools
        defaultPool.shutdown();
        criticalPool.shutdown();
        bulkPool.shutdown();

        try {
            // Await termination of all pools in parallel (sequentially checking their completion)
            long start = System.currentTimeMillis();
            boolean defaultFinished = defaultPool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            long remainingTime = Math.max(0, SHUTDOWN_TIMEOUT_SECONDS - elapsed);

            boolean criticalFinished = criticalPool.awaitTermination(remainingTime, TimeUnit.SECONDS);
            elapsed = (System.currentTimeMillis() - start) / 1000;
            remainingTime = Math.max(0, SHUTDOWN_TIMEOUT_SECONDS - elapsed);

            boolean bulkFinished = bulkPool.awaitTermination(remainingTime, TimeUnit.SECONDS);

            if (defaultFinished && criticalFinished && bulkFinished) {
                log.info("All in-flight tasks completed during shutdown window.");
            } else {
                int remaining = taskRepository.findByStatus(TaskStatus.IN_PROGRESS).size();
                log.warn("Shutdown window ({}s) expired. {} task(s) still IN_PROGRESS — forcing shutdown.",
                        SHUTDOWN_TIMEOUT_SECONDS, remaining);

                // Force termination
                defaultPool.shutdownNow();
                criticalPool.shutdownNow();
                bulkPool.shutdownNow();
            }

        } catch (InterruptedException e) {
            log.warn("Shutdown wait interrupted — forcing shutdownNow() on all pools");
            defaultPool.shutdownNow();
            criticalPool.shutdownNow();
            bulkPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Stop the retry scheduler so no retries fire after the app exits.
        retrySchedulerExecutor.shutdown();
        try {
            retrySchedulerExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            retrySchedulerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("=======================================================");
        log.info("=== GRACEFUL SHUTDOWN COMPLETE                       ===");
        log.info("=======================================================");
    }
}
